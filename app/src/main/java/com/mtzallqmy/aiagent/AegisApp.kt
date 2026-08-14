package com.mtzallqmy.aiagent

import android.app.Application
import com.mtzallqmy.aiagent.agent.AgentRuntime
import com.mtzallqmy.aiagent.agent.ContextManager
import com.mtzallqmy.aiagent.agent.HeartbeatAgent
import com.mtzallqmy.aiagent.agent.MemoryStoreSubAgentMemoryGateway
import com.mtzallqmy.aiagent.agent.ProviderRegistry
import com.mtzallqmy.aiagent.agent.SkillRegistry
import com.mtzallqmy.aiagent.agent.SmartRouterConfiguration
import com.mtzallqmy.aiagent.agent.SmartRoutingProvider
import com.mtzallqmy.aiagent.agent.SubAgentRunner
import com.mtzallqmy.aiagent.agent.backends.DeviceBackend
import com.mtzallqmy.aiagent.agent.backends.DeviceBackendRegistry
import com.mtzallqmy.aiagent.agent.backends.CodingBackend
import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.datastore.SecureSettings
import com.mtzallqmy.aiagent.database.DatabaseProvider
import com.mtzallqmy.aiagent.feature.device.DeviceToolSet
import com.mtzallqmy.aiagent.memory.EmbeddingProviderRegistry
import com.mtzallqmy.aiagent.memory.KeywordEmbedder
import com.mtzallqmy.aiagent.memory.MemoryRefiner
import com.mtzallqmy.aiagent.memory.MemoryStore
import com.mtzallqmy.aiagent.memory.OpenAiEmbeddingsProvider
import com.mtzallqmy.aiagent.memory.RagRuntime
import com.mtzallqmy.aiagent.memory.SQLiteVectorStore
import com.mtzallqmy.aiagent.local_llm.AndroidLocalDeviceResources
import com.mtzallqmy.aiagent.local_llm.LlamaCppLocalModelBackend
import com.mtzallqmy.aiagent.provider.anthropic.AnthropicProvider
import com.mtzallqmy.aiagent.provider.compatible.OpenAiCompatibleProvider
import com.mtzallqmy.aiagent.provider.google.GeminiProvider
import com.mtzallqmy.aiagent.provider.openai.OpenAiProvider
import com.mtzallqmy.aiagent.provider.openrouter.OpenRouterProvider
import com.mtzallqmy.aiagent.provider.local.LocalProvider
import com.mtzallqmy.aiagent.provider.local.LlamaCppEmbeddingsProvider
import com.mtzallqmy.aiagent.security.CredentialScope
import com.mtzallqmy.aiagent.security.CredentialVault
import com.mtzallqmy.aiagent.sandbox.ProotLinuxBackend
import com.mtzallqmy.aiagent.sandbox.SandboxBackendRegistry
import com.mtzallqmy.aiagent.sandbox.termux.AndroidTermuxCommandTransport
import com.mtzallqmy.aiagent.schedules.ScheduleExecutionHost
import com.mtzallqmy.aiagent.schedules.ScheduleRuntime
import com.mtzallqmy.aiagent.schedules.ScheduleRuntimeOwner
import com.mtzallqmy.aiagent.tool.clipboard.ClipboardToolSet
import com.mtzallqmy.aiagent.tool.filesystem.FileToolSet
import com.mtzallqmy.aiagent.tool.http.HttpToolSet
import com.mtzallqmy.aiagent.tool.mcp.McpClient
import com.mtzallqmy.aiagent.tool.mcp.McpRuntime
import com.mtzallqmy.aiagent.tool.mcp.StreamableHttpMcpTransport
import com.mtzallqmy.aiagent.tool.ssh.SshToolSet
import com.mtzallqmy.aiagent.tool.terminal.TerminalToolSet
import com.mtzallqmy.aiagent.tools.ApprovalEngine
import com.mtzallqmy.aiagent.tools.SharedPreferencesApprovalRuleStore
import com.mtzallqmy.aiagent.tools.ToolRuntime
import com.mtzallqmy.aiagent.tools.TypedToolRegistry
import com.mtzallqmy.aiagent.workspace.WorkspaceManager
import java.io.File
import com.mtzallqmy.aiagent.workflow.AtomicFileWorkflowStore
import com.mtzallqmy.aiagent.workflow.WorkflowEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Minimal composition root: wires all registered implementations.
 * No hardcoded keys — credentials only enter at runtime via Settings/CredentialVault.
 */
class AegisApp : Application(), ScheduleRuntimeOwner, ScheduleExecutionHost {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var databaseProvider: DatabaseProvider
        private set
    lateinit var settings: SecureSettings
        private set
    lateinit var vault: CredentialVault
        private set
    lateinit var capabilityRegistry: CapabilityRegistry
        private set
    lateinit var approvalEngine: ApprovalEngine
        private set
    lateinit var toolRuntime: ToolRuntime
        private set
    lateinit var toolRegistry: TypedToolRegistry
        private set
    lateinit var mcpRuntime: McpRuntime
        private set
    lateinit var providerRegistry: ProviderRegistry
        private set
    lateinit var localProvider: LocalProvider
        private set
    lateinit var smartRouter: SmartRoutingProvider
        private set
    lateinit var sandboxBackendRegistry: SandboxBackendRegistry
        private set
    lateinit var workflowEngine: WorkflowEngine
        private set
    override lateinit var scheduleRuntime: ScheduleRuntime
        private set
    lateinit var runtime: AgentRuntime
        private set
    lateinit var contextManager: ContextManager
        private set
    lateinit var memoryStore: MemoryStore
        private set
    lateinit var subAgentRunner: SubAgentRunner
        private set
    lateinit var workspaceManager: WorkspaceManager
        private set
    lateinit var skillRegistry: SkillRegistry
        private set
    lateinit var deviceBackendRegistry: DeviceBackendRegistry
        private set
    lateinit var heartbeatAgent: HeartbeatAgent
        private set
    lateinit var memoryRefiner: MemoryRefiner
        private set
    lateinit var embeddingProviderRegistry: EmbeddingProviderRegistry
        private set
    lateinit var ragRuntime: RagRuntime
        private set
    lateinit var codingBackend: CodingBackend
        private set

    override fun onCreate() {
        super.onCreate()

        databaseProvider = DatabaseProvider
        settings = SecureSettings(this)
        vault = CredentialVault(this)

        capabilityRegistry = CapabilityRegistry()
        approvalEngine = ApprovalEngine(ruleStore = SharedPreferencesApprovalRuleStore(this))
        toolRuntime = ToolRuntime(capabilityRegistry, approvalEngine)

        providerRegistry = ProviderRegistry()
        // Keys are resolved lazily from the CredentialVault (Android Keystore). No secret ever
        // lives in these lambdas or in memory beyond the loaded value.
        providerRegistry.register(
            OpenAiProvider(apiKeyProvider = { vault.load(CredentialScope.PROVIDER, "openai_api_key") }),
        )
        providerRegistry.register(
            AnthropicProvider(apiKeyProvider = { vault.load(CredentialScope.PROVIDER, "anthropic_api_key") }),
        )
        providerRegistry.register(
            GeminiProvider(apiKeyProvider = { vault.load(CredentialScope.PROVIDER, "gemini_api_key") }),
        )
        providerRegistry.register(
            OpenRouterProvider(apiKeyProvider = { vault.load(CredentialScope.PROVIDER, "openrouter_api_key") }),
        )
        providerRegistry.register(
            OpenAiCompatibleProvider(
                baseUrlProvider = { settings.getString("custom_provider_base_url") ?: "" },
                apiKeyProvider = { vault.load(CredentialScope.PROVIDER, "custom_provider_api_key") },
            ),
        )

        val internalModels = File(filesDir, "models").apply { mkdirs() }
        val modelRoots = buildList {
            add(internalModels)
            getExternalFilesDir("models")?.apply { mkdirs() }?.let(::add)
        }
        val localModelBackend = LlamaCppLocalModelBackend(
            modelRoots = modelRoots,
            resources = AndroidLocalDeviceResources(this),
        )
        localProvider = LocalProvider(backend = localModelBackend)
        providerRegistry.register(localProvider)

        smartRouter = SmartRoutingProvider(
            registry = providerRegistry,
            configuration = SmartRouterConfiguration(
                enabled = { settings.getBoolean("smart_routing") },
                selectedProviderId = { settings.getString("selected_provider_id") ?: "openai" },
                selectedModelId = { settings.getString("selected_model_id") },
            ),
        )
        providerRegistry.register(smartRouter)

        contextManager = ContextManager()
        memoryStore = MemoryStore(database = { databaseProvider.get(this) })
        workspaceManager = WorkspaceManager(this)
        skillRegistry = SkillRegistry()
        sandboxBackendRegistry = SandboxBackendRegistry(
            listOf(
                ProotLinuxBackend(
                    transport = AndroidTermuxCommandTransport(this),
                    installationOptIn = { settings.getBoolean("proot_backend_enabled") },
                ),
            ),
        )

        toolRegistry = TypedToolRegistry().apply {
            (
                FileToolSet(this@AegisApp).tools +
                    HttpToolSet().tools +
                    ClipboardToolSet(this@AegisApp).tools +
                    DeviceToolSet(this@AegisApp).tools +
                    TerminalToolSet().tools +
                    SshToolSet().tools
                ).forEach(::register)
        }
        mcpRuntime = McpRuntime(toolRegistry) { configuration, authentication ->
            McpClient(
                StreamableHttpMcpTransport(
                    endpoint = configuration.endpoint,
                    authentication = authentication,
                ),
            )
        }
        subAgentRunner = SubAgentRunner(
            providers = providerRegistry,
            tools = toolRegistry,
            toolRuntime = toolRuntime,
            memory = MemoryStoreSubAgentMemoryGateway(memoryStore),
            scope = applicationScope,
        )

        embeddingProviderRegistry = EmbeddingProviderRegistry().apply {
            register(KeywordEmbedder())
            register(
                OpenAiEmbeddingsProvider(
                    apiKeyProvider = { vault.load(CredentialScope.PROVIDER, "openai_api_key") },
                ),
            )
            register(LlamaCppEmbeddingsProvider(localModelBackend))
        }
        ragRuntime = RagRuntime(
            providers = embeddingProviderRegistry,
            selectedProviderId = {
                settings.getString("embedding_provider_id") ?: "keyword-fallback"
            },
            vectorStoreFactory = { provider ->
                val safeId = provider.providerId.replace(Regex("[^A-Za-z0-9._-]"), "_")
                SQLiteVectorStore(
                    context = this,
                    dimension = provider.dimension,
                    databaseName = "aegis_vectors_${safeId}_${provider.dimension}.db",
                )
            },
        )
        memoryRefiner = MemoryRefiner()
        heartbeatAgent = HeartbeatAgent()

        // Device backends: Accessibility (on-device) + ADB (optional, requires PC pairing)
        deviceBackendRegistry = DeviceBackendRegistry()
        deviceBackendRegistry.register(com.mtzallqmy.aiagent.tool.android.AccessibilityDeviceBackend())
        deviceBackendRegistry.register(com.mtzallqmy.aiagent.tool.android.AdbDeviceBackend())

        codingBackend = com.mtzallqmy.aiagent.tool.terminal.LocalSandboxCoding()

        workflowEngine = WorkflowEngine(
            store = AtomicFileWorkflowStore(File(noBackupFilesDir, "workflows/state.json")),
            actionExecutor = AppWorkflowActionExecutor(
                context = this,
                providers = providerRegistry,
                tools = toolRegistry,
                toolRuntime = toolRuntime,
                approvalEngine = approvalEngine,
            ),
            scope = applicationScope,
        )
        scheduleRuntime = ScheduleRuntime(this)
        applicationScope.launch {
            workflowEngine.recoverIncompleteRuns()
        }

        runtime = AgentRuntime(
            provider = smartRouter,
            toolRuntime = toolRuntime,
        )
    }

    override suspend fun executeScheduledWorkflow(
        workflowId: String,
        workflowVersion: Int,
        input: kotlinx.serialization.json.JsonObject,
        scheduleId: String,
    ): String = workflowEngine.startStored(workflowId, workflowVersion, input)
}
