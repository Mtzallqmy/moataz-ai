package com.mtzallqmy.aiagent.tool.http

import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.network.SafeHttpClient
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.RegisteredTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Real HTTP tools: http.get/post/put/patch/delete/head — network calls actually
 * performed through SafeHttpClient (SSRF-protected). Responses are bounded.
 */
class HttpToolSet(
    private val maxResponseChars: Int = 30_000,
    private val allowedSchemes: Set<String> = setOf("https", "http"),
) {
    private val client = SafeHttpClient.create()

    val tools: List<RegisteredTool> = listOf(
        "http.get" to "GET",
        "http.post" to "POST",
        "http.put" to "PUT",
        "http.patch" to "PATCH",
        "http.delete" to "DELETE",
        "http.head" to "HEAD",
    ).map { (id, method) ->
        RegisteredTool.typed(HttpMethodTool(id, method), HttpRequestInput.serializer())
    }

    private inner class HttpMethodTool(
        overrideId: String,
        private val method: String,
    ) : AgentTool<HttpRequestInput, JsonObject> {
        override val descriptor = ToolDescriptor(
            id = overrideId, displayName = method,
            description = "Perform an HTTP $method request against a remote API",
            inputSchema = """{"type":"object","required":["url"],"properties":{"url":{"type":"string"},"headers":{"type":"object"},"body":{"type":"string"}}}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.COMMUNICATION, requiredCapabilities = setOf(CapabilityId("network")), timeoutMs = 30_000L,
        )
        override suspend fun availability(context: ToolContext) = ToolAvailability.Available
        override suspend fun execute(input: HttpRequestInput, context: ToolContext): JsonObject = withContext(Dispatchers.IO) {
            validateUrl(input.url)
            val request = Request.Builder().url(input.url).apply {
                input.headers.forEach { (k, v) -> header(k, v) }
                when (method) {
                    "GET", "HEAD" -> method
                    "POST" -> post((input.body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
                    "PUT" -> put((input.body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
                    "PATCH" -> patch((input.body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
                    "DELETE" -> delete()
                    else -> get()
                }
            }.build()
            client.newCall(request).execute().use { resp ->
                val responseHeaders = resp.headers.toMultimap().entries.take(12).map { (k, v) -> "$k: ${v.take(100)}" }
                val responseBody = if (method == "HEAD") "" else {
                    resp.body?.string()?.take(maxResponseChars) ?: ""
                }
                buildJsonObject {
                    put("status_code", JsonPrimitive(resp.code))
                    put("headers", JsonArray(responseHeaders.map { JsonPrimitive(it) }))
                    put("body", JsonPrimitive(responseBody))
                }
            }
        }
    }

    private fun validateUrl(url: String) {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme !in allowedSchemes) error("Scheme not allowed: $scheme")
        if (url.isBlank()) error("Empty URL")
    }
}

@Serializable
data class HttpRequestInput(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)
