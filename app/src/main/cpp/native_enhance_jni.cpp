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

namespace {

jobject buildTelemetryPayload(
    JNIEnv* env,
    const kotopogoda::TelemetryData& telemetry,
    bool success
) {
    jclass telemetryClass = env->FindClass(
        "com/kotopogoda/uploader/feature/viewer/enhance/NativeRunTelemetry"
    );
    if (telemetryClass == nullptr) {
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(
        telemetryClass,
        "<init>",
        "(ZJZJZZIJJZIIIIFFILjava/lang/String;)V"
    );
    if (ctor == nullptr) {
        env->DeleteLocalRef(telemetryClass);
        return nullptr;
    }

    const char* delegateName = "cpu";
    jstring delegateUsed = env->NewStringUTF(delegateName);
    if (delegateUsed == nullptr) {
        env->DeleteLocalRef(telemetryClass);
        return nullptr;
    }

    jobject payload = env->NewObject(
        telemetryClass,
        ctor,
        success ? JNI_TRUE : JNI_FALSE,
        static_cast<jlong>(telemetry.timingMs),
        telemetry.usedVulkan ? JNI_TRUE : JNI_FALSE,
        static_cast<jlong>(telemetry.peakMemoryKb),
        telemetry.cancelled ? JNI_TRUE : JNI_FALSE,
        telemetry.fallbackUsed ? JNI_TRUE : JNI_FALSE,
        static_cast<jint>(telemetry.fallbackCause),
        static_cast<jlong>(telemetry.durationMsVulkan),
        static_cast<jlong>(telemetry.durationMsCpu),
        telemetry.tileTelemetry.tileUsed ? JNI_TRUE : JNI_FALSE,
        static_cast<jint>(telemetry.tileTelemetry.tileSize),
        static_cast<jint>(telemetry.tileTelemetry.overlap),
        static_cast<jint>(telemetry.tileTelemetry.totalTiles),
        static_cast<jint>(telemetry.tileTelemetry.processedTiles),
        telemetry.seamMaxDelta,
        telemetry.seamMeanDelta,
        static_cast<jint>(telemetry.gpuAllocRetryCount),
        delegateUsed
    );

    env->DeleteLocalRef(delegateUsed);
    env->DeleteLocalRef(telemetryClass);
    return payload;
}

}

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

JNIEXPORT jobject JNICALL
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
    
    jobject payload = buildTelemetryPayload(env, telemetry, success);

    LOGI("nativeRunPreview завершен: success=%d, timing=%ldms", success, telemetry.timingMs);

    return payload;
}

JNIEXPORT jobject JNICALL
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
    
    jobject payload = buildTelemetryPayload(env, telemetry, success);

    LOGI("nativeRunFull завершен: success=%d, timing=%ldms, cancelled=%d",
         success, telemetry.timingMs, telemetry.cancelled);

    return payload;
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
