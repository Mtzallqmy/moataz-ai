package com.mtzallqmy.aiagent.feature.browser

import android.os.Build
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object BrowserUrlPolicy {
    fun isAllowed(url: String, allowHttp: Boolean = false): Boolean = runCatching {
        if (url == "about:blank") return true
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && !(allowHttp && scheme == "http")) return false
        if (!uri.userInfo.isNullOrBlank()) return false
        val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
        host.isNotBlank() && !isPrivateHost(host)
    }.getOrDefault(false)

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "0.0.0.0" || host == "::1") return true
        if (host.endsWith(".local") || host.endsWith(".localhost")) return true
        if (host.startsWith("10.") || host.startsWith("127.") || host.startsWith("192.168.")) return true
        if (host.startsWith("169.254.")) return true
        val secondOctet = host.split('.').getOrNull(1)?.toIntOrNull()
        if (host.startsWith("172.") && secondOctet != null && secondOctet in 16..31) return true
        return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")
    }
}

fun interface BrowserDownloadHandler {
    fun enqueue(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?): BrowserArtifact?
}

class AndroidDownloadManagerHandler(private val context: Context) : BrowserDownloadHandler {
    override fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): BrowserArtifact? = runCatching {
        require(BrowserUrlPolicy.isAllowed(url)) { "Blocked download URL" }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setMimeType(mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        userAgent?.let { request.addRequestHeader("User-Agent", it) }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = manager.enqueue(request)
        BrowserArtifact(
            id = id.toString(),
            name = fileName,
            uri = "content://downloads/my_downloads/$id",
            mediaType = mimeType,
        )
    }.getOrNull()
}

/**
 * WebView driver with no page-callable native JavaScript bridge. Navigation,
 * DOM extraction, file selection, downloads, and cookie access stay behind the
 * Kotlin boundary and must still be authorized by ToolRuntime.
 */
class WebViewEngine(
    private val webViewFactory: () -> WebView,
    private val downloadHandler: BrowserDownloadHandler? = null,
    private val allowHttp: Boolean = false,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var webView: WebView? = null
    private var pendingNavigation: CompletableDeferred<Unit>? = null
    private var pendingUploadUris: Array<Uri>? = null
    private var pendingDownload: CompletableDeferred<BrowserArtifact?>? = null

    suspend fun create() = withContext(Dispatchers.Main) {
        check(webView == null) { "WebView already created" }
        webView = webViewFactory().also(::setupWebView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = false
            allowFileAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            saveFormData = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                val files = pendingUploadUris ?: return false
                pendingUploadUris = null
                filePathCallback?.onReceiveValue(files)
                return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return true
                val blocked = !BrowserUrlPolicy.isAllowed(url, allowHttp)
                if (blocked && request.isForMainFrame) {
                    failNavigation(SecurityException("Blocked navigation target"))
                }
                return blocked
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                completeNavigation()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    failNavigation(IllegalStateException("Navigation failed: ${error?.errorCode}"))
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                failNavigation(SecurityException("TLS validation failed"))
            }

            override fun onSafeBrowsingHit(
                view: WebView?,
                request: WebResourceRequest?,
                threatType: Int,
                callback: SafeBrowsingResponse?,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    callback?.backToSafety(false)
                }
                failNavigation(SecurityException("Safe Browsing blocked a malicious page"))
            }
        }
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val result = if (url != null) {
                downloadHandler?.enqueue(url, userAgent, contentDisposition, mimeType)
            } else {
                null
            }
            pendingDownload?.complete(result)
            pendingDownload = null
        }
    }

    private fun completeNavigation() {
        pendingNavigation?.complete(Unit)
        pendingNavigation = null
    }

    private fun failNavigation(error: Throwable) {
        pendingNavigation?.completeExceptionally(error)
        pendingNavigation = null
    }

    private fun requireView(): WebView = webView ?: error("WebView not created")

    suspend fun navigate(url: String): Result<Unit> = try {
        require(BrowserUrlPolicy.isAllowed(url, allowHttp)) { "Blocked navigation target" }
        val navigation = withContext(Dispatchers.Main) {
            val view = requireView()
            pendingNavigation?.cancel()
            CompletableDeferred<Unit>().also {
                pendingNavigation = it
                view.loadUrl(url)
            }
        }
        navigation.await()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        withContext(Dispatchers.Main) {
            pendingNavigation?.cancel()
            pendingNavigation = null
            webView?.stopLoading()
        }
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

    suspend fun snapshot(): String = withContext(Dispatchers.Main) {
        val result = evaluateRaw(SNAPSHOT_JS)
        decodeJavascriptString(result).take(MAX_SNAPSHOT_CHARS)
    }

    suspend fun title(): String = withContext(Dispatchers.Main) { requireView().title.orEmpty() }

    suspend fun clickSelector(selector: String): Boolean = runBooleanTemplate(clickTemplateJs(), "click", selector)

    suspend fun typeIntoSelector(selector: String, text: String): Boolean =
        runBooleanTemplate(typeTemplateJs(), "type", selector, text)

    suspend fun submitForm(selector: String?): Boolean =
        runBooleanTemplate(submitTemplateJs(), "submit", selector.orEmpty())

    suspend fun scrollBy(deltaY: Int): Boolean = withContext(Dispatchers.Main) {
        requireView().scrollBy(0, deltaY)
        true
    }

    suspend fun findOnPage(query: String): Int = withContext(Dispatchers.Main) {
        evaluateRaw("(${findTemplateJs()})(${buildPayloadJson("find", query)})").toIntOrNull() ?: 0
    }

    suspend fun evaluate(script: String): JsonElement? = withContext(Dispatchers.Main) {
        require(script.length <= MAX_SCRIPT_CHARS) { "Script exceeds the browser evaluation limit" }
        val raw = evaluateRaw(script)
        if (raw == "null" || raw.isBlank()) JsonNull else runCatching { json.parseToJsonElement(raw) }.getOrNull()
    }

    suspend fun upload(selector: String, files: List<Uri>): Boolean = withContext(Dispatchers.Main) {
        require(files.isNotEmpty() && files.size <= MAX_UPLOAD_FILES) { "Invalid upload file count" }
        require(files.all { it.scheme == "content" }) { "Only explicit content references may be uploaded" }
        pendingUploadUris = files.toTypedArray()
        val clicked = evaluateRaw("(${uploadTemplateJs()})(${buildPayloadJson("upload", selector)})") == "true"
        if (!clicked) pendingUploadUris = null
        clicked
    }

    suspend fun download(selector: String): BrowserArtifact? {
        if (downloadHandler == null) return null
        val deferred = withContext(Dispatchers.Main) {
            pendingDownload?.cancel()
            CompletableDeferred<BrowserArtifact?>().also { pendingDownload = it }
        }
        val clicked = clickSelector(selector)
        if (!clicked) {
            withContext(Dispatchers.Main) {
                pendingDownload = null
                deferred.cancel()
            }
            return null
        }
        return try {
            deferred.await()
        } finally {
            withContext(Dispatchers.Main) {
                if (pendingDownload === deferred) pendingDownload = null
            }
        }
    }

    suspend fun cookies(): List<BrowserCookie> = withContext(Dispatchers.Main) {
        val url = requireView().url ?: return@withContext emptyList()
        CookieManager.getInstance().getCookie(url).orEmpty().split(';').mapNotNull { part ->
            val delimiter = part.indexOf('=')
            if (delimiter <= 0) null else BrowserCookie(
                name = part.substring(0, delimiter).trim(),
                value = part.substring(delimiter + 1).trim(),
                domain = Uri.parse(url).host,
            )
        }
    }

    suspend fun setCookie(cookie: BrowserCookie): Boolean = withContext(Dispatchers.Main) {
        require(cookie.name.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+"))) { "Invalid cookie name" }
        require(!cookie.value.contains(';') && !cookie.value.contains('\n') && !cookie.value.contains('\r')) {
            "Invalid cookie value"
        }
        val url = requireView().url ?: return@withContext false
        val host = Uri.parse(url).host ?: return@withContext false
        cookie.domain?.let { domain ->
            val normalized = domain.removePrefix(".")
            require(host == normalized || host.endsWith(".$normalized")) { "Cookie domain is outside the active origin" }
        }
        val header = buildString {
            append(cookie.name).append('=').append(cookie.value)
            append("; Path=").append(cookie.path)
            cookie.domain?.let { append("; Domain=").append(it) }
            if (cookie.secure) append("; Secure")
            if (cookie.httpOnly) append("; HttpOnly")
            cookie.expiresAtEpochSeconds?.let {
                append("; Expires=").append(
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC)),
                )
            }
            append("; SameSite=Lax")
        }
        val result = CompletableDeferred<Boolean>()
        CookieManager.getInstance().setCookie(url, header) { result.complete(it) }
        result.await()
    }

    suspend fun clearCookies(): Boolean = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<Boolean>()
        CookieManager.getInstance().removeAllCookies { result.complete(it) }
        result.await()
    }

    suspend fun currentUrl(): String = withContext(Dispatchers.Main) { requireView().url.orEmpty() }

    suspend fun awaitIdle(timeoutMs: Long = 15_000L) = withTimeout(timeoutMs) {
        while (true) {
            val ready = withContext(Dispatchers.Main) {
                requireView().progress == 100 &&
                    decodeJavascriptString(evaluateRaw("document.readyState")).let { it == "complete" || it == "interactive" }
            }
            if (ready) return@withTimeout
            delay(50L)
        }
    }

    suspend fun destroy() = withContext(Dispatchers.Main) {
        pendingNavigation?.cancel()
        pendingDownload?.cancel()
        pendingUploadUris = null
        requireView().apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
    }

    private suspend fun runBooleanTemplate(
        template: String,
        action: String,
        selector: String,
        text: String? = null,
    ): Boolean = withContext(Dispatchers.Main) {
        evaluateRaw("($template)(${buildPayloadJson(action, selector, text)})") == "true"
    }

    private suspend fun evaluateRaw(script: String): String {
        val result = CompletableDeferred<String>()
        requireView().evaluateJavascript(script) { result.complete(it ?: "null") }
        return result.await()
    }

    private fun decodeJavascriptString(raw: String): String = runCatching {
        json.parseToJsonElement(raw).jsonPrimitive.content
    }.getOrDefault("")

    private fun buildPayloadJson(action: String, selector: String, text: String? = null): String =
        JsonPrimitive(
            buildJsonObject {
                put("action", action)
                put("selector", selector)
                text?.let { put("text", it) }
            }.toString(),
        ).toString()

    private fun clickTemplateJs() = """
        function(payload){
          const el=document.querySelector(JSON.parse(payload).selector);
          if(!el) return false;
          el.scrollIntoView({block:'center'}); el.click(); return true;
        }
    """.trimIndent()

    private fun typeTemplateJs() = """
        function(payload){
          const p=JSON.parse(payload), el=document.querySelector(p.selector);
          if(!el || el.disabled || el.readOnly) return false;
          el.focus();
          const proto=el instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
          const setter=Object.getOwnPropertyDescriptor(proto,'value')?.set;
          if(setter) setter.call(el,p.text); else el.value=p.text;
          el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:p.text}));
          el.dispatchEvent(new Event('change',{bubbles:true})); return true;
        }
    """.trimIndent()

    private fun submitTemplateJs() = """
        function(payload){
          const p=JSON.parse(payload);
          const form=p.selector?document.querySelector(p.selector):document.activeElement?.closest('form');
          if(!form || form.tagName!=='FORM') return false;
          if(typeof form.requestSubmit==='function') form.requestSubmit(); else form.submit();
          return true;
        }
    """.trimIndent()

    private fun findTemplateJs() = """
        function(payload){
          const q=JSON.parse(payload).selector.toLocaleLowerCase();
          if(!q) return 0;
          return (document.body?.innerText||'').toLocaleLowerCase().split(q).length-1;
        }
    """.trimIndent()

    private fun uploadTemplateJs() = """
        function(payload){
          const el=document.querySelector(JSON.parse(payload).selector);
          if(!el || el.tagName!=='INPUT' || el.type!=='file' || el.disabled) return false;
          el.click(); return true;
        }
    """.trimIndent()

    companion object {
        private const val MAX_SNAPSHOT_CHARS = 512 * 1024
        private const val MAX_SCRIPT_CHARS = 64 * 1024
        private const val MAX_UPLOAD_FILES = 10

        val SNAPSHOT_JS = """
            (function(){
              const selector='a,button,input,textarea,select,[role],h1,h2,h3,p,label,li';
              const nodes=Array.from(document.querySelectorAll(selector)).slice(0,500);
              return JSON.stringify({
                url:location.href,title:document.title,
                nodes:nodes.map((el,i)=>({
                  index:i,tag:el.tagName,id:el.id||'',
                  cls:(typeof el.className==='string'?el.className:'').slice(0,80),
                  text:(el.innerText||el.getAttribute('aria-label')||el.getAttribute('placeholder')||'').slice(0,160),
                  href:el.href||'',type:el.type||'',disabled:!!el.disabled
                }))
              });
            })()
        """.trimIndent()
    }
}
