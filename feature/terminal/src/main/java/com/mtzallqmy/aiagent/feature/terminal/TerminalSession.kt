package com.mtzallqmy.aiagent.feature.terminal

import com.mtzallqmy.aiagent.tool.terminal.TerminalToolSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Terminal session state exposed to the Terminal UI. */
class TerminalSession(
    private val toolSet: TerminalToolSet = TerminalToolSet(),
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output

    private val _activeSessions = MutableStateFlow<Set<String>>(emptySet())
    val activeSessions: StateFlow<Set<String>> = _activeSessions

    fun execute(command: String) {
        scope.launch {
            appendOutput("$ ${command}\n")
            val result = toolSet.executeCommand(command)
            if (result.stdout.isNotBlank()) appendOutput(result.stdout)
            if (result.stderr.isNotBlank()) appendOutput("[stderr] ${result.stderr}")
            appendOutput("\n[exit ${result.exitCode}]\n")
        }
    }

    fun createSession() {
        scope.launch {
            val tools = toolSet.tools
            // sessions are managed inside TerminalToolSet via its own tools
            _activeSessions.value = toolSet.activeSessions()
        }
    }

    fun clear() { _output.value = "" }

    private fun appendOutput(text: String) {
        _output.value += text
    }
}
