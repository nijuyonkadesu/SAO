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
advertises only the supported static-image capabilities and exports JPEG for this
route. **Paste** stays disabled for GIF/video; **DM** remains their attachment
path. Instagram Sticker remains disabled. A connected module without a validated
editor lease also leaves Paste disabled.

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

## Verification and limits

The production adapter was compiled directly into the reproducible
[isolated probe](../tools/instagram-native-probe/README.md). With real Instagram classes
and synthetic controller/key metadata it verified the DM role, native controller
binding, mutable thread changes in the same editor, replaced-editor rejection,
cleared-listener rejection and PNG/JPEG-only capabilities. An unmodified native
InputConnection delivered one `commitContent` callback to a recording listener.
The probe never invokes Instagram's actual sending listener.

Device instrumentation covers one-use leases, wrong authority/MIME, paused windows,
chat swaps, expiry, detached editors, IME reconnects, ambiguous controllers, empty keys,
UID enforcement, exact-MIME dispatch and no retry after rejection. Picker tests check
that verified native still-photo sessions enable Paste independently of accessibility,
while highlight/copy and motion selections do not send. Existing receiver tests verify
JPEG/GIF/MP4/WebM routing and actual byte hashes under another receiving UID.

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
