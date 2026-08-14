package com.mtzallqmy.aiagent.schedules

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface ScheduleStore {
    suspend fun put(record: ScheduleRecord)
    suspend fun get(id: String): ScheduleRecord?
    suspend fun list(): List<ScheduleRecord>
    suspend fun remove(id: String)
}

class AtomicFileScheduleStore(
    private val file: File,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) : ScheduleStore {
    private val mutex = Mutex()

    override suspend fun put(record: ScheduleRecord) = mutate { records ->
        records.filterNot { it.definition.id == record.definition.id } + record
    }

    override suspend fun get(id: String): ScheduleRecord? = mutex.withLock {
        withContext(Dispatchers.IO) { read().firstOrNull { it.definition.id == id } }
    }

    override suspend fun list(): List<ScheduleRecord> = mutex.withLock {
        withContext(Dispatchers.IO) { read().sortedBy { it.definition.name } }
    }

    override suspend fun remove(id: String) = mutate { it.filterNot { record -> record.definition.id == id } }

    private suspend fun mutate(block: (List<ScheduleRecord>) -> List<ScheduleRecord>) = mutex.withLock {
        withContext(Dispatchers.IO) { write(block(read())) }
    }

    private fun read(): List<ScheduleRecord> {
        if (!file.exists()) return emptyList()
        require(file.isFile && file.canRead()) { "Schedule store is not readable" }
        require(file.length() <= MAX_STORE_BYTES) { "Schedule store exceeds size limit" }
        return json.decodeFromString(ListSerializer(ScheduleRecord.serializer()), file.readText())
    }

    private fun write(records: List<ScheduleRecord>) {
        require(records.size <= MAX_SCHEDULES) { "Schedule limit exceeded" }
        file.parentFile?.let { require(it.exists() || it.mkdirs()) }
        val temp = File(file.parentFile, "${file.name}.tmp")
        val bytes = json.encodeToString(ListSerializer(ScheduleRecord.serializer()), records).encodeToByteArray()
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val MAX_STORE_BYTES = 16L * 1024 * 1024
        const val MAX_SCHEDULES = 10_000
    }
}
