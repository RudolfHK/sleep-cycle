package de.sleeptracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.drawText
import de.sleeptracker.analysis.NightProfile
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.stageName
import de.sleeptracker.app.ui.theme.DataColors
import de.sleeptracker.audio.SoundEpisode
import de.sleeptracker.core.SleepStage
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** Colours for the environment layers, shared by all night charts and legends. */
object LayerColors {
    val noise = Color(0xFFCFC3B6)
    val light = DataColors.light
    val movement = DataColors.movement
}

enum class NightLayer { PHASES, NOISE, LIGHT, MOVEMENT, SOUNDS }

// --- Legend & description ------------------------------------------------------------------

enum class LegendKind { SWATCH, LINE, AREA, DASH }

data class LegendItem(val color: Color, val label: String, val kind: LegendKind = LegendKind.SWATCH)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(items: List<LegendItem>, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
                    when (item.kind) {
                        LegendKind.SWATCH -> drawRect(item.color)
                        LegendKind.AREA -> {
                            drawRect(item.color.copy(alpha = 0.35f), Offset(0f, size.height * 0.3f), Size(size.width, size.height * 0.7f))
                            drawLine(item.color, Offset(0f, size.height * 0.3f), Offset(size.width, size.height * 0.3f), 2f)
                        }
                        LegendKind.LINE -> drawLine(item.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3f)
                        LegendKind.DASH -> drawLine(item.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)))
                    }
                }
                Spacer(Modifier.width(5.dp))
                Text(item.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One-sentence explanation of what a chart shows and how to read it. */
@Composable
fun ChartDescription(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
}

/** Read-out line shown after tapping a chart. */
@Composable
fun ReadoutBar(text: String?) {
    Box(
        Modifier.fillMaxWidth().height(24.dp).background(if (text != null) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text != null) Text(text, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun LayerChips(available: List<NightLayer>, enabled: Set<NightLayer>, label: @Composable (NightLayer) -> String, onToggle: (NightLayer) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        available.forEach { l ->
            FilterChip(
                selected = l in enabled,
                onClick = { onToggle(l) },
                label = { Text(label(l), fontSize = 12.sp, maxLines = 1) },
                shape = MaterialTheme.shapes.extraSmall,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

// --- Night overview (single night, all layers overlaid) ---------------------------------------

data class NightOverviewData(
    val startMillis: Long,
    val endMillis: Long,
    val onsetMillis: Long?,
    val runs: List<StageRun>,
    /** Epoch start times (30 s). */
    val epochTimes: List<Long>,
    /** Estimated dB per epoch (NaN = no data). */
    val noiseDb: List<Float>,
    /** Lux per epoch (NaN = no data). */
    val lux: List<Float>,
    val movement: List<Float>,
    val episodes: List<SoundEpisode>,
) {
    val hasNoise = noiseDb.any { !it.isNaN() }
    val hasLight = lux.any { !it.isNaN() }
    val hasMovement = movement.any { it > 0f }
}

private fun laneY(stage: SleepStage, h: Float): Float {
    val lane = when (stage) { SleepStage.AWAKE -> 0; SleepStage.REM -> 1; SleepStage.LIGHT -> 2; SleepStage.DEEP -> 3 }
    return h * (lane + 0.5f) / 4f
}

private fun luxNorm(lux: Float, maxLux: Float) = (log10(1f + lux) / log10(1f + maxLux)).coerceIn(0f, 1f)

/**
 * Overlay of the whole night: stage bands + stepped hypnogram, noise area and light line on one
 * time axis, with movement and sound-episode tracks below. Tap anywhere for a read-out of all
 * layers at that minute.
 */
@Composable
fun NightOverviewChart(
    data: NightOverviewData,
    layers: Set<NightLayer>,
    zoom: ChartZoomState,
    readout: @Composable (time: Long, stage: SleepStage?, db: Float?, lux: Float?, movement: Float?) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    description: String,
) {
    var tapTime by remember(data) { mutableStateOf<Long?>(null) }
    val span = (data.endMillis - data.startMillis).coerceAtLeast(1L)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val validDb = data.noiseDb.filter { !it.isNaN() }
    val dbMin = (validDb.minOrNull() ?: 0f)
    val dbMax = maxOf(validDb.maxOrNull() ?: 1f, dbMin + 10f)
    val maxLux = maxOf(data.lux.filter { !it.isNaN() }.maxOrNull() ?: 10f, 10f)
    val maxMove = maxOf(data.movement.maxOrNull() ?: 1f, 1f)

    fun xOf(t: Long, width: Float): Float = zoom.toView((t - data.startMillis).toFloat() / span) * width
    fun epochIndex(t: Long): Int? {
        if (data.epochTimes.isEmpty()) return null
        val i = data.epochTimes.binarySearch(t).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
        return i.coerceIn(0, data.epochTimes.size - 1)
    }

    val tapModifier = Modifier
        .chartZoomGestures(zoom)
        .pointerInput(data, zoom) {
            detectTapGestures(
                onTap = { p -> tapTime = data.startMillis + (zoom.toData(p.x / size.width) * span).toLong() },
                onDoubleTap = { p -> zoom.toggle(p.x / size.width) },
            )
        }

    Column(modifier) {
        val t = tapTime
        ReadoutBar(
            t?.let {
                val i = epochIndex(it)
                readout(
                    it,
                    data.runs.firstOrNull { r -> it >= r.startMillis && it < r.startMillis + r.durationMillis }?.stage,
                    i?.let { idx -> data.noiseDb.getOrNull(idx)?.takeIf { v -> !v.isNaN() } },
                    i?.let { idx -> data.lux.getOrNull(idx)?.takeIf { v -> !v.isNaN() } },
                    i?.let { idx -> data.movement.getOrNull(idx) },
                )
            },
        )
        // Marker row: bedtime, fell asleep, wake-up.
        Canvas(Modifier.fillMaxWidth().height(10.dp).clipToBounds()) {
            fun mark(time: Long, c: Color) {
                val x = xOf(time, size.width)
                if (x in -2f..size.width + 2f) drawRect(c, Offset(x - 1.5f, 0f), Size(3f, size.height))
            }
            mark(data.startMillis, ink.copy(alpha = 0.6f))
            data.onsetMillis?.let { mark(it, DataColors.stage(SleepStage.LIGHT)) }
            mark(data.endMillis, DataColors.stage(SleepStage.AWAKE))
        }
        Canvas(
            Modifier.fillMaxWidth().height(height).clipToBounds().semantics { contentDescription = description }.then(tapModifier),
        ) {
            val w = size.width
            val h = size.height
            for (k in 1..3) drawLine(grid, Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1f)
            hourGrid(data.startMillis, data.endMillis, grid) { xOf(it, w) }
            if (NightLayer.PHASES in layers) {
                // Background bands for the stage, then the stepped hypnogram.
                for (r in data.runs) {
                    val x0 = xOf(r.startMillis, w)
                    val x1 = xOf(r.startMillis + r.durationMillis, w)
                    if (x1 < 0 || x0 > w) continue
                    drawRect(DataColors.stage(r.stage).copy(alpha = 0.16f), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(1f), h))
                }
                val path = Path()
                data.runs.forEachIndexed { i, r ->
                    val x0 = xOf(r.startMillis, w)
                    val x1 = xOf(r.startMillis + r.durationMillis, w)
                    val y = laneY(r.stage, h)
                    if (i == 0) path.moveTo(x0, y) else path.lineTo(x0, y)
                    path.lineTo(x1, y)
                }
                drawPath(path, ink.copy(alpha = 0.85f), style = Stroke(width = 2f))
            }
            if (NightLayer.NOISE in layers && data.hasNoise) {
                drawSeries(data.epochTimes, data.noiseDb, { v -> (v - dbMin) / (dbMax - dbMin) }, LayerColors.noise, filled = true) { xOf(it, w) }
            }
            if (NightLayer.LIGHT in layers && data.hasLight) {
                drawSeries(data.epochTimes, data.lux, { v -> luxNorm(v, maxLux) }, LayerColors.light, filled = false) { xOf(it, w) }
            }
            tapTime?.let {
                val x = xOf(it, w)
                drawLine(ink, Offset(x, 0f), Offset(x, h), 1.5f)
            }
        }
        if (NightLayer.MOVEMENT in layers && data.hasMovement) {
            Canvas(Modifier.fillMaxWidth().height(28.dp).padding(top = 4.dp).clipToBounds().then(tapModifier)) {
                val w = size.width
                val bw = (w / (data.epochTimes.size.coerceAtLeast(1) * zoom.span)).coerceAtLeast(1f)
                data.epochTimes.forEachIndexed { i, t ->
                    val v = data.movement.getOrNull(i) ?: 0f
                    if (v <= 0f) return@forEachIndexed
                    val x = xOf(t, w)
                    if (x < -bw || x > w) return@forEachIndexed
                    val bh = v / maxMove * size.height
                    drawRect(LayerColors.movement, Offset(x, size.height - bh), Size(bw, bh))
                }
            }
        }
        if (NightLayer.SOUNDS in layers && data.episodes.isNotEmpty()) {
            Canvas(Modifier.fillMaxWidth().height(14.dp).padding(top = 3.dp).clipToBounds().then(tapModifier)) {
                val w = size.width
                for (e in data.episodes) {
                    val x0 = xOf(e.startMillis, w)
                    val x1 = xOf(e.endMillis, w)
                    if (x1 < 0 || x0 > w) continue
                    drawRect(DataColors.sound(e.type), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(3f), size.height))
                }
            }
        }
        val viewStart = data.startMillis + (zoom.start * span).toLong()
        val viewEnd = data.startMillis + (zoom.end * span).toLong()
        TimeAxis(viewStart, viewEnd)
    }
}

private fun DrawScope.hourGrid(start: Long, end: Long, color: Color, x: (Long) -> Float) {
    var t = (start / 3_600_000L + 1) * 3_600_000L
    while (t < end) {
        val px = x(t)
        if (px in 0f..size.width) drawLine(color, Offset(px, 0f), Offset(px, size.height), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 6f)))
        t += 3_600_000L
    }
}

/** Line or filled area of a series normalised to 0..1, with gaps for NaN. */
private fun DrawScope.drawSeries(
    times: List<Long>,
    values: List<Float>,
    norm: (Float) -> Float,
    color: Color,
    filled: Boolean,
    x: (Long) -> Float,
) {
    val h = size.height
    var segment = ArrayList<Offset>()
    fun flush() {
        if (segment.size >= 2) {
            val line = splinePath(segment)
            if (filled) {
                val area = splinePath(segment)
                area.lineTo(segment.last().x, h); area.lineTo(segment.first().x, h); area.close()
                drawPath(area, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.04f))))
            }
            drawPath(line, color, style = Stroke(width = if (filled) 1.5f else 2.2f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        segment = ArrayList()
    }
    for (i in times.indices) {
        val v = values.getOrNull(i) ?: Float.NaN
        if (v.isNaN()) { flush(); continue }
        segment += Offset(x(times[i]), h - norm(v).coerceIn(0f, 1f) * h * 0.92f)
    }
    flush()
}

// --- Phases per night (stacked) ----------------------------------------------------------------

data class PhaseNight(
    val label: String,
    val awake: Int,
    val rem: Int,
    val light: Int,
    val deep: Int,
    /** Estimated dB, null = no data. */
    val noiseDb: Float?,
    /** 0..100, null = no data. */
    val darkPercent: Float?,
    val hasPhases: Boolean,
)

/** Stacked minutes per stage for each night; optional overlay lines for noise and darkness. */
@Composable
fun PhaseStackChart(
    nights: List<PhaseNight>,
    showNoise: Boolean,
    showDark: Boolean,
    zoom: ChartZoomState,
    readout: @Composable (PhaseNight) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    description: String,
) {
    var selected by remember(nights) { mutableStateOf<Int?>(null) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val maxMin = (nights.maxOfOrNull { it.awake + it.rem + it.light + it.deep } ?: 60).coerceAtLeast(60) * 1.08f
    val dbs = nights.mapNotNull { it.noiseDb }
    val dbMin = dbs.minOrNull() ?: 0f
    val dbMax = maxOf(dbs.maxOrNull() ?: 1f, dbMin + 6f)
    val win = window(zoom, nights.size)
    Column(modifier) {
        ReadoutBar(selected?.let { nights.getOrNull(it) }?.let { readout(it) })
        Canvas(
            Modifier.fillMaxWidth().height(height).clipToBounds().semantics { contentDescription = description }
                .chartZoomGestures(zoom)
                .pointerInput(nights, zoom) {
                    detectTapGestures(
                        onTap = { p -> if (nights.isNotEmpty()) selected = window(zoom, nights.size).indexAt(p.x, size.width.toFloat(), nights.size) },
                        onDoubleTap = { p -> zoom.toggle(p.x / size.width) },
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            // Hour grid lines.
            var hr = 60
            while (hr < maxMin) { val y = h - hr / maxMin * h; drawLine(grid, Offset(0f, y), Offset(w, y), 1f); hr += 120 }
            if (nights.isEmpty()) return@Canvas
            val slot = win.slot(w)
            val bw = minOf(slot * 0.6f, 24.dp.toPx()).coerceAtLeast(1.5f)
            for (i in win.visibleIndices(nights.size)) {
                val n = nights[i]
                val x = (win.x(i, w) + (slot - bw) / 2).roundToInt().toFloat()
                val alpha = if (selected == null || selected == i) 1f else 0.4f
                var y = h
                if (!n.hasPhases) {
                    // Imported night: total sleep only, drawn neutral.
                    val total = n.light + n.rem + n.deep
                    val bh = total / maxMin * h
                    drawRect(empty.copy(alpha = alpha), Offset(x, y - bh), Size(bw, bh))
                    y -= bh
                    val ah = n.awake / maxMin * h
                    drawRect(DataColors.stage(SleepStage.AWAKE).copy(alpha = 0.5f * alpha), Offset(x, y - ah), Size(bw, ah))
                    continue
                }
                for ((stage, minutes) in listOf(SleepStage.DEEP to n.deep, SleepStage.LIGHT to n.light, SleepStage.REM to n.rem, SleepStage.AWAKE to n.awake)) {
                    val bh = minutes / maxMin * h
                    drawRect(DataColors.stage(stage).copy(alpha = alpha), Offset(x, y - bh), Size(bw, bh))
                    y -= bh
                }
            }
            fun overlay(values: List<Float?>, norm: (Float) -> Float, color: Color) {
                val vis = win.visibleIndices(nights.size)
                val pts = vis.map { i -> values[i]?.let { Offset(win.x(i, w) + slot / 2, h - norm(it).coerceIn(0f, 1f) * h * 0.95f) } }
                smoothSegments(pts).forEach { seg ->
                    if (seg.size >= 2) drawPath(splinePath(seg), color, style = Stroke(2.2f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    seg.forEach { drawCircle(color, 2.8f, it) }
                }
            }
            if (showNoise) overlay(nights.map { it.noiseDb }, { (it - dbMin) / (dbMax - dbMin) }, LayerColors.noise)
            if (showDark) overlay(nights.map { it.darkPercent }, { it / 100f }, LayerColors.light)
        }
        IndexLabels(nights.map { it.label }, win)
    }
}

// --- Typical night profile ---------------------------------------------------------------------

/**
 * 100 % stacked area of the stage share per clock-time slot ("hypnodensity"), with average noise
 * and light as overlay lines and the in-bed share as a faint ceiling.
 */
@Composable
fun ProfileChart(
    profile: NightProfile,
    showNoise: Boolean,
    showLight: Boolean,
    readout: @Composable (de.sleeptracker.analysis.ProfileSlot) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    description: String,
) {
    var selected by remember(profile) { mutableStateOf<Int?>(null) }
    val slots = profile.slots
    val ink = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dbs = slots.mapNotNull { it.noiseDb }
    val dbMin = dbs.minOrNull() ?: 0f
    val dbMax = maxOf(dbs.maxOrNull() ?: 1f, dbMin + 6f)
    val maxLux = maxOf(slots.mapNotNull { it.lux }.maxOrNull() ?: 10f, 10f)
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val axisStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = muted)
    val axisPx = with(androidx.compose.ui.platform.LocalDensity.current) { 30.dp.toPx() }
    val bottomPx = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }
    Column(modifier) {
        ReadoutBar(selected?.let { slots.getOrNull(it) }?.let { readout(it) })
        Canvas(
            Modifier.fillMaxWidth().height(height + 14.dp).clipToBounds().semantics { contentDescription = description }
                .pointerInput(profile) {
                    detectTapGestures { p ->
                        if (slots.isNotEmpty()) {
                            val f = ((p.x - axisPx) / (size.width - axisPx)).coerceIn(0f, 0.9999f)
                            selected = (f * slots.size).toInt()
                        }
                    }
                },
        ) {
            if (slots.isEmpty()) return@Canvas
            val w = size.width - axisPx
            val h = size.height - bottomPx
            val sw = w / slots.size
            fun xOf(i: Int) = axisPx + i * sw + sw / 2
            fun yOf(f: Float) = h - f.coerceIn(0f, 1f) * h

            // Y grid: share of all nights in bed.
            for (k in 0..2) {
                val f = k / 2f
                drawLine(grid, Offset(axisPx, yOf(f)), Offset(size.width, yOf(f)), 1f)
                val t = textMeasurer.measure("${(f * 100).roundToInt()}%", axisStyle)
                drawText(t, topLeft = Offset(axisPx - t.size.width - 4f, (yOf(f) - t.size.height / 2f).coerceIn(0f, h - t.size.height)))
            }

            // Stacked areas. Boundaries are sampled densely from monotone splines and clamped so
            // that each boundary is never below the one under it: bands cannot cross or overlap.
            val order = listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE)
            val nodeX = FloatArray(slots.size + 2)
            nodeX[0] = axisPx
            slots.indices.forEach { nodeX[it + 1] = xOf(it) }
            nodeX[slots.size + 1] = size.width
            val step = 2f
            val sampleCount = ((size.width - axisPx) / step).toInt().coerceAtLeast(2) + 1
            val sx = FloatArray(sampleCount) { axisPx + it * (size.width - axisPx) / (sampleCount - 1) }
            var lower = FloatArray(sampleCount)
            order.forEachIndexed { k, st ->
                val nodes = FloatArray(slots.size + 2)
                slots.forEachIndexed { i, s ->
                    var acc = 0f
                    for (q in 0..k) acc += (s.share[order[q]] ?: 0f)
                    nodes[i + 1] = acc * s.coverage
                }
                nodes[0] = nodes[1]
                nodes[slots.size + 1] = nodes[slots.size]
                val upper = de.sleeptracker.analysis.MonotoneSpline.sample(nodeX, nodes, sx)
                for (j in upper.indices) upper[j] = upper[j].coerceIn(lower[j], 1f)
                val path = Path()
                path.moveTo(sx[0], yOf(upper[0]))
                for (j in 1 until sampleCount) path.lineTo(sx[j], yOf(upper[j]))
                for (j in sampleCount - 1 downTo 0) path.lineTo(sx[j], yOf(lower[j]))
                path.close()
                drawPath(path, DataColors.stage(st).copy(alpha = 0.85f))
                lower = upper
            }

            // Hour grid and labels every 2 hours.
            slots.forEachIndexed { i, s ->
                if (s.startMinute % 120 == 0) {
                    val x = axisPx + i * sw
                    drawLine(grid.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, h), 1f)
                    val t = textMeasurer.measure(Fmt.minuteOfDay((s.startMinute + 18 * 60) % 1440), axisStyle)
                    drawText(t, topLeft = Offset((x - t.size.width / 2f).coerceIn(axisPx, size.width - t.size.width), h + 2f))
                }
            }
            fun line(values: List<Float?>, norm: (Float) -> Float, color: Color) {
                val pts = values.mapIndexed { i, v -> v?.let { Offset(xOf(i), h - norm(it).coerceIn(0f, 1f) * h * 0.95f) } }
                smoothSegments(pts).forEach { seg ->
                    if (seg.size >= 2) drawPath(splinePath(seg), color, style = Stroke(2.4f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
            if (showNoise) line(slots.map { it.noiseDb }, { (it - dbMin) / (dbMax - dbMin) }, LayerColors.noise)
            if (showLight) line(slots.map { it.lux }, { luxNorm(it, maxLux) }, LayerColors.light)
            selected?.let {
                drawRect(Color.White.copy(alpha = 0.07f), Offset(axisPx + it * sw, 0f), Size(sw, h))
                drawLine(ink, Offset(xOf(it), 0f), Offset(xOf(it), h), 1.5f)
            }
        }
    }
}

/** Standard legend entries for the four stages. */
@Composable
fun stageLegend(): List<LegendItem> = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP).map {
    LegendItem(DataColors.stage(it), stageName(it))
}

/** Whether two floats are practically equal (for read-outs). */
internal fun Float.near(o: Float) = abs(this - o) < 1e-3f
