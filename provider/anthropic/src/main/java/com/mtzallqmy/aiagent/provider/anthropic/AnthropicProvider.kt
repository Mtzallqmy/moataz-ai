package com.mtzallqmy.aiagent.provider.anthropic

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
 * Real Anthropic Messages API provider with SSE streaming and tool use.
 * Supports claude-opus-4-7 and other models configured by the user.
 */
class AnthropicProvider(
    private val apiKeyProvider: suspend () -> String?,
    private val apiVersion: String = "2023-06-01",
    private val defaultModel: String = "claude-opus-4-8",
) : AiProvider {

    override val providerId: String = "anthropic"
    override val name: String = "Anthropic (Claude)"

    private val client = SafeHttpClient.create()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listModels(): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val key = apiKeyProvider() ?: throw ProviderError.AuthenticationError("No Anthropic API key")
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/models?limit=1000")
                .header("x-api-key", key)
                .header("anthropic-version", apiVersion)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw mapHttpError(resp.code)
                val models = json.decodeFromString<AnthropicModelList>(resp.body?.string() ?: "{}").data.map { m ->
                    AiModel(
                        id = m.id,
                        name = m.displayName ?: m.id,
                        providerId = providerId,
                        capabilities = ModelCapabilities(
                            chat = true,
                            streaming = true,
                            toolCalling = true,
                            reasoning = m.id.contains("opus", ignoreCase = true) || m.id.contains("sonnet", ignoreCase = true),
                            contextWindow = m.maxInputTokens ?: 200_000,
                            maxOutputTokens = m.maxTokens ?: 16_000,
                        ),
                        routing = ModelRoutingMetadata(
                            speedTier = when {
                                m.id.contains("haiku", true) -> ModelSpeedTier.FAST
                                m.id.contains("opus", true) -> ModelSpeedTier.QUALITY
                                else -> ModelSpeedTier.BALANCED
                            },
                        ),
                    )
                }
                Result.success(models)
            }
        } catch (e: Throwable) {
            Result.failure(mapError(e))
        }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val key = apiKeyProvider() ?: throw ProviderError.AuthenticationError("No Anthropic API key")
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", key)
                .header("anthropic-version", apiVersion)
                .header("Content-Type", "application/json")
                .post(("{\"model\":\"" + defaultModel + "\",\"max_tokens\":5,\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"stream\":false}").toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> Result.success(Unit)
                    resp.code in 401..403 -> throw ProviderError.AuthenticationError("Invalid Anthropic key")
                    resp.code == 429 -> throw ProviderError.RateLimitError()
                    resp.code == 404 -> throw ProviderError.ModelNotFoundError(defaultModel)
                    else -> throw ProviderError.ProviderError_(resp.code, "Anthropic error")
                }
            }
        } catch (e: Throwable) {
            Result.failure(mapError(e))
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = callbackFlow {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            trySend(GenerationEvent.GenerationFailed(ProviderError.AuthenticationError("No Anthropic API key")))
            close()
            return@callbackFlow
        }
        val payload = buildPayload(request)
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", apiVersion)
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
                    val toolIdsByIndex = mutableMapOf<Int, String>()
                    var inputTokensReported = 0
                    var outputTokensReported = 0
                    fun emitUsage(inputTotal: Int?, outputTotal: Int?) {
                        val input = inputTotal ?: inputTokensReported
                        val output = outputTotal ?: outputTokensReported
                        val inputDelta = (input - inputTokensReported).coerceAtLeast(0)
                        val outputDelta = (output - outputTokensReported).coerceAtLeast(0)
                        if (inputDelta > 0 || outputDelta > 0) {
                            trySend(GenerationEvent.Usage(inputDelta, outputDelta))
                        }
                        inputTokensReported = maxOf(inputTokensReported, input)
                        outputTokensReported = maxOf(outputTokensReported, output)
                    }
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
                                val event = runCatching { json.decodeFromString<AnthropicStreamEvent>(data) }
                                    .getOrNull() ?: continue
                                when (event.type) {
                                    "message_start" -> event.message?.usage?.let { usage ->
                                        emitUsage(usage.inputTokens, usage.outputTokens)
                                    }
                                    "content_block_start" -> when (event.contentBlock?.type) {
                                        "text" -> event.contentBlock.text?.takeIf { it.isNotEmpty() }?.let {
                                            trySend(GenerationEvent.TextDelta(it))
                                        }
                                        "tool_use" -> {
                                            val id = event.contentBlock.id.orEmpty().ifBlank { "anthropic-tool-${event.index}" }
                                            toolIdsByIndex[event.index] = id
                                            val name = event.contentBlock.name.orEmpty()
                                            if (name.isNotBlank()) {
                                                trySend(GenerationEvent.ToolCallStarted(id, name))
                                            }
                                        }
                                        else -> Unit
                                    }
                                    "content_block_delta" -> {
                                        event.delta?.text?.takeIf { it.isNotEmpty() }?.let {
                                            trySend(GenerationEvent.TextDelta(it))
                                        }
                                        event.delta?.partialJson?.takeIf { it.isNotEmpty() }?.let { fragment ->
                                            val id = toolIdsByIndex[event.index] ?: "anthropic-tool-${event.index}"
                                            trySend(GenerationEvent.ToolCallArgumentsDelta(id, fragment))
                                        }
                                    }
                                    "message_delta" -> {
                                        event.usage?.let { usage ->
                                            emitUsage(usage.inputTokens, usage.outputTokens)
                                        }
                                        if (event.delta?.stopReason != null) completeOnce()
                                    }
                                    "message_stop" -> completeOnce()
                                    "error" -> {
                                        terminalSent = true
                                        trySend(
                                            GenerationEvent.GenerationFailed(
                                                ProviderError.ProviderError_(
                                                    event.error?.code?.toIntOrNull() ?: 500,
                                                    event.error?.message ?: "Anthropic error",
                                                ),
                                            ),
                                        )
                                        break
                                    }
                                }
                            }
                        }
                        if (!call.isCanceled() && !terminalSent) completeOnce()
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
        sb.append("{\"model\":").append(jsonString(request.modelId.ifEmpty { defaultModel })).append(",\"max_tokens\":").append(request.maxTokens ?: 4096).append(",\"stream\":true")
        system?.let { sb.append(",\"system\":").append(jsonString(it)) }
        sb.append(",\"messages\":")
        sb.append("[")
        chatMessages.forEachIndexed { idx, m ->
            if (idx > 0) sb.append(",")
            when {
                m.role == MessageRole.TOOL && !m.toolCallId.isNullOrBlank() -> {
                    sb.append("{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":")
                        .append(jsonString(m.toolCallId))
                        .append(",\"content\":").append(jsonString(m.content)).append("}]}")
                }
                m.role == MessageRole.ASSISTANT && m.toolCalls.isNotEmpty() -> {
                    sb.append("{\"role\":\"assistant\",\"content\":[")
                    var contentIndex = 0
                    if (m.content.isNotBlank()) {
                        sb.append("{\"type\":\"text\",\"text\":").append(jsonString(m.content)).append("}")
                        contentIndex++
                    }
                    m.toolCalls.forEach { call ->
                        if (contentIndex++ > 0) sb.append(",")
                        sb.append("{\"type\":\"tool_use\",\"id\":").append(jsonString(call.id))
                            .append(",\"name\":").append(jsonString(call.name))
                            .append(",\"input\":").append(safeArguments(call.arguments)).append("}")
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
        sb.append("]")
        if (request.tools.isNotEmpty()) {
            sb.append(",\"tools\":")
            sb.append("[")
            request.tools.forEachIndexed { idx, t ->
                if (idx > 0) sb.append(",")
                sb.append("{\"name\":").append(jsonString(t.id)).append(",\"description\":").append(jsonString(t.description)).append(",\"input_schema\":")
                sb.append(safeSchema(t.inputSchema))
                sb.append("}")
            }
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun safeSchema(raw: String): String = runCatching {
        json.parseToJsonElement(raw.ifBlank { "{\"type\":\"object\",\"properties\":{}}" }).toString()
    }.getOrDefault("{\"type\":\"object\",\"properties\":{}}")

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
        401, 403 -> ProviderError.AuthenticationError("Anthropic authentication failed")
        429 -> ProviderError.RateLimitError()
        404 -> ProviderError.ModelNotFoundError(defaultModel)
        else -> ProviderError.ProviderError_(code, "Anthropic HTTP $code")
    }

    private fun mapError(e: Throwable): ProviderError = when (e) {
        is ProviderError -> e
        is IOException -> ProviderError.NetworkError(e.message ?: "Network failure")
                else -> ProviderError.ProviderError_(500, e.message ?: "Anthropic error")
    }
}

@Serializable
private data class AnthropicModelList(val data: List<AnthropicModelEntry> = emptyList())

@Serializable
private data class AnthropicModelEntry(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("max_input_tokens") val maxInputTokens: Int? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
private data class AnthropicStreamEvent(
    val type: String = "",
    val index: Int = 0,
    @SerialName("content_block") val contentBlock: AnthropicBlock? = null,
    val message: AnthropicMessage? = null,
    val delta: AnthropicDelta? = null,
    val usage: AnthropicUsage? = null,
    val error: AnthropicError? = null,
)

@Serializable
private data class AnthropicMessage(
    val usage: AnthropicUsage? = null,
)

@Serializable
private data class AnthropicBlock(
    val type: String? = null,
    val id: String? = null,
    val name: String? = null,
    val text: String? = null,
)

@Serializable
private data class AnthropicDelta(
    val type: String? = null,
    val text: String? = null,
    @SerialName("partial_json") val partialJson: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
)

@Serializable
private data class AnthropicError(
    val type: String? = null,
    val code: String? = null,
    val message: String? = null,
)
