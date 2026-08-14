package com.mtzallqmy.aiagent.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours

/**
 * Heartbeat agent — concept studied from Kai 9000 (Apache-2.0,
 * clean-room reimplementation): a periodic autonomous self-check that reviews
 * memories, pending tasks, and provider health. If something needs attention
 * it surfaces a notification; otherwise it stays silent.
 */
class HeartbeatAgent(
    private val intervalMs: Long = 30L * 60 * 1000, // 30 minutes
    private val activeHoursStart: Int = 8,
    private val activeHoursEnd: Int = 22,
) {
    sealed interface HeartbeatEvent {
        data object Silent : HeartbeatEvent
        data class NeedsAttention(val reason: String, val severity: Int = 1) : HeartbeatEvent
    }

    /** Pluggable reviewer — the app wires memory/provider/task checks here. */
    fun interface Reviewer {
        suspend fun review(): HeartbeatEvent
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastEvent = AtomicReference<HeartbeatEvent>(HeartbeatEvent.Silent)
    private var job: Job? = null
    private val listeners = mutableListOf<(HeartbeatEvent) -> Unit>()

    fun onEvent(listener: (HeartbeatEvent) -> Unit) { listeners.add(listener) }

    fun start(reviewer: Reviewer) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                mutex.withLock { runCatching { reviewer.review() } }
                    .onSuccess { event ->
                        lastEvent.set(event)
                        if (event is HeartbeatEvent.NeedsAttention && isInActiveHours()) {
                            listeners.toList().forEach { it(event) }
                        }
                    }
                delay(intervalMs)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    fun lastKnownEvent(): HeartbeatEvent = lastEvent.get()

    private fun isInActiveHours(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour in activeHoursStart until activeHoursEnd
    }
}
