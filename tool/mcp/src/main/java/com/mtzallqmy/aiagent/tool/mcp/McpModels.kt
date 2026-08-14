package com.mtzallqmy.aiagent.tool.mcp

import com.mtzallqmy.aiagent.model.RiskLevel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class McpToolDescriptor(
    val name: String,
    val title: String? = null,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
)

data class McpResource(
    val uri: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
)

data class McpResourceContents(
    val uri: String,
    val mimeType: String?,
    val text: String? = null,
    val blobBase64: String? = null,
)

data class McpPrompt(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val arguments: List<McpPromptArgument> = emptyList(),
)

data class McpPromptArgument(val name: String, val description: String?, val required: Boolean)
data class McpPromptMessage(val role: String, val content: JsonElement)

data class McpServerPermissions(
    val enabled: Boolean = false,
    /** A tool is invisible and uncallable unless it has an explicit risk entry. */
    val allowedTools: Map<String, RiskLevel> = emptyMap(),
    val resourcesAllowed: Boolean = false,
    val promptsAllowed: Boolean = false,
)

data class McpServerConfiguration(
    val serverId: String,
    val endpoint: String,
    val permissions: McpServerPermissions,
) {
    init {
        require(serverId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
    }
}

sealed class McpProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Transport(message: String, cause: Throwable? = null) : McpProtocolException(message, cause)
    class Rpc(val code: Int, message: String) : McpProtocolException("MCP RPC $code: $message")
    class InvalidResponse(message: String) : McpProtocolException(message)
    class PermissionDenied(message: String) : McpProtocolException(message)
}
