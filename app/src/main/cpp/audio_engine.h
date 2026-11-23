#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <oboe/Oboe.h>
#include "ring_buffer.h"

// One Oboe stream (capture or playback) with a lock-free ring between the audio callback
// and the Kotlin thread that reads or writes whole 20 ms frames. The callbacks never
// allocate, lock or call into Java.
//
// Capture uses the VOICE_RECOGNITION preset, not VOICE_COMMUNICATION: communication mode
// moves a Bluetooth headset from A2DP to HFP and turns music into mono telephone audio,
// which is the one thing this app is built to avoid.
class AudioEngine {
public:
    AudioEngine();
    ~AudioEngine();

    // Return 0 on success, otherwise a (non-zero) oboe::Result code.
    int startInput(int sampleRate, int deviceId, bool voiceCommunicationPreset);
    int startOutput(int sampleRate);
    void stop();

    int readCapture(int16_t* dst, int count);
    int writePlayback(const int16_t* src, int count);
    int buffered() const;
    int sessionId() const;
    bool needsRestart() const { return needsRestart_.load(); }
    int xruns() const { return xruns_.load(); }

private:
    class Callback;

    std::shared_ptr<oboe::AudioStream> stream_;
    std::shared_ptr<Callback> callback_;
    SpscRing ring_;
    std::atomic<bool> needsRestart_{false};
    std::atomic<int> xruns_{0};
    bool isInput_ = false;
    std::mutex lifecycle_;  // start/stop only; never taken on the audio thread

    friend class Callback;
};
