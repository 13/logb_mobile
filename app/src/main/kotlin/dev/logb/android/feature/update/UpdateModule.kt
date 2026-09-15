package dev.logb.android.feature.update

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.logb.android.BuildConfig
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.network.LogbJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The client for api.github.com and the APK download. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateDownloads

@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    /**
     * Built on its own, not from ApiClient.okHttp: that client adds the LogB token, which must
     * never leave for GitHub. Long read and call timeouts for a ten-megabyte APK on a weak connection.
     */
    @Provides @Singleton @UpdateDownloads
    fun updateHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(15, TimeUnit.MINUTES)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "${ApiClient.USER_AGENT_PREFIX}${BuildConfig.VERSION_NAME}").build())
        }
        .build()

    @Provides @Singleton
    fun gitHubApi(@UpdateDownloads client: OkHttpClient): GitHubApi = Retrofit.Builder()
        .baseUrl(GitHubApi.BASE_URL)
        .client(client)
        .addConverterFactory(LogbJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GitHubApi::class.java)

    @Provides
    fun apkSignatures(impl: PackageManagerApkSignatures): ApkSignatures = impl
}
