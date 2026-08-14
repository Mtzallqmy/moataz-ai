package com.mtzallqmy.aiagent.tool.http

import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.model.ToolDescriptor
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.RegisteredTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI

/**
 * n8n adapter — concepts studied from n8n (Sustainable Use license;
 * clean-room REST integration): trigger a remote n8n workflow via webhook
 * and fetch its latest execution result via the n8n public API.
 *
 * Requires a user-configured n8n instance (baseUrl + API key). Local-only
 * webhook triggers (agent fires a workflow) are always safe; execution
 * listing requires the API key.
 */
class N8nAdapter(
    private val baseUrlProvider: suspend () -> String?,
    private val apiKeyProvider: suspend () -> String?,
    private val httpClient: OkHttpClient,
) {
    /** Webhook trigger tool — fires a workflow synchronously-ish (POST). */
    private val triggerWorkflowAgentTool = object : AgentTool<N8nTriggerInput, JsonObject> {
        override val descriptor = ToolDescriptor(
            id = "n8n.trigger_workflow",
            displayName = "Trigger n8n Workflow",
            description = "Send a webhook payload to a remote n8n workflow",
            inputSchema = """{"type":"object","required":["webhookUrl"],"properties":{"webhookUrl":{"type":"string"},"payload":{"type":"object"}}}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.COMMUNICATION,
            requiredCapabilities = setOf(CapabilityId("http")),
            timeoutMs = 30_000L,
        )

        override suspend fun availability(context: ToolContext): ToolAvailability {
            return if (baseUrlProvider() != null) ToolAvailability.Available
            else ToolAvailability.Unavailable("n8n base URL not configured")
        }

        override suspend fun execute(input: N8nTriggerInput, context: ToolContext): JsonObject {
            val configuredBase = baseUrlProvider() ?: error("n8n base URL not configured")
            validateWebhookOrigin(input.webhookUrl, configuredBase)
            return withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(input.webhookUrl)
                    .post(input.payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    buildJsonObject {
                        put("status_code", JsonPrimitive(response.code))
                        put("response", JsonPrimitive(body.take(10_000)))
                    }
                }
            }
        }
    }

    val triggerWorkflowTool: RegisteredTool =
        RegisteredTool.typed(triggerWorkflowAgentTool, N8nTriggerInput.serializer())

    private fun validateWebhookOrigin(webhookUrl: String, configuredBase: String) {
        val webhook = URI(webhookUrl)
        val base = URI(configuredBase)
        require(!webhook.host.isNullOrBlank() && !base.host.isNullOrBlank()) { "Absolute n8n URLs are required" }
        require(webhook.scheme in setOf("https", "http")) { "Unsupported n8n webhook scheme" }
        require(webhook.scheme.equals(base.scheme, ignoreCase = true)) { "Webhook scheme does not match configured n8n origin" }
        require(webhook.host.equals(base.host, ignoreCase = true)) { "Webhook host does not match configured n8n origin" }
        val webhookPort = if (webhook.port == -1) defaultPort(webhook.scheme) else webhook.port
        val basePort = if (base.port == -1) defaultPort(base.scheme) else base.port
        require(webhookPort == basePort) { "Webhook port does not match configured n8n origin" }
    }

    private fun defaultPort(scheme: String?): Int = if (scheme.equals("https", ignoreCase = true)) 443 else 80

    /** Fetch the latest execution status of a workflow via the n8n API. */
    suspend fun latestExecution(workflowId: String): JsonElement? {
        val base = baseUrlProvider() ?: return null
        val key = apiKeyProvider() ?: return null
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$base/api/v1/executions?workflowId=$workflowId&limit=1")
                .header("X-N8N-API-KEY", key)
                .get()
                .build()
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    Json.parseToJsonElement(response.body?.string() ?: "{}")
                }
            }.getOrNull()
        }
    }

    /** Check the adapter is configured. */
    suspend fun isConfigured(): Boolean = baseUrlProvider() != null
}

@Serializable
data class N8nTriggerInput(
    val webhookUrl: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)
