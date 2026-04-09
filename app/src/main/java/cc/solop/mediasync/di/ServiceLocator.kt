package cc.solop.mediasync.di

import android.content.Context
import cc.solop.mediasync.BuildConfig
import cc.solop.mediasync.data.api.AuthApi
import cc.solop.mediasync.data.api.MediaApi
import cc.solop.mediasync.data.auth.TokenStore
import cc.solop.mediasync.data.media.MediaStoreScanner
import cc.solop.mediasync.data.repo.SyncStatusRepository
import cc.solop.mediasync.domain.UploadOrchestrator
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

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

    val uploadHttpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            // Keep uploader parallelism modest to reduce socket contention on mobile networks.
            maxRequests = 2
            maxRequestsPerHost = 2
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }

    val mediaApi: MediaApi by lazy {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(MediaApi::class.java)
    }

    val authApi: AuthApi by lazy {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(AuthApi::class.java)
    }

    val mediaStoreScanner: MediaStoreScanner by lazy { MediaStoreScanner(appContext) }
    val syncStatusRepository: SyncStatusRepository by lazy { SyncStatusRepository(appContext) }
    val uploadOrchestrator: UploadOrchestrator by lazy {
        UploadOrchestrator(
            mediaApi = mediaApi,
            contentResolver = appContext.contentResolver,
            uploadHttpClient = uploadHttpClient,
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
