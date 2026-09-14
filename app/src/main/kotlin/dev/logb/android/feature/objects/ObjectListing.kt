package dev.logb.android.feature.objects

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** The web's `SORT_KEYS`, in its order; `key` is the value the web persists, kept for parity. */
enum class SortKey(val key: String) {
    Name("name"), LastActivity("last-activity"), Changed("changed"), Cost("cost"), Counter("counter");

    companion object {
        fun parse(value: String?): SortKey? = entries.firstOrNull { it.key == value }
    }
}

/** One row of the list: the card and, when the list shows depth, its parent's name. */
data class ListRow(val card: ObjectCard, val parentName: String?)

/** Port of `frontend/src/lib/object-list.ts`: what the Objects list shows for a tab, a query and a sort. */
object ObjectListing {
    /** Lower case with accents removed, so "fahrrader" finds "Fahrräder". */
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    fun matchesQuery(card: ObjectCard, query: String, typeLabel: (String) -> String): Boolean {
        val q = fold(query.trim())
        if (q.isEmpty()) return true
        return listOf(card.name, typeLabel(card.type), card.description).any { fold(it).contains(q) }
    }

    /**
     * The value each non-name sort orders by, highest or newest first. Null means "not known" and
     * goes last: an object never used is not more recent than one used last year; a cost of 0 is
     * unknown for the same reason. ISO dates and RFC 3339 timestamps compare as text.
     */
    private fun value(card: ObjectCard, key: SortKey): Comparable<*>? = when (key) {
        SortKey.Name -> null
        SortKey.LastActivity -> card.lastActivityDate
        SortKey.Changed -> card.updatedAt
        SortKey.Cost -> card.totalCostCents.takeIf { it > 0 }
        SortKey.Counter -> card.counter
    }

    fun sortObjects(list: List<ObjectCard>, key: SortKey, locale: Locale): List<ObjectCard> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        val byName = Comparator<ObjectCard> { a, b -> collator.compare(a.name, b.name) }
        if (key == SortKey.Name) return list.sortedWith(byName)
        return list.sortedWith { a, b ->
            val va = value(a, key)
            val vb = value(b, key)
            when {
                va == null && vb == null -> byName.compare(a, b)
                va == null -> 1
                vb == null -> -1
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val c = (vb as Comparable<Any>).compareTo(va)
                    if (c != 0) c else byName.compare(a, b)
                }
            }
        }
    }

    /**
     * The active tab without a query shows top-level objects, as the dashboard always has: a
     * child is reached through its parent. An object whose parent is not active counts as
     * top-level there, or a live child of an archived parent would appear nowhere. A query
     * searches every depth, and the archived tab is flat; both name each object's parent so two
     * "Filter"s in different rooms can be told apart.
     */
    fun visibleRows(
        active: List<ObjectCard>, archived: List<ObjectCard>, showArchived: Boolean, query: String, sort: SortKey,
        typeLabel: (String) -> String, locale: Locale,
    ): List<ListRow> {
        val names = (active + archived).associate { it.uuid to it.name }
        val activeIds = active.map { it.uuid }.toSet()
        val searching = query.isNotBlank()
        val pool = if (showArchived) archived else active
        val shown = pool.filter { o ->
            when {
                searching -> matchesQuery(o, query, typeLabel)
                showArchived -> true
                else -> o.parentUuid == null || o.parentUuid !in activeIds
            }
        }
        val withParent = searching || showArchived
        return sortObjects(shown, sort, locale).map { card ->
            ListRow(card, if (withParent && card.parentUuid != null) names[card.parentUuid] else null)
        }
    }
}
