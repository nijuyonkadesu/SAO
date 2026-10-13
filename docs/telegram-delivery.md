# Telegram and Nagram X sticker delivery

Investigated on 2026-10-09 after a real Nagram X report: SAO displayed “sticker unavailable”
and clipboard paste produced a photo attachment.

## Causes and fixes

1. `DeliveryPolicy.clipboard` previously allowed a sticker rendition only for WhatsApp. Every
   Telegram target therefore received a white-background JPEG and the media-fallback status,
   even while the UI mode chip said Sticker. Static Telegram selections now copy a transparent
   512×512 WebP; Media mode still copies JPEG, and GIFs retain their original animation.
2. Nagram X's published `nu.gpu.nagram` / `nu.gpu.nagramx` packages were not built-in Telegram
   targets. They are now recognized automatically. Other package names retain the explicit
   Telegram app-picker/manual mapping; exact recipient packages are never substituted.
3. The Sticker action used an ordinary `ACTION_SEND image/webp`. Telegram's `LaunchActivity`
   routes `image/*` into its photo workflow. That is insufficient evidence of sticker capability.
   The action now selects the document workflow with `application/octet-stream`; the URI's
   FileProvider type, ClipData MIME, filename and bytes remain WebP. No caption or multiple-file
   payload is added. Telegram's document preparation recognizes the `.webp`, decodes its bounds,
  and attaches native sticker attributes to the document. Capability checks use that same route.

Both inspected clients enable their cache-copy path on Android 11+ (`NO_SCOPED_STORAGE` is
true only through API 29), covering SAO's minimum API 30. That copy preserves the provider's
`.webp` display name before the document MIME/attribute checks.

Changing the Sticker/Media preference no longer re-copies an existing selection. It affects
the next image tap, Copy or explicit Paste action. The last copy status describes the actual
clipboard content. The chip reserves the larger label's width, and title/status each occupy
one bounded, ellipsized line, keeping the carousel geometry stable on toggles. Full text is
available to accessibility services.

## Checked source paths

Pinned official Telegram source: commit `f2908b14133bbffbf7ab04f641ecb5bfaf533242`,
“update to 12.10.6 (7112)”, 2026-09-30.

- [LaunchActivity](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java):
  image share versus document share, `didSelectDialogs`, and document sending.
- [ChatActivityEnterView](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java):
  clipboard paste calls `editPhoto` using the supplied MIME; that MIME selects the temporary
  filename extension. Keyboard rich content follows a separate input-connection path.
- [SendMessagesHelper](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java):
  WebP recognition in `prepareSendingMedia` and native `TL_documentAttributeSticker` creation
  in document preparation. The imported-document dimension condition is nonzero width/height
  no greater than 800; SAO uses 512×512 and a 100 KiB bound.
- [MediaController](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java):
  URI byte-signature detection, provider display-name lookup, and preservation of filenames
  while copying a shared file into Telegram's private cache.

The same relevant behavior was checked in Nagram X commit
`2db685af00a4352c877ecf96474cbf0494284715` (2026-08-23):
[LaunchActivity](https://github.com/risin42/NagramX/blob/2db685af00a4352c877ecf96474cbf0494284715/TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java),
[ChatActivityEnterView](https://github.com/risin42/NagramX/blob/2db685af00a4352c877ecf96474cbf0494284715/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java),
[SendMessagesHelper](https://github.com/risin42/NagramX/blob/2db685af00a4352c877ecf96474cbf0494284715/TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java),
and its [published package names](https://github.com/risin42/NagramX/blob/2db685af00a4352c877ecf96474cbf0494284715/README.md).

The [official sticker-import API](https://core.telegram.org/import-stickers) creates a new pack
on each import. It is a separate operation and is not used as a substitute for sending a
single selected sticker.

## Verification limits

Tests decode the actual clipboard/provider bytes, verify WebP MIME and dimensions, retain JPEG
Media mode, exercise a receiver under another UID with a document-only intent filter, and
verify unchanged clipboard timestamps/URIs and carousel geometry across mode toggles with
a long filename at normal and 200% text sizes. The test receiver models the inspected document/sticker branch and is explicitly
not a logged-in Telegram/Nagram X client. No live messages were sent. Confirm final behavior
on the user's installed version; clipboard photo editing/captions can change the final payload,
and arbitrary forks can alter upstream classification.
