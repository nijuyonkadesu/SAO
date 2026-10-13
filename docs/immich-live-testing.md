# Live Immich testing

Use a development emulator. `LiveImmichDeviceTest` replaces its saved Immich connection through
the native settings form. It only reads the remote library; it does not upload, edit, delete,
create tags/albums, or send messages to another app.

The test is skipped by the normal suite. It requires both `liveImmich=true` and an app-private
`files/live_immich.json` containing `url` and `key`. Keep the input file outside the repository
with mode 0600. The API key is never a runner argument or clipboard item.

After installing the debug app and test APK, transfer the private JSON through stdin:

```sh
adb shell -T "run-as one.njk.sao sh -c 'umask 077; mkdir -p files; cat > files/live_immich.json'" < /path/to/private/connection.json
adb shell am instrument -w \
  -e class one.njk.sao.floating.LiveImmichDeviceTest \
  -e liveImmich true \
  one.njk.sao.test/androidx.test.runner.AndroidJUnitRunner
adb shell run-as one.njk.sao cat files/live_immich_report.json
```

The harness removes the plaintext input after saving and again during cleanup. The configured
connection remains AES-GCM encrypted in `noBackupFilesDir`. Delete the host input after testing.
Its report contains only counts, version and pass flags, with no keys, photo names, asset IDs,
descriptions, locations or image bytes. Treat any manually captured live-library screenshots
as private development artifacts.

On 2026-10-09 the supplied server passed in 36 seconds on Android 15/API 35. Host Tailscale
provided both MagicDNS resolution and direct connectivity inside the headless emulator; no
emulator VPN or ADB reverse was necessary. The one-hour wait completed at 19:15:04 UTC.
Sanitized local evidence is in `app/build/outputs/diagnostics/immich-live-report.json`.

The live run caught two adapter issues: an unsupported `isTrashed` structured-filter key, and
over-broad explicit-null serialization of an absent trash-date filter. The correct filter is
`trashedAt: {eq: null}`, with the outer filter still omitted when unset. It also caught a closed
pooled read connection. Read requests now use OkHttp connection recovery; mutation requests
retain disabled retries. Contract tests reproduce the disconnected search and verify an
ambiguous write is issued once.

The live library had GIFs and photos matching emoji input, but no native tags or albums.
Tag/album fixture coverage remains in `ImmichDeviceTest`; the live test does not invent or add
those fixtures to a user's library. Clipboard WebP validation verifies SAO's output, while
actual sticker delivery in a logged-in Telegram fork remains a separate client test.

## Emoji search parity

`LiveEmojiSearchTest` uses the same private input file and `liveImmich=true` flag. Select this
class instead of `LiveImmichDeviceTest` to compare typed picker queries against Immich web's
smart-search request (`query`, `language=en`, first page, timeline scope across images/videos). It compares
the ranked asset IDs in memory and records only counts and pass flags in
`files/live_emoji_report.json`. No photo names, asset IDs or credentials are written to the report.

The emoji correction removes SAO's former exception that forced emoji-only and mixed text/emoji
to metadata search while smart search was enabled. The normal image/result caches are retained.
Mock tests cover untouched Unicode queries, variation selectors, skin tones, ZWJ sequences and
selected tag filters. The metadata setting remains an explicit user opt-out from smart search.

The 2026-10-09 live device run passed for `😂`, `😭`, `💀`, `👩🏽‍💻` and `happy 😂`.
Each query returned the same 100 image IDs in the same order as the web request. The eight
Immich device regression tests and all build variants also passed. Sanitized evidence is in
`app/build/outputs/diagnostics/immich-emoji-search-report.json`.


The 2026-10-10 parity harness compares web default Context across **all images/videos**, rather
than the earlier image-only subset. It includes `skill` and `GIF` plus the five Unicode queries;
all seven matched the website's 100 ranked assets. Both text examples also rendered their first
MP4 in the actual floating carousel. The previous image-only comparison did not expose the
excluded-video bug. Sanitized evidence: `immich-all-media-search-report.json`.

Named profiles use `noBackupFilesDir/source-profiles-v1`. `LiveEmojiSearchTest` temporarily updates
its fixture profile and restores the complete previous catalog in finally. The opt-in test input
is still removed after each run. Re-provision it before running another opt-in class.

## Context pagination

`LiveImmichPaginationTest` uses the same opt-in flag/private input (or the already encrypted
configuration for the supplied development server). It compares three 100-asset Context pages
against a 300-asset structured reference for `skill`, `GIF` and `😂`. IDs are compared only in
memory; the report contains query/count/page/version/pass flags. It removes the plaintext input
in finally and never writes to the server. The runner command is the same as above with this
class name; its app-private report is `files/live_pagination_report.json`.

The 2026-10-10 API 35 run matched all 300 ranked results for each query and confirmed a fourth
page was available. Sanitized evidence: `app/build/outputs/diagnostics/pagination/live-report.json`.
Normal regression fixtures additionally traverse 1,205 results, exercise short/unsupported
pages and validate cancellation, explicit failed-page retry and query/focus preservation.
