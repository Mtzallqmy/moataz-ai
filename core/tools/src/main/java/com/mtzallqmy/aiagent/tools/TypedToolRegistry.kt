package com.mtzallqmy.aiagent.tools

import com.mtzallqmy.aiagent.model.ToolDescriptor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Type-erased runtime boundary. Erasure happens only after a concrete serializer
 * has decoded validated JSON into the tool's specific input DTO.
 */
class RegisteredTool private constructor(
    val descriptor: ToolDescriptor,
    private val availabilityCheck: suspend (ToolContext) -> ToolAvailability,
    private val prepareDecoded: (JsonObject) -> PreparedToolCall,
) {
    suspend fun availability(context: ToolContext): ToolAvailability = availabilityCheck(context)
    fun prepare(input: JsonObject): PreparedToolCall = prepareDecoded(input)

    companion object {
        fun <I : Any, O : Any> typed(
            tool: AgentTool<I, O>,
            inputSerializer: KSerializer<I>,
            json: Json = STRICT_JSON,
        ): RegisteredTool = RegisteredTool(
            descriptor = tool.descriptor,
            availabilityCheck = tool::availability,
            prepareDecoded = { input ->
                val typedInput = json.decodeFromJsonElement(inputSerializer, input)
                PreparedToolCall { context -> tool.execute(typedInput, context) }
            },
        )

        private val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}

class PreparedToolCall internal constructor(
    private val execution: suspend (ToolContext) -> Any,
) {
    suspend fun execute(context: ToolContext): Any = execution(context)
}

class TypedToolRegistry {
    private val tools = linkedMapOf<String, RegisteredTool>()

    @Synchronized
    fun register(tool: RegisteredTool) {
        check(tools.putIfAbsent(tool.descriptor.id, tool) == null) {
            "Tool already registered: ${tool.descriptor.id}"
        }
    }

    @Synchronized
    fun get(toolId: String): RegisteredTool? = tools[toolId]

    @Synchronized
    fun unregister(toolId: String): RegisteredTool? = tools.remove(toolId)

    @Synchronized
    fun list(): List<RegisteredTool> = tools.values.toList()

    @Synchronized
    fun descriptors(): List<ToolDescriptor> = tools.values.map { it.descriptor }
}
