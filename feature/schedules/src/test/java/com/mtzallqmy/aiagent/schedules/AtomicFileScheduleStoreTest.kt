package com.mtzallqmy.aiagent.schedules

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileScheduleStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `record survives store recreation`() = runTest {
        val file = temporaryFolder.newFile("schedules.json").apply { delete() }
        val definition = ScheduleDefinition(
            id = "schedule",
            name = "Schedule",
            workflowId = "workflow",
            workflowVersion = 1,
            kind = ScheduleKind.ONE_TIME,
            scheduledAtEpochMillis = 2_000,
            createdAtMillis = 1,
            updatedAtMillis = 1,
        )
        val record = ScheduleRecord(definition, ScheduleStatus.SCHEDULED)
        AtomicFileScheduleStore(file).put(record)

        assertEquals(record, AtomicFileScheduleStore(file).get("schedule"))
    }

    @Test
    fun `corrupt store is surfaced without destructive reset`() = runTest {
        val file = temporaryFolder.newFile("corrupt.json").apply { writeText("broken") }
        val store = AtomicFileScheduleStore(file)

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { store.list() }
        }
        assertEquals("broken", file.readText())
    }
}
