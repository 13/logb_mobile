package dev.logb.android.core.design.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.logb.android.R
import dev.logb.android.core.domain.ReminderTemplate

/** The title a reminder template shows and is created with, in the person's language. */
@Composable
fun templateTitle(t: ReminderTemplate): String = stringResource(
    when (t.titleKey) {
        "template_oil" -> R.string.template_oil
        "template_inspection" -> R.string.template_inspection
        "template_tyres" -> R.string.template_tyres
        "template_service" -> R.string.template_service
        "template_chain" -> R.string.template_chain
        "template_smoke" -> R.string.template_smoke
        "template_heating" -> R.string.template_heating
        "template_descale" -> R.string.template_descale
        "template_filter" -> R.string.template_filter
        "template_checkup" -> R.string.template_checkup
        "template_dentist" -> R.string.template_dentist
        else -> R.string.template_reading
    },
)

/** A `Validation` error key as the sentence under a field, or null when the field is fine. */
@Composable
fun errorText(key: String?): String? = key?.let {
    stringResource(
        when (it) {
            "error_required" -> R.string.error_required
            "error_date" -> R.string.error_date
            "error_negative" -> R.string.error_negative
            "error_positive" -> R.string.error_positive
            "error_no_counter" -> R.string.error_no_counter
            "error_reading_needs_counter" -> R.string.error_reading_needs_counter
            "error_due_required" -> R.string.error_due_required
            "error_every" -> R.string.error_every
            else -> R.string.error_invalid
        },
    )
}
