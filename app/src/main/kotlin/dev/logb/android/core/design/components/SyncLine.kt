package dev.logb.android.core.design.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.logb.android.R
import dev.logb.android.core.format.relativeTime
import dev.logb.android.core.sync.SyncStatus

/** The one place the network is mentioned: a quiet line under the Objects title. */
@Composable
fun SyncLine(status: SyncStatus, onOpenSync: () -> Unit, modifier: Modifier = Modifier, failed: Int = 0) {
    val (text, warn) = if (failed > 0) pluralStringResource(R.plurals.sync_could_not_save, failed, failed) to true else when (status) {
        SyncStatus.None -> null to false
        is SyncStatus.Idle -> (if (status.pending > 0) pluralStringResource(R.plurals.sync_waiting, status.pending, status.pending) else stringResource(R.string.sync_synced, relativeTime(status.lastSyncedAt))) to false
        SyncStatus.Syncing -> stringResource(R.string.sync_syncing) to false
        is SyncStatus.Offline -> (if (status.pending > 0) stringResource(R.string.sync_offline_pending, pluralStringResource(R.plurals.sync_waiting, status.pending, status.pending)) else stringResource(R.string.sync_offline)) to false
        is SyncStatus.Failed -> stringResource(R.string.sync_failed) to true
        SyncStatus.SignedOut -> null to false
    }
    if (text == null) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.clickable(onClick = onOpenSync).padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
