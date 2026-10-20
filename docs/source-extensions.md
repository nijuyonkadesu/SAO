# Source extension APKs

Install SAO and `immich-sao.apk`, then open **Settings → Media sources → Add source → Immich**. Give each connection a name. Repeat for more servers, accounts or album scopes; those **profile names** appear in both the home Collection selector and the floating picker. The source edit action opens the extension settings directly. Immich and Local Sticker include importing in that same page, with Material section headings and dividers. Context remains the default when supported.

Source extensions update independently of SAO. Version 1 automatically accepts extensions signed by SAO’s publisher. Other publishers require explicit approval of their package and current signing certificate; the APK remains labelled unverified. It does not load arbitrary third-party code into SAO. SAO includes no sample source; installing and configuring a provider supplies its media. Profiles from uninstalled providers are hidden without deleting their saved configuration. The existing Rays integration remains supported during this transition. [Local Sticker](local-sticker-source.md) provides named folders and selected-media collections, configurable folder/photo/file pickers, per-album processing controls, offline labeling/OCR, optional FP32/8-bit CLIP Context search, user-owned model folders and metadata corrections in a separate APK.

[Profile picker and light/dark extension settings previews](screenshots/README.md#source-extension-profiles).

[Moeview](moeview-source.md) is another independent APK, serving named Yande.re/Konachan/Gelbooru/Danbooru profiles through the unchanged v1 contract. Its tag queries, board-specific routes, optional credentials and browser verification remain in that extension; SAO needs no provider-specific code or APK update.

[FindThatMeme](findthatmeme-source.md) adds an experimental public, keyless meme/GIF/video catalog as another independent APK. It supplies keyword search, image/video filters, pagination and home presets through the unchanged v1 contract; SAO needs no update. [Public meme source research](public-meme-source-research.md) records the provider comparison and tested limitations.

In **Settings → Media sources → Show in floating picker**, each profile has a switch. Turning it off removes that profile from the floating picker and its All results, while keeping it available on the main screen. Credentials, settings and cached media are retained. The preference survives restarts and extension-profile refreshes. Disabling the active floating source cancels that session and chooses another enabled source; with none enabled, the picker offers **Manage sources**. Main-screen browsing and its All membership are unchanged. Only an actual switch change writes this host preference.

[Light/dark visibility controls and empty-picker guidance](screenshots/README.md#floating-source-visibility). Validation passed four targeted preference/routing/UI checks, 13 existing source/home checks, 31 host JVM checks and host lint with zero errors. The controls were visually checked on Android 15. Home now keeps its loading sticker inside each card and warms a small preview window; [current loading behavior](home-source-browser.md).

The main screen and floating picker use separate selection keys in the same host preference store. Changing one leaves the other screen's query, carousel and in-flight work intact. Existing installations seed both choices from the former shared selection once. Floating visibility switches still affect only the picker.

**Settings → Media sources → Installed extensions** lists installed source APKs, even before they have profiles or while disabled. App info opens Android's package settings; Uninstall opens Android's confirmation. Returning refreshes discovery. Listing an APK never binds it or bypasses signer approval/protocol checks. Unverified publishers show **Review and allow**, with a warning displaying the package and SHA-256 certificate fingerprint. **Cancel** grants nothing; **Allow extension** pins that package/certificate. **Revoke access** blocks its sources again while retaining the installed APK and configuration. Add source presents the same warning before opening an unapproved extension.

## Ownership

[Media imports and connected folders](media-imports.md) add optional capability-driven sharing, uploads, folder mappings and durable transfer receipts. Credentials and Local's models/index remain extension-owned; the host owns scheduling and retry policy. Originals are retained.

| SAO | Extension APK |
| --- | --- |
| Picker, search controls, carousel and playback | Named profiles and their private configuration |
| Request timing, cancellation and pagination orchestration | Authentication, API paths and domain query translation |
| Bounded read retry policy | One requested page and its opaque continuation |
| Shared media cache, export and WebM conversion | Authenticated read-only media streams |
| Clipboard, chat paste and Vector/LSPosed | Extension-specific setup and connection verification |
| Intake UI, folder mappings, durable queue and retry policy | Writable targets, verified writes and backend duplicate handling |

The small `source-api` library defines the contract. `immich-provider` translates Immich API records; `immich-extension` supplies its private store, settings Activity, bound service and streaming provider. SAO's production dependencies include neither module nor the Immich client. Common UI, player, cache and messaging libraries are not dependencies of the extension.

A Local Sticker profile owns several persisted folder grants/collections. SAO receives the same profile records and pages, without knowing about folder scanning, OCR, models or metadata overrides.

## Profiles and capabilities

A public profile contains `id`, `name`, `revision` and a short display `summary`. SAO stores those references plus the extension component. It receives **no server address, API key, folder grants or credential object**. The extension encrypts Immich configuration using Android Keystore AES-GCM and an atomic file in its private, non-backed-up storage. Editing only a name preserves the revision; changing credentials or album scope creates a new revision.

Public profiles optionally expose `nsfw: true` (default false for older v1 providers). **NSFW Mode** retains its original setting title and description. When off, SAO excludes marked profiles from both browsers and from All, cancels affected sessions, and disables their selection/visibility controls in Media sources while leaving Edit available. Saved selections and manual floating-only disables are preserved; the policy is derived in memory without rewriting source preferences. Moeview 1.1.4 marks Non-safe and All ratings automatically; Local Sticker 1.6.3 supplies a per-source switch. This classifies a whole source, not individual files. Providers own classification; the host has no provider-name conditionals.

SAO namespaces source identity and cache entries by extension, profile and revision. Switching source cancels the prior session and its requests. An item from another source cannot be exported through the new session. All accepts its current members and routes each media/sequence request back to its original owner. Removing/reconfiguring a source removes its old host media cache. Missing or disabled APKs retain their public source reference and report an error; the host can remove that unavailable reference.

Capabilities declare supported search modes (`CONTEXT`, `FILENAME`, `DESCRIPTION`, `PATH`, `OCR`), facets (`PEOPLE`, `PLACES`, `MEDIA`, `EMOJI_TAGS`), a default mode, random browsing and chronological neighbours. SAO disables unsupported controls and normalizes requests against this declaration. Immich checks server features; unavailable Context/OCR modes are disabled and Filename becomes the fallback. There is no provider-name switch in the generic search UI. Emoji text is passed intact to Context, not copied into metadata fields.

Optional v1 `categories` contains up to 40 provider-authored home presets: a stable `id`, display `label`, full `query` and optional `adult` flag. SAO renders those presets as chips and submits their query through the same capability normalization as search. The legacy category `adult` field is retained for wire compatibility; NSFW Mode now gates entire sources using their profile flag. Media records may include `filename`, kept separate from an edited title/description. Older extensions remain compatible; without these optional fields the host can browse the source but has no provider presets or trustworthy filename to copy. Immich 1.1.0 and Local Sticker 1.4.0 supply both.

Optional v1 `suggestions` lists the search modes supporting query completion. SAO sends a `suggestions` operation only for an advertised mode, with the full `query` and collapsed UTF-16 `cursor`. The provider returns `{start,end,items:[{value,label,detail?}]}`: an exclusive-end edit range containing the cursor, and at most 20 choices. The host validates bounds, Unicode boundaries and bounded single-line replacements, then preserves text outside that range. Tag syntax and dictionaries belong to the extension. Moeview 1.1.0 implements this for Description; older Immich/Local Sticker APKs continue to work without suggestion requests.

The shared Material dropdown appears in floating search and **Settings → Home categories**. Local `:emoji` completion takes priority. Provider completion waits 300 ms after editing, cancels obsolete work and stops when the screen stops. It has no result cache or automatic retry. Failure cooldowns are scoped to the source revision: Retry-After (seconds or HTTP date, capped at one day), otherwise 30 seconds for HTTP 429 or five seconds for other failures. No background timer or polling resumes requests; a later edit must trigger one.

[Category settings and reviewed screenshots](screenshots/README.md#search-suggestions-and-home-categories). The suggestion/category implementation passed 75 JVM checks. The latest loading/preset/prefetch follow-up passes 37 host JVM, 17 host Android and 16 Moeview Android checks, covering both autocomplete UIs, unchanged emoji completion, isolated per-profile category persistence, unsaved drafts, source selection, keyboard visibility and carousel focus. All media and credentials in these checks are synthetic localhost fixtures. The signed optimized Moeview APK also passed a public-tag completion smoke test through SAO.

**Settings → Media sources → All sources (experimental)** adds the host-owned All profile. It contains public member references, never credentials. [Aggregation, cursor ownership and failure handling](home-source-browser.md#experimental-all-source) are host responsibilities; extensions need no aggregation implementation.

An optional v1 `changes` capability carries a granted, source-owned content URI. SAO validates its owning package and observes it only while the picker is visible. Notifications are coalesced to at most one local refresh per second; matching items/metadata merge without clearing the list or focus. Random batches remain stable. Older v1 clients ignore this optional field. Extensions without it need no observer or extra API calls.

Search may include the optional `discover` hint for the first page of a changed query or newly visible session. Local Sticker applies its discovery cooldown only to this hint; capability reads, pagination and reactive refreshes do not schedule scans. Extensions can ignore the hint. Configured-folder background checks remain extension-owned for Local and host-owned for remote intake, as described in [media imports](media-imports.md).

## IPC contract v1

An installed APK exports a service handling `one.njk.sao.source.EXTENSION`, with manifest metadata `one.njk.sao.source.API_VERSION=1` and `one.njk.sao.source.SETTINGS=<fully qualified Activity>`. SAO uses a targeted package-visibility query and verifies the signing certificate. The host-owned signature permission still protects extension services/settings: SAO holds it even when the extension has a different signer. The shared SDK additionally checks the host’s identity and, for a different signer, the user’s current approval. The host’s reverse import endpoints authorize approved caller UIDs at runtime and scope every operation to that caller’s profile. No persistent binding, background discovery loop, wakelock or network polling is required.

`ISourceExtension.descriptor()` supplies the protocol version. `request(requestId, profileId, operation, JSON, callback)` and `cancel(requestId)` are asynchronous AIDL calls. Payloads are bounded to 64 KiB per request and 256 KiB per response, measured conservatively as UTF-16. There are at most 24 active requests in a provider; work runs on IO with limited parallelism. Binder caller UID is captured before dispatch; caller death cancels its work. Service cancellation completes pending callbacks, including requests cancelled before execution, so a live host can reconnect without waiting for the request timeout. An incompatible protocol is rejected before executing operations.

| Operation | Result |
| --- | --- |
| `profiles` | Up to 100 public named profiles and an opaque continuation; no network required |
| `capabilities` | Supported modes, facets and optional features |
| `search` | Up to 100 items plus opaque `next` cursor |
| `suggestions` | Optional: at most 20 query replacements and their UTF-16 edit range |
| `filters`, `people`, `places` | Supported filter choices; people may be paginated |
| `media`, `personMedia` | Granted read-only `content://` stream URI |
| `sequenceOpen`, `sequencePage` | Timeline items, directional cursors and opaque sequence token |
| `deleteProfile` | Remove a profile only if its revision still matches; a mutation, never automatically retried |
| `import` | Idempotent, signed-caller-only migration of one old encrypted connection |
| `importOptions` | Optional accepted types, opaque writable targets and public collection summaries |
| `importMedia` | Stream one granted immutable host snapshot; return an idempotent receipt |
| `importComplete`, `importCancel` | Finish analysis for a batch, or clean only incomplete provider writes |

Profile discovery cursors include a public-list fingerprint; changes between pages fail explicitly instead of silently dropping profiles. All other profile reads carry the expected configuration revision and host source identity. SAO tags capability updates with the owning source identity, preventing a previous profile’s modes from changing the current search. Media records contain a source-scoped ID, display metadata, format and opaque asset reference, not an authenticated URL. Sequence tokens/cursors are opaque to SAO. Immich's sequence token supports reopening after the bound service has stopped; chronology remains the Photos timeline order.

User approval never enables automatic legacy credential migration into another publisher’s APK. The same-publisher legacy migration is the sole credential handoff: an old SAO installation passes its existing opaque configuration once to the trusted Immich extension. SAO replaces and erases its old credential record **only after acknowledgement**. If the extension is absent or migration fails, that encrypted legacy record remains intact and inactive. Fresh configurations are entered exclusively in the extension.

## Shared settings template

First-party extensions depend on `:source-settings`, a Views library that supplies common **Save source** / **Remove source** labels, inline importing and `SourceSettingsGuard`. The host cannot inspect private configuration drafts. Each extension supplies its own dirty comparison and save operation; no credentials cross IPC:

```kotlin
val guard = SourceSettingsGuard(this, binding.saveSource, { model.hasChanges }, { model.state.value.working }, model::save)
binding.back.setOnClickListener { guard.leave() }
```

The guard also handles system Back. Its Material dialog offers Save source, Keep editing and Discard changes, survives Activity recreation, and keeps the draft open if saving fails. Compare editable configuration to the initially loaded draft; ignore progress, last-scan timestamps and immediately saved picker preferences. Do not persist credentials in a host store or track edits through per-keystroke preference writes. Group Save and Remove after the configuration sections. Future extension forms should use this same template.

## Approval handshake

`SourceSignatures` reads `SigningInfo.apkContentsSigners` with `GET_SIGNING_CERTIFICATES` and computes sorted SHA-256 fingerprints. User approvals live in SAO’s existing settings DataStore, keyed by package and signer set; normal updates retain approval, while a new signer must be approved again. Approval verifies the installed signer against the one shown in the warning before saving. Independently signed profiles include the signer in their host media-cache identity, so replacing a package with another publisher cannot reuse its previous media namespace. Existing same-publisher profiles retain their identities. No source is bound, migrated or queried for profiles merely because its APK appears in inventory.

`SourceAccess.requireTarget` checks approval before any host Binder connection. Source services and media providers authenticate SAO through `SourceAccess`; source settings use the shared asynchronous `SourceSettingsActivity` guard. For different signers, the source checks the host-owned signature permission and calls `content://<host-package>.source-trust`. This read-only `ContentProvider.call` endpoint returns only a boolean: an external caller may query only its own installed package, verified against `Binder.getCallingUid()`. Nested calls clear/restore Binder identity. Provider ownership and current signer approval are checked; package names alone do not establish trust. All authorization/storage work stays off the UI thread. Activity-result launchers register before STARTED, including results delivered before authentication finishes.

Revocation is checked on existing Binder handles and granted media URIs as well as new connections. Host import controls use the same current approval and retain profile/revision ownership checks. There is no idle binding, certificate polling or reconnection loop. Third-party publishers must use the updated SDK or implement equivalent host/URI/callback authorization; SAO cannot override hard-coded checks inside an older third-party APK. Older same-publisher v1 APKs continue to work.

## Errors and retry policy

The table below describes reads. [Imports](media-imports.md) use their own durable queue, duplicate checks and bounded write retry policy. They never reuse browsing retries.

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

Validation passed: 51 JVM tests and 91 distinct Android 15 emulator checks across the source-extension changes, covering lifecycle, search, pagination, sequence restoration, media, local indexing and visible-only index notifications. Local checks include official CLIP reference outputs, download recovery, 120,000-vector ranking, per-album pause/resume/restart and constrained-job checkpoints, external model grants/reuse, deterministic native unmapping, idle unloading and cross-model isolation. The real folder-picker flow passed against the optimized Local Sticker APK with PNG/GIF/MP4/WebM indexing, Context search and original-byte exports. Host, source SDK and Local Sticker lint passed with zero errors; settings were visually checked in light/dark themes. Host DEX inspection confirms that Immich and Local Sticker implementation classes and local ML models are absent from SAO. The earlier model/storage update required only the extension APK. The optional emoji row and removal of built-in samples update SAO itself; configurable media import belongs to Local Sticker 1.3.0. No source protocol change is required.

Current picker validation: 31 host JVM tests, 15 local indexing checks and nine host instrumentation checks, including real photo/folder grants and PNG/GIF/MP4/WebM original-byte comparisons against the signed optimized extension. New UI captures are in the screenshot gallery.

Protocol design follows Android's [bound-service lifecycle](https://developer.android.com/develop/background-work/services/bound-services), [AIDL threading rules](https://developer.android.com/develop/background-work/services/aidl) and [targeted package visibility](https://developer.android.com/training/package-visibility/declaring). Installed APK metadata/version/signing discovery takes inspiration from [Mihon's extension loader](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt); SAO uses separate-process IPC rather than loading extension classes into the host.
