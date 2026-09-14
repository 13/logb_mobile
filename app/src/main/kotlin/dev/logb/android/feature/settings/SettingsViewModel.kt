package dev.logb.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.BuildConfig
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.blobs.BlobPrefs
import dev.logb.android.core.blobs.BlobSettings
import dev.logb.android.core.blobs.BlobStore
import dev.logb.android.core.db.entity.OpEntity
import dev.logb.android.core.db.entity.SyncStateEntity
import dev.logb.android.core.db.inTransaction
import dev.logb.android.core.notify.DigestWorker
import dev.logb.android.core.notify.NotificationPrefs
import dev.logb.android.core.notify.NotificationSettings
import dev.logb.android.core.prefs.Appearance
import dev.logb.android.core.prefs.AppearancePrefs
import dev.logb.android.core.prefs.ThemeMode
import dev.logb.android.core.sync.SyncManager
import dev.logb.android.core.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val session: Session = Session.Loading,
    val appearance: Appearance = Appearance(),
    val sync: SyncStatus = SyncStatus.None,
    val syncState: SyncStateEntity? = null,
    val deadOps: List<OpEntity> = emptyList(),
    val pending: Int = 0,
    val blobs: BlobSettings = BlobSettings(),
    val usageBytes: Long = 0,
    val version: String = BuildConfig.VERSION_NAME,
    val notifications: NotificationSettings = NotificationSettings(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val prefs: AppearancePrefs,
    private val syncManager: SyncManager,
    private val accounts: ActiveAccount,
    private val blobPrefs: BlobPrefs,
    private val blobStore: BlobStore,
    private val notificationPrefs: NotificationPrefs,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    private val usage = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private fun refreshUsage() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { usage.value = blobStore.usageBytes() }
    init { refreshUsage() }

    val state: StateFlow<SettingsUiState> = combine(
        sessions.session, prefs.appearance, syncManager.status,
        sessions.session.flatMapLatest { s -> if (s is Session.SignedIn) accounts.db.syncStateDao().observe() else flowOf(null) },
        sessions.session.flatMapLatest { s -> if (s is Session.SignedIn) combine(accounts.db.opDao().dead(), accounts.db.opDao().pendingCount()) { d, p -> d to p } else flowOf(emptyList<OpEntity>() to 0) },
        combine(blobPrefs.settings, usage) { b, u -> b to u },
        notificationPrefs.settings,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val ops = values[4] as Pair<List<OpEntity>, Int>
        val blobs = values[5] as Pair<BlobSettings, Long>
        SettingsUiState(values[0] as Session, values[1] as dev.logb.android.core.prefs.Appearance, values[2] as SyncStatus, values[3] as SyncStateEntity?, ops.first, ops.second, blobs.first, blobs.second, notifications = values[6] as NotificationSettings)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { prefs.setTheme(mode) }

    fun setDynamicColor(on: Boolean) = viewModelScope.launch { prefs.setDynamicColor(on) }

    fun syncNow() = viewModelScope.launch { syncManager.syncNow() }

    fun setNotificationsEnabled(on: Boolean) = viewModelScope.launch {
        notificationPrefs.setEnabled(on)
        DigestWorker.schedule(context, notificationPrefs.current())
    }

    fun setNotificationTime(hour: Int, minute: Int) = viewModelScope.launch {
        notificationPrefs.setTime(hour, minute)
        DigestWorker.schedule(context, notificationPrefs.current())
    }

    fun setBudget(bytes: Long) = viewModelScope.launch { blobPrefs.setBudget(bytes); freeUpSpace(bytes) }
    fun setUnmeteredOnly(on: Boolean) = viewModelScope.launch { blobPrefs.setOriginalsUnmeteredOnly(on) }

    /** Evicts originals down to `budget` (default: everything but pending uploads) and refreshes the usage figure. */
    fun freeUpSpace(budget: Long = 0) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val db = accounts.db
        val keep = db.opDao().pending().filter { it.kind == "create" && it.entity == "attachment" }
            .mapNotNull { op -> db.attachmentDao().get(op.entityUuid)?.let { db.fileDao().get(it.fileUuid)?.sha256 } }.toSet()
        blobStore.evictOriginalsOver(budget, keep, db.blobDao().originalsLeastRecentlyUsed()).forEach { db.blobDao().setOriginalPresent(it, false) }
        usage.value = blobStore.usageBytes()
    }

    fun retry(opId: String) = viewModelScope.launch {
        accounts.db.opDao().revive(opId)
        syncManager.syncNow()
    }

    /**
     * Discarding a failed `set` reverts nothing -- the pull already carries the server's truth
     * for that field. Discarding a failed `create` removes the local row it would have made,
     * since the server refused it and nothing else references it.
     */
    fun discard(op: OpEntity) = viewModelScope.launch {
        val db = accounts.db
        db.inTransaction {
            if (op.kind == "create") {
                when (op.entity) {
                    "object" -> db.objectDao().hardDelete(listOf(op.entityUuid) + db.objectDao().descendantUuids(op.entityUuid))
                    "activity" -> db.activityDao().hardDelete(listOf(op.entityUuid))
                    "reminder" -> db.reminderDao().hardDelete(listOf(op.entityUuid))
                    "attachment" -> db.attachmentDao().hardDelete(listOf(op.entityUuid))
                }
            }
            db.opDao().delete(op.id)
        }
    }

    fun signOut(removeLocalData: Boolean) = viewModelScope.launch {
        val s = sessions.session.value as? Session.SignedIn
        sessions.signOut()
        if (removeLocalData && s != null) accounts.deleteLocalData(s.serverUrl, s.user.id)
    }
}
