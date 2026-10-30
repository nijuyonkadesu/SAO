# SAO floating picker: Material 3 visual review and implementation recommendation

The design agent performed research only. Its recommendation was evaluated and implemented in SAO; implementation notes appear below. Read Android CLI skill and current picker XML/activity, and inspected actual emulator screenshots `/tmp/sao-release-picker-final.png` and `/tmp/sao-picker-large-landscape.png`. The latter is an older large-text baseline; no online emulator was available for a fresh capture (`android layout` was attempted first).

## What was actually reviewed

Visually browsed the current live https://m3.material.io/components catalog overview across all its major sections: buttons, date/time pickers, loading/progress, navigation, sheets, and the remaining component families. Captured the overview at successive scroll positions. Some live previews initially remained blurred/blank while loading; for the relevant families, fetched the official image assets directly from the live pages and viewed those at full resolution.

Viewed the dedicated overview/examples for carousel, toolbars, button groups, chips, search, segmented buttons, and bottom sheets, plus carousel guidelines (six concrete layout/do/don't diagrams), and the toolbar/search guidelines. The follow-up below completes dedicated overview/representative-asset visual coverage for every one of the 36 live catalog entries. This does not claim every variant, subpage/spec/accessibility tab, motion frame, or runtime Android demo was reviewed.

Current M3 expressive guidance prefers connected button groups over expressive segmented buttons, and offers docked/floating toolbars for contextual commands. Those patterns suit a two-source choice and a fixed command dock. They do not justify a navigation bar or another modal surface in a small picker.

Official references: [Catalog](https://m3.material.io/components), [Carousel guidelines](https://m3.material.io/components/carousel/guidelines), [Toolbars](https://m3.material.io/components/toolbars/overview), [Button groups](https://m3.material.io/components/button-groups/overview), [Chips](https://m3.material.io/components/chips/overview), [Search](https://m3.material.io/components/search/overview), [Bottom sheets](https://m3.material.io/components/bottom-sheets/overview).

## Default recommendation

Keep the existing 400 dp maximum width, 520 dp maximum height, translucent host arrangement, carousel physics, focus restoration, immediate copy on tap, bottom actions, and keyboard. The main improvement is stronger surface hierarchy and a compact source control with an inline source panel. The product remains a picker rather than expanding into a gallery.

1. Header: one horizontal row, approximately 56 dp tall at normal text. Existing destination/Clipboard control gets remaining width. Add a MaterialButton reading `Samples ▾` or `Immich ▾`, approximately 88–104 dp at normal text, with a 48 dp minimum touch height. Search and Close remain distinct 48×48 dp controls. No album subtitle in this header in v1. Remove the redundant `SAO ·` prefix if needed to give the actual destination more room.
2. Keep the existing horizontal emoji ChipGroup; do not fabricate a row of word-tag pills. Chips remain optional when a source has no useful filters. Search accepts words and literal emoji, with the existing normalization behavior.
3. Keep RecyclerView + CarouselLayoutManager + MaskableFrameLayout. Preserve HeroCarouselStrategy in ordinary browsing, existing FullScreenCarouselStrategy fallback in short/large-text layouts, CarouselSnapHelper(false), momentum, and separate focused/selected IDs. A focal sticker uses fitCenter. Peeks stay recognizable; do not force tiny decorative slivers when the available size becomes too small.
4. Replace the carousel content temporarily with the source panel when the source button is tapped. Only one inline target/source panel is open at a time. The source panel has a 48 dp Back/title row; a connected single-selection pair `Samples` / `Immich`; a read-only collection/search-mode summary; and a 48 dp `Configure Immich` text action to full native settings. Choosing a configured source can return immediately to browsing; an unconfigured source shows a short inline setup state with Configure. It must not silently change to Samples on a failed connection. Samples selection preserves the saved Immich connection.
5. Keep album selection in full settings for v1. The overlay should not fetch or lay out hundreds of albums. If it is added later, use a bounded RecyclerView replacing this same media area, with local name filtering and stable selected album; no horizontal chip wall or nested dialog.
6. Keep selection name and actual copy status below the media area; mode chip stays checked for both Sticker and Media. Commands remain independent actions. Give Copy/Paste a tonal visual grouping; keep Sticker/Media visually separate as app-specific send/share paths. They are not toggle buttons. Visible labels may stay compact; accessibility descriptions should say `Send sticker to [app]` and `Share media to [app]` to distinguish them from clipboard format preference.

## Native hierarchy

```text
Existing transparent FrameLayout root
  MaterialCardView panel (28 dp corners)
    LinearLayout vertical (12 dp content inset)
      LinearLayout header
        existing destination control (weight=1)
        MaterialButton source control
        MaterialButton Search (48×48)
        MaterialButton Close (48×48)
      existing HorizontalScrollView / ChipGroup (emoji filters)
      FrameLayout media area (weight=1)
        existing RecyclerView carousel
        existing target RecyclerView
        source panel LinearLayout / bounded ScrollView if needed
        empty/initial-error state
        progress / pagination-error footer
      dock surface LinearLayout
        existing selection row / checked mode Chip
        DockedToolbarLayout
          existing action LinearLayout (one or two rows)
            Copy/Paste group (visual tonal container)
            Sticker/Media group (independent MaterialButtons)
        existing short capability hint
```

Use existing state ownership. Source pane visibility is transient ViewModel/memory state; query, emoji filter, and focus do not go into preferences. Connection URL/key, source preference, search mode, and chosen album are settings. Switching source never copies or restores a stale action target. Preserve source-qualified browsing state where practical; restoring focus or returning from Configure must not trigger copy.

## Surface, spacing, and text

Use semantic theme tokens, not the mockup's fixed hex values. Panel: colorSurfaceContainerLow; image cards: colorSurfaceContainerHigh; dock: colorSurfaceContainer; Copy/Paste grouping: colorSecondaryContainer with colorOnSecondaryContainer. Selected card: 2 dp colorPrimary outline + 24 dp check badge; chip active state: primary/secondary container tokens with checkmark. Default chip outlines use colorOutlineVariant for less visual noise. Text: colorOnSurface; supporting copy/capability text: colorOnSurfaceVariant. No global launcher theme migration is required.

Keep outer 28 dp corners, card corners about 24 dp, 8 dp card gaps, 12 dp panel inset, and 8–12 dp gaps between command groups. TitleMedium for destination, LabelLarge for source/actions, BodyMedium for selection status, LabelSmall for short capability text. Allow the normal dock 64 dp if icons are displayed above labels; use two rows when labels do not fit at large text. Keep all command touch targets at least 48 dp.

## Exact MDC 1.14 dock details

The project's pinned MDC 1.14.0 has `com.google.android.material.dockedtoolbar.DockedToolbarLayout` (a FrameLayout). Its standard style is `Widget.Material3.DockedToolbar`, with colorSurfaceContainer, square shape, 64 dp minimum height, 16 dp start/end padding, and fitsSystemWindows=true. The following is a proposed overlay-specific container, not a copied screen sample:

```xml
<com.google.android.material.dockedtoolbar.DockedToolbarLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    style="@style/Widget.Material3.DockedToolbar"
    android:minHeight="0dp"
    android:paddingStart="0dp"
    android:paddingEnd="0dp"
    android:fitsSystemWindows="false"
    app:backgroundTint="?attr/colorSurfaceContainer"
    app:paddingTopSystemWindowInsets="false"
    app:paddingBottomSystemWindowInsets="false">
    <!-- Existing action LinearLayout, with each command >= 48 dp. -->
</com.google.android.material.dockedtoolbar.DockedToolbarLayout>
```

The explicit zero minimum height is important for the existing tight landscape+IME mode; its children determine the reachable height. The picker already applies external insets, so this container must not apply them again. Retain the existing action LinearLayout inside this FrameLayout rather than moving its orientation logic to the toolbar. No HideViewOnScrollBehavior or OverflowLinearLayout: commands must remain visible and may reflow.

The dock style installs an inherited button/icon theme overlay, so style the Copy/Paste tonal commands and ordinary send commands explicitly. The source pair can use MaterialButtonToggleGroup with singleSelection=true and selectionRequired=true, styled as a connected group. In 1.14 that class extends MaterialButtonGroup. Using `Widget.Material3.MaterialButtonGroup.Connected` supplies connected shape/spacing without a global expressive-theme switch; confirm the selected-state selectors in the current theme. Do not use a toggle group for Copy/Paste or the send commands.

Primary implementation references: [1.14 dock docs](https://github.com/material-components/material-components-android/blob/1.14.0/docs/components/DockedToolbar.md), [1.14 dock styles](https://github.com/material-components/material-components-android/blob/1.14.0/lib/java/com/google/android/material/dockedtoolbar/res/values/styles.xml), [1.14 dock size tokens](https://github.com/material-components/material-components-android/blob/1.14.0/lib/java/com/google/android/material/dockedtoolbar/res/values/tokens.xml), [1.14 ButtonGroup docs](https://github.com/material-components/material-components-android/blob/1.14.0/docs/components/ButtonGroup.md).

## Compact, IME, and accessibility behavior

Measure the header's actual available text width. Hide any optional subtitle first (none in v1), then change source to a fixed 48×48 dp icon button if the source label cannot fit without squeezing destination/Search/Close. Avoid a fontScale-only heuristic. Do not shrink Search or Close. Source contentDescription includes source, album, search mode, and `Change media source`; tooltip can simply say `Media source`.

Expanded search replaces the destination/source area and hides the source control, keeping the existing inline TextInputLayout/EditText and keyboard. Search collapse restores source without losing query/filter/focus. Back first leaves editing or the source/target panel, then closes the picker. Preserve the current proven tight landscape arrangement: inline search and four accessible 48 dp command icons, with tooltips/full names. With more room, keep labels and use two action rows for large text. Carousel shrinks or temporarily hides before commands become inaccessible.

Source group exposes single selection; selected cards expose selection state independent of carousel focus. Decorative check badge is not an extra accessibility stop. Status uses a polite live region, but loading announcements should be coalesced. Navigating back from an inline panel restores focus to its source/destination trigger. Source switching clears an unusable selection without changing the clipboard. Optional disconnected backend or unavailable assistance never blocks Close, source switching, or local browsing.

Use official rounded `photo_library` (stacked images) for the source icon; it describes the media collection and works for both local and server content. The actual official SVG is saved at `/tmp/sao-m3-source-official-photo_library.svg`. Its viewBox is `0 -960 960 960`; a VectorDrawable conversion needs a 960×960 viewport and a group translateY=960 before using the same path. Tint via the Material button, with 24 dp icon size. [Official icon asset](https://github.com/google/material-design-icons/blob/master/symbols/web/photo_library/materialsymbolsrounded/photo_library_24px.svg).

## Backend loading and retry

Initial load/error belongs to the media area. Metadata pagination keeps loaded cards and their focus, shows a thin progress indicator during the next page, and offers `Couldn't load more` with a 48 dp Retry button only on page failure. Do not clear the adapter, reset the focus, or overwrite copy status for a page failure. No permanent footer when idle. Prefer a stable small footer region while the request/error is visible rather than repeatedly resizing the window. Smart ranked search has no cursor: after its 100 results, simply stop; do not imply more pages or add an unnecessary total count.

## Artifacts and verification

Standalone interactive study: `m3-picker-design-study.html`; rendered screenshot: `/tmp/sao-m3-picker-layout-study-20261009.png`. Viewed the rendered screenshot, exercised source-panel toggle, checked that its two choices are mutually exclusive, and checked that returning restores the image area. These are design-artifact checks, not Android device validation. The artifact now uses the agreed existing emoji chips and no source header subtitle. After implementation, capture actual normal, expanded-search, inline-source, large-text, landscape+IME, and pagination-error states with Android layout first, then immediately view each capture.

## Complete catalog coverage checklist (follow-up)

All **36 component entries** linked from the live component catalog were visually inspected through their dedicated overview pages and at least one representative official asset. The seven relevant families reviewed earlier were reused; the other 29 were added in this follow-up. `all-buttons` is a category/aggregate route, not an additional component entry.

No component had to be left unviewed. Three initial 40 px lazy placeholders (side sheets, dialogs, divider) were replaced with the matching full-resolution official assets before assessment. Overview/static representative coverage does **not** imply review of every variant, motion frame, spec/accessibility subpage, or runtime Android demo. The loading indicator is specifically represented by a static frame. Combination/use notes below are design judgments for SAO, not claims that the catalog mandates this exact app layout.

| Covered component and official source | Visual evidence | SAO combination/use note |
| --- | --- | --- |
| ☑ [Button groups](https://m3.material.io/components/button-groups/overview) | Standard buttons and connected selected alternatives; previously reviewed. Full-resolution representative visual inspected. | Use connected single selection inside the source pane. Clipboard/send commands remain independent ordinary buttons. |
| ☑ [Buttons](https://m3.material.io/components/buttons/overview) | Elevated, filled, tonal, outlined, and text examples. Full-resolution representative visual inspected. | Tonal Copy/Paste gives useful emphasis. Configure/source controls can use text or low-emphasis styling; avoid giving every control filled-primary emphasis. |
| ☑ [Extended FABs](https://m3.material.io/components/extended-fab/overview) | Three labelled extended FAB sizes with leading icons. Full-resolution representative visual inspected. | Suitable for a main screen primary task; too dominant and space-hungry inside this overlay. Keep settings Show icon an ordinary labelled action. |
| ☑ [FAB menu](https://m3.material.io/components/fab-menu/overview) | Three vertically stacked rounded menus with a closing FAB. Full-resolution representative visual inspected. | Would conceal the persistent dock and add an action surface. Do not introduce one for selection or source choice. |
| ☑ [Floating action buttons (FABs)](https://m3.material.io/components/floating-action-button/overview) | Three rounded elevated FAB sizes. Full-resolution representative visual inspected. | The existing floating entry bubble already serves the entry role. Do not add a second FAB competing with the media or dock. |
| ☑ [Icon buttons](https://m3.material.io/components/icon-buttons/overview) | Plain, filled, tonal, and outlined icon containers. Full-resolution representative visual inspected. | Useful for Search/Close and the measured-width Source fallback. Keep distinct 48 dp targets and full descriptions; stronger backgrounds should communicate a real emphasis/state. |
| ☑ [Segmented buttons](https://m3.material.io/components/segmented-buttons/overview) | Single/multi-selection overview and current expressive replacement notice; previously reviewed. Full-resolution representative visual inspected. | A two-source choice is appropriate, but use the connected group styling already chosen. Do not make action commands look like selected modes. |
| ☑ [Split buttons](https://m3.material.io/components/split-button) | Five sizes with separate primary and arrow areas. Full-resolution representative visual inspected. | Useful when a main action has related alternatives. Source opens one panel rather than having a distinct default action, so one dropdown-affordance button is clearer here. |
| ☑ [Date pickers](https://m3.material.io/components/date-pickers/overview) | Docked calendar, modal calendar, and typed-date variants. Full-resolution representative visual inspected. | No current date-selection task. A future photo-date filter would be a new feature; do not add this just because metadata has dates. |
| ☑ [Time pickers](https://m3.material.io/components/time-pickers/overview) | Dial and typed hour/minute controls with AM/PM alternatives. Full-resolution representative visual inspected. | No current time-selection task. Bubble idle delays are durations, so a clock-time picker would be the wrong semantic control. |
| ☑ [Loading indicator](https://m3.material.io/components/loading-indicator/overview) | A contained morphing-shape indicator represented in the overview hero image. Full-resolution representative visual inspected. | Consider only for an empty initial-loading state. Quiet incremental loading should retain the thin existing progress indicator. This review covers the visual frame, not all animation frames. |
| ☑ [Progress indicators](https://m3.material.io/components/progress-indicators/overview) | Eight straight/wavy linear and circular variants at different thicknesses. Full-resolution representative visual inspected. | Thin linear progress fits media/page loading without taking a new row. Preserve loaded content; keep copy progress distinct from source loading. |
| ☑ [Navigation bar](https://m3.material.io/components/navigation-bar/overview) | Compact and wider four-destination bars with active tonal indicators. Full-resolution representative visual inspected. | These represent primary destinations. Samples/Immich are data-source choices, and Copy/Paste are commands; neither warrants a bottom navigation bar. |
| ☑ [Navigation drawer](https://m3.material.io/components/navigation-drawer/overview) | Standard and modal drawers with destinations, sections, and counts. Full-resolution representative visual inspected. | Appropriate for many top-level destinations in larger app flows. Avoid in the bounded overlay; current inline lists provide the required choices. |
| ☑ [Navigation rail](https://m3.material.io/components/navigation-rail/overview) | Collapsed and expanded rails with selected destinations and a primary action. Full-resolution representative visual inspected. | Consumes valuable horizontal space. Could matter in a separate wide app redesign, not the current picker or unchanged launcher. |
| ☑ [Bottom sheets](https://m3.material.io/components/bottom-sheets/overview) | Actual photo-app sheet with contextual action grouping; previously reviewed. Full-resolution representative visual inspected. | Secondary controls can inform the contained source panel structure. The overlay already owns its surface; an additional modal bottom sheet would complicate dismissal and IME behavior. |
| ☑ [Side sheets](https://m3.material.io/components/side-sheets/overview) | Standard and modal side-sheet diagrams with headline, Back/Close, and scrim. Full-resolution representative visual inspected. | A useful large-window secondary-content pattern. The same title/back/content structure can inform the inline source pane without creating an actual side sheet in 400 dp. |
| ☑ [App bars](https://m3.material.io/components/app-bars/overview) | Four arrangements: search, compact headline/subtitle, and larger titles with actions. Full-resolution representative visual inspected. | Use the compact title/actions composition. Expanded titles would steal carousel height. Search replaces the start area and retains fixed trailing controls. |
| ☑ [Badges](https://m3.material.io/components/badges/overview) | Dot, single-digit, and large numeric notification badges. Full-resolution representative visual inspected. | These convey notifications/counts. Use the selected-item check treatment already chosen, rather than a red notification badge or unnecessary server/result counts. |
| ☑ [Cards](https://m3.material.io/components/cards/overview) | Elevated, filled, and outlined card surfaces. Full-resolution representative visual inspected. | Supports the panel/card hierarchy. Avoid nesting many independently elevated cards; use tonal image surfaces and a primary selection outline. |
| ☑ [Carousel](https://m3.material.io/components/carousel/overview) | Overview plus six concrete responsive layout/do/don't guideline diagrams; previously reviewed. Full-resolution representative visual inspected. | Keep existing centered hero, recognizable peeks, focus/selection separation, fitCenter artwork, and natural fling motion. Use the proven fallback when space is short. |
| ☑ [Checkbox](https://m3.material.io/components/checkbox/overview) | Unchecked, checked, and indeterminate visual states. Full-resolution representative visual inspected. | Correct for the existing multi-select Telegram app settings list. Source and delivery mode are single choices, so checkboxes would suggest the wrong behavior. |
| ☑ [Chips](https://m3.material.io/components/chips/overview) | Filter/input/assist patterns and the subtle-outline update; previously reviewed. Full-resolution representative visual inspected. | Keep existing emoji filters, checked active state, and the delivery preference chip. Do not turn albums or backend configuration into a permanent wall of chips. |
| ☑ [Dialogs](https://m3.material.io/components/dialogs/overview) | Basic rounded modal and full-screen form dialog examples. Full-resolution representative visual inspected. | Keep routine source switching and selection inline. Configuration belongs on the existing full native settings screen; the review gives no reason to add a confirmation dialog. |
| ☑ [Divider](https://m3.material.io/components/divider/overview) | A thumbnail/text list separated by thin horizontal rules. Full-resolution representative visual inspected. | One subtle boundary between media and dock is useful. Avoid a rule between every status line or chip; spacing and surface color can group related content. |
| ☑ [Lists](https://m3.material.io/components/lists/overview) | Grouped label/supporting/trailing-text rows and media rows with leading art. Full-resolution representative visual inspected. | Use for target apps and any eventual bounded album selection. A two-line collection/search summary is clearer than dense technical labels or tiny header subtitles. |
| ☑ [Menus](https://m3.material.io/components/menus/overview) | Vibrant contextual menu and nested submenu examples. Full-resolution representative visual inspected. | Useful for rare alternatives elsewhere, but it would add a popup here. Retain the explicit inline source panel and fixed command dock. |
| ☑ [Radio button](https://m3.material.io/components/radio-button/overview) | Selected/unselected circular choice states. Full-resolution representative visual inspected. | Good for one-of-many settings choices such as a longer album or search-mode list. The inline two-source pair can remain a connected group. |
| ☑ [Search](https://m3.material.io/components/search/overview) | Actual search field/result composition plus overview/guidelines; previously reviewed. Full-resolution representative visual inspected. | Keep the inline collapsible field and original keyboard. Preserve words, literal emoji, chip filter, and focus when leaving editing; do not expand into a full-screen search route. |
| ☑ [Sliders](https://m3.material.io/components/sliders/overview) | Single-value, centered, and range expressive tracks/handles. Full-resolution representative visual inspected. | Good for continuous values. Current discrete delays and source choices do not need a slider, and a media range filter would be new scope. |
| ☑ [Snackbar](https://m3.material.io/components/snackbar/overview) | Dark transient status surface with a trailing action. Full-resolution representative visual inspected. | Would overlap or compete with the fixed dock and disappear before some users can act. Keep copy confirmation and page Retry inline as requested. |
| ☑ [Switch](https://m3.material.io/components/switch/overview) | Off/on track and thumb states, including a checked thumb. Full-resolution representative visual inspected. | Appropriate for on/off settings like idle dimming or assistance. Sticker/Media are two named alternatives; retain the checked mode chip rather than a switch. |
| ☑ [Tabs](https://m3.material.io/components/tabs/overview) | Primary icon/text tabs and secondary text tabs with active underline. Full-resolution representative visual inspected. | Useful for sibling pages, but a permanent tab row would consume compact height. Use source choice inside its temporary pane; do not restyle the action dock as tabs. |
| ☑ [Text fields](https://m3.material.io/components/text-fields/overview) | Filled and outlined fields with persistent labels and supporting text. Full-resolution representative visual inspected. | Use native TextInputLayout/EditText for inline search and full settings URL/key fields. Keep field error/help text local to its field; emoji remains ordinary search input. |
| ☑ [Toolbars](https://m3.material.io/components/toolbars/overview) | Floating/docked command examples and native 1.14 implementation; previously reviewed. Full-resolution representative visual inspected. | The fixed dock is the appropriate contextual command container. Use explicit inset handling, preserve command reflow, and omit hide-on-scroll/overflow behaviors. |
| ☑ [Tooltips](https://m3.material.io/components/tooltips/overview) | Compact plain label and larger rich explanatory surface. Full-resolution representative visual inspected. | Plain tooltips help identify compact icon controls; contentDescription carries the accessible name. Long explanations should remain inline/settings instead of becoming another rich popup. |

Audit files: `/tmp/sao-m3-catalog-coverage-links.json` records the 36 live catalog URLs; `/tmp/sao-m3-remaining-coverage.json` records the 29 added assets, their official URLs and fetch statuses. All 29 added assets were opened with view_image. No repository changes were made.

The full review does not change the adopted design. It strengthens the choice to combine a compact app-bar composition, contextual docked toolbar, carousel, selected chips, and a contained list/selection pane. The unrelated navigation, picker, and modal families should remain outside this compact flow.

## Final implemented Android capture review

Viewed fresh actual captures `/tmp/sao-immich-picker-samples.png`, `/tmp/sao-immich-picker-source.png`, and `/tmp/sao-immich-picker-remote.png`. In these normal portrait states, no new control/text clipping blocker is visible. The source trigger remains distinct from Search/Close, the dock fits inside the panel, the collection summary/configuration control fit the inline source area, and the remote filename plus copy fallback status wraps visibly.

The checked Sticker preference alongside `Copied as media · sticker unavailable` correctly distinguishes requested preference from actual output. The selection mark stays visible and the preview remains contained. Disabled dock commands look disabled, while available Copy/Media commands remain legible.

The pre-fix source capture does not visibly differentiate checked/unselected source buttons; the parent is already applying checked-state semantic colors plus a selected icon, replacing the source-pane X with Back, and removing the redundant SAO prefix. These address the visible state/navigation issues; a fresh capture after that small correction can confirm the final state.

A small accessibility refinement remains advisable: use action descriptions such as `Send sticker` and `Share media` (optionally followed by target), while retaining the existing delivery-mode description on the preference chip. This needs no visible-label or behavioral change. The item preview and selected check badge are marked decorative, and the selected outer item receives isSelected; the source trigger has a source-specific description and tooltip in code.

`android layout` was attempted before this screenshot review, but the current live layout was already the launcher rather than the picker. This review therefore verifies the provided images and relevant accessibility attributes in code; it does not claim a runtime TalkBack pass or fresh validation of large text/IME/landscape. The parent's current Android tests and captures remain the evidence for those layouts. No additional UI scope is recommended before completing the change.

## Implemented in SAO

The evaluated design is implemented in Views/XML: inline source switching, the measured-width source button, a DockedToolbarLayout, tonal clipboard actions, semantic surfaces and a selected badge. The source group uses checked-state colors and a check icon; its Back action uses an arrow. Compact keyboard/large-font behavior remains covered by Android tests. See [Immich backend research](immich-api.md) for API scope and configuration.
