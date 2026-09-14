package dev.logb.android.navigation

import kotlinx.serialization.Serializable

@Serializable object Objects
@Serializable data class ObjectDetail(val uuid: String, val tab: String = "timeline")
@Serializable object Search
@Serializable object Settings
@Serializable object Appearance
@Serializable object Account
@Serializable object SyncSettings
@Serializable object About

/** The three destinations the bottom bar shows; everything else is a drill-down from one of them. */
enum class Destination { Objects, Search, Settings }

/**
 * Which destination a route belongs to, so a drill-down keeps its parent lit, as the web shell's
 * `nav.ts` does. Matched on the route's serialised name rather than its class, so it can be asked
 * about a `NavDestination.route` string.
 */
fun activeDestination(route: String?): Destination? {
    val name = route?.substringAfterLast('.')?.substringBefore('/')?.substringBefore('?') ?: return null
    return when (name) {
        "Objects", "ObjectDetail" -> Destination.Objects
        "Search" -> Destination.Search
        "Settings", "Appearance", "Account", "SyncSettings", "About" -> Destination.Settings
        else -> null
    }
}
