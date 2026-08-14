package com.mtzallqmy.aiagent.provider.openrouter

import com.mtzallqmy.aiagent.model.ModelCapabilities
import com.mtzallqmy.aiagent.provider.compatible.OpenAiCompatibleProvider

/**
 * OpenRouter: OpenAI-compatible router with its own model catalog endpoint.
 */
class OpenRouterProvider(
    private val apiKeyProvider: suspend () -> String?,
    private val defaultModel: String = "anthropic/claude-opus-4-7",
) : OpenAiCompatibleProvider(
    providerId = "openrouter",
    name = "OpenRouter",
    baseUrlProvider = { "https://openrouter.ai/api/v1" },
    apiKeyProvider = apiKeyProvider,
    defaultModel = defaultModel,
) {
    override suspend fun modelsEndpoint(): String = "https://openrouter.ai/api/v1/models"

    override suspend fun listModels(): Result<List<com.mtzallqmy.aiagent.model.AiModel>> {
        // Reuse the OpenAI-compatible catalog endpoint with OpenRouter's published models.
        return super.listModels()
    }

    override suspend fun testConnection(): Result<Unit> = super.testConnection()
}
