package dev.logb.android.core.design.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.logb.android.R
import dev.logb.android.core.domain.AddResult
import dev.logb.android.core.domain.TagCount
import dev.logb.android.core.domain.TagError
import dev.logb.android.core.domain.Tags

/** The web's TagInput: chips with remove, a field that turns commas and Done into tags, and suggestions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagInput(tags: List<String>, onTags: (List<String>) -> Unit, suggestions: List<TagCount>, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<TagError?>(null) }
    fun add(raw: String) {
        when (val r = Tags.addTag(tags, raw)) {
            is AddResult.Added -> { onTags(r.tags); text = ""; error = null }
            is AddResult.Refused -> if (r.error != TagError.EMPTY) error = r.error
        }
    }
    Column(modifier) {
        Text(stringResource(R.string.tags_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { tag ->
                val remove = stringResource(R.string.tags_remove, tag)
                // As on the web: tapping the chip body does nothing; only the trailing close icon removes it.
                InputChip(selected = false, onClick = {}, label = { Text(tag) },
                    trailingIcon = {
                        Icon(Icons.Outlined.Close, contentDescription = remove, modifier = Modifier.clickable { onTags(Tags.removeTag(tags, tag)) })
                    })
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { value -> Tags.splitTyped(tags, value).let { r -> if (r.tags != tags) onTags(r.tags); text = r.text; error = r.error } },
            placeholder = { Text(stringResource(R.string.tags_placeholder)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { e -> { Text(stringResource(if (e == TagError.TOO_LONG) R.string.tags_too_long else R.string.tags_too_many)) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add(text) }),
            modifier = Modifier.fillMaxWidth(),
        )
        val offered = Tags.suggestTags(suggestions, tags, text)
        if (offered.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                offered.forEach { s -> SuggestionChip(onClick = { add(s) }, label = { Text(s) }) }
            }
        }
    }
}
