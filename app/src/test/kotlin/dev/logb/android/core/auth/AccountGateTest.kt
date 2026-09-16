package dev.logb.android.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AccountGate.runBound] must tell apart its own cancellation -- [AccountGate.AccountChanging],
 * raised by [AccountGate.whileChanging] -- from any other cancellation the block might see (a
 * future `withTimeout` in the runner, or the caller's own cancellation): only the former is
 * "interrupted by an account change" and returns null; everything else must be rethrown.
 */
class AccountGateTest {
    @Test
    fun `a block cancelled by whileChanging returns null`() = runTest {
        val gate = AccountGate()
        val started = CompletableDeferred<Unit>()
        val run = async { gate.runBound { started.complete(Unit); CompletableDeferred<Unit>().await() } }
        started.await()
        gate.whileChanging { }
        assertNull(run.await())
    }

    @Test
    fun `a cancellation that is not the gate's own is rethrown, not swallowed as an interruption`() = runTest {
        val gate = AccountGate()
        val failure = assertFailsWith<TimeoutCancellationException> {
            gate.runBound { withTimeout(1) { CompletableDeferred<Unit>().await() } }
        }
        assertTrue(failure.message!!.isNotBlank())
    }

    @Test
    fun `the caller's own cancellation is rethrown, not swallowed as an interruption`() = runTest {
        val gate = AccountGate()
        val started = CompletableDeferred<Unit>()
        val run = async { gate.runBound { started.complete(Unit); CompletableDeferred<Unit>().await() } }
        started.await()
        run.cancelAndJoin()
        assertTrue(run.isCancelled)
    }

    @Test
    fun `a normal pass runs and returns its own value`() = runTest {
        val gate = AccountGate()
        assertEquals("done", gate.runBound { "done" })
    }
}
