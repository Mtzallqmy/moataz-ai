package com.mtzallqmy.aiagent.feature.files

import android.content.Context
import android.net.Uri
import com.mtzallqmy.aiagent.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Real files feature: SAF import/export into the workspace. */
class FilesFeature(
    private val context: Context,
    private val workspace: WorkspaceManager = WorkspaceManager(context),
) {
    /** Import a content URI into the current workspace (SAF). */
    suspend fun importToWorkspace(uri: Uri, workspaceId: String, fileName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val stream: InputStream = context.contentResolver.openInputStream(uri)
                ?: error("Unable to open URI")
            stream.use { input ->
                workspace.writeFile(workspaceId, fileName, input.readBytes().toString(Charsets.UTF_8))
            }
            "Imported $fileName into workspace $workspaceId"
        }
    }

    suspend fun exportFromWorkspace(workspaceId: String, fileName: String, target: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val source = workspace.workspace(workspaceId).resolve(fileName)
            source.copyTo(target, overwrite = true)
        }
    }
}
