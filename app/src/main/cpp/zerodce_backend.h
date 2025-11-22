#ifndef ZERODCE_BACKEND_H
#define ZERODCE_BACKEND_H

#include <atomic>
#include <memory>
#include <functional>

namespace ncnn {
    class Mat;
    class Net;
}

namespace kotopogoda {

class TileProcessor;
struct TelemetryData;

class ZeroDceBackend {
public:
    ZeroDceBackend(ncnn::Net* net, std::atomic<bool>& cancelFlag);
    ~ZeroDceBackend();

    bool process(
        const ncnn::Mat& input,
        ncnn::Mat& output,
        float strength,
        TelemetryData& telemetry,
        const std::function<void(int, int)>& stageProgressCallback = std::function<void(int, int)>()
    );

private:
    bool processDirectly(
        const ncnn::Mat& input,
        ncnn::Mat& output,
        float strength,
        int* lastErrorCode = nullptr
    );

    ncnn::Net* net_;
    std::atomic<bool>& cancelFlag_;
    std::unique_ptr<TileProcessor> tileProcessor_;
};

}

#endif
