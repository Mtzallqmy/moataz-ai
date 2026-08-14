package com.mtzallqmy.aiagent.sandbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SandboxBackendRegistryTest {
    @Test
    fun preservesBackendIsolationIdentity() {
        val app = TestBackend("app", SandboxIsolationLevel.APP_SANDBOX)
        val proot = TestBackend("proot", SandboxIsolationLevel.PROOT_USERSPACE)
        val registry = SandboxBackendRegistry(listOf(app, proot))

        assertEquals(SandboxIsolationLevel.APP_SANDBOX, registry.get("app")?.capabilities?.isolationLevel)
        assertEquals(SandboxIsolationLevel.PROOT_USERSPACE, registry.get("proot")?.capabilities?.isolationLevel)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsDuplicateBackendIds() {
        SandboxBackendRegistry().apply {
            register(TestBackend("same", SandboxIsolationLevel.APP_SANDBOX))
            register(TestBackend("same", SandboxIsolationLevel.REMOTE_SANDBOX))
        }
    }

    private class TestBackend(
        override val id: String,
        isolation: SandboxIsolationLevel,
    ) : SandboxBackend {
        override val capabilities = SandboxBackendDeclarations.androidAppSandbox.copy(isolationLevel = isolation)
        override suspend fun healthCheck() = SandboxHealth(true, "ok")
        override suspend fun install(spec: SandboxEnvironmentSpec) = result()
        override suspend fun start(environmentId: String) = SandboxSession("id", environmentId, 0)
        override suspend fun exec(session: SandboxSession, request: SandboxExecRequest) = result()
        override suspend fun transfer(transfer: SandboxTransfer) = result()
        override suspend fun stop(session: SandboxSession) = result()
        override suspend fun reset(environmentId: String) = result()

        private fun result() = SandboxExecResult(0, "", "", false, false, 0)
    }
}
