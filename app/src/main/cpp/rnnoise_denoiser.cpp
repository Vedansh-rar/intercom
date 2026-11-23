#include "rnnoise_denoiser.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "rnnoise.h"

namespace {

constexpr double kPi = 3.14159265358979323846;

// Windowed-sinc low-pass, cut-off 7 kHz at a 48 kHz sample rate, Blackman window,
// normalised to unity gain at DC. Stop band above 8 kHz keeps the up-sampled image and
// the down-sampling alias out of the voice.
void designTaps(float* h, int n) {
    const double fc = 7000.0 / 48000.0;
    const double mid = (n - 1) / 2.0;
    double sum = 0.0;
    double tmp[64];
    for (int i = 0; i < n; ++i) {
        const double x = i - mid;
        const double sinc = std::fabs(x) < 1e-9 ? 2.0 * fc : std::sin(2.0 * kPi * fc * x) / (kPi * x);
        const double w = 0.42 - 0.5 * std::cos(2.0 * kPi * i / (n - 1)) + 0.08 * std::cos(4.0 * kPi * i / (n - 1));
        tmp[i] = sinc * w;
        sum += tmp[i];
    }
    for (int i = 0; i < n; ++i) h[i] = static_cast<float>(tmp[i] / sum);
}

}  // namespace

RnnoiseDenoiser::~RnnoiseDenoiser() {
    if (state_) rnnoise_destroy(state_);
}

bool RnnoiseDenoiser::init() {
    if (state_) return true;
    state_ = rnnoise_create(nullptr);
    if (!state_) return false;
    designTaps(taps_, kTaps);
    clearHistory();
    return true;
}

void RnnoiseDenoiser::clearHistory() {
    std::memset(upHistory_, 0, sizeof(upHistory_));
    std::memset(downHistory_, 0, sizeof(downHistory_));
}

void RnnoiseDenoiser::reset() {
    if (!state_) return;
    // RNNoise has no reset call: a fresh state is the clean slate.
    DenoiseState* fresh = rnnoise_create(nullptr);
    if (fresh) {
        rnnoise_destroy(state_);
        state_ = fresh;
    }
    clearHistory();
}

bool RnnoiseDenoiser::process(int16_t* pcm, int count) {
    if (!state_ || !pcm || count <= 0 || count % kInFrame != 0) return false;

    for (int base = 0; base < count; base += kInFrame) {
        // ---- up-sample 160 -> 480. Output n = 3 i + p uses taps p, p+3, p+6, ...
        for (int i = 0; i < kInFrame; ++i) {
            // Shift the newest sample into the history (newest first).
            for (int j = kPerPhase - 1; j > 0; --j) upHistory_[j] = upHistory_[j - 1];
            upHistory_[0] = static_cast<float>(pcm[base + i]);
            for (int p = 0; p < kPhases; ++p) {
                float acc = 0.f;
                for (int j = 0; j < kPerPhase; ++j) acc += taps_[kPhases * j + p] * upHistory_[j];
                work_[kPhases * i + p] = acc * static_cast<float>(kPhases);  // zero-stuffing loss
            }
        }

        // ---- the denoiser itself, in place on 480 float samples scaled like int16.
        rnnoise_process_frame(state_, work_, work_);

        // ---- low-pass and decimate 480 -> 160. History holds the previous 47 samples.
        float joined[kTaps - 1 + kOutFrame];
        std::memcpy(joined, downHistory_, sizeof(downHistory_));
        std::memcpy(joined + (kTaps - 1), work_, sizeof(work_));
        for (int i = 0; i < kInFrame; ++i) {
            const int n = kTaps - 1 + kPhases * i;   // index of the newest sample for this output
            float acc = 0.f;
            for (int k = 0; k < kTaps; ++k) acc += taps_[k] * joined[n - k];
            const float v = std::min(32767.f, std::max(-32768.f, acc));
            pcm[base + i] = static_cast<int16_t>(std::lrintf(v));
        }
        std::memcpy(downHistory_, joined + kOutFrame, sizeof(downHistory_));
    }
    return true;
}
