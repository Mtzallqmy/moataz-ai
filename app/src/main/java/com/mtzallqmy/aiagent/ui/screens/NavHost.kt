package com.mtzallqmy.aiagent.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mtzallqmy.aiagent.R
import com.mtzallqmy.aiagent.ui.components.ChatScreen
import com.mtzallqmy.aiagent.ui.components.LogsScreen
import com.mtzallqmy.aiagent.ui.components.ProvidersScreen
import com.mtzallqmy.aiagent.ui.components.SecurityCenterScreen
import com.mtzallqmy.aiagent.ui.components.SettingsScreen
import com.mtzallqmy.aiagent.ui.components.ToolsScreen

private val TABS = listOf(
    R.string.tab_chat to Icons.Default.Chat,
    R.string.tab_tools to Icons.Default.Work,
    R.string.tab_providers to Icons.Default.Terminal,
    R.string.tab_security to Icons.Default.Security,
    R.string.tab_settings to Icons.Default.Settings,
    R.string.tab_logs to Icons.Default.Memory,
)

@Composable
fun AegisNavHost() {
    var selected by remember { mutableIntStateOf(0) }

    val runtime = (androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.mtzallqmy.aiagent.AegisApp)?.runtime
    val providerRegistry = (androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.mtzallqmy.aiagent.AegisApp)?.providerRegistry
    val capabilities = (androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.mtzallqmy.aiagent.AegisApp)?.capabilityRegistry
    val settings = (androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.mtzallqmy.aiagent.AegisApp)?.settings
    val vault = (androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.mtzallqmy.aiagent.AegisApp)?.vault
    val tools = (androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.mtzallqmy.aiagent.AegisApp)?.toolRegistry?.list().orEmpty()

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(icon, contentDescription = stringResource(label)) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        },
    ) { padding ->
        when (selected) {
            0 -> ChatScreen(
                runtime = runtime,
                providers = providerRegistry,
                settings = settings,
                tools = tools,
                modifier = Modifier.padding(padding),
            )
            1 -> ToolsScreen(
                capabilities = capabilities,
                modifier = Modifier.padding(padding),
            )
            2 -> ProvidersScreen(
                providerRegistry = providerRegistry,
                settings = settings,
                vault = vault,
                modifier = Modifier.padding(padding),
            )
            3 -> SecurityCenterScreen(modifier = Modifier.padding(padding))
            4 -> SettingsScreen(
                settings = settings,
                modifier = Modifier.padding(padding),
            )
            5 -> LogsScreen(modifier = Modifier.padding(padding))
        }
    }
}
