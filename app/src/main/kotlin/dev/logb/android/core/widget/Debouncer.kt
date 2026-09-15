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
 */
class Debouncer(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val action: suspend () -> Unit,
) {
    private var pending: Job? = null

    /** Cancels anything pending and schedules a fresh run [delayMs] out. */
    fun request() {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            action()
        }
    }

    /** Skips the wait: cancels anything pending and runs now. */
    fun requestNow() {
        pending?.cancel()
        pending = scope.launch { action() }
    }
}
