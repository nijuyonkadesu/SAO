# Floating picker delivery capabilities

**Ideal flow:** inside a chat, press **Paste** in SAO to open that chat’s sending preview/draft or send directly. Every format should work, ideally with all checks under Vector. **Media** sends a normal attachment, not a sticker.

✓ Supported · X Unavailable · ~ Conditional · ? Unverified. Sticker formats below describe the receiving app; notes explain SAO’s current routes.

> [!NOTE]
> Default Paste needs Accessibility **Paste assistance** and the original editor. For Vector, enable SAO, scope the receiving app and restart it.

## 1. WhatsApp / Business

| Media Format | Default | With Vector |
| --- | --- | --- |
| Photo (JPEG/PNG) | ✓ | ✓ |
| GIF | ~ Client-dependent | ~ Composer must advertise GIF |
| MP4 | ? Clipboard insertion unverified | ? Clipboard only; no native video adapter |
| WebM | ? Clipboard insertion unverified | ? Clipboard only; no native video adapter |

**Supported sticker medium by app:** ✓ Static/animated WebP · X GIF/MP4/WebM (must convert to WebP).

> [!NOTE]
> SAO supports static stickers: native Vector Paste sends directly; default Paste may show a format choice. **Media** opens the attachment/share flow.

## 2. Telegram / configured forks

| Media Format | Default | With Vector |
| --- | --- | --- |
| Photo (JPEG/PNG) | ✓ | ✓ |
| GIF | ~ Client-dependent | ~ Same default route; no native adapter |
| MP4 | ~ Client-dependent | ~ Same default route; no native adapter |
| WebM | ~ Client-dependent | ~ Same default route; no native adapter |

**Supported sticker medium by app:** ✓ Static WebP · ✓ Animated TGS · ✓ Video WebM/VP9 · X GIF/MP4 (must convert).

> [!NOTE]
> **Sticker** shares static WebP through Telegram’s recipient/Send flow; clipboard WebP does not guarantee a sticker. SAO does not prepare animated/video stickers.

## 3. Discord

| Media Format | Default | With Vector |
| --- | --- | --- |
| Photo (JPEG/PNG) | ✓ | ✓ |
| GIF | ✓ | ✓ |
| MP4 | X — No video Paste handler | ✓ Native draft |
| WebM | X — No video Paste handler | ~ Native draft; phone check pending |

**Supported sticker medium by app:** ✓ PNG/APNG/GIF · ✓ Lottie (restricted upload) · X MP4/WebM (not sticker formats).

> [!NOTE]
> With a valid Vector lease, **Paste** and **Attach** stage media in the original chat. Otherwise Attach opens the share flow. Files over 20 MiB offer Use original or an optional smaller copy; account limits may be higher. Discord stickers require existing sticker IDs.

## 4. Instagram DM

| Media Format | Default | With Vector |
| --- | --- | --- |
| Photo (JPEG/PNG) | X — No media clipboard handler | ✓ |
| GIF | X — No media clipboard handler | X — Tested 447 local-GIF path returned HTTP 404 |
| MP4 | X — No video clipboard handler | ~ 447 send/playback confirmed; other builds unverified |
| WebM | X — No video clipboard handler | ~ Contract verified; phone check pending |

**Supported sticker medium by app:** ✓ In-app static stickers · ✓ In-app animated/GIF stickers.

> [!NOTE]
> With a valid Vector lease, **Paste** and **DM** send supported media directly to the original conversation. Otherwise DM opens the recipient picker. No native sticker insertion.

> [!WARNING]
> Instagram original MP4 is phone-confirmed on 447; a sender-side thumbnail is missing. GIF delivery remains unresolved. The 435/451 video contracts and WebM have structural/fixture checks, not phone delivery confirmation. Instagram uses contract checks without an exact-version gate.

## Details

Tapping copies immediately; scrolling alone does not. Media-mode copies preserve original bytes and formats, including HDR. WhatsApp sticker clipboard preparation uses PNG, and Telegram uses 512×512 WebP. Necessary WebM compatibility exports and explicitly requested Discord smaller copies have separate outputs; uploads remain original. See [conversion, limits and cache policy](media-exports.md). Selecting or copying never sends; accessibility never presses Send. Native requests validate the original chat and do not automatically retry rejected/unknown dispatches.

Sticker specifications: [WhatsApp](https://github.com/WhatsApp/stickers/blob/main/Android/README.md#sticker-art-and-app-requirements), [Telegram](https://core.telegram.org/api/stickers), [Discord](https://docs.discord.com/developers/resources/sticker#sticker-format-types), [Instagram DM packs](https://about.fb.com/news/2024/11/new-ways-to-connect-through-dms/) and [GIF library](https://about.fb.com/news/2020/05/welcome-giphy/). Discord Lottie uploads require a verified/partnered server; custom server stickers in DMs require [Nitro](https://support.discord.com/hc/en-us/articles/4403089981975-Custom-Stickers-FAQ).

Implementation/validation: [Instagram](instagram-dm-delivery.md), [Instagram investigation](instagram-native-debugging.md), [Discord](discord-apk-analysis.md), [Telegram/Nagram](telegram-delivery.md). WhatsApp/Instagram photos, Instagram 447 MP4 and Discord drafts/MP4 have phone confirmation.
