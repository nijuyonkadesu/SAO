# Extension catalog and updates

**Settings → Media sources** lists configured profiles, installed extensions and **Get extensions** in one page. Install adds a supported source APK; Add source configures a named profile inside it. Update appears beside an installed extension when its numeric version code is older. App info and Uninstall remain Android system actions.

Android asks once to allow installations from SAO, then confirms each install or update. Declining or cancelling leaves installed APKs and their profiles intact. Updates retain package IDs, signing certificates and extension-private credentials/indexes. Installing an APK does not create a profile or enable uploads.

## Repository and release layout

[SAO-extensions](https://github.com/nijuyonkadesu/SAO-extensions) contains Immich, Local Sticker, Moeview, FindThatMeme and experimental Google Photos, plus their client modules, licenses and source documentation. The move preserves 46 relevant commits and their authors/messages; `docs/history-map.txt` records original-to-filtered IDs. SAO’s original history was not rewritten.

The extension repository’s `main` branch contains source. Its small [`repo` branch](https://github.com/nijuyonkadesu/SAO-extensions/tree/repo) contains `index.json`, `repo.json`, a static download page and SVG icons/licenses. APKs live in independent GitHub Releases, rather than Git history. Distributed releases are optimized APKs signed with the official release certificate and labelled stable. CI is deferred; [manual publishing](https://github.com/nijuyonkadesu/SAO-extensions#manual-releases) reads signing credentials from environment variables, verifies the pinned publisher, and uploads immutable versions with `gh`. Publish APK assets before updating catalog pointers. Keep that certificate for future in-place updates; debug-signed installations cannot be updated by a differently signed release.

## Catalog contract

The default public [catalog URL](https://raw.githubusercontent.com/nijuyonkadesu/SAO-extensions/repo/index.json) uses schema version 1. Each extension has an ID, package name, name/description, numeric `versionCode`, display `versionName`, protocol `apiVersion`, `minSdk`, publisher certificate SHA-256, release channel and artifacts. Each artifact declares ABI (or universal), HTTPS release URL, byte size and SHA-256. Installed cards use Android’s package icon, loaded on IO with the package inventory. Available cards use the optional catalog SVG icon through the existing Coil cache and AndroidSVG dependency. Missing or invalid icons fall back to a themed source symbol. Official catalogs retain their icon URL namespace restriction. Custom catalogs can supply HTTPS icon URLs. SVGs are bounded to 64 KiB and rendered at the small display size on IO, and entity declarations are rejected. First-party artwork comes from the extension APK vectors; the release tool exports those same paths/groups as small catalog SVGs, while Android receives adaptive and monochrome layers. [Preview and attribution](https://github.com/nijuyonkadesu/SAO-extensions#sao-extensions). Icon cache keys include the extension version so new builds refresh artwork without changing signed URLs or discarding other cached images. Rebinding progress does not restart icon requests; recycling cancels them. Release links remain optional metadata.

Catalog distribution metadata is independent of source capabilities: the installed extension still negotiates search modes, paging, filters and imports through the existing Binder protocol. No provider-specific logic moved into SAO.

**Get extensions → Add repository** accepts a direct HTTPS SAO `index.json` URL or a GitHub repository link (the latter resolves to `repo/index.json`; tree/blob links also work). The official repository is included initially. Any number of repositories can be added or removed, including the official one; removing the last leaves the list empty. These URLs use the existing settings DataStore and do not contain credentials. Removing a repository does not uninstall its apps, remove configured profiles, or revoke separately granted source access.

Opening Media sources checks configured catalogs, with at most two HTTP requests at once. Each completed repository becomes available independently; errors appear beside the failing URL. While the screen's ViewModel lives, completed catalogs are retained and adding/removing a URL does not refetch unaffected repositories. **Check for updates** explicitly refreshes all repositories. No disk catalog cache, background polling, automatic install or automatic download retry is added. Browsing media never starts an update check.

Repository priority follows the displayed order: the official repository first when present, then other URLs alphabetically. The first repository owns a duplicate package name; another repository cannot silently replace that publisher or offer a different build under the same row. Each available card identifies its repository. Custom repositories use the same schema/version and bounds as the official catalog; arbitrary web pages and Mihon/Keiyoushi indexes are not SAO catalogs.

## Download and installation

The existing OkHttp stack streams downloads on IO with cancellation attached through body consumption. Normal connection recovery remains enabled: an unreachable resolved address or stale pooled connection does not have to fail an explicit GET when another route works. There is no application retry loop, background retry, or automatic restart of an interrupted response body. HTTPS-to-HTTP redirects remain disabled. Catalogs are bounded to 512 KiB, 64 entries and eight artifacts per entry; individual APKs to 128 MiB. Device ABI, Android version and protocol gate actions. The official repository retains the official release URL checks and pins the release publisher's certificate independently of the installed SAO certificate, so debug builds can browse and download official releases too. Explicitly added repositories may distribute independently signed SAO extensions over HTTPS; the actual APK must match the catalog certificate and checksum. Installation does not approve that publisher for Binder/media access: the existing warning and explicit approval remain required afterward. Updates always require the installed certificate to match, so this updater cannot replace an installed app with another publisher’s build. In particular, a release APK cannot update a debug-signed installation of the same extension.

Long-press an available or installed extension card to copy its device-compatible APK download URL. Installed apps without a matching catalog entry have no download link to copy. The same action is labelled for accessibility, and recycling or updating a card replaces its link.

Before staging, SAO verifies exact size and SHA-256, package ID, numeric/display version, signing certificate, minimum SDK and exported source-service metadata. It rechecks before installation and refuses downgrades or same-version replacement. Android verifies the staged package again. This uses `PackageInstaller.Session` with explicit user confirmation and a non-exported result receiver; it does not use a generic file-opening intent or privileged installer.

Repository membership is checked before downloading and again before installation. Removing the supplying repository cancels its uncommitted transfer; already submitted Android installer sessions remain system-owned. Permission detours pin the chosen repository, package, version and signer. Only one transfer is active. Downloads survive rotation in a ViewModel, can be cancelled, and are removed if the settings screen is destroyed. A download finishing while hidden waits for the screen to resume before opening Android’s installer. Incomplete staging sessions are abandoned; completed staging files are removed, and stale private download files are reclaimed on the next explicit download. No wake lock, boot task or polling timer is added. Installer results refresh the installed inventory; failures leave retry available.

## Shared SDK and standalone builds

SAO maintains `source-api` and `source-settings`, published as versioned AARs with sources and standard Ivy descriptors in [SDK 1.2.0](https://github.com/nijuyonkadesu/SAO/releases/tag/source-sdk-1.2.0), including the optional seekable-media and cancellation contract. Published SDKs resolve without a sibling checkout or GitHub token. For local SDK development, stage both libraries with the publication tasks below and use the extension checkout’s `saoSdkRepo` override if the checkouts are not siblings. Host instrumentation uses separately published reference-client test libraries; production SAO has no extension implementation dependencies.

To publish a new SDK from SAO, update the publication version and run:

```sh
./gradlew :source-api:publishSourceSdkPublicationToSourceSdkRepository :source-settings:publishSourceSdkPublicationToSourceSdkRepository
```

Upload `build/source-sdk/` artifacts to the matching `source-sdk-<version>` release before updating extension dependency versions. See `gradle/publish-source-sdk.gradle` for the naming/layout contract. Keep existing artifacts immutable.

## Validation

The initial repository migration passed standalone builds/lint for its four sources, 57 extension JVM and 38 Android checks. Initial host validation included 47 JVM checks and 42 Android checks covering browsing, media export, reverse uploads, extension settings and system controls. Catalog checks cover metadata validation, APK identity/checksum/signature/protocol, bounded retries, cancellation cleanup and compatibility policy. A real Android 15 install/update test downloaded published releases, exercised unknown-app permission, cancelled and retried an update, retained a saved FindThatMeme profile, and installed/discovered Moeview. Light/dark and large-text layouts were reviewed. [Screenshots](screenshots/README.md#extension-catalog-and-updates).

Catalog Android tests accept a release-signed FindThatMeme APK staged as `cache/catalog_fixture.apk` in the host’s private cache. For tests which exercise successful validation/download, that package must be absent or older on a disposable emulator; production rejects installing the same version. Missing/non-upgradable fixtures are skipped. The icon follow-up passes nine targeted Android checks, including bounded transparent SVG rendering, entity rejection, optional-icon URL policy, updater validation and installed-system controls; light/dark and 200% text were visually reviewed. Stage generated catalog SVGs in `cache/catalog-icon-fixtures` to exercise the real Coil decoder pipeline. Passing `liveCatalogIcons=true` additionally checks the four public GitHub icon URLs; it never queries media libraries. Never uninstall an extension containing personal data for a test.

## Manage sources update locality

Home/Floating/All preference writes are serialized without changing every row to a temporary disabled state. Profile selection updates use RecyclerView payloads; unchanged headers and installed/available cards receive no change notifications. Extension cards hold only their own entry and transfer state, so another package’s progress does not rebind their titles or icons. Wi-Fi controls likewise use an in-place header update.

Repository follow-up (2026-10-24): 45 host JVM checks and nine targeted Android 15 checks pass. Coverage includes escaped/signed URL handling, isolated repository failure and bounded concurrency, duplicate-package priority, no refetch on removal, native add/remove and draft restoration, an independently signed APK, membership rechecks, stalled-transfer cancellation after priority changes, and exactly one profile-row payload with no unrelated adapter notifications. Debug/optimized release builds pass; lint reports zero errors. APKs were copied locally only; no catalog, SDK or APK release was published.

## Release update investigation (2026-10-27)

The public raw index matched the released versions, sizes, hashes and publisher.
The reported Moeview failure was during download/verification, not catalog parsing.
A direct public APK download and the debug repository check passed; the published
minified host reproduced the generic download failure, then reached confirmation
on retry. That observation alone does not establish the failing network route on
the user's phone.

A deterministic transport test reproduced a separate concrete defect in the same
path: `retryOnConnectionFailure(false)` stopped at an unreachable first DNS address
even when another address served the file. Restoring normal bounded connection
recovery fixes that case. The test verifies exactly one server request; a second
test disconnects a response body and verifies it is not fetched again automatically.
Failure-only diagnostics record the download stage and exception class/issue, never
URLs, credentials, identifiers or response bodies.

The [isolated release UI probe](../tools/extension-update-probe/README.md) then drove
the actual signed/minified host, public catalog, public APK downloads and Android
Update confirmations on an owned Android 15 emulator. All five in-place upgrades
passed: Moeview 10→11, Immich 8→9, Local Sticker 15→16 (x86-64), FindThatMeme 3→4
and Google Photos 4→5. Every first-install timestamp was preserved. Moeview also
exercised Android's install-source permission screen. No personal profiles, media
or credentials were used. The probe is independent of SAO's obfuscated classes and
adds no dependency to the host APK. Sixty-four host JVM tests and debug/minified
release builds pass. The local fix was signed with the existing release key;
the published releases/catalog were not replaced by this follow-up.
