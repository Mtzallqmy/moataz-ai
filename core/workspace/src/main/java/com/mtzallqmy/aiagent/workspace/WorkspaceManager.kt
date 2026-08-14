package com.mtzallqmy.aiagent.workspace

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Workspace manager: /workspaces/{id} holding files, generated code, tool
 * artifacts, notes, downloads, and run-related logs. Isolated per run/task.
 */
class WorkspaceManager(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, "workspaces").also { it.mkdirs() }

    fun create(id: String = UUID.randomUUID().toString()): File {
        val dir = File(root, id).also { it.mkdirs() }
        File(dir, "notes").mkdirs()
        File(dir, "artifacts").mkdirs()
        File(dir, "downloads").mkdirs()
        File(dir, "code").mkdirs()
        File(dir, "logs").mkdirs()
        return dir
    }

    fun workspace(id: String): File = File(root, id).also {
        if (!it.exists()) it.mkdirs()
    }

    suspend fun readFile(id: String, path: String): String = withContext(Dispatchers.IO) {
        val file = resolveSafe(id, path)
        file.readText()
    }

    suspend fun writeFile(id: String, path: String, content: String): Unit = withContext(Dispatchers.IO) {
        val file = resolveSafe(id, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    suspend fun appendFile(id: String, path: String, content: String): Unit = withContext(Dispatchers.IO) {
        val file = resolveSafe(id, path)
        file.parentFile?.mkdirs()
        file.appendText(content)
    }

    suspend fun listFiles(id: String, path: String = "."): List<String> = withContext(Dispatchers.IO) {
        val dir = resolveSafe(id, path)
        if (!dir.exists()) {
            emptyList()
        } else {
            (dir.listFiles() ?: emptyArray()).map { it.name }.sorted()
        }
    }

    fun destroy(id: String): Boolean = workspace(id).deleteRecursively()

    /** Path traversal protection: resolved path must stay inside the workspace. */
    private fun resolveSafe(workspaceId: String, path: String): File {
        val base = workspace(workspaceId).canonicalFile
        val target = File(base, path).canonicalFile
        require(target.startsWith(base)) { "Path escapes workspace: $path" }
        return target
    }
}
