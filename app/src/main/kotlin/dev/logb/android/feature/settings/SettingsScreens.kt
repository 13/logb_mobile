package dev.logb.android.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.logb.android.R
import dev.logb.android.core.auth.LockPolicy
import dev.logb.android.core.auth.Session
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.relativeTime
import dev.logb.android.core.prefs.ThemeMode
import dev.logb.android.core.sync.SyncStatus

@Composable
private fun themeLabel(mode: ThemeMode) = stringResource(when (mode) { ThemeMode.System -> R.string.theme_system; ThemeMode.Light -> R.string.theme_light; ThemeMode.Dark -> R.string.theme_dark })

@Composable
private fun syncValueLabel(status: SyncStatus): String? = when (status) {
    SyncStatus.None, SyncStatus.SignedOut -> null
    is SyncStatus.Idle -> stringResource(R.string.sync_synced, relativeTime(status.lastSyncedAt))
    SyncStatus.Syncing -> stringResource(R.string.sync_syncing)
    is SyncStatus.Offline -> if (status.pending > 0) pluralStringResource(R.plurals.sync_waiting, status.pending, status.pending) else stringResource(R.string.sync_offline)
    is SyncStatus.Failed -> stringResource(R.string.sync_failed)
}

@Composable
fun SettingsHubScreen(onOpen: (SettingsPage) -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsHubContent(state, onOpen)
}

/** The hub without its view model. */
@Composable
fun SettingsHubContent(state: SettingsUiState, onOpen: (SettingsPage) -> Unit) {
    val language = currentLocale().language
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(title = stringResource(R.string.nav_settings))
        val rows = settingsRows(state.session, state.appearance, state.sync, language, state.version, failed = state.deadOps.size)
        rows.forEach { row ->
            val (title, icon) = when (row.page) {
                SettingsPage.Appearance -> R.string.settings_appearance to Icons.Outlined.Palette
                SettingsPage.Account -> R.string.settings_account to Icons.Outlined.Person
                SettingsPage.Sync -> R.string.settings_sync to Icons.Outlined.Sync
                SettingsPage.Notifications -> R.string.settings_notifications to Icons.Outlined.Notifications
                SettingsPage.About -> R.string.settings_about to Icons.Outlined.Info
            }
            val value = when (row.page) {
                SettingsPage.Appearance -> "${themeLabel(state.appearance.theme)} · ${language.uppercase()}"
                SettingsPage.Sync -> if (state.deadOps.isNotEmpty()) pluralStringResource(R.plurals.sync_could_not_save, state.deadOps.size, state.deadOps.size) else syncValueLabel(state.sync)
                SettingsPage.Notifications -> if (state.notifications.enabled) stringResource(R.string.notify_daily_at, state.notifications.time) else stringResource(R.string.off)
                else -> row.value
            }
            HubRow(icon, stringResource(title), value) { onOpen(row.page) }
        }
    }
}

@Composable
private fun HubRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = value?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun AppearanceScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LogbTopBar(title = stringResource(R.string.settings_appearance), onBack = onBack)
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(selected = state.appearance.theme == mode, onClick = { viewModel.setTheme(mode) }, shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) { Text(themeLabel(mode)) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            val options = listOf("" to R.string.language_system, "en" to R.string.language_en, "de" to R.string.language_de)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (tag, label) ->
                    SegmentedButton(
                        selected = current == tag,
                        onClick = { AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(stringResource(label)) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dynamic_color), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.dynamic_color_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.appearance.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
            }
        }
    }
}

@Composable
fun AccountScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var removeData by remember { mutableStateOf(false) }
    val s = state.session as? Session.SignedIn
    val serverVersion by viewModel.serverVersion.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    var changePassword by remember { mutableStateOf(false) }
    var confirmEverywhere by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LogbTopBar(title = stringResource(R.string.settings_account), onBack = onBack)
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.server_url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s?.serverUrl?.removeSuffix("/") ?: "", style = MaterialTheme.typography.bodyLarge)
            serverVersion?.let { Text(stringResource(R.string.server_version, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (serverVersion != null && !capabilities.tags) {
                Text(stringResource(R.string.server_needs_update), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.username), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s?.user?.username ?: "", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            val context = androidx.compose.ui.platform.LocalContext.current
            val availability = LockPolicy.availability(androidx.biometric.BiometricManager.from(context).canAuthenticate(LockPolicy.AUTHENTICATORS))
            val available = availability == LockPolicy.Availability.Available
            Row(
                Modifier.fillMaxWidth().toggleable(value = state.lockEnabled && available, enabled = available, role = Role.Switch, onValueChange = viewModel::setLockEnabled),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.lock_enable), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(if (available) R.string.lock_enable_hint else R.string.lock_unavailable),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.lockEnabled && available, onCheckedChange = null, enabled = available)
            }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { changePassword = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.change_password)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sign_out)) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmEverywhere = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sign_out_everywhere)) }
            Text(stringResource(R.string.sign_out_everywhere_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (changePassword) ChangePasswordDialog(onDismiss = { changePassword = false }, onSubmit = { pw, done -> viewModel.changePassword(pw, done) })
    if (confirmEverywhere) {
        AlertDialog(
            onDismissRequest = { confirmEverywhere = false },
            title = { Text(stringResource(R.string.sign_out_everywhere)) },
            text = { Text(stringResource(R.string.sign_out_everywhere_body)) },
            confirmButton = { Button(onClick = { confirmEverywhere = false; viewModel.signOutEverywhere() }) { Text(stringResource(R.string.sign_out_everywhere)) } },
            dismissButton = { TextButton(onClick = { confirmEverywhere = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.sign_out)) },
            text = {
                Column {
                    Text(stringResource(R.string.sign_out_body))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { removeData = !removeData }) {
                        Checkbox(checked = removeData, onCheckedChange = { removeData = it })
                        Text(stringResource(R.string.sign_out_remove_data))
                    }
                }
            },
            confirmButton = { Button(onClick = { confirm = false; viewModel.signOut(removeData) }) { Text(stringResource(R.string.sign_out)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** New password twice; the server's own refusal (too short, say) shows under the field. */
@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit, onSubmit: (String, (String?) -> Unit) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val mismatch = stringResource(R.string.change_password_mismatch)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_password)) },
        text = {
            Column {
                if (done) {
                    Text(stringResource(R.string.change_password_done))
                } else {
                    androidx.compose.material3.OutlinedTextField(first, { first = it; error = null }, label = { Text(stringResource(R.string.change_password_new)) }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(second, { second = it; error = null }, label = { Text(stringResource(R.string.change_password_repeat)) }, singleLine = true, isError = error != null, supportingText = error?.let { { Text(it) } }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            } else {
                Button(
                    enabled = !busy && first.isNotEmpty(),
                    onClick = {
                        if (first != second) { error = mismatch; return@Button }
                        busy = true
                        onSubmit(first) { result -> busy = false; if (result == null) done = true else error = result }
                    },
                ) { Text(stringResource(R.string.save)) }
            }
        },
        dismissButton = { if (!done) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun SyncScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var discarding by remember { mutableStateOf<dev.logb.android.core.db.entity.OpEntity?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LogbTopBar(title = stringResource(R.string.settings_sync), onBack = onBack)
        Column(Modifier.padding(16.dp)) {
            Text(syncValueLabel(state.sync) ?: stringResource(R.string.sync_never), style = MaterialTheme.typography.bodyLarge)
            state.syncState?.let { st ->
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.sync_cursor, st.cursorSeq, st.epoch?.take(8) ?: "–"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.sync_device, st.deviceId.take(8)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = viewModel::syncNow, enabled = state.sync !is SyncStatus.Syncing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sync_now)) }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.sync_pending_title), style = MaterialTheme.typography.titleSmall)
            Text(
                if (state.pending == 0) stringResource(R.string.sync_pending_none) else pluralStringResource(R.plurals.sync_waiting, state.pending, state.pending),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.storage_used, android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, state.usageBytes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.storage_budget), style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(1L, 2L, 4L, 8L)
                options.forEachIndexed { i, gb ->
                    val bytes = gb * 1024 * 1024 * 1024
                    SegmentedButton(selected = state.blobs.budgetBytes == bytes, onClick = { viewModel.setBudget(bytes) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text("$gb GB") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.storage_unmetered_only), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = state.blobs.originalsUnmeteredOnly, onCheckedChange = viewModel::setUnmeteredOnly)
            }
            TextButton(onClick = { viewModel.freeUpSpace() }) { Text(stringResource(R.string.storage_free_up)) }
            if (state.deadOps.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.sync_failed_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.sync_failed_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.deadOps.forEach { op ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("${op.kind} · ${op.entity}${op.field?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodyLarge)
                        Text(op.lastError ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.retry(op.id) }) { Text(stringResource(R.string.retry)) }
                            TextButton(onClick = { discarding = op }) { Text(stringResource(R.string.discard)) }
                        }
                    }
                }
            }
        }
    }
    discarding?.let { op ->
        AlertDialog(
            onDismissRequest = { discarding = null },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(if (op.kind == "create") R.string.discard_create_body else R.string.discard_set_body)) },
            confirmButton = { TextButton(onClick = { discarding = null; viewModel.discard(op) }) { Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { discarding = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LogbTopBar(title = stringResource(R.string.settings_about), onBack = onBack)
        Column(Modifier.padding(16.dp)) {
            Text("LogB ${state.version}", style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_commit, dev.logb.android.BuildConfig.GIT_HASH), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.about_licenses), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Kotlin, Jetpack Compose, Material 3, Room, WorkManager, DataStore, Hilt (Apache 2.0) · OkHttp, Retrofit (Apache 2.0) · Coil (Apache 2.0) · kotlinx.serialization, kotlinx.coroutines (Apache 2.0)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
