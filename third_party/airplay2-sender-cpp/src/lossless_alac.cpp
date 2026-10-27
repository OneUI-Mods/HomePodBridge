// SPDX-License-Identifier: Apache-2.0
#include "lossless_alac.h"
#include "ALACEncoder.h"
#include <array>
#include <stdexcept>

namespace fxchain {
struct LosslessAlacEncoder::Impl {
    ALACEncoder encoder;
    AudioFormatDescription input{}, output{};
    std::array<uint8_t, 4096> buffer{};
    Impl() {
        output.mSampleRate = 44100;
        output.mFormatID = kALACFormatAppleLossless;
        output.mFormatFlags = 1; // ALAC 16-bit source
        output.mChannelsPerFrame = 2;
        output.mFramesPerPacket = 352;
        encoder.SetFrameSize(352);
        encoder.SetFastMode(true);
        if (encoder.InitializeEncoder(output) != 0)
            throw std::runtime_error("ALAC initialization failed");
        input.mSampleRate = 44100;
        input.mFormatID = kALACFormatLinearPCM;
        input.mFormatFlags = kALACFormatFlagIsSignedInteger | kALACFormatFlagIsPacked;
        input.mBytesPerPacket = input.mBytesPerFrame = 4;
        input.mFramesPerPacket = 1;
        input.mChannelsPerFrame = 2;
        input.mBitsPerChannel = 16;
    }
};
LosslessAlacEncoder::LosslessAlacEncoder() : impl_(std::make_unique<Impl>()) {}
LosslessAlacEncoder::~LosslessAlacEncoder() = default;
std::string LosslessAlacEncoder::encode(const int16_t* samples, uint32_t frames) {
    if (!samples || (frames != 44 && frames != 88 && frames != 352)) return {};
    // Keep the negotiated 352-frame cookie. Short packets carry ALAC's explicit sample count.
    int32_t bytes = static_cast<int32_t>(frames * 4);
    if (impl_->encoder.Encode(impl_->input, impl_->output,
            reinterpret_cast<unsigned char*>(const_cast<int16_t*>(samples)),
            impl_->buffer.data(), &bytes) != 0 || bytes <= 0 || bytes > 4096)
        return {};
    return {reinterpret_cast<char*>(impl_->buffer.data()), static_cast<size_t>(bytes)};
}
std::vector<uint8_t> LosslessAlacEncoder::cookie() const {
    uint32_t size = impl_->encoder.GetMagicCookieSize(2);
    std::vector<uint8_t> result(size);
    impl_->encoder.GetMagicCookie(result.data(), &size);
    result.resize(size);
    return result;
}
}
