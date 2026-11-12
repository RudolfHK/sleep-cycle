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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.stageName
import de.sleeptracker.app.ui.theme.DataColors
import de.sleeptracker.core.SleepStage
import de.sleeptracker.core.SoundEventType

data class StageRun(val startMillis: Long, val durationMillis: Long, val stage: SleepStage)

private fun laneOf(stage: SleepStage) = when (stage) {
    SleepStage.AWAKE -> 0
    SleepStage.REM -> 1
    SleepStage.LIGHT -> 2
    SleepStage.DEEP -> 3
}

/** Hypnogram with four lanes. Tap to inspect a moment of the night. */
@Composable
fun Hypnogram(runs: List<StageRun>, startMillis: Long, endMillis: Long, modifier: Modifier = Modifier, description: String) {
    var selected by remember(runs) { mutableStateOf<StageRun?>(null) }
    var selectedTime by remember(runs) { mutableStateOf<Long?>(null) }
    val span = (endMillis - startMillis).coerceAtLeast(1L)
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            val s = selected
            val t = selectedTime
            if (s != null && t != null) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(DataColors.stage(s.stage)))
                Spacer(Modifier.width(6.dp))
                Text("${Fmt.time(t)} · ${stageName(s.stage)}", style = MaterialTheme.typography.labelMedium)
            }
        }
        Row {
            Column(Modifier.height(140.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceAround) {
                listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP).forEach {
                    Text(stageName(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height(140.dp)
                    .semantics { contentDescription = description }
                    .pointerInput(runs) {
                        detectTapGestures { pos ->
                            val t = startMillis + (pos.x / size.width * span).toLong()
                            selectedTime = t
                            selected = runs.firstOrNull { t >= it.startMillis && t < it.startMillis + it.durationMillis }
                        }
                    },
            ) {
                val laneH = size.height / 4f
                for (i in 1 until 4) drawLine(grid, Offset(0f, laneH * i), Offset(size.width, laneH * i), 1f)
                var prevLane = -1
                var prevX = 0f
                for (r in runs) {
                    val x0 = ((r.startMillis - startMillis).toFloat() / span * size.width).coerceIn(0f, size.width)
                    val x1 = ((r.startMillis + r.durationMillis - startMillis).toFloat() / span * size.width).coerceIn(0f, size.width)
                    val lane = laneOf(r.stage)
                    val color = DataColors.stage(r.stage)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x0, lane * laneH + laneH * 0.15f),
                        size = Size((x1 - x0).coerceAtLeast(1.5f), laneH * 0.7f),
                        cornerRadius = CornerRadius(4f, 4f),
                    )
                    if (prevLane >= 0 && prevLane != lane) {
                        drawLine(
                            color.copy(alpha = 0.5f),
                            Offset(prevX, prevLane * laneH + laneH / 2), Offset(x0, lane * laneH + laneH / 2), 1.5f,
                        )
                    }
                    prevLane = lane; prevX = x1
                }
                selectedTime?.let {
                    val x = (it - startMillis).toFloat() / span * size.width
                    drawLine(Color.White.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, size.height), 2f)
                }
            }
        }
        TimeAxis(startMillis, endMillis, Modifier.padding(start = 48.dp))
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
fun ActivityChart(values: List<Float>, modifier: Modifier = Modifier, color: Color = DataColors.movement, height: Dp = 70.dp, description: String) {
    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val w = size.width / values.size
        values.forEachIndexed { i, v ->
            if (v <= 0f) return@forEachIndexed
            val h = v / max * size.height
            drawRect(color, Offset(i * w, size.height - h), Size(w.coerceAtLeast(1f), h))
        }
    }
}

/** Sound events as coloured marks on the night's time line. */
@Composable
fun SoundTimeline(events: List<Pair<Long, SoundEventType>>, startMillis: Long, endMillis: Long, modifier: Modifier = Modifier, description: String) {
    val span = (endMillis - startMillis).coerceAtLeast(1L)
    val line = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Canvas(modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = description }) {
        drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
        for ((t, type) in events) {
            val x = ((t - startMillis).toFloat() / span * size.width).coerceIn(0f, size.width)
            drawLine(DataColors.sound(type), Offset(x, size.height * 0.15f), Offset(x, size.height * 0.85f), 4f, cap = StrokeCap.Round)
        }
    }
}

data class BarEntry(val label: String, val value: Float, val display: String, val color: Color? = null)

/** Interactive bar chart: tap a bar to see its value. */
@Composable
fun BarChart(
    entries: List<BarEntry>,
    modifier: Modifier = Modifier,
    goal: Float? = null,
    height: Dp = 160.dp,
    description: String,
) {
    var selected by remember(entries) { mutableStateOf<Int?>(null) }
    val barColor = MaterialTheme.colorScheme.primary
    val goalColor = MaterialTheme.colorScheme.secondary
    val max = maxOf(entries.maxOfOrNull { it.value } ?: 0f, goal ?: 0f).coerceAtLeast(1f) * 1.1f
    Column(modifier) {
        Text(
            selected?.let { "${entries[it].label}: ${entries[it].display}" } ?: " ",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .pointerInput(entries) {
                    detectTapGestures { pos ->
                        if (entries.isNotEmpty()) selected = (pos.x / size.width * entries.size).toInt().coerceIn(0, entries.size - 1)
                    }
                },
        ) {
            if (entries.isEmpty()) return@Canvas
            val slot = size.width / entries.size
            val bw = (slot * 0.65f).coerceAtLeast(2f)
            entries.forEachIndexed { i, e ->
                val h = e.value / max * size.height
                val c = e.color ?: barColor
                drawRoundRect(
                    color = if (selected == null || selected == i) c else c.copy(alpha = 0.4f),
                    topLeft = Offset(i * slot + (slot - bw) / 2, size.height - h),
                    size = Size(bw, h),
                    cornerRadius = CornerRadius(bw / 4, bw / 4),
                )
            }
            goal?.let {
                val y = size.height - it / max * size.height
                drawLine(goalColor, Offset(0f, y), Offset(size.width, y), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
        if (entries.size <= 12) {
            Row(Modifier.fillMaxWidth()) {
                entries.forEach {
                    Text(
                        it.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        } else if (entries.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(entries.first().label, style = MaterialTheme.typography.labelSmall)
                Text(entries.last().label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Circular score indicator (1–100). */
@Composable
fun ScoreRing(score: Int, modifier: Modifier = Modifier, size: Dp = 140.dp, label: String) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = DataColors.score(score)
    Box(modifier.size(size).semantics { contentDescription = "$label $score" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.09f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * score.coerceIn(0, 100) / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Stacked horizontal bar of the phase distribution with legend. */
@Composable
fun PhaseBar(awake: Int, light: Int, deep: Int, rem: Int, modifier: Modifier = Modifier, legend: @Composable (SleepStage, Int) -> String) {
    val total = (awake + light + deep + rem).coerceAtLeast(1)
    val parts = listOf(SleepStage.AWAKE to awake, SleepStage.REM to rem, SleepStage.LIGHT to light, SleepStage.DEEP to deep)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
            parts.forEach { (stage, v) ->
                if (v > 0) Box(Modifier.weight(v.toFloat()).height(16.dp).background(DataColors.stage(stage)))
            }
            if (awake + light + deep + rem == 0) Box(Modifier.weight(1f).height(16.dp).background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Spacer(Modifier.height(8.dp))
        parts.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (stage, v) ->
                    Row(Modifier.weight(1f).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(DataColors.stage(stage)))
                        Spacer(Modifier.width(6.dp))
                        Text("${legend(stage, v)} (${v * 100 / total} %)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
