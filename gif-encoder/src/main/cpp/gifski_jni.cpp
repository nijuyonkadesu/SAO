#include <jni.h>
#include <atomic>
#include <cstdio>
#include <memory>
#include "gifski.h"

namespace {
constexpr uint8_t kQuality = 90;
struct Encoder {
    gifski* gif = nullptr;
    FILE* output = nullptr;
    std::atomic_bool cancelled{false};
    size_t bytes = 0;
    size_t limit;
    explicit Encoder(size_t maxBytes): limit(maxBytes) {}
    ~Encoder() {
        // Normally joined by nativeFinish; cover failures during construction too.
        if(gif) { cancelled.store(true); gifski_finish(gif); }
        if(output) std::fclose(output);
    }
};
int progress(void* data) { return !static_cast<Encoder*>(data)->cancelled.load(); }
int write(size_t size,const uint8_t* bytes,void* data) {
    auto* encoder=static_cast<Encoder*>(data);
    if(encoder->cancelled.load()) return GIFSKI_ABORTED;
    if(size>encoder->limit-encoder->bytes) return GIFSKI_WRITE_ZERO;
    if(size && std::fwrite(bytes,1,size,encoder->output)!=size) return GIFSKI_WRITE_ZERO;
    encoder->bytes+=size;
    return GIFSKI_OK;
}
Encoder* get(jlong handle) { return reinterpret_cast<Encoder*>(handle); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_one_njk_sao_gif_GifskiEncoder_nativeCreate(JNIEnv* env,jobject,jstring path,jlong maxBytes) {
    if(!path || maxBytes<=0) return 0;
    auto encoder=std::make_unique<Encoder>(static_cast<size_t>(maxBytes));
    const char* name=env->GetStringUTFChars(path,nullptr);
    if(!name) return 0;
    encoder->output=std::fopen(name,"wb");
    env->ReleaseStringUTFChars(path,name);
    if(!encoder->output) return 0;
    GifskiSettings settings{};
    // Zero/None activates upstream's automatic ~800x600 resizing. Explicit maxima
    // preserve every valid GIF dimension; never silently shrink larger clips.
    settings.width=65535; settings.height=65535;
    settings.quality=kQuality; settings.fast=false; settings.repeat=0;
    encoder->gif=gifski_new(&settings);
    // Keep palette, temporal denoising and optional lossy LZW at the same quality.
    // Cargo enables only the gifsicle feature, without CLI or decoder dependencies.
    if(!encoder->gif || gifski_set_motion_quality(encoder->gif,kQuality)!=GIFSKI_OK ||
        gifski_set_lossy_quality(encoder->gif,kQuality)!=GIFSKI_OK ||
        gifski_set_progress_callback(encoder->gif,progress,encoder.get())!=GIFSKI_OK ||
        gifski_set_write_callback(encoder->gif,write,encoder.get())!=GIFSKI_OK) return 0;
    return reinterpret_cast<jlong>(encoder.release());
}
extern "C" JNIEXPORT jint JNICALL
Java_one_njk_sao_gif_GifskiEncoder_nativeAdd(JNIEnv* env,jobject,jlong handle,jint frame,
    jint width,jint height,jint rowBytes,jobject buffer,jdouble seconds) {
    auto* encoder=get(handle);
    auto* bytes=static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity=env->GetDirectBufferCapacity(buffer);
    if(!encoder || !encoder->gif || !bytes || frame<0 || width<=0 || height<=0 || width>65535 || height>65535 ||
        rowBytes%4!=0 || rowBytes<static_cast<int64_t>(width)*4 || capacity<(height-1LL)*rowBytes+width*4LL) return GIFSKI_INVALID_INPUT;
    if(encoder->cancelled.load()) return GIFSKI_ABORTED;
    return gifski_add_frame_rgba_stride(encoder->gif,frame,width,height,rowBytes,bytes,seconds);
}
extern "C" JNIEXPORT jint JNICALL
Java_one_njk_sao_gif_GifskiEncoder_nativeFinish(JNIEnv*,jobject,jlong handle) {
    auto* encoder=get(handle);
    if(!encoder || !encoder->gif) return GIFSKI_INVALID_STATE;
    const auto result=gifski_finish(encoder->gif);
    encoder->gif=nullptr;
    const bool flushed=std::fflush(encoder->output)==0;
    return result==GIFSKI_OK && !flushed ? GIFSKI_WRITE_ZERO : result;
}
extern "C" JNIEXPORT void JNICALL
Java_one_njk_sao_gif_GifskiEncoder_nativeCancel(JNIEnv*,jobject,jlong handle) { get(handle)->cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL
Java_one_njk_sao_gif_GifskiEncoder_nativeDestroy(JNIEnv*,jobject,jlong handle) { delete get(handle); }
