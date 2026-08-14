package com.mtzallqmy.aiagent.tool.mcp

import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.model.ToolDescriptor
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.RegisteredTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import com.mtzallqmy.aiagent.tools.TypedToolRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

data class McpConnection(
    val serverId: String,
    val toolIds: Set<String>,
    val healthy: Boolean,
)

/** Owns MCP sessions and exposes remote calls only as RegisteredTools. */
class McpRuntime(
    private val registry: TypedToolRegistry,
    private val clientFactory: (McpServerConfiguration, McpAuthentication) -> McpClient,
) {
    private data class Session(
        val configuration: McpServerConfiguration,
        val client: McpClient,
        val toolIds: Set<String>,
        val authentication: McpAuthentication,
    )

    private val lock = Mutex()
    private val sessions = linkedMapOf<String, Session>()

    suspend fun connect(
        configuration: McpServerConfiguration,
        authentication: McpAuthentication = McpAuthentication.NONE,
    ): McpConnection = lock.withLock {
        require(configuration.permissions.enabled) { "MCP server is disabled" }
        check(sessions[configuration.serverId] == null) { "MCP server is already connected" }
        val client = clientFactory(configuration, authentication)
        try {
            client.initialize()
            val allowed = client.listTools().filter {
                configuration.permissions.allowedTools.containsKey(it.name)
            }
            val registered = mutableListOf<String>()
            try {
                allowed.forEach { remote ->
                    validateRemoteName(remote.name)
                    val id = toolId(configuration.serverId, remote.name)
                    val risk = requireNotNull(configuration.permissions.allowedTools[remote.name])
                    registry.register(
                        RegisteredTool.typed(
                            McpRegisteredTool(id, remote, risk, client),
                            JsonObject.serializer(),
                        ),
                    )
                    registered += id
                }
            } catch (error: Throwable) {
                registered.forEach(registry::unregister)
                throw error
            }
            val session = Session(configuration, client, registered.toSet(), authentication)
            sessions[configuration.serverId] = session
            McpConnection(configuration.serverId, session.toolIds, client.isHealthy)
        } catch (error: Throwable) {
            client.close()
            throw error
        }
    }

    suspend fun disconnect(serverId: String): Boolean {
        val session = lock.withLock { sessions.remove(serverId) } ?: return false
        session.toolIds.forEach(registry::unregister)
        session.client.close()
        return true
    }

    suspend fun reconnect(serverId: String): McpConnection {
        val previous = lock.withLock { sessions[serverId] }
            ?: throw IllegalArgumentException("MCP server is not connected: $serverId")
        disconnect(serverId)
        return connect(previous.configuration, previous.authentication)
    }

    suspend fun healthCheck(serverId: String): Boolean = session(serverId).client.healthCheck()

    suspend fun listResources(serverId: String): List<McpResource> {
        val session = session(serverId)
        require(session.configuration.permissions.resourcesAllowed) { "MCP resources permission is denied" }
        return session.client.listResources()
    }

    suspend fun readResource(serverId: String, uri: String): List<McpResourceContents> {
        val session = session(serverId)
        require(session.configuration.permissions.resourcesAllowed) { "MCP resources permission is denied" }
        return session.client.readResource(uri)
    }

    suspend fun listPrompts(serverId: String): List<McpPrompt> {
        val session = session(serverId)
        require(session.configuration.permissions.promptsAllowed) { "MCP prompts permission is denied" }
        return session.client.listPrompts()
    }

    suspend fun getPrompt(
        serverId: String,
        name: String,
        arguments: JsonObject = JsonObject(emptyMap()),
    ): List<McpPromptMessage> {
        val session = session(serverId)
        require(session.configuration.permissions.promptsAllowed) { "MCP prompts permission is denied" }
        return session.client.getPrompt(name, arguments)
    }

    suspend fun close() {
        val ids = lock.withLock { sessions.keys.toList() }
        ids.forEach { disconnect(it) }
    }

    private suspend fun session(serverId: String): Session = lock.withLock {
        sessions[serverId] ?: throw IllegalArgumentException("MCP server is not connected: $serverId")
    }

    private fun validateRemoteName(name: String) {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}"))) { "Invalid MCP tool name" }
    }

    private fun toolId(serverId: String, remoteName: String): String =
        "mcp.$serverId.${remoteName.replace('/', '.')}"

    private class McpRegisteredTool(
        id: String,
        remote: McpToolDescriptor,
        risk: RiskLevel,
        private val client: McpClient,
    ) : AgentTool<JsonObject, JsonObject> {
        private val remoteName = remote.name
        override val descriptor = ToolDescriptor(
            id = id,
            displayName = remote.title ?: remote.name,
            description = "Untrusted external MCP tool. ${remote.description}".take(4_096),
            inputSchema = remote.inputSchema.toString(),
            outputSchema = remote.outputSchema?.toString() ?: """{"type":"object"}""",
            riskLevel = risk,
            timeoutMs = 60_000,
        )

        override suspend fun availability(context: ToolContext): ToolAvailability =
            if (client.isHealthy) ToolAvailability.Available
            else ToolAvailability.Unavailable("MCP session is not initialized")

        override suspend fun execute(input: JsonObject, context: ToolContext): JsonObject =
            client.callTool(remoteName, input)
    }
}
