#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <map>
#include <mutex>
#include "ncnn_engine.h"

#define LOG_TAG "NativeEnhanceJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::map<jlong, kotopogoda::NcnnEngine*> g_engines;
static std::mutex g_enginesMutex;
static jlong g_nextHandle = 1;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kotopogoda_uploader_feature_viewer_enhance_NativeEnhanceController_nativeInit(
    JNIEnv* env,
    jobject thiz,
    jobject assetManager,
    jstring modelsDir,
    jstring zeroDceParamChecksum,
    jstring zeroDceBinChecksum,
    jstring restormerParamChecksum,
    jstring restormerBinChecksum,
    jint previewProfile,
    jboolean forceCpu
) {
    LOGI("nativeInit вызван");
    
    const char* modelsDirStr = env->GetStringUTFChars(modelsDir, nullptr);
    const char* zeroDceParamChecksumStr = env->GetStringUTFChars(zeroDceParamChecksum, nullptr);
    const char* zeroDceBinChecksumStr = env->GetStringUTFChars(zeroDceBinChecksum, nullptr);
    const char* restormerParamChecksumStr = env->GetStringUTFChars(restormerParamChecksum, nullptr);
    const char* restormerBinChecksumStr = env->GetStringUTFChars(restormerBinChecksum, nullptr);
    
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    
    auto* engine = new kotopogoda::NcnnEngine();
    
    kotopogoda::PreviewProfile profile = previewProfile == 1 
        ? kotopogoda::PreviewProfile::QUALITY 
        : kotopogoda::PreviewProfile::BALANCED;
    
    bool success = engine->initialize(
        mgr,
        std::string(modelsDirStr),
        { std::string(zeroDceParamChecksumStr), std::string(zeroDceBinChecksumStr) },
        { std::string(restormerParamChecksumStr), std::string(restormerBinChecksumStr) },
        profile,
        forceCpu == JNI_TRUE
    );

    env->ReleaseStringUTFChars(modelsDir, modelsDirStr);
    env->ReleaseStringUTFChars(zeroDceParamChecksum, zeroDceParamChecksumStr);
    env->ReleaseStringUTFChars(zeroDceBinChecksum, zeroDceBinChecksumStr);
    env->ReleaseStringUTFChars(restormerParamChecksum, restormerParamChecksumStr);
    env->ReleaseStringUTFChars(restormerBinChecksum, restormerBinChecksumStr);
    
    if (!success) {
        LOGE("Не удалось инициализировать движок");
        delete engine;
        return 0;
    }
    
    std::lock_guard<std::mutex> lock(g_enginesMutex);
    jlong handle = g_nextHandle++;
    g_engines[handle] = engine;
    
    LOGI("Движок инициализирован с handle=%lld", (long long)handle);
    
    return handle;
}

JNIEXPORT jlongArray JNICALL
Java_com_kotopogoda_uploader_feature_viewer_enhance_NativeEnhanceController_nativeRunPreview(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jobject bitmap,
    jfloat strength
) {
    LOGI("nativeRunPreview вызван: handle=%lld, strength=%.2f", (long long)handle, strength);
    
    kotopogoda::NcnnEngine* engine = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_enginesMutex);
        auto it = g_engines.find(handle);
        if (it == g_engines.end()) {
            LOGE("Недействительный handle: %lld", (long long)handle);
            return nullptr;
        }
        engine = it->second;
    }
    
    kotopogoda::TelemetryData telemetry;
    bool success = engine->runPreview(env, bitmap, strength, telemetry);
    
    jlongArray result = env->NewLongArray(9);
    jlong data[9];
    data[0] = success ? 1 : 0;
    data[1] = telemetry.timingMs;
    data[2] = telemetry.usedVulkan ? 1 : 0;
    data[3] = telemetry.peakMemoryKb;
    data[4] = telemetry.cancelled ? 1 : 0;
    data[5] = telemetry.fallbackUsed ? 1 : 0;
    data[6] = static_cast<jlong>(telemetry.fallbackCause);
    data[7] = telemetry.durationMsVulkan;
    data[8] = telemetry.durationMsCpu;

    env->SetLongArrayRegion(result, 0, 9, data);
    
    LOGI("nativeRunPreview завершен: success=%d, timing=%ldms", success, telemetry.timingMs);
    
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_com_kotopogoda_uploader_feature_viewer_enhance_NativeEnhanceController_nativeRunFull(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jobject sourceBitmap,
    jfloat strength,
    jobject outputBitmap
) {
    LOGI("nativeRunFull вызван: handle=%lld, strength=%.2f", (long long)handle, strength);
    
    kotopogoda::NcnnEngine* engine = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_enginesMutex);
        auto it = g_engines.find(handle);
        if (it == g_engines.end()) {
            LOGE("Недействительный handle: %lld", (long long)handle);
            return nullptr;
        }
        engine = it->second;
    }
    
    kotopogoda::TelemetryData telemetry;
    bool success = engine->runFull(env, sourceBitmap, strength, outputBitmap, telemetry);
    
    jlongArray result = env->NewLongArray(9);
    jlong data[9];
    data[0] = success ? 1 : 0;
    data[1] = telemetry.timingMs;
    data[2] = telemetry.usedVulkan ? 1 : 0;
    data[3] = telemetry.peakMemoryKb;
    data[4] = telemetry.cancelled ? 1 : 0;
    data[5] = telemetry.fallbackUsed ? 1 : 0;
    data[6] = static_cast<jlong>(telemetry.fallbackCause);
    data[7] = telemetry.durationMsVulkan;
    data[8] = telemetry.durationMsCpu;

    env->SetLongArrayRegion(result, 0, 9, data);
    
    LOGI("nativeRunFull завершен: success=%d, timing=%ldms, cancelled=%d", 
         success, telemetry.timingMs, telemetry.cancelled);
    
    return result;
}

JNIEXPORT void JNICALL
Java_com_kotopogoda_uploader_feature_viewer_enhance_NativeEnhanceController_nativeCancel(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    LOGI("nativeCancel вызван: handle=%lld", (long long)handle);
    
    kotopogoda::NcnnEngine* engine = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_enginesMutex);
        auto it = g_engines.find(handle);
        if (it == g_engines.end()) {
            LOGE("Недействительный handle: %lld", (long long)handle);
            return;
        }
        engine = it->second;
    }
    
    engine->cancel();
}

JNIEXPORT void JNICALL
Java_com_kotopogoda_uploader_feature_viewer_enhance_NativeEnhanceController_nativeRelease(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    LOGI("nativeRelease вызван: handle=%lld", (long long)handle);
    
    kotopogoda::NcnnEngine* engine = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_enginesMutex);
        auto it = g_engines.find(handle);
        if (it == g_engines.end()) {
            LOGE("Недействительный handle: %lld", (long long)handle);
            return;
        }
        engine = it->second;
        g_engines.erase(it);
    }
    
    engine->release();
    delete engine;
    
    LOGI("Движок с handle=%lld освобожден", (long long)handle);
}

JNIEXPORT jboolean JNICALL
Java_com_kotopogoda_uploader_feature_viewer_enhance_NativeEnhanceController_nativeIsGpuDelegateAvailable(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    (void)env;
    (void)thiz;

    std::lock_guard<std::mutex> lock(g_enginesMutex);
    auto it = g_engines.find(handle);
    if (it == g_engines.end()) {
        return JNI_FALSE;
    }

    return it->second->isGpuDelegateAvailable() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_kotopogoda_uploader_feature_viewer_enhance_NativeEnhanceController_nativeConsumeIntegrityFailure(
    JNIEnv* env,
    jclass clazz
) {
    (void)clazz;
    kotopogoda::NcnnEngine::IntegrityFailure failure =
        kotopogoda::NcnnEngine::consumeLastIntegrityFailure();

    if (!failure.hasFailure) {
        return nullptr;
    }

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }

    jobjectArray array = env->NewObjectArray(3, stringClass, nullptr);
    if (array == nullptr) {
        return nullptr;
    }

    jstring filePath = env->NewStringUTF(failure.filePath.c_str());
    jstring expected = env->NewStringUTF(failure.expectedChecksum.c_str());
    jstring actual = env->NewStringUTF(failure.actualChecksum.c_str());

    env->SetObjectArrayElement(array, 0, filePath);
    env->SetObjectArrayElement(array, 1, expected);
    env->SetObjectArrayElement(array, 2, actual);

    env->DeleteLocalRef(filePath);
    env->DeleteLocalRef(expected);
    env->DeleteLocalRef(actual);

    return array;
}

}
