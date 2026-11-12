package de.sleeptracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.sleeptracker.app.ui.Fmt

/**
 * Line chart over the night. NaN values are gaps (no sensor data). Optional dashed reference line.
 * Tap to read a value.
 */
@Composable
fun LineChart(
    values: List<Float>,
    color: Color,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    reference: Float? = null,
    height: Dp = 90.dp,
    description: String,
) {
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val valid = values.filter { !it.isNaN() }
    val min = valid.minOrNull() ?: 0f
    val max = (valid.maxOrNull() ?: 1f).let { if (it - min < 1e-3f) min + 1f else it }
    val refColor = MaterialTheme.colorScheme.outline
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                selected?.let { values.getOrNull(it) }?.takeIf { !it.isNaN() }?.let(format) ?: " ",
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            )
            if (valid.isNotEmpty()) {
                Text("${format(min)} – ${format(max)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .pointerInput(values) {
                    detectTapGestures { p -> if (values.isNotEmpty()) selected = (p.x / size.width * values.size).toInt().coerceIn(0, values.size - 1) }
                },
        ) {
            if (values.size < 2) return@Canvas
            val step = size.width / (values.size - 1)
            fun y(v: Float) = size.height - (v - min) / (max - min) * size.height * 0.9f - size.height * 0.05f
            val path = Path()
            var drawing = false
            values.forEachIndexed { i, v ->
                if (v.isNaN()) { drawing = false; return@forEachIndexed }
                val x = i * step
                if (!drawing) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
                drawing = true
            }
            drawPath(path, color, style = Stroke(width = 2.5f))
            reference?.takeIf { it in min..max }?.let {
                drawLine(refColor, Offset(0f, y(it)), Offset(size.width, y(it)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
            selected?.let { i ->
                val x = i * step
                drawLine(color.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height), 1.5f)
            }
        }
    }
}

/** One night of the sleep schedule. Minutes are measured from 18:00 (0..1439). */
data class ScheduleNight(
    val label: String,
    val bedMinute: Int,
    val asleepMinute: Int,
    val wakeMinute: Int,
    val detail: String,
)

/**
 * Sleep schedule: one vertical floating bar per night from bedtime to wake-up on an
 * evening→morning axis; a dot marks when the user fell asleep. Tap a bar for details.
 */
@Composable
fun ScheduleChart(nights: List<ScheduleNight>, modifier: Modifier = Modifier, description: String) {
    var selected by remember(nights) { mutableStateOf<Int?>(null) }
    if (nights.isEmpty()) return
    // Axis range: rounded to whole hours around the data.
    // Axis range in whole 2-hour steps so the labels line up with the grid.
    val rawLo = ((nights.minOf { it.bedMinute } / 60) * 60).coerceAtLeast(0)
    val rawHi = (((nights.maxOf { it.wakeMinute } + 59) / 60) * 60).coerceAtMost(1440)
    val span = (((rawHi - rawLo).coerceAtLeast(120) + 119) / 120) * 120
    val hi = (rawLo + span).coerceAtMost(1440)
    val lo = hi - span
    val bar = MaterialTheme.colorScheme.primary
    val asleep = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val hourLabels = (lo..hi step 120).toList()
    Column(modifier) {
        Text(
            selected?.let { "${nights[it].label}: ${nights[it].detail}" } ?: " ",
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        )
        Row {
            Column(Modifier.height(200.dp).width(44.dp), verticalArrangement = Arrangement.SpaceBetween) {
                hourLabels.forEach {
                    Text(Fmt.minuteOfDay((it + 18 * 60) % 1440), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height(200.dp)
                    .semantics { contentDescription = description }
                    .pointerInput(nights) {
                        detectTapGestures { p -> selected = (p.x / size.width * nights.size).toInt().coerceIn(0, nights.size - 1) }
                    },
            ) {
                val range = (hi - lo).toFloat()
                fun y(m: Int) = (m - lo) / range * size.height
                hourLabels.forEach { drawLine(grid, Offset(0f, y(it)), Offset(size.width, y(it)), 1f) }
                val slot = size.width / nights.size
                val w = (slot * 0.55f).coerceIn(2f, 28f)
                nights.forEachIndexed { i, n ->
                    val x = i * slot + (slot - w) / 2
                    val c = if (selected == null || selected == i) bar else bar.copy(alpha = 0.4f)
                    drawRoundRect(c.copy(alpha = c.alpha * 0.45f), Offset(x, y(n.bedMinute)), Size(w, (y(n.asleepMinute) - y(n.bedMinute)).coerceAtLeast(1f)), CornerRadius(w / 3, w / 3))
                    drawRoundRect(c, Offset(x, y(n.asleepMinute)), Size(w, (y(n.wakeMinute) - y(n.asleepMinute)).coerceAtLeast(2f)), CornerRadius(w / 3, w / 3))
                    drawCircle(asleep, radius = (w / 3).coerceIn(2f, 6f), center = Offset(x + w / 2, y(n.asleepMinute)))
                }
            }
        }
        if (nights.size <= 14) {
            Row(Modifier.fillMaxWidth().padding(start = 44.dp)) {
                nights.forEach {
                    Text(it.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

data class PairedEntry(val label: String, val first: Float, val second: Float, val display: String)

/** Two bars per entry (e.g. time in bed vs. time asleep). */
@Composable
fun PairedBarChart(
    entries: List<PairedEntry>,
    firstLabel: String,
    secondLabel: String,
    modifier: Modifier = Modifier,
    firstColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
    secondColor: Color = MaterialTheme.colorScheme.primary,
    description: String,
) {
    var selected by remember(entries) { mutableStateOf<Int?>(null) }
    val max = (entries.maxOfOrNull { maxOf(it.first, it.second) } ?: 1f).coerceAtLeast(1f) * 1.1f
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend(firstColor, firstLabel)
            Spacer(Modifier.width(12.dp))
            Legend(secondColor, secondLabel)
        }
        Text(selected?.let { "${entries[it].label}: ${entries[it].display}" } ?: " ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .semantics { contentDescription = description }
                .pointerInput(entries) {
                    detectTapGestures { p -> if (entries.isNotEmpty()) selected = (p.x / size.width * entries.size).toInt().coerceIn(0, entries.size - 1) }
                },
        ) {
            if (entries.isEmpty()) return@Canvas
            val slot = size.width / entries.size
            val w = (slot * 0.36f).coerceAtLeast(1.5f)
            entries.forEachIndexed { i, e ->
                val a = if (selected == null || selected == i) 1f else 0.4f
                val x = i * slot + slot * 0.12f
                val h1 = e.first / max * size.height
                val h2 = e.second / max * size.height
                drawRoundRect(firstColor.copy(alpha = firstColor.alpha * a), Offset(x, size.height - h1), Size(w, h1), CornerRadius(w / 4, w / 4))
                drawRoundRect(secondColor.copy(alpha = secondColor.alpha * a), Offset(x + w, size.height - h2), Size(w, h2), CornerRadius(w / 4, w / 4))
            }
        }
        if (entries.size <= 14) {
            Row(Modifier.fillMaxWidth()) {
                entries.forEach { Text(it.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
