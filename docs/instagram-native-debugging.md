# Instagram native Paste: investigation and verification

Recorded 2026-10-27. This is a reproducible account of the investigation, not a claim
that every structurally compatible Instagram build can deliver every format.

**Result:** the user confirmed that a fresh Paste sends the original MP4 and playback
works on Instagram 447.0.0.55.81 with Vector. A sender-side thumbnail was missing;
the user accepted this behavior pending any recipient-side problem. Recipient-side
rendering was not independently inspected. Local GIF delivery remains unresolved:
the MP4-to-GIF attempt entered Instagram's pipeline but failed with HTTP 404.

## 1. Establish the failing layer

Trace these boundaries separately:

`picker capability → export → URI grant → host staging → lease validation → native dispatch → asynchronous upload/send → conversation rendering`

Photo Paste already worked, while video Paste was disabled. That established a
working injected bridge and photo handler; it did not establish a video handler.
Later, `video=ready, gif=ready` established adapter readiness, not server acceptance.
`NativeInsertResult.ACCEPTED` means native dispatch was accepted, not that the remote
message was delivered. This distinction explains why the isolated probe passed
while the user's converted GIF still failed.

## 2. Obtain the exact installed code

Use Bash, an existing ADB pairing, JDK 21 and the configured Android SDK. Wireless
pairing and connection ports are different; mDNS connection ports can change after
sleep/network changes. Do not hold a wake lock to keep ADB connected.

```bash
adb devices -l
adb mdns services
# If needed: adb pair "$PHONE_IP:$PAIRING_PORT"
# Then:      adb connect "$PHONE_IP:$CONNECT_PORT"
export SAO_DEVICE='SERIAL_FROM_ADB_DEVICES'
export SAO_LAB="$(mktemp -d "${TMPDIR:-/tmp}/sao-instagram.XXXXXX")"
mapfile -t IG_PATHS < <(adb -s "$SAO_DEVICE" shell pm path com.instagram.android | tr -d '\r')
for entry in "${IG_PATHS[@]}"; do
    remote="${entry#package:}"
    adb -s "$SAO_DEVICE" pull "$remote" "$SAO_LAB/$(basename "$remote")"
done
export IG_APK="$SAO_LAB/base.apk"
sha256sum "$IG_APK"
"$ANDROID_HOME/build-tools/37.0.0/aapt2" dump badging "$IG_APK" | head -n 1
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs "$IG_APK"
```

Use the installed SDK's build-tools version. Keep base and configuration splits
together. Inspect APK code/resources only; no application database, account storage
or conversation extraction is required. Keep APKs and decompiled output outside Git.

The installed 447 APK differed from the previously inspected original 451 APK in
symbols and signing/build metadata. Version text alone therefore could not select
the adapter. Removing a version gate would not repair different classes/signatures.

## 3. Trace from stable names into obfuscated owners

Start with named `ComposerAutoCompleteTextView`, resource
`row_thread_composer_edittext`, `DirectThreadKey`, `Medium`, `ClipInfo` and
`PendingMediaManager`. Search strings such as `unsupportedGifError`,
`ANIMATED_MEDIA` and `animated_image_attachment`. Follow actual constructor writes,
field reads and call sites; a name in a DEX string/type table is not proof that the
class is defined in that DEX. Compare decompiler output with DEX instructions.

The temporary inspector used Androguard **4.1.4**, one DEX at a time, with byte
prefiltering and targeted class/method/reference output. This compact equivalent
can be saved as `$SAO_LAB/dex_trace.py`; it adds no Android runtime dependency:

```python
import sys
from zipfile import ZipFile
from androguard.core.dex import DEX
from loguru import logger
logger.disable("androguard")
apk, mode, needle = sys.argv[1:]
assert mode in {"class", "calls", "refs", "strings"}
with ZipFile(apk) as archive:
    for name in archive.namelist():
        if not name.endswith(".dex"):
            continue
        data = archive.read(name)
        if needle.encode() not in data:
            continue
        dex = DEX(data)
        for cls in dex.get_classes():
            if mode == "class" and cls.get_name() != needle:
                continue
            if mode == "class":
                for field in cls.get_fields():
                    print(name, "FIELD", cls.get_name(), field.get_name(), field.get_descriptor())
            for method in cls.get_methods():
                instructions = list(method.get_instructions()) if method.get_code() else []
                matches = mode == "class" or any(
                    needle in ins.get_output() and
                    (mode == "refs" or
                     mode == "calls" and ins.get_name().startswith("invoke-") or
                     mode == "strings" and ins.get_name().startswith("const-string"))
                    for ins in instructions)
                if not matches:
                    continue
                print(name, method.get_class_name() + "->" + method.get_name(), method.get_descriptor())
                offset = 0
                for ins in instructions:
                    print(f"{offset:04x}: {ins.get_name()} {ins.get_output()}")
                    offset += ins.get_length()
```

```bash
uv run --no-project --with androguard==4.1.4 python "$SAO_LAB/dex_trace.py" \
  "$IG_APK" class 'Lcom/instagram/ui/widget/textview/ComposerAutoCompleteTextView;'
uv run --no-project --with androguard==4.1.4 python "$SAO_LAB/dex_trace.py" \
  "$IG_APK" strings unsupportedGifError
uv run --no-project --with androguard==4.1.4 python "$SAO_LAB/dex_trace.py" \
  "$IG_APK" refs 'Lcom/instagram/common/gallery/Medium;'
```

Use class-descriptor/identifier needles for byte prefiltering. A complete formatted
method reference may not occur contiguously in raw DEX bytes; prefilter by its class,
then narrow the disassembly. For large classes, request individual methods in a
temporary inspector instead of repeatedly dumping the whole class.

The useful 447 chain was:

`composer → X.15C listener → X.14w weak sender reference → X.10V → X.10T delegate → X.9zT closure → X.0q6 DM controller`

Constructor/field inspection connected `X.0q6.A0O` to its editor container and
`X.0q6.A0t` to `UserSession`. The controller's unique static self-to-`DirectThreadKey`
getter led through its thread model. Production discovery follows these ownership
relationships structurally; it does not copy this entire name chain into a lookup.

Gallery sending required a separately verified contract:

| Inspected build | Controller / sender field | Sender / selection | Video callback / boolean tail |
| --- | --- | --- | --- |
| 435.0.0.37.76 | `X.5Ta.A13` | `X.5Ve` / `X.Dvg` | `FyJ` / 3 |
| 447.0.0.55.81 | `X.0q6.A12` | `X.10V` / `X.N2q` | `GHJ` / 3 |
| 451.0.0.53.80 | `X.01SB.A14` | `X.01TL` / `X.0S1g` | `GUG` / 2 |

`InstagramGalleryContract` validates owner-field finality/type, sender constructor
dependencies, selected-media constructor, complete `A0A` argument/return types and
delegate callbacks. It builds `Medium` with the actual clip duration, rotation and
dimensions. The sender executes Instagram's normal video preparation/upload flow.
The listener's verified restriction flag remains enforced. Unknown or ambiguous
contracts advertise no capability; a matching version string is insufficient.

For GIFs, trace enum `X.6A8.A06` (`ANIMATED_MEDIA`) through the stock local-file
factory `X.PHv.A00` and forwarding branch `X.PmM.A00` into `PendingMediaManager`.
The separate `InstagramAnimatedContract` records verified host/pending/upload/command
shapes for 447/451. This established a local-animation data path, not server support
for our reconstructed send command; the later 404 exposed that verification limit.

## 4. Configure the modern injected module

SAO uses `io.github.libxposed.api.XposedModule`, API **102**, without legacy
`XposedHelpers`. The APK's `app/src/main/resources/META-INF/xposed/` contains:

- `java_init.list`: `one.njk.sao.floating.nativebridge.SaoXposedModule`.
- `module.prop`: `minApiVersion=102`, `targetApiVersion=102`, `staticScope=true`,
  `autoHotReload=false`.
- `scope.list`: supported host packages, including `com.instagram.android`.

In Vector, enable SAO and scope Instagram. After updating injected code, force-stop
and reopen Instagram: replacing SAO's APK does not replace classes already loaded
in Instagram. Hook protocol revision **3**, reported by the running host, identifies
the loaded generation; SAO's unchanged APK version is not adequate evidence.

`onModuleLoaded` rejects system-server/unscoped processes. `onPackageReady` installs
`Instrumentation.callActivityOnCreate/Resume/Pause/Destroy` interceptors using
`hook(method).intercept(...)` and preserves `chain.proceed()`. Host initialization
uses the Activity's class loader. It observes the real
`setInputContentInfoListener(String[], listener)` and naturally created
`onCreateInputConnection(EditorInfo)`; it never manufactures a keyboard connection.
Lifecycle diagnostics are installed before adapter discovery so unsupported classes
do not masquerade as an inactive framework.

The photo keyboard handler decodes a bitmap and rejects GIF; advertising `video/*`
there cannot select a video pipeline. Gallery video support is independent and can
use the validated visible composer without keyboard focus. Instagram's built-in
GIF picker sends a provider identifier through `animated_media`; arbitrary local
bytes cannot be substituted for that identifier.

## 5. Preserve recipient, threading and file lifetime

The source of truth is under
[`app/.../floating/nativebridge`](../app/src/main/java/one/njk/sao/floating/nativebridge/):

- `InstagramThreadBinding`: bounded ownership traversal (128 objects, depth 5),
  weak references, exact editor ownership and unambiguous named thread-key getter.
  Hash verified parcel metadata in memory; copy a recipient key through its
  `Parcelable.Creator` when needed. Do not inspect message collections or log keys.
- `NativeEditorSession`: Main-thread, 120-second, one-use lease tied to Activity,
  editor, recipient identity and MIME capability; reject chat/editor replacement,
  detachment, stopped bridge, expiry and duplicate requests.
- `SaoXposedModule.HostClient`: UID enforcement, one in-flight insert; Main capture
  → Binder-worker staging/metadata → Main revalidation/dispatch → IO cleanup.
  Fresh selection waits at most 250 ms; Main dispatch phases have 3-second bounds.
- `InstagramGalleryContract`: request-owned sender/delegate; validate the original
  chat again before the later native video callback, permit it once and retain
  Instagram's real batch-permission result. Never replace the global sender.
- `InstagramMediaFile`: reject Main-thread staging; copy streams without changing
  bytes, cap files at the adapter's 512 MiB limit, copy time at 30 seconds and retained
  cache at 2 GiB. Delete unsubmitted files; retain submitted files for asynchronous
  host work. Opportunistic age cleanup happens during explicit Paste, not a timer.

Transport is AIDL: the host registers `INativeStickerClient` with SAO's exported
`NativeBridgeService`; SAO calls `snapshot`, `insert`, `setActive` and `observe`.
Registration verifies the allowlisted package against `Binder.getCallingUid()`;
host calls verify SAO's installed UID. The service itself has no signature manifest
permission. The separate `NATIVE_CONTROL` broadcast is signature protected.
An isolated empty bootstrap URI establishes package visibility, not media access.
Actual media uses a separate SAO FileProvider read grant; Binder carries a content
URI, not video byte arrays. Unknown send outcomes retain required access and are
never automatically retried. Focus recovery is event-driven with a 2-second bound.
An idle picker does not poll snapshots; inactive floating mode clears host bindings.

## 6. Reproduce structural and host-side tests

Use an account-free **owned emulator** for APK/class probing. Install the inspected
base/splits there; switching APK signers may require uninstalling Instagram on that
disposable emulator. Never uninstall the user's Instagram to make a probe install.

```bash
export SAO_EMULATOR='EMULATOR_SERIAL'
./gradlew -p tools/instagram-native-probe :app:assembleDebug
adb -s "$SAO_EMULATOR" install -r tools/instagram-native-probe/app/build/outputs/apk/debug/app-debug.apk
adb -s "$SAO_EMULATOR" shell am instrument -w \
  one.njk.sao.instagramprobe/one.njk.sao.floating.nativebridge.NativeInstagramProbe
adb -s "$SAO_EMULATOR" shell run-as one.njk.sao.instagramprobe cat files/native-report.json
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
cp app/build/outputs/apk/debug/app-debug.apk /srv/samba/share/
adb -s "$SAO_EMULATOR" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SAO_EMULATOR" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SAO_EMULATOR" shell am instrument -w \
  -e class 'one.njk.sao.floating.VideoDeviceTest#instagramPasteUsesVideoCapabilityAndOriginalBytesWithoutARecipientPicker,one.njk.sao.floating.VideoDeviceTest#instagramGifOnlyCapabilityConvertsCenteredMp4OnExplicitPaste' \
  one.njk.sao.test/androidx.test.runner.AndroidJUnitRunner
```

The probe compiles the production Java adapters via Gradle `Sync`; it is not a
parallel adapter implementation. It loads Instagram code into its own process and
constructs synthetic controller/session/key objects and recording delegates.
Both inspected 447/451 APKs passed **42 boolean checks**: reflection contracts,
recipient binding/copy, selected-video dimensions/duration, late binding recovery,
one-use callback, changed-chat rejection and GIF metadata/byte preservation.
It never invokes an authenticated uploader or Instagram's sending listener and
does not prove framework injection or server delivery. See the
[probe README](../tools/instagram-native-probe/README.md).

The final route fix passed **62 JVM tests** and the **2 focused picker tests** above.
The original-video test advertises both MP4 and GIF, then checks the exported MP4
bytes and MIME. The second checks conversion when only GIF is advertised. These
use test receivers, not the user's chat. Earlier conversion/adapter work also passed
debug/minified release builds, lint and targeted staging/lease/conversion tests.

## 7. Verify the loaded phone hook, then the asynchronous result

```bash
adb -s "$SAO_DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk
# After changes to injected code; this closes Instagram, so coordinate the restart:
adb -s "$SAO_DEVICE" shell am force-stop com.instagram.android
adb -s "$SAO_DEVICE" shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.instagram.android
export IG_LAUNCHER='COMPONENT_RETURNED_ABOVE'
adb -s "$SAO_DEVICE" shell am start -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER -n "$IG_LAUNCHER"
# Open the intended DM manually.
adb -s "$SAO_DEVICE" logcat -d -v threadtime -s 'SAO.NativeBridge:I' '*:S'
```

Open the picker over that DM and center the intended media. The observed sanitized
capability shape was:

```text
Instagram 447.0.0.55.81/<build>, hooks 3: editor=ready, video=ready, gif=ready,
mime=[image/gif, video/mp4, video/webm]
```

Natural keyboard capture can additionally expose JPEG/PNG. These changed-state
logs deliberately omit identifiers, lease tokens, URIs, filenames and queries.
Modern module logging may go to Vector's own log store; an empty generic SAO
logcat filter does not establish absent hooks. Likewise, an unavailable `su`
executable does not establish absent injection.

For send failures, resolve Instagram's current PID with `pidof com.instagram.android`
and inspect only that process around the user's explicit retry. During this session,
logcat was captured in Python memory, restricted to `PendingMediaWorker` and
`IgFunctionalErrorEvent`, and sanitized before output. For repeatable minimal
diagnostics, extract only `failureDomain`, `errorCode`, `sendAttemptChannel` and the
two retry booleans; never save raw logs or print whole error messages/stacks.
Resolve PID again after restart. Do not automate Paste or Send on a real conversation.

## 8. HTTP 404, incorrect assumptions and correction

The converted-GIF retry started `PendingMediaWorker`, then reported:

```text
failureDomain=http; errorCode=404; sendAttemptChannel=http;
shouldAllowAutomaticRetry=false; shouldAllowManualRetry=false
```

The failure also reported HTML where JSON was expected. Static tracing of 447's
`X.5ak.A04` found separate `animated_image_attachment`, `photo_attachment` and
`video_attachment` routes. **The actual failing request URL was not captured.**
This establishes a failure after native dispatch in the local-animation flow;
it does not establish which endpoint returned 404, why the server rejected it,
or that the generated GIF was invalid. Local GIF delivery is still unverified.

| Incorrect assumption made during this work | Correction |
| --- | --- |
| An adapter accepting GIF bytes also implemented MP4-to-GIF conversion. | Conversion was initially missing. Added Android sequential decode/Media3 processing and encoder-only gifski; tested actual output bytes, timing, cancellation and separate cache policy. |
| Existing inspected mappings covered the installed build; removing a version check would suffice. | Pulled the exact installed 447 APK and mapped its complete gallery/GIF shapes; retained signature/ownership checks. |
| The class probe was sufficient validation before preferring the GIF route. | It establishes construction and safeguards only. The authenticated retry exposed the later HTTP 404; delivery claims require an actual send/playback observation. |
| Prefer GIF whenever the native session advertises GIF. | This selected the failing local-animation path even when native MP4 was available, adding conversion and losing audio/HDR unnecessarily. Prefer original MIME, then supported video compatibility, then Instagram's explicit GIF-only fallback. |

The route correction is isolated in
[`NativeMediaFormat`](../app/src/main/java/one/njk/sao/floating/NativeMediaFormat.kt),
consumed by `PickerActivity`. It does not replay the failed message or repair the
GIF server contract. A **fresh user-initiated MP4 Paste** used the verified gallery
sender; the user confirmed send and playback. The sender thumbnail limitation is
recorded above and left unchanged at the user's request. Native MP4 support is an
internal Vector adapter, not a new public Instagram clipboard-video API.

Related: [delivery architecture](instagram-dm-delivery.md),
[encoder design and limits](video-gif-conversion-research.md),
[capability matrix](media-capabilities.md).
