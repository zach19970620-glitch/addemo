#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

#include "dl_interface.h"

#define LOG_TAG "RkDlJni"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using DlInitFn = int (*)(DL_Config);
using DlProcessFn = int (*)(unsigned int, DL_ImageDataPtr[MAX_IMG_NUM], int, DL_ResultPtr);
using DlExitFn = int (*)(unsigned int);

namespace {

std::mutex g_mutex;
void *g_handle = nullptr;
DlInitFn g_dlInit = nullptr;
DlProcessFn g_dlProcess = nullptr;
DlExitFn g_dlExit = nullptr;
bool g_inited = false;
unsigned int g_procId = 0;
unsigned int g_procType = 0;
std::vector<uint8_t> g_nv12Buffer;
std::vector<uint8_t> g_debugBuffer;
std::vector<uint8_t> g_rgbOut;

jclass g_faceResultClass = nullptr;
jmethodID g_faceResultCtor = nullptr;
jclass g_processResultClass = nullptr;
jmethodID g_processResultCtor = nullptr;

/**
 * librk_dl 的 INFO_LOG/ERROR_LOG 使用 printf，而非 Android log。
 * 临时捕获 stdout，转发到 RkDlJni，便于直接看到 FACE_REC 的真实失败原因。
 */
template <typename Fn>
int callWithVendorLog(const char *phase, Fn &&fn) {
    int pipeFd[2];
    if (pipe(pipeFd) != 0) {
        return fn();
    }

    std::fflush(stdout);
    const int savedStdout = dup(STDOUT_FILENO);
    if (savedStdout < 0 || dup2(pipeFd[1], STDOUT_FILENO) < 0) {
        if (savedStdout >= 0) close(savedStdout);
        close(pipeFd[0]);
        close(pipeFd[1]);
        return fn();
    }
    close(pipeFd[1]);

    std::string output;
    std::thread reader([&]() {
        char chunk[4096];
        ssize_t count;
        while ((count = read(pipeFd[0], chunk, sizeof(chunk))) > 0) {
            output.append(chunk, static_cast<size_t>(count));
        }
        close(pipeFd[0]);
    });

    const int ret = fn();
    std::fflush(stdout);
    dup2(savedStdout, STDOUT_FILENO);
    close(savedStdout);
    reader.join();

    while (!output.empty() && (output.back() == '\n' || output.back() == '\r')) {
        output.pop_back();
    }
    if (!output.empty()) {
        ALOGI("vendor[%s]:\n%s", phase, output.c_str());
    }
    return ret;
}

bool ensureSymbolsLocked() {
    if (g_dlInit && g_dlProcess && g_dlExit) {
        return true;
    }
    g_handle = dlopen("librk_dl.so", RTLD_NOW);
    if (!g_handle) {
        ALOGE("dlopen librk_dl.so failed: %s", dlerror());
        return false;
    }
    g_dlInit = reinterpret_cast<DlInitFn>(dlsym(g_handle, "DL_Init"));
    g_dlProcess = reinterpret_cast<DlProcessFn>(dlsym(g_handle, "DL_Process"));
    g_dlExit = reinterpret_cast<DlExitFn>(dlsym(g_handle, "DL_Exit"));
    if (!g_dlInit || !g_dlProcess || !g_dlExit) {
        ALOGE("dlsym DL_* failed: %s", dlerror());
        return false;
    }
    return true;
}

/** NV21 → NV12：Y 平面不变，色度 VU→UV */
void nv21ToNv12(const uint8_t *nv21, uint8_t *nv12, int width, int height) {
    const size_t ySize = static_cast<size_t>(width) * static_cast<size_t>(height);
    std::memcpy(nv12, nv21, ySize);
    const uint8_t *vu = nv21 + ySize;
    uint8_t *uv = nv12 + ySize;
    for (size_t i = 0; i < ySize / 2; i += 2) {
        uv[i] = vu[i + 1];
        uv[i + 1] = vu[i];
    }
}

/** NV12 → RGB888，用于展示库回写的 debugBuf */
void nv12ToRgb(const uint8_t *nv12, uint8_t *rgb, int width, int height) {
    const uint8_t *yPlane = nv12;
    const uint8_t *uvPlane = nv12 + width * height;
    for (int j = 0; j < height; ++j) {
        const uint8_t *yRow = yPlane + j * width;
        const uint8_t *uvRow = uvPlane + (j / 2) * width;
        uint8_t *rgbRow = rgb + j * width * 3;
        for (int i = 0; i < width; ++i) {
            const int y = static_cast<int>(yRow[i]) - 16;
            const int u = static_cast<int>(uvRow[i & ~1]) - 128;
            const int v = static_cast<int>(uvRow[(i & ~1) + 1]) - 128;
            int r = (298 * y + 409 * v + 128) >> 8;
            int g = (298 * y - 100 * u - 208 * v + 128) >> 8;
            int b = (298 * y + 516 * u + 128) >> 8;
            rgbRow[i * 3] = static_cast<uint8_t>(std::clamp(r, 0, 255));
            rgbRow[i * 3 + 1] = static_cast<uint8_t>(std::clamp(g, 0, 255));
            rgbRow[i * 3 + 2] = static_cast<uint8_t>(std::clamp(b, 0, 255));
        }
    }
}

bool cacheJavaClasses(JNIEnv *env) {
    if (g_faceResultClass && g_faceResultCtor && g_processResultClass && g_processResultCtor) {
        return true;
    }

    jclass faceLocal = env->FindClass("com/haokai/hk5mh210/inference/FaceResult");
    if (!faceLocal) {
        ALOGE("FindClass FaceResult failed");
        return false;
    }
    g_faceResultClass = reinterpret_cast<jclass>(env->NewGlobalRef(faceLocal));
    env->DeleteLocalRef(faceLocal);
    g_faceResultCtor = env->GetMethodID(g_faceResultClass, "<init>", "(IIIIIFIIII)V");
    if (!g_faceResultCtor) {
        ALOGE("GetMethodID FaceResult.<init> failed");
        return false;
    }

    jclass procLocal = env->FindClass("com/haokai/hk5mh210/inference/ProcessResult");
    if (!procLocal) {
        ALOGE("FindClass ProcessResult failed");
        return false;
    }
    g_processResultClass = reinterpret_cast<jclass>(env->NewGlobalRef(procLocal));
    env->DeleteLocalRef(procLocal);
    g_processResultCtor = env->GetMethodID(
        g_processResultClass,
        "<init>",
        "([Lcom/haokai/hk5mh210/inference/FaceResult;[BII)V");
    if (!g_processResultCtor) {
        ALOGE("GetMethodID ProcessResult.<init> failed");
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_haokai_hk5mh210_inference_RkDlNative_nativeInit(
    JNIEnv *env,
    jobject /* thiz */,
    jstring modelRootPath,
    jint procId,
    jint procType) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!ensureSymbolsLocked()) {
        return -1;
    }
    if (g_inited) {
        return 0;
    }

    const char *root = env->GetStringUTFChars(modelRootPath, nullptr);
    if (!root) {
        return -2;
    }

    DL_Config cfg{};
    cfg.procId = static_cast<unsigned int>(procId);
    cfg.procType = static_cast<unsigned int>(procType);
    std::snprintf(cfg.modelRootPath, sizeof(cfg.modelRootPath), "%s", root);
    env->ReleaseStringUTFChars(modelRootPath, root);

    ALOGI("DL_Init procId=%u procType=%u modelRoot=%s", cfg.procId, cfg.procType, cfg.modelRootPath);
    const int ret = callWithVendorLog("init", [&]() { return g_dlInit(cfg); });
    if (ret != 0) {
        ALOGE("DL_Init failed: %d", ret);
        return ret;
    }
    g_procId = cfg.procId;
    g_procType = cfg.procType;
    g_inited = true;
    cacheJavaClasses(env);
    return 0;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_haokai_hk5mh210_inference_RkDlNative_nativeProcessNv21(
    JNIEnv *env,
    jobject /* thiz */,
    jbyteArray nv21Array,
    jint width,
    jint height,
    jlong frameNo,
    jint procType,
    jboolean includeDebug) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_inited || !g_dlProcess) {
        return nullptr;
    }
    if (!cacheJavaClasses(env)) {
        return nullptr;
    }
    if (width <= 0 || height <= 0 || (width % 2) != 0 || (height % 2) != 0) {
        ALOGE("invalid size %dx%d", width, height);
        return nullptr;
    }

    const jsize len = env->GetArrayLength(nv21Array);
    const int yuvSize = width * height * 3 / 2;
    if (len < yuvSize) {
        ALOGE("nv21 too small: %d < %d", len, yuvSize);
        return nullptr;
    }

    jbyte *nv21Bytes = env->GetByteArrayElements(nv21Array, nullptr);
    if (!nv21Bytes) {
        return nullptr;
    }

    const unsigned int requestedProcType = static_cast<unsigned int>(procType);
    if (requestedProcType == 0 || (requestedProcType & ~g_procType) != 0) {
        ALOGE(
            "invalid procType=%u, initialized modules=%u",
            requestedProcType,
            g_procType);
        env->ReleaseByteArrayElements(nv21Array, nv21Bytes, JNI_ABORT);
        return nullptr;
    }

    // 走库内 NV12 主路径；实时检测帧不创建 debugBuf，避免无用的全帧 RGB 转换。
    g_nv12Buffer.resize(static_cast<size_t>(yuvSize));
    nv21ToNv12(reinterpret_cast<const uint8_t *>(nv21Bytes), g_nv12Buffer.data(), width, height);
    env->ReleaseByteArrayElements(nv21Array, nv21Bytes, JNI_ABORT);
    if (includeDebug == JNI_TRUE) {
        g_debugBuffer.resize(static_cast<size_t>(yuvSize));
        std::memcpy(g_debugBuffer.data(), g_nv12Buffer.data(), static_cast<size_t>(yuvSize));
    }

    DL_ImageData image{};
    image.camIndex = 0;
    image.procType = requestedProcType;
    image.srcWidth = static_cast<unsigned int>(width);
    image.srcHeight = static_cast<unsigned int>(height);
    image.dataType = DL_IMG_DATA_TYPE_NV12;
    image.dataBuf = g_nv12Buffer.data();
    image.debugBuf = includeDebug == JNI_TRUE ? g_debugBuffer.data() : nullptr;
    image.frameNo = static_cast<unsigned long>(frameNo);
    image.frameRate = 5.0f;
    image.timestamp = 0;

    DL_ImageDataPtr inImages[MAX_IMG_NUM] = {nullptr};
    inImages[0] = &image;

    DL_ODResult odOut{};
    DL_SegResult segOut{};
    DL_Result result{};
    result.odOut[0] = &odOut;
    result.segOut[0] = &segOut;

    const int ret = callWithVendorLog(
        "process",
        [&]() { return g_dlProcess(g_procId, inImages, 1, &result); });
    if (ret != 0) {
        ALOGE("DL_Process failed: %d", ret);
        return nullptr;
    }

    unsigned int n = odOut.numOD;
    if (n > MAX_OD_NUM) {
        n = MAX_OD_NUM;
    }
    ALOGI(
        "DL_Process ok numOD=%u size=%dx%d procType=%u debug=%d",
        n,
        width,
        height,
        requestedProcType,
        includeDebug == JNI_TRUE);

    jobjectArray faces = env->NewObjectArray(static_cast<jsize>(n), g_faceResultClass, nullptr);
    if (!faces) {
        return nullptr;
    }
    const bool attributeRequested =
        (requestedProcType & (1u << DL_ALGO_PROC_TYPE_FACE_REC)) != 0;
    int attrOk = 0;
    for (unsigned int i = 0; i < n; ++i) {
        const DL_ODData &od = odOut.ODInfo[i];
        const int age = attributeRequested ? od.age : -1;
        const int gender = attributeRequested ? od.gender : -1;
        const int race = attributeRequested ? od.race : -1;
        const int emotion = attributeRequested ? od.emotion : -1;
        if (age >= 0 || gender >= 0 || race >= 0 || emotion >= 0) {
            ++attrOk;
        }
        ALOGI(
            "face[%u] box=(%u,%u)-(%u,%u) score=%.3f age=%d gender=%d race=%d emotion=%d",
            i, od.left, od.top, od.right, od.bottom, od.score,
            age, gender, race, emotion);
        jobject obj = env->NewObject(
            g_faceResultClass,
            g_faceResultCtor,
            od.id,
            static_cast<jint>(od.left),
            static_cast<jint>(od.top),
            static_cast<jint>(od.right),
            static_cast<jint>(od.bottom),
            od.score,
            age,
            gender,
            race,
            emotion);
        if (obj) {
            env->SetObjectArrayElement(faces, static_cast<jsize>(i), obj);
            env->DeleteLocalRef(obj);
        }
    }
    if (attributeRequested && n > 0 && attrOk == 0) {
        ALOGW(
            "FACE_REC attrs all -1 (det ok). Check: 1) VGGFace.rknn complete under modelRoot "
            "2) logcat for 'face recognition DoProcess failed' / 'invalid face roi'");
    }

    jbyteArray debugArr = nullptr;
    if (includeDebug == JNI_TRUE) {
        const size_t rgbBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
        g_rgbOut.resize(rgbBytes);
        nv12ToRgb(g_debugBuffer.data(), g_rgbOut.data(), width, height);
        debugArr = env->NewByteArray(static_cast<jsize>(rgbBytes));
        if (debugArr) {
            env->SetByteArrayRegion(
                debugArr,
                0,
                static_cast<jsize>(rgbBytes),
                reinterpret_cast<const jbyte *>(g_rgbOut.data()));
        }
    }

    jobject out = env->NewObject(
        g_processResultClass,
        g_processResultCtor,
        faces,
        debugArr,
        width,
        height);
    if (faces) {
        env->DeleteLocalRef(faces);
    }
    if (debugArr) {
        env->DeleteLocalRef(debugArr);
    }
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_haokai_hk5mh210_inference_RkDlNative_nativeRelease(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jint procId) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_inited || !g_dlExit) {
        return 0;
    }
    const int ret = g_dlExit(static_cast<unsigned int>(procId));
    g_inited = false;
    g_nv12Buffer.clear();
    g_nv12Buffer.shrink_to_fit();
    g_debugBuffer.clear();
    g_debugBuffer.shrink_to_fit();
    g_rgbOut.clear();
    g_rgbOut.shrink_to_fit();
    ALOGI("DL_Exit ret=%d", ret);
    return ret;
}
