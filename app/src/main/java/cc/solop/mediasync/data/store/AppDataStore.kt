package cc.solop.mediasync.data.store

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.appDataStore by preferencesDataStore(name = "media_sync_prefs")
