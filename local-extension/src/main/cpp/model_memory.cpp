#include <jni.h>
#include <cerrno>
#include <climits>
#include <cstring>
#include <sys/mman.h>
#include <sys/stat.h>

namespace {
void error(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/io/IOException");
    if (type) env->ThrowNew(type, message);
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_one_njk_sao_extension_local_ModelMemory_map(JNIEnv* env, jobject, jint descriptor) {
    struct stat info{};
    if (fstat(descriptor, &info) != 0) { error(env, std::strerror(errno)); return nullptr; }
    if (!S_ISREG(info.st_mode) || info.st_size <= 0 || info.st_size > INT_MAX) {
        error(env, "Choose a locally stored model file smaller than 2 GiB"); return nullptr;
    }
    const auto size = static_cast<size_t>(info.st_size);
    void* address = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, descriptor, 0);
    if (address == MAP_FAILED) { error(env, std::strerror(errno)); return nullptr; }
    jobject buffer = env->NewDirectByteBuffer(address, static_cast<jlong>(size));
    if (!buffer) munmap(address, size);
    return buffer;
}

extern "C" JNIEXPORT void JNICALL
Java_one_njk_sao_extension_local_ModelMemory_unmap(JNIEnv* env, jobject, jobject buffer) {
    void* address = env->GetDirectBufferAddress(buffer);
    const auto size = env->GetDirectBufferCapacity(buffer);
    if (address && size > 0) munmap(address, static_cast<size_t>(size));
}
