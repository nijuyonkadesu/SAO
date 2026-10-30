# Shared local decoding

`NativeDecoder` is a request-owned JNI stream over source-built FFmpeg **9.0.2**
(`avformat`, `avcodec`, `swscale`, `avutil`). It supplies decoded visual frames;
it owns no source credentials, network client, upload policy, cache or UI. SAO
owns input-rendition selection and lifecycle; injected Instagram code can use
the same library through the module resource context.

## Build and distribution

The normal Gradle build invokes `tools/build-media-codecs.py` through CMake.
Requirements: JDK 21, SDK 37, NDK 28.2.13676358, CMake 3.22.1, Python 3.12+,
Make and the usual NDK build-host tools. The first build downloads the official
[FFmpeg source archive](https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz), verifies
SHA-256 `8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e`,
then builds each configured ABI. `SAO_FFMPEG_ARCHIVE=/path/to/archive.tar.xz`
selects an already-downloaded archive, with the same verification. Generated
sources, objects and archives stay under ignored `build/ffmpeg/<ABI>`.

The script is the complete reproducible configuration: static PIC archives,
`-Oz`, section garbage collection, hidden internal symbols, no GPL/nonfree
components, executables, encoders, muxers, network or external codec packages.
Zlib is explicitly enabled for PNG/APNG. Built-in decoder/demuxer registration
remains dynamic: there is no application MIME/filename codec allowlist. The
small JNI shared object links these archives and uses 16-KiB ELF alignment.
All four Android ABIs are built. This broad fallback has a substantial size cost:
the local minified universal release is about **49 MiB**, versus about 9 MiB
before bundling it. R8 cannot remove native codec implementations.

FFmpeg is licensed under [LGPL 2.1 or later](https://ffmpeg.org/legal.html) with
this configuration; the license is bundled at `assets/licenses/ffmpeg.txt`.
The exact upstream source, configuration and JNI source are public/rebuildable;
no downloaded codec binaries or proprietary Instagram code enter this module.
Release distributors must preserve these notices and corresponding-source access.

## Runtime contract

`open(context, file, maxPixels, cancelled)` opens one local seekable file and
decodes its first frame to determine actual geometry/color. `Info` carries coded
dimensions, duration, frame count, transfer/primaries, sample aspect ratio and
display rotation. `next(directByteBuffer)` produces tightly packed **straight
RGBA**, with separate normalized microsecond PTS/duration. SDR uses RGBA8; PQ/HLG
uses RGBA-F16 to preserve transfer-encoded precision for Media3's GPU pipeline.
The caller owns geometry/color processing, alpha policy and encoding. A separate
short-lived `hasMultipleFrames()` probe consumes frames and must not be reused
as a playback stream.

Custom AVIO reads only the supplied file; secondary file/URL opens are refused.
The demux probe is bounded to 4 MiB/5 seconds and 32 streams, decoder threads to
two, and native frame allocations to the caller's pixel budget. Read callbacks
check cancellation and deadlines. Stream size changes reject safely rather than
writing past a caller-owned buffer. `close()` serializes with reads and releases
demuxer, decoder, frames, packets, conversion context and JNI references. The
library remains loaded; no live decoder, thread or work remains merely because
the library is mapped. Malformed, encrypted, external-codec-only or over-budget
media can still fail: this is broad built-in codec support, not a guarantee that
every possible media stream is decodable.

Normal processes load their installed native library. Injection can resolve the
public module APK's compatible process ABI and extract a read-only library into
the receiving process's own cache; it never reads the other app's private data.
Native entrypoint names/descriptors are retained by consumer R8 rules.

## SAO consumers

- `MediaDecodeInputs`: inspect the original's complete track format, prefer a
  source-provided platform-decodable PLAYBACK rendition when necessary, otherwise
  retain the original for software decoding. Leases remain held through use;
  consumer errors do not replay the operation against a second input.
- `VideoFrameDecoder`: platform decoder selected by **full MediaFormat**, then
  software fallback only before delivering frames. The same frame contract feeds
  gifski and Instagram's native WebP encoder. HDR-to-SDR, rotation and non-square
  pixels use Media3 GL; source pixels are not silently resized to fit memory.
- `SoftwareVideoPreview`: muted, PTS-paced visible-surface playback following an
  ExoPlayer decode failure, or an unsupported animated-image renderer. Its owned
  background worker and surface are closed before another carousel session runs.
- `SoftwareVideoAssetLoader`: the same native frames enter Media3 `RawAssetLoader`
  as one shared texture in flight, with GPU synchronization before reuse. RGBA-F16
  retains HDR for KEEP_HDR exports. An independent audio sequence preserves the
  existing Media3 audio path. Software decoding does not imply software encoding;
  unavailable hardware encoders or unsupported audio still return typed failures.
  Decoder-only failures can restart a private, incomplete MP4 export once; nothing
  is dispatched until the final validated output exists.

The source extension remains lightweight. Immich PLAYBACK uses its encoded-video
endpoint; ORIGINAL downloads, ordinary Copy and uploads keep their existing bytes.
No idle scan, retry service, network polling or wake lock is introduced.

## Focused verification

Synthetic fixtures reproduce an MPEG-4 Part 2 odd-width decoder rejection,
H.264 4:4:4, animated PNG with transparency/variable timing, HDR and rotation.
Device tests cover decoded pixels/timestamps, actual TextureView rendering and
cancellation, source-rendition lease/priority/no-replay, GIF export and software
MP4 export with audio/orientation. The account-free Instagram probe independently
checks real native WebP output: **357×372, 10 frames, 400 ms**, and APNG canvas/
timing. A minified-release JNI smoke decodes the same odd-width fixture through
the release APK and native library. None of these tests performs authenticated
uploads or sends; physical delivery remains a separate user-triggered check.
