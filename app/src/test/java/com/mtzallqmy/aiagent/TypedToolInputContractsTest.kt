package com.mtzallqmy.aiagent

import com.mtzallqmy.aiagent.feature.device.AppPackageInput
import com.mtzallqmy.aiagent.feature.device.AppsListInput
import com.mtzallqmy.aiagent.tool.clipboard.ClipboardReadInput
import com.mtzallqmy.aiagent.tool.clipboard.ClipboardWriteInput
import com.mtzallqmy.aiagent.tool.filesystem.FileEditInput
import com.mtzallqmy.aiagent.tool.filesystem.GitDiffInput
import com.mtzallqmy.aiagent.tool.filesystem.RepoMapInput
import com.mtzallqmy.aiagent.tool.http.HttpRequestInput
import com.mtzallqmy.aiagent.tool.http.N8nTriggerInput
import com.mtzallqmy.aiagent.tool.ssh.SshExecInput
import com.mtzallqmy.aiagent.tool.ssh.SshToolSet
import com.mtzallqmy.aiagent.tool.terminal.TerminalCreateInput
import com.mtzallqmy.aiagent.tool.terminal.TerminalExecInput
import com.mtzallqmy.aiagent.tool.terminal.TerminalKillInput
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypedToolInputContractsTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun everyProductionToolInputDecodesThroughConcreteSerializer() {
        assertEquals("https://example.com", json.decodeFromString(HttpRequestInput.serializer(), """{"url":"https://example.com"}""").url)
        json.decodeFromString(ClipboardReadInput.serializer(), "{}")
        assertEquals("copy", json.decodeFromString(ClipboardWriteInput.serializer(), """{"text":"copy"}""").text)
        assertNull(json.decodeFromString(AppsListInput.serializer(), "{}").query)
        assertEquals("com.example", json.decodeFromString(AppPackageInput.serializer(), """{"packageName":"com.example"}""").packageName)
        json.decodeFromString(TerminalCreateInput.serializer(), "{}")
        assertEquals(5_000L, json.decodeFromString(TerminalExecInput.serializer(), """{"command":"pwd","timeout_ms":5000}""").timeoutMs)
        assertEquals("session", json.decodeFromString(TerminalKillInput.serializer(), """{"sessionId":"session"}""").sessionId)
        assertEquals("strict", json.decodeFromString(SshExecInput.serializer(), """{"host":"example.com","user":"agent","command":"pwd"}""").hostKeyPolicy)
        assertEquals("src", json.decodeFromString(RepoMapInput.serializer(), """{"subdir":"src"}""").subdir)
        assertEquals("replace", json.decodeFromString(FileEditInput.serializer(), """{"path":"a.txt","operation":"replace","find":"a","replace":"b"}""").operation)
        json.decodeFromString(GitDiffInput.serializer(), "{}")
        assertEquals("https://n8n.example/webhook/id", json.decodeFromString(N8nTriggerInput.serializer(), """{"webhookUrl":"https://n8n.example/webhook/id"}""").webhookUrl)
    }

    @Test(expected = SerializationException::class)
    fun unknownFieldsFailClosed() {
        json.decodeFromString(HttpRequestInput.serializer(), """{"url":"https://example.com","unexpected":true}""")
    }

    @Test(expected = SerializationException::class)
    fun wrongFieldTypesFailClosed() {
        json.decodeFromString(TerminalExecInput.serializer(), """{"command":42}""")
    }

    @Test
    fun sshConnectionSpecUsesTypedValuesAndStrictDefault() {
        val spec = SshToolSet().validateConnectionArgs(
            SshExecInput(host = "example.com", user = "agent", command = "pwd", port = 2222),
        )

        assertEquals("example.com", spec.host)
        assertEquals("agent", spec.user)
        assertEquals(2222, spec.port)
        assertEquals(SshToolSet.HostKeyPolicy.STRICT, spec.hostKeyPolicy)
    }
}
