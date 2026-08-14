package com.mtzallqmy.aiagent.agent

import com.mtzallqmy.aiagent.model.AiModel
import com.mtzallqmy.aiagent.model.DataSensitivity
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.GenerationRequest
import com.mtzallqmy.aiagent.model.MessageRole
import com.mtzallqmy.aiagent.model.ModelDeployment
import com.mtzallqmy.aiagent.model.ModelSpeedTier
import com.mtzallqmy.aiagent.model.ProviderError
import com.mtzallqmy.aiagent.model.WorkloadKind
import com.mtzallqmy.aiagent.providers.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

data class SmartRouterConfiguration(
    val enabled: suspend () -> Boolean,
    val selectedProviderId: suspend () -> String?,
    val selectedModelId: suspend () -> String?,
)

data class RoutingDecision(
    val providerId: String,
    val modelId: String,
    val reason: String,
    val local: Boolean,
)

/**
 * AiProvider facade that either performs capability-based selection or, when
 * disabled, strictly delegates to the user's selected provider/model.
 */
class SmartRoutingProvider(
    private val registry: ProviderRegistry,
    private val configuration: SmartRouterConfiguration,
) : AiProvider {
    override val providerId: String = PROVIDER_ID
    override val name: String = "Smart Router"

    private val mutableDecision = MutableStateFlow<RoutingDecision?>(null)
    val lastDecision = mutableDecision.asStateFlow()

    override suspend fun listModels(): Result<List<AiModel>> = runCatching {
        providerCatalog().flatMap { it.models }
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        if (!configuration.enabled()) {
            val selected = configuration.selectedProviderId()
                ?: throw ProviderError.ConfigurationError("No provider selected while Smart Router is disabled")
            requireDelegate(selected).testConnection().getOrThrow()
        } else if (providerCatalog().none { it.models.isNotEmpty() }) {
            throw ProviderError.RoutingError("No provider returned an available model")
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        try {
            val route = resolve(request)
            mutableDecision.value = route.decision
            route.provider.generate(request.copy(modelId = route.decision.modelId)).collect { emit(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(GenerationEvent.GenerationFailed(mapError(error)))
        }
    }

    internal suspend fun resolve(request: GenerationRequest): Route {
        if (!configuration.enabled()) return resolveDisabled(request)

        val hint = request.routingHint
        val workload = when (hint.workload) {
            WorkloadKind.AUTO -> inferNonSensitiveWorkload(request)
            else -> hint.workload
        }
        var candidates = providerCatalog().flatMap { catalog ->
            catalog.models.map { Candidate(catalog.provider, it) }
        }

        val localOnly = hint.sensitivity == DataSensitivity.SENSITIVE || hint.offline
        if (localOnly) candidates = candidates.filter { it.model.routing.deployment == ModelDeployment.LOCAL }
        if (hint.requiresVision) candidates = candidates.filter { it.model.capabilities.vision }
        if (workload == WorkloadKind.CODING) candidates = candidates.filter { it.model.routing.codingOptimized }
        if (request.tools.isNotEmpty()) candidates = candidates.filter { it.model.capabilities.toolCalling }
        if (hint.requiredContextTokens > 0) {
            candidates = candidates.filter { it.model.capabilities.contextWindow >= hint.requiredContextTokens }
        }

        if (candidates.isEmpty()) {
            val constraint = when {
                hint.sensitivity == DataSensitivity.SENSITIVE -> "No suitable local model for sensitive content; cloud fallback is prohibited"
                hint.offline -> "No suitable local model is available while offline"
                hint.requiresVision -> "No available model advertises vision support"
                workload == WorkloadKind.CODING -> "No available model is explicitly marked coding-optimized"
                request.tools.isNotEmpty() -> "No available model advertises tool calling"
                else -> "No model satisfies the routing constraints"
            }
            throw ProviderError.RoutingError(constraint)
        }

        val selected = candidates.maxWithOrNull(
            compareBy<Candidate> { score(it, request, workload) }
                .thenBy { -it.model.capabilities.contextWindow }
                .thenBy { it.provider.providerId }
                .thenBy { it.model.id },
        ) ?: throw ProviderError.RoutingError("No route could be selected")

        val reason = when {
            hint.sensitivity == DataSensitivity.SENSITIVE -> "sensitive-local"
            hint.offline -> "offline-local"
            hint.requiresVision && selected.model.routing.deployment == ModelDeployment.LOCAL -> "local-vision"
            hint.requiresVision -> "vision-capability"
            workload == WorkloadKind.CODING -> "coding-optimized"
            workload == WorkloadKind.SIMPLE -> "fast-model"
            hint.requiredContextTokens > 0 -> "context-capacity"
            else -> "general-capability"
        }
        return Route(
            provider = selected.provider,
            decision = RoutingDecision(
                providerId = selected.provider.providerId,
                modelId = selected.model.id,
                reason = reason,
                local = selected.model.routing.deployment == ModelDeployment.LOCAL,
            ),
        )
    }

    private suspend fun resolveDisabled(request: GenerationRequest): Route {
        val requestedProvider = request.routingHint.requestedProviderId
        val providerId = requestedProvider ?: configuration.selectedProviderId()
            ?: throw ProviderError.ConfigurationError("No provider selected while Smart Router is disabled")
        val provider = requireDelegate(providerId)
        val modelId = request.modelId.ifBlank { configuration.selectedModelId().orEmpty() }
        return Route(
            provider,
            RoutingDecision(providerId, modelId, "router-disabled-explicit-selection", providerId == "local"),
        )
    }

    private suspend fun providerCatalog(): List<ProviderModels> = registry.all()
        .filter { it.providerId != providerId }
        .mapNotNull { provider ->
            provider.listModels().getOrNull()?.let { ProviderModels(provider, it) }
        }

    private fun score(candidate: Candidate, request: GenerationRequest, workload: WorkloadKind): Int {
        val model = candidate.model
        var score = 0
        if (request.routingHint.preferLocal && model.routing.deployment == ModelDeployment.LOCAL) score += 80
        if (request.routingHint.requestedProviderId == candidate.provider.providerId) score += 120
        if (request.modelId.isNotBlank() && request.modelId == model.id) score += 140
        if (request.routingHint.requiresVision && model.routing.deployment == ModelDeployment.LOCAL) score += 40
        score += when (workload) {
            WorkloadKind.SIMPLE -> when (model.routing.speedTier) {
                ModelSpeedTier.FAST -> 60
                ModelSpeedTier.BALANCED -> 20
                ModelSpeedTier.QUALITY -> 0
            }
            WorkloadKind.GENERAL, WorkloadKind.AUTO -> when (model.routing.speedTier) {
                ModelSpeedTier.QUALITY -> 30
                ModelSpeedTier.BALANCED -> 20
                ModelSpeedTier.FAST -> 10
            }
            WorkloadKind.CODING -> 60
        }
        if (request.routingHint.requiredContextTokens > 0) {
            // Among adequate models, avoid allocating an unnecessarily huge context.
            score -= (model.capabilities.contextWindow / 100_000).coerceAtMost(20)
        }
        return score
    }

    /** Content inference is used only for workload/latency, never for sensitivity. */
    private fun inferNonSensitiveWorkload(request: GenerationRequest): WorkloadKind {
        val userText = request.messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        val codingSignals = listOf("```", " stacktrace", "exception", "compile", "function ", "class ", "gradle", "kotlin", "rust", "python")
        if (codingSignals.any { userText.contains(it, ignoreCase = true) }) return WorkloadKind.CODING
        return if (userText.length <= 280 && request.tools.isEmpty()) WorkloadKind.SIMPLE else WorkloadKind.GENERAL
    }

    private fun requireDelegate(id: String): AiProvider {
        if (id == providerId) throw ProviderError.ConfigurationError("Smart Router cannot delegate to itself")
        return registry.get(id) ?: throw ProviderError.ConfigurationError("Provider is not registered: $id")
    }

    private fun mapError(error: Throwable): ProviderError = when (error) {
        is ProviderError -> error
        else -> ProviderError.RoutingError(error.message ?: "Smart routing failed")
    }

    internal data class Route(val provider: AiProvider, val decision: RoutingDecision)
    private data class ProviderModels(val provider: AiProvider, val models: List<AiModel>)
    private data class Candidate(val provider: AiProvider, val model: AiModel)

    companion object {
        const val PROVIDER_ID = "smart-router"
    }
}
