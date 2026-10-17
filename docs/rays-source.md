# Rays as an installed-app source

Install the Rays build containing the paged external API changes, then choose **Add source → Rays** in SAO's Media sources page or floating picker. Rays appears in the add menu only while its app is installed and enabled. Existing saved connections remain removable if the app is later uninstalled. Release and development Rays builds can be configured separately and given any source name.

In Rays, enable **Settings → API (Experimental) → API grant**, add SAO's displayed package name (`one.njk.sao`, or `one.njk.sao.testing` for prerelease), and enable its switch. Return to SAO, test the connection, then save. Stock Rays 2.3 does not contain the binding/API fixes; SAO explains that an updated Rays build is needed.

SAO reads only the installed app's public AIDL contract and granted content URIs. Rays retains its database, annotation workflow and models; none are bundled into SAO. Images, GIFs and animated WebP use the existing carousel and copy/paste delivery paths. SVG is rasterized locally for preview and static export. Rays currently imports images; videos remain available through sources that support them, such as Immich.

Search passes the complete Unicode query to Rays, including emoji. Matching follows Rays' title/tag search domains, regex and whitespace settings. Immich's context/people/place controls, chronology browsing and random-search buttons are not exposed for Rays. Queries are not cached by this provider. The existing in-memory picker session can retain the current result set while reopening the picker, and prepared clipboard exports retain their existing file cache.

Each page requests at most 60 items and advances using Rays' explicit next offset until the end. The Binder connection exists only for a request, all blocking IPC and content reads run on IO, and switching sources cancels stale work. Binding times out after five seconds; failures require an explicit retry and never start an automatic retry loop. Original media remains in Rays until the user invokes an export action. SAO validates URI authority and source identity before opening it.

Validation uses Android 15 with a separate installed Rays build, a 65-item synthetic library, and three explicitly authorized local sticker copies kept outside both repositories. It covers paging to EOF, page-scoped URI grants, Unicode queries, GIF/animated-WebP byte preservation, static WebP sticker export, SVG rasterization and source switching. The companion Rays fork includes standalone API tests and client-contract documentation.
