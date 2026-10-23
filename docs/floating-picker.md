# Floating media picker

Open **Settings** and tap **Show icon** under the inline **Floating picker** heading,
below the existing settings. Its switches, delay choices, source/app controls and
permission status use Material chips on this page. Grant Draw over other apps
when requested. The icon has a 40 dp visual and a 48 dp touch target. Drag it to
either edge, tap to open, or hold to stop. By default it fades after three seconds
to 35% opacity and slides halfway into the nearest screen edge. Its visible half
remains touchable. **Show icon** in the notification or settings restores full
visibility; **Stop** remains a separate control. Idle dimming and a
3/5/10-second delay are configurable. The icon disappears while the picker is
visible or the screen is locked. A tucked icon returns tucked on unlock. Revoking Draw over other apps stops the service and its notification.

While dragging, a compact Material dismiss target appears at the bottom, above
the navigation area or an open keyboard. It highlights and gives optional platform
haptic feedback when the icon enters it. Release there to stop the floating session;
release elsewhere to snap to an edge. Rotation, lock, or a cancelled gesture removes
the target and restores the last committed anchor. Only an ordinary completed drop
persists the normalized edge/height; movement, rotation and lock/unlock do not write
position preferences. Hold-to-stop and the notification/settings Stop controls remain
available, including an explicitly labelled accessibility long-click action.

The icon and dismiss target use a display-associated `TYPE_APPLICATION_OVERLAY`
window context and `currentWindowMetrics`. Their `setFitInsetsTypes(0)` coordinate
frame matches the metrics; SAO applies system-bar/cutout insets once. Window-context,
display-change and attachment callbacks reapply the saved anchor after rotation and
unlock. Window move animation is disabled through the public API on Android 14+
while SAO retains its own idle tuck animation. Android 15's system-managed notification
bubbles require conversations and do not fit this media picker; snap/dismiss behavior
is implemented within SAO's overlay. See [rotation and drag verification](../diagnostics/bubble-lifecycle/README.md).

The existing launcher and carousel remain the default entry point. The picker
uses Android Views, RecyclerView, and official Material 3 components. It does
not install a keyboard or take over the user's input method.

The notification entry point hosts the same `FloatingSettingsFragment`, so permissions,
service controls and preference collectors have one implementation. Bindings and collectors
follow the fragment's view lifecycle. The icon lets Material measure its 40 dp body plus
minimum-touch insets, keeping its smiley centered rather than forcing the padded size as the
body size. See [alignment and inline-settings verification](../diagnostics/floating-settings/README.md).

Settings use a filled secondary container for enabled switches, selected values, granted permissions and configured source/app controls. Status actions keep their button behavior; their fill reflects the actual state rather than the last tap. Unconfigured controls remain outlined. The service wraps its window context with the same Material dynamic-color overlay as activities, and refreshes the icon's container, foreground and ripple on configuration/attachment events.

## Collection and search

`MediaSource` is the source boundary. SAO ships without a built-in sample library. Only configured profiles from installed providers appear in the source switcher. With no source, the picker explains how to install an extension and offers **Add source**. The first configured profile is selected automatically; removing the active profile selects another remaining profile.

**Emoji shortcuts** is an optional SAO setting, off by default. **Choose emoji** accepts up to 32 distinct emoji in display order, preserving flags, skin tones and joined sequences. When enabled, the row uses this saved list instead of a provider-generated list; tapping one searches it as text, including in Context mode. Turning it off reclaims the row’s space. Typing `:wink` suggestions remains separate.

The named source control opens installed profiles. Select a row, edit a profile, or choose **Add source** to configure another through an installed extension. Immich results load in pages and accept emoji input as well as text. The source contract exposes pages, previews, decoder-ready
still images and original streams. See [Immich API research](immich-api.md) for
supported server versions, endpoints, permissions and validation. Immich is now a separately installed APK: [setup and extension contract](source-extensions.md).

The fixtures and immutable WhatsApp pack are reproducible with
`tools/generate_floating_samples.py` and Pillow 12.3.0. Python and Pillow are
development tools; neither is required to build or run SAO.

## Selecting an item

Tap a carousel item to **highlight and copy immediately**, keeping the picker
open. Swiping changes the focal card without changing the selection or clipboard.
Strong flings carry across multiple cards using RecyclerView's normal momentum;
Material snaps to a focal card only when scrolling settles. The focused media ID
is owned by the activity ViewModel with SavedStateHandle. A small in-memory
process-session snapshot supports closing and reopening the picker. Ordinary reopening,
rotation and keyboard resizing restore that card. Search text and the selected
emoji/tag filter also restore across closing/reopening in the same app process
and system recreation of an existing task.
Only an explicit edit or clear changes the query. A changed text/tag/emoji query
resets to the first result. Restoring a scroll position never selects or copies.
The fixed dock acts on the selected item even after browsing elsewhere. Opening,
filtering, and recreation do not copy. Filtering the selection out disables its
actions while leaving the clipboard intact. GIF/video playback is restricted to the
focal card while the carousel is idle. Scrolling pauses motion; playback resumes
when it settles. Detached/recycled requests are disposed. See the
[fast-scroll investigation](../diagnostics/scroll-jitter/README.md).

Search initially occupies only an icon. Expand it to use the existing keyboard;
browsing, selection, filter chips, actions, Search, Back, and keyboard dismissal
collapse it while preserving the query. The header shows an active query. Large
text uses two action rows, and the panel stays above the keyboard.

Typing `:wink` or another emoji shortcut opens a compact Material autocomplete list. Tap an emoji to replace just that shortcut and keep typing with the keyboard open. Suggestions use bundled English aliases/keywords, work with every source and need no network connection. See [Telegram source mapping, implementation and licenses](emoji-autocomplete.md).

For Immich, expanded search also shows a compact **Context** / **Filters** row.
The mode chip opens an inline, single-selection list: **Context** (the default),
**Filename**, **Description**, **Full path or folder**, and **OCR**. Context and
OCR respect the server's enabled features. Emoji remain valid search text in
every mode; selecting an emoji chip applies its separate tag filter.

**Filters** opens an inline panel with one section visible at a time:
**People** (multiple selections, portraits and name lookup), **Places**
(country/state/city), or **Media** (all/images/videos). The compact row shows the
number of active filter categories and a reset action. These choices combine
with the query and emoji filter. GIFs follow Immich's reported media type.
Opening options pauses carousel motion and hides the delivery dock; returning
restores browsing. Tight landscape layouts expose options through the search
icon after search is expanded, dismissing the keyboard to make room.

Mode and filter choices use the picker ViewModel, SavedStateHandle and in-memory
browse session, with no preferences writes. People and place selections belong
to the selected source identity and never carry to another connection. The
generic media choice can carry across Immich connections. Filter choice lists
load on demand, cancel when the source changes, and surface failures inline.
Reopening a failed section does not send another request; **Retry** is explicit.
Available controls follow each extension’s capabilities. See
[search-controls evidence](../diagnostics/immich-search-controls/README.md).

Expanded search also exposes **Lucky**, labelled **I’m feeling lucky** in the
options panel. It clears search text and requests a random batch from the current
source, retaining emoji, People/Places/Media and configured album constraints.
Immich selects up to 100 items server-side across the eligible library; other providers implement their own random page. This is not a shuffle of already-loaded
Context results. **Shuffle** / **Shuffle again** requests another batch. The
batch has no automatic pagination, so scrolling cannot create an endless series
of random requests. Different batches can overlap.

Lucky clears the current selection without changing the clipboard. It retains
the batch and focal card through reopening/recreation in the same process.
Typing or choosing a search type returns to normal search and its usual
pagination. Only a lightweight random-request ID enters SavedStateHandle; media
lists remain in memory. Repeated taps during a read are ignored. Errors use the
existing inline retry and bounded read policy.

### Hold and drag selectors

**Search type**, **Filters**, and **Media sources** share a 160 ms hold-and-drag
interaction. A small Material surface expands from the button, with the current
provisional choice highlighted between its neighbors. Drag upward for the next
choice or downward for the previous one; the list wraps at both ends. Release
to commit. Ordinary taps retain the complete inline selector.

- Search type loops through the server's enabled modes and applies the chosen
  type directly, preserving text, emoji and constraints.
- Filters loops through All media, Images, Videos, People…, Places…, and Reset
  filters. Media choices apply directly while retaining other constraints.
  People/Places open their existing inline input panels. Reset clears advanced
  constraints and preserves search text, type and the emoji/tag chip.
- Sources loops through configured profiles from installed providers. Choices switch on
  release. Add source remains in the full picker opened by a tap. Previewing does not
  create clients, fetch results, or persist a source selection.

Dragging sideways out of the surface cancels on release. Back, rotation,
closing the picker, changed choices and source changes discard the preview.
Only one selector can be active. The wheel stays in the existing window and
retains IME focus; it clamps to visible bounds and uses a compact single-row
preview when space is limited. Row text changes only when a choice changes;
movement otherwise updates a translation, with optional platform haptic ticks.
No search, copy or preference writes occur during preview.

Short taps use `performClick`. Touch exploration retains the complete selector;
explicit accessible next/previous actions remain available. The implementation
follows [Android's View touch dispatch guidance](https://developer.android.com/develop/ui/views/touch-and-input/gestures/viewgroup)
and [input event guidance](https://developer.android.com/develop/ui/views/touch-and-input/input-events).
See [gesture verification and screenshots](../diagnostics/picker-hold-drag/README.md).

| Dock action | Payload and behavior |
| --- | --- |
| Copy | Copies the centered item: JPEG for ordinary static media, original GIF/video for animation, transparent 512 × 512 PNG for WhatsApp or WebP for Telegram with Sticker preference. Actual copy status is inline. |
| Paste | Explicitly copies, closes, and requests `ACTION_PASTE` once in the verified original editor. Requires optional assistance. Without Vector, Discord accepts image URIs including GIFs, while video and Instagram clipboard Paste remain unavailable. Never presses Send. |
| Sticker | Telegram/fork: static 512 × 512 WebP of at most 100 KB sent through the client's document route to the exact originating/configured package. Provider MIME remains image/webp. WhatsApp: optional verified native LSPosed handler. Disabled for GIF/video or absent capability. |
| Instagram Paste | Optional API-102 bridge; a verified original-DM lease sends photos through the keyboard handler and MP4/WebM through the compatible gallery handler. GIF retains the DM recipient picker. |
| Media / Attach / DM | Ordinary media handoff to the exact supported app, or Android sharesheet when the target is unknown. Discord labels this action Attach and receives original GIF/video bytes with their MIME and a URI read grant. Instagram uses only its exported DM receiver, labeled DM; no Feed/Stories fallback. See [Instagram delivery](instagram-dm-delivery.md) for APK evidence and authenticated-client limits. |

All dock actions use the actual centered card in the active carousel, including
sequence browsing and a restored scroll position. No image tap is required first.
Tapping a card still centers/highlights it and copies immediately; scrolling and
reopening do not copy or insert. Availability follows the centered media's type
and the original app integration. Empty/pending results and in-progress delivery
also disable actions. The title follows the center, and an earlier item's copy
status is not shown under a different item. Instagram receiver discovery reruns
on media MIME changes, not on every scroll frame or every photo.

The title and SAO's feedback form a two-line block with a fixed 2 dp gap, centered beside the Sticker/Media chip. The gap comes from the block's existing vertical padding, preserving the row height and neighboring alignment. Each line reserves its height at the current font size; empty feedback stays invisible without removing its space. Long text uses the platform TextView marquee after a 1.5-second reading pause. The Context **amazing** localhost regression checks stable carousel/dock bounds across empty, copied and GIF-tip states at normal and 200% text sizes.

The embedded dock follows its action row's measured height instead of reserving the standalone toolbar's 64 dp minimum. This removes 16 dp of empty bottom space at normal text size while retaining the panel's 12 dp inset and the buttons' standard touch targets.

Colors follow [Material 3's roles](https://m3.material.io/styles/color/the-color-system): low/high surface containers for the panel/cards, on-surface titles, and on-surface-variant supporting text. Copy/Paste inherit the official tonal button's on-secondary-container foreground, ripple and disabled states against their shared secondary container. Motion badges have an opaque surface container behind their text; selector labels use their semantic foreground without extra alpha fading. Activity dynamic color and DayNight theming resolve these roles for the current wallpaper and theme.

Picker icons share Google's Material Symbols Rounded family (24 optical size, weight 400), including vector selection checks that do not depend on font rendering. [Source and license](../THIRD_PARTY_NOTICES.md). The selected outline follows Material Carousel's current visible mask with matching 24 dp corners, so all four edges remain continuous as a card narrows. The pixel regression checks every edge at four mask widths in light and dark themes; device checks cover scrolling, restored positions, dynamic overlay colors, configured settings states, rotation and 200% text.

Screenshots: [empty tip](screenshots/floating-dock-empty.png),
[overflowing title and tip](screenshots/floating-dock-marquee.png).

The compact **Sticker/Media** chip marks both active modes with a checkmark and persists the delivery preference and explicitly
affects the next selection, Copy or explicit Paste; it does not re-copy when toggled. Unsupported sticker clipboard
formats fall back to media with inline status. No selection menu or app-owned
copy toast appears. Android can still display its system clipboard preview.

**Telegram apps** in Floating picker settings supports searchable installed apps
with icons and checkboxes, plus comma/newline-separated manual package names.
Built-in Telegram and published Nagram X packages stay included. Custom names are validated, trimmed,
deduplicated, and stored with exact case in DataStore; uninstalled saved names
remain removable. Mapping affects classification and the Accessibility runtime
package filter. Routing keeps the original exact package. A mapping does not
guarantee a fork accepts sticker WebP. When detection is unavailable, tapping the **Clipboard ▾** header expands an inline list of supported apps; this never creates an
original-editor Paste or native lease.

Usage Access is optional and lets SAO choose formats for the foreground app.
Its foreground query runs once on an icon press, off Main, with a UI deadline;
there is no periodic UsageStats scan.
An enabled Paste assistance service or a live native bridge can also identify
the current supported app. Ambiguous foreground state is treated as unknown.
Unknown targets and animated items use ordinary media for clipboard delivery.
SAO does not convert GIF/video into a still sticker or promise animated sticker paste.
If detection is unavailable and the header says Clipboard, choose Telegram/Nagram from the
inline recipient list before selecting or copying. A keyboard/client can still convert pasted
content; the direct Sticker action avoids the photo-sharing route. See
[Telegram delivery investigation](telegram-delivery.md) for source checks and test limits.

Exports are immutable files under `files/floating_exports/v1`, exposed by the
restricted FileProvider. Closing the picker does not remove a URI still being
used by another app. Sticker conversion preserves the complete image inside a
transparent 512-pixel canvas; ordinary static media uses a white JPEG canvas.

Paste assistance is explicitly enabled through Android's Accessibility
settings and separately toggled in SAO. It is limited to WhatsApp, WhatsApp
Business, Telegram, configured Telegram forks, and image paste (including GIFs) in Discord. Instagram attachments use its DM receiver instead of paste assistance. The service inspects editor identity and editability, not message
text. It remembers the previously focused editor across focus loss, then captures
that exact node and window on an icon press. An explicit Paste restores its focus
and waits for readiness before performing one Paste action. Replacement windows,
removed editors and another focused editable field invalidate the request. A never
focused field is not guessed. Browsing the picker does not expire the selection;
opening another picker or stopping assistance clears it. Failure leaves the
clipboard available for manual paste.

## Modern LSPosed bridge

This module targets **libxposed API 102.0.0**, using `XposedModule` lifecycle
callbacks and interceptor hooks. It has no legacy `de.robv.android.xposed`
dependency, `xposed_init`, or legacy manifest metadata. Registration lives in
`META-INF/xposed/{java_init.list,module.prop,scope.list}` and is preserved in
minified builds. The API is `compileOnly`, so ordinary SAO runs without LSPosed.

Use an API-102-capable maintained LSPosed framework, such as
[Vector 2.2](https://github.com/JingMatrix/Vector/releases/tag/v2.2). Enable SAO
and scope it to **WhatsApp**, **WhatsApp Business**, **Instagram** and/or **Discord**, then restart that app.
Only those applications' main processes install hooks. Native bridge status is
visible in Floating picker settings; a connected bridge alone does not mean a
chat editor is available.

Instagram's optional **Paste** action uses its native keyboard-image handler, which
sends directly after the explicit button press. It is available only for a validated
still-photo editor lease, independently of accessibility Paste assistance. GIF/video
retain the DM attachment flow. See [Instagram adapter and verification](instagram-dm-delivery.md).

**Paste prefers a verified native handler** over accessibility assistance. In WhatsApp
Sticker mode it uses `image/webp.wasticker`; Media mode uses the advertised image MIME.
GIFs retain their real MIME; MP4/WebM use the verified native draft event when available. Selection still
highlights/copies immediately; neither selecting nor toggling delivery mode invokes a
native handler. A rejected native request is never retried through clipboard assistance.

The floating service bootstraps Android 11+ package visibility by granting each installed
scoped host read access to one isolated **zero-byte** FileProvider file. The hosts' manifests
cannot declare SAO in `<queries>`; a no-query API 35 probe reproduced a failed explicit
service bind without this grant. The grant exposes no exported media or configuration.
Android makes the granting package visible to URI-grant recipients; see
[automatic package visibility](https://developer.android.com/training/package-visibility/automatic).
The bridge's package/UID checks in both directions remain mandatory. Binding allows at
most three attempts per resume with backoff to accommodate a first-start visibility race.
Media insertion is still dispatched once.

Lifecycle hooks are installed independently of host adapter initialization. The picker
shows a per-app disabled reason and host version for unsupported hooks, missing editor,
unsupported MIME or pending chat metadata. A naturally created editor connection can
wait for initial metadata; an established direct-send conversation change revokes it.
Late hook/listener discovery asks Android to restart input, rather than constructing
an input connection or invoking the host's sending code speculatively.

Discord's native **Paste** stages PNG/JPEG/GIF through its existing receive-content
handler, without an accessibility anchor and without invoking Send. Its exact native
root/editor/listener and opaque draft revision identify a selection; foreground typing
can mint a fresh token, but a changed revision while the picker is open invalidates the
original token. Discord exposes no current-channel ID in this native composer, so this
is a draft-editor lease rather than a verified channel-ID lease. Videos use Attach.
See [the isolated client probe](../tools/discord-native-probe/README.md).

The WhatsApp host observes the naturally created final `onCreateInputConnection(EditorInfo)` wrapper of
the conversation composer with resource name `entry`. It requires explicitly
advertised **`image/webp.wasticker`** support and the composer's live `ARG_JID`
metadata. An activity launch `jid` is unnecessary and may be absent or stale when
navigating between conversations.
This catches in-place chat changes even when an activity retains an old intent. Missing or ambiguous metadata disables native insertion. No extra input connection is constructed during resume. Capability snapshots read
immutable cached state without waiting for the host UI thread. The selected URI is explicitly
granted to the scoped package. When the original chat resumes, the module calls
the wrapper's own `commitContent` with a transparent 512 × 512 WebP and the
WhatsApp sticker MIME type in Sticker mode, or a native JPEG/GIF in Media mode. It does not rely on PNG classification. There is no guessed
obfuscated sender signature, fabricated IME permission token, or automatic retry.

The current official WhatsApp APK **2.26.40.70** was inspected during development:
`MentionableEntry.onCreateInputConnection` advertises content MIME types, including
`image/webp.wasticker`, and
returns an `InputConnectionCompat` wrapper with its rich-content callback. Its
Paste handler also forwards content URIs to that callback when WhatsApp enables
that feature. The integration uses these platform methods and the final runtime
wrapper instead of hard-coding the APK's obfuscated listener class names.

Both directions of the Binder bridge enforce installed package/UID identity.
Selections bind to the original editor, activity, hashed conversation identity,
and a two-minute lease. Changed chats, detached editors, ambiguous hosts, stale
requests, and repeated request IDs fail closed. A successful callback means the
host accepted insertion; it does not assert that a sticker message reached the
recipient. No account information or conversation content is logged.


## Development and verification

The scripts in `~/Android/Sdk/README.md` work on headless Ubuntu. `scrcpy` is
optional on a separate desktop; screenshots, layouts, and input use ADB locally.

```bash
~/Android/Sdk/start-medium-phone.sh
~/Android/Sdk/build-app.sh "$PWD"
~/Android/Sdk/install-app.sh "$PWD"
~/Android/Sdk/launch-app.sh one.njk.sao
android layout
android screen capture -o /tmp/sao-picker.png
~/Android/Sdk/app-logs.sh one.njk.sao --warnings --dump
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug
./gradlew :app:assemblePrerelease :app:assembleRelease
```

Automated tests cover emoji/tag search, delivery decisions, foreground ambiguity,
offline assets and renditions, pack URI validation, preservation of picker state,
cross-UID FileProvider access with and without URI grants, rejection of
unauthorized native clients, and overlay startup/hide/restore/hold-to-stop.
IME tests verify that the panel fits above the default keyboard and restores
after Search, preserving the query. At 200% text in landscape, the tightest
keyboard layout uses an inline search field and four labelled action icons in
one row; tooltips and accessibility descriptions retain their names. Normal
layout restores when the keyboard closes. Tight/large-text viewports use
Material's full-screen carousel strategy; ordinary browsing uses the hero
strategy. This prevents invalid hero keylines when available image height is
too small. Installed-app choices replace the carousel area rather than adding
another row below the dock.
Headless Android 15 supports the non-root UI and export checks. A logged-in,
rooted API-102 device is still needed to verify actual WhatsApp sticker
classification, preview, and sending through its live handler. Animated sticker
insertion is deliberately not advertised as verified.

## Responsiveness and coroutine ownership

`FloatingDispatchers` is injected and configurable for tests. Views, selection
coordination, and animations use Main.immediate. File/package queries and bridge
IPC use IO. Accessibility node reads/actions and clipboard publication use
separate limited IO workers and transaction mutexes. Filtering uses bounded
Default workers; bitmap decoding/scaling/encoding uses one Default conversion
worker with an export mutex. Source bytes are read and encoded bytes written on
IO. No production `runBlocking` is used.

The service caches foreground app state on IO, and assistance caches original
editor identity before the panel takes focus. Opening the panel reads memory
only, never waits for optional node/host discovery. Missing leases disable the
assisted actions. Media requests have a version owner and serialized clipboard
publication; obsolete work is cancelled and checked again before publication.
An in-flight synchronous Binder call cannot be interrupted by coroutine
cancellation; its late results are discarded and platform operations cannot
overlap. Closing/recreating the panel does not delete exported files.

The reported whole-phone freeze has not been reproduced on a physical device.
These changes remove identified main-thread blocking risks; they do not prove
its original cause. Physical-device Accessibility/LSPosed stress testing and a
Perfetto trace are still needed if it recurs.

### Revamp verification (2026-10-09)

- 68 distinct passing regression tests: 12 app JVM tests, 12 Immich contract tests and 44 Android 15 emulator tests.
  The separate opt-in live Immich and emoji parity tests also passed; they are skipped by the ordinary suite.
  See [live Immich validation](immich-live-testing.md).
- Debug, prerelease, release and lintDebug pass; the minified release launcher,
  local carousel, and original-GIF clipboard flow were also checked manually.
- Tests cover immediate copy without closing, selection independent of browsing,
  filtering/recreation without copying, stale publication rejection, Main
  responsiveness during blocked IO, exact-package custom-fork WebP delivery,
  GIF preservation, keyboard/200% text/landscape bounds, half-edge idle dimming and touch, multi-card flings, session
  browsing position, notification restoration, permission revocation, and cross-UID URI grants.
- All variants retain the modern module entry and scoped hosts, with no bundled
  framework API or legacy Xposed entry.
- A 60-second emulator Perfetto capture recorded 154 SAO main-thread Binder
  slices, with a maximum of 13.7 ms in that interval. This does not characterize
  a physical phone or diagnose its earlier freeze. Trace and verification notes
  are in the ignored `app/build/outputs/diagnostics/` directory.

To capture a recurrence on a connected phone, start tracing before reproducing
it and include whether Paste assistance and the native module are enabled:

```bash
adb shell perfetto --background -t 60s -b 16mb -a one.njk.sao \
  -o /data/misc/perfetto-traces/sao.perfetto-trace \
  sched/sched_switch sched/sched_waking gfx view wm am binder_driver
# Reproduce within 60 seconds, then pull after tracing ends.
adb pull /data/misc/perfetto-traces/sao.perfetto-trace
```

Inspect the trace with [Perfetto](https://ui.perfetto.dev/), following its
[Android recording documentation](https://perfetto.dev/docs/getting-started/system-tracing).
No physical rooted device with a logged-in WhatsApp and API-102 framework was
available; native delivery and the original phone freeze still need that
end-to-end validation.

The latest interaction correction replaces full idle hiding with dimming and
half-edge tucking. Regression checks cover taps on both visible halves, full
restoration through the notification, multi-card flings, reopening/recreation/
IME position retention, in-memory session snapshots, and query-triggered resets.

Search/emoji restoration uses the complete in-memory browsing state, without
copying during restoration. The active delivery chip remains checked for both
Sticker and Media. Device regressions verify query/chip/position persistence,
checkmarks, recreation and an unchanged clipboard.

The icon hides while locked and returns automatically on unlock in its prior
tucked state. System screen/unlock broadcasts have a dedicated receiver that
accepts protected system events, including SystemUI delivery; picker visibility is delivered in process through StateFlow. Wake-up uses a
bounded asynchronous check for the final interactive/unlocked state, since the
broadcast can precede the keyguard state transition. The emulator test sleeps,
wakes and dismisses its actual keyguard, then verifies restoration without Show
icon. See [Android's receiver guidance](https://developer.android.com/develop/background-work/background-tasks/broadcasts).

## Choosing transient state ownership

The previous DataStore browsing writer has been removed. Android's guidance
recommends ViewModel for screen state and SavedStateHandle for lightweight
system task restoration. An activity ViewModel ends when finish() completes,
so a separate value snapshot in PickerBrowseSession preserves normal close /
reopen without keeping a finished ViewModel, activity or coroutine alive.

| Approach | Fit for this picker |
| --- | --- |
| Activity ViewModel + SavedStateHandle | Owns query, emoji filter and focused ID; handles rotation and Android restoring an existing task. It does not span finished activity instances by itself. |
| Keep the activity alive in the background | Would retain its ViewModel but changes dismissal/task behavior and leaves UI work alive. |
| Service/global ViewModelStore | Requires custom ownership and cleanup; would retain work/state beyond the screen that needs it. |
| ViewModel + a small in-memory session snapshot | Chosen: preserves reopening in the same process while allowing the activity/ViewModel and delivery work to end normally. |
| DataStore for browsing | Unnecessary disk persistence for this transient session state. Removed. |

Only three small strings participate in SavedStateHandle: query text, filter and
focused media ID. No media bitmaps, views, target-editor anchors or sending jobs
are retained in the session snapshot. UI changes update memory only; Android
serializes SavedStateHandle with normal lifecycle saved state. DataStore remains
for actual user settings. A one-time migration removes only the obsolete browse
keys from earlier prototypes.

A cold app restart after the picker/task was dismissed starts fresh. If Android
restores an existing task after killing the process, SavedStateHandle restores
its browsing state. The device test checks that typing, chip changes, scrolling,
recreation and reopening do not change the preferences file's bytes or timestamp.

Sources: [Android state-saving guidance](https://developer.android.com/topic/libraries/architecture/saving-states),
[SavedStateHandle for Views](https://developer.android.com/topic/libraries/architecture/views/viewmodel/viewmodel-savedstate-views),
[ViewModel lifetime](https://developer.android.com/topic/libraries/architecture/viewmodel).


## Videos and named sources

MP4 uses the original video MIME and bytes for automatic clipboard copying and explicit Media
delivery. GIF/video selections disable static Sticker actions. One focused MP4 plays muted and
loops through Media3 after scrolling settles; scrolling, moving away, hiding the carousel or
stopping the picker releases it.
A poster remains available if decoding fails. Video playback/export supports files up to the
existing 64 MiB original download limit; it does not turn MP4 into animated Telegram/WhatsApp
stickers. Animated native sticker encoding still needs a separately verified client contract.

The inline source panel is a native Material list containing named profiles from installed providers.
Tap a row to select it; its checkmark and the header identify the active profile. The row's edit
button opens its extension's configuration, and Add source creates a separate named profile.
The same list is available under Floating settings → Media sources. Credentials are durable settings owned exclusively by the extension; SAO saves public profile references and selection. Query/filter/focus remain in the existing
ViewModel/SavedStateHandle and in-memory session. See [provider architecture](immich-api.md#named-source-ownership-and-failures).


## Infinite results and Discord attachments

Immich Context and metadata both load additional pages near the end of the carousel. There is no
fixed total result limit. The floating adapter follows the main app's `inHousePagination` idea
of prefetching from item binding, with one active request, server-provided continuations, query/source
cancellation and a visible retry on network failure. Query, focus and already-loaded pages remain
in memory when closing/reopening. Changing the query starts a new result set.

Discord (`com.discord`) is recognized by foreground detection and the inline recipient list.
Selecting still copies immediately. Photos and GIFs can use **Paste** with optional assistance
to insert into the original focused editor. For video, use **Attach** to open Discord's own
attachment/destination flow; SAO does not automatically send to a channel or press Send. The
handoff uses Android [`ACTION_SEND`](https://developer.android.com/training/sharing/send),
`EXTRA_STREAM`, matching ClipData MIME and a temporary read grant. GIF, WebM and MP4 exports keep
their original bytes and extension. Missing/generic Immich MIME types are inferred from the
original filename, including `video/webm`, rather than treating every video as MP4.

Discord documents mobile image/video/GIF attachments in its
[File Attachments FAQ](https://support.discord.com/hc/en-us/articles/25444343291031-File-Attachments-FAQ).
Without Vector, clipboard paste depends on its editor; video Paste and all Sticker actions are disabled for
Discord. Android API 35 tests verify exact SHA-256/length/MIME under a separate receiving UID,
plus muted WebM playback and the Discord dock. These tests verify SAO's transport, not Discord's
upload or rendering behavior. A logged-in Discord client is required to check that final step;
codec support and account/server upload limits remain the client's responsibility. The native
composer from Discord 348.10 was also exercised independently on API 35: PNG and GIF URI paste
called its image-insertion listener, while WebM and MP4 did not. See
[the APK analysis](discord-apk-analysis.md) for scope, evidence and the remaining video paths.

## Floating icon rotation

The overlay uses its own window context for geometry and configuration updates. A service's global
configuration can differ from that visual context; Android documents the distinction under
[createWindowContext](https://developer.android.com/reference/android/content/Context#createWindowContext(int,%20android.os.Bundle)).
API 31+ registers a callback on that context. The bubble also handles its ViewRoot configuration,
and API 30 retains a deferred service callback as a fallback. Updates are coalesced into the next
Main-thread frame, after the visual configuration changes. Pending drag, long-press and tuck work
is cancelled before repositioning, and frame/context callbacks are removed on service destruction.

The icon reanchors to the saved edge and proportional vertical position using current bounds and
system-bar/cutout insets. Its settled tucked state and opacity survive rotation. Showing it after
being detached (picker/lock) also refreshes geometry. Rotation never saves pixel coordinates or
writes preferences; only a completed user drag changes the saved anchor.

API 35 regression coverage includes both edges in visible/tucked states through repeated portrait,
landscape and reverse-landscape cycles, unchanged preference bytes/timestamps, a separate-process
host, physical tapping of the remaining half, rotation while the picker hides the icon, and a touch
held across rotation. Those three cases and the existing hide/restore, long-press/permission and
lock/unlock cases passed (six tests). Debug/release/prerelease builds and lint passed. The emulator's
initial foreground-only test also passed before the change; the exact disappearance reported on
the physical phone was not reproduced there. The revised tests check the corrected callback and
state handling without claiming a physical-device reproduction.

During the later broad API 35 run, the background View's cached `getLocationOnScreen` reported
portrait coordinates while the screenshot and system window frame both showed the correctly
positioned landscape icon. Accessibility window coordinates also retained transforms in that
scenario. Rotation tests now use the system window manager's rendered overlay frame from
`dumpsys window windows` for visibility and physical tap coordinates, alongside the independent display/inset/parameter
checks. Samples must stay correct for 200 ms to avoid rotation animation races. The separate
host has its own task and explicit cleanup, preventing it from intercepting later activity
restoration tests. This required test corrections, with no additional production overlay change.

### Pagination and Discord verification (2026-10-10)

The final broad API 35 run passed all 50 regular Android cases; three live-only cases skipped
without their opt-in flag. The separate live pagination case passed against the supplied server.
App JVM and Immich contract tests each passed 13 cases. Debug/release/prerelease APKs built, and
lint completed with zero errors (61 warnings in the existing report). GIF/WebM/MP4 handoffs
matched original bytes under a separate receiver UID, and actual WebM frames rendered muted in
the carousel. Actual Discord upload/rendering remains a logged-in client check.


Generated device logs, screenshots and build outputs are developer-local artifacts and are excluded from Git. The source tests and isolated Instagram adapter probe are versioned.


### Prompt picker dismissal

The picker releases its in-memory visibility owner when `finish()` accepts dismissal,
before exit animation and preview cleanup reach `onStop()`. The overlay service observes
that state on `Main.immediate`, without a system broadcast round trip or close timer.
The retained picker ViewModel owns an opaque token across recreation; it holds no Activity
or View reference. Normal backgrounding and ViewModel clearing also release ownership.
Rotation keeps the icon hidden while rebuilding the picker, and existing lock/permission
checks continue to gate restoration. No browsing state is written to preferences.

### Native Paste priority verification (2026-10-14)

The API 35 device suite covers native WhatsApp Sticker/Media Paste without accessibility,
Discord native GIF draft dispatch, Instagram still-photo-only explicit dispatch, delayed
initial metadata, established conversation revocation, noisy controller discovery,
foreground draft renewal versus paused changes, exact sticker MIME declarations, one-use
requests, URI/MIME/grant checks, attachment fallback and prompt picker dismissal.

The separate no-query Discord probe reproduced failed package lookup/binding before the
bootstrap. With SAO granting only the isolated empty URI, lookup and binding succeed;
that URI reads zero bytes while an unrelated media URI remains blocked. Its real native
PNG/GIF keyboard callbacks fire, with zero Send callbacks. The existing Instagram
real-class probe still passes. Debug/release/prerelease build, JVM and lint checks pass.
These local recording probes do not establish Vector injection or authenticated host
upload/delivery on the user's rooted phone. Per-app inline diagnostics support that
remaining device verification.

### Closed keyboard and inactive service (2026-10-14)

Discord explicitly clears composer focus when its keyboard hides. On Android 12+,
SAO now leases the configured native receive-content view instead of requiring a live
IME connection. Listener configuration and view attachment trigger discovery, including
late React composer creation. Only one visible enabled receiver can be selected. On
explicit Paste, the same leased editor regains focus and its own `performReceiveContent`
handler stages the URI. No input connection or replacement keyboard is manufactured.
Android 11 retains the existing captured IME/clipboard fallback. MIME/capability removal,
listener replacement, hidden/detached editors, changed drafts and consumed tokens still
revoke selections. PNG/GIF insertions through Discord's actual classes work without an
input connection in the isolated probe, with zero Send callbacks.

WhatsApp no longer requires a `jid` in the activity launch intent. Its live editor's
`ARG_JID` bundle already identifies the conversation; missing/conflicting live metadata
still disables native insertion, and changed metadata revokes the prior lease.

Native snapshots are event-driven: an idle floating icon makes no snapshot IPC. Only explicit selection/insertion requests read a snapshot; picker-visible subscriptions receive pushed state. When the floating **service is stopped**, subscriptions and accessibility
node discovery stop. Accessibility event types are set to zero and node leases cleared.
Host hooks return without inspecting editors, scanning views or restarting input; native
bindings are released and Instagram's captured controller bindings cleared. UsageStats
is queried only on an icon press. A tucked/faded
bubble remains an active floating service.

A scoped host retains minimal lifecycle hooks and a permission-protected activation
receiver. It performs at most one startup state handshake to discover an already running
floating service; inactive failures have no retry loop. Signed start/stop broadcasts and
Binder activation carry monotonic generations so stale control messages cannot restore
older state. Both media Binder directions retain UID enforcement. Host snapshot callers
use a UID resolved once per binding, rather than repeated PackageManager queries. Paused
host snapshots do not schedule periodic UI metadata reads; insertion validates on resume.
These one-time setup/control calls are distinct from continuous polling or discovery.

A recording-client regression verifies zero snapshots during inactivity and after Stop,
and cleared cached leases. A real AccessibilityService device test verifies event types
are zero while stopped, restored on activation and zero again after Stop. Focus-loss tests preserve a view receiver lease across picker
pause without an IME, while stale/ambiguous metadata, hidden editors and removed handlers
remain rejected. Physical Vector injection and authenticated sends still require phone
verification.

## Immich sequence browsing

In the inline floating settings, **Browse sequence on hold** is off by default.
When enabled for an Immich source, a short tap still highlights and copies; a
stationary hold opens that asset's neighbors in the same order as Immich's
Photos timeline. Scrolling, a second pointer, recycling and leaving the window
cancel pending holds. **Long-press delay** defaults to **160 ms** and can be set
from 100–1000 ms. Results, Context, Filters and Sources all read this same value.
The settings slider saves at the end of a touch gesture, not on every drag step;
discrete accessibility/keyboard adjustments also save.

The sequence replaces the carousel temporarily. It has a **Back to results**
action, and system Back restores the original query, filters, loaded results and
focused item without querying or copying again. Search results stay in the
existing in-memory session, which also owns the temporary sequence and its
cancellable work. Rotation/recreation retains that navigation. Finishing the
picker pauses reads and preserves the inner screen, loaded pages and focused
item for reopening. Changing sources discards the old sequence; stale reads
cannot publish into the new source. Changing/submitting the search returns to
search mode; merely opening the search bar does not.

Ordering comes directly from `timeline/buckets` and `timeline/bucket` with
`order=desc`, `orderBy=takenAt`, `visibility=timeline`, `withStacked=true`, and
`withPartners=true`, retaining any configured album scope. This preserves the
Photos local-day, capture-time and filename tie breaks instead of approximating
them with an upload-time sort. Query text, emoji and search filters do not
restrict the neighboring timeline. Archived/hidden assets and non-primary stack
members that are absent from Photos cannot anchor this view; a failed read
keeps Back and an explicit Retry available.

Immich's internal timeline API returns an entire bucket's index. The extension streams
only its ordered IDs into memory, discarding the parallel thumbnail/date arrays;
SAO receives only the requested window and opaque continuations. No originals or previews
for an entire month/library are fetched. Metadata is hydrated for the initial
window (up to 20 items on either side) and subsequent pages of up to 40 cards,
in either direction. A bucket response has a 64 MiB metadata safety limit.
Slow streams are cancelled through the underlying HTTP call, with cancellation
preserved as cancellation rather than an error/retry. Transient reads use the
existing one-retry policy; authentication/permission failures await user action.

Every sequence card has a small date/time caption from Immich's `createdAt`,
formatted in the device's local timezone and locale. It does not affect ordering.
Missing or invalid upload dates are labelled unavailable, never replaced with a
capture date. Normal search cards keep their existing appearance. Large-text
landscape retains the Back action, carousel and delivery dock by hiding redundant
selection/status text in the constrained viewport.

`SequenceBrowseDeviceTest` covers real tap/hold/swipe timing, shared selector
delay, both paging directions and month boundaries, exact server ID ordering,
Back without a search request or clipboard write, recreation, source changes,
upload captions, settings persistence and large-text landscape. Its localhost
fixtures return metadata in a deliberately different order from the timeline.
`TimelineIndexTest` checks streaming parsing and cancellation of a stalled body.

References: [Photos defaults](https://github.com/immich-app/immich/blob/main/web/src/lib/managers/timeline-manager/timeline-manager.svelte.ts),
[timeline contract](https://github.com/immich-app/immich/blob/main/server/src/dtos/time-bucket.dto.ts),
[timeline ordering](https://github.com/immich-app/immich/blob/main/server/src/repositories/asset.repository.ts).

## Background reliability

The floating service uses `stopWithTask="false"` and `START_STICKY` for normal task removal and system process recovery. Some OxygenOS/ColorOS builds discard the started-service record as well. `FloatingRuntime` therefore retains one device-local run-intent boolean in `noBackupFilesDir`, independently of picker state and configuration. Show and Stop are serialized in an application-owned coroutine scope; dismissal is committed before stopping. Repeated restore events only read this flag and reuse an already-running icon.

When Android reconnects the existing, user-enabled **Paste assistance** accessibility service, SAO restores the requested icon once. Boot completion and SAO package replacement use the same path through a non-exported receiver. No additional accessibility or notification-listener permission is requested. Android's background-start restrictions still apply; denied attempts are logged once for that event, without retries. Cleanup never schedules a restart. There are no wake locks, screen-on flags, alarms, polling watchdogs or periodic recovery jobs. Native editor leases and browsing state remain process-local.

Inline **Background reliability** settings show the real battery-optimization exemption and the last own-process exit reason from `ApplicationExitInfo`, queried once on settings resume on IO and cancelled on pause. Battery optimization and vendor background/auto-launch settings are distinct. OxygenOS/ColorOS may require locking SAO in Recents; no public API lets SAO toggle that vendor setting. Force stop prevents recovery until the user opens SAO again. See [source comparison and limits](background-reliability.md).

Device tests cover actual task removal, durable dismissal, discarded-service restoration, duplicate restore events, isolation from browsing preferences, settings intents and large-text layouts. With the debug APK installed and **Show icon on app launch** enabled, run `python3 tools/test-floating-recovery.py --serial emulator-5554`. This emulator-only driver tests real process death, Android accessibility reconnect after discarding the service record, app replacement, manual Stop, device sleep/Doze without a SAO wake lock, and Force stop. It temporarily enables Paste assistance and overlay access, restores those settings and leaves SAO stopped. Emulator `su` is used only to discard the non-exported service record in the test fixture; the implementation needs no root.

On development machines, setting `sao.debugApkShareDir=/srv/samba/share/` in `~/.gradle/gradle.properties` automatically copies `app-debug.apk` after debug packaging/assembly, including incremental builds. The task tracks only this APK and never scans the rest of the share.

### Automatic current-chat attachments with Vector

With a valid native lease, Discord **Paste**/**Attach** stage JPEG/PNG/GIF/MP4/WebM in
the original draft. Instagram **Paste**/**DM** send photos or MP4/WebM directly through
the leased controller; videos require its verified gallery contract without an exact
app-version gate. Unsupported capabilities retain the ordinary Attach/DM recipient
flow. Instagram GIF is not routed through its bitmap photo handler. Selection/copy
never sends. File/metadata work runs off Main, the lease is rechecked after preparation,
and native dispatch is consumed once without automatic send retries. See the
[capability matrix](media-capabilities.md) for validation limits.

An explicit icon press refreshes the visible native composer before opening the picker.
Normal snapshots never re-arm a revoked selection. MIME availability can change without
replacing its chat-bound token; a new explicit opening can recover a failed/revoked
draft without an intervening photo paste or keyboard reconnect. Failed snapshots use
bounded backoff while the floating service is active; explicit opening can retry immediately.
Rejected actions preserve any existing URI grant held by an earlier attachment and
never retry the send. Instagram's verified gallery contracts cover 435 and 451.


Native bridge revision 2 uses ACCEPTED / REJECTED / UNKNOWN results. A timeout while still queued cancels dispatch. If dispatch already began, SAO never retries automatically and keeps the URI read grant and prepared input alive for the host’s ongoing read; the user is told to check the chat. This avoids duplicate sends and revoking a file during an uncertain attachment operation. Physical Vector injection still requires device verification after installing both updated host and scoped app modules.
