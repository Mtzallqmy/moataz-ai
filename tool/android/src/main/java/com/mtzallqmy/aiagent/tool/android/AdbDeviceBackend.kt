package com.mtzallqmy.aiagent.tool.android

import com.mtzallqmy.aiagent.agent.backends.DeviceBackend
import com.mtzallqmy.aiagent.model.CapabilityId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * ADB device-control backend — concepts studied from DroidMind
 * (Apache-2.0, clean-room reimplementation; no code copied).
 *
 * Provides device management, shell, app control, file push/pull, screenshots
 * and diagnostics via the `adb` command when available on the PATH (or bundled
 * binary). ADB is OPTIONAL: the app runs fully without it — this backend
 * simply reports unavailable. Commands are validated against an allow-list
 * and wrapped in timeouts; results are structured JSON.
 */
class AdbDeviceBackend(
    private val adbPath: String = "adb",
    private val defaultTimeoutMs: Long = 30_000L,
    private val allowedPrefixes: Set<String> = DEFAULT_ALLOWED,
) : DeviceBackend {
    override val id: String = "adb"
    override val name: String = "ADB Device Control"
    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId("device.shell"),
        CapabilityId("device.apps"),
        CapabilityId("device.files"),
        CapabilityId("device.diagnostics"),
        CapabilityId("device.ui"),
    )

    override suspend fun isAvailable(): Boolean = runAdb("devices", timeoutMs = 10_000L)
        .let { it.success && "device" in it.stdout }

    override suspend fun diagnostics(): String = withContext(Dispatchers.IO) {
        val dev = runAdb("devices")
        if (!dev.success) return@withContext "adb: not available (${dev.stderr.take(80)})"
        val lines = dev.stdout.lines().filter { "device" in it && it != "List of devices attached" }
        if (lines.isEmpty()) return@withContext "adb: available but no devices connected"
        val first = lines.first().substringBefore('\t').trim()
        val props = runAdb("-s $first shell getprop ro.product.model")
        "adb: connected to $first (${props.stdout.trim()})"
    }

    /** Device management: list, reboot, properties. */
    suspend fun listDevices(): List<String> = runAdb("devices").stdout.lines()
        .filter { "device" in it && it != "List of devices attached" }
        .map { it.substringBefore('\t').trim() }

    suspend fun deviceProp(serial: String?, prop: String): String =
        runAdb("${serialOr(serial)} shell getprop $prop").stdout.trim()

    suspend fun reboot(serial: String? = null): Boolean =
        runAdb("${serialOr(serial)} reboot").success

    /** App control: install, uninstall, start, stop, list. */
    suspend fun installApk(serial: String?, apkPath: String): Boolean =
        runAdb("${serialOr(serial)} install -r $apkPath").success

    suspend fun uninstall(serial: String?, pkg: String): Boolean =
        runAdb("${serialOr(serial)} uninstall $pkg").success

    suspend fun startActivity(serial: String?, pkg: String): Boolean =
        runAdb("${serialOr(serial)} shell monkey -p $pkg -c android.intent.category.LAUNCHER 1").success

    suspend fun forceStop(serial: String?, pkg: String): Boolean =
        runAdb("${serialOr(serial)} shell am force-stop $pkg").success

    suspend fun listPackages(serial: String?): List<String> =
        runAdb("${serialOr(serial)} shell pm list packages").stdout.lines()
            .map { it.removePrefix("package:") }.filter { it.isNotBlank() }

    /** Shell execution with allow-list validation. */
    suspend fun shell(serial: String?, command: String, timeoutMs: Long = defaultTimeoutMs): JsonObject {
        val base = command.trim().substringBefore(' ')
        if (allowedPrefixes.isNotEmpty() && base !in allowedPrefixes) {
            return buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive("ADB command prefix not allowed: $base"))
            }
        }
        val result = runAdb("${serialOr(serial)} shell $command", timeoutMs)
        return buildJsonObject {
            put("success", JsonPrimitive(result.success))
            put("stdout", JsonPrimitive(result.stdout))
            put("stderr", JsonPrimitive(result.stderr))
            put("exit_code", JsonPrimitive(result.exitCode))
        }
    }

    /** File push/pull with path validation. */
    suspend fun push(serial: String?, local: String, remote: String): Boolean =
        runAdb("${serialOr(serial)} push $local $remote").success

    suspend fun pull(serial: String?, remote: String, local: String): Boolean =
        runAdb("${serialOr(serial)} pull $remote $local").success

    /** Diagnostics: logcat, bugreport path, memory. */
    suspend fun recentLogcat(serial: String?, lines: Int = 100): String =
        runAdb("${serialOr(serial)} logcat -d -t $lines").stdout

    suspend fun screenshot(serial: String?, outputPath: String): Boolean =
        runAdb("${serialOr(serial)} exec-out screencap -p > $outputPath").success

    /** UI automation basics (taps/keys) — accessibility remains the primary path. */
    suspend fun tap(serial: String?, x: Int, y: Int): Boolean =
        runAdb("${serialOr(serial)} shell input tap $x $y").success

    suspend fun key(serial: String?, keyCode: Int): Boolean =
        runAdb("${serialOr(serial)} shell input keyevent $keyCode").success

    suspend fun swipe(serial: String?, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Boolean =
        runAdb("${serialOr(serial)} shell input swipe $x1 $y1 $x2 $y2 $durationMs").success

    private fun serialOr(serial: String?): String = if (serial.isNullOrBlank()) "" else "-s $serial"

    private suspend fun runAdb(command: String, timeoutMs: Long = defaultTimeoutMs): AdbResult = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(timeoutMs) {
                val args = "$adbPath $command".split(' ').filter { it.isNotBlank() }
                val process = ProcessBuilder(args).redirectErrorStream(false).start()
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                process.waitFor()
                AdbResult(exitCode = process.exitValue(), stdout = stdout, stderr = stderr)
            }
        }.getOrElse { AdbResult(exitCode = -1, stdout = "", stderr = it.message ?: "adb unavailable") }
    }

    private data class AdbResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val success: Boolean get() = exitCode == 0
    }

    companion object {
        val DEFAULT_ALLOWED = setOf(
            "getprop", "pm", "am", "monkey", "input", "logcat", "screencap",
            "dumpsys", "id", "ls", "cat", "df", "free", "uptime", "echo", "date",
        )
    }
}
