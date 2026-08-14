package com.mtzallqmy.aiagent.schedules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class ScheduleKind { ONE_TIME, PERIODIC }

@Serializable
enum class ScheduleTiming { INEXACT, EXACT }

@Serializable
enum class ScheduleNetworkConstraint { NOT_REQUIRED, CONNECTED, UNMETERED }

@Serializable
data class ScheduleConstraints(
    val network: ScheduleNetworkConstraint = ScheduleNetworkConstraint.NOT_REQUIRED,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = false,
)

@Serializable
data class ScheduleDefinition(
    val id: String,
    val name: String,
    val workflowId: String,
    val workflowVersion: Int,
    val input: JsonObject = JsonObject(emptyMap()),
    val kind: ScheduleKind,
    val timing: ScheduleTiming = ScheduleTiming.INEXACT,
    val scheduledAtEpochMillis: Long? = null,
    val intervalMillis: Long? = null,
    val flexMillis: Long? = null,
    val constraints: ScheduleConstraints = ScheduleConstraints(),
    val enabled: Boolean = true,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Serializable
enum class ScheduleStatus { SCHEDULED, RUNNING, DISPATCHED, FAILED, CANCELLED, BLOCKED }

@Serializable
data class ScheduleRecord(
    val definition: ScheduleDefinition,
    val status: ScheduleStatus,
    val lastDispatchMillis: Long? = null,
    val lastCompletedMillis: Long? = null,
    val lastWorkflowRunId: String? = null,
    val lastError: String? = null,
)

enum class ExactAlarmAccess { NOT_REQUIRED, GRANTED, DENIED }

data class ScheduleSubmission(
    val record: ScheduleRecord,
    val exactAlarmAccess: ExactAlarmAccess,
    val semantics: String,
)

class ScheduleValidationException(message: String) : IllegalArgumentException(message)
