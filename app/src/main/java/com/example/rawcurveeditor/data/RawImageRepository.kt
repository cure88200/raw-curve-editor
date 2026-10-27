package com.example.rawcurveeditor.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.example.rawcurveeditor.domain.CurveSplineEngine
import com.example.rawcurveeditor.domain.ExportFormat
import com.example.rawcurveeditor.domain.HistogramData
import com.example.rawcurveeditor.domain.RgbCurvesState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

data class LoadedRawImageInfo(
    val photoId: Int,
    val uri: Uri,
    val displayName: String,
    val fullWidth: Int,
    val fullHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val isDecodedByLibRaw: Boolean,
    val initialPreviewBitmap: Bitmap,
    val thumbnailBitmap: Bitmap,
    val histogram: HistogramData
)

data class PreviewRenderResult(
    val bitmap: Bitmap,
    val histogram: HistogramData
)

class RawImageRepository(private val context: Context) {

    private class DoubleBitmapBuffer(width: Int, height: Int) {
        val buffers = arrayOf(
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        )
        val histBuffer = IntArray(1024)
        var index = 0

        @Synchronized
        fun next(): Bitmap {
            index = (index + 1) and 1
            return buffers[index]
        }
    }

    private val doubleBuffers = ConcurrentHashMap<Int, DoubleBitmapBuffer>()

    suspend fun loadFromUri(
        photoId: Int,
        uri: Uri,
        maxPreviewDimension: Int = 1280
    ): Result<LoadedRawImageInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(uri) ?: "RAW_Image.ARW"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("ファイルを読み込めませんでした")

            var decodedByLibRaw = false
            var dims: IntArray? = null

            if (NativeRawBridge.isNativeLoaded) {
                dims = NativeRawBridge.decodeRawBytesNative(photoId, bytes, maxPreviewDimension)
                if (dims != null && dims.size == 4 && dims[0] > 0 && dims[1] > 0) {
                    decodedByLibRaw = true
                }
            }

            if (dims == null) {
                val fallbackBitmap = decodeFallbackBitmap(bytes)
                    ?: error("未対応のRAWまたは画像形式です ($fileName)")
                dims = NativeRawBridge.loadBitmapToNative(photoId, fallbackBitmap, maxPreviewDimension)
                    ?: error("ネイティブバッファへの展開に失敗しました")
                fallbackBitmap.recycle()
            }

            val fullW = dims[0]
            val fullH = dims[1]
            val prevW = dims[2]
            val prevH = dims[3]

            val initialPreview = Bitmap.createBitmap(prevW, prevH, Bitmap.Config.ARGB_8888)
            val rawHist = IntArray(1024)
            val linearTables = CurveSplineEngine.buildCurveTables1024(RgbCurvesState())
            NativeRawBridge.renderPreviewFastNative(
                photoId,
                linearTables.master,
                linearTables.red,
                linearTables.green,
                linearTables.blue,
                initialPreview,
                rawHist
            )

            doubleBuffers[photoId] = DoubleBitmapBuffer(prevW, prevH)
            val thumbnail = createThumbnail(initialPreview, 160)
            val histogram = parseHistogram(rawHist)

            LoadedRawImageInfo(
                photoId = photoId,
                uri = uri,
                displayName = fileName,
                fullWidth = fullW,
                fullHeight = fullH,
                previewWidth = prevW,
                previewHeight = prevH,
                isDecodedByLibRaw = decodedByLibRaw,
                initialPreviewBitmap = initialPreview,
                thumbnailBitmap = thumbnail,
                histogram = histogram
            )
        }
    }

    suspend fun renderPreview(
        photoId: Int,
        previewWidth: Int,
        previewHeight: Int,
        curvesState: RgbCurvesState
    ): PreviewRenderResult? = withContext(Dispatchers.Default) {
        if (previewWidth <= 0 || previewHeight <= 0 || !NativeRawBridge.isNativeLoaded) {
            return@withContext null
        }
        val tables = CurveSplineEngine.buildCurveTables1024(curvesState)
        val dbl = doubleBuffers.getOrPut(photoId) {
            DoubleBitmapBuffer(previewWidth, previewHeight)
        }
        val targetBitmap = dbl.next()
        val rawHist = dbl.histBuffer
        val ok = NativeRawBridge.renderPreviewFastNative(
            photoId,
            tables.master,
            tables.red,
            tables.green,
            tables.blue,
            targetBitmap,
            rawHist
        )
        if (ok) {
            PreviewRenderResult(
                bitmap = targetBitmap,
                histogram = parseHistogram(rawHist)
            )
        } else {
            null
        }
    }

    suspend fun renderFreshPreviewSnapshot(
        photoId: Int,
        previewWidth: Int,
        previewHeight: Int,
        curvesState: RgbCurvesState
    ): PreviewRenderResult? = withContext(Dispatchers.Default) {
        if (previewWidth <= 0 || previewHeight <= 0 || !NativeRawBridge.isNativeLoaded) {
            return@withContext null
        }
        val tables = CurveSplineEngine.buildCurveTables1024(curvesState)
        val targetBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val rawHist = IntArray(1024)
        val ok = NativeRawBridge.renderPreviewFastNative(
            photoId,
            tables.master,
            tables.red,
            tables.green,
            tables.blue,
            targetBitmap,
            rawHist
        )
        if (ok) {
            PreviewRenderResult(
                bitmap = targetBitmap,
                histogram = parseHistogram(rawHist)
            )
        } else {
            null
        }
    }

    fun removePhoto(photoId: Int) {
        doubleBuffers.remove(photoId)
        if (NativeRawBridge.isNativeLoaded) {
            NativeRawBridge.removePhotoNative(photoId)
        }
    }

    suspend fun exportImage(
        photoId: Int,
        uri: Uri,
        originalName: String,
        fullWidth: Int,
        fullHeight: Int,
        curvesState: RgbCurvesState,
        scalePercent: Int,
        format: ExportFormat,
        jpegQuality: Int,
        onProgress: (Float) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            require(fullWidth > 0 && fullHeight > 0) { "画像が読み込まれていません" }
            onProgress(0.02f)

            if (!NativeRawBridge.hasFullResLoadedNative(photoId)) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("元ファイルを読み込めませんでした")
                var dims = NativeRawBridge.decodeRawBytesNative(photoId, bytes, 0)
                if (dims == null) {
                    val fallback = decodeFallbackBitmap(bytes)
                        ?: error("画像の再展開に失敗しました")
                    dims = NativeRawBridge.loadBitmapToNative(photoId, fallback, 0)
                    fallback.recycle()
                }
                if (dims == null) error("ネイティブバッファの再展開に失敗しました")
            }

            onProgress(0.10f)

            val clampedPercent = scalePercent.coerceIn(5, 400)
            val scale = clampedPercent / 100.0f
            val targetW = max(1, (fullWidth * scale).roundToInt())
            val targetH = max(1, (fullHeight * scale).roundToInt())

            val tables = CurveSplineEngine.buildCurveTables1024(curvesState)
            val exportBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)

            val rendered = coroutineScope {
                val pollJob = launch {
                    while (isActive) {
                        val nativePct = NativeRawBridge.getExportProgressNative().coerceIn(0, 90)
                        val mapped = 0.10f + (nativePct / 90f) * 0.78f
                        onProgress(mapped)
                        delay(35)
                    }
                }
                try {
                    NativeRawBridge.renderExportFastNative(
                        photoId,
                        tables.master,
                        tables.red,
                        tables.green,
                        tables.blue,
                        exportBitmap
                    )
                } finally {
                    pollJob.cancel()
                }
            }

            if (!rendered) {
                exportBitmap.recycle()
                error("書き出しレンダリングに失敗しました")
            }

            onProgress(0.90f)

            val baseName = originalName.substringBeforeLast('.').ifBlank { "RAW_Edit" }
            val outputFileName = "${baseName}_${clampedPercent}pct_${targetW}x${targetH}.${format.extension}"

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, outputFileName)
                put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/RawCurveEditor"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = resolver.insert(collection, contentValues)
                ?: error("保存先ファイルの作成に失敗しました")

            try {
                resolver.openOutputStream(itemUri)?.use { outStream ->
                    val compressFormat = when (format) {
                        ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
                        ExportFormat.PNG -> Bitmap.CompressFormat.PNG
                    }
                    val quality = if (format == ExportFormat.JPEG) jpegQuality.coerceIn(1, 100) else 100
                    if (!exportBitmap.compress(compressFormat, quality, outStream)) {
                        error("画像のエンコードに失敗しました")
                    }
                } ?: error("出力ストリームを開けませんでした")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, doneValues, null, null)
                }
            } finally {
                exportBitmap.recycle()
            }

            onProgress(1.0f)
            itemUri
        }
    }

    fun createThumbnail(source: Bitmap, maxDim: Int = 160): Bitmap {
        val w = source.width
        val h = source.height
        val scale = maxDim.toFloat() / maxOf(w, h, 1)
        val tw = max(1, (w * scale).roundToInt())
        val th = max(1, (h * scale).roundToInt())
        return Bitmap.createScaledBitmap(source, tw, th, true)
    }

    private fun parseHistogram(raw: IntArray): HistogramData {
        if (raw.size < 256 * 4) return HistogramData()

        val r = raw.copyOfRange(0, 256)
        val g = raw.copyOfRange(256, 512)
        val b = raw.copyOfRange(512, 768)
        val luma = raw.copyOfRange(768, 1024)

        var maxVal = 1
        for (i in 2..253) {
            maxVal = maxOf(maxVal, r[i], g[i], b[i], luma[i])
        }
        if (maxVal <= 1) {
            for (i in 0..255) {
                maxVal = maxOf(maxVal, r[i], g[i], b[i], luma[i])
            }
        }
        return HistogramData(r = r, g = g, b = b, luma = luma, maxCount = maxVal)
    }

    private fun decodeFallbackBitmap(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (bmp == null) {
            val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
            val thumbBytes = exif?.thumbnailBytes
            if (thumbBytes != null) {
                bmp = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size, opts)
            }
        }
        if (bmp != null) {
            val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL
            bmp = applyExifOrientation(bmp, orientation)
        }
        return bmp
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }
}
