package dev.logb.android.feature.objects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.ActivityEntity
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.db.model.Ancestor
import dev.logb.android.core.db.model.AttachmentWithFile
import dev.logb.android.core.db.model.ObjectStats
import dev.logb.android.core.domain.ObjectTypes
import dev.logb.android.core.domain.ReminderPresenter
import dev.logb.android.core.domain.ReminderView
import dev.logb.android.core.domain.TimelineFold
import dev.logb.android.core.domain.TimelineRow
import dev.logb.android.navigation.ObjectDetail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** The timeline grouped by year, newest first, each year's rows folded. */
data class YearGroup(val year: String, val rows: List<TimelineRow>)

data class ObjectDetailUiState(
    val obj: ObjectEntity? = null,
    val stats: ObjectStats? = null,
    val ancestors: List<Ancestor> = emptyList(),
    val children: List<ObjectCard> = emptyList(),
    val years: List<YearGroup> = emptyList(),
    val categoryFilter: String? = null,
    val categories: List<String> = emptyList(),
    /** Attachments of every entry on the timeline, by entry uuid. */
    val attachmentsByActivity: Map<String, List<AttachmentWithFile>> = emptyMap(),
    val documents: List<AttachmentWithFile> = emptyList(),
    val openReminders: List<ReminderView> = emptyList(),
    val doneReminders: List<ReminderView> = emptyList(),
    val currency: String = "EUR",
    val loaded: Boolean = false,
)

/** Everything one object's screen needs, from Room flows; testable on an in-memory mirror. */
class ObjectDetailModel(private val db: LogbDatabase, private val uuid: String, private val today: () -> LocalDate = { LocalDate.now() }) {
    private val objects = ObjectsModel(db, today)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun state(categoryFilter: Flow<String?>, currency: String): Flow<ObjectDetailUiState> {
        val timeline = db.activityDao().timeline(uuid)
        val attachments = timeline.flatMapLatest { acts -> db.attachmentDao().forActivities(acts.map { it.uuid }) }
        val reminders = combine(db.reminderDao().forObject(uuid), timeline) { rs, _ -> rs }
        val children = db.objectDao().children(uuid).flatMapLatest { kids -> objects.cardsFor(kids) }
        return combine(db.objectDao().observe(uuid), timeline, attachments, reminders, categoryFilter, db.attachmentDao().forObject(uuid), children) { values ->
            @Suppress("UNCHECKED_CAST")
            val obj = values[0] as ObjectEntity?
            val acts = values[1] as List<ActivityEntity>
            val atts = values[2] as List<AttachmentWithFile>
            val rems = values[3] as List<dev.logb.android.core.db.entity.ReminderEntity>
            val filter = values[4] as String?
            val docs = values[5] as List<AttachmentWithFile>
            val kids = values[6] as List<ObjectCard>
            if (obj == null) return@combine ObjectDetailUiState(loaded = true, currency = currency)
            val stats = db.objectDao().stats(uuid)
            val t = today()
            val lastReading = ReminderPresenter.clampLastReading(stats.lastReadingDate, t)
            val views = rems.map { ReminderPresenter.present(it, stats.currentCounter, lastReading, t) }
            val filtered = if (filter == null) acts else acts.filter { it.category == filter }
            ObjectDetailUiState(
                obj = obj, stats = stats, ancestors = db.objectDao().ancestors(uuid), children = kids,
                years = filtered.groupBy { it.date.take(4) }.entries.sortedByDescending { it.key }.map { (y, list) -> YearGroup(y, TimelineFold.fold(list)) },
                categoryFilter = filter,
                categories = ObjectTypes.categoriesFor(obj.type).filter { c -> acts.any { it.category == c } },
                attachmentsByActivity = atts.groupBy { it.attachment.activityUuid ?: "" },
                documents = docs,
                openReminders = views.filter { it.reminder.doneAt == null }.sortedWith(compareByDescending<ReminderView> { it.due }.thenBy { it.daysUntil ?: Long.MAX_VALUE }),
                doneReminders = views.filter { it.reminder.doneAt != null }.sortedByDescending { it.reminder.doneAt },
                currency = currency, loaded = true,
            )
        }
    }
}

@HiltViewModel
class ObjectDetailViewModel @Inject constructor(accounts: ActiveAccount, savedState: SavedStateHandle) : ViewModel() {
    val route: ObjectDetail = savedState.toRoute()
    private val filter = MutableStateFlow<String?>(null)
    private val model = ObjectDetailModel(accounts.db, route.uuid)
    val state: StateFlow<ObjectDetailUiState> = model.state(filter, accounts.signedIn?.currency ?: "EUR")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObjectDetailUiState())

    fun setFilter(category: String?) { filter.value = if (filter.value == category) null else category }

    fun fileUrl(serverId: Long?, thumb: Boolean): String? = serverId?.let { accountsRef?.fileUrl(it, thumb) }

    private val accountsRef = accounts
}
