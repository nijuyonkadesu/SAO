# Extension catalog and updates

**Settings → Media sources** lists configured profiles, installed extensions and **Get extensions** in one page. Install adds a supported source APK; Add source configures a named profile inside it. Update appears beside an installed extension when its numeric version code is older. App info and Uninstall remain Android system actions.

Android asks once to allow installations from SAO, then confirms each install or update. Declining or cancelling leaves installed APKs and their profiles intact. Updates retain package IDs, signing certificates and extension-private credentials/indexes. Installing an APK does not create a profile or enable uploads.

## Repository and release layout

[SAO-extensions](https://github.com/nijuyonkadesu/SAO-extensions) contains Immich, Local Sticker, Moeview and FindThatMeme, plus their client modules, licenses and source documentation. The move preserves 46 relevant commits and their authors/messages; `docs/history-map.txt` records original-to-filtered IDs. SAO’s original history was not rewritten.

The extension repository’s `main` branch contains source. Its small [`repo` branch](https://github.com/nijuyonkadesu/SAO-extensions/tree/repo) contains `index.json`, `repo.json`, a static download page and SVG icons/licenses. APKs live in independent GitHub Releases, rather than Git history. These initial releases are optimized APKs signed with the existing development certificate and labelled **Development build**. CI is deferred; [manual publishing](https://github.com/nijuyonkadesu/SAO-extensions#manual-development-releases) prepares artifacts, verifies signing and uploads with `gh`. Publish immutable APK assets before updating catalog pointers. Keep that certificate for future in-place updates.

## Catalog contract

The fixed public [catalog URL](https://raw.githubusercontent.com/nijuyonkadesu/SAO-extensions/repo/index.json) uses schema version 1. Each extension has an ID, package name, name/description, numeric `versionCode`, display `versionName`, protocol `apiVersion`, `minSdk`, publisher certificate SHA-256, release channel and artifacts. Each artifact declares ABI (or universal), HTTPS release URL, byte size and SHA-256. Optional icon/release links serve other catalog clients; SAO does not fetch icons separately.

Catalog distribution metadata is independent of source capabilities: the installed extension still negotiates search modes, paging, filters and imports through the existing Binder protocol. No provider-specific logic moved into SAO.

The host reads the catalog once when opening Media sources. **Check for updates** explicitly refreshes it; a failed request offers Retry while installed sources remain usable. There is no background catalog polling, scheduled update job, automatic install, result cache or automatic download retry. Browsing media never starts an update check.

## Download and installation

The existing OkHttp stack streams downloads on IO with cancellation attached through body consumption. Catalogs are bounded to 512 KiB, 64 entries and eight artifacts per entry; individual APKs to 128 MiB. Device ABI, Android version and protocol gate actions. The host accepts only the official release URL namespace and the same publisher certificate as its own APK. Independently signed extensions can still be installed separately and explicitly approved; this updater never replaces them with a different publisher’s build.

Before staging, SAO verifies exact size and SHA-256, package ID, numeric/display version, signing certificate, minimum SDK and exported source-service metadata. It rechecks before installation and refuses downgrades or same-version replacement. Android verifies the staged package again. This uses `PackageInstaller.Session` with explicit user confirmation and a non-exported result receiver; it does not use a generic file-opening intent or privileged installer.

Only one transfer is active. Downloads survive rotation in a ViewModel, can be cancelled, and are removed if the settings screen is destroyed. A download finishing while hidden waits for the screen to resume before opening Android’s installer. Incomplete staging sessions are abandoned; completed staging files are removed, and stale private download files are reclaimed on the next explicit download. No wake lock, boot task or polling timer is added. Installer results refresh the installed inventory; failures leave retry available.

## Shared SDK and standalone builds

SAO maintains `source-api` and `source-settings`, published as versioned AARs with sources and standard Ivy descriptors in [SDK Releases](https://github.com/nijuyonkadesu/SAO/releases/tag/source-sdk-1.0.0). Extension Gradle builds resolve these public assets without a sibling checkout or GitHub token. Host instrumentation uses separately published reference-client test libraries; production SAO has no extension implementation dependencies.

To publish a new SDK from SAO, update the publication version and run:

```sh
./gradlew :source-api:publishSourceSdkPublicationToSourceSdkRepository :source-settings:publishSourceSdkPublicationToSourceSdkRepository
```

Upload `build/source-sdk/` artifacts to the matching `source-sdk-<version>` release before updating extension dependency versions. See `gradle/publish-source-sdk.gradle` for the naming/layout contract. Keep existing artifacts immutable.

## Validation

Standalone debug/release builds and lint pass for all four sources; 57 extension JVM and 38 Android checks pass. Host validation includes 47 JVM checks and 39 Android checks covering browsing, media export, reverse uploads, extension settings and system controls. Catalog checks cover metadata validation, APK identity/checksum/signature/protocol, bounded retries, cancellation cleanup and compatibility policy. A real Android 15 install/update test downloaded published releases, exercised unknown-app permission, cancelled and retried an update, retained a saved FindThatMeme profile, and installed/discovered Moeview. Light/dark and large-text layouts were reviewed. [Screenshots](screenshots/README.md#extension-catalog-and-updates).

Catalog Android tests accept a freshly built development FindThatMeme APK staged as `cache/catalog_fixture.apk` in the host’s private cache. For tests which exercise successful validation/download, that package must be absent or older on a disposable emulator; production rejects installing the same version. Missing/non-upgradable fixtures are skipped. Never uninstall an extension containing personal data for a test.
