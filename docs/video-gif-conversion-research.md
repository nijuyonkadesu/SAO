# Android video-to-GIF conversion research

Researched 2026-10-27. Conversion was not implemented by the earlier native GIF adapter.
The adapter accepts GIF files; MP4 must first be exported as an actual animated GIF,
then handed to the verified native GIF capability. Renaming a MIME type or filename
cannot do this. Uploads and ordinary media exports must retain their originals.

## Recommendation

Use Android hardware video decoding, the existing Media3 color/rotation processing,
and the **gifski encoder-only C API** through a small JNI boundary. Keep the encoder
independent of picker UI and source extensions. Frame extraction and native encoding
must run off Main, as one cancellable conversion at a time, with bounded frame queues
and native resources joined/released on cancellation. Feed color-managed RGBA frames
with their actual presentation timestamps; do not repeatedly seek thumbnail APIs or
spool a directory containing every decoded frame.

GIF remains an indexed, SDR animation format. High-quality conversion cannot guarantee
both the original video's visual quality and a similar file size. Preserve dimensions
and timing unless the user chooses otherwise, use the encoder's high-quality mode,
and report an unsupported/resource-limited conversion instead of silently shrinking,
truncating, dropping frames or applying an arbitrary byte target. HDR needs explicit
HDR-to-SDR tone mapping for GIF; other delivery paths keep their existing HDR behavior.

The native dependency was approved. The implementation excludes FFmpeg, CLI, PNG
input, bundled video decoders and other optional gifski features. SAO is already
AGPL-3.0, and gifski is AGPL-3.0-or-later.

## Integration

The host's `VideoGifDecoder` streams MediaCodec output through the existing Media3
GL processor into an RGBA ImageReader. It applies orientation once and performs
explicit BT.2020 HDR-to-SDR tone mapping when supported. There is one image in flight;
gifski's internal queues/lookahead provide backpressure. No bitmap-per-thumbnail
seeking, directory of PNG frames, second video encode or new Media3 dependency is used.
The producer runs on a conversion-only background-priority HandlerThread, so native
writer workers inherit that priority without changing shared coroutine-pool threads.

`VideoGifTranscoder` owns cancellation and joins the producer/native writer before
releasing the source lease. The normal export semaphore admits one conversion at a
time. Full-size frames get a heap-relative resource guard, output is bounded by the
host's 192 MiB export budget, and a 90-second deadline keeps preparation inside the
native handoff lease window. These limits reject unsafe conversions, not recompress
them. The source file is unchanged. GIF timing is quantized to its 10 ms timebase;
the container validator uses the input's actual end timestamp for the final delay,
which gifski otherwise infers from the preceding frame interval. Identical frames
may be merged while preserving their display duration.

Instagram Paste chooses GIF for videos only when the selected native conversation
advertises the verified GIF capability. The separate DM action keeps original-video
attachment available. Other apps, Copy, ordinary sharing and uploads retain their
existing export policies. GIF cache keys are separate from original/MP4 renditions.
An unsupported native GIF contract remains disabled; conversion alone cannot enable
an unknown Instagram upload API. GIF output has no audio and cannot preserve HDR.

The complete stripped JNI+encoder sizes in the release APK are **880,448 B ARM64**,
640,456 B ARMv7, 958,172 B x86 and 961,144 B x86-64. A universal APK packages all
four, about 3.3 MiB total, plus small license/Kotlin overhead; this is not a 0.8 MiB
increase for every APK. There are no native encoder binaries in source extensions.

Validation: 40 focused device tests and 59 host unit tests passed, along with lint
and debug/minified release builds. The real Instagram 451-class probe passed all
40 boolean checks without authenticated sending. Device coverage includes GIF-only MP4 Paste,
the complete conversion-to-native-file preparation chain, 720p resize prevention,
RGBA stride, final-frame timing, exact originals/cache separation, write-budget
failure, HDR handling/rejection and cancellation cleanup. All four shared objects
have 16 KiB LOAD alignment; the release APK also passes zipalign's 16 KiB check.

## Candidates checked

| Candidate | Findings | Fit for SAO |
|---|---|---|
| gifski 1.34.0, encoder-only | Cross-frame palettes, temporal dithering, unchanged-region optimization; streaming RGBA/timestamp C API and cancellation callback. | Preferred quality-oriented encoder. |
| ImageToolboxLibs modern Rust GIF encoder | Quantette k-means quantization plus Rust gif crate. Inspected implementation quantizes/writes each frame independently. ARM64 binary in its repository is 540,968 bytes. | Smaller, but lacks gifski's cross-frame processing. |
| FFmpeg palettegen/paletteuse | Good optimized baseline, including rectangle differencing and dithering. Performed well for flat-color caption graphics. | Avoid shipping an additional decoding/filtering framework for one encoder. |
| Classic Java AnimatedGifEncoder/NeuQuant | Old per-frame palette route, without cross-frame optimization. | Rejected; the uncommitted exploratory scaffold was removed. |

## Android library build check

Built upstream **gifski tag 1.34.0**, commit `1060eab`, outside SAO using:

```
cargo build --locked --release --lib --no-default-features --target aarch64-linux-android
```

NDK 28.2.13676358, API 30 linker. The resulting stripped `libgifski.so` is **839,384 bytes**
(0.80 MiB); ZIP deflation gives 390,451 bytes, but that is not an APK-size promise:
Android may store native libraries uncompressed. This is one ABI, before a small JNI
wrapper, and not the size of a universal multi-ABI APK. Its only dynamic dependencies
are Android system `libc`, `libm` and `libdl`. ELF LOAD segments have 16 KiB alignment.
No FFmpeg or video-decoding library was linked. Android runtime speed and delivery
still need implementation and device verification.

## Synthetic output comparison

All inputs are generated test media, not personal images. Each clip is **480 x 270,
3 seconds, 45 frames at 15 fps**. Gifski quality/motion/lossy-quality settings are 100,
without its fast mode or resizing. Feed canonical RGB/PNG frames for this desktop
comparison; the Android implementation should use RGBA directly. Metadata checks
confirm original dimensions, duration and frame count for every compared output.

| Clip | Original MP4 | Optimized FFmpeg GIF | Gifski high-quality GIF |
|---|---:|---:|---:|
| Caption + moving square | 7,904 B | 27,280 B | 28,739 B |
| Test motion | 184,877 B | 744,010 B | 663,500 B |
| Animated gradient | 40,923 B | 2,508,511 B | 2,281,466 B |

These are illustrative synthetic measurements, not representative-library statistics
or Android performance claims. Gifski is not the smallest encoder for every input.
Its gradient output was visually smoother than the global-palette baseline. Numeric
SSIM alone should not select a GIF encoder because dithering is penalized strongly.
The gradient example also shows why no encoder can promise compact GIFs for arbitrary
video while preserving dimensions, frame count and perceptual quality.

## References

- [gifski upstream](https://github.com/ImageOptim/gifski/tree/1.34.0)
- [gifski C API](https://github.com/ImageOptim/gifski/blob/1.34.0/gifski.h)
- [gifski streaming/threading API](https://docs.rs/gifski/1.34.0/gifski/fn.new.html)
- [ImageToolbox Rust encoder implementation](https://github.com/T8RIN/ImageToolboxLibs/blob/master/libs/gif-converter/src/main/rust/gif_encoder/src/lib.rs)
- [Media3 frame extraction](https://developer.android.com/media/media3/inspector/extract-frames)
- [Media3 HDR and tone mapping](https://developer.android.com/media/media3/transformer/supported-formats#hdr)
- [FFmpeg palettegen/paletteuse](https://ffmpeg.org/ffmpeg-filters.html#paletteuse)
- [Android NDK toolchains](https://developer.android.com/ndk/guides/other_build_systems)
