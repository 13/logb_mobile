package dev.logb.android.feature.widget

import android.util.Log
import dev.logb.android.core.auth.Session
import dev.logb.android.feature.reminders.DueItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/**
 * What a placed widget shows, as a flow rather than a one-off read.
 *
 * Glance runs `provideGlance` once per session (a session lives at least 45 s), and an
 * `update()`/`updateAll()` on a session that is still alive only recomposes what `provideContent`
 * already captured. A value read once before `provideContent` therefore goes stale on the very
 * refresh meant to fix it -- a lock toggle or a sign-out would re-render the old rows. Driving the
 * composable from this flow instead means every change reaches the live session on its own.
 */
object DueWidgetFlow {
    private const val TAG = "LogB"

    /** Backoff before re-subscribing to a query that just threw, so a persistently broken read doesn't spin. */
    private const val RETRY_DELAY_MS = 5_000L

    /**
     * Before anything is known: locked, so the first frame can never carry a name. The count is
     * a placeholder that the first real emission replaces.
     */
    val INITIAL = DueWidgetState(count = 0, rows = emptyList(), locked = true, signedIn = true)

    /**
     * [items] is only called while signed in (and again for every new signed-in session, so an
     * account switch reads the new mirror); signed out, the database is never touched.
     * [Session.Loading] emits nothing, leaving whatever is showing -- [INITIAL] at first -- in place.
     * A lock setting that cannot be read counts as locked ([DueWidgetStates.resolveLocked]).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun states(
        session: Flow<Session>,
        lockEnabled: Flow<Boolean>,
        items: () -> Flow<List<DueItem>>,
        dueWord: String,
        upcomingWord: (Long) -> String,
    ): Flow<DueWidgetState> {
        val locked: Flow<Boolean> = lockEnabled
            .map<Boolean, Boolean?> { it }
            .catch { e -> if (e is CancellationException) throw e; emit(null) }
            .map { DueWidgetStates.resolveLocked(it) }
        val due: Flow<Pair<Boolean, List<DueItem>>> = session
            .filterNot { it is Session.Loading }
            .map { it as? Session.SignedIn }
            .distinctUntilChanged()
            .flatMapLatest { signedIn ->
                if (signedIn == null) {
                    flowOf(false to emptyList())
                } else {
                    items()
                        .map { true to it }
                        // A failed read must not end this flow for the rest of the Glance session -- a plain
                        // catch() that only logs lets the flow complete, and the widget would never hear about
                        // a later, successful read. retryWhen re-subscribes the query instead, so once it
                        // recovers the widget catches up; meanwhile a safe signed-in/nothing-due pair covers
                        // the gap without inventing rows or leaking a stale name.
                        .retryWhen { e, _ ->
                            if (e is CancellationException) throw e
                            Log.w(TAG, "widget read failed, retrying", e)
                            emit(true to emptyList())
                            delay(RETRY_DELAY_MS)
                            true
                        }
                }
            }
        return combine(due, locked) { (signedIn, list), isLocked ->
            DueWidgetStates.from(list, locked = isLocked, signedIn = signedIn, dueWord = dueWord, upcomingWord = upcomingWord)
        }.distinctUntilChanged()
    }
}
