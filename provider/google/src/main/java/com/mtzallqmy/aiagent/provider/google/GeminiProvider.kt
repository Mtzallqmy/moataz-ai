package com.mtzallqmy.aiagent.provider.google

import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.network.SafeHttpClient
import com.mtzallqmy.aiagent.providers.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Real Gemini provider using the Generative Language REST API with SSE streaming.
 */
class GeminiProvider(
    private val apiKeyProvider: suspend () -> String?,
    private val defaultModel: String = "gemini-2.5-flash",
) : AiProvider {
    override val providerId = "gemini"
    override val name = "Google Gemini"

    private val client = SafeHttpClient.create()
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun listModels(): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val key = apiKeyProvider() ?: throw ProviderError.AuthenticationError("No Gemini API key")
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models")
                .header("x-goog-api-key", key)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val raw = resp.body?.string() ?: "{}"
                val root = json.decodeFromString<GeminiModelList>(raw)
                val result = root.models.mapNotNull { m ->
                    val name = m.name ?: return@mapNotNull null
                    val id = name.removePrefix("models/")
                    val inputTokens = m.inputTokenLimit ?: 0
                    val outputTokens = m.outputTokenLimit ?: 0
                    val supportsGenContent = m.supportedGenerationMethods?.contains("generateContent") == true
                    val lowerId = id.lowercase()
                    // The Models endpoint exposes generateContent support but not a reliable
                    // per-model function-calling flag. Stay conservative for media-only variants
                    // instead of advertising tool calling that the endpoint can reject at runtime.
                    val mediaOnlyVariant = listOf("image", "tts", "audio", "embedding").any(lowerId::contains)
                    val supportsToolCalling = supportsGenContent && lowerId.startsWith("gemini-") && !mediaOnlyVariant
                    if (!supportsGenContent) null
                    else AiModel(
                        id = id,
                        name = m.displayName ?: id,
                        providerId = providerId,
                        capabilities = ModelCapabilities(
                            chat = true,
                            streaming = true,
                            toolCalling = supportsToolCalling,
                            vision = lowerId.startsWith("gemini-") && !listOf("tts", "audio", "embedding").any(lowerId::contains),
                            reasoning = lowerId.startsWith("gemini-2.5") || lowerId.startsWith("gemini-3"),
                            contextWindow = inputTokens,
                            maxOutputTokens = outputTokens,
                        ),
                        routing = ModelRoutingMetadata(
                            speedTier = if (id.contains("flash", true)) ModelSpeedTier.FAST else ModelSpeedTier.QUALITY,
                            codingOptimized = id.contains("code", true),
                        ),
                    )
                }
                Result.success(result)
            }
        } catch (e: Throwable) { Result.failure(mapError(e)) }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKeyProvider() ?: throw ProviderError.AuthenticationError("No Gemini API key")
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$defaultModel:generateContent"
            val body = """{"contents":[{"parts":[{"text":"ping"}]}]}"""
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", key)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> Unit
                    resp.code in 401..403 -> throw ProviderError.AuthenticationError("Invalid Gemini key")
                    resp.code == 429 -> throw ProviderError.RateLimitError()
                    resp.code == 404 -> throw ProviderError.ModelNotFoundError(defaultModel)
                    else -> throw ProviderError.ProviderError_(resp.code, "Gemini error")
                }
            }
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = callbackFlow {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            trySend(GenerationEvent.GenerationFailed(ProviderError.AuthenticationError("No Gemini API key")))
            close()
            return@callbackFlow
        }
        val model = request.modelId.ifEmpty { defaultModel }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse"
        val payload = buildPayload(request)
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", key)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!call.isCanceled()) trySend(GenerationEvent.GenerationFailed(mapError(error)))
                close()
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        trySend(GenerationEvent.GenerationFailed(mapHttpError(resp.code)))
                        close()
                        return
                    }
                    var terminalSent = false
                    val toolCallIds = mutableMapOf<String, String>()
                    var inputTokensReported = 0
                    var outputTokensReported = 0
                    fun completeOnce() {
                        if (!terminalSent) {
                            terminalSent = true
                            trySend(GenerationEvent.GenerationCompleted(""))
                        }
                    }
                    try {
                        resp.body?.source()?.let { source ->
                            while (!source.exhausted() && !call.isCanceled()) {
                                val line = source.readUtf8Line() ?: break
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                val root = runCatching { json.parseToJsonElement(data) as? kotlinx.serialization.json.JsonObject }
                                    .getOrNull() ?: continue
                                val candidate = (root["candidates"] as? kotlinx.serialization.json.JsonArray)
                                    ?.firstOrNull() as? kotlinx.serialization.json.JsonObject
                                val parts = (candidate?.get("content") as? kotlinx.serialization.json.JsonObject)
                                    ?.get("parts") as? kotlinx.serialization.json.JsonArray
                                parts?.forEachIndexed { partIndex, partElement ->
                                    val part = partElement as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
                                    part["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { text ->
                                        trySend(GenerationEvent.TextDelta(text))
                                    }
                                    val functionCall = part["functionCall"] as? kotlinx.serialization.json.JsonObject
                                    val functionName = functionCall?.get("name")?.jsonPrimitive?.content
                                    if (!functionName.isNullOrBlank()) {
                                        val key = "$partIndex:$functionName"
                                        val isNew = key !in toolCallIds
                                        val callId = toolCallIds.getOrPut(key) { "gemini-tool-${toolCallIds.size}" }
                                        if (isNew) {
                                            trySend(GenerationEvent.ToolCallStarted(callId, functionName))
                                            val args = functionCall["args"] ?: buildJsonObject { }
                                            trySend(GenerationEvent.ToolCallArgumentsDelta(callId, args.toString()))
                                        }
                                    }
                                }
                                candidate?.get("finishReason")?.jsonPrimitive?.content?.let { reason ->
                                    if (reason == "STOP" || reason == "MAX_TOKENS") completeOnce()
                                }
                                (root["usageMetadata"] as? kotlinx.serialization.json.JsonObject)?.let { usage ->
                                    val promptTotal = usage["promptTokenCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: inputTokensReported
                                    val completionTotal = usage["candidatesTokenCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: outputTokensReported
                                    val promptDelta = (promptTotal - inputTokensReported).coerceAtLeast(0)
                                    val completionDelta = (completionTotal - outputTokensReported).coerceAtLeast(0)
                                    if (promptDelta > 0 || completionDelta > 0) {
                                        trySend(GenerationEvent.Usage(promptDelta, completionDelta))
                                    }
                                    inputTokensReported = maxOf(inputTokensReported, promptTotal)
                                    outputTokensReported = maxOf(outputTokensReported, completionTotal)
                                }
                            }
                        }
                        if (!call.isCanceled()) completeOnce()
                    } catch (error: Exception) {
                        if (!call.isCanceled()) trySend(GenerationEvent.GenerationFailed(mapError(error)))
                    } finally {
                        close()
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    private fun buildPayload(request: GenerationRequest): String {
        val system = request.messages.firstOrNull { it.role == MessageRole.SYSTEM }?.content
        val chatMessages = request.messages.filter { it.role != MessageRole.SYSTEM }
        val sb = StringBuilder()
        sb.append("{\"contents\":")
        sb.append("[")
        chatMessages.forEachIndexed { idx, m ->
            if (idx > 0) sb.append(",")
            when {
                m.role == MessageRole.TOOL && !m.toolName.isNullOrBlank() -> {
                    sb.append("{\"role\":\"user\",\"parts\":[{\"functionResponse\":{\"name\":")
                        .append(jsonString(m.toolName))
                        .append(",\"response\":{\"output\":").append(jsonString(m.content)).append("}}}]}")
                }
                m.role == MessageRole.ASSISTANT && m.toolCalls.isNotEmpty() -> {
                    sb.append("{\"role\":\"model\",\"parts\":[")
                    var partIndex = 0
                    if (m.content.isNotBlank()) {
                        sb.append("{\"text\":").append(jsonString(m.content)).append("}")
                        partIndex++
                    }
                    m.toolCalls.forEach { call ->
                        if (partIndex++ > 0) sb.append(",")
                        sb.append("{\"functionCall\":{\"name\":").append(jsonString(call.name))
                            .append(",\"args\":").append(safeArguments(call.arguments)).append("}}")
                    }
                    sb.append("]}")
                }
                else -> {
                    val role = if (m.role == MessageRole.ASSISTANT) "model" else "user"
                    sb.append("{\"role\":").append(jsonString(role))
                        .append(",\"parts\":[{\"text\":").append(jsonString(m.content)).append("}]}")
                }
            }
        }
        sb.append("]")
        system?.let { sb.append(",\"systemInstruction\":{\"parts\":[{\"text\":").append(jsonString(it)).append("}]}") }
        if (request.tools.isNotEmpty()) {
            sb.append(",\"tools\":[{\"functionDeclarations\":[")
            request.tools.forEachIndexed { idx, t ->
                if (idx > 0) sb.append(",")
                sb.append("{\"name\":").append(jsonString(t.id)).append(",\"description\":").append(jsonString(t.description)).append(",\"parameters\":")
                sb.append(safeSchema(t.inputSchema))
                sb.append("}")
            }
            sb.append("]}]")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun safeSchema(raw: String): String = runCatching {
        json.parseToJsonElement(raw.ifBlank { DEFAULT_TOOL_SCHEMA }).toString()
    }.getOrDefault(DEFAULT_TOOL_SCHEMA)

    private fun safeArguments(raw: String): String = runCatching {
        val parsed = json.parseToJsonElement(raw.ifBlank { "{}" })
        if (parsed is kotlinx.serialization.json.JsonObject) parsed.toString() else "{}"
    }.getOrDefault("{}")

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) when (ch) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u${ch.code.toString(16).padStart(4, '0')}") else append(ch)
        }
        append('"')
    }

    private fun mapHttpError(code: Int): ProviderError = when (code) {
        401, 403 -> ProviderError.AuthenticationError("Gemini authentication failed")
        429 -> ProviderError.RateLimitError()
        404 -> ProviderError.ModelNotFoundError(defaultModel)
        else -> ProviderError.ProviderError_(code, "Gemini HTTP $code")
    }

    private fun mapError(e: Throwable): ProviderError = when (e) {
        is ProviderError -> e
        is IOException -> ProviderError.NetworkError(e.message ?: "Network failure")
        else -> ProviderError.ProviderError_(500, e.message ?: "Gemini error")
    }

    private companion object {
        const val DEFAULT_TOOL_SCHEMA = "{\"type\":\"object\",\"properties\":{}}"
    }
}

@Serializable private data class GeminiModelList(val models: List<GeminiModelEntry> = emptyList())
@Serializable private data class GeminiModelEntry(
    val name: String? = null,
    val displayName: String? = null,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null,
    val supportedGenerationMethods: List<String>? = null,
)
