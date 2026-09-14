package dev.logb.android.feature.stats

import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.domain.SpendStats
import kotlinx.coroutines.flow.first

/** Port of `api::stats::read`: the database groups, [SpendStats] does the rest. Reads only the mirror. */
class StatsModel(private val db: LogbDatabase) {
    suspend fun stats(year: Int?, purchases: Boolean): SpendStats.Stats {
        val spend = db.activityDao().spendByObjectMonthCategory().map { SpendStats.Spend(it.objectUuid, it.month, it.category, it.costCents) }.toMutableList()
        val objects = db.objectDao().all().first().map { it.toRow() }
        if (purchases) {
            val purchased = db.activityDao().purchasedObjectUuids().toSet()
            spend += SpendStats.purchaseSpend(objects, purchased)
        }
        return SpendStats.summarize(objects, spend, year)
    }
}

fun ObjectEntity.toRow() = SpendStats.ObjectRow(
    id = uuid, parentId = parentUuid, name = name, kind = type, archived = archivedAt != null,
    purchaseDate = purchaseDate, purchasePriceCents = purchasePriceCents, createdAt = createdAt,
)
