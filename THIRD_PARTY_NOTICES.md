# Third-party assets

## Local Context search

The Local Sticker extension uses the default [Immich ONNX export](https://huggingface.co/immich-app/ViT-B-32__openai/tree/a857c8de2c07bbcfa6646adfcf31b798845afa1e) of OpenAI CLIP ViT-B/32 and an optional [8-bit ONNX export](https://huggingface.co/Xenova/clip-vit-base-patch32/tree/d15189d7028b43f1d3e65039190477f6af591c2a), downloaded separately into a user-owned folder. CLIP is copyright OpenAI, 2021, [MIT](local-extension/src/main/assets/licenses/clip.txt). `ClipTokenizer` implements its byte-level BPE using the matching Hugging Face tokenizer vocabulary and merges. Inference uses Microsoft's [ONNX Runtime](https://github.com/microsoft/onnxruntime), [MIT](local-extension/src/main/assets/licenses/onnxruntime.txt). These license texts are bundled in the extension APK.

## Material Symbols

The main settings `ic_settings_*.xml` and usage analytics `ic_usage*.xml` vectors use the same Material Symbols Rounded style (24 px optical size, weight 400, fill 0), from [Google’s upstream symbols](https://github.com/google/material-design-icons/tree/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/android). They are licensed under Apache-2.0; the packaged Material Symbols license applies.

The `ic_picker_*.xml`, `ic_floating_picker.xml` and the Immich/Local Sticker/Moeview/FindThatMeme extensions’ `ic_extension.xml` vectors are Google Material Symbols Rounded, using optical size 24, weight 400, grade 0 and fill 0. Source: [google/material-design-icons, commit 49d4db3](https://github.com/google/material-design-icons/tree/49d4db35df873165d6bd6ba09b063c7dafbac2f4/symbols/android).

Copyright Google LLC. Licensed under the [Apache License 2.0](third_party/material-symbols/LICENSE). The vectors retain Google's path geometry; their root tint is removed and their base fill changed to black so SAO's Material component foreground roles control tinting.

## Emoji autocomplete

`EmojiShortcode.kt` adapts cursor-token detection from Telegram Android's `MentionsAdapter.java`, copyright Nikolai Kudashov, 2013–2018, licensed under [GNU GPL v2 or later](third_party/telegram/LICENSE). [Pinned source and adaptation details](docs/emoji-autocomplete.md). SAO adds bounded scans, URL/time/escape guards and complete-token replacement when the cursor is inside a shortcut.

The generated `assets/emoji/keywords-en.tsv` combines [gemoji](https://github.com/github/gemoji/tree/fadaeaf1f1a9be82b321316a6c5502e43138b2f6), copyright 2019 GitHub, Inc., [MIT license](third_party/emoji-keywords/gemoji/LICENSE), and [Unicode CLDR](https://github.com/unicode-org/cldr-json/tree/91c267402229a59e3ef2774544f001bf959e8809), copyright 2015–2024 Unicode, Inc., [Unicode License V3](third_party/emoji-keywords/cldr/LICENSE). The generator merges English keywords with emoji aliases; SAO displays system/EmojiCompat glyphs. License texts are also packaged under `assets/licenses/`.

## Rays IPC contract

`com/skyd/rays/api/IApiService.aidl` follows the installed-app API from [Rays Android](https://github.com/SkyD666/Rays-Android/tree/9bd92a3ff8c9b4073531fed5d01639456df9136e), copyright SkyD666 and contributors, [GNU GPL v3](third_party/rays/LICENSE). The paged method is contributed in the companion Rays fork. Only the IPC definition is included; Rays' application, database and models remain in the separately installed app. Its license is also bundled under `assets/licenses/rays.txt`.

## Moeview reference client

The `moeview` library and its parser/transport checks adapt the user-provided `booru-android/booru` reference. [Provenance, integration changes and verification limits](docs/moeview-source.md#reference-and-validation). Its source directory is unchanged. The Moeview APK packages the existing shared Material Symbols license rather than maintaining another copy.
