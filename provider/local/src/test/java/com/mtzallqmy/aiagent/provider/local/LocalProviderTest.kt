package com.mtzallqmy.aiagent.provider.local

import com.mtzallqmy.aiagent.local_llm.DiscoveredLocalModel
import com.mtzallqmy.aiagent.local_llm.GgufModelMetadata
import com.mtzallqmy.aiagent.local_llm.LoadedLocalModel
import com.mtzallqmy.aiagent.local_llm.LocalGenerationEvent
import com.mtzallqmy.aiagent.local_llm.LocalGenerationOptions
import com.mtzallqmy.aiagent.local_llm.LocalModelBackend
import com.mtzallqmy.aiagent.local_llm.LocalModelLoadAssessment
import com.mtzallqmy.aiagent.local_llm.LocalModelLoadOptions
import com.mtzallqmy.aiagent.local_llm.LocalModelReference
import com.mtzallqmy.aiagent.local_llm.LocalModelState
import com.mtzallqmy.aiagent.local_llm.LocalTokenUsage
import com.mtzallqmy.aiagent.model.ChatMessage
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.GenerationRequest
import com.mtzallqmy.aiagent.model.MessageRole
import com.mtzallqmy.aiagent.model.ModelDeployment
import com.mtzallqmy.aiagent.model.ProviderError
import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.model.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProviderTest {
    @Test
    fun `local catalog advertises truthful capabilities`() = runTest {
        val provider = LocalProvider(FakeLocalBackend())
        val model = provider.listModels().getOrThrow().single()

        assertEquals(LocalProvider.PROVIDER_ID, model.providerId)
        assertEquals(ModelDeployment.LOCAL, model.routing.deployment)
        assertTrue(model.capabilities.streaming)
        assertFalse(model.capabilities.toolCalling)
        assertFalse(model.capabilities.vision)
        assertEquals(8192, model.capabilities.contextWindow)
        assertFalse(model.id.contains('/'))
    }

    @Test
    fun `warning acknowledgement is explicit during assessed load`() = runTest {
        val backend = FakeLocalBackend(warnings = listOf("memory warning"))
        val provider = LocalProvider(backend)
        val modelId = provider.listModels().getOrThrow().single().id
        val assessment = provider.assessModel(modelId)

        provider.loadAssessedModel(modelId, assessment)

        assertFalse(backend.lastAcknowledgedWarnings)
    }

    @Test
    fun `generation streams normalized events and usage`() = runTest {
        val backend = FakeLocalBackend()
        val provider = LocalProvider(backend)
        val modelId = provider.listModels().getOrThrow().single().id
        val assessment = provider.assessModel(modelId)
        provider.loadAssessedModel(modelId, assessment)

        val events = provider.generate(request(modelId)).toList()

        assertEquals(GenerationEvent.GenerationStarted, events[0])
        assertEquals(GenerationEvent.TextDelta("hello"), events[1])
        assertEquals(GenerationEvent.Usage(3, 1, 0.0), events[2])
        assertEquals(GenerationEvent.GenerationCompleted("hello"), events[3])
        assertTrue(backend.lastPrompt.contains("<|user|>\nHello"))
    }

    @Test
    fun `tool calls are rejected instead of silently ignored`() = runTest {
        val backend = FakeLocalBackend()
        val provider = LocalProvider(backend)
        val modelId = provider.listModels().getOrThrow().single().id
        provider.loadAssessedModel(modelId, provider.assessModel(modelId))
        val tool = ToolDescriptor("read", "Read", "Read", "{}", "{}", RiskLevel.READ)

        val event = provider.generate(request(modelId).copy(tools = listOf(tool))).toList().single()

        assertTrue((event as GenerationEvent.GenerationFailed).error is ProviderError.CapabilityError)
    }

    private fun request(modelId: String) = GenerationRequest(
        messages = listOf(ChatMessage(role = MessageRole.USER, content = "Hello")),
        modelId = modelId,
        maxTokens = 10,
    )
}

private class FakeLocalBackend(
    private val warnings: List<String> = emptyList(),
) : LocalModelBackend {
    private val reference = LocalModelReference("/private/models/model.gguf")
    private val metadata = GgufModelMetadata(3, 1, 2, "llama", "Test Local", 7, 8192)
    private val mutableState = MutableStateFlow<LocalModelState>(LocalModelState.Idle)
    override val state = mutableState
    var lastAcknowledgedWarnings = false
    var lastPrompt = ""

    override suspend fun discoverModels() = listOf(
        DiscoveredLocalModel(reference, 2_000_000, 1, metadata),
    )

    override suspend fun assessLoad(reference: LocalModelReference, options: LocalModelLoadOptions) =
        LocalModelLoadAssessment(
            "assessment",
            metadata,
            2_000_000,
            "a".repeat(64),
            3_000_000,
            10_000_000,
            10_000_000,
            emptyList(),
            warnings,
        )

    override suspend fun load(
        reference: LocalModelReference,
        options: LocalModelLoadOptions,
        assessmentId: String,
        acknowledgeWarnings: Boolean,
    ): LoadedLocalModel {
        lastAcknowledgedWarnings = acknowledgeWarnings
        val loaded = LoadedLocalModel(reference, metadata, "a".repeat(64), "Test", 1, 2_000_000, 2, options)
        mutableState.value = LocalModelState.Ready(loaded)
        return loaded
    }

    override suspend fun unload() {
        mutableState.value = LocalModelState.Idle
    }

    override fun generate(prompt: String, options: LocalGenerationOptions): Flow<LocalGenerationEvent> {
        lastPrompt = prompt
        return flowOf(
            LocalGenerationEvent.Text("hello"),
            LocalGenerationEvent.Completed(LocalTokenUsage(3, 1, 10)),
        )
    }

    override suspend fun cancelGeneration() = Unit
}
