# Isolated Discord composer and visibility probe

Requires the original installed Discord **348.10 Stable** APK and an API 35 emulator,
plus the repository Gradle wrapper/JDK 21/Android SDK. No proprietary APK or source is
included. The production Discord policy is compiled directly from SAO. Local PNG/GIF
and MP4/WebM fixtures are copied from existing repository assets during the build.

```sh
./gradlew -p tools/discord-native-probe :app:assembleDebug
adb install -r tools/discord-native-probe/app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w \
  one.njk.sao.discordprobe/one.njk.sao.floating.nativebridge.NativeDiscordProbe
```

The probe loads the installed client's unmodified native classes in the probe UID and
substitutes a recording listener for React. It checks exact editor ownership, draft
revision changes, listener removal, actual keyboard MIME types and PNG/GIF
`commitContent` callbacks. It additionally verifies the production receiver path with
no InputConnection after clearing focus, for PNG and GIF. It also tests accessibility Paste for PNG/GIF and the
unsupported video formats. `onRequestSend` must never be called. No authenticated
account, real chat, upload or send is involved.

The probe intentionally has **no SAO package query**. Before the visibility bootstrap,
SAO package lookup and explicit service binding both fail on Android 35. To test the
same isolated empty-file grant used by the floating service:

```sh
# Start the recipient first: instrumentation force-stops it and revokes older grants.
adb shell am instrument -w -e await_bootstrap_ms 3000 \
  one.njk.sao.discordprobe/one.njk.sao.floating.nativebridge.NativeDiscordProbe \
  > /tmp/sao-discord-visibility-report.log &
sleep 1
adb shell am instrument -w -e visibility_probe_hold_ms 10000 \
  -e class one.njk.sao.floating.nativebridge.NativeBridgeDeviceTest#visibilityBootstrapMakesServiceVisibleWithoutGrantingMedia \
  one.njk.sao.test/androidx.test.runner.AndroidJUnitRunner
cat /tmp/sao-discord-visibility-report.log
```

Run the probe while the test keeps SAO alive: instrumentation completion force-stops
its target and revokes transient grants. With the grant, package lookup and service
connection succeed, the bootstrap URI reads as an empty file, and an unrelated ungranted media URI remains inaccessible. This
probe cannot register as a scoped host: the bridge separately checks caller UID.
Production grants are restricted to the four supported host packages; the test grants
this probe only. Run the focused test with the repository debug and test APKs installed.

Successful native callbacks establish the client's native receive-content contract,
not React/server acceptance, animation after upload or Vector injection on the phone.

The video extension prepares bundled MP4/WebM metadata off Main and constructs the
production native draft event using Discord's real classes. It checks event name,
surface/tag, original URI/MIME and supplied dimensions without dispatching to an
account. A context outside React cannot advertise native video insertion. The normal
image-only receiver and its rejected clipboard-video cases remain separate checks.
