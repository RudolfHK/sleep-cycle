package de.sleeptracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.sleeptracker.analysis.MonotoneSpline
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Builds smooth (monotone cubic) path segments through [points]; gaps are separate segments. */
fun smoothSegments(points: List<Offset?>): List<List<Offset>> {
    val out = ArrayList<List<Offset>>()
    var cur = ArrayList<Offset>()
    for (p in points) {
        if (p == null) { if (cur.isNotEmpty()) out += cur; cur = ArrayList() } else cur += p
    }
    if (cur.isNotEmpty()) out += cur
    return out
}

/** Path through [pts] using Fritsch–Carlson control points (no overshoot). */
fun splinePath(pts: List<Offset>, path: Path = Path(), moveFirst: Boolean = true): Path {
    if (pts.isEmpty()) return path
    if (moveFirst) path.moveTo(pts[0].x, pts[0].y) else path.lineTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return path
    val xs = FloatArray(pts.size) { pts[it].x }
    val ys = FloatArray(pts.size) { pts[it].y }
    // x must strictly increase; nudge duplicates (can happen with extreme zoom rounding).
    for (i in 1 until xs.size) if (xs[i] <= xs[i - 1]) xs[i] = xs[i - 1] + 0.01f
    MonotoneSpline.controlPoints(xs, ys).forEachIndexed { i, c -> path.cubicTo(c[0], c[1], c[2], c[3], xs[i + 1], ys[i + 1]) }
    return path
}

/** "Nice" axis maximum (1, 2, 2.5, 5 × 10ⁿ). */
fun niceCeil(v: Float): Float {
    if (v <= 0f) return 1f
    val mag = 10.0.pow(floor(log10(v.toDouble()))).toFloat()
    val f = v / mag
    val nice = listOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f).first { it >= f - 1e-6f }
    return nice * mag
}

data class TrendLine(val values: List<Float?>, val color: Color, val width: Float = 1.6f, val dashed: Boolean = false)

/**
 * Smooth trend chart: faint capped background bars, a monotone spline with gradient fill, optional
 * secondary splines, an optional filled band to an upper series, reference line, y-axis grid with
 * labels, tap read-out and zoom.
 */
@Composable
fun TrendChart(
    labels: List<String>,
    values: List<Float?>,
    color: Color,
    format: (Float) -> String,
    readout: (Int) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
    description: String,
    zoom: ChartZoomState? = null,
    showBars: Boolean = true,
    barColors: List<Color?>? = null,
    secondary: List<TrendLine> = emptyList(),
    /** Upper series: the area between it and [values] is filled (e.g. time in bed above asleep). */
    bandUpper: List<Float?>? = null,
    bandColor: Color = color,
    reference: Float? = null,
    referenceLabel: String? = null,
    /** Fixed axis range; null = from 0 (or from the data minimum when [includeZero] is false). */
    yRange: ClosedFloatingPointRange<Float>? = null,
    includeZero: Boolean = true,
    onSelect: ((Int) -> Unit)? = null,
) {
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val n = values.size
    val all = values.filterNotNull() + (bandUpper?.filterNotNull() ?: emptyList()) +
        secondary.flatMap { it.values.filterNotNull() } + listOfNotNull(reference)
    val (yMin, yMax) = when {
        yRange != null -> yRange.start to yRange.endInclusive
        includeZero -> 0f to niceCeil((all.maxOrNull() ?: 1f) * 1.08f)
        else -> {
            val lo = all.minOrNull() ?: 0f
            val hi = all.maxOrNull() ?: 1f
            val pad = maxOf((hi - lo) * 0.15f, 1f)
            floor(lo - pad) to ceil(hi + pad)
        }
    }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 9.sp, color = muted)
    val density = LocalDensity.current
    val maxBarPx = with(density) { 22.dp.toPx() }
    val axisPx = with(density) { 30.dp.toPx() }

    Column(modifier) {
        ReadoutBar(selected?.takeIf { it < n }?.let(readout))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clipToBounds()
                .semantics { contentDescription = description }
                .chartZoomGestures(zoom)
                .pointerInput(values, zoom) {
                    detectTapGestures(
                        onTap = { p ->
                            if (n == 0) return@detectTapGestures
                            val plotW = size.width - axisPx
                            val i = window(zoom, n).indexAt((p.x - axisPx).coerceAtLeast(0f), plotW, n)
                            selected = i
                            onSelect?.invoke(i)
                        },
                        onDoubleTap = { p -> zoom?.toggle(((p.x - axisPx) / (size.width - axisPx)).coerceIn(0f, 1f)) },
                    )
                },
        ) {
            val plotW = size.width - axisPx
            val top = 6f
            val h = size.height - top - 2f
            fun y(v: Float) = top + h - ((v - yMin) / (yMax - yMin)).coerceIn(-0.05f, 1.05f) * h

            // Grid with y labels.
            for (k in 0..3) {
                val v = yMin + (yMax - yMin) * k / 3f
                val gy = y(v)
                drawLine(grid, Offset(axisPx, gy), Offset(size.width, gy), 1f)
                val t = textMeasurer.measure(format(v), axisStyle)
                drawText(t, topLeft = Offset(axisPx - t.size.width - 4f, (gy - t.size.height / 2f).coerceIn(0f, size.height - t.size.height)))
            }
            if (n == 0) return@Canvas
            val win = window(zoom, n)
            val slot = win.slot(plotW)
            fun cx(i: Int) = axisPx + win.x(i, plotW) + slot / 2
            val visible = win.visibleIndices(n)
            // Points just outside the view keep the curve continuous at the edges.
            val drawRange = (visible.first - 1).coerceAtLeast(0)..(visible.last + 1).coerceAtMost(n - 1)

            if (showBars) {
                val bw = minOf(slot * 0.55f, maxBarPx).coerceAtLeast(1f)
                for (i in visible) {
                    val v = values[i] ?: continue
                    val left = (cx(i) - bw / 2).roundToInt().toFloat()
                    val right = (cx(i) + bw / 2).roundToInt().toFloat()
                    val yt = y(v).roundToInt().toFloat()
                    val c = barColors?.getOrNull(i) ?: color
                    val alpha = if (selected == null || selected == i) 0.16f else 0.08f
                    drawRoundRect(c.copy(alpha = alpha), Offset(left, yt), Size((right - left).coerceAtLeast(1f), (y(yMin) - yt).coerceAtLeast(0f)), CornerRadius(3f))
                }
            }

            fun pts(series: List<Float?>) = drawRange.map { i -> series.getOrNull(i)?.let { Offset(cx(i), y(it)) } }

            bandUpper?.let { upper ->
                // Fill between upper and lower splines, per continuous segment.
                val lower = pts(values)
                val up = pts(upper)
                val pairs = lower.indices.map { k -> if (lower[k] != null && up[k] != null) lower[k]!! to up[k]!! else null }
                var seg = ArrayList<Pair<Offset, Offset>>()
                fun flush() {
                    if (seg.size >= 2) {
                        val path = splinePath(seg.map { it.second })
                        splinePath(seg.map { it.first }.reversed(), path, moveFirst = false)
                        path.close()
                        drawPath(path, bandColor.copy(alpha = 0.18f))
                        drawPath(splinePath(seg.map { it.second }), bandColor.copy(alpha = 0.7f), style = Stroke(1.4f))
                    }
                    seg = ArrayList()
                }
                pairs.forEach { if (it == null) flush() else seg += it }
                flush()
            }

            // Main series: gradient area + spline.
            val main = smoothSegments(pts(values))
            for (s in main) {
                if (s.size >= 2) {
                    val area = splinePath(s)
                    area.lineTo(s.last().x, y(yMin)); area.lineTo(s.first().x, y(yMin)); area.close()
                    drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)), startY = top, endY = top + h))
                    drawPath(splinePath(s), color, style = Stroke(2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            secondary.forEach { line ->
                smoothSegments(pts(line.values)).forEach { s ->
                    if (s.size >= 2) drawPath(
                        splinePath(s), line.color,
                        style = Stroke(line.width, cap = StrokeCap.Round, pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null),
                    )
                }
            }
            reference?.let { r ->
                val ry = y(r)
                drawLine(muted, Offset(axisPx, ry), Offset(size.width, ry), 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                referenceLabel?.let {
                    val t = textMeasurer.measure(it, axisStyle)
                    drawText(t, topLeft = Offset(size.width - t.size.width - 2f, (ry - t.size.height - 1f).coerceAtLeast(0f)))
                }
            }
            // Point markers when the density allows it.
            if (visible.count() <= 40) {
                for (i in visible) {
                    val v = values[i] ?: continue
                    drawPointMarker(Offset(cx(i), y(v)), color, surface, big = selected == i)
                }
            }
            selected?.takeIf { it in visible }?.let { i ->
                drawLine(ink.copy(alpha = 0.35f), Offset(cx(i), top), Offset(cx(i), top + h), 1f)
                values[i]?.let { drawPointMarker(Offset(cx(i), y(it)), color, surface, big = true) }
            }
        }
        IndexLabels(labels, window(zoom, n), Modifier.padding(start = 30.dp))
    }
}

private fun DrawScope.drawPointMarker(c: Offset, color: Color, bg: Color, big: Boolean) {
    val r = if (big) 5.5f else 3.2f
    drawCircle(bg, r + 1.6f, c)
    drawCircle(color, r, c)
    if (big) drawCircle(color.copy(alpha = 0.25f), r + 6f, c)
}
