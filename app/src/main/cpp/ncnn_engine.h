#ifndef NCNN_ENGINE_H
#define NCNN_ENGINE_H

#include <memory>
#include <string>
#include <atomic>
#include <mutex>
#include <jni.h>
#include <android/asset_manager.h>
#include <android/bitmap.h>

namespace ncnn {
    class Net;
    class Mat;
    class VulkanDevice;
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
    FallbackCause fallbackCause = FallbackCause::NONE;
};

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
        TelemetryData& telemetry
    );

    bool runFull(
        JNIEnv* env,
        jobject sourceBitmap,
        float strength,
        jobject outputBitmap,
        TelemetryData& telemetry
    );

    void cancel();
    void release();

    bool isInitialized() const { return initialized_; }
    bool hasVulkan() const { return vulkanAvailable_; }
    bool isGpuDelegateAvailable() const { return gpuDelegateAvailable_; }

    static IntegrityFailure consumeLastIntegrityFailure();

private:
    bool loadModels(AAssetManager* assetManager, const std::string& modelsDir);
    bool loadModelsForDelegate(const std::string& modelsDir, bool useVulkan);
    bool switchToCpuFallback();
    bool verifyChecksum(const std::string& filePath, const std::string& expectedChecksum);
    static void reportIntegrityFailure(
        const std::string& filePath,
        const std::string& expectedChecksum,
        const std::string& actualChecksum
    );
    void setupVulkan(int gpuCount);
    void cleanupVulkan();

    std::unique_ptr<ncnn::Net> zeroDceNet_;
    std::unique_ptr<ncnn::Net> restormerNet_;
    ncnn::VulkanDevice* vulkanDevice_;

    ModelChecksums zeroDceChecksums_;
    ModelChecksums restormerChecksums_;
    PreviewProfile previewProfile_;
    std::string modelsDir_;
    AAssetManager* assetManager_;

    std::atomic<bool> initialized_;
    std::atomic<bool> cancelled_;
    std::atomic<bool> vulkanAvailable_;
    std::atomic<bool> gpuDelegateAvailable_;
    std::atomic<bool> forceCpuMode_;

    static std::mutex integrityMutex_;
    static IntegrityFailure lastIntegrityFailure_;
};

}

#endif
