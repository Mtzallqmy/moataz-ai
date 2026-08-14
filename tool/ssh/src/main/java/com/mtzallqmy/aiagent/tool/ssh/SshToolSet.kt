package com.mtzallqmy.aiagent.tool.ssh

import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.RegisteredTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * SSH tool set.
 * Security defaults per requirements: StrictHostKeyChecking "no" is BANNED as a
 * default; strict verification is the default and accept-new is explicit opt-in.
 */
class SshToolSet(
    private val sshClientFactory: (SshConnectionSpec) -> SshBackend = { RealProcessSshBackend(it) },
    private val defaultPort: Int = 22,
) {
    val tools: List<RegisteredTool> = listOf(
        RegisteredTool.typed(SshExecTool(), SshExecInput.serializer()),
    )

    data class SshConnectionSpec(
        val host: String,
        val port: Int,
        val user: String,
        val keyPath: String?,
        val hostKeyPolicy: HostKeyPolicy,
    )

    /**
     * Host-key policy: NEVER silently accept all hosts.
     * "accept-new" accepts only never-seen hosts; subsequent mismatches fail.
     */
    enum class HostKeyPolicy { ACCEPT_NEW, STRICT }

    fun validateConnectionArgs(input: SshExecInput): SshConnectionSpec {
        val host = input.host.ifBlank { error("host required") }
        val user = input.user.ifBlank { error("user required") }
        require(host.matches(SAFE_HOST)) { "Invalid SSH host" }
        require(user.matches(SAFE_USER)) { "Invalid SSH user" }
        val port = input.port ?: defaultPort
        require(port in 1..65535) { "SSH port must be between 1 and 65535" }
        val keyPath = input.keyPath?.ifBlank { null }
        val policyStr = input.hostKeyPolicy.lowercase()
        val policy = when (policyStr) {
            "accept_new", "accept-new" -> HostKeyPolicy.ACCEPT_NEW
            "strict" -> HostKeyPolicy.STRICT
            else -> error("Invalid host_key_policy (accept_new|strict)")
        }
        // Ban dangerous defaults: never allow "no" (trust-all).
        if (policyStr == "no") error("StrictHostKeyChecking=no is not permitted — use accept_new or strict")
        return SshConnectionSpec(host, port, user, keyPath, policy)
    }

    private inner class SshExecTool : AgentTool<SshExecInput, JsonObject> {
        override val descriptor = ToolDescriptor(
            id = "ssh.exec", displayName = "SSH Execute", description = "Execute a command on a remote host via SSH",
            inputSchema = """{"type":"object","required":["host","user","command"],"properties":{"host":{"type":"string"},"port":{"type":"integer","minimum":1,"maximum":65535},"user":{"type":"string"},"key_path":{"type":"string"},"command":{"type":"string"},"host_key_policy":{"type":"string","enum":["strict","accept_new","accept-new"]}}}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.SYSTEM_SENSITIVE, requiredCapabilities = setOf(CapabilityId("network")), timeoutMs = 60_000L,
        )
        override suspend fun availability(context: ToolContext): ToolAvailability {
            val hasBinary = try {
                ProcessBuilder("which", "ssh").start().let { p -> p.waitFor(); p.exitValue() == 0 }
            } catch (e: Throwable) { false }
            return if (hasBinary) ToolAvailability.Available
            else ToolAvailability.Unavailable("ssh binary not present on this device")
        }
        override suspend fun execute(input: SshExecInput, context: ToolContext): JsonObject = withContext(Dispatchers.IO) {
            val spec = validateConnectionArgs(input)
            val backend = sshClientFactory(spec)
            backend.execute(input.command, timeoutMs = descriptor.timeoutMs)
        }
    }

    private companion object {
        val SAFE_HOST = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]*$")
        val SAFE_USER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    }
}

@Serializable
data class SshExecInput(
    val host: String,
    val user: String,
    val command: String,
    val port: Int? = null,
    @SerialName("key_path") val keyPath: String? = null,
    @SerialName("host_key_policy") val hostKeyPolicy: String = "strict",
)

/** Pluggable SSH backend abstraction. */
interface SshBackend {
    suspend fun execute(command: String, timeoutMs: Long = 60_000L): JsonObject
}

/**
 * Real SSH backend using the system ssh binary with safe defaults.
 * Host-key handling comes from the validated connection spec. Strict is the
 * default; accept-new must be explicitly requested. BatchMode prevents hidden
 * interactive credential prompts.
 */
class RealProcessSshBackend(
    private val spec: SshToolSet.SshConnectionSpec,
) : SshBackend {
    override suspend fun execute(command: String, timeoutMs: Long): JsonObject = coroutineScope {
        val argv = buildList {
            add("ssh")
            add("-p"); add(spec.port.toString())
            add("-o"); add("StrictHostKeyChecking=${if (spec.hostKeyPolicy == SshToolSet.HostKeyPolicy.STRICT) "yes" else "accept-new"}")
            add("-o"); add("BatchMode=yes")
            add("-o"); add("ConnectTimeout=15")
            add("-o"); add("ServerAliveInterval=10")
            add("-o"); add("ServerAliveCountMax=2")
            spec.keyPath?.let { add("-i"); add(it) }
            add("${spec.user}@${spec.host}")
            add(command)
        }
        val process = ProcessBuilder(argv).redirectErrorStream(false).start()
        val stdout = async(Dispatchers.IO) { readLimited(process.inputStream.bufferedReader(), 20_000) }
        val stderr = async(Dispatchers.IO) { readLimited(process.errorStream.bufferedReader(), 5_000) }
        try {
            val finished = withContext(Dispatchers.IO) { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            if (!finished) process.destroyForcibly()
            buildJsonObject {
                put("exit_code", JsonPrimitive(if (finished) process.exitValue() else -1))
                put("stdout", JsonPrimitive(stdout.await()))
                put("stderr", JsonPrimitive(stderr.await()))
                put("timed_out", JsonPrimitive(!finished))
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun readLimited(reader: BufferedReader, limit: Int): String = reader.use {
        val output = StringBuilder()
        val buffer = CharArray(1_024)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            val remaining = limit - output.length
            if (remaining > 0) output.append(buffer, 0, minOf(count, remaining))
        }
        output.toString()
    }
}
