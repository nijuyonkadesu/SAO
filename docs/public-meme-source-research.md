# Public meme source investigation

Tested on **20 October 2026**. This is a research report; no new source APK or host behavior was added.

**Recommendation:** FindThatMeme is viable for an **experimental, keyless keyword/caption source**, including animated media. MemeStack is the better candidate for **description search**, with a production API key and still images only. Neither qualifies as a dependable replacement for Immich Context across images and videos. Keep these distinctions visible in any extension's advertised capabilities.

## What was actually tested

Public HTTP requests used `curl`, explicit time/size limits and an identifying SAO research user agent. No account was created, no third-party application's key was reused, and no private library was accessed. Search text was sent intact.

FindThatMeme received eight search requests; MemeStack's documented public v1 API received ten. Tests covered captions, visual descriptions, a template name, emoji, pagination and an empty-result case. Downloaded **23 media files** were inspected with `ffprobe`; representative results were also viewed in contact sheets. Timing below is one observation per request from this machine, not a latency benchmark or an accuracy study.

| Candidate | Search and media | Actual result | Adoption constraint |
| --- | --- | --- | --- |
| [FindThatMeme](https://findthatmeme.com/) | Mostly text/OCR; images, GIFs, MP4s | Keyless JSON search and direct media worked | Undocumented website API; weak visual-description relevance |
| [MemeStack](https://memestack.ai/developers) | Meaning and text; still images | Public v1 search, pagination and image retrieval worked | Apps are directed to keyed v2; free tier is 100 counted calls/day/account |
| [KLIPY](https://docs.klipy.com/) | Keyword search; memes, GIFs, stickers, clips | Invalid-key response only; relevance untested | Own key; prior approval for caching and mixing providers |
| [GIPHY](https://developers.giphy.com/docs/api/) | Keyword search; GIFs, stickers, MP4 renditions | HTTP 401 without a key; relevance untested | Own key; caching and mixed-result restrictions |
| [Imgflip](https://imgflip.com/api) | Template catalog/search | HTTP 200 and 100 public templates | Templates do not provide the requested broad catalog of completed memes |

## FindThatMeme

The current website uses `POST /api/v1/search` with `search` and `offset`. All eight searches returned HTTP 200 and 50 records. Two consecutive pages of `this is fine` had 50 unique IDs each and **no shared IDs**. Request durations ranged from **0.76 to 6.44 seconds**.

Results include an ID, source page and direct media URL. Fifteen downloaded files all returned HTTP 200, without cookies or a browser session. Decoding confirmed JPEGs, H.264 MP4s and an animated GIF: **360 × 288, 29 frames, 2.94 seconds**. A tested MP4 was **450 × 450, 32 frames, 1.07 seconds**. Another `.gif` had only one frame: a suffix alone does not establish animation.

Relevance was uneven:

| Query | FindThatMeme observation | MemeStack observation |
| --- | --- | --- |
| `this is fine` | First three were near-duplicate caption collages, not the familiar dog comic | Exact dog comic ranked first |
| `dog sitting in a burning room` | None of the first three showed the intended dog comic | Exact dog comic ranked first |
| `woman confused with math equations` | None of the first three showed the intended confused-math-woman meme | Intended meme absent from the first five |
| `distracted boyfriend` | Recognizable variant at rank three | Medusa variant ranked first; other variants followed |

The site's `type:video this is fine` syntax returned 50 video records with MP4 URLs. Emoji input also returned records, but this does not establish reliable emotion search. The tested text endpoint should **not** be advertised as embedding-based Context search. Its API exposes content scores; those are not a verified server-side safe-search guarantee.

This endpoint is a website implementation detail, despite `/v1` in its path. [SearXNG's independent integration](https://github.com/searxng/searxng/blob/master/searx/engines/findthatmeme.py) calls it unofficial; its [history](https://github.com/searxng/searxng/commits/master/searx/engines/findthatmeme.py) includes a November 2024 URL-change fix. It is suitable for an independently updateable experimental extension, with no promise of uninterrupted service.

## MemeStack

Ten public v1 requests returned HTTP 200. The description test finding the burning-room dog demonstrates useful semantic retrieval, but several other queries produced loose matches. Many sampled results were political memes or charts, rather than a general reaction-meme collection.

Pagination worked without duplicate IDs between the two tested pages. However, `total` counts strong matches while returned items can include nearest guesses: one response contained five images, `total: 0`, and `match_quality: closest_only`. A nonsense query with `closest=none` correctly returned no items. Simply following `has_more` can therefore fetch increasingly irrelevant results. Filtering out every item marked closest would also discard the correctly retrieved dog from the description test.

All eight thumbnail/canonical downloads returned HTTP 200 and decoded. Canonical images reached **2075 × 2500**, but the dog source itself was only **419 × 235**. One response declared `image/png` while its bytes decoded as JPEG; format detection must inspect the payload. The documented Tenor-compatible `gif` format keys also carry still images, not actual GIFs.

The [production contract](https://memestack.ai/developers) requires a v2 key; its unauthenticated search returned HTTP 401. Production v2 relevance and authenticated behavior were **not** tested. The free tier requires a per-result attribution link. Random is documented as ignored, so an adapter must not advertise random browsing. The public v1 test route should not silently become the production integration.

## Why the other options are weaker

- **KLIPY:** the documented versioned API covers the desired media mix. A request with the documentation's placeholder key returned HTTP 404 with an invalid-key JSON error. Its [integration requirements](https://docs.klipy.com/integration-requirements.md) require prior approval for media caching and combining its results with other providers. SAO's shared cache and shuffled All view make this a material compatibility issue; authenticated search quality remains untested.
- **GIPHY:** its [current API requirements](https://developers.giphy.com/docs/api/) likewise restrict caching and mixing other providers in one grid. A normal SAO source implementation cannot assume those behaviors are permitted.
- **Google/Bing:** [Bing Search APIs retired on 11 August 2025](https://learn.microsoft.com/en-us/lifecycle/announcements/bing-search-api-retirement). [Google Custom Search JSON API is closed to new customers and ends for existing customers on 1 January 2027](https://developers.google.com/custom-search/v1/overview). Scraping their HTML would also leave media extraction dependent on arbitrary third-party sites.
- **Tenor:** its [quickstart](https://developers.google.com/tenor/guides/quickstart) says new API clients have not been accepted since January 2026.
- **Brave:** a [documented image-search API](https://api-dashboard.search.brave.com/app/documentation/image-search) remains available with a subscription token. The unauthenticated probe returned HTTP 422. It is general web-image search; result quality was not tested, and it does not establish direct video-file retrieval.
- **Other meme catalogs:** this machine received `DEPLOYMENT_DISABLED` from Memegine, `NoSuchWebsiteConfiguration` from MemeAtlas, and a domain-sale redirect from MemeSearch. These observations rule them out for this prototype, not all future deployments of those names.

## Implementation boundaries if adopted

Use the existing separate-APK source contract. Provider URLs, query translation, credentials and response parsing belong in the extension; SAO retains request scheduling, pagination orchestration and permitted media caching. An API returning JSON IDs and direct media is preferable to scraping layouts, but neither guarantees multi-year availability.

Advertise only verified search/media capabilities. Do not synthesize Context, random, upload or chronology support from a provider's name or from a superficially compatible response. Preserve source links and required attribution. Respect rate-limit cooldowns without background polling or retry loops. FindThatMeme's observed generic `binary/octet-stream` headers and stale size metadata, plus MemeStack's format mismatch, require payload validation and enforced streamed byte limits.

## Reproduce the successful searches

These are finite public read requests. The MemeStack v1 example is for evaluation, not a recommendation to bypass its production API.

```sh
curl --fail-with-body --silent --show-error --connect-timeout 10 --max-time 25 \
  --max-filesize 2000000 \
  --user-agent 'SAO-source-research/1.0 (+https://github.com/nijuyonkadesu/SAO)' \
  --header 'Content-Type: application/json' \
  --data '{"search":"this is fine","offset":0}' \
  https://findthatmeme.com/api/v1/search

curl --fail-with-body --silent --show-error --connect-timeout 10 --max-time 25 \
  --max-filesize 2000000 \
  --user-agent 'SAO-source-research/1.0 (+https://github.com/nijuyonkadesu/SAO)' \
  --get --data-urlencode 'q=dog sitting in a burning room' --data 'limit=5' \
  https://api.memestack.ai/v1/images/search
```

For FindThatMeme, repeat with `offset: 50` for the next page. For MemeStack v1, use `offset=5`. Follow the returned media links rather than reconstructing arbitrary CDN paths, then verify the decoded format and dimensions instead of trusting HTTP headers or a rendition key.
