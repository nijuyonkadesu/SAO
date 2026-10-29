# Instagram native Paste: investigation and verification

Recorded 2026-10-27–29. This is a reproducible account of the investigation, not a claim
that every structurally compatible Instagram build can deliver every format.

**Result:** the user confirmed that a fresh Paste sends the original MP4 and playback
works on Instagram 447.0.0.55.81 with Vector. A sender-side thumbnail was missing;
the user accepted this behavior pending any recipient-side problem. Recipient-side
rendering was not independently inspected. **Native animated-sticker delivery also
works:** the user confirmed the revision-12 video → WebP → native Riff route, with
the floating picker remaining responsive. This is Sticker + As GIF → Paste; it is
distinct from Instagram's provider-GIF message route. The latter returned HTTP 404
for locally uploaded GIFs and remains unsupported. Sections 8–11 retain those
failed experiments and their corrections; sections 12–14 describe the committed
implementation and its physical verification. Section 15 records the static-photo
implementation and its physical verification. Conversion-speed optimization
is deferred at the user's request.

The working animated-sticker chain is:

`explicit Paste → capture recipient/consume lease → stage granted URI on IO → decode original video or GIF → WebP → upload-only PendingMediaManager → positive upload FBID → 5av/Ys9 → stock Riff configuration once → registered sticker ID → stock native send/callback`

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
in Instagram. The running host reports its hook protocol revision; the original
MP4 verification used **3**, the rejected attachment experiment **4**, and the
uploaded-ID GIF candidate **5**, and the verified responsive animated-sticker path
**12**. SAO's unchanged APK version is not adequate evidence.

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
adb -s "$SAO_EMULATOR" install --abi arm64-v8a -r tools/instagram-native-probe/app/build/outputs/apk/debug/app-debug.apk
adb -s "$SAO_EMULATOR" shell am instrument --abi arm64-v8a -w \
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
adb -s "$SAO_DEVICE" logcat -v brief -T 1 \
  -s 'SAO.NativeBridge:I' 'SAO.InstagramGif:I' '*:S'
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

For current builds use those two SAO-owned tags only. Check the loaded hook
revision, encoder completion, upload completion and final native callback separately;
successful staging/upload is not delivery. Early investigation temporarily parsed
`PendingMediaWorker` / `IgFunctionalErrorEvent` in memory and emitted only bounded
domain/code/channel/retry fields; the owned callback diagnostics now supply those
fields without collecting raw Instagram logs. Never persist raw host logs or print
native exception messages, account/thread IDs, filenames, URLs or lease tokens.
Do not automate Paste or Send on a real conversation.

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

## 9. Follow-up: separate upload from GIF configuration

The user explicitly enabled GIF conversion in the picker and performed each Paste.
Request-scoped diagnostics established `File.exists()`, `canRead()` and a nonempty
staged GIF inside Instagram's cache. Both sub-megabyte and larger GIFs failed, so
size alone did not explain the failure. A large attempt exposed `X.f6l` wrapping
`X.pfV` (`HttpResponseException`), with the static reason “Failed to complete POST
request”; its exact HTTP status was not captured on that attempt. Do not infer a
native-client size ceiling from the separate Instagram business messaging API.

DEX tracing distinguished `PendingMediaManager.A0B(pending,false)` (**postMedia**)
from `A0E(pending,false,true)` (**uploadMedia**). The stock `X.owF` upload-only flow
registers `X.Plp` through manager `A0F`, handles terminal `FKq(pending)`, checks
`pending.A19()` and positive attachment FBID `A3O`, then unregisters through `A0G`;
`FaB` is the other callback. SAO now uses that event contract, a bounded request
timeout, and a fresh recipient/session check before configuration. No upload poll,
automatic resend, wake lock or source/credential change was introduced.

`X.PHv.A01` also constructs a local-file `X.5aa` command with `2lR` pending metadata,
`DirectAnimatedMedia`, and a `GifUrlImpl` file URI; `X.225.A0V` is simply
`Uri.Builder.build().toString()`. This corrected the assumption that every
`config_animated_media_message` must originate from a provider catalog. However,
reconstructing that command still produced unavailable-media errors after a
successful upload. Replacing it with `X.AOq` after upload produced a Sending stall,
without reaching `X.5ak.A04` in the captured attempt. Neither experiment establishes
working delivery. Do not manufacture a GIPHY/Tenor ID: `DirectAnimatedMedia` checks
the `tenor:` ID prefix, but adding a prefix does not register arbitrary media.

The configurator candidate registers only a completed SAO GIF upload, keyed by the native
client context and matching `UserSession`/pending key. A modern libxposed interceptor
at `X.5ak.GiQ` routes that owned request to `Ad2(callback,command,pending)`, which
delegates to native `A04` and its explicit `animated_image_attachment`/FBID branch.
Unregistered requests use `chain.proceed()`. This avoids the intervening generic
image-processing path. A subsequent physical retry confirmed upload completion,
positive attachment readiness and the exact configured route
`direct_v2/threads/broadcast/animated_image_attachment/`. A further retry captured
terminal `code=404`, `domain=SEND_FAILURE_DOMAIN_HTTP`, `channel=http`, with both
retry flags false. This connects the 404 to this attachment request, unlike the
initial unlocated failure. Successful blob upload is not evidence that the message
endpoint accepts that blob. Registration is one-use and expires.
If a registered command reaches the handler with a different session/pending key,
SAO completes native `Wbb.FdI` with stock `LRk.A0X` (invalid mutation parameters),
whose automatic/manual retry flags `A0D/A0E` are verified false. Silently consuming
that command would itself leave the optimistic message in Sending.

The isolated probe activates the dispatcher descriptor **without installing an
Xposed hook**. It verifies actual 447 classes, completed-upload registration,
different-account/unregistered-request rejection, one-use claiming and a recording
native failure callback on rejected context; it never
calls `Ad2`, uploads, or sends. Its 49 boolean checks passed. A debug-only,
request-owned `Wbb` proxy now forwards every native callback unchanged and records
only numeric `LRk.A05`, enum `A01`, allowlisted channel `A07`, and `A0D/A0E` retry flags at terminal
`FdI`; it never stringifies the native error or logs the message ID. This diagnoses
configuration failures independently of the upload worker's logs. Keep GIF delivery
unverified until a fresh user Paste sends and plays.
No chat IDs, client-context IDs, attachment FBIDs, filenames, media URLs, account
credentials or raw response bodies belong in this document or committed logs.

## 10. Uploaded-ID GIF candidate (failed on the phone)

The actual 447 GIF command is `5aa`; `5ab.GiQ` schedules `UlA` case 1, which calls
`Pz6.A05` and `direct_v2/threads/broadcast/animated_media/`. That request sends
`DirectAnimatedMedia.A06` as `id`; it does not send the local GIF URL or pending
metadata. A generated local ID therefore does not identify the uploaded blob in
that HTTP route. The forwarding factory's local metadata has a different role in
the native encrypted dispatcher; copying its shape did not establish an ordinary
local-GIF send contract.

The revision-5 candidate creates `5aa` only after upload succeeds, using actual `A3O`
as its media ID, with local `GifUrlImpl` for preview and copied recipient metadata.
`InstagramGifCommand` reproduces the inspected `Ayf` initialization; no `Unsafe`,
provider-ID fabrication, public hosting or automatic resend is involved. The
interceptor at `5ab.GiQ` verifies request ownership and calls `chain.proceed` with a
forwarding completion observer. It no longer redirects into `5ak.Ad2`. The phone
again confirmed upload success, followed by native `animated_media`
dispatch and terminal HTTP 404 with both retry flags false. Substituting the real
upload FBID therefore did not repair delivery. The updated isolated probe passes
51 boolean checks, including refusal before upload and preservation of the actual
uploaded ID; those checks still do not prove server support. Whether rejection is
caused by missing resource registration or another server constraint is unverified.

The separate native creation flow in `owF` performs upload **then**
`ODp.A00` / `IGDirectConfigureCutoutStickerMutation`
(`xig_direct_configure_cutout_sticker`), with `media_id`, creation-flow and cutout
metadata. `mSG.EAm` requires a returned sticker ID and reports `missing_sticker_id`
otherwise. This is native custom-sticker creation, not proof of arbitrary local-GIF
message support. No registration mutation was invoked. Changing Media/As GIF into
sticker creation needs a product decision; neither public hosting nor a fabricated
provider ID was attempted.

Related: [delivery architecture](instagram-dm-delivery.md),
[encoder design and limits](video-gif-conversion-research.md),
[capability matrix](media-capabilities.md).

## 11. Native animated stickers: working path and corrected assumptions

The user authorized native sticker creation after both local-GIF message APIs
returned 404. Tracing `VideoCutoutStickerUploader.A00` / `owF` found its actual
format: **animated WebP**, uploaded with `ANIMATED_MEDIA`, truthful `image/webp`,
pending `A0I=6`, Riff/direct flags and `render_as_sticker`. A raw GIF upload followed
by `ODp.A00` / `IGDirectConfigureCutoutStickerMutation` failed with `X.BEM` code
**1675030**. That is a generic query failure, not proof of a URI, size or permission
problem. The initial adapter also used `a06.BCV` (execute-and-subscribe); stock
`215.A1O` uses **BCR** plus the `6mj.A00(request,session)` callback executor.

Revision 8 used Instagram's installed `GifImage` / `GifFrame` and `WebPEncoder`.
`MediaCompositionToWebpEncoder` and `owX` establish `prepare(w,h,true)` and
`addFrame(RGBA,durationMs,0,0,0,0)`. Both JNI methods return **1** on success;
the isolated codec test disproved a zero-success assumption. GIF composition
honors frame offsets, transparency and disposal-to-background/previous. Full-size
bitmaps/native frames are disposed and `HybridData.resetNative()` is guarded by
its native `mDestructed` atomic. No codec library was copied into SAO.

The phone then **successfully uploaded WebP and returned a registered sticker ID**.
The following send failed with client code **6**, channel `na`, no automatic retry.
`NcK` maps this to pending-media/configuration failure. This exposed a second wrong
assumption: **`Ys9.A0F` is a media ID awaiting configuration, not a registered sticker
ID.** `5aw.GiQ` → `oxF` case 3 → `m9A.A06` → `I5o.A00` performs the same GraphQL
configuration. Supplying the already-registered ID asked Instagram to configure
it again. The stock failure snackbar belongs to that duplicate operation.

Revision **9** removes SAO's redundant GraphQL adapter. After `PendingMediaManager`
reports successful upload and a positive `1jR.A3O`, SAO queues native `5av`
(`configure_riff_sticker_message`) with that **upload ID** in `Ys9.A0F`.
`I5o.A00` configures once; `5aw.A00` consumes its resulting `ch4.A02`, then
`Pz6.A06` sends **that registered sticker ID** through `JdK.A00`. The owned
`5aw.GiQ` interceptor only verifies session/request ownership and forwards the
native callback. It does not replace the sender. The phone returned successful
native completion and the user confirmed that the animated sticker sends/works.

Keep the two IDs distinct even though both are opaque strings. An upload success
alone proves no message delivery; a constructor probe alone proves no authenticated
API behavior. Never substitute a fabricated Tenor/GIPHY ID or retry an uncertain
native send. Changed recipients and failed/expired uploads cannot queue a sticker.
Only explicit **Paste** in **Sticker** mode creates a native sticker; Copy remains
original media. The separate **DM** fallback still attaches a normal video.

The account-free probe loads the installed ARM64 codecs and Superpack SoSource
(`assets/lib/libs.spo`) into probe-owned writable directories. Its synthetic GIFs
verify dimensions, variable durations, alpha, and both disposal modes; Pillow
independently checks their WebP output. No Instagram libraries, account state,
raw API responses, media URLs or identifiers enter the repository. Resource-backed
failure feedback is loaded on IO and posted on Main only for the explicit request.
Obsolete global HTTP-builder/old-GIF-configurator diagnostics were removed.

## 12. Conversion latency, busy picker and remaining work

The first working path did two encodes: video → gifski GIF → native WebP. The user
reported slow preparation, some failed MP4s, and an icon that would not reopen
while processing a longer/higher-resolution item. `NativeBridgeRegistry` also held
one global operations mutex across synchronous `remote.insert`; `FloatingService`
awaited that same mutex before showing the picker. This was coroutine waiting,
not evidence that the Binder call ran on Main, but it still blocked picker access.

Revision **10** passes original video bytes under a private sticker-rendition MIME
and encodes decoded RGBA frames **directly to WebP** in Instagram's process.
SAO's shared `VideoFrameDecoder` uses sequential `MediaCodec` decoding and the
existing Media3 GL rotation/HDR-to-SDR pipeline; it emits one frame at a time with
presentation timestamps. One owned RGBA lookahead buffer supplies native WebP
frame durations. There is no intermediate GIF, gifski quantization, added dependency,
blind byte target or silent resolution/frame-rate reduction. `As GIF` still means
animated-sticker delivery to the user; Instagram's accepted transport format is WebP.

Bridge operation gates are per client. Selection uses a nonwaiting gate and does
not re-arm an in-progress recipient lease. Another explicit Paste is rejected
instead of queued; picker browsing/Copy and another app's capture stay available.
An event-driven in-progress state explains the temporarily unavailable Paste.
No idle timer, polling, wake lock or background media scan was introduced.

Memory, decode and conversion-deadline failures receive separate localized
messages. Wire result `PREPARATION_FAILED` means no send side effect and specific
feedback was already presented; SAO must not overwrite it with a generic rejection.
Unknown remains terminal and never triggers replay. The worker owns its codec
resources, is background-priority, and joins
before cleanup; interruption is preserved during callback-thread teardown.
The native format/rotation/frame-memory limits remain explicit. The direct MP4
and WebM fixtures each preserved **128×192, 24 frames, 2000 ms**. A synthetic
1080p H.264 fixture also preserved **1920×1080, 24 frames, 2000 ms**; outputs were
independently verified with ffprobe/Pillow. All 59 native probe checks passed.
Targeted device checks cover GIF bytes/colors/timing/rotation/cancellation, bounded
staging, stalled handoff access and client replacement during an ongoing operation.
A replaced client cannot clear its replacement’s operation or editor state. Physical
verification exposed the two integration regressions below; fixture checks alone
do not establish injected-process correctness or phone latency.

Static-photo Sticker mode is tracked separately in section 15; it must not silently
send a normal attachment. Conversion-speed optimization, source-independent As GIF
controls and size feedback are deferred; the user accepted the working video
path's latency.

## 13. Injected Media3 resource context (revision 11)

Revision 10's standalone encoder used its own app context, so its Media3 shader
resources resolved correctly. In the injected process the same code received
Instagram's context: **resource IDs belong to an APK**, and a Media3 raw-shader ID
could resolve to unrelated Instagram resource bytes. An explicit foreign-context
negative control reproduced invalid shader input (`GlProgram.addShader`); on the
emulator its invalid GL error text also triggered CheckJNI's UTF-8 assertion.
Do not run that unsafe negative control routinely. The regression probe instead
compares the raw shader bytes from the correct/foreign Resources without compiling
foreign bytes as GLSL.

`NativeModuleResources` obtains SAO's resource package from the already-validated
FileProvider authority using `createPackageContext(package,0)`, without starting
its Application. A small ContextWrapper overrides Resources/AssetManager and
retains that wrapper through `getApplicationContext()`. Identity, storage and
services still belong to Instagram's UID. Media3 therefore gets its own shaders
without attempting to use SAO's private storage as Instagram. This is separate
from Instagram's installed decoder/encoder ClassLoader. The corrected probe
also checks the retained host identity; actual phone logs now confirmed direct
video conversion completed. No private filenames or URI/error messages are logged.

## 14. Accepted recipient versus an ephemeral editor lease (revision 12)

Comparing the earlier working export flow with revision 10 isolated another
behavioral change: heavy conversion formerly finished in SAO before handoff;
now it ran after initial native acceptance. `NativeEditorSession` nevertheless
validated the temporary focus/token again afterward. Reopening SAO could remove
focus, and normal listener reconstruction allocated a new Binding. The animated
adapter also compared Binding **object identity**, rejecting an otherwise identical
account/controller/recipient after its wrapper was recreated.

Only preparations declaring `hasCapturedRecipient()` now reserve/consume their
one-use lease at acceptance, before IO. A request-owned CapturedContent keeps its
original URI/MIME/token/request ID, recipient identity, activity/editor and scope
epoch. Final dispatch checks those captured values, lifetime, actual recipient
and handler availability; it does not require renewed foreground focus or the
newly generated UI token. Preparation and insertion each remain one-use. Scope
reset, confirmed chat change, account/controller change and editor replacement
still reject. Keyboard/other unbound preparations retain their existing focused
editor checks. A temporary unresolved metadata snapshot revokes UI capabilities
without treating it as proof of a changed accepted recipient.

The Instagram guard compares actual account/controller/recipient metadata instead
of Binding allocation identity. The real-class probe checks recreated listeners
and changed-account rejection. Targeted instrumentation checks token renewal,
covered-host dispatch, replay rejection, chat/scope changes and existing keyboard
lease rules. The probe passes **65 boolean checks** and the five targeted lease
tests pass. Explicit failed dispatch diagnostics contain bounded reason codes;
preparation diagnostics contain exception types and code-method/line frames,
never exception messages, private paths, media URLs or IDs. Physical revision-12
retries completed native upload/configuration/send without a native error for both
tested videos, and the user confirmed delivery. The picker remained responsive.
Conversion latency remains a separate follow-up; no speed claim follows from the
correctness checks.

## 15. Static-photo stickers (revision 13)

`InstagramStickerFormat` now advertises private still-image renditions only when
`ImageDecoder.isMimeTypeSupported` accepts the source MIME on that device; this
capability set is computed once. The picker requests them only for **Sticker**
mode. The third button remains **As GIF**, disabled for still photos; static
stickers require no animation toggle. Media mode continues through the existing
photo handler. Neither Copy nor source uploads use this conversion.

`InstagramAnimatedContract.prepare` stages JPEG/PNG and other supported stills on
IO, then calls `InstagramStickerEncoder.convertImage`. Platform `ImageDecoder`
bakes EXIF orientation into full-size software pixels, rejects animated input
instead of flattening it, and explicitly converts to sRGB. Straight alpha avoids
rounding RGB through a premultiplied decode/encode cycle. The encoder writes
`WEBP_LOSSLESS` directly to a request-owned file; its quality argument **75 is
compression effort**, not a lossy visual-quality target. No square canvas, padding,
background replacement, resize or arbitrary byte target is added. The existing
WhatsApp/Telegram static encoder deliberately produces constrained 512×512 assets,
so using it here would impose unrelated receiving-app requirements.
[ImageDecoder contract](https://developer.android.com/reference/android/graphics/ImageDecoder#setUnpremultipliedRequired(boolean)),
[WebP lossless contract](https://developer.android.com/reference/android/graphics/Bitmap.CompressFormat#WEBP_LOSSLESS).

Existing WebP is validated/staged byte-for-byte, preserving animation as well as
stills. Both paths reuse the verified upload-only/Riff command and captured
recipient ticket; the native wire metadata remains `ANIMATED_MEDIA`, `image/webp`,
format **6**, with `render_as_sticker`. No second configurator, URI publication,
new dependency or account/credential access is introduced. Before bitmap allocation,
dimensions must fit WebP's 16,383-pixel edge and `width*height <= maxMemory/64`;
the existing owned worker, cancellation/60-second bound and typed failure feedback
apply. Original/staged temporary files follow existing cleanup rules.

The account-free 447 probe passes **80 boolean checks**, including a 64×48 PNG
with alpha 0/128/255, EXIF-6 JPEG rotation from 32×24 to 24×32, unchanged source
bytes, unpremultiplied decoded-pixel equality, exact WebP pass-through and native
pending metadata. Comparing premultiplied PNG/WebP decoder output initially showed
small rounding differences; compare straight RGBA when testing lossless encoded
content. Failed boolean checks now fail instrumentation rather than merely
appearing as `false` in a successful report. These checks invoke no uploader or
sender; physical verification is separate.

The user initially saw “image format unavailable” for every JPEG after installing
the new SAO APK. Instagram's process still predated that install: updating SAO
had not replaced its injected classes or the old private-rendition capability set.
The picker also incorrectly mapped **every unavailable static Sticker action** to
an unsupported-format message. Do not infer failed conversion from a disabled
action; no conversion has started there.

After an authorized force-stop/relaunch, scoped `SAO.NativeBridge` logs confirmed
**hooks 13**, `gif=ready` and `image/jpeg.igsticker` in the captured editor's MIME
set. The user's explicit JPEG Paste encoded **640×480** lossless WebP, completed
upload/configuration/send with `nativeError=false`, and the user confirmed static
stickers work. The picker now reserves the format warning for an unrecognized
source MIME; supported stills use the actual hook/session/handler diagnostic,
including an explicit **force-stop/reopen** instruction for older hooks. No
conversion or native sending changes were required for this incident.
