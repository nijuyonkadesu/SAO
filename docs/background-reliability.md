# Floating icon recovery: OxygenOS / ColorOS

An AOSP process-kill test is insufficient to establish OnePlus reliability: an OEM can discard the service's started state, or put the entire package into the stopped state. `START_STICKY` covers the former only while Android retains the started record. Moving an activity to the background does not change a later Recents removal into a background operation.

## Open-source comparison

- [Pano Scrobbler, reviewed commit 9901540](https://github.com/kawaiiDango/pano-scrobbler/blob/990154031e761b8e5460a0585fc834b43c9e2d71/composeApp/src/androidMain/kotlin/com/arn/scrobble/media/NLService.kt) runs its real scrobbling functionality in a system-bound `NotificationListenerService`, initializes on `onListenerConnected`, and also returns `START_STICKY`. Its [manifest](https://github.com/kawaiiDango/pano-scrobbler/blob/990154031e761b8e5460a0585fc834b43c9e2d71/composeApp/src/androidMain/AndroidManifest.xml) specifies `stopWithTask=false`. Its [FAQ](https://github.com/kawaiiDango/pano-scrobbler/blob/990154031e761b8e5460a0585fc834b43c9e2d71/faq.md) still documents manufacturer restrictions and Recents locking. Notification access is essential to scrobbling; requesting it solely to keep SAO alive would be inappropriate.
- [KDE Connect, reviewed commit a4edf08](https://github.com/KDE/kdeconnect-android/blob/a4edf08c8e7439f3a3ae430f4ef7593f69415143/src/main/java/org/kde/kdeconnect/BackgroundService.kt) uses a sticky `connectedDevice` foreground service, matching its actual device-connection functionality. Its [receiver](https://github.com/KDE/kdeconnect-android/blob/a4edf08c8e7439f3a3ae430f4ef7593f69415143/src/main/java/org/kde/kdeconnect/KdeConnectBroadcastReceiver.kt) starts or refreshes it on boot, app replacement and connection/screen events. It also has genuine notification/accessibility features. SAO must not claim a connected-device or media-playback foreground type for an idle floating picker.

The relevant lesson is genuine system lifecycle ownership and restoration from system callbacks, rather than a wake lock or an activity trick. This comparison does not establish that the apps receive identical OEM treatment.

## SAO's implementation

`FloatingRuntime` serializes Show, Stop and Restore and persists just the user's run intent in a device-local DataStore excluded from backup. It does not persist queries, selection, scroll positions, editor handles or chat targets. Every explicit dismissal commits false before stopping. Process/service teardown alone does not change user intent.

Normal Android sticky restarts remain the first path. If Android reconnects SAO's existing, user-enabled Paste assistance service, that callback restores an intended session once, even when the old floating service record is gone. A non-exported receiver also restores on unlocked boot completion and SAO app replacement. Recovery never launches an activity, changes permissions or sends media. No restoration runs from editor events, `onDestroy`, a timer, an alarm or a periodic job. Duplicate events do not disturb an active icon.

There are no app-held wake locks, screen-on flags, hidden media sessions or keepalive traffic. The phone can sleep and Doze normally. The optional own-process exit diagnostic is read once when settings resumes; it does not inspect other apps or continually sample processes.

## Limits and verification

[Android's FGS exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) govern new starts. In particular, on Android 15+, overlay permission alone is insufficient without a visible overlay. A denied attempt waits for another legitimate lifecycle event or explicit app opening; it does not start a retry storm.

[Android 15's stopped state](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state) prevents automatic resurrection after Force stop. [OnePlus guidance](https://dontkillmyapp.com/oneplus) documents Recents clearing, vendor settings and the absence of a universal developer-side override. SAO cannot detect or change the vendor's Recents lock through a supported public API. Its UI therefore does not claim that battery exemption proves vendor protection is configured.

The Android 15 emulator verifies task removal, process recovery, discarded-service recovery through real accessibility reconnect, app replacement, manual Stop and sleep. These checks do not certify OxygenOS/ColorOS: a physical OnePlus run is still required. If it fails, **Background reliability → Last app exit** and the device log can distinguish a crash, low-memory kill and user/manufacturer stop without accessing any media library.
