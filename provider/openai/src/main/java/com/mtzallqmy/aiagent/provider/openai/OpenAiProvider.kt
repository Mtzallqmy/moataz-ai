package com.mtzallqmy.aiagent.provider.openai

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Real OpenAI provider: chat completions with Server-Sent Events streaming
 * and function-calling tool use. Wire format is normalized to GenerationEvents.
 */
class OpenAiProvider(
    private val apiKeyProvider: suspend () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val defaultModel: String = "gpt-4o-mini",
    private val client: OkHttpClient = SafeHttpClient.create(),
) : AiProvider {
    override val providerId = "openai"
    override val name = "OpenAI"

    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun listModels(): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val key = apiKeyProvider() ?: throw ProviderError.AuthenticationError("No OpenAI API key")
            val request = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", "Bearer $key")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val raw = resp.body?.string() ?: ""
                val envelope = json.decodeFromString<ModelListEnvelope>(raw)
                val models = envelope.data.map { m ->
                    AiModel(
                        id = m.id,
                        name = m.id,
                        providerId = providerId,
                        capabilities = mapCapabilities(m.id),
                        routing = mapRouting(m.id),
                    )
                }
                Result.success(models)
            }
        } catch (e: Throwable) { Result.failure(mapError(e)) }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKeyProvider() ?: throw ProviderError.AuthenticationError("No API key configured")
            val request = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", "Bearer $key")
                .build()
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    in 200..299 -> Unit
                    401, 403 -> throw ProviderError.AuthenticationError("Invalid API key")
                    429 -> throw ProviderError.RateLimitError()
                    404 -> throw ProviderError.ModelNotFoundError(defaultModel)
                    else -> throw ProviderError.ProviderError_(resp.code, "OpenAI request failed")
                }
            }
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = callbackFlow {
        val key = apiKeyProvider() ?: run {
            trySend(GenerationEvent.GenerationFailed(ProviderError.AuthenticationError("No API key configured")))
            close()
            return@callbackFlow
        }
        val payload = buildPayload(request)
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!call.isCanceled()) {
                    trySend(GenerationEvent.GenerationFailed(mapError(error)))
                }
                close()
            }

            override fun onResponse(call: Call, response: Response) {
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
                                val chunk = runCatching { json.decodeFromString<ChatCompletionChunk>(data) }
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
                                        ?: "openai-tool-$index".also { toolIdsByIndex[index] = it }
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
                        if (!call.isCanceled()) {
                            trySend(GenerationEvent.GenerationFailed(mapError(error)))
                        }
                    } finally {
                        close()
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    private fun buildPayload(request: GenerationRequest): String = buildJsonObject {
        put("model", request.modelId.ifEmpty { defaultModel })
        put("messages", buildJsonArray {
            request.messages.forEach { message ->
                add(buildJsonObject {
                    when {
                        message.role == MessageRole.TOOL && !message.toolCallId.isNullOrBlank() -> {
                            put("role", "tool")
                            put("tool_call_id", message.toolCallId)
                            put("content", message.content)
                        }
                        message.role == MessageRole.ASSISTANT && message.toolCalls.isNotEmpty() -> {
                            put("role", "assistant")
                            put("content", message.content)
                            put("tool_calls", buildJsonArray {
                                message.toolCalls.forEach { call ->
                                    add(buildJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", call.name)
                                            put("arguments", call.arguments)
                                        })
                                    })
                                }
                            })
                        }
                        else -> {
                            // A legacy TOOL message without a call id cannot be validly
                            // correlated by OpenAI, so degrade it to user context.
                            put("role", if (message.role == MessageRole.TOOL) "user" else message.role.name.lowercase())
                            put("content", message.content)
                        }
                    }
                })
            }
        })
        put("temperature", JsonPrimitive(request.temperature))
        put("stream", true)
        // OpenAI only returns aggregate usage for streamed chat completions when
        // stream_options.include_usage is requested.
        put("stream_options", buildJsonObject { put("include_usage", true) })
        request.maxTokens?.let { put("max_tokens", it) }
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.id)
                            put("description", tool.description)
                            put("parameters", parseToolSchema(tool.inputSchema))
                        })
                    })
                }
            })
        }
    }.toString()

    private fun parseToolSchema(raw: String): JsonElement = runCatching {
        json.parseToJsonElement(raw.ifBlank { DEFAULT_TOOL_SCHEMA })
    }.getOrElse {
        json.parseToJsonElement(DEFAULT_TOOL_SCHEMA)
    }

    private fun mapCapabilities(modelId: String): ModelCapabilities = ModelCapabilities(
        chat = true,
        streaming = true,
        toolCalling = true,
        parallelToolCalling = modelId.contains("gpt-4", ignoreCase = true),
        vision = modelId.contains("gpt-4o", ignoreCase = true) || modelId.contains("gpt-4.1", ignoreCase = true),
        jsonMode = true,
        contextWindow = when {
            modelId.startsWith("gpt-4o") -> 128_000
            modelId.startsWith("gpt-4.1") -> 1_048_576
            modelId.startsWith("gpt-4") -> 128_000
            modelId.startsWith("gpt-3.5") -> 16_385
            else -> 128_000
        },
    )

    private fun mapRouting(modelId: String) = ModelRoutingMetadata(
        speedTier = when {
            modelId.contains("nano", true) || modelId.contains("mini", true) -> ModelSpeedTier.FAST
            modelId.contains("pro", true) -> ModelSpeedTier.QUALITY
            else -> ModelSpeedTier.BALANCED
        },
        codingOptimized = modelId.contains("codex", true) || modelId.contains("code", true),
    )

    private fun mapHttpError(code: Int): ProviderError = when (code) {
        401, 403 -> ProviderError.AuthenticationError("OpenAI authentication failed")
        429 -> ProviderError.RateLimitError()
        404 -> ProviderError.ModelNotFoundError(defaultModel)
        else -> ProviderError.ProviderError_(code, "OpenAI HTTP $code")
    }

    private fun mapError(e: Throwable): ProviderError = when (e) {
        is ProviderError -> e
        is java.io.IOException -> ProviderError.NetworkError(e.message ?: "Network failure")
        else -> ProviderError.ProviderError_(500, e.message ?: "Unknown OpenAI error")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_TOOL_SCHEMA = "{\"type\":\"object\",\"properties\":{}}"
    }
}

@Serializable
private data class ModelListEnvelope(val data: List<ModelEntry> = emptyList())

@Serializable
private data class ModelEntry(val id: String)

@Serializable
private data class ChatCompletionChunk(
    val choices: List<ChunkChoice> = emptyList(),
    val usage: ChunkUsage? = null,
)

@Serializable
private data class ChunkChoice(
    val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class ChunkDelta(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChunkToolCall>? = null,
)

@Serializable
private data class ChunkToolCall(
    val index: Int? = null,
    val id: String? = null,
    val function: ChunkFunction? = null,
)

@Serializable
private data class ChunkFunction(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
private data class ChunkUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
)
