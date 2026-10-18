# Source extension APKs

Install SAO and `immich-sao.apk`, then open **Settings → Media sources → Add source → Immich**. Give each connection a name. Repeat for more servers, accounts or album scopes; those **profile names** appear in the floating picker's source switcher. Edit a source to open its extension's settings. Context remains the default when supported.

Source extensions update independently of SAO. Version 1 accepts extensions signed by the same publisher as SAO; debug builds must use the same debug certificate. It does not load arbitrary third-party code into SAO. The existing Rays integration remains supported during this transition. [Local Sticker](local-sticker-source.md) provides named folders, per-album processing controls, offline labeling/OCR, optional CLIP Context search and metadata corrections in a separate APK.

[Profile picker and light/dark extension settings previews](screenshots/README.md#source-extension-profiles).

## Ownership

| SAO | Extension APK |
| --- | --- |
| Picker, search controls, carousel and playback | Named profiles and their private configuration |
| Request timing, cancellation and pagination orchestration | Authentication, API paths and domain query translation |
| Bounded read retry policy | One requested page and its opaque continuation |
| Shared media cache, export and WebM conversion | Authenticated read-only media streams |
| Clipboard, chat paste and Vector/LSPosed | Extension-specific setup and connection verification |

The small `source-api` library defines the contract. `immich-provider` translates Immich API records; `immich-extension` supplies its private store, settings Activity, bound service and streaming provider. SAO's production dependencies include neither module nor the Immich client. Common UI, player, cache and messaging libraries are not dependencies of the extension.

A Local Sticker profile owns several persisted folder grants/collections. SAO receives the same profile records and pages, without knowing about folder scanning, OCR, models or metadata overrides.

## Profiles and capabilities

A public profile contains `id`, `name`, `revision` and a short display `summary`. SAO stores those references plus the extension component. It receives **no server address, API key, folder grants or credential object**. The extension encrypts Immich configuration using Android Keystore AES-GCM and an atomic file in its private, non-backed-up storage. Editing only a name preserves the revision; changing credentials or album scope creates a new revision.

SAO namespaces source identity and cache entries by extension, profile and revision. Switching source cancels the prior session and its requests. An item from another source cannot be exported through the new session. Removing/reconfiguring a source removes its old host media cache. Missing or disabled APKs retain their public source reference and report an error; the host can remove that unavailable reference.

Capabilities declare supported search modes (`CONTEXT`, `FILENAME`, `DESCRIPTION`, `PATH`, `OCR`), facets (`PEOPLE`, `PLACES`, `MEDIA`, `EMOJI_TAGS`), a default mode, random browsing and chronological neighbours. SAO disables unsupported controls and normalizes requests against this declaration. Immich checks server features; unavailable Context/OCR modes are disabled and Filename becomes the fallback. There is no provider-name switch in the generic search UI. Emoji text is passed intact to Context, not copied into metadata fields.

An optional v1 `changes` capability carries a granted, source-owned content URI. SAO validates its owning package and observes it only while the picker is visible. Notifications are coalesced to at most one local refresh per second; matching items/metadata merge without clearing the list or focus. Random batches remain stable. Older v1 clients ignore this optional field. Extensions without it need no observer or extra API calls.

## IPC contract v1

An installed APK exports a service handling `one.njk.sao.source.EXTENSION`, with manifest metadata `one.njk.sao.source.API_VERSION=1` and `one.njk.sao.source.SETTINGS=<fully qualified Activity>`. SAO uses a targeted package-visibility query and verifies the signing certificate. A signature permission protects binding/settings; the service and settings Activity also verify the caller UID and certificate. No persistent binding, background discovery loop, wakelock or network polling is required.

`ISourceExtension.descriptor()` supplies the protocol version. `request(requestId, profileId, operation, JSON, callback)` and `cancel(requestId)` are asynchronous AIDL calls. Payloads are bounded to 64 KiB per request and 256 KiB per response, measured conservatively as UTF-16. There are at most 24 active requests in a provider; work runs on IO with limited parallelism. Binder caller UID is captured before dispatch; caller death cancels its work. Service cancellation completes pending callbacks, including requests cancelled before execution, so a live host can reconnect without waiting for the request timeout. An incompatible protocol is rejected before executing operations.

| Operation | Result |
| --- | --- |
| `profiles` | Up to 100 public named profiles and an opaque continuation; no network required |
| `capabilities` | Supported modes, facets and optional features |
| `search` | Up to 100 items plus opaque `next` cursor |
| `filters`, `people`, `places` | Supported filter choices; people may be paginated |
| `media`, `personMedia` | Granted read-only `content://` stream URI |
| `sequenceOpen`, `sequencePage` | Timeline items, directional cursors and opaque sequence token |
| `deleteProfile` | Remove a profile only if its revision still matches; a mutation, never automatically retried |
| `import` | Idempotent, signed-caller-only migration of one old encrypted connection |

Profile discovery cursors include a public-list fingerprint; changes between pages fail explicitly instead of silently dropping profiles. All other profile reads carry the expected configuration revision and host source identity. SAO tags capability updates with the owning source identity, preventing a previous profile’s modes from changing the current search. Media records contain a source-scoped ID, display metadata, format and opaque asset reference, not an authenticated URL. Sequence tokens/cursors are opaque to SAO. Immich's sequence token supports reopening after the bound service has stopped; chronology remains the Photos timeline order.

The legacy migration is the sole credential handoff: an old SAO installation passes its existing opaque configuration once to the trusted Immich extension. SAO replaces and erases its old credential record **only after acknowledgement**. If the extension is absent or migration fails, that encrypted legacy record remains intact and inactive. Fresh configurations are entered exclusively in the extension.

## Errors and retry policy

Callbacks return a safe `{ "code": "…", "retryAfter": "…" }` failure, never raw exception text, response bodies, URLs or secrets. Reliable media pipes use the same error shape. Cancellation remains coroutine cancellation rather than an error toast.

| Code | SAO behaviour |
| --- | --- |
| `UNAVAILABLE` | Keep the selected source; ask to install/enable its extension |
| `DISCONNECTED` | One delayed rebind for reads; then report the disconnected extension |
| `UNTRUSTED`, `INCOMPATIBLE`, `INVALID_RESPONSE` | Stop; explain signing/update/protocol problem |
| `PROFILE_MISSING`, `PROFILE_CHANGED` | Stop stale work; refresh sources or reopen configuration |
| `PERMISSION_REQUIRED`, `MEDIA_CHANGED` | Restore source folder access, or search again for the current file |
| `HTTP_401`, `HTTP_403` | No automatic retry; ask to fix extension credentials/permissions |
| `HTTP_404`, `MEDIA_TOO_LARGE` | Report unavailable media or the transfer-size limit |
| `NETWORK`, `HTTP_408/429/502/503/504` | At most one delayed retry per read |
| `TIMEOUT`, `TLS` | Stop and offer an explicit retry/settings correction |
| `UNSUPPORTED`, `LIMIT_EXCEEDED`, `REQUEST_FAILED` | Stop this operation; do not silently switch profiles |

A read gets at most **two attempts**, with 500–1000 ms jitter. Numeric `Retry-After` values up to five seconds are honoured; longer or date-based cooldowns require a later explicit retry. Mutations/settings verification are not retried. The provider disables OkHttp's own connection retry, avoiding nested retry budgets. Binding times out after five seconds, provider operations after 85 seconds, and host requests after 90 seconds. Repeated or backwards cursors are rejected; serial advancement through empty filtered pages remains cancellable. Failure does not start an idle reconnect/retry loop.

## Media cache

Media bytes live only under SAO's `cacheDir/source-media`, partitioned by profile identity and configuration revision. The extension streams directly from its authenticated HTTP response through a cancellable pipe and keeps no media file cache. SAO publishes a file atomically only after a complete successful stream; failures/cancellation remove `.pending` files and never poison a successful entry. Interrupted-process staging files are reclaimed on first cache use.

Completed media have a shared **192 MiB** LRU budget. Transfers are limited to three; individual originals/videos are bounded to 64 MiB, previews to 8 MiB and person thumbnails to 2 MiB. A provider can declare opaque variant cache keys when multiple operations return identical bytes: an animated GIF preview and its original then share one transfer and cache entry. Previews using the original variant have the same 64 MiB limit. Temporary transfers can add up to three bounded staging files. Eviction runs after downloads, not after every cache hit. OS cache eviction is safe; the next request fetches again. Search responses are not persisted or reused across new searches. Existing in-memory picker state still preserves the current results/scroll when reopening the picker.

## Build and validation

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :immich-extension:assembleRelease
./gradlew :source-api:testDebugUnitTest :app:testDebugUnitTest :immich:testDebugUnitTest
```

Sign the extension's optimized release APK with the same certificate as SAO before installing **both APKs** using Android CLI. The extension is approximately 3.1 MiB; development builds without shrinking are larger. Cross-process instrumentation uses localhost synthetic fixtures for profile isolation, capability recovery, API headers, streams/cache, cancellation and extension settings; it never accesses a configured personal library. Live-server tests remain explicit opt-in.

Validation passed: 51 JVM tests and 81 distinct Android 15 emulator checks across the source-extension changes, covering lifecycle, search, pagination, sequence restoration, media, local indexing and visible-only index notifications. Local checks include official CLIP reference outputs, download recovery, 120,000-vector ranking, per-album pause/resume/restart and constrained-job checkpoints. The real folder-picker flow passed against the optimized Local Sticker APK with PNG/GIF/MP4/WebM indexing, Context search and original-byte exports. Host, source SDK and Local Sticker lint passed with zero errors; settings were visually checked in light/dark themes. Host DEX inspection confirms that Immich and Local Sticker implementation classes and local ML models are absent from SAO. The album-controls/Context update requires only the extension APK; SAO's app code is unchanged.

Protocol design follows Android's [bound-service lifecycle](https://developer.android.com/develop/background-work/services/bound-services), [AIDL threading rules](https://developer.android.com/develop/background-work/services/aidl) and [targeted package visibility](https://developer.android.com/training/package-visibility/declaring). Installed APK metadata/version/signing discovery takes inspiration from [Mihon's extension loader](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt); SAO uses separate-process IPC rather than loading extension classes into the host.
