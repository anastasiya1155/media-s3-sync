package cc.solop.mediasync.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import cc.solop.mediasync.BuildConfig
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TokenStore(private val context: Context) {
    private val legacyPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val prefs = createEncryptedPrefsOrNull()

    @Volatile
    private var cachedToken: String = loadInitialToken()

    private val _tokenFlow = MutableStateFlow(cachedToken)
    val tokenFlow: StateFlow<String> = _tokenFlow

    fun getCachedToken(): String = cachedToken

    fun setToken(token: String) {
        cachedToken = token
        prefs?.edit()?.putString(KEY_TOKEN, token)?.apply()
        legacyPrefs.edit().remove(KEY_TOKEN).apply()
        _tokenFlow.value = token
    }

    fun clearToken() {
        setToken("")
    }

    private fun loadInitialToken(): String {
        val persisted = prefs?.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        if (persisted != null) {
            legacyPrefs.edit().remove(KEY_TOKEN).apply()
            return persisted
        }

        val legacyToken = legacyPrefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        if (legacyToken != null) {
            prefs?.edit()?.putString(KEY_TOKEN, legacyToken)?.apply()
            legacyPrefs.edit().remove(KEY_TOKEN).apply()
            return legacyToken
        }

        legacyPrefs.edit().remove(KEY_TOKEN).apply()
        return BuildConfig.DEFAULT_AUTH_TOKEN
    }

    private fun createEncryptedPrefsOrNull(): SharedPreferences? {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME_SECURE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.onFailure {
            Log.e(TAG, "Failed to initialize encrypted token storage", it)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "TokenStore"
        private const val PREFS_NAME = "media_sync_auth_prefs"
        private const val PREFS_NAME_SECURE = "media_sync_auth_secure_prefs"
        private const val KEY_TOKEN = "auth_token"
    }
}
