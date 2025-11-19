#include "ncnn_engine.h"
#include "sha256_verifier.h"
#include "restormer_backend.h"
#include "zerodce_backend.h"
#include <ncnn/net.h>
#include <ncnn/gpu.h>
#include <ncnn/cpu.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/bitmap.h>
#include <fstream>
#include <algorithm>
#include <cctype>
#include <chrono>

#define LOG_TAG "NcnnEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace kotopogoda {

namespace {
const char* delegateToString(DelegateType delegate) {
    return delegate == DelegateType::VULKAN ? "vulkan" : "cpu";
}
}

std::mutex NcnnEngine::integrityMutex_;
NcnnEngine::IntegrityFailure NcnnEngine::lastIntegrityFailure_;

NcnnEngine::NcnnEngine()
    : vulkanDevice_(nullptr),
      previewProfile_(PreviewProfile::BALANCED),
      modelsDir_(),
      assetManager_(nullptr),
      initialized_(false),
      cancelled_(false),
      vulkanAvailable_(false),
      gpuDelegateAvailable_(false) {
}

NcnnEngine::~NcnnEngine() {
    release();
}

void NcnnEngine::setupVulkan(int gpuCount) {
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

bool NcnnEngine::switchToCpuFallback() {
    if (modelsDir_.empty()) {
        LOGE("Невозможно выполнить CPU fallback: неизвестна директория моделей");
        return false;
    }

    LOGW("ENHANCE/FALLBACK: переключение на CPU после ошибки Vulkan");
    cleanupVulkan();
    ncnn::destroy_gpu_instance();
    return loadModelsForDelegate(modelsDir_, false);
}

void NcnnEngine::reportIntegrityFailure(
    const std::string& filePath,
    const std::string& expectedChecksum,
    const std::string& actualChecksum
) {
    std::lock_guard<std::mutex> lock(integrityMutex_);
    lastIntegrityFailure_.hasFailure = true;
    lastIntegrityFailure_.filePath = filePath;
    lastIntegrityFailure_.expectedChecksum = expectedChecksum;
    lastIntegrityFailure_.actualChecksum = actualChecksum;
}

NcnnEngine::IntegrityFailure NcnnEngine::consumeLastIntegrityFailure() {
    std::lock_guard<std::mutex> lock(integrityMutex_);
    IntegrityFailure failure = lastIntegrityFailure_;
    lastIntegrityFailure_ = IntegrityFailure{};
    return failure;
}

bool NcnnEngine::verifyChecksum(const std::string& filePath, const std::string& expectedChecksum) {
    if (expectedChecksum.empty()) {
        LOGE("Ожидаемая контрольная сумма не указана для %s", filePath.c_str());
        reportIntegrityFailure(filePath, expectedChecksum, "");
        return false;
    }

    std::string computed = Sha256Verifier::computeSha256(filePath);

    if (computed.empty()) {
        LOGE("Не удалось вычислить SHA256 для %s", filePath.c_str());
        reportIntegrityFailure(filePath, expectedChecksum, "");
        return false;
    }

    std::string normalizedExpected = expectedChecksum;
    std::transform(
        normalizedExpected.begin(),
        normalizedExpected.end(),
        normalizedExpected.begin(),
        [](unsigned char c) { return static_cast<char>(std::tolower(c)); }
    );

    if (computed != normalizedExpected) {
        LOGW("Несоответствие контрольной суммы для %s", filePath.c_str());
        LOGW("Ожидалось: %s", normalizedExpected.c_str());
        LOGW("Получено:  %s", computed.c_str());
        reportIntegrityFailure(filePath, normalizedExpected, computed);
        return false;
    }

    LOGI("Контрольная сумма проверена для %s", filePath.c_str());
    return true;
}

bool NcnnEngine::loadModels(AAssetManager* assetManager, const std::string& modelsDir) {
    (void)assetManager;
    return loadModelsForDelegate(modelsDir, vulkanAvailable_.load());
}

bool NcnnEngine::loadModelsForDelegate(const std::string& modelsDir, bool useVulkan) {
    zeroDceNet_.reset();
    restormerNet_.reset();
    zeroDceNet_ = std::make_unique<ncnn::Net>();
    restormerNet_ = std::make_unique<ncnn::Net>();

    vulkanAvailable_ = useVulkan;

    zeroDceNet_->opt.use_vulkan_compute = useVulkan;
    restormerNet_->opt.use_vulkan_compute = useVulkan;

    zeroDceNet_->opt.use_fp16_packed = true;
    zeroDceNet_->opt.use_fp16_storage = true;
    zeroDceNet_->opt.use_fp16_arithmetic = false;

    restormerNet_->opt.use_fp16_packed = true;
    restormerNet_->opt.use_fp16_storage = true;
    restormerNet_->opt.use_fp16_arithmetic = false;

    if (useVulkan) {
        zeroDceNet_->opt.num_threads = 4;
        restormerNet_->opt.num_threads = 8;
        LOGI("Модели будут использовать Vulkan");
    } else {
        int cpuThreads = std::max(1, std::min(4, ncnn::get_big_cpu_count()));
        zeroDceNet_->opt.num_threads = cpuThreads;
        restormerNet_->opt.num_threads = cpuThreads;
        LOGI("Модели будут использовать CPU с %d потоками", cpuThreads);
    }

    std::string zeroDceParam = modelsDir + "/zerodcepp_fp16.param";
    std::string zeroDceBin = modelsDir + "/zerodcepp_fp16.bin";
    std::string restormerParam = modelsDir + "/restormer_fp16.param";
    std::string restormerBin = modelsDir + "/restormer_fp16.bin";

    if (!verifyChecksum(zeroDceParam, zeroDceChecksums_.param)) {
        LOGE("Контрольная сумма Zero-DCE++ param не совпадает");
        return false;
    }

    LOGI("Загрузка Zero-DCE++ из %s", zeroDceParam.c_str());
    int ret = zeroDceNet_->load_param(zeroDceParam.c_str());
    if (ret != 0) {
        LOGE("Не удалось загрузить параметры Zero-DCE++: %d", ret);
        if (useVulkan) {
            LOGW("delegate=vulkan cause=load_failed stage=zerodce_param ret=%d", ret);
            cleanupVulkan();
            ncnn::destroy_gpu_instance();
            return loadModelsForDelegate(modelsDir, false);
        }
        return false;
    }

    if (!verifyChecksum(zeroDceBin, zeroDceChecksums_.bin)) {
        LOGE("Контрольная сумма Zero-DCE++ bin не совпадает");
        return false;
    }

    ret = zeroDceNet_->load_model(zeroDceBin.c_str());
    if (ret != 0) {
        LOGE("Не удалось загрузить модель Zero-DCE++: %d", ret);
        if (useVulkan) {
            LOGW("delegate=vulkan cause=load_failed stage=zerodce_model ret=%d", ret);
            cleanupVulkan();
            ncnn::destroy_gpu_instance();
            return loadModelsForDelegate(modelsDir, false);
        }
        return false;
    }

    if (!verifyChecksum(restormerParam, restormerChecksums_.param)) {
        LOGE("Контрольная сумма Restormer param не совпадает");
        return false;
    }

    LOGI("Загрузка Restormer из %s", restormerParam.c_str());
    ret = restormerNet_->load_param(restormerParam.c_str());
    if (ret != 0) {
        LOGE("Не удалось загрузить параметры Restormer: %d", ret);
        if (useVulkan) {
            LOGW("delegate=vulkan cause=load_failed stage=restormer_param ret=%d", ret);
            cleanupVulkan();
            ncnn::destroy_gpu_instance();
            return loadModelsForDelegate(modelsDir, false);
        }
        return false;
    }

    if (!verifyChecksum(restormerBin, restormerChecksums_.bin)) {
        LOGE("Контрольная сумма Restormer bin не совпадает");
        return false;
    }

    ret = restormerNet_->load_model(restormerBin.c_str());
    if (ret != 0) {
        LOGE("Не удалось загрузить модель Restormer: %d", ret);
        if (useVulkan) {
            LOGW("delegate=vulkan cause=load_failed stage=restormer_model ret=%d", ret);
            cleanupVulkan();
            ncnn::destroy_gpu_instance();
            return loadModelsForDelegate(modelsDir, false);
        }
        return false;
    }

    LOGI("Все модели загружены успешно");
    return true;
}
bool NcnnEngine::initialize(
    AAssetManager* assetManager,
    const std::string& modelsDir,
    const ModelChecksums& zeroDceChecksums,
    const ModelChecksums& restormerChecksums,
    PreviewProfile profile,
    bool forceCpu
) {
    if (initialized_.load()) {
        LOGW("Движок уже инициализирован");
        return true;
    }
    
    LOGI("Инициализация NCNN движка");
    LOGI("Директория моделей: %s", modelsDir.c_str());
    LOGI("Профиль превью: %d", static_cast<int>(profile));
    
    zeroDceChecksums_ = zeroDceChecksums;
    restormerChecksums_ = restormerChecksums;
    previewProfile_ = profile;
    assetManager_ = assetManager;
    modelsDir_ = modelsDir;

    if (forceCpu) {
        gpuDelegateAvailable_.store(false);
        vulkanDevice_ = nullptr;
        vulkanAvailable_ = false;
        LOGW("Vulkan принудительно отключен");
    } else {
        int gpuCount = ncnn::get_gpu_count();
        bool gpuAvailable = gpuCount > 0;
        gpuDelegateAvailable_.store(gpuAvailable);
        setupVulkan(gpuCount);
    }

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
    
    mat.create(static_cast<int>(info.width), static_cast<int>(info.height), 3, 4u, nullptr);
    
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

    telemetry.fallbackUsed = false;
    telemetry.durationMsVulkan = 0;
    telemetry.durationMsCpu = 0;
    telemetry.fallbackCause = FallbackCause::NONE;
    telemetry.delegate = vulkanAvailable_.load() ? DelegateType::VULKAN : DelegateType::CPU;
    telemetry.extractorError = TelemetryData::ExtractorErrorTelemetry{};

    auto propagateExtractorError = [&](const TelemetryData& sourceTelemetry, const char* stage) {
        if (!sourceTelemetry.extractorError.hasError) {
            return;
        }
        telemetry.extractorError = sourceTelemetry.extractorError;
        LOGE(
            "ENHANCE/ERROR: stage=%s delegate=%s extractor_ret=%d duration_ms=%ld",
            stage,
            delegateToString(telemetry.delegate),
            sourceTelemetry.extractorError.ret,
            sourceTelemetry.extractorError.durationMs
        );
    };

    auto runPipeline = [&, strength](
        ncnn::Mat& output,
        bool* delegateFailed,
        FallbackCause* fallbackCause
    ) -> bool {
        telemetry.tileTelemetry = TelemetryData::TileTelemetry{};
        telemetry.timingMs = 0;
        telemetry.seamMaxDelta = 0.0f;
        telemetry.seamMeanDelta = 0.0f;
        telemetry.gpuAllocRetryCount = 0;

        if (previewProfile_ == PreviewProfile::QUALITY) {
            ncnn::Mat restOutput;
            RestormerBackend restormer(restormerNet_.get(), cancelled_, vulkanAvailable_.load());
            TelemetryData restormerTelemetry;

            if (!restormer.process(inputMat, restOutput, restormerTelemetry, delegateFailed, fallbackCause)) {
                propagateExtractorError(restormerTelemetry, "restormer_preview");
                return false;
            }

            telemetry.timingMs += restormerTelemetry.timingMs;

            ZeroDceBackend zeroDce(zeroDceNet_.get(), cancelled_, vulkanAvailable_.load());
            TelemetryData zeroDceTelemetry;
            ncnn::Mat finalMat;

            if (!zeroDce.process(restOutput, finalMat, strength, zeroDceTelemetry, delegateFailed, fallbackCause)) {
                propagateExtractorError(zeroDceTelemetry, "zerodce_preview_quality");
                return false;
            }

            telemetry.timingMs += zeroDceTelemetry.timingMs;
            telemetry.tileTelemetry = zeroDceTelemetry.tileTelemetry;
            telemetry.seamMaxDelta = zeroDceTelemetry.seamMaxDelta;
            telemetry.seamMeanDelta = zeroDceTelemetry.seamMeanDelta;
            telemetry.gpuAllocRetryCount = zeroDceTelemetry.gpuAllocRetryCount;
            output = finalMat;
            return true;
        }

        ZeroDceBackend zeroDce(zeroDceNet_.get(), cancelled_, vulkanAvailable_.load());
        bool ok = zeroDce.process(inputMat, output, strength, telemetry, delegateFailed, fallbackCause);
        if (!ok) {
            propagateExtractorError(telemetry, "zerodce_preview_balanced");
        }
        return ok;
    };

    bool delegateFailed = false;
    FallbackCause failureCause = FallbackCause::NONE;
    ncnn::Mat outputMat;
    auto gpuStart = std::chrono::high_resolution_clock::now();
    bool success = runPipeline(outputMat, &delegateFailed, &failureCause);

    const bool initialVulkan = telemetry.delegate == DelegateType::VULKAN;

    if (!success && delegateFailed && initialVulkan) {
        auto failureTime = std::chrono::high_resolution_clock::now();
        telemetry.durationMsVulkan = std::chrono::duration_cast<std::chrono::milliseconds>(failureTime - gpuStart).count();
        telemetry.fallbackUsed = true;
        telemetry.fallbackCause = failureCause;

        if (!switchToCpuFallback()) {
            LOGE("Не удалось выполнить fallback на CPU");
            return false;
        }

        telemetry.delegate = DelegateType::CPU;
        delegateFailed = false;
        ncnn::Mat cpuOutput;
        auto cpuStart = std::chrono::high_resolution_clock::now();

        if (!runPipeline(cpuOutput, nullptr, nullptr)) {
            return false;
        }

        telemetry.durationMsCpu = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::high_resolution_clock::now() - cpuStart
        ).count();
        outputMat = cpuOutput;
    } else if (!success) {
        return false;
    }

    matToBitmap(env, outputMat, sourceBitmap);

    telemetry.usedVulkan = !telemetry.fallbackUsed && telemetry.delegate == DelegateType::VULKAN;
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

    telemetry.fallbackUsed = false;
    telemetry.durationMsVulkan = 0;
    telemetry.durationMsCpu = 0;
    telemetry.fallbackCause = FallbackCause::NONE;
    telemetry.delegate = vulkanAvailable_.load() ? DelegateType::VULKAN : DelegateType::CPU;
    telemetry.extractorError = TelemetryData::ExtractorErrorTelemetry{};

    auto propagateExtractorError = [&](const TelemetryData& sourceTelemetry, const char* stage) {
        if (!sourceTelemetry.extractorError.hasError) {
            return;
        }
        telemetry.extractorError = sourceTelemetry.extractorError;
        LOGE(
            "ENHANCE/ERROR: stage=%s delegate=%s extractor_ret=%d duration_ms=%ld",
            stage,
            delegateToString(telemetry.delegate),
            sourceTelemetry.extractorError.ret,
            sourceTelemetry.extractorError.durationMs
        );
    };

    auto runPipeline = [&, strength](
        ncnn::Mat& finalMat,
        bool* delegateFailed,
        FallbackCause* fallbackCause
    ) -> bool {
        telemetry.tileTelemetry = TelemetryData::TileTelemetry{};
        telemetry.timingMs = 0;
        telemetry.seamMaxDelta = 0.0f;
        telemetry.seamMeanDelta = 0.0f;
        telemetry.gpuAllocRetryCount = 0;

        RestormerBackend restormer(restormerNet_.get(), cancelled_, vulkanAvailable_.load());
        TelemetryData restormerTelemetry;

        ncnn::Mat restOutput;
        if (!restormer.process(inputMat, restOutput, restormerTelemetry, delegateFailed, fallbackCause)) {
            propagateExtractorError(restormerTelemetry, "restormer_full");
            return false;
        }

        telemetry.timingMs += restormerTelemetry.timingMs;

        ZeroDceBackend zeroDce(zeroDceNet_.get(), cancelled_, vulkanAvailable_.load());
        TelemetryData zeroDceTelemetry;

        if (!zeroDce.process(restOutput, finalMat, strength, zeroDceTelemetry, delegateFailed, fallbackCause)) {
            propagateExtractorError(zeroDceTelemetry, "zerodce_full");
            return false;
        }

        telemetry.timingMs += zeroDceTelemetry.timingMs;
        telemetry.tileTelemetry = zeroDceTelemetry.tileTelemetry;
        telemetry.seamMaxDelta = zeroDceTelemetry.seamMaxDelta;
        telemetry.seamMeanDelta = zeroDceTelemetry.seamMeanDelta;
        telemetry.gpuAllocRetryCount = zeroDceTelemetry.gpuAllocRetryCount;
        return true;
    };

    bool delegateFailed = false;
    FallbackCause failureCause = FallbackCause::NONE;
    ncnn::Mat finalMat;
    auto gpuStart = std::chrono::high_resolution_clock::now();
    bool success = runPipeline(finalMat, &delegateFailed, &failureCause);

    const bool initialVulkan = telemetry.delegate == DelegateType::VULKAN;

    if (!success && delegateFailed && initialVulkan) {
        auto failureTime = std::chrono::high_resolution_clock::now();
        telemetry.durationMsVulkan = std::chrono::duration_cast<std::chrono::milliseconds>(failureTime - gpuStart).count();
        telemetry.fallbackUsed = true;
        telemetry.fallbackCause = failureCause;

        if (!switchToCpuFallback()) {
            LOGE("Не удалось выполнить fallback на CPU");
            return false;
        }

        telemetry.delegate = DelegateType::CPU;
        delegateFailed = false;
        auto cpuStart = std::chrono::high_resolution_clock::now();

        if (!runPipeline(finalMat, nullptr, nullptr)) {
            return false;
        }

        telemetry.durationMsCpu = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::high_resolution_clock::now() - cpuStart
        ).count();
    } else if (!success) {
        return false;
    }

    matToBitmap(env, finalMat, outputBitmap);

    telemetry.usedVulkan = !telemetry.fallbackUsed && telemetry.delegate == DelegateType::VULKAN;
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

    modelsDir_.clear();
    assetManager_ = nullptr;

    initialized_ = false;
}

}
