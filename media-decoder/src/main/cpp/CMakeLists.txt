cmake_minimum_required(VERSION 3.22.1)
project(sao_media LANGUAGES C CXX)
find_package(Python3 COMPONENTS Interpreter REQUIRED)
set(FFMPEG_OUTPUT "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/ffmpeg/${ANDROID_ABI}" CACHE PATH "Pinned FFmpeg source-build directory")
set(FFMPEG_LIBS)
foreach(name avformat avcodec swscale avutil)
    list(APPEND FFMPEG_LIBS "${FFMPEG_OUTPUT}/lib/lib${name}.a")
endforeach()
add_custom_command(OUTPUT ${FFMPEG_LIBS}
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/build-media-codecs.py"
        --ndk "${ANDROID_NDK}" --abi "${ANDROID_ABI}" --api "${ANDROID_PLATFORM_LEVEL}"
        --output "${FFMPEG_OUTPUT}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/build-media-codecs.py"
    VERBATIM)
add_custom_target(sao_ffmpeg DEPENDS ${FFMPEG_LIBS})
file(MAKE_DIRECTORY "${FFMPEG_OUTPUT}/include")
add_library(sao_media SHARED media_jni.cpp)
add_dependencies(sao_media sao_ffmpeg)
target_compile_features(sao_media PRIVATE cxx_std_17)
target_include_directories(sao_media PRIVATE "${FFMPEG_OUTPUT}/include")
target_compile_options(sao_media PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(sao_media PRIVATE "-Wl,--start-group" ${FFMPEG_LIBS} "-Wl,--end-group" z m dl atomic)
target_link_options(sao_media PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections")
