#ifndef ZERODCE_BACKEND_H
#define ZERODCE_BACKEND_H

#include <atomic>
#include <memory>

namespace ncnn {
    class Mat;
    class Net;
}

namespace kotopogoda {

class TileProcessor;
struct TelemetryData;
enum class FallbackCause;

class ZeroDceBackend {
public:
    ZeroDceBackend(ncnn::Net* net, std::atomic<bool>& cancelFlag);
    ~ZeroDceBackend();

    bool process(
        const ncnn::Mat& input,
        ncnn::Mat& output,
        float strength,
        TelemetryData& telemetry
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
