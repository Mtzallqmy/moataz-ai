package com.mtzallqmy.aiagent.feature.logs

import com.mtzallqmy.aiagent.common.SecretSanitizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Observability: run timeline entries. Secrets are sanitized before anything
 * is stored or exposed in the logs UI. Chain-of-thought is never stored.
 */
object RunLogs {
    private val _events = MutableSharedFlow<LogEntry>(replay = 100)
    val events: SharedFlow<LogEntry> = _events

    private val history = mutableListOf<LogEntry>()

    fun record(runId: String, level: LogLevel, message: String) {
        val sanitized = SecretSanitizer.sanitize(message)
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            runId = runId,
            level = level,
            message = sanitized,
        )
        history.add(entry)
        if (history.size > 2_000) history.removeAt(0)
        _events.tryEmit(entry)
    }

    fun snapshot(): List<LogEntry> = history.toList()

    fun clear() { history.clear() }
}

data class LogEntry(
    val timestamp: Long,
    val runId: String,
    val level: LogLevel,
    val message: String,
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

enum class LogLevel { INFO, TOOL_CALL, TOOL_RESULT, APPROVAL, WARNING, ERROR }
