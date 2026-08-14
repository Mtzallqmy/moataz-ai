package com.mtzallqmy.aiagent.schedules

import android.content.Context
import androidx.work.ListenableWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import java.io.File

interface ScheduleRuntimeOwner {
    val scheduleRuntime: ScheduleRuntime
}

class ScheduleRuntime internal constructor(
    private val applicationContext: Context?,
    private val store: ScheduleStore,
    private val backend: SchedulePlatformBackend,
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        applicationContext = context.applicationContext,
        store = AtomicFileScheduleStore(File(context.noBackupFilesDir, "schedules/state.json")),
        backend = AndroidScheduleBackend(context.applicationContext),
    )

    suspend fun submit(definition: ScheduleDefinition): ScheduleSubmission {
        validate(definition)
        val access = backend.exactAlarmAccess(definition)
        if (access == ExactAlarmAccess.DENIED) {
            val blocked = ScheduleRecord(
                definition,
                ScheduleStatus.BLOCKED,
                lastError = "Exact alarm special access is required",
            )
            store.put(blocked)
            return ScheduleSubmission(blocked, access, semantics(definition))
        }

        val record = ScheduleRecord(
            definition = definition,
            status = if (definition.enabled) ScheduleStatus.SCHEDULED else ScheduleStatus.CANCELLED,
        )
        store.put(record)
        return try {
            backend.schedule(definition)
            ScheduleSubmission(record, access, semantics(definition))
        } catch (error: SecurityException) {
            val blocked = record.copy(status = ScheduleStatus.BLOCKED, lastError = "Exact alarm access was revoked")
            store.put(blocked)
            ScheduleSubmission(blocked, ExactAlarmAccess.DENIED, semantics(definition))
        } catch (error: Throwable) {
            store.put(record.copy(status = ScheduleStatus.FAILED, lastError = error.message?.take(2_000)))
            throw error
        }
    }

    suspend fun cancel(id: String) {
        backend.cancel(id)
        store.get(id)?.let { current ->
            store.put(current.copy(status = ScheduleStatus.CANCELLED, lastError = null))
        }
    }

    suspend fun remove(id: String) {
        backend.cancel(id)
        store.remove(id)
    }

    suspend fun list(): List<ScheduleRecord> = store.list()

    suspend fun restoreEnabledSchedules() {
        store.list().filter { it.definition.enabled }.forEach { record ->
            val definition = record.definition
            if (definition.kind == ScheduleKind.ONE_TIME &&
                definition.scheduledAtEpochMillis != null &&
                definition.scheduledAtEpochMillis < nowMillis()
            ) return@forEach
            if (backend.exactAlarmAccess(definition) == ExactAlarmAccess.DENIED) {
                store.put(record.copy(status = ScheduleStatus.BLOCKED, lastError = "Exact alarm access was revoked"))
            } else {
                try {
                    backend.schedule(definition)
                    store.put(record.copy(status = ScheduleStatus.SCHEDULED, lastError = null))
                } catch (error: Throwable) {
                    store.put(
                        record.copy(
                            status = if (error is SecurityException) ScheduleStatus.BLOCKED else ScheduleStatus.FAILED,
                            lastError = error.message?.take(2_000) ?: error::class.java.simpleName,
                        ),
                    )
                }
            }
        }
    }

    internal suspend fun dispatch(scheduleId: String): ListenableWorker.Result {
        val record = store.get(scheduleId) ?: return ListenableWorker.Result.failure()
        if (!record.definition.enabled || record.status == ScheduleStatus.CANCELLED) {
            return ListenableWorker.Result.failure()
        }
        val dispatched = nowMillis()
        store.put(record.copy(status = ScheduleStatus.RUNNING, lastDispatchMillis = dispatched, lastError = null))
        return try {
            val host = applicationContext as? ScheduleExecutionHost
                ?: throw IllegalStateException("Application does not implement ScheduleExecutionHost")
            val runId = host.executeScheduledWorkflow(
                record.definition.workflowId,
                record.definition.workflowVersion,
                record.definition.input,
                scheduleId,
            )
            val status = if (record.definition.kind == ScheduleKind.PERIODIC) {
                ScheduleStatus.SCHEDULED
            } else ScheduleStatus.DISPATCHED
            store.put(
                record.copy(
                    status = status,
                    lastDispatchMillis = dispatched,
                    lastCompletedMillis = nowMillis(),
                    lastWorkflowRunId = runId,
                    lastError = null,
                ),
            )
            ListenableWorker.Result.success()
        } catch (error: Throwable) {
            store.put(
                record.copy(
                    status = if (record.definition.kind == ScheduleKind.PERIODIC) ScheduleStatus.SCHEDULED else ScheduleStatus.FAILED,
                    lastDispatchMillis = dispatched,
                    lastCompletedMillis = nowMillis(),
                    lastError = error.message?.take(2_000) ?: error::class.java.simpleName,
                ),
            )
            ListenableWorker.Result.failure()
        }
    }

    internal fun validate(definition: ScheduleDefinition) {
        if (!ID_PATTERN.matches(definition.id)) throw ScheduleValidationException("Invalid schedule id")
        if (!ID_PATTERN.matches(definition.workflowId)) throw ScheduleValidationException("Invalid workflow id")
        if (definition.workflowVersion < 1) throw ScheduleValidationException("Workflow version must be positive")
        if (definition.name.isBlank() || definition.name.length > 256) throw ScheduleValidationException("Invalid schedule name")
        if (definition.input.toString().encodeToByteArray().size > MAX_INPUT_BYTES) {
            throw ScheduleValidationException("Schedule input exceeds $MAX_INPUT_BYTES bytes")
        }
        when (definition.kind) {
            ScheduleKind.ONE_TIME -> {
                val at = definition.scheduledAtEpochMillis
                    ?: throw ScheduleValidationException("One-time schedule requires scheduledAtEpochMillis")
                if (at <= nowMillis()) throw ScheduleValidationException("One-time schedule must be in the future")
                if (definition.intervalMillis != null || definition.flexMillis != null) {
                    throw ScheduleValidationException("One-time schedule cannot specify interval/flex")
                }
            }
            ScheduleKind.PERIODIC -> {
                if (definition.timing == ScheduleTiming.EXACT) {
                    throw ScheduleValidationException("Android does not guarantee exact periodic scheduling")
                }
                val interval = definition.intervalMillis
                    ?: throw ScheduleValidationException("Periodic schedule requires intervalMillis")
                if (interval < MIN_PERIODIC_MILLIS) {
                    throw ScheduleValidationException("Periodic interval must be at least 15 minutes")
                }
                val flex = definition.flexMillis
                if (flex != null && (flex < MIN_FLEX_MILLIS || flex > interval)) {
                    throw ScheduleValidationException("Periodic flex must be between 5 minutes and the interval")
                }
                if (definition.scheduledAtEpochMillis != null) {
                    throw ScheduleValidationException("Periodic schedule does not accept an exact start time")
                }
            }
        }
    }

    private fun semantics(definition: ScheduleDefinition): String = when {
        definition.kind == ScheduleKind.PERIODIC ->
            "Periodic work is inexact. Android may defer it for Doze, quotas, battery, and constraints; minimum interval is 15 minutes."
        definition.timing == ScheduleTiming.EXACT && definition.constraints != ScheduleConstraints() ->
            "Alarm dispatch is exact when special access is granted; workflow execution may wait for WorkManager constraints and is not exact."
        definition.timing == ScheduleTiming.EXACT ->
            "Alarm dispatch is exact when special access is granted; Android still does not guarantee completion time."
        else ->
            "One-time WorkManager execution is inexact and may be deferred by Android power management and quotas."
    }

    companion object {
        fun from(context: Context): ScheduleRuntime =
            (context.applicationContext as? ScheduleRuntimeOwner)?.scheduleRuntime
                ?: error("Application does not implement ScheduleRuntimeOwner")

        private val ID_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,127}$")
        private const val MIN_PERIODIC_MILLIS = 15 * 60_000L
        private const val MIN_FLEX_MILLIS = 5 * 60_000L
        private const val MAX_INPUT_BYTES = 1024 * 1024
    }
}
