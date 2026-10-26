# Host UI motion

SAO uses Views and the existing Material dependency. Presentation is owned by the host; extension APIs and source configuration are unchanged.

- Home, Settings and Usage use Material Shared Axis transitions for forward/back navigation. Each screen is one transition group, so individual chips and carousel cards do not fly separately.
- Returning home restores the SAO toolbar title instead of retaining the previous screen's title.
- Source management, home collections, Telegram app selection, floating settings and the share-import screen have a short entry reveal. Restoration does not replay it.
- The floating picker reveals the search field, source/target panels and inline search sections. Opening the home-collection editor and displaying a home error/empty panel use the same effect.
- `ui/SaoMotion.kt` owns these reveals: 4 dp of movement with the theme's short duration and emphasized deceleration. Visibility and input update immediately; hiding cancels the effect and resets the final properties. Activity stop/view destruction also cancels it.
- Android's disabled-animation setting skips effects. No timers, polling, preferences, background workers or new dependencies are involved. Media binding, playback, carousel scrolling, copy/paste operations and bubble restoration do not start these effects.

Usage-specific calendar and value transitions remain in `usage/UsageMotion.kt`. They display prepared final metrics; animation never writes interpolated counts or triggers database/source requests. See [usage analytics](usage-analytics.md) for incremental aggregation and caching.

## Validation

The debug and instrumentation APKs build; lint has no errors. On an isolated API 35 emulator, two motion tests pass with animation both enabled and disabled: forward/back navigation and recreation settle correctly, and rapid hide/show, stop and destruction restore final view properties. Three local-fixture picker checks cover inline filters across reopening/recreation without clipboard changes, source hold selection and retained chronology/search position. Settled controls and a navigation recording were visually inspected; local artifacts are in `/srv/samba/share/tmp/sao-motion/`. This does not measure performance on every physical device.
