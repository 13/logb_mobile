package dev.logb.android.core.network

import dev.logb.android.core.network.dto.BootstrapResult
import dev.logb.android.core.network.dto.Credentials
import dev.logb.android.core.network.dto.NewApiToken
import dev.logb.android.core.network.dto.NewToken
import dev.logb.android.core.network.dto.PullResult
import dev.logb.android.core.network.dto.PushBody
import dev.logb.android.core.network.dto.PushResult
import dev.logb.android.core.network.dto.Settings
import dev.logb.android.core.network.dto.User
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** The server's HTTP API, as `docs/openapi.json` in the logb repo describes it. Only what this app calls. */
interface LogbApi {
    @GET("api/health") suspend fun health(): Response<Unit>

    @POST("api/auth/login") suspend fun login(@Body body: Credentials): User

    @GET("api/auth/me") suspend fun me(): User

    @POST("api/auth/tokens") suspend fun createToken(@Body body: NewToken): NewApiToken

    @DELETE("api/auth/tokens/{id}") suspend fun revokeToken(@Path("id") id: Long): Response<Unit>

    @POST("api/auth/logout") suspend fun logout(): Response<Unit>

    @GET("api/settings") suspend fun settings(): Settings

    @GET("api/sync/bootstrap") suspend fun bootstrap(): BootstrapResult

    @GET("api/sync/pull")
    suspend fun pull(@Query("since") since: Long, @Query("epoch") epoch: String?, @Query("limit") limit: Int): PullResult

    @POST("api/sync/push") suspend fun push(@Body body: PushBody): PushResult
}
