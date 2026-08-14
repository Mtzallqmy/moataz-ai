package com.mtzallqmy.aiagent.tool.android

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationLoopTest {
    @Test
    fun `verification polls without repeating a side effecting action`() = runTest {
        var actions = 0
        var observations = 0
        val root = UiNode(
            packageName = "test",
            className = "Root",
            text = "done",
            contentDescription = "",
            resourceId = "",
            bounds = UiBounds(0, 0, 10, 10),
            clickable = false,
            editable = false,
            focusable = false,
            scrollable = false,
            enabled = true,
            checked = false,
            selected = false,
            children = emptyList(),
        )
        val loop = VerificationLoop(
            treeProvider = {
                observations += 1
                root.takeIf { observations >= 2 }
            },
            maxAttempts = 3,
            delayMs = 1,
        )

        val verified = loop.verifyAfter(
            action = { actions += 1 },
            expectedState = { it.text == "done" },
            sleep = {},
        )

        assertTrue(verified)
        assertEquals(1, actions)
        assertEquals(2, observations)
    }
}
