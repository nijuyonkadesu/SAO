# Immich backend

Research date: 2026-10-09. Latest stable at that date: **v3.3.1**, released 2026-10-08.

The floating picker previously used seven bundled sample images/GIFs. It now has a dedicated
`:immich` Android library (Retrofit suspend API, Moshi DTOs, scoped OkHttp client), with an app-side
`SwitchingMediaSource` that routes to samples or the selected named source profile. Normal launcher
behavior remains unchanged. The client has no dependency on the picker, Hilt, Views, or Compose.

## Sources and compatibility

Primary sources:

- [Official API guide](https://docs.immich.app/api/) and [API explorer](https://api.immich.app/).
- [Pinned v3.3.1 OpenAPI specification](https://github.com/immich-app/immich/blob/v3.3.1/open-api/immich-openapi-specs.json).
- [v3.3.1 release](https://github.com/immich-app/immich/releases/tag/v3.3.1).
- [Search behavior](https://docs.immich.app/features/searching/), [tags](https://docs.immich.app/features/tags/),
  [photo editing](https://docs.immich.app/features/editing/).
- [Pinned upload/download controller](https://github.com/immich-app/immich/blob/v3.3.1/server/src/controllers/asset-media.controller.ts)
  and [CLI upload implementation](https://github.com/immich-app/immich/blob/v3.3.1/packages/cli/src/commands/asset.ts).

This implementation targets **Immich 3.2+ within major version 3**, with its request/response
contracts checked against 3.3.1. Search's structured `filter`, `orderBy`, and opaque `cursor`
were introduced in 3.2. Old flat filters and numeric `page` are deprecated but still supported
in 3.x; smart search uses that paged Context route because the structured variant has no
rank-aware continuation yet. Metadata search uses the structured route. Connection validation
rejects older major/minor versions and future major versions rather than claiming compatibility.
A 1.x/2.x adapter would need a separately verified schema and pagination strategy.

Immich publishes TypeScript/Dart SDKs generated from OpenAPI. A small typed Kotlin client fits
SAO's existing Retrofit/Moshi stack better than bundling hundreds of unrelated generated endpoints.
Unknown response fields are tolerated; request payloads follow the pinned schema.

## Supported operations

Paths below are relative to `/api/`. Server URLs may include a reverse-proxy path; the client
preserves that prefix and accepts either a server-root URL or an existing `/api` URL.

| Area | Endpoints | Key permissions / behavior |
| --- | --- | --- |
| Compatibility | `GET server/version`, `GET server/features` | Check structured-search support and smart-search availability |
| Key inspection | `GET api-keys/me` | Returns key ID/name/permissions, not its secret |
| Metadata search | `POST search/metadata` | `asset.read`; structured filters, opaque cursor, 60 assets/page in picker |
| Semantic search | `POST search/smart` | `asset.read`; server ML/index required; 100 assets/page, follows server `nextPage` |
| Random browsing | `POST search/random` | `asset.read`; structured filters, direct asset array, 100 items per explicit tap |
| Sequence browsing | `GET timeline/buckets`, `GET timeline/bucket`, `GET assets/{id}` | `asset.read`; optional hold-to-browse using Photos ordering, a streamed bucket ID index and bounded metadata hydration; internal timeline contract |
| People filters | `GET people`, `GET search/person`, `GET people/{id}/thumbnail` | `person.read`; 40 people/page, bounded name lookup and portrait downloads |
| Place filters | `GET search/suggestions?type=country/state/city` | `asset.read`; country/state constrain dependent suggestions |
| Asset details | `GET assets/{id}` | `asset.read`; IDs are UUIDs, original disk paths are not download URLs |
| Previews | `GET assets/{id}/thumbnail?size=preview&edited=...` | `asset.view`; thumbnail/preview/fullsize options; GIF previews flatten animation |
| Originals | `GET assets/{id}/original?edited=...` | `asset.download`; streaming, caller-owned output file |
| Upload preflight | `POST assets/bulk-upload-check` | `asset.upload`; SHA1 hex/base64 checksum and caller identifier |
| Upload | `POST assets` multipart | `asset.upload`; original file bytes, timestamps; 201 created / 200 duplicate |
| Metadata editing | `PUT assets/{id}` | `asset.update`; description/favorite/date/location/rating/visibility/live-photo link |
| Photo editing | `GET/PUT/DELETE assets/{id}/edits` | `asset.edit.get/create/delete`; non-destructive crop/rotation/mirroring |
| Custom metadata | `GET/PUT assets/{id}/metadata` | `asset.read/update`; namespaced JSON objects, useful for future SAO mappings |
| Albums | `GET/POST albums`, `GET/PATCH/DELETE albums/{id}` | `album.read/create/update/delete` |
| Album membership | `PUT/DELETE albums/{id}/assets` | `albumAsset.create/delete`; body `ids`, per-asset success/error results |
| Tags | `GET/POST/PUT tags`, `GET/PUT/DELETE tags/{id}` | `tag.read/create/update/delete`; upsert supports hierarchical paths |
| Tag membership | `PUT/DELETE tags/{id}/assets` | `tag.asset`; same bulk `ids` contract |
| Trash | `DELETE assets`, `POST trash/restore/assets` | `asset.delete`; delete defaults to trash, permanent deletion requires explicit `force=true` |

Upload, editing, album/tag mutation and trash methods are module capabilities for subsequent
management features. Browsing and source selection never call them. This change does not add
a gallery editor, automatic upload job, sync engine, or remote deletion controls.

### Search and emoji

Metadata search can combine filename/description patterns, album/tag IDs, asset type, visibility,
trash status and sorting. The picker includes images and videos in server order, includes timeline/archive assets,
and excludes hidden/locked/trashed assets. The structured trash condition is
`trashedAt: {eq: null}`; `isTrashed` is not a supported search-filter key. A narrowly scoped
Moshi adapter preserves this explicit null while omitting unrelated optional fields.
Search type is selected in the expanded picker and defaults to Context. The
other modes match one explicit metadata field rather than combining filename
and description. Literal `%`/`_` in pattern text are escaped. Tags are fetched
once per active connection in memory.

| Picker mode | Request text field |
| --- | --- |
| Context | `POST search/smart`: original Unicode `query`, numeric `page` |
| Filename | `POST search/metadata`: `filter.originalFileName.like` |
| Description | `POST search/metadata`: `filter.description.like` |
| Full path or folder | `POST search/metadata`: `filter.originalPath.like` |
| OCR | `POST search/metadata`: `filter.ocr.matches` |

Metadata patterns match the whole trimmed input as a literal substring; OCR
uses Immich's text-search matcher. Empty input browses metadata with the active
filters. People selections require all selected people. Country/state/city and
media type constrain both Context and metadata requests, alongside the configured
album scope and emoji tag filter. Context uses the native flat filter fields;
metadata uses the structured filter. Choices are scoped to the connection
identity, with source checks before and after each request. People IDs/location
names from one profile cannot be used to filter another.

The ViewModel loads people and place choices only when their section opens.
People list pagination is separate from asset pagination. Name lookup follows
the server's bounded person-search endpoint. Optional portraits download through
the authenticated client into the same bounded account-scoped media cache (2 MiB
maximum each); credentials never reach Coil. Missing `person.read` surfaces an
inline error, with an explicit retry rather than repeated background requests.

**Emoji is valid input.** In smart-search mode, emoji-only input and mixed text/emoji use
`POST search/smart` with the original Unicode query. There is no emoji-specific metadata
fallback. This follows [Immich's web search manager](https://github.com/immich-app/immich/blob/v3.3.1/web/src/lib/managers/search-manager.svelte.ts)
and [web endpoint selection](https://github.com/immich-app/immich/blob/v3.3.1/web/src/routes/%28user%29/search/%5B%5Bphotos%3Dphotos%5D%5D/%5B%5BassetId%3Did%5D%5D/%2Bpage.svelte).
When the user explicitly selects a metadata mode, text searches only that mode's
field. Emoji chips are exact tag filters in every mode.
They come from actual Immich tags; a hierarchical tag such as `reactions/😂` has the leaf name
`😂`. Tag-name normalization preserves ZWJ sequences and skin tones while ignoring variation
selectors; semantic query text is not normalized or translated by SAO.

An untagged, unlabeled photo is not guaranteed to be discoverable by an emoji. There is no
Emoji Kitchen lookup or automatic emotion classifier here. Semantic searches require server
indexing and suitable model language.

Metadata pagination follows opaque `nextCursor`. Context smart search sends a flat `query`,
`page` and `size=100` and follows the server's `nextPage`; it does not combine these fields with
a structured `filter`/`cursor`. The [pinned search service](https://github.com/immich-app/immich/blob/v3.3.1/server/src/services/search.service.ts)
explicitly leaves structured smart-search cursors unimplemented, while its flat route retains
numeric pagination. The [repository](https://github.com/immich-app/immich/blob/v3.3.1/server/src/repositories/search.repository.ts)
orders semantic results by distance and asset ID before applying the page offset.
There is no 100/1,000-result total cap in the picker. Pages stop when the server has no continuation.

**I’m feeling lucky** uses the [pinned random-search contract](https://github.com/immich-app/immich/blob/v3.3.1/server/src/dtos/search.dto.ts)
with the same structured album/tag/people/place/media/visibility/trash filter,
but no query text, cursor, order or rank fields. The
[server repository](https://github.com/immich-app/immich/blob/v3.3.1/server/src/repositories/search.repository.ts)
orders eligible assets by PostgreSQL `random()` before applying the requested
size. SAO requests 100, decodes the endpoint's direct asset array, and does not
enumerate the library or locally shuffle a ranked prefix. Random browsing does
not require the ML index. Each explicit reshuffle fetches a new batch; reopening
uses only the current process-session batch. Batches can overlap and there is no
cross-batch exclusion history. Scrolling does not request more random batches.

Flat Context results are filtered to IMAGE/VIDEO and timeline/archive before publication, excluding
hidden Live Photo components and audio. Unsupported-only pages advance serially until usable
results or the end. Album IDs and a selected single tag are sent to the server. When multiple
hierarchical tags share an emoji leaf name, flat tag matching means ALL rather than ANY; a bounded
metadata lookup of that page's IDs retains the original ANY semantics and semantic order. Flat
Context's ownership scope can differ from structured search for other users' shared albums; the
live check covered the user's own library, which had no albums.

The picker adopts the existing main carousel's `inHousePagination` bind-trigger pattern: binding
within ten results of the end requests the next page. Scroll focus also triggers it, and a short
appended page can trigger the next read without requiring another gesture. Only one page request
is active; changed queries/sources cancel it. IDs are deduplicated and repeated or malformed
continuations stop. A failed page keeps the displayed results and offers an explicit retry after
the bounded read retry. Opening the picker does not enumerate the library.
The most recent query's already-browsed pages, focus and query live
in process memory, with scalar query/focus restoration through `SavedStateHandle`. No browsing
state is written to preferences. Large-library metadata accumulates only as the user browses.

### Upload and editing details

Current upload requires `assetData`, `fileCreatedAt`, and `fileModifiedAt`. Older examples that
require `deviceId`/`deviceAssetId` do not describe the current contract. `ImmichClient.upload`
streams a `File` as multipart and keeps its filename/MIME type; it does not recompress images.
Optional duration/sidecars/custom metadata can be added as explicitly typed multipart fields
when an upload UI needs them. Preflight can avoid duplicates, but upload's duplicate response
must still be handled. Mutations disable transport retries to avoid repeating ambiguous writes.
GET/HEAD and the explicitly listed read-only POST search (including random)/preflight routes use OkHttp's
connection recovery, sharing the same pool and credential/redirect policy. This recovers closed
pooled sockets without retrying uploads, edits, membership changes or deletions.

Photo edits are an ordered list of crop coordinates/sizes, integer rotation angles from 0–270° and
horizontal/vertical mirror parameters. Originals remain intact; resetting uses the edits DELETE
route. Download/preview calls expose `edited` explicitly. The picker uses edited still images
and original GIF/video bytes to retain animation. Camera/RAW/TIFF exports use Immich's normalized preview,
so they do not depend on Android decoding their originals or applying camera orientation.
PNG/WebP exports use original bytes to retain transparency even when the server generates JPEG
previews. Edited variants follow the server's output format. Exports retain the existing 512px
behavior. The module separately exposes full
original downloads. Animated sticker conversion remains unavailable.

Patch models distinguish omitted values from explicit null for nullable fields. An album
description is cleared with null; an asset description is a string (empty clears it). Rating
can be null (unrated), -1 (rejected), or 1–5; 0 is not valid in v3. Album updates use PATCH;
asset metadata updates use PUT. Album membership is not a replacement operation and can
partially fail, so callers must inspect every bulk result.

## Configuration and data handling

Floating settings → Media sources lists named connections. Add Immich source accepts a name,
URL/API key and an album scope. Any number of Immich profiles can be configured.
Search type now belongs to the picker rather than connection settings. Context
is the default, and the old stored smart-search flag is retained only for
configuration identity/migration compatibility, not request routing.
Test connection loads accessible albums when `album.read` is granted. Save validates version,
key permissions, optional album access and an actual authenticated search. The compact picker
also exposes source selection. Choosing Samples retains every configured connection. The picker lists profiles inline with
selection checkmarks and an edit action; long names collapse to the source icon in a narrow header.

Minimum picker key permissions: `asset.read`, `asset.view`, `asset.download`, `tag.read`.
Add `album.read` to list/choose albums. Grant mutation permissions only for management features
that use them; the picker does not need upload/update/delete permissions.
Add `person.read` to use the optional People filter and its portraits.

Credentials use `x-api-key` on the dedicated Immich client's requests. They never enter URLs,
request logs, global Coil headers or the existing Waifu client. Redirects are disabled, including
same-host redirects; configure the final server URL. TLS uses normal Android trust verification.
Explicit HTTP URLs work for LAN setups; HTTPS protects credentials in transit.
Connection recovery follows [OkHttp's documented policy](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/OkHttpClient.kt).

Connection settings are AES-GCM encrypted using Android Keystore and atomically stored under
`noBackupFilesDir`. The password field excludes Android view saved state and autofill. Photos
download to account/settings-scoped cache filenames; exports include account scope and asset
revision so another account or edited version cannot reuse stale bytes. Cache downloads are
atomic, concurrency-limited and bounded (8 MiB preview / 64 MiB original).
Cached media is trimmed toward 192 MiB after downloads, retaining recently requested files to
avoid interfering with in-flight decoding; active/recent transfers can temporarily exceed that target.
Network/file reads
run on IO, cancellation closes streaming bodies, and bitmap transformations use the existing
bounded CPU dispatcher. Media leaves SAO through its existing FileProvider flow.

## Validation and limits

MockWebServer contract tests exercise URL/auth scoping, UTF-8 emoji filters, cursor requests,
current multipart uploads, edit actions, album partial failures, explicit-null patches, redirects,
atomic bounded downloads and authentication errors. Android tests exercise encrypted storage,
retained credentials on a local switch, tag-backed emoji search, remote GIF animation bytes,
account isolation and in-memory picker reopening. Existing picker/delivery regressions are also run.

Live interoperability was verified on 2026-10-09 against the supplied Immich **3.3.1** server
after the requested one-hour delay. A headless API 35 emulator reached the Tailscale hostname
directly through the host network, without port forwarding. The native connection form,
encrypted save, two 60-image cursor pages, literal/emoji search, smart search, real thumbnail
decoding, immediate 512px WebP copy, explicit JPEG copy, unchanged GIF bytes, carousel/query
restoration and opening the half-hidden floating icon all passed. The server exposed zero
native tags/albums; emoji input still found five metadata matches. Album/tag matches remain
covered by mock-server integration tests rather than live fixtures. No remote assets were changed.
See [live test instructions](immich-live-testing.md) for the opt-in harness and sanitized evidence.

Videos, including MP4, appear beside still images and GIFs. One focused video plays muted and
loops using Media3 ExoPlayer; authenticated originals are downloaded into the bounded cache
before playback. Media/clipboard delivery preserves the original file and MIME. Motion is never
flattened into a static sticker. Audio-only assets, HLS streaming, shared-link
auth, OAuth/password login, partner/person sharing, server administration and full album-user
management remain separate capabilities. A scoped API key is sufficient for this integration.


## Named source ownership and failures

`SourceProfiles` owns an encrypted catalog and selected profile ID. Each profile has a stable UUID,
user name and typed configuration. The old encrypted Immich connection migrates once; credentials,
smart-search opt-out, album scope and Samples selection are preserved. Migration writes the new
catalog atomically before removing the legacy file. Browse position/query remain in memory.

`SourceConfigurationCodec` handles persistence per provider kind. A Hilt map of lazy
`MediaSourceFactory` providers creates a single active session on first page/filter/media demand.
`ImmichMediaSource` contains only Immich browsing/cache behavior; `SwitchingMediaSource` handles
selection, ownership and disposal. Adding another kind requires its codec/factory/source, not a
class per account. Unknown provider settings survive an older build without activation.

Each runtime identity combines profile UUID with a connection/settings fingerprint. Renaming does
not recreate a client and can be saved without contacting an offline server. Switching, changing credentials/settings or deleting the selected profile
cancels in-flight work and closes the old HTTP client's calls, pool and executor. Requests remain
children of their caller's coroutine and also observe session cancellation. Late result/clipboard
publication is checked against the current identity. Deleting the selected profile selects Samples.
The downloaded-media and result caches remain; file/export identities now include profile and
configuration scope. Same server/asset IDs in different accounts cannot reuse bytes.

`ReadRetry` is restricted to provider reads and connection checks: at most one additional attempt,
with cancellable 500–1000 ms jittered delay, for IO/socket timeouts and HTTP 408/429/502/503/504.
Authentication, validation and TLS errors are not retried. Short numeric Retry-After delays are
honored; long or date-based cooldowns surface the failure for a later explicit retry. There is no
background polling or retry loop. OkHttp's read-only socket recovery remains distinct; mutation
requests still have automatic transport retries disabled. Downloads have three permits and striped
file locks; tag loading is serialized to avoid concurrent duplicate fetches.

The architecture follows [Android's data-layer guidance](https://developer.android.com/topic/libraries/architecture/data-layer)
and [Kotlin's cancellation model](https://kotlinlang.org/docs/cancellation-and-timeouts.html).
Picker query/source paging uses [flatMapLatest](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/flat-map-latest.html)
to cancel superseded result streams.

## MP4 playback and Context parity (2026-10-10)

The earlier image-only filter excluded the web's first results for both `skill` and `GIF`: 15 of
its first 20 assets were MP4, with no native image/gif in those sets. Shared static images had the
same relative ranking. Search now requests IMAGE and VIDEO and preserves their order; GIF assets
classified by Immich as VIDEO are also retained. Smart search remains the default for all text,
emoji and mixed queries, without an OCR/description priority override.

[Media3 1.11.1](https://developer.android.com/jetpack/androidx/releases/media3) supplies the native
Views ExoPlayer/PlayerView implementation. A TextureView participates in Material carousel clipping
and masking ([official surface guidance](https://developer.android.com/media/media3/ui/surface)).
Playback waits until RecyclerView is idle, followed by a 200 ms stabilization delay for video.
Dragging/flinging pauses GIFs and releases the video player; only the final focused card resumes.
This avoids decoder creation on cards crossed during a fling. Unchanged badge text is assigned
only when binding an item, avoiding repeated layout requests as focus moves. See the
[fast-scroll regression evidence](../diagnostics/scroll-jitter/README.md).
Audio tracks are disabled; buffers are capped at 8 MiB and 5 seconds.
The player releases when focus moves, the card detaches, a source/target panel hides it, or the
activity stops/destroys ([lifecycle guidance](https://developer.android.com/media/media3/exoplayer/hello-world)).
ExoPlayer calls run on Main; downloads run on IO and decoding runs on Media3's workers. Thumbnail
posters remain visible until the first video frame and after a playback failure.

Live API 35 checks against Immich 3.3.1 matched all ranked results for `skill`, `GIF`, `😂`, `😭`,
`💀`, `👩🏽‍💻` and `happy 😂`, and rendered the first MP4 for both text examples. No remote media
was modified. Evidence: `app/build/outputs/diagnostics/immich-all-media-search-report.json`.


Final checks passed on 2026-10-10: debug/release/prerelease builds; 12 app JVM and 12 module
contract tests; 41 distinct Android regression tests (39 in the broad suite plus two additional
HTTP cancellation/cooldown tests). All eight source-device cases passed after the final behavior
changes. The live scenario also added a second named profile through the real form and verified
same-kind switch/search parity. Native source rows were checked at normal and 200% text size.
Lint completed with zero errors; warning details remain in its generated report. Temporary
plaintext test credentials were removed, and source/APK/log auditing found no key disclosure.

### Infinite Context results (2026-10-10)

The earlier top-100 structured smart-search request was the cause of truncated Context results.
Regression fixtures now traverse 1,205 ranked assets across 13 pages, check unsupported-page
skipping, tag ANY semantics, invalid continuations, bind-trigger prefetch, failed-page retry and
close/reopen preservation. A read-only live API 35 check loaded 300 results across three pages
for each of `skill`, `GIF` and `😂`, matched the first 300 structured reference results in order,
and confirmed more pages remained. Sanitized evidence:
`app/build/outputs/diagnostics/pagination/live-report.json`.
