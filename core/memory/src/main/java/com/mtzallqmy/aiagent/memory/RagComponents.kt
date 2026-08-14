package com.mtzallqmy.aiagent.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mtzallqmy.aiagent.common.SecretSanitizer
import com.mtzallqmy.aiagent.network.SafeHttpClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class EmbeddingQuality { FALLBACK_KEYWORD, MODEL_LOCAL, MODEL_CLOUD }

interface EmbeddingsProvider {
    val providerId: String
    val dimension: Int
    val quality: EmbeddingQuality
    suspend fun embed(text: String): List<Double>
    suspend fun embedMany(texts: List<String>): List<List<Double>>
}

class EmbeddingProviderRegistry {
    private val providers = linkedMapOf<String, EmbeddingsProvider>()
    @Synchronized fun register(provider: EmbeddingsProvider) {
        require(provider.providerId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        check(providers.putIfAbsent(provider.providerId, provider) == null) {
            "Embedding provider already registered: ${provider.providerId}"
        }
    }
    @Synchronized fun get(providerId: String): EmbeddingsProvider? = providers[providerId]
    @Synchronized fun list(): List<EmbeddingsProvider> = providers.values.toList()
}

/** Selects a configured real embedding provider and keeps each dimension in its own store. */
class RagRuntime(
    private val providers: EmbeddingProviderRegistry,
    private val selectedProviderId: suspend () -> String,
    private val vectorStoreFactory: (EmbeddingsProvider) -> VectorStore,
) {
    private val stores = linkedMapOf<String, VectorStore>()

    suspend fun ingest(namespace: String, sourceId: String, text: String): Int {
        val provider = selected()
        return DocumentIngestor(store(provider), provider).ingest(namespace, sourceId, text)
    }

    suspend fun findRelevant(namespace: String, query: String, topK: Int = 5): List<SimilarChunk> {
        val provider = selected()
        return DocumentIngestor(store(provider), provider).findRelevant(namespace, query, topK)
    }

    private suspend fun selected(): EmbeddingsProvider {
        val id = selectedProviderId()
        return providers.get(id) ?: throw IllegalStateException("Embedding provider is not registered: $id")
    }

    @Synchronized
    private fun store(provider: EmbeddingsProvider): VectorStore =
        stores.getOrPut("${provider.providerId}:${provider.dimension}") { vectorStoreFactory(provider) }
}

/** Explicit fallback for keyword matching, never presented as a semantic model. */
class KeywordEmbedder(override val dimension: Int = 256) : EmbeddingsProvider {
    override val providerId = "keyword-fallback"
    override val quality = EmbeddingQuality.FALLBACK_KEYWORD
    override suspend fun embed(text: String): List<Double> = buildEmbedding(listOf(text))[0]
    override suspend fun embedMany(texts: List<String>): List<List<Double>> = buildEmbedding(texts)
    private fun buildEmbedding(texts: List<String>): List<List<Double>> = texts.map { text ->
        val vector = DoubleArray(dimension)
        Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).forEach {
            vector[(it.value.hashCode() and 0x7FFFFFFF) % dimension] += 1.0
        }
        normalize(vector)
    }
}

/** Real OpenAI-compatible embeddings endpoint; credentials are resolved lazily. */
class OpenAiEmbeddingsProvider(
    private val apiKeyProvider: suspend () -> String?,
    private val model: String = "text-embedding-3-small",
    override val dimension: Int = 1536,
    private val baseUrl: String = "https://api.openai.com/v1",
) : EmbeddingsProvider {
    override val providerId = "openai-embeddings"
    override val quality = EmbeddingQuality.MODEL_CLOUD
    private val client = SafeHttpClient.create(timeoutMs = 60_000)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(dimension in 1..65_536)
        require(baseUrl.startsWith("https://") && SafeHttpClient.normalizeUrl(baseUrl) == baseUrl.trimEnd('/')) {
            "Embedding endpoint must be a public HTTPS URL"
        }
    }

    override suspend fun embed(text: String): List<Double> = embedMany(listOf(text)).single()
    override suspend fun embedMany(texts: List<String>): List<List<Double>> = withContext(Dispatchers.IO) {
        require(texts.isNotEmpty() && texts.size <= MAX_BATCH)
        texts.forEach { require(it.isNotBlank() && it.length <= MAX_INPUT_CHARS) }
        val key = apiKeyProvider()?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("No embedding API key configured")
        val payload = json.encodeToString(EmbeddingRequest(model, texts, dimension))
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/embeddings")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Embedding request failed with HTTP ${response.code}")
            val decoded = json.decodeFromString<EmbeddingResponse>(
                response.body?.string() ?: throw IllegalStateException("Empty embedding response"),
            )
            require(decoded.data.size == texts.size) { "Embedding response count mismatch" }
            decoded.data.sortedBy { it.index }.map {
                require(it.embedding.size == dimension && it.embedding.all(Double::isFinite)) {
                    "Embedding response is invalid"
                }
                it.embedding
            }
        }
    }

    @Serializable
    private data class EmbeddingRequest(
        val model: String,
        val input: List<String>,
        val dimensions: Int,
        @SerialName("encoding_format") val encodingFormat: String = "float",
    )
    @Serializable private data class EmbeddingResponse(val data: List<EmbeddingItem> = emptyList())
    @Serializable private data class EmbeddingItem(val index: Int, val embedding: List<Double>)
    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val MAX_BATCH = 128
        const val MAX_INPUT_CHARS = 1_000_000
    }
}

data class VectorRecord(
    val id: String,
    val namespace: String,
    val vector: List<Double>,
    val source: String,
    val content: String,
    val contentHash: String,
)

interface VectorStore {
    val backendId: String
    suspend fun upsert(
        id: String,
        namespace: String,
        vector: List<Double>,
        source: String,
        content: String = "",
        contentHash: String = sha256Text(content),
    )
    suspend fun replaceSource(namespace: String, source: String, records: List<VectorRecord>)
    suspend fun search(namespace: String, query: List<Double>, topK: Int = 5): List<SimilarChunk>
    suspend fun delete(id: String)
    suspend fun clear(namespace: String)
}

data class SimilarChunk(
    val id: String,
    val namespace: String,
    val source: String,
    val score: Double,
    val content: String = "",
    val contentHash: String = "",
)

class InMemoryVectorStore(private val dimension: Int = 256) : VectorStore {
    override val backendId = "in-memory"
    private data class Item(
        val namespace: String,
        val source: String,
        val content: String,
        val contentHash: String,
        val vector: List<Double>,
    )
    private val lock = Any()
    private val items = HashMap<String, Item>()

    override suspend fun upsert(
        id: String, namespace: String, vector: List<Double>, source: String, content: String, contentHash: String,
    ) {
        validateVector(vector, dimension)
        synchronized(lock) { items[id] = Item(namespace, source, content, contentHash, vector.toList()) }
    }

    override suspend fun replaceSource(namespace: String, source: String, records: List<VectorRecord>) {
        records.forEach {
            require(it.namespace == namespace && it.source == source)
            validateVector(it.vector, dimension)
        }
        synchronized(lock) {
            items.entries.removeAll { it.value.namespace == namespace && it.value.source == source }
            records.forEach {
                items[it.id] = Item(namespace, source, it.content, it.contentHash, it.vector.toList())
            }
        }
    }

    override suspend fun search(namespace: String, query: List<Double>, topK: Int): List<SimilarChunk> {
        validateSearch(query, dimension, topK)
        val snapshot = synchronized(lock) { items.toMap() }
        return rank(snapshot.asSequence().filter { it.value.namespace == namespace }.map { (id, item) ->
            SimilarChunk(id, namespace, item.source, cosine(item.vector, query), item.content, item.contentHash)
        }, topK)
    }

    override suspend fun delete(id: String) { synchronized(lock) { items.remove(id) } }
    override suspend fun clear(namespace: String) {
        synchronized(lock) { items.entries.removeAll { it.value.namespace == namespace } }
    }
    val size: Int get() = synchronized(lock) { items.size }
}

/**
 * Durable SQLite vector backend. Search is exact cosine over a bounded
 * namespace snapshot; sqlite-vector or a remote backend can implement the same interface.
 */
class SQLiteVectorStore(
    context: Context,
    private val dimension: Int,
    databaseName: String = "aegis_vectors.db",
) : VectorStore, AutoCloseable {
    override val backendId = "sqlite-exact-cosine"
    private val helper = VectorDatabase(context.applicationContext, databaseName)
    init { require(dimension in 1..65_536) }

    override suspend fun upsert(
        id: String, namespace: String, vector: List<Double>, source: String, content: String, contentHash: String,
    ) {
        withContext(Dispatchers.IO) {
            validateVector(vector, dimension)
            helper.writableDatabase.insertWithOnConflict(
                TABLE, null, values(id, namespace, vector, source, content, contentHash), SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    override suspend fun replaceSource(namespace: String, source: String, records: List<VectorRecord>) {
        withContext(Dispatchers.IO) {
            records.forEach {
                require(it.namespace == namespace && it.source == source)
                validateVector(it.vector, dimension)
            }
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.delete(TABLE, "namespace = ? AND source = ?", arrayOf(namespace, source))
                records.forEach {
                    db.insertOrThrow(TABLE, null, values(it.id, namespace, it.vector, source, it.content, it.contentHash))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override suspend fun search(namespace: String, query: List<Double>, topK: Int): List<SimilarChunk> =
        withContext(Dispatchers.IO) {
            validateSearch(query, dimension, topK)
            val rows = mutableListOf<SimilarChunk>()
            helper.readableDatabase.query(
                TABLE,
                arrayOf("id", "source", "content", "content_hash", "vector"),
                "namespace = ?",
                arrayOf(namespace),
                null, null, null,
                MAX_NAMESPACE_ROWS.toString(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += SimilarChunk(
                        id = cursor.getString(0),
                        namespace = namespace,
                        source = cursor.getString(1),
                        content = cursor.getString(2),
                        contentHash = cursor.getString(3),
                        score = cosine(decodeVector(cursor.getBlob(4), dimension), query),
                    )
                }
            }
            rank(rows.asSequence(), topK)
        }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { helper.writableDatabase.delete(TABLE, "id = ?", arrayOf(id)) }
    }
    override suspend fun clear(namespace: String) {
        withContext(Dispatchers.IO) { helper.writableDatabase.delete(TABLE, "namespace = ?", arrayOf(namespace)) }
    }
    override fun close() = helper.close()

    private fun values(
        id: String, namespace: String, vector: List<Double>, source: String, content: String, contentHash: String,
    ) = ContentValues().apply {
        put("id", id)
        put("namespace", namespace)
        put("dimension", dimension)
        put("vector", encodeVector(vector))
        put("source", source)
        put("content", content)
        put("content_hash", contentHash)
        put("updated_at", System.currentTimeMillis())
    }

    private class VectorDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $TABLE (
                    id TEXT PRIMARY KEY NOT NULL,
                    namespace TEXT NOT NULL,
                    dimension INTEGER NOT NULL,
                    vector BLOB NOT NULL,
                    source TEXT NOT NULL,
                    content TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX vector_namespace_source ON $TABLE(namespace, source)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw IllegalStateException("Vector database migration $oldVersion->$newVersion is missing")
        }
    }
    private companion object {
        const val TABLE = "vector_chunks"
        const val MAX_NAMESPACE_ROWS = 100_000
    }
}

class DocumentIngestor(
    private val vectorStore: VectorStore,
    private val embedder: EmbeddingsProvider,
    private val chunkMaxChars: Int = 800,
    private val chunkOverlapChars: Int = 100,
) {
    init {
        require(chunkMaxChars in 100..32_000)
        require(chunkOverlapChars in 0 until chunkMaxChars)
    }
    suspend fun ingest(namespace: String, sourceId: String, text: String): Int {
        require(namespace.isNotBlank() && sourceId.isNotBlank())
        if (SecretSanitizer.containsSecret(text)) {
            throw IllegalArgumentException("Refusing to index text containing a detected secret")
        }
        val chunks = chunk(text)
        val vectors = if (chunks.isEmpty()) emptyList() else embedder.embedMany(chunks)
        require(vectors.size == chunks.size)
        vectorStore.replaceSource(
            namespace,
            sourceId,
            chunks.mapIndexed { index, chunk ->
                require(vectors[index].size == embedder.dimension) { "Embedding dimension mismatch" }
                VectorRecord(
                    id = "$sourceId#$index",
                    namespace = namespace,
                    vector = vectors[index],
                    source = sourceId,
                    content = chunk,
                    contentHash = sha256Text(chunk),
                )
            },
        )
        return chunks.size
    }
    suspend fun findRelevant(namespace: String, query: String, topK: Int = 5): List<SimilarChunk> =
        vectorStore.search(namespace, embedder.embed(query), topK)

    private fun chunk(text: String): List<String> {
        if (text.length <= chunkMaxChars) return listOf(text.trim()).filter(String::isNotEmpty)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + chunkMaxChars, text.length)
            text.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(chunks::add)
            start = if (end >= text.length) text.length else start + chunkMaxChars - chunkOverlapChars
        }
        return chunks
    }
}

data class Citation(
    val sourceId: String,
    val snippet: String,
    val score: Double,
    val contentHash: String? = null,
)

private fun validateVector(vector: List<Double>, dimension: Int) {
    require(vector.size == dimension) { "vector dimension ${vector.size} != $dimension" }
    require(vector.all(Double::isFinite)) { "vector contains non-finite values" }
}
private fun validateSearch(query: List<Double>, dimension: Int, topK: Int) {
    validateVector(query, dimension)
    require(topK in 1..100)
}
private fun cosine(vector: List<Double>, query: List<Double>): Double {
    val aNorm = sqrt(vector.sumOf { it * it })
    val bNorm = sqrt(query.sumOf { it * it })
    if (aNorm == 0.0 || bNorm == 0.0) return 0.0
    return (vector.indices.sumOf { vector[it] * query[it] } / (aNorm * bNorm)).coerceIn(-1.0, 1.0)
}
private fun rank(items: Sequence<SimilarChunk>, topK: Int): List<SimilarChunk> =
    items.sortedWith(compareByDescending<SimilarChunk> { it.score }.thenBy { it.id }).take(topK).toList()
private fun normalize(vector: DoubleArray): List<Double> {
    val norm = sqrt(vector.sumOf { it * it })
    return if (norm == 0.0) vector.toList() else vector.map { it / norm }
}
private fun encodeVector(vector: List<Double>): ByteArray =
    ByteBuffer.allocate(vector.size * Double.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        vector.forEach(::putDouble)
    }.array()
private fun decodeVector(bytes: ByteArray, dimension: Int): List<Double> {
    require(bytes.size == dimension * Double.SIZE_BYTES) { "Stored vector is corrupt" }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return List(dimension) { buffer.double }
}
internal fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
