# Floating picker delivery capabilities

**Ideal flow:** inside a chat, press **Paste** in SAO to open that chat’s sending preview/draft or send directly. Every format should work, ideally with all checks under Vector. **Media** sends a normal attachment, not a sticker.

✓ Supported route · X Unavailable (reason shown) · ~ Conditional or awaiting phone verification. Sticker formats below describe the receiving app; notes explain SAO’s current implementation.

> [!NOTE]
> Default Paste needs Accessibility **Paste assistance** and the original editor. For Vector, enable SAO, scope the receiving app and restart it.

## 1. WhatsApp / Business

| Media Format | Default | With Vector |
| --- | --- | --- |
| Photo (JPEG/PNG) | ✓ | ✓ |
| GIF | ~ Client-dependent | ~ Composer must advertise GIF |
| Animated WebP/APNG | ~ Converted to GIF | ~ GIF handler; conversion tested |
| MP4 | ~ Clipboard; use Media if rejected | ~ Clipboard; no native video adapter |
| WebM / other video | ~ MP4 compatibility copy | ~ MP4 + clipboard; no native video adapter |

**Supported sticker medium by app:** ✓ Static/animated WebP · X GIF/MP4/WebM (must convert to WebP).

> [!NOTE]
> Static stickers work through Vector; default Paste may show a format choice. Animated stickers are deferred: moving media uses the normal-media route even in Sticker mode. Animated images get a GIF copy; non-MP4 videos get MP4 with audio retained. Media opens the attachment flow. New WebP compatibility is emulator-verified; phone delivery remains to be confirmed.

## 2. Telegram / configured forks

| Media Format | Default | With Vector |
| --- | --- | --- |
| Photo (JPEG/PNG) | ✓ | ✓ |
| GIF | ~ Client-dependent | ~ Same default route; no native adapter |
| Animated WebP/APNG | ~ Client’s original-format handling | ~ Same default route; no native adapter |
| MP4 | ~ Client-dependent | ~ Same default route; no native adapter |
| WebM | ~ Client-dependent | ~ Same default route; no native adapter |

**Supported sticker medium by app:** ✓ Static WebP · ✓ Animated TGS · ✓ Video WebM/VP9 · X GIF/MP4 (must convert).

> [!NOTE]
> Paste uses the current/recent editor through accessibility; Vector adds no Telegram hook. Original WebM is retained when accepted. The labelled Sticker fallback shares static WebP through recipient/Send selection; clipboard WebP does not guarantee a sticker. SAO does not prepare TGS/video stickers. Converted GIF playback was phone-confirmed; Telegram’s chat preview can misrender a GIF that plays correctly in its GIF section.

## 3. Discord

| Media Format | Default | With Vector |
| --- | --- | --- |
| Photo (JPEG/PNG) | ✓ | ✓ |
| GIF | ✓ | ✓ |
| Animated WebP/APNG | ~ Client image handling | ~ Native image draft; client-dependent |
| MP4 | ~ As GIF only; no video Paste handler | ✓ Native video draft |
| WebM | ~ As GIF only; no video Paste handler | ~ Native draft; phone check pending |

**Supported sticker medium by app:** ✓ PNG/APNG/GIF · ✓ Lottie (restricted upload) · X MP4/WebM (not sticker formats).

> [!NOTE]
> With a valid Vector lease, Paste/Attach stage media in the original chat, including a recently focused composer. Otherwise Attach opens the share flow. Files over 20 MiB offer Use original or an optional smaller copy; account limits may be higher. Discord stickers require existing sticker IDs; arbitrary media cannot become a Discord sticker.

## 4. Instagram DM

| Media Format | Default | With Vector |
| --- | --- | --- |
| Photo (JPEG/PNG) | X — No media clipboard handler | ✓ |
| GIF | X — No media clipboard handler | ✓ Native animated sticker in Sticker mode |
| Animated WebP/APNG | X — No media clipboard handler | ~ Native sticker preparation; input-dependent |
| MP4 | X — No video clipboard handler | ✓ Media mode; ✓ Sticker + As GIF |
| WebM | X — No video clipboard handler | ~ Contract verified; phone check pending |

**Supported sticker medium by app:** ✓ In-app static stickers · ✓ In-app animated/GIF stickers.

> [!NOTE]
> Vector Paste sends to the captured DM: Media mode uses native video/photo delivery; Sticker mode prepares static/animated WebP through Instagram’s native upload/registration/send path. For video stickers, enable As GIF; that native route converts frames directly to WebP, with no GIF intermediary. JPEG stickers and MP4-derived animated stickers were phone-confirmed on 447. Without a compatible bridge, DM opens recipient selection for a normal attachment.

> [!WARNING]
> Arbitrary local GIF messages in Media mode remain unsupported: the provider-GIF route returned HTTP 404. Use Sticker mode for animated stickers. Original MP4 sends/playback work on 447, though its sender-side thumbnail can be missing. Other Instagram versions rely on compatible runtime contracts; they are not all phone-verified.

## Details

Tapping copies immediately; scrolling alone does not. Original-media Copy/Download and uploads preserve bytes and formats. Paste uses the centred item. As GIF is a host-wide choice for moving media; generated GIFs use quality 90 with lossy compression and never select an upscaled rendition. Existing GIFs are reused. GIF has binary transparency, while PNG/WebP originals retain full alpha. WhatsApp additionally negotiates normal-media compatibility as described above. Native insertion, accessibility preparation and attachments share that delivery policy. Selecting/copying never sends; accessibility never presses Send. Native requests validate the captured chat, keep conversion off the main thread, and never retry an uncertain send. See [playback/GIF policy](media-playback.md) and [exports and limits](media-exports.md).

Sticker specifications: [WhatsApp](https://github.com/WhatsApp/stickers/blob/main/Android/README.md#sticker-art-and-app-requirements), [Telegram](https://core.telegram.org/api/stickers), [Discord](https://docs.discord.com/developers/resources/sticker#sticker-format-types), [Instagram DM packs](https://about.fb.com/news/2024/11/new-ways-to-connect-through-dms/) and [GIF library](https://about.fb.com/news/2020/05/welcome-giphy/). Discord Lottie uploads require a verified/partnered server; custom server stickers in DMs require [Nitro](https://support.discord.com/hc/en-us/articles/4403089981975-Custom-Stickers-FAQ).

Implementation/validation: [Instagram](instagram-dm-delivery.md), [Instagram investigation and debugging steps](instagram-native-debugging.md), [Discord](discord-apk-analysis.md), [Telegram/Nagram](telegram-delivery.md). Phone confirmation covers WhatsApp static stickers/photos, Telegram GIF playback, Instagram 447 photos/original MP4/static and animated stickers, and Discord media drafts/MP4. Remaining conditional entries distinguish fixture checks from real sends.
