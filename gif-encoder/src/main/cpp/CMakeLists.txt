cmake_minimum_required(VERSION 3.22.1)
project(sao_gif LANGUAGES CXX)

find_program(CARGO_EXECUTABLE cargo HINTS "$ENV{CARGO_HOME}/bin" "$ENV{HOME}/.cargo/bin" REQUIRED)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET aarch64-linux-android)
    set(LINKER_TARGET aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TARGET armv7-linux-androideabi)
    set(LINKER_TARGET armv7a-linux-androideabi)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TARGET x86_64-linux-android)
    set(LINKER_TARGET x86_64-linux-android)
elseif(ANDROID_ABI STREQUAL "x86")
    set(RUST_TARGET i686-linux-android)
    set(LINKER_TARGET i686-linux-android)
else()
    message(FATAL_ERROR "Unsupported Android ABI: ${ANDROID_ABI}")
endif()

set(RUST_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../rust")
set(RUST_OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/cargo")
set(RUST_LIBRARY "${RUST_OUTPUT}/${RUST_TARGET}/release/libsao_gifski.a")
string(TOUPPER "${RUST_TARGET}" RUST_ENV_TARGET)
string(REPLACE "-" "_" RUST_ENV_TARGET "${RUST_ENV_TARGET}")
get_filename_component(NDK_BIN "${CMAKE_CXX_COMPILER}" DIRECTORY)
set(RUST_LINKER "${NDK_BIN}/${LINKER_TARGET}${ANDROID_PLATFORM_LEVEL}-clang")
if(CMAKE_HOST_WIN32)
    string(APPEND RUST_LINKER ".cmd")
endif()

# Compile with the same NDK/API as JNI; never download opaque native binaries.
# 16 KiB alignment is applied to the final shared object by the current NDK.
add_custom_command(OUTPUT "${RUST_LIBRARY}"
    COMMAND "${CMAKE_COMMAND}" -E env
        "CARGO_TARGET_DIR=${RUST_OUTPUT}"
        "CARGO_TARGET_${RUST_ENV_TARGET}_LINKER=${RUST_LINKER}"
        "${CARGO_EXECUTABLE}" build --locked --release --target "${RUST_TARGET}"
            --manifest-path "${RUST_DIR}/Cargo.toml"
    DEPENDS "${RUST_DIR}/Cargo.toml" "${RUST_DIR}/Cargo.lock" "${RUST_DIR}/src/lib.rs"
    VERBATIM)
add_custom_target(sao_gifski_build DEPENDS "${RUST_LIBRARY}")
add_library(gifski STATIC IMPORTED)
set_target_properties(gifski PROPERTIES IMPORTED_LOCATION "${RUST_LIBRARY}")
add_dependencies(gifski sao_gifski_build)

add_library(sao_gif SHARED gifski_jni.cpp)
target_compile_features(sao_gif PRIVATE cxx_std_17)
target_compile_options(sao_gif PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(sao_gif PRIVATE gifski dl m log)
target_link_options(sao_gif PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384")
