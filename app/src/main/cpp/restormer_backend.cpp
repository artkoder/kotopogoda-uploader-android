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

RestormerBackend::RestormerBackend(ncnn::Net* net, std::atomic<bool>& cancelFlag)
    : net_(net), cancelFlag_(cancelFlag) {
    
    TileConfig config;
    config.tileSize = 512;
    config.overlap = 16;
    config.maxMemoryMb = 512;
    config.threadCount = 4;
    
    tileProcessor_ = std::make_unique<TileProcessor>(config, cancelFlag);
}

RestormerBackend::~RestormerBackend() {
}

bool RestormerBackend::processDirectly(const ncnn::Mat& input, ncnn::Mat& output) {
    if (cancelFlag_.load()) {
        return false;
    }
    
    ncnn::Extractor ex = net_->create_extractor();
    ex.input("input", input);
    ex.extract("output", output);
    
    return !cancelFlag_.load();
}

bool RestormerBackend::process(
    const ncnn::Mat& input,
    ncnn::Mat& output,
    TelemetryData& telemetry
) {
    auto startTime = std::chrono::high_resolution_clock::now();
    
    LOGI("Начало обработки Restormer: %dx%dx%d", input.w, input.h, input.c);
    
    bool needsTiling = input.w > 512 || input.h > 512;
    bool success = false;
    
    if (needsTiling) {
        LOGI("Используется тайловая обработка");
        
        auto processFunc = [this](const ncnn::Mat& tileIn, ncnn::Mat& tileOut, ncnn::Net* net) -> bool {
            return this->processDirectly(tileIn, tileOut);
        };
        
        success = tileProcessor_->processTiled(input, output, net_, processFunc);
    } else {
        LOGI("Обработка без тайлинга");
        success = processDirectly(input, output);
    }
    
    auto endTime = std::chrono::high_resolution_clock::now();
    telemetry.timingMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
    
    LOGI("Обработка Restormer завершена за %ld мс, успех=%d", telemetry.timingMs, success);
    
    return success;
}

}
