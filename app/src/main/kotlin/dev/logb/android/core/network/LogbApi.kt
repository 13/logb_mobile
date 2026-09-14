package dev.logb.android.core.network

import dev.logb.android.core.network.dto.ActivityDto
import dev.logb.android.core.network.dto.AttachmentDto
import dev.logb.android.core.network.dto.ActivityInput
import dev.logb.android.core.network.dto.BootstrapResult
import dev.logb.android.core.network.dto.Credentials
import dev.logb.android.core.network.dto.HealthInfo
import dev.logb.android.core.network.dto.UserPatch
import dev.logb.android.core.network.dto.NewApiToken
import dev.logb.android.core.network.dto.NewToken
import dev.logb.android.core.network.dto.ObjectDto
import dev.logb.android.core.network.dto.ObjectInput
import dev.logb.android.core.network.dto.PullResult
import dev.logb.android.core.network.dto.PushBody
import dev.logb.android.core.network.dto.PushResult
import dev.logb.android.core.network.dto.ReminderDto
import dev.logb.android.core.network.dto.ReminderInput
import dev.logb.android.core.network.dto.Settings
import dev.logb.android.core.network.dto.User
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Streaming
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.PartMap
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** The server's HTTP API, as `docs/openapi.json` in the logb repo describes it. Only what this app calls. */
interface LogbApi {
    @GET("api/health") suspend fun health(): Response<Unit>

    @GET("api/health") suspend fun healthInfo(): HealthInfo

    /** Ends every browser session of the account; API tokens (this phone's included) stay. */
    @POST("api/auth/logout-all") suspend fun logoutAll(): Response<Unit>

    @PATCH("api/users/{id}") suspend fun updateUser(@Path("id") id: Long, @Body body: UserPatch): Response<Unit>

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

    // Creates go through REST carrying the row's client_uuid: the server inserts, the change
    // feed announces. A replay with the same client_uuid answers 200 with the same row.
    @POST("api/objects") suspend fun createObject(@Body body: ObjectInput): ObjectDto

    @POST("api/objects/{id}/activities") suspend fun createActivity(@Path("id") objectId: Long, @Body body: ActivityInput): ActivityDto

    @POST("api/objects/{id}/reminders") suspend fun createReminder(@Path("id") objectId: Long, @Body body: ReminderInput): ReminderDto

    @Streaming @GET("api/files/{id}") suspend fun downloadOriginal(@Path("id") fileId: Long): ResponseBody

    @Streaming @GET("api/files/{id}/thumb") suspend fun downloadThumb(@Path("id") fileId: Long): ResponseBody

    /** The multipart upload; identical bytes are stored once server-side and answer with the shared `file_uuid`. */
    @Multipart
    @POST("api/objects/{id}/attachments")
    suspend fun upload(@Path("id") objectId: Long, @Part file: MultipartBody.Part, @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>): AttachmentDto
}
