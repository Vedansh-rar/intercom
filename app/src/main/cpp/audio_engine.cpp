#include "audio_engine.h"
#include <android/log.h>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {
// 64 frames of 20 ms at 16 kHz is more than enough headroom for either direction.
constexpr size_t kRingSamples = 16000 / 50 * 64;
}  // namespace

class AudioEngine::Callback : public oboe::AudioStreamDataCallback,
                              public oboe::AudioStreamErrorCallback {
public:
    explicit Callback(AudioEngine* owner) : owner_(owner) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override {
        auto* samples = static_cast<int16_t*>(audioData);
        if (owner_->isInput_) {
            // Capture: hand the samples to the ring; if the Kotlin side is behind, the
            // newest samples are dropped and counted.
            const size_t wrote = owner_->ring_.write(samples, static_cast<size_t>(numFrames));
            if (wrote < static_cast<size_t>(numFrames)) owner_->xruns_.fetch_add(1);
        } else {
            // Playback: take what is queued and pad with silence if it ran dry.
            const size_t got = owner_->ring_.read(samples, static_cast<size_t>(numFrames));
            for (size_t i = got; i < static_cast<size_t>(numFrames); ++i) samples[i] = 0;
            if (got < static_cast<size_t>(numFrames)) owner_->xruns_.fetch_add(1);
        }
        (void)stream;
        return oboe::DataCallbackResult::Continue;
    }

    // Called after the stream has been closed because of an error such as the headset
    // being unplugged. Restarting from here would block the callback thread, so only
    // flag it; the Kotlin supervisor restarts from its own thread with backoff.
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override {
        (void)stream;
        LOGW("stream closed with error: %s", oboe::convertToText(error));
        owner_->needsRestart_.store(true);
    }

private:
    AudioEngine* owner_;
};

AudioEngine::AudioEngine() : ring_(kRingSamples) {}

AudioEngine::~AudioEngine() { stop(); }

int AudioEngine::startInput(int sampleRate, int deviceId, bool voiceCommunicationPreset) {
    std::lock_guard<std::mutex> g(lifecycle_);
    isInput_ = true;
    needsRestart_.store(false);
    xruns_.store(0);
    ring_.clear();
    callback_ = std::make_shared<Callback>(this);

    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(sampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setInputPreset(voiceCommunicationPreset ? oboe::InputPreset::VoiceCommunication
                                                  : oboe::InputPreset::VoiceRecognition)
        ->setSessionId(oboe::SessionId::Allocate)
        ->setDataCallback(callback_)
        ->setErrorCallback(callback_);
    if (deviceId > 0) b.setDeviceId(deviceId);

    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK) {
        LOGW("open input failed: %s", oboe::convertToText(r));
        stream_.reset();
        return static_cast<int>(r);
    }
    r = stream_->requestStart();
    if (r != oboe::Result::OK) {
        LOGW("start input failed: %s", oboe::convertToText(r));
        stream_->close();
        stream_.reset();
        return static_cast<int>(r);
    }
    LOGI("input started: rate=%d burst=%d", stream_->getSampleRate(), stream_->getFramesPerBurst());
    return 0;
}

int AudioEngine::startOutput(int sampleRate) {
    std::lock_guard<std::mutex> g(lifecycle_);
    isInput_ = false;
    needsRestart_.store(false);
    xruns_.store(0);
    ring_.clear();
    callback_ = std::make_shared<Callback>(this);

    // Media usage with a speech content type keeps the voice next to the music in the
    // mixer, on A2DP, exactly as the AudioTrack path did.
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(sampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Speech)
        ->setDataCallback(callback_)
        ->setErrorCallback(callback_);

    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK) {
        LOGW("open output failed: %s", oboe::convertToText(r));
        stream_.reset();
        return static_cast<int>(r);
    }
    r = stream_->requestStart();
    if (r != oboe::Result::OK) {
        LOGW("start output failed: %s", oboe::convertToText(r));
        stream_->close();
        stream_.reset();
        return static_cast<int>(r);
    }
    LOGI("output started: rate=%d burst=%d", stream_->getSampleRate(), stream_->getFramesPerBurst());
    return 0;
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> g(lifecycle_);
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
    callback_.reset();
}

int AudioEngine::readCapture(int16_t* dst, int count) {
    return static_cast<int>(ring_.read(dst, static_cast<size_t>(count)));
}

int AudioEngine::writePlayback(const int16_t* src, int count) {
    return static_cast<int>(ring_.write(src, static_cast<size_t>(count)));
}

int AudioEngine::buffered() const { return static_cast<int>(ring_.size()); }

int AudioEngine::sessionId() const {
    // Read under no lock: only called from the Kotlin side right after a successful
    // start, before any stop() can race it.
    return stream_ ? static_cast<int>(stream_->getSessionId()) : -1;
}
