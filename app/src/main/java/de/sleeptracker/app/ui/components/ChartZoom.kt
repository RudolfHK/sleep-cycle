package de.sleeptracker.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.sleeptracker.app.R

/**
 * Horizontal viewport of a chart as fractions (0..1) of the full data range.
 * Charts start fully zoomed out (0..1).
 */
@Stable
class ChartZoomState(private val minSpan: Float = 0.05f) {
    var start by mutableFloatStateOf(0f)
        private set
    var end by mutableFloatStateOf(1f)
        private set

    val span: Float get() = end - start
    val isZoomed: Boolean get() = span < 0.999f

    /** Zooms by [factor] (> 1 = in) keeping the point at [focus] (0..1 of the visible area) in place. */
    fun zoomBy(factor: Float, focus: Float = 0.5f) {
        if (factor <= 0f || factor.isNaN()) return
        val anchor = start + span * focus.coerceIn(0f, 1f)
        val newSpan = (span / factor).coerceIn(minSpan, 1f)
        var s = anchor - newSpan * focus.coerceIn(0f, 1f)
        s = s.coerceIn(0f, 1f - newSpan)
        start = s
        end = s + newSpan
    }

    /** Pans by a fraction of the visible span (positive = later). */
    fun panBy(fractionOfView: Float) {
        val d = fractionOfView * span
        val s = (start + d).coerceIn(0f, 1f - span)
        end = s + span
        start = s
    }

    fun reset() { start = 0f; end = 1f }

    /** Double tap: zoom in 3× at the tap, or back out when already zoomed. */
    fun toggle(focus: Float) = if (isZoomed) reset() else zoomBy(3f, focus)

    /** Maps a data fraction (0..1) to a view fraction; values outside 0..1 are off-screen. */
    fun toView(fraction: Float): Float = (fraction - start) / span

    /** Maps a view fraction (0..1, e.g. a tap) to a data fraction. */
    fun toData(viewFraction: Float): Float = start + viewFraction * span

    companion object {
        /** Minimum span so that about [visibleItems] items remain visible. */
        fun forItems(count: Int, visibleItems: Int = 5) = ChartZoomState((visibleItems.toFloat() / count.coerceAtLeast(1)).coerceIn(0.02f, 1f))
    }
}

@Composable
fun rememberChartZoom(count: Int, key: Any? = null): ChartZoomState =
    remember(count, key) { ChartZoomState.forItems(count) }

/**
 * Pinch to zoom (two fingers) and, when zoomed, one-finger horizontal drag to pan. Single-finger
 * vertical movement is left alone so the page keeps scrolling.
 */
fun Modifier.chartZoomGestures(state: ChartZoomState?): Modifier {
    if (state == null) return this
    return this
        .pointerInput(state) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } >= 2) {
                        val zoom = event.calculateZoom()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (zoom != 1f) state.zoomBy(zoom, centroid.x / size.width)
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(state, state.isZoomed) {
            if (state.isZoomed) {
                detectHorizontalDragGestures { change, dragAmount ->
                    state.panBy(-dragAmount / size.width)
                    change.consume()
                }
            }
        }
}

/**
 * Panel for a zoomable chart: title, zoom −/+ and reset buttons, and an expand button that opens the
 * chart full-screen. [content] receives `expanded = true` inside the full-screen dialog.
 */
@Composable
fun ChartPanel(
    title: String,
    zoom: ChartZoomState?,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.(expanded: Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(
        title,
        modifier,
        actions = {
            if (zoom != null) ZoomButtons(zoom)
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.OpenInFull, stringResource(R.string.chart_expand), Modifier.size(18.dp))
            }
        },
    ) {
        content(false)
        if (zoom != null) {
            Text(
                stringResource(if (zoom.isZoomed) R.string.chart_zoom_hint_zoomed else R.string.chart_zoom_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        footer()
    }
    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (zoom != null) ZoomButtons(zoom)
                        IconButton(onClick = { expanded = false }) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        content(true)
                        footer()
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomButtons(zoom: ChartZoomState) {
    IconButton(onClick = { zoom.zoomBy(1 / 2f) }, enabled = zoom.isZoomed, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Filled.ZoomOut, stringResource(R.string.chart_zoom_out), Modifier.size(20.dp))
    }
    IconButton(onClick = { zoom.zoomBy(2f) }, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Filled.ZoomIn, stringResource(R.string.chart_zoom_in), Modifier.size(20.dp))
    }
    IconButton(onClick = { zoom.reset() }, enabled = zoom.isZoomed, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Filled.FitScreen, stringResource(R.string.chart_zoom_reset), Modifier.size(18.dp))
    }
}
