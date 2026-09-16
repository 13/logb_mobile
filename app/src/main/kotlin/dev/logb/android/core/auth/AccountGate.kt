package dev.logb.android.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps work bound to one account (a sync: its client, its token, its mirror) apart from a change
 * of the signed-in account (a switch, a sign-out).
 *
 * - [runBound] runs account-bound work while holding [lock]. It waits while a change is pending,
 *   and it is cancelled when a change starts while it runs.
 * - [whileChanging] marks a change as pending, cancels every [runBound] block that has already
 *   registered, then takes [lock] itself -- which it only gets once each cancelled block has fully
 *   unwound, so this *is* the join. Only then does the change run, and only once it has finished
 *   (the new session committed) can the next [runBound] block start, now seeing the new account.
 *
 * Lock order: [SessionRepository]'s own mutex is always taken *before* [whileChanging]; a
 * [runBound] block must never call back into [SessionRepository]'s guarded functions (the sync
 * calls [SessionRepository.onUnauthorized] only after its block has returned), so the two locks
 * are never taken in opposite orders.
 */
@Singleton
class AccountGate @Inject constructor() {
    private val lock = Mutex()
    private val guard = Any()
    private var pending = 0
    private val running = mutableSetOf<Job>()
    private val _changing = MutableStateFlow(false)

    /** True while an account change is waiting for, or holding, the gate. */
    val changing: StateFlow<Boolean> = _changing.asStateFlow()

    /**
     * Runs [block] as account-bound work. Returns its result, or null when an account change
     * interrupted it (the caller's own cancellation is rethrown, never turned into null).
     */
    suspend fun <T : Any> runBound(block: suspend () -> T): T? {
        while (true) {
            _changing.first { !it }
            when (val attempt = attempt(block)) {
                is Attempt.Ran -> return attempt.value
                Attempt.ChangeStarted -> continue // wait for that change, then run for the new account
            }
        }
    }

    private sealed interface Attempt<out T> {
        data class Ran<T>(val value: T?) : Attempt<T>
        data object ChangeStarted : Attempt<Nothing>
    }

    private suspend fun <T : Any> attempt(block: suspend () -> T): Attempt<T> = coroutineScope {
        // LAZY: the block must not be able to take the lock before it is registered, or a change
        // starting in between would neither cancel it nor see it.
        val job = async(start = CoroutineStart.LAZY) { lock.withLock { block() } }
        val registered = synchronized(guard) {
            if (pending > 0) false else { running += job; true }
        }
        if (!registered) {
            job.cancel()
            return@coroutineScope Attempt.ChangeStarted
        }
        try {
            Attempt.Ran(job.await())
        } catch (e: CancellationException) {
            ensureActive() // the caller itself was cancelled: propagate
            // Only whileChanging's own cancellation means "interrupted by an account change" --
            // anything else (a future withTimeout in the runner, say) is a real failure and must
            // be seen as one, not silently turned into null.
            if (e is AccountChanging) Attempt.Ran(null) else throw e
        } finally {
            synchronized(guard) { running -= job }
        }
    }

    /** Cancels and joins every [runBound] block, holds new ones off, runs [change], then lets them go. */
    suspend fun <T> whileChanging(change: suspend () -> T): T {
        val toCancel = synchronized(guard) {
            pending++
            _changing.value = true
            running.toList()
        }
        try {
            toCancel.forEach { it.cancel(AccountChanging()) }
            return lock.withLock { change() }
        } finally {
            synchronized(guard) {
                pending--
                if (pending == 0) _changing.value = false
            }
        }
    }

    /** Why a [runBound] block was cancelled. */
    class AccountChanging : CancellationException("the signed-in account is changing")
}
