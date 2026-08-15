package com.mtzallqmy.aiagent.provider.compatible

import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.network.SafeHttpClient
import com.mtzallqmy.aiagent.providers.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Generic OpenAI-compatible provider: the user supplies base URL, auth header value,
 * model id, and optional extra headers. Used for Anthropic-compatible, LM Studio,
 * Ollama, LiteLLM, Together, DeepSeek, OpenRouter (subclass) etc.
 */
open class OpenAiCompatibleProvider(
    override val providerId: String = "openai-compatible",
    override val name: String = "OpenAI Compatible",
    private val baseUrlProvider: suspend () -> String?,
    private val apiKeyProvider: suspend () -> String?,
    private val authHeaderName: String = "Authorization",
    private val authHeaderValueProvider: (String?) -> String = { key -> "Bearer ${key.orEmpty()}" },
    private val extraHeadersProvider: () -> Map<String, String> = { emptyMap() },
    private val defaultModel: String = "",
    private val allowPrivateNetwork: Boolean = false,
) : AiProvider {

    protected val client = SafeHttpClient.create(allowPrivateNetwork = allowPrivateNetwork)
    protected val json = Json { ignoreUnknownKeys = true }

    protected open suspend fun completionsEndpoint(): String {
        val base = baseUrlProvider()?.trimEnd('/') ?: throw IllegalStateException("No base URL")
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    protected open suspend fun modelsEndpoint(): String {
        val base = baseUrlProvider()?.trimEnd('/') ?: throw IllegalStateException("No base URL")
        return if (base.endsWith("/models")) base else "$base/models"
    }
    override suspend fun listModels(): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val base = baseUrlProvider() ?: throw ProviderError.ConfigurationError("Base URL not configured")
            val key = apiKeyProvider()
            val req = Request.Builder()
                .url(modelsEndpoint())
                .header(authHeaderName, authHeaderValueProvider(key))
                .apply { extraHeadersProvider().forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val raw = resp.body?.string() ?: "[]"
                val entries = try {
                    json.decodeFromString<CompatibleModelList>(raw).data
                } catch (e: Exception) {
                    // Fallback: treat body as a bare JSON array
                    try {
                        json.decodeFromString<List<CompatibleModelEntry>>(raw)
                    } catch (e2: Exception) {
                        emptyList()
                    }
                }
                val models = entries.map { e ->
                    AiModel(
                        id = e.id,
                        name = e.id,
                        providerId = providerId,
                        capabilities = ModelCapabilities(chat = true, streaming = true),
                        routing = ModelRoutingMetadata(
                            speedTier = when {
                                e.id.contains("flash", true) || e.id.contains("mini", true) || e.id.contains("small", true) -> ModelSpeedTier.FAST
                                e.id.contains("large", true) || e.id.contains("pro", true) -> ModelSpeedTier.QUALITY
                                else -> ModelSpeedTier.BALANCED
                            },
                            codingOptimized = e.id.contains("coder", true) || e.id.contains("code", true),
                        ),
                    )
                }
                Result.success(models)
            }
        } catch (e: Throwable) { Result.failure(mapError(e)) }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKeyProvider()
            val req = Request.Builder()
                .url(completionsEndpoint())
                .header(authHeaderName, authHeaderValueProvider(key))
                .header("Content-Type", "application/json")
                .post(("{\"model\":\"" + defaultModel.ifEmpty { "default" } + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":5,\"stream\":false}").toRequestBody("application/json".toMediaType()))
                .apply { extraHeadersProvider().forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> Unit
                    resp.code in 401..403 -> throw ProviderError.AuthenticationError("Invalid credentials for $providerId")
                    resp.code == 429 -> throw ProviderError.RateLimitError()
                    resp.code == 404 -> throw ProviderError.ModelNotFoundError(defaultModel)
                    else -> throw ProviderError.ProviderError_(resp.code, "$providerId request failed")
                }
            }
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = callbackFlow {
        val base = baseUrlProvider()
        val key = apiKeyProvider()
        if (base.isNullOrBlank()) {
            trySend(GenerationEvent.GenerationFailed(ProviderError.ConfigurationError("Base URL not configured")))
            close()
            return@callbackFlow
        }
        val payload = buildPayload(request)
        val req = runCatching {
            Request.Builder()
                .url(completionsEndpoint())
                .header(authHeaderName, authHeaderValueProvider(key))
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .apply { extraHeadersProvider().forEach { (name, value) -> header(name, value) } }
                .build()
        }.getOrElse { error ->
            trySend(GenerationEvent.GenerationFailed(mapError(error)))
            close()
            return@callbackFlow
        }
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
                    val toolIdsByIndex = mutableMapOf<Int, String>()
                    val startedToolIndices = mutableSetOf<Int>()
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
                                if (data == "[DONE]") {
                                    completeOnce()
                                    break
                                }
                                val chunk = runCatching { json.decodeFromString<CompatibleChunk>(data) }
                                    .getOrNull() ?: continue
                                chunk.usage?.let { usage ->
                                    trySend(GenerationEvent.Usage(usage.promptTokens ?: 0, usage.completionTokens ?: 0))
                                }
                                val choice = chunk.choices.firstOrNull() ?: continue
                                val delta = choice.delta
                                delta.content?.takeIf { it.isNotEmpty() }?.let {
                                    trySend(GenerationEvent.TextDelta(it))
                                }
                                for (toolCall in delta.toolCalls.orEmpty()) {
                                    val index = toolCall.index ?: 0
                                    val functionName = toolCall.function?.name
                                    val effectiveId = toolCall.id
                                        ?.takeIf { it.isNotBlank() }
                                        ?.also { toolIdsByIndex[index] = it }
                                        ?: toolIdsByIndex[index]
                                        ?: "compatible-tool-$index".also { toolIdsByIndex[index] = it }
                                    if (index !in startedToolIndices && !functionName.isNullOrBlank()) {
                                        startedToolIndices += index
                                        trySend(GenerationEvent.ToolCallStarted(effectiveId, functionName))
                                    }
                                    toolCall.function?.arguments?.takeIf { it.isNotEmpty() }?.let { args ->
                                        trySend(GenerationEvent.ToolCallArgumentsDelta(effectiveId, args))
                                    }
                                }
                                if (choice.finishReason != null) completeOnce()
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

    protected open fun buildPayload(request: GenerationRequest): String {
        val sb = StringBuilder()
        sb.append("{\"model\":").append(jsonString(request.modelId.ifEmpty { defaultModel })).append(",\"messages\":")
        sb.append("[")
        request.messages.forEachIndexed { idx, m ->
            if (idx > 0) sb.append(",")
            val toolCallId = m.toolCallId
            when {
                m.role == MessageRole.TOOL && !toolCallId.isNullOrBlank() -> {
                    sb.append("{\"role\":\"tool\",\"tool_call_id\":")
                        .append(jsonString(toolCallId))
                        .append(",\"content\":").append(jsonString(m.content)).append("}")
                }
                m.role == MessageRole.ASSISTANT && m.toolCalls.isNotEmpty() -> {
                    sb.append("{\"role\":\"assistant\",\"content\":")
                        .append(jsonString(m.content))
                        .append(",\"tool_calls\":[")
                    m.toolCalls.forEachIndexed { toolIndex, call ->
                        if (toolIndex > 0) sb.append(",")
                        sb.append("{\"id\":").append(jsonString(call.id))
                            .append(",\"type\":\"function\",\"function\":{\"name\":")
                            .append(jsonString(call.name))
                            .append(",\"arguments\":").append(jsonString(call.arguments)).append("}}")
                    }
                    sb.append("]}")
                }
                else -> {
                    val role = if (m.role == MessageRole.TOOL) "user" else m.role.name.lowercase()
                    sb.append("{\"role\":").append(jsonString(role))
                        .append(",\"content\":").append(jsonString(m.content)).append("}")
                }
            }
        }
        sb.append("],\"temperature\":").append(request.temperature).append(",\"stream\":true")
        request.maxTokens?.let { sb.append(",\"max_tokens\":").append(it) }
        if (request.tools.isNotEmpty()) {
            sb.append(",\"tools\":")
            sb.append("[")
            request.tools.forEachIndexed { idx, t ->
                if (idx > 0) sb.append(",")
                sb.append("{\"type\":\"function\",\"function\":{\"name\":").append(jsonString(t.id)).append(",\"description\":").append(jsonString(t.description)).append(",\"parameters\":")
                sb.append(safeSchema(t.inputSchema))
                sb.append("}}")
            }
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }

    protected fun safeSchema(raw: String): String = runCatching {
        json.parseToJsonElement(raw.ifBlank { "{\"type\":\"object\",\"properties\":{}}" }).toString()
    }.getOrDefault("{\"type\":\"object\",\"properties\":{}}")

    protected fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) when (ch) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u${ch.code.toString(16).padStart(4, '0')}") else append(ch)
        }
        append('"')
    }

    protected fun mapHttpError(code: Int): ProviderError = when (code) {
        401, 403 -> ProviderError.AuthenticationError("$providerId authentication failed")
        429 -> ProviderError.RateLimitError()
        404 -> ProviderError.ModelNotFoundError(defaultModel)
        else -> ProviderError.ProviderError_(code, "$providerId HTTP $code")
    }

    protected fun mapError(e: Throwable): ProviderError = when (e) {
        is ProviderError -> e
        is IOException -> ProviderError.NetworkError(e.message ?: "Network failure")
        else -> ProviderError.ProviderError_(500, e.message ?: "$providerId error")
    }
}

@Serializable
private data class CompatibleModelList(val data: List<CompatibleModelEntry> = emptyList())

@Serializable
private data class CompatibleModelEntry(val id: String)

@Serializable
private data class CompatibleChunk(
    val choices: List<CompatibleChoice> = emptyList(),
    val usage: CompatibleUsage? = null,
)

@Serializable
private data class CompatibleChoice(
    val delta: CompatibleDelta = CompatibleDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class CompatibleDelta(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<CompatibleToolCall>? = null,
)

@Serializable
private data class CompatibleToolCall(
    val id: String? = null,
    val index: Int? = null,
    val function: CompatibleFunction? = null,
)

@Serializable
private data class CompatibleFunction(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
private data class CompatibleUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
)
