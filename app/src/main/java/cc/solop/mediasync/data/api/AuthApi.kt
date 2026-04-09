package cc.solop.mediasync.data.api

import retrofit2.http.Body
import retrofit2.http.POST

data class LoginRequest(
    val email: String,
    val password: String,
    val strategy: String = "local",
)

data class LoginResponse(
    val accessToken: String? = null,
)

interface AuthApi {
    @POST("api/authentication")
    suspend fun login(@Body body: LoginRequest): LoginResponse
}
