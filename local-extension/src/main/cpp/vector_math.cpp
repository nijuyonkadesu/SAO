#include <jni.h>
#include <cmath>
#include <cstring>
#include <new>

namespace {
constexpr int dimensions = 512;
struct Query { jfloat values[dimensions]; };
static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "Embedding storage is little endian");
}

extern "C" JNIEXPORT jlong JNICALL
Java_one_njk_sao_extension_local_VectorMath_create(JNIEnv* env, jobject, jfloatArray input) {
    if (env->GetArrayLength(input) != dimensions) return 0;
    auto* query = new (std::nothrow) Query;
    if (!query) return 0;
    env->GetFloatArrayRegion(input, 0, dimensions, query->values);
    if (env->ExceptionCheck()) { delete query; return 0; }
    return reinterpret_cast<jlong>(query);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_one_njk_sao_extension_local_VectorMath_dot(JNIEnv* env, jobject, jlong handle, jbyteArray input) {
    const auto* query = reinterpret_cast<const Query*>(handle);
    if (!query || env->GetArrayLength(input) != dimensions * sizeof(jfloat)) return NAN;
    const auto* bytes = static_cast<const unsigned char*>(env->GetPrimitiveArrayCritical(input, nullptr));
    if (!bytes) return NAN;
    double score = 0;
    // A bounded numerical kernel only: no allocation, I/O or JNI calls while the array is pinned.
    for (int index = 0; index < dimensions; ++index) {
        jfloat value;
        std::memcpy(&value, bytes + index * sizeof(value), sizeof(value));
        score += static_cast<double>(query->values[index]) * value;
    }
    env->ReleasePrimitiveArrayCritical(input, const_cast<unsigned char*>(bytes), JNI_ABORT);
    return score;
}

extern "C" JNIEXPORT void JNICALL
Java_one_njk_sao_extension_local_VectorMath_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Query*>(handle);
}
