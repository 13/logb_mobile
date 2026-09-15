package dev.logb.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.logb.android.core.alerts.ReminderNotificationsClearer
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.notify.ReminderActions
import dev.logb.android.core.notify.ReminderNotifier
import dev.logb.android.core.sync.Repositories
import dev.logb.android.feature.widget.WidgetUpdater
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NotifyModule {
    @Provides
    fun reminderNotificationsClearer(notifier: ReminderNotifier): ReminderNotificationsClearer = notifier

    @Provides
    @Singleton
    fun reminderActions(@ApplicationContext context: android.content.Context, repos: Repositories, notifier: ReminderNotifier, sessions: SessionRepository): ReminderActions =
        ReminderActions(
            ensureSignedIn = {
                if (sessions.session.value is Session.Loading) sessions.restore()
                sessions.session.value is Session.SignedIn
            },
            done = { repos.reminderRepository.done(it, null) },
            snooze = { repos.reminderRepository.snooze(it, 7) },
            cancel = notifier::cancel,
            // In the action itself, not on the debounce: this process may not live long enough for that.
            refreshWidget = { WidgetUpdater.refresh(context) },
        )
}
