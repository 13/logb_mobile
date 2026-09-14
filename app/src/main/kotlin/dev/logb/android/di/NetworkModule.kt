package dev.logb.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.network.LogbApi
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /** Placeholder until the session exists (Task 5): a client with no server and no token. */
    @Provides
    @Singleton
    fun api(): LogbApi = ApiClient.create("https://localhost/", tokenProvider = { null })
}
