package dev.logb.android.core.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Conflates a burst of [request] calls into one [action] call roughly [delayMs] after the last
 * one -- each new request cancels the wait and restarts it, so the action only ever runs once the
 * calls stop coming. Pure and scope-injected, with no wall-clock sleep of its own, so a test can
 * drive it with [kotlinx.coroutines.test] virtual time instead of real delays.
 *
 * Callers arrive on whatever thread wrote the data (a sync worker, a broadcast receiver, the UI),
 * so the pending job is guarded by a lock. Only the *waiting* job is ever cancelled: an immediate
 * run from [requestNow] -- the privacy-sensitive path, a lock toggle or a sign-out -- is never
 * tracked and so can never be cancelled by an ordinary [request] arriving right behind it.
 */
class Debouncer(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val action: suspend () -> Unit,
) {
    private val lock = Any()
    private var pending: Job? = null

    /** Cancels anything waiting and schedules a fresh run [delayMs] out. */
    fun request() {
        synchronized(lock) {
            pending?.cancel()
            pending = scope.launch {
                delay(delayMs)
                action()
            }
        }
    }

    /** Skips the wait: cancels anything waiting and runs now, whatever arrives next. */
    fun requestNow() {
        synchronized(lock) {
            pending?.cancel()
            pending = null
        }
        scope.launch { action() }
    }
}
