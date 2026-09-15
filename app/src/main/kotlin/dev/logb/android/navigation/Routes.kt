package dev.logb.android.navigation

import kotlinx.serialization.Serializable

@Serializable object Objects
@Serializable data class ObjectDetail(val uuid: String, val tab: String = "timeline")
@Serializable data class ObjectForm(val uuid: String? = null, val parentUuid: String? = null)
@Serializable data class ActivityForm(val objectUuid: String, val uuid: String? = null, val category: String? = null, val doneReminderUuid: String? = null, val title: String? = null, val fromShare: Boolean = false)
@Serializable data class ReminderForm(val objectUuid: String, val uuid: String? = null, val kind: String? = null)
@Serializable object DueList
@Serializable object Stats
@Serializable data class Viewer(val attachmentUuid: String)
@Serializable object ShareTarget
@Serializable data class ReadingForm(val objectUuid: String)
@Serializable object Search
@Serializable object Settings
@Serializable object Appearance
@Serializable object Account
@Serializable object SyncSettings
@Serializable object About
@Serializable object Notifications
@Serializable object TypesSettings
@Serializable object DataSettings
@Serializable object ApiAccessSettings

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
        "Objects", "ObjectDetail", "ObjectForm", "ActivityForm", "ReadingForm", "ReminderForm", "DueList", "Viewer", "ShareTarget", "Stats" -> Destination.Objects
        "Search" -> Destination.Search
        "Settings", "Appearance", "Account", "SyncSettings", "About", "Notifications", "TypesSettings", "DataSettings", "ApiAccessSettings" -> Destination.Settings
        else -> null
    }
}
