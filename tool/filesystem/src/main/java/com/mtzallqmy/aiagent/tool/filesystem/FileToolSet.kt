package com.mtzallqmy.aiagent.tool.filesystem

import android.content.Context
import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.RegisteredTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import com.mtzallqmy.aiagent.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Real filesystem tools: file.read, file.write, file.append, file.list,
 * file.info, file.delete. All paths are resolved inside the current
 * workspace; path traversal is rejected by WorkspaceManager.
 */
class FileToolSet(
    context: Context,
    private val workspace: WorkspaceManager = WorkspaceManager(context),
) {
    val tools: List<RegisteredTool> = listOf(
        RegisteredTool.typed(FileReadTool(workspace), FilePathInput.serializer()),
        RegisteredTool.typed(FileWriteTool(workspace), FileContentInput.serializer()),
        RegisteredTool.typed(FileAppendTool(workspace), FileContentInput.serializer()),
        RegisteredTool.typed(FileListTool(workspace), FileListInput.serializer()),
        RegisteredTool.typed(FileInfoTool(workspace), FilePathInput.serializer()),
        RegisteredTool.typed(FileDeleteTool(workspace), FilePathInput.serializer()),
    )
}

@Serializable
data class FilePathInput(val path: String)

@Serializable
data class FileContentInput(val path: String, val content: String)

@Serializable
data class FileListInput(val path: String = ".")

private abstract class FileBaseTool<I : Any>(
    override val descriptor: ToolDescriptor,
    protected val workspace: WorkspaceManager,
) : AgentTool<I, ToolResultEnvelope> {

    override suspend fun availability(context: ToolContext): ToolAvailability {
        return try {
            val dir = workspace.workspace(context.workspaceId)
            if (dir.exists()) ToolAvailability.Available
            else ToolAvailability.Unavailable("Workspace ${context.workspaceId} not available")
        } catch (e: Throwable) {
            ToolAvailability.Unavailable(e.message ?: "Workspace unavailable")
        }
    }

    suspend fun realExecute(body: suspend () -> String): ToolResultEnvelope {
        val start = System.currentTimeMillis()
        return try {
            val out = withContext(Dispatchers.IO) { body() }
            ToolResultEnvelope(
                toolId = descriptor.id, success = true, data = out,
                durationMs = System.currentTimeMillis() - start,
            )
        } catch (e: Throwable) {
            ToolResultEnvelope(
                toolId = descriptor.id, success = false, data = "",
                error = e.message ?: "IO error", durationMs = 0,
                isRetryable = false, errorCategory = ToolErrorCategory.GENERIC,
            )
        }
    }
}

private class FileReadTool(workspace: WorkspaceManager) : FileBaseTool<FilePathInput>(
    ToolDescriptor(
        id = "file.read", displayName = "Read File", description = "Read the content of a file inside the current workspace",
        inputSchema = """{"type":"object","required":["path"],"properties":{"path":{"type":"string"}}}""",
        outputSchema = """{"type":"string"}""", riskLevel = RiskLevel.READ,
        requiredCapabilities = setOf(CapabilityId("workspace")), timeoutMs = 10_000L,
    ), workspace
) {
    override suspend fun execute(input: FilePathInput, context: ToolContext): ToolResultEnvelope {
        return realExecute { workspace.readFile(context.workspaceId, input.path) }
    }
}

private class FileWriteTool(workspace: WorkspaceManager) : FileBaseTool<FileContentInput>(
    ToolDescriptor(
        id = "file.write", displayName = "Write File", description = "Write content to a file inside the current workspace",
        inputSchema = """{"type":"object","required":["path","content"],"properties":{"path":{"type":"string"},"content":{"type":"string"}}}""",
        outputSchema = """{"type":"string"}""", riskLevel = RiskLevel.MODIFY,
        requiredCapabilities = setOf(CapabilityId("workspace")), timeoutMs = 15_000L,
    ), workspace
) {
    override suspend fun execute(input: FileContentInput, context: ToolContext): ToolResultEnvelope {
        return realExecute {
            workspace.writeFile(context.workspaceId, input.path, input.content)
            "Wrote ${input.content.length} chars to ${input.path}"
        }
    }
}

private class FileAppendTool(workspace: WorkspaceManager) : FileBaseTool<FileContentInput>(
    ToolDescriptor(
        id = "file.append", displayName = "Append File", description = "Append content to a file inside the current workspace",
        inputSchema = """{"type":"object","required":["path","content"],"properties":{"path":{"type":"string"},"content":{"type":"string"}}}""",
        outputSchema = """{"type":"string"}""", riskLevel = RiskLevel.MODIFY,
        requiredCapabilities = setOf(CapabilityId("workspace")), timeoutMs = 15_000L,
    ), workspace
) {
    override suspend fun execute(input: FileContentInput, context: ToolContext): ToolResultEnvelope {
        return realExecute {
            workspace.appendFile(context.workspaceId, input.path, input.content)
            "Appended ${input.content.length} chars to ${input.path}"
        }
    }
}

private class FileListTool(workspace: WorkspaceManager) : FileBaseTool<FileListInput>(
    ToolDescriptor(
        id = "file.list", displayName = "List Files", description = "List files in a workspace directory",
        inputSchema = """{"type":"object","properties":{"path":{"type":"string"}}}""",
        outputSchema = """{"type":"string"}""", riskLevel = RiskLevel.READ,
        requiredCapabilities = setOf(CapabilityId("workspace")), timeoutMs = 10_000L,
    ), workspace
) {
    override suspend fun execute(input: FileListInput, context: ToolContext): ToolResultEnvelope {
        return realExecute {
            workspace.listFiles(context.workspaceId, input.path).joinToString("\n").ifBlank { "(empty directory)" }
        }
    }
}

private class FileInfoTool(workspace: WorkspaceManager) : FileBaseTool<FilePathInput>(
    ToolDescriptor(
        id = "file.info", displayName = "File Info", description = "Get file metadata (size, last modified, type)",
        inputSchema = """{"type":"object","required":["path"],"properties":{"path":{"type":"string"}}}""",
        outputSchema = """{"type":"string"}""", riskLevel = RiskLevel.READ,
        requiredCapabilities = setOf(CapabilityId("workspace")), timeoutMs = 10_000L,
    ), workspace
) {
    override suspend fun execute(input: FilePathInput, context: ToolContext): ToolResultEnvelope {
        return realExecute {
            val file = workspace.workspace(context.workspaceId).resolve(input.path)
            buildString {
                appendLine("name=${file.name}")
                appendLine("isDirectory=${file.isDirectory}")
                appendLine("length=${file.length()}")
                appendLine("lastModified=${file.lastModified()}")
            }
        }
    }
}

private class FileDeleteTool(workspace: WorkspaceManager) : FileBaseTool<FilePathInput>(
    ToolDescriptor(
        id = "file.delete", displayName = "Delete File", description = "Delete a file inside the current workspace",
        inputSchema = """{"type":"object","required":["path"],"properties":{"path":{"type":"string"}}}""",
        outputSchema = """{"type":"string"}""", riskLevel = RiskLevel.SYSTEM_SENSITIVE,
        requiredCapabilities = setOf(CapabilityId("workspace")), timeoutMs = 10_000L,
    ), workspace
) {
    override suspend fun execute(input: FilePathInput, context: ToolContext): ToolResultEnvelope {
        return realExecute {
            val file = workspace.workspace(context.workspaceId).resolve(input.path)
            "deleted=${file.deleteRecursively()} path=${input.path}"
        }
    }
}
