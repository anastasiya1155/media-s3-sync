package cc.solop.mediasync.di

import android.content.Context
import cc.solop.mediasync.BuildConfig
import cc.solop.mediasync.data.api.MediaApi
import cc.solop.mediasync.data.auth.TokenStore
import cc.solop.mediasync.data.media.MediaStoreScanner
import cc.solop.mediasync.data.repo.SyncStatusRepository
import cc.solop.mediasync.domain.UploadOrchestrator
import com.squareup.moshi.Moshi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class ServiceLocator private constructor(private val appContext: Context) {

    val tokenStore: TokenStore by lazy { TokenStore(appContext) }

    private val authInterceptor = Interceptor { chain ->
        val token = tokenStore.getCachedToken().trim()
        val request = if (token.isNotBlank()) {
            chain.request().newBuilder().addHeader("authorization", token).build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().addInterceptor(authInterceptor).build()
    }

    val mediaApi: MediaApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
            .build()
            .create(MediaApi::class.java)
    }

    val mediaStoreScanner: MediaStoreScanner by lazy { MediaStoreScanner(appContext) }
    val syncStatusRepository: SyncStatusRepository by lazy { SyncStatusRepository(appContext) }
    val uploadOrchestrator: UploadOrchestrator by lazy {
        UploadOrchestrator(
            mediaApi = mediaApi,
            contentResolver = appContext.contentResolver,
            okHttpClient = okHttpClient,
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: ServiceLocator? = null

        fun from(context: Context): ServiceLocator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ServiceLocator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
