package com.example.rawcurveeditor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
    curvesState: RgbCurvesState,
    activeChannel: CurveChannel,
    histogram: HistogramData,
    onSelectChannel: (CurveChannel) -> Unit,
    onUpdateChannelPoints: (CurveChannel, List<CurvePoint>) -> Unit,
    onResetChannel: (CurveChannel) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activePoints = curvesState.pointsFor(activeChannel)
    var selectedPointIndex by remember(activeChannel) { mutableIntStateOf(-1) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MonoSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Channel selector header + Reset controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

        Spacer(modifier = Modifier.height(10.dp))

        // Interactive Curve Graph Box
        val currentPointsState by rememberUpdatedState(activePoints)
        val currentChannelState by rememberUpdatedState(activeChannel)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.15f)
                .clip(RoundedCornerShape(6.dp))
                .background(MonoBackground)
                .border(1.dp, MonoBorder, RoundedCornerShape(6.dp))
                .pointerInput(activeChannel) {
                    val hitRadiusPx = 30.dp.toPx()
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val pts = currentPointsState
                            val idx = findNearestPointIndex(pts, tapOffset, w, h, hitRadiusPx)
                            // Allow deleting interior control points on double tap
                            if (idx > 0 && idx < pts.lastIndex) {
                                val updated = pts.toMutableList().apply { removeAt(idx) }
                                selectedPointIndex = -1
                                onUpdateChannelPoints(currentChannelState, updated)
                            }
                        },
                        onTap = { tapOffset ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val pts = currentPointsState
                            val existingIdx = findNearestPointIndex(pts, tapOffset, w, h, hitRadiusPx)
                            if (existingIdx >= 0) {
                                selectedPointIndex = existingIdx
                            } else if (pts.size < 16) {
                                val normX = (tapOffset.x / w).coerceIn(0.001f, 0.999f)
                                val normY = (1f - (tapOffset.y / h)).coerceIn(0f, 1f)
                                val updated = (pts + CurvePoint(normX, normY)).sortedBy { it.x }
                                selectedPointIndex = updated.indexOfFirst { it.x == normX && it.y == normY }
                                onUpdateChannelPoints(currentChannelState, updated)
                            }
                        }
                    )
                }
                .pointerInput(activeChannel) {
                    val hitRadiusPx = 32.dp.toPx()
                    var dragIdx = -1

                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val pts = currentPointsState
                            val nearest = findNearestPointIndex(pts, startOffset, w, h, hitRadiusPx)
                            if (nearest >= 0) {
                                dragIdx = nearest
                                selectedPointIndex = nearest
                            } else if (pts.size < 16) {
                                // Create a new point and start dragging it immediately
                                val normX = (startOffset.x / w).coerceIn(0.001f, 0.999f)
                                val normY = (1f - (startOffset.y / h)).coerceIn(0f, 1f)
                                val newPt = CurvePoint(normX, normY)
                                val updated = (pts + newPt).sortedBy { it.x }
                                dragIdx = updated.indexOf(newPt)
                                selectedPointIndex = dragIdx
                                onUpdateChannelPoints(currentChannelState, updated)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val pts = currentPointsState
                            if (dragIdx in pts.indices) {
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val pos = change.position

                                // Dragging an interior point far outside the box removes it
                                val isInterior = dragIdx > 0 && dragIdx < pts.lastIndex
                                val outOfBoundsMargin = 48.dp.toPx()
                                if (isInterior && (pos.y < -outOfBoundsMargin || pos.y > h + outOfBoundsMargin)) {
                                    val updated = pts.toMutableList().apply { removeAt(dragIdx) }
                                    dragIdx = -1
                                    selectedPointIndex = -1
                                    onUpdateChannelPoints(currentChannelState, updated)
                                    return@detectDragGestures
                                }

                                // Constrain X between adjacent neighbors so points don't cross over
                                val minX = if (dragIdx == 0) 0f else (pts[dragIdx - 1].x + 0.008f)
                                val maxX = if (dragIdx == pts.lastIndex) 1f else (pts[dragIdx + 1].x - 0.008f)

                                val newX = (pos.x / w).coerceIn(minX, maxX)
                                val newY = (1f - (pos.y / h)).coerceIn(0f, 1f)

                                val updated = pts.toMutableList()
                                updated[dragIdx] = CurvePoint(newX, newY)
                                onUpdateChannelPoints(currentChannelState, updated)
                            }
                        },
                        onDragEnd = {
                            dragIdx = -1
                        },
                        onDragCancel = {
                            dragIdx = -1
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawHistogramBackdrop(histogram, activeChannel)
                drawRuleOfThirdsGrid()

                // Draw inactive channels subtly in background
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

                // Draw active channel curve & control points
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
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Footer coordinates readout & interaction hint
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val selectedPt = activePoints.getOrNull(selectedPointIndex)
            val coordText = if (selectedPt != null) {
                val inVal = (selectedPt.x * 255).roundToInt()
                val outVal = (selectedPt.y * 255).roundToInt()
                "IN: $inVal  →  OUT: $outVal   (${activePoints.size} pts)"
            } else {
                "TAP/DRAG: ADD & MOVE   •   DOUBLE-TAP: DELETE"
            }

            Text(
                text = coordText,
                color = MonoTextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            if (selectedPointIndex > 0 && selectedPointIndex < activePoints.lastIndex) {
                Text(
                    text = "DELETE PT",
                    color = AccentRed,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            val idx = selectedPointIndex
                            if (idx > 0 && idx < activePoints.lastIndex) {
                                val updated = activePoints.toMutableList().apply { removeAt(idx) }
                                selectedPointIndex = -1
                                onUpdateChannelPoints(activeChannel, updated)
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isSelected || isModified) accent else MonoTextMuted)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = channel.label,
            color = textColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun SmallMonoButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val textColor = if (enabled) MonoTextSecondary else MonoTextMuted.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MonoBorder.copy(alpha = if (enabled) 1f else 0.4f), RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
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

    // Diagonal baseline
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
    val w = size.width
    val h = size.height

    val bins = when (activeChannel) {
        CurveChannel.MASTER -> histogram.luma
        CurveChannel.RED -> histogram.r
        CurveChannel.GREEN -> histogram.g
        CurveChannel.BLUE -> histogram.b
    }
    val fillColor = channelAccentColor(activeChannel).copy(alpha = 0.14f)

    val path = Path().apply {
        moveTo(0f, h)
        for (i in 0 until 256) {
            val x = (i / 255f) * w
            val normalized = (bins[i] / maxCount).coerceIn(0f, 1f)
            val y = h - (normalized * h * 0.88f)
            lineTo(x, y)
        }
        lineTo(w, h)
        close()
    }
    drawPath(path = path, color = fillColor)
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
