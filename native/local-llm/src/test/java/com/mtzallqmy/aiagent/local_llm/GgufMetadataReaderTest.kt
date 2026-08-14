package com.mtzallqmy.aiagent.local_llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GgufMetadataReaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `reads bounded model metadata without loading tensors`() {
        val file = temporaryFolder.newFile("model.gguf")
        createGguf(file, contextSize = 8192)

        val metadata = GgufMetadataReader().read(file)

        assertEquals(3, metadata.version)
        assertEquals(1L, metadata.tensorCount)
        assertEquals(4L, metadata.metadataCount)
        assertEquals("llama", metadata.architecture)
        assertEquals("Test Model", metadata.name)
        assertEquals(7L, metadata.quantizationType)
        assertEquals(8192L, metadata.trainedContextSize)
    }

    @Test
    fun `rejects a non GGUF file`() {
        val file = temporaryFolder.newFile("bad.gguf").apply {
            writeBytes(ByteArray(64) { 1 })
        }

        assertThrows(IllegalArgumentException::class.java) { GgufMetadataReader().read(file) }
    }
}
