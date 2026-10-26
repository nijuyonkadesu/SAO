# SAO usage analytics

## Reference study

Kimon was cloned to `/tmp/sao-kimon-reference` and inspected at commit `2fcfe3b2f4376b35b1db79b46366c54f57ea8f9f`. The study covers its navigation, every Analyze tab/component, statistics models/use cases, and all 17 images linked from [SCREENSHOTS.md](https://github.com/zenzer0s/kimon/blob/2fcfe3b2f4376b35b1db79b46366c54f57ea8f9f/SCREENSHOTS.md). All eight Analyze screenshots were inspected individually in light and dark mode; the other nine establish the surrounding screen responsibilities. Kimon uses Compose and a noncommercial source license. SAO uses an original implementation of the general presentation ideas, with Android Views and official Material 3 components; no Kimon source, assets, fonts, theme or dependencies are copied.

### Kimon screen inventory

| Screen | Responsibility |
|---|---|
| Focus | Start/pause/reset Pomodoro sessions, choose a tag, show concentric-dial or flip-card clocks; timer/break configuration, DND, alarms and notification are supporting features. |
| Analyze | Four time-range pages under a persistent capsule selector; swipe/click navigation, selected-range state and independently scrolling card sections. |
| Plan | Add, complete and remove tasks, attach tags and estimated focus-session counts. |
| Sleep | Daily sleep duration/goal/quality, bedtime and wake-up summaries, date navigation, manual records and sleep/Health Connect integration. |
| Steps | Steps versus daily goal, estimated distance/calories and daily/history summaries; can share the Sleep area when configured. |
| Settings | Entrances to timer, sound/vibration, appearance/theme, data/backup, about/developer and optional sleep/steps configuration. Theme supports system/light/dark, dynamic palettes and AMOLED variants; backup imports/exports application data. |
| Widgets | A sleep summary and focus heatmap outside the main app. These are separate surfaces, not Analyze tabs. |

### Every Analyze page and its components

| Page | Meaning and components |
|---|---|
| Overview | Today’s focus time and session count; current/best active-day streaks; navigable monthly activity calendar with weekday rows/week columns, marked active dates and outlined today; month active-day count, average duration per active day and total duration; lifetime duration, sessions and active days. Today/lifetime remain independent of the selected calendar month. |
| Day | Previous/next date and calendar picker; chosen day’s focus duration/session count; 24-hour rounded-column chart with time/grid/value axes; chronological session/break timeline with tag color, label, start/end and duration; empty state links back to Focus. |
| Week | Previous/next week and date picker; weekly duration/session count; donut showing focus-time distribution by tags with named/color legend and percentages; seven-day focus trend with points, connecting curve, shaded area and labeled axes. |
| Year | Previous/next year and picker; yearly duration, sessions, average session, active days, best streak, best day/week/month; paged pairs of monthly activity heatmaps, weekday labels, dates and page-position indicator. The source currently calculates some streak values from lifetime sessions; SAO scopes annual records to the selected year. |
| Shared components | Icon badges and semantic card headers; tonal statistic tiles; connected edge rounding for grouped tiles; compact summaries; previous/next controls and range badges; themed surface/outline hierarchy, consistent gaps and contextual empty states. Colors distinguish data categories but legends/numbers remain readable without color. |

## SAO implementation

SAO owns collection, storage, aggregation, charts and controls. No extension or SDK protocol change is needed. Entry: an analytics icon **between Edit and Settings** in the existing home toolbar. The home carousel and settings layout stay as they are.

| SAO page | Content |
|---|---|
| Overview | Today’s searches, media copies and paste handoffs; current/best usage streak; navigable monthly activity calendar; active days/month activity; lifetime actions and active days. |
| Day | Date picker and previous/next controls; action/search/handoff totals; 24-hour action chart; hourly summary; action, search-mode/performance, outcome, source and media-format breakdowns. This is an hourly aggregate, not a stored event log. |
| Week | Week navigation; action and delivery totals; destination-app distribution donut with exact counts/percentages; seven-day activity trend; source and media-format breakdowns. |
| Year | Year navigation; actions, searches, paste handoffs, active days, streak and best day/week/month; browsable monthly heatmaps. Calendar date selection opens that Day report. |

### Metric definitions

- **Search request:** a completed initial result-page request made by the picker after the debounced query changes, including contextual/filter/emoji/lucky searches. Canceled requests and an unconfigured picker are not counted. Reopening a retained result set, paging, thumbnails, completion suggestions and index-refresh pagination do not create search counts. Only a closed, typed mode, outcome, result count and latency are retained, never query text or selected people/places.
- **Media copy / filename copy:** successful Android clipboard publication, including the existing copy-on-selection behavior. Preparing a file alone does not count. Internal preparation for Paste does not create an additional explicit-copy count.
- **Paste handoff:** explicit Paste handed to accessibility/native integration. Accepted, rejected and uncertain outcomes remain separate; an accepted handoff does not prove the user sent a message or the server delivered it.
- **Share / sticker handoff:** an explicit Android/native attachment/sticker route was launched. Selecting Media is recorded as an attachment, not as a sticker.
- **Download / bookmark:** a successful gallery save/bookmark write. Merely viewing, scrolling, highlighting, prefetching and opening the analytics screen are not actions.
- **Dimensions:** home/picker surface, stable source-profile UUID, destination family, image/animation/video, delivery path and outcome. Source names are resolved from SAO’s existing profile catalog for display; credentials, source configuration hashes, filenames, asset IDs, media, chat identity and raw search text are excluded.
- **Activity and streaks:** local calendar dates with an actual recorded action. Current streak may end today or yesterday; future dates are ignored. Week boundaries follow the app locale. No idle overlay time or background extension work is counted.

### Data, privacy and efficiency

The existing Room/coroutines stack provides a separate host-owned usage database, with counters grouped by local day/hour and typed dimensions. A bounded, ordered asynchronous writer keeps recording from blocking delivery or making media work fail. Recording and Clear statistics controls are available directly on the usage page; clear/pause are serialized with writes so queued work cannot repopulate cleared data. Clearing preserves the recording toggle. Recording is local and enabled by default; there is no telemetry SDK, upload, background timer, wake lock, extension binding or UsageStats permission. Database files are excluded from Android cloud/device backup. Counters start with this build; no invented historical data is shown.

Room provides period-bounded bucket queries and compact lifetime daily totals. Calculations run off the main thread and views collect only while started. A ViewModel/SavedStateHandle retains page/date/scroll state, without preference writes. Date/time-zone changes refresh the page through lifecycle/system events, not polling. The writer waits for real actions while idle. Persistence is best effort on abrupt process death; failed analytics never changes a media action’s result.

### Views / Material 3 design

The screen uses a Material toolbar destination, Material tabs, cards, text/icon buttons, filled selection chips, date picker, clear-confirmation dialog and tonal metric tiles. Graphs use small native Canvas Views rather than another chart dependency; exact-value summaries and accessible labels accompany them. Calendars use actual focusable views and lazy horizontal month presentation. Theme color roles cover surfaces, text and series, with Material Symbols, 16 dp screen insets, 16 dp card padding, 12 dp card gaps and 48 dp action targets. Strings/plurals are Android resources; locale formatting, RTL, dark mode and large-text reflow are supported. No dependency was added, and the source SDK and extension APKs are unchanged.

### Validation

Verified on a disposable Android 15 emulator with synthetic counters and no private accounts or chat sends:

- **51 JVM tests pass**, including six new aggregation/privacy cases covering empty data, gaps, future dates, locale/week/year boundaries, leap years, scoped yearly records and accepted-outcome denominators.
- **Three Android tests pass:** ordered atomic recording/pause/clear behavior; toolbar order and selection restoration across recreation; opt-in populated reports, calendar dimensions, actual filename-copy recording and UI pause/confirmed-clear controls. The populated-report case also passes separately in dark mode and at 200% font size.
- **Visual inspection:** all four populated pages and their chart/calendar details in light/dark and large text; empty state; mirrored RTL layout; home toolbar icon placement. Screenshots are saved locally in `/srv/samba/share/tmp/usage/`.
- **Builds:** debug, instrumentation APK and minified unsigned release succeed. Lint reports no errors and no issues in the new usage code/resources. Usage database backup exclusions were reviewed.

Synthetic seeding exists only in an opt-in instrumentation test; production never seeds history. The debug APK is copied to `/srv/samba/share/app-debug.apk`. No APK/release is published and the PR description is unchanged. These checks validate SAO's recording and reporting; they do not claim end-to-end delivery in third-party messaging apps.
