package de.sleeptracker.analysis

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Monotone cubic Hermite interpolation (Fritsch–Carlson). The curve passes through every node and
 * never overshoots the data between two nodes, which keeps charts honest (no dips below zero, no
 * invented peaks). Used for drawing smooth chart lines.
 */
object MonotoneSpline {

    /** Tangents (dy/dx) at each node. [xs] must be strictly increasing. */
    fun tangents(xs: FloatArray, ys: FloatArray): FloatArray {
        val n = xs.size
        require(ys.size == n)
        val m = FloatArray(n)
        if (n < 2) return m
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        return m
    }

    /**
     * Bezier control points for each segment i (between node i and i+1):
     * (c1x, c1y, c2x, c2y) — ready for Path.cubicTo.
     */
    fun controlPoints(xs: FloatArray, ys: FloatArray): List<FloatArray> {
        val m = tangents(xs, ys)
        return (0 until xs.size - 1).map { i ->
            val h = (xs[i + 1] - xs[i]) / 3f
            floatArrayOf(xs[i] + h, ys[i] + m[i] * h, xs[i + 1] - h, ys[i + 1] - m[i + 1] * h)
        }
    }

    /** Evaluates the spline at [x] (for tests and read-outs). */
    fun evaluate(xs: FloatArray, ys: FloatArray, x: Float): Float {
        val n = xs.size
        if (n == 0) return Float.NaN
        if (n == 1 || x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        val m = tangents(xs, ys)
        var i = 0
        while (i < n - 2 && x > xs[i + 1]) i++
        val h = xs[i + 1] - xs[i]
        val t = (x - xs[i]) / h
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] +
            (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
    }

    internal fun near(a: Float, b: Float) = abs(a - b) < 1e-4f
}
