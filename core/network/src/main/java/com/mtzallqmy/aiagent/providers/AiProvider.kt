package com.mtzallqmy.aiagent.providers

import com.mtzallqmy.aiagent.model.AiModel
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.GenerationRequest
import kotlinx.coroutines.flow.Flow

/**
 * Provider abstraction: the Agent Core is company-agnostic.
 * Every provider (OpenAI, Anthropic, Gemini, OpenRouter, OpenAI-compatible)
 * maps its wire format to normalized GenerationEvents.
 */
interface AiProvider {
    val providerId: String
    val name: String
    suspend fun listModels(): Result<List<AiModel>>
    suspend fun testConnection(): Result<Unit>
    fun generate(request: GenerationRequest): Flow<GenerationEvent>
}
