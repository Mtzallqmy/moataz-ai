package com.mtzallqmy.aiagent.feature.providers

import com.mtzallqmy.aiagent.agent.ProviderRegistry
import com.mtzallqmy.aiagent.model.AiModel
import com.mtzallqmy.aiagent.model.ProviderError
import kotlinx.coroutines.flow.first

/** Providers feature: lists, tests connections, manages key-pool strategies. */
class ProvidersFeature(
    private val registry: ProviderRegistry,
) {
    suspend fun listModels(providerId: String): Result<List<AiModel>> =
        registry.get(providerId)?.listModels()
            ?: Result.failure(IllegalStateException("Provider not registered: $providerId"))

    suspend fun testConnection(providerId: String): Result<Unit> =
        registry.get(providerId)?.testConnection()
            ?: Result.failure(IllegalStateException("Provider not registered: $providerId"))

    fun listProviders(): List<Pair<String, String>> =
        registry.all().map { it.providerId to it.name }

    fun describeError(error: ProviderError): String = when (error) {
        is ProviderError.AuthenticationError -> "Authentication failed: ${error.reason}"
        is ProviderError.RateLimitError -> "Rate limited — backing off"
        is ProviderError.ModelNotFoundError -> "Model not found: ${error.modelId}"
        is ProviderError.NetworkError -> "Network error: ${error.reason}"
        is ProviderError.ProviderError_ -> "Provider error (${error.statusCode}): ${error.reason}"
        is ProviderError.StreamingNotSupported -> "Streaming not supported by this provider"
        is ProviderError.ConfigurationError -> "Configuration error: ${error.reason}"
        is ProviderError.CapabilityError -> "Capability unavailable: ${error.reason}"
        is ProviderError.RoutingError -> "Routing failed: ${error.reason}"
    }
}
