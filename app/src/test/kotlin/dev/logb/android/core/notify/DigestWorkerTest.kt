package dev.logb.android.core.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.FakeTokenStore
import dev.logb.android.core.auth.LockPrefs
import dev.logb.android.core.auth.ServerRecord
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.db.DatabaseProvider
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.widget.WidgetRefresher
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The worker's own signed-out path, with real prefs and notifier and fake stores. */
@RunWith(RobolectricTestRunner::class)
class DigestWorkerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val notifier = ReminderNotifier(context)
    private val serverStore = FakeServerStore()
    private val sessions = SessionRepository(serverStore, FakeTokenStore(), ApiFactory { b, t, j -> ApiClient.create(b, t, j) })
    private val prefs = NotificationPrefs(context)
    private val refreshes = mutableListOf<String>()
    private val refresher = object : WidgetRefresher {
        override fun requestRefresh() { refreshes += "debounced" }
        override fun requestImmediateRefresh() { refreshes += "immediate" }
    }

    @Before fun grantPermission() = shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun worker(): DigestWorker {
        val accounts = ActiveAccount(sessions, DatabaseProvider(context), ApiFactory { b, t, j -> ApiClient.create(b, t, j) })
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                DigestWorker(appContext, workerParameters, sessions, accounts, notifier, prefs, refresher, LockPrefs(appContext))
        }
        return TestListenableWorkerBuilder<DigestWorker>(context).setWorkerFactory(factory).build()
    }

    @Test fun `a signed-out account cancels everything still posted`() = runBlocking {
        prefs.setEnabled(true)
        serverStore.write(ServerRecord("https://logb.example/", null, "ben"))
        sessions.restore()
        assertIs<Session.SignedOut>(sessions.session.value)
        notifier.post(DigestNotifications(DigestText("2 reminders", null), listOf(golf(), house())))
        assertEquals(3, manager.activeNotifications.size)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(0, manager.activeNotifications.size, "a signed-out phone must not keep the account's reminders posted")
    }

    @Test fun `notifications turned off leaves what is posted alone`() = runBlocking {
        prefs.setEnabled(false) // explicit: the DataStore file outlives a single test in this class
        notifier.post(DigestNotifications(null, listOf(golf())))
        assertEquals(1, manager.activeNotifications.size)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(1, manager.activeNotifications.size)
    }

    private fun golf() = ChildNotification(DigestPlan.idFor("golf"), "golf", "obj-golf", "Golf: Oil change", "due", listOf(NotificationAction.Done, NotificationAction.Snooze))
    private fun house() = ChildNotification(DigestPlan.idFor("house"), "house", "obj-house", "House: Filter", "due", listOf(NotificationAction.Done, NotificationAction.Snooze))
}
