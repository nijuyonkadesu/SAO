# Home and source collections

Tap the **Collection** heading to select an installed source profile, or use the pencil to manage sources. Category chips come from that extension, including its preset query and supported search mode. Install the updated **Immich 1.1.0** or **Local Sticker 1.4.0** APK for presets and filenames. Existing profiles/credentials remain in their extension.

The original 300 dp cards, 450 dp carousel, Material chips and bottom bookmark/download/share actions remain. Home reuses the floating picker's media adapter and focal-keyline controller: focused GIFs animate, one muted Media3 player previews the focused video, and actions use the actual focal card. Playback stops when hidden. The current result set, category and focus live in the ViewModel/SavedStateHandle, with no scroll/query preference writes. New indexing notifications refresh visible browsing without resetting focus or retrying a failed read.

[Light/dark home previews](screenshots/README.md#home-source-collections). The original README images remain the layout reference.

## Experimental All source

Enable **Settings → Media sources → All sources (experimental)**, then select **All** from either browser. Disabling it while selected returns to a real source. Only installed profiles participate. Credentials, authentication and provider APIs remain in their source APKs.

Each host page reads at most three provider pages with at most three concurrent requests, rotating fairly through profiles. New results are shuffled together; appending pages keeps already displayed cards in place. This is page-wise mixing, not a uniform random sample of every file in every library. Each provider keeps its own opaque continuation. Asset identity includes the owning profile, so equal asset IDs in different accounts remain distinct, and previews/originals/video/sequence requests route to the right owner. Configuration/membership changes invalidate the composite session and cancel its work. Exhausted providers stop receiving requests; repeated/cycling cursors stop pagination. Cursor history stores pagination metadata, not previous-query results.

All advertises common search modes and supported facets; People/Places are disabled because their IDs belong to a particular account. Context remains the default when common, otherwise Description is preferred when available. Preset categories are collected from the members. Random and sequence controls are offered when every member supports them.

A failed provider does not discard successful results or advance that provider's cursor. An inline message pauses automatic fetching until **Retry**. Retry resumes unfinished positions; completed providers are not fetched again. Local indexing notifications cannot restart a failed aggregate request. Existing bounded read retries still apply inside the ordinary extension adapter; there is no idle polling/reconnect loop or disk query cache.

## Filenames and original quality

Hold the **media title** in home or the floating picker to copy its original filename. Image holds retain chronological browsing. Both use the configured hold delay, initially 160 ms. Copying a filename supersedes any pending image-copy job; both publications share the clipboard dispatcher and ordering guard. Filename is a separate extension field: an edited description is never substituted for it. Older providers without filenames cannot offer this action.

**Media** copy/share/download preserves the original file's bytes, MIME type, resolution and transparency. Ordinary static photos no longer pass through a 512×512 JPEG canvas or receive white padding. Sticker exports keep their dedicated 512-pixel conversion. A new export namespace prevents reuse of earlier reduced-quality files. Synthetic tests cover the reported filename shape, a 3000×1800 JPEG, transparent PNGs, provider streams, title-hold copying and the Telegram Media clipboard path; no personal library is accessed. A receiving app can still apply its own compression.

Validation: 51 JVM checks and 26 Android 15 emulator checks passed, including 10 new home/aggregate/export checks and 16 existing floating/source/playback/chronology checks. Host/source SDK and both optimized source APKs passed lint with zero errors. Light/dark home and video captures were visually inspected.
