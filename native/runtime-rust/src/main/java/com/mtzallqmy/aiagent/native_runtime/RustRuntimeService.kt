package com.mtzallqmy.aiagent.native_runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor

/** Runs JNI only inside the manifest-declared Android isolated process. */
class RustRuntimeService : Service() {
    private val binder = object : IRustRuntimeService.Stub() {
        override fun start(requestJson: String, workingDirectory: ParcelFileDescriptor?): String {
            require(requestJson.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
                "Rust runtime request exceeds $MAX_REQUEST_BYTES bytes"
            }
            val fd = workingDirectory?.detachFd() ?: -1
            return RustRuntimeNative.nativeStart(requestJson, fd)
        }

        override fun awaitResult(executionId: String, waitTimeoutMs: Long): String =
            RustRuntimeNative.nativeAwaitResult(executionId, waitTimeoutMs.coerceIn(1L, MAX_WAIT_MS))

        override fun cancel(executionId: String): Boolean = RustRuntimeNative.nativeCancel(executionId)

        override fun release(executionId: String): Boolean = RustRuntimeNative.nativeRelease(executionId)

        override fun capabilities(): String = RustRuntimeNative.nativeCapabilities()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val MAX_REQUEST_BYTES = 64 * 1024
        const val MAX_WAIT_MS = 5 * 60 * 1_000L
    }
}
