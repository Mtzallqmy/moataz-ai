package com.mtzallqmy.aiagent.local_llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelPreflightTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `blocks unsupported ABI and expected OOM`() {
        val root = temporaryFolder.newFolder("models")
        val model = root.resolve("model.gguf").also(::createGguf)
        val preflight = LocalModelPreflight(
            LocalModelDiscovery(listOf(root)),
            FakeResources(availableRam = 32L * 1024 * 1024, abis = listOf("armeabi-v7a")),
        )

        val assessment = preflight.assess(
            LocalModelReference(model.canonicalPath),
            LocalModelLoadOptions(contextSize = 4096),
            "assessment",
        )

        assertFalse(assessment.canLoad)
        assertTrue(assessment.blockers.any { it.contains("64-bit ABI") })
        assertTrue(assessment.blockers.any { it.contains("available RAM") })
    }

    @Test
    fun `checksum mismatch is a hard blocker`() {
        val root = temporaryFolder.newFolder("models")
        val model = root.resolve("model.gguf").also(::createGguf)
        val preflight = LocalModelPreflight(LocalModelDiscovery(listOf(root)), FakeResources())

        val assessment = preflight.assess(
            LocalModelReference(model.canonicalPath, "0".repeat(64)),
            LocalModelLoadOptions(),
            "assessment",
        )

        assertFalse(assessment.canLoad)
        assertTrue(assessment.blockers.any { it.contains("checksum") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `model path cannot escape configured roots`() {
        val root = temporaryFolder.newFolder("models")
        val outside = temporaryFolder.newFile("outside.gguf").also(::createGguf)
        LocalModelDiscovery(listOf(root)).requireConfinedFile(outside.path)
    }
}

internal data class FakeResources(
    val availableRam: Long = 16L * 1024 * 1024 * 1024,
    val totalRam: Long = 16L * 1024 * 1024 * 1024,
    val freeDisk: Long = 16L * 1024 * 1024 * 1024,
    val abis: List<String> = listOf("arm64-v8a"),
) : LocalDeviceResources {
    override fun availableRamBytes() = availableRam
    override fun totalRamBytes() = totalRam
    override fun freeDiskBytes(path: java.io.File) = freeDisk
    override fun supportedAbis() = abis
}
