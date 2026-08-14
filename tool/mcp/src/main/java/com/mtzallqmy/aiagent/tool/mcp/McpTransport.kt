package com.mtzallqmy.aiagent.tool.mcp

import com.mtzallqmy.aiagent.network.SafeHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class McpTransportResponse(
    val statusCode: Int,
    val body: String,
    val sessionId: String?,
)

interface McpTransport {
    suspend fun send(payload: String, sessionId: String?): McpTransportResponse
    suspend fun closeSession(sessionId: String?)
}

fun interface McpAuthentication {
    suspend fun authorizationHeaders(): Map<String, String>

    companion object {
        val NONE = McpAuthentication { emptyMap() }
        fun bearer(tokenProvider: suspend () -> String?) = McpAuthentication {
            tokenProvider()?.takeIf(String::isNotBlank)?.let { mapOf("Authorization" to "Bearer $it") }
                ?: emptyMap()
        }
    }
}

class StreamableHttpMcpTransport(
    private val endpoint: String,
    private val authentication: McpAuthentication = McpAuthentication.NONE,
    private val staticHeaders: Map<String, String> = emptyMap(),
) : McpTransport {
    private val client = SafeHttpClient.create(timeoutMs = 60_000)

    init {
        require(endpoint.startsWith("https://") && SafeHttpClient.normalizeUrl(endpoint) == endpoint) {
            "MCP endpoint must be a normalized public HTTPS URL"
        }
        require(staticHeaders.keys.none { it.equals("Authorization", true) || it.equals("Mcp-Session-Id", true) }) {
            "Authentication and session headers cannot be static"
        }
    }

    override suspend fun send(payload: String, sessionId: String?): McpTransportResponse =
        withContext(Dispatchers.IO) {
            require(payload.toByteArray().size <= MAX_REQUEST_BYTES) { "MCP request is too large" }
            val request = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .apply {
                    staticHeaders.forEach { (name, value) -> header(name, value) }
                    authentication.authorizationHeaders().forEach { (name, value) -> header(name, value) }
                    sessionId?.let { header("Mcp-Session-Id", it) }
                }
                .post(payload.toRequestBody(JSON))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body
                    val declared = responseBody?.contentLength() ?: 0L
                    if (declared > MAX_RESPONSE_BYTES) {
                        throw McpProtocolException.InvalidResponse("MCP response is too large")
                    }
                    val body = responseBody?.source()?.readUtf8(MAX_RESPONSE_BYTES + 1).orEmpty()
                    if (body.toByteArray().size > MAX_RESPONSE_BYTES) {
                        throw McpProtocolException.InvalidResponse("MCP response is too large")
                    }
                    McpTransportResponse(
                        statusCode = response.code,
                        body = body,
                        sessionId = response.header("Mcp-Session-Id"),
                    )
                }
            } catch (error: Exception) {
                throw McpProtocolException.Transport("MCP HTTP request failed", error)
            }
        }

    override suspend fun closeSession(sessionId: String?) {
        if (sessionId == null) return
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint)
                .apply {
                    authentication.authorizationHeaders().forEach { (name, value) -> header(name, value) }
                    header("Mcp-Session-Id", sessionId)
                }
                .delete()
                .build()
            runCatching { client.newCall(request).execute().close() }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
    }
}
