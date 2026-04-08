package cc.solop.mediasync.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.solop.mediasync.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "media_sync_prefs")

class TokenStore(private val context: Context) {
    private val tokenKey = stringPreferencesKey("auth_token")

    @Volatile
    private var cachedToken: String = BuildConfig.DEFAULT_AUTH_TOKEN

    suspend fun warmup() {
        cachedToken = context.dataStore.data.map { it[tokenKey] ?: BuildConfig.DEFAULT_AUTH_TOKEN }.first()
    }

    fun getCachedToken(): String = cachedToken

    suspend fun setToken(token: String) {
        cachedToken = token
        context.dataStore.edit { prefs ->
            prefs[tokenKey] = token
        }
    }
}
