#pragma once

#include <cstdint>

struct DenoiseState;

// RNNoise (Xiph.Org, BSD-3-Clause) behind a 16 kHz interface.
//
// RNNoise itself only runs at 48 kHz in 480-sample (10 ms) frames, and the call's audio is
// 16 kHz, so each 160-sample piece of voice is up-sampled by 3 with a linear-phase FIR,
// denoised, and down-sampled by 3 with the same filter. Nothing is allocated per call and
// nothing blocks: this runs on the capture thread 50 times a second.
//
// Added delay: RNNoise's own 10 ms hop plus about 1 ms for the two filters.
class RnnoiseDenoiser {
public:
    RnnoiseDenoiser() = default;
    ~RnnoiseDenoiser();
    RnnoiseDenoiser(const RnnoiseDenoiser&) = delete;
    RnnoiseDenoiser& operator=(const RnnoiseDenoiser&) = delete;

    bool init();

    // Denoises [count] mono 16 kHz samples in place. [count] must be a multiple of 160
    // (a 20 ms frame is 320). Returns false, leaving the samples untouched, otherwise.
    bool process(int16_t* pcm, int count);

    // Forgets all history: after a gap in the audio, or a route change.
    void reset();

    static constexpr int kInFrame = 160;   // 10 ms at 16 kHz
    static constexpr int kOutFrame = 480;  // 10 ms at 48 kHz

private:
    static constexpr int kTaps = 48;
    static constexpr int kPhases = 3;
    static constexpr int kPerPhase = kTaps / kPhases;

    void clearHistory();

    DenoiseState* state_ = nullptr;
    float taps_[kTaps] = {};
    float upHistory_[kPerPhase] = {};          // newest first
    float downHistory_[kTaps - 1] = {};        // last 47 samples at 48 kHz, oldest first
    float work_[kOutFrame] = {};
};
