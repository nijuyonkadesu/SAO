# Instagram DM delivery

Inspected 2026-10-13: Instagram **451.0.0.53.80**, ARM64 version code **385713082**.
The original base and density split are signed by Meta, with Instagram's legacy
certificate in the verified signing lineage. APKs and proprietary decompilation
remain outside the repository. No logged-in account, real conversation or media
send was used during emulator verification.

## Non-root DM attachment

SAO recognizes `com.instagram.android` through foreground detection and the existing
recipient list. Selecting an item highlights and copies immediately. **DM** hands it
to Instagram's own recipient picker. GIF/MP4/WebM retain their original bytes and
MIME; static images use SAO's existing JPEG export.

Instagram exports `com.instagram.share.ADD_TO_DIRECT_MESSAGING` for `image/*` and
`video/*` through its `DirectExternalMediaShareActivityPhoto` and `…Video` aliases.
They are disabled in the manifest and become enabled after Instagram's first launch,
including in the signed-out emulator. Android's resolved ActivityInfo retains the
manifest default; resolution applies the effective component enabled state. SAO
checks that the receiver resolves, is exported, belongs to Instagram and requires
no additional permission. It resolves again using the exported file's actual MIME
on an IO dispatcher when DM is pressed.

The intent contains a FileProvider stream, matching ClipData and a temporary read
grant. There is no fallback to Feed, Stories or a generic Instagram share chooser.
Missing receivers leave Copy available and display an initialization hint. The
recipient and final Send decision remain with the user in this attachment flow.

## Optional LSPosed Paste

The modern module targets **libxposed API 102.0.0**. The maintained JingMatrix
LSPosed fork's latest verified release is [Vector 2.2](https://github.com/JingMatrix/Vector/releases/tag/v2.2),
which supports API 102. There is no legacy Xposed dependency. Install SAO, enable
its module, scope it to **Instagram**, and force-stop/reopen Instagram after updating.
Open a DM, focus its text composer, and then open the floating picker.

With a verified native session, a still selection enables **Paste**. Pressing
this button explicitly invokes Instagram's own keyboard-image handler in the
original DM. That handler enters Instagram's sending pipeline directly; there is
no separate attachment preview or second Send press. The accessibility description and inline hint state this behavior. The visible
button uses the same Paste label as other apps. Selecting/copying alone never
invokes it, and it works without SAO's accessibility Paste assistance or changing
keyboards. Instagram decides whether the request actually uploads/delivers.

The inspected keyboard callback accepts PNG/JPEG and explicitly rejects GIFs. SAO
advertises only the supported static-image capabilities for this keyboard route
and exports JPEG. A separate gallery adapter enables MP4/WebM Paste as described
below. A separate local animated-attachment adapter now offers GIF Paste for its verified
447/451 contracts; unsupported contracts keep **DM** recipient selection. Instagram Sticker remains disabled. A connected module without a validated
editor lease also leaves Paste disabled.

### Native video insertion

A verified gallery sender adds `video/mp4` and `video/webm` to the session.
When the GIF route is unavailable, explicit **Paste** or **DM** sends the selected video directly to the original DM through that sender,
without opening the recipient picker. It does not pass a video to the bitmap keyboard
handler. No video is sent by selecting, copying or browsing. Upload/server acceptance
still belongs to Instagram; authenticated video delivery through Vector needs a phone
check. Unsupported contracts and formats keep the DM recipient-picker route.
There is no exact-version gate: class ownership and constructor/field/method shapes
control availability. Verified contracts cover 435.0.0.37.76, 447.0.0.55.81 and 451.0.0.53.80;
unknown shapes remain unavailable. The 435/447 senders have an additional boolean argument
and different selected-media/callback types, so removing a version gate alone was
insufficient to support it.

`InstagramGalleryContract` isolates the inspected obfuscated gallery contract
from the structural keyboard-photo adapter. It checks the controller's sender field,
method/constructor signatures and selected-media model. The controller must still own
the leased editor and the same verified thread identity. Missing or changed contracts
do not advertise video. Videos do not become stickers.

The host's Binder worker copies the granted URI into Instagram-owned cache and reads
its real MP4/WebM duration, dimensions and rotation. The original bytes are preserved.
Preparation rejects Main, malformed or mislabeled files, clips over 512 MiB, a 30-second copy
deadline or a full 2 GiB adapter cache. Old adapter files are cleaned only during an
explicit Paste; recent submitted files remain available to Instagram's asynchronous
uploader. Unsubmitted/invalid files are deleted. There is no background cleanup job.

### Local animated GIF insertion (2026-10-27)

The inspected 451 keyboard handler explicitly consumes/rejects `image/gif` with
`unsupportedGifError`. Its regular GIF catalog request posts a GIPHY/Tenor ID to
`direct_v2/threads/broadcast/animated_media/`; adding a local URL to that model does
not upload the file. SAO does not use either route for local GIFs.

The forwarding pipeline provides evidence of a real local animated upload:
`X.0Nq2` creates `ANIMATED_MEDIA` pending media from a file, selects the host's
modern/legacy upload mode, and registers it with `PendingMediaManager`. The image
uploader handles that animated type, and the media-send handler has a distinct
`animated_image_attachment` endpoint for its uploaded attachment ID. The production
`InstagramAnimatedContract` isolates these verified signatures. It submits the
local animated pending attachment through Instagram's ordinary media command,
without fabricating a GIF provider ID, adding an external upload service, or using
the bitmap keyboard callback. The pending attachment is not marked as a sticker.

Explicit Paste captures a parcel copy of the original thread key on Main after
validating the selection lease. The Binder worker stages and validates the GIF,
builds native metadata and initializes native services. No upload or send is
started during this preparation. Main revalidates the lease, current controller,
session and recipient before registering/uploading/dispatching once. Rejected,
expired, detached or switched-chat preparations delete their files. Once native
upload starts, files remain available for asynchronous processing and sends are
never retried automatically.

`InstagramMediaFile` shares bounded file preparation between GIFs and videos.
Content URIs, local-file streams and in-process byte-buffer streams use the same
copy path; media bytes never travel in Binder arrays. Header and framework animation
validation run off Main, with a 1-pixel validation decode while retaining the original
dimensions and unmodified file. The existing 512 MiB/file, 2 GiB/cache and copy
deadline limits apply. Cleanup also counts the old video cache and runs only on
an explicit Paste. This native adapter only stages existing GIF bytes; video-to-GIF
conversion belongs to the host export pipeline described below. It adds no idle polling.

The isolated real-class probe verifies the 447/451 reflection contracts, GIF capability
gating, copied recipient key, actual animated pending metadata/file path, media
command recipient and byte preservation. It never initializes an authenticated
uploader or invokes a native send. Device tests cover URI/byte-stream equivalence,
invalid input, copy limits, partial-file cleanup, off-Main work, submitted-file
lifetime and shared video staging. **Authenticated GIF upload/delivery through Vector
still needs a rooted-device check.** GIF capability is absent for unverified shapes,
including the older 435 contract; its existing photo/video routes remain available.

The host verifies the lease before preparation, revalidates it on Main afterward,
and consumes it before invoking the gallery sender once. A changed chat, editor,
activity, capability, expired lease or stopped bridge aborts dispatch. A single in-flight
host insertion prevents concurrent preparation; unknown/rejected sends are never retried.

Instagram performs further asynchronous clip processing after initial dispatch. SAO creates
a request-owned native gallery sender with a guarded delegate, leaving the app's original
sender untouched. Immediately before the native video send callback, it checks the original
binding/thread again. A changed/detached chat or stopped bridge drops the callback, and the
guard consumes it even on rejection. A late duplicate cannot send a second message. Gallery
contract discovery also runs on the host IO executor; late capability updates need no
fabricated or restarted keyboard connection.
Gallery capability also works through the visible DM composer without keyboard focus;
an existing natural keyboard connection retains photo support alongside it.

An early unresolved listener no longer stays disabled after its bounded discovery
attempts. Each explicit icon press may retry that same visible composer's binding once;
ordinary refreshes retain their existing limits. A probe using the real 435 classes
reproduced the unresolved-listener failure before this change and verifies recovery
without installing a new listener. Both inspected contracts run the same check.

Unavailable video Paste now reports the actual stage inline: gallery loading/unsupported,
unresolved controller/thread, restricted media, missing sender, or selection timeout.
The report includes the host version/build and distinguishes older loaded SAO hooks
from the updated APK. GIF has separate guidance. These diagnostics use the existing
bridge snapshots and add no polling or persistent logs containing conversation data.

### Adapter and session validation

`InstagramComposerPolicy` requires the named `ComposerAutoCompleteTextView` and exact
DM resource role `row_thread_composer_edittext`. It observes the editor's native
`setInputContentInfoListener` configuration, rather than substituting its listener.
`InstagramThreadBinding` follows a bounded set of captured closure fields and weak
references to the DM controller. The controller must own this exact editor and expose
Instagram's named `DirectThreadKey` through a unique getter shape. Unknown, ambiguous,
empty or replaced metadata fails closed. This avoids hardcoding obfuscated class,
field and sender-method names in the module. Discovery follows weak sender references and Kotlin controller delegates, skipping media-store branches. It never walks message collections,
databases or account sessions. Only a hash of the key's verified parcel representation
is retained in process memory; conversation IDs and contents are never logged or stored.

The shared `NativeEditorSession` captures the host's naturally created final
InputConnection. It does not manufacture a keyboard connection. A lease is bound to
the original activity, editor, hashed conversation identity and accepted MIME types;
it expires after two minutes and is consumed before one dispatch, including a
rejected/unknown result. Natural IME reconnects may replace the wrapper while retaining
a lease for the same editor and conversation. Switching chats, replacing/detaching
the editor, clearing/replacing its media listener, or changing capabilities revokes it.

Both Binder directions check the installed package/UID. SAO grants its own FileProvider
URI to the scoped host, closes the picker so that the original chat can regain focus,
and waits for focus using bounded IO polling. It dispatches once and never retries a
send or falls back to another recipient. Main-thread session validation runs immediately
before invoking the native handler. Subsequent decode/upload/sending work belongs to
Instagram; a true return means that its handler accepted the request, not confirmed
server delivery. Binder discovery returns cached copies and schedules coalesced Main
refreshes; UI rendering does not wait on Binder or network calls.

These internal Instagram contracts can change between builds. Unsupported editor or
controller shapes disable the native action while preserving non-root DM attachments.

## Why non-root Paste stays unavailable

The inspected composer delegates context-menu Paste to its superclass and a text-paste
listener. Its separate keyboard path wraps InputConnection for rich content. The
[Android keyboard API](https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard)
belongs to the active IME, so an overlay cannot invoke it through an unchanged keyboard.
LSPosed gives SAO access inside the scoped host, allowing the separate native action.

An isolated API 35 probe with the real installed composer and recording listener
verified that PNG/GIF/WebM/MP4 clipboard URIs are visible but context-menu Paste yields
zero media callbacks. A keyboard `commitContent` call yields one callback. A successful
accessibility Paste return therefore does not establish an inserted attachment.

## Video Paste investigation (2026-10-16)

Rechecked the installed 451.0.0.53.80 APK. The keyboard callback schedules a worker
that reads the content URI into a `Bitmap`, draws it onto a white bitmap and passes
that bitmap to the photo sender. This was confirmed in both decompiled Java and
the corresponding DEX instructions. Adding `video/*` to SAO's current keyboard
capabilities would still enter this bitmap path; it would not select Instagram's
video upload pipeline. The current restriction is therefore intentional for that
adapter, rather than proof that Vector cannot support videos.

The same DM controller owns a separate gallery sender. Its selected-media objects
wrap Instagram's named `com.instagram.common.gallery.Medium` model and distinguish
images from videos. The video worker reads the local clip's metadata, constructs
`ClipInfo`, applies the host's clip limits and forwards it to its native DM media
pipeline. Its controller delegate leads back to the original composer controller
that SAO already validates. This makes an original-chat video adapter plausible
without opening the external DM recipient picker; authenticated delivery has not
been established by this inspection.

A video adapter would need its own verified gallery contract, host-readable clip
preparation on IO, correct video metadata and validation of the original chat again
after preparation. It must preserve the one-use dispatch and no-retry behavior.
The selected-media wrapper and sender entry points are obfuscated and differ from
the keyboard contract, so unsupported versions or formats must keep the current
DM fallback. This investigation led to the versioned MP4 adapter above. The isolated
probe still never invokes an authenticated sending listener.

## Video-to-GIF Paste

When a bound Instagram conversation advertises native GIF uploads, Paste converts
MP4/WebM through Android's decoder and the host's gifski encoder, then gives the
resulting `image/gif` URI to that same recipient-bound upload adapter. This capability
is independent of the gallery video sender. The inspected 447/451 contracts read their
UserSession from the verified DM controller and still respect media restrictions,
chat changes and one-use leases. Unknown contracts, including the older 435 GIF
upload shape, remain disabled rather than assuming compatibility.

Conversion is off Main, cancellable and cached separately. It preserves dimensions
and frame timing within GIF's 10 ms timebase; GIF has no audio/HDR and can be much
larger than MP4. Unsafe full-resolution or oversized conversions fail explicitly.
The DM button retains the original-video attachment route when Paste uses GIF.
[Encoder, measurements and resource limits](video-gif-conversion-research.md).

Generated-media tests verify actual GIF bytes, RGBA row padding, colors, orientation,
duration, source/cache separation, cancellation cleanup and GIF-only MP4 Paste.
The real-class probe also verifies that GIF remains available without a gallery
sender. It never invokes authenticated upload/send; delivery still needs a rooted
device with Vector and the compatible native client.

### Installed 447 build (2026-10-27)

The device's 447.0.0.55.81 APK does not contain the 451 GIF controller/upload
classes. Its former gallery names also resolve to different classes. This made
video Paste unavailable before conversion, despite a working stable photo hook.
SAO now checks a complete additional 447 contract for both gallery videos and
local GIF uploads. Contract descriptors separate controller, pending-media,
upload and dispatch symbols; verified signatures, enum roles and ownership still
control activation. No version-number bypass or guessed MIME field is used.

The exact 447 APK and original 451 APK each pass 42 native-class checks, including
GIF availability without the gallery sender. Ten focused device tests cover
conversion, explicit Paste, photo/video coexistence and recipient leases. Actual
framework delivery still needs the physical device; no test dispatches a message.
Hook revision 3 lets the picker identify an older module still loaded in Instagram.
`SAO.NativeBridge` logcat entries record changed version/readiness/MIME capabilities
only during explicit selection/observation, without recipient/session identifiers,
filenames, search text or media contents.

## Verification and limits

The production adapter was compiled directly into the reproducible
[isolated probe](../tools/instagram-native-probe/README.md). With real Instagram classes
from both inspected versions and synthetic controller/key metadata it verified the DM role, native controller
binding, mutable thread changes in the same editor, replaced-editor rejection,
cleared-listener rejection and PNG/JPEG keyboard capabilities. Gallery checks verify
MP4/WebM capability without a version-string gate, missing-sender/changed-contract rejection and real selected-video objects
with matching duration and dimensions. An unmodified native
InputConnection delivered one `commitContent` callback to a recording listener.
The probe never invokes Instagram's actual sending listener.

Device instrumentation covers one-use leases, wrong authority/MIME, paused windows,
chat swaps, expiry, detached editors, IME reconnects, ambiguous controllers, empty keys,
UID enforcement, exact-MIME dispatch and no retry after rejection. Picker tests check
that verified native photo/MP4 sessions enable Paste independently of accessibility;
highlight/copy never sends, and unadvertised motion formats stay disabled. Existing receiver tests verify
JPEG/GIF/MP4/WebM routing and actual byte hashes under another receiving UID.
Recovery tests also cover late capability changes without rotating the picker token,
explicit reopening after a revoked draft without reconnecting the keyboard, and
preserving earlier attachment grants after a subsequent rejection.

These checks validate SAO's adapter, safeguards, UI and transport. Framework injection
and authenticated DM upload/delivery still need the user's rooted device. No emulator
account or message send is required by the automated suite. Generated device logs and
screenshots remain local under `diagnostics/`; the probe source is versioned.

References: [Android rich content](https://developer.android.com/develop/ui/views/receive-rich-content),
[Android image keyboard API](https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard),
[modern module API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API),
[Chromium's Instagram DM target](https://chromium.googlesource.com/chromium/src/+/HEAD/chrome/browser/share/default_ranking_android.cc).

## Availability fixes (2026-10-14)

The floating service now grants scoped clients access to an isolated empty bootstrap
URI so Android package visibility permits binding to SAO. Caller UID enforcement and
separate per-media grants remain unchanged. Lifecycle hooks and diagnostics are installed
before per-version composer initialization; missing classes do not silently hide bridge
activation. The picker can distinguish inactive scope/binding, unsupported composer,
missing editor, unsupported MIME and pending DM metadata, and includes host app version.

Listener discovery is bounded to closure/controller relationships, skipping unrelated
media-store graphs. Initial discovery retries at most twice after 250/1000 ms; an
already resolved binding is not rescanned. A late-installed listener requests a natural
Android input reconnection, and a listener configured before capture can be recovered
from its exact native field. The existing natural connection may await initial metadata;
changed established conversation identity still revokes it. JPEG/PNG remain the only
supported native formats. The isolated real-class probe still passes; authenticated
Instagram send and framework injection require physical-device verification.

The module now gates editor work on the floating service's active state. Stop clears its
session/controller bindings; reactivation can recover a listener configured while SAO
was inactive. The existing JPEG/PNG keyboard send path remains unchanged. Activation
uses a sender-permission-protected broadcast plus UID-checked Binder control, without
continuous native polling while floating is stopped.
