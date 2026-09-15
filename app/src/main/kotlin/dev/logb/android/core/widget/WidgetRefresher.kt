package dev.logb.android.core.widget

/**
 * Tells whatever keeps the home-screen widget current that something worth showing changed.
 * Ordinary writes and syncs go through [requestRefresh], which may coalesce a burst into one
 * redraw a little later; a change to what a placed widget is *allowed* to show -- the app lock
 * toggling, a sign-out -- goes through [requestImmediateRefresh] instead, since even the short
 * wait a debounce buys is a privacy leak there.
 *
 * Lives in `core` (not `feature.widget`) so `core.auth` and `core.sync` can depend on it without
 * depending on the widget feature itself.
 */
interface WidgetRefresher {
    fun requestRefresh()
    fun requestImmediateRefresh()
}

/** Does nothing: the default for constructors, and for tests that don't care about the widget. */
object NoopWidgetRefresher : WidgetRefresher {
    override fun requestRefresh() {}
    override fun requestImmediateRefresh() {}
}
