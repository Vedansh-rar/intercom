#pragma once
#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

// Single-producer single-consumer ring of int16 samples. Lock-free and allocation-free
// after construction, so it is safe to touch from an Oboe audio callback.
// Capacity is rounded up to a power of two.
class SpscRing {
public:
    explicit SpscRing(size_t minCapacity) {
        size_t cap = 1;
        while (cap < minCapacity + 1) cap <<= 1;
        mask_ = cap - 1;
        buf_.assign(cap, 0);
    }

    // Producer side. Returns how many samples were stored (fewer than n when full).
    size_t write(const int16_t* src, size_t n) {
        const size_t w = write_.load(std::memory_order_relaxed);
        const size_t r = read_.load(std::memory_order_acquire);
        const size_t freeSpace = (mask_ + 1) - 1 - ((w - r) & mask_);
        if (n > freeSpace) n = freeSpace;
        for (size_t i = 0; i < n; ++i) buf_[(w + i) & mask_] = src[i];
        write_.store((w + n) & mask_, std::memory_order_release);
        return n;
    }

    // Consumer side. Returns how many samples were read (fewer than n when short).
    size_t read(int16_t* dst, size_t n) {
        const size_t r = read_.load(std::memory_order_relaxed);
        const size_t w = write_.load(std::memory_order_acquire);
        const size_t avail = (w - r) & mask_;
        if (n > avail) n = avail;
        for (size_t i = 0; i < n; ++i) dst[i] = buf_[(r + i) & mask_];
        read_.store((r + n) & mask_, std::memory_order_release);
        return n;
    }

    size_t size() const {
        return (write_.load(std::memory_order_acquire) - read_.load(std::memory_order_acquire)) & mask_;
    }

    // Only call while neither side is running.
    void clear() {
        read_.store(0);
        write_.store(0);
    }

private:
    std::vector<int16_t> buf_;
    size_t mask_ = 0;
    std::atomic<size_t> write_{0};
    std::atomic<size_t> read_{0};
};
