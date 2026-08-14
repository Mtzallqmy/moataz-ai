package com.mtzallqmy.aiagent.provider.compatible

import com.mtzallqmy.aiagent.providers.AiProvider

/**
 * Provider presets: known OpenAI-compatible AI platforms registered through the
 * generic OpenAI-compatible backend. No duplicate implementations — each preset
 * is only a (baseUrl, header name, default models) configuration.
 *
 * Presets: Groq, DeepSeek, xAI (Grok), Mistral, NVIDIA NIM, Hugging Face
 * Inference, Together, Fireworks, Cerebras, Ollama, LM Studio, llama.cpp server.
 */
object ProviderPresets {

    fun groq(apiKeyProvider: suspend () -> String?) = OpenAiCompatibleProvider(
        providerId = "groq", name = "Groq",
        baseUrlProvider = { "https://api.groq.com/openai/v1" },
        apiKeyProvider = apiKeyProvider, authHeaderName = "Authorization",
        authHeaderValueProvider = { key -> "Bearer ${key.orEmpty()}" },
        defaultModel = "llama-3.3-70b-versatile",
    )

    fun deepSeek(apiKeyProvider: suspend () -> String?) = OpenAiCompatibleProvider(
        providerId = "deepseek", name = "DeepSeek",
        baseUrlProvider = { "https://api.deepseek.com" },
        apiKeyProvider = apiKeyProvider, authHeaderName = "Authorization",
        authHeaderValueProvider = { key -> "Bearer ${key.orEmpty()}" },
        defaultModel = "deepseek-chat",
    )

    fun xai(apiKeyProvider: suspend () -> String?) = OpenAiCompatibleProvider(
        providerId = "xai", name = "xAI (Grok)",
        baseUrlProvider = { "https://api.x.ai/v1" },
        apiKeyProvider = apiKeyProvider, authHeaderName = "Authorization",
        authHeaderValueProvider = { key -> "Bearer ${key.orEmpty()}" },
        defaultModel = "grok-3",
    )

    fun mistral(apiKeyProvider: suspend () -> String?) = OpenAiCompatibleProvider(
        providerId = "mistral", name = "Mistral",
        baseUrlProvider = { "https://api.mistral.ai/v1" },
        apiKeyProvider = apiKeyProvider, authHeaderName = "Authorization",
        authHeaderValueProvider = { key -> "Bearer ${key.orEmpty()}" },
        defaultModel = "mistral-large-latest",
    )

    fun nvidiaNim(apiKeyProvider: suspend () -> String?) = OpenAiCompatibleProvider(
        providerId = "nvidia-nim", name = "NVIDIA NIM",
        baseUrlProvider = { "https://integrate.api.nvidia.com/v1" },
        apiKeyProvider = apiKeyProvider, authHeaderName = "Authorization",
        authHeaderValueProvider = { key -> "Bearer ${key.orEmpty()}" },
        defaultModel = "meta/llama-3.1-8b-instruct",
    )

    fun huggingFace(apiKeyProvider: suspend () -> String?) = OpenAiCompatibleProvider(
        providerId = "huggingface", name = "Hugging Face Inference",
        baseUrlProvider = { "https://api-inference.huggingface.co/v1" },
        apiKeyProvider = apiKeyProvider, authHeaderName = "Authorization",
        authHeaderValueProvider = { key -> "Bearer ${key.orEmpty()}" },
        defaultModel = "HuggingFaceH4/zephyr-7b-beta",
    )

    fun together(apiKeyProvider: suspend () -> String?) = OpenAiCompatibleProvider(
        providerId = "together", name = "Together AI",
        baseUrlProvider = { "https://api.together.xyz/v1" },
        apiKeyProvider = apiKeyProvider, authHeaderName = "Authorization",
        authHeaderValueProvider = { key -> "Bearer ${key.orEmpty()}" },
        defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
    )

    fun fireworks(apiKeyProvider: suspend () -> String?) = OpenAiCompatibleProvider(
        providerId = "fireworks", name = "Fireworks AI",
        baseUrlProvider = { "https://api.fireworks.ai/inference/v1" },
        apiKeyProvider = apiKeyProvider, authHeaderName = "Authorization",
        authHeaderValueProvider = { key -> "Bearer ${key.orEmpty()}" },
        defaultModel = "accounts/fireworks/models/llama-v3p3-70b-instruct",
    )

    fun cerebras(apiKeyProvider: suspend () -> String?) = OpenAiCompatibleProvider(
        providerId = "cerebras", name = "Cerebras",
        baseUrlProvider = { "https://api.cerebras.ai/v1" },
        apiKeyProvider = apiKeyProvider, authHeaderName = "Authorization",
        authHeaderValueProvider = { key -> "Bearer ${key.orEmpty()}" },
        defaultModel = "llama3.3-70b",
    )

    /** Local Ollama server (http://localhost:11434/v1 by default). */
    fun ollama(apiKeyProvider: suspend () -> String? = { "" }, baseUrl: String = "http://localhost:11434/v1") =
        OpenAiCompatibleProvider(
            providerId = "ollama", name = "Ollama (local)",
            baseUrlProvider = { baseUrl },
            apiKeyProvider = apiKeyProvider, authHeaderName = "Authorization",
            authHeaderValueProvider = { key -> "Bearer ${key.orEmpty()}" },
            defaultModel = "llama3",
            allowPrivateNetwork = true,
        )

    /** LM Studio local server (http://localhost:1234/v1 by default). */
    fun lmStudio(apiKeyProvider: suspend () -> String? = { "" }, baseUrl: String = "http://localhost:1234/v1") =
        OpenAiCompatibleProvider(
            providerId = "lm-studio", name = "LM Studio (local)",
            baseUrlProvider = { baseUrl },
            apiKeyProvider = apiKeyProvider, authHeaderName = "Authorization",
            authHeaderValueProvider = { key -> "Bearer ${key.orEmpty()}" },
            defaultModel = "local-model",
            allowPrivateNetwork = true,
        )

    /** llama.cpp server (OpenAI-compatible mode; http://localhost:8080/v1 by default). */
    fun llamaCppServer(apiKeyProvider: suspend () -> String? = { "" }, baseUrl: String = "http://localhost:8080/v1") =
        OpenAiCompatibleProvider(
            providerId = "llama-cpp-server", name = "llama.cpp server (local)",
            baseUrlProvider = { baseUrl },
            apiKeyProvider = apiKeyProvider, authHeaderName = "Authorization",
            authHeaderValueProvider = { key -> "Bearer ${key.orEmpty()}" },
            defaultModel = "local-model",
            allowPrivateNetwork = true,
        )
}
