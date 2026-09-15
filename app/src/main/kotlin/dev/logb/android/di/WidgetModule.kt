package dev.logb.android.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.logb.android.core.widget.WidgetRefresher
import dev.logb.android.feature.widget.RealWidgetRefresher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetModule {
    @Binds
    @Singleton
    abstract fun widgetRefresher(impl: RealWidgetRefresher): WidgetRefresher
}
