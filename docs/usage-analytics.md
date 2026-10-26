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

The dashboard is one scrolling page, not Kimon's four-tab layout. One selected date drives the detailed day/week/year reports; previous/next day, a date picker and a return-to-today action keep the scope explicit. The activity calendar has a connected Month/Year toggle group. Month uses an official Material Uncontained carousel with fixed-width cards, a neighboring-month preview, and free flinging before snapping. Browsing months updates their summaries without changing the selected day or rebuilding other reports; choosing a date updates the detailed reports. Year navigation changes the selected date as before. Calendar mode, browsed month, selected date and scroll positions live in SavedStateHandle, never preferences. Switching calendar mode keeps the toggle group mounted for its native selection animation and replaces only the calendar contents; it does not fetch data or rebuild neighboring reports.

| SAO section, in display order | Content |
|---|---|
| Selected day | A primary-container summary with total actions and compact, separately toned search/copy/handoff tiles. Defaults to today. |
| Activity calendar | Month/Year choices retain the yearly heatmap and provide lazy month cards with numbered dates, locale weekday ordering and 28–31 days, including leap years. Cards reserve six week rows to prevent height changes while browsing. All recorded months are available, with at least the past twelve months to browse; no synthetic activity is generated. Four color-intensity levels use quartiles of active-day action counts in the selected period; zero is neutral. Equal counts have equal intensity; outliers do not flatten the other days. A Less/More legend explains the scale. Future dates are omitted. Selecting a date brings the hourly section into view. Month shows total actions, active days and actions per active day. Year shows active days and lifetime current/best streaks. |
| By hour | Selected day's 24-hour activity bars, exact-count accessible summary and tap-to-inspect counts. |
| Through the week | Selected week's total actions/handoffs and seven-day trend; date range is shown. Selecting a point changes the dashboard date. |
| Destinations | The same week's accepted handoffs, with a compact donut and count/percentage legend; reflows vertically at large text sizes. |
| Breakdowns | Weekly action types, media/source counts, handoff outcomes and search-mode/performance counters, grouped rather than repeated across separate tabs. |
| Milestones | Selected-year totals and best day/week/month/streak, average actions per active day, and lifetime totals. |
| Local controls | Pause/resume recording and confirmed clearing, on the same page. |

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

Room provides one selected-week bucket query and compact lifetime daily totals. Daily details filter that week's buckets; monthly/yearly heatmaps and records use daily totals, so displaying all sections does not fetch a year's detailed buckets or bind an extension. Calculations run off the main thread and views collect only while started. A ViewModel/SavedStateHandle retains date and vertical/horizontal scroll state, without preference writes. Date/time-zone changes refresh the page through lifecycle/system events, not polling. The writer waits for real actions while idle. Persistence is best effort on abrupt process death; failed analytics never changes a media action’s result.

### Views / Material 3 design

Analytics alone uses `Theme.Material3Expressive.DayNight.NoActionBar`, wrapped with `DynamicColors` so its system palette stays consistent with SAO. The host activity and other destinations keep their existing theme. The already included Material 1.14.0 supplies Expressive tonal icon buttons, a tonal date button, connected Month/Year selection and native pressed/checked shape transitions. Emphasized type gives the daily total and key numbers a clear hierarchy. The main summary has an asymmetric bottom-start corner, mirrored in RTL; connected metric tiles use 20 dp outer corners and 4 dp inner corners with 2 dp gaps. Metrics stack at large text sizes. No fonts, libraries or decorative assets were added.

Primary/on-primary-container roles define the main summary; secondary and tertiary container/on-container pairs distinguish supporting metrics. Neutral surface containers keep plots readable, with a subtle fill beneath the weekly trend. Repeated decorative chart badges and tabs are removed. The month carousel uses the already included Material library (`CarouselLayoutManager`, `UncontainedCarouselStrategy`, `MaskableFrameLayout`, `CarouselSnapHelper(false)`). Only the focused month accepts date actions, and partially masked neighbors are hidden from accessibility until focused. The official mask-change callback anchors the statistics row to the right edge for left previews and the left edge for right previews, exposing rounded tile corners instead of a center slice. This uses render translations without remeasuring; a post-offset scroll callback keeps the side correct during Material's scroll/layout sequence and RTL browsing. The focused row keeps its original position; previews move only when their mask reaches the row bounds, accounting for its padding. Graphs use small native Canvas Views rather than another chart dependency; exact-value summaries and accessible labels accompany them. The compact heatmap exposes each date as an accessible virtual button through ExploreByTouchHelper; keyboard/TalkBack selection and the full-size date picker provide alternatives to tiny chart-cell touch targets. Theme color roles cover surfaces, text and series, with Material Symbols, 16 dp screen insets, 16 dp card padding, 12 dp card gaps and 48 dp navigation targets. Strings/plurals are Android resources; locale formatting, RTL, dark mode and large-text reflow are supported. The source SDK and extension APKs are unchanged.

### Validation

Verified on a disposable Android 15 emulator with synthetic counters and no private accounts or chat sends:

- **54 JVM tests pass**, including nine usage cases covering empty data, gaps, future dates, locale/week/year boundaries, leap years, scoped yearly records, accepted-outcome denominators, heatmap intensity/outliers and consistent day/week/year scopes in one dashboard, and month totals/boundaries/leap days.
- **Three Android tests pass:** ordered atomic recording/pause/clear behavior; toolbar order, stacked sections and calendar-mode/date/browsed-month/scroll restoration across recreation, multi-month flinging and keeping detailed reports mounted during browsing; opt-in populated reports, compact cell dimensions, monthly navigation, fitting all weekday columns, accessible date selection, actual filename-copy recording and UI pause/confirmed-clear controls. The populated-report case also passes separately in dark mode, at 200% font size, and with the RTL pseudolocale.
- **Visual inspection:** the stacked dashboard and chart/calendar details in light/dark, large text and RTL. Expressive captures are saved locally in `/srv/samba/share/tmp/analytics/expressive/`. Earlier captures: stacked redesign in `analytics/redesign/`, monthly carousel in `analytics/carousel/`, edge anchoring with both neighbors in `analytics/carousel-edges/` (light and RTL dark at 200% text), monthly calendar in `analytics/monthly/`, toolbar/empty states in `analytics/`.
- **Builds:** debug and instrumentation APKs succeed. Lint reports no errors and no issues in the new usage code/resources. Usage database backup exclusions were reviewed.

Synthetic seeding exists only in an opt-in instrumentation test; production never seeds history. The debug APK is copied to `/srv/samba/share/app-debug.apk`. No APK/release is published and the PR description is unchanged. These checks validate SAO's recording and reporting; they do not claim end-to-end delivery in third-party messaging apps.
