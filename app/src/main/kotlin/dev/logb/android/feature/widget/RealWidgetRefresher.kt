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
 *
 * [anyPlaced] and [update] are seams: the real ones read Glance's own manager and tell every
 * placed widget to redraw, and a test can substitute them to see whether the update happened.
 */
@Singleton
class RealWidgetRefresher(
    scope: CoroutineScope,
    private val anyPlaced: suspend () -> Boolean,
    private val update: suspend () -> Unit,
) : WidgetRefresher {
    @Inject
    constructor(@ApplicationContext context: Context, scope: CoroutineScope) : this(
        scope,
        anyPlaced = { GlanceAppWidgetManager(context).getGlanceIds(DueWidget::class.java).isNotEmpty() },
        update = { WidgetUpdater.refresh(context) },
    )

    private val debouncer = Debouncer(scope, DEBOUNCE_MS, ::refreshIfPlaced)

    override fun requestRefresh() = debouncer.request()

    override fun requestImmediateRefresh() = debouncer.requestNow()

    private suspend fun refreshIfPlaced() {
        if (!anyPlaced()) return
        update()
    }

    companion object {
        private const val DEBOUNCE_MS = 500L
    }
}
