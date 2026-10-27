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
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class LoadedRawImageInfo(
    val displayName: String,
    val fullWidth: Int,
    val fullHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val isDecodedByLibRaw: Boolean,
    val initialPreviewBitmap: Bitmap,
    val histogram: HistogramData
)

class RawImageRepository(private val context: Context) {

    private var previewWidth: Int = 0
    private var previewHeight: Int = 0
    private var fullWidth: Int = 0
    private var fullHeight: Int = 0

    suspend fun loadFromUri(
        uri: Uri,
        maxPreviewDimension: Int = 1600
    ): Result<LoadedRawImageInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(uri) ?: "RAW_Image.ARW"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("ファイルを読み込めませんでした")

            var decodedByLibRaw = false
            var dims: IntArray? = null

            if (NativeRawBridge.isNativeLoaded) {
                dims = NativeRawBridge.decodeRawBytesNative(bytes, maxPreviewDimension)
                if (dims != null && dims.size == 4 && dims[0] > 0 && dims[1] > 0) {
                    decodedByLibRaw = true
                }
            }

            if (dims == null) {
                // Fallback for standard images or if LibRaw couldn't parse a specific container
                val fallbackBitmap = decodeFallbackBitmap(bytes)
                    ?: error("未対応のRAWまたは画像形式です ($fileName)")
                dims = NativeRawBridge.loadBitmapToNative(fallbackBitmap, maxPreviewDimension)
                    ?: error("ネイティブバッファへの展開に失敗しました")
                fallbackBitmap.recycle()
            }

            fullWidth = dims[0]
            fullHeight = dims[1]
            previewWidth = dims[2]
            previewHeight = dims[3]

            val previewBitmap = Bitmap.createBitmap(
                previewWidth,
                previewHeight,
                Bitmap.Config.ARGB_8888
            )

            val linearLut = CurveSplineEngine.buildCombinedLut16(RgbCurvesState())
            NativeRawBridge.renderPreviewWithLutNative(
                linearLut.rLut,
                linearLut.gLut,
                linearLut.bLut,
                previewBitmap
            )

            val histogram = computeHistogram()

            LoadedRawImageInfo(
                displayName = fileName,
                fullWidth = fullWidth,
                fullHeight = fullHeight,
                previewWidth = previewWidth,
                previewHeight = previewHeight,
                isDecodedByLibRaw = decodedByLibRaw,
                initialPreviewBitmap = previewBitmap,
                histogram = histogram
            )
        }
    }

    suspend fun renderPreview(curvesState: RgbCurvesState): Bitmap? = withContext(Dispatchers.Default) {
        if (previewWidth <= 0 || previewHeight <= 0 || !NativeRawBridge.isNativeLoaded) {
            return@withContext null
        }
        val lut = CurveSplineEngine.buildCombinedLut16(curvesState)
        val outBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val ok = NativeRawBridge.renderPreviewWithLutNative(
            lut.rLut,
            lut.gLut,
            lut.bLut,
            outBitmap
        )
        if (ok) outBitmap else null
    }

    suspend fun exportImage(
        originalName: String,
        curvesState: RgbCurvesState,
        scalePercent: Int,
        format: ExportFormat,
        jpegQuality: Int
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            require(fullWidth > 0 && fullHeight > 0) { "画像が読み込まれていません" }
            val clampedPercent = scalePercent.coerceIn(5, 400)
            val scale = clampedPercent / 100.0f
            val targetW = max(1, (fullWidth * scale).roundToInt())
            val targetH = max(1, (fullHeight * scale).roundToInt())

            val lut = CurveSplineEngine.buildCombinedLut16(curvesState)
            val exportBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)

            val rendered = NativeRawBridge.renderExportNative(
                lut.rLut,
                lut.gLut,
                lut.bLut,
                exportBitmap
            )
            if (!rendered) {
                exportBitmap.recycle()
                error("書き出しレンダリングに失敗しました")
            }

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

            itemUri
        }
    }

    private fun computeHistogram(): HistogramData {
        val raw = NativeRawBridge.computeHistogramNative() ?: return HistogramData()
        if (raw.size < 256 * 4) return HistogramData()

        val r = raw.copyOfRange(0, 256)
        val g = raw.copyOfRange(256, 512)
        val b = raw.copyOfRange(512, 768)
        val luma = raw.copyOfRange(768, 1024)

        // Exclude extreme clipping bins (0 and 255) when normalizing vertical scale so the curve graph looks balanced
        var maxVal = 1
        for (i in 2..253) {
            maxVal = maxOf(maxVal, r[i], g[i], b[i], luma[i])
        }
        return HistogramData(r = r, g = g, b = b, luma = luma, maxCount = maxVal)
    }

    private fun decodeFallbackBitmap(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (bmp == null) {
            // Try embedded JPEG preview inside RAW via ExifInterface
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
