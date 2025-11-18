#ifndef TILE_PROCESSOR_H
#define TILE_PROCESSOR_H

#include <vector>
#include <atomic>
#include <functional>

namespace ncnn {
    class Mat;
    class Net;
}

namespace kotopogoda {

struct TileConfig {
    int tileSize = 512;
    int overlap = 16;
    int maxMemoryMb = 512;
    int threadCount = 4;
    bool useReflectPadding = false;
    bool enableHannWindow = true;
};

struct TileInfo {
    int x;
    int y;
    int width;
    int height;
    int paddedX;
    int paddedY;
    int paddedWidth;
    int paddedHeight;
};

struct TileProcessStats {
    int tileCount = 0;
    int tileSize = 0;
    int overlap = 0;
    float seamMaxDelta = 0.0f;
};

class TileProcessor {
public:
    TileProcessor(const TileConfig& config, std::atomic<bool>& cancelFlag);
    ~TileProcessor();

    const TileConfig& config() const { return config_; }

    bool processTiled(
        const ncnn::Mat& input,
        ncnn::Mat& output,
        ncnn::Net* net,
        std::function<bool(const ncnn::Mat&, ncnn::Mat&, ncnn::Net*, int*)> processFunc,
        std::function<void(int, int)> progressCallback = nullptr,
        TileProcessStats* stats = nullptr,
        int* errorCode = nullptr
    );

private:
    void computeTileGrid(int width, int height, std::vector<TileInfo>& tiles);
    void extractTile(const ncnn::Mat& input, const TileInfo& tile, ncnn::Mat& tileData);
    void blendTile(
        ncnn::Mat& output,
        const ncnn::Mat& tileData,
        const TileInfo& tile,
        float& seamMaxDelta
    );
    void createHannWindow(int width, int height, int overlap);
    int reflectCoordinate(int coordinate, int limit) const;

    TileConfig config_;
    std::atomic<bool>& cancelFlag_;
    std::vector<float> hannWindowHorz_;
    std::vector<float> hannWindowVert_;
};

}

#endif
