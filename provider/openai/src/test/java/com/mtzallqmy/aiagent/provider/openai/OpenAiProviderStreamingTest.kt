package com.mtzallqmy.aiagent.provider.openai

import com.mtzallqmy.aiagent.model.ChatMessage
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.GenerationRequest
import com.mtzallqmy.aiagent.model.MessageRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiProviderStreamingTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `stream completes and keeps tool id across argument chunks`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"echo","arguments":""}}]},"finish_reason":null}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"value\":"}}]},"finish_reason":null}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )
        val provider = OpenAiProvider(
            apiKeyProvider = { "test-key" },
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            client = OkHttpClient(),
        )

        val events = withTimeout(5_000) {
            provider.generate(
                GenerationRequest(
                    messages = listOf(ChatMessage(role = MessageRole.USER, content = "call echo")),
                    modelId = "test-model",
                ),
            ).toList()
        }

        val started = events.filterIsInstance<GenerationEvent.ToolCallStarted>()
        val args = events.filterIsInstance<GenerationEvent.ToolCallArgumentsDelta>()
        assertEquals(listOf(GenerationEvent.ToolCallStarted("call_1", "echo")), started)
        assertTrue(args.isNotEmpty())
        assertTrue(args.all { it.callId == "call_1" })
        assertEquals("{\"value\":1}", args.joinToString(separator = "") { it.argsFragment })
        assertEquals(1, events.filterIsInstance<GenerationEvent.GenerationCompleted>().size)
    }
}
