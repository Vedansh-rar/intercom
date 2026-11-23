#include "opus_coder.h"

OpusEncoderWrap::~OpusEncoderWrap() {
    if (enc_) opus_encoder_destroy(enc_);
}

bool OpusEncoderWrap::init(int sampleRate, int bitrate) {
    int err = OPUS_OK;
    enc_ = opus_encoder_create(sampleRate, 1, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || !enc_) {
        enc_ = nullptr;
        return false;
    }
    opus_encoder_ctl(enc_, OPUS_SET_VBR(1));
    opus_encoder_ctl(enc_, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc_, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc_, OPUS_SET_INBAND_FEC(1));
    // Tell the encoder to expect some loss so it spends bits on FEC. 10% is a middle
    // setting for a radio link that drops now and then.
    opus_encoder_ctl(enc_, OPUS_SET_PACKET_LOSS_PERC(10));
    opus_encoder_ctl(enc_, OPUS_SET_DTX(0));
    // Mid complexity: fine for speech and cheap enough for a mid-range phone.
    opus_encoder_ctl(enc_, OPUS_SET_COMPLEXITY(5));
    return true;
}

int OpusEncoderWrap::encode(const int16_t* pcm, int frameSamples, uint8_t* out, int maxBytes) {
    if (!enc_) return OPUS_INVALID_STATE;
    return opus_encode(enc_, pcm, frameSamples, out, maxBytes);
}

bool OpusEncoderWrap::setBitrate(int bitrate) {
    if (!enc_) return false;
    return opus_encoder_ctl(enc_, OPUS_SET_BITRATE(bitrate)) == OPUS_OK;
}

OpusDecoderWrap::~OpusDecoderWrap() {
    if (dec_) opus_decoder_destroy(dec_);
}

bool OpusDecoderWrap::init(int sampleRate) {
    int err = OPUS_OK;
    dec_ = opus_decoder_create(sampleRate, 1, &err);
    if (err != OPUS_OK || !dec_) {
        dec_ = nullptr;
        return false;
    }
    return true;
}

int OpusDecoderWrap::decode(const uint8_t* data, int len, int16_t* out, int frameSamples, bool fec) {
    if (!dec_) return OPUS_INVALID_STATE;
    return opus_decode(dec_, data, data ? len : 0, out, frameSamples, fec ? 1 : 0);
}
