package dev.logb.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.logb.android.core.notify.ReminderActions
import dev.logb.android.core.notify.ReminderNotifier
import dev.logb.android.core.sync.Repositories
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NotifyModule {
    @Provides
    @Singleton
    fun reminderActions(repos: Repositories, notifier: ReminderNotifier): ReminderActions =
        ReminderActions(
            done = { repos.reminderRepository.done(it, null) },
            snooze = { repos.reminderRepository.snooze(it, 7) },
            cancel = notifier::cancel,
        )
}
