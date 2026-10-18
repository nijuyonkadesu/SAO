# Share Arts by Overlay
Started this project to test and apply skills I obtained on learning native android development, and yes, I've faced lots of hurdles during the development. I enjoy to overcome them all lol!
Choose a category, scroll till you find your favourite pic, share or save! Uses [waifu.pics](https://waifu.pics/docs) api. And many thanks for public free API!

## Previews
<p align="center">
<img src="previews/ss (1).png" alt="home" width="270px" />
<img src="previews/ss (2).png" alt="sharing on telegram" width="270px" />
</p>

## Floating media sources

The floating picker supports separately installed source extensions. Install SAO and the Immich extension APK, then add named profiles under **Settings → Media sources**. Each extension owns its credentials; SAO handles browsing, media caching and chat insertion. [Setup and extension contract](docs/source-extensions.md).

The [Local Sticker extension](docs/local-sticker-source.md) supports named folders with per-album progress/pause/restart, offline labels/OCR, optional CLIP Context search and metadata corrections.

## Stack
- [x] Retrofit - network client
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

The optional [floating media picker](docs/floating-picker.md) supports bundled samples, named Immich and installed-app [Rays sources](docs/rays-source.md), text/emoji search, GIF/video previews and chat delivery. It leaves the finalized home carousel unchanged. [Instagram delivery and modern LSPosed setup](docs/instagram-dm-delivery.md) describe the optional native Paste action.
