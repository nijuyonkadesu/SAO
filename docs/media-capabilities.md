# Floating picker delivery capabilities

**Ideal flow:** you are already inside a chat. Press **Paste** in SAO and the
centered item opens in that chat's sending preview, attachment draft, or sends
directly. This should work for every media format, through Android or Vector.
In **Media** mode, the result is a normal attachment, not a sticker.

**Ideal support would mean every With Vector cell is a checkmark.** These tables
show the current implementation instead.

**Legend:** ✓ supported · X unavailable · ~ conditional · ? not verified.
**Default** means without Vector. Its Paste button needs Accessibility **Paste
assistance** and the original editor. With Vector, enable SAO, scope the receiving
app and restart it; a supported native route takes priority.

Sticker checklists describe **formats the receiving app supports**, independently
of Vector. The notes explain which of those SAO can currently deliver.

## WhatsApp / WhatsApp Business

| Media Format | Default | With Vector |
| --- | :---: | :---: |
| Photo (JPEG / PNG) | ✓ | ✓ |
| GIF | ~ | ~ |
| MP4 | ? | ? |
| WebM | ? | ? |

**Supported sticker medium by app:** ✓ Static WebP · ✓ Animated WebP · X GIF / MP4 / WebM as-is.
[WhatsApp sticker formats](https://github.com/WhatsApp/stickers/blob/main/Android/README.md#sticker-art-and-app-requirements).

**Notes:** GIF Paste depends on the installed composer advertising support.
Video clipboard insertion is unverified, and SAO has no native video adapter yet.
**Media** opens WhatsApp's attachment/share flow. For static stickers, Vector uses
the native sticker handler and can send directly; default Paste may show a format
choice. The bundled static sample pack can also be installed. SAO does not prepare
animated stickers.

## Telegram / configured forks

| Media Format | Default | With Vector |
| --- | :---: | :---: |
| Photo (JPEG / PNG) | ✓ | ✓ |
| GIF | ~ | ~ |
| MP4 | ~ | ~ |
| WebM | ~ | ~ |

**Supported sticker medium by app:** ✓ Static WebP · ✓ Animated TGS · ✓ Video WebM (VP9) · X GIF / MP4 as-is.
[Telegram sticker formats](https://core.telegram.org/api/stickers).

**Notes:** Both columns use the same non-root Paste route; there is no Telegram
Vector adapter. GIF/video preview and acceptance depend on the client. SAO retains
the previously focused editor and tries to restore it on Paste. **Media** uses the
share flow. **Sticker** shares a prepared static WebP through Telegram's
recipient/Send flow; clipboard WebP alone does not guarantee a sticker. SAO does
not prepare TGS or video stickers.

## Discord

| Media Format | Default | With Vector |
| --- | :---: | :---: |
| Photo (JPEG / PNG) | ✓ | ✓ |
| GIF | ✓ | ✓ |
| MP4 | X | X |
| WebM | X | X |

**Supported sticker medium by app:** ✓ PNG · ✓ APNG · ✓ GIF · ✓ Lottie (restricted upload) · X MP4 / WebM.
[Discord sticker formats](https://docs.discord.com/developers/resources/sticker#sticker-format-types).

**Notes:** Paste stages an attachment draft. **Attach** also supports original
video files through Discord's share flow, which may ask for a destination.
Stickers must already exist on Discord with a sticker ID; SAO does not create or
send native Discord stickers. Pasting a GIF sends media. Lottie uploads require a
verified/partnered server; using custom server stickers in DMs requires
[Nitro](https://support.discord.com/hc/en-us/articles/4403089981975-Custom-Stickers-FAQ).

## Instagram DM

| Media Format | Default | With Vector |
| --- | :---: | :---: |
| Photo (JPEG / PNG) | X | ✓ |
| GIF | X | X |
| MP4 | X | ~ |
| WebM | X | X |

**Supported sticker medium by app:** ✓ In-app static stickers · ✓ In-app animated / GIF stickers.
Instagram exposes [DM sticker packs](https://about.fb.com/news/2024/11/new-ways-to-connect-through-dms/)
and [GIFs/stickers from its library](https://about.fb.com/news/2020/05/welcome-giphy/),
rather than a documented external sticker-file import format.

**Notes:** Vector photo Paste sends directly to the original DM. MP4 uses a native
gallery adapter for **451.0.0.53.80 only**; real-APK/fixture checks pass, but phone
upload validation is pending. **DM** is the alternative attachment flow and opens
Instagram's recipient picker; Instagram decides which GIF/video formats it
accepts. SAO cannot insert native DM stickers.

## Copy, sending and validation

Tapping an item copies immediately; scrolling alone does not. Copy remains
available even when Paste is unavailable. Media-mode photos use JPEG; WhatsApp
static-sticker clipboard preparation uses PNG; Telegram uses 512×512 WebP.
GIF/MP4/WebM media exports preserve their original bytes. SAO does not convert
animations or videos into stickers.

Native WhatsApp/Instagram Paste can send immediately on explicit press; Discord
stages a draft. Selecting or copying never sends. Accessibility does not press
Send. Manual clipboard Paste does not require Paste assistance, but the receiving
app still controls accepted formats.

Native requests are tied to the original chat/editor and are never automatically
retried after a rejected or unknown dispatch. Non-root accessibility cannot inspect
Telegram's internal chat identity when it reuses the same editor/window.

WhatsApp/Instagram photo and Discord draft insertion have user phone confirmation.
Instagram MP4 checks cover real APK gallery objects, original bytes/metadata,
capability gating and recording clients, **not authenticated upload or delivery**.
Its request-owned callback revalidates the original chat after native processing
without modifying Instagram's normal gallery sender.

See [Instagram analysis](instagram-dm-delivery.md), [Discord analysis](discord-apk-analysis.md)
and [Telegram/Nagram analysis](telegram-delivery.md). Android's
[rich-content API](https://developer.android.com/develop/ui/views/receive-rich-content)
requires receiving-app format support. Discord's
[message API](https://docs.discord.com/developers/resources/message#create-message)
sends existing sticker IDs.
