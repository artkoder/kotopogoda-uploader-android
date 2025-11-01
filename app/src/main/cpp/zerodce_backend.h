#ifndef ZERODCE_BACKEND_H
#define ZERODCE_BACKEND_H

#include <atomic>

namespace ncnn {
    class Mat;
    class Net;
}

namespace kotopogoda {

struct TelemetryData;

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
    ncnn::Net* net_;
    std::atomic<bool>& cancelFlag_;
};

}

#endif
