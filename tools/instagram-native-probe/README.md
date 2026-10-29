# Isolated Instagram adapter probe

Requires an inspected Instagram **435.0.0.37.76**, **447.0.0.55.81** or **451.0.0.53.80** ARM64 APK, API 35 emulator
and the repository's Gradle wrapper/JDK 21/Android SDK, NDK 28.2 and CMake 3.22.1. No proprietary APK or code is
included. `NativeInstagramProbe` uses inspected build-specific names only to construct
sparse synthetic controller metadata in Instagram's real classes. The production
adapter is compiled directly from the main app. Controller discovery is structural;
the gallery adapter checks the inspected obfuscated contracts without version gates.

```sh
./gradlew -p tools/instagram-native-probe :app:assembleDebug
adb install --abi arm64-v8a -r tools/instagram-native-probe/app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument --abi arm64-v8a -w \
  one.njk.sao.instagramprobe/one.njk.sao.floating.nativebridge.NativeInstagramProbe
adb shell run-as one.njk.sao.instagramprobe cat files/native-report.json
```

The probe never calls Instagram's sending listener. It checks the production
adapter's exact composer role, native controller binding, mutable thread key,
editor replacement, listener clearing, inactive binding reset, recovery of a preconfigured
listener across the native superclass hierarchy, and allowed PNG/JPEG keyboard MIME types.
Gallery checks validate the owned native sender, MP4 capability, missing-sender/changed-contract
rejection and a real selected-video object with matching dimensions/duration. Its final
`commitContent` call enters the unmodified native keyboard wrapper with a recording
callback and synthetic content URI; that callback does not decode or upload media.
It reports pass flags only, with no account, thread IDs or conversation content.
Both inspected APKs also expose MP4/WebM via the verified composer view without an IME connection.
The initialization test installs the listener before its controller owns the editor,
then publishes the editor and explicitly reopens selection. It verifies recovery with
the same listener and specific loading/binding/restriction/missing-sender diagnostics.

The bundled MP4 fixture is prepared off Main. The gallery check constructs native data
objects but **never calls the gallery sender**. It cannot establish video upload or
delivery through Vector.

It also constructs the real request-owned gallery sender with a recording delegate.
Invoking that delegate's video callback verifies one-use dispatch, cancellation on
changed chat state and that the original sender is not modified. The recorder performs
no upload or send.

A successful result does **not** establish authenticated DM upload, delivery or
framework injection. Those still require the user's rooted device and native client.
The normal instrumentation tests separately cover actual exported fixture bytes,
FileProvider grants, UI capability gating and one-use editor leases.

The native-sticker adapter is mapped for **447**. The probe constructs actual
`ANIMATED_MEDIA` pending metadata and `5av` Riff commands from encoded WebP. A
synthetic positive upload FBID must become `Ys9.A0F`; that field is passed as
`data.media_id` to the stock configurator, not an already-registered sticker ID.
Construction before upload is refused. Request ownership checks reject another
account and unregistered/replayed requests. A recording callback checks that
context rejection completes once with a native nonretryable error. No native
upload/configuration/send method is called. Unmapped sticker contracts remain
unavailable; independent 435/451 photo/video gallery contracts are retained.

The 447 fixture was mapped from the device's installed APK, not inferred from its
version string. Earlier 447/451 probes passed 42 boolean checks for the previous
implementation; those results did not establish GIF delivery. The probe uses synthetic
session/controller objects; it never reads the installed app's account or chat data.

The animated-sticker probe also executes Instagram's own `GifImage` and
`WebPEncoder` against synthetic GIFs, including transparency, variable durations
and disposal-to-background/previous. The tiny probe-only native ABI marker forces
an ARM64 process on an x86-64 emulator with ARM translation. The runtime loader
uses the installed APK/library paths but extracts its Superpack archive into the
probe's writable directories; it never initializes an authenticated Instagram
Application or reads account state. Output fixtures are saved only in the probe:
`files/native-sticker-fixture.webp` and `files/native-disposal-fixture.webp` for
independent frame/timing/alpha checks. No Instagram libraries enter the repository
or production APK. Successful encoding still does not prove server registration.

The physical 447 tests confirmed original MP4 send/playback, but both checked local
converted-GIF send routes returned HTTP 404 despite passing this probe. See the
[technical investigation](../../docs/instagram-native-debugging.md) for APK/DEX tracing,
Vector setup, ADB verification, test boundaries and the corrected routing assumptions.

Direct-video checks run the production shared `VideoFrameDecoder` and the installed
WebP encoder on both MP4 and WebM fixtures. These bypass intermediate GIF creation;
outputs are `files/native-video-mp4.webp` / `files/native-video-webm.webp`. Check
frame count/dimensions/duration independently; the fixture is not a phone benchmark.

The bundled synthetic `sticker-hd.mp4` is a 2-second, 12-fps 1920×1080 H.264 test
pattern, generated with FFmpeg (`testsrc2`, libx264, CRF 22, BT.709 metadata).
It is included only in this standalone probe, never the production SAO APK.
The final native probe passes 59 boolean checks. Separately, the host's targeted
instrumentation tests verify conversion bytes/colors/timing and nonblocking,
nonreplayed bridge operations, including native-client replacement mid-operation.

The injected-context regression checks compare a Media3 raw shader's bytes against
the module resource context and verify that the wrapper keeps the native host's
identity/storage. The foreign resource ID is read only as a negative control;
it is never compiled as shader code. The probe also recreates the same native
listener Binding, validates the captured recipient again, and rejects a changed
account. The current complete probe passes 65 boolean checks.
