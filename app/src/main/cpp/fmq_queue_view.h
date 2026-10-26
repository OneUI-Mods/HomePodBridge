// Prototype only. No descriptors, streams, devices or policies are created here.
#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>

struct hp_fmq_view {
    std::atomic<uint64_t>* read;
    std::atomic<uint64_t>* write;
    uint8_t* data;
    size_t capacity;
    size_t quantum;

    bool valid() const {
        return read && write && data && read != write
            && capacity && capacity <= 4 * 1024 * 1024
            && quantum && quantum <= capacity && capacity % quantum == 0
            && reinterpret_cast<uintptr_t>(read) % alignof(std::atomic<uint64_t>) == 0
            && reinterpret_cast<uintptr_t>(write) % alignof(std::atomic<uint64_t>) == 0;
    }
    // Indices count bytes, including wrap at uint64_t max; never reset shared indices on attach.
    bool transfer(void* memory, size_t bytes, bool produce) const {
        if (!valid() || !memory || !bytes || bytes > capacity || bytes % quantum) return false;
        uint64_t r = read->load(std::memory_order_acquire);
        uint64_t w = write->load(std::memory_order_acquire);
        uint64_t occupied = w - r;
        if (occupied > capacity || r % quantum || w % quantum) return false;
        if (produce ? bytes > capacity - occupied : bytes > occupied) return false;
        uint64_t cursor = produce ? w : r;
        // Treat terminal counter overflow as unavailable; never silently rebase a shared queue.
        if (cursor > UINT64_MAX - bytes) return false;
        size_t offset = cursor % capacity;
        size_t first = bytes < capacity - offset ? bytes : capacity - offset;
        auto* buffer = static_cast<uint8_t*>(memory);
        if (produce) {
            std::memcpy(data + offset, buffer, first);
            std::memcpy(data, buffer + first, bytes - first);
            write->store(w + bytes, std::memory_order_release);
        } else {
            std::memcpy(buffer, data + offset, first);
            std::memcpy(buffer + first, data, bytes - first);
            read->store(r + bytes, std::memory_order_release);
        }
        return true;
    }
};
