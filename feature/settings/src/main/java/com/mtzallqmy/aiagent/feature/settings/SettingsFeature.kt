package com.mtzallqmy.aiagent.feature.settings

import com.mtzallqmy.aiagent.datastore.SecureSettings
import com.mtzallqmy.aiagent.security.CredentialScope
import com.mtzallqmy.aiagent.security.CredentialVault
import kotlinx.coroutines.flow.Flow

/**
 * Settings feature: UI reads/writes preferences. Keys themselves are stored
 * ONLY in the CredentialVault (Keystore-encrypted); settings only hold refs.
 */
class SettingsFeature(
    private val settings: SecureSettings,
    private val vault: CredentialVault,
) {
    val selectedProviderId: Flow<String?> = settings.selectedProviderId
    val arabicLocale: Flow<Boolean> = settings.arabicLocale
    val smartRouting: Flow<Boolean> = settings.smartRouting
    val failoverEnabled: Flow<Boolean> = settings.failoverEnabled
    val remoteControlEnabled: Flow<Boolean> = settings.remoteControlEnabled

    suspend fun setSelectedProvider(providerId: String, modelId: String?) {
        settings.setString("selected_provider_id", providerId)
        settings.setString("selected_model_id", modelId)
    }

    suspend fun setBoolean(key: String, value: Boolean) = settings.setBoolean(key, value)

    /** Store a provider API key securely; return number of keys in the pool. */
    fun storeProviderKey(providerId: String, secretRef: String, key: String): Int {
        vault.save(CredentialScope.PROVIDER, secretRef, key)
        return vault.allNames(CredentialScope.PROVIDER).size
    }

    /** How many keys are registered for a provider (never returns the keys). */
    fun providerKeyCount(providerId: String): Int =
        vault.allNames(CredentialScope.PROVIDER).count { it.startsWith(providerId) }

    fun removeProviderKey(providerId: String, secretRef: String) {
        vault.delete(CredentialScope.PROVIDER, secretRef)
    }
}
