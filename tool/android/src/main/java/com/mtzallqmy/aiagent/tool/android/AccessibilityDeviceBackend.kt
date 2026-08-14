package com.mtzallqmy.aiagent.tool.android

import android.content.Context
import com.mtzallqmy.aiagent.agent.backends.DeviceBackend
import com.mtzallqmy.aiagent.model.CapabilityId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Accessibility-based device backend — the local, no-dependency access path.
 * Works with AccessibilityAgentService: UI tree, tap, swipe, text input,
 * scroll, and key events. Always available once the user enables the
 * accessibility service.
 */
class AccessibilityDeviceBackend(
    private val serviceProvider: () -> AccessibilityAgentService? = { AccessibilityAgentService.current() },
) : DeviceBackend {
    override val id: String = "accessibility"
    override val name: String = "Android Accessibility Service"
    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId("ui.tree"),
        CapabilityId("ui.tap"),
        CapabilityId("ui.text"),
        CapabilityId("ui.scroll"),
        CapabilityId("ui.key"),
        CapabilityId("ui.read"),
    )

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Main) {
        serviceProvider() != null
    }

    override suspend fun diagnostics(): String {
        val service = withContext(Dispatchers.Main) { serviceProvider() }
        return if (service != null) {
            val tree = runCatching { service.captureTree() }.getOrNull()
            val childCount = tree?.root?.let { runCatching { it.children.size }.getOrDefault(-1) } ?: -1
            "accessibility: enabled (root children: $childCount)"
        } else {
            "accessibility: service not enabled (Settings > Accessibility > Aegis Agent)"
        }
    }
}
