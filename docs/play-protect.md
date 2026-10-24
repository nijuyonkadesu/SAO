# Play Protect installation block

Investigated 2026-10-24. The reported warning is **“App blocked to protect your device” / sensitive permissions**, affecting the main SAO APK. The affected phone’s classifier verdict is unavailable; the diagnosis below matches the supplied warning to the published binary and Google’s documentation, rather than claiming a reproduced verdict.

## Finding

Google documents this warning for Internet-sideloaded apps declaring SMS, notification-listener or Accessibility access in participating markets. SAO declares an Accessibility service, so it meets that documented condition even when Paste assistance is switched off. Removing an unused permission, changing a label, minifying, or disabling the service at runtime would leave this declaration present. [Google’s warning guidance](https://developers.google.com/android/play-protect/warning-dev-guidance).

The production `sao-1.1.0.apk` was downloaded from the existing [v1.1.0 release](https://github.com/nijuyonkadesu/SAO/releases/tag/v1.1.0) for inspection; nothing was uploaded.

| Binary evidence | Result |
|---|---|
| Package / version | `one.njk.sao`, 1.1.0, versionCode 2 |
| APK SHA-256 | `c3415df64767c695e7e31c9b0b33a5853bf2454a5085d3d493be9df44f31ef6e` — matches published SHA256SUMS |
| Signature verification | `apksigner verify --verbose --print-certs` passes; v3, RSA 2048-bit, one signer |
| Release certificate SHA-256 | `fd793dd1514b2a5c1d561f27df03ed76d06a94f872627eb7ec4e522871cf4441` — the existing nijuyonkadesu certificate, not the development certificate |
| Release flags | No `debuggable`/`testOnly` flag; targetSdk 36 |
| Documented sensitive declaration | `PasteAssistService`, protected by `android.permission.BIND_ACCESSIBILITY_SERVICE`; `canRetrieveWindowContent=true` |
| Other listed triggers | No READ_SMS, RECEIVE_SMS or notification-listener service |

A valid self-signed certificate is normal on Android. The signature check does not reveal Google’s private reputation signals, but there is no evidence of an invalid signature or a need to replace the key. Rotating it would also disrupt installed-app updates and signature-based integrations. [AOSP signing documentation](https://source.android.com/docs/security/features/apksigning).

## Existing permission use

[PasteAssistService](../app/src/main/java/one/njk/sao/floating/PasteAssistService.kt) locates a chat editor and requests focus/Paste after an explicit user action. Its configuration uses window-state/focus events and view IDs, with no gesture, screenshot or key-filter capability. Package scope is restricted to supported chat apps and user-configured Telegram packages. Runtime events are disabled when floating mode or the opt-in Paste assistance preference is off. Password editors are rejected. The service does not inspect conversation text, persist chat content, or invoke Send. Vector delivery is a separate path and may send directly as disclosed in its UI.

The access remains necessary for SAO’s present non-root Paste mechanism. System clipboard access and ACTION_SEND cannot replace that editor action while preserving the same flow; Vector is optional. SAO does not designate itself as a disability accessibility tool (`isAccessibilityTool` is absent/default false). Setting it true would misrepresent this utility. Existing settings describe Paste assistance and direct users to Android’s explicit service-enable screen.

Overlay, usage access, extension installation and the foreground service have separate feature purposes. They are not among the four triggers in Google’s explanation of this particular warning. This focused investigation is not an independent malware certification of every dependency or Google’s classifier.

## Resolution

Preserve the signing identity and the current single-APK Paste functionality. Request classification review of the **actual blocked APK** through Google’s [Play Protect appeal form](https://support.google.com/googleplay/android-developer/contact/protectappeals). Google requests a contact email, package name, the APK hash of a sample uploaded to VirusTotal, and supporting information. No sample was uploaded, no appeal submitted, and no release changed during this investigation. A newer signed APK has a different hash and requires its own evidence.

A draft for the published 1.1.0 binary follows. Include the user’s exact warning screenshot and installation route if available. This is a review request, not a guarantee that Google will allow installation. Removing Accessibility from a separate build would remove this documented condition but would also remove non-root editor Paste; a companion app would move the same sensitive declaration into another APK. Neither product change was made.

### Appeal draft

Package: `one.njk.sao`  
APK SHA-256: `c3415df64767c695e7e31c9b0b33a5853bf2454a5085d3d493be9df44f31ef6e`

Please review the sensitive-permission installation block for SAO 1.1.0, an open-source floating media picker. Source: https://github.com/nijuyonkadesu/SAO. Published binary: https://github.com/nijuyonkadesu/SAO/releases/download/v1.1.0/sao-1.1.0.apk.

Its optional Paste assistance uses AccessibilityService to locate a chat editor and invoke focus/Paste only after the user presses Paste. The preference defaults off and Android requires the user to enable the service. Discovery is limited to supported chat packages and is inactive outside floating mode or when the preference is off. Password editors are excluded. This service does not inspect conversation text, collect credentials, persist or transmit chat content, or press Send. It does not claim to be a disability accessibility tool. SAO requests no SMS or notification-listener access.

The release APK passes Android signature verification using our existing release certificate. We request review of the reported “App blocked to protect your device” sensitive-permissions classification for this APK.
