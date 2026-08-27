// SPDX-FileCopyrightText: 2024 RPCS4 Android Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ============================================================================
// SDL_QueueAudio backend over Android AAudio (audio_aaudio.cpp)
//
// SceAudioOut.cpp drives audio exclusively through the pull-queue family:
//   open -> pause(0) -> QueueAudio(...) / GetQueuedAudioSize() spin -> ...
//
// The shim keeps a single output stream (matching the upstream stub which only
// services the first opened port) at 48 kHz stereo float32. Whatever the guest
// asks for - mono/2ch/8ch, s16/f32 - is converted on the way in and downmixed
// to stereo. Queue accounting is kept in *source-format bytes* so the core's
// `while (SDL_GetQueuedAudioSize(dev) >= 4096 * 8 * n_channels)` pacing loop
// behaves exactly like it does against real SDL2.
// ============================================================================

#include "SDL.h"

#include <aaudio/AAudio.h>

#include <android/log.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <vector>

#define TAG "rpcs4-audio"

namespace {

constexpr size_t kMaxFramesQueued = 48000;   // one second of stereo float32

struct AudioOut {
    std::mutex mtx;

    // Source format as requested through SDL_OpenAudioDevice.
    unsigned short srcFormat = AUDIO_S16;
    unsigned char srcChannels = 2;
    bool opened = false;
    bool running = false;

    AAudioStream* stream = nullptr;

    // Stereo interleaved float32 samples pending playback.
    std::vector<float> pcm;
};

AudioOut& out()
{
    static AudioOut inst;
    return inst;
}

bool isF32(unsigned short fmt)
{
    return fmt == AUDIO_F32;
}

float sampleAsF32(const Uint8* base, size_t index, bool f32)
{
    if (f32) {
        float v;
        std::memcpy(&v, base + index * sizeof(float), sizeof(float));
        return v;
    }
    short v;
    std::memcpy(&v, base + index * sizeof(short), sizeof(short));
    return static_cast<float>(v) / 32768.f;
}

/** Convert [data,lenBytes] of srcChannels/srcFormat into stereo floats appended to pcm. */
void pushConverted(const Uint8* data, Uint32 lenBytes)
{
    auto& o = out();

    const bool f32 = isF32(o.srcFormat);
    const int sampleSize = f32 ? 4 : 2;
    const int channels = o.srcChannels > 0 ? o.srcChannels : 2;

    const size_t srcScalars = lenBytes / static_cast<size_t>(sampleSize);
    if (srcScalars == 0) return;
    const size_t frames = srcScalars / static_cast<size_t>(channels);
    if (frames == 0) return;

    // Capacity check: drop-to-cap so the guest's pacing loop stays authoritative.
    if (o.pcm.size() / 2 >= kMaxFramesQueued) {
        return;
    }

    o.pcm.reserve(o.pcm.size() + frames * 2);
    for (size_t f = 0; f < frames && o.pcm.size() / 2 < kMaxFramesQueued; ++f) {
        const size_t base = f * static_cast<size_t>(channels);
        const float l = sampleAsF32(data, base + 0, f32);
        const float r = channels >= 2
            ? sampleAsF32(data, base + 1, f32)
            : l;
        o.pcm.push_back(l);
        o.pcm.push_back(r);
    }
}

void onStreamData(float* dst, int32_t numFrames)
{
    auto& o = out();
    const std::lock_guard<std::mutex> lock(o.mtx);

    const size_t want = static_cast<size_t>(numFrames) * 2;   // floats
    const size_t have = o.pcm.size();
    const size_t take = std::min(have, want);

    std::memcpy(dst, o.pcm.data(), take * sizeof(float));
    if (take < want) {
        std::memset(dst + take, 0, (want - take) * sizeof(float));
    }
    o.pcm.erase(o.pcm.begin(), o.pcm.begin() + static_cast<long>(take));
}

aaudio_data_callback_result_t onDataCallback(AAudioStream* /*stream*/, void* userData, void* audioData, int32_t numFrames)
{
    auto* dst = static_cast<float*>(audioData);
    (void)userData;
    onStreamData(dst, numFrames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void onErrorCallback(AAudioStream*, void* /*userData*/, aaudio_result_t error)
{
    __android_log_print(ANDROID_LOG_ERROR, TAG, "AAudio error: %s", AAudio_convertResultToText(error));
}

}   // namespace

extern "C" {

SDL_AudioDeviceID SDL_OpenAudioDevice(
    const char* /*device*/, int iscapture,
    const SDL_AudioSpec* desired, SDL_AudioSpec* obtained,
    int /*allowed_changes*/)
{
    if (iscapture != 0 || desired == nullptr || obtained == nullptr) return 0;

    auto& o = out();
    const std::lock_guard<std::mutex> lock(o.mtx);

    // Remember what the guest feeds us so QueueAudio can convert.
    o.srcFormat = desired->format;
    o.srcChannels = desired->channels ? desired->channels : 2;

    if (!o.opened) {
        AAudioStreamBuilder* builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "AAudio_createStreamBuilder failed");
            return 0;
        }

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSampleRate(builder, 48000);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setDataCallback(builder, &onDataCallback, nullptr);
        AAudioStreamBuilder_setErrorCallback(builder, &onErrorCallback, nullptr);

        const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &o.stream);
        AAudioStreamBuilder_delete(builder);
        if (result != AAUDIO_OK || o.stream == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "openStream failed: %s",
                                AAudio_convertResultToText(result));
            return 0;
        }
        o.opened = true;
        o.running = false;
    }

    // obtained: we keep channel count fixed (stereo); format becomes f32.
    obtained->freq = 48000;
    obtained->format = AUDIO_F32;
    obtained->channels = 2;
    obtained->samples = desired->samples;
    obtained->callback = nullptr;

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "opened stream (source %dch %s)", o.srcChannels,
                        isF32(o.srcFormat) ? "f32" : "s16");
    return 1;   // Non-zero device id; upstream stores it verbatim.
}

void SDL_PauseAudioDevice(SDL_AudioDeviceID /*dev*/, int pause_on)
{
    auto& o = out();
    const std::lock_guard<std::mutex> lock(o.mtx);
    if (!o.opened || o.stream == nullptr) return;

    if (pause_on != 0) {
        if (o.running) {
            AAudioStream_requestPause(o.stream);
            o.running = false;
        }
    } else if (!o.running) {
        AAudioStream_requestFlush(o.stream);
        AAudioStream_requestStart(o.stream);
        o.running = true;
    }
}

int SDL_QueueAudio(SDL_AudioDeviceID /*dev*/, const void* data, Uint32 len)
{
    if (data == nullptr || len == 0) return 0;

    auto& o = out();
    const std::lock_guard<std::mutex> lock(o.mtx);
    pushConverted(static_cast<const Uint8*>(data), len);
    return 0;
}

Uint32 SDL_GetQueuedAudioSize(SDL_AudioDeviceID /*dev*/)
{
    auto& o = out();
    const std::lock_guard<std::mutex> lock(o.mtx);

    // Report in SOURCE-format bytes so upstream's `4096 * 8 * n_channels`
    // pacing threshold compares like-for-like with desktop SDL2 behavior.
    const size_t sampleSize = isF32(o.srcFormat) ? sizeof(float) : sizeof(short);
    const size_t framesBuffered = o.pcm.size() / 2;
    return static_cast<Uint32>(framesBuffered * sampleSize * o.srcChannels);
}

}   // extern "C"
