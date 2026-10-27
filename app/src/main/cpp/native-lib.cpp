#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <vector>
#include <array>
#include <unordered_map>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <thread>
#include <atomic>

#ifdef HAVE_LIBRAW
#include "libraw/libraw.h"
#endif

#define LOG_TAG "RawCurveNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct PreviewEntry {
    int fullWidth = 0;
    int fullHeight = 0;
    int previewWidth = 0;
    int previewHeight = 0;
    std::vector<uint16_t> previewRgb16;
};

struct FullResCache {
    int photoId = -1;
    int width = 0;
    int height = 0;
    std::vector<uint16_t> rgb16;
};

std::mutex g_mutex;
std::unordered_map<int, PreviewEntry> g_previews;
FullResCache g_fullRes;
std::atomic<int> g_exportProgress{0};

// Pre-allocated 16-bit -> 8-bit combined LUTs protected by g_mutex
uint8_t g_lutR[65536];
uint8_t g_lutG[65536];
uint8_t g_lutB[65536];

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

int getThreadCount(int workHeight) {
    unsigned int hw = std::thread::hardware_concurrency();
    int threads = (hw > 0) ? static_cast<int>(std::min(hw, 8u)) : 4;
    return std::max(1, std::min(threads, workHeight / 16));
}

void resizeRgb16BicubicParallel(
    const uint16_t* src, int srcW, int srcH,
    uint16_t* dst, int dstW, int dstH
) {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return;

    const float scaleX = static_cast<float>(srcW) / static_cast<float>(dstW);
    const float scaleY = static_cast<float>(srcH) / static_cast<float>(dstH);
    int numThreads = getThreadCount(dstH);

    auto worker = [&](int yStart, int yEnd) {
        for (int y = yStart; y < yEnd; ++y) {
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
                    const uint16_t* rowPtr = src + (static_cast<size_t>(sy) * srcW * 3);
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
                    float invW = 1.0f / weightSum;
                    sumR *= invW;
                    sumG *= invW;
                    sumB *= invW;
                }

                uint16_t* outPx = dst + (static_cast<size_t>(y) * dstW + x) * 3;
                outPx[0] = static_cast<uint16_t>(clampf(sumR, 0.0f, 65535.0f));
                outPx[1] = static_cast<uint16_t>(clampf(sumG, 0.0f, 65535.0f));
                outPx[2] = static_cast<uint16_t>(clampf(sumB, 0.0f, 65535.0f));
            }
        }
    };

    if (numThreads <= 1) {
        worker(0, dstH);
    } else {
        std::vector<std::thread> threads;
        threads.reserve(numThreads);
        int rowsPerThread = (dstH + numThreads - 1) / numThreads;
        for (int t = 0; t < numThreads; ++t) {
            int y0 = t * rowsPerThread;
            int y1 = std::min(dstH, y0 + rowsPerThread);
            if (y0 < y1) {
                threads.emplace_back(worker, y0, y1);
            }
        }
        for (auto& th : threads) th.join();
    }
}

void buildPreviewFromFullResLocked(int photoId, int maxPreviewDim) {
    if (g_fullRes.width <= 0 || g_fullRes.height <= 0) return;

    PreviewEntry& entry = g_previews[photoId];
    int srcW = g_fullRes.width;
    int srcH = g_fullRes.height;
    entry.fullWidth = srcW;
    entry.fullHeight = srcH;

    int maxDim = std::max(srcW, srcH);
    if (maxDim <= maxPreviewDim) {
        entry.previewWidth = srcW;
        entry.previewHeight = srcH;
        entry.previewRgb16 = g_fullRes.rgb16;
        return;
    }

    float scale = static_cast<float>(maxPreviewDim) / static_cast<float>(maxDim);
    int dstW = std::max(1, static_cast<int>(std::round(srcW * scale)));
    int dstH = std::max(1, static_cast<int>(std::round(srcH * scale)));

    entry.previewWidth = dstW;
    entry.previewHeight = dstH;
    entry.previewRgb16.resize(static_cast<size_t>(dstW) * dstH * 3);

    resizeRgb16BicubicParallel(
        g_fullRes.rgb16.data(), srcW, srcH,
        entry.previewRgb16.data(), dstW, dstH
    );
}

inline float sampleTable1024(const float* table, float normX) {
    float pos = clampf(normX, 0.0f, 1.0f) * 1023.0f;
    int idx = static_cast<int>(pos);
    int nextIdx = std::min(1023, idx + 1);
    float frac = pos - idx;
    return table[idx] + frac * (table[nextIdx] - table[idx]);
}

void buildCombinedLut16FromTablesLocked(
    const float* master1024,
    const float* red1024,
    const float* green1024,
    const float* blue1024
) {
    constexpr float inv65535 = 1.0f / 65535.0f;
    for (int i = 0; i < 65536; ++i) {
        float x = i * inv65535;
        float rMid = sampleTable1024(red1024, x);
        float gMid = sampleTable1024(green1024, x);
        float bMid = sampleTable1024(blue1024, x);

        float rOut = sampleTable1024(master1024, rMid);
        float gOut = sampleTable1024(master1024, gMid);
        float bOut = sampleTable1024(master1024, bMid);

        g_lutR[i] = static_cast<uint8_t>(clampf(rOut * 255.0f + 0.5f, 0.0f, 255.0f));
        g_lutG[i] = static_cast<uint8_t>(clampf(gOut * 255.0f + 0.5f, 0.0f, 255.0f));
        g_lutB[i] = static_cast<uint8_t>(clampf(bOut * 255.0f + 0.5f, 0.0f, 255.0f));
    }
}

} // namespace

extern "C" {

JNIEXPORT jintArray JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_decodeRawBytesNative(
    JNIEnv* env,
    jobject /* thiz */,
    jint photoId,
    jbyteArray rawBytes,
    jint maxPreviewDimension
) {
#ifdef HAVE_LIBRAW
    jsize length = env->GetArrayLength(rawBytes);
    if (length <= 0) return nullptr;

    jbyte* bufferPtr = env->GetByteArrayElements(rawBytes, nullptr);
    if (!bufferPtr) return nullptr;

    LibRaw rawProcessor;
    rawProcessor.imgdata.params.use_camera_wb = 1;
    rawProcessor.imgdata.params.use_auto_wb = 0;
    rawProcessor.imgdata.params.no_auto_bright = 1;
    rawProcessor.imgdata.params.output_bps = 16;
    rawProcessor.imgdata.params.output_color = 1;
    rawProcessor.imgdata.params.user_qual = 3;
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
        if (image) LibRaw::dcraw_clear_mem(image);
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    g_fullRes.photoId = photoId;
    g_fullRes.width = image->width;
    g_fullRes.height = image->height;
    size_t pixelCount = static_cast<size_t>(image->width) * image->height;
    g_fullRes.rgb16.resize(pixelCount * 3);

    if (image->bits == 16) {
        const uint16_t* src16 = reinterpret_cast<const uint16_t*>(image->data);
        std::memcpy(g_fullRes.rgb16.data(), src16, pixelCount * 3 * sizeof(uint16_t));
    } else {
        const uint8_t* src8 = image->data;
        for (size_t i = 0; i < pixelCount * 3; ++i) {
            g_fullRes.rgb16[i] = static_cast<uint16_t>(src8[i]) * 257;
        }
    }

    LibRaw::dcraw_clear_mem(image);
    rawProcessor.recycle();

    if (maxPreviewDimension > 0) {
        buildPreviewFromFullResLocked(photoId, maxPreviewDimension);
    }

    const PreviewEntry& entry = g_previews[photoId];
    jint dims[4] = {
        g_fullRes.width,
        g_fullRes.height,
        entry.previewWidth,
        entry.previewHeight
    };
    jintArray result = env->NewIntArray(4);
    env->SetIntArrayRegion(result, 0, 4, dims);
    return result;
#else
    return nullptr;
#endif
}

JNIEXPORT jintArray JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_loadBitmapToNative(
    JNIEnv* env,
    jobject /* thiz */,
    jint photoId,
    jobject bitmap,
    jint maxPreviewDimension
) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return nullptr;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return nullptr;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return nullptr;

    std::lock_guard<std::mutex> lock(g_mutex);
    g_fullRes.photoId = photoId;
    g_fullRes.width = static_cast<int>(info.width);
    g_fullRes.height = static_cast<int>(info.height);
    size_t count = static_cast<size_t>(info.width) * info.height;
    g_fullRes.rgb16.resize(count * 3);

    const uint8_t* src = static_cast<const uint8_t*>(pixels);
    for (int y = 0; y < g_fullRes.height; ++y) {
        const uint8_t* row = src + y * info.stride;
        uint16_t* dstRow = g_fullRes.rgb16.data() + static_cast<size_t>(y) * g_fullRes.width * 3;
        for (int x = 0; x < g_fullRes.width; ++x) {
            dstRow[x * 3 + 0] = static_cast<uint16_t>(row[x * 4 + 0]) * 257;
            dstRow[x * 3 + 1] = static_cast<uint16_t>(row[x * 4 + 1]) * 257;
            dstRow[x * 3 + 2] = static_cast<uint16_t>(row[x * 4 + 2]) * 257;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    if (maxPreviewDimension > 0) {
        buildPreviewFromFullResLocked(photoId, maxPreviewDimension);
    }

    const PreviewEntry& entry = g_previews[photoId];
    jint dims[4] = {
        g_fullRes.width,
        g_fullRes.height,
        entry.previewWidth,
        entry.previewHeight
    };
    jintArray result = env->NewIntArray(4);
    env->SetIntArrayRegion(result, 0, 4, dims);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_hasFullResLoadedNative(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint photoId
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_fullRes.photoId == photoId && !g_fullRes.rgb16.empty()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_removePhotoNative(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint photoId
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_previews.erase(photoId);
    if (g_fullRes.photoId == photoId) {
        g_fullRes.photoId = -1;
        g_fullRes.width = 0;
        g_fullRes.height = 0;
        g_fullRes.rgb16.clear();
        g_fullRes.rgb16.shrink_to_fit();
    }
}

JNIEXPORT jintArray JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_computeHistogramNative(
    JNIEnv* env,
    jobject /* thiz */,
    jint photoId
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    jint hist[256 * 4];
    std::memset(hist, 0, sizeof(hist));

    auto it = g_previews.find(photoId);
    if (it != g_previews.end()) {
        const auto& buf = it->second.previewRgb16;
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
    }

    jintArray out = env->NewIntArray(256 * 4);
    env->SetIntArrayRegion(out, 0, 256 * 4, hist);
    return out;
}

/**
 * Ultra-fast multi-threaded preview rendering that ALSO computes the real-time post-curve
 * histogram (R, G, B, Luma) in the same pass with thread-local bins.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_renderPreviewFastNative(
    JNIEnv* env,
    jobject /* thiz */,
    jint photoId,
    jfloatArray masterTable1024,
    jfloatArray redTable1024,
    jfloatArray greenTable1024,
    jfloatArray blueTable1024,
    jobject outBitmap,
    jintArray outHistogram1024
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_previews.find(photoId);
    if (it == g_previews.end() || it->second.previewRgb16.empty()) return JNI_FALSE;

    const PreviewEntry& entry = it->second;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, outBitmap, &info) < 0) return JNI_FALSE;
    if (static_cast<int>(info.width) != entry.previewWidth ||
        static_cast<int>(info.height) != entry.previewHeight) {
        return JNI_FALSE;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &pixels) < 0) return JNI_FALSE;

    jfloat* mTbl = env->GetFloatArrayElements(masterTable1024, nullptr);
    jfloat* rTbl = env->GetFloatArrayElements(redTable1024, nullptr);
    jfloat* gTbl = env->GetFloatArrayElements(greenTable1024, nullptr);
    jfloat* bTbl = env->GetFloatArrayElements(blueTable1024, nullptr);

    buildCombinedLut16FromTablesLocked(mTbl, rTbl, gTbl, bTbl);

    env->ReleaseFloatArrayElements(masterTable1024, mTbl, JNI_ABORT);
    env->ReleaseFloatArrayElements(redTable1024, rTbl, JNI_ABORT);
    env->ReleaseFloatArrayElements(greenTable1024, gTbl, JNI_ABORT);
    env->ReleaseFloatArrayElements(blueTable1024, bTbl, JNI_ABORT);

    const uint16_t* src = entry.previewRgb16.data();
    uint8_t* dst = static_cast<uint8_t*>(pixels);
    int w = entry.previewWidth;
    int h = entry.previewHeight;
    int stride = static_cast<int>(info.stride);

    int numThreads = getThreadCount(h);
    std::vector<std::array<int32_t, 1024>> threadHists(numThreads);
    for (auto& thHist : threadHists) {
        thHist.fill(0);
    }

    auto worker = [&](int threadIdx, int yStart, int yEnd) {
        int32_t* localHist = threadHists[threadIdx].data();
        for (int y = yStart; y < yEnd; ++y) {
            uint8_t* dstRow = dst + static_cast<size_t>(y) * stride;
            const uint16_t* srcRow = src + static_cast<size_t>(y) * w * 3;
            for (int x = 0; x < w; ++x) {
                uint8_t r8 = g_lutR[srcRow[x * 3 + 0]];
                uint8_t g8 = g_lutG[srcRow[x * 3 + 1]];
                uint8_t b8 = g_lutB[srcRow[x * 3 + 2]];

                dstRow[x * 4 + 0] = r8;
                dstRow[x * 4 + 1] = g8;
                dstRow[x * 4 + 2] = b8;
                dstRow[x * 4 + 3] = 255;

                uint8_t luma = static_cast<uint8_t>((77 * r8 + 150 * g8 + 29 * b8) >> 8);
                localHist[r8]++;
                localHist[256 + g8]++;
                localHist[512 + b8]++;
                localHist[768 + luma]++;
            }
        }
    };

    if (numThreads <= 1) {
        worker(0, 0, h);
    } else {
        std::vector<std::thread> threads;
        threads.reserve(numThreads);
        int rowsPerThread = (h + numThreads - 1) / numThreads;
        for (int t = 0; t < numThreads; ++t) {
            int y0 = t * rowsPerThread;
            int y1 = std::min(h, y0 + rowsPerThread);
            if (y0 < y1) threads.emplace_back(worker, t, y0, y1);
        }
        for (auto& th : threads) th.join();
    }

    AndroidBitmap_unlockPixels(env, outBitmap);

    if (outHistogram1024 != nullptr) {
        jint mergedHist[1024];
        std::memset(mergedHist, 0, sizeof(mergedHist));
        for (int t = 0; t < numThreads; ++t) {
            const int32_t* lh = threadHists[t].data();
            for (int i = 0; i < 1024; ++i) {
                mergedHist[i] += lh[i];
            }
        }
        env->SetIntArrayRegion(outHistogram1024, 0, 1024, mergedHist);
    }

    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_getExportProgressNative(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    return g_exportProgress.load(std::memory_order_relaxed);
}

JNIEXPORT jboolean JNICALL
Java_com_example_rawcurveeditor_data_NativeRawBridge_renderExportFastNative(
    JNIEnv* env,
    jobject /* thiz */,
    jint photoId,
    jfloatArray masterTable1024,
    jfloatArray redTable1024,
    jfloatArray greenTable1024,
    jfloatArray blueTable1024,
    jobject outBitmap
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_fullRes.photoId != photoId || g_fullRes.rgb16.empty()) return JNI_FALSE;

    g_exportProgress.store(0, std::memory_order_relaxed);

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, outBitmap, &info) < 0) return JNI_FALSE;

    int dstW = static_cast<int>(info.width);
    int dstH = static_cast<int>(info.height);
    int srcW = g_fullRes.width;
    int srcH = g_fullRes.height;
    int stride = static_cast<int>(info.stride);

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &pixels) < 0) return JNI_FALSE;

    jfloat* mTbl = env->GetFloatArrayElements(masterTable1024, nullptr);
    jfloat* rTbl = env->GetFloatArrayElements(redTable1024, nullptr);
    jfloat* gTbl = env->GetFloatArrayElements(greenTable1024, nullptr);
    jfloat* bTbl = env->GetFloatArrayElements(blueTable1024, nullptr);

    buildCombinedLut16FromTablesLocked(mTbl, rTbl, gTbl, bTbl);

    env->ReleaseFloatArrayElements(masterTable1024, mTbl, JNI_ABORT);
    env->ReleaseFloatArrayElements(redTable1024, rTbl, JNI_ABORT);
    env->ReleaseFloatArrayElements(greenTable1024, gTbl, JNI_ABORT);
    env->ReleaseFloatArrayElements(blueTable1024, bTbl, JNI_ABORT);

    uint8_t* dst = static_cast<uint8_t*>(pixels);
    const uint16_t* src = g_fullRes.rgb16.data();

    std::atomic<int> completedRows{0};
    int numThreads = getThreadCount(dstH);

    auto worker = [&](int yStart, int yEnd) {
        if (dstW == srcW && dstH == srcH) {
            for (int y = yStart; y < yEnd; ++y) {
                uint8_t* dstRow = dst + static_cast<size_t>(y) * stride;
                const uint16_t* srcRow = src + static_cast<size_t>(y) * srcW * 3;
                for (int x = 0; x < dstW; ++x) {
                    dstRow[x * 4 + 0] = g_lutR[srcRow[x * 3 + 0]];
                    dstRow[x * 4 + 1] = g_lutG[srcRow[x * 3 + 1]];
                    dstRow[x * 4 + 2] = g_lutB[srcRow[x * 3 + 2]];
                    dstRow[x * 4 + 3] = 255;
                }
                int done = ++completedRows;
                if ((done & 31) == 0) {
                    g_exportProgress.store((done * 85) / std::max(1, dstH), std::memory_order_relaxed);
                }
            }
        } else {
            const float scaleX = static_cast<float>(srcW) / static_cast<float>(dstW);
            const float scaleY = static_cast<float>(srcH) / static_cast<float>(dstH);

            for (int y = yStart; y < yEnd; ++y) {
                uint8_t* dstRow = dst + static_cast<size_t>(y) * stride;
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
                        float invW = 1.0f / weightSum;
                        sumR *= invW;
                        sumG *= invW;
                        sumB *= invW;
                    }

                    uint16_t r16 = static_cast<uint16_t>(clampf(sumR, 0.0f, 65535.0f));
                    uint16_t g16 = static_cast<uint16_t>(clampf(sumG, 0.0f, 65535.0f));
                    uint16_t b16 = static_cast<uint16_t>(clampf(sumB, 0.0f, 65535.0f));

                    dstRow[x * 4 + 0] = g_lutR[r16];
                    dstRow[x * 4 + 1] = g_lutG[g16];
                    dstRow[x * 4 + 2] = g_lutB[b16];
                    dstRow[x * 4 + 3] = 255;
                }
                int done = ++completedRows;
                if ((done & 15) == 0) {
                    g_exportProgress.store((done * 85) / std::max(1, dstH), std::memory_order_relaxed);
                }
            }
        }
    };

    if (numThreads <= 1) {
        worker(0, dstH);
    } else {
        std::vector<std::thread> threads;
        threads.reserve(numThreads);
        int rowsPerThread = (dstH + numThreads - 1) / numThreads;
        for (int t = 0; t < numThreads; ++t) {
            int y0 = t * rowsPerThread;
            int y1 = std::min(dstH, y0 + rowsPerThread);
            if (y0 < y1) threads.emplace_back(worker, y0, y1);
        }
        for (auto& th : threads) th.join();
    }

    g_exportProgress.store(90, std::memory_order_relaxed);
    AndroidBitmap_unlockPixels(env, outBitmap);
    return JNI_TRUE;
}

} // extern "C"
