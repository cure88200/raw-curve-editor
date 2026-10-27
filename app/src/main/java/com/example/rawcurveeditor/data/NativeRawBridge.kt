package com.example.rawcurveeditor.data

import android.graphics.Bitmap

object NativeRawBridge {
    var isNativeLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("rawcurve")
            isNativeLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            isNativeLoaded = false
        }
    }

    external fun decodeRawBytesNative(
        photoId: Int,
        rawBytes: ByteArray,
        maxPreviewDimension: Int
    ): IntArray?

    external fun loadBitmapToNative(
        photoId: Int,
        bitmap: Bitmap,
        maxPreviewDimension: Int
    ): IntArray?

    external fun hasFullResLoadedNative(photoId: Int): Boolean

    external fun removePhotoNative(photoId: Int)

    external fun computeHistogramNative(photoId: Int): IntArray?

    external fun renderPreviewFastNative(
        photoId: Int,
        masterTable1024: FloatArray,
        redTable1024: FloatArray,
        greenTable1024: FloatArray,
        blueTable1024: FloatArray,
        outBitmap: Bitmap,
        outHistogram1024: IntArray?
    ): Boolean

    external fun renderExportFastNative(
        photoId: Int,
        masterTable1024: FloatArray,
        redTable1024: FloatArray,
        greenTable1024: FloatArray,
        blueTable1024: FloatArray,
        outBitmap: Bitmap
    ): Boolean

    external fun getExportProgressNative(): Int
}
