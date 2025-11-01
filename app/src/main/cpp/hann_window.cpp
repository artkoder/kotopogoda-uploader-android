#include "hann_window.h"
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace kotopogoda {

void HannWindow::create1D(int size, int overlap, std::vector<float>& window) {
    window.resize(size);
    
    for (int i = 0; i < size; ++i) {
        if (i < overlap) {
            window[i] = 0.5f * (1.0f - std::cos(M_PI * i / overlap));
        } else if (i >= size - overlap) {
            int dist = size - 1 - i;
            window[i] = 0.5f * (1.0f - std::cos(M_PI * dist / overlap));
        } else {
            window[i] = 1.0f;
        }
    }
}

void HannWindow::create2D(int width, int height, int overlap, std::vector<float>& window) {
    window.resize(width * height);
    
    std::vector<float> windowH, windowV;
    create1D(width, overlap, windowH);
    create1D(height, overlap, windowV);
    
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            window[y * width + x] = windowH[x] * windowV[y];
        }
    }
}

}
