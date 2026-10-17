# Isolated Instagram adapter probe

Requires the original installed Instagram **451.0.0.53.80 ARM64** APK, API 35 emulator
and the repository's Gradle wrapper/JDK 21/Android SDK. No proprietary APK or code is
included. `NativeInstagramProbe` uses inspected build-specific names only to construct
sparse synthetic controller metadata in Instagram's real classes. The production
adapter is compiled directly from the main app and does not hardcode those names.

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
