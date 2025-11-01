#include "tile_processor.h"
#include "hann_window.h"
#include <ncnn/mat.h>
#include <ncnn/net.h>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "TileProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace kotopogoda {

TileProcessor::TileProcessor(const TileConfig& config, std::atomic<bool>& cancelFlag)
    : config_(config), cancelFlag_(cancelFlag) {
    createHannWindow(config_.tileSize, config_.tileSize, config_.overlap);
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
            
            tile.paddedX = std::max(0, x - overlap);
            tile.paddedY = std::max(0, y - overlap);
            tile.paddedWidth = std::min(tileSize, width - tile.paddedX);
            tile.paddedHeight = std::min(tileSize, height - tile.paddedY);
            
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
                
                if (srcX < input.w && srcY < input.h) {
                    dstChannel[y * tile.paddedWidth + x] = srcChannel[srcY * input.w + srcX];
                } else {
                    dstChannel[y * tile.paddedWidth + x] = 0.0f;
                }
            }
        }
    }
}

void TileProcessor::blendTile(ncnn::Mat& output, const ncnn::Mat& tileData, const TileInfo& tile) {
    int channels = output.c;
    int overlap = config_.overlap;
    
    for (int c = 0; c < channels; ++c) {
        float* dstChannel = output.channel(c);
        const float* srcChannel = tileData.channel(c);
        
        for (int y = 0; y < tile.paddedHeight; ++y) {
            for (int x = 0; x < tile.paddedWidth; ++x) {
                int dstX = tile.paddedX + x;
                int dstY = tile.paddedY + y;
                
                if (dstX >= output.w || dstY >= output.h) {
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
            }
        }
    }
}

bool TileProcessor::processTiled(
    const ncnn::Mat& input,
    ncnn::Mat& output,
    ncnn::Net* net,
    std::function<bool(const ncnn::Mat&, ncnn::Mat&, ncnn::Net*)> processFunc
) {
    if (cancelFlag_.load()) {
        LOGW("Обработка отменена перед началом");
        return false;
    }
    
    std::vector<TileInfo> tiles;
    computeTileGrid(input.w, input.h, tiles);
    
    if (tiles.size() == 1) {
        LOGI("Изображение помещается в один тайл, обрабатываем напрямую");
        return processFunc(input, output, net);
    }
    
    output.create(input.w, input.h, input.c);
    output.fill(0.0f);
    
    int processed = 0;
    for (const auto& tile : tiles) {
        if (cancelFlag_.load()) {
            LOGW("Обработка отменена на тайле %d из %zu", processed, tiles.size());
            return false;
        }
        
        ncnn::Mat tileInput, tileOutput;
        extractTile(input, tile, tileInput);
        
        if (!processFunc(tileInput, tileOutput, net)) {
            LOGW("Ошибка обработки тайла %d", processed);
            return false;
        }
        
        blendTile(output, tileOutput, tile);
        
        processed++;
        if (processed % 10 == 0) {
            LOGI("Обработано тайлов: %d / %zu", processed, tiles.size());
        }
    }
    
    LOGI("Все %zu тайлов обработаны успешно", tiles.size());
    return true;
}

}
