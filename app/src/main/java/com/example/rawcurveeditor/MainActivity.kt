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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.rawcurveeditor.domain.CurveChannel
import com.example.rawcurveeditor.domain.CurvePoint
import com.example.rawcurveeditor.domain.ExportFormat
import com.example.rawcurveeditor.domain.HistogramData
import com.example.rawcurveeditor.domain.RgbCurvesState
import com.example.rawcurveeditor.ui.EditorUiState
import com.example.rawcurveeditor.ui.EditorViewModel
import com.example.rawcurveeditor.ui.PhotoSessionItem
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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

                val multiFilePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        viewModel.openRawUris(uris)
                    }
                }

                // Auto-dismiss status toast messages after 2.5 seconds
                LaunchedEffect(state.statusMessage, state.isLoading, state.isExporting) {
                    if (!state.isLoading && !state.isExporting && state.statusMessage != null) {
                        delay(2500L)
                        viewModel.clearStatusMessage()
                    }
                }

                RawCurveEditorScreen(
                    state = state,
                    onOpenFiles = {
                        multiFilePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onSelectPhoto = viewModel::selectPhoto,
                    onClosePhoto = viewModel::closePhoto,
                    onSelectChannel = viewModel::selectChannel,
                    onUpdateChannelPoints = viewModel::updateChannelPoints,
                    onResetChannel = viewModel::resetChannel,
                    onResetAll = viewModel::resetAllChannels,
                    onCopyCurves = viewModel::copyCurves,
                    onPasteCurves = viewModel::pasteCurves,
                    onRequestApplyToAllPhotos = { viewModel.showApplyAllConfirmDialog(true) },
                    onDismissApplyAllConfirm = { viewModel.showApplyAllConfirmDialog(false) },
                    onConfirmApplyToAllPhotos = viewModel::confirmApplyCurvesToAllPhotos,
                    onSetShowBefore = viewModel::setShowBefore,
                    onOpenExportDialog = { viewModel.showExportDialog(true) },
                    onDismissExportDialog = { viewModel.showExportDialog(false) },
                    onConfirmExport = viewModel::exportImages,
                    onDismissStatus = viewModel::clearStatusMessage
                )
            }
        }
    }
}

@Composable
private fun RawCurveEditorScreen(
    state: EditorUiState,
    onOpenFiles: () -> Unit,
    onSelectPhoto: (Int) -> Unit,
    onClosePhoto: (Int) -> Unit,
    onSelectChannel: (CurveChannel) -> Unit,
    onUpdateChannelPoints: (CurveChannel, List<CurvePoint>) -> Unit,
    onResetChannel: (CurveChannel) -> Unit,
    onResetAll: () -> Unit,
    onCopyCurves: () -> Unit,
    onPasteCurves: () -> Unit,
    onRequestApplyToAllPhotos: () -> Unit,
    onDismissApplyAllConfirm: () -> Unit,
    onConfirmApplyToAllPhotos: () -> Unit,
    onSetShowBefore: (Boolean) -> Unit,
    onOpenExportDialog: () -> Unit,
    onDismissExportDialog: () -> Unit,
    onConfirmExport: (Int, ExportFormat, Int, Boolean) -> Unit,
    onDismissStatus: () -> Unit
) {
    val activePhoto = state.activePhoto

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopBar(
            state = state,
            onOpenFiles = onOpenFiles,
            onSetShowBefore = onSetShowBefore,
            onOpenExport = onOpenExportDialog
        )

        HorizontalDivider(color = MonoBorder, thickness = 1.dp)

        if (state.photos.isNotEmpty()) {
            PhotoFilmstripBar(
                photos = state.photos,
                selectedPhotoId = activePhoto?.id ?: -1,
                onSelectPhoto = onSelectPhoto,
                onClosePhoto = onClosePhoto,
                onAddMorePhotos = onOpenFiles
            )
            HorizontalDivider(color = MonoBorder, thickness = 1.dp)
        }

        // Center Image Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MonoBackground)
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            val activeBitmap = if (state.isShowingBefore) {
                activePhoto?.originalPreviewBitmap
            } else {
                state.previewBitmap
            }

            if (activeBitmap != null) {
                ZoomableImagePreview(
                    photoId = activePhoto?.id ?: -1,
                    bitmap = activeBitmap,
                    revision = if (state.isShowingBefore) -1L else state.previewRevision,
                    isBefore = state.isShowingBefore
                )
            } else if (!state.isLoading) {
                EmptyStatePrompt(onOpenFiles = onOpenFiles)
            }

            // Determinate Progress Overlay for Loading or Exporting
            if (state.isLoading || state.isExporting) {
                ProgressModalOverlay(
                    title = if (state.isExporting) "EXPORTING IMAGE" else "DEVELOPING RAW",
                    detail = if (state.isExporting) {
                        state.exportProgressDetail
                    } else {
                        state.statusMessage ?: "PROCESSING..."
                    },
                    progress = if (state.isExporting) state.exportProgress else state.loadingProgress
                )
            }

            // Auto-dismissing status toast banner
            if (!state.isLoading && !state.isExporting && state.statusMessage != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MonoSurfaceElevated.copy(alpha = 0.95f))
                        .border(1.dp, MonoBorder, RoundedCornerShape(4.dp))
                        .clickable { onDismissStatus() }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
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
            activePhotoId = activePhoto?.id ?: -1,
            curvesState = activePhoto?.curvesState ?: RgbCurvesState(),
            activeChannel = state.activeChannel,
            histogram = activePhoto?.histogram ?: HistogramData(),
            hasCopiedCurves = state.clipboardCurves != null,
            photoCount = state.photos.size,
            onSelectChannel = onSelectChannel,
            onUpdateChannelPoints = onUpdateChannelPoints,
            onResetChannel = onResetChannel,
            onResetAll = onResetAll,
            onCopyCurves = onCopyCurves,
            onPasteCurves = onPasteCurves,
            onRequestApplyToAllPhotos = onRequestApplyToAllPhotos
        )
    }

    if (state.isApplyAllConfirmVisible && state.photos.size > 1) {
        ApplyAllConfirmDialog(
            photoCount = state.photos.size,
            onDismiss = onDismissApplyAllConfirm,
            onConfirm = onConfirmApplyToAllPhotos
        )
    }

    if (state.isExportDialogVisible && activePhoto != null) {
        ExportDialog(
            originalWidth = activePhoto.fullWidth,
            originalHeight = activePhoto.fullHeight,
            totalPhotoCount = state.photos.size,
            onDismiss = onDismissExportDialog,
            onConfirmExport = onConfirmExport
        )
    }
}

@Composable
private fun ApplyAllConfirmDialog(
    photoCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
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
                    text = "APPLY TO ALL PHOTOS",
                    color = MonoTextPrimary,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "現在のカーブ設定を開いているすべての画像（全 ${photoCount} 枚）に適用しますか？",
                    color = MonoTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )

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
                            .clickable(onClick = onConfirm)
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "APPLY ALL",
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

@Composable
private fun TopBar(
    state: EditorUiState,
    onOpenFiles: () -> Unit,
    onSetShowBefore: (Boolean) -> Unit,
    onOpenExport: () -> Unit
) {
    val activePhoto = state.activePhoto

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MonoSurface)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MonoSurfaceElevated)
                    .border(1.dp, MonoBorder, RoundedCornerShape(4.dp))
                    .clickable(onClick = onOpenFiles)
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "+ OPEN",
                    color = MonoTextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            if (activePhoto != null) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activePhoto.fileName,
                        color = MonoTextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val badge = if (activePhoto.isLibRaw16Bit) "16-BIT RAW" else "16-BIT PIPE"
                    Text(
                        text = "${activePhoto.fullWidth}×${activePhoto.fullHeight}px • $badge (${state.photos.size})",
                        color = MonoTextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (activePhoto != null) {
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
                        .padding(horizontal = 10.dp, vertical = 6.dp)
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
                        .padding(horizontal = 13.dp, vertical = 6.dp)
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
private fun PhotoFilmstripBar(
    photos: List<PhotoSessionItem>,
    selectedPhotoId: Int,
    onSelectPhoto: (Int) -> Unit,
    onClosePhoto: (Int) -> Unit,
    onAddMorePhotos: () -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MonoSurface)
            .padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(items = photos, key = { it.id }) { item ->
            val isSelected = item.id == selectedPhotoId
            val borderColor = if (isSelected) MonoTextPrimary else MonoBorder

            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 46.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MonoBackground)
                    .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(4.dp))
                    .clickable { onSelectPhoto(item.id) }
            ) {
                Image(
                    bitmap = remember(item.thumbnailBitmap) { item.thumbnailBitmap.asImageBitmap() },
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (item.curvesState.isModified) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MonoTextPrimary)
                    )
                }

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .clickable { onClosePhoto(item.id) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            color = MonoTextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 46.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MonoSurfaceElevated)
                    .border(1.dp, MonoBorder, RoundedCornerShape(4.dp))
                    .clickable(onClick = onAddMorePhotos),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    color = MonoTextPrimary,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ProgressModalOverlay(
    title: String,
    detail: String,
    progress: Float
) {
    val clamped = progress.coerceIn(0f, 1f)
    val pct = (clamped * 100).roundToInt()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .pointerInput(Unit) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MonoSurface)
                .border(1.dp, MonoBorder, RoundedCornerShape(8.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = MonoTextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$pct%",
                    color = MonoTextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { clamped },
                color = MonoTextPrimary,
                trackColor = MonoBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = detail,
                color = MonoTextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ZoomableImagePreview(
    photoId: Int,
    bitmap: android.graphics.Bitmap,
    revision: Long,
    isBefore: Boolean
) {
    var scale by remember(photoId) { mutableFloatStateOf(1f) }
    var offset by remember(photoId) { mutableStateOf(Offset.Zero) }

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
            .pointerInput(photoId) {
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
        key(revision) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "RAW Preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        }

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
private fun EmptyStatePrompt(onOpenFiles: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MonoBorder, RoundedCornerShape(8.dp))
            .background(MonoSurface)
            .clickable(onClick = onOpenFiles)
            .padding(horizontal = 32.dp, vertical = 28.dp)
    ) {
        Text(
            text = "OPEN RAW / IMAGES",
            color = MonoTextPrimary,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "SONY .ARW / .DNG / .CR2 / .NEF / .JPG / .PNG",
            color = MonoTextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "MULTI-FILE SELECTION SUPPORTED",
            color = MonoTextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
