package com.example.rawcurveeditor.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rawcurveeditor.data.RawImageRepository
import com.example.rawcurveeditor.domain.CurveChannel
import com.example.rawcurveeditor.domain.CurvePoint
import com.example.rawcurveeditor.domain.ExportFormat
import com.example.rawcurveeditor.domain.HistogramData
import com.example.rawcurveeditor.domain.RgbCurvesState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val fileName: String = "",
    val fullWidth: Int = 0,
    val fullHeight: Int = 0,
    val isLibRaw16Bit: Boolean = false,
    val originalPreviewBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val histogram: HistogramData = HistogramData(),
    val curvesState: RgbCurvesState = RgbCurvesState(),
    val activeChannel: CurveChannel = CurveChannel.MASTER,
    val isShowingBefore: Boolean = false,
    val isExportDialogVisible: Boolean = false,
    val statusMessage: String? = null
) {
    val hasImage: Boolean get() = previewBitmap != null && fullWidth > 0 && fullHeight > 0
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RawImageRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var previewRenderJob: Job? = null

    fun openRawUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    statusMessage = "RAW (16-bit) 現像中..."
                )
            }
            val result = repository.loadFromUri(uri)
            result.fold(
                onSuccess = { info ->
                    val resetCurves = RgbCurvesState()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            fileName = info.displayName,
                            fullWidth = info.fullWidth,
                            fullHeight = info.fullHeight,
                            isLibRaw16Bit = info.isDecodedByLibRaw,
                            originalPreviewBitmap = info.initialPreviewBitmap,
                            previewBitmap = info.initialPreviewBitmap,
                            histogram = info.histogram,
                            curvesState = resetCurves,
                            statusMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "読み込みエラー: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun selectChannel(channel: CurveChannel) {
        _uiState.update { it.copy(activeChannel = channel) }
    }

    fun updateChannelPoints(channel: CurveChannel, newPoints: List<CurvePoint>) {
        val updatedCurves = _uiState.value.curvesState.withPoints(channel, newPoints)
        _uiState.update { it.copy(curvesState = updatedCurves) }
        schedulePreviewRender(updatedCurves)
    }

    fun resetChannel(channel: CurveChannel) {
        val updatedCurves = _uiState.value.curvesState.resetChannel(channel)
        _uiState.update { it.copy(curvesState = updatedCurves) }
        schedulePreviewRender(updatedCurves)
    }

    fun resetAllChannels() {
        val updatedCurves = _uiState.value.curvesState.resetAll()
        _uiState.update { it.copy(curvesState = updatedCurves) }
        schedulePreviewRender(updatedCurves)
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

    fun exportImage(scalePercent: Int, format: ExportFormat, jpegQuality: Int) {
        val snapshot = _uiState.value
        if (!snapshot.hasImage) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExportDialogVisible = false,
                    isExporting = true,
                    statusMessage = "書き出し中 ($scalePercent% / ${format.label})..."
                )
            }

            val result = repository.exportImage(
                originalName = snapshot.fileName,
                curvesState = snapshot.curvesState,
                scalePercent = scalePercent,
                format = format,
                jpegQuality = jpegQuality
            )

            result.fold(
                onSuccess = {
                    val outW = (snapshot.fullWidth * (scalePercent / 100f)).toInt()
                    val outH = (snapshot.fullHeight * (scalePercent / 100f)).toInt()
                    _uiState.update { state ->
                        state.copy(
                            isExporting = false,
                            statusMessage = "Pictures/RawCurveEditor に保存しました (${outW}×${outH}px)"
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update { state ->
                        state.copy(
                            isExporting = false,
                            statusMessage = "書き出し失敗: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    private fun schedulePreviewRender(curvesState: RgbCurvesState) {
        previewRenderJob?.cancel()
        previewRenderJob = viewModelScope.launch {
            val rendered = repository.renderPreview(curvesState)
            if (rendered != null) {
                _uiState.update { it.copy(previewBitmap = rendered) }
            }
        }
    }
}
