package com.mtzallqmy.aiagent.workflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface WorkflowStore {
    suspend fun saveDefinition(definition: WorkflowDefinition)
    suspend fun getDefinition(id: String, version: Int): WorkflowDefinition?
    suspend fun createRun(run: WorkflowRun)
    suspend fun updateRun(run: WorkflowRun)
    suspend fun getRun(runId: String): WorkflowRun?
    suspend fun listRuns(statuses: Set<WorkflowRunStatus> = emptySet()): List<WorkflowRun>
}

/**
 * Private-app-file durable store with fsync and atomic replacement. Corrupt data
 * is surfaced as an error and is never silently reset or destructively migrated.
 */
class AtomicFileWorkflowStore(
    private val file: File,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "nodeType"
    },
) : WorkflowStore {
    private val mutex = Mutex()

    override suspend fun saveDefinition(definition: WorkflowDefinition) = mutate { snapshot ->
        snapshot.copy(definitions = snapshot.definitions + (definition.key() to definition))
    }

    override suspend fun getDefinition(id: String, version: Int): WorkflowDefinition? = read { snapshot ->
        snapshot.definitions[definitionKey(id, version)]
    }

    override suspend fun createRun(run: WorkflowRun) = mutate { snapshot ->
        require(run.id !in snapshot.runs) { "Workflow run already exists: ${run.id}" }
        snapshot.copy(runs = snapshot.runs + (run.id to run))
    }

    override suspend fun updateRun(run: WorkflowRun) = mutate { snapshot ->
        require(run.id in snapshot.runs) { "Workflow run does not exist: ${run.id}" }
        snapshot.copy(runs = snapshot.runs + (run.id to run))
    }

    override suspend fun getRun(runId: String): WorkflowRun? = read { it.runs[runId] }

    override suspend fun listRuns(statuses: Set<WorkflowRunStatus>): List<WorkflowRun> = read { snapshot ->
        snapshot.runs.values
            .asSequence()
            .filter { statuses.isEmpty() || it.status in statuses }
            .sortedByDescending { it.createdAtMillis }
            .toList()
    }

    private suspend fun <T> read(block: (StoreSnapshot) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { block(readSnapshot()) }
    }

    private suspend fun mutate(block: (StoreSnapshot) -> StoreSnapshot) = mutex.withLock {
        withContext(Dispatchers.IO) {
            writeSnapshot(block(readSnapshot()))
        }
    }

    private fun readSnapshot(): StoreSnapshot {
        if (!file.exists()) return StoreSnapshot()
        require(file.isFile && file.canRead()) { "Workflow store is not readable: ${file.path}" }
        require(file.length() <= MAX_STORE_BYTES) { "Workflow store exceeds the configured size limit" }
        return json.decodeFromString(StoreSnapshot.serializer(), file.readText(Charsets.UTF_8))
    }

    private fun writeSnapshot(snapshot: StoreSnapshot) {
        file.parentFile?.let { parent ->
            require(parent.exists() || parent.mkdirs()) { "Cannot create workflow store directory" }
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        val bytes = json.encodeToString(snapshot).encodeToByteArray()
        FileOutputStream(temp, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Serializable
    private data class StoreSnapshot(
        val definitions: Map<String, WorkflowDefinition> = emptyMap(),
        val runs: Map<String, WorkflowRun> = emptyMap(),
    )

    private fun WorkflowDefinition.key() = definitionKey(id, version)

    private fun definitionKey(id: String, version: Int) = "$id@$version"

    private companion object {
        const val MAX_STORE_BYTES = 64L * 1024 * 1024
    }
}
