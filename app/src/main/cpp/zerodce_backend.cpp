#include "zerodce_backend.h"
#include "tile_processor.h"
#include "ncnn_engine.h"
#include <ncnn/mat.h>
#include <ncnn/net.h>
#include <android/log.h>
#include <chrono>

#define LOG_TAG "ZeroDceBackend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace kotopogoda {

ZeroDceBackend::ZeroDceBackend(ncnn::Net* net, std::atomic<bool>& cancelFlag, bool usingVulkan)
    : net_(net), cancelFlag_(cancelFlag), usingVulkan_(usingVulkan) {
    TileConfig config;
    config.tileSize = 384;
    config.overlap = 64;
    config.useReflectPadding = true;
    config.enableHannWindow = true;
    tileProcessor_ = std::make_unique<TileProcessor>(config, cancelFlag_);
}

ZeroDceBackend::~ZeroDceBackend() {
}

bool ZeroDceBackend::processDirectly(
    const ncnn::Mat& input,
    ncnn::Mat& output,
    float strength,
    bool* delegateFailed,
    FallbackCause* fallbackCause,
    int* lastErrorCode
) {
    if (cancelFlag_.load()) {
        LOGW("ENHANCE/ERROR: Обработка Zero-DCE++ отменена до старта");
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
            "ENHANCE/ERROR: layer=zerodce_input delegate=%s size=%dx%dx%d ret=%d",
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
        } else {
            LOGW("ENHANCE/ERROR: Не удалось подать вход в Zero-DCE++ (ret=%d)", ret);
        }
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
        if (usingVulkan_ && delegateFailed) {
            *delegateFailed = true;
            if (fallbackCause) {
                *fallbackCause = FallbackCause::EXTRACT_FAILED;
            }
        } else {
            LOGW("ENHANCE/ERROR: Ошибка извлечения выхода Zero-DCE++ (код=%d)", ret);
        }
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
    bool* delegateFailed,
    FallbackCause* fallbackCause
) {
    auto startTime = std::chrono::high_resolution_clock::now();

    LOGI("Начало обработки Zero-DCE++: %dx%dx%d, strength=%.2f", input.w, input.h, input.c, strength);

    const int64_t pixelCount = static_cast<int64_t>(input.w) * static_cast<int64_t>(input.h);
    const int64_t tileAreaThreshold = static_cast<int64_t>(2048) * 2048;
    const int64_t megaPixelThreshold = 12LL * 1000LL * 1000LL;
    const bool shouldTile = tileProcessor_ && (pixelCount >= tileAreaThreshold || pixelCount >= megaPixelThreshold);

    telemetry.tileTelemetry.tileUsed = shouldTile;
    telemetry.tileTelemetry.tileSize = tileProcessor_ ? tileProcessor_->config().tileSize : 0;
    telemetry.tileTelemetry.overlap = tileProcessor_ ? tileProcessor_->config().overlap : 0;

    LOGI(
        "Zero-DCE++ стратегия: delegate=%s tile_used=%d tile_size=%d overlap=%d pixels=%lld threshold_area=%lld threshold_mp=%lld",
        usingVulkan_ ? "vulkan" : "cpu",
        shouldTile,
        telemetry.tileTelemetry.tileSize,
        telemetry.tileTelemetry.overlap,
        (long long)pixelCount,
        (long long)tileAreaThreshold,
        (long long)megaPixelThreshold
    );

    bool success = false;
    int gpuAllocRetryCount = 0;
    int extractorErrorCode = 0;
    telemetry.extractorError = TelemetryData::ExtractorErrorTelemetry{};

    if (shouldTile) {
        auto processFunc = [
            this,
            strength,
            &gpuAllocRetryCount,
            delegateFailed,
            fallbackCause
        ](
            const ncnn::Mat& tileIn,
            ncnn::Mat& tileOut,
            ncnn::Net* net,
            int* errorCode
        ) -> bool {
            const int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; ++attempt) {
                if (cancelFlag_.load()) {
                    LOGW("ENHANCE/ERROR: Обработка Zero-DCE++ отменена внутри тайла");
                    return false;
                }

                ncnn::Extractor ex = net->create_extractor();
                int ret = ex.input("input", tileIn);
                if (ret != 0) {
                    if (errorCode) {
                        *errorCode = ret;
                    }
                    if (usingVulkan_ && delegateFailed) {
                        *delegateFailed = true;
                        if (fallbackCause) {
                            *fallbackCause = FallbackCause::EXTRACT_FAILED;
                        }
                        LOGW("delegate=vulkan cause=extract_failed stage=zerodce_tile_input ret=%d", ret);
                    } else {
                        LOGW("ENHANCE/ERROR: Не удалось подать данные тайла в Zero-DCE++ (ret=%d)", ret);
                    }
                    return false;
                }

                ncnn::Mat enhancedTile;
                ret = ex.extract("output", enhancedTile);
                if (ret == 0) {
                    tileOut.create(tileIn.w, tileIn.h, tileIn.c);
                    for (int c = 0; c < tileIn.c; ++c) {
                        const float* srcChannel = tileIn.channel(c);
                        const float* enhChannel = enhancedTile.channel(c);
                        float* dstChannel = tileOut.channel(c);
                        int pixelTotal = tileIn.w * tileIn.h;
                        for (int i = 0; i < pixelTotal; ++i) {
                            dstChannel[i] = srcChannel[i] * (1.0f - strength) + enhChannel[i] * strength;
                        }
                    }
                    return true;
                }

                if (errorCode) {
                    *errorCode = ret;
                }
                gpuAllocRetryCount++;
                LOGW(
                    "ENHANCE/ERROR: Ошибка Zero-DCE++ (ret=%d) на тайле, попытка %d/%d",
                    ret,
                    attempt,
                    maxAttempts
                );
                if (usingVulkan_ && delegateFailed) {
                    *delegateFailed = true;
                    if (fallbackCause) {
                        *fallbackCause = FallbackCause::EXTRACT_FAILED;
                    }
                }
            }
            return false;
        };

        auto progressCallback = [&telemetry](int current, int total) {
            telemetry.tileTelemetry.processedTiles = current;
            telemetry.tileTelemetry.totalTiles = total;
            LOGI("Zero-DCE++ прогресс тайлов: %d/%d", current, total);
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
        telemetry.seamMaxDelta = stats.seamMaxDelta;
        telemetry.seamMeanDelta = stats.seamMeanDelta;
        telemetry.tileTelemetry.totalTiles = stats.tileCount;
        telemetry.tileTelemetry.tileSize = stats.tileSize;
        telemetry.tileTelemetry.overlap = stats.overlap;
        if (success) {
            telemetry.tileTelemetry.processedTiles = stats.tileCount;
        }
    } else {
        telemetry.tileTelemetry.tileUsed = false;
        telemetry.tileTelemetry.totalTiles = 0;
        telemetry.tileTelemetry.processedTiles = 0;
        success = processDirectly(input, output, strength, delegateFailed, fallbackCause, &extractorErrorCode);
        telemetry.seamMaxDelta = 0.0f;
        telemetry.seamMeanDelta = 0.0f;
    }

    auto endTime = std::chrono::high_resolution_clock::now();
    telemetry.timingMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
    telemetry.gpuAllocRetryCount = gpuAllocRetryCount;

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
                usingVulkan_ ? "vulkan" : "cpu",
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
