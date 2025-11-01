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

class TileProcessor {
public:
    TileProcessor(const TileConfig& config, std::atomic<bool>& cancelFlag);
    ~TileProcessor();

    bool processTiled(
        const ncnn::Mat& input,
        ncnn::Mat& output,
        ncnn::Net* net,
        std::function<bool(const ncnn::Mat&, ncnn::Mat&, ncnn::Net*)> processFunc
    );

private:
    void computeTileGrid(int width, int height, std::vector<TileInfo>& tiles);
    void extractTile(const ncnn::Mat& input, const TileInfo& tile, ncnn::Mat& tileData);
    void blendTile(ncnn::Mat& output, const ncnn::Mat& tileData, const TileInfo& tile);
    void createHannWindow(int width, int height, int overlap);

    TileConfig config_;
    std::atomic<bool>& cancelFlag_;
    std::vector<float> hannWindowHorz_;
    std::vector<float> hannWindowVert_;
};

}

#endif
