package com.mtzallqmy.aiagent.feature.chat

import com.mtzallqmy.aiagent.agent.AgentRuntime
import com.mtzallqmy.aiagent.model.AgentState
import com.mtzallqmy.aiagent.model.ChatMessage
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.MessageRole
import com.mtzallqmy.aiagent.model.RoutingHint
import com.mtzallqmy.aiagent.model.RunTimelineEntry
import com.mtzallqmy.aiagent.tools.RegisteredTool
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Chat presentation state backed by the real [AgentRuntime].
 *
 * Runtime flows are collected exactly once for the lifetime of this controller.
 * Re-subscribing on every send previously duplicated streamed deltas/tool events
 * and leaked collectors for as long as the process stayed alive.
 */
class ChatViewModel(
    private val runtime: AgentRuntime,
    private val availableTools: List<RegisteredTool> = emptyList(),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) {
    private val uiScope = scope

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state

    private val _timeline = MutableStateFlow<List<RunTimelineEntry>>(emptyList())
    val timeline: StateFlow<List<RunTimelineEntry>> = _timeline

    /** Only one AgentRuntime run can be active, so one assistant slot owns incoming deltas. */
    private val activeAssistantIndex = AtomicInteger(NO_ACTIVE_MESSAGE)
    private var lastModelId: String = ""
    private var lastProviderId: String? = null

    init {
        observeRuntimeOnce()
    }

    /**
     * Starts a run and returns false when the runtime is already busy.
     * The optimistic message pair is rolled back when start is rejected.
     */
    fun send(
        text: String,
        tools: List<RegisteredTool> = availableTools,
        modelId: String = "",
        requestedProviderId: String? = null,
    ): Boolean {
        val task = text.trim()
        if (task.isBlank()) return false

        val before = _messages.value
        val assistantIndex = before.size + 1
        _messages.value = before +
            ChatMessage(role = MessageRole.USER, content = task) +
            ChatMessage(role = MessageRole.ASSISTANT, content = "")
        activeAssistantIndex.set(assistantIndex)

        val runId = runtime.runTask(
            task = task,
            modelId = modelId,
            tools = tools,
            history = before,
            routingHint = RoutingHint(requestedProviderId = requestedProviderId),
        )
        if (runId == null) {
            activeAssistantIndex.set(NO_ACTIVE_MESSAGE)
            _messages.value = before
            return false
        }
        lastModelId = modelId
        lastProviderId = requestedProviderId
        return true
    }

    private fun observeRuntimeOnce() {
        uiScope.launch {
            runtime.state.collect { state ->
                _state.value = state
            }
        }
        uiScope.launch {
            runtime.events.collect { event ->
                when (event) {
                    is GenerationEvent.TextDelta -> appendAssistant(event.text)
                    is GenerationEvent.ToolCallStarted -> {
                        _timeline.value += RunTimelineEntry(
                            runId = runtime.run.value?.runId.orEmpty(),
                            label = "tool:${event.toolName}",
                            startedAt = System.currentTimeMillis(),
                        )
                    }
                    is GenerationEvent.GenerationCompleted -> {
                        // Some providers may not emit text deltas. Preserve a useful final
                        // response in that case instead of leaving an empty assistant bubble.
                        if (event.finalText.isNotBlank()) {
                            replaceAssistantIfBlank(event.finalText)
                        }
                        activeAssistantIndex.set(NO_ACTIVE_MESSAGE)
                    }
                    is GenerationEvent.GenerationFailed -> {
                        replaceAssistant("Error: ${event.error.message ?: "Generation failed"}")
                        activeAssistantIndex.set(NO_ACTIVE_MESSAGE)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun appendAssistant(delta: String) {
        if (delta.isEmpty()) return
        updateAssistant { it + delta }
    }

    private fun replaceAssistantIfBlank(text: String) {
        updateAssistant { current -> if (current.isBlank()) text else current }
    }

    private fun replaceAssistant(text: String) {
        updateAssistant { text }
    }

    private inline fun updateAssistant(transform: (String) -> String) {
        val index = activeAssistantIndex.get()
        val current = _messages.value.toMutableList()
        if (index !in current.indices || current[index].role != MessageRole.ASSISTANT) return
        current[index] = current[index].copy(content = transform(current[index].content))
        _messages.value = current
    }

    fun stop() {
        runtime.cancel()
    }

    fun resend(): Boolean {
        val current = _messages.value
        val lastUserIndex = current.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) return false
        val lastUser = current[lastUserIndex]
        _messages.value = current.take(lastUserIndex)
        return send(
            lastUser.content,
            modelId = lastModelId,
            requestedProviderId = lastProviderId,
        )
    }

    fun editMessage(index: Int, text: String) {
        val current = _messages.value.toMutableList()
        if (index !in current.indices || current[index].role != MessageRole.USER) return
        current[index] = current[index].copy(content = text)
        _messages.value = current
    }

    private companion object {
        const val NO_ACTIVE_MESSAGE = -1
    }
}
