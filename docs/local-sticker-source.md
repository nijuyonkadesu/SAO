# Local Sticker extension

Install SAO and the matching `local-sticker.apk`, then choose **Settings → Media sources → Add source → Local Sticker**. Give the profile a name, choose one or more folders with the Android folder picker, optionally rename each collection, and save. Repeat to create independent profiles. Their names appear in SAO's existing source switcher.

The extension recursively indexes supported JPEG/PNG/WebP/GIF/HEIF/AVIF images and MP4/WebM/MKV videos. Originals remain in the chosen folders. Folder grants, indexed metadata and corrections belong exclusively to the extension; SAO receives public profile references, pages and read-only media streams. There is no all-files permission or Internet permission.

## Automatic indexing and corrections

Bundled ML Kit models generate general image labels and Latin-script OCR without Google Play services, a model picker or per-file approval. GIFs, animated WebP and videos use a representative frame. The general labeler recognizes objects/concepts; it is not a dedicated anime, character or meme-emotion model. It does not produce semantic embeddings or identify named people/places.

Files are available by name as soon as discovered; generated metadata becomes searchable as processing progresses. SAO observes index notifications only while the floating picker is visible. Matching updates merge into the existing result list while preserving the focal item. A new search uses the current full index and normal sort order. Closing the picker unregisters its observer; no result polling or idle source binding is used.

Open the source's settings to see progress, pause/resume, rescan, or **Browse and correct metadata**. Tap a file to edit its title, description, tags/emoji and OCR. Editing tags replaces its automatic tag set; editing OCR replaces recognized text for search. Unedited fields continue to use automatic results. **Reset to automatic metadata** removes those overrides. Rescans and changed-file analysis preserve corrections and never write into original files.

Indexing runs in one-time, battery/storage-constrained WorkManager jobs. Directory checkpoints and analysis state survive process death; work yields between bounded batches and stops on cancellation. There are no periodic scan jobs, keepalive foreground services, screen-on flags or custom wake locks. Android/WorkManager holds its normal execution wake lock only during scheduled work; this is unrelated to floating-icon recovery. Removing folder access does not erase existing metadata. Choose the folder again to restore access, then rescan.

Initial save starts a scan. Using a source checks for new/changed files at most once every five minutes; **Rescan folders** requests a check immediately and retries failed files. Unchanged files retain their prior analysis. A failed folder traversal cannot prune that folder's existing entries. Automatic analysis failures remain visible in the metadata browser and do not prevent manual tagging or exporting readable originals.

## Search capabilities

| Control | Local Sticker behavior |
| --- | --- |
| Description (default) | Word-prefix matching across names, title, description, effective tags and OCR; terms combine with AND |
| Filename | Literal filename substring |
| Full path or folder | Literal collection/relative-path substring |
| OCR | Effective recognized/corrected text only |
| Media | All, images or videos |
| Emoji | Literal emoji input, manually assigned emoji tags and aliases for common generated labels (such as Smile → 😊); Unicode joiners/skin tones are retained |
| Lucky | Random sample from eligible indexed files |
| Sequence | Saved/modified time descending, with stable ties and the exact held item |
| Context, People, Places | Disabled through capability declarations |

Search text is never sent to another app or server. The metadata database uses SQLite FTS4 for word search and escaped literal matching for emoji/punctuation. Search results are paginated by saved time/ID rather than offsets; new searches read the current index. Original/preview bytes use SAO's shared cache, including shared original variants for animations and video exports.

## Building and testing

```sh
./gradlew :local-extension:assembleRelease :local-extension:assembleDebugAndroidTest
```

The ML runtime dominates APK size. Standalone APKs are produced separately for `arm64-v8a`, `armeabi-v7a` and `x86_64`; sign them with the same key as SAO. Use the x86_64 debug APK on the emulator. Local-source classes/models are absent from SAO's production dependencies. There is no dependency on Rays or copied Rays APK content.

The optimized ARM64 APK is about 28 MiB. ML Kit's manifest-discovered component constructors are explicitly retained during shrinking. If Kotlin 2.4.20 reports the incremental-cache assertion `Class name must not be root`, rebuild with `-Pkotlin.incremental=false`; this is a compiler-cache workaround, not an application runtime setting.

Six local indexing/metadata checks, the cross-app media flow, the real folder-picker flow against the optimized APK and a visible-only source-update check passed on the Android 15 emulator. The host regression suite passed another 55 checks; 51 JVM tests passed, and lint reported zero errors. Settings and metadata screens were visually checked in light/dark themes using synthetic media.

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

Primary references: [folder grants](https://developer.android.com/training/data-storage/shared/documents-files), [WorkManager constraints](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [bundled labeling](https://developers.google.com/ml-kit/vision/image-labeling/android), [bundled OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [FTS4 syntax](https://www.sqlite.org/fts3.html).
