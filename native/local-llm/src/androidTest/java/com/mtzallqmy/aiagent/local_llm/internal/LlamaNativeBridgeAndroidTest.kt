package com.mtzallqmy.aiagent.local_llm.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamaNativeBridgeAndroidTest {
    @Test
    fun missingModelIsRejectedByNativeLoader() {
        val bridge = LlamaCppJniBridge()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missing = context.cacheDir.resolve("missing-${System.nanoTime()}.gguf")
        val result = runCatching { bridge.loadModel(missing.absolutePath, true) }
        assertTrue("Native loader must reject a missing model", result.isFailure)
    }

    @Test
    fun corruptGgufIsRejectedByNativeLoader() {
        val bridge = LlamaCppJniBridge()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corrupt = context.cacheDir.resolve("corrupt-${System.nanoTime()}.gguf")
        corrupt.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46, 0, 0, 0, 0))
        try {
            val result = runCatching { bridge.loadModel(corrupt.absolutePath, true) }
            assertTrue("Native loader must reject a corrupt GGUF", result.isFailure)
        } finally {
            corrupt.delete()
        }
    }
}
