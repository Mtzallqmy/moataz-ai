package com.mtzallqmy.aiagent

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mtzallqmy.aiagent.datastore.SecureSettings
import com.mtzallqmy.aiagent.ui.AegisTheme
import com.mtzallqmy.aiagent.ui.screens.AegisNavHost
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val arabic = SecureSettings.bootstrapArabicLocale(newBase)
        val systemLocale = Resources.getSystem().configuration.locales[0]
        val locale = if (arabic) Locale.forLanguageTag("ar") else systemLocale
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AegisTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AegisNavHost()
                }
            }
        }
    }
}
