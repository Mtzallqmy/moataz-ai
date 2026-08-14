# Aegis Android schedules

`:feature:schedules` persists schedule definitions and dispatch history. Inexact
one-time and periodic work uses WorkManager with network, charging, and
battery-not-low constraints. Android's 15-minute minimum periodic interval and
5-minute flex minimum are enforced.

Explicitly exact one-time alarms use `AlarmManager.setExactAndAllowWhileIdle` only
when the user-controlled `SCHEDULE_EXACT_ALARM` special access is available. Exact
periodic schedules are rejected because Android does not guarantee them. When an
exact alarm has constraints, the alarm dispatch is exact but its WorkManager
execution waits for those constraints and is therefore not exact. Completion time
is never described as exact.

Alarms are restored after boot/package replacement, and WorkManager persists its
own jobs. Foreground service is not used by this module: dispatch is short and the
persisted Workflow Engine continues the run. A future long-running worker may
promote itself only with a user-visible notification and a justified foreground
service type.

`AegisApp` is the required execution host. A scheduled dispatch starts a stored,
versioned workflow and records its run ID. Missing workflows or permissions fail
and are persisted; no fake execution result exists.
