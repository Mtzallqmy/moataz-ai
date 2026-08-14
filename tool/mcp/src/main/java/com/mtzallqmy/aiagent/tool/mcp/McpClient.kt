package com.mtzallqmy.aiagent.tool.mcp

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** MCP 2025-03-26 client with bounded pagination, sessions, health, and reconnect. */
class McpClient(
    private val transport: McpTransport,
    private val clientName: String = "aegis-agent",
    private val clientVersion: String = "1.1.0",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestId = AtomicLong(1)
    private val lifecycle = Mutex()
    @Volatile private var initialized = false
    @Volatile private var sessionId: String? = null
    @Volatile var serverCapabilities: JsonObject? = null
        private set

    val isHealthy: Boolean get() = initialized

    suspend fun initialize(): Boolean = lifecycle.withLock {
        if (initialized) return true
        initializeLocked()
        true
    }

    suspend fun healthCheck(): Boolean = runCatching {
        executeWithReconnect { request("ping", JsonObject(emptyMap()), requireInitialized = true) }
        true
    }.getOrDefault(false)

    suspend fun listTools(): List<McpToolDescriptor> = paged("tools/list", "tools") { element ->
        val obj = element.jsonObject
        McpToolDescriptor(
            name = requiredString(obj, "name"),
            title = optionalString(obj, "title"),
            description = optionalString(obj, "description").orEmpty(),
            inputSchema = obj["inputSchema"] as? JsonObject
                ?: throw McpProtocolException.InvalidResponse("MCP tool inputSchema is missing"),
            outputSchema = obj["outputSchema"] as? JsonObject,
        )
    }

    /** Internal: external callers can execute only through McpRuntime registered tools. */
    internal suspend fun callTool(name: String, arguments: JsonObject): JsonObject =
        executeWithReconnect {
            request(
                "tools/call",
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("arguments", arguments)
                },
                requireInitialized = true,
            ).jsonObject
        }

    suspend fun listResources(): List<McpResource> = paged("resources/list", "resources") { element ->
        val obj = element.jsonObject
        McpResource(
            uri = requiredString(obj, "uri"),
            name = requiredString(obj, "name"),
            title = optionalString(obj, "title"),
            description = optionalString(obj, "description"),
            mimeType = optionalString(obj, "mimeType"),
        )
    }

    suspend fun readResource(uri: String): List<McpResourceContents> = executeWithReconnect {
        val result = request(
            "resources/read",
            buildJsonObject { put("uri", JsonPrimitive(uri)) },
            requireInitialized = true,
        ).jsonObject
        result["contents"]?.jsonArray?.map { element ->
            val obj = element.jsonObject
            McpResourceContents(
                uri = requiredString(obj, "uri"),
                mimeType = optionalString(obj, "mimeType"),
                text = optionalString(obj, "text"),
                blobBase64 = optionalString(obj, "blob"),
            ).also {
                if ((it.text == null) == (it.blobBase64 == null)) {
                    throw McpProtocolException.InvalidResponse("Resource content must contain exactly text or blob")
                }
            }
        } ?: throw McpProtocolException.InvalidResponse("Resource contents are missing")
    }

    suspend fun listPrompts(): List<McpPrompt> = paged("prompts/list", "prompts") { element ->
        val obj = element.jsonObject
        McpPrompt(
            name = requiredString(obj, "name"),
            title = optionalString(obj, "title"),
            description = optionalString(obj, "description"),
            arguments = (obj["arguments"] as? JsonArray).orEmpty().map {
                val argument = it.jsonObject
                McpPromptArgument(
                    name = requiredString(argument, "name"),
                    description = optionalString(argument, "description"),
                    required = argument["required"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                )
            },
        )
    }

    suspend fun getPrompt(name: String, arguments: JsonObject = JsonObject(emptyMap())): List<McpPromptMessage> =
        executeWithReconnect {
            val result = request(
                "prompts/get",
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("arguments", arguments)
                },
                requireInitialized = true,
            ).jsonObject
            result["messages"]?.jsonArray?.map {
                val message = it.jsonObject
                McpPromptMessage(
                    role = requiredString(message, "role"),
                    content = message["content"]
                        ?: throw McpProtocolException.InvalidResponse("Prompt message content is missing"),
                )
            } ?: throw McpProtocolException.InvalidResponse("Prompt messages are missing")
        }

    suspend fun close() = lifecycle.withLock {
        transport.closeSession(sessionId)
        reset()
    }

    private suspend fun initializeLocked() {
        val result = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", JsonPrimitive(PROTOCOL_VERSION))
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", JsonPrimitive(clientName))
                    put("version", JsonPrimitive(clientVersion))
                })
            },
            requireInitialized = false,
        ).jsonObject
        val negotiated = requiredString(result, "protocolVersion")
        if (negotiated != PROTOCOL_VERSION) {
            reset()
            throw McpProtocolException.InvalidResponse("Unsupported negotiated MCP version: $negotiated")
        }
        serverCapabilities = result["capabilities"] as? JsonObject ?: JsonObject(emptyMap())
        notify("notifications/initialized", JsonObject(emptyMap()))
        initialized = true
    }

    private suspend fun <T> paged(method: String, resultKey: String, mapper: (JsonElement) -> T): List<T> =
        executeWithReconnect {
            val output = mutableListOf<T>()
            var cursor: String? = null
            repeat(MAX_PAGES) {
                val result = request(
                    method,
                    cursor?.let { buildJsonObject { put("cursor", JsonPrimitive(it)) } }
                        ?: JsonObject(emptyMap()),
                    requireInitialized = true,
                ).jsonObject
                val page = result[resultKey] as? JsonArray
                    ?: throw McpProtocolException.InvalidResponse("$method response lacks $resultKey")
                output += page.map(mapper)
                cursor = optionalString(result, "nextCursor")
                if (cursor == null) return@executeWithReconnect output
            }
            throw McpProtocolException.InvalidResponse("$method exceeded pagination limit")
        }

    private suspend fun <T> executeWithReconnect(operation: suspend () -> T): T {
        var last: Throwable? = null
        repeat(MAX_RECONNECT_ATTEMPTS + 1) { attempt ->
            try {
                if (!initialized) initialize()
                return operation()
            } catch (error: McpProtocolException.PermissionDenied) {
                throw error
            } catch (error: McpProtocolException.Rpc) {
                throw error
            } catch (error: Throwable) {
                last = error
                lifecycle.withLock {
                    transport.closeSession(sessionId)
                    reset()
                }
                if (attempt < MAX_RECONNECT_ATTEMPTS) delay(250L shl attempt)
            }
        }
        throw McpProtocolException.Transport("MCP reconnect attempts exhausted", last)
    }

    private suspend fun request(method: String, params: JsonObject, requireInitialized: Boolean): JsonElement {
        if (requireInitialized && !initialized) {
            throw McpProtocolException.Transport("MCP session is not initialized")
        }
        val id = requestId.getAndIncrement()
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
        val response = transport.send(payload.toString(), sessionId)
        updateSession(response)
        if (response.statusCode == 401 || response.statusCode == 403) {
            throw McpProtocolException.PermissionDenied("MCP authentication was rejected")
        }
        if (response.statusCode !in 200..299) {
            throw McpProtocolException.Transport("MCP HTTP ${response.statusCode}")
        }
        val message = parseResponse(response.body, id)
        message["error"]?.jsonObject?.let { error ->
            throw McpProtocolException.Rpc(
                error["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -32603,
                optionalString(error, "message") ?: "Unknown server error",
            )
        }
        return message["result"] ?: throw McpProtocolException.InvalidResponse("MCP response result is missing")
    }

    private suspend fun notify(method: String, params: JsonObject) {
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
        val response = transport.send(payload.toString(), sessionId)
        updateSession(response)
        if (response.statusCode !in 200..299 && response.statusCode != 202) {
            throw McpProtocolException.Transport("MCP notification HTTP ${response.statusCode}")
        }
    }

    private fun parseResponse(raw: String, expectedId: Long): JsonObject {
        val candidates = if (raw.trimStart().startsWith("event:") || raw.contains("\ndata:")) {
            raw.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
        } else {
            sequenceOf(raw.trim())
        }
        return candidates.mapNotNull { encoded ->
            runCatching { json.parseToJsonElement(encoded) as? JsonObject }.getOrNull()
        }.firstOrNull { obj ->
            obj["jsonrpc"]?.jsonPrimitive?.contentOrNull == "2.0" &&
                obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() == expectedId
        } ?: throw McpProtocolException.InvalidResponse("No MCP response matched request $expectedId")
    }

    private fun updateSession(response: McpTransportResponse) {
        response.sessionId?.let {
            if (!it.matches(Regex("[\\x21-\\x7E]{1,256}"))) {
                throw McpProtocolException.InvalidResponse("MCP session ID is invalid")
            }
            if (sessionId != null && sessionId != it) {
                throw McpProtocolException.InvalidResponse("MCP server changed session ID unexpectedly")
            }
            sessionId = it
        }
    }

    private fun reset() {
        initialized = false
        sessionId = null
        serverCapabilities = null
    }

    private fun requiredString(obj: JsonObject, key: String): String =
        optionalString(obj, key)?.takeIf(String::isNotBlank)
            ?: throw McpProtocolException.InvalidResponse("Required MCP field is missing: $key")

    private fun optionalString(obj: JsonObject, key: String): String? =
        obj[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

    private companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        const val MAX_PAGES = 100
        const val MAX_RECONNECT_ATTEMPTS = 2
    }
}
