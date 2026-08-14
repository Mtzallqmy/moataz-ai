package com.mtzallqmy.aiagent.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mtzallqmy.aiagent.R
import com.mtzallqmy.aiagent.agent.ProviderRegistry
import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.datastore.SecureSettings
import com.mtzallqmy.aiagent.feature.logs.LogEntry
import com.mtzallqmy.aiagent.feature.logs.RunLogs
import com.mtzallqmy.aiagent.feature.security.SecurityCenter
import com.mtzallqmy.aiagent.feature.security.SecurityReport
import com.mtzallqmy.aiagent.model.CapabilityAvailabilityState
import com.mtzallqmy.aiagent.security.CredentialScope
import com.mtzallqmy.aiagent.security.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    capabilities: CapabilityRegistry?,
    modifier: Modifier = Modifier,
) {
    val statuses by produceState<List<Pair<String, String>>>(emptyList(), capabilities) {
        value = capabilities?.allStatuses()?.map { it.id.value to describe(it.state) } ?: emptyList()
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_tools)) })
        LazyColumn(modifier = Modifier.padding(8.dp)) {
            items(statuses) { pair ->
                val (id, state) = pair
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(id)
                        Text(state)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    providerRegistry: ProviderRegistry?,
    settings: SecureSettings?,
    vault: CredentialVault?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_providers)) })
        LazyColumn(modifier = Modifier.padding(8.dp)) {
            items(providerRegistry?.all().orEmpty(), key = { it.providerId }) { provider ->
                val secretRef = remember(provider.providerId) { providerCredentialRef(provider.providerId) }
                var apiKey by remember(provider.providerId) { mutableStateOf("") }
                var credentialSaved by remember(provider.providerId, vault) {
                    mutableStateOf(secretRef?.let { ref -> runCatching { vault?.has(CredentialScope.PROVIDER, ref) == true }.getOrDefault(false) } ?: false)
                }
                var baseUrl by remember(provider.providerId) { mutableStateOf("") }

                LaunchedEffect(provider.providerId, settings) {
                    if (provider.providerId == "openai-compatible") {
                        baseUrl = settings?.getString("custom_provider_base_url").orEmpty()
                    }
                }

                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    results[provider.providerId] ?: stringResource(R.string.not_tested),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (secretRef != null) {
                                    Text(
                                        if (credentialSaved) stringResource(R.string.credential_saved)
                                        else stringResource(R.string.credential_missing),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (credentialSaved) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            val connectedText = stringResource(R.string.connected)
                            Button(
                                onClick = {
                                    scope.launch {
                                        val result = provider.testConnection()
                                        results = results + (
                                            provider.providerId to if (result.isSuccess) connectedText
                                            else result.exceptionOrNull()?.message?.take(160).orEmpty().ifBlank { "error" }
                                        )
                                    }
                                },
                            ) { Text(stringResource(R.string.test)) }
                        }

                        if (secretRef != null) {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text(stringResource(R.string.api_key)) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = apiKey.isNotBlank() && vault != null,
                                    onClick = {
                                        val value = apiKey.trim()
                                        if (value.isBlank() || vault == null) return@Button
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                runCatching { vault.save(CredentialScope.PROVIDER, secretRef, value) }
                                            }
                                            if (result.isSuccess) {
                                                apiKey = ""
                                                credentialSaved = true
                                                results = results + (provider.providerId to "Credential stored securely")
                                            } else {
                                                results = results + (provider.providerId to (result.exceptionOrNull()?.message ?: "Credential save failed"))
                                            }
                                        }
                                    },
                                ) { Text(stringResource(R.string.save)) }
                                OutlinedButton(
                                    enabled = credentialSaved && vault != null,
                                    onClick = {
                                        if (vault == null) return@OutlinedButton
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                runCatching { vault.delete(CredentialScope.PROVIDER, secretRef) }
                                            }
                                            if (result.isSuccess) {
                                                apiKey = ""
                                                credentialSaved = false
                                                results = results + (provider.providerId to "Credential removed")
                                            }
                                        }
                                    },
                                ) { Text(stringResource(R.string.remove)) }
                            }
                        }

                        if (provider.providerId == "openai-compatible") {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text(stringResource(R.string.base_url)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                enabled = settings != null,
                                onClick = {
                                    scope.launch {
                                        settings?.setString("custom_provider_base_url", baseUrl.trim().ifBlank { null })
                                        results = results + (provider.providerId to "Base URL saved")
                                    }
                                },
                            ) { Text(stringResource(R.string.save_endpoint)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityCenterScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val report by produceState<SecurityReport?>(null, context) {
        value = runCatching { SecurityCenter(context).collect() }.getOrNull()
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_security)) })
        val current = report
        if (current == null) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SecurityStatusCard("Accessibility", current.accessibilityEnabled) }
                item { SecurityStatusCard("Notification listener", current.notificationListenerEnabled) }
                item { SecurityStatusCard("Notifications permission", current.notificationsPermission) }
                item { SecurityStatusCard("Storage access", current.storageAccess) }
                item { SecurityValueCard("Contacts sandbox mode", current.sandboxContacts) }
                item { SecurityValueCard("SMS sandbox mode", current.sms) }
                item { SecurityValueCard("Call-log sandbox mode", current.callLog) }
            }
        }
    }
}

@Composable
private fun SecurityStatusCard(label: String, enabled: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label)
            Text(
                if (enabled) "Enabled" else "Disabled",
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}


@Composable
private fun SecurityValueCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label)
            Text(value, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SecureSettings?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val arabic by (settings?.arabicLocale?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) })
    val smartRouting by (settings?.smartRouting?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) })

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_settings)) })
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingSwitchRow(
                label = stringResource(R.string.arabic_mode),
                checked = arabic,
                enabled = settings != null,
                onCheckedChange = { enabled ->
                    scope.launch {
                        settings?.setBoolean(SecureSettings.KEY_ARABIC_LOCALE, enabled)
                        context.findActivity()?.recreate()
                    }
                },
            )
            SettingSwitchRow(
                label = stringResource(R.string.smart_routing),
                checked = smartRouting,
                enabled = settings != null,
                onCheckedChange = { enabled ->
                    scope.launch { settings?.setBoolean(SecureSettings.KEY_SMART_ROUTING, enabled) }
                },
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun providerCredentialRef(providerId: String): String? = when (providerId) {
    "openai" -> "openai_api_key"
    "anthropic" -> "anthropic_api_key"
    "gemini" -> "gemini_api_key"
    "openrouter" -> "openrouter_api_key"
    "openai-compatible" -> "custom_provider_api_key"
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(modifier: Modifier = Modifier) {
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        RunLogs.events.collect { entry ->
            entries = entries + entry
            if (entries.size > 100) entries = entries.drop(entries.size - 100)
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_logs)) })
        LazyColumn(modifier = Modifier.padding(8.dp)) {
            items(entries) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text("${entry.formattedTime} [${entry.level}] ", style = MaterialTheme.typography.bodySmall)
                    Text(entry.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun describe(state: CapabilityAvailabilityState): String = when (state) {
    CapabilityAvailabilityState.AVAILABLE -> "Available"
    CapabilityAvailabilityState.PERMISSION_REQUIRED -> "Permission required"
    CapabilityAvailabilityState.SERVICE_DISABLED -> "Service disabled"
    CapabilityAvailabilityState.BACKEND_UNAVAILABLE -> "Backend unavailable"
    CapabilityAvailabilityState.DEVICE_UNSUPPORTED -> "Device unsupported"
    CapabilityAvailabilityState.CONFIGURATION_REQUIRED -> "Configuration required"
    CapabilityAvailabilityState.SECURITY_DENIED -> "Security denied"
    CapabilityAvailabilityState.DEGRADED -> "Degraded"
}
