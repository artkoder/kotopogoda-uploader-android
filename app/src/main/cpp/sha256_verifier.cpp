#include "sha256_verifier.h"
#include <fstream>
#include <iomanip>
#include <sstream>
#include <cstring>

namespace kotopogoda {

static const unsigned int K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
};

static inline unsigned int rotr(unsigned int x, unsigned int n) {
    return (x >> n) | (x << (32 - n));
}

static inline unsigned int ch(unsigned int x, unsigned int y, unsigned int z) {
    return (x & y) ^ (~x & z);
}

static inline unsigned int maj(unsigned int x, unsigned int y, unsigned int z) {
    return (x & y) ^ (x & z) ^ (y & z);
}

static inline unsigned int sigma0(unsigned int x) {
    return rotr(x, 2) ^ rotr(x, 13) ^ rotr(x, 22);
}

static inline unsigned int sigma1(unsigned int x) {
    return rotr(x, 6) ^ rotr(x, 11) ^ rotr(x, 25);
}

static inline unsigned int gamma0(unsigned int x) {
    return rotr(x, 7) ^ rotr(x, 18) ^ (x >> 3);
}

static inline unsigned int gamma1(unsigned int x) {
    return rotr(x, 17) ^ rotr(x, 19) ^ (x >> 10);
}

static void sha256Transform(unsigned int state[8], const unsigned char block[64]) {
    unsigned int w[64];
    unsigned int a, b, c, d, e, f, g, h;
    
    for (int i = 0; i < 16; ++i) {
        w[i] = (block[i * 4] << 24) | (block[i * 4 + 1] << 16) |
               (block[i * 4 + 2] << 8) | block[i * 4 + 3];
    }
    
    for (int i = 16; i < 64; ++i) {
        w[i] = gamma1(w[i - 2]) + w[i - 7] + gamma0(w[i - 15]) + w[i - 16];
    }
    
    a = state[0];
    b = state[1];
    c = state[2];
    d = state[3];
    e = state[4];
    f = state[5];
    g = state[6];
    h = state[7];
    
    for (int i = 0; i < 64; ++i) {
        unsigned int t1 = h + sigma1(e) + ch(e, f, g) + K[i] + w[i];
        unsigned int t2 = sigma0(a) + maj(a, b, c);
        h = g;
        g = f;
        f = e;
        e = d + t1;
        d = c;
        c = b;
        b = a;
        a = t1 + t2;
    }
    
    state[0] += a;
    state[1] += b;
    state[2] += c;
    state[3] += d;
    state[4] += e;
    state[5] += f;
    state[6] += g;
    state[7] += h;
}

std::string Sha256Verifier::computeSha256(const std::string& filePath) {
    std::ifstream file(filePath, std::ios::binary);
    if (!file) {
        return "";
    }
    
    unsigned int state[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };
    
    unsigned char buffer[64];
    unsigned long long totalLength = 0;
    
    while (file.read(reinterpret_cast<char*>(buffer), 64) || file.gcount() > 0) {
        std::streamsize bytesRead = file.gcount();
        totalLength += bytesRead;
        
        if (bytesRead < 64) {
            buffer[bytesRead++] = 0x80;
            
            if (bytesRead > 56) {
                std::memset(buffer + bytesRead, 0, 64 - bytesRead);
                sha256Transform(state, buffer);
                bytesRead = 0;
            }
            
            std::memset(buffer + bytesRead, 0, 56 - bytesRead);
            
            unsigned long long bitLength = totalLength * 8;
            for (int i = 0; i < 8; ++i) {
                buffer[63 - i] = bitLength & 0xff;
                bitLength >>= 8;
            }
            
            sha256Transform(state, buffer);
            break;
        }
        
        sha256Transform(state, buffer);
    }
    
    std::stringstream ss;
    ss << std::hex << std::setfill('0');
    for (int i = 0; i < 8; ++i) {
        ss << std::setw(8) << state[i];
    }
    
    return ss.str();
}

bool Sha256Verifier::verify(const std::string& filePath, const std::string& expectedChecksum) {
    std::string computed = computeSha256(filePath);
    return computed == expectedChecksum;
}

}
