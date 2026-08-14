package com.mtzallqmy.aiagent.local_llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LocalModelBackend {
    val state: StateFlow<LocalModelState>

    suspend fun discoverModels(): List<DiscoveredLocalModel>

    suspend fun assessLoad(
        reference: LocalModelReference,
        options: LocalModelLoadOptions,
    ): LocalModelLoadAssessment

    suspend fun load(
        reference: LocalModelReference,
        options: LocalModelLoadOptions,
        assessmentId: String,
        acknowledgeWarnings: Boolean = false,
    ): LoadedLocalModel

    suspend fun unload()

    fun generate(
        prompt: String,
        options: LocalGenerationOptions = LocalGenerationOptions(),
    ): Flow<LocalGenerationEvent>

    suspend fun cancelGeneration()

    /** Produces a real model embedding from the currently loaded GGUF model. */
    suspend fun embed(
        text: String,
        options: LocalEmbeddingOptions = LocalEmbeddingOptions(),
    ): LocalEmbedding = throw UnsupportedOperationException("This local backend does not support embeddings")
}

sealed interface LocalModelState {
    data object Idle : LocalModelState
    data class Loading(val path: String) : LocalModelState
    data class Ready(val model: LoadedLocalModel) : LocalModelState
    data class Generating(val model: LoadedLocalModel) : LocalModelState
    data class Embedding(val model: LoadedLocalModel) : LocalModelState
    data class Failed(val message: String) : LocalModelState
}

data class LocalModelReference(
    val canonicalPath: String,
    val expectedSha256: String? = null,
)

data class DiscoveredLocalModel(
    val reference: LocalModelReference,
    val fileSizeBytes: Long,
    val lastModifiedMillis: Long,
    val metadata: GgufModelMetadata,
)

data class GgufModelMetadata(
    val version: Int,
    val tensorCount: Long,
    val metadataCount: Long,
    val architecture: String?,
    val name: String?,
    val quantizationType: Long?,
    val trainedContextSize: Long?,
)

data class LocalModelLoadOptions(
    val contextSize: Int = 4096,
    val threads: Int = 4,
    val useMemoryMap: Boolean = true,
) {
    init {
        require(contextSize in 256..131_072) { "contextSize must be between 256 and 131072" }
        require(threads in 1..32) { "threads must be between 1 and 32" }
    }
}

data class LocalModelLoadAssessment(
    val assessmentId: String,
    val metadata: GgufModelMetadata,
    val fileSizeBytes: Long,
    val sha256: String,
    val estimatedPeakMemoryBytes: Long,
    val availableRamBytes: Long,
    val freeDiskBytes: Long,
    val blockers: List<String>,
    val warnings: List<String>,
) {
    val canLoad: Boolean get() = blockers.isEmpty()
}

data class LoadedLocalModel(
    val reference: LocalModelReference,
    val metadata: GgufModelMetadata,
    val sha256: String,
    val nativeDescription: String,
    val parameterCount: Long,
    val tensorBytes: Long,
    val embeddingDimension: Int,
    val options: LocalModelLoadOptions,
)

data class LocalGenerationOptions(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
    val seed: Int = -1,
) {
    init {
        require(temperature in 0.0f..2.0f) { "temperature must be between 0 and 2" }
        require(maxTokens in 1..8192) { "maxTokens must be between 1 and 8192" }
    }
}

sealed interface LocalGenerationEvent {
    data class Text(val value: String) : LocalGenerationEvent
    data class Completed(val usage: LocalTokenUsage) : LocalGenerationEvent
}

data class LocalTokenUsage(
    val promptTokens: Int,
    val generatedTokens: Int,
    val elapsedMillis: Long,
)

data class LocalEmbeddingOptions(
    val contextSize: Int = 512,
    val threads: Int = 4,
    val normalize: Boolean = true,
) {
    init {
        require(contextSize in 64..8192)
        require(threads in 1..32)
    }
}

data class LocalEmbedding(
    val values: List<Double>,
    val modelSha256: String,
    val elapsedMillis: Long,
)

class LocalModelLoadRejectedException(message: String) : IllegalStateException(message)
