package dev.logb.android.feature.entries

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.logb.android.R
import java.io.File
import java.util.UUID

/** Camera, gallery, document: three ways to the same result, a list of content URIs the caller imports. */
@Composable
fun AttachmentPicker(onPicked: (List<Uri>) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cameraTarget = remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraTarget.value?.let { onPicked(listOf(it)) } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { if (it.isNotEmpty()) onPicked(it) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onPicked(it) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "${UUID.randomUUID()}.jpg"))
                cameraTarget.value = uri
                camera.launch(uri)
            },
            modifier = Modifier.weight(1f),
        ) { Icon(Icons.Outlined.PhotoCamera, contentDescription = null); Text(" " + stringResource(R.string.pick_camera)) }
        OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Image, contentDescription = null); Text(" " + stringResource(R.string.pick_gallery))
        }
        OutlinedButton(onClick = { documents.launch(arrayOf("application/pdf", "text/plain", "text/markdown", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.AttachFile, contentDescription = null); Text(" " + stringResource(R.string.pick_document))
        }
    }
}
