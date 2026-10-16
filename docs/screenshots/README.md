# Floating picker previews

Captured on the Android 15 emulator using bundled samples and a localhost Immich fixture. The emulator has no native hook framework; native delivery was checked separately with recording APK probes and on the user's phone.

| Carousel and automatic copy | Context search selector | Inline floating settings |
| --- | --- | --- |
| <img src="floating-carousel.png" width="260" alt="Sample carousel with a highlighted image, clipboard status and bottom action dock" /> | <img src="floating-search.png" width="260" alt="Expanded Context selector with looping drag choices during text and emoji search" /> | <img src="floating-settings.png" width="260" alt="Floating picker controls below the existing main settings" /> |

The optional sequence view follows Immich's Photos ordering and labels each card with its upload time. The shared hold delay and sequence toggle are inline in settings.

| Sequence and upload time | Sequence and shared hold settings |
| --- | --- |
| <img src="floating-sequence.png" width="280" alt="Chronological neighbors with upload date and time and a Back to results action" /> | <img src="floating-sequence-settings.png" width="280" alt="Browse sequence toggle and configurable 160 millisecond hold delay" /> |

Dock messages sit below the title and above the action buttons. Text rows reserve space when
empty; overflowing labels pause for 1.5 seconds before scrolling. Opening search selects the
whole existing query, and Lucky's icon follows the Material light/dark theme.

| Dock text above actions | Search selection in dark mode | Accessibility instructions |
| --- | --- | --- |
| <img src="floating-dock-marquee.png" width="260" alt="Context amazing with a scrolling title and GIF tip above the transparent action dock" /> | <img src="floating-search-dark.png" width="260" alt="Selected text and emoji query with a contrasting Lucky icon in dark mode" /> | <img src="floating-accessibility-settings.png" width="260" alt="Accessibility access description directs users to find Paste assistance in Android settings" /> |
