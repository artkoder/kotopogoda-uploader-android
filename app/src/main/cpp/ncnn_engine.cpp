#include "ncnn_engine.h"
#include "sha256_verifier.h"
#include "restormer_backend.h"
#include "zerodce_backend.h"
#include <ncnn/net.h>
#include <ncnn/gpu.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/bitmap.h>
#include <fstream>

#define LOG_TAG "NcnnEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace kotopogoda {

std::atomic<bool> NcnnEngine::checksumVerified_(false);
std::atomic<bool> NcnnEngine::checksumMismatchLogged_(false);

NcnnEngine::NcnnEngine()
    : vulkanDevice_(nullptr),
      previewProfile_(PreviewProfile::BALANCED),
      initialized_(false),
      cancelled_(false),
      vulkanAvailable_(false) {
}

NcnnEngine::~NcnnEngine() {
    release();
}

void NcnnEngine::setupVulkan() {
    int gpuCount = ncnn::get_gpu_count();
    LOGI("Количество доступных GPU: %d", gpuCount);
    
    if (gpuCount > 0) {
        vulkanDevice_ = ncnn::get_gpu_device(0);
        vulkanAvailable_ = true;
        LOGI("Vulkan включен, используется устройство 0");
    } else {
        vulkanDevice_ = nullptr;
        vulkanAvailable_ = false;
        LOGI("Vulkan недоступен, используется CPU");
    }
}

void NcnnEngine::cleanupVulkan() {
    vulkanDevice_ = nullptr;
    vulkanAvailable_ = false;
}

bool NcnnEngine::verifyChecksum(const std::string& filePath, const std::string& expectedChecksum) {
    if (checksumVerified_.load()) {
        return true;
    }
    
    std::string computed = Sha256Verifier::computeSha256(filePath);
    
    if (computed.empty()) {
        LOGE("Не удалось вычислить SHA256 для %s", filePath.c_str());
        return false;
    }
    
    if (computed != expectedChecksum) {
        if (!checksumMismatchLogged_.exchange(true)) {
            LOGW("Несоответствие контрольной суммы для %s", filePath.c_str());
            LOGW("Ожидалось: %s", expectedChecksum.c_str());
            LOGW("Получено:  %s", computed.c_str());
        }
        return false;
    }
    
    LOGI("Контрольная сумма проверена для %s", filePath.c_str());
    checksumVerified_ = true;
    return true;
}

bool NcnnEngine::loadModels(AAssetManager* assetManager, const std::string& modelsDir) {
    zeroDceNet_ = std::make_unique<ncnn::Net>();
    restormerNet_ = std::make_unique<ncnn::Net>();
    
    if (vulkanAvailable_) {
        zeroDceNet_->opt.use_vulkan_compute = true;
        restormerNet_->opt.use_vulkan_compute = true;
        LOGI("Модели будут использовать Vulkan");
    } else {
        zeroDceNet_->opt.use_vulkan_compute = false;
        restormerNet_->opt.use_vulkan_compute = false;
    }
    
    zeroDceNet_->opt.use_fp16_packed = true;
    zeroDceNet_->opt.use_fp16_storage = true;
    zeroDceNet_->opt.use_fp16_arithmetic = false;
    zeroDceNet_->opt.num_threads = 4;
    
    restormerNet_->opt.use_fp16_packed = true;
    restormerNet_->opt.use_fp16_storage = true;
    restormerNet_->opt.use_fp16_arithmetic = false;
    restormerNet_->opt.num_threads = 8;
    
    std::string zeroDceParam = modelsDir + "/zerodce.param";
    std::string zeroDceBin = modelsDir + "/zerodce.bin";
    std::string restormerParam = modelsDir + "/restormer.param";
    std::string restormerBin = modelsDir + "/restormer.bin";
    
    LOGI("Загрузка Zero-DCE++ из %s", zeroDceParam.c_str());
    int ret = zeroDceNet_->load_param(zeroDceParam.c_str());
    if (ret != 0) {
        LOGE("Не удалось загрузить параметры Zero-DCE++: %d", ret);
        return false;
    }
    
    ret = zeroDceNet_->load_model(zeroDceBin.c_str());
    if (ret != 0) {
        LOGE("Не удалось загрузить модель Zero-DCE++: %d", ret);
        return false;
    }
    
    LOGI("Загрузка Restormer из %s", restormerParam.c_str());
    ret = restormerNet_->load_param(restormerParam.c_str());
    if (ret != 0) {
        LOGE("Не удалось загрузить параметры Restormer: %d", ret);
        return false;
    }
    
    ret = restormerNet_->load_model(restormerBin.c_str());
    if (ret != 0) {
        LOGE("Не удалось загрузить модель Restormer: %d", ret);
        return false;
    }
    
    LOGI("Все модели загружены успешно");
    return true;
}

bool NcnnEngine::initialize(
    AAssetManager* assetManager,
    const std::string& modelsDir,
    const std::string& zeroDceChecksum,
    const std::string& restormerChecksum,
    PreviewProfile profile
) {
    if (initialized_.load()) {
        LOGW("Движок уже инициализирован");
        return true;
    }
    
    LOGI("Инициализация NCNN движка");
    LOGI("Директория моделей: %s", modelsDir.c_str());
    LOGI("Профиль превью: %d", static_cast<int>(profile));
    
    zeroDceChecksum_ = zeroDceChecksum;
    restormerChecksum_ = restormerChecksum;
    previewProfile_ = profile;
    
    setupVulkan();
    
    if (!loadModels(assetManager, modelsDir)) {
        LOGE("Не удалось загрузить модели");
        return false;
    }
    
    initialized_ = true;
    LOGI("NCNN движок успешно инициализирован");
    
    return true;
}

static void bitmapToMat(JNIEnv* env, jobject bitmap, ncnn::Mat& mat) {
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    
    void* pixels;
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    
    mat.create(info.width, info.height, 3);
    
    uint32_t* pixelData = reinterpret_cast<uint32_t*>(pixels);
    
    for (int y = 0; y < static_cast<int>(info.height); ++y) {
        for (int x = 0; x < static_cast<int>(info.width); ++x) {
            uint32_t pixel = pixelData[y * info.width + x];
            
            float r = ((pixel >> 16) & 0xFF) / 255.0f;
            float g = ((pixel >> 8) & 0xFF) / 255.0f;
            float b = (pixel & 0xFF) / 255.0f;
            
            mat.channel(0)[y * info.width + x] = r;
            mat.channel(1)[y * info.width + x] = g;
            mat.channel(2)[y * info.width + x] = b;
        }
    }
    
    AndroidBitmap_unlockPixels(env, bitmap);
}

static void matToBitmap(JNIEnv* env, const ncnn::Mat& mat, jobject bitmap) {
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    
    void* pixels;
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    
    uint32_t* pixelData = reinterpret_cast<uint32_t*>(pixels);
    
    for (int y = 0; y < mat.h; ++y) {
        for (int x = 0; x < mat.w; ++x) {
            float r = mat.channel(0)[y * mat.w + x];
            float g = mat.channel(1)[y * mat.w + x];
            float b = mat.channel(2)[y * mat.w + x];
            
            r = std::max(0.0f, std::min(1.0f, r));
            g = std::max(0.0f, std::min(1.0f, g));
            b = std::max(0.0f, std::min(1.0f, b));
            
            uint8_t ri = static_cast<uint8_t>(r * 255.0f);
            uint8_t gi = static_cast<uint8_t>(g * 255.0f);
            uint8_t bi = static_cast<uint8_t>(b * 255.0f);
            
            pixelData[y * info.width + x] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
        }
    }
    
    AndroidBitmap_unlockPixels(env, bitmap);
}

bool NcnnEngine::runPreview(
    JNIEnv* env,
    jobject sourceBitmap,
    float strength,
    TelemetryData& telemetry
) {
    if (!initialized_.load()) {
        LOGE("Движок не инициализирован");
        return false;
    }
    
    cancelled_ = false;
    
    ncnn::Mat inputMat;
    bitmapToMat(env, sourceBitmap, inputMat);
    
    LOGI("Превью: размер входа %dx%d", inputMat.w, inputMat.h);
    
    ncnn::Mat outputMat;
    
    if (previewProfile_ == PreviewProfile::QUALITY) {
        RestormerBackend restormer(restormerNet_.get(), cancelled_);
        TelemetryData restormerTelemetry;
        
        if (!restormer.process(inputMat, outputMat, restormerTelemetry)) {
            LOGE("Ошибка обработки Restormer в превью");
            return false;
        }
        
        telemetry.timingMs += restormerTelemetry.timingMs;
        
        ncnn::Mat finalMat;
        ZeroDceBackend zeroDce(zeroDceNet_.get(), cancelled_);
        TelemetryData zeroDceTelemetry;
        
        if (!zeroDce.process(outputMat, finalMat, strength, zeroDceTelemetry)) {
            LOGE("Ошибка обработки Zero-DCE++ в превью");
            return false;
        }
        
        telemetry.timingMs += zeroDceTelemetry.timingMs;
        outputMat = finalMat;
    } else {
        ZeroDceBackend zeroDce(zeroDceNet_.get(), cancelled_);
        
        if (!zeroDce.process(inputMat, outputMat, strength, telemetry)) {
            LOGE("Ошибка обработки Zero-DCE++ в превью");
            return false;
        }
    }
    
    matToBitmap(env, outputMat, sourceBitmap);
    
    telemetry.usedVulkan = vulkanAvailable_.load();
    telemetry.cancelled = cancelled_.load();
    
    return true;
}

bool NcnnEngine::runFull(
    JNIEnv* env,
    jobject sourceBitmap,
    float strength,
    jobject outputBitmap,
    TelemetryData& telemetry
) {
    if (!initialized_.load()) {
        LOGE("Движок не инициализирован");
        return false;
    }
    
    cancelled_ = false;
    
    ncnn::Mat inputMat;
    bitmapToMat(env, sourceBitmap, inputMat);
    
    LOGI("Полная обработка: размер входа %dx%d", inputMat.w, inputMat.h);
    
    ncnn::Mat outputMat;
    RestormerBackend restormer(restormerNet_.get(), cancelled_);
    TelemetryData restormerTelemetry;
    
    if (!restormer.process(inputMat, outputMat, restormerTelemetry)) {
        LOGE("Ошибка обработки Restormer");
        return false;
    }
    
    telemetry.timingMs += restormerTelemetry.timingMs;
    
    ncnn::Mat finalMat;
    ZeroDceBackend zeroDce(zeroDceNet_.get(), cancelled_);
    TelemetryData zeroDceTelemetry;
    
    if (!zeroDce.process(outputMat, finalMat, strength, zeroDceTelemetry)) {
        LOGE("Ошибка обработки Zero-DCE++");
        return false;
    }
    
    telemetry.timingMs += zeroDceTelemetry.timingMs;
    
    matToBitmap(env, finalMat, outputBitmap);
    
    telemetry.usedVulkan = vulkanAvailable_.load();
    telemetry.cancelled = cancelled_.load();
    
    return true;
}

void NcnnEngine::cancel() {
    LOGI("Запрошена отмена операции");
    cancelled_ = true;
}

void NcnnEngine::release() {
    if (!initialized_.load()) {
        return;
    }
    
    LOGI("Освобождение ресурсов NCNN движка");
    
    zeroDceNet_.reset();
    restormerNet_.reset();
    
    cleanupVulkan();
    
    initialized_ = false;
}

}
