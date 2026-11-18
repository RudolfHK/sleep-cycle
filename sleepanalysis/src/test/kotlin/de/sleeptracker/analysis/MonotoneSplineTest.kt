package de.sleeptracker.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotoneSplineTest {
    @Test
    fun passesThroughNodesWithoutOvershoot() {
        val xs = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f)
        val ys = floatArrayOf(0f, 0f, 8f, 8.2f, 0f, 3f)
        for (i in xs.indices) assertEquals(ys[i], MonotoneSpline.evaluate(xs, ys, xs[i]), 1e-4f)
        var x = 0f
        while (x <= 5f) {
            val y = MonotoneSpline.evaluate(xs, ys, x)
            val i = x.toInt().coerceAtMost(4)
            val lo = minOf(ys[i], ys[i + 1]) - 1e-3f
            val hi = maxOf(ys[i], ys[i + 1]) + 1e-3f
            assertTrue("x=$x y=$y outside [$lo,$hi]", y in lo..hi)
            x += 0.05f
        }
    }

    @Test
    fun monotoneDataStaysMonotone() {
        val xs = floatArrayOf(0f, 1f, 2f, 3f)
        val ys = floatArrayOf(1f, 2f, 10f, 10.5f)
        var prev = -1f
        var x = 0f
        while (x <= 3f) {
            val y = MonotoneSpline.evaluate(xs, ys, x)
            assertTrue(y >= prev - 1e-4f); prev = y; x += 0.01f
        }
    }

    @Test
    fun smallInputs() {
        assertEquals(0, MonotoneSpline.controlPoints(floatArrayOf(1f), floatArrayOf(2f)).size)
        val c = MonotoneSpline.controlPoints(floatArrayOf(0f, 3f), floatArrayOf(0f, 3f))
        assertEquals(1, c.size)
        assertEquals(1f, c[0][0], 1e-6f); assertEquals(1f, c[0][1], 1e-6f) // straight line for two points
        assertEquals(5f, MonotoneSpline.evaluate(floatArrayOf(2f), floatArrayOf(5f), 9f), 0f)
    }
}
