# Share Arts by Overlay
Started this project to test and apply skills I obtained on learning native android development, and yes, I've faced lots of hurdles during the development. I enjoy to overcome them all lol!
Choose an installed source, pick a category, scroll to your favourite image or video, then share or save. The home screen keeps its original carousel layout; source APKs now supply the media and category presets. Tap the Collection heading to switch profiles. [Home browser, experimental All source and filename copying](docs/home-source-browser.md).

## Previews
<p align="center">
<img src="previews/ss (1).png" alt="home" width="270px" />
<img src="previews/ss (2).png" alt="sharing on telegram" width="270px" />
</p>

## Media sources

Home and the floating picker use the same separately installed source extensions. **Settings → Media sources → Get extensions** installs supported APKs; newer versions have an **Update** action beside the installed extension. Android asks for installation permission and confirmation. Add named profiles with **Add source**. Each extension owns its credentials; SAO handles browsing, media caching and chat insertion. [Setup and extension contract](docs/source-extensions.md).

All four extensions now build and release independently in [SAO-extensions](https://github.com/nijuyonkadesu/SAO-extensions). [Catalog, installation and manual publishing](docs/extension-catalog.md).

The [Local Sticker extension](docs/local-sticker-source.md) supports named folders with per-album progress/pause/restart, offline labels/OCR, optional FP32/8-bit CLIP Context search with reusable external model folders, and metadata corrections.

The [Moeview extension](docs/moeview-source.md) adds Yande.re, Konachan, Gelbooru and Danbooru profiles, tag search/home presets, GIF/video media and optional browser verification. Install `moeview-sao.apk`; the existing SAO host works without an update.

**Settings → Home categories** lets you customize each source's home chips. Supported extensions offer search suggestions in this editor and floating search; Moeview supplies board-specific tags. [Category settings](docs/home-source-browser.md#custom-home-categories).

## Stack
- [x] Retrofit - network client in the Immich extension
- [x] COIL - Image Loader
- [x] Dagger Hilt - Dependency Injection
- [x] flow & coroutines
- [x] File Provider
- [x] MediaColumns API
- [x] M3 Carousel View
- [x] MVVM - Architecture
- [x] Gradle Version Catalog
- 
## Download APK
Go to the [Releases](https://github.com/nijuyonkadesu/SAO/releases/tag/Release) to download the latest APK

## TODO
- Use WindowManager to use the app on top of any app
- Favorites List Management
- Dunno, maybe add many more APIs into this single app? I wonder...
- A profile system? fufu-fu (ˉ▽￣～)

The optional [floating media picker](docs/floating-picker.md) supports separately installed Immich/Local Sticker/Moeview extensions and installed-app [Rays sources](docs/rays-source.md), text/emoji search, GIF/video previews and chat delivery. It shares media playback and focal-card selection with the home screen. [Instagram delivery and modern LSPosed setup](docs/instagram-dm-delivery.md) describe the optional native Paste action.

Translations: [Crowdin community translation workflow and setup status](docs/translations.md).
