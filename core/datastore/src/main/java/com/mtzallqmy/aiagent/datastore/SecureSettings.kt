package com.mtzallqmy.aiagent.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "aegis_settings")

/**
 * Settings store. Secret values are NEVER stored here; settings only hold
 * references to secrets in the CredentialVault (Keystore-encrypted).
 *
 * The locale preference is mirrored to a tiny non-secret SharedPreferences file
 * so Activity.attachBaseContext() can apply the language before DataStore has
 * asynchronously emitted its first value. This mirror contains no credentials.
 */
class SecureSettings(private val context: Context) {

    val selectedProviderId: Flow<String?> = context.settingsDataStore.data
        .map { it[stringPreferencesKey(KEY_SELECTED_PROVIDER)] }

    val selectedModelId: Flow<String?> = context.settingsDataStore.data
        .map { it[stringPreferencesKey(KEY_SELECTED_MODEL)] }

    val arabicLocale: Flow<Boolean> = context.settingsDataStore.data
        .map { it[booleanPreferencesKey(KEY_ARABIC_LOCALE)] ?: bootstrapArabicLocale(context) }

    val smartRouting: Flow<Boolean> = context.settingsDataStore.data
        .map { it[booleanPreferencesKey(KEY_SMART_ROUTING)] ?: false }

    val failoverEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[booleanPreferencesKey(KEY_FAILOVER_ENABLED)] ?: false }

    val remoteControlEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[booleanPreferencesKey(KEY_REMOTE_CONTROL_ENABLED)] ?: false }

    suspend fun setString(key: String, value: String?) {
        context.settingsDataStore.edit {
            if (value == null) it.remove(stringPreferencesKey(key))
            else it[stringPreferencesKey(key)] = value
        }
    }

    suspend fun setBoolean(key: String, value: Boolean) {
        if (key == KEY_ARABIC_LOCALE) {
            // Persist synchronously enough for the following Activity recreation to
            // see the new locale in attachBaseContext(). No secret data lives here.
            context.getSharedPreferences(BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ARABIC_LOCALE, value)
                .commit()
        }
        context.settingsDataStore.edit { it[booleanPreferencesKey(key)] = value }
    }

    suspend fun getString(key: String): String? =
        context.settingsDataStore.data.map { it[stringPreferencesKey(key)] }.first()

    suspend fun getBoolean(key: String): Boolean =
        context.settingsDataStore.data.map { it[booleanPreferencesKey(key)] ?: false }.first()

    companion object {
        const val KEY_SELECTED_PROVIDER = "selected_provider_id"
        const val KEY_SELECTED_MODEL = "selected_model_id"
        const val KEY_ARABIC_LOCALE = "arabic_locale"
        const val KEY_SMART_ROUTING = "smart_routing"
        const val KEY_FAILOVER_ENABLED = "failover_enabled"
        const val KEY_REMOTE_CONTROL_ENABLED = "remote_control_enabled"

        private const val BOOTSTRAP_PREFS = "aegis_bootstrap"

        fun bootstrapArabicLocale(context: Context): Boolean =
            context.getSharedPreferences(BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ARABIC_LOCALE, false)
    }
}
