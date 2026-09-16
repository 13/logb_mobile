package dev.logb.android.feature.pairing

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.logb.android.R
import dev.logb.android.core.auth.PairError

/** [PairError] as the screen's own text -- the network case reuses the address screen's existing "unreachable" wording, since a redeem failing that way is exactly that. */
@Composable
fun pairErrorMessage(error: PairError): String = when (error) {
    PairError.NotACode -> stringResource(R.string.pair_not_a_code)
    PairError.UnsafeAddress -> stringResource(R.string.pair_unsafe_address)
    PairError.Unsupported -> stringResource(R.string.pair_unsupported)
    PairError.Invalid -> stringResource(R.string.pair_invalid)
    PairError.RateLimited -> stringResource(R.string.pair_rate_limited)
    is PairError.Rejected -> error.message ?: stringResource(R.string.pair_rejected)
    PairError.Unreachable -> stringResource(R.string.server_unreachable)
    PairError.CameraPermissionDenied -> stringResource(R.string.pair_camera_permission_denied)
}

/**
 * Mounted alongside the server screen, the sign-in screen, and the signed-in unlocked app -- see
 * [PairingConfirmViewModel]'s doc. Renders nothing until a deep link actually arrives.
 */
@Composable
fun PairingConfirmHost(viewModel: PairingConfirmViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    state.prompt?.let { prompt ->
        PairingConfirmDialog(prompt, busy = state.busy, onConfirm = viewModel::confirm, onCancel = viewModel::cancel)
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            text = { Text(pairErrorMessage(error)) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun PairingConfirmDialog(prompt: PairingPrompt, busy: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(if (prompt is PairingPrompt.SignIn) stringResource(R.string.pair_confirm_signin_title, prompt.host) else stringResource(R.string.pair_replace_title)) },
        text = (prompt as? PairingPrompt.Replace)?.let { { Text(stringResource(R.string.pair_replace_body, it.fromUsername, it.fromHost, it.toHost)) } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.sign_in))
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}
