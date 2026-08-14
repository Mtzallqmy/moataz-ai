package com.mtzallqmy.aiagent.tools

import com.mtzallqmy.aiagent.model.ApprovalOption
import com.mtzallqmy.aiagent.model.ApprovalPolicy
import com.mtzallqmy.aiagent.model.ApprovalRequest
import com.mtzallqmy.aiagent.model.RiskLevel
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalEngineRulesTest {

    @Test
    fun `allow once applies only to the pending request`() = runTest {
        val engine = engine()
        val request = request()

        assertEquals(ApprovalOption.ALLOW_ONCE, resolve(engine, request, ApprovalOption.ALLOW_ONCE))
        assertEquals(ApprovalOption.ASK, engine.decide(request(id = "next")).decision)
    }

    @Test
    fun `allow for run matches only the same rule and run`() = runTest {
        val engine = engine()
        val request = request(runId = "run-1")

        resolve(engine, request, ApprovalOption.ALLOW_FOR_TASK)

        assertEquals(ApprovalOption.ALLOW_FOR_TASK, engine.decide(request(id = "same", runId = "run-1")).decision)
        assertEquals(ApprovalOption.ASK, engine.decide(request(id = "other", runId = "run-2")).decision)
        engine.clearRun("run-1")
        assertEquals(ApprovalOption.ASK, engine.decide(request(id = "cleared", runId = "run-1")).decision)
    }

    @Test
    fun `always allow matches stable action rule and not request id`() = runTest {
        val engine = engine()
        val original = request(id = "request-id-is-not-a-rule")

        resolve(engine, original, ApprovalOption.ALWAYS_ALLOW)

        assertEquals(ApprovalOption.ALWAYS_ALLOW, engine.decide(request(id = "different-id")).decision)
        assertEquals(ApprovalOption.ASK, engine.decide(request(id = "different-action", action = "delete")).decision)
        assertNotEquals("request-id-is-not-a-rule", engine.persistentRules().single().key.toolId)
    }

    @Test
    fun `deny persists for the exact matching rule`() = runTest {
        val engine = engine()
        resolve(engine, request(), ApprovalOption.DENY)

        assertEquals(ApprovalOption.DENY, engine.decide(request(id = "matching")).decision)
        assertEquals(ApprovalOption.ASK, engine.decide(request(id = "other-target", target = "/other")).decision)
    }

    @Test
    fun `persistent rules survive engine recreation`() = runTest {
        val store = InMemoryApprovalRuleStore()
        val first = engine(store)
        resolve(first, request(), ApprovalOption.ALWAYS_ALLOW)

        val recreated = engine(store)

        assertEquals(ApprovalOption.ALWAYS_ALLOW, recreated.decide(request(id = "after-restart")).decision)
    }

    @Test
    fun `revocation removes persisted allow rule`() = runTest {
        val store = InMemoryApprovalRuleStore()
        val engine = engine(store)
        val request = request()
        resolve(engine, request, ApprovalOption.ALWAYS_ALLOW)
        val key = engine.ruleKeyForRequest(request.id)!!

        engine.revoke(key)

        assertEquals(ApprovalOption.ASK, engine.decide(request(id = "after-revoke")).decision)
        assertNull(engine(store).persistentRules().singleOrNull())
    }

    @Test
    fun `request id resolves original request and stable rule key`() = runTest {
        val engine = engine()
        val request = request(id = "lookup-id")
        resolve(engine, request, ApprovalOption.ALLOW_ONCE)

        assertEquals(request, engine.requestFor("lookup-id"))
        assertEquals(
            ApprovalRuleKey("file.write", "write", "/workspace/file", RiskLevel.MODIFY, "coding-agent"),
            engine.ruleKeyForRequest("lookup-id"),
        )
    }

    private suspend fun resolve(
        engine: ApprovalEngine,
        request: ApprovalRequest,
        option: ApprovalOption,
    ): ApprovalOption = kotlinx.coroutines.coroutineScope {
        val pending = async { engine.requestApproval(request) }
        engine.requests.receive()
        engine.respond(request.id, option)
        pending.await().decision
    }

    private fun engine(store: ApprovalRuleStore = InMemoryApprovalRuleStore()) =
        ApprovalEngine(policyProvider = { ApprovalPolicy.ASK_EVERY_TIME }, ruleStore = store)

    private fun request(
        id: String = "request-1",
        action: String = "write",
        target: String = "/workspace/file",
        runId: String = "run-1",
    ) = ApprovalRequest(
        id = id,
        toolName = "File write",
        toolId = "file.write",
        action = action,
        target = target,
        argumentsSummary = "path=/workspace/file",
        riskLevel = RiskLevel.MODIFY,
        requestingAgent = "coding-agent",
        agentScope = "coding-agent",
        runId = runId,
        reason = "test",
    )
}
