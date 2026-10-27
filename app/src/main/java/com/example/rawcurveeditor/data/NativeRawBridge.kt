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

    external fun decodeRawBytesNative(rawBytes: ByteArray, maxPreviewDimension: Int): IntArray?

    external fun loadBitmapToNative(bitmap: Bitmap, maxPreviewDimension: Int): IntArray?

    external fun computeHistogramNative(): IntArray?

    external fun renderPreviewWithLutNative(
        lutR16: IntArray,
        lutG16: IntArray,
        lutB16: IntArray,
        outBitmap: Bitmap
    ): Boolean

    external fun renderExportNative(
        lutR16: IntArray,
        lutG16: IntArray,
        lutB16: IntArray,
        outBitmap: Bitmap
    ): Boolean
}
