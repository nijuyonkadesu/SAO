# Floating picker previews

Captured on the Android 15 emulator using bundled samples and a localhost Immich fixture. The emulator has no native hook framework; native delivery was checked separately with recording APK probes and on the user's phone.

| Carousel in light mode | Carousel in dark mode | Context search selector |
| --- | --- | --- |
| <img src="floating-carousel.png" width="260" alt="Light picker with continuous selection border and rounded Material search, close and check icons" /> | <img src="floating-carousel-dark.png" width="260" alt="Dark picker with continuous selection border and matching icon foregrounds" /> | <img src="floating-search.png" width="260" alt="Expanded Context selector with looping drag choices during text and emoji search" /> |

Enabled/selected settings, granted permissions and configured source/app controls share a filled Material secondary container. The floating icon follows the same dynamic theme as activities. Picker icons use Material Symbols Rounded; the selection outline follows the visible carousel mask, including narrow side cards. Visually checked in light/dark themes and at 200% text.

| Configured settings | Floating icon in light mode | Floating icon in dark mode |
| --- | --- | --- |
| <img src="floating-settings-configured.png" width="260" alt="Configured media source and Telegram-app controls filled like enabled preferences" /> | <img src="floating-icon-light.png" width="260" alt="Floating smiley using the wallpaper's light primary container and foreground" /> | <img src="floating-icon-dark.png" width="260" alt="Floating smiley using the corresponding dark primary container and foreground" /> |

Opening SAO shows the floating icon automatically once overlay access is granted. The inline “Show icon on app launch” setting is enabled by default; Stop or dismissal ends the current session.

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

<img src="floating-instagram-video.png" width="280" alt="Instagram target with an MP4 preview and enabled native Paste" />

The MP4 preview uses a bundled test clip and a recording native client. It demonstrates
capability gating and UI; authenticated Instagram video sending through Vector still
requires phone validation. See the [delivery capability matrix](../media-capabilities.md).
