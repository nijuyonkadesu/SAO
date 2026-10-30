# Release extension update probe

Java-only platform instrumentation, installed as its own application. It drives
the actual signed/minified SAO UI and Android installer without loading SAO classes,
patching permissions or depending on the host's obfuscated Kotlin/AndroidX runtime.
No test dependency is added to the distributed APK. Run only on an owned emulator,
in English, with no personal profiles. It installs a real public extension update.

Install the host release and a previous release of the chosen extension first.
The probe opens SAO, enters Media sources, finds Update, handles the Android
install-source permission if necessary, confirms the system Update dialog, and
checks the version plus unchanged first-install time. It records boolean/version
results only; no media, credentials or profile contents are inspected.

```bash
./gradlew -p tools/extension-update-probe :app:assembleDebug
adb -s "$SAO_EMULATOR" install -r tools/extension-update-probe/app/build/outputs/apk/debug/app-debug.apk
adb -s "$SAO_EMULATOR" shell am instrument -w \
  -e allowPublicUpdate true -e extensionPackage one.njk.sao.extension.moeview \
  -e extensionName Moeview -e expectedVersion 12 \
  one.njk.sao.extensionupdateprobe/.ExtensionUpdateProbe
adb -s "$SAO_EMULATOR" shell run-as one.njk.sao.extensionupdateprobe cat files/update-report.json
```

Use the catalog's numeric `versionCode` for `expectedVersion` and its displayed
extension name for `extensionName`. The installed extension must be older and have
the same signer. A debug-signed extension cannot update to a release-signed APK;
reset only disposable fixtures when preparing that transition. Successful reports
require an actual confirmation and preserve the original installation timestamp.
Parse the report: `am instrument` can return shell exit code zero on probe failure.

For a fresh installation, stage no APK for the target and pass `-e operation install`.
The same journey presses the catalog's Install action and Android's Install confirmation.
Its report has `freshInstall=true`; in-place updates instead require `installationRetained=true`.

The host's `CatalogTransportTest` separately reproduces an unreachable first DNS
address and verifies recovery without duplicate server requests, plus no automatic
restart after a response body disconnects. `CatalogRepositoryDeviceTest` has an
opt-in `liveCatalog=true` check of the real catalog/download/verification path.
