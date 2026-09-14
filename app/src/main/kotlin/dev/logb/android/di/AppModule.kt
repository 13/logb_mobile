package dev.logb.android.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.DataStoreServerStore
import dev.logb.android.core.auth.KeystoreTokenStore
import dev.logb.android.core.auth.ServerStore
import dev.logb.android.core.auth.TokenStore
import dev.logb.android.core.network.ApiClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds abstract fun serverStore(impl: DataStoreServerStore): ServerStore

    @Binds abstract fun tokenStore(impl: KeystoreTokenStore): TokenStore

    companion object {
        @Provides
        @Singleton
        fun apiFactory(): ApiFactory = ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) }
    }
}
