# Google Play publication review

Reviewed 2026-10-24 against SAO `a1d00f0` and SAO-extensions `0a1b93c`. This is a source-based assessment of the current features and packaging, not a Google approval or an account-level Console audit. No features, permissions, distribution channels or releases were changed for this review.

**The full current build is not ready to submit unchanged.** Confirmed preparation gaps include the Accessibility consent flow and an in-app privacy-policy entry. The external APK installer, public-content safeguards and native/private-service integrations present additional substantial review risks. A successful Play Protect appeal would not approve these features for Play publication; [Play Protect classification](play-protect.md) and Play Store policy review are separate processes.

“Confirmed gap” below means observable in this checkout. “Review risk” means a documented policy touches the feature but eligibility/authorization has not been established. Console declarations, developer-account status and third-party permissions cannot be inferred from source code.

## Scope by package

| Package | Main points to resolve |
|---|---|
| SAO | Installer eligibility, Accessibility disclosure, privacy, public-content safeguards, Vector integration, placeholder rights |
| Immich source | Cleartext server configurations, account/media disclosures, companion review setup |
| Local Sticker source | ML Kit diagnostics, selected-media/index privacy, model/dependency licenses, review media/models |
| Moeview source | Public UGC/sexual-content controls, media rights, proxy/verification-cookie disclosures and service terms |
| FindThatMeme source | Public UGC moderation/content filtering, media rights and data-flow declarations |
| Google Photos source | Native account-session access, private API authorization, consent/renewal and review feasibility |

## 1. External extension installation and updates — high review risk

SAO declares `REQUEST_INSTALL_PACKAGES`, downloads APKs from configurable HTTPS catalogs, and submits them through `PackageInstaller`. Extensions run under separate UIDs and communicate over Binder/content URIs. SAO does not load their DEX classes into its own process.

The installer permission requires an eligible core package-installation/interoperability use case and a Play Console declaration. User confirmation is necessary but does not establish eligibility. SAO's sharing and source-dependency design may support a file-sharing/interoperability case, but eligibility against the permitted categories still needs an explicit assessment. Arbitrary custom repositories broaden the publisher/content responsibility. [Installer permission policy](https://support.google.com/googleplay/android-developer/answer/12085295).

Current safeguards include hashes, bounded downloads, actual APK package/version/certificate checks, installed-signer continuity, Android confirmation and separate source-access approval. These are useful security controls, not an exemption from distribution rules. Keep the distinction between installing another standalone package and downloading code for execution inside SAO. Play-distributed SAO must also never gain an external self-updater. [Device/network policy](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en), [dynamic code-loading guidance](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading).

Before submission: establish installer eligibility or design a Play-specific distribution path using Play listings for source installs/updates. Do not assume the newly added catalog feature is automatically publishable just because every install is explicit. Nothing was removed from the GitHub edition.

Evidence: `app/src/main/AndroidManifest.xml`; `extensions/ExtensionCatalog.kt`, `CatalogRepository.kt`, `ExtensionInstaller.kt`; [catalog architecture](extension-catalog.md).

## 2. Accessibility — confirmed consent-flow gap; declaration required

`PasteAssistService` retrieves window structure to locate editors and invokes focus/Paste after an explicit action. Its preference defaults off; package/event scope is constrained and password editors are excluded. It does not claim `isAccessibilityTool=true`. These bounds should remain.

The settings chip currently opens Android Accessibility settings directly. The description does not constitute a dedicated in-app access/use/sharing disclosure with affirmative acceptance and a decline path before the capability is enabled. The Android permission screen and the privacy policy do not replace that flow. A disclosure can be inline; an extra popup is not inherently required. Play also requires an Accessibility declaration and review evidence. [Accessibility policy](https://support.google.com/googleplay/android-developer/answer/10964491), [disclosure guidance](https://support.google.com/googleplay/android-developer/answer/11150561).

Google permits narrowly defined deterministic automation subject to the other rules. Explicit Paste is a materially stronger case than unattended chat automation; the presence of Accessibility is not itself an automatic Play ban. Describe the actual API access, rather than implying Android only grants a clipboard operation. Submit a demonstration covering consent, refusal, use and disabling. Do not relabel SAO as a disability tool to avoid disclosure.

Evidence: `floating/PasteAssistService.kt`, `floating/FloatingPreferences.kt`, `floating/FloatingSettingsFragment.kt`, `res/xml/paste_assistance.xml`.

## 3. Public sexual/NSFW content — confirmed default gap; substantial content risk

`SettingsDataStore.currentModeFlow()` defaults NSFW Mode to **true**. Moeview itself defaults to Safe only and enforces that profile scope, but offers Non-safe/All profiles; FindThatMeme can include sensitive results. SAO's source-level NSFW flag relies on extension/user classification. It is not a general content-moderation or age-screening system. No age-screening flow was identified.

Google prohibits pornography and sexually gratifying content, including illustrations. Permitted incidental sexual UGC requires primarily non-sexual content, no promotion of sexual material, default filtering, sufficient steps to disable filtering and appropriate protection of minors. An “18+” label or source toggle alone cannot make an explicitly sexual content service eligible. [Sexual-content policy](https://support.google.com/googleplay/android-developer/answer/18258653), [incidental-content requirements](https://support.google.com/googleplay/android-developer/answer/12923286).

Before submission: assess the actual public sources/content and marketing, safe defaults, age screening and the reliability of filtering across extension capabilities. This is different from a user opening their own private files in a general media utility. Private Immich/Local libraries should not automatically be treated as public adult-content platforms.

Evidence: `database/SettingsDataStore.kt`; extension `MoeviewProfiles.kt`, `MoeviewRecords.kt`, and FindThatMeme settings; host source NSFW gating in `SourceProfilesViewModel.kt`.

## 4. Public UGC moderation — confirmed missing host mechanisms

Moeview browses public image boards; FindThatMeme retrieves public memes, including third-party posts. The current SAO contract has no content-reporting, uploader-blocking or moderation operations, and the host has no corresponding controls. Hiding a source or changing its rating is not reporting objectionable content.

Google explicitly includes specialized browsers/clients of UGC platforms in its UGC policy. Public-content clients need appropriate reporting/blocking and ongoing moderation, with requirements tailored to the experience. Uploading to a private personal Immich server is a different case from publishing UGC to other users. Do not invent a social network inside SAO merely to satisfy requirements that do not apply to private libraries; resolve the public-source case deliberately. [UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937).

## 5. Vector hooks and private application APIs — high review risk

SAO bundles modern libxposed code that executes within WhatsApp, Instagram and Discord and invokes internal composer/gallery handlers. User selection, framework scoping, authenticated IPC and explicit Paste actions are meaningful controls. They do not establish that each target service authorizes this integration or that it complies with that service's terms.

Google prohibits unauthorized interaction with other apps/services, security circumvention and service/API use that violates its terms. Root availability alone does not prove a violation, and a root utility should not automatically be called malware. Review the particular hooks and upstream authorization instead of assuming a “requires Vector” disclaimer solves the issue. [Device/network policy](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en).

Also verify that the visible Paste hint clearly explains any immediate send, especially Instagram, before the user presses it. Preserve explicit action and current-chat checks; avoid unattended actions. A Play-specific build without these integrations would change supported behavior and needs a product decision, not a silent code edit.

Evidence: `floating/nativebridge/SaoXposedModule.java`, `InstagramComposerPolicy.java`, `DiscordComposerPolicy.java`, `WhatsappComposerPolicy.java`; [delivery matrix](media-capabilities.md).

## 6. Google Photos authentication and private RPC — highest-risk source

The Google Photos extension uses Vector inside Photos' process to request an AccountManager web-login token, exchanges its short-lived login URL for session cookies, and uses Photos' private `batchexecute` interface. It also supports importing a browser session and on-demand renewal. It is not using the public Photos Picker/Library API or a normal app-owned OAuth scope flow. The code is bounded; it does not export a Google account database or master refresh token.

This needs specific review of service authorization/terms and consent for account/session access. User consent to connect an account is important but does not establish Google's authorization for an unofficial private API integration. There is no evidence in the repository of that authorization or an accepted Play review. Treat the risk as unresolved, not as a proven credential-theft finding. [Device/network policy](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en), [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311).

Before publication: disclose session/account data and renewal, verify all credential retention/revocation paths, and choose an authorized integration/distribution approach. The public Photos APIs have different capabilities; switching to them is not a drop-in implementation change.

Evidence: extension `GooglePhotosVectorModule.java`, `GooglePhotosApi.kt`, `GooglePhotosProfiles.kt`, `GooglePhotosSessionRenewal.kt` and settings.

## 7. Privacy policy and accurate Data safety — confirmed in-app gap

No in-app Privacy policy entry or linked policy was found in the inspected host/extension settings. Public website/Console configuration is unknown. A privacy policy must be accessible in-app and in Play Console, covering accessed data, uses, recipients, protection, retention/deletion and a contact. A repository license or source-code availability is not that policy. [User Data requirements](https://support.google.com/googleplay/android-developer/answer/10144311).

Prepare a data-flow inventory separately for each distributed package and the combined experience:

- Host: selected media/cache, shared uploads, automatic folder routes, clipboard actions, current-app usage events, installed-app selection, catalogs and APK requests.
- Immich: server/account credentials, queries, photos/video/metadata, selected albums and uploads to the configured server.
- Local: user-selected URIs/files, OCR/labels/embeddings, model downloads and SDK diagnostics.
- Moeview/FindThatMeme: queries, remote media, optional credentials/verification cookies, and any proxy receiving requests.
- Google Photos: account identifier, native session linkage, cookies, queries, media and renewal.

Do not blindly declare every on-device value “collected” or every transfer “shared”: the form has specific definitions and exceptions, including on-device processing and some user-directed transfers. Equally, separate APKs do not justify an unsupported blanket claim of no data access. Evaluate the actual paths and each SDK. [Data safety instructions](https://support.google.com/googleplay/android-developer/answer/10787469).

Local Sticker uses ML Kit labeling/OCR. Google documents device/app information, installation identifiers and diagnostics/performance data for ML Kit. Local image analysis therefore does not justify a zero-network/zero-collection claim for the whole APK without checking the linked versions and configurations. [ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure).

SAO currently configures sources rather than creating SAO user accounts. Do not automatically promise deletion of an external Google/Immich account. Document profile removal, credentials/index/cache retention and original-file preservation; re-evaluate the account-deletion rules if SAO later offers account creation.

## 8. Cleartext personal data — confirmed Immich configuration risk

Immich accepts HTTP server URLs; its client sends the API key in `x-api-key`, and the extension enables cleartext traffic. That can expose credentials, queries, private media and upload originals on the transport. Android permitting HTTP is not evidence of compliance with secure personal-data handling. A private LAN alone is not encryption. [User Data security requirements](https://support.google.com/googleplay/android-developer/answer/10144311).

A Play submission should establish encrypted transport for these flows or an adequately justified secure deployment arrangement. Any restriction on existing local HTTP setups is a user-facing product change and has not been made. Continue to preserve original uploads; their EXIF/other embedded metadata must be included in the privacy assessment.

## 9. Artwork, media rights and branding — specific unresolved asset

`eager.webp` is bundled and displayed as the carousel loading placeholder. `adapters/BindingAdapter.kt` credits a LINE sticker pack, but no redistribution grant for that asset was identified. Credit and a Telegram pack URL do not establish permission. Confirm rights or use artwork with a suitable license before submission.

Also check store screenshots and remotely sourced media, download/share marketing, modified logos and dependency/model licenses. Immich artwork has attribution/license material in the extension; that does not settle all trademark/branding questions. Open-source app code does not confer rights to every retrieved image. [Intellectual-property policy](https://support.google.com/googleplay/android-developer/answer/9888072).

## 10. Foreground services, overlay and background work — declaration/review required

The host declares `specialUse` with a manifest explanation for the persistent floating picker, a notification/Stop path and boot recovery. Play requires a foreground-service declaration and evidence for the specific use; `specialUse` is reviewed, not a generic keep-alive exemption. Demonstrate the visible user benefit, user control and stop/recovery behavior. Do not add battery-management bypasses. [FGS declaration requirements](https://support.google.com/googleplay/android-developer/answer/13392821), [specialUse documentation](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

Folder scans/indexing use WorkManager. Explicit transfers use user-initiated jobs; automatic work must continue to use the automatic-work path. These choices support a better review case than repurposing the floating service for every background task. Actual Console declarations are unverified.

## 11. Usage access, app inventory and photos — disclosure scope

`ForegroundAppDetector` reads usage events to resolve the current chat; its initial query covers up to the previous day, with in-memory state afterward. `AppCatalog` enumerates launcher apps for Telegram-fork selection. Explain these purposes and local handling accurately rather than claiming no app-usage access.

No `QUERY_ALL_PACKAGES`, SMS, notification-listener, broad photo-library or all-files permission is declared in the inspected production manifests. Local uses Photo picker/SAF grants. Keep that narrow design; adding broad media/package permissions would introduce further eligibility reviews. [Sensitive APIs policy](https://support.google.com/googleplay/android-developer/answer/16558241), [photo/video guidance](https://support.google.com/googleplay/android-developer/answer/14115180).

## 12. Review access and companion-only APKs — submission preparation

The host requires at least one source; each extension settings Activity is opened by SAO rather than through its own launcher. Immich needs a reachable server/key, Local may need media/models, and Google Photos native connection needs Vector. Provide working, non-personal review fixtures and full setup/access instructions for the functionality being submitted. Do not hide a failing feature from reviewers or describe root-dependent features as universally available. [Prepare for review](https://support.google.com/googleplay/android-developer/answer/9859455).

Explain each extension's companion role and how reviewers reach useful functionality. Do not assume that absence of a launcher automatically bans a plugin, or that a mostly empty host with external prerequisites will pass without review access. Include accurate audience/content-rating declarations, especially for public UGC.

## 13. Technical and account gates

All six current Gradle application modules target API 36, satisfying the currently published phone/tablet requirement effective August 31, 2026. Recheck before an actual submission. [Target API policy](https://support.google.com/googleplay/android-developer/answer/11926878).

New Play apps use AAB/Play App Signing. Existing manual APK release builds are not the complete upload workflow. Plan signing identities across the host and first-party source apps: the current official catalog pins the host publisher and signature permissions rely on signing identity. Distinct Play-generated app-signing keys would affect auto-trust/catalog behavior; approved independent publishers can use the existing authorization route. The upload key and the key signing installed APKs are separate concepts. [App Bundle/signing FAQ](https://developer.android.com/guide/app-bundle/faq), [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756).

16 KB support is required for relevant new/updated submissions. Static checks on the current optimized host plus five source artifacts found no arm64 ELF alignment below 16 KB; `zipalign -c -P 16 4` passed for all six. The host has one arm64 library; Local has five, including ONNX/ML Kit, all with aligned LOAD segments. This is packaging evidence, not a 16 KB-device runtime test or AAB delivery test. [16 KB requirement](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html).

Production releases need monotonically increasing version codes and release signing. If the developer account falls under the new-personal-account rules, production access also requires the applicable closed test, currently 12 testers continuously opted in for 14 days. Account status was not inspected. [Testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465).

## What is already a better foundation

The source APK boundary, certificate approval and revocation, scoped URI grants, encrypted credential files, explicit installation confirmation, Photo picker/SAF access, bounded/cancellable work and lack of idle update polling are useful safeguards. Downloaded CLIP ONNX weights/tokenizer data are consumed by a bundled inference runtime; that is a different design from fetching arbitrary native/Dex plugins. Embedding search/OCR/classification are not generative image features merely because they use ML.

None of those properties guarantees Store acceptance. The immediate work before a submission is the consent/privacy/content preparation and an explicit decision about the installer, hooks and private Photos route. A separate Play edition may be appropriate; retaining the full GitHub edition preserves the currently requested features. That distribution decision was not made by this audit.
