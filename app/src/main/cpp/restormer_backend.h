#ifndef RESTORMER_BACKEND_H
#define RESTORMER_BACKEND_H

#include <memory>
#include <atomic>

namespace ncnn {
    class Mat;
    class Net;
}

namespace kotopogoda {

class TileProcessor;
struct TelemetryData;

class RestormerBackend {
public:
    RestormerBackend(ncnn::Net* net, std::atomic<bool>& cancelFlag);
    ~RestormerBackend();

    bool process(
        const ncnn::Mat& input,
        ncnn::Mat& output,
        TelemetryData& telemetry
    );

private:
    bool processDirectly(
        const ncnn::Mat& input,
        ncnn::Mat& output,
        int* lastErrorCode = nullptr
    );

    ncnn::Net* net_;
    std::atomic<bool>& cancelFlag_;
    std::unique_ptr<TileProcessor> tileProcessor_;
};

}

#endif
