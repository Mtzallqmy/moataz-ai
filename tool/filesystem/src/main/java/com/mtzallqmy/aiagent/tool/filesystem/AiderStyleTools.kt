package com.mtzallqmy.aiagent.tool.filesystem

import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.model.ToolDescriptor
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.RegisteredTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * Repository map tool — concept studied from Aider (Apache-2.0,
 * clean-room reimplementation): produces a compact map of a repository
 * (tree + per-file byte sizes + first lines) so the model knows what
 * files exist without reading them all.
 */
class RepoMapTool(
    private val baseDirProvider: suspend () -> File,
    private val maxFiles: Int = 100,
    private val firstLines: Int = 3,
) : AgentTool<RepoMapInput, JsonObject> {
    override val descriptor = ToolDescriptor(
        id = "filesystem.repo_map",
        displayName = "Repository Map",
        description = "Produce a compact map of the workspace: file tree, sizes, first lines",
        inputSchema = """{"type":"object","properties":{"subdir":{"type":"string"}}}""",
        outputSchema = """{"type":"object"}""",
        riskLevel = RiskLevel.READ,
        requiredCapabilities = setOf(CapabilityId("filesystem")),
        timeoutMs = 30_000L,
    )

    override suspend fun availability(context: ToolContext) = ToolAvailability.Available

    override suspend fun execute(input: RepoMapInput, context: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val base = baseDirProvider().canonicalFile
        val root = (if (input.subdir.isNotBlank()) File(base, input.subdir) else base).canonicalFile
        require(root.toPath().startsWith(base.toPath())) { "Path escapes workspace" }
        require(root.isDirectory) { "Directory not found: $root" }

        val files = root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith('.') && !it.name.contains("/.git/") }
            .take(maxFiles)
            .toList()

        val tree = files.map { it.relativeTo(base).path }.joinToString("\n") { "  - $it" }
        val sizes = files.joinToString("\n") { "  ${it.relativeTo(base).path}: ${it.length()} bytes" }
        val heads = files.joinToString("\n---\n") { file ->
            val head = runCatching { file.readLines().take(firstLines).joinToString("\n") }.getOrDefault("")
            "${file.relativeTo(base).path}:\n$head"
        }
        buildJsonObject {
            put("file_count", JsonPrimitive(files.size))
            put("tree", JsonPrimitive(tree))
            put("sizes", JsonPrimitive(sizes))
            put("first_lines", JsonPrimitive(heads.take(20_000)))
        }
    }

    fun registered(): RegisteredTool = RegisteredTool.typed(this, RepoMapInput.serializer())
}

/**
 * File edit tool (Aider-style): replace/append structured edits with
 * explicit verification — never whole-file rewrites from the model.
 */
class FileEditTool(
    private val baseDirProvider: suspend () -> File,
) : AgentTool<FileEditInput, JsonObject> {
    override val descriptor = ToolDescriptor(
        id = "filesystem.edit",
        displayName = "Edit File",
        description = "Apply a targeted replace or append edit to a file",
        inputSchema = """{"type":"object","required":["path","operation"],"properties":{"path":{"type":"string"},"operation":{"type":"string","enum":["replace","append"]},"find":{"type":"string"},"replace":{"type":"string"},"text":{"type":"string"}}}""",
        outputSchema = """{"type":"object"}""",
        riskLevel = RiskLevel.MODIFY,
        requiredCapabilities = setOf(CapabilityId("filesystem")),
        timeoutMs = 30_000L,
    )

    override suspend fun availability(context: ToolContext) = ToolAvailability.Available

    override suspend fun execute(input: FileEditInput, context: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val base = baseDirProvider().canonicalFile
        val file = File(base, input.path).canonicalFile.also {
            require(it.toPath().startsWith(base.toPath())) { "Path escapes workspace" }
        }

        when (input.operation) {
            "replace" -> {
                val find = input.find ?: error("find required")
                val replacement = input.replace ?: ""
                require(file.exists()) { "File not found: ${input.path}" }
                val content = file.readText()
                require(find in content) { "Pattern not found in ${input.path}" }
                file.writeText(content.replace(find, replacement, ignoreCase = false))
                buildJsonObject { put("bytes_written", JsonPrimitive(file.length())) }
            }
            "append" -> {
                val text = input.text ?: ""
                file.parentFile?.mkdirs()
                file.appendText(text + "\n")
                buildJsonObject { put("bytes_written", JsonPrimitive(text.length + 1)) }
            }
            else -> error("Unknown operation: ${input.operation} (use replace or append)")
        }
    }

    fun registered(): RegisteredTool = RegisteredTool.typed(this, FileEditInput.serializer())
}

/**
 * Git diff tool: shows current working-tree changes so the agent verifies
 * edits before committing or asking for approval.
 */
class GitDiffTool(
    private val baseDirProvider: suspend () -> File,
) : AgentTool<GitDiffInput, JsonObject> {
    override val descriptor = ToolDescriptor(
        id = "filesystem.git_diff",
        displayName = "Git Diff",
        description = "Show the current working-tree diff and status",
        inputSchema = """{"type":"object","properties":{}}""",
        outputSchema = """{"type":"object"}""",
        riskLevel = RiskLevel.READ,
        requiredCapabilities = setOf(CapabilityId("filesystem"), CapabilityId("terminal")),
        timeoutMs = 30_000L,
    )

    override suspend fun availability(context: ToolContext) = ToolAvailability.Available

    override suspend fun execute(input: GitDiffInput, context: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val base = baseDirProvider()
        val diff = runGit(base, "git diff --stat")
        val status = runGit(base, "git status --short")
        buildJsonObject {
            put("diff_stat", JsonPrimitive(diff))
            put("status", JsonPrimitive(status))
        }
    }

    private fun runGit(base: File, command: String): String = runCatching {
        val process = ProcessBuilder(command.split(" ")).directory(base).redirectErrorStream(true).start()
        process.waitFor()
        process.inputStream.bufferedReader().readText().take(10_000)
    }.getOrElse { "git unavailable: ${it.message}" }

    fun registered(): RegisteredTool = RegisteredTool.typed(this, GitDiffInput.serializer())
}

@Serializable
data class RepoMapInput(val subdir: String = "")

@Serializable
data class FileEditInput(
    val path: String,
    val operation: String,
    val find: String? = null,
    val replace: String? = null,
    val text: String? = null,
)

@Serializable
class GitDiffInput
