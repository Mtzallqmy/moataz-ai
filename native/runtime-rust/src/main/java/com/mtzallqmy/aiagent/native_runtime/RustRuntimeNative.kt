package com.mtzallqmy.aiagent.native_runtime

internal object RustRuntimeNative {
    init {
        System.loadLibrary("aegis_runtime")
    }

    @JvmStatic external fun nativeStart(requestJson: String, workingDirectoryFd: Int): String
    @JvmStatic external fun nativeAwaitResult(executionId: String, waitTimeoutMs: Long): String
    @JvmStatic external fun nativeCancel(executionId: String): Boolean
    @JvmStatic external fun nativeRelease(executionId: String): Boolean
    @JvmStatic external fun nativeCapabilities(): String
}
