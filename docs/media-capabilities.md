# Floating picker delivery capabilities

This describes SAO's implemented routes, not every feature the messaging apps offer.
**Paste** uses the centered item. Without Vector, it needs SAO's optional Accessibility
**Paste assistance** and the original editor; the receiving client decides whether it
opens a preview. SAO does not press Send through accessibility. Manual clipboard Paste
does not require that service, but has the same receiving-app format limitations.

With Vector, enable SAO, scope the messaging app and restart it. A verified native
composer takes priority over accessibility. Installing Vector alone does not add a
native handler for every app or format. Unsupported native routes keep their existing
non-root alternatives.

| App | Content | Without Vector: Paste into the original chat | With Vector: native Paste | Other SAO route |
| --- | --- | --- | --- | --- |
| WhatsApp / Business | Photo | Supported by the host's clipboard handler | Native image handler | Media opens WhatsApp's attachment/share flow |
| WhatsApp / Business | GIF | Clipboard route; behavior depends on the installed client | Native GIF handler when the composer advertises it | Media keeps the original GIF |
| WhatsApp / Business | MP4 / WebM | Clipboard attempt is available; video insertion is **not verified** | **No native video adapter**; same non-root limitation | Media keeps the original video |
| WhatsApp / Business | Static sticker | Clipboard image is not guaranteed to become a sticker; WhatsApp may show a format choice | Native sticker handler with the exact `image/webp.wasticker` capability | Fixed bundled sample pack can be added to WhatsApp |
| Telegram / configured forks | Photo / GIF / video | Previous-editor Paste; preview/format handling depends on the client | Same non-root route; **no Telegram Vector adapter** | Media shares the original GIF/video or an exported photo |
| Telegram / configured forks | Static sticker | Clipboard WebP is **not guaranteed** to send as a sticker | Same; no native sticker hook | Sticker uses Telegram's document/WebP classification and its recipient/Send flow |
| Discord | Photo / GIF | Supported clipboard/receive-content route; stages an attachment draft | Native receive-content handler stages a draft in the original chat | Attach opens Discord's attachment/share flow |
| Discord | MP4 / WebM | **No Paste** | **No native video adapter** | Attach keeps the original file; may require choosing the destination |
| Discord | Native sticker | **No conversion of arbitrary media into a Discord sticker** | Same restriction | Discord requires an existing hosted sticker ID, including custom server stickers |
| Instagram DM | Photo | **No media clipboard Paste** in the inspected composer | Keyboard-photo handler sends directly to the original DM | DM opens Instagram's recipient picker |
| Instagram DM | MP4 | **No video clipboard Paste** | Native gallery-video handler sends directly; **451.0.0.53.80 only; phone validation pending** | DM keeps the original video and opens the recipient picker |
| Instagram DM | GIF / WebM | **No Paste** | **No implemented native route** | DM attachment route; final format acceptance belongs to Instagram |
| Instagram DM | Native sticker | **No native sticker insertion** | Same | Ordinary media attachment only |

Selecting/tapping an item copies it immediately. Scrolling alone does not copy. Copy
remains available independently of Paste: photos use JPEG in Media mode; static
WhatsApp sticker preparation uses PNG; Telegram sticker preparation uses 512×512 WebP.
GIF/MP4/WebM exports preserve their original bytes. Animated-sticker conversion is not
implemented for any target; a GIF or video is sent as media through the listed routes.

Native WhatsApp/Instagram actions can send immediately on explicit Paste; Discord
native Paste stages a draft. Selecting or copying never invokes a native send.
Chat/editor changes invalidate native leases; rejected or unknown dispatches are never
automatically retried. Non-root accessibility cannot inspect Telegram's internal chat
identity when the client reuses its editor/window.

## Validation limits

WhatsApp/Instagram photo and Discord draft insertion also have user phone confirmation.
The new Instagram video adapter is checked against the real installed APK's gallery
contract and video data objects, original bytes/metadata, UI capability gating and
recording native-client fixtures. These checks do **not** establish authenticated video
upload or delivery through Vector. The gallery contract is version-gated; other builds
and unsupported formats keep DM. Native video preparation uses IO and a host-owned
temporary file; it revalidates the original chat before single-use dispatch.
Its request-owned gallery delegate checks again at Instagram's final video callback,
after native asynchronous processing, without changing the app's normal gallery sender.

See [Instagram analysis](instagram-dm-delivery.md), [Discord analysis](discord-apk-analysis.md)
and [Telegram/Nagram analysis](telegram-delivery.md). Android's
[rich-content API](https://developer.android.com/develop/ui/views/receive-rich-content)
requires the receiving app to implement the desired formats. Discord's
[message API](https://docs.discord.com/developers/resources/message#create-message)
uses existing sticker IDs; [custom server stickers](https://support.discord.com/hc/en-us/articles/4403089981975-Custom-Stickers-FAQ)
require Nitro for use in DMs.
