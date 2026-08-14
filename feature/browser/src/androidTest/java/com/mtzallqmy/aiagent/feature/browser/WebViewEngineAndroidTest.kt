package com.mtzallqmy.aiagent.feature.browser

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewEngineAndroidTest {
    @Test
    fun webViewRuntimeKeepsFileContentMixedContentAndThirdPartyWindowsBlocked() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var webView: WebView
        val engine = WebViewEngine(
            webViewFactory = { WebView(context).also { webView = it } },
        )
        try {
            engine.create()
            withContext(Dispatchers.Main) {
                val settings = webView.settings
                assertFalse(settings.allowContentAccess)
                assertFalse(settings.allowFileAccess)
                assertFalse(settings.allowFileAccessFromFileURLs)
                assertFalse(settings.allowUniversalAccessFromFileURLs)
                assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
                assertTrue(settings.mixedContentMode == WebSettings.MIXED_CONTENT_NEVER_ALLOW)
            }
            assertTrue(engine.navigate("about:blank").isSuccess)
            assertTrue(engine.navigate("file:///data/local/tmp/browser-test.html").isFailure)
        } finally {
            engine.destroy()
        }
    }
}
