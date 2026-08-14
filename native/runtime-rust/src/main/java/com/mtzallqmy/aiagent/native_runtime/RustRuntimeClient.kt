package com.mtzallqmy.aiagent.native_runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Binder client. The app process never loads the Rust library directly. */
class RustRuntimeClient(private val context: Context) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = false }
    @Volatile private var service: IRustRuntimeService? = null
    @Volatile private var connection: ServiceConnection? = null

    suspend fun connect() {
        if (service != null) return
        suspendCancellableCoroutine { continuation ->
            val candidate = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = IRustRuntimeService.Stub.asInterface(binder)
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }

                override fun onNullBinding(name: ComponentName?) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException("Rust runtime service returned a null binding"),
                    )
                }
            }
            connection = candidate
            val bound = context.bindService(
                Intent(context, RustRuntimeService::class.java),
                candidate,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound && continuation.isActive) {
                connection = null
                continuation.resumeWithException(IllegalStateException("Unable to bind Rust runtime service"))
            }
            continuation.invokeOnCancellation { close() }
        }
    }

    fun start(request: RustExecutionRequest, workingDirectory: File? = null): RustStartResponse {
        val remote = requireService()
        val directory = workingDirectory?.let {
            require(it.isDirectory) { "Working directory does not exist" }
            ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        return directory.use {
            json.decodeFromString(
                RustStartResponse.serializer(),
                remote.start(json.encodeToString(RustExecutionRequest.serializer(), request), it),
            )
        }
    }

    fun awaitResult(executionId: String, waitTimeoutMs: Long): RustExecutionResult =
        json.decodeFromString(
            RustExecutionResult.serializer(),
            requireService().awaitResult(executionId, waitTimeoutMs),
        )

    fun cancel(executionId: String): Boolean = requireService().cancel(executionId)

    fun release(executionId: String): Boolean = requireService().release(executionId)

    fun capabilities(): RustRuntimeCapabilities = json.decodeFromString(
        RustRuntimeCapabilities.serializer(),
        requireService().capabilities(),
    )

    override fun close() {
        val active = connection ?: return
        runCatching { context.unbindService(active) }
        connection = null
        service = null
    }

    private fun requireService(): IRustRuntimeService =
        checkNotNull(service) { "Rust runtime is not connected" }
}
