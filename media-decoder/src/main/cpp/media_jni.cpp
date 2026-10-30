#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cmath>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/display.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}

namespace {
constexpr AVRational MICROSECONDS{1, 1000000};
constexpr int64_t NO_TIME = INT64_MIN;
void require(bool valid, const char *stage) { if (!valid) throw std::runtime_error(stage); }
void check(int result, const char *stage) { require(result >= 0, stage); }
void fail(JNIEnv *env, const std::exception &error) {
    // These messages are fixed code-stage names, never FFmpeg messages or input paths.
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
}
struct Decoder {
    JNIEnv *environment;
    jobject cancellation;
    jmethodID isCancelled;
    Decoder(JNIEnv *env, jobject poll) : environment(env), cancellation(env->NewGlobalRef(poll)),
        isCancelled(env->GetMethodID(env->GetObjectClass(poll), "getAsBoolean", "()Z")) { }
    AVFormatContext *format = nullptr;
    AVCodecContext *codec = nullptr;
    AVFrame *frame = av_frame_alloc();
    AVPacket *packet = av_packet_alloc();
    SwsContext *scale = nullptr;
    int track = -1, width = 0, height = 0;
    double rotation = 0;
    AVRational pixelAspect{1, 1};
    int pixelBytes = 4;
    int64_t maxPixels = 0, origin = NO_TIME, previous = -1, defaultDuration = 10000;
    bool draining = false;
    bool buffered = false;
    FILE *input = nullptr;
    AVIOContext *io = nullptr;
    std::chrono::steady_clock::time_point deadline = std::chrono::steady_clock::now() + std::chrono::seconds(60);
    ~Decoder() {
        sws_freeContext(scale);
        av_frame_free(&frame); av_packet_free(&packet);
        avcodec_free_context(&codec); avformat_close_input(&format);
        if (io) { av_freep(&io->buffer); avio_context_free(&io); }
        if (input) fclose(input);
        if (cancellation) environment->DeleteGlobalRef(cancellation);
    }
    void open(const char *path, int64_t budget) {
        require(frame && packet && cancellation && isCancelled, "software allocation");
        maxPixels = budget;
        format = avformat_alloc_context();
        require(format != nullptr, "software demux allocation");
        input = fopen(path, "rb");
        require(input != nullptr, "software input file");
        auto *buffer = static_cast<uint8_t *>(av_malloc(64 * 1024));
        require(buffer != nullptr, "software input buffer");
        io = avio_alloc_context(buffer, 64 * 1024, 0, this,
            [](void *owner, uint8_t *target, int size) -> int {
                auto *instance = static_cast<Decoder *>(owner);
                if (std::chrono::steady_clock::now() > instance->deadline ||
                    instance->environment->CallBooleanMethod(instance->cancellation, instance->isCancelled) ||
                    instance->environment->ExceptionCheck()) return AVERROR_EXIT;
                size_t count = fread(target, 1, size, instance->input);
                return count ? int(count) : feof(instance->input) ? AVERROR_EOF : AVERROR(EIO);
            }, nullptr, [](void *owner, int64_t offset, int whence) -> int64_t {
                auto *file = static_cast<Decoder *>(owner)->input;
                if (whence & AVSEEK_SIZE) {
                    auto position = ftello(file);
                    if (fseeko(file, 0, SEEK_END) != 0) return AVERROR(EIO);
                    auto size = ftello(file); fseeko(file, position, SEEK_SET); return size;
                }
                return fseeko(file, offset, whence & ~AVSEEK_FORCE) == 0 ? ftello(file) : AVERROR(EIO);
            });
        if (!io) { av_free(buffer); throw std::runtime_error("software input context"); }
        format->pb = io;
        format->flags |= AVFMT_FLAG_CUSTOM_IO;
        format->max_streams = 32;
        format->io_open = [](AVFormatContext *, AVIOContext **, const char *, int, AVDictionary **) -> int {
            return AVERROR(EPERM); // A media file cannot open additional files or URLs.
        };
        format->probesize = 4 * 1024 * 1024;
        format->max_analyze_duration = 5 * AV_TIME_BASE;
        check(avformat_open_input(&format, nullptr, nullptr, nullptr), "software open");
        std::vector<AVDictionary *> options(format->nb_streams, nullptr);
        for (auto &option : options) av_dict_set_int(&option, "max_pixels", budget, 0);
        int inspected = avformat_find_stream_info(format, options.data());
        for (auto &option : options) av_dict_free(&option);
        check(inspected, "software stream metadata");
        track = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
        check(track, "software video stream");
        auto *stream = format->streams[track];
        auto *implementation = avcodec_find_decoder(stream->codecpar->codec_id);
        require(implementation != nullptr, "software codec unavailable");
        codec = avcodec_alloc_context3(implementation);
        require(codec != nullptr, "software codec allocation");
        check(avcodec_parameters_to_context(codec, stream->codecpar), "software codec parameters");
        codec->thread_count = 2;
        codec->max_pixels = maxPixels;
        codec->pkt_timebase = stream->time_base;
        check(avcodec_open2(codec, implementation, nullptr), "software codec initialization");
        require(receive(), "software empty stream");
        buffered = true;
        width = frame->width; height = frame->height;
        if (frame->color_trc != AVCOL_TRC_UNSPECIFIED) codec->color_trc = frame->color_trc;
        if (frame->color_primaries != AVCOL_PRI_UNSPECIFIED) codec->color_primaries = frame->color_primaries;
        if (codec->color_trc == AVCOL_TRC_SMPTE2084 || codec->color_trc == AVCOL_TRC_ARIB_STD_B67) pixelBytes = 8;
        require(width > 0 && height > 0 && int64_t(width) * height <= maxPixels, "software frame budget");
        const AVPacketSideData *matrix = av_packet_side_data_get(stream->codecpar->coded_side_data,
            stream->codecpar->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
        const AVFrameSideData *frameMatrix = av_frame_get_side_data(frame, AV_FRAME_DATA_DISPLAYMATRIX);
        const uint8_t *data = frameMatrix ? frameMatrix->data : matrix ? matrix->data : nullptr;
        size_t size = frameMatrix ? frameMatrix->size : matrix ? matrix->size : 0;
        if (data && size >= 9 * sizeof(int32_t)) {
            double angle = av_display_rotation_get(reinterpret_cast<const int32_t *>(data));
            require(std::isfinite(angle), "software display matrix");
            rotation = std::fmod(-angle + 360., 360.);
        }
        auto rate = av_guess_frame_rate(format, stream, nullptr);
        if (rate.num > 0 && rate.den > 0) defaultDuration = av_rescale_q(1, av_inv_q(rate), MICROSECONDS);
        auto aspect = av_guess_sample_aspect_ratio(format, stream, frame);
        if (aspect.num > 0 && aspect.den > 0) pixelAspect = aspect;
    }
    bool receive() {
        if (buffered) { buffered = false; return true; }
        deadline = std::chrono::steady_clock::now() + std::chrono::seconds(15);
        for (;;) {
            int result = avcodec_receive_frame(codec, frame);
            if (result == 0) break;
            if (result == AVERROR_EOF) return false;
            require(result == AVERROR(EAGAIN), "software frame decode");
            require(!draining, "software drain stalled");
            do {
                result = av_read_frame(format, packet);
                if (result < 0) {
                    require(result == AVERROR_EOF, "software packet read");
                    check(avcodec_send_packet(codec, nullptr), "software flush");
                    draining = true; break;
                }
                if (packet->stream_index != track) av_packet_unref(packet);
                else break;
            } while (true);
            if (!draining) {
                int sent = avcodec_send_packet(codec, packet);
                av_packet_unref(packet);
                check(sent, "software packet decode");
            }
        }
        return true;
    }
    bool next(uint8_t *output, int64_t *timing) {
        if (!receive()) return false;
        require(frame->width > 0 && frame->height > 0 &&
            int64_t(frame->width) * frame->height <= maxPixels, "software decoded frame budget");
        // A changed stream size needs a new consumer allocation, never an overwrite.
        require(frame->width == width && frame->height == height, "software frame size changed");
        scale = sws_getCachedContext(scale, frame->width, frame->height, AVPixelFormat(frame->format),
            frame->width, frame->height, pixelBytes == 8 ? AV_PIX_FMT_RGBA64LE : AV_PIX_FMT_RGBA, SWS_BICUBIC, nullptr, nullptr, nullptr);
        require(scale != nullptr, "software pixel conversion");
        int space = frame->colorspace == AVCOL_SPC_BT709 ? SWS_CS_ITU709 :
            frame->colorspace == AVCOL_SPC_BT2020_NCL || frame->colorspace == AVCOL_SPC_BT2020_CL ? SWS_CS_BT2020 : SWS_CS_DEFAULT;
        const int *coefficients = sws_getCoefficients(space);
        sws_setColorspaceDetails(scale, coefficients, frame->color_range == AVCOL_RANGE_JPEG,
            sws_getCoefficients(SWS_CS_ITU709), 1, 0, 1 << 16, 1 << 16);
        uint8_t *destination[] = {output, nullptr, nullptr, nullptr};
        int stride[] = {frame->width * pixelBytes, 0, 0, 0};
        check(sws_scale(scale, frame->data, frame->linesize, 0, frame->height, destination, stride), "software pixel scaling");
        if (pixelBytes == 8) {
            // Preserve HDR code values as RGBA_F16; Media3 performs GPU tone mapping.
            auto *values = reinterpret_cast<uint16_t *>(output);
            for (size_t index = 0; index < size_t(width) * height * 4; ++index) {
                float value = values[index] / 65535.f;
                uint32_t bits; std::memcpy(&bits, &value, sizeof(bits));
                int exponent = int((bits >> 23) & 255) - 127 + 15;
                uint32_t fraction = bits & 0x7fffff;
                if (exponent < -10) values[index] = 0;
                else if (exponent <= 0) {
                    uint32_t mantissa = 0x800000 | fraction;
                    int shift = 14 - exponent;
                    values[index] = uint16_t((mantissa + (1u << (shift - 1)) - 1 + ((mantissa >> shift) & 1)) >> shift);
                } else values[index] = uint16_t((exponent << 10) + ((fraction + 0xfff + ((fraction >> 13) & 1)) >> 13));
            }
        }
        int64_t pts = frame->best_effort_timestamp;
        if (pts == AV_NOPTS_VALUE) pts = previous < 0 ? 0 : previous + defaultDuration;
        else {
            pts = av_rescale_q(pts, format->streams[track]->time_base, MICROSECONDS);
            if (origin == NO_TIME) origin = pts;
            pts -= origin;
        }
        timing[0] = std::max<int64_t>(previous + 1, pts);
        timing[1] = frame->duration > 0 ? av_rescale_q(frame->duration, format->streams[track]->time_base, MICROSECONDS) : defaultDuration;
        previous = timing[0]; av_frame_unref(frame);
        return true;
    }
};
Decoder *decoder(jlong handle) { require(handle != 0, "software decoder closed"); return reinterpret_cast<Decoder *>(handle); }
}

extern "C" JNIEXPORT jlong JNICALL Java_one_njk_sao_media_NativeDecoder_nativeOpen(JNIEnv *env, jclass, jstring path, jlong maxPixels, jobject poll) {
    const char *input = env->GetStringUTFChars(path, nullptr);
    if (!input) return 0;
    try {
        av_log_set_level(AV_LOG_QUIET);
        auto instance = std::make_unique<Decoder>(env, poll); instance->open(input, maxPixels);
        env->ReleaseStringUTFChars(path, input);
        return reinterpret_cast<jlong>(instance.release());
    } catch (const std::exception &error) { env->ReleaseStringUTFChars(path, input); fail(env, error); return 0; }
}
extern "C" JNIEXPORT jlongArray JNICALL Java_one_njk_sao_media_NativeDecoder_nativeInfo(JNIEnv *env, jobject, jlong handle) {
    try {
        auto *instance = decoder(handle);
        instance->environment = env;
        auto *stream = instance->format->streams[instance->track];
        jlong duration = instance->format->duration == AV_NOPTS_VALUE ? 0 : instance->format->duration;
        jlong values[] = {instance->width, instance->height, duration, stream->nb_frames,
            instance->codec->color_trc, instance->codec->color_primaries, instance->pixelBytes,
            jlong(std::llround(instance->rotation * 1000)), instance->pixelAspect.num, instance->pixelAspect.den};
        auto result = env->NewLongArray(10);
        if (result) env->SetLongArrayRegion(result, 0, 10, values);
        return result;
    } catch (const std::exception &error) { fail(env, error); return nullptr; }
}
extern "C" JNIEXPORT jboolean JNICALL Java_one_njk_sao_media_NativeDecoder_nativeNext(JNIEnv *env, jobject, jlong handle, jobject target, jlongArray times) {
    try {
        auto *instance = decoder(handle);
        instance->environment = env;
        auto *pixels = static_cast<uint8_t *>(env->GetDirectBufferAddress(target));
        require(pixels && env->GetDirectBufferCapacity(target) >= int64_t(instance->width) * instance->height * instance->pixelBytes &&
            env->GetArrayLength(times) >= 2, "software output buffer");
        int64_t timing[2];
        if (!instance->next(pixels, timing)) return JNI_FALSE;
        jlong values[] = {timing[0], timing[1]}; env->SetLongArrayRegion(times, 0, 2, values);
        return JNI_TRUE;
    } catch (const std::exception &error) { fail(env, error); return JNI_FALSE; }
}
extern "C" JNIEXPORT void JNICALL Java_one_njk_sao_media_NativeDecoder_nativeClose(JNIEnv *env, jobject, jlong handle) {
    if (!handle) return;
    reinterpret_cast<Decoder *>(handle)->environment = env;
    delete reinterpret_cast<Decoder *>(handle);
}
extern "C" JNIEXPORT jboolean JNICALL Java_one_njk_sao_media_NativeDecoder_nativeHasMultipleFrames(JNIEnv *env, jobject, jlong handle) {
    try {
        auto *instance = decoder(handle);
        instance->environment = env;
        if (!instance->receive()) return JNI_FALSE;
        av_frame_unref(instance->frame);
        bool multiple = instance->receive();
        av_frame_unref(instance->frame);
        return multiple ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception &error) { fail(env, error); return JNI_FALSE; }
}
