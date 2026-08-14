package com.mtzallqmy.aiagent.agent

import com.mtzallqmy.aiagent.model.AiModel
import com.mtzallqmy.aiagent.model.ChatMessage
import com.mtzallqmy.aiagent.model.DataSensitivity
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.GenerationRequest
import com.mtzallqmy.aiagent.model.MessageRole
import com.mtzallqmy.aiagent.model.ModelCapabilities
import com.mtzallqmy.aiagent.model.ModelDeployment
import com.mtzallqmy.aiagent.model.ModelRoutingMetadata
import com.mtzallqmy.aiagent.model.ModelSpeedTier
import com.mtzallqmy.aiagent.model.ProviderError
import com.mtzallqmy.aiagent.model.RoutingHint
import com.mtzallqmy.aiagent.model.WorkloadKind
import com.mtzallqmy.aiagent.providers.AiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRoutingProviderTest {
    @Test
    fun `sensitive content is restricted to local models`() = runTest {
        val router = router(
            enabled = true,
            providers = listOf(
                provider("cloud", model("cloud-fast", "cloud", speed = ModelSpeedTier.FAST)),
                provider("local", model("local-model", "local", local = true)),
            ),
        )

        val route = router.resolve(request(RoutingHint(sensitivity = DataSensitivity.SENSITIVE)))

        assertEquals("local", route.decision.providerId)
        assertEquals("sensitive-local", route.decision.reason)
    }

    @Test
    fun `sensitive content never falls back to cloud`() = runTest {
        val router = router(true, listOf(provider("cloud", model("cloud", "cloud"))))

        val error = try {
            router.resolve(request(RoutingHint(sensitivity = DataSensitivity.SENSITIVE)))
            throw AssertionError("Expected routing error")
        } catch (error: ProviderError.RoutingError) {
            error
        }

        assertTrue(error.reason.contains("cloud fallback is prohibited"))
    }

    @Test
    fun `offline route selects local`() = runTest {
        val router = router(
            true,
            listOf(
                provider("cloud", model("cloud", "cloud")),
                provider("local", model("local", "local", local = true)),
            ),
        )
        assertEquals("local", router.resolve(request(RoutingHint(offline = true))).decision.providerId)
    }

    @Test
    fun `vision route requires advertised vision capability and prefers local`() = runTest {
        val router = router(
            true,
            listOf(
                provider("cloud", model("cloud-vision", "cloud", vision = true)),
                provider("local", model("local-vision", "local", local = true, vision = true)),
            ),
        )
        val route = router.resolve(request(RoutingHint(requiresVision = true, preferLocal = true)))
        assertEquals("local-vision", route.decision.modelId)
    }

    @Test
    fun `coding route requires explicit coding metadata`() = runTest {
        val router = router(
            true,
            listOf(
                provider("general", model("general", "general")),
                provider("coding", model("coder", "coding", coding = true)),
            ),
        )
        assertEquals(
            "coder",
            router.resolve(request(RoutingHint(workload = WorkloadKind.CODING))).decision.modelId,
        )
    }

    @Test
    fun `simple route selects fast model`() = runTest {
        val router = router(
            true,
            listOf(
                provider("quality", model("quality", "quality", speed = ModelSpeedTier.QUALITY)),
                provider("fast", model("fast", "fast", speed = ModelSpeedTier.FAST)),
            ),
        )
        assertEquals(
            "fast",
            router.resolve(request(RoutingHint(workload = WorkloadKind.SIMPLE))).decision.modelId,
        )
    }

    @Test
    fun `long context excludes insufficient models`() = runTest {
        val router = router(
            true,
            listOf(
                provider("short", model("short", "short", context = 4096)),
                provider("long", model("long", "long", context = 200_000)),
            ),
        )
        assertEquals(
            "long",
            router.resolve(request(RoutingHint(requiredContextTokens = 100_000))).decision.modelId,
        )
    }

    @Test
    fun `disabled router delegates exact selected provider and model`() = runTest {
        val selected = provider("selected", model("selected-model", "selected"))
        val router = router(false, listOf(selected), selectedProvider = "selected", selectedModel = "selected-model")
        val route = router.resolve(request())
        assertEquals("selected", route.decision.providerId)
        assertEquals("selected-model", route.decision.modelId)
        assertEquals("router-disabled-explicit-selection", route.decision.reason)
    }

    private fun router(
        enabled: Boolean,
        providers: List<AiProvider>,
        selectedProvider: String? = null,
        selectedModel: String? = null,
    ): SmartRoutingProvider {
        val registry = ProviderRegistry().apply { providers.forEach(::register) }
        return SmartRoutingProvider(
            registry,
            SmartRouterConfiguration(
                enabled = { enabled },
                selectedProviderId = { selectedProvider },
                selectedModelId = { selectedModel },
            ),
        )
    }

    private fun request(hint: RoutingHint = RoutingHint()) = GenerationRequest(
        messages = listOf(ChatMessage(role = MessageRole.USER, content = "Hello")),
        modelId = "",
        routingHint = hint,
    )

    private fun model(
        id: String,
        provider: String,
        local: Boolean = false,
        vision: Boolean = false,
        coding: Boolean = false,
        context: Int = 8192,
        speed: ModelSpeedTier = ModelSpeedTier.BALANCED,
    ) = AiModel(
        id,
        id,
        provider,
        ModelCapabilities(streaming = true, vision = vision, contextWindow = context),
        ModelRoutingMetadata(
            deployment = if (local) ModelDeployment.LOCAL else ModelDeployment.CLOUD,
            speedTier = speed,
            codingOptimized = coding,
        ),
    )

    private fun provider(id: String, vararg models: AiModel) = object : AiProvider {
        override val providerId = id
        override val name = id
        override suspend fun listModels() = Result.success(models.toList())
        override suspend fun testConnection() = Result.success(Unit)
        override fun generate(request: GenerationRequest): Flow<GenerationEvent> = emptyFlow()
    }
}
