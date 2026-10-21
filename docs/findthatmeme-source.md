# FindThatMeme source APK

> [!NOTE]
> Source implementation and extension build commands now belong to [SAO-extensions](https://github.com/nijuyonkadesu/SAO-extensions). Install and update the APK from SAO’s Media sources page. [Distribution and build instructions](extension-catalog.md).

Install **`findthatmeme-sao.apk`**, then open **SAO → Settings → Media sources → Add source → FindThatMeme**. Name the source and choose **Save and use source**. No account, API key or SAO APK update is required on the existing source-extension host. Repeat to create additional named profiles; optional included words scope each profile's searches.

The extension uses the existing v1 discovery, Binder and read-granted content-provider contract. No host, source API or shared settings implementation was changed. Existing signing-certificate approval applies normally to independently signed builds.

## Browsing

- Keyword/caption search, including intact multiword queries and emoji text, appears under **Description**. This provider does not perform Immich-style Context search.
- Images and Videos filters use the backend's type syntax and also check returned media types. Unsupported Context/OCR modes, Lucky, chronology, suggestions and uploads are not advertised.
- Pages contain up to 50 backend records. SAO requests continuation; the extension performs one HTTP search per requested page. Cursors are authenticated and bound to the query, profile and configuration revision. Consecutive repeated pages fail instead of scrolling forever.
- JPEG, PNG, WebP and GIF images retain their source bytes. Video MP4/WebM streams use the site's image poster for carousel previews and the original file for playback/export. Unsupported or incomplete records are skipped.
- Six editable home presets are provided through the existing category capability. SAO's normal home-category editor, floating visibility and All behavior continue to apply.

**Include sensitive results** is off by default. The extension filters likely explicit results using the site's classification scores, while continuing pagination over filtered records. Unrated and misclassified content can still appear. Enabling it marks the whole profile as NSFW, so SAO's existing NSFW Mode gates it.

## Resource ownership and failures

The APK stores only private encrypted profile configuration and the key needed to authenticate opaque references. There is no result/media cache, indexing job, foreground service, periodic request or polling loop. SAO retains its existing media cache, transfer limits and request/retry scheduling. Renaming a profile preserves its configuration revision; changing its scope revokes old grants and creates a new revision.

HTTP requests run on demand with cancellation, timeouts and automatic connection retries disabled. HTTP errors and `Retry-After` travel through the normal source failure contract. A failed file does not poison subsequent image/video requests. Search JSON is limited to 2 MiB; original streams to 64 MiB; separate video posters to 8 MiB, including responses without a size header. Returned URLs are restricted to the catalog's HTTPS media bucket, and file signatures are verified before forwarding bytes. Generic or incorrect HTTP MIME headers are not trusted.

Streams use `ParcelFileDescriptor.createReliableSocketPair()` with the same sequential-read interface SAO already consumes. A blocking peer-close read cancels a stalled HTTP transfer when the host closes its descriptor; it has no timer or polling interval and is released when the transfer finishes. This covers cancellation after Android has returned the descriptor, when the opening `CancellationSignal` alone is insufficient. Reliable errors remain available to the host's `checkError()` call.

The site API is unofficial. Service changes may require an extension-only update. See the [live public-source investigation](public-meme-source-research.md) for relevance tests, limitations and curl examples.

## Build and checks

```sh
./gradlew :findthatmeme-extension:assembleDebug :findthatmeme-extension:assembleDebugAndroidTest :findthatmeme-extension:assembleRelease :findthatmeme-extension:lintDebug
cp app/build/outputs/apk/debug/app-debug.apk /srv/samba/share/
```

The optimized APK is approximately **2.2 MiB**, signed with the same development certificate as the supplied SAO build. The host APK itself remains byte-for-byte unchanged. Test fixtures contain synthetic PNG/GIF/MP4/WebM files; live tests are explicit opt-in and access only the public catalog.

```sh
adb shell am instrument -w -e class one.njk.sao.extension.findthatmeme.FindThatMemeDeviceTest one.njk.sao.extension.findthatmeme.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e publicLive true -e class one.njk.sao.extension.findthatmeme.FindThatMemeLiveDeviceTest one.njk.sao.extension.findthatmeme.test/androidx.test.runner.AndroidJUnitRunner
```

Validation on Android 15 passed **14 fixture checks and one live public-catalog check**, covering profiles/capabilities, complete query text, pagination, cursor isolation, sensitive-content continuation, exact media bytes, size limits, cancellation, cooldowns and recovery. The unchanged host discovers, browses and edits the extension in both themes; unsupported Lucky stays hidden. The signed optimized APK was separately installed, configured through SAO and used to play a real public MP4. Lint reports zero errors and the same target-SDK warning as the other target-36 extensions.

[Reviewed settings, floating search and live video screenshots](screenshots/README.md#findthatmeme-extension).
