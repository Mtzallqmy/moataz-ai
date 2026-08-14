package com.mtzallqmy.aiagent.feature.browser

import android.net.Uri
import com.mtzallqmy.aiagent.model.CapabilityId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * A browser boundary that exposes only operations the selected backend can
 * actually perform. Callers must inspect [capabilities] before offering an
 * operation in the UI or granting it to an agent.
 *
 * Page text, DOM snapshots, remote task output, and artifact metadata are
 * untrusted input. They must never be promoted to system instructions.
 */
interface BrowserBackend {
    val id: String
    val name: String
    val capabilities: Set<CapabilityId>

    suspend fun isAvailable(): Boolean
    suspend fun open(url: String): BrowserTab?
    suspend fun tabs(): List<BrowserTab>
    suspend fun activate(tabId: String): Boolean
    suspend fun navigate(url: String): Boolean
    suspend fun currentState(): BrowserState
    suspend fun find(query: String): Int
    suspend fun click(selector: String): Boolean
    suspend fun type(selector: String, text: String): Boolean
    suspend fun submitForm(selector: String? = null): Boolean
    suspend fun scroll(deltaY: Int): Boolean
    suspend fun evaluate(script: String): JsonElement?
    suspend fun upload(selector: String, files: List<Uri>): Boolean
    suspend fun download(selector: String): BrowserArtifact?
    suspend fun cookies(): List<BrowserCookie>
    suspend fun setCookie(cookie: BrowserCookie): Boolean
    suspend fun clearCookies(): Boolean
    suspend fun close(tabId: String? = null): Boolean
    suspend fun verify(expected: BrowserExpectation): BrowserVerification
}

data class BrowserTab(
    val id: String,
    val url: String,
    val title: String,
    val active: Boolean,
)

data class BrowserState(
    val tabId: String,
    val url: String,
    val title: String,
    val accessibleTree: String,
    val capturedAt: Instant = Instant.now(),
)

data class BrowserExpectation(
    val urlContains: String? = null,
    val titleContains: String? = null,
    val elementPresent: String? = null,
    val textContains: String? = null,
)

data class BrowserVerification(
    val passed: Boolean,
    val failures: List<String>,
    val observed: BrowserState,
)

data class BrowserCookie(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String = "/",
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
    val expiresAtEpochSeconds: Long? = null,
)

data class BrowserArtifact(
    val id: String,
    val name: String,
    val uri: String,
    val mediaType: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

enum class RemoteBrowserJobStatus {
    QUEUED,
    DISPATCHING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
}

data class RemoteBrowserJob(
    val id: String,
    val sessionId: String?,
    val status: RemoteBrowserJobStatus,
    val result: String? = null,
    val error: String? = null,
    val screenshots: List<String> = emptyList(),
    val artifacts: List<BrowserArtifact> = emptyList(),
)

sealed interface BrowserAction {
    data class Navigate(val url: String) : BrowserAction
    data class Click(val selector: String) : BrowserAction
    data class Type(val selector: String, val text: String) : BrowserAction
    data class Submit(val selector: String? = null) : BrowserAction
    data class Scroll(val deltaY: Int) : BrowserAction
}

data class VerifiedBrowserAction(
    val actionSucceeded: Boolean,
    val verification: BrowserVerification,
)

/** Enforces Observe -> Select -> Execute -> Observe -> Verify. */
class BrowserVerificationLoop(private val backend: BrowserBackend) {
    suspend fun execute(action: BrowserAction, expected: BrowserExpectation): VerifiedBrowserAction {
        backend.currentState()
        currentCoroutineContext().ensureActive()
        val succeeded = when (action) {
            is BrowserAction.Navigate -> backend.navigate(action.url)
            is BrowserAction.Click -> backend.click(action.selector)
            is BrowserAction.Type -> backend.type(action.selector, action.text)
            is BrowserAction.Submit -> backend.submitForm(action.selector)
            is BrowserAction.Scroll -> backend.scroll(action.deltaY)
        }
        currentCoroutineContext().ensureActive()
        val verification = backend.verify(expected)
        return VerifiedBrowserAction(
            actionSucceeded = succeeded,
            verification = verification.copy(passed = succeeded && verification.passed),
        )
    }
}

internal fun verifyState(state: BrowserState, expected: BrowserExpectation): BrowserVerification {
    val failures = buildList {
        expected.urlContains?.takeUnless { state.url.contains(it, ignoreCase = true) }
            ?.let { add("URL does not contain the expected value") }
        expected.titleContains?.takeUnless { state.title.contains(it, ignoreCase = true) }
            ?.let { add("Title does not contain the expected value") }
        expected.elementPresent?.takeUnless { state.accessibleTree.contains(it, ignoreCase = true) }
            ?.let { add("Expected element was not observed") }
        expected.textContains?.takeUnless { state.accessibleTree.contains(it, ignoreCase = true) }
            ?.let { add("Expected text was not observed") }
    }
    return BrowserVerification(failures.isEmpty(), failures, state)
}
