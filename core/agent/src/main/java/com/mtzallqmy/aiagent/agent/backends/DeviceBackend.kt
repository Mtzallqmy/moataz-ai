package com.mtzallqmy.aiagent.agent.backends

import com.mtzallqmy.aiagent.model.CapabilityId

/**
 * DeviceBackend abstraction (concepts studied from Kai 9000 / DroidMind / Sanna,
 * Apache-2.0/MIT — clean-room reimplementation, not a fork).
 *
 * Pluggable strategies for device interaction so the Agent Core never depends on
 * any single access path. The app works locally without ADB; backends are optional.
 *
 *   DeviceBackend → AccessibilityService / Shizuku(stub) / AdbDeviceBackend
 */
interface DeviceBackend {
    val id: String
    val name: String
    val capabilities: Set<CapabilityId>

    /** True when this backend is usable on the current device right now. */
    suspend fun isAvailable(): Boolean

    /** Human-readable diagnostics about this backend's readiness. */
    suspend fun diagnostics(): String
}

/** Composite that picks the best available backend per capability. */
class DeviceBackendRegistry {
    private val backends = mutableListOf<DeviceBackend>()

    fun register(backend: DeviceBackend) {
        backends.removeAll { it.id == backend.id }
        backends.add(backend)
    }

    fun all(): List<DeviceBackend> = backends.toList()

    /** Backend(s) that can serve the requested capability, availability first. */
    suspend fun select(capability: CapabilityId): List<DeviceBackend> {
        val candidates = backends.filter { capability in it.capabilities }
        val (ready, checking) = candidates.partition { it.isAvailable() }
        return ready + checking
    }

    suspend fun diagnostics(): Map<String, String> =
        backends.associate { it.id to it.diagnostics() }
}
