# NaIgre Reader Project Roadmap

## Project Summary

NaIgre is an Android reference reader focused on one thing: **finding information in large reference PDFs, notes, and campaign maps as quickly as possible**.

The app is designed primarily for large RPG books, manuals, rulebooks, technical references, and other PDFs where the user frequently jumps between distant sections.

It is intentionally **not** a general-purpose PDF editor.

Core philosophy:

* local-only
* PDF-first, with read-only Markdown notes and directory-backed image albums for maps and handouts
* read-only
* extremely fast navigation
* minimal UI
* no unnecessary animations
* bookmarks/search/tabs are first-class features
* optimize for real-world reference use, not document editing

The application is currently in active prototype development.

---

# Technology

## Platform

* Android
* Kotlin
* classic Android Views/custom drawing surface
* Gradle
* Linux-oriented development workflow
* Android SDK + adb
* no Windows-specific tooling required

## PDF Engine

MuPDF is the current rendering backend.

Reasons:

* handles very large PDFs well
* renders existing annotations
* exposes annotation payloads
* exposes PDF outlines/bookmarks
* supports direct page access
* suitable for custom rendering/navigation

MuPDF is isolated behind an internal PDF abstraction so the rest of the application should not depend heavily on MuPDF-specific APIs.

The project is open source, so MuPDF's AGPL licensing is acceptable.

---

# Current Functionality

The following features are already implemented and validated on real PDFs, including a roughly 1200-page heavily linked document.

## PDF loading

* add multiple local PDFs using Android's document picker
* persist a logical-book library, selected working set, and retained document permissions
* reopen the active PDF and cross-book tabs
* refresh, relink, or replace a logical book when its source changes
* recover an unavailable active source inline through retry, relink/rescan, or Library without blocking other reader controls
* large PDFs work
* graphics-heavy PDFs work
* no cloud storage integration
* recursively import one campaign folder, including nested PDFs and same-directory TXT TOCs

## Image albums

* every directory containing direct JPEG, PNG, or WebP children becomes one library album
* folder recursion discovers nested albums without duplicating their images into parent albums
* image filenames become stable navigation destinations with persistent visit ranking
* albums use the same table colors, tabs, page turns, zoom/pan, and temporary reference pane as PDFs
* memory-aware decoding retains source detail up to 12 MP independently of viewport size, with lower ceilings only for genuinely constrained devices
* image albums do not participate in PDF full-text indexing

## Markdown notes

* each `.md` or `.markdown` file is one library book
* notes render as native, continuously scrolling read-only text
* standard Markdown headings become stable navigation destinations
* GitHub-style pipe tables render as native table rows inside the note
* note filenames and headings participate in Navigate search
* note sections participate in the same persistent full-text index as PDF pages
* text results show the closest heading and open the matching section in a primary tab
* notes can also open in the temporary right/top reference pane
* per-tab note scroll positions are retained for the current app session
* headings cycle through collapsed, all descendant headings only, and fully expanded; revealing a child's text makes the next parent tap collapse the subtree
* headings without subheadings use a two-state fold; visibility is retained independently per tab/reference for the current session
* heading and text-result jumps expand the destination's ancestors; folded content remains searchable
* direct file import and recursive campaign-folder scanning both discover notes
* Obsidian/wiki links, editing, note graphs, and embedded local asset loading are deliberately excluded

## Rendering

* single-page layout
* two-page spread layout
* zero-pixel gutter between spread pages
* optional "Skip cover" spread alignment

Spread modes:

Normal:

```text
[1|2]
[3|4]
[5|6]
```

Skip cover:

```text
[1]
[2|3]
[4|5]
[6|7]
```

Bookmark/page jumps correctly snap to the spread containing the target page.

Example:

```text
jump to page 3
→ display pages 2–3
```

when Skip cover is enabled.

## Navigation

* direct page-number jump
* previous/next navigation
* internal PDF links opening in primary tabs
* edge-tap page turning
* swipe page turning
* configurable page-turn gesture mode
* pinch zoom
* pan when zoomed
* double-tap/reset zoom
* automatic, wide, and tall reader chrome layouts

Map albums retain substantially more source detail than the viewport, but bitmap zoom can still become soft at extreme zoom levels. Zoom-triggered rerendering or tiled decoding remains deliberately deferred.

## Bookmark navigation

Two bookmark sources are supported.

### Native PDF outline

Automatically loaded from the PDF.

Hierarchy is preserved for search context.

### External TXT TOC

Flat entries retain the existing format:

```text
Great Cavern::126
Temple Entrance::132
Western Galleries::149
```

Optional Markdown-style headings preserve section context without repeating long paths:

```text
# Cult Hideout::40
## Level 2::80
Great Cavern::126
```

Headings may omit `::page` when they are grouping-only. Plain entries inherit the active heading path, same-or-higher headings clear deeper context, and `---` resets the hierarchy.

Page numbers are user-facing and 1-based.

Imported TXT bookmarks are merged with the native PDF outline.

The TXT association should persist for the currently loaded book/session.

## Fuzzy search

Bookmark search is implemented in Kotlin.

Examples:

```text
gr cav
→ Great Cavern

tmp ent
→ Temple Entrance
```

Matching prioritizes:

* exact substrings
* word boundaries
* consecutive characters
* acronym-like matches
* subsequence matches

Search must remain extremely fast.

## Full-text search

* bookmark and document-text modes share the search overlay
* one horizontal `All / book` selector scopes either search mode to the table or one book
* the selected scope is session-only, remains stable across tab switches, and resets to `All` if that book leaves the table
* PDF pages and Markdown sections from all selected searchable books are stored in a persistent local FTS index
* one low-priority worker indexes selected books incrementally and resumes partial work
* indexed pages remain searchable while the rest of a large book is processed
* text results live persistently in the reference pane and retain the query while navigating
* the result pane repeats the scope selector and reruns the retained query when it changes
* result rows show a book-color cue, book name, physical page, closest bookmark context, and highlighted snippet
* tapping a result navigates the current primary tab; `+` creates or activates a result-specific tab
* the opened page highlights matching terms and focuses the first match
* plain query terms are ANDed, matching is case/diacritic insensitive for predictable speed, and the final term supports prefix matching
* search reports displayed and total match counts instead of silently applying a result cap
* the highest-ranked initial batch appears first, with additional globally ranked batches available through an explicit compact action
* incomplete indexes label their matches as results "so far" and show indexed-part coverage
* image-only PDFs require future OCR support and are not searchable yet

Search ranking learns from explicit bookmark selections:

* an empty query orders bookmarks by visit count, preserving outline order for ties
* a typed query adds a small, capped, logarithmic usage boost to fuzzy relevance
* exact and strong substring matches therefore remain dominant over weak fuzzy matches
* selecting a normal result, `+`, or `▥` counts as a visit
* page turns and ordinary tab switches do not count
* counts persist independently for each book on the table

Search UX:

* search button is prominent
* search remains accessible even when normal reader chrome is hidden
* the focused query input is the first row; a compact Navigate/Text switch and horizontally scrolling book scope share the toolbar below it
* search opens as a fast overlay
* no animations are necessary
* input is focused immediately
* results are shown as a list

Result behavior:

```text
tap result
→ navigate using current tab

tap +
→ open result in a new tab

tap ▥
→ open result in the temporary reference pane
```

A bookmark should never create duplicate tabs unnecessarily.

If a bookmark already has an existing anchored tab:

```text
select bookmark
→ switch to existing tab
```

The tab should remain at its current page rather than reset to the bookmark's original page.

---

# Internal PDF Links

Internal link rectangles and destinations are extracted during background page rendering and cached with the rendered page.

Tapping a link from either the primary or temporary-reference pane opens or activates a destination-specific primary tab. The temporary reference remains open.

Tab names use:

1. an exact bookmark destination title, using MuPDF's destination identity first and page/vertical coordinates second
2. otherwise the closest same-page bookmark above the target as `Bookmark · p123`
3. for page-only destinations, a strong match between the tapped link's visible label and a same- or adjacent-page bookmark title
4. otherwise the closest bookmark from an earlier page as `Bookmark · p123`, but only when the destination page has no bookmark candidates
5. otherwise `Page 123`

Visible link text is extracted only after an ambiguous link is tapped, so it adds no work to page rendering or scrolling. Weak or ambiguous bookmark matches use the visible label as `Label · p123`, with `Page 123` retained when no text can be extracted. Exact bookmark links reuse bookmark identity; other destinations use page-and-position identity. Existing physical-page tabs are upgraded in place when a better label becomes available. External links are ignored, and link navigation does not alter bookmark-search frequency.

---

# Tabs

Tabs represent **reference locations**, not browser-style document instances.

Each tab records both a book and a reference location. The foreground MuPDF document is switched lazily when a tab targets another book.

Each tab stores its own current page and navigation state.

Example:

```text
Great Cavern | Encounter Tables | Map
```

Tab names should normally show **only the bookmark name**.

Do not automatically rename a tab as the user turns nearby pages.

Example:

```text
Great Cavern
```

may start at page 412.

If the user flips to page 413 or 414, the tab still remains:

```text
Great Cavern
```

because the user is mentally still within that reference location.

Tabs are intentionally disposable.

Supported operations:

* switch tab
* close tab
* close other tabs

Not currently needed:

* rename tabs
* duplicate tabs
* pin tabs
* tab groups

Tabs use a restrained tint of the assigned book color as a compact visual cue; their labels remain readable neutral text, so color is never the only identifier. Both layouts use the same full-width horizontal primary-tab strip at the bottom. Menu remains at the upper-left, while global Search is permanently anchored at the lower-right above the tab strip's position even when chrome is hidden. The reference pane owns one compact upper-right control that combines its identity, page or result position, close action, and long-press actions; the primary pane owns its upper-right page indicator.

Selecting a bookmark already represented by a tab should switch to that tab instead of opening another.

---

# Color and Theme System

The interface uses a warm, Gruvbox-inspired neutral palette in Dark and Light variants. Dark is the default; Light and System-following modes are explicit reader-menu choices.

Color semantics are intentionally narrow:

* neutral tone, typography, and borders communicate ordinary interface state
* a book's assigned hue appears only on document identity surfaces: restrained tab tints and compact markers elsewhere
* subdued gold is reserved for text-search matches and page highlights
* red is reserved for errors and destructive states
* PDF page pixels are never transformed by the application theme

All programmatic UI resolves semantic palette roles rather than embedding screen-specific colors. Platform controls, dialogs, and system bars use matching neutral theme attributes.

---

# Temporary Reference Pane

A bookmark search result can be opened as a disposable reference while the normal tabbed reader remains in place.

The reference occupies the right half in Wide layout and the top half in Tall layout. It has its own page turns, direct page jump, zoom, and pan. Long-pressing its marker returns it to its opening location or closes it. Opening another reference replaces it, and closing it restores the configured single-page or spread layout.

The reader menu groups Library, Reading view, Interface, and current-book actions into focused submenus, leaving versioned Diagnostics directly accessible. Interface offers Automatic, Wide, and Tall reader profiles. Automatic resolves from the available window shape rather than locking device orientation. The setting controls reference placement; primary tabs remain at the bottom in every layout.

It is intentionally one pane rather than a second tab workspace. It has no adjustable divider, multiple-reference UI, history, drag-and-drop, or side-swapping, and it is not persisted across app restarts.

Reference rendering uses the secondary MuPDF document/executor. While the pane is open, requested reference pages take priority over speculative prefetch.

---

# Rendering Performance

Page rendering is asynchronous.

There are separate rendering paths for foreground navigation and speculative prefetch.

## Cache

Rendered pages use an LRU bitmap cache.

Goals:

* returning to previously viewed tabs should be effectively instant
* cached page switches should not rerender unnecessarily

## Prefetch

Neighboring pages/spreads are rendered in the background.

Spread prefetch warms **both pages**.

Foreground rendering and background prefetch use separate MuPDF document/executor paths so speculative work cannot block urgent navigation.

## Atomic spread rendering

When switching spreads:

* keep the old complete spread visible
* render both pages of the new spread
* swap only when the new spread is complete

Never visibly transition through:

```text
old spread
→ single left page
→ complete spread
```

This avoids ugly partial spread rendering.

---

# Reader Chrome

Reader UI should remain minimal.

Preferred layout:

```text
┌─────────────────────────────────────────┐
│ ⋮  Book Name                    Search │
│                                         │
│                                         │
│                 PDF                     │
│                                         │
│                                         │
│                              412 / 1200 │
└─────────────────────────────────────────┘
```

Tabs should remain compact.

Normal center tap:

```text
toggle reader chrome
```

Chrome hiding/showing must be effectively instant.

Do not resize or rerender the PDF surface when toggling chrome.

Chrome should be implemented as overlays over a stable reader surface.

Android system bars should not be toggled every time reader chrome changes.

Full immersive mode should remain a separate explicit mode. While enabled, NaIgre may remain
visible above Android's lock screen without dismissing the keyguard; leaving the reader still
requires the normal device unlock.

The search action must remain reachable when chrome is hidden.

Possible behavior:

```text
tap upper-right area
→ open search
```

or keep the search affordance visible independently.

---

# Annotations

Annotations are strictly read-only.

The application must never modify PDFs.

Existing annotation appearances should render normally.

When tapping an annotation:

If it contains text:

```text
show only the text payload
```

Example:

```text
The eastern door is trapped unless lever 14 is pulled.
```

Do not show:

* author
* date
* annotation type
* subject
* technical metadata

If the annotation contains no text:

```text
do nothing
```

The old annotation metadata inspector can remain hidden under diagnostics/development tools but should not be part of normal reader UX.

Opening an annotation popup must not cause Android navigation/system buttons to unexpectedly appear when immersive mode is active.

---

# Persistence

The app should restore useful reading state across restarts.

Persist:

* known logical books and their retained document URIs
* the selected working set that currently forms the table
* imported TXT TOC association per book
* native outline index per book
* assigned book color
* tabs
* active tab
* page per tab
* spread mode
* Skip cover preference
* page-turn gesture preference

Image zoom and position are intentionally session-only during prototyping. They are retained per tab and image while the app process remains alive, but are not restored after a full restart.

This is currently a prototype.

Do **not** implement migration logic for old prototype schemas unless explicitly requested.

There is currently one user and no public release compatibility requirement.

Prefer deleting/resetting obsolete prototype state over adding complex migration code.

---

# Product Principles

## Optimize for speed

Primary interaction:

```text
tap search
→ type 3–6 characters
→ tap result
→ information visible
```

Every design decision should preserve or improve this flow.

Avoid:

* unnecessary animations
* delays
* modal navigation steps
* redundant confirmation dialogs
* visually heavy interfaces
* hidden work that blocks foreground navigation

## Prefer explicit simple UX

Use obvious controls where they improve speed.

Example:

```text
+ button on a search result
```

is preferable to relying entirely on an undiscoverable gesture for creating a new tab.

Gestures are acceptable when they are obvious or configurable.

## Tabs are disposable

Search is usually faster than manually organizing tabs.

Tab management should remain minimal.

## PDF remains untouched

Never write into the PDF.

No:

* annotation creation
* highlighting
* editing
* form modification
* bookmark editing
* saving altered PDFs

---

# Explicit Non-Goals

The following are currently out of scope.

Do not implement unless requested.

* PDF editing
* creating annotations
* creating highlights
* cloud sync
* cloud storage integration
* accounts
* online services
* collaborative features
* document conversion
* non-PDF formats
* complex library management
* thumbnails for every page
* fancy transitions
* heavy animations
* annotation metadata UI
* OCR
* text editing
* PDF form editing

---

# Phase Status

## Phase 1 — PDF Engine Proof of Concept

Complete.

Validated:

* open PDF
* render pages
* jump to arbitrary page
* large PDFs
* landscape spreads
* existing annotation rendering
* annotation text extraction

---

## Phase 2 — Bookmark Navigation + Tabs

Complete.

Implemented:

* native PDF outlines
* external flat and Markdown-hierarchical `title::page` TXT TOC
* merged bookmark index
* fuzzy bookmark search
* tap-to-jump
* location tabs
* long-press/new-tab behavior
* tab switching
* tab closing

Validated on real large PDFs.

---

## Phase 3 — Rendering Performance + Reader Interaction

Complete.

Implemented:

* bitmap LRU cache
* background prefetch
* separate foreground/prefetch rendering paths
* stale render cancellation
* fast cached tab switching
* pinch zoom
* pan
* direct annotation hit testing
* session persistence
* custom reader surface

---

## Phase 4 — Daily Driver UX

Complete / undergoing real-world testing.

Implemented:

* simplified reader chrome
* prominent search
* fast search overlay
* stable bookmark-named tabs
* bookmark-tab deduplication
* explicit new-tab action
* page-turn gestures
* close-other-tabs cleanup
* text-only annotation popups
* improved persistence
* instant chrome toggling architecture

---

## Phase 4.1 — Spread + UX Fixes

Complete.

Implemented:

* zero-pixel spread gutter
* persistent search access
* annotation popup immersive-mode fix
* atomic spread rendering
* full-spread prefetch
* Skip cover spread alignment

Current build is now intended for real-world usage testing.

---

## Phase 4.2 — Temporary References

Complete / awaiting tablet validation.

Implemented:

* explicit `▥` action on bookmark results
* one disposable reference pane, right in Wide layout and above in Tall layout
* automatic/wide/tall layout profiles coordinating reference placement with one shared bottom tab strip
* independent reference page turns, page jump, zoom, and pan
* long-press tab/reference actions that return to their opening page or image
* replacement and close behavior without a second tab bar
* primary spread restoration after closing
* session-only reference state
* reference rendering priority over speculative prefetch

---

## Phase 4.3 — Usage-Ranked Bookmark Search

Complete / awaiting tablet validation.

Implemented:

* persistent per-bookmark visit counters
* frequency-first ordering for an empty search
* capped logarithmic usage boost for fuzzy queries
* visit recording across normal, new-tab, and alongside result actions
* book-scoped counter identities (extended for the multi-PDF table in Phase 5)

---

## Phase 4.4 — Internal PDF Links

Complete / awaiting tablet validation.

Implemented:

* background extraction and resolution of internal PDF links
* transformed link hit testing across zoomed, panned, and spread pages
* destination-specific primary tabs from either reader pane
* coordinate-aware bookmark naming with a conservative physical-page fallback
* on-demand visible-label matching for PDFs whose links expose only a target page
* destination deduplication without conflating separate spots on the same page
* deliberately ignored external links

---

## Phase 5 — Multiple PDFs on the Table

Complete / awaiting tablet validation.

Implemented:

* persistent multi-PDF table backed by Android document-provider permissions
* independently persisted native outlines and optional TXT TOCs
* federated bookmark search with book names and color dots
* cross-book tabs and temporary references
* restrained book-color surface tints on tabs and the combined in-pane reference control
* curated automatic colors with manual color selection
* lazy foreground/secondary MuPDF switching so memory use does not grow with table size
* relink and remove-book workflows
* book-scoped visit ranking and rendered-page cache identities
* upper-left reader menu and square-corner search overlay

---

## Phase 6 — Persistent Library + On-the-fly Table

Complete / awaiting tablet validation.

Implemented:

* full-screen grid of known PDFs with persistent selected/unselected state
* logical book identity independent from the current PDF URI or file hash
* non-destructive **Take off table** and explicit **Forget book and history** actions
* active bookmark search scoped to selected books
* lightweight library catalog plus separately persisted per-book outline/TXT indexes
* unloaded bookmark indexes for deselected books
* page-independent hierarchical visit identities
* direct use of original document URIs without private PDF copies
* automatic source-stamp checks on open/resume and authoritative explicit PDF refresh
* shared source-generation identities for page cache, outline refresh, and full-text index compatibility
* same-page-count PDF replacements cannot reuse stale rendered pages or extracted text
* relink/replacement that retains TOC, color, UUID, and visit history
* same-URI restoration and confirmation before merging a same-filename replacement
* no named-table layer; the selected set is the single current table
* persistent overlapping library tags with All, On-table, and Untagged filters
* per-item and bulk tag membership editing, plus batched add/replace-table actions
* distinct delete-tag and forget-tagged-items workflows with shared-tag warnings
* intentionally clean alpha schema without legacy book-table migration

---

## Phase 6.1 — Campaign Folder Import

Complete / awaiting tablet validation.

Implemented:

* persisted Storage Access Framework folder grants without broad storage permissions
* direct-child PDF discovery for a campaign directory
* automatic exact-stem TXT TOC pairing such as `downtime.pdf` → `downtime.txt`
* idempotent rescans that add new books and retain existing logical identities and selection state
* source-revision refresh for changed PDFs and source-metadata refresh for matching TXT files
* conservative collision and parse-failure reporting without destructive replacement
* no automatic forgetting when a previously imported source disappears

---

# Current Priority

Prepare the first public beta (`26.09.0`) with the existing reader feature set.

The version scheme is `YY.MM.PATCH`, with a monthly release counter starting at zero
and a separately increasing Android version code. Public builds retain
`wund0r.naigre.reader`; debug and device-verification builds install independently.
Use [RELEASING.md](RELEASING.md) for private signing setup, verified candidate
preparation and the manual publication checklist. There is no automatic publisher
or migration from the old debug-signed prototype.

Complete the signed-build/device/update acceptance and independent signing-key
backup before publication. Use [TESTING.md](TESTING.md) for current and historical
device checklists; unperformed checks remain pending.

Do not immediately add major new features.

Use the application in actual reference/RPG sessions and collect friction points.

Focus on:

* search latency
* tab usefulness
* page-turn interaction
* chrome behavior
* spread behavior
* annotation interaction
* cache hit performance
* situations where navigation feels slower than expected

Prioritize changes based on actual usage rather than feature completeness.

---

# Likely Phase 5 — Full-Text Search

This is the strongest candidate for the next major feature.

MuPDF can provide page text/search results.

Proposed search interface:

```text
Bookmarks | Text
```

Bookmark mode remains the default.

Full-text mode:

```text
query: ring of petrification
```

Results should be shown as a list:

```text
p. 184
"...the ring of petrification..."

p. 417
"...immune to the ring of petrification..."

p. 903
"...Ring of Petrification..."
```

Requirements:

* list all useful matches
* allow repeatedly jumping between results
* do not use a simple "Find next" workflow
* results should be fast to revisit
* support opening a result in the current tab
* support opening a result in a new tab
* preserve bookmark-tab semantics

Potential optimization:

Build/cache a local text index per PDF if direct repeated MuPDF search becomes too slow.

Everything must remain local.

---

# Future Feature — Navigation History

Likely valuable for heavily cross-linked PDFs.

Concept:

```text
page 412
→ search
page 931
→ follow link
page 177
→ Back
page 931
→ Back
page 412
```

A lightweight per-tab back/forward location history may become as useful as tabs.

Do not implement until real usage confirms the need.

---

# Future Feature — Continuous Vertical Scrolling

Potentially useful, especially together with a page-position slider.

Concept:

```text
slider
→ jump approximately to page 700
→ vertically scroll through nearby pages
→ locate exact content
```

Must use virtualization.

Never render the entire PDF simultaneously.

Potential approach:

* RecyclerView/custom virtual layout
* visible page window
* low-resolution fast render while flinging
* cancel stale renders aggressively
* high-quality render after scroll settles
* reuse current bitmap cache

Performance is the primary concern.

Do not implement casually inside another phase.

Treat continuous scrolling as its own focused project.

---

# Future Feature — Higher-Resolution Zoom

Current bitmap zoom becomes soft at high zoom levels.

This is acceptable right now.

Possible future approach:

* detect settled zoom level
* rerender visible page region/page at higher resolution
* replace cached lower-resolution bitmap
* potentially avoid intermediate PNG/Bitmap conversion if possible

Only prioritize this if real usage shows current zoom quality is inadequate.

---

# Future Feature — Multiple Books / Book Sessions

Eventually support multiple independent book sessions.

Internal model should trend toward:

```text
BookSession
├── PDF
├── external TOC
├── bookmark index
├── tabs
├── active tab
├── reader state
└── preferences
```

Possible future UX:

```text
Arden Vul
Dolmenwood
Rules Cyclopedia
```

Each book should retain its own tabs and positions.

This does not need to become a complex library manager.

Think of it as several persistent reference workspaces.

Not currently a priority.

---

# Development Guidelines for Codex

When changing the project:

1. Preserve existing validated behavior unless the task explicitly changes it.
2. Prefer small targeted changes over large rewrites.
3. Keep MuPDF-specific logic isolated inside the PDF layer.
4. Keep rendering work off the UI thread.
5. Never allow speculative rendering to block foreground navigation.
6. Be careful with Bitmap ownership and recycling.
7. Avoid rerendering when cached content is sufficient.
8. Avoid rebuilding tab UI on ordinary page turns.
9. Maintain stable bookmark/tab identity independently from current page.
10. Treat spreads atomically.
11. Keep all page-number conversions explicit:

    * UI/TXT page numbers: 1-based
    * internal PDF page indices: 0-based
12. Preserve read-only behavior.
13. Do not add network permissions or network features.
14. Do not add compatibility/migration complexity during prototype development unless explicitly requested.
15. Optimize for interaction latency before visual polish.
16. Avoid animations unless they materially improve usability.
17. Use real large PDFs as the final performance benchmark.

---

# Current Product Goal

The app should feel like a command palette attached directly to a PDF.

The target experience is:

```text
need information
→ search
→ a few characters
→ result
→ page
```

in as little time as possible.

Everything else exists to make that loop faster or easier.
