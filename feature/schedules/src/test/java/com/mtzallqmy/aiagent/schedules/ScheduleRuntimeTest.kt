package com.mtzallqmy.aiagent.schedules

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ScheduleRuntimeTest {
    private val now = 1_000_000L

    @Test
    fun `inexact one time schedule persists and reaches backend`() = runTest {
        val store = MemoryScheduleStore()
        val backend = FakeScheduleBackend()
        val runtime = runtime(store, backend)
        val definition = oneTime()

        val result = runtime.submit(definition)

        assertEquals(ScheduleStatus.SCHEDULED, result.record.status)
        assertEquals(ExactAlarmAccess.NOT_REQUIRED, result.exactAlarmAccess)
        assertEquals(listOf(definition), backend.scheduled)
        assertEquals(definition, store.get(definition.id)?.definition)
        assert(result.semantics.contains("inexact"))
    }

    @Test
    fun `exact access denial is persisted blocked and not scheduled`() = runTest {
        val store = MemoryScheduleStore()
        val backend = FakeScheduleBackend(exactAccess = ExactAlarmAccess.DENIED)
        val runtime = runtime(store, backend)

        val result = runtime.submit(oneTime(timing = ScheduleTiming.EXACT))

        assertEquals(ScheduleStatus.BLOCKED, result.record.status)
        assertEquals(ExactAlarmAccess.DENIED, result.exactAlarmAccess)
        assertFalse(backend.scheduled.isNotEmpty())
    }

    @Test
    fun `exact schedule with constraints states dispatch versus execution semantics`() = runTest {
        val runtime = runtime(MemoryScheduleStore(), FakeScheduleBackend())
        val definition = oneTime(timing = ScheduleTiming.EXACT).copy(
            constraints = ScheduleConstraints(network = ScheduleNetworkConstraint.CONNECTED),
        )

        val result = runtime.submit(definition)

        assert(result.semantics.contains("dispatch is exact"))
        assert(result.semantics.contains("may wait"))
    }

    @Test
    fun `periodic exact schedules are rejected honestly`() = runTest {
        val runtime = runtime(MemoryScheduleStore(), FakeScheduleBackend())
        val definition = periodic().copy(timing = ScheduleTiming.EXACT)

        assertThrows(ScheduleValidationException::class.java) {
            runtime.validate(definition)
        }
    }

    @Test
    fun `periodic interval and flex obey WorkManager limits`() = runTest {
        val runtime = runtime(MemoryScheduleStore(), FakeScheduleBackend())

        assertThrows(ScheduleValidationException::class.java) {
            runtime.validate(periodic(interval = 14 * 60_000L))
        }
        assertThrows(ScheduleValidationException::class.java) {
            runtime.validate(periodic(interval = 30 * 60_000L).copy(flexMillis = 4 * 60_000L))
        }
    }

    @Test
    fun `cancel removes platform work and persists cancelled state`() = runTest {
        val store = MemoryScheduleStore()
        val backend = FakeScheduleBackend()
        val runtime = runtime(store, backend)
        runtime.submit(oneTime())

        runtime.cancel("schedule")

        assertEquals(listOf("schedule"), backend.cancelled)
        assertEquals(ScheduleStatus.CANCELLED, store.get("schedule")?.status)
    }

    private fun TestScope.runtime(store: ScheduleStore, backend: SchedulePlatformBackend) = ScheduleRuntime(
        applicationContext = null,
        store = store,
        backend = backend,
        scope = this,
        nowMillis = { now },
    )

    private fun oneTime(timing: ScheduleTiming = ScheduleTiming.INEXACT) = ScheduleDefinition(
        id = "schedule",
        name = "Schedule",
        workflowId = "workflow",
        workflowVersion = 1,
        kind = ScheduleKind.ONE_TIME,
        timing = timing,
        scheduledAtEpochMillis = now + 60_000,
        createdAtMillis = now,
        updatedAtMillis = now,
    )

    private fun periodic(interval: Long = 15 * 60_000L) = ScheduleDefinition(
        id = "periodic",
        name = "Periodic",
        workflowId = "workflow",
        workflowVersion = 1,
        kind = ScheduleKind.PERIODIC,
        intervalMillis = interval,
        createdAtMillis = now,
        updatedAtMillis = now,
    )
}

private class FakeScheduleBackend(
    private val exactAccess: ExactAlarmAccess = ExactAlarmAccess.GRANTED,
) : SchedulePlatformBackend {
    val scheduled = mutableListOf<ScheduleDefinition>()
    val cancelled = mutableListOf<String>()
    override fun exactAlarmAccess(definition: ScheduleDefinition) =
        if (definition.timing == ScheduleTiming.EXACT) exactAccess else ExactAlarmAccess.NOT_REQUIRED
    override fun schedule(definition: ScheduleDefinition) { scheduled += definition }
    override fun cancel(scheduleId: String) { cancelled += scheduleId }
}

private class MemoryScheduleStore : ScheduleStore {
    private val records = mutableMapOf<String, ScheduleRecord>()
    override suspend fun put(record: ScheduleRecord) { records[record.definition.id] = record }
    override suspend fun get(id: String) = records[id]
    override suspend fun list() = records.values.toList()
    override suspend fun remove(id: String) { records.remove(id) }
}
