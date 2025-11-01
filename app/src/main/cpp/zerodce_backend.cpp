#include "zerodce_backend.h"
#include <ncnn/mat.h>
#include <ncnn/net.h>
#include <android/log.h>
#include <chrono>

#define LOG_TAG "ZeroDceBackend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace kotopogoda {

ZeroDceBackend::ZeroDceBackend(ncnn::Net* net, std::atomic<bool>& cancelFlag)
    : net_(net), cancelFlag_(cancelFlag) {
}

ZeroDceBackend::~ZeroDceBackend() {
}

bool ZeroDceBackend::process(
    const ncnn::Mat& input,
    ncnn::Mat& output,
    float strength,
    TelemetryData& telemetry
) {
    if (cancelFlag_.load()) {
        return false;
    }
    
    auto startTime = std::chrono::high_resolution_clock::now();
    
    LOGI("Начало обработки Zero-DCE++: %dx%dx%d, strength=%.2f", input.w, input.h, input.c, strength);
    
    ncnn::Extractor ex = net_->create_extractor();
    ex.input("input", input);
    
    ncnn::Mat enhancedOutput;
    int ret = ex.extract("output", enhancedOutput);
    
    if (ret != 0 || cancelFlag_.load()) {
        LOGW("Ошибка извлечения выхода Zero-DCE++");
        return false;
    }
    
    output.create(input.w, input.h, input.c);
    
    for (int c = 0; c < input.c; ++c) {
        const float* srcChannel = input.channel(c);
        const float* enhChannel = enhancedOutput.channel(c);
        float* dstChannel = output.channel(c);
        
        for (int i = 0; i < input.w * input.h; ++i) {
            dstChannel[i] = srcChannel[i] * (1.0f - strength) + enhChannel[i] * strength;
        }
    }
    
    auto endTime = std::chrono::high_resolution_clock::now();
    telemetry.timingMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
    
    LOGI("Обработка Zero-DCE++ завершена за %ld мс", telemetry.timingMs);
    
    return !cancelFlag_.load();
}

}
