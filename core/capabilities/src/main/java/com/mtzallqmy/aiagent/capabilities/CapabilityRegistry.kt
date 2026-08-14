package com.mtzallqmy.aiagent.capabilities

import com.mtzallqmy.aiagent.model.CapabilityAvailabilityState
import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.CapabilityStatus

/**
 * Runtime capability discovery. A capability is AVAILABLE only after a real check:
 * Android permission state, service state, backend availability, or device support.
 * States are never hard-coded booleans.
 */
interface Capability {
    val id: CapabilityId
    suspend fun availability(): CapabilityAvailabilityState
}

/**
 * Central registry. Tools query it; the Agent must never assume a capability exists.
 */
class CapabilityRegistry {
    private val capabilities = mutableMapOf<CapabilityId, Capability>()

    fun register(capability: Capability) {
        capabilities[capability.id] = capability
    }

    fun unregister(id: CapabilityId) {
        capabilities.remove(id)
    }

    suspend fun status(id: CapabilityId): CapabilityStatus {
        val capability = capabilities[id] ?: return CapabilityStatus(id, CapabilityAvailabilityState.BACKEND_UNAVAILABLE)
        return CapabilityStatus(id, capability.availability())
    }

    suspend fun allStatuses(): List<CapabilityStatus> = capabilities.values.map { it ->
        CapabilityStatus(it.id, it.availability())
    }

    fun list(): Set<CapabilityId> = capabilities.keys.toSet()
}
