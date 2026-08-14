package com.mtzallqmy.aiagent.agent.backends

import com.mtzallqmy.aiagent.model.CapabilityId
import kotlinx.serialization.json.JsonObject

/**
 * Coding backend abstraction — concepts studied from OpenHands (MIT,
 * clean-room reimplementation): a pluggable strategy for executing coding
 * agent work (sandbox file editing, linting, patching).
 *
 *   CodingBackend → LocalSandboxCoding (on-device TerminalToolSet path)
 *                 → OpenHandsRemote (REST adapter to an OpenHands instance)
 */
interface CodingBackend {
    val id: String
    val name: String
    val capabilities: Set<CapabilityId>
    suspend fun isAvailable(): Boolean

    suspend fun run(
        task: String,
        context: Map<String, String> = emptyMap(),
    ): CodingResult
}

data class CodingResult(
    val success: Boolean,
    val summary: String,
    val patches: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val artifacts: Map<String, String> = emptyMap(),
)

/** Optional JSON payload for remote coding backends. */
typealias CodingPayload = JsonObject
