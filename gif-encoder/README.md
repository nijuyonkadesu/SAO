# GIF encoder

Small JNI boundary around **gifski 1.34.0**, built from crates.io source with locked
dependency checksums. This module encodes timestamped RGBA frames; it knows nothing
about sources, Instagram, Android decoding, cache policies or coroutines. Its sole
runtime library is `libsao_gif.so`. CLI, PNG decoding and FFmpeg are disabled. The
`gifsicle` feature enables lossy LZW within the pinned Rust implementation; it adds
no external executable or dependency. Palette, motion and lossy-LZW quality are 90,
fast mode is off, and dimensions are not changed. All source timestamps are supplied;
the encoder may merge frames/regions or denoise subtle changes at this quality.

## Building

Install the Android SDK, NDK **28.2.13676358**, CMake **3.22.1**, and stable Rust
(tested with **1.99.1**). Android API 30 and 16 KiB native alignment are used.
Install the four Rust standard-library targets once:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
./gradlew :app:assembleDebug
```

CMake finds `cargo` on PATH, through `CARGO_HOME`, or in `~/.cargo/bin`. A normal
Gradle build compiles the encoder and JNI for all supported Android ABIs, then AGP
strips/packages them. No opaque prebuilts or generated native binaries are committed.
Do not enable upstream's default features: they add command-line and decoder code.

`GifskiEncoder` has one producer. Only `cancel()` is called concurrently. It signals
atomic state used by native progress/write callbacks; `finish()` joins the writer
and its workers. `close()` aborts unfinished work, joins and frees the context.
Cancellation must join the producer before closing the encoder. A bounded write
callback stops an export that exceeds its host-provided resource budget; it never
reduces quality to meet that budget. The host serializes conversions and owns files.

Upstream C API declarations are unchanged in `src/main/cpp/gifski.h`. Attribution
and licenses are packaged under `assets/licenses/gifski.txt`; refresh after an
intentional Cargo.lock change with `python3 tools/update-gifski-notices.py`.
