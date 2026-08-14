package com.mtzallqmy.aiagent.sandbox.termux

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TermuxCommand(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val stdin: String? = null,
    val workingDirectory: String = TERMUX_HOME,
    val timeoutMs: Long = 60_000L,
)

data class TermuxCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutOriginalLength: Int,
    val stderrOriginalLength: Int,
    val internalErrorCode: Int,
    val internalErrorMessage: String,
) {
    val success: Boolean get() = exitCode == 0 && internalErrorCode == -1
}

interface TermuxCommandTransport {
    fun health(): TermuxTransportHealth
    suspend fun execute(command: TermuxCommand): TermuxCommandResult
}

data class TermuxTransportHealth(
    val packageInstalled: Boolean,
    val permissionGranted: Boolean,
    val serviceResolvable: Boolean,
)

class AndroidTermuxCommandTransport(private val context: Context) : TermuxCommandTransport {
    override fun health(): TermuxTransportHealth {
        val installed = runCatching {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        }.isSuccess
        val permission = ContextCompat.checkSelfPermission(context, TERMUX_RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        val resolvable = context.packageManager.resolveService(commandIntent(), PackageManager.MATCH_DEFAULT_ONLY) != null
        return TermuxTransportHealth(installed, permission, resolvable)
    }

    override suspend fun execute(command: TermuxCommand): TermuxCommandResult {
        require(command.executable.startsWith("$PREFIX/") || command.executable.startsWith("/data/data/com.termux/files/usr/")) {
            "Termux commands must use an executable inside the Termux prefix"
        }
        require(command.arguments.size <= MAX_ARGUMENTS) { "Too many Termux arguments" }
        require(command.arguments.sumOf { it.toByteArray().size } <= MAX_ARGUMENT_BYTES) {
            "Termux arguments exceed the transport limit"
        }
        require(command.stdin.orEmpty().toByteArray().size <= MAX_STDIN_BYTES) {
            "Termux stdin exceeds the transport limit"
        }
        val currentHealth = health()
        check(currentHealth.packageInstalled) { "Termux is not installed" }
        check(currentHealth.permissionGranted) { "RUN_COMMAND permission is not granted" }
        check(currentHealth.serviceResolvable) { "Termux RunCommandService is not available" }

        val executionId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<TermuxCommandResult>()
        TermuxResultRegistry.register(executionId, deferred)
        val resultIntent = Intent(context, TermuxCommandResultReceiver::class.java)
            .setPackage(context.packageName)
            .putExtra(EXTRA_EXECUTION_ID, executionId)
        val pendingFlags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            executionId.hashCode(),
            resultIntent,
            pendingFlags,
        )
        val intent = commandIntent().apply {
            putExtra(EXTRA_COMMAND_PATH, command.executable)
            putExtra(EXTRA_ARGUMENTS, command.arguments.toTypedArray())
            putExtra(EXTRA_WORKDIR, command.workingDirectory)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            command.stdin?.let { putExtra(EXTRA_STDIN, it) }
            putExtra(EXTRA_COMMAND_LABEL, "Aegis sandbox operation")
            putExtra(EXTRA_COMMAND_DESCRIPTION, "Runs an explicitly approved sandbox backend operation.")
        }
        try {
            checkNotNull(context.startService(intent)) { "Termux rejected the command service intent" }
            return withTimeout(command.timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)) { deferred.await() }
        } finally {
            TermuxResultRegistry.remove(executionId)
        }
    }

    private fun commandIntent(): Intent = Intent(ACTION_RUN_COMMAND).setClassName(
        TERMUX_PACKAGE,
        TERMUX_RUN_COMMAND_SERVICE,
    )
}

class TermuxCommandResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val executionId = intent?.getStringExtra(EXTRA_EXECUTION_ID) ?: return
        val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
        if (bundle == null) {
            TermuxResultRegistry.fail(executionId, IllegalStateException("Termux result bundle is missing"))
            return
        }
        TermuxResultRegistry.complete(executionId, bundle.toTermuxResult())
    }
}

private object TermuxResultRegistry {
    private val results = ConcurrentHashMap<String, CompletableDeferred<TermuxCommandResult>>()

    fun register(id: String, result: CompletableDeferred<TermuxCommandResult>) {
        check(results.putIfAbsent(id, result) == null) { "Duplicate Termux execution id" }
    }

    fun complete(id: String, result: TermuxCommandResult) {
        results.remove(id)?.complete(result)
    }

    fun fail(id: String, error: Throwable) {
        results.remove(id)?.completeExceptionally(error)
    }

    fun remove(id: String) {
        results.remove(id)?.cancel()
    }
}

private fun Bundle.toTermuxResult(): TermuxCommandResult = TermuxCommandResult(
    exitCode = getInt(RESULT_EXIT_CODE, -1),
    stdout = getString(RESULT_STDOUT, ""),
    stderr = getString(RESULT_STDERR, ""),
    stdoutOriginalLength = numberAsInt(RESULT_STDOUT_ORIGINAL_LENGTH, getString(RESULT_STDOUT, "").length),
    stderrOriginalLength = numberAsInt(RESULT_STDERR_ORIGINAL_LENGTH, getString(RESULT_STDERR, "").length),
    internalErrorCode = getInt(RESULT_ERROR_CODE, -1),
    internalErrorMessage = getString(RESULT_ERROR_MESSAGE, ""),
)

private fun Bundle.numberAsInt(key: String, fallback: Int): Int = when (val value = get(key)) {
    is Int -> value
    is Long -> value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    is String -> value.toIntOrNull() ?: fallback
    else -> fallback
}

const val TERMUX_HOME = "/data/data/com.termux/files/home"
const val PREFIX = "/data/data/com.termux/files/usr"
private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
private const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
private const val EXTRA_RESULT_BUNDLE = "com.termux.RUN_COMMAND_RESULT"
private const val RESULT_STDOUT = "stdout"
private const val RESULT_STDERR = "stderr"
private const val RESULT_EXIT_CODE = "exitCode"
private const val RESULT_ERROR_CODE = "err"
private const val RESULT_ERROR_MESSAGE = "errmsg"
private const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
private const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
private const val EXTRA_EXECUTION_ID = "aegis_execution_id"
private const val MAX_ARGUMENTS = 128
private const val MAX_ARGUMENT_BYTES = 96 * 1024
private const val MAX_STDIN_BYTES = 256 * 1024
private const val MAX_TIMEOUT_MS = 15 * 60 * 1_000L
