package cc.solop.mediasync.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cc.solop.mediasync.BuildConfig
import cc.solop.mediasync.data.store.appDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class TokenStore(private val context: Context) {
    private val tokenKey = stringPreferencesKey("auth_token")

    @Volatile
    private var cachedToken: String = BuildConfig.DEFAULT_AUTH_TOKEN

    suspend fun warmup() {
        cachedToken = context.appDataStore.data.map { it[tokenKey] ?: BuildConfig.DEFAULT_AUTH_TOKEN }.first()
    }

    fun getCachedToken(): String = cachedToken

    suspend fun setToken(token: String) {
        cachedToken = token
        context.appDataStore.edit { prefs ->
            prefs[tokenKey] = token
        }
    }
}
