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
| MP4 | X — No video Paste handler | ~ Native draft; phone check pending |
| WebM | X — No video Paste handler | ~ Native draft; phone check pending |

**Supported sticker medium by app:** ✓ PNG/APNG/GIF · ✓ Lottie (restricted upload) · X MP4/WebM (not sticker formats).

> [!NOTE]
> With a valid Vector lease, **Paste** and **Attach** stage media in the original chat. Otherwise Attach opens the share flow. Discord stickers require existing sticker IDs.

## 4. Instagram DM

| Media Format | Default | With Vector |
| --- | --- | --- |
| Photo (JPEG/PNG) | X — No media clipboard handler | ✓ |
| GIF | X — No media clipboard handler | X — No GIF adapter |
| MP4 | X — No video clipboard handler | ~ Compatible gallery contract required |
| WebM | X — No video clipboard handler | ~ Compatible gallery contract required |

**Supported sticker medium by app:** ✓ In-app static stickers · ✓ In-app animated/GIF stickers.

> [!NOTE]
> With a valid Vector lease, **Paste** and **DM** send supported media directly to the original conversation. Otherwise DM opens the recipient picker. No native sticker insertion.

> [!WARNING]
> Instagram and Discord video paths passed native contract/fixture checks; authenticated phone upload remains unverified. Instagram uses contract checks without an exact-version gate.

## Details

Tapping copies immediately; scrolling alone does not. Media-mode photos use JPEG, WhatsApp sticker clipboard preparation uses PNG, and Telegram uses 512×512 WebP. GIF/MP4/WebM media exports preserve their original bytes. Selecting or copying never sends; accessibility never presses Send. Native requests validate the original chat and do not automatically retry rejected/unknown dispatches.

Sticker specifications: [WhatsApp](https://github.com/WhatsApp/stickers/blob/main/Android/README.md#sticker-art-and-app-requirements), [Telegram](https://core.telegram.org/api/stickers), [Discord](https://docs.discord.com/developers/resources/sticker#sticker-format-types), [Instagram DM packs](https://about.fb.com/news/2024/11/new-ways-to-connect-through-dms/) and [GIF library](https://about.fb.com/news/2020/05/welcome-giphy/). Discord Lottie uploads require a verified/partnered server; custom server stickers in DMs require [Nitro](https://support.discord.com/hc/en-us/articles/4403089981975-Custom-Stickers-FAQ).

Implementation/validation: [Instagram](instagram-dm-delivery.md), [Discord](discord-apk-analysis.md), [Telegram/Nagram](telegram-delivery.md). WhatsApp/Instagram photos and Discord drafts have phone confirmation; the Instagram/Discord video adapters have no authenticated upload confirmation.
