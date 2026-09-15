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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.logb.android.R
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.network.dto.NotificationTest

/** Settings › Notifications: the local reminder digest, and the server's webhook digest. */
@Composable
fun NotificationsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel(), digest: ServerDigestViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val digestState by digest.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val n = state.notifications
    // Android 13+ asks before an app may post; refused means the switch stays off rather than lying.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> viewModel.setNotificationsEnabled(granted) }
    // On Android 13+ nothing may be posted until the permission is granted, and "granted" is also what
    // `areNotificationsEnabled` reports; so the switch asks first and only then turns on. Later blocking
    // in the system settings shows as a hint under an enabled switch.
    val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val blocked = granted && !NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun toggle(on: Boolean) {
        when {
            !on -> viewModel.setNotificationsEnabled(false)
            !granted -> ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> viewModel.setNotificationsEnabled(true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(title = stringResource(R.string.settings_notifications), onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
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
            Spacer(Modifier.height(32.dp))
            ServerDigestSection(digestState, onUrl = digest::onUrl, onFormat = digest::onFormat, onSave = digest::save, onTest = digest::test)
        }
    }
}

/** The server-side digest: a webhook URL, its format, and a way to try it now. Its own section, testable on its own. */
@Composable
fun ServerDigestSection(
    state: ServerDigestUiState,
    onUrl: (String) -> Unit,
    onFormat: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.notify_server_title), style = MaterialTheme.typography.titleMedium)
        if (state.offline) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.needs_connection), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.notify_server_hour, state.hour), style = MaterialTheme.typography.bodySmall, color = muted)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.notify_webhook_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(if (state.instanceWebhook) R.string.notify_webhook_hint_instance else R.string.notify_webhook_hint),
            style = MaterialTheme.typography.bodySmall, color = muted,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.url,
            onValueChange = onUrl,
            label = { Text(stringResource(R.string.notify_webhook_url)) },
            placeholder = { Text("https://ntfy.sh/…") },
            singleLine = true,
            isError = state.invalidUrl,
            supportingText = if (state.invalidUrl) {
                { Text(stringResource(R.string.notify_webhook_invalid)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.notify_format), style = MaterialTheme.typography.bodySmall, color = muted)
        Spacer(Modifier.height(4.dp))
        Row {
            FilterChip(selected = state.format == "text", onClick = { onFormat("text") }, label = { Text(stringResource(R.string.notify_format_text)) })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = state.format == "json", onClick = { onFormat("json") }, label = { Text(stringResource(R.string.notify_format_json)) })
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSave, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = onTest, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.notify_test)) }
        val test = state.test
        val resultText = when {
            state.saved -> stringResource(R.string.notify_saved)
            test != null -> testResultText(test)
            else -> null
        }
        if (resultText != null) {
            Spacer(Modifier.height(12.dp))
            Text(resultText, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Joins whichever of push and webhook actually had somewhere to go; neither means nowhere to send one yet. */
@Composable
private fun testResultText(test: NotificationTest): String {
    val parts = buildList {
        if (test.pushSent + test.pushFailed > 0) add(stringResource(R.string.notify_test_push, test.pushSent))
        val webhook = test.webhook
        if (webhook != null) add(stringResource(R.string.notify_test_webhook, webhook))
    }
    return if (parts.isNotEmpty()) parts.joinToString(" ") else stringResource(R.string.notify_test_nowhere)
}
