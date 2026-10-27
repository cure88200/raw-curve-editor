package com.example.rawcurveeditor.domain

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Monotone Cubic Hermite Spline (Fritsch-Carlson method) curve evaluator.
 * Prevents unnatural overshoots/oscillations when dragging control points and generates
 * 16-bit (65536-entry) Lookup Tables for banding-free RAW color grading.
 */
object CurveSplineEngine {

    private const val LUT_16_SIZE = 65536

    data class CombinedRgbLut16(
        val rLut: IntArray,
        val gLut: IntArray,
        val bLut: IntArray
    )

    /**
     * Build combined 16-bit -> 8-bit LUTs for R, G, and B channels:
     * Output_C(x) = MasterCurve(ChannelCurve(x))
     */
    fun buildCombinedLut16(state: RgbCurvesState): CombinedRgbLut16 {
        val masterFloat = evaluateCurveTable(state.master, LUT_16_SIZE)
        val rFloat = evaluateCurveTable(state.red, LUT_16_SIZE)
        val gFloat = evaluateCurveTable(state.green, LUT_16_SIZE)
        val bFloat = evaluateCurveTable(state.blue, LUT_16_SIZE)

        val rLut = IntArray(LUT_16_SIZE)
        val gLut = IntArray(LUT_16_SIZE)
        val bLut = IntArray(LUT_16_SIZE)

        val maxIdx = LUT_16_SIZE - 1
        for (i in 0 until LUT_16_SIZE) {
            val rMid = (rFloat[i] * maxIdx).roundToInt().coerceIn(0, maxIdx)
            val gMid = (gFloat[i] * maxIdx).roundToInt().coerceIn(0, maxIdx)
            val bMid = (bFloat[i] * maxIdx).roundToInt().coerceIn(0, maxIdx)

            rLut[i] = (masterFloat[rMid] * 255f).roundToInt().coerceIn(0, 255)
            gLut[i] = (masterFloat[gMid] * 255f).roundToInt().coerceIn(0, 255)
            bLut[i] = (masterFloat[bMid] * 255f).roundToInt().coerceIn(0, 255)
        }

        return CombinedRgbLut16(rLut, gLut, bLut)
    }

    /**
     * Sample a curve at [steps] evenly spaced X coordinates in 0..1 for UI rendering or LUT creation.
     */
    fun evaluateCurveTable(points: List<CurvePoint>, steps: Int): FloatArray {
        val out = FloatArray(steps)
        if (points.isEmpty()) {
            for (i in 0 until steps) out[i] = i.toFloat() / (steps - 1).coerceAtLeast(1)
            return out
        }

        val sorted = mergeDuplicateX(points.sortedBy { it.x })
        if (sorted.size == 1) {
            val y = sorted[0].y.coerceIn(0f, 1f)
            for (i in 0 until steps) out[i] = y
            return out
        }

        val n = sorted.size
        val xs = FloatArray(n) { sorted[it].x }
        val ys = FloatArray(n) { sorted[it].y }
        val tangents = computeMonotoneTangents(xs, ys)

        val firstX = xs.first()
        val lastX = xs.last()
        var segIdx = 0

        for (i in 0 until steps) {
            val x = i.toFloat() / (steps - 1).coerceAtLeast(1)
            val y = when {
                x <= firstX -> ys.first()
                x >= lastX -> ys.last()
                else -> {
                    while (segIdx < n - 2 && x > xs[segIdx + 1]) {
                        segIdx++
                    }
                    val h = xs[segIdx + 1] - xs[segIdx]
                    if (h <= 1e-6f) {
                        ys[segIdx]
                    } else {
                        val t = (x - xs[segIdx]) / h
                        val t2 = t * t
                        val t3 = t2 * t
                        val h00 = 2f * t3 - 3f * t2 + 1f
                        val h10 = t3 - 2f * t2 + t
                        val h01 = -2f * t3 + 3f * t2
                        val h11 = t3 - t2
                        h00 * ys[segIdx] +
                            h10 * h * tangents[segIdx] +
                            h01 * ys[segIdx + 1] +
                            h11 * h * tangents[segIdx + 1]
                    }
                }
            }
            out[i] = y.coerceIn(0f, 1f)
        }
        return out
    }

    private fun mergeDuplicateX(sorted: List<CurvePoint>): List<CurvePoint> {
        if (sorted.size <= 1) return sorted
        val result = ArrayList<CurvePoint>(sorted.size)
        for (pt in sorted) {
            if (result.isNotEmpty() && abs(pt.x - result.last().x) < 1e-4f) {
                result[result.lastIndex] = pt
            } else {
                result.add(pt)
            }
        }
        return result
    }

    private fun computeMonotoneTangents(xs: FloatArray, ys: FloatArray): FloatArray {
        val n = xs.size
        val m = FloatArray(n)
        if (n < 2) return m

        val d = FloatArray(n - 1)
        for (i in 0 until n - 1) {
            val dx = (xs[i + 1] - xs[i]).coerceAtLeast(1e-6f)
            d[i] = (ys[i + 1] - ys[i]) / dx
        }

        m[0] = d[0]
        for (i in 1 until n - 1) {
            if (d[i - 1] * d[i] <= 0f) {
                m[i] = 0f
            } else {
                m[i] = 0.5f * (d[i - 1] + d[i])
            }
        }
        m[n - 1] = d[n - 2]

        for (i in 0 until n - 1) {
            if (abs(d[i]) < 1e-6f) {
                m[i] = 0f
                m[i + 1] = 0f
            } else {
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val h = hypot(a, b)
                if (h > 3f) {
                    val t = 3f / h
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
        }
        return m
    }
}
