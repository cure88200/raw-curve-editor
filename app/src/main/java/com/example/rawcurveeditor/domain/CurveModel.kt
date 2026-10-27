package com.example.rawcurveeditor.domain

import androidx.compose.runtime.Immutable

enum class CurveChannel(val label: String) {
    MASTER("RGB"),
    RED("R"),
    GREEN("G"),
    BLUE("B")
}

@Immutable
data class CurvePoint(
    val x: Float, // 0.0f .. 1.0f
    val y: Float  // 0.0f .. 1.0f
)

@Immutable
data class RgbCurvesState(
    val master: List<CurvePoint> = defaultLinearPoints(),
    val red: List<CurvePoint> = defaultLinearPoints(),
    val green: List<CurvePoint> = defaultLinearPoints(),
    val blue: List<CurvePoint> = defaultLinearPoints()
) {
    fun pointsFor(channel: CurveChannel): List<CurvePoint> = when (channel) {
        CurveChannel.MASTER -> master
        CurveChannel.RED -> red
        CurveChannel.GREEN -> green
        CurveChannel.BLUE -> blue
    }

    fun withPoints(channel: CurveChannel, newPoints: List<CurvePoint>): RgbCurvesState {
        val sorted = newPoints
            .map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            .sortedBy { it.x }
        return when (channel) {
            CurveChannel.MASTER -> copy(master = sorted)
            CurveChannel.RED -> copy(red = sorted)
            CurveChannel.GREEN -> copy(green = sorted)
            CurveChannel.BLUE -> copy(blue = sorted)
        }
    }

    fun resetChannel(channel: CurveChannel): RgbCurvesState =
        withPoints(channel, defaultLinearPoints())

    fun resetAll(): RgbCurvesState = RgbCurvesState()

    val isModified: Boolean
        get() = master != defaultLinearPoints() ||
                red != defaultLinearPoints() ||
                green != defaultLinearPoints() ||
                blue != defaultLinearPoints()

    companion object {
        fun defaultLinearPoints(): List<CurvePoint> = listOf(
            CurvePoint(0f, 0f),
            CurvePoint(1f, 1f)
        )
    }
}

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    JPEG("jpg", "image/jpeg", "JPEG"),
    PNG("png", "image/png", "PNG")
}

data class HistogramData(
    val r: IntArray = IntArray(256),
    val g: IntArray = IntArray(256),
    val b: IntArray = IntArray(256),
    val luma: IntArray = IntArray(256),
    val maxCount: Int = 1
)
