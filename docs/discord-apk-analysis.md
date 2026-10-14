# Discord media insertion: APK analysis

Inspection date: 2026-10-11. Build: **348.10 - Stable**, package `com.discord`, version code
`348010`, target SDK 36. The version matches Google's public Play listing and the tracker’s
stable index at the time of download. Beta and Alpha were deliberately excluded.

Downloaded APKs are outside the repository at
`/home/shichika/Downloads/discord-348.10-stable/`. `base.apk` contains the code; installation
also needs the matching architecture, language and density splits. An ARM64 ZIP of the four
matching APKs is available as `discord-348.10-stable-arm64.apks`.

Android `apksigner` verifies every downloaded split. Each has the same signing certificate:
`3c39d23cf9367849a5c699395647fe0e5bfea5a1f1f40d8c717ddc70f8bfa113`.
The certificate's MD5 matches the publicly published Discord certificate. The base APK also
has a verified Google source stamp. Base APK SHA-256:
`2331efbbb5e37fbd9b6184127bd53f401f3bb873b452d0595911f016cd51a519`.

## Result

| Media | Existing native Paste handler | SAO behavior |
| --- | --- | --- |
| PNG/JPEG | Accepts an image URI | Paste assistance can target the original editor |
| GIF | Accepts an image URI, without a special GIF exclusion | GIF Paste is now enabled; original bytes remain on the clipboard |
| MP4/WebM | Does not consume video URIs as attachments | Paste stays disabled; Attach remains the fallback |

Clipboard selection and paste are separate actions. Selecting media still highlights and copies
immediately. Paste explicitly closes SAO and requests one paste in the previously captured
editor. It never presses Send. GIF insertion needs neither root nor a replacement keyboard.

## Native and JavaScript evidence

JADX inspection located the composer in `com.discord.chat.input.views.ChatInputRootView`
and `DCDChatInput`. The root registers an Android receive-content listener for `image/*`.
The listener partitions items by the URI's actual ContentResolver MIME type, taking only
`image/` items. It forwards their URIs to `ChatInputListener.onImageInserted`.

`ChatInputViewManager` forwards that event to React Native as `onPasteImage`, retaining the
URI and its MIME type. GIFs therefore use the same native entry point as ordinary photos;
videos do not enter it. Although the edit control changes ordinary paste to paste-as-plain-text,
the registered content listener still consumes image URIs first.

The bundled JavaScript is Hermes bytecode version 98. Targeted disassembly traced the event
through the chat-input handlers to a draft-file insertion using the current channel's ID,
original URI and MIME type. It obtains missing image dimensions and checks the composer's
focus/upload capability. This supports staging media in the existing draft rather than choosing
a recipient. Hermes tooling warns that version 98 is not formally supported, so this trace is
supporting evidence rather than an authenticated end-to-end test.

## Emulator verification

An isolated API 35 probe loads the actual installed Discord classes through its package class
loader. It constructs Discord's composer, initializes the font cache from Discord's own bundled
font and substitutes a recording listener for the React event listener. Bundled SAO sample files
are supplied by a probe provider. No Discord login, messages or personal media are involved.

Calling the composer's accessibility Paste action produced:

| URI MIME | Paste action returned | Image-insertion callback | Text inserted |
| --- | --- | --- | --- |
| `image/png` | true | yes | none |
| `image/gif` | true | yes | none |
| `video/webm` | true | no | none |
| `video/mp4` | true | no | none |

A successful action return alone does not prove that an attachment was inserted. The callback
distinguishes the accepted image paths from the ignored video paths. The composer runs in the
probe UID and is not attached to Discord's authenticated chat UI. React upload, server acceptance
and animation after sending remain outside this test. SAO's separate receiver tests check URI
permissions, MIME type and unchanged GIF/video bytes across UIDs.

Evidence is in `diagnostics/discord-apk-analysis/`; temporary disassembly and the original probe
build are in `/tmp/sao-discord-apk-research/`. Proprietary APK/decompiled code is not added to SAO.
The updated SAO debug build passed all 13 JVM tests and three focused Discord device tests,
including original GIF clipboard bytes and the updated GIF guidance in the floating picker.

## Remaining video routes

The exported `ShareActivity` accepts binary share intents. Its `ShareProps` reads
`android.intent.extra.shortcut.ID` into `targetChannelId`; the JavaScript share screen can resolve
that channel as an initial destination. The isolated probe also invokes the real `ShareProps`
parser: a synthetic channel ID is retained and the original attachment is included. This verifies
intent parsing, not destination rendering in an authenticated client. It is an internal,
undocumented routing detail, not a
generic API to discover the currently visible chat. Passing a guessed or remembered channel ID
would not meet SAO's current-chat requirement.

Discord also includes a native document picker with a URI result handler. A SAO DocumentsProvider
could expose media to that picker, preserving the chat that started the attachment request, with
additional user actions. A Discord-specific LSPosed integration could investigate that attachment
result path or reliable current-channel capture plus the targeted share route. Neither video route
has been implemented or validated in an authenticated client. The API-102 SAO bridge now also scopes Discord for native PNG/JPEG/GIF
draft insertion. Native video insertion remains unimplemented.

References: [Google Play](https://play.google.com/store/apps/details?id=com.discord),
[Android receive-content API](https://developer.android.com/develop/ui/views/receive-rich-content),
[Android image-keyboard API](https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard),
[Discord's attachment flow](https://support.discord.com/hc/en-us/articles/25444343291031-File-Attachments-FAQ).

## Native bridge update (2026-10-14)

Paste now prefers the verified Vector/LSPosed lease over accessibility. The production
adapter checks the named `DCDChatInput` and `ChatInputRootView`, exact owned editor,
current listener and `lastEditId` opaque draft revision. It does not read draft text,
messages, collections or channel IDs. A changed revision invalidates an outstanding
picker selection; a newly focused foreground draft can receive a fresh lease.

The [reproducible isolated probe](../tools/discord-native-probe/README.md) compiles the
production adapter against Discord's real classes. The unmodified keyboard connection
advertised `image/*`; the policy narrowed it to JPEG/PNG/GIF. PNG and GIF `commitContent`
calls each reached the recording image-insertion callback, and no Send callback fired.
Removing the native listener disabled the policy; modifying synthetic draft text changed
its revision. Unsupported MP4/WebM continue through Attach. These results remain limited
to native editor dispatch in the probe UID, not authenticated React upload/server delivery.

The native composer has no reliable current-channel identifier. The adapter leases that
exact draft/editor, with replacement/revision/focus checks, rather than claiming a verified
channel-ID lease. It stages attachments and never presses Send.

## Keyboard-hide correction (2026-10-14)

`ChatInputRootView` schedules a blur job and clears its edit field when the IME hides.
Requiring an IME/editor-focus lease made native Paste timing-dependent. SAO now uses
Discord's already registered native receive-content MIME types on Android 12+ and calls
that exact editor's public `performReceiveContent` handler on explicit Paste. It restores
only the leased field's focus before dispatch, preserving the focus/image event ordering
for React, and never calls Send. On Android 11, the captured keyboard/clipboard paths
remain available.

The real-class recording probe accepts PNG/GIF with a null InputConnection through the
production policy; both trigger the image callback and zero Send callbacks. The shared
lease tests additionally cover a genuinely unfocused field, pause/resume, single dispatch,
capability removal and hidden-view revocation. Native upload/server behavior remains a
physical-client verification step. See [Android receive-content](https://developer.android.com/develop/ui/views/receive-rich-content).
