#include "zerodce_backend.h"
#include "ncnn_engine.h"
#include <ncnn/mat.h>
#include <ncnn/net.h>
#include <android/log.h>
#include <chrono>
#include <algorithm>

#define LOG_TAG "ZeroDceBackend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace kotopogoda {

ZeroDceBackend::ZeroDceBackend(ncnn::Net* net, std::atomic<bool>& cancelFlag)
    : net_(net), cancelFlag_(cancelFlag) {}

ZeroDceBackend::~ZeroDceBackend() {
}

bool ZeroDceBackend::processDirectly(
    const ncnn::Mat& input,
    ncnn::Mat& output,
    float strength,
    int* lastErrorCode
) {
    if (cancelFlag_.load()) {
        LOGW("ENHANCE/ERROR: Обработка Zero-DCE++ отменена до старта");
        return false;
    }

    if (lastErrorCode) {
        *lastErrorCode = 0;
    }

    const char* delegateName = net_->opt.use_vulkan_compute ? "vulkan" : "cpu";
    ncnn::Extractor ex = net_->create_extractor();
    int ret = ex.input("input", input);
    if (ret != 0) {
        if (lastErrorCode) {
            *lastErrorCode = ret;
        }
        LOGE(
            "ENHANCE/ERROR: layer=zerodce_input delegate=%s size=%dx%dx%d ret=%d",
            delegateName,
            input.w,
            input.h,
            input.c,
            ret
        );
        LOGW("ENHANCE/ERROR: Не удалось подать вход в Zero-DCE++ (ret=%d)", ret);
        return false;
    }

    ncnn::Mat enhancedOutput;
    ret = ex.extract("output", enhancedOutput);

    if (ret != 0) {
        if (lastErrorCode) {
            *lastErrorCode = ret;
        }
        LOGE(
            "ENHANCE/ERROR: layer=zerodce_output delegate=%s size=%dx%dx%d ret=%d",
            delegateName,
            input.w,
            input.h,
            input.c,
            ret
        );
        LOGW("ENHANCE/ERROR: Ошибка извлечения выхода Zero-DCE++ (код=%d)", ret);
        return false;
    }

    if (cancelFlag_.load()) {
        LOGW("ENHANCE/ERROR: Обработка Zero-DCE++ прервана после экстракции");
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

    return !cancelFlag_.load();
}

bool ZeroDceBackend::process(
    const ncnn::Mat& input,
    ncnn::Mat& output,
    float strength,
    TelemetryData& telemetry,
    const std::function<void(int, int)>& stageProgressCallback
) {
    auto startTime = std::chrono::high_resolution_clock::now();

    LOGI("Начало обработки Zero-DCE++: %dx%dx%d, strength=%.2f", input.w, input.h, input.c, strength);

    const int maxSide = 2048;
    const bool needResize = input.w > maxSide || input.h > maxSide;

    telemetry.tileTelemetry.tileUsed = false;
    telemetry.tileTelemetry.tileSize = 0;
    telemetry.tileTelemetry.overlap = 0;
    telemetry.tileTelemetry.totalTiles = 1;
    telemetry.tileTelemetry.processedTiles = 0;

    ncnn::Mat processingInput = input;
    if (needResize) {
        const int longestSide = std::max(input.w, input.h);
        const float scale = static_cast<float>(maxSide) / static_cast<float>(longestSide);
        const int targetW = std::max(1, static_cast<int>(input.w * scale + 0.5f));
        const int targetH = std::max(1, static_cast<int>(input.h * scale + 0.5f));

        LOGI("Zero-DCE++ downscale: %dx%d -> %dx%d", input.w, input.h, targetW, targetH);
        ncnn::Mat resized;
        ncnn::resize_bilinear(input, resized, targetW, targetH);
        processingInput = resized;
    }

    telemetry.extractorError = TelemetryData::ExtractorErrorTelemetry{};
    int extractorErrorCode = 0;
    ncnn::Mat processedOutput;
    const bool success = processDirectly(processingInput, processedOutput, strength, &extractorErrorCode);

    if (success) {
        if (needResize) {
            ncnn::resize_bilinear(processedOutput, output, input.w, input.h);
            LOGI("Zero-DCE++ upscale: %dx%d -> %dx%d", processedOutput.w, processedOutput.h, input.w, input.h);
        } else {
            output = processedOutput;
        }
        telemetry.tileTelemetry.processedTiles = 1;
        if (stageProgressCallback) {
            stageProgressCallback(1, 1);
        }
    }
    telemetry.seamMaxDelta = 0.0f;
    telemetry.seamMeanDelta = 0.0f;

    auto endTime = std::chrono::high_resolution_clock::now();
    telemetry.timingMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
    telemetry.gpuAllocRetryCount = 0;

    LOGI(
        "duration_ms_zerodce=%ld tile_used=%d tile_size=%d overlap=%d tiles=%d seam_max_delta=%.3f seam_mean_delta=%.3f gpu_alloc_retry_count=%d",
        telemetry.timingMs,
        telemetry.tileTelemetry.tileUsed,
        telemetry.tileTelemetry.tileSize,
        telemetry.tileTelemetry.overlap,
        telemetry.tileTelemetry.totalTiles,
        telemetry.seamMaxDelta,
        telemetry.seamMeanDelta,
        telemetry.gpuAllocRetryCount
    );

    if (!success) {
        if (extractorErrorCode != 0) {
            telemetry.extractorError.hasError = true;
            telemetry.extractorError.ret = extractorErrorCode;
            telemetry.extractorError.durationMs = telemetry.timingMs;
            LOGE(
                "ENHANCE/ERROR: Zero-DCE++ extractor_failed ret=%d duration_ms=%ld delegate=%s size=%dx%dx%d",
                extractorErrorCode,
                telemetry.extractorError.durationMs,
                "cpu",
                input.w,
                input.h,
                input.c
            );
        } else {
            LOGW("ENHANCE/ERROR: Обработка Zero-DCE++ завершилась с ошибкой");
        }
    }

    return success && !cancelFlag_.load();
}


}
