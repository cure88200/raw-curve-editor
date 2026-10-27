package com.example.rawcurveeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rawcurveeditor.ui.EditorUiState
import com.example.rawcurveeditor.ui.EditorViewModel
import com.example.rawcurveeditor.ui.components.ExportDialog
import com.example.rawcurveeditor.ui.components.RgbCurveEditorPanel
import com.example.rawcurveeditor.ui.theme.MonoBackground
import com.example.rawcurveeditor.ui.theme.MonoBorder
import com.example.rawcurveeditor.ui.theme.MonoSurface
import com.example.rawcurveeditor.ui.theme.MonoSurfaceElevated
import com.example.rawcurveeditor.ui.theme.MonoTextMuted
import com.example.rawcurveeditor.ui.theme.MonoTextPrimary
import com.example.rawcurveeditor.ui.theme.MonoTextSecondary
import com.example.rawcurveeditor.ui.theme.RawCurveMonotoneTheme

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )

        setContent {
            RawCurveMonotoneTheme {
                val state by viewModel.uiState.collectAsState()

                // Use OpenDocument with "*/*" so Sony .ARW and all camera RAW extensions are selectable
                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        viewModel.openRawUri(uri)
                    }
                }

                RawCurveEditorScreen(
                    state = state,
                    onOpenFile = {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onSelectChannel = viewModel::selectChannel,
                    onUpdateChannelPoints = viewModel::updateChannelPoints,
                    onResetChannel = viewModel::resetChannel,
                    onResetAll = viewModel::resetAllChannels,
                    onSetShowBefore = viewModel::setShowBefore,
                    onOpenExportDialog = { viewModel.showExportDialog(true) },
                    onDismissExportDialog = { viewModel.showExportDialog(false) },
                    onConfirmExport = viewModel::exportImage,
                    onDismissStatus = viewModel::clearStatusMessage
                )
            }
        }
    }
}

@Composable
private fun RawCurveEditorScreen(
    state: EditorUiState,
    onOpenFile: () -> Unit,
    onSelectChannel: (com.example.rawcurveeditor.domain.CurveChannel) -> Unit,
    onUpdateChannelPoints: (com.example.rawcurveeditor.domain.CurveChannel, List<com.example.rawcurveeditor.domain.CurvePoint>) -> Unit,
    onResetChannel: (com.example.rawcurveeditor.domain.CurveChannel) -> Unit,
    onResetAll: () -> Unit,
    onSetShowBefore: (Boolean) -> Unit,
    onOpenExportDialog: () -> Unit,
    onDismissExportDialog: () -> Unit,
    onConfirmExport: (Int, com.example.rawcurveeditor.domain.ExportFormat, Int) -> Unit,
    onDismissStatus: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Minimalist Monotone Top Bar
        TopBar(
            state = state,
            onOpenFile = onOpenFile,
            onSetShowBefore = onSetShowBefore,
            onOpenExport = onOpenExportDialog
        )

        HorizontalDivider(color = MonoBorder, thickness = 1.dp)

        // Center Image Viewport (supports pinch-zoom, pan, double-tap to reset zoom)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MonoBackground)
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            val activeBitmap = if (state.isShowingBefore) {
                state.originalPreviewBitmap
            } else {
                state.previewBitmap
            }

            if (activeBitmap != null) {
                ZoomableImagePreview(
                    bitmap = activeBitmap,
                    isBefore = state.isShowingBefore
                )
            } else if (!state.isLoading) {
                EmptyStatePrompt(onOpenFile = onOpenFile)
            }

            if (state.isLoading || state.isExporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MonoTextPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = state.statusMessage ?: "PROCESSING...",
                            color = MonoTextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Status toast banner
            if (!state.isLoading && !state.isExporting && state.statusMessage != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MonoSurfaceElevated.copy(alpha = 0.95f))
                        .border(1.dp, MonoBorder, RoundedCornerShape(4.dp))
                        .clickable { onDismissStatus() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = state.statusMessage,
                        color = MonoTextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        HorizontalDivider(color = MonoBorder, thickness = 1.dp)

        // Bottom RGB Tone Curve Panel
        RgbCurveEditorPanel(
            curvesState = state.curvesState,
            activeChannel = state.activeChannel,
            histogram = state.histogram,
            onSelectChannel = onSelectChannel,
            onUpdateChannelPoints = onUpdateChannelPoints,
            onResetChannel = onResetChannel,
            onResetAll = onResetAll
        )
    }

    if (state.isExportDialogVisible && state.hasImage) {
        ExportDialog(
            originalWidth = state.fullWidth,
            originalHeight = state.fullHeight,
            onDismiss = onDismissExportDialog,
            onConfirmExport = onConfirmExport
        )
    }
}

@Composable
private fun TopBar(
    state: EditorUiState,
    onOpenFile: () -> Unit,
    onSetShowBefore: (Boolean) -> Unit,
    onOpenExport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MonoSurface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Open file button + file info
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MonoSurfaceElevated)
                    .border(1.dp, MonoBorder, RoundedCornerShape(4.dp))
                    .clickable(onClick = onOpenFile)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "OPEN RAW",
                    color = MonoTextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            if (state.hasImage) {
                Column {
                    Text(
                        text = state.fileName,
                        color = MonoTextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    val badge = if (state.isLibRaw16Bit) "16-BIT LIBRAW" else "16-BIT PIPE"
                    Text(
                        text = "${state.fullWidth}×${state.fullHeight} px • $badge",
                        color = MonoTextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Right: Before/After hold button + Export button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.hasImage) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (state.isShowingBefore) MonoTextPrimary else Color.Transparent)
                        .border(1.dp, MonoBorder, RoundedCornerShape(4.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onSetShowBefore(true)
                                    tryAwaitRelease()
                                    onSetShowBefore(false)
                                }
                            )
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "BEFORE",
                        color = if (state.isShowingBefore) MonoBackground else MonoTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MonoTextPrimary)
                        .clickable(onClick = onOpenExport)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "EXPORT",
                        color = MonoBackground,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImagePreview(
    bitmap: android.graphics.Bitmap,
    isBefore: Boolean
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 6f)
        scale = newScale
        offset = if (newScale <= 1.01f) {
            Offset.Zero
        } else {
            offset + panChange
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1.05f) 1f else 2.5f
                        offset = Offset.Zero
                    }
                )
            }
            .transformable(state = transformState),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = remember(bitmap) { bitmap.asImageBitmap() },
            contentDescription = "RAW Preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )

        if (isBefore) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .border(1.dp, MonoBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "ORIGINAL (BEFORE)",
                    color = MonoTextPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun EmptyStatePrompt(onOpenFile: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MonoBorder, RoundedCornerShape(8.dp))
            .background(MonoSurface)
            .clickable(onClick = onOpenFile)
            .padding(horizontal = 32.dp, vertical = 28.dp)
    ) {
        Text(
            text = "OPEN RAW IMAGE",
            color = MonoTextPrimary,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "SONY .ARW / .DNG / .CR2 / .NEF / .RAF",
            color = MonoTextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "16-BIT LIBRAW PIPELINE",
            color = MonoTextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
