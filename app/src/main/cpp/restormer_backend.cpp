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
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace kotopogoda {

RestormerBackend::RestormerBackend(ncnn::Net* net, std::atomic<bool>& cancelFlag, bool usingVulkan)
    : net_(net), cancelFlag_(cancelFlag), usingVulkan_(usingVulkan) {
    
    TileConfig config;
    config.tileSize = 384;
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
    FallbackCause* fallbackCause,
    int* lastErrorCode
) {
    if (cancelFlag_.load()) {
        return false;
    }

    if (lastErrorCode) {
        *lastErrorCode = 0;
    }

    const char* delegateName = usingVulkan_ ? "vulkan" : "cpu";
    ncnn::Extractor ex = net_->create_extractor();
    int ret = ex.input("input", input);
    if (ret != 0) {
        if (lastErrorCode) {
            *lastErrorCode = ret;
        }
        LOGE(
            "ENHANCE/ERROR: layer=restormer_input delegate=%s size=%dx%dx%d ret=%d",
            delegateName,
            input.w,
            input.h,
            input.c,
            ret
        );
        if (usingVulkan_ && delegateFailed) {
            *delegateFailed = true;
            if (fallbackCause) {
                *fallbackCause = FallbackCause::EXTRACT_FAILED;
            }
        }
        return false;
    }

    ret = ex.extract("output", output);
    if (ret != 0) {
        if (lastErrorCode) {
            *lastErrorCode = ret;
        }
        LOGE(
            "ENHANCE/ERROR: layer=restormer_output delegate=%s size=%dx%dx%d ret=%d",
            delegateName,
            input.w,
            input.h,
            input.c,
            ret
        );
        if (usingVulkan_ && delegateFailed) {
            *delegateFailed = true;
            if (fallbackCause) {
                *fallbackCause = FallbackCause::EXTRACT_FAILED;
            }
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

    const auto& tileConfig = tileProcessor_->config();
    LOGI("Restormer tile_config: delegate=%s tile_size=%d overlap=%d",
         usingVulkan_ ? "vulkan" : "cpu",
         tileConfig.tileSize,
         tileConfig.overlap);

    telemetry.tileTelemetry = TelemetryData::TileTelemetry{};
    telemetry.tileTelemetry.tileSize = tileConfig.tileSize;
    telemetry.tileTelemetry.overlap = tileConfig.overlap;

    bool needsTiling = input.w > tileConfig.tileSize || input.h > tileConfig.tileSize;
    bool success = false;
    int extractorErrorCode = 0;
    telemetry.extractorError = TelemetryData::ExtractorErrorTelemetry{};

    if (needsTiling) {
        telemetry.tileTelemetry.tileUsed = true;
        LOGI("Используется тайловая обработка");

        auto processFunc = [this, delegateFailed, fallbackCause](
            const ncnn::Mat& tileIn,
            ncnn::Mat& tileOut,
            ncnn::Net* net,
            int* errorCode
        ) -> bool {
            (void)net;
            return this->processDirectly(tileIn, tileOut, delegateFailed, fallbackCause, errorCode);
        };

        auto progressCallback = [&telemetry](int current, int total) {
            telemetry.tileTelemetry.processedTiles = current;
            telemetry.tileTelemetry.totalTiles = total;
        };

        TileProcessStats stats;
        success = tileProcessor_->processTiled(
            input,
            output,
            net_,
            processFunc,
            progressCallback,
            &stats,
            &extractorErrorCode
        );

        telemetry.tileTelemetry.totalTiles = stats.tileCount;
        telemetry.tileTelemetry.tileSize = stats.tileSize;
        telemetry.tileTelemetry.overlap = stats.overlap;
        telemetry.seamMaxDelta = stats.seamMaxDelta;
        telemetry.seamMeanDelta = stats.seamMeanDelta;
        if (success) {
            telemetry.tileTelemetry.processedTiles = stats.tileCount;
        }

        LOGI(
            "Restormer tiles: tile_size=%d overlap=%d tiles_total=%d tiles_completed=%d seam_max_delta=%.3f seam_mean_delta=%.3f",
            telemetry.tileTelemetry.tileSize,
            telemetry.tileTelemetry.overlap,
            telemetry.tileTelemetry.totalTiles,
            telemetry.tileTelemetry.processedTiles,
            telemetry.seamMaxDelta,
            telemetry.seamMeanDelta
        );
    } else {
        LOGI("Обработка без тайлинга");
        success = processDirectly(input, output, delegateFailed, fallbackCause, &extractorErrorCode);
        telemetry.tileTelemetry.tileUsed = false;
        telemetry.tileTelemetry.totalTiles = 1;
        telemetry.tileTelemetry.processedTiles = success ? 1 : 0;
        telemetry.seamMaxDelta = 0.0f;
        telemetry.seamMeanDelta = 0.0f;
    }

    auto endTime = std::chrono::high_resolution_clock::now();
    telemetry.timingMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();

    LOGI("Обработка Restormer завершена за %ld мс, успех=%d", telemetry.timingMs, success);

    if (!success && extractorErrorCode != 0) {
        telemetry.extractorError.hasError = true;
        telemetry.extractorError.ret = extractorErrorCode;
        telemetry.extractorError.durationMs = telemetry.timingMs;
        LOGE(
            "ENHANCE/ERROR: Restormer extractor_failed ret=%d duration_ms=%ld delegate=%s size=%dx%dx%d",
            extractorErrorCode,
            telemetry.extractorError.durationMs,
            usingVulkan_ ? "vulkan" : "cpu",
            input.w,
            input.h,
            input.c
        );
    }

    return success;
}

}
