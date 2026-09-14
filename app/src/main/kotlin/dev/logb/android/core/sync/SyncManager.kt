package dev.logb.android.core.sync

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

/**
 * The only thing that talks to the server on a schedule. Runs are serialised: a foreground
 * trigger landing during a periodic run waits rather than racing it. Status is one flow the
 * Objects screen renders as a single line.
 */
class SyncManager(
    private val sessions: SessionRepository,
    private val connectivity: ConnectivityMonitor,
    private val runnerFactory: () -> SyncRunner?,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 2_000,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.None)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private var debounced: Job? = null

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
        val runner = runnerFactory() ?: run {
            _status.value = SyncStatus.SignedOut
            return Result.failure(IllegalStateException("signed out"))
        }
        if (!connectivity.isOnline.value) {
            _status.value = SyncStatus.Offline(pending = 0)
            return Result.failure(IOException("offline"))
        }
        _status.value = SyncStatus.Syncing
        try {
            runner.run()
            _status.value = SyncStatus.Idle(Clock.nowIso())
            Result.success(Unit)
        } catch (e: UnauthorizedException) {
            sessions.onUnauthorized()
            _status.value = SyncStatus.SignedOut
            Result.failure(e)
        } catch (e: ApiException) {
            _status.value = SyncStatus.Failed(e.message)
            Result.failure(e)
        } catch (e: IOException) {
            _status.value = SyncStatus.Offline(pending = 0)
            Result.failure(e)
        }
    }
}
