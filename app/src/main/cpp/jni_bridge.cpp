#include <jni.h>
#include <cstdint>
#include <memory>
#include "audio_engine.h"
#include "opus_coder.h"

// JNI for com.intercom.app.audio.NativeBridge. Everything here is called once per 20 ms
// frame at most and uses critical array access, so nothing is allocated per call.

namespace {

struct ScopedShorts {
    JNIEnv* env;
    jshortArray arr;
    jshort* ptr;
    ScopedShorts(JNIEnv* e, jshortArray a)
        : env(e), arr(a), ptr(a ? static_cast<jshort*>(e->GetPrimitiveArrayCritical(a, nullptr)) : nullptr) {}
    ~ScopedShorts() { if (ptr) env->ReleasePrimitiveArrayCritical(arr, ptr, 0); }
};

struct ScopedBytes {
    JNIEnv* env;
    jbyteArray arr;
    jbyte* ptr;
    ScopedBytes(JNIEnv* e, jbyteArray a)
        : env(e), arr(a), ptr(a ? static_cast<jbyte*>(e->GetPrimitiveArrayCritical(a, nullptr)) : nullptr) {}
    ~ScopedBytes() { if (ptr) env->ReleasePrimitiveArrayCritical(arr, ptr, 0); }
};

AudioEngine* eng(jlong h) { return reinterpret_cast<AudioEngine*>(h); }

}  // namespace

extern "C" {

// ------------------------------------------------------------------ Opus encoder

JNIEXPORT jlong JNICALL
Java_com_intercom_app_audio_NativeBridge_opusEncoderCreate(JNIEnv*, jclass, jint sampleRate, jint bitrate) {
    auto* e = new OpusEncoderWrap();
    if (!e->init(sampleRate, bitrate)) {
        delete e;
        return 0;
    }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jint JNICALL
Java_com_intercom_app_audio_NativeBridge_opusEncoderEncode(JNIEnv* env, jclass, jlong handle,
                                                           jshortArray pcm, jint frameSamples,
                                                           jbyteArray out) {
    auto* e = reinterpret_cast<OpusEncoderWrap*>(handle);
    if (!e || !pcm || !out) return -1;
    const jsize outLen = env->GetArrayLength(out);
    ScopedShorts in(env, pcm);
    ScopedBytes dst(env, out);
    if (!in.ptr || !dst.ptr) return -1;
    return e->encode(reinterpret_cast<const int16_t*>(in.ptr), frameSamples,
                     reinterpret_cast<uint8_t*>(dst.ptr), outLen);
}

JNIEXPORT jboolean JNICALL
Java_com_intercom_app_audio_NativeBridge_opusEncoderSetBitrate(JNIEnv*, jclass, jlong handle, jint bitrate) {
    auto* e = reinterpret_cast<OpusEncoderWrap*>(handle);
    return e && e->setBitrate(bitrate) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_intercom_app_audio_NativeBridge_opusEncoderDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<OpusEncoderWrap*>(handle);
}

// ------------------------------------------------------------------ Opus decoder

JNIEXPORT jlong JNICALL
Java_com_intercom_app_audio_NativeBridge_opusDecoderCreate(JNIEnv*, jclass, jint sampleRate) {
    auto* d = new OpusDecoderWrap();
    if (!d->init(sampleRate)) {
        delete d;
        return 0;
    }
    return reinterpret_cast<jlong>(d);
}

JNIEXPORT jint JNICALL
Java_com_intercom_app_audio_NativeBridge_opusDecoderDecode(JNIEnv* env, jclass, jlong handle,
                                                           jbyteArray payload, jboolean fec,
                                                           jshortArray out, jint frameSamples) {
    auto* d = reinterpret_cast<OpusDecoderWrap*>(handle);
    if (!d || !out) return -1;
    const jsize outLen = env->GetArrayLength(out);
    if (outLen < frameSamples) return -1;
    const jsize len = payload ? env->GetArrayLength(payload) : 0;
    ScopedBytes in(env, payload);
    ScopedShorts dst(env, out);
    if (!dst.ptr || (payload && !in.ptr)) return -1;
    return d->decode(payload ? reinterpret_cast<const uint8_t*>(in.ptr) : nullptr, len,
                     reinterpret_cast<int16_t*>(dst.ptr), frameSamples, fec == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_intercom_app_audio_NativeBridge_opusDecoderDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<OpusDecoderWrap*>(handle);
}

// ------------------------------------------------------------------ audio engine

JNIEXPORT jlong JNICALL
Java_com_intercom_app_audio_NativeBridge_engineCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new AudioEngine());
}

JNIEXPORT void JNICALL
Java_com_intercom_app_audio_NativeBridge_engineDestroy(JNIEnv*, jclass, jlong handle) {
    delete eng(handle);
}

JNIEXPORT jint JNICALL
Java_com_intercom_app_audio_NativeBridge_engineStartInput(JNIEnv*, jclass, jlong handle, jint sampleRate,
                                                          jint deviceId, jboolean voiceCommPreset) {
    return eng(handle)->startInput(sampleRate, deviceId, voiceCommPreset == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_intercom_app_audio_NativeBridge_engineStartOutput(JNIEnv*, jclass, jlong handle, jint sampleRate) {
    return eng(handle)->startOutput(sampleRate);
}

JNIEXPORT void JNICALL
Java_com_intercom_app_audio_NativeBridge_engineStop(JNIEnv*, jclass, jlong handle) {
    eng(handle)->stop();
}

JNIEXPORT jint JNICALL
Java_com_intercom_app_audio_NativeBridge_engineReadCapture(JNIEnv* env, jclass, jlong handle,
                                                           jshortArray out, jint count) {
    if (!out || env->GetArrayLength(out) < count) return 0;
    ScopedShorts dst(env, out);
    if (!dst.ptr) return 0;
    return eng(handle)->readCapture(reinterpret_cast<int16_t*>(dst.ptr), count);
}

JNIEXPORT jint JNICALL
Java_com_intercom_app_audio_NativeBridge_engineWritePlayback(JNIEnv* env, jclass, jlong handle,
                                                             jshortArray pcm, jint count) {
    if (!pcm || env->GetArrayLength(pcm) < count) return 0;
    ScopedShorts src(env, pcm);
    if (!src.ptr) return 0;
    return eng(handle)->writePlayback(reinterpret_cast<const int16_t*>(src.ptr), count);
}

JNIEXPORT jint JNICALL
Java_com_intercom_app_audio_NativeBridge_engineBuffered(JNIEnv*, jclass, jlong handle) {
    return eng(handle)->buffered();
}

JNIEXPORT jint JNICALL
Java_com_intercom_app_audio_NativeBridge_engineSessionId(JNIEnv*, jclass, jlong handle) {
    return eng(handle)->sessionId();
}

JNIEXPORT jboolean JNICALL
Java_com_intercom_app_audio_NativeBridge_engineNeedsRestart(JNIEnv*, jclass, jlong handle) {
    return eng(handle)->needsRestart() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_intercom_app_audio_NativeBridge_engineXruns(JNIEnv*, jclass, jlong handle) {
    return eng(handle)->xruns();
}

}  // extern "C"
