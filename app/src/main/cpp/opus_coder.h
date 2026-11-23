#pragma once
#include <cstdint>
#include <opus.h>

// Thin wrappers over libopus configured for this app: mono, VOIP tuning, VBR, in-band FEC
// on, DTX off. One instance per direction; not thread-safe, each is used from one thread.

class OpusEncoderWrap {
public:
    OpusEncoderWrap() = default;
    ~OpusEncoderWrap();
    OpusEncoderWrap(const OpusEncoderWrap&) = delete;
    OpusEncoderWrap& operator=(const OpusEncoderWrap&) = delete;

    bool init(int sampleRate, int bitrate);
    // Returns encoded bytes, or a negative libopus error.
    int encode(const int16_t* pcm, int frameSamples, uint8_t* out, int maxBytes);
    bool setBitrate(int bitrate);

private:
    OpusEncoder* enc_ = nullptr;
};

class OpusDecoderWrap {
public:
    OpusDecoderWrap() = default;
    ~OpusDecoderWrap();
    OpusDecoderWrap(const OpusDecoderWrap&) = delete;
    OpusDecoderWrap& operator=(const OpusDecoderWrap&) = delete;

    bool init(int sampleRate);
    // data == nullptr conceals a lost packet. With fec true, data is the packet *after*
    // the lost one and the lost frame is recovered from its in-band FEC.
    // Returns samples decoded, or a negative libopus error.
    int decode(const uint8_t* data, int len, int16_t* out, int frameSamples, bool fec);

private:
    OpusDecoder* dec_ = nullptr;
};
