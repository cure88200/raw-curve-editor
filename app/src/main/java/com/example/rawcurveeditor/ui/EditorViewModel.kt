package com.example.rawcurveeditor.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rawcurveeditor.data.RawImageRepository
import com.example.rawcurveeditor.domain.CurveChannel
import com.example.rawcurveeditor.domain.CurvePoint
import com.example.rawcurveeditor.domain.ExportFormat
import com.example.rawcurveeditor.domain.HistogramData
import com.example.rawcurveeditor.domain.RgbCurvesState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

private const val MAX_HISTORY_STEPS = 50

@Immutable
data class PhotoSessionItem(
    val id: Int,
    val uri: Uri,
    val fileName: String,
    val fullWidth: Int,
    val fullHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val isLibRaw16Bit: Boolean,
    val originalPreviewBitmap: Bitmap,
    val thumbnailBitmap: Bitmap,
    val histogram: HistogramData,
    val curvesState: RgbCurvesState = RgbCurvesState(),
    val undoStack: List<RgbCurvesState> = emptyList(),
    val redoStack: List<RgbCurvesState> = emptyList()
) {
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
}

data class EditorUiState(
    val isLoading: Boolean = false,
    val loadingProgress: Float = 0f,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportProgressDetail: String = "",
    val photos: List<PhotoSessionItem> = emptyList(),
    val selectedPhotoId: Int = -1,
    val previewBitmap: Bitmap? = null,
    val previewRevision: Long = 0L,
    val activeChannel: CurveChannel = CurveChannel.MASTER,
    val clipboardCurves: RgbCurvesState? = null,
    val isShowingBefore: Boolean = false,
    val isExportDialogVisible: Boolean = false,
    val isApplyAllConfirmVisible: Boolean = false,
    val isPasteConfirmVisible: Boolean = false,
    val isExitConfirmVisible: Boolean = false,
    val statusMessage: String? = null
) {
    val activePhoto: PhotoSessionItem?
        get() = photos.firstOrNull { it.id == selectedPhotoId } ?: photos.firstOrNull()

    val hasImage: Boolean
        get() = activePhoto != null && previewBitmap != null
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private data class PreviewRenderRequest(
        val photoId: Int,
        val previewWidth: Int,
        val previewHeight: Int,
        val curvesState: RgbCurvesState
    )

    private val repository = RawImageRepository(application.applicationContext)
    private val nextPhotoId = AtomicInteger(1)

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val previewRenderChannel = Channel<PreviewRenderRequest>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (req in previewRenderChannel) {
                val res = repository.renderPreview(
                    photoId = req.photoId,
                    previewWidth = req.previewWidth,
                    previewHeight = req.previewHeight,
                    curvesState = req.curvesState
                )
                if (res != null) {
                    _uiState.update { state ->
                        val updatedPhotos = state.photos.map { item ->
                            if (item.id == req.photoId) {
                                item.copy(histogram = res.histogram)
                            } else {
                                item
                            }
                        }
                        if (state.activePhoto?.id == req.photoId) {
                            state.copy(
                                photos = updatedPhotos,
                                previewBitmap = res.bitmap,
                                previewRevision = state.previewRevision + 1L
                            )
                        } else {
                            state.copy(photos = updatedPhotos)
                        }
                    }
                }
            }
        }
    }

    fun openRawUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    loadingProgress = 0f,
                    statusMessage = "RAWデータ (16-bit) 展開中 (0/${uris.size})..."
                )
            }

            val newlyLoaded = mutableListOf<PhotoSessionItem>()
            var lastError: String? = null

            uris.forEachIndexed { index, uri ->
                _uiState.update {
                    it.copy(
                        loadingProgress = index.toFloat() / uris.size.coerceAtLeast(1),
                        statusMessage = "RAWデータ (16-bit) 展開中 (${index + 1}/${uris.size})..."
                    )
                }
                val assignedId = nextPhotoId.getAndIncrement()
                val res = repository.loadFromUri(assignedId, uri)
                res.fold(
                    onSuccess = { info ->
                        newlyLoaded.add(
                            PhotoSessionItem(
                                id = info.photoId,
                                uri = info.uri,
                                fileName = info.displayName,
                                fullWidth = info.fullWidth,
                                fullHeight = info.fullHeight,
                                previewWidth = info.previewWidth,
                                previewHeight = info.previewHeight,
                                isLibRaw16Bit = info.isDecodedByLibRaw,
                                originalPreviewBitmap = info.initialPreviewBitmap,
                                thumbnailBitmap = info.thumbnailBitmap,
                                histogram = info.histogram,
                                curvesState = RgbCurvesState()
                            )
                        )
                    },
                    onFailure = { err ->
                        lastError = err.message
                    }
                )
            }

            if (newlyLoaded.isNotEmpty()) {
                val targetActive = newlyLoaded.last()
                _uiState.update { state ->
                    val combined = state.photos + newlyLoaded
                    state.copy(
                        isLoading = false,
                        loadingProgress = 1f,
                        photos = combined,
                        selectedPhotoId = targetActive.id,
                        previewBitmap = targetActive.originalPreviewBitmap,
                        previewRevision = state.previewRevision + 1L,
                        statusMessage = if (newlyLoaded.size > 1) {
                            "${newlyLoaded.size} 枚の画像を読み込みました"
                        } else {
                            null
                        }
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "読み込みエラー: ${lastError ?: "不明なエラー"}"
                    )
                }
            }
        }
    }

    fun selectPhoto(photoId: Int) {
        val current = _uiState.value
        val target = current.photos.firstOrNull { it.id == photoId } ?: return
        if (current.selectedPhotoId == photoId) return

        current.activePhoto?.let { prev ->
            updateThumbnailAsync(prev.id, prev.previewWidth, prev.previewHeight, prev.curvesState)
        }

        _uiState.update {
            it.copy(
                selectedPhotoId = target.id,
                previewBitmap = target.originalPreviewBitmap,
                previewRevision = it.previewRevision + 1L
            )
        }
        enqueuePreviewRender(target.id, target.previewWidth, target.previewHeight, target.curvesState)
    }

    fun closePhoto(photoId: Int) {
        val current = _uiState.value
        val remaining = current.photos.filterNot { it.id == photoId }
        repository.removePhoto(photoId)

        if (remaining.isEmpty()) {
            _uiState.update {
                it.copy(
                    photos = emptyList(),
                    selectedPhotoId = -1,
                    previewBitmap = null,
                    previewRevision = it.previewRevision + 1L
                )
            }
            return
        }

        val nextActive = if (current.selectedPhotoId == photoId) {
            remaining.last()
        } else {
            remaining.firstOrNull { it.id == current.selectedPhotoId } ?: remaining.last()
        }

        _uiState.update {
            it.copy(
                photos = remaining,
                selectedPhotoId = nextActive.id,
                previewBitmap = nextActive.originalPreviewBitmap,
                previewRevision = it.previewRevision + 1L
            )
        }
        enqueuePreviewRender(nextActive.id, nextActive.previewWidth, nextActive.previewHeight, nextActive.curvesState)
    }

    fun selectChannel(channel: CurveChannel) {
        _uiState.update { it.copy(activeChannel = channel) }
    }

    /**
     * Called once at the beginning of a curve touch/drag gesture or point deletion
     * to push the pre-gesture state onto the undo stack.
     */
    fun recordUndoSnapshot() {
        val active = _uiState.value.activePhoto ?: return
        _uiState.update { state ->
            state.copy(
                photos = state.photos.map { item ->
                    if (item.id == active.id) {
                        val newUndo = (item.undoStack + item.curvesState).takeLast(MAX_HISTORY_STEPS)
                        item.copy(
                            undoStack = newUndo,
                            redoStack = emptyList()
                        )
                    } else {
                        item
                    }
                }
            )
        }
    }

    fun onCurveGestureFinished() {
        val active = _uiState.value.activePhoto ?: return
        updateThumbnailAsync(active.id, active.previewWidth, active.previewHeight, active.curvesState)
    }

    fun updateChannelPoints(channel: CurveChannel, newPoints: List<CurvePoint>) {
        val active = _uiState.value.activePhoto ?: return
        val updatedCurves = active.curvesState.withPoints(channel, newPoints)
        updateActivePhotoCurves(active.id, updatedCurves, pushUndo = false)
    }

    fun deleteChannelPoint(channel: CurveChannel, newPoints: List<CurvePoint>) {
        val active = _uiState.value.activePhoto ?: return
        val updatedCurves = active.curvesState.withPoints(channel, newPoints)
        updateActivePhotoCurves(active.id, updatedCurves, pushUndo = true)
        updateThumbnailAsync(active.id, active.previewWidth, active.previewHeight, updatedCurves)
    }

    fun resetChannel(channel: CurveChannel) {
        val active = _uiState.value.activePhoto ?: return
        val updatedCurves = active.curvesState.resetChannel(channel)
        if (updatedCurves == active.curvesState) return
        updateActivePhotoCurves(active.id, updatedCurves, pushUndo = true)
        updateThumbnailAsync(active.id, active.previewWidth, active.previewHeight, updatedCurves)
    }

    fun resetAllChannels() {
        val active = _uiState.value.activePhoto ?: return
        val updatedCurves = active.curvesState.resetAll()
        if (updatedCurves == active.curvesState) return
        updateActivePhotoCurves(active.id, updatedCurves, pushUndo = true)
        updateThumbnailAsync(active.id, active.previewWidth, active.previewHeight, updatedCurves)
    }

    fun undo() {
        val active = _uiState.value.activePhoto ?: return
        if (active.undoStack.isEmpty()) return

        val previousCurves = active.undoStack.last()
        val remainingUndo = active.undoStack.dropLast(1)
        val updatedRedo = (active.redoStack + active.curvesState).takeLast(MAX_HISTORY_STEPS)

        _uiState.update { state ->
            state.copy(
                photos = state.photos.map { item ->
                    if (item.id == active.id) {
                        item.copy(
                            curvesState = previousCurves,
                            undoStack = remainingUndo,
                            redoStack = updatedRedo
                        )
                    } else {
                        item
                    }
                }
            )
        }
        enqueuePreviewRender(active.id, active.previewWidth, active.previewHeight, previousCurves)
        updateThumbnailAsync(active.id, active.previewWidth, active.previewHeight, previousCurves)
    }

    fun redo() {
        val active = _uiState.value.activePhoto ?: return
        if (active.redoStack.isEmpty()) return

        val nextCurves = active.redoStack.last()
        val remainingRedo = active.redoStack.dropLast(1)
        val updatedUndo = (active.undoStack + active.curvesState).takeLast(MAX_HISTORY_STEPS)

        _uiState.update { state ->
            state.copy(
                photos = state.photos.map { item ->
                    if (item.id == active.id) {
                        item.copy(
                            curvesState = nextCurves,
                            undoStack = updatedUndo,
                            redoStack = remainingRedo
                        )
                    } else {
                        item
                    }
                }
            )
        }
        enqueuePreviewRender(active.id, active.previewWidth, active.previewHeight, nextCurves)
        updateThumbnailAsync(active.id, active.previewWidth, active.previewHeight, nextCurves)
    }

    fun copyCurves() {
        val active = _uiState.value.activePhoto ?: return
        _uiState.update {
            it.copy(
                clipboardCurves = active.curvesState,
                statusMessage = "カーブ設定をコピーしました"
            )
        }
    }

    fun requestPasteCurves() {
        val state = _uiState.value
        val active = state.activePhoto ?: return
        val copied = state.clipboardCurves ?: return

        // If the target image already has curve edits (and differs from clipboard), ask for confirmation
        if (active.curvesState.isModified && active.curvesState != copied) {
            _uiState.update { it.copy(isPasteConfirmVisible = true) }
        } else {
            confirmPasteCurves()
        }
    }

    fun showPasteConfirmDialog(visible: Boolean) {
        _uiState.update { it.copy(isPasteConfirmVisible = visible) }
    }

    fun confirmPasteCurves() {
        val state = _uiState.value
        val active = state.activePhoto ?: return
        val copied = state.clipboardCurves ?: return

        _uiState.update { it.copy(isPasteConfirmVisible = false) }
        updateActivePhotoCurves(active.id, copied, pushUndo = true)
        updateThumbnailAsync(active.id, active.previewWidth, active.previewHeight, copied)
        _uiState.update {
            it.copy(statusMessage = "カーブ設定を貼り付けました")
        }
    }

    fun showApplyAllConfirmDialog(visible: Boolean) {
        _uiState.update { it.copy(isApplyAllConfirmVisible = visible) }
    }

    fun confirmApplyCurvesToAllPhotos() {
        val state = _uiState.value
        val active = state.activePhoto ?: return
        if (state.photos.size <= 1) {
            _uiState.update { it.copy(isApplyAllConfirmVisible = false) }
            return
        }

        val sourceCurves = active.curvesState
        _uiState.update { current ->
            current.copy(
                isApplyAllConfirmVisible = false,
                photos = current.photos.map { item ->
                    if (item.curvesState == sourceCurves) {
                        item
                    } else {
                        item.copy(
                            curvesState = sourceCurves,
                            undoStack = (item.undoStack + item.curvesState).takeLast(MAX_HISTORY_STEPS),
                            redoStack = emptyList()
                        )
                    }
                },
                statusMessage = "全 ${current.photos.size} 枚の画像にカーブを適用しました"
            )
        }

        viewModelScope.launch {
            _uiState.value.photos.forEach { item ->
                val res = repository.renderFreshPreviewSnapshot(
                    photoId = item.id,
                    previewWidth = item.previewWidth,
                    previewHeight = item.previewHeight,
                    curvesState = sourceCurves
                )
                if (res != null) {
                    val thumb = repository.createThumbnail(res.bitmap, 160)
                    res.bitmap.recycle()
                    _uiState.update { s ->
                        s.copy(
                            photos = s.photos.map { p ->
                                if (p.id == item.id) {
                                    p.copy(
                                        thumbnailBitmap = thumb,
                                        histogram = res.histogram
                                    )
                                } else {
                                    p
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    fun showExitConfirmDialog(visible: Boolean) {
        _uiState.update { it.copy(isExitConfirmVisible = visible) }
    }

    fun setShowBefore(showBefore: Boolean) {
        _uiState.update { it.copy(isShowingBefore = showBefore) }
    }

    fun showExportDialog(visible: Boolean) {
        _uiState.update { it.copy(isExportDialogVisible = visible) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun exportImages(
        scalePercent: Int,
        format: ExportFormat,
        jpegQuality: Int,
        exportAll: Boolean
    ) {
        val snapshot = _uiState.value
        val targets = if (exportAll) {
            snapshot.photos
        } else {
            listOfNotNull(snapshot.activePhoto)
        }
        if (targets.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExportDialogVisible = false,
                    isExporting = true,
                    exportProgress = 0.01f,
                    exportProgressDetail = "1 / ${targets.size} • 0%"
                )
            }

            var successCount = 0
            var lastError: String? = null

            targets.forEachIndexed { index, photo ->
                val result = repository.exportImage(
                    photoId = photo.id,
                    uri = photo.uri,
                    originalName = photo.fileName,
                    fullWidth = photo.fullWidth,
                    fullHeight = photo.fullHeight,
                    curvesState = photo.curvesState,
                    scalePercent = scalePercent,
                    format = format,
                    jpegQuality = jpegQuality,
                    onProgress = { singlePhotoProgress ->
                        val overall = ((index + singlePhotoProgress) / targets.size.toFloat()).coerceIn(0f, 1f)
                        val pctInt = (overall * 100).roundToInt()
                        _uiState.update { s ->
                            s.copy(
                                exportProgress = overall,
                                exportProgressDetail = if (targets.size > 1) {
                                    "${index + 1} / ${targets.size} (${photo.fileName}) • $pctInt%"
                                } else {
                                    "${photo.fileName} • $pctInt%"
                                }
                            )
                        }
                    }
                )

                result.fold(
                    onSuccess = { successCount++ },
                    onFailure = { err -> lastError = err.message }
                )
            }

            val summaryMsg = if (successCount == targets.size) {
                if (targets.size == 1) {
                    val p = targets.first()
                    val outW = (p.fullWidth * (scalePercent / 100f)).roundToInt()
                    val outH = (p.fullHeight * (scalePercent / 100f)).roundToInt()
                    "保存完了: Pictures/RawCurveEditor (${outW}×${outH}px)"
                } else {
                    "全 $successCount 枚の画像を Pictures/RawCurveEditor に保存しました"
                }
            } else {
                "一部失敗 ($successCount/${targets.size} 成功): ${lastError ?: ""}"
            }

            _uiState.update {
                it.copy(
                    isExporting = false,
                    exportProgress = 1f,
                    statusMessage = summaryMsg
                )
            }
        }
    }

    private fun updateActivePhotoCurves(
        photoId: Int,
        updatedCurves: RgbCurvesState,
        pushUndo: Boolean
    ) {
        var previewW = 0
        var previewH = 0
        _uiState.update { current ->
            val updatedPhotos = current.photos.map { item ->
                if (item.id == photoId) {
                    previewW = item.previewWidth
                    previewH = item.previewHeight
                    if (pushUndo && item.curvesState != updatedCurves) {
                        val newUndo = (item.undoStack + item.curvesState).takeLast(MAX_HISTORY_STEPS)
                        item.copy(
                            curvesState = updatedCurves,
                            undoStack = newUndo,
                            redoStack = emptyList()
                        )
                    } else {
                        item.copy(curvesState = updatedCurves)
                    }
                } else {
                    item
                }
            }
            current.copy(photos = updatedPhotos)
        }
        if (previewW > 0 && previewH > 0) {
            enqueuePreviewRender(photoId, previewW, previewH, updatedCurves)
        }
    }

    private fun enqueuePreviewRender(
        photoId: Int,
        previewWidth: Int,
        previewHeight: Int,
        curvesState: RgbCurvesState
    ) {
        previewRenderChannel.trySend(
            PreviewRenderRequest(
                photoId = photoId,
                previewWidth = previewWidth,
                previewHeight = previewHeight,
                curvesState = curvesState
            )
        )
    }

    private fun updateThumbnailAsync(
        photoId: Int,
        previewWidth: Int,
        previewHeight: Int,
        curvesState: RgbCurvesState
    ) {
        viewModelScope.launch {
            val res = repository.renderFreshPreviewSnapshot(photoId, previewWidth, previewHeight, curvesState)
            if (res != null) {
                val thumb = repository.createThumbnail(res.bitmap, 160)
                res.bitmap.recycle()
                _uiState.update { s ->
                    s.copy(
                        photos = s.photos.map { p ->
                            if (p.id == photoId) {
                                p.copy(
                                    thumbnailBitmap = thumb,
                                    histogram = res.histogram
                                )
                            } else {
                                p
                            }
                        }
                    )
                }
            }
        }
    }
}
