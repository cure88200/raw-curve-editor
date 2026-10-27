#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <mutex>

#ifdef HAVE_LIBRAW
#include "libraw/libraw.h"
#endif

#define LOG_TAG "RawCurveNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct RawImageBuffer {
    int width = 0;
    int height = 0;
    // Interleaved RGB 16-bit (0..65535) for maximum dynamic range preservation
    std::vector<uint16_t> rgb16;

    // Preview downscaled buffer (RGB 16-bit) for interactive real-time curve dragging
    int previewWidth = 0;
    int previewHeight = 0;
    std::vector<uint16_t> previewRgb16;
};

std::mutex g_bufferMutex;
RawImageBuffer g_imageBuffer;

inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// Catmull-Rom bicubic interpolation weight
inline float cubicWeight(float x) {
    x = std::fabs(x);
    if (x <= 1.0f) {
        return 1.5f * x * x * x - 2.5f * x * x + 1.0f;
    } else if (x < 2.0f) {
        return -0.5f * x * x * x + 2.5f * x * x - 4.0f * x + 2.0f;
    }
    return 0.0f;
}

void resizeRgb16Bicubic(
    const uint16_t* src, int srcW, int srcH,
    uint16_t* dst, int dstW, int dstH
) {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return;

    const float scaleX = static_cast<float>(srcW) / static_cast<float>(dstW);
    const float scaleY = static_cast<float>(srcH) / static_cast<float>(dstH);

    for (int y = 0; y < dstH; ++y) {
        float srcY = (y + 0.5f) * scaleY - 0.5f;
        int yInt = static_cast<int>(std::floor(srcY));
        float yFrac = srcY - yInt;

        float wy[4];
        for (int m = -1; m <= 2; ++m) {
            wy[m + 1] = cubicWeight(yFrac - m);
        }

        for (int x = 0; x < dstW; ++x) {
            float srcX = (x + 0.5f) * scaleX - 0.5f;
            int xInt = static_cast<int>(std::floor(srcX));
            float xFrac = srcX - xInt;

            float wx[4];
            for (int n = -1; n <= 2; ++n) {
                wx[n + 1] = cubicWeight(xFrac - n);
            }

            float sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
            float weightSum = 0.0f;

            for (int m = -1; m <= 2; ++m) {
                int sy = std::clamp(yInt + m, 0, srcH - 1);
                const uint16_t* rowPtr = src + (sy * srcW * 3);
                float wY = wy[m + 1];

                for (int n = -1; n <= 2; ++n) {
                    int sx = std::clamp(xInt + n, 0, srcW - 1);
                    float w = wY * wx[n + 1];
                    const uint16_t* px = rowPtr + (sx * 3);
                    sumR += px[0] * w;
                    sumG += px[1] * w;
                    sumB += px[2] * w;
                    weightSum += w;
                }
            }

            if (weightSum > 0.0f) {
                sumR /= weightSum;
                sumG /= weightSum;
                sumB /= weightSum;
            }

            uint16_t* outPx = dst + (y * dstW + x) * 3;
            outPx[0] = static_cast<uint16_t>(clampf(sumR, 0.0f, 65535.0f));
            outPx[1] = static_cast<uint16_t>(clampf(sumG, 0.0f, 65535.0f));
            outPx[2] = static_cast<uint16_t>(clampf(sumB, 0.0f, 65535.0f));
        }
    }
}

void buildPreviewBufferLocked(int maxPreviewDim) {
    if (g_imageBuffer.width <= 0 || g_imageBuffer.height <= 0) return;

    int srcW = g_imageBuffer.width;
    int srcH = g_imageBuffer.height;
    int maxDim = std::max(srcW, srcH);

    if (maxDim <= maxPreviewDim) {
        g_imageBuffer.previewWidth = srcW;
        g_imageBuffer.previewHeight = srcH;
        g_imageBuffer.previewRgb16 = g_imageBuffer.rgb16;
        return;
    }

    float scale = static_cast<float>(maxPreviewDim) / static_cast<float>(maxDim);
    int dstW = std::max(1, static_cast<int>(std::round(srcW * scale)));
    int dstH = std::max(1, static_cast<int>(std::round(srcH * scale)));

    g_imageBuffer.previewWidth = dstW;
    g_imageBuffer.previewHeight = dstH;
    g_imageBuffer.previewRgb16.resize(static_cast<size_t>(dstW) * dstH * 3);

    resizeRgb16Bicubic(
        g_imageBuffer.rgb16.data(), srcW, srcH,
        g_imageBuffer.previewRgb16.data(), dstW, dstH
    );
}

} // namespace

extern "C" {

/**
 * Decode a RAW file (e.g. Sony .ARW, .CR2, .NEF, .DNG) from memory buffer via LibRaw
 * into our 16-bit internal RGB buffer.
 * Returns [fullWidth, fullHeight, previewWidth, previewHeight] or null on error.
 */
JNIEXPORT jintArray JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_decodeRawBytesNative(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray rawBytes,
    jint maxPreviewDimension
) {
#ifdef HAVE_LIBRAW
    jsize length = env->GetArrayLength(rawBytes);
    if (length <= 0) return nullptr;

    jbyte* bufferPtr = env->GetByteArrayElements(rawBytes, nullptr);
    if (!bufferPtr) return nullptr;

    LibRaw rawProcessor;
    // Configure high-quality 16-bit sRGB output with camera white balance
    rawProcessor.imgdata.params.use_camera_wb = 1;
    rawProcessor.imgdata.params.use_auto_wb = 0;
    rawProcessor.imgdata.params.no_auto_bright = 1;
    rawProcessor.imgdata.params.output_bps = 16;     // 16-bit depth per channel!
    rawProcessor.imgdata.params.output_color = 1;    // sRGB color space
    rawProcessor.imgdata.params.user_qual = 3;       // AHD demosaicing (high quality)
    rawProcessor.imgdata.params.gamm[0] = 1.0 / 2.222;
    rawProcessor.imgdata.params.gamm[1] = 4.5;

    int ret = rawProcessor.open_buffer(bufferPtr, static_cast<size_t>(length));
    env->ReleaseByteArrayElements(rawBytes, bufferPtr, JNI_ABORT);

    if (ret != LIBRAW_SUCCESS) {
        LOGE("LibRaw open_buffer failed: %s", libraw_strerror(ret));
        return nullptr;
    }

    ret = rawProcessor.unpack();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("LibRaw unpack failed: %s", libraw_strerror(ret));
        return nullptr;
    }

    ret = rawProcessor.dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("LibRaw dcraw_process failed: %s", libraw_strerror(ret));
        return nullptr;
    }

    libraw_processed_image_t* image = rawProcessor.dcraw_make_mem_image(&ret);
    if (!image || image->type != LIBRAW_IMAGE_BITMAP || image->colors != 3) {
        LOGE("LibRaw dcraw_make_mem_image failed or unexpected format: %d", ret);
        if (image) LibRaw::dcraw_clear_mem(image);
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_bufferMutex);
    g_imageBuffer.width = image->width;
    g_imageBuffer.height = image->height;
    size_t pixelCount = static_cast<size_t>(image->width) * image->height;
    g_imageBuffer.rgb16.resize(pixelCount * 3);

    if (image->bits == 16) {
        const uint16_t* src16 = reinterpret_cast<const uint16_t*>(image->data);
        std::memcpy(g_imageBuffer.rgb16.data(), src16, pixelCount * 3 * sizeof(uint16_t));
    } else {
        // 8-bit fallback promoted to 16-bit
        const uint8_t* src8 = image->data;
        for (size_t i = 0; i < pixelCount * 3; ++i) {
            g_imageBuffer.rgb16[i] = static_cast<uint16_t>(src8[i]) * 257;
        }
    }

    LibRaw::dcraw_clear_mem(image);
    rawProcessor.recycle();

    buildPreviewBufferLocked(maxPreviewDimension);

    jint dims[4] = {
        g_imageBuffer.width,
        g_imageBuffer.height,
        g_imageBuffer.previewWidth,
        g_imageBuffer.previewHeight
    };
    jintArray result = env->NewIntArray(4);
    env->SetIntArrayRegion(result, 0, 4, dims);
    LOGI("Decoded RAW via LibRaw: %dx%d (preview %dx%d)",
         dims[0], dims[1], dims[2], dims[3]);
    return result;
#else
    return nullptr;
#endif
}

/**
 * Fallback initializer when loading standard images or when using Android's Bitmap decoder.
 * Promotes 8-bit ARGB_8888 Bitmap into the 16-bit native pipeline.
 */
JNIEXPORT jintArray JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_loadBitmapToNative(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jint maxPreviewDimension
) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return nullptr;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return nullptr;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return nullptr;

    std::lock_guard<std::mutex> lock(g_bufferMutex);
    g_imageBuffer.width = static_cast<int>(info.width);
    g_imageBuffer.height = static_cast<int>(info.height);
    size_t count = static_cast<size_t>(info.width) * info.height;
    g_imageBuffer.rgb16.resize(count * 3);

    const uint8_t* src = static_cast<const uint8_t*>(pixels);
    for (int y = 0; y < g_imageBuffer.height; ++y) {
        const uint8_t* row = src + y * info.stride;
        uint16_t* dstRow = g_imageBuffer.rgb16.data() + static_cast<size_t>(y) * g_imageBuffer.width * 3;
        for (int x = 0; x < g_imageBuffer.width; ++x) {
            dstRow[x * 3 + 0] = static_cast<uint16_t>(row[x * 4 + 0]) * 257;
            dstRow[x * 3 + 1] = static_cast<uint16_t>(row[x * 4 + 1]) * 257;
            dstRow[x * 3 + 2] = static_cast<uint16_t>(row[x * 4 + 2]) * 257;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    buildPreviewBufferLocked(maxPreviewDimension);

    jint dims[4] = {
        g_imageBuffer.width,
        g_imageBuffer.height,
        g_imageBuffer.previewWidth,
        g_imageBuffer.previewHeight
    };
    jintArray result = env->NewIntArray(4);
    env->SetIntArrayRegion(result, 0, 4, dims);
    return result;
}

/**
 * Compute 256-bin R, G, B, and Luminance histograms from the preview 16-bit buffer.
 * Returns a flat IntArray of size 256 * 4 (R[0..255], G[0..255], B[0..255], Luma[0..255]).
 */
JNIEXPORT jintArray JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_computeHistogramNative(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_bufferMutex);
    jint hist[256 * 4];
    std::memset(hist, 0, sizeof(hist));

    const auto& buf = g_imageBuffer.previewRgb16;
    size_t pixelCount = buf.size() / 3;
    for (size_t i = 0; i < pixelCount; ++i) {
        uint8_t r = static_cast<uint8_t>(buf[i * 3 + 0] >> 8);
        uint8_t g = static_cast<uint8_t>(buf[i * 3 + 1] >> 8);
        uint8_t b = static_cast<uint8_t>(buf[i * 3 + 2] >> 8);
        uint8_t luma = static_cast<uint8_t>((77 * r + 150 * g + 29 * b) >> 8);

        hist[r]++;
        hist[256 + g]++;
        hist[512 + b]++;
        hist[768 + luma]++;
    }

    jintArray out = env->NewIntArray(256 * 4);
    env->SetIntArrayRegion(out, 0, 256 * 4, hist);
    return out;
}

/**
 * Apply 16-bit RGB LUTs (size 65536 each for R, G, B after combining Master + Channel curves)
 * onto the preview buffer and write directly into an Android RGBA_8888 Bitmap.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_renderPreviewWithLutNative(
    JNIEnv* env,
    jobject /* thiz */,
    jintArray lutR16,
    jintArray lutG16,
    jintArray lutB16,
    jobject outBitmap
) {
    std::lock_guard<std::mutex> lock(g_bufferMutex);
    if (g_imageBuffer.previewRgb16.empty()) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, outBitmap, &info) < 0) return JNI_FALSE;
    if (static_cast<int>(info.width) != g_imageBuffer.previewWidth ||
        static_cast<int>(info.height) != g_imageBuffer.previewHeight) {
        return JNI_FALSE;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &pixels) < 0) return JNI_FALSE;

    jint* rLut = env->GetIntArrayElements(lutR16, nullptr);
    jint* gLut = env->GetIntArrayElements(lutG16, nullptr);
    jint* bLut = env->GetIntArrayElements(lutB16, nullptr);

    const uint16_t* src = g_imageBuffer.previewRgb16.data();
    uint8_t* dst = static_cast<uint8_t*>(pixels);
    int w = g_imageBuffer.previewWidth;
    int h = g_imageBuffer.previewHeight;

    for (int y = 0; y < h; ++y) {
        uint8_t* dstRow = dst + y * info.stride;
        const uint16_t* srcRow = src + static_cast<size_t>(y) * w * 3;
        for (int x = 0; x < w; ++x) {
            uint16_t r16 = srcRow[x * 3 + 0];
            uint16_t g16 = srcRow[x * 3 + 1];
            uint16_t b16 = srcRow[x * 3 + 2];

            dstRow[x * 4 + 0] = static_cast<uint8_t>(rLut[r16]);
            dstRow[x * 4 + 1] = static_cast<uint8_t>(gLut[g16]);
            dstRow[x * 4 + 2] = static_cast<uint8_t>(bLut[b16]);
            dstRow[x * 4 + 3] = 255;
        }
    }

    env->ReleaseIntArrayElements(lutR16, rLut, JNI_ABORT);
    env->ReleaseIntArrayElements(lutG16, gLut, JNI_ABORT);
    env->ReleaseIntArrayElements(lutB16, bLut, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, outBitmap);

    return JNI_TRUE;
}

/**
 * Render full-resolution 16-bit image scaled to arbitrary output dimensions (including >100%)
 * using Catmull-Rom bicubic interpolation in 16-bit space before applying the 16-bit curve LUT.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_renderExportNative(
    JNIEnv* env,
    jobject /* thiz */,
    jintArray lutR16,
    jintArray lutG16,
    jintArray lutB16,
    jobject outBitmap
) {
    std::lock_guard<std::mutex> lock(g_bufferMutex);
    if (g_imageBuffer.rgb16.empty()) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, outBitmap, &info) < 0) return JNI_FALSE;

    int dstW = static_cast<int>(info.width);
    int dstH = static_cast<int>(info.height);
    int srcW = g_imageBuffer.width;
    int srcH = g_imageBuffer.height;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &pixels) < 0) return JNI_FALSE;

    jint* rLut = env->GetIntArrayElements(lutR16, nullptr);
    jint* gLut = env->GetIntArrayElements(lutG16, nullptr);
    jint* bLut = env->GetIntArrayElements(lutB16, nullptr);

    uint8_t* dst = static_cast<uint8_t*>(pixels);
    const uint16_t* src = g_imageBuffer.rgb16.data();

    if (dstW == srcW && dstH == srcH) {
        // Exact 100% export: direct 16-bit LUT mapping
        for (int y = 0; y < dstH; ++y) {
            uint8_t* dstRow = dst + y * info.stride;
            const uint16_t* srcRow = src + static_cast<size_t>(y) * srcW * 3;
            for (int x = 0; x < dstW; ++x) {
                dstRow[x * 4 + 0] = static_cast<uint8_t>(rLut[srcRow[x * 3 + 0]]);
                dstRow[x * 4 + 1] = static_cast<uint8_t>(gLut[srcRow[x * 3 + 1]]);
                dstRow[x * 4 + 2] = static_cast<uint8_t>(bLut[srcRow[x * 3 + 2]]);
                dstRow[x * 4 + 3] = 255;
            }
        }
    } else {
        // Arbitrary scale (e.g. 25% to 200%+): sample in 16-bit space via Bicubic interpolation, then apply LUT
        const float scaleX = static_cast<float>(srcW) / static_cast<float>(dstW);
        const float scaleY = static_cast<float>(srcH) / static_cast<float>(dstH);

        for (int y = 0; y < dstH; ++y) {
            uint8_t* dstRow = dst + y * info.stride;
            float srcY = (y + 0.5f) * scaleY - 0.5f;
            int yInt = static_cast<int>(std::floor(srcY));
            float yFrac = srcY - yInt;

            float wy[4];
            for (int m = -1; m <= 2; ++m) {
                wy[m + 1] = cubicWeight(yFrac - m);
            }

            for (int x = 0; x < dstW; ++x) {
                float srcX = (x + 0.5f) * scaleX - 0.5f;
                int xInt = static_cast<int>(std::floor(srcX));
                float xFrac = srcX - xInt;

                float wx[4];
                for (int n = -1; n <= 2; ++n) {
                    wx[n + 1] = cubicWeight(xFrac - n);
                }

                float sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
                float weightSum = 0.0f;

                for (int m = -1; m <= 2; ++m) {
                    int sy = std::clamp(yInt + m, 0, srcH - 1);
                    const uint16_t* rowPtr = src + static_cast<size_t>(sy) * srcW * 3;
                    float wY = wy[m + 1];

                    for (int n = -1; n <= 2; ++n) {
                        int sx = std::clamp(xInt + n, 0, srcW - 1);
                        float w = wY * wx[n + 1];
                        const uint16_t* px = rowPtr + sx * 3;
                        sumR += px[0] * w;
                        sumG += px[1] * w;
                        sumB += px[2] * w;
                        weightSum += w;
                    }
                }

                if (weightSum > 0.0f) {
                    sumR /= weightSum;
                    sumG /= weightSum;
                    sumB /= weightSum;
                }

                uint16_t r16 = static_cast<uint16_t>(clampf(sumR, 0.0f, 65535.0f));
                uint16_t g16 = static_cast<uint16_t>(clampf(sumG, 0.0f, 65535.0f));
                uint16_t b16 = static_cast<uint16_t>(clampf(sumB, 0.0f, 65535.0f));

                dstRow[x * 4 + 0] = static_cast<uint8_t>(rLut[r16]);
                dstRow[x * 4 + 1] = static_cast<uint8_t>(gLut[g16]);
                dstRow[x * 4 + 2] = static_cast<uint8_t>(bLut[b16]);
                dstRow[x * 4 + 3] = 255;
            }
        }
    }

    env->ReleaseIntArrayElements(lutR16, rLut, JNI_ABORT);
    env->ReleaseIntArrayElements(lutG16, gLut, JNI_ABORT);
    env->ReleaseIntArrayElements(lutB16, bLut, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, outBitmap);

    return JNI_TRUE;
}

} // extern "C"
