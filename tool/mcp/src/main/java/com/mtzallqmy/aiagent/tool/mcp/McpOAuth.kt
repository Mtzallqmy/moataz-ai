package com.mtzallqmy.aiagent.tool.mcp

import com.mtzallqmy.aiagent.network.SafeHttpClient
import com.mtzallqmy.aiagent.security.CredentialScope
import com.mtzallqmy.aiagent.security.CredentialVault
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request

data class McpOAuthConfiguration(
    val serverId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: Set<String>,
) {
    init {
        require(serverId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        requireSecureEndpoint(authorizationEndpoint)
        requireSecureEndpoint(tokenEndpoint)
        require(clientId.isNotBlank())
        require(URI(redirectUri).scheme?.isNotBlank() == true)
        require(scopes.isNotEmpty() && scopes.all { it.matches(Regex("[A-Za-z0-9:._/-]{1,128}")) })
    }
    private fun requireSecureEndpoint(endpoint: String) {
        require(endpoint.startsWith("https://") && SafeHttpClient.normalizeUrl(endpoint) == endpoint) {
            "OAuth endpoints must be normalized public HTTPS URLs"
        }
    }
}

@Serializable
data class McpOAuthTokens(
    val accessToken: String,
    val tokenType: String,
    val expiresAtMillis: Long?,
    val refreshToken: String?,
    val scope: String?,
)

interface McpOAuthTokenStore {
    fun load(serverId: String): McpOAuthTokens?
    fun save(serverId: String, tokens: McpOAuthTokens)
    fun clear(serverId: String)
}

class CredentialVaultMcpOAuthTokenStore(private val vault: CredentialVault) : McpOAuthTokenStore {
    private val json = Json { ignoreUnknownKeys = false }
    override fun load(serverId: String): McpOAuthTokens? = vault.load(CredentialScope.MCP, key(serverId))?.let {
        runCatching { json.decodeFromString<McpOAuthTokens>(it) }.getOrNull()
    }
    override fun save(serverId: String, tokens: McpOAuthTokens) {
        vault.save(CredentialScope.MCP, key(serverId), json.encodeToString(tokens))
    }
    override fun clear(serverId: String) = vault.delete(CredentialScope.MCP, key(serverId))
    private fun key(serverId: String) = "oauth_$serverId"
}

data class McpOAuthAuthorization(val authorizationUrl: String, val state: String)

/** OAuth 2.1 authorization-code flow with PKCE, state verification, and refresh. */
class McpOAuthClient(
    private val configuration: McpOAuthConfiguration,
    private val tokenStore: McpOAuthTokenStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : McpAuthentication {
    private data class Pending(val state: String, val verifier: String)
    private val random = SecureRandom()
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = SafeHttpClient.create(timeoutMs = 30_000)
    private var pending: Pending? = null

    suspend fun beginAuthorization(): McpOAuthAuthorization = lock.withLock {
        val state = randomUrlToken(32)
        val verifier = randomUrlToken(64)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
        )
        pending = Pending(state, verifier)
        val query = linkedMapOf(
            "response_type" to "code",
            "client_id" to configuration.clientId,
            "redirect_uri" to configuration.redirectUri,
            "scope" to configuration.scopes.sorted().joinToString(" "),
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        ).entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val separator = if (configuration.authorizationEndpoint.contains('?')) "&" else "?"
        McpOAuthAuthorization(configuration.authorizationEndpoint + separator + query, state)
    }

    suspend fun completeAuthorization(code: String, state: String): McpOAuthTokens = lock.withLock {
        val request = pending ?: throw IllegalStateException("No OAuth authorization is pending")
        if (!MessageDigest.isEqual(state.toByteArray(), request.state.toByteArray())) {
            pending = null
            throw SecurityException("OAuth state mismatch")
        }
        require(code.isNotBlank())
        pending = null
        val tokens = exchange(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", configuration.redirectUri)
                .add("client_id", configuration.clientId)
                .add("code_verifier", request.verifier)
                .build(),
        )
        tokenStore.save(configuration.serverId, tokens)
        tokens
    }

    override suspend fun authorizationHeaders(): Map<String, String> = lock.withLock {
        var tokens = tokenStore.load(configuration.serverId) ?: return emptyMap()
        val expiry = tokens.expiresAtMillis
        if (expiry != null && expiry <= clock() + REFRESH_SKEW_MILLIS) {
            val refresh = tokens.refreshToken ?: run {
                tokenStore.clear(configuration.serverId)
                return emptyMap()
            }
            tokens = exchange(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .add("client_id", configuration.clientId)
                    .build(),
                previousRefreshToken = refresh,
            )
            tokenStore.save(configuration.serverId, tokens)
        }
        if (!tokens.tokenType.equals("Bearer", ignoreCase = true)) {
            throw SecurityException("Unsupported OAuth token type")
        }
        mapOf("Authorization" to "Bearer ${tokens.accessToken}")
    }

    suspend fun revokeLocally() = lock.withLock {
        pending = null
        tokenStore.clear(configuration.serverId)
    }

    private suspend fun exchange(body: FormBody, previousRefreshToken: String? = null): McpOAuthTokens =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(configuration.tokenEndpoint).post(body).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw SecurityException("OAuth token exchange failed with HTTP ${response.code}")
                }
                val parsed = json.decodeFromString<TokenResponse>(
                    response.body?.string() ?: throw SecurityException("OAuth token response is empty"),
                )
                require(parsed.accessToken.isNotBlank()) { "OAuth access token is empty" }
                val expiresAt = parsed.expiresIn?.let { seconds ->
                    Math.addExact(clock(), Math.multiplyExact(seconds, 1_000L))
                }
                McpOAuthTokens(
                    accessToken = parsed.accessToken,
                    tokenType = parsed.tokenType,
                    expiresAtMillis = expiresAt,
                    refreshToken = parsed.refreshToken ?: previousRefreshToken,
                    scope = parsed.scope,
                )
            }
        }

    private fun randomUrlToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    @Serializable
    private data class TokenResponse(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String,
        @kotlinx.serialization.SerialName("token_type") val tokenType: String = "Bearer",
        @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long? = null,
        @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String? = null,
        val scope: String? = null,
    )

    private companion object {
        const val REFRESH_SKEW_MILLIS = 60_000L
    }
}
