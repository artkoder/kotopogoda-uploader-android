#ifndef HANN_WINDOW_H
#define HANN_WINDOW_H

#include <vector>

namespace kotopogoda {

class HannWindow {
public:
    static void create1D(int size, int overlap, std::vector<float>& window);
    static void create2D(int width, int height, int overlap, std::vector<float>& window);
};

}

#endif
