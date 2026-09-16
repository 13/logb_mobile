package dev.logb.android.core.network

import dev.logb.android.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** One `Json` for everything the server sends: unknown keys are the server being newer than the app, not an error. */
val LogbJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

object ApiClient {
    const val USER_AGENT_PREFIX = "LogB-Android/"

    /** `https://` when no scheme was typed, and exactly one trailing slash, which Retrofit requires. */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return url.trimEnd('/') + "/"
    }

    fun okHttp(tokenProvider: () -> String?, cookieJar: CookieJar? = null): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .apply { if (cookieJar != null) cookieJar(cookieJar) }
            .addInterceptor(HeadersInterceptor(tokenProvider))
            .addInterceptor(ErrorInterceptor)
            .apply {
                if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            }
            .build()

    fun create(baseUrl: String, tokenProvider: () -> String?, cookieJar: CookieJar? = null): LogbApi =
        Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(okHttp(tokenProvider, cookieJar))
            .addConverterFactory(LogbJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(LogbApi::class.java)

    /** The bearer token when there is one, and a user agent that names the app, on every request. */
    private class HeadersInterceptor(private val tokenProvider: () -> String?) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val builder = chain.request().newBuilder().header("User-Agent", "$USER_AGENT_PREFIX${BuildConfig.VERSION_NAME}")
            tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
            return chain.proceed(builder.build())
        }
    }

    /**
     * Turns a non-2xx answer into an exception carrying the server's `{error, message}`. Done here
     * rather than at every call site, so a call site only ever sees a parsed body or an exception.
     */
    private object ErrorInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            if (response.isSuccessful) return response
            val (code, message) = response.use { parseError(it) }
            throw when (response.code) {
                401 -> UnauthorizedException(message, chain.request().header("Authorization")?.removePrefix("Bearer "))
                410 -> GoneException(message)
                else -> ApiException(response.code, code, message)
            }
        }

        private fun parseError(response: Response): Pair<String, String> {
            val fallback = "http_${response.code}" to (response.message.ifBlank { "HTTP ${response.code}" })
            val text = runCatching { response.body.string() }.getOrNull() ?: return fallback
            val body = runCatching { LogbJson.decodeFromString(ErrorBody.serializer(), text) }.getOrNull() ?: return fallback
            return body.error to body.message
        }
    }

    @kotlinx.serialization.Serializable
    private data class ErrorBody(val error: String = "error", val message: String = "")
}
