package com.mtzallqmy.aiagent.provider.local

import com.mtzallqmy.aiagent.local_llm.DiscoveredLocalModel
import com.mtzallqmy.aiagent.local_llm.LocalGenerationEvent
import com.mtzallqmy.aiagent.local_llm.LocalGenerationOptions
import com.mtzallqmy.aiagent.local_llm.LocalModelBackend
import com.mtzallqmy.aiagent.local_llm.LocalModelLoadAssessment
import com.mtzallqmy.aiagent.local_llm.LocalModelLoadOptions
import com.mtzallqmy.aiagent.local_llm.LocalModelReference
import com.mtzallqmy.aiagent.local_llm.LocalModelState
import com.mtzallqmy.aiagent.model.AiModel
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.GenerationRequest
import com.mtzallqmy.aiagent.model.MessageRole
import com.mtzallqmy.aiagent.model.ModelCapabilities
import com.mtzallqmy.aiagent.model.ModelDeployment
import com.mtzallqmy.aiagent.model.ModelRoutingMetadata
import com.mtzallqmy.aiagent.model.ModelSpeedTier
import com.mtzallqmy.aiagent.model.ProviderError
import com.mtzallqmy.aiagent.providers.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Real on-device provider backed by the NDK/JNI llama.cpp runtime. */
class LocalProvider(
    private val backend: LocalModelBackend,
    private val defaultLoadOptions: LocalModelLoadOptions = LocalModelLoadOptions(),
) : AiProvider {
    override val providerId: String = PROVIDER_ID
    override val name: String = "Local (llama.cpp)"

    private val discoveredById = ConcurrentHashMap<String, DiscoveredLocalModel>()

    override suspend fun listModels(): Result<List<AiModel>> = runCatching {
        backend.discoverModels().map { discovered ->
            val id = modelId(discovered.reference)
            discoveredById[id] = discovered
            AiModel(
                id = id,
                name = discovered.metadata.name ?: "Local GGUF ${id.takeLast(8)}",
                providerId = providerId,
                capabilities = ModelCapabilities(
                    chat = true,
                    streaming = true,
                    toolCalling = false,
                    vision = false,
                    contextWindow = discovered.metadata.trainedContextSize
                        ?.coerceIn(256, Int.MAX_VALUE.toLong())
                        ?.toInt()
                        ?: defaultLoadOptions.contextSize,
                    maxOutputTokens = 8192,
                ),
                routing = ModelRoutingMetadata(
                    deployment = ModelDeployment.LOCAL,
                    speedTier = ModelSpeedTier.BALANCED,
                    // An operator may override routing metadata later; a filename is not
                    // treated as proof that a model is coding-optimized.
                    codingOptimized = false,
                ),
            )
        }
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        if (backend.discoverModels().isEmpty()) {
            throw ProviderError.ConfigurationError("No readable GGUF model exists in configured local model roots")
        }
    }

    /**
     * Performs the required preflight without loading. UI/security code can display
     * blockers and warnings before calling [loadAssessedModel].
     */
    suspend fun assessModel(
        modelId: String,
        options: LocalModelLoadOptions = defaultLoadOptions,
    ): LocalModelLoadAssessment {
        val model = resolveModel(modelId)
        return backend.assessLoad(model.reference, options)
    }

    /** Warnings are never silently acknowledged by the provider. */
    suspend fun loadAssessedModel(
        modelId: String,
        assessment: LocalModelLoadAssessment,
        options: LocalModelLoadOptions = defaultLoadOptions,
        acknowledgeWarnings: Boolean = false,
    ) = backend.load(
        reference = resolveModel(modelId).reference,
        options = options,
        assessmentId = assessment.assessmentId,
        acknowledgeWarnings = acknowledgeWarnings,
    )

    suspend fun unload() = backend.unload()

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        val selected = runCatching { resolveModel(request.modelId) }.getOrElse { error ->
            emit(GenerationEvent.GenerationFailed(mapError(error)))
            return@flow
        }
        val ready = backend.state.value as? LocalModelState.Ready
        if (ready == null || ready.model.reference.canonicalPath != selected.reference.canonicalPath) {
            emit(
                GenerationEvent.GenerationFailed(
                    ProviderError.ConfigurationError(
                        "Selected local model is not loaded; run load assessment and explicitly acknowledge any warnings first",
                    ),
                ),
            )
            return@flow
        }
        if (request.tools.isNotEmpty()) {
            emit(
                GenerationEvent.GenerationFailed(
                    ProviderError.CapabilityError("This local text model does not advertise native tool calling"),
                ),
            )
            return@flow
        }

        val output = StringBuilder()
        emit(GenerationEvent.GenerationStarted)
        try {
            backend.generate(
                prompt = formatPrompt(request),
                options = LocalGenerationOptions(
                    temperature = request.temperature.toFloat(),
                    maxTokens = request.maxTokens ?: 512,
                ),
            ).collect { event ->
                when (event) {
                    is LocalGenerationEvent.Text -> {
                        output.append(event.value)
                        emit(GenerationEvent.TextDelta(event.value))
                    }
                    is LocalGenerationEvent.Completed -> emit(
                        GenerationEvent.Usage(
                            promptTokens = event.usage.promptTokens,
                            completionTokens = event.usage.generatedTokens,
                            totalCost = 0.0,
                        ),
                    )
                }
            }
            emit(GenerationEvent.GenerationCompleted(output.toString()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(GenerationEvent.GenerationFailed(mapError(error)))
        }
    }

    private suspend fun resolveModel(id: String): DiscoveredLocalModel {
        discoveredById[id]?.let { return it }
        listModels().getOrThrow()
        return discoveredById[id] ?: throw ProviderError.ModelNotFoundError(id)
    }

    private fun formatPrompt(request: GenerationRequest): String = buildString {
        request.messages.forEach { message ->
            val role = when (message.role) {
                MessageRole.SYSTEM -> "system"
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.TOOL -> "tool"
            }
            append("<|").append(role).append("|>\n")
            append(message.content).append('\n')
        }
        append("<|assistant|>\n")
    }

    private fun modelId(reference: LocalModelReference): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(reference.canonicalPath.encodeToByteArray())
        return "local-${bytes.take(12).joinToString("") { "%02x".format(it) }}"
    }

    private fun mapError(error: Throwable): ProviderError = when (error) {
        is ProviderError -> error
        is IllegalArgumentException -> ProviderError.ConfigurationError(error.message ?: "Invalid local model configuration")
        else -> ProviderError.ProviderError_(500, error.message ?: "Local inference failed")
    }

    companion object {
        const val PROVIDER_ID = "local"
    }
}
