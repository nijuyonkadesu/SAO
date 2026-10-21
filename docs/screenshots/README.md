# Floating picker previews

Captured on the Android 15 emulator using synthetic media and a localhost Immich fixture. Earlier sample media now lives only in the instrumentation APK; SAO ships without it. The emulator has no native hook framework; native delivery was checked separately with recording APK probes and on the user's phone.

| Carousel in light mode | Carousel in dark mode | Context search selector |
| --- | --- | --- |
| <img src="floating-carousel.png" width="260" alt="Light picker with continuous selection border and rounded Material search, close and check icons" /> | <img src="floating-carousel-dark.png" width="260" alt="Dark picker with continuous selection border and matching icon foregrounds" /> | <img src="floating-search.png" width="260" alt="Expanded Context selector with looping drag choices during text and emoji search" /> |

Enabled/selected settings, granted permissions and configured Telegram-app controls share a filled Material secondary container. Media sources’ [Manage action stays outlined](floating-sources-manage.png) even when sources are configured. The floating icon follows the same dynamic theme as activities. Picker icons use Material Symbols Rounded; the selection outline follows the visible carousel mask, including narrow side cards. Visually checked in light/dark themes and at 200% text.

| Configured settings | Floating icon in light mode | Floating icon in dark mode |
| --- | --- | --- |
| <img src="floating-settings-configured.png" width="260" alt="Configured media source and Telegram-app controls filled like enabled preferences" /> | <img src="floating-icon-light.png" width="260" alt="Floating smiley using the wallpaper's light primary container and foreground" /> | <img src="floating-icon-dark.png" width="260" alt="Floating smiley using the corresponding dark primary container and foreground" /> |

Opening SAO shows the floating icon automatically once overlay access is granted. The inline “Show icon on app launch” setting is enabled by default; Stop or dismissal ends the current session.

Type `:wink` to choose an emoji from a compact autocomplete list. Selecting it replaces only the shortcut, keeps the keyboard open and preserves surrounding text. Lookup uses bundled English keywords and aliases. [Telegram source mapping and implementation](../emoji-autocomplete.md).

| Emoji shortcuts in light mode | Emoji shortcuts in dark mode |
| --- | --- |
| <img src="floating-emoji-autocomplete.png" width="260" alt="Typing wink after a colon offers winking emoji while preserving the rest of the query" /> | <img src="floating-emoji-autocomplete-dark.png" width="260" alt="The same emoji shortcut list uses Material dark surface and foreground colors" /> |

Background reliability shows battery status, system settings shortcuts and the last app-exit reason. System reconnect, restart and app-update recovery respect manual dismissal; no wake locks or periodic watchdogs are used.

<img src="floating-background-reliability.png" width="280" alt="Inline background reliability controls with battery status and a shortcut to SAO system app settings" />

The optional sequence view follows Immich's Photos ordering and labels each card with its upload time. The shared hold delay and sequence toggle are inline in settings.

| Sequence and upload time | Sequence and shared hold settings |
| --- | --- |
| <img src="floating-sequence.png" width="280" alt="Chronological neighbors with upload date and time and a Back to results action" /> | <img src="floating-sequence-settings.png" width="280" alt="Browse sequence toggle and configurable 160 millisecond hold delay" /> |

The title and supporting message have a subtle 2 dp gap taken from existing padding, keeping their block aligned with the Sticker/Media chip. Empty messages reserve the same space; overflowing labels pause for 1.5 seconds before scrolling. The action row has the same 12 dp panel inset as the sides. Tonal actions, supporting labels and motion badges follow Material light/dark color roles. Opening search selects the whole existing query, and Lucky's icon follows the theme.

| Dock text above actions | Search selection in dark mode | Accessibility instructions |
| --- | --- | --- |
| <img src="floating-dock-marquee.png" width="260" alt="Context amazing with a scrolling title and GIF tip above the transparent action dock" /> | <img src="floating-search-dark.png" width="260" alt="Selected text and emoji query with a contrasting Lucky icon in dark mode" /> | <img src="floating-accessibility-settings.png" width="260" alt="Accessibility access description directs users to find Paste assistance in Android settings" /> |

<img src="floating-instagram-video.png" width="280" alt="Instagram target with an MP4 preview and Paste/DM actions targeting the original conversation" />

The MP4 preview uses a bundled test clip and a recording native client. It demonstrates
capability gating and UI. With a valid Vector lease, Paste/DM sends supported media
to the original Instagram conversation, and Discord Paste/Attach stages it in the
original draft. Both video paths still require authenticated phone validation. See the [delivery capability matrix](../media-capabilities.md).

The installed Rays source was also checked on Android 15 using a synthetic video
imported through Rays' normal Add screen. [Setup and source behavior](../rays-source.md).

| Rays connection | Rays video in the floating picker |
| --- | --- |
| <img src="rays-source-settings.png" width="260" alt="Named Rays source with successful API connection and local-library setup instructions" /> | <img src="rays-video-picker.png" width="260" alt="Synthetic MP4 playing in the existing floating carousel from the installed Rays library" /> |

## Source extension profiles

Immich now runs in a separately installed APK. Named profiles remain in SAO's existing source list; editing opens the extension's own Material settings. These previews use a localhost fixture, with no personal server or media. [Setup and contract](../source-extensions.md).

| Profiles in SAO | Extension settings | Extension settings in dark mode |
| --- | --- | --- |
| <img src="source-extension-profiles.png" width="260" alt="Fixture and Work library profiles served by the installed Immich extension" /> | <img src="immich-extension-settings.png" width="260" alt="Extension-owned connection form with a verified synthetic server" /> | <img src="immich-extension-settings-dark.png" width="260" alt="The same verified connection form using Material dark colors" /> |

The separate [Local Sticker extension](../local-sticker-source.md) shows per-album media counts and labels/OCR/Context progress, with Pause/Resume and Restart controls. Optional Context uses the downloaded Immich-default CLIP model. These images use synthetic PNG/GIF/video fixtures; [paused albums in dark mode](local-source-settings-dark.png) were checked too.

| Album progress and controls | Local Context settings | Optional corrections |
| --- | --- | --- |
| <img src="local-source-settings.png" width="260" alt="Per-album image/animation/video counts, labels/OCR and Context progress, and Pause/Restart controls" /> | <img src="local-source-context.png" width="260" alt="Immich-default CLIP Context enabled in the separate extension's Material dark settings" /> | <img src="local-source-editor.png" width="260" alt="Editable title, description, tags and detected text for a synthetic WebM frame" /> |

[Automatically generated metadata browser](local-source-metadata.png).

## Source setup and emoji shortcuts

The optional emoji row is configured in SAO and searches emoji as text. The empty picker directs users to install/configure a source. Local Sticker offers folder, photo and file pickers; selected-media collections keep the existing indexing, progress and correction controls. These captures use synthetic fixtures and the optimized extension APK.

<img src="floating-no-sources.png" width="220" alt="Empty floating picker with Add source guidance" /> <img src="floating-custom-emoji.png" width="220" alt="Custom emoji shortcuts above the existing media carousel" /> <img src="floating-emoji-shortcuts-settings.png" width="220" alt="Emoji shortcut toggle and editable list in SAO settings" />

<img src="local-source-import-methods.png" width="260" alt="Folder, Photo and Files picker options in Local Sticker" /> <img src="local-source-selected-collection.png" width="260" alt="Named selected-media collection with format counts and processing controls" />

Local Sticker 1.4.1 exposes whole-folder import alongside selected-media import, without changing the chosen picker. Android's Photo picker grants selected files, rather than whole cloud albums.

<img src="local-source-whole-folder.png" width="260" alt="Photo picker selected with separate Add photos and videos and Add whole folder actions and album-access guidance" />

## Home source collections

The large-card carousel reads installed extension profiles and their category presets. A centered Hero layout shows the prominent card and two narrow previews; Material shifts the previews to the available side at the list boundaries. Category chips and bottom actions remain; home shares GIF/video playback and focal-card selection with the floating picker. Long-press the title to copy the source filename. These captures use a synthetic localhost fixture, including a test-pattern MP4.

| Home in light mode | Provider category in dark mode | Muted video preview |
| --- | --- | --- |
| <img src="home-source-light.png" width="260" alt="Original home carousel with a source Collection heading, large image card, extension category chips and existing bottom actions" /> | <img src="home-source-category-dark.png" width="260" alt="The same home carousel in dark mode with the extension's Happy category selected" /> | <img src="home-source-video-dark.png" width="260" alt="Synthetic MP4 playing inside the original home card with a Video badge" /> |

[Home, experimental All source and original-file exports](../home-source-browser.md).

Hold **Collection**, drag vertically through looping source choices and release to switch. Tapping retains the full source list. The same selector and configured hold delay are used in the floating picker.

<img src="home-source-collection-hold.png" width="260" alt="Home Collection heading expanded into the shared Material hold-and-drag source selector" />

Loading errors now offer **Retry** and **Source settings**, which opens the affected profile directly. **All** opens source management. Light/dark captures use a synthetic authentication failure; the extension form and independent main/floating selections were checked on Android 15.

<img src="home-source-error-light.png" width="220" alt="Light home carousel authentication error with Retry and Source settings actions" /> <img src="home-source-error-dark.png" width="220" alt="Dark home carousel with the same direct recovery actions" />

## Moeview extension

The separate [Moeview APK](../moeview-source.md) supplies named booru profiles and category presets to the existing SAO layout. These home captures use synthetic images and a test-pattern MP4; no personal library is accessed.

<img src="moeview-home.png" width="220" alt="Moeview profile and ten tag categories in the unchanged SAO home carousel" /> <img src="moeview-video-dark.png" width="220" alt="Moeview MP4 playing in the existing dark home carousel" />

<img src="moeview-settings.png" width="220" alt="Extension-owned Material form with board, ratings, base tags and explicit verification" /> <img src="moeview-settings-dark.png" width="220" alt="The same Moeview form in Material dark colors" />

The real Danbooru verification screen reaches Cloudflare's human-verification checkbox. This capture demonstrates the explicit browser flow; it does **not** establish that clearance or the native API handoff succeeds.

<img src="moeview-cloudflare.png" width="260" alt="Danbooru's human-verification checkbox inside the explicit extension-owned verification screen" />

## Floating source visibility

Per-profile switches under **Show in floating picker** keep configured sources on the main screen while excluding them from the floating selector and its All results. Turning every switch off offers **Manage sources**. These captures use synthetic profiles; the selected main profile remains selected even with its floating switch off.

<img src="floating-source-switches.png" width="220" alt="Floating-only per-source switches alongside existing source editing controls in light mode" /> <img src="floating-source-switches-dark.png" width="220" alt="The same floating-only source settings in dark mode" /> <img src="floating-sources-off.png" width="220" alt="Empty floating picker explains disabled sources and offers Manage sources" />

The original sticker loads inside individual home cards. The carousel stays mounted while metadata and a bounded nearby preview window load, without a separate loading overlay. This delayed-network fixture holds preview bytes so the in-card placeholders remain visible.

<img src="home-card-previews.png" width="260" alt="Original sticker placeholders inside home carousel cards while preview transfers are deliberately held" />

## Search suggestions and home categories

**Settings → Home categories** edits SAO's per-profile home chips, separately from extension credentials. Add, edit, remove or reset categories; supported sources offer query completions. Choosing a profile here leaves the browsing source unchanged. These synthetic Moeview fixtures exercise the selected board's tag dictionary and preserve preceding query terms. Light/dark dropdowns and keyboard visibility were reviewed.

<img src="home-category-editor.png" width="220" alt="SAO Home categories page with per-source chips and Add and Reset actions" /> <img src="home-category-suggestions.png" width="220" alt="Inline category editor with a board tag suggestion above the keyboard" /> <img src="floating-tag-suggestions.png" width="220" alt="Floating search offering board-specific completions for the second tag" /> <img src="floating-tag-suggestions-dark.png" width="220" alt="The same floating tag suggestions in Material dark colors" />

## Installed source extensions

The main screen and floating picker keep separate selection keys in the existing host store. Media sources lists installed APKs independently of configured profiles, with Android App info and Uninstall actions. General controls, profiles and extension cards share one scroll surface.

| Light | Dark |
| --- | --- |
| <img src="installed-extensions.png" width="260" alt="Installed source APKs with versions, App info and Uninstall actions" /> | <img src="installed-extensions-dark.png" width="260" alt="Installed source extensions using Material dark colors" /> |

## Native photo albums

Local Sticker 1.5 opens Android’s Albums tab when supported and provides a Cloud photo settings shortcut. The picker grants selected-media access; it does not link a Google Photos album or follow later additions. These captures use synthetic emulator media. Google Photos custom-album availability requires an account/device check.

| Local Sticker setup | Android Albums tab |
| --- | --- |
| <img src="local-cloud-photo-setup.png" width="260" alt="Photo picker import mode with a direct Cloud photo settings shortcut" /> | <img src="local-photo-albums.png" width="260" alt="Android photo picker starts on the Albums tab with synthetic videos and downloads" /> |

## Media imports and folder mappings

Editing a profile opens its extension settings directly. Configuration, collections, importing and processing share one scroll surface, with colored Material 3 headings and divider lines. There is no intermediate settings overview. One-time selected-file imports and automatic device-folder uploads have distinct descriptions; Save source and Remove source sit together at the end. Local collections explain in-place discovery versus selected items. All three extension forms share a save/keep/discard warning for modified drafts. These Android 15 captures use synthetic media and test folders; originals remain intact.

| Light | Dark |
| --- | --- |
| <img src="source-settings-immich.png" width="260" alt="Immich profile, connection and library configuration on the native settings page" /> | <img src="source-settings-immich-dark.png" width="260" alt="Immich configuration with colored section headings in dark mode" /> |
| <img src="source-settings-immich-imports.png" width="260" alt="Add media and connected-folder controls below the Immich configuration on the same page" /> | <img src="source-settings-immich-imports-dark.png" width="260" alt="Inline Immich importing and live queue progress in dark mode" /> |
| <img src="source-settings-local.png" width="260" alt="Local selected collection progress and an explanation of automatic folder discovery versus adding selections" /> | <img src="source-settings-local-dark.png" width="260" alt="Local collection controls and selected-media guidance using Material dark colors" /> |
| <img src="source-settings-unsaved.png" width="260" alt="Shared save, keep editing and discard changes dialog" /> | <img src="source-settings-unsaved-dark.png" width="260" alt="Unsaved source warning restored across a dark theme change" /> |

[Setup, scheduling and retry behavior](../media-imports.md).

## Wi-Fi upload policy

**Settings → Media sources → Uploads** offers **Upload only on Wi-Fi** for every source. Selected and automatic uploads wait for Wi-Fi; automatic uploads also require an unmetered connection. Local copies stay offline-capable. These captures use a synthetic profile and deferred queue; both themes show the enabled setting.

| Light | Dark |
| --- | --- |
| <img src="source-upload-wifi.png" width="260" alt="Global Upload only on Wi-Fi setting in SAO Media sources" /> | <img src="source-upload-wifi-dark.png" width="260" alt="Enabled Wi-Fi upload switch and upload guidance in Material dark colors" /> |

## NSFW source access and additive metadata

NSFW Mode retains its original wording. Marked sources stay configured and editable while their disabled switches explain how to restore access; both browsers and All apply the same policy. Local Sticker keeps automatic tags/text visible and combines them with the editable additions. Android 15 captures use synthetic media only.

| Disabled source | Local source flag | Metadata additions |
| --- | --- | --- |
| ![NSFW source disabled](nsfw-source-disabled.png) | ![Local NSFW source setting](local-nsfw-setting.png) | ![Additions alongside generated data](local-additive-metadata.png) |

## Independent extension publishers

A differently signed APK remains unverified after explicit approval. The warning identifies its package and SHA-256 signing certificate; Cancel grants nothing, and Revoke access blocks its sources and subsequent Binder/media access. These reviewed Android 15 captures use an independently signed synthetic Immich fixture.

| Approval warning | Allowed publisher and revoke control |
| --- | --- |
| ![Extension certificate warning](extension-trust-warning.png) | ![Unverified publisher with revoke control](extension-trust-allowed.png) |

## FindThatMeme extension

The separate APK supplies keyword search, media filters, pagination and home presets to the unchanged SAO host. Settings and floating captures use synthetic fixtures; the last capture shows a public cat MP4 playing through the signed optimized extension. Light/dark forms, their save footer and the existing picker were reviewed. [Setup and validation](../findthatmeme-source.md).

<img src="findthatmeme-settings-light.png" width="220" alt="FindThatMeme profile settings with source name, included words and content ratings in light mode" /> <img src="findthatmeme-settings-dark.png" width="220" alt="The same extension-owned settings in Material dark colors" /> <img src="findthatmeme-floating-dark.png" width="220" alt="Existing SAO floating search uses Description and media filters while hiding unsupported Lucky" /> <img src="findthatmeme-live-video-dark.png" width="220" alt="Public cat video playing in the unchanged SAO carousel through the optimized FindThatMeme APK" />

## Attachment size choice

Only explicit Discord Paste / Attach over the free-tier threshold asks whether to keep the original or create a smaller copy. Copy stays unchanged. Synthetic JPEG and intercepted send intents; visually checked in both themes. [Conversion and HDR policy](../media-exports.md).

| Light | Dark |
| --- | --- |
| <img src="discord-attachment-size-light.png" width="260" alt="Discord oversized attachment choice in light theme" /> | <img src="discord-attachment-size-dark.png" width="260" alt="Discord oversized attachment choice in dark theme" /> |

## Extension catalog and updates

Supported APKs build independently in [SAO-extensions](https://github.com/nijuyonkadesu/SAO-extensions). Media sources offers Install, Update, App info and Uninstall on the same page. These captures show public development releases, including a real FindThatMeme 1.0.0 → 1.0.1 update with a saved synthetic profile retained. Android owns permission and confirmation; cancelling leaves Update available. No personal credentials or media were used.

<img src="extension-catalog-light.png" width="220" alt="Installed Moeview and available FindThatMeme with distinct vector icons" /> <img src="extension-catalog-dark.png" width="220" alt="The same extension catalog in Material dark colors" />

<img src="extension-update-light.png" width="220" alt="A newer FindThatMeme release offers Update beside App info and Uninstall" /> <img src="extension-update-dark.png" width="220" alt="Installed extension updates in Material dark colors" /> <img src="extension-update-confirmation.png" width="220" alt="Android confirms the FindThatMeme update before changing the installed APK" />

Installed APK icons come from Android’s package inventory; available icons use the catalog SVGs. First-party APKs now have distinct lightweight vector artwork: an adapted Immich flower and original Local Sticker, Moeview and FindThatMeme marks, plus Android adaptive/monochrome variants. [Artwork preview and attribution](https://github.com/nijuyonkadesu/SAO-extensions#sao-extensions). Icon requests use the shared image cache and survive progress rebinds.

<img src="extension-installed-icons.png" width="220" alt="Installed Immich and Local Sticker cards display their new Android adaptive icons" />

[200% text: extension actions wrap without clipping](extension-actions-large-text.png). [Catalog and validation details](../extension-catalog.md).
