package de.sleeptracker.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.sleeptracker.analysis.Chronotype
import de.sleeptracker.analysis.TagImpact
import de.sleeptracker.analysis.TrendAlert
import de.sleeptracker.app.R
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.components.MetricRow
import de.sleeptracker.app.ui.components.SectionCard
import de.sleeptracker.app.ui.durationText
import de.sleeptracker.app.ui.journal.tagLabel
import de.sleeptracker.app.ui.theme.DataColors
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun metricName(m: TrendAlert.Metric) = stringResource(
    when (m) {
        TrendAlert.Metric.SNORING -> R.string.trend_snoring
        TrendAlert.Metric.BEDTIME -> R.string.trend_bedtime
        TrendAlert.Metric.EFFICIENCY -> R.string.efficiency
        TrendAlert.Metric.DURATION -> R.string.sleep_duration
        TrendAlert.Metric.SCORE -> R.string.sleep_score
        TrendAlert.Metric.LATENCY -> R.string.latency
        TrendAlert.Metric.BREATHING -> R.string.breathing_rate
    },
)

@Composable
private fun metricValue(m: TrendAlert.Metric, v: Float): String = when (m) {
    TrendAlert.Metric.SNORING -> stringResource(R.string.per_night_minutes, v.roundToInt())
    TrendAlert.Metric.BEDTIME -> Fmt.minuteOfDay(Math.floorMod(v.roundToInt(), 1440))
    TrendAlert.Metric.EFFICIENCY -> "%.0f %%".format(v)
    TrendAlert.Metric.DURATION -> durationText(v.roundToInt())
    TrendAlert.Metric.SCORE -> "%.0f".format(v)
    TrendAlert.Metric.LATENCY -> "%.0f min".format(v)
    TrendAlert.Metric.BREATHING -> "%.1f /min".format(v)
}

/** Left accent bar + content, used for alerts and tag impacts. */
@Composable
private fun AccentRow(color: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { content() }
    }
}

@Composable
fun TrendAlertsCard(alerts: List<TrendAlert>, enoughData: Boolean) {
    SectionCard(stringResource(R.string.trends_title)) {
        Text(stringResource(R.string.trends_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        when {
            !enoughData -> Text(stringResource(R.string.trends_need_more), style = MaterialTheme.typography.bodyMedium)
            alerts.isEmpty() -> Text(stringResource(R.string.trends_none), style = MaterialTheme.typography.bodyMedium)
            else -> alerts.forEach { a ->
                AccentRow(if (a.concern) DataColors.warn else DataColors.good) {
                    Text(metricName(a.metric), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${metricValue(a.metric, a.before)} → ${metricValue(a.metric, a.after)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (a.concern) DataColors.warn else DataColors.good,
                    )
                    Text(
                        stringResource(if (a.concern) R.string.trend_worse else R.string.trend_better) + " · " +
                            stringResource(R.string.trend_nights, a.nightsBefore, a.nightsAfter),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun TagImpactCard(impacts: List<TagImpact>) {
    SectionCard(stringResource(R.string.tag_impact_title)) {
        Text(stringResource(R.string.tag_impact_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (impacts.isEmpty()) {
            Text(stringResource(R.string.tag_impact_none), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val maxAbs = impacts.maxOf { abs(it.scoreDiff) }.coerceAtLeast(5f)
        impacts.forEachIndexed { i, t ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tagLabel(t.tag), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.tag_impact_counts, t.withNights, t.withoutNights),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "%+.0f".format(t.scoreDiff),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (t.scoreDiff >= 0) DataColors.good else DataColors.bad,
                )
            }
            DivergingBar(t.scoreDiff / maxAbs)
            Text(
                listOfNotNull(
                    stringResource(R.string.tag_impact_score),
                    t.deepDiff?.let { stringResource(R.string.tag_impact_deep, "%+.0f".format(it)) },
                    stringResource(R.string.tag_impact_latency, "%+.0f".format(t.latencyDiff)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.insights_caution), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Bar from the centre: right/green for better, left/red for worse. [f] in −1..1. */
@Composable
private fun DivergingBar(f: Float) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(8.dp).padding(vertical = 2.dp)) {
        val mid = size.width / 2
        drawRect(track, Offset.Zero, size)
        val w = mid * f.coerceIn(-1f, 1f)
        val color = if (f >= 0) DataColors.good else DataColors.bad
        if (w >= 0) drawRect(color, Offset(mid, 0f), Size(w, size.height)) else drawRect(color, Offset(mid + w, 0f), Size(-w, size.height))
        drawRect(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.4f), Offset(mid - 0.5f, 0f), Size(1f, size.height))
    }
}

@Composable
fun chronotypeName(t: Chronotype.Type) = stringResource(
    when (t) {
        Chronotype.Type.EXTREME_EARLY -> R.string.chrono_extreme_early
        Chronotype.Type.MODERATE_EARLY -> R.string.chrono_moderate_early
        Chronotype.Type.SLIGHT_EARLY -> R.string.chrono_slight_early
        Chronotype.Type.INTERMEDIATE -> R.string.chrono_intermediate
        Chronotype.Type.SLIGHT_LATE -> R.string.chrono_slight_late
        Chronotype.Type.MODERATE_LATE -> R.string.chrono_moderate_late
        Chronotype.Type.EXTREME_LATE -> R.string.chrono_extreme_late
    },
)

@Composable
fun ChronotypeCard(c: Chronotype?) {
    SectionCard(stringResource(R.string.chronotype)) {
        if (c == null) {
            Text(stringResource(R.string.chronotype_need_more), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Text(chronotypeName(c.type), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        // Scale from extreme early to extreme late with a marker.
        val pos = ((c.msfScMinutes / 60f - 1f) / 7f).coerceIn(0f, 1f)
        val track = MaterialTheme.colorScheme.surfaceVariant
        val accent = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(14.dp).padding(vertical = 4.dp)) {
            drawRect(track, Offset.Zero, size)
            for (k in 1 until 7) drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.3f), Offset(size.width * k / 7f, 0f), Size(1f, size.height))
            drawRect(accent, Offset(pos * size.width - 3f, -2f), Size(6f, size.height + 4f))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.chrono_early_label), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.chrono_late_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MetricRow(stringResource(R.string.chrono_midsleep), Fmt.minuteOfDay(Math.floorMod(c.msfScMinutes, 1440)))
        MetricRow(stringResource(R.string.nights), stringResource(R.string.chrono_nights, c.freeNights, c.workNights))
        Text(stringResource(R.string.chronotype_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RegularityIndexCard(sri: Float?) {
    SectionCard(stringResource(R.string.sri_title)) {
        if (sri == null) {
            Text(stringResource(R.string.sri_need_more), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val (label, color) = when {
            sri >= 80f -> stringResource(R.string.sri_very_regular) to DataColors.good
            sri >= 60f -> stringResource(R.string.sri_regular) to DataColors.good
            sri >= 40f -> stringResource(R.string.sri_moderate) to DataColors.warn
            else -> stringResource(R.string.sri_irregular) to DataColors.bad
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.0f".format(sri), style = MaterialTheme.typography.displaySmall, color = color)
            Spacer(Modifier.width(8.dp))
            Text("/ 100 · $label", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
        }
        val track = MaterialTheme.colorScheme.surfaceVariant
        Canvas(Modifier.fillMaxWidth().height(10.dp).padding(vertical = 2.dp)) {
            drawRect(track, Offset.Zero, size)
            drawRect(color, Offset.Zero, Size(size.width * ((sri + 100f) / 200f).coerceIn(0f, 1f), size.height))
        }
        Text(stringResource(R.string.sri_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
