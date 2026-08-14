package com.mtzallqmy.aiagent.providers

import com.mtzallqmy.aiagent.model.AiModel
import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.GenerationRequest
import com.mtzallqmy.aiagent.model.ModelCapabilities
import com.mtzallqmy.aiagent.model.ProviderError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Dify remote backend — concepts studied from the Dify API (Modified
 * Apache-2.0 for its server; clean-room client adapter): invokes Dify
 * workflows/chatflows via the chat-messages API with RAG support.
 *
 * Opt-in: requires a user-configured Dify API URL + key.
 */
class DifyRemoteBackend(
    private val baseUrlProvider: suspend () -> String?,
    private val apiKeyProvider: suspend () -> String?,
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val models: List<AiModel> = listOf(
        AiModel(
            id = "dify_workflow",
            name = "Dify Workflow",
            providerId = "dify",
            capabilities = ModelCapabilities(
                streaming = false,
                toolCalling = true,
                vision = false,
            ),
        ),
    )

    suspend fun isAvailable(): Boolean {
        val base = baseUrlProvider()
        val key = apiKeyProvider()
        return !base.isNullOrBlank() && !key.isNullOrBlank()
    }

    /**
     * chat-messages endpoint (workflow/chatflow mode). Blocking response
     * mode is wrapped as a single completed generation event.
     */
    suspend fun generate(request: GenerationRequest): Flow<GenerationEvent> {
        val base = baseUrlProvider()
            ?: return flowOf(GenerationEvent.GenerationFailed(
                ProviderError.ProviderError_(statusCode = 0, reason = "Dify base URL not configured"),
            ))
        val key = apiKeyProvider()
            ?: return flowOf(GenerationEvent.GenerationFailed(
                ProviderError.ProviderError_(statusCode = 0, reason = "Dify API key not configured"),
            ))

        val latest = request.messages.lastOrNull()
            ?: return flowOf(GenerationEvent.GenerationFailed(
                ProviderError.ProviderError_(statusCode = 0, reason = "No message to send"),
            ))

        val payload = buildJsonObject {
            put("inputs", buildJsonObject {})
            put("query", JsonPrimitive(latest.content))
            put("user", JsonPrimitive(UUID.randomUUID().toString()))
            put("response_mode", JsonPrimitive("blocking"))
        }

        val httpResult = withContext(Dispatchers.IO) {
            val httpReq = Request.Builder()
                .url("$base/v1/chat-messages")
                .header("Authorization", "Bearer $key")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            runCatching {
                httpClient.newCall(httpReq).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    when {
                        response.isSuccessful ->
                            json.parseToJsonElement(body).jsonObject["answer"]
                                ?.jsonPrimitive?.content ?: body
                        response.code == 401 || response.code == 429 -> null
                        else -> null
                    }
                }
            }
        }

        val answer = httpResult.getOrNull()
        return if (answer != null) {
            flow { emit(GenerationEvent.GenerationCompleted(answer)) }
        } else {
            flowOf(GenerationEvent.GenerationFailed(
                ProviderError.ProviderError_(
                    statusCode = 0,
                    reason = httpResult.exceptionOrNull()?.message ?: "Dify request failed",
                ),
            ))
        }
    }
}
