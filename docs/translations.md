# Translating SAO and its sources

Contributions can be ordinary GitHub pull requests containing Android XML resources. English is the complete default; partial translations fall back to English. No new language is included by this change. Crowdin configuration is provided as optional maintainer tooling; a hosted translation project has not been created.

## Contribute a translation

1. Find the English `*strings.xml` files in `app/src/main/res/values/`. Add translated entries with the same resource names under `app/src/main/res/values-<locale>/`, preserving each filename. For example, `values-fr` or `values-pt-rBR`; scripts use BCP 47 folders such as `values-b+zh+Hant`.
2. Translate shared extension form text under `source-settings/src/main/res/values-<locale>/`. Source-specific forms, summaries, preset labels and suggestions belong to the [SAO-extensions repository](https://github.com/nijuyonkadesu/SAO-extensions/blob/main/docs/translations.md), in the corresponding APK module's resource folders. Updating shared translations requires rebuilding the versioned source-settings SDK and its consumers as part of the normal release process.
3. Preserve resource names, `%1$s`/`%2$d` placeholders and formatting types. Reorder numbered placeholders when grammar requires it. Keep XML markup, escapes (`\'`, `\n`) and `formatted="false"` where present. Never add `translatable="false"` resources to a translation.
4. Use `<plurals>` with all quantities required by the target language, including `other`. Independent counts have separate phrases so, for example, the number of photos cannot determine the grammatical form for videos.
5. Translate interface labels, not API identifiers, backend queries, board tags, filenames, user-created source/album names, OCR text or credentials. Product/model names remain as written. Preset display labels are resources; their query values intentionally remain unchanged.
6. Run `./gradlew :app:assembleDebug :app:lintDebug`. String-format, extra-translation, plural and hardcoded-text checks remain enabled; only missing translations are allowed. Confirm the UI on a small screen and with large fonts. English defaults must always remain complete.

The build discovers language-only resource folders automatically. Adding `values-fr/*strings.xml`, for example, adds French to SAO's language picker and Android 13+ per-app settings without a Kotlin enum or a second locale list. Do not add a resource folder containing only styles and call it a translation. Dependencies' unrelated translations are filtered out of the supported-language list. English remains the fallback language.

## Runtime behavior

SAO uses [`AppCompatDelegate.setApplicationLocales`](https://developer.android.com/guide/topics/resources/app-languages), with **System default** represented by an empty locale list. Android 13+ stores the choice in the framework; Android 11–12 use AppCompat's `autoStoreLocales` backport. Activity recreation lets layouts, menus, accessibility text and configuration-dependent resources update together. We do not write our own locale preference or call `Resources.updateConfiguration` / `Locale.setDefault`.

The chosen effective BCP 47 locale list accompanies settings Intents and source RPCs through the shared [source API](../source-api/src/main/java/one/njk/sao/source/SourceLocales.kt). Extensions resolve RPC presentation strings in an immutable request-scoped `ConfigurationContext`, so concurrent requests can use different languages. After authenticating the host, `SourceSettingsActivity` uses the same official `AppCompatDelegate.setApplicationLocales` API for the extension's own UI. Internal AppCompat screens inherit it automatically. Android 13+ owns per-app persistence; on Android 11–12 extensions retain it in AppCompat's process state and reapply the host's requested language after a cold start. There is no mirrored extension preference or manual resource/global-locale mutation. Settings follow the most recently authorized host that opens them. Missing source translations fall back to English. Language does not alter source identity, credentials, media cache keys, search input, pagination or saved user names.

Background host UI, including the floating icon's notification and accessibility labels, uses SAO's effective language context. A language change refreshes labels and active source capability presentation once; it adds no polling or background translation network calls.

## Verify without adding a language

Debug builds enable Android's [pseudolocales](https://developer.android.com/guide/topics/resources/pseudolocales): `en-XA` expands/accentuates text; `ar-XB` exercises RTL. They are selectable in debug builds, are not real translations, and are absent from release language choices/resources. All five source APKs support RTL and debug pseudolocales too. This tests hardcoded text, clipping, directionality and propagation while production remains English-only.

The instrumentation checks cover scoped resource isolation, malformed locale input, XML fallback/formatting, language switching with Activity recreation, extension settings/child windows, and localized capability labels without changing backend queries. Process-restart persistence is also checked on the disposable emulator.

## Optional Crowdin setup (maintainers)

[Crowdin's Android XML support](https://store.crowdin.com/android-xml) works with the existing resources; no Crowdin dependency is bundled in any APK. Use a file-based project and the checked-in [`crowdin.yml`](../crowdin.yml). Configure target languages, placeholder QA and translators in that project. Keep `CROWDIN_PROJECT_ID` and `CROWDIN_PERSONAL_TOKEN` in local environment variables or repository secrets, never in Git. The extensions repo has a separate configuration because its APKs are independently versioned.

With the Crowdin CLI installed, `crowdin upload sources` and `crowdin download` use those mappings. Review downloaded XML in a pull request and run the same Android checks. `skip_untranslated_strings` preserves Android's fallback behavior instead of exporting copies of English into every language. Direct XML PRs remain fully supported even if Crowdin is never configured. See [Crowdin configuration](https://support.crowdin.com/developer/configuration-file/) for the external setup.

## Validation for this change

Android 12 (API 32) and Android 15 (API 35) were exercised with the four `LanguageSupportDeviceTest` cases. Tests use synthetic profiles/local HTTP responses and debug pseudolocales, never private libraries. Framework resource isolation, language selection/recreation, all five authenticated extension settings screens, concurrent RPC labels, stable user names/revisions and unchanged category queries are checked. A separate force-stop/relaunch check verified that both versions restore the selected language. RTL settings were visually inspected. Release locale XML/choices contain only `en`; all six optimized APKs build, and the localization-related lint checks report no issues.
