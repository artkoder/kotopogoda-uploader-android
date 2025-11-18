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

struct TelemetryData {
    long timingMs = 0;
    bool usedVulkan = false;
    long peakMemoryKb = 0;
    bool cancelled = false;
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
        PreviewProfile profile
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

    static IntegrityFailure consumeLastIntegrityFailure();

private:
    bool loadModels(AAssetManager* assetManager, const std::string& modelsDir);
    bool verifyChecksum(const std::string& filePath, const std::string& expectedChecksum);
    static void reportIntegrityFailure(
        const std::string& filePath,
        const std::string& expectedChecksum,
        const std::string& actualChecksum
    );
    void setupVulkan();
    void cleanupVulkan();

    std::unique_ptr<ncnn::Net> zeroDceNet_;
    std::unique_ptr<ncnn::Net> restormerNet_;
    ncnn::VulkanDevice* vulkanDevice_;

    ModelChecksums zeroDceChecksums_;
    ModelChecksums restormerChecksums_;
    PreviewProfile previewProfile_;

    std::atomic<bool> initialized_;
    std::atomic<bool> cancelled_;
    std::atomic<bool> vulkanAvailable_;

    static std::mutex integrityMutex_;
    static IntegrityFailure lastIntegrityFailure_;
};

}

#endif
