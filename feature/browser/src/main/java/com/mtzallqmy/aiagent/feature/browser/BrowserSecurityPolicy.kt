package com.mtzallqmy.aiagent.feature.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Pure policy helpers so browser security behavior is testable without WebView. */
internal object BrowserSecurityPolicy {
    fun isAllowedUrl(url: String): Boolean = BrowserUrlPolicy.isAllowed(url)

    fun isSafeSelector(selector: String): Boolean =
        selector.isNotBlank() &&
            selector.length <= 2_048 &&
            selector.none { it.code < 0x20 || it == '\u007f' }

    fun isSafeText(text: String): Boolean = text.length <= 64 * 1024 && '\u0000' !in text
}

/**
 * Treat every webpage snapshot as untrusted. Form-control values are removed
 * before the snapshot leaves the browser backend.
 */
internal object BrowserSnapshotSanitizer {
    private const val MAX_SNAPSHOT_CHARS = 256 * 1024
    private const val MAX_NODES = 500
    private const val MAX_TEXT = 240
    private const val MAX_URL = 2_048
    private val json = Json { ignoreUnknownKeys = true }
    private val formTags = setOf("INPUT", "TEXTAREA", "SELECT")

    fun sanitize(raw: String): String {
        require(raw.length <= MAX_SNAPSHOT_CHARS * 2) { "Browser snapshot is unreasonably large" }
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw IllegalArgumentException("Malformed browser snapshot", it)
        }
        val nodes = root["nodes"]?.jsonArray.orEmpty().take(MAX_NODES)
        val sanitized = buildJsonObject {
            put("url", JsonPrimitive(root.string("url").take(MAX_URL)))
            put("title", JsonPrimitive(root.string("title").take(MAX_TEXT)))
            put("nodes", buildJsonArray {
                nodes.forEach { element ->
                    val node = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                    val tag = node.string("tag").uppercase().take(24)
                    add(buildJsonObject {
                        put("index", node["index"] ?: JsonPrimitive(-1))
                        put("tag", JsonPrimitive(tag))
                        put("id", JsonPrimitive(node.string("id").take(160)))
                        put("cls", JsonPrimitive(node.string("cls").take(160)))
                        put(
                            "text",
                            JsonPrimitive(
                                if (tag in formTags) "[form-field]"
                                else node.string("text").take(MAX_TEXT),
                            ),
                        )
                        put("href", JsonPrimitive(node.string("href").take(MAX_URL)))
                        put("type", JsonPrimitive(node.string("type").take(64)))
                        put("disabled", node["disabled"] ?: JsonPrimitive(false))
                    })
                }
            })
        }.toString()
        require(sanitized.length <= MAX_SNAPSHOT_CHARS) { "Browser snapshot exceeds safe size" }
        return sanitized
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
}
