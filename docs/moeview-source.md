# Moeview source APK

Install `moeview-sao.apk`, then open **SAO → Settings → Media sources → Add source → Moeview**. Name the profile and choose Yande.re, Konachan, Gelbooru or Danbooru. Repeat for separate boards, rating scopes, accounts or base-tag collections. Profile names appear in the existing home Collection selector and floating picker. This source uses the current v1 extension contract; no SAO app update is required.

The form belongs to the extension and uses Material 3 Views. Saving works offline; **Test connection** is an explicit single API request. Profiles, optional account credentials and verification are encrypted with Android Keystore in private, non-backed-up storage. SAO receives public profile metadata and opaque asset references, never credentials or API URLs.

## Search and browsing

The default **Description** mode searches booru tag metadata. It is not Context/embedding search: `blue_hair smile` requires both tags, underscores join words within one tag, and `-comic` excludes a tag. Optional base tags apply to every query and home category. Context, OCR, filename/path search, people/places and chronological browsing are unavailable and disabled through capabilities. Emoji text is passed literally; boorus do not provide semantic emoji search. Home presets now use a reviewed dictionary for each board, covering school uniforms, blush, ponytails, characters and the requested reaction concepts where supported. [Full equivalence table, unavailable concepts and verification limits](moeview-category-tags.md). Existing custom categories remain; reset them in SAO to adopt the new defaults.

With an updated SAO host, version 1.1.0 offers tag suggestions in floating search and the host's **Home categories** editor. Each profile queries its selected board's tag dictionary, preserving exclusion/OR prefixes and surrounding tags. Yande.re/Konachan/Danbooru prefix queries append `*`; Gelbooru uses `%`, matching the website's autocomplete. Short prefixes and metatags make no HTTP request. Suggestions use the profile's existing route, credentials and rate gate, with no result cache or automatic retries. The source APK still works with older v1 hosts; they simply do not request completions.

Profiles default to **Safe only**. Non-safe only and All ratings are explicit profile settings. A scoped profile rejects manual `rating:` overrides; choose All ratings to write your own rating query. Server results are checked against the profile's rating scope. Account-level tag/search restrictions still apply, particularly to anonymous Danbooru queries.

| Board | Default API route | Lucky |
| --- | --- | --- |
| Yande.re | Direct | Unavailable |
| Konachan | Moeview proxy for Safe only; direct for other ratings | Unavailable |
| Gelbooru | Moeview proxy | Supported |
| Danbooru | Direct | Supported |

A proxy switch selects the direct API instead when needed. Account credentials or saved browser verification always use the direct API. Failed requests do not silently change routes. The public Moeview edges are not an official, versioned upstream API and can change. The reference's safe-only Konachan routing is retained.

SAO requests successive pages; the provider translates one page at a time with an opaque continuation bound to the profile revision and query. A short nonempty page retains continuation until the API returns an empty page, so hidden/unavailable files do not prematurely end browsing. Unsupported files, including ZIP/ugoira, are omitted. Lucky fetches one fresh random batch with no continuation; a new press performs a new request.

## Media, lifecycle and failures

PNG/JPEG/WebP/AVIF, GIF, MP4 and WebM records use SAO's existing carousel, player, copy/paste and Vector paths. Availability depends on the board. Static previews use the board's display-size `sample_url` (Danbooru: `large_file_url`), falling back to the original rather than a small grid thumbnail. This follows the opened-image view in [Moeview's JavaScript bundle](https://moeview.app/assets/index-Bc3E0iUD.js), inspected on 2026-10-19. Animated image previews use original bytes; videos use still thumbnails plus the original video for playback. Media copy/export uses the full original, not a sample. Original filenames are available to the existing title-hold action. Original fallbacks share the original cache key and transfer limit, avoiding duplicate downloads or an 8 MiB thumbnail limit on a full-resolution file.

The provider streams from HTTP into cancellable, read-only reliable pipes; it stores no downloaded originals or query-result cache. Authenticated/encrypted references survive process death without retaining search lists and reject tampering or profile reconfiguration. URLs and redirects are restricted to the board's HTTPS media domains. Account credentials and Cloudflare cookies are excluded from media/CDN requests and proxies.

SAO continues to own media caching, pagination timing, empty-page advancement, cancellation, and its existing bounded read retry. Client retries and OkHttp connection retries are disabled in this extension. A process-wide 500 ms gate per board paces requests across profiles. HTTP pools are initialized on demand, with at most four idle clients retained; eviction releases their owned dispatchers. There is no polling, worker, boot receiver, foreground service or wakelock. HTTP status/Retry-After, TLS, timeout, malformed-response and stale-profile failures pass through the existing safe SDK error contract. One failed transfer does not invalidate unrelated images/videos or poison subsequent requests.

## Cloudflare verification

A 403 with `cf-mitigated: challenge` is a browser challenge, not an API-key error. **Verify with Cloudflare** opens the board in an extension-owned WebView for the user to complete verification. **Use verification** retains only Cloudflare cookie names (`cf_clearance`, `__cf_bm`, `_cfuvid`) and the actual WebView user agent in that profile. It does not copy Chrome's cookies or collect board login-session cookies. Test the connection, then save the profile. **Clear saved verification** removes that profile's saved verification.

Verification is scoped to that exact board API origin and known API paths. It expires and is tied to the visitor/device; changing networks or further browser checks can invalidate it. Cloudflare documents limited embedded-browser support, so this is a best-effort user-assisted flow, not guaranteed access on every phone/network. API keys do not replace the browser challenge. The WebView has no JavaScript bridge or file/content access, accepts only HTTPS navigation to the chosen board, and is destroyed when closed; it is never used for background requests.

[Cloudflare clearance](https://developers.cloudflare.com/cloudflare-challenges/concepts/clearance/) · [supported browsers](https://developers.cloudflare.com/cloudflare-challenges/reference/supported-browsers/) · [Gelbooru API](https://gelbooru.com/index.php?page=wiki&s=view&id=18780) · [Danbooru query implementation](https://github.com/danbooru/danbooru/blob/master/app/logical/post_query_builder.rb).

## Reference and validation

`moeview` adapts the user-provided `/home/shichika/test/booru-android/booru` client and its parser/transport checks. Its public board dialects, tag/media models, hotlink headers and transport rules are preserved. The adaptation adds bounded JSON reads, prompt socket cancellation, explicit resource ownership, safe error propagation and Gelbooru's live string timestamp shape. The reference directory is unchanged. `moeview-extension` provides profiles, Views settings, SDK service and media pipes. Neither module is a production dependency of SAO, Immich or Local Sticker.

Build with `./gradlew :moeview:testDebugUnitTest :moeview-extension:assembleRelease :moeview-extension:lintRelease`, then align/sign the optimized APK with the same certificate as SAO. The debug APK is larger and is not the distribution build. Cross-process tests use synthetic HTTP/media fixtures. Small anonymous live probes checked API response shapes only; no personal library is accessed. Cloudflare verification is probed separately and explicitly, without automated challenge interaction.

Version 1.1.1's optimized, signed APK is 2.27 MiB (2,384,064 bytes). All 35 parser/HTTP JVM and 16 Android 15 emulator checks pass, covering all four autocomplete dialects, encrypted profiles, capability gating, Binder pagination, original PNG/GIF/MP4/WebM bytes, display-size previews, credential/cookie scoping, stale references, failure recovery, board-specific tag completion, reviewed per-board defaults, shared preview transfers and SAO's home/settings integration. The settings save test scrolls the form as a user would. Emulator fixtures cover offline profile save, capability discovery and canonical tag insertion. Live board checks fetched public tag metadata and single post records only, without image downloads. Reviewed light/dark captures and synthetic fixtures are linked below.

Anonymous one-record probes succeeded for Yande.re and the Konachan/Gelbooru proxy routes. Danbooru returned `403` with `cf-mitigated: challenge`. The real verification screen reached its human-verification checkbox, but no clearance cookie was issued without human completion. The native API handoff therefore remains unverified on a physical phone. [Reviewed screenshots](screenshots/README.md#moeview-extension).
