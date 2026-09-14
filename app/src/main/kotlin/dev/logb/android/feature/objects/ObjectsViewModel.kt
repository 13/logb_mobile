package dev.logb.android.feature.objects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.ReminderEntity
import dev.logb.android.core.domain.ReminderPresenter
import dev.logb.android.core.sync.SyncManager
import dev.logb.android.core.sync.SyncReason
import dev.logb.android.core.sync.SyncStatus
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
    val coverFileServerId: Long?,
)

data class ObjectsUiState(
    val cards: List<ObjectCard> = emptyList(),
    val archived: Boolean = false,
    val dueCount: Int = 0,
    val sync: SyncStatus = SyncStatus.None,
    val currency: String = "EUR",
    val loaded: Boolean = false,
)

/** Pure enough to test on an in-memory mirror: everything the screen needs, from Room flows. */
class ObjectsModel(private val db: LogbDatabase, private val today: () -> LocalDate = { LocalDate.now() }) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cards(archived: Flow<Boolean>): Flow<List<ObjectCard>> =
        combine(archived.flatMapLatest { db.objectDao().roots(it) }, db.reminderDao().allOpen()) { roots, open -> roots to open }
            .map { (roots, open) ->
                roots.map { o ->
                    val stats = db.objectDao().stats(o.uuid)
                    val due = dueCount(open.filter { it.objectUuid == o.uuid }, stats.currentCounter, stats.lastReadingDate)
                    val cover = o.coverAttachmentUuid?.let { db.attachmentDao().get(it) }?.let { db.fileDao().get(it.fileUuid) }?.serverId
                    ObjectCard(o.uuid, o.name, o.type, stats.currentCounter, o.counterUnit, stats.totalCostCents, stats.lastActivityDate, due, cover)
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
class ObjectsViewModel @Inject constructor(accounts: ActiveAccount, private val syncManager: SyncManager) : ViewModel() {
    private val model = ObjectsModel(accounts.db)
    private val archived = MutableStateFlow(false)
    private val currency = accounts.signedIn?.currency ?: "EUR"

    val state: StateFlow<ObjectsUiState> = combine(model.cards(archived), archived, model.totalDue(), syncManager.status) { cards, arch, due, sync ->
        ObjectsUiState(cards, arch, due, sync, currency, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObjectsUiState(currency = currency))

    fun toggleArchived() { archived.value = !archived.value }

    fun refresh() = viewModelScope.launch { syncManager.syncNow() }

    init { syncManager.requestSync(SyncReason.Foreground) }
}
