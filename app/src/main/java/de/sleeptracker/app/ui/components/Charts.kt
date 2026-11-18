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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
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
import de.sleeptracker.app.ui.stageName
import de.sleeptracker.app.ui.theme.DataColors
import de.sleeptracker.core.SleepStage
import de.sleeptracker.core.SoundEventType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class StageRun(val startMillis: Long, val durationMillis: Long, val stage: SleepStage)

private fun laneOf(stage: SleepStage) = when (stage) {
    SleepStage.AWAKE -> 0
    SleepStage.REM -> 1
    SleepStage.LIGHT -> 2
    SleepStage.DEEP -> 3
}

/** Visible item window for index-based charts: first item (fractional) and number of items in view. */
internal data class ItemWindow(val first: Float, val count: Float) {
    fun x(i: Int, width: Float) = (i - first) / count * width
    fun slot(width: Float) = width / count
    fun indexAt(x: Float, width: Float, n: Int) = floor(first + x / width * count).toInt().coerceIn(0, n - 1)
    fun visibleIndices(n: Int) = (floor(first).toInt().coerceAtLeast(0))..(ceil(first + count).toInt().coerceAtMost(n) - 1)
}

internal fun window(zoom: ChartZoomState?, n: Int): ItemWindow {
    val s = zoom?.start ?: 0f
    val span = zoom?.span ?: 1f
    return ItemWindow(s * n, (span * n).coerceAtLeast(1f))
}

/** Axis labels below an index-based chart: all when few are visible, otherwise first/middle/last visible. */
@Composable
internal fun IndexLabels(labels: List<String>, win: ItemWindow, modifier: Modifier = Modifier) {
    if (labels.isEmpty()) return
    val visible = win.visibleIndices(labels.size)
    if (visible.isEmpty()) return
    if (visible.count() <= 12 && win.count <= 12.5f) {
        Row(modifier.fillMaxWidth()) {
            visible.forEach {
                Text(labels[it], Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    } else {
        val mid = (visible.first + visible.last) / 2
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(visible.first, mid, visible.last).distinct().forEach {
                Text(labels[it], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Hypnogram with four lanes. Tap to inspect a moment; zoomable in time. */
@Composable
fun Hypnogram(
    runs: List<StageRun>,
    startMillis: Long,
    endMillis: Long,
    modifier: Modifier = Modifier,
    description: String,
    zoom: ChartZoomState? = null,
    height: Dp = 150.dp,
) {
    var selectedTime by remember(runs) { mutableStateOf<Long?>(null) }
    val span = (endMillis - startMillis).coerceAtLeast(1L)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val cursor = MaterialTheme.colorScheme.onSurface
    val viewStart = startMillis + ((zoom?.start ?: 0f) * span).toLong()
    val viewEnd = startMillis + ((zoom?.end ?: 1f) * span).toLong()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            val t = selectedTime
            val s = t?.let { tt -> runs.firstOrNull { tt >= it.startMillis && tt < it.startMillis + it.durationMillis } }
            if (s != null && t != null) {
                Box(Modifier.size(8.dp).background(DataColors.stage(s.stage)))
                Spacer(Modifier.width(6.dp))
                Text("${Fmt.time(t)} · ${stageName(s.stage)}", style = MaterialTheme.typography.labelMedium)
            }
        }
        Row {
            Column(Modifier.height(height).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceAround) {
                listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP).forEach {
                    Text(stageName(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height(height)
                    .clipToBounds()
                    .semantics { contentDescription = description }
                    .chartZoomGestures(zoom)
                    .pointerInput(runs, zoom) {
                        detectTapGestures(
                            onTap = { pos ->
                                val f = zoom?.toData(pos.x / size.width) ?: (pos.x / size.width)
                                selectedTime = startMillis + (f * span).toLong()
                            },
                            onDoubleTap = { pos -> zoom?.toggle(pos.x / size.width) },
                        )
                    },
            ) {
                val laneH = size.height / 4f
                for (i in 1 until 4) drawLine(grid, Offset(0f, laneH * i), Offset(size.width, laneH * i), 1f)
                fun x(t: Long): Float {
                    val f = (t - startMillis).toFloat() / span
                    return (zoom?.toView(f) ?: f) * size.width
                }
                var prevLane = -1
                var prevX = 0f
                for (r in runs) {
                    val x0 = x(r.startMillis)
                    val x1 = x(r.startMillis + r.durationMillis)
                    val lane = laneOf(r.stage)
                    val color = DataColors.stage(r.stage)
                    if (x1 >= 0f && x0 <= size.width) {
                        drawRect(color, Offset(x0, lane * laneH + laneH * 0.18f), Size((x1 - x0).coerceAtLeast(1.5f), laneH * 0.64f))
                        if (prevLane >= 0 && prevLane != lane) {
                            drawLine(color.copy(alpha = 0.45f), Offset(prevX, prevLane * laneH + laneH / 2), Offset(x0, lane * laneH + laneH / 2), 1.2f)
                        }
                    }
                    prevLane = lane; prevX = x1
                }
                selectedTime?.let { drawLine(cursor.copy(alpha = 0.7f), Offset(x(it), 0f), Offset(x(it), size.height), 1.5f) }
            }
        }
        TimeAxis(viewStart, viewEnd, Modifier.padding(start = 48.dp))
    }
}

@Composable
fun TimeAxis(startMillis: Long, endMillis: Long, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val mid = startMillis + (endMillis - startMillis) / 2
        listOf(startMillis, mid, endMillis).forEach {
            Text(Fmt.time(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Thin bars of per-epoch activity. */
@Composable
fun ActivityChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = DataColors.movement,
    height: Dp = 70.dp,
    description: String,
    zoom: ChartZoomState? = null,
) {
    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    Canvas(
        modifier.fillMaxWidth().height(height).clipToBounds().semantics { contentDescription = description }
            .chartZoomGestures(zoom)
            .pointerInput(zoom) { detectTapGestures(onDoubleTap = { p -> zoom?.toggle(p.x / size.width) }) },
    ) {
        if (values.isEmpty()) return@Canvas
        val win = window(zoom, values.size)
        val w = win.slot(size.width)
        for (i in win.visibleIndices(values.size)) {
            val v = values[i]
            if (v <= 0f) continue
            val h = v / max * size.height
            drawRect(color, Offset(win.x(i, size.width), size.height - h), Size(w.coerceAtLeast(1f), h))
        }
    }
}

/** Sound events as coloured marks on the night's time line. */
@Composable
fun SoundTimeline(events: List<Pair<Long, SoundEventType>>, startMillis: Long, endMillis: Long, modifier: Modifier = Modifier, description: String) {
    val span = (endMillis - startMillis).coerceAtLeast(1L)
    val line = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = description }) {
        drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5f)
        for ((t, type) in events) {
            val x = ((t - startMillis).toFloat() / span * size.width).coerceIn(0f, size.width)
            drawLine(DataColors.sound(type), Offset(x, size.height * 0.15f), Offset(x, size.height * 0.85f), 3f, cap = StrokeCap.Butt)
        }
    }
}

/** Sound episodes as coloured bars on the night's time line (one lane per category). */
@Composable
fun EpisodeTimeline(episodes: List<de.sleeptracker.audio.SoundEpisode>, startMillis: Long, endMillis: Long, modifier: Modifier = Modifier, description: String) {
    val span = (endMillis - startMillis).coerceAtLeast(1L)
    val line = MaterialTheme.colorScheme.outlineVariant
    val lanes = de.sleeptracker.core.SoundCategory.entries
    Canvas(modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = description }) {
        val laneH = size.height / lanes.size
        lanes.indices.forEach { drawLine(line, Offset(0f, laneH * (it + 0.5f)), Offset(size.width, laneH * (it + 0.5f)), 1f) }
        for (e in episodes) {
            val x0 = ((e.startMillis - startMillis).toFloat() / span * size.width).coerceIn(0f, size.width)
            val x1 = ((e.endMillis - startMillis).toFloat() / span * size.width).coerceIn(0f, size.width)
            val lane = lanes.indexOf(e.category)
            drawRect(DataColors.sound(e.type), Offset(x0, lane * laneH + laneH * 0.15f), Size((x1 - x0).coerceAtLeast(3f), laneH * 0.7f))
        }
    }
}

data class BarEntry(val label: String, val value: Float, val display: String, val color: Color? = null)

/**
 * Interactive bar chart: tap a bar to see its value, double-tap / pinch to zoom.
 * [overlay] draws a line (e.g. rolling average) on the same scale; [onSelect] reports taps.
 */
@Composable
fun BarChart(
    entries: List<BarEntry>,
    modifier: Modifier = Modifier,
    goal: Float? = null,
    height: Dp = 160.dp,
    description: String,
    zoom: ChartZoomState? = null,
    overlay: List<Float>? = null,
    onSelect: ((Int) -> Unit)? = null,
) {
    var selected by remember(entries) { mutableStateOf<Int?>(null) }
    val barColor = MaterialTheme.colorScheme.primary
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val overlayColor = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = maxOf(entries.maxOfOrNull { it.value } ?: 0f, goal ?: 0f, overlay?.maxOrNull() ?: 0f).coerceAtLeast(1f) * 1.1f
    val win = window(zoom, entries.size)
    Column(modifier) {
        Text(
            selected?.let { entries.getOrNull(it) }?.let { "${it.label}: ${it.display}" } ?: " ",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clipToBounds()
                .semantics { contentDescription = description }
                .chartZoomGestures(zoom)
                .pointerInput(entries, zoom) {
                    detectTapGestures(
                        onTap = { pos ->
                            if (entries.isNotEmpty()) {
                                val i = window(zoom, entries.size).indexAt(pos.x, size.width.toFloat(), entries.size)
                                selected = i
                                onSelect?.invoke(i)
                            }
                        },
                        onDoubleTap = { pos -> zoom?.toggle(pos.x / size.width) },
                    )
                },
        ) {
            for (k in 1..3) drawLine(grid, Offset(0f, size.height * k / 4f), Offset(size.width, size.height * k / 4f), 1f)
            if (entries.isEmpty()) return@Canvas
            val slot = win.slot(size.width)
            // Width capped so few bars do not become blocks; edges snapped to whole pixels.
            val bw = minOf(slot * 0.55f, 22.dp.toPx()).coerceAtLeast(1.5f)
            for (i in win.visibleIndices(entries.size)) {
                val e = entries[i]
                val h = e.value / max * size.height
                val c = e.color ?: barColor
                val center = win.x(i, size.width) + slot / 2
                val left = (center - bw / 2).roundToInt().toFloat()
                val right = (center + bw / 2).roundToInt().toFloat()
                drawRoundRect(
                    color = if (selected == null || selected == i) c else c.copy(alpha = 0.35f),
                    topLeft = Offset(left, (size.height - h).roundToInt().toFloat()),
                    size = Size((right - left).coerceAtLeast(1f), h),
                    cornerRadius = CornerRadius(minOf(bw / 6, 3f)),
                )
            }
            overlay?.let { values ->
                val path = Path()
                var started = false
                for (i in win.visibleIndices(values.size)) {
                    val x = win.x(i, size.width) + slot / 2
                    val y = size.height - values[i] / max * size.height
                    if (!started) path.moveTo(x, y) else path.lineTo(x, y)
                    started = true
                }
                drawPath(path, overlayColor, style = Stroke(width = 2f))
            }
            goal?.let {
                val y = size.height - it / max * size.height
                drawLine(goalColor, Offset(0f, y), Offset(size.width, y), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
        IndexLabels(entries.map { it.label }, win)
    }
}

/** Circular score indicator (1–100) with a flat cap. */
@Composable
fun ScoreRing(score: Int, modifier: Modifier = Modifier, size: Dp = 120.dp, label: String) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = MaterialTheme.colorScheme.primary
    Box(modifier.size(size).semantics { contentDescription = "$label $score" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.07f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            drawArc(color, 135f, 270f * score.coerceIn(0, 100) / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Stacked horizontal bar of the phase distribution with legend. */
@Composable
fun PhaseBar(awake: Int, light: Int, deep: Int, rem: Int, modifier: Modifier = Modifier, legend: @Composable (SleepStage, Int) -> String) {
    val total = (awake + light + deep + rem).coerceAtLeast(1)
    val parts = listOf(SleepStage.AWAKE to awake, SleepStage.REM to rem, SleepStage.LIGHT to light, SleepStage.DEEP to deep)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(12.dp)) {
            parts.forEach { (stage, v) ->
                if (v > 0) Box(Modifier.weight(v.toFloat()).height(12.dp).background(DataColors.stage(stage), RectangleShape))
            }
            if (awake + light + deep + rem == 0) Box(Modifier.weight(1f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Spacer(Modifier.height(8.dp))
        parts.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (stage, v) ->
                    Row(Modifier.weight(1f).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(DataColors.stage(stage)))
                        Spacer(Modifier.width(6.dp))
                        Text("${legend(stage, v)} · ${v * 100 / total} %", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
