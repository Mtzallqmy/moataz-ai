package com.mtzallqmy.aiagent.schedules

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal interface SchedulePlatformBackend {
    fun exactAlarmAccess(definition: ScheduleDefinition): ExactAlarmAccess
    fun schedule(definition: ScheduleDefinition)
    fun cancel(scheduleId: String)
}

internal class AndroidScheduleBackend(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java),
) : SchedulePlatformBackend {
    private val appContext = context.applicationContext

    override fun exactAlarmAccess(definition: ScheduleDefinition): ExactAlarmAccess = when {
        definition.timing != ScheduleTiming.EXACT -> ExactAlarmAccess.NOT_REQUIRED
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> ExactAlarmAccess.GRANTED
        alarmManager.canScheduleExactAlarms() -> ExactAlarmAccess.GRANTED
        else -> ExactAlarmAccess.DENIED
    }

    override fun schedule(definition: ScheduleDefinition) {
        cancel(definition.id)
        if (!definition.enabled) return
        if (definition.timing == ScheduleTiming.EXACT) scheduleExactAlarm(definition)
        else scheduleWork(definition)
    }

    override fun cancel(scheduleId: String) {
        workManager.cancelUniqueWork(workName(scheduleId))
        workManager.cancelUniqueWork("aegis-exact-dispatch-$scheduleId")
        exactPendingIntent(scheduleId, PendingIntent.FLAG_NO_CREATE)?.let(alarmManager::cancel)
    }

    private fun scheduleWork(definition: ScheduleDefinition) {
        val input = Data.Builder().putString(ScheduledWorkflowWorker.KEY_SCHEDULE_ID, definition.id).build()
        val constraints = definition.constraints.toWorkConstraints()
        when (definition.kind) {
            ScheduleKind.ONE_TIME -> {
                val delay = max(0L, requireNotNull(definition.scheduledAtEpochMillis) - System.currentTimeMillis())
                val request = OneTimeWorkRequestBuilder<ScheduledWorkflowWorker>()
                    .setInputData(input)
                    .setConstraints(constraints)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .addTag(TAG)
                    .build()
                workManager.enqueueUniqueWork(workName(definition.id), ExistingWorkPolicy.REPLACE, request)
            }
            ScheduleKind.PERIODIC -> {
                val interval = requireNotNull(definition.intervalMillis)
                val flex = definition.flexMillis ?: defaultFlex(interval)
                val request = PeriodicWorkRequestBuilder<ScheduledWorkflowWorker>(
                    interval, TimeUnit.MILLISECONDS, flex, TimeUnit.MILLISECONDS,
                )
                    .setInputData(input)
                    .setConstraints(constraints)
                    .addTag(TAG)
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    workName(definition.id),
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            }
        }
    }

    private fun scheduleExactAlarm(definition: ScheduleDefinition) {
        check(exactAlarmAccess(definition) == ExactAlarmAccess.GRANTED) {
            "Exact alarm special access is not granted"
        }
        val triggerAt = requireNotNull(definition.scheduledAtEpochMillis)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            requireNotNull(exactPendingIntent(definition.id, PendingIntent.FLAG_UPDATE_CURRENT, definition.constraints)),
        )
    }

    private fun exactPendingIntent(
        scheduleId: String,
        existenceFlag: Int,
        constraints: ScheduleConstraints? = null,
    ): PendingIntent? {
        val intent = Intent(appContext, ExactScheduleReceiver::class.java).apply {
            data = Uri.Builder().scheme("aegis").authority("schedule").appendPath(scheduleId).build()
            putExtra(ScheduledWorkflowWorker.KEY_SCHEDULE_ID, scheduleId)
            constraints?.let {
                putExtra(EXTRA_NETWORK, it.network.name)
                putExtra(EXTRA_CHARGING, it.requiresCharging)
                putExtra(EXTRA_BATTERY_NOT_LOW, it.requiresBatteryNotLow)
            }
        }
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            existenceFlag or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ScheduleConstraints.toWorkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            when (network) {
                ScheduleNetworkConstraint.NOT_REQUIRED -> NetworkType.NOT_REQUIRED
                ScheduleNetworkConstraint.CONNECTED -> NetworkType.CONNECTED
                ScheduleNetworkConstraint.UNMETERED -> NetworkType.UNMETERED
            },
        )
        .setRequiresCharging(requiresCharging)
        .setRequiresBatteryNotLow(requiresBatteryNotLow)
        .build()

    private fun workName(id: String) = "aegis-schedule-$id"

    private fun defaultFlex(interval: Long) = max(MIN_FLEX_MILLIS, interval / 10)

    private companion object {
        const val TAG = "aegis-schedule"
        const val MIN_FLEX_MILLIS = 5 * 60_000L
        const val EXTRA_NETWORK = "network"
        const val EXTRA_CHARGING = "charging"
        const val EXTRA_BATTERY_NOT_LOW = "battery_not_low"
    }
}

class ExactScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(ScheduledWorkflowWorker.KEY_SCHEDULE_ID) ?: return
        val request = OneTimeWorkRequestBuilder<ScheduledWorkflowWorker>()
            .setInputData(Data.Builder().putString(ScheduledWorkflowWorker.KEY_SCHEDULE_ID, scheduleId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        when (runCatching {
                            ScheduleNetworkConstraint.valueOf(intent.getStringExtra("network").orEmpty())
                        }.getOrDefault(ScheduleNetworkConstraint.NOT_REQUIRED)) {
                            ScheduleNetworkConstraint.NOT_REQUIRED -> NetworkType.NOT_REQUIRED
                            ScheduleNetworkConstraint.CONNECTED -> NetworkType.CONNECTED
                            ScheduleNetworkConstraint.UNMETERED -> NetworkType.UNMETERED
                        },
                    )
                    .setRequiresCharging(intent.getBooleanExtra("charging", false))
                    .setRequiresBatteryNotLow(intent.getBooleanExtra("battery_not_low", false))
                    .build(),
            )
            .addTag("aegis-exact-dispatch")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "aegis-exact-dispatch-$scheduleId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        ScheduleRuntime.from(context).scope.launch {
            try {
                ScheduleRuntime.from(context).restoreEnabledSchedules()
            } finally {
                pending.finish()
            }
        }
    }
}
