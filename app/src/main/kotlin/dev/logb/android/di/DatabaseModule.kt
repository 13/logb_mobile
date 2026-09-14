package dev.logb.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.logb.android.core.db.DatabaseProvider
import dev.logb.android.core.db.LogbDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** Placeholder until the session exists (Task 5): one fixed mirror, so injection compiles. */
    @Provides
    @Singleton
    fun database(provider: DatabaseProvider): LogbDatabase = provider.open("placeholder", 0)
}
