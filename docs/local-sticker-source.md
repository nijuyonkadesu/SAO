# Local Sticker extension

Install SAO and the matching `local-sticker.apk`, then choose **Settings → Media sources → Add source → Local Sticker**. Give the profile a name, choose one or more folders with the Android folder picker, optionally rename each collection, and save. Repeat to create independent profiles. Their names appear in SAO's existing source switcher.

The extension recursively indexes supported JPEG/PNG/WebP/GIF/HEIF/AVIF images and MP4/WebM/MKV videos. Originals remain in the chosen folders. Folder grants, indexed metadata, corrections and models belong exclusively to the extension; SAO receives public profile references, pages and read-only media streams. There is no all-files permission. Internet access is used for the explicit Context-model download, never to upload images or search text; ML Kit's CCT telemetry backend and upload components are removed from the manifest.

## Automatic indexing and corrections

Bundled ML Kit models generate general image labels and Latin-script OCR without Google Play services, a model picker or per-file approval. GIFs, animated WebP and videos use a representative frame. The general labeler recognizes objects/concepts; it is not a dedicated anime, character or meme-emotion model. Context search adds separate CLIP embeddings; named people/places are not supported.

Files are available by name as soon as discovered; generated metadata becomes searchable as processing progresses. SAO observes index notifications only while the floating picker is visible. Matching updates merge into the existing result list while preserving the focal item. A new search uses the current full index and normal sort order. Closing the picker unregisters its observer; no result polling or idle source binding is used.

Each folder card shows its phase, images (including the detected animation count), videos, and separate processed/remaining/failed counters for labels/OCR and Context. Discovery shows a growing count and indeterminate progress; analysis shows determinate progress across both enabled stages. **Pause/Resume** preserves that album's checkpoints while other albums continue. **Restart** confirms a fresh scan and reanalysis of only that album, preserving corrections and originals. Library-wide pause/resume and rescan remain available below the cards. Folder-access failures and failed media are reported separately.

Choose **Browse and correct metadata** to edit a file's title, description, tags/emoji and OCR. Editing tags replaces its automatic tag set; editing OCR replaces recognized text for search. Unedited fields continue to use automatic results. **Reset to automatic metadata** removes those overrides. Rescans and changed-file analysis preserve corrections and never write into original files. Context uses image content, so editing tags does not rewrite its embedding.

Indexing runs in one-time, battery/storage-constrained WorkManager jobs. Directory checkpoints and analysis state survive process death and constrained job restarts. Labels/OCR and Context are interleaved in bounded batches, so early files become semantically searchable before all OCR finishes; one model closes before the next loads. Work yields between bounded passes and stops on cancellation. There are no periodic scan jobs, keepalive foreground services, screen-on flags or custom wake locks. Android/WorkManager holds its normal execution wake lock only during scheduled work; this is unrelated to floating-icon recovery. Removing folder access does not erase existing metadata. Choose the folder again to restore access, then rescan.

Initial save starts a scan. Using a source checks for new/changed files at most once every five minutes; **Rescan folders** requests a check immediately and retries failed files. Unchanged files retain their prior analysis. A failed folder traversal cannot prune that folder's existing entries. Automatic analysis failures remain visible in the metadata browser and do not prevent manual tagging or exporting readable originals.

## Context search

In the extension settings, download the **609 MB** CLIP model, enable **Use Context search for this library**, then save. Context becomes that profile's default search mode. Without the model, the source continues to work with Description, filename, path and OCR searches. Model files are shared across local profiles; each profile keeps its own index and settings.

This uses Immich's default `ViT-B-32__openai` FP32 image/text encoders, the corresponding 77-token byte-level tokenizer, bicubic shortest-edge resize/center crop, CLIP normalization, normalized 512-dimensional vectors and cosine ranking. A phrase such as “happy person wearing a blue shirt” is compared with image embeddings; it does not require matching generated tags. Emoji input goes through the text encoder intact. Android decoding/downsampling and representative video frames can differ from a server's previews, and a server configured with another model will produce different rankings.

The fixed model revision and SHA-256 hashes are verified before an atomic installation. Downloads support pause/resume and bounded retries; no inference request uses the network, and ONNX telemetry is explicitly disabled. Model sessions use two CPU threads with idle spinning disabled. The text session is released after 30 seconds of inactivity or service destruction, and the image session closes after each bounded indexing pass. Model weights are reused; query results are not cached.

Embeddings live in the extension's private SQLite database and are invalidated when a file changes. Each vector uses 2 KiB before SQLite overhead: 120,000 indexed files require about 234 MiB for vectors alone. Ranking streams internal batches of at most 256 vectors and retains at most 61 candidates, using a small native dot-product kernel. The Android 15 emulator ranked 120,000 synthetic vectors for the first 60 results in about 0.66 seconds with a warm text encoder, excluding index creation; phone timings vary. Pagination uses score/ID cursors, with no hidden result cap. All indexing, ranking, model loading and file work run off the main thread.

## Search capabilities

| Control | Local Sticker behavior |
| --- | --- |
| Context (default when enabled) | CLIP semantic similarity, including whole phrases and emoji input; requires the downloaded model |
| Description (fallback default) | Word-prefix matching across names, title, description, effective tags and OCR; terms combine with AND |
| Filename | Literal filename substring |
| Full path or folder | Literal collection/relative-path substring |
| OCR | Effective recognized/corrected text only |
| Media | All, images or videos |
| Emoji | Text embeddings in Context; literal metadata/tag matching in Description and tag filters, including common label aliases such as Smile → 😊. Unicode joiners/skin tones are retained |
| Lucky | Random sample from eligible indexed files |
| Sequence | Saved/modified time descending, with stable ties and the exact held item |
| People, Places | Disabled through capability declarations |

Search text stays on the device and is evaluated inside the extension. The metadata database uses SQLite FTS4 for word search and escaped literal matching for emoji/punctuation. Metadata results are paginated by saved time/ID; Context results use score/ID. New searches read the current index. Original/preview bytes use SAO's shared cache, including shared original variants for animations and video exports.

## Building and testing

```sh
./gradlew :local-extension:assembleRelease :local-extension:assembleDebugAndroidTest
```

The ML runtime dominates APK size. Standalone APKs are produced separately for `arm64-v8a`, `armeabi-v7a` and `x86_64`; sign them with the same key as SAO. Use the x86_64 debug APK on the emulator. Local-source classes/models are absent from SAO's production dependencies. There is no dependency on Rays or copied Rays APK content.

The optimized ARM64 APK is about 28 MiB, with compressed native libraries; installation extracts the native runtimes. The optional CLIP weights are downloaded separately. ML Kit's manifest-discovered component constructors, the native scorer entry points and ONNX Runtime's JNI types are explicitly retained during shrinking, following [Microsoft's required R8 rule](https://onnxruntime.ai/docs/build/android.html#note-proguard-rules-for-r8-minimization-android-app-builds-to-work). The numerical kernel uses NDK 28.2.13676358/CMake 3.22.1. If Kotlin 2.4.20 reports the incremental-cache assertion `Class name must not be root`, rebuild with `-Pkotlin.incremental=false`; this is a compiler-cache workaround, not an application runtime setting.

Local tests cover indexing, per-album progress and controls, migration, corrections, model downloads/recovery, official tokenizer and encoder reference outputs, native numerical accuracy, 120,000-vector ranking and cancellation. Cross-app tests cover the unchanged host API and real settings controls. Tests use synthetic/private fixtures and public model files, never personal Immich media. Settings are visually checked in light/dark themes.

The local instrumentation suite uses synthetic, private documents and bundled test media. Cross-app tests can prepare a synthetic local profile explicitly:

```sh
adb shell am instrument -w -e prepareLocalFixture true \
  -e class one.njk.sao.extension.local.LocalLibraryDeviceTest#prepareHostFixture \
  one.njk.sao.extension.local.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class one.njk.sao.floating.LocalSourceDeviceTest \
  one.njk.sao.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e removeLocalFixture true \
  -e class one.njk.sao.extension.local.LocalLibraryDeviceTest#removeHostFixture \
  one.njk.sao.extension.local.test/androidx.test.runner.AndroidJUnitRunner
```

For Context fixtures, download through the extension settings or install the pinned model files into the debug extension's `no_backup/clip-default` directory with a `ready` marker containing `ClipModels.MODEL`, then pass `-e clipModelFixtures true` to `LocalContextDeviceTest`. Add `-e localScaleCheck true` for the 120,000-item test. `ClipDownloadDeviceTest` requires the explicit `-e verifyModelDownload true` network opt-in and downloads only the public tokenizer. Prepare the cross-app Context fixture with `-e prepareLocalFixture true -e prepareContextFixture true` before running `LocalContextSourceDeviceTest`; `-e removeLocalFixture true` cleans that owned fixture afterward. [Reference generation](../tools/generate-clip-reference.py) uses the pinned official models and bundled synthetic image, not guessed classifications.

Primary references: [Immich searching](https://docs.immich.app/features/searching/), [pinned model files](https://huggingface.co/immich-app/ViT-B-32__openai/tree/a857c8de2c07bbcfa6646adfcf31b798845afa1e), [Immich visual preprocessing](https://github.com/immich-app/immich/blob/main/machine-learning/immich_ml/models/clip/visual.py), [text tokenization](https://github.com/immich-app/immich/blob/main/machine-learning/immich_ml/models/clip/textual.py), [ONNX Runtime Android](https://onnxruntime.ai/docs/tutorials/mobile/deploy-android.html), [folder grants](https://developer.android.com/training/data-storage/shared/documents-files), [WorkManager constraints](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [bundled labeling](https://developers.google.com/ml-kit/vision/image-labeling/android), [bundled OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [FTS4 syntax](https://www.sqlite.org/fts3.html).
