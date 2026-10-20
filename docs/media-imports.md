# Adding media and connected folders

Update SAO, **Immich 1.2.0** and/or **Local Sticker 1.6.0**. In **Settings → Media sources**, open a profile's edit action to see its intake overview. Browsing selections and floating visibility remain independent of importing.

[Light/dark intake overviews](screenshots/README.md#media-imports-and-folder-mappings).

**Add media / Files** accepts multiple images and videos. Android's Share menu also offers **SAO**: choose a named destination profile, then a collection if it has several. Only writable profiles accepting every selected format are offered. Read-only providers and the synthetic All source are not destinations.

- **Immich:** the profile's selected owned album is its destination; All media uploads to the library. Uploading needs `asset.upload`, plus `album.read`/`albumAsset.create` for an album. Browsing-only credentials continue working. A failed album assignment does not require sending the original again.
- **Local Sticker:** choose a writable folder collection. Existing read-only grants may need the same folder selected again and saved to retain write access. Sharing copies media there; connected collections are indexed in place. Completed copies enter the index directly and are analyzed in a batch, without a library rescan. Existing same-name files are never overwritten.

Originals are **always kept**. Imports do not mirror deletions or move libraries between accounts. Immich performs its own subsequent indexing; upload success does not imply Context search has processed the asset yet.

## Folders and battery use

In an Immich profile, **Connect a folder** grants access to its subtree and displays the mapping, for example `Reactions → Memes album`. Existing media and later additions enter the same queue. Local Sticker owns its folders through **Collections and processing**; SAO does not copy them or acquire their grants.

Reconciliation enumerates metadata. It matches URI, size, modification metadata and MIME; it has no date high-water mark, so a new file with an old date is discovered. Unchanged completed files incur no import-table writes, hashing, model work or upload calls. Large trees still have metadata enumeration cost. A provider replacing an existing document while preserving all metadata cannot be distinguished without reading content.

- Configured folders get an Android-managed catch-up about every **six hours**, subject to battery/storage and scheduling. Paused sources do not scan. No recurring job exists without configured folders.
- **Check folders now** requests reconciliation. A newly visible Local browsing session or changed query can request one with a five-minute cooldown. Capability reads, later pages and index-change refreshes do not initiate discovery. The floating service schedules no import/index work.
- Automatic uploads require an **unmetered connection**. Explicit selected uploads use the current connection. Local copies work offline.
- Android 14+ explicit uploads use a user-initiated transfer job with a notification; older devices/recovery use WorkManager. Folder work is deferrable and checkpointed. There is no permanent watcher service, custom wakelock or exact alarm.

Generic SAF providers have no universal reliable change cursor or instant monitoring guarantee. For server/NAS folders, [Immich external libraries](https://docs.immich.app/features/libraries/) avoid transferring files through the phone.

## Queue, retries and cleanup

The source overview shows collection names/mappings, last checks, added/pending counts and errors. Pause takes effect between files. Retry affects unfinished items. **Clear** finishes the current file, clears other unfinished items and pauses ingestion until Resume. Disconnect stops future checks and clears that folder's unfinished items. Originals and completed imports remain.

Each new/changed candidate gets a durable receipt and SHA-1. SAO snapshots/hashes in one streaming pass; retries reuse that snapshot/checksum. Immich checks server checksums before uploading, including after uncertain replies, so exact duplicates need no second file payload. Receipts are scoped to source revision/destination. Configuration changes stop stale work; reconnect folders to confirm their new destination.

Transient failures retry at most **four times after the initial attempt**, with increasing delays/jitter and `Retry-After` respected. Invalid or excessively long cooldowns require manual retry. Authentication, revoked grants, changed profiles/files and unsupported formats stop for correction. The browsing read-retry helper never handles mutations.

Queued snapshots/receipts are private, excluded from backup, and separate from the browsing cache. Completion/clearing removes snapshots and unneeded item grants. Transfer work cleans abandoned unreferenced snapshots older than a day. Local partial copies stay hidden until publication; cancellation requests delete only owned incomplete copies. Revoked/unavailable providers may need access restored before cleanup. Originals and completed uploads are never cleaned up.

## Optional extension contract

The additive v1 `imports` capability advertises intake. Explicit `importOptions` returns MIME types, opaque destinations, network/folder support, collection summaries and a settings label. Older extensions remain readable without implementing writes.

`importMedia` carries a UUID, target token, filename/MIME, byte count/date, SHA-1 and an exact read-granted host snapshot URI. The service accepts only its signed caller's `${caller}.imports` authority. Binder JSON carries no bytes, credentials or backend URLs. Streaming helpers verify bytes and close provider pipes on cancellation. Per-file import IPC has an eight-minute deadline, separate from reads.

`importComplete` ends a batch so Local schedules analysis once. Optional `importCancel` cleans unfinished writes for at most 100 request IDs; it never deletes published media. Receipts contain opaque asset IDs and created/existing status.

SAO owns intake UI, folder routing, scheduling, snapshots, durable queue and retry policy. Extensions own credentials, writable-target checks, API translation, duplicate semantics and committing results. Local exclusively owns collection grants, models/OCR and indexing. Immich exclusively owns server/album operations. The host has no provider-name branches.

The native [Photo picker](https://developer.android.com/training/data-storage/shared/photo-picker) grants selected items, including cloud media; Albums does not grant a whole Google Photos album or subscribe to future additions. Folder connections require a SAF directory. [MediaStore generation counters](https://developer.android.com/reference/android/provider/MediaStore#getGeneration(android.content.Context,java.lang.String)) help with accessible MediaStore collections; they are not a universal SAF cursor or permission to read cloud albums. See Android's [transfer scheduling guidance](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options).

## Validation

The follow-up passes 65 JVM checks and 37 distinct host Android checks: nine import checks, 26 Immich source/search regressions and two native Local picker journeys. The Local indexing subset passes 19 functional checks, with two optional fixture helpers skipped. Host, source SDK and extension builds/lint pass with zero lint errors; intake overviews were reviewed in both themes.

Cross-process checks use signed optimized extensions, localhost servers and synthetic media. They cover actual share intents and user-initiated jobs, native folder grants, old-dated additions, unchanged-file no-ops, interrupted-response deduplication, retained snapshots, pause/retry controls, filename collisions and original-byte preservation. The cloud Photo picker journey passes on a fresh primary-user emulator; its earlier run stalled in the reused secondary-user setup. No personal server is needed for these checks.
