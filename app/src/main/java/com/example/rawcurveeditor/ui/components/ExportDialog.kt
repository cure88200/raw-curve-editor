package com.example.rawcurveeditor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.rawcurveeditor.domain.ExportFormat
import com.example.rawcurveeditor.ui.theme.MonoBackground
import com.example.rawcurveeditor.ui.theme.MonoBorder
import com.example.rawcurveeditor.ui.theme.MonoSurface
import com.example.rawcurveeditor.ui.theme.MonoSurfaceElevated
import com.example.rawcurveeditor.ui.theme.MonoTextMuted
import com.example.rawcurveeditor.ui.theme.MonoTextPrimary
import com.example.rawcurveeditor.ui.theme.MonoTextSecondary
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun ExportDialog(
    originalWidth: Int,
    originalHeight: Int,
    totalPhotoCount: Int,
    initialScalePercent: Int = 100,
    initialFormat: ExportFormat = ExportFormat.JPEG,
    initialJpegQuality: Int = 95,
    onDismiss: () -> Unit,
    onConfirmExport: (scalePercent: Int, format: ExportFormat, jpegQuality: Int, exportAll: Boolean) -> Unit
) {
    var scalePercent by remember { mutableIntStateOf(initialScalePercent.coerceIn(10, 300)) }
    var percentText by remember { mutableStateOf(scalePercent.toString()) }
    var format by remember { mutableStateOf(initialFormat) }
    var jpegQuality by remember { mutableIntStateOf(initialJpegQuality.coerceIn(1, 100)) }
    var exportAll by remember { mutableStateOf(false) }

    val targetWidth = max(1, (originalWidth * (scalePercent / 100f)).roundToInt())
    val targetHeight = max(1, (originalHeight * (scalePercent / 100f)).roundToInt())
    val megapixels = (targetWidth.toLong() * targetHeight.toLong()) / 1_000_000.0

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MonoSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MonoBorder, RoundedCornerShape(8.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "EXPORT IMAGE",
                    color = MonoTextPrimary,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                // Target selector when multiple photos are loaded
                if (totalPhotoCount > 1) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "TARGET",
                        color = MonoTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val options = listOf(
                            false to "CURRENT (1)",
                            true to "ALL PHOTOS ($totalPhotoCount)"
                        )
                        options.forEach { (isAll, label) ->
                            val selected = exportAll == isAll
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (selected) MonoTextPrimary else MonoBackground)
                                    .border(
                                        1.dp,
                                        if (selected) MonoTextPrimary else MonoBorder,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable { exportAll = isAll }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) MonoBackground else MonoTextPrimary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Format selector (JPEG / PNG)
                Text(
                    text = "FORMAT",
                    color = MonoTextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExportFormat.entries.forEach { item ->
                        val selected = item == format
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selected) MonoTextPrimary else MonoBackground)
                                .border(
                                    1.dp,
                                    if (selected) MonoTextPrimary else MonoBorder,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { format = item }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = item.label,
                                color = if (selected) MonoBackground else MonoTextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (format == ExportFormat.JPEG) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "JPEG QUALITY",
                            color = MonoTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "$jpegQuality%",
                            color = MonoTextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = jpegQuality.toFloat(),
                        onValueChange = { jpegQuality = it.roundToInt().coerceIn(1, 100) },
                        valueRange = 10f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = MonoTextPrimary,
                            activeTrackColor = MonoTextPrimary,
                            inactiveTrackColor = MonoBorder
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Resolution Scale (%) section with live pixel readout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RESOLUTION SCALE (%)",
                        color = MonoTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    OutlinedTextField(
                        value = percentText,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() }.take(3)
                            percentText = filtered
                            val parsed = filtered.toIntOrNull()
                            if (parsed != null) {
                                scalePercent = parsed.coerceIn(5, 300)
                            }
                        },
                        suffix = {
                            Text(
                                text = "%",
                                color = MonoTextSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MonoTextPrimary,
                            unfocusedBorderColor = MonoBorder,
                            focusedTextColor = MonoTextPrimary,
                            unfocusedTextColor = MonoTextPrimary,
                            cursorColor = MonoTextPrimary
                        ),
                        modifier = Modifier.width(92.dp)
                    )
                }

                Slider(
                    value = scalePercent.toFloat().coerceIn(10f, 200f),
                    onValueChange = {
                        val v = it.roundToInt().coerceIn(10, 200)
                        scalePercent = v
                        percentText = v.toString()
                    },
                    valueRange = 10f..200f,
                    colors = SliderDefaults.colors(
                        thumbColor = MonoTextPrimary,
                        activeTrackColor = MonoTextPrimary,
                        inactiveTrackColor = MonoBorder
                    )
                )

                val presets = listOf(25, 50, 75, 100, 125, 150, 200)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    presets.forEach { preset ->
                        val isSelected = scalePercent == preset
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) MonoSurfaceElevated else MonoBackground)
                                .border(
                                    1.dp,
                                    if (isSelected) MonoTextPrimary else MonoBorder,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable {
                                    scalePercent = preset
                                    percentText = preset.toString()
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${preset}%",
                                color = if (isSelected) MonoTextPrimary else MonoTextSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Real-time Pixel Dimension Readout Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MonoBackground)
                        .border(1.dp, MonoBorder, RoundedCornerShape(6.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "SOURCE",
                            color = MonoTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "$originalWidth × $originalHeight px",
                            color = MonoTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "OUTPUT ($scalePercent%)",
                            color = MonoTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "%d × %d px (%.1f MP)",
                                targetWidth,
                                targetHeight,
                                megapixels
                            ),
                            color = MonoTextPrimary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, MonoBorder, RoundedCornerShape(4.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "CANCEL",
                            color = MonoTextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MonoTextPrimary)
                            .clickable {
                                onConfirmExport(scalePercent, format, jpegQuality, exportAll)
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (exportAll && totalPhotoCount > 1) "EXPORT ALL ($totalPhotoCount)" else "EXPORT",
                            color = MonoBackground,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
