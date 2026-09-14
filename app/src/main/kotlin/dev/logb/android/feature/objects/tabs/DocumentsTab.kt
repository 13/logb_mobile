package dev.logb.android.feature.objects.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.logb.android.R
import dev.logb.android.core.design.components.AttachmentThumb
import dev.logb.android.core.design.components.EmptyState
import dev.logb.android.feature.objects.ObjectDetailUiState

@Composable
fun DocumentsTab(state: ObjectDetailUiState, onAttachment: (String) -> Unit = {}, onAdd: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize()) {
        if (onAdd != null) OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { Text(stringResource(R.string.attachment_add)) }
        if (state.loaded && state.documents.isEmpty()) {
            EmptyState(icon = rememberVectorPainter(Icons.Outlined.Description), title = stringResource(R.string.documents_empty), body = stringResource(R.string.documents_empty_body))
            return@Column
        }
        LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(state.documents, key = { it.attachment.uuid }) { doc -> AttachmentThumb(doc, size = null, onClick = { onAttachment(doc.attachment.uuid) }) }
        }
    }
}
