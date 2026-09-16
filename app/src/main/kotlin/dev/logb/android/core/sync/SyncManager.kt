package dev.logb.android.core.sync

import dev.logb.android.core.auth.AccountGate
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.network.ApiException
import dev.logb.android.core.network.UnauthorizedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** One sync pass: (phase 2: push, then) pull. Built per run by [SyncManager.runnerFactory], null when signed out. */
fun interface SyncRunner {
    suspend fun run()
}

/** [SyncManager.syncNow]: the pass was stopped because the signed-in account is changing. An [IOException], so a worker retries later. */
class SyncInterrupted : IOException("sync stopped: the signed-in account is changing")

/**
 * The only thing that talks to the server on a schedule. Runs are serialised: a foreground
 * trigger landing during a periodic run waits rather than racing it. Status is one flow the
 * Objects screen renders as a single line.
 *
 * Every pass runs inside [gate] ([AccountGate.runBound]), shared with [SessionRepository]: a
 * switch or sign-out cancels a running pass and waits for it to unwind before touching the
 * session, and a pass requested meanwhile waits for the change to finish -- its runner is only
 * built afterwards, for whoever is signed in then. [SyncWorker] goes through [syncNow] too.
 */
class SyncManager(
    private val sessions: SessionRepository,
    private val connectivity: ConnectivityMonitor,
    private val runnerFactory: () -> SyncRunner?,
    private val scope: CoroutineScope,
    private val pendingCount: suspend () -> Int = { 0 },
    private val debounceMs: Long = 2_000,
    private val widgetRefresh: suspend () -> Unit = {},
    private val gate: AccountGate = AccountGate(),
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.None)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private var debounced: Job? = null

    init {
        // A connection coming back is the moment queued writes have been waiting for. The count
        // reads the signed-in mirror, so it is account-bound work too.
        scope.launch {
            connectivity.isOnline.collect { online -> if (online && (gate.runBound { pendingCount() } ?: 0) > 0) requestSync(SyncReason.Connectivity) }
        }
    }

    /** Fire and forget, debounced; the caller does not wait. */
    fun requestSync(reason: SyncReason) {
        val delayMs = if (reason == SyncReason.AfterWrite) debounceMs else 0
        debounced?.cancel()
        debounced = scope.launch {
            if (delayMs > 0) delay(delayMs)
            syncNow()
        }
    }

    /** One full pass now, awaited. The result says why it stopped; the status flow says the same. */
    suspend fun syncNow(): Result<Unit> = mutex.withLock {
        val outcome = gate.runBound { runOnce() } ?: run {
            // Stopped for a switch or sign-out; the next pass belongs to whoever is signed in next.
            _status.value = SyncStatus.None
            return@withLock Result.failure(SyncInterrupted())
        }
        val unauthorized = outcome.exceptionOrNull() as? UnauthorizedException
        if (unauthorized != null) {
            // Outside the gate: onUnauthorized takes SessionRepository's mutex, which a switch
            // holds while it waits on the gate. It only signs out if the refused token is still
            // the stored one -- a 401 for a token that has since been replaced or cleared says
            // nothing about the account that is signed in now, and must not be shown as such.
            val signedOut = sessions.onUnauthorized(unauthorized.token)
            _status.value = if (signedOut) SyncStatus.SignedOut else SyncStatus.Failed(unauthorized.message)
        }
        outcome
    }

    private suspend fun runOnce(): Result<Unit> {
        val runner = runnerFactory() ?: run {
            _status.value = SyncStatus.SignedOut
            return Result.failure(IllegalStateException("signed out"))
        }
        if (!connectivity.isOnline.value) {
            _status.value = SyncStatus.Offline(pending = pendingCount())
            return Result.failure(IOException("offline"))
        }
        _status.value = SyncStatus.Syncing
        return try {
            runner.run()
            _status.value = SyncStatus.Idle(Clock.nowIso(), pending = pendingCount())
            // Only on success: a widget's staleness past a failed or offline attempt is handled by
            // the next write, the next successful sync, or the midnight worker, not by this run.
            scope.launch { widgetRefresh() }
            Result.success(Unit)
        } catch (e: UnauthorizedException) {
            Result.failure(e)
        } catch (e: ApiException) {
            _status.value = SyncStatus.Failed(e.message)
            Result.failure(e)
        } catch (e: IOException) {
            _status.value = SyncStatus.Offline(pending = pendingCount())
            Result.failure(e)
        }
    }
}
