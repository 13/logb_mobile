package dev.logb.android.feature.objects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.db.entity.ReminderEntity
import dev.logb.android.core.domain.ReminderPresenter
import dev.logb.android.core.sync.SyncManager
import dev.logb.android.core.sync.SyncReason
import dev.logb.android.core.sync.SyncStatus
import dev.logb.android.feature.stats.InsightsModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** What an object row shows: the type, the name, and what the app knows and used to hide. */
data class ObjectCard(
    val uuid: String,
    val name: String,
    val type: String,
    val counter: Long?,
    val counterUnit: String?,
    val totalCostCents: Long,
    val lastActivityDate: String?,
    val dueCount: Int,
    val coverSha: String?,
    val parentUuid: String? = null,
    val archived: Boolean = false,
    val description: String = "",
    /** RFC 3339, for the "recently changed" sort. */
    val updatedAt: String = "",
    /** Counter units per day × 1000 over recent readings, for the "≈ 120 km a month" line. */
    val counterPerDayMilli: Long? = null,
)

data class ObjectsUiState(
    val cards: List<ObjectCard> = emptyList(),
    /** The parent's name for rows shown with depth (a query, or the archived tab), by object uuid. */
    val parentNames: Map<String, String> = emptyMap(),
    val query: String = "",
    val sort: SortKey = SortKey.Name,
    val archived: Boolean = false,
    val dueCount: Int = 0,
    val sync: SyncStatus = SyncStatus.None,
    val currency: String = "EUR",
    val loaded: Boolean = false,
    val failed: Int = 0,
)

/** Pure enough to test on an in-memory mirror: everything the screen needs, from Room flows. */
class ObjectsModel(private val db: LogbDatabase, private val today: () -> LocalDate = { LocalDate.now() }) {
    private val insights = InsightsModel(db, today)

    /** Every live object, active and archived, at every depth: the list filters and sorts in memory, as the web does. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun allCards(): Flow<List<ObjectCard>> = db.objectDao().all().flatMapLatest { cardsFor(it) }

    /** Cards for a given set of objects, re-evaluated whenever their reminders or entries change. */
    fun cardsFor(objects: List<ObjectEntity>): Flow<List<ObjectCard>> = combine(db.reminderDao().allOpen(), db.activityDao().version()) { open, _ -> open }.map { open ->
        objects.map { o ->
            val stats = db.objectDao().stats(o.uuid)
            val due = dueCount(open.filter { it.objectUuid == o.uuid }, stats.currentCounter, stats.lastReadingDate)
            val cover = o.coverAttachmentUuid?.let { db.attachmentDao().get(it) }?.takeIf { it.deletedAt == null }?.let { db.fileDao().get(it.fileUuid) }?.sha256?.takeIf { it.isNotBlank() }
            val rate = if (o.counterUnit != null) insights.usage(o.uuid)?.rateMilli else null
            ObjectCard(
                o.uuid, o.name, o.type, stats.currentCounter, o.counterUnit, stats.totalCostCents, stats.lastActivityDate, due, cover,
                parentUuid = o.parentUuid, archived = o.archivedAt != null, description = o.description, updatedAt = o.updatedAt, counterPerDayMilli = rate,
            )
        }
    }

    /** Every due reminder across every object, for the banner. */
    fun totalDue(): Flow<Int> = db.reminderDao().allOpen().map { open ->
        open.groupBy { it.objectUuid }.entries.sumOf { (objectUuid, rs) ->
            val stats = db.objectDao().stats(objectUuid)
            dueCount(rs, stats.currentCounter, stats.lastReadingDate)
        }
    }

    private fun dueCount(open: List<ReminderEntity>, currentCounter: Long?, lastReadingDate: String?): Int {
        val t = today()
        val lastReading = ReminderPresenter.clampLastReading(lastReadingDate, t)
        return open.count { ReminderPresenter.present(it, currentCounter, lastReading, t).due }
    }
}

@HiltViewModel
class ObjectsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    accounts: ActiveAccount,
    private val syncManager: SyncManager,
    private val prefs: ObjectsPrefs,
) : ViewModel() {
    private val model = ObjectsModel(accounts.db)
    private val archived = MutableStateFlow(false)
    private val query = MutableStateFlow("")
    private val currency = accounts.signedIn?.currency ?: "EUR"

    val state: StateFlow<ObjectsUiState> = combine(
        combine(model.allCards(), archived, query, prefs.sort) { cards, arch, q, sort ->
            val locale = context.resources.configuration.locales[0]
            val rows = ObjectListing.visibleRows(cards.filter { !it.archived }, cards.filter { it.archived }, arch, q, sort, { context.getString(typeLabelRes(it)) }, locale)
            ObjectsUiState(rows.map { it.card }, rows.mapNotNull { r -> r.parentName?.let { r.card.uuid to it } }.toMap(), q, sort, arch)
        },
        model.totalDue(), syncManager.status, accounts.db.opDao().dead(),
    ) { list, due, sync, dead ->
        list.copy(dueCount = due, sync = sync, currency = currency, loaded = true, failed = dead.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObjectsUiState(currency = currency))

    fun toggleArchived() { archived.value = !archived.value }
    fun setQuery(q: String) { query.value = q }
    fun setSort(key: SortKey) = viewModelScope.launch { prefs.setSort(key) }

    fun refresh() = viewModelScope.launch { syncManager.syncNow() }

    init { syncManager.requestSync(SyncReason.Foreground) }
}
