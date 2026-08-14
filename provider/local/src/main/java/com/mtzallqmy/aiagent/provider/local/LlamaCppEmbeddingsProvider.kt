package com.mtzallqmy.aiagent.provider.local

import com.mtzallqmy.aiagent.local_llm.LocalEmbeddingOptions
import com.mtzallqmy.aiagent.local_llm.LocalModelBackend
import com.mtzallqmy.aiagent.memory.EmbeddingQuality
import com.mtzallqmy.aiagent.memory.EmbeddingsProvider

/** Semantic embeddings from the currently loaded GGUF model through llama.cpp JNI. */
class LlamaCppEmbeddingsProvider(
    private val backend: LocalModelBackend,
    private val options: LocalEmbeddingOptions = LocalEmbeddingOptions(),
) : EmbeddingsProvider {
    override val providerId = "llama-cpp-local-embeddings"
    override val quality = EmbeddingQuality.MODEL_LOCAL

    override val dimension: Int
        get() = when (val state = backend.state.value) {
            is com.mtzallqmy.aiagent.local_llm.LocalModelState.Ready -> state.model.embeddingDimension
            is com.mtzallqmy.aiagent.local_llm.LocalModelState.Embedding -> state.model.embeddingDimension
            else -> throw IllegalStateException("Load an embedding-capable GGUF model first")
        }

    override suspend fun embed(text: String): List<Double> {
        val embedding = backend.embed(text, options).values
        require(embedding.size == dimension) {
            "Configured dimension $dimension does not match GGUF output ${embedding.size}"
        }
        return embedding
    }

    override suspend fun embedMany(texts: List<String>): List<List<Double>> {
        require(texts.isNotEmpty() && texts.size <= 128)
        return texts.map { embed(it) }
    }
}
