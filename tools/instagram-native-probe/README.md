# Isolated Instagram adapter probe

Requires an inspected Instagram **435.0.0.37.76**, **447.0.0.55.81** or **451.0.0.53.80** ARM64 APK, API 35 emulator
and the repository's Gradle wrapper/JDK 21/Android SDK. No proprietary APK or code is
included. `NativeInstagramProbe` uses inspected build-specific names only to construct
sparse synthetic controller metadata in Instagram's real classes. The production
adapter is compiled directly from the main app. Controller discovery is structural;
the gallery adapter checks the inspected obfuscated contracts without version gates.

```sh
./gradlew -p tools/instagram-native-probe :app:assembleDebug
adb install -r tools/instagram-native-probe/app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w \
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

On 447 and 451 the probe also checks the local GIF upload contract, missing-session capability
gating and a parcel copy of the recipient key. It constructs real `ANIMATED_MEDIA`
pending metadata and its ordinary media command using a fixture GIF, verifying the
local file path, original recipient and unchanged bytes. These checks call no upload,
registration or send entry point. The older 435 gallery contract does not advertise GIF.

The 447 fixture was mapped from the device's installed APK, not inferred from its
version string. Both 447 and 451 pass 42 boolean checks, including independent GIF
readiness and missing-session diagnostics. The probe still uses its own synthetic
session/controller objects; it never reads the installed app's account or chat data.

The subsequent physical 447 test confirmed original MP4 send/playback, but the local
converted-GIF path returned HTTP 404 despite passing this probe. See the
[technical investigation](../../docs/instagram-native-debugging.md) for APK/DEX tracing,
Vector setup, ADB verification, test boundaries and the corrected routing assumptions.
