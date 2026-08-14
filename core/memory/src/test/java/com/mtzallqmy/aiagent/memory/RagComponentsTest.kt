package com.mtzallqmy.aiagent.memory

import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RagComponentsTest {

    @Test
    fun `embedder is deterministic and dimension-consistent`() = runBlocking {
        val embedder = KeywordEmbedder(dimension = 64)
        assertEquals(EmbeddingQuality.FALLBACK_KEYWORD, embedder.quality)
        val v1 = embedder.embed("Hello world test one")
        val v2 = embedder.embed("Hello world test one")
        assertEquals(64, v1.size)
        assertEquals(v1, v2)
        // Different texts should differ (not perfectly, but usually)
        val v3 = embedder.embed("Completely unrelated phrase about cats and dogs")
        assertFalse("different texts should produce different vectors", v1 == v3)
    }

    @Test
    fun `vector store cosine search ranks most similar first`() = runBlocking {
        val store = InMemoryVectorStore(dimension = 4)
        store.upsert("a", "ns", listOf(1.0, 0.0, 0.0, 0.0), "src-a")
        store.upsert("b", "ns", listOf(0.9, 0.1, 0.0, 0.0), "src-b")
        store.upsert("c", "ns", listOf(0.0, 0.0, 0.0, 1.0), "src-c")
        val results = store.search("ns", listOf(1.0, 0.0, 0.0, 0.0), topK = 2)
        assertEquals(2, results.size)
        assertEquals("src-a", results[0].source)
        assertEquals("src-b", results[1].source)
        assertTrue(results[0].score >= results[1].score)
    }

    @Test
    fun `vector store isolates namespaces and rejects wrong dimensions`() = runBlocking {
        val store = InMemoryVectorStore(dimension = 4)
        store.upsert("x", "ns1", listOf(1.0, 0.0, 0.0, 0.0), "s1")
        store.upsert("y", "ns2", listOf(1.0, 0.0, 0.0, 0.0), "s2")
        val r1 = store.search("ns1", listOf(1.0, 0.0, 0.0, 0.0), topK = 5)
        assertEquals(1, r1.size)
        assertEquals("s1", r1[0].source)
        val ex = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                store.upsert("z", "ns1", listOf(1.0, 0.0, 0.0), "s3")
            }
        }
        assertTrue(ex.message!!.contains("dimension"))
    }

    @Test
    fun `ingestor chunks and indexes a long document`() = runBlocking {
        val store = InMemoryVectorStore(dimension = 64)
        val ingestor = DocumentIngestor(store, KeywordEmbedder(dimension = 64),
            chunkMaxChars = 100, chunkOverlapChars = 20)
        val text = "word ".repeat(200) // ~1000 chars -> multiple chunks
        val count = ingestor.ingest("docs", "doc1", text)
        assertTrue(count > 1)
        val hits = ingestor.findRelevant("docs", "word", topK = 3)
        assertTrue(hits.isNotEmpty())
        hits.forEach { assertEquals("doc1", it.source) }
    }

    @Test
    fun `ingestor refuses to index text containing secrets`() = runBlocking {
        val store = InMemoryVectorStore(dimension = 64)
        val ingestor = DocumentIngestor(store, KeywordEmbedder(dimension = 64))
        val withSecret = "Hello world. sk-1234567890abcdef1234567890abcdef is my key"
        val ex = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                ingestor.ingest("docs", "doc2", withSecret)
            }
        }
        assertTrue(ex.message!!.contains("secret"))
    }

    @Test
    fun `reingestion atomically replaces stale source chunks and retains content references`() = runBlocking {
        val store = InMemoryVectorStore(dimension = 64)
        val ingestor = DocumentIngestor(
            store,
            KeywordEmbedder(dimension = 64),
            chunkMaxChars = 100,
            chunkOverlapChars = 20,
        )
        assertTrue(ingestor.ingest("docs", "same-source", "first ".repeat(100)) > 1)
        assertTrue(store.size > 1)

        assertEquals(1, ingestor.ingest("docs", "same-source", "replacement content"))
        assertEquals(1, store.size)
        val result = ingestor.findRelevant("docs", "replacement", 1).single()
        assertEquals("replacement content", result.content)
        assertTrue(result.contentHash.isNotBlank())
    }

    @Test
    fun `vector store rejects non finite values and invalid topK`() = runBlocking {
        val store = InMemoryVectorStore(dimension = 2)
        assertFailsWith<IllegalArgumentException> {
            store.upsert("bad", "ns", listOf(Double.NaN, 0.0), "source")
        }
        assertFailsWith<IllegalArgumentException> {
            store.search("ns", listOf(1.0, 0.0), topK = 0)
        }
        Unit
    }

    @Test
    fun `rag runtime uses only explicitly selected provider and dimension store`() = runBlocking {
        val keyword = KeywordEmbedder(dimension = 32)
        val registry = EmbeddingProviderRegistry().apply { register(keyword) }
        var selected = "missing"
        val runtime = RagRuntime(
            providers = registry,
            selectedProviderId = { selected },
            vectorStoreFactory = { InMemoryVectorStore(it.dimension) },
        )
        assertFailsWith<IllegalStateException> {
            runtime.ingest("docs", "source", "content")
        }

        selected = keyword.providerId
        assertEquals(1, runtime.ingest("docs", "source", "content"))
        assertEquals("source", runtime.findRelevant("docs", "content", 1).single().source)
    }
}
