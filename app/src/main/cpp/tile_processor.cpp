#include "tile_processor.h"
#include "hann_window.h"
#include <ncnn/mat.h>
#include <ncnn/net.h>
#include <algorithm>
#include <cmath>
#include <android/log.h>

#define LOG_TAG "TileProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace kotopogoda {

TileProcessor::TileProcessor(const TileConfig& config, std::atomic<bool>& cancelFlag)
    : config_(config), cancelFlag_(cancelFlag) {
    if (config_.enableHannWindow) {
        createHannWindow(config_.tileSize, config_.tileSize, config_.overlap);
    } else {
        hannWindowHorz_.assign(config_.tileSize, 1.0f);
        hannWindowVert_.assign(config_.tileSize, 1.0f);
    }
}

TileProcessor::~TileProcessor() {
}

void TileProcessor::createHannWindow(int width, int height, int overlap) {
    HannWindow::create1D(width, overlap, hannWindowHorz_);
    HannWindow::create1D(height, overlap, hannWindowVert_);
}

void TileProcessor::computeTileGrid(int width, int height, std::vector<TileInfo>& tiles) {
    tiles.clear();

    int tileSize = config_.tileSize;
    int overlap = config_.overlap;
    int step = tileSize - 2 * overlap;

    for (int y = 0; y < height; y += step) {
        for (int x = 0; x < width; x += step) {
            TileInfo tile;

            tile.x = x;
            tile.y = y;
            tile.width = std::min(tileSize, width - x);
            tile.height = std::min(tileSize, height - y);

            if (config_.useReflectPadding) {
                tile.paddedX = x - overlap;
                tile.paddedY = y - overlap;
                tile.paddedWidth = tileSize;
                tile.paddedHeight = tileSize;
            } else {
                tile.paddedX = std::max(0, x - overlap);
                tile.paddedY = std::max(0, y - overlap);
                tile.paddedWidth = std::min(tileSize, std::max(0, width - tile.paddedX));
                tile.paddedHeight = std::min(tileSize, std::max(0, height - tile.paddedY));
            }

            tiles.push_back(tile);
        }
    }
    
    LOGI("Создана сетка из %zu тайлов для изображения %dx%d", tiles.size(), width, height);
}

void TileProcessor::extractTile(const ncnn::Mat& input, const TileInfo& tile, ncnn::Mat& tileData) {
    int channels = input.c;
    tileData.create(tile.paddedWidth, tile.paddedHeight, channels);

    for (int c = 0; c < channels; ++c) {
        const float* srcChannel = input.channel(c);
        float* dstChannel = tileData.channel(c);

        for (int y = 0; y < tile.paddedHeight; ++y) {
            for (int x = 0; x < tile.paddedWidth; ++x) {
                int srcX = tile.paddedX + x;
                int srcY = tile.paddedY + y;
                if (config_.useReflectPadding) {
                    srcX = reflectCoordinate(srcX, input.w);
                    srcY = reflectCoordinate(srcY, input.h);
                    dstChannel[y * tile.paddedWidth + x] = srcChannel[srcY * input.w + srcX];
                } else if (srcX >= 0 && srcX < input.w && srcY >= 0 && srcY < input.h) {
                    dstChannel[y * tile.paddedWidth + x] = srcChannel[srcY * input.w + srcX];
                } else {
                    dstChannel[y * tile.paddedWidth + x] = 0.0f;
                }
            }
        }
    }
}

void TileProcessor::blendTile(
    ncnn::Mat& output,
    const ncnn::Mat& tileData,
    const TileInfo& tile,
    float& seamMaxDelta
) {
    int channels = output.c;
    int overlap = config_.overlap;

    for (int c = 0; c < channels; ++c) {
        float* dstChannel = output.channel(c);
        const float* srcChannel = tileData.channel(c);
        
        for (int y = 0; y < tile.paddedHeight; ++y) {
            for (int x = 0; x < tile.paddedWidth; ++x) {
                int dstX = tile.paddedX + x;
                int dstY = tile.paddedY + y;
                
                if (dstX < 0 || dstY < 0 || dstX >= output.w || dstY >= output.h) {
                    continue;
                }

                float weight = 1.0f;
                
                int distLeft = x;
                int distRight = tile.paddedWidth - 1 - x;
                int distTop = y;
                int distBottom = tile.paddedHeight - 1 - y;
                
                if (distLeft < overlap) {
                    weight *= hannWindowHorz_[distLeft];
                } else if (distRight < overlap) {
                    weight *= hannWindowHorz_[tile.paddedWidth - 1 - distRight];
                }
                
                if (distTop < overlap) {
                    weight *= hannWindowVert_[distTop];
                } else if (distBottom < overlap) {
                    weight *= hannWindowVert_[tile.paddedHeight - 1 - distBottom];
                }

                float srcValue = srcChannel[y * tile.paddedWidth + x];
                float dstValue = dstChannel[dstY * output.w + dstX];
                dstChannel[dstY * output.w + dstX] = dstValue + srcValue * weight;

                if (weight < 0.999f) {
                    seamMaxDelta = std::max(seamMaxDelta, std::fabs(srcValue - dstValue));
                }
            }
        }
    }
}

bool TileProcessor::processTiled(
    const ncnn::Mat& input,
    ncnn::Mat& output,
    ncnn::Net* net,
    std::function<bool(const ncnn::Mat&, ncnn::Mat&, ncnn::Net*, int*)> processFunc,
    std::function<void(int, int)> progressCallback,
    TileProcessStats* stats,
    int* errorCode
) {
    if (cancelFlag_.load()) {
        LOGW("ENHANCE/ERROR: Обработка отменена перед началом");
        return false;
    }

    if (errorCode) {
        *errorCode = 0;
    }

    std::vector<TileInfo> tiles;
    computeTileGrid(input.w, input.h, tiles);
    
    if (tiles.size() == 1) {
        LOGI("Изображение помещается в один тайл, обрабатываем напрямую");
        if (stats) {
            stats->tileCount = 1;
            stats->tileSize = config_.tileSize;
            stats->overlap = config_.overlap;
            stats->seamMaxDelta = 0.0f;
        }
        return processFunc(input, output, net, errorCode);
    }
    
    output.create(input.w, input.h, input.c);
    output.fill(0.0f);

    if (stats) {
        stats->tileCount = static_cast<int>(tiles.size());
        stats->tileSize = config_.tileSize;
        stats->overlap = config_.overlap;
        stats->seamMaxDelta = 0.0f;
    }

    int processed = 0;
    float seamMaxDelta = 0.0f;
    for (const auto& tile : tiles) {
        if (cancelFlag_.load()) {
            LOGW("ENHANCE/ERROR: Обработка отменена на тайле %d из %zu", processed, tiles.size());
            return false;
        }

        ncnn::Mat tileInput, tileOutput;
        extractTile(input, tile, tileInput);

        if (errorCode) {
            *errorCode = 0;
        }

        if (!processFunc(tileInput, tileOutput, net, errorCode)) {
            int reportedCode = errorCode ? *errorCode : 0;
            LOGW(
                "ENHANCE/ERROR: Ошибка обработки тайла %d ret=%d",
                processed,
                reportedCode
            );
            return false;
        }

        blendTile(output, tileOutput, tile, seamMaxDelta);

        processed++;
        if (progressCallback) {
            progressCallback(processed, static_cast<int>(tiles.size()));
        }
        if (processed % 10 == 0) {
            LOGI("Обработано тайлов: %d / %zu", processed, tiles.size());
        }
    }

    if (stats) {
        stats->seamMaxDelta = seamMaxDelta;
    }

    LOGI("Все %zu тайлов обработаны успешно", tiles.size());
    return true;
}

int TileProcessor::reflectCoordinate(int coordinate, int limit) const {
    if (limit <= 1) {
        return 0;
    }
    int period = 2 * (limit - 1);
    int mod = coordinate % period;
    if (mod < 0) {
        mod += period;
    }
    if (mod >= limit) {
        mod = period - mod;
    }
    if (mod < 0) {
        mod = 0;
    }
    return mod;
}

}
