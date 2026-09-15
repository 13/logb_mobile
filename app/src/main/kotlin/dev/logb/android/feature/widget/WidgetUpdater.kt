package dev.logb.android.feature.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException

/**
 * Tells every placed `DueWidget` to redraw. Called after any write that could change what is due
 * (Task 4, after every save), so it has to be cheap and safe with nothing placed at all -- Glance
 * itself is a no-op in that case, but the read this triggers (a session restore, a lock check)
 * can still fail, and that must never surface to the caller that merely wanted its own write to
 * land.
 */
object WidgetUpdater {
    private const val TAG = "LogB"

    suspend fun refresh(context: Context) {
        try {
            DueWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "widget refresh skipped", e)
        }
    }
}
