package com.mtzallqmy.aiagent.schedules

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.serialization.json.JsonObject

/** Implemented by the application composition root; no fake/default execution exists. */
interface ScheduleExecutionHost {
    suspend fun executeScheduledWorkflow(
        workflowId: String,
        workflowVersion: Int,
        input: JsonObject,
        scheduleId: String,
    ): String
}

class ScheduledWorkflowWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID) ?: return Result.failure()
        val runtime = ScheduleRuntime.from(applicationContext)
        return runtime.dispatch(scheduleId)
    }

    companion object {
        const val KEY_SCHEDULE_ID = "schedule_id"
    }
}
