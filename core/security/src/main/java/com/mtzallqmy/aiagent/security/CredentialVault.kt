package com.mtzallqmy.aiagent.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import java.security.ProviderException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random

/**
 * Secure credential storage backed by Android Keystore (AES-256-GCM).
 *
 * HARDENING (v1.1):
 * - Every secret is encrypted with its own independent Android-Keystore AES key
 *   (per scope/name alias), so rotating or deleting one secret never invalidates
 *   unrelated credentials.
 * - StrongBox availability is detected and requested when the device supports
 *   it (best-effort; falls back to TEE without failing).
 * - Corrupted ciphertext is quarantined (moved to a ".corrupt" entry) instead
 *   of silently destroyed, so the issue is observable and recoverable.
 * Keys never leave the TEE/StrongBox. Plaintext secrets are never stored in
 * DataStore, logs, messages, memory (beyond the loaded value), or analytics.
 */
class CredentialVault(
    context: Context,
    @get:VisibleForTesting internal val keystore: KeystoreGateway = AndroidKeystoreGateway(
        strongBoxAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            runCatching {
                context.applicationContext.packageManager
                    .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            }.getOrDefault(false),
    ),
) {
    private val appContext = context.applicationContext

    val supportsStrongBox: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) false
        else runCatching {
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        }.getOrDefault(false)
    }

    /** Encrypts and stores one secret under (scope, name). */
    fun save(scope: CredentialScope, name: String, secret: String) {
        requireCredentialName(name)
        val prefs = credentialPreferences(scope)
        val replacingExisting = prefs.contains(name)
        val replacingCorrupt = prefs.contains("$name.corrupt")
        val previousAlias = if (replacingExisting || replacingCorrupt) {
            runCatching { storedAlias(scope, name, prefs) }.getOrNull()
        } else {
            null
        }
        val alias = if (replacingExisting) previousAlias ?: deriveSubKeyAlias(scope.id, name)
        else deriveSubKeyAlias(scope.id, name)
        val startsFreshAliasMetadata = !replacingExisting || previousAlias == null
        val ciphertext = AuthenticatedAesGcm(keystore, alias).encrypt(secret.toByteArray(Charsets.UTF_8))
        val editor = prefs.edit()
            .putString(name, Base64.getEncoder().encodeToString(ciphertext))
            .remove("$name.corrupt")
            .remove("$name.corruptNote")
        if (startsFreshAliasMetadata) {
            // A fresh/recovered write starts from the deterministic v2 alias. Never
            // retain stale rotation metadata when an existing alias could not be resolved.
            editor
                .remove(legacyNonceKey(name))
                .remove(nonceKey(name))
                .putInt(aliasVersionKey(name), CURRENT_ALIAS_VERSION)
        }
        check(editor.commit()) { "Credential storage failed" }
        if (startsFreshAliasMetadata && previousAlias != null && previousAlias != alias) {
            runCatching { keystore.deleteKey(previousAlias) }
        }
    }

    /** Loads and decrypts one secret; returns null when missing or unrecoverable. */
    fun load(scope: CredentialScope, name: String): String? {
        requireCredentialName(name)
        val prefs = credentialPreferences(scope)
        val raw = prefs.getString(name, null) ?: return null
        return try {
            val ciphertext = Base64.getDecoder().decode(raw)
            val alias = storedAlias(scope, name, prefs)
            AuthenticatedAesGcm(keystore, alias).decrypt(ciphertext).toString(Charsets.UTF_8)
        } catch (error: Throwable) {
            if (error is ThreadDeath || error is VirtualMachineError) throw error
            quarantine(prefs, name, raw, error)
            null
        }
    }

    fun delete(scope: CredentialScope, name: String) {
        requireCredentialName(name)
        val prefs = credentialPreferences(scope)
        val alias = runCatching { storedAlias(scope, name, prefs) }.getOrNull()
        val committed = prefs.edit()
            .remove(name)
            .remove("$name.corrupt")
            .remove("$name.corruptNote")
            .remove(legacyNonceKey(name))
            .remove(nonceKey(name))
            .remove(aliasVersionKey(name))
            .commit()
        check(committed) { "Credential deletion failed" }
        alias?.let { runCatching { keystore.deleteKey(it) } }
    }

    fun has(scope: CredentialScope, name: String): Boolean {
        requireCredentialName(name)
        return credentialPreferences(scope).contains(name)
    }

    fun allNames(scope: CredentialScope): List<String> {
        val prefs = credentialPreferences(scope)
        val keys = prefs.all.keys
        return keys.asSequence()
            .filterNot { it.startsWith(METADATA_PREFIX) }
            .filterNot { it.endsWith(".corrupt") || it.endsWith(".corruptNote") }
            // Legacy rotation metadata used "$name.nonce". Hide it when its base secret exists.
            .filterNot { key -> key.endsWith(".nonce") && prefs.contains(key.removeSuffix(".nonce")) }
            .sorted()
            .toList()
    }

    /** Wipes a whole scope and removes the corresponding Keystore aliases. */
    fun clear(scope: CredentialScope) {
        val prefs = credentialPreferences(scope)
        val namesForCleanup = prefs.all.keys.asSequence()
            .filterNot { it.startsWith(METADATA_PREFIX) }
            .filterNot { it.endsWith(".corruptNote") }
            .map { key -> if (key.endsWith(".corrupt")) key.removeSuffix(".corrupt") else key }
            .filterNot { key -> key.endsWith(".nonce") && (prefs.contains(key.removeSuffix(".nonce")) || prefs.contains("${key.removeSuffix(".nonce")}.corrupt")) }
            .distinct()
            .toList()
        val aliases = namesForCleanup.mapNotNull { name ->
            runCatching { storedAlias(scope, name, prefs) }.getOrNull()
        }
        check(prefs.edit().clear().commit()) { "Credential scope clear failed" }
        aliases.forEach { alias -> runCatching { keystore.deleteKey(alias) } }
    }

    /**
     * Rotate one secret to a new independent sub-key. The nonce and alias version
     * are persisted atomically before the previous key is deleted, so a rotation
     * can never make a successfully stored secret unreadable.
     */
    fun rotate(scope: CredentialScope, name: String) {
        requireCredentialName(name)
        val prefs = credentialPreferences(scope)
        val oldAlias = storedAlias(scope, name, prefs)
        val secret = load(scope, name) ?: return
        val nonce = Random.nextBytes(16)
        val newAlias = deriveSubKeyAlias(scope.id, name, nonce)
        val ciphertext = AuthenticatedAesGcm(keystore, newAlias).encrypt(secret.toByteArray(Charsets.UTF_8))
        val committed = prefs.edit()
            .putString(nonceKey(name), Base64.getEncoder().encodeToString(nonce))
            .putInt(aliasVersionKey(name), CURRENT_ALIAS_VERSION)
            .remove(legacyNonceKey(name))
            .putString(name, Base64.getEncoder().encodeToString(ciphertext))
            .commit()
        if (!committed) {
            runCatching { keystore.deleteKey(newAlias) }
            error("Credential rotation failed")
        }
        if (oldAlias != newAlias) runCatching { keystore.deleteKey(oldAlias) }
    }

    private fun credentialPreferences(scope: CredentialScope) =
        appContext.getSharedPreferences("credentials:${scope.id}", Context.MODE_PRIVATE)

    private fun storedAlias(
        scope: CredentialScope,
        name: String,
        prefs: android.content.SharedPreferences,
    ): String {
        val nonce = prefs.getString(nonceKey(name), null)
            ?: prefs.getString(legacyNonceKey(name), null)
        val decodedNonce = nonce?.let { Base64.getDecoder().decode(it) }
        val version = if (prefs.contains(aliasVersionKey(name))) {
            prefs.getInt(aliasVersionKey(name), CURRENT_ALIAS_VERSION)
        } else {
            LEGACY_ALIAS_VERSION
        }
        return if (version >= CURRENT_ALIAS_VERSION) {
            deriveSubKeyAlias(scope.id, name, decodedNonce)
        } else {
            deriveLegacySubKeyAlias(scope.id, name, decodedNonce)
        }
    }

    private fun deriveSubKeyAlias(scopeId: String, name: String, nonce: ByteArray? = null): String {
        val nonceSuffix = nonce?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it).take(10) } ?: "d0"
        val identity = MessageDigest.getInstance("SHA-256")
            .digest("$scopeId\u0000$name".toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "aegis_v2_${scopeId.take(10)}_${identity}_$nonceSuffix"
            .replace(Regex("[^a-zA-Z0-9_]"), "_")
            .take(80)
            .lowercase()
    }

    /** Alias format used by v1.1 and kept only for decrypting existing installs. */
    private fun deriveLegacySubKeyAlias(scopeId: String, name: String, nonce: ByteArray? = null): String {
        val nonceSuffix = nonce?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it).take(10) } ?: "d0"
        val raw = "aegis_${scopeId.take(10)}_${name.take(24)}_$nonceSuffix"
        return raw.replace(Regex("[^a-zA-Z0-9_]"), "_").take(60).lowercase()
    }

    private fun quarantine(
        prefs: android.content.SharedPreferences,
        name: String,
        raw: String,
        cause: Throwable,
    ) {
        val note = "corrupt:${cause.javaClass.simpleName}:${System.currentTimeMillis()}"
        prefs.edit()
            .remove(name)
            .putString("$name.corrupt", raw)
            .putString("$name.corruptNote", note)
            .apply()
    }

    private fun requireCredentialName(name: String) {
        require(CREDENTIAL_NAME.matches(name)) { "Invalid credential name" }
    }

    private fun nonceKey(name: String) = "${metadataPrefix(name)}.nonce"
    private fun aliasVersionKey(name: String) = "${metadataPrefix(name)}.aliasVersion"
    private fun legacyNonceKey(name: String) = "$name.nonce"
    private fun metadataPrefix(name: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "$METADATA_PREFIX$hash"
    }

    companion object {
        private const val KEY_ALIAS = "aegis_credential_key"
        const val CIPHER_KEY_SIZE = 256
        const val GCM_TAG_LENGTH = 128
        const val GCM_IV_LENGTH = 12
        private const val LEGACY_ALIAS_VERSION = 1
        private const val CURRENT_ALIAS_VERSION = 2
        private const val METADATA_PREFIX = "__aegis_meta__"
        private val CREDENTIAL_NAME = Regex("^[A-Za-z0-9_.-]{1,96}$")
    }
}

data class CredentialScope(val id: String) {
    companion object {
        val PROVIDER = CredentialScope("provider")
        val SSH = CredentialScope("ssh")
        val MCP = CredentialScope("mcp")
        val API_KEY_POOL = CredentialScope("keypool")
    }
}

/** Keystore abstraction for testability. */
interface KeystoreGateway {
    fun loadOrCreateKey(alias: String): SecretKey
    fun deleteKey(alias: String)
}

internal class AndroidKeystoreGateway(
    @get:VisibleForTesting private val strongBoxAvailable: Boolean = false,
) : KeystoreGateway {
    override fun loadOrCreateKey(alias: String): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) {
            return ks.getKey(alias, null) as SecretKey
        }

        val tryStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBoxAvailable
        return if (tryStrongBox) {
            try {
                generateKey(alias, useStrongBox = true)
            } catch (_: ProviderException) {
                // StrongBox is best-effort. Some devices advertise support but can
                // still reject allocation (capacity/firmware/provider errors).
                generateKey(alias, useStrongBox = false)
            }
        } else {
            generateKey(alias, useStrongBox = false)
        }
    }

    private fun generateKey(alias: String, useStrongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(CredentialVault.CIPHER_KEY_SIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && useStrongBox) {
            builder.setIsStrongBoxBacked(true)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    override fun deleteKey(alias: String) {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

}

/** AES-256-GCM authenticated encryption with random IV per operation. */
internal class AuthenticatedAesGcm(
    private val gateway: KeystoreGateway,
    private val alias: String,
) {
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, gateway.loadOrCreateKey(alias))
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        // layout: iv (12) || ciphertext (with tag)
        return iv + ciphertext
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size >= GCM_IV_LENGTH + GCM_TAG_LENGTH / 8) { "Malformed encrypted credential" }
        val iv = blob.take(GCM_IV_LENGTH).toByteArray()
        val ciphertext = blob.drop(GCM_IV_LENGTH).toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, gateway.loadOrCreateKey(alias), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    fun resetCipherState() {
        // Ciphers are per-operation; nothing persistent to reset.
    }

    companion object {
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }
}

/** API key pool strategies for provider fallback (per requirements). */
enum class KeyPoolStrategy { PRIMARY, FAILOVER, ROUND_ROBIN, WEIGHTED }

class ApiKeyPool(
    keys: List<ProviderKeyEntry>,
    private val strategy: KeyPoolStrategy = KeyPoolStrategy.PRIMARY,
) {
    /** Mutable pool so keys can be enabled/disabled and health-tracked at runtime. */
    private val entries: MutableList<ProviderKeyEntry> = keys.toMutableList()

    @Synchronized
    fun add(entry: ProviderKeyEntry) { entries.add(entry) }

    @Synchronized
    fun remove(secretRef: String): Boolean = entries.removeAll { it.secretRef == secretRef }

    @Synchronized
    fun enable(secretRef: String, enabled: Boolean) {
        entries.firstOrNull { it.secretRef == secretRef }?.enabled = enabled
    }

    @Synchronized
    fun updateHealth(secretRef: String, success: Boolean, statusCode: Int? = null, rateLimitUntil: Long? = null) {
        val entry = entries.firstOrNull { it.secretRef == secretRef } ?: return
        val now = System.currentTimeMillis()
        if (success) {
            entry.lastSuccess = now
            entry.errorCount = 0
            entry.rateLimitedUntil = null
        } else {
            entry.lastError = now
            entry.errorCount += 1
            entry.rateLimitedUntil = rateLimitUntil ?: entry.rateLimitedUntil
        }
        entry.lastStatusCode = statusCode ?: entry.lastStatusCode
    }

    @Synchronized
    fun healthSummary(): List<String> = entries.map { entry ->
        val masked = entry.secretRef.take(8) + "****" + entry.secretRef.takeLast(4)
        "${masked} enabled=${entry.enabled} errors=${entry.errorCount} lastStatus=${entry.lastStatusCode}"
    }

    @Synchronized
    fun current(): ProviderKeyEntry? = usableEntries().run {
        when (strategy) {
            KeyPoolStrategy.PRIMARY -> firstOrNull()
            KeyPoolStrategy.FAILOVER -> firstOrNull()
            KeyPoolStrategy.ROUND_ROBIN -> getOrNull(roundRobinIndex % maxOf(size, 1)).also { roundRobinIndex++ }
            KeyPoolStrategy.WEIGHTED -> weightedPick()
        }
    }

    /** Failover: pick next usable key; do NOT rotate on 400/model-not-found (client errors). */
    @Synchronized
    fun failover(exclude: String, statusCode: Int?): ProviderKeyEntry? {
        if (statusCode != null && (statusCode in 400..499 && statusCode != 429)) return null
        return usableEntries().firstOrNull { it.secretRef != exclude }
    }

    /** Usable keys: enabled and not currently rate-limited. */
    private fun usableEntries(): List<ProviderKeyEntry> =
        entries.filter { it.enabled && (it.rateLimitedUntil ?: 0L) < System.currentTimeMillis() }

    private var roundRobinIndex = 0

    private fun weightedPick(): ProviderKeyEntry? {
        val usable = usableEntries()
        if (usable.isEmpty()) return null
        val total = usable.sumOf { it.weight }
        if (total <= 0) return usable.first()
        val roll = Random.nextInt(total)
        var acc = 0
        for (entry in usable) {
            acc += entry.weight
            if (roll < acc) return entry
        }
        return usable.last()
    }
}

data class ProviderKeyEntry(
    val secretRef: String,
    val weight: Int = 1,
    var enabled: Boolean = true,
    var errorCount: Int = 0,
    var lastSuccess: Long? = null,
    var lastError: Long? = null,
    var lastStatusCode: Int? = null,
    var rateLimitedUntil: Long? = null,
)
