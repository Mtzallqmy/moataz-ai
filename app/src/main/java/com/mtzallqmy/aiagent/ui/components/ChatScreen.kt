package com.mtzallqmy.aiagent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mtzallqmy.aiagent.R
import com.mtzallqmy.aiagent.agent.AgentRuntime
import com.mtzallqmy.aiagent.agent.ProviderRegistry
import com.mtzallqmy.aiagent.datastore.SecureSettings
import com.mtzallqmy.aiagent.feature.chat.ChatViewModel
import com.mtzallqmy.aiagent.model.AgentState
import com.mtzallqmy.aiagent.model.AiModel
import com.mtzallqmy.aiagent.model.MessageRole
import com.mtzallqmy.aiagent.tools.RegisteredTool
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    runtime: AgentRuntime?,
    providers: ProviderRegistry?,
    settings: SecureSettings?,
    tools: List<RegisteredTool>,
    modifier: Modifier = Modifier,
) {
    // The scope is owned by this composition; when the screen leaves composition
    // the ChatViewModel collectors are cancelled instead of leaking process-wide.
    val screenScope = rememberCoroutineScope()
    val viewModel = remember(runtime, tools, screenScope) {
        runtime?.let { ChatViewModel(it, tools, screenScope) }
    }
    val emptyMessages = remember { mutableStateOf(emptyList<com.mtzallqmy.aiagent.model.ChatMessage>()) }
    val idleState = remember { mutableStateOf(AgentState.IDLE) }
    val messages by (viewModel?.messages?.collectAsState() ?: emptyMessages)
    val state by (viewModel?.state?.collectAsState() ?: idleState)

    val storedProvider by (settings?.selectedProviderId?.collectAsState(initial = null)
        ?: remember { mutableStateOf<String?>(null) })
    val storedModel by (settings?.selectedModelId?.collectAsState(initial = null)
        ?: remember { mutableStateOf<String?>(null) })
    val smartRouting by (settings?.smartRouting?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) })

    val providerOptions = remember(providers) {
        providers?.all()?.filter { it.providerId != "smart-router" }.orEmpty()
    }
    var selectedProvider by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<AiModel>>(emptyList()) }
    var selectedModel by remember { mutableStateOf("") }
    var loadingModels by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(storedProvider, providerOptions) {
        val validStored = storedProvider?.takeIf { id -> providerOptions.any { it.providerId == id } }
        val resolved = validStored ?: providerOptions.firstOrNull()?.providerId.orEmpty()
        if (resolved.isNotBlank() && resolved != selectedProvider) selectedProvider = resolved
        if (settings != null && validStored == null && resolved.isNotBlank()) {
            settings.setString("selected_provider_id", resolved)
        }
    }

    LaunchedEffect(selectedProvider) {
        if (selectedProvider.isBlank()) {
            models = emptyList()
            selectedModel = storedModel.orEmpty()
            return@LaunchedEffect
        }
        loadingModels = true
        val loaded = providers?.get(selectedProvider)?.listModels()?.getOrNull().orEmpty()
        models = loaded
        loadingModels = false

        val resolvedModel = storedModel
            ?.takeIf { id -> loaded.isEmpty() || loaded.any { it.id == id } }
            ?: loaded.firstOrNull()?.id
            ?: ""
        selectedModel = resolvedModel
        if (settings != null && loaded.isNotEmpty() && resolvedModel != storedModel) {
            settings.setString("selected_model_id", resolvedModel.ifBlank { null })
        }
    }

    LaunchedEffect(storedModel, models) {
        val stored = storedModel ?: return@LaunchedEffect
        if (models.isEmpty() || models.any { it.id == stored }) selectedModel = stored
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) })

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            state = rememberLazyListState(),
        ) {
            itemsIndexed(messages) { _, message ->
                AgentMessageBubble(
                    text = message.content,
                    isUser = message.role == MessageRole.USER,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        HorizontalDivider()

        if (smartRouting) {
            AssistChip(
                onClick = {},
                label = { Text(stringResource(R.string.smart_router_active)) },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                enabled = false,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = providerOptions.firstOrNull { it.providerId == selectedProvider }?.name
                            ?: selectedProvider,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.provider)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false },
                    ) {
                        providerOptions.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.name) },
                                onClick = {
                                    selectedProvider = provider.providerId
                                    selectedModel = ""
                                    providerExpanded = false
                                    screenScope.launch {
                                        settings?.setString("selected_provider_id", provider.providerId)
                                        settings?.setString("selected_model_id", null)
                                    }
                                },
                            )
                        }
                    }
                }

                if (loadingModels) {
                    CircularProgressIndicator()
                } else {
                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { if (models.isNotEmpty()) modelExpanded = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = models.firstOrNull { it.id == selectedModel }?.name
                                ?: selectedModel.ifBlank { stringResource(R.string.model_not_selected) },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.model)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            enabled = models.isNotEmpty() || selectedModel.isNotBlank(),
                        )
                        ExposedDropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false },
                        ) {
                            models.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.name) },
                                    onClick = {
                                        selectedModel = model.id
                                        modelExpanded = false
                                        screenScope.launch {
                                            settings?.setString("selected_model_id", model.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.ask_agent)) },
                maxLines = 5,
            )
            IconButton(
                onClick = {
                    val modelId = if (smartRouting) "" else selectedModel
                    val requestedProviderId = if (smartRouting) null else selectedProvider.takeIf { it.isNotBlank() }
                    if (viewModel?.send(
                            text = input,
                            modelId = modelId,
                            requestedProviderId = requestedProviderId,
                        ) == true
                    ) input = ""
                },
                enabled = input.isNotBlank() && viewModel != null,
            ) {
                Icon(Icons.Default.Send, contentDescription = stringResource(R.string.send))
            }
            if (state == AgentState.OBSERVING || state == AgentState.PLANNING ||
                state == AgentState.THINKING || state == AgentState.EXECUTING_TOOL ||
                state == AgentState.REPLANNING || state == AgentState.WAITING_FOR_TOOL ||
                state == AgentState.WAITING_FOR_APPROVAL
            ) {
                TextButton(onClick = { viewModel?.stop() }) {
                    Text(stringResource(R.string.stop))
                }
            }
        }
    }
}

/** Wrapper of the core:ui bubble for ChatMessage. */
@Composable
private fun AgentMessageBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
) {
    MessageBubble(
        text = text.ifEmpty { "\u2026" },
        isUser = isUser,
        modifier = modifier,
    )
}
