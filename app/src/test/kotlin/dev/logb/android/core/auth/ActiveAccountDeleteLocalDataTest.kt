package dev.logb.android.core.auth

import androidx.test.core.app.ApplicationProvider
import dev.logb.android.core.db.DatabaseProvider
import dev.logb.android.core.network.ApiClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import kotlin.test.assertEquals

/**
 * [ActiveAccount.deleteLocalData] runs from [SessionRepository]'s `afterSignOut`, called from a
 * ViewModel's `viewModelScope` -- Main by default -- but it does disk I/O (closing and deleting
 * the mirror file). It must hop off Main for that, not do it inline while holding Main's one
 * thread.
 */
@RunWith(RobolectricTestRunner::class)
class ActiveAccountDeleteLocalDataTest {
    private val sessions = SessionRepository(FakeServerStore(), FakeTokenStore(), ApiFactory { b, t, j -> ApiClient.create(b, t, j) })
    private val accounts = ActiveAccount(sessions, DatabaseProvider(ApplicationProvider.getApplicationContext()), ApiFactory { b, t, j -> ApiClient.create(b, t, j) })

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun `deleteLocalData does not run its disk I-O on the calling Main thread`() {
        // A single real thread standing in for Main: whatever runs "on Main" must share this one
        // thread with everything else queued on it, so if deleteLocalData did its work inline
        // here, nothing else queued on Main could run until it returned.
        val mainThread = newSingleThreadContext("test-main")
        Dispatchers.setMain(mainThread)
        try {
            val order = Collections.synchronizedList(mutableListOf<String>())
            runBlocking {
                val deleteJob = launch(Dispatchers.Main) {
                    accounts.deleteLocalData("https://logb.example/", 1)
                    order += "deleteLocalData returned"
                }
                // Queued on the very same Main thread right after: if deleteLocalData ran its I/O
                // inline on Main, this could only run once that returns. If it hops to
                // Dispatchers.IO first (the fix), Main is free and this runs first.
                val otherJob = launch(Dispatchers.Main) { order += "other Main work" }
                deleteJob.join()
                otherJob.join()
            }
            assertEquals(listOf("other Main work", "deleteLocalData returned"), order.toList())
        } finally {
            Dispatchers.resetMain()
            mainThread.close()
        }
    }
}
