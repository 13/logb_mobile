package dev.logb.android.feature.settings

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.logb.android.R
import dev.logb.android.core.design.components.LogbTopBar

/** Settings › Notifications: the daily digest on or off, and when. */
@Composable
fun NotificationsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val n = state.notifications
    // Android 13+ asks before an app may post; refused means the switch stays off rather than lying.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> viewModel.setNotificationsEnabled(granted) }
    val blocked = !NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun toggle(on: Boolean) {
        if (on && Build.VERSION.SDK_INT >= 33 && !blocked) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.setNotificationsEnabled(on && !blocked)
    }
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(title = stringResource(R.string.settings_notifications), onBack = onBack)
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().toggleable(value = n.enabled, role = Role.Switch, onValueChange = ::toggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.notify_enable), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.notify_enable_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = n.enabled, onCheckedChange = null)
            }
            if (blocked && n.enabled) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.notify_denied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.fillMaxWidth().clickable(enabled = n.enabled) {
                    TimePickerDialog(context, { _, h, m -> viewModel.setNotificationTime(h, m) }, n.hour, n.minute, true).show()
                },
            ) {
                Text(stringResource(R.string.notify_time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(n.time, style = MaterialTheme.typography.bodyLarge, color = if (n.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
