package com.mtzallqmy.aiagent.agent

import com.mtzallqmy.aiagent.model.ChatMessage
import com.mtzallqmy.aiagent.model.MessageRole

/**
 * Context manager: bounded history selection and tool-output compression.
 *
 * It deliberately removes complete user turns instead of individual messages so
 * an assistant tool invocation is never detached from its following TOOL result.
 * Token counting is approximate; provider usage events remain the source of truth
 * for accounting and run-level budgets.
 */
class ContextManager(
    private val contextWindow: Int = 4096,
    private val reserveForResponse: Int = 2048,
    private val maxToolOutputChars: Int = 3_000,
) {
    init {
        require(contextWindow > 0) { "contextWindow must be positive" }
        require(reserveForResponse >= 0) { "reserveForResponse must not be negative" }
        require(maxToolOutputChars > 0) { "maxToolOutputChars must be positive" }
    }

    /**
     * Fits message history into a conservative estimated token budget.
     * System messages are retained and old *complete user turns* are dropped first.
     */
    fun fit(messages: List<ChatMessage>, estimatedTokens: Int = 0): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()

        val normalized = messages
            .map { message ->
                if (message.role == MessageRole.TOOL) {
                    message.copy(content = compressToolOutput(message.content))
                } else message
            }
            .fold(mutableListOf<ChatMessage>()) { acc, message ->
                val previous = acc.lastOrNull()
                if (
                    message.role == MessageRole.TOOL &&
                    previous?.role == MessageRole.TOOL &&
                    previous.content == message.content &&
                    previous.toolCallId == message.toolCallId
                ) {
                    acc
                } else {
                    acc += message
                    acc
                }
            }

        val systemMessages = normalized.filter { it.role == MessageRole.SYSTEM }
        val nonSystem = normalized.filterNot { it.role == MessageRole.SYSTEM }
        val turns = splitIntoTurns(nonSystem).toMutableList()

        val available = (contextWindow - reserveForResponse - estimatedTokens).coerceAtLeast(MIN_CONTEXT_TOKENS)
        fun totalTokens(): Int = systemMessages.sumOf(::estimateMessageTokens) +
            turns.sumOf { turn -> turn.sumOf(::estimateMessageTokens) }

        // Preserve at least the newest turn; remove whole historical turns to keep
        // user/assistant/tool correlation structurally valid for provider APIs.
        while (turns.size > 1 && totalTokens() > available) {
            turns.removeAt(0)
        }

        return buildList {
            addAll(systemMessages)
            turns.forEach { addAll(it) }
        }
    }

    fun compressToolOutput(output: String): String =
        if (output.length > maxToolOutputChars) {
            output.take(maxToolOutputChars) + "\n... [output truncated]"
        } else output

    /** Approximate tokens ≈ characters / 4. Provider usage is used for actual accounting. */
    internal fun estimateTokens(text: String): Int = (text.length + 3) / 4

    private fun estimateMessageTokens(message: ChatMessage): Int {
        val toolMetadata = message.toolCalls.sumOf { call ->
            estimateTokens(call.name) + estimateTokens(call.arguments)
        }
        return estimateTokens(message.content) + toolMetadata + MESSAGE_OVERHEAD_TOKENS
    }

    private fun splitIntoTurns(messages: List<ChatMessage>): List<List<ChatMessage>> {
        if (messages.isEmpty()) return emptyList()
        val turns = mutableListOf<MutableList<ChatMessage>>()
        messages.forEach { message ->
            if (message.role == MessageRole.USER || turns.isEmpty()) {
                turns.add(mutableListOf(message))
            } else {
                turns.last().add(message)
            }
        }
        return turns
    }

    companion object {
        private const val MIN_CONTEXT_TOKENS = 512
        private const val MESSAGE_OVERHEAD_TOKENS = 8
    }
}
