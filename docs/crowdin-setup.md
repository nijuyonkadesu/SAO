# Activate SAO's Crowdin website

Crowdin is the selected hosted translation service. The repository-side setup is ready; the owner account/project has not been connected. One **public, file-based project**, with **English** as its source language, covers both repositories and all seven translation areas. Native GitHub integration handles synchronization and translation PRs; no GitHub Actions, app dependencies or runtime translation downloads are introduced.

## Why Crowdin

Crowdin fits SAO’s volunteer workflow and existing Android file layout.

| Service | Relevant strengths | Fit for SAO |
| --- | --- | --- |
| [Crowdin](https://support.crowdin.com/online-editor/) | Browser editor, screenshots/comments, glossary, Android plurals and placeholder checks; [multiple GitHub repositories in one project](https://support.crowdin.com/github-integration/#working-with-multiple-repositories-within-one-project) | Selected: handles the existing resource files through seven file rules, with one contributor portal for the app and extensions. |
| [Weblate](https://weblate.org/) | Open-source hosted or self-hosted editor, Android XML, Git synchronization; [Mihon uses it](https://mihon.app/docs/contribute/) | Strong alternative, especially for self-hosting. Its [component/discovery setup](https://docs.weblate.org/en/latest/admin/projects.html#automatic-creation-of-components) introduces more maintenance for our many separate resource files. |
| [Transifex](https://www.transifex.com/open-source/) | Hosted community editor with an open-source program | Viable, but offers no specific simplification over Crowdin for the existing two-repository Android resource layout. |

The contributor workflow is **website → select language → translate → Save**. XML files and pull requests stay implementation details for maintainers.

## Owner setup

1. [Sign in to Crowdin](https://crowdin.com/login), using your GitHub account if convenient. [Create one project](https://support.crowdin.com/creating-project/) named **SAO**, choose **Public**, **File-based**, and source language **English**. Choose an available project address; do not assume a particular slug is ours. Do not pre-fill a list of target languages as part of this setup; add languages when contributors request them.
2. Authorize Crowdin’s [native GitHub integration](https://support.crowdin.com/github-integration/). Website sign-in and repository authorization are separate account actions. Configure synchronization for `nijuyonkadesu/SAO` and `nijuyonkadesu/SAO-extensions`; restrict repository access to those two when the authorization screen offers selected-repository access.
3. In the Crowdin project's **Integrations → GitHub**, use **Source and translation files** mode and connect `nijuyonkadesu/SAO`, using its checked-in `/crowdin.yml`. While SAO PR #24 is open, the prepared configuration is on `feat/dynamic-stickers`; switch tracking to `master` after it is merged. Track one host source branch at a time to avoid duplicating its strings.
4. Choose **Add Repository** in that same Crowdin integration and connect `nijuyonkadesu/SAO-extensions`, branch `main`, configuration `/crowdin.yml`. Do not create a second Crowdin project. The module paths are distinct across the two repos, and both configs preserve them to prevent collisions between their many `strings.xml` files.
5. Keep translations on Crowdin's integration branch and enable translation pull requests. Keep merge/release decisions with the maintainer; connecting the website does not publish APKs. Use the configured concise PR title. Crowdin normally syncs hourly; **Sync Now** can perform the initial import immediately.
6. Configure community access and QA using the exact menus below. These settings can be saved before adding target languages. Source labels are added on the initial GitHub import; translation memory and glossary can use the project's defaults.
7. Sync both repositories and check the file preview: only `*strings.xml` under the seven modules should appear. No Kotlin/Java source, credentials, local profiles, media, or build output is matched. Confirm that `translatable="false"` entries, including product names and shared string aliases, are excluded from translation.
8. Verify the public project URL while signed out. Then replace the setup-status note in both translation guides and the README translation link with that actual URL. Only then add a live Crowdin badge or in-app contribution link. This prevents contributors being sent to a nonexistent or unrelated project.

[Crowdin's open-source program](https://crowdin.com/product/for-open-source) requires an owner application and approval; it is not enabled by adding YAML to Git. SAO's public AGPL-3.0 code is compatible with the license criterion, but Crowdin must assess the remaining requirements. The free program includes contribution of project translations to its Global Translation Memory. The owner should review its current terms when applying. No paid plan or paid translation service is selected by these repository changes.

## Exact settings menus, including a project with no target languages

Open the project's owner/manager dashboard. Target languages may remain empty while setting up access, source imports and QA. Translation editors and language-specific plural forms become useful after a target language is added.

- **Project Settings → Privacy & Collaboration:** set **Project Visibility → Public project**. Leave **Moderated project joining** off for open volunteer participation; turn it on only if join requests should require approval. Leave **Task-based access control** off for this community workflow. [Crowdin access settings](https://support.crowdin.com/project-settings/privacy-collaboration/).
- **Avatar → Account Settings → Notifications:** enable **Language Requests** for In-app and/or Email. This is an account notification setting, not a project-level language-request toggle. Project members use **Dashboard → Request New Language**; an owner adds an accepted language through **Project Settings → Languages → Target Languages → Save**. [Crowdin language requests](https://support.crowdin.com/for-managers/#managing-language-requests).
- **Project Settings → QA Checks:** keep **Enable QA Checks** selected. Enable **Placeholders mismatch**, **Tags mismatch**, and **Android syntax**, choose **Error** for these syntax checks, then click **Save** at the bottom. Android syntax specifically protects `<xliff:g>` tags; printf arguments such as `%1$s` and `%2$d` are covered by placeholder recognition/mismatch checks. **Project Settings → Placeholders** has all types enabled by default; search for a placeholder example to inspect its recognition. [QA settings](https://support.crowdin.com/project-settings/qa-checks/), [placeholder settings](https://support.crowdin.com/project-settings/placeholders/).
- **Android plural forms:** there is no separate Android “plural QA” checkbox in the documented QA list. Keep the source parser set to **Android**, as the configs already do. Crowdin handles `<plurals>` through that format and the selected target language's forms. The **ICU syntax** QA check concerns ICU messages; it is not the switch for native Android XML plurals. Android lint remains part of reviewing exported resources.
- **Project Settings → Translation Memories / Glossaries:** use the default resources created with the project; no second “share across repositories” switch is needed because both integrations belong to this one project. The seven area labels appear after the first source sync, without requiring a target language. Leave automatic AI/MT rules unconfigured for this human-translation workflow.

## What the configurations do

- Explicitly use Crowdin's native **Android** parser for strings, plurals and read-only entries.
- Preserve module paths in one project, and label strings by the seven user-recognizable areas.
- Export to `values-%android_code%/` without modifying English sources, resource keys or filenames.
- Omit untranslated strings so Android's existing English fallback remains effective.
- Preserve earlier translations when English changes and mark them unapproved for review.
- Use short translation PR/commit messages; retain `[ci skip]` on intermediate per-file bot commits to avoid redundant builds. This introduces no CI workflow.

The native integration uses its authorized account connection; translators need no token or repository write access. The optional CLI can use `CROWDIN_PROJECT_ID` and `CROWDIN_PERSONAL_TOKEN` from the environment. Both repositories refer to the **same project ID**. Actual credentials must never be committed. Crowdin's library/CDN is not installed in any APK.

## Verify the first translation PR

Confirm that a translated file is routed to the correct repository/module and contains only translated resource entries. Existing Android lint checks formats, plural quantities and unexpected translation keys. Review text, screenshots and glossary terminology, then merge. The app's existing language discovery picks up new `values-<locale>` folders; shared settings translations require the normal SDK rebuild and consumer update before distribution. Screenshots can be attached to strings in the Crowdin editor without an APK SDK.

## Validation performed before account connection

Both configurations pass `crowdin config lint` using the official Crowdin CLI 5.5.0. A separate local glob/XML check covers all 30 current Android string-resource files exactly once, with seven area labels, unique paths across the two repositories, and export destinations for ordinary, regional and script-specific locales. `crowdin config sources` additionally requires an authorized project, so remote discovery is deferred until account connection. This validates configuration, not remote account access, GitHub authorization, synchronization or a live public portal. Those are verified after owner setup. No translations, APK rebuilds or releases are needed for these configuration/documentation changes.
