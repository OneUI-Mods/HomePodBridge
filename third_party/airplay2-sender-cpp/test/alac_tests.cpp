// SPDX-License-Identifier: Apache-2.0
#include "lossless_alac.h"
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <vector>

int main() {
    fxchain::LosslessAlacEncoder encoder;
    auto cookie = encoder.cookie();
    ALACDecoder decoder;
    if (decoder.Init(cookie.data(), static_cast<uint32_t>(cookie.size())) != 0 ||
        decoder.mConfig.frameLength != 352 || decoder.mConfig.bitDepth != 16 ||
        decoder.mConfig.numChannels != 2 || decoder.mConfig.sampleRate != 44100 ||
        decoder.mConfig.pb != 40 || decoder.mConfig.mb != 10 || decoder.mConfig.kb != 14) return 1;
    std::mt19937 random(0x123456);
    uint64_t micros = 0;
    size_t rawBytes = 0, encodedBytes = 0;
    // Exercise predictor state across packets and dissimilar signal types.
    for (int packet = 0; packet < 1200; ++packet) {
        std::array<int16_t, 704> input{}, output{};
        const int kind = packet % 6;
        for (size_t i = 0; i < input.size(); ++i) {
            switch (kind) {
            case 0: input[i] = 0; break;
            case 1: input[i] = static_cast<int16_t>(12000 * std::sin((packet * 352 + i / 2) * 0.05)); break;
            case 2: input[i] = static_cast<int16_t>(random()); break;
            case 3: input[i] = i % 2 ? INT16_MAX : INT16_MIN; break;
            case 4: input[i] = static_cast<int16_t>(i * 93); break;
            case 5: input[i] = -1; break;
            }
        }
        auto start = std::chrono::steady_clock::now();
        auto encoded = encoder.encode(input.data());
        micros += std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count();
        if (encoded.empty() || encoded.size() > 1416) return 2;
        if ((kind == 0 || kind == 1) && encoded.size() >= 1408) return 3;
        // Bit readers may read their last word past the final byte; pad locally.
        const auto size = encoded.size();
        encoded.resize(size + 8, 0);
        BitBuffer bits;
        BitBufferInit(&bits, reinterpret_cast<uint8_t*>(encoded.data()), static_cast<uint32_t>(size));
        uint32_t frames = 0;
        if (decoder.Decode(&bits, reinterpret_cast<uint8_t*>(output.data()), 352, 2, &frames) != 0 ||
            frames != 352 || input != output) return 4;
        rawBytes += input.size() * sizeof(int16_t);
        encodedBytes += size;
    }
    std::printf("ALAC: 1200 exact PCM round trips; %zu/%zu bytes, average %llu us/packet (host CPU)\n",
        encodedBytes, rawBytes, static_cast<unsigned long long>(micros / 1200));
    for (uint32_t count : {88u,352u}) {
        uint64_t duration=0;
        for(int packet=0;packet<600;packet++) {
            std::vector<int16_t> input(count*2),output(704);
            for(size_t i=0;i<input.size();i++) input[i]=packet%2 ? int16_t(random()) : int16_t(i*191+packet*17);
            auto begin=std::chrono::steady_clock::now();auto data=encoder.encode(input.data(),count);
            duration+=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-begin).count();
            if(data.empty()) return 5;
            uint32_t size=uint32_t(data.size());data.resize(size+8,0);BitBuffer bits;
            BitBufferInit(&bits,reinterpret_cast<uint8_t*>(data.data()),size);uint32_t decoded=0;
            if(decoder.Decode(&bits,reinterpret_cast<uint8_t*>(output.data()),352,2,&decoded)!=0 || decoded!=count) return 6;
            output.resize(count*2);if(input!=output) return 7;
        }
        std::printf("ALAC partial: %u frames, 600 exact round trips, average %llu us/packet\n",count,(unsigned long long)(duration/600));
    }
    if(!encoder.encode(nullptr,88).empty() || !encoder.encode(std::array<int16_t,704>{}.data(),87).empty()) return 8;
    return 0;
}
