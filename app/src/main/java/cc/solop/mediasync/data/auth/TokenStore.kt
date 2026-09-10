package cc.solop.mediasync.data.auth

import android.content.Context
import cc.solop.mediasync.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TokenStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cachedToken: String = prefs.getString(KEY_TOKEN, BuildConfig.DEFAULT_AUTH_TOKEN)
        ?: BuildConfig.DEFAULT_AUTH_TOKEN

    private val _tokenFlow = MutableStateFlow(cachedToken)
    val tokenFlow: StateFlow<String> = _tokenFlow

    fun getCachedToken(): String = cachedToken

    fun setToken(token: String) {
        cachedToken = token
        prefs.edit().putString(KEY_TOKEN, token).apply()
        _tokenFlow.value = token
    }

    fun clearToken() {
        setToken("")
    }

    companion object {
        private const val PREFS_NAME = "media_sync_auth_prefs"
        private const val KEY_TOKEN = "auth_token"
    }
}
