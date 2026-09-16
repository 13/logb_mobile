package dev.logb.android.feature.widget

import app.cash.turbine.test
import dev.logb.android.core.auth.Session
import dev.logb.android.core.network.dto.User
import dev.logb.android.core.notify.DueFixtures
import dev.logb.android.feature.reminders.DueItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [DueWidgetFlow.states]: the one flow a live Glance session collects. */
class DueWidgetFlowTest {
    private val signedIn = Session.SignedIn("https://logb.example/", User(1, "ben"), "logb_pat_x", "EUR")
    private val golf = DueFixtures.service("r1", "Golf", "Oil change", due = true)
    private val boiler = DueFixtures.service("r2", "Boiler", "Filter", due = true)

    private fun states(session: Flow<Session>, lock: Flow<Boolean>, items: () -> Flow<List<DueItem>>) =
        DueWidgetFlow.states(session, lock, items, dueWord = "due", upcomingWord = { "in $it days" })

    @Test fun `signed in and unlocked shows rows, and a lock turned on afterwards drops them to the count`() = runTest {
        val lock = MutableStateFlow(false)
        states(MutableStateFlow(signedIn), lock) { MutableStateFlow(listOf(golf, boiler)) }.test {
            val open = awaitItem()
            assertEquals(listOf("Golf", "Boiler"), open.rows.map { it.objectName })
            assertFalse(open.locked)

            lock.value = true
            val locked = awaitItem()
            assertEquals(DueWidgetState(2, emptyList(), locked = true, signedIn = true), locked)
        }
    }

    @Test fun `a sign-out after the first emission yields the signed-out state and never reads the database again`() = runTest {
        val session = MutableStateFlow<Session>(signedIn)
        var reads = 0
        states(session, MutableStateFlow(false)) { reads++; MutableStateFlow(listOf(golf)) }.test {
            assertEquals(1, awaitItem().rows.size)
            session.value = Session.SignedOut("https://logb.example/", "ben")
            assertEquals(DueWidgetState(0, emptyList(), locked = false, signedIn = false), awaitItem())
        }
        assertEquals(1, reads)
    }

    @Test fun `signed out from the start never touches the database`() = runTest {
        states(MutableStateFlow(Session.SignedOut("https://logb.example/", "ben")), MutableStateFlow(true)) { error("no DB access while signed out") }.test {
            assertFalse(awaitItem().signedIn)
        }
    }

    @Test fun `loading emits nothing until the session is known`() = runTest {
        val session = MutableStateFlow<Session>(Session.Loading)
        states(session, MutableStateFlow(false)) { MutableStateFlow(listOf(golf)) }.test {
            expectNoEvents()
            session.value = signedIn
            assertEquals(1, awaitItem().rows.size)
        }
    }

    @Test fun `the items flow re-emitting (a Done landing) drops that row`() = runTest {
        val items = MutableStateFlow(listOf(golf, boiler))
        states(MutableStateFlow(signedIn), MutableStateFlow(false)) { items }.test {
            assertEquals(2, awaitItem().count)
            items.value = listOf(boiler)
            assertEquals(listOf("Boiler"), awaitItem().rows.map { it.objectName })
        }
    }

    @Test fun `a database read that throws falls back safely, then a retry recovers`() = runTest {
        var attempt = 0
        val items: () -> Flow<List<DueItem>> = {
            flow {
                attempt++
                if (attempt == 1) throw IllegalStateException("db locked")
                emit(listOf(golf))
            }
        }
        states(MutableStateFlow(signedIn), MutableStateFlow(false), items).test {
            // The flow survives the failure (no crash, nothing skipped) with a safe, name-free state.
            assertEquals(DueWidgetState(0, emptyList(), locked = false, signedIn = true), awaitItem())
            // The retry re-subscribes the query, and its success reaches the same collection.
            assertEquals(listOf("Golf"), awaitItem().rows.map { it.objectName })
        }
        assertEquals(2, attempt)
    }

    @Test fun `a lock setting that cannot be read counts as locked`() = runTest {
        val failing = flow<Boolean> { throw IllegalStateException("datastore read failed") }
        states(MutableStateFlow(signedIn), failing) { MutableStateFlow(listOf(golf)) }.test {
            val s = awaitItem()
            assertTrue(s.locked)
            assertEquals(emptyList(), s.rows)
        }
    }

    @Test fun `the initial value is locked`() {
        assertTrue(DueWidgetFlow.INITIAL.locked)
        assertEquals(emptyList(), DueWidgetFlow.INITIAL.rows)
    }
}
