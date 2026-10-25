// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace fxchain {
// One independent 352-frame stereo packet; no extra codec lookahead.
class LosslessAlacEncoder {
public:
    LosslessAlacEncoder();
    ~LosslessAlacEncoder();
    std::string encode(const int16_t* samples);
    std::vector<uint8_t> cookie() const;
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
}
