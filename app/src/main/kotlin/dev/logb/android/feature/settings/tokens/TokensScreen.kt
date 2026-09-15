package dev.logb.android.feature.settings.tokens

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.logb.android.R
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatDate
import dev.logb.android.core.network.dto.ApiToken
import kotlinx.coroutines.launch

@Composable
fun TokensScreen(onBack: () -> Unit, viewModel: TokensViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    TokensContent(
        state = state,
        onBack = onBack,
        onName = viewModel::onName,
        onCreate = viewModel::askCreate,
        onRevoke = viewModel::askRevoke,
        onConfirm = viewModel::confirm,
        onDismiss = viewModel::dismiss,
        onCopy = { text ->
            scope.launch {
                val clip = ClipData.newPlainText("LogB token", text)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                }
                clipboard.setClipEntry(ClipEntry(clip))
            }
        },
    )
}

/** The API access page without its view model, for the screenshot test. */
@Composable
fun TokensContent(
    state: TokensUiState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onCreate: () -> Unit,
    onRevoke: (ApiToken) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val locale = currentLocale()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var copied by remember(state.fresh) { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<ApiToken?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LogbTopBar(title = stringResource(R.string.tokens_title), onBack = onBack)
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.tokens_intro), style = MaterialTheme.typography.bodyMedium, color = muted)
            if (state.offline) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.needs_connection), style = MaterialTheme.typography.bodyMedium, color = muted)
            } else {
                if (state.fresh != null) {
                    Spacer(Modifier.height(16.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.tokens_created), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            SelectionContainer { Text(state.fresh, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { onCopy(state.fresh); copied = true }) {
                                Text(stringResource(if (copied) R.string.tokens_copied else R.string.tokens_copy))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(pluralStringResource(R.plurals.tokens_count, state.rows.size, state.rows.size), style = MaterialTheme.typography.titleSmall, color = muted)
                if (state.rows.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.tokens_none), style = MaterialTheme.typography.bodyMedium, color = muted)
                } else {
                    state.rows.forEach { row ->
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.token.name, style = MaterialTheme.typography.bodyLarge)
                                val used = row.token.lastUsedAt?.let { stringResource(R.string.tokens_last_used, formatDate(it.take(10), locale)) } ?: stringResource(R.string.tokens_never_used)
                                Text("${row.token.prefix}… · $used", style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                            if (row.isThisPhone) {
                                Text(stringResource(R.string.tokens_this_phone), style = MaterialTheme.typography.bodySmall, color = muted)
                            } else {
                                TextButton(onClick = { revoking = row.token }) { Text(stringResource(R.string.tokens_revoke), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { onName(it.take(64)) },
                    label = { Text(stringResource(R.string.tokens_name)) },
                    placeholder = { Text(stringResource(R.string.tokens_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onCreate, enabled = TokenRows.validName(state.name) != null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tokens_create))
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tokens_password_note), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
    revoking?.let { token ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(stringResource(R.string.tokens_revoke)) },
            text = { Text(stringResource(R.string.tokens_revoke_confirm)) },
            confirmButton = { TextButton(onClick = { revoking = null; onRevoke(token) }) { Text(stringResource(R.string.tokens_revoke), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (state.asking != null) {
        PasswordDialog(busy = state.busy, error = state.error, onConfirm = onConfirm, onDismiss = onDismiss)
    }
}

/**
 * The typed password lives only here, in this composable's own local state: it is never lifted
 * into the view model, never in a `SavedStateHandle`, and never `rememberSaveable` -- a rotation
 * (or the dialog closing) forgets it rather than persisting it anywhere.
 */
@Composable
private fun PasswordDialog(busy: Boolean, error: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tokens_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.tokens_password_hint), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy && password.isNotBlank(), onClick = { onConfirm(password) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
