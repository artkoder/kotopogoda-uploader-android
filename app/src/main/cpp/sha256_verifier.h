#ifndef SHA256_VERIFIER_H
#define SHA256_VERIFIER_H

#include <string>

namespace kotopogoda {

class Sha256Verifier {
public:
    static std::string computeSha256(const std::string& filePath);
    static bool verify(const std::string& filePath, const std::string& expectedChecksum);
};

}

#endif
