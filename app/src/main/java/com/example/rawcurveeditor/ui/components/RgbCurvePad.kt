package com.example.rawcurveeditor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rawcurveeditor.domain.CurveChannel
import com.example.rawcurveeditor.domain.CurvePoint
import com.example.rawcurveeditor.domain.CurveSplineEngine
import com.example.rawcurveeditor.domain.HistogramData
import com.example.rawcurveeditor.domain.RgbCurvesState
import com.example.rawcurveeditor.ui.theme.AccentBlue
import com.example.rawcurveeditor.ui.theme.AccentGreen
import com.example.rawcurveeditor.ui.theme.AccentMaster
import com.example.rawcurveeditor.ui.theme.AccentRed
import com.example.rawcurveeditor.ui.theme.MonoBackground
import com.example.rawcurveeditor.ui.theme.MonoBorder
import com.example.rawcurveeditor.ui.theme.MonoSurface
import com.example.rawcurveeditor.ui.theme.MonoSurfaceElevated
import com.example.rawcurveeditor.ui.theme.MonoTextMuted
import com.example.rawcurveeditor.ui.theme.MonoTextPrimary
import com.example.rawcurveeditor.ui.theme.MonoTextSecondary
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun RgbCurveEditorPanel(
    activePhotoId: Int,
    curvesState: RgbCurvesState,
    activeChannel: CurveChannel,
    histogram: HistogramData,
    canUndo: Boolean,
    canRedo: Boolean,
    hasCopiedCurves: Boolean,
    photoCount: Int,
    onSelectChannel: (CurveChannel) -> Unit,
    onBeginCurveGesture: () -> Unit,
    onEndCurveGesture: () -> Unit,
    onUpdateChannelPoints: (CurveChannel, List<CurvePoint>) -> Unit,
    onDeleteChannelPoint: (CurveChannel, List<CurvePoint>) -> Unit,
    onResetChannel: (CurveChannel) -> Unit,
    onResetAll: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCopyCurves: () -> Unit,
    onPasteCurves: () -> Unit,
    onRequestApplyToAllPhotos: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activePoints = curvesState.pointsFor(activeChannel)
    var selectedPointIndex by remember(activeChannel, activePhotoId) { mutableIntStateOf(-1) }

    LaunchedEffect(activePoints.size) {
        if (selectedPointIndex !in activePoints.indices) {
            selectedPointIndex = -1
        }
    }

    val canDeleteSelectedPoint = selectedPointIndex > 0 && selectedPointIndex < activePoints.lastIndex

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MonoSurface)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Row 1: Channel selector pills + DEL PT / CH RESET / ALL RESET
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CurveChannel.entries.forEach { channel ->
                    ChannelPillButton(
                        channel = channel,
                        isSelected = channel == activeChannel,
                        isModified = curvesState.pointsFor(channel) != RgbCurvesState.defaultLinearPoints(),
                        onClick = { onSelectChannel(channel) }
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallMonoButton(
                    text = "DEL PT",
                    enabled = canDeleteSelectedPoint,
                    onClick = {
                        val idx = selectedPointIndex
                        if (idx > 0 && idx < activePoints.lastIndex) {
                            val updated = activePoints.toMutableList().apply { removeAt(idx) }
                            selectedPointIndex = -1
                            onDeleteChannelPoint(activeChannel, updated)
                        }
                    }
                )
                SmallMonoButton(
                    text = "CH RESET",
                    enabled = activePoints != RgbCurvesState.defaultLinearPoints(),
                    onClick = {
                        selectedPointIndex = -1
                        onResetChannel(activeChannel)
                    }
                )
                SmallMonoButton(
                    text = "ALL RESET",
                    enabled = curvesState.isModified,
                    onClick = {
                        selectedPointIndex = -1
                        onResetAll()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val currentPointsState by rememberUpdatedState(activePoints)
        val currentChannelState by rememberUpdatedState(activeChannel)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.22f)
                .clip(RoundedCornerShape(6.dp))
                .background(MonoBackground)
                .border(1.dp, MonoBorder, RoundedCornerShape(6.dp))
                .pointerInput(activeChannel, activePhotoId) {
                    val hitRadiusPx = 24.dp.toPx()
                    val outOfBoundsMargin = 52.dp.toPx()

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        // Record Undo snapshot once at the start of the touch gesture
                        onBeginCurveGesture()

                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)

                        var workingPoints = currentPointsState.toList()
                        var dragIdx = findNearestPointIndex(
                            points = workingPoints,
                            touch = down.position,
                            width = w,
                            height = h,
                            hitRadiusPx = hitRadiusPx
                        )

                        if (dragIdx >= 0) {
                            selectedPointIndex = dragIdx
                            val minX = if (dragIdx == 0) 0f else (workingPoints[dragIdx - 1].x + 0.008f)
                            val maxX = if (dragIdx == workingPoints.lastIndex) 1f else (workingPoints[dragIdx + 1].x - 0.008f)
                            val newX = (down.position.x / w).coerceIn(minX, maxX)
                            val newY = (1f - (down.position.y / h)).coerceIn(0f, 1f)

                            workingPoints = workingPoints.toMutableList().also {
                                it[dragIdx] = CurvePoint(newX, newY)
                            }
                            onUpdateChannelPoints(currentChannelState, workingPoints)
                        } else if (workingPoints.size < 16) {
                            val leftEndpointX = workingPoints.firstOrNull()?.x ?: 0f
                            val rightEndpointX = workingPoints.lastOrNull()?.x ?: 1f
                            val safeMinX = (leftEndpointX + 0.008f).coerceAtMost(0.99f)
                            val safeMaxX = (rightEndpointX - 0.008f).coerceAtLeast(safeMinX)

                            val normX = (down.position.x / w).coerceIn(safeMinX, safeMaxX)
                            val normY = (1f - (down.position.y / h)).coerceIn(0f, 1f)
                            val newPoint = CurvePoint(normX, normY)

                            workingPoints = (workingPoints + newPoint).sortedBy { it.x }
                            dragIdx = workingPoints.indexOfFirst { it === newPoint }
                            selectedPointIndex = dragIdx
                            onUpdateChannelPoints(currentChannelState, workingPoints)
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }

                            val pos = change.position
                            change.consume()

                            if (dragIdx in workingPoints.indices) {
                                val isInterior = dragIdx > 0 && dragIdx < workingPoints.lastIndex
                                if (isInterior && (pos.y < -outOfBoundsMargin || pos.y > h + outOfBoundsMargin)) {
                                    workingPoints = workingPoints.toMutableList().apply { removeAt(dragIdx) }
                                    dragIdx = -1
                                    selectedPointIndex = -1
                                    onUpdateChannelPoints(currentChannelState, workingPoints)
                                    break
                                }

                                val minX = if (dragIdx == 0) 0f else (workingPoints[dragIdx - 1].x + 0.008f)
                                val maxX = if (dragIdx == workingPoints.lastIndex) 1f else (workingPoints[dragIdx + 1].x - 0.008f)

                                val newX = (pos.x / w).coerceIn(minX, maxX)
                                val newY = (1f - (pos.y / h)).coerceIn(0f, 1f)

                                workingPoints = workingPoints.toMutableList().also {
                                    it[dragIdx] = CurvePoint(newX, newY)
                                }
                                onUpdateChannelPoints(currentChannelState, workingPoints)
                            }
                        }

                        onEndCurveGesture()
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawHistogramBackdrop(histogram, activeChannel)
                drawRuleOfThirdsGrid()

                // Inactive channels in subtle translucent tone
                CurveChannel.entries.forEach { channel ->
                    if (channel != activeChannel) {
                        val pts = curvesState.pointsFor(channel)
                        if (pts != RgbCurvesState.defaultLinearPoints()) {
                            drawCurveSpline(
                                points = pts,
                                color = channelAccentColor(channel).copy(alpha = 0.35f),
                                strokeWidth = 1.5.dp.toPx()
                            )
                        }
                    }
                }

                // Active channel spline + control points
                val activeColor = channelAccentColor(activeChannel)
                drawCurveSpline(
                    points = activePoints,
                    color = activeColor,
                    strokeWidth = 2.5.dp.toPx()
                )
                drawControlPoints(
                    points = activePoints,
                    selectedIndex = selectedPointIndex,
                    activeColor = activeColor
                )
            }

            // Subtle coordinate HUD overlay in top-left of curve box
            val selectedPt = activePoints.getOrNull(selectedPointIndex)
            val hudText = if (selectedPt != null) {
                val inVal = (selectedPt.x * 255).roundToInt()
                val outVal = (selectedPt.y * 255).roundToInt()
                "IN:$inVal → OUT:$outVal (${activePoints.size}pt)"
            } else {
                "${activeChannel.label} • ${activePoints.size}pt"
            }
            Text(
                text = hudText,
                color = MonoTextSecondary.copy(alpha = 0.8f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Fixed-height footer row: UNDO / REDO on the left + COPY / PASTE / APPLY ALL on the right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallMonoButton(
                    text = "UNDO",
                    enabled = canUndo,
                    onClick = onUndo
                )
                SmallMonoButton(
                    text = "REDO",
                    enabled = canRedo,
                    onClick = onRedo
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallMonoButton(
                    text = "COPY",
                    enabled = activePhotoId != -1,
                    onClick = onCopyCurves
                )
                SmallMonoButton(
                    text = "PASTE",
                    enabled = activePhotoId != -1 && hasCopiedCurves,
                    onClick = onPasteCurves
                )
                SmallMonoButton(
                    text = "APPLY ALL",
                    enabled = photoCount > 1,
                    onClick = onRequestApplyToAllPhotos
                )
            }
        }
    }
}

@Composable
private fun ChannelPillButton(
    channel: CurveChannel,
    isSelected: Boolean,
    isModified: Boolean,
    onClick: () -> Unit
) {
    val accent = channelAccentColor(channel)
    val bgColor = if (isSelected) MonoSurfaceElevated else Color.Transparent
    val borderColor = if (isSelected) accent else MonoBorder
    val textColor = if (isSelected) MonoTextPrimary else MonoTextSecondary

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isSelected || isModified) accent else MonoTextMuted)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = channel.label,
            color = textColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun SmallMonoButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val textColor = if (enabled) MonoTextPrimary else MonoTextMuted.copy(alpha = 0.4f)
    val borderColor = if (enabled) MonoBorder else MonoBorder.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (enabled) MonoSurfaceElevated else Color.Transparent)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun channelAccentColor(channel: CurveChannel): Color = when (channel) {
    CurveChannel.MASTER -> AccentMaster
    CurveChannel.RED -> AccentRed
    CurveChannel.GREEN -> AccentGreen
    CurveChannel.BLUE -> AccentBlue
}

private fun histogramBinsForChannel(
    histogram: HistogramData,
    channel: CurveChannel
): IntArray = when (channel) {
    CurveChannel.MASTER -> histogram.luma
    CurveChannel.RED -> histogram.r
    CurveChannel.GREEN -> histogram.g
    CurveChannel.BLUE -> histogram.b
}

private fun findNearestPointIndex(
    points: List<CurvePoint>,
    touch: Offset,
    width: Float,
    height: Float,
    hitRadiusPx: Float
): Int {
    var bestIdx = -1
    var bestDist = hitRadiusPx
    points.forEachIndexed { idx, pt ->
        val px = pt.x * width
        val py = (1f - pt.y) * height
        val dist = hypot(touch.x - px, touch.y - py)
        if (dist <= bestDist) {
            bestDist = dist
            bestIdx = idx
        }
    }
    return bestIdx
}

private fun DrawScope.drawRuleOfThirdsGrid() {
    val w = size.width
    val h = size.height
    val gridColor = MonoBorder.copy(alpha = 0.55f)

    for (i in 1..3) {
        val frac = i / 4f
        drawLine(
            color = gridColor,
            start = Offset(w * frac, 0f),
            end = Offset(w * frac, h),
            strokeWidth = 1f
        )
        drawLine(
            color = gridColor,
            start = Offset(0f, h * frac),
            end = Offset(w, h * frac),
            strokeWidth = 1f
        )
    }

    drawLine(
        color = MonoBorder.copy(alpha = 0.8f),
        start = Offset(0f, h),
        end = Offset(w, 0f),
        strokeWidth = 1.2f
    )
}

private fun DrawScope.drawHistogramBackdrop(
    histogram: HistogramData,
    activeChannel: CurveChannel
) {
    val maxCount = histogram.maxCount.coerceAtLeast(1).toFloat()

    // 1. Draw the 3 non-active channels faintly
    CurveChannel.entries.forEach { channel ->
        if (channel != activeChannel) {
            drawSingleChannelHistogram(
                bins = histogramBinsForChannel(histogram, channel),
                maxCount = maxCount,
                baseColor = channelAccentColor(channel),
                fillAlpha = 0.045f,
                strokeAlpha = 0.18f,
                strokeWidthPx = 1.0.dp.toPx()
            )
        }
    }

    // 2. Draw the active channel at the standard visibility
    drawSingleChannelHistogram(
        bins = histogramBinsForChannel(histogram, activeChannel),
        maxCount = maxCount,
        baseColor = channelAccentColor(activeChannel),
        fillAlpha = 0.14f,
        strokeAlpha = 0.38f,
        strokeWidthPx = 1.3.dp.toPx()
    )
}

private fun DrawScope.drawSingleChannelHistogram(
    bins: IntArray,
    maxCount: Float,
    baseColor: Color,
    fillAlpha: Float,
    strokeAlpha: Float,
    strokeWidthPx: Float
) {
    val w = size.width
    val h = size.height

    val fillPath = Path()
    val strokePath = Path()

    fillPath.moveTo(0f, h)
    for (i in 0 until 256) {
        val x = (i / 255f) * w
        val normalized = (bins[i] / maxCount).coerceIn(0f, 1f)
        val y = h - (normalized * h * 0.88f)
        fillPath.lineTo(x, y)
        if (i == 0) strokePath.moveTo(x, y) else strokePath.lineTo(x, y)
    }
    fillPath.lineTo(w, h)
    fillPath.close()

    drawPath(path = fillPath, color = baseColor.copy(alpha = fillAlpha))
    drawPath(
        path = strokePath,
        color = baseColor.copy(alpha = strokeAlpha),
        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawCurveSpline(
    points: List<CurvePoint>,
    color: Color,
    strokeWidth: Float
) {
    val samples = 256
    val table = CurveSplineEngine.evaluateCurveTable(points, samples)
    val w = size.width
    val h = size.height

    val path = Path()
    for (i in 0 until samples) {
        val x = (i.toFloat() / (samples - 1)) * w
        val y = (1f - table[i]) * h
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

private fun DrawScope.drawControlPoints(
    points: List<CurvePoint>,
    selectedIndex: Int,
    activeColor: Color
) {
    val w = size.width
    val h = size.height
    points.forEachIndexed { idx, pt ->
        val center = Offset(pt.x * w, (1f - pt.y) * h)
        val isSelected = idx == selectedIndex
        val outerRadius = if (isSelected) 7.dp.toPx() else 5.dp.toPx()

        drawCircle(
            color = MonoBackground,
            radius = outerRadius + 1.5.dp.toPx(),
            center = center
        )
        drawCircle(
            color = activeColor,
            radius = outerRadius,
            center = center,
            style = if (isSelected) Stroke(width = 2.5.dp.toPx()) else Stroke(width = 1.8.dp.toPx())
        )
        if (isSelected) {
            drawCircle(
                color = activeColor,
                radius = 3.dp.toPx(),
                center = center
            )
        }
    }
}
