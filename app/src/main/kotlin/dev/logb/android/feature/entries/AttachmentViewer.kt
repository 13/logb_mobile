package dev.logb.android.feature.entries

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.R
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.blobs.BlobDownloader
import dev.logb.android.core.blobs.BlobImage
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.db.model.AttachmentWithFile
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.sync.Repositories
import dev.logb.android.navigation.Viewer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ViewerState(val att: AttachmentWithFile? = null, val obj: ObjectEntity? = null, val original: File? = null, val fetching: Boolean = false, val gone: Boolean = false)

@HiltViewModel
class AttachmentViewerViewModel @Inject constructor(accounts: ActiveAccount, private val repos: Repositories, private val downloader: () -> BlobDownloader?, savedState: SavedStateHandle) : ViewModel() {
    private val route: Viewer = savedState.toRoute()
    private val db = accounts.db
    private val _state = MutableStateFlow(ViewerState())
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val a = db.attachmentDao().get(route.attachmentUuid)?.takeIf { it.deletedAt == null }
            val f = a?.let { db.fileDao().get(it.fileUuid) }
            if (a == null || f == null) { _state.update { it.copy(gone = true) }; return@launch }
            _state.update { it.copy(att = AttachmentWithFile(a, f), obj = db.objectDao().get(a.objectUuid), fetching = true) }
            val original = downloader()?.ensureOriginal(f.sha256)
            _state.update { it.copy(original = original, fetching = false) }
        }
    }

    fun setCaption(caption: String) = viewModelScope.launch {
        repos.attachmentRepository.setCaption(route.attachmentUuid, caption)
        _state.update { s -> s.copy(att = s.att?.let { it.copy(attachment = it.attachment.copy(caption = caption.trim())) }) }
    }

    fun useAsCover() = viewModelScope.launch { _state.value.att?.let { repos.attachmentRepository.setCover(it.attachment.objectUuid, it.attachment.uuid) } }

    fun delete(onDone: () -> Unit) = viewModelScope.launch { repos.attachmentRepository.delete(route.attachmentUuid); onDone() }
}

@Composable
fun AttachmentViewerScreen(onBack: () -> Unit, viewModel: AttachmentViewerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var editCaption by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    val att = state.att ?: return
    val isImage = att.file.mime.startsWith("image/")
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(
            title = att.attachment.caption.ifBlank { att.file.originalName },
            onBack = onBack,
            actions = {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete)) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.caption_edit)) }, onClick = { menu = false; editCaption = true })
                    if (isImage) DropdownMenuItem(text = { Text(stringResource(R.string.use_as_cover)) }, onClick = { menu = false; viewModel.useAsCover() })
                    state.original?.let { file ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.open_with)) }, onClick = {
                            menu = false
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, att.file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                        })
                    }
                }
            },
        )
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (isImage) {
                var scale by remember { mutableFloatStateOf(1f) }
                var offsetX by remember { mutableFloatStateOf(0f) }
                var offsetY by remember { mutableFloatStateOf(0f) }
                AsyncImage(
                    model = BlobImage(att.file.sha256, thumb = state.original == null),
                    contentDescription = att.attachment.caption.ifBlank { att.file.originalName },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offsetX = if (scale > 1f) offsetX + pan.x else 0f
                                offsetY = if (scale > 1f) offsetY + pan.y else 0f
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                )
                if (state.fetching) CircularProgressIndicator(Modifier.align(Alignment.BottomCenter).padding(24.dp))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(att.file.originalName, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(att.file.mime, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    if (state.fetching) CircularProgressIndicator(Modifier.padding(16.dp))
                    else if (state.original == null) Text(stringResource(R.string.original_unavailable), color = Color.LightGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
                    else TextButton(onClick = {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", state.original!!)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, att.file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                    }) { Text(stringResource(R.string.open_with)) }
                }
            }
        }
    }
    if (editCaption) {
        var text by remember { mutableStateOf(att.attachment.caption) }
        AlertDialog(
            onDismissRequest = { editCaption = false },
            title = { Text(stringResource(R.string.caption_edit)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { editCaption = false; viewModel.setCaption(text) }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editCaption = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_attachment_title)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
