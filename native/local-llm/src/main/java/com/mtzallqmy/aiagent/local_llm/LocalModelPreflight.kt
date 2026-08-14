package com.mtzallqmy.aiagent.local_llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.ceil

interface LocalDeviceResources {
    fun availableRamBytes(): Long
    fun totalRamBytes(): Long
    fun freeDiskBytes(path: File): Long
    fun supportedAbis(): List<String>
}

class AndroidLocalDeviceResources(context: Context) : LocalDeviceResources {
    private val appContext = context.applicationContext

    override fun availableRamBytes(): Long = memoryInfo().availMem

    override fun totalRamBytes(): Long = memoryInfo().totalMem

    override fun freeDiskBytes(path: File): Long = StatFs(path.parentFile?.path ?: path.path).availableBytes

    override fun supportedAbis(): List<String> = Build.SUPPORTED_ABIS.toList()

    private fun memoryInfo() = ActivityManager.MemoryInfo().also { info ->
        appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
    }
}

internal class LocalModelPreflight(
    private val discovery: LocalModelDiscovery,
    private val resources: LocalDeviceResources,
    private val metadataReader: GgufMetadataReader = GgufMetadataReader(),
) {
    fun assess(
        reference: LocalModelReference,
        options: LocalModelLoadOptions,
        assessmentId: String,
    ): LocalModelLoadAssessment {
        val file = discovery.requireConfinedFile(reference.canonicalPath)
        val metadata = metadataReader.read(file)
        val sha256 = sha256(file)
        val availableRam = resources.availableRamBytes()
        val freeDisk = resources.freeDiskBytes(file)
        val estimatedMemory = estimatePeakMemory(file.length(), options.contextSize)
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val supported = resources.supportedAbis().any { it == "arm64-v8a" || it == "x86_64" }
        if (!supported) blockers += "Local inference requires a supported 64-bit ABI (arm64-v8a or x86_64)"
        if (file.length() < MIN_MODEL_BYTES) blockers += "GGUF file is too small to contain model tensors"
        if (availableRam <= 0L) blockers += "Available RAM could not be determined"
        if (estimatedMemory > availableRam && availableRam > 0L) {
            blockers += "Estimated peak memory exceeds currently available RAM"
        } else if (availableRam > 0L && estimatedMemory > (availableRam * WARNING_RAM_RATIO).toLong()) {
            warnings += "Estimated peak memory uses more than 70% of currently available RAM"
        }
        if (freeDisk < MIN_FREE_DISK_BYTES) {
            warnings += "Less than 64 MiB free storage remains next to the model"
        }
        metadata.trainedContextSize?.let { trained ->
            if (options.contextSize > trained) {
                warnings += "Requested context exceeds the model's trained context size ($trained)"
            }
        }

        reference.expectedSha256?.let { expected ->
            if (!SHA256_REGEX.matches(expected)) {
                blockers += "Expected checksum is not a valid SHA-256 value"
            } else if (!sha256.equals(expected, ignoreCase = true)) {
                blockers += "Model SHA-256 does not match the expected checksum"
            }
        }

        return LocalModelLoadAssessment(
            assessmentId = assessmentId,
            metadata = metadata,
            fileSizeBytes = file.length(),
            sha256 = sha256,
            estimatedPeakMemoryBytes = estimatedMemory,
            availableRamBytes = availableRam,
            freeDiskBytes = freeDisk,
            blockers = blockers,
            warnings = warnings,
        )
    }

    private fun estimatePeakMemory(fileSize: Long, contextSize: Int): Long {
        val mappedModelWorkingSet = ceil(fileSize * MODEL_WORKING_SET_MULTIPLIER).toLong()
        val conservativeKvCache = contextSize.toLong() * KV_BYTES_PER_TOKEN
        return mappedModelWorkingSet + conservativeKvCache + RUNTIME_HEADROOM_BYTES
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MODEL_WORKING_SET_MULTIPLIER = 1.10
        const val WARNING_RAM_RATIO = 0.70
        const val KV_BYTES_PER_TOKEN = 256L * 1024
        const val RUNTIME_HEADROOM_BYTES = 128L * 1024 * 1024
        const val MIN_FREE_DISK_BYTES = 64L * 1024 * 1024
        const val MIN_MODEL_BYTES = 1L * 1024 * 1024
        val SHA256_REGEX = Regex("^[a-fA-F0-9]{64}$")
    }
}
