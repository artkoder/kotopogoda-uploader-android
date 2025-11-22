#ifndef NCNN_ENGINE_H
#define NCNN_ENGINE_H

#include <memory>
#include <string>
#include <atomic>
#include <mutex>
#include <functional>
#include <jni.h>
#include <android/asset_manager.h>
#include <android/bitmap.h>

namespace ncnn {
    class Net;
    class Mat;
}

namespace kotopogoda {

enum class PreviewProfile {
    BALANCED = 0,
    QUALITY = 1
};

enum class DelegateType {
    CPU = 0,
    VULKAN = 1,
};

enum class FallbackCause {
    NONE = 0,
    LOAD_FAILED = 1,
    EXTRACT_FAILED = 2,
};

struct TelemetryData {
    struct TileTelemetry {
        bool tileUsed = false;
        int tileSize = 0;
        int overlap = 0;
        int totalTiles = 0;
        int processedTiles = 0;
    } tileTelemetry;

    struct ExtractorErrorTelemetry {
        bool hasError = false;
        int ret = 0;
        long durationMs = 0;
    } extractorError;

    long timingMs = 0;
    bool usedVulkan = false;
    long peakMemoryKb = 0;
    bool cancelled = false;
    float seamMaxDelta = 0.0f;
    float seamMeanDelta = 0.0f;
    int gpuAllocRetryCount = 0;
    bool fallbackUsed = false;
    long durationMsVulkan = 0;
    long durationMsCpu = 0;
    DelegateType delegate = DelegateType::CPU;
    std::string restPrecision = "fp16";
    FallbackCause fallbackCause = FallbackCause::NONE;
};

using TileProgressCallback = std::function<void(const char*, int, int)>;

class NcnnEngine {
public:
    struct ModelChecksums {
        std::string param;
        std::string bin;
    };

    struct IntegrityFailure {
        bool hasFailure = false;
        std::string filePath;
        std::string expectedChecksum;
        std::string actualChecksum;
    };

    NcnnEngine();
    ~NcnnEngine();

    bool initialize(
        AAssetManager* assetManager,
        const std::string& modelsDir,
        const ModelChecksums& zeroDceChecksums,
        const ModelChecksums& restormerChecksums,
        PreviewProfile profile,
        bool forceCpu
    );

    bool runPreview(
        JNIEnv* env,
        jobject sourceBitmap,
        float strength,
        TelemetryData& telemetry,
        const TileProgressCallback& progressCallback = TileProgressCallback()
    );

    bool runFull(
        JNIEnv* env,
        jobject sourceBitmap,
        float strength,
        jobject outputBitmap,
        TelemetryData& telemetry,
        const TileProgressCallback& progressCallback = TileProgressCallback()
    );

    void cancel();
    void release();

    bool isInitialized() const { return initialized_; }

    static IntegrityFailure consumeLastIntegrityFailure();

private:
    bool loadModels(AAssetManager* assetManager, const std::string& modelsDir);
    bool verifyChecksum(const std::string& filePath, const std::string& expectedChecksum);
    static void reportIntegrityFailure(
        const std::string& filePath,
        const std::string& expectedChecksum,
        const std::string& actualChecksum
    );

    std::unique_ptr<ncnn::Net> zeroDceNet_;
    std::unique_ptr<ncnn::Net> restormerNet_;

    ModelChecksums zeroDceChecksums_;
    ModelChecksums restormerChecksums_;
    PreviewProfile previewProfile_;
    std::string modelsDir_;
    AAssetManager* assetManager_;

    std::atomic<bool> initialized_;
    std::atomic<bool> cancelled_;
    std::atomic<bool> forceCpuMode_;
    std::atomic<DelegateType> currentDelegate_;
    std::string restPrecision_;

    static std::mutex integrityMutex_;
    static IntegrityFailure lastIntegrityFailure_;
};

}

#endif
