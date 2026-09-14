package dev.logb.android.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.logb.android.R
import dev.logb.android.core.blobs.BlobImage
import dev.logb.android.core.db.model.AttachmentWithFile

/**
 * One attachment as a tile: the photo's thumbnail from the blob store, or a document icon with
 * its name. A file that exists only on this phone (its upload still queued) carries a cloud-off
 * badge, because that is a fact the person should be able to see.
 */
@Composable
fun AttachmentThumb(att: AttachmentWithFile, size: Dp?, onClick: () -> Unit) {
    val isImage = att.file.mime.startsWith("image/")
    val base = if (size != null) Modifier.size(size) else Modifier.fillMaxWidth().aspectRatio(1f)
    Box(base.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (isImage && att.file.sha256.isNotBlank()) {
            AsyncImage(model = BlobImage(att.file.sha256), contentDescription = att.attachment.caption.ifBlank { att.file.originalName }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (size == null) Text(att.file.originalName, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (att.attachment.serverId == null) {
            Icon(
                Icons.Outlined.CloudOff, contentDescription = stringResource(R.string.attachment_not_uploaded), tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), MaterialTheme.shapes.extraSmall),
            )
        }
    }
}
