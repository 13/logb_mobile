package dev.logb.android.core.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Drives [Debouncer] with virtual time. `backgroundScope`-launched work is deliberately excluded
 * from `advanceUntilIdle()` (coroutines-test's guard against hanging on an endless background
 * task), so these use `advanceTimeBy` and `runCurrent` -- both bounded, and both do run it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebouncerTest {
    @Test
    fun `five requests within 500ms give one refresh`() = runTest {
        var runs = 0
        val d = Debouncer(backgroundScope, 500) { runs++ }
        repeat(5) { d.request() }
        advanceTimeBy(501)
        assertEquals(1, runs)
    }

    @Test
    fun `each request restarts the wait, so nothing has run before the delay elapses`() = runTest {
        var runs = 0
        val d = Debouncer(backgroundScope, 500) { runs++ }
        repeat(4) { d.request(); advanceTimeBy(100) } // 400ms elapsed, always < 500ms since the last request
        assertEquals(0, runs)
        advanceTimeBy(500)
        assertEquals(1, runs)
    }

    @Test
    fun `two bursts more than the delay apart each get their own refresh`() = runTest {
        var runs = 0
        val d = Debouncer(backgroundScope, 500) { runs++ }
        d.request()
        advanceTimeBy(600)
        d.request()
        advanceTimeBy(600)
        assertEquals(2, runs)
    }

    @Test
    fun `requestNow runs at once and cancels a pending debounced run`() = runTest {
        var runs = 0
        val d = Debouncer(backgroundScope, 500) { runs++ }
        d.request()
        d.requestNow()
        runCurrent()
        assertEquals(1, runs, "the pending debounced request must not also fire")
        advanceTimeBy(600)
        assertEquals(1, runs, "the cancelled debounced request must never fire, even once its delay elapses")
    }
}
