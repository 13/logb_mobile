package dev.logb.android.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.logb.android.R
import dev.logb.android.core.design.theme.figureSmall
import dev.logb.android.core.format.NO_VALUE
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatCents
import dev.logb.android.core.format.formatCentsWhole
import dev.logb.android.core.format.formatCounter
import dev.logb.android.core.format.formatPerCounter
import dev.logb.android.core.format.formatQuantity
import dev.logb.android.feature.objects.tabs.categoryLabel

/** The web's `Insights.svelte`: an object's cost, usage and fuel figures, in the same order. */
@Composable
fun InsightsSection(insights: ObjectInsights?, counterUnit: String?, currency: String, includeContents: Boolean, onIncludeContents: (Boolean) -> Unit) {
    val locale = currentLocale()
    val data = insights ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.insights_title), style = MaterialTheme.typography.titleSmall)
        if (data.hasContents) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.insights_contents), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = includeContents, onCheckedChange = onIncludeContents)
            }
        }
        val o = data.ownership
        if (o.totalCents > 0) {
            val since = Labels.sinceLabel(o.since, locale)
            val tail = if (o.perYearCents != null) stringResource(R.string.insights_per_year_since, formatCentsWhole(o.perYearCents, currency, locale), since) else stringResource(R.string.insights_since, since)
            Figure(stringResource(R.string.insights_ownership), formatCents(o.totalCents, currency, locale), tail)
        }
        if (!data.spent) {
            Text(stringResource(R.string.insights_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Heading(stringResource(R.string.insights_spend_by_month))
            BarList(data.byMonth.map { Bar(it.bucket, Labels.monthLabel(it.bucket, locale), it.costCents, formatCents(it.costCents, currency, locale)) })
            Heading(stringResource(R.string.insights_by_year))
            BarList(data.byYear.map { Bar(it.bucket, it.bucket, it.costCents, formatCents(it.costCents, currency, locale)) })
            Heading(stringResource(R.string.insights_by_category))
            BarList(data.byCategory.map { Bar(it.bucket, categoryLabel(it.bucket), it.costCents, formatCents(it.costCents, currency, locale)) })
            if (data.costPerCounterMilli != null && counterUnit != null) {
                Figure(stringResource(R.string.insights_per_counter, counterUnit), formatPerCounter(data.costPerCounterMilli, currency, locale))
            }
        }
        data.fuel?.let { fuel ->
            Figure(stringResource(R.string.insights_fuel_total), formatQuantity(fuel.quantityMilli, fuel.unit, locale))
            // `costPerCounterMilli` is null under exactly the same condition as `per100Milli`, so one guard covers both.
            if (fuel.per100Milli != null && counterUnit != null) {
                Figure(stringResource(R.string.insights_consumption), "${formatQuantity(fuel.per100Milli, fuel.unit, locale)}/100 $counterUnit")
                Figure(stringResource(R.string.insights_fuel_per_counter, counterUnit), formatPerCounter(fuel.costPerCounterMilli ?: 0, currency, locale))
            }
        }
        if (data.counterPerDayMilli != null && counterUnit != null) {
            // A month is the unit people think in for mileage; 30.44 days is the average one. Rounded to a whole unit.
            val perMonth = Math.round(data.counterPerDayMilli * 30.44 / 1000)
            Figure(stringResource(R.string.insights_usage), stringResource(R.string.insights_per_month, formatCounter(perMonth, counterUnit, locale)))
        }
        if (data.usageByMonth.isNotEmpty() && counterUnit != null) {
            Heading(stringResource(R.string.insights_usage_by_month))
            // A month the readings cannot measure says so, rather than drawing a zero it does not know.
            BarList(data.usageByMonth.map { Bar(it.month, Labels.monthLabel(it.month, locale), it.amount ?: 0, if (it.amount == null) NO_VALUE else formatCounter(it.amount, counterUnit, locale)) })
        }
        data.fuel?.takeIf { it.fills.isNotEmpty() && counterUnit != null }?.let { fuel ->
            Heading(stringResource(R.string.insights_by_fill))
            Text(stringResource(R.string.insights_by_fill_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BarList(fuel.fills.mapIndexed { i, f -> Bar("${f.date}-$i", Labels.fillLabel(f.date, locale), f.per100Milli, "${formatQuantity(f.per100Milli, fuel.unit, locale)}/100 $counterUnit") })
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Figure(label: String, value: String, tail: String? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = MaterialTheme.typography.figureSmall)
            tail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
