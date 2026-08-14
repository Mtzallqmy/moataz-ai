package com.mtzallqmy.aiagent.agent

import com.mtzallqmy.aiagent.providers.AiProvider

/** Registry of provider implementations — providers are pluggable without changing Agent Core. */
class ProviderRegistry {
    private val providers = mutableMapOf<String, AiProvider>()

    fun register(provider: AiProvider) { providers[provider.providerId] = provider }

    fun get(providerId: String): AiProvider? = providers[providerId]

    fun all(): List<AiProvider> = providers.values.toList()

    fun select(providerId: String): AiProvider =
        providers[providerId] ?: error("Provider not registered: $providerId")
}
