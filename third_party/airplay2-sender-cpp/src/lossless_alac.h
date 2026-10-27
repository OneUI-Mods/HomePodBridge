// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace fxchain {
// Independent stereo packets with a 352-frame maximum; 88-frame packets use explicit ALAC sizes.
class LosslessAlacEncoder {
public:
    LosslessAlacEncoder();
    ~LosslessAlacEncoder();
    std::string encode(const int16_t* samples, uint32_t frames = 352);
    std::vector<uint8_t> cookie() const;
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
}
