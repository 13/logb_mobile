package dev.logb.android.feature.settings.data

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.logb.android.R
import dev.logb.android.core.design.components.LogbTopBar
import java.time.LocalDate

@Composable
fun DataScreen(onBack: () -> Unit, viewModel: DataViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(viewModel::export) }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::askImport) }
    val errorMessage = when (state.error) {
        DataError.Offline -> stringResource(R.string.needs_connection)
        DataError.TooLarge -> stringResource(R.string.data_too_large)
        DataError.FileGone -> stringResource(R.string.data_file_gone)
        DataError.Other -> state.errorMessage
        null -> null
    }
    DataContent(
        state = state,
        onBack = onBack,
        onExport = { createDoc.launch(DataTransfer.exportFileName(today)) },
        onImport = { openDoc.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
        onConfirmImport = viewModel::confirmImport,
        onCancelImport = viewModel::cancelImport,
        errorMessage = errorMessage,
    )
}

/** The Data page without its view model, for the screenshot test. */
@Composable
fun DataContent(
    state: DataUiState,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    errorMessage: String? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LogbTopBar(title = stringResource(R.string.settings_data), onBack = onBack)
        Column(Modifier.padding(16.dp)) {
            Button(onClick = onExport, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_export)) }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onImport, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_import)) }
            if (state.busy != null) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (state.busy == Busy.Exporting) R.string.data_exporting else R.string.data_importing),
                    style = MaterialTheme.typography.bodyMedium, color = muted,
                )
            }
            if (state.exported) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.data_exported), style = MaterialTheme.typography.bodyMedium)
            }
            state.imported?.let { counts ->
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(
                        R.string.settings_import_done,
                        counts.objects, counts.activities, counts.attachments, counts.reminders, counts.typesCreated, counts.typesMerged,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                Text(errorMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.settings_export_not_backup), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
    if (state.pendingImport != null) {
        AlertDialog(
            onDismissRequest = onCancelImport,
            title = { Text(stringResource(R.string.settings_import)) },
            text = { Text(stringResource(R.string.data_import_warning)) },
            confirmButton = { Button(onClick = onConfirmImport) { Text(stringResource(R.string.data_import_confirm)) } },
            dismissButton = { TextButton(onClick = onCancelImport) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
