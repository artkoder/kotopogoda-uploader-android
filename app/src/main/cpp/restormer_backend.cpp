#include "restormer_backend.h"
#include "tile_processor.h"
#include "ncnn_engine.h"
#include <ncnn/mat.h>
#include <ncnn/net.h>
#include <android/log.h>
#include <chrono>

#define LOG_TAG "RestormerBackend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace kotopogoda {

RestormerBackend::RestormerBackend(ncnn::Net* net, std::atomic<bool>& cancelFlag, bool usingVulkan)
    : net_(net), cancelFlag_(cancelFlag), usingVulkan_(usingVulkan) {
    
    TileConfig config;
    config.tileSize = 512;
    config.overlap = 16;
    config.maxMemoryMb = 512;
    config.threadCount = 4;
    
    tileProcessor_ = std::make_unique<TileProcessor>(config, cancelFlag);
}

RestormerBackend::~RestormerBackend() {
}

bool RestormerBackend::processDirectly(
    const ncnn::Mat& input,
    ncnn::Mat& output,
    bool* delegateFailed,
    FallbackCause* fallbackCause
) {
    if (cancelFlag_.load()) {
        return false;
    }

    ncnn::Extractor ex = net_->create_extractor();
    int ret = ex.input("input", input);
    if (ret != 0) {
        if (usingVulkan_ && delegateFailed) {
            *delegateFailed = true;
            if (fallbackCause) {
                *fallbackCause = FallbackCause::EXTRACT_FAILED;
            }
            LOGW("delegate=vulkan cause=extract_failed stage=restormer_input ret=%d", ret);
        }
        return false;
    }

    ret = ex.extract("output", output);
    if (ret != 0) {
        if (usingVulkan_ && delegateFailed) {
            *delegateFailed = true;
            if (fallbackCause) {
                *fallbackCause = FallbackCause::EXTRACT_FAILED;
            }
            LOGW("delegate=vulkan cause=extract_failed stage=restormer_output ret=%d", ret);
        }
        return false;
    }

    return !cancelFlag_.load();
}

bool RestormerBackend::process(
    const ncnn::Mat& input,
    ncnn::Mat& output,
    TelemetryData& telemetry,
    bool* delegateFailed,
    FallbackCause* fallbackCause
) {
    auto startTime = std::chrono::high_resolution_clock::now();
    
    LOGI("Начало обработки Restormer: %dx%dx%d", input.w, input.h, input.c);
    
    bool needsTiling = input.w > 512 || input.h > 512;
    bool success = false;
    
    if (needsTiling) {
        LOGI("Используется тайловая обработка");
        
        auto processFunc = [this, delegateFailed, fallbackCause](
            const ncnn::Mat& tileIn,
            ncnn::Mat& tileOut,
            ncnn::Net* net
        ) -> bool {
            (void)net;
            return this->processDirectly(tileIn, tileOut, delegateFailed, fallbackCause);
        };

        success = tileProcessor_->processTiled(input, output, net_, processFunc);
    } else {
        LOGI("Обработка без тайлинга");
        success = processDirectly(input, output, delegateFailed, fallbackCause);
    }
    
    auto endTime = std::chrono::high_resolution_clock::now();
    telemetry.timingMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
    
    LOGI("Обработка Restormer завершена за %ld мс, успех=%d", telemetry.timingMs, success);
    
    return success;
}

}
