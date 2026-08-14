package com.mtzallqmy.aiagent.agent

import com.mtzallqmy.aiagent.model.AiModel
import com.mtzallqmy.aiagent.model.ChatMessage
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.GenerationRequest
import com.mtzallqmy.aiagent.model.ModelCapabilities
import com.mtzallqmy.aiagent.model.ProviderError
import com.mtzallqmy.aiagent.model.ToolDescriptor
import com.mtzallqmy.aiagent.providers.AiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderRegistryTest {

    private fun fakeProvider(id: String, name: String = id) = object : AiProvider {
        override val providerId: String = id
        override val name: String = name
        override suspend fun listModels(): Result<List<AiModel>> = Result.success(emptyList())
        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)
        override fun generate(request: GenerationRequest): Flow<GenerationEvent> = emptyFlow()
    }

    @Test
    fun `register and get provider`() {
        val registry = ProviderRegistry()
        val provider = fakeProvider("openai")
        registry.register(provider)
        assertSame(provider, registry.get("openai"))
    }

    @Test
    fun `select returns registered provider`() {
        val registry = ProviderRegistry()
        val provider = fakeProvider("anthropic")
        registry.register(provider)
        assertSame(provider, registry.select("anthropic"))
    }

    @Test
    fun `select throws for unknown provider`() {
        val registry = ProviderRegistry()
        try {
            registry.select("unknown-provider")
            fail("Expected error for unregistered provider")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unknown-provider"))
        }
    }

    @Test
    fun `all returns all registered providers`() {
        val registry = ProviderRegistry()
        registry.register(fakeProvider("openai"))
        registry.register(fakeProvider("gemini"))
        val ids = registry.all().map { it.providerId }.toSet()
        assertEquals(setOf("openai", "gemini"), ids)
    }

    @Test
    fun `re-register replaces existing provider`() {
        val registry = ProviderRegistry()
        val first = fakeProvider("openai", name = "first")
        val second = fakeProvider("openai", name = "second")
        registry.register(first)
        registry.register(second)
        assertEquals(1, registry.all().size)
        assertEquals("second", registry.get("openai")?.name)
    }

    @Test
    fun `get returns null for missing provider`() {
        assertEquals(null, ProviderRegistry().get("missing"))
    }

    @Test
    fun `fake provider testConnection succeeds via runTest`() = runTest {
        val provider = fakeProvider("test")
        val result = provider.testConnection()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `ProviderError hierarchy carries typed reasons`() {
        val auth = ProviderError.AuthenticationError("bad key")
        assertTrue(auth is ProviderError)
        assertTrue(auth.message!!.contains("bad key"))

        val rate = ProviderError.RateLimitError(retryAfterSeconds = 60)
        assertTrue(rate.message!!.contains("60 s"))

        val http = ProviderError.ProviderError_(500, "internal")
        assertTrue(http.message!!.contains("500"))
    }

    @Test
    fun `models and messages are plain data classes`() {
        val model = AiModel(id = "claude-opus-4-7", name = "Claude Opus 4.7", providerId = "anthropic") // name param required
        assertEquals("claude-opus-4-7", model.id)
        assertEquals("anthropic", model.providerId)

        val copy = model.copy(name = "Renamed")
        assertFalse(model == copy)
    }

    @Test
    fun `chat message role and content roundtrip`() {
        val msg = ChatMessage(role = com.mtzallqmy.aiagent.model.MessageRole.USER, content = "hello")
        assertEquals("hello", msg.content)
        assertEquals(com.mtzallqmy.aiagent.model.MessageRole.USER, msg.role)
    }
}
