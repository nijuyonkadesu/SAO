# Media conversion and attachment limits

SAO uses its existing platform image codecs and Media3 1.11.1. No new dependency, native codec bundle or source-extension update is required. Conversions belong to the host's delivery layer; extension credentials, APIs and original files remain separate.

| Action | Bytes delivered |
| --- | --- |
| Upload to a source | Original, unchanged |
| Floating Media / Copy; home Download | Original, unchanged |
| Home Share | Displayed rendition, unchanged; no original download |
| Static sticker | Reuse a compliant file, otherwise prepare its app-specific 512-pixel rendition |
| WebM compatibility fallback | MP4 only when the receiving route cannot accept WebM |
| Discord Smaller copy | Explicitly requested, size-limited copy; never replaces the original |

## Images

Sticker conversion decodes a seekable file descriptor directly to approximately the required 496-pixel content area, with software allocation and orientation/reflection applied. It avoids loading the compressed original into a heap array and decoding an unnecessary 2048-pixel intermediate. Pipe/SVG inputs spool to a temporary file once. A compliant 512×512 PNG/WebP without orientation metadata is reused byte-for-byte. Detected animation is rejected rather than flattened.

The existing transparent 512×512 canvas and inset remain. PNG stays lossless. WebP first tries lossless encoding, then at most seven lossy probes against the actual receiving format's limit: WhatsApp static WebP 100 KiB, Telegram static WebP 512 KiB. The search retains a verified fitting candidate; it does not assume perfectly monotonic encoded sizes. WhatsApp's keyboard PNG intermediary is separate from its final native WebP limit. SAO still does not generate animated/video stickers; WhatsApp's animated-WebP limits are not a target for ordinary videos.

Discord's optional smaller JPEG copy retains full resolution and applies orientation. PNG retains transparency and lossless encoding. An unsafe full-resolution allocation, an impossible lossless budget or an Ultra HDR gainmap image is refused with an explanation, allowing the user to use the original. No hidden resizing or alpha flattening is used to meet a budget.

## Video and HDR

Compatibility conversion reads the already cached video under a scoped lease, avoiding a second original download and input snapshot. Media3 can transmux matching tracks; otherwise SDR uses H.264/AAC and HDR uses HEVC/AAC. Decoder fallback is enabled; resolution/MIME fallback is disabled. Output dimensions, tracks and container are validated before exposing the file. Cancellation stops Transformer on its own looper, closes the worker thread and deletes incomplete output.

Original HDR media is always preserved. A necessary conversion keeps HDR and its transfer function; an attempted SDR fallback or missing output HDR metadata fails explicitly. Devices without suitable HDR decode/edit/encode support can use the original when the receiving route accepts it. HDR conversion cannot be promised on every phone.

Only an explicit **Smaller copy** choice supplies a video byte target. The calculation reserves audio/container space, permits at most two encoding attempts and checks the actual output size. Dimensions, frame timing and audio are retained. Budgeted MP4 attachments omit the muxer's streaming-space reservation instead of spending the byte budget on padding; normal compatibility exports keep the default streaming layout. No file is dispatched if it still exceeds the requested budget.

## Discord's size question

Only explicit Discord **Paste / Attach** over 20 MiB opens **Cancel / Use original / Smaller copy**. Selection and Copy never prompt or silently compress. SAO cannot inspect the account's allowance; Nitro users can keep the original. Smaller copy is available for video and static JPEG/PNG, with a quality warning. Other formats can keep the original or cancel. Converted MIME support is rechecked before native dispatch; a failed or uncertain send is never automatically repeated.

## Scheduling and retention

Both browsers share a host export store. Each output has its own coroutine mutex; unrelated copies no longer queue behind one slow download/conversion. One semaphore bounds expensive conversions. File IO uses the IO dispatcher, image processing the bounded conversion dispatcher, and Transformer a dedicated looper. Writes use unique staging files, cancellation checks and atomic renames; failed writes can be retried independently.

Export snapshots have a **192 MiB soft LRU budget** and seven-day age limit. Active inputs/writers, ten-minute handoffs and the latest SAO-published media clipboard item are protected. One clipboard marker survives process death; it is updated only when SAO publishes a clip and cleared when SAO copies a filename. Protected files can temporarily exceed the budget. Old export namespaces and home-share snapshots participate in cleanup. Maintenance runs on explicit exports, with no timer, polling, worker or wake lock. The separate source-media cache remains 192 MiB and protects active conversion inputs from eviction.

## References and validation

Reviewed [Compressor at 751b07b](https://github.com/JoshAtticus/Compressor/tree/751b07b1e934562b3f1380c73482716179747568) (MIT) for track probing, decoder fallback and explicit bitrate configuration, and [ImageToolbox at 96fbe7f](https://github.com/T8RIN/ImageToolbox/tree/96fbe7ffb664bc3710f8f85477fe613d9b1cfa94) (Apache-2.0) for bounded software decoding and encoding choices. These are independently implemented changes using dependencies already in SAO; neither application's module/native libraries were copied. ImageToolbox's long decrement-and-resize byte search and Compressor's device-brand workarounds were not adopted.

Primary specifications: [Media3 transformations](https://developer.android.com/media/media3/transformer/transformations), [customization](https://developer.android.com/media/media3/transformer/customization), [HDR](https://developer.android.com/media/media3/transformer/tone-mapping), [MP4 streamable-space tradeoff](https://developer.android.com/reference/androidx/media3/transformer/InAppMp4Muxer.Factory#setAttemptStreamableOutputEnabled(boolean)), [WhatsApp](https://github.com/WhatsApp/stickers), [Telegram](https://core.telegram.org/import-stickers), [Discord attachment allowances](https://support.discord.com/hc/en-us/articles/25444343291031-File-Attachments-FAQ).

Synthetic Android tests exercise real image/video codecs, EXIF rotation/reflection, animation rejection, exact originals, receiving-app sticker budgets, full-resolution smaller copies, impossible transparent PNG budgets, cancellation, concurrent exports, input leases, cleanup/clipboard retention and Discord's real prompt with intercepted sends. HDR original bytes are verified; the emulator's software renderer cannot perform this HDR export and exercises the safe capability failure. Hardware HDR conversion still needs a compatible phone check. No private media or real chats are used. [Reviewed prompt screenshots](screenshots/README.md#attachment-size-choice).

Validation: 37 host JVM tests and 30 targeted Android 15 checks pass (15 new export/prompt checks and 15 home/source/upload/video regressions). Two older fixtures were corrected to target the actual dialog window and refresh profile availability after earlier fixtures. Lint reports zero errors; the 97 existing warnings remain. The host debug APK is copied to the configured Samba share after each debug build.
