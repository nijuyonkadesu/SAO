# Floating picker revamp

Status: implemented in the Android Views picker. The accompanying HTML remains
an interactive design preview; the APK implements clipboard and delivery behavior.
See `floating-picker.md` for operation and verification details. Device checks
added a full-screen carousel fallback for short/large-text viewports and a single
search/action row for the tightest landscape keyboard layout, after reproducing
an invalid-keyline crash in the hero strategy. The inline destination list opens
from the Clipboard header and occupies the carousel area.

Confirmed interaction: **tap a reaction → highlight it → copy it immediately →
keep the picker open**. Swiping only browses. There is no selection dialog and
no automatic Paste or Send.

## Priority: investigate the freeze

The reported phone freeze has not been reproduced or diagnosed. No physical
device is connected to this workspace. Device/Android version, the last action,
and whether Accessibility assistance or LSPosed was enabled are still unknown.

Code review identified concrete areas to investigate:

- `FloatingService.openPicker()` runs on the main dispatcher and calls
  `PasteAssistService.capturePackage()` / `captureAnchor()`. These retrieve an
  Accessibility tree and focused node synchronously. `pasteOnce()` also reads
  and refreshes nodes on the main dispatcher. Other apps' response time must not
  block SAO's UI.
- The LSPosed resume scan explicitly calls `onCreateInputConnection()` on the
  host editor. The bridge should observe its naturally created final connection
  rather than create extra connections during resume.
- Native discovery and insertion repeatedly request snapshots that wait for a
  host main-thread callback. Slow or unavailable hosts must not hold up opening
  the picker or create a queue of stale requests.
- Insets handling currently queries window metrics during resize callbacks;
  bubble dragging queries them repeatedly during movement. Profile Binder and
  layout work, cache geometry until it changes, and coalesce updates to a frame.

These are risk areas, not a claim that any one caused the reported freeze.
Android's [Accessibility interaction client source](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/view/accessibility/AccessibilityInteractionClient.java)
documents synchronous client waits and a five-second interaction timeout.
Calling it from SAO's main dispatcher is therefore a concrete responsiveness
risk, even though a trace is still needed to explain a whole-phone stall.
Use a Perfetto trace and logs to distinguish SAO UI blocking, host blocking,
rendering pressure, and a system stall. Test root and non-root independently.
Follow Android's [ANR diagnosis guidance](https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs).

Before adding the new interactions:

1. Move blocking Accessibility reads to a serialized worker and allow the panel
   to open without waiting for optional assistance. Discard stale results.
2. Observe natural input-connection creation in the scoped host; preserve the
   original-chat, window-focus, expiry, and replay checks.
3. Bound optional discovery, keep its work cancellable, and use current cached
   capability state. Do not advertise an unverified recipient or handler.
4. Decode/convert media off the main thread. Use one selection-copy pipeline;
   obsolete selections cannot publish to the clipboard.
5. Measure repeated open/close, search/IME changes, GIF playback, dragging, and
   rapid selection. Validate with real-device traces when available.

### Coroutine dispatch and ownership

Inject dispatchers so the coordination can be tested; do not hard-code every
operation into a main-thread lifecycle coroutine.

| Work | Execution |
| --- | --- |
| View updates, animations, focus, adapter notifications, host View/input-connection handling | `Dispatchers.Main.immediate` (the host uses its main Looper for View callbacks). |
| File/network access, package queries, Accessibility node/Binder reads and actions, bridge IPC, system clipboard publication | `Dispatchers.IO`, with dedicated serialized assistance and clipboard workers. |
| Local filtering, bitmap decoding/scaling/encoding | Bounded `Dispatchers.Default` workers; one media conversion at a time. |
| DataStore | Its supported asynchronous APIs; never block waiting for a preference read. |

Keep selection state and request versions on one owner. A clipboard coordinator
serializes publications, and checks the selected request again immediately
before publishing. System clipboard calls do not justify blocking UI or View
work behind optional app/service queries. UI status is delivered on Main.

Use structured scopes, `collectLatest`/conflation for superseded selections,
`Mutex` for shared export state, and cancellable bounded discovery. Re-throw
`CancellationException`. Do not use `runBlocking`, synchronous waits, or a
main-thread lock while waiting for another app. Do not describe `withTimeout`
as interrupting a synchronous Binder call: an in-flight platform call may keep
running, so discard late results and prevent overlapping requests.

The native host connection is observed on its proper UI thread; background
capability snapshots should consume immutable state rather than synchronously
forcing extra editor lifecycles. Clipboard/capability coordination and all UI
states must remain responsive when the host is unresponsive.

Preserve the originating app/editor before the panel takes focus, using cached
event-driven identity and capability state. Do not query the active window after
opening and mistake SAO's own editor for the original chat. When a valid anchor
or session is unavailable, open the panel with clipboard browsing ready and
leave the assisted action disabled. No stale discovery result may replace the
captured recipient.

## Appearance

Use Android Views and official Material 3 components throughout. The main
launcher continues opening the existing carousel. The picker adopts that app's
carousel vocabulary, rounded shapes, colors, chips, and bottom action bar.

The panel is compact: approximately 360–400 dp wide, bounded by the available
display, with a maximum height around 520 dp. Insets and large text determine
the actual size. It keeps the current transparent host/window arrangement that
passed keyboard resize tests. The bottom actions remain outside the scrolling
media area and above the keyboard.

```
┌──────────────────────────────────────┐
│ SAO · Telegram fork           🔍   ×  │
│ 😂  ❤️  😭  😮  😠  …                 │
│                                      │
│  peek     LARGE REACTION     peek     │
│               ✓                      │
│                                      │
│ Love · Image copied       [Sticker]   │
├──────────────────────────────────────┤
│   Copy       Paste     Sticker  Media │
└──────────────────────────────────────┘
```

The delivery preference is a compact, labelled Media/Sticker control with an
active checkmark for both modes, beside
selection status, rather than a full-width switch above the images. Labels
describe preference; clipboard status describes the actual exported payload.

## Carousel and selection

Use `CarouselLayoutManager`, `MaskableFrameLayout`, and `CarouselSnapHelper`,
which are the same official component family already used by the main app.
The picker uses a centered hero with neighboring peeks and fits the entire
reaction inside its focal card. It does not crop a sticker to fill a photo box.
See the [official Views carousel documentation](https://github.com/material-components/material-components-android/blob/master/docs/components/Carousel.md).

- Opening, filtering, binding, scrolling, and rotation do not copy anything.
- A tap selects the actual media ID. A selected card has a primary-colored
  outline and a check badge; selection is also exposed to accessibility.
- Tapping a neighboring card brings it into focus and selects/copies that item.
- Swiping changes the focused item without changing the selected item or
  clipboard. Bottom actions continue to operate on the highlighted selection;
  its name stays visible in the dock when the carousel focuses elsewhere.
- GIFs animate only in the focal card. Offscreen/recycled requests are disposed;
  other cards show a still preview or GIF badge.
- Status is inline: `Copying…`, `Image copied`, `GIF copied`, or `Copy failed ·
  retry`. Android may still show its own clipboard preview.
- Copy success keeps the panel open. Re-copy is available from the dock.
- Changing the explicit delivery preference affects the next selection, Copy or Paste.
  It leaves the clipboard unchanged. An unavailable sticker path uses media and reports
  that actual result inline when the user explicitly copies.
- If filtering removes the selected item, clear the selection and disable its
  actions. Leave the clipboard untouched.

Keep focused ID and selected ID separate. Do not reuse the main fragment's
screen-coordinate hit-testing or global action mode. Share the visual component
family, not the carousel's download/bookmark/network behavior.

## Search

The default header has a search icon; no permanent text field. Pressing it
replaces the title area with an inline Material text field and focuses the
existing keyboard.

Search collapses and the keyboard dismisses when the user swipes the carousel,
taps a reaction, chooses a tag/emoji, taps a bottom action, or presses Search.
Back first leaves search, then closes the panel. A keyboard dismissal also ends
editing. The query is preserved throughout collapse and recreation.

When a query is active, show a concise query summary in the header and an active
search indicator. Reopening search restores the text and cursor. Clear removes
the query. Emoji/tag chips remain a single horizontally scrolling row.

In a short viewport, reduce the carousel height first. Keep close, search, and
the dock usable; arrange dock actions into two rows for large text rather than
clipping or hiding them. Search transitions are short and respect disabled
animations. They do not continuously resize windows or rebind the media list.

## Persistent bottom actions

The dock always has the same positions:

| Action | Behavior |
| --- | --- |
| Copy | Re-copy the highlighted selection. Enabled once a selection exists. |
| Paste | Explicitly paste into the original verified editor, then close the panel. Disabled without assistance and a valid anchor. Never presses Send. |
| Sticker | Telegram: send a static WebP to the actual detected/configured fork. WhatsApp: use the live scoped native handler. Disabled when the item or handler cannot support it. |
| Media | Explicitly open ordinary media delivery for the current supported target, or the Android sharesheet for an unknown target. |

No SAO action-options dialog or overflow menu. Disabled capabilities have a short
inline explanation, not another popup. Native insertion, Paste, and app handoff
close the panel only when the explicit action begins successfully. Failures
stay inline and do not silently retry sending.

Move rare pack-installation actions into Floating picker settings. The user
should not have to inspect a sticker-pack setup command every time they copy a
reaction. Keep the official immutable sample pack available there.

## Telegram forks

Add **Floating picker settings → Telegram apps**:

- Searchable multi-select list of launchable installed apps, with icon, label,
  exact package name, and Material checkboxes.
- Built-in Telegram packages are included by default. Add other apps without
  replacing those defaults.
- A manual package entry accepts comma/newline-separated names. Trim, validate,
  and deduplicate them; display malformed entries inline. Preserve exact case.
- Saved but uninstalled apps remain removable and show `Not installed`.
- Store custom packages in Preferences DataStore as a string set.

One `AppFamilyResolver` combines the built-ins and saved Telegram packages.
Keep `packageName`, display label, family, and actual capabilities separate.
Foreground classification, header, dock actions, clipboard policy, and
Accessibility support all use this resolver. Update the Accessibility service's
runtime package filter when mappings change.

**Routing must use the original exact package**, not the first official Telegram
installation found on the phone. Mapping a fork enables Telegram-style actions;
its real ability to handle WebP is checked separately. An unsupported fork keeps
copy and media fallback available. Custom mappings do not expand LSPosed scope.

Declare visibility for launchable apps and supported share intents rather than
requesting all-package visibility. See Android's [package visibility guidance](https://developer.android.com/training/package-visibility/declaring).

A manual mapping does not grant Usage Access or Accessibility permission. When
the foreground app cannot be detected, show `Clipboard` in the header. Let the
user select a configured destination in an inline app row for explicit handoff;
that manual choice does not create a Paste anchor or a native chat session.

## Floating icon (corrected interaction)

The active visual is 40 dp inside a 48 dp touch target. After the chosen idle
interval, fade to 35% opacity and slide halfway into the nearest screen edge over
250 ms. Keep the visible half touchable. Tapping opens the picker directly and
dragging expands the icon before moving it. Do not remove it merely for idling.

The bubble is removed while the picker is open or the screen is locked. Closing
the picker restores it fully and restarts the timer; an idle-tucked icon returns
tucked after unlocking. Notification and Settings Show icon restore full
visibility. Stop and permission revocation still remove the overlay/service.
Idle dimming can be disabled, and the delay remains configurable.

ValueAnimator updates opacity and window X once per frame. Cancel old animation
callbacks before restoring or dragging; cache geometry until display changes.

### Browsing correction

Use `CarouselSnapHelper(false)` so a strong fling retains RecyclerView momentum
and can cross several items, with focal snapping after settling. Keep focused ID
independent of selection. Hold its query/filter and focused ID in the activity ViewModel with
SavedStateHandle; use a small memory-only snapshot for close/reopen within the
app process. Browsing does not read or write DataStore.
Restore the query text, selected emoji/tag filter and focused card on reopen,
recreation and IME layout changes; reset only for a
changed search/chip query or a missing media ID. Skip transient loading-list
submissions that would reset the layout manager. Never copy during restoration.

## Implementation order and acceptance

1. Freeze audit, nonblocking opening, and diagnostic traces.
2. Shared app-family resolver, fork settings, exact-package routing, and dynamic
   Accessibility scope.
3. Selection state and serialized default-copy pipeline; persistent dock.
4. Material carousel and expandable header search.
5. Smaller icon, idle dimming/half-edge tuck, notification recovery, and consistent status.
6. Device verification, documentation, and a replacement APK.

Acceptance checks:

- Tap selects/copies exactly once without closing the panel; scroll/filter/
  recreation do not accidentally overwrite the clipboard.
- Rapid taps cannot let an older export overwrite the newest selection.
- Dock actions always reference the selected ID, even after browsing elsewhere.
- Collapsed search preserves the query; the dock stays reachable with IME,
  landscape, large text, and screen readers.
- A custom fork is classified and targeted by its exact package; removing a
  mapping updates UI and assistance scope. Unsupported sticker types fall back.
- The icon dims, remains half visible and touchable, restores from the notification,
  and behaves correctly during drag/open/lock/permission revocation.
- Slow optional services do not block the panel or trigger auto retries. Root
  insertion still rejects changed chats and stale/replayed requests.
- Repeat real-device stress runs with and without Accessibility/LSPosed. Do not
  declare the reported freeze fixed solely because emulator UI tests pass.
