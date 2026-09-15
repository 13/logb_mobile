package dev.logb.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.notify.ReminderActions
import dev.logb.android.core.notify.ReminderNotifier
import dev.logb.android.core.sync.Repositories
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NotifyModule {
    @Provides
    @Singleton
    fun reminderActions(repos: Repositories, notifier: ReminderNotifier, sessions: SessionRepository): ReminderActions =
        ReminderActions(
            ensureSignedIn = {
                if (sessions.session.value is Session.Loading) sessions.restore()
                sessions.session.value is Session.SignedIn
            },
            done = { repos.reminderRepository.done(it, null) },
            snooze = { repos.reminderRepository.snooze(it, 7) },
            cancel = notifier::cancel,
        )
}
