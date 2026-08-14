package com.mtzallqmy.aiagent.memory

import com.mtzallqmy.aiagent.common.SecretSanitizer
import com.mtzallqmy.aiagent.database.AppDatabase
import com.mtzallqmy.aiagent.database.MemoryEntity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.exp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class MemoryType(val wireName: String) {
    CONVERSATION("conversation"),
    WORKING("working"),
    LONG_TERM("long_term"),
    PROCEDURAL("procedural"),
    WORKSPACE("workspace");

    companion object {
        fun fromWireName(value: String): MemoryType = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown memory type: $value")
    }
}

object MemoryNamespaces {
    const val CONVERSATION = "conversation"
    const val WORKING = "working"
    const val LONG_TERM = "long_term"
    const val PROCEDURAL = "procedural"
    const val WORKSPACE = "workspace"
}

@Serializable
data class SourceReference(
    val sourceId: String,
    val uri: String? = null,
    val title: String? = null,
    val contentHash: String? = null,
)

@Serializable
private data class MemoryMetadata(
    val hits: Int = 0,
    val importance: Double = 0.5,
    val lastAccessedAt: Long? = null,
    val contentHash: String,
    val sources: List<SourceReference> = emptyList(),
)

data class MemoryRecord(
    val id: String,
    val namespace: String,
    val type: MemoryType,
    val key: String,
    val value: String,
    val score: Double,
    val importance: Double,
    val recencyScore: Double,
    val hitCount: Int,
    val pinned: Boolean,
    val expiresAt: Long?,
    val sourceReferences: List<SourceReference>,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long?,
)

data class MemoryWrite(
    val namespace: String,
    val type: MemoryType,
    val key: String,
    val value: String,
    val importance: Double = 0.5,
    val ttlMillis: Long? = null,
    val pinned: Boolean = false,
    val sourceReferences: List<SourceReference> = emptyList(),
)

/** Typed Room memory repository with expiry, ranking, deduplication, pinning, and sources. */
class MemoryStore(
    private val database: () -> Any,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun db() = database() as AppDatabase

    suspend fun put(write: MemoryWrite): MemoryRecord {
        validate(write)
        rejectSecret(write.value)
        val now = clock()
        val contentHash = sha256(normalize(write.value))
        val existing = db().memoryDao()
            .getByIdentity(write.namespace, write.type.wireName, write.key, now)
            .firstOrNull { metadata(it).contentHash == contentHash }
        val oldMetadata = existing?.let(::metadata)
        val mergedSources = (oldMetadata?.sources.orEmpty() + write.sourceReferences)
            .distinctBy { listOf(it.sourceId, it.uri, it.contentHash) }
            .take(MAX_SOURCE_REFERENCES)
        val storedMetadata = MemoryMetadata(
            hits = oldMetadata?.hits ?: 0,
            importance = maxOf(oldMetadata?.importance ?: 0.0, write.importance),
            lastAccessedAt = oldMetadata?.lastAccessedAt,
            contentHash = contentHash,
            sources = mergedSources,
        )
        val entity = MemoryEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            namespace = write.namespace,
            type = write.type.wireName,
            key = write.key,
            value = write.value,
            metadata = json.encodeToString(storedMetadata),
            score = rank(storedMetadata.importance, storedMetadata.hits, now, now),
            pinned = write.pinned || existing?.pinned == true,
            expiresAt = write.ttlMillis?.let { Math.addExact(now, it) } ?: existing?.expiresAt,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        db().memoryDao().upsert(entity)
        return entity.toRecord(now)
    }

    /** Compatibility boundary; new callers should use typed [MemoryWrite]. */
    suspend fun put(
        namespace: String,
        key: String,
        value: String,
        type: String = MemoryType.LONG_TERM.wireName,
        metadata: String? = null,
        score: Double = 0.5,
        expiresAtMs: Long? = null,
    ) {
        val now = clock()
        put(
            MemoryWrite(
                namespace = namespace,
                type = MemoryType.fromWireName(type),
                key = key,
                value = value,
                importance = score.coerceIn(0.0, 1.0),
                ttlMillis = expiresAtMs?.let { (it - now).coerceAtLeast(1) },
                sourceReferences = metadata?.takeIf { it.isNotBlank() }?.let {
                    listOf(SourceReference(sourceId = "legacy-metadata", title = it.take(256)))
                }.orEmpty(),
            ),
        )
    }

    suspend fun edit(id: String, value: String): MemoryRecord? {
        rejectSecret(value)
        val dao = db().memoryDao()
        val existing = dao.get(id) ?: return null
        val now = clock()
        val expiresAt = existing.expiresAt
        check(expiresAt == null || expiresAt > now) { "Cannot edit expired memory" }
        val old = metadata(existing)
        val updatedMeta = old.copy(contentHash = sha256(normalize(value)))
        val updated = existing.copy(
            value = value,
            metadata = json.encodeToString(updatedMeta),
            score = rank(updatedMeta.importance, updatedMeta.hits, now, now),
            updatedAt = now,
        )
        dao.upsert(updated)
        return updated.toRecord(now)
    }

    suspend fun delete(id: String) = db().memoryDao().delete(id)

    suspend fun pin(id: String, pinned: Boolean): MemoryRecord? {
        val dao = db().memoryDao()
        val existing = dao.get(id) ?: return null
        val now = clock()
        val updated = existing.copy(pinned = pinned, updatedAt = now)
        dao.upsert(updated)
        return updated.toRecord(now)
    }

    suspend fun recordRecall(id: String): MemoryRecord? {
        val dao = db().memoryDao()
        val existing = dao.get(id) ?: return null
        val now = clock()
        val expiresAt = existing.expiresAt
        if (expiresAt != null && expiresAt <= now) return null
        val old = metadata(existing)
        val updatedMeta = old.copy(hits = old.hits + 1, lastAccessedAt = now)
        val updated = existing.copy(
            metadata = json.encodeToString(updatedMeta),
            score = rank(updatedMeta.importance, updatedMeta.hits, now, now),
            updatedAt = now,
        )
        dao.upsert(updated)
        return updated.toRecord(now)
    }

    fun listTyped(namespace: String): Flow<List<MemoryRecord>> =
        db().memoryDao().list(namespace, clock()).map { items ->
            val now = clock()
            items.map { it.toRecord(now) }.sortedWith(memoryComparator())
        }

    /** Compatibility for existing integrations. */
    fun list(namespace: String): Flow<List<MemoryEntity>> = db().memoryDao().list(namespace, clock())

    suspend fun searchTyped(namespace: String, query: String, limit: Int = 50): List<MemoryRecord> {
        require(limit in 1..200)
        val now = clock()
        return db().memoryDao().search(namespace, query.trim(), now, limit)
            .map { it.toRecord(now) }
            .sortedWith(memoryComparator())
            .take(limit)
    }

    suspend fun search(namespace: String, query: String): List<MemoryEntity> =
        db().memoryDao().search(namespace, query.trim(), clock(), 50)

    suspend fun purgeExpired(): Int = db().memoryDao().deleteExpired(clock())

    private fun MemoryEntity.toRecord(now: Long): MemoryRecord {
        val parsed = metadata(this)
        return MemoryRecord(
            id = id,
            namespace = namespace,
            type = MemoryType.fromWireName(type),
            key = key,
            value = value,
            score = rank(parsed.importance, parsed.hits, parsed.lastAccessedAt ?: updatedAt, now),
            importance = parsed.importance,
            recencyScore = recency(parsed.lastAccessedAt ?: updatedAt, now),
            hitCount = parsed.hits,
            pinned = pinned,
            expiresAt = expiresAt,
            sourceReferences = parsed.sources,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastAccessedAt = parsed.lastAccessedAt,
        )
    }

    private fun metadata(entity: MemoryEntity): MemoryMetadata = runCatching {
        json.decodeFromString<MemoryMetadata>(entity.metadata.orEmpty())
    }.getOrElse {
        MemoryMetadata(
            hits = legacyHits(entity.metadata),
            importance = entity.score.coerceIn(0.0, 1.0),
            contentHash = sha256(normalize(entity.value)),
        )
    }

    private fun validate(write: MemoryWrite) {
        require(NAMESPACE.matches(write.namespace)) { "Invalid memory namespace" }
        require(write.key.isNotBlank() && write.key.length <= 512) { "Invalid memory key" }
        require(write.value.isNotBlank() && write.value.length <= MAX_VALUE_CHARS) { "Invalid memory value" }
        require(write.importance in 0.0..1.0) { "Importance must be between 0 and 1" }
        require(write.ttlMillis == null || write.ttlMillis in 1..MAX_TTL_MILLIS) { "Invalid memory TTL" }
        require(write.sourceReferences.size <= MAX_SOURCE_REFERENCES) { "Too many source references" }
    }

    private fun rejectSecret(value: String) {
        if (SecretSanitizer.containsSecret(value)) {
            throw IllegalArgumentException("Refusing to store value containing a detected secret in memory")
        }
    }

    private fun memoryComparator() = compareByDescending<MemoryRecord> { it.pinned }
        .thenByDescending { it.score }
        .thenByDescending { it.updatedAt }

    private fun rank(importance: Double, hits: Int, accessedAt: Long, now: Long): Double =
        (importance * 0.55 + recency(accessedAt, now) * 0.30 + (1.0 - exp(-hits / 5.0)) * 0.15)
            .coerceIn(0.0, 1.0)

    private fun recency(accessedAt: Long, now: Long): Double =
        exp(-((now - accessedAt).coerceAtLeast(0).toDouble() / RECENCY_HALF_LIFE_MILLIS))

    private fun legacyHits(value: String?): Int = Regex(""""hits"\s*:\s*(\d+)""")
        .find(value.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase()
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        val NAMESPACE = Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,383}")
        const val MAX_VALUE_CHARS = 1_000_000
        const val MAX_SOURCE_REFERENCES = 64
        const val MAX_TTL_MILLIS = 10L * 365 * 24 * 60 * 60 * 1_000
        const val RECENCY_HALF_LIFE_MILLIS = 30.0 * 24 * 60 * 60 * 1_000
    }
}
