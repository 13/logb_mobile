package dev.logb.android.feature.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.logb.android.core.widget.Debouncer
import dev.logb.android.core.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [WidgetRefresher]. Cheap when nothing is placed -- checked fresh on every run, since a
 * widget can be added or removed between requests -- and conflates bursts of ordinary writes and
 * syncs into one redraw on [scope] (the app-wide scope from `SyncModule.appScope`, so a refresh
 * outlives whichever screen asked for it). The privacy-sensitive callers skip that wait through
 * [requestImmediateRefresh].
 */
@Singleton
class RealWidgetRefresher @Inject constructor(@ApplicationContext private val context: Context, scope: CoroutineScope) : WidgetRefresher {
    private val debouncer = Debouncer(scope, DEBOUNCE_MS, ::refreshIfPlaced)

    override fun requestRefresh() = debouncer.request()

    override fun requestImmediateRefresh() = debouncer.requestNow()

    private suspend fun refreshIfPlaced() {
        if (GlanceAppWidgetManager(context).getGlanceIds(DueWidget::class.java).isEmpty()) return
        WidgetUpdater.refresh(context)
    }

    companion object {
        private const val DEBOUNCE_MS = 500L
    }
}
