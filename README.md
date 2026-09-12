# NaIgre Reader

A read-only Android reference reader optimized for **very fast navigation** across large local PDFs, Markdown notes, and map-image albums.

NaIgre uses MuPDF for PDFs, Markwon for native Markdown rendering, and Android's bounded image decoder for map albums.

## v0.29.4 changes

- Hidden-chrome Search retains a 60%-opaque neutral button surface and an 80%-opaque icon, keeping the permanent one-tap target legible over both light documents and dark maps.

## v0.29.3 changes

- The persistent Search icon now remains at 55% opacity while reader chrome is hidden, improving visibility over white document pages without restoring its floating button surface.

## v0.29.2 changes

- Search remains a fixed one-tap target when reader chrome is hidden, but sheds its button surface and dims its icon to 35% opacity. Restoring chrome restores its full treatment instantly without movement or animation.

## v0.29.1 changes

- Library tag-filter buttons retain 12dp of horizontal text padding after their custom background is applied.

## v0.29.0 changes

- Library items can carry multiple persistent tags. The Library's neutral filter strip includes **All**, **On table**, every named tag, and the virtual **Untagged** group.
- Item cards show compact tag summaries. **Tags…** in each item's action menu edits membership and can create a new tag; long-pressing a named tag exposes bulk membership, rename, deletion, and table actions.
- A tag can replace the current table or add its items to it in one batched update. Source metadata, visit history, and off-table full-text indexes remain attached to each logical item.
- **Forget tagged items…** removes the tag and all of its items after confirmation. The warning explicitly lists affected filenames that also belong to other tags; source files are never deleted.

## v0.28.2 changes

- The full-screen Library now respects status-bar, navigation-bar, and display-cutout insets, keeping its header actions fully visible on edge-to-edge Android windows.

## v0.28.1 changes

- Menu, Search, and both floating page/reference indicators now share a 48dp touch height with the same inset 44dp visible surface.
- A lone primary tab uses balanced left and right label padding when its unavailable close action is omitted.

## v0.28.0 changes

- External TXT TOCs can use Markdown-style headings to describe hierarchy while retaining the compact `Title::page` destination syntax.
- Headings with a page are both navigable entries and parents; headings without a page are grouping-only. Plain destinations inherit the active heading path, and `---` resets it.
- Hierarchical TXT paths participate in navigation search, bookmark identity and section context. Full-text results show the parent breadcrumb alongside the book name.

## v0.27.0 changes

- Wide and Tall now share one book-tinted, horizontally scrolling primary tab strip at the bottom. Tabs keep their direct close actions and no longer move when the reader layout changes.
- Search is permanently anchored at the lower-right immediately above the tab strip's position. Hiding tabs does not move or hide Search; Menu remains at the upper-left with the rest of the reader chrome.
- Tall references now occupy the upper half with the primary reader below, keeping the primary workspace adjacent to its tabs. Wide references remain on the right.
- The primary page indicator lives at the upper-right of the primary pane, while the combined reference identity/page/close control lives at the upper-right of the reference pane. Pane-aware insets keep Markdown and full-text results clear of their controls.

## v0.26.0 changes

- The separate temporary-reference tab/chip is gone. A compact control inside the reference pane now combines its book-colored identity, current page or search-result count, and a full-size close action.
- Tapping the page portion still opens exact page navigation. Long-pressing either the title or page portion retains the reference actions, including returning to its opening location.
- PDF, image, Markdown, and full-text-search references share the same control. Search results keep it visible when document chrome is hidden, no longer duplicate its close action in their header, and reserve space for it in both Wide and Tall layouts.

## v0.25.0 changes

- Tall layout replaces the wide left tab rail with a full-width horizontal strip at the bottom, restoring the entire phone width to the primary document while retaining direct close buttons on every tab.
- Primary tabs in both layouts use restrained book-colored surface tints instead of leading color dots. Active tabs use a stronger tint and bold text, while labels receive bounded, end-ellipsized space.
- In Tall layout, Menu remains at the upper-left and a page/image reference label lives inside the reference pane. Page indicators, full-text results, and Markdown content stay clear of the bottom tab strip and system navigation inset.

## v0.24.2 changes

- The shared **All** search-scope chip now has a proper compact control width in both the Search overlay and persistent full-text reference pane.

## v0.24.1 changes

- Search modes now use a solid accent for the active mode and a neutral, accent-outlined inactive mode, preserving readable contrast in both themes.
- Alternating result bands extend cleanly behind edge-aligned, transparent 48dp row actions instead of peeking around inset button backgrounds.
- Progressive result loading is a consistent **Show more** text action with a full touch target. Full-text result controls, book filters, and header actions now share the same 48dp slot and 44dp visible-control rhythm.

## v0.24.0 changes

- Search keeps one draft query while switching between **Navigate** and **Full text**. Opening Search normally still starts empty, while **Edit** preloads the active full-text query.
- The compact mode selector now uses the app's muted accent treatment and is visually separated from neutral book-scope chips without becoming taller.
- Navigate and full-text results use subtle alternating neutral row backgrounds, making each row's `+` and `▥` actions easier to track across wide panes.
- The full-text result header leaves a clear gap above the book selector and includes its own compact close action for use while reader tabs are hidden.

## v0.23.0 changes

- The launcher label now uses the settled **NaIgre** product name.
- Android backup is disabled because the library stores references to user-selected files and persisted document permissions; restoring only NaIgre's private metadata on another device would produce a library of unavailable sources.
- The release build keeps minification and resource shrinking explicitly disabled during prototyping, avoiding an unmeasured R8 change in the PDF and Markdown paths. Release lint remains mandatory.
- The V1 prototype checkpoint and final tablet acceptance list are now recorded in the roadmap.

## v0.22.0 changes

- Reader tab restoration, persistent tab serialization, and bookmark visit storage now live in a dedicated session repository instead of `MainActivity`.
- Android document-provider metadata lookup and its provider-compatibility fallback are centralized, along with source-stamp comparison used by import, refresh, relink, and resume checks.
- The full-text SQLite helper is now process-owned, preventing activity recreation from abandoning an open database connection or racing a replacement activity during shutdown.
- The persisted preference keys and tab JSON format are unchanged, preserving existing tabs and bookmark visit history through this internal restructuring.

## v0.21.0 changes

- An unavailable active source now shows a compact inline recovery panel with **Retry**, **Relink**, and **Library** instead of a dead-end message and blocking open-error dialog.
- Image albums use **Rescan** in the same panel. Restored folder sources clear their unavailable state during a successful campaign-folder scan.
- Markdown notes can now be relinked or replaced like PDFs while retaining their logical book identity, color, visits, tabs, headings, and text-search lifecycle.
- Retry and relink continue through the existing source-revision path, so restored or replaced documents cannot reuse stale rendered pages or extracted text.

## v0.20.0 changes

- Navigate and full-text search no longer hide their result limits. Each reports the exact number of matches and says when only the highest-ranked batch is currently shown.
- The initial batches remain bounded at 100 navigation destinations and 160 text matches for fast first results. A compact **More** action progressively exposes the remaining globally ranked matches without destabilizing the list.
- Full-text status distinguishes an active query from incomplete background indexing, including document scope and indexed-part coverage. Partial-index matches are explicitly labeled as results "so far."
- Full-text ranking keeps only the requested best candidates in memory while still counting every match. Snippets are loaded in safe chunks as more results are requested.
- Diagnostics records the latest full-text query duration, displayed/total match count, indexed coverage, and document count.

## v0.19.0 changes

- Every library item now has a source-generation identity shared by rendered-page cache keys and full-text index state. A cached page or extracted text row from an older PDF revision can no longer be accepted merely because the book UUID and page count still match.
- Changed PDFs invalidate their page cache, native outline, and full-text index together across resume detection, tab opening, campaign-folder rescans, same-file re-adds, manual refresh, and relinking/replacement.
- **Refresh PDF** is authoritative even when a document provider reports unchanged or unavailable size/timestamp metadata: it advances a persisted revision token and forces text extraction while retaining the logical book UUID, color, visits, TOC, and tabs.
- Search excludes stale-revision rows immediately while the current revision is being indexed. The text-index database upgrades to revision-aware schema v3, so selected PDFs and notes rebuild their text indexes once after this update.
- Diagnostics reports the active source revision, whether its text index is current, and the most recent source-refresh reason.

## v0.18.0 changes

- Map albums now decode independently of viewport size. A phone with a normal Android memory allowance receives the same detailed image as a tablet instead of a smaller bitmap merely because its screen is narrower.
- Normal devices retain source resolution up to a 12 MP ceiling; Android low-RAM devices and unusually small app heaps use a 6–8 MP safeguard. Extremely long image dimensions remain bounded for reliable Canvas drawing.
- Primary and reference panes share one cached album bitmap for the same image even at different pane widths. Android memory-pressure callbacks release non-visible cached pages without recycling anything still displayed.
- Diagnostics reports the device memory class, selected image ceiling, cache capacity, and decoded dimensions of visible map images.

## v0.17.0 changes

- **Fullscreen above lock screen** keeps the active NaIgre reader visible and interactive after the device locks, while Android's keyguard remains active behind it. Leaving NaIgre still requires the normal device unlock.
- Waking the device restores immersive system-bar hiding. It does not wake the screen automatically, dismiss the keyguard, launch NaIgre after its process has stopped, or require a special permission.
- An unchanged Markdown note remains visible while its resume-time freshness check runs in the background instead of being reopened immediately.

## v0.16.1 changes

- Markdown notes now render GitHub-style pipe tables, including header rows, alignment markers, cell borders, and inline formatting inside cells. Table-cell contents also participate in full-text search; existing text indexes rebuild automatically once after updating.

## v0.16.0 changes

- Markdown files (`.md` and `.markdown`) are first-class library books. **Add file** accepts either a PDF or a note, and recursive campaign-folder scans discover both.
- Notes render read-only as continuous native Android text rather than pages or a WebView. Each note keeps its scroll location per tab for the current app session.
- Markdown headings become color-coded Navigate-search destinations and can open in the current tab, a new tab, or the temporary reference pane. A note's filename is searchable as its root destination.
- Full-text indexing and the persistent reference-pane result list now search selected PDFs and Markdown notes together. Note results show heading context, highlight the match, and jump directly to the matching section.
- Note changes are fingerprinted so a folder rescan or **Refresh note** rebuilds headings and text only when the content actually changes. Standard Markdown is supported; Obsidian/wiki links, editing, embedded asset loading, and note graphs are intentionally out of scope.

## v0.15.4 changes

- Search now attaches directly at its final full-width top position with explicit zero-duration enter/exit animations, eliminating the platform dialog's center-origin pop and giving immediate visual feedback.

## v0.15.3 changes

- Wide chrome now gives its aligned tabs, Menu, and Search surfaces equal vertical breathing room, using 8dp above and below the control row.

## v0.15.2 changes

- Menu and Search retain accessible 48dp touch targets but draw 44dp-high surfaces aligned with the tab strip, removing the subtle height mismatch without making either action harder to hit.

## v0.15.1 changes

- Page indicators stay limited to the current page or image; book identity remains available through tab colors and the Current book menu instead of occupying permanent reader space.

## v0.15.0 changes

- The reader menu is grouped into Library, Reading view, Interface, and current-book submenus, while versioned Diagnostics remains directly accessible. Current PDF maintenance no longer competes with ordinary view settings in one flat list.
- Wide layout combines the menu, horizontally scrolling tabs, temporary-reference marker, and Search into one compact top row; the old filename-only row is gone. The temporary reference remains on the right.

## v0.14.0 changes

- Reader layout is now a single three-state setting under **⋮ → Layout**: **Automatic**, **Wide**, or **Tall**.
- Wide layout keeps the horizontally scrolling tab strip at the top and places the temporary reference on the right.
- Tall layout removes the redundant full-width header, moves `⋮` and primary tabs into a wider left rail, and places the temporary reference below; its marker stays pinned at the bottom of the rail instead of consuming tab-scroll space. Search remains independently available at the upper-right.
- The Tall rail background now reaches the top content edge while its controls respect system insets; `⋮` and Search share the same 48dp square treatment, and both tab layouts leave explicit space below the menu control.
- Primary and temporary-reference tabs now share a 44dp minimum height. Menu, Search, and both lower-right page indicators use a consistent 8dp outer-edge gap.
- Search once again places its focused query field first. Navigate/Text are compact fixed-width tabs beside the horizontally scrolling book selector, recovering a full row of vertical result space without shrinking their touch targets.
- Automatic chooses Wide or Tall from the available window shape, including rotation and resized windows, without locking Android's device orientation.
- The tall rail hides with the rest of the reader chrome. Reference-marker long press is again limited to returning to its opening location or closing it.

## v0.13.1 changes

- Fixed full-text searches remaining on **Searching…** while stale searches accumulated behind indexing progress updates.
- Full-text requests are now coalesced so only the newest queued query runs, and completed indexes no longer trigger redundant searches or database write transactions.
- Full-text search is always case-insensitive for predictable speed. Smartcase remains available for the much smaller in-memory bookmark/filename search.

## v0.13.0 changes

- Long-press the temporary reference marker to move the pane between the right and bottom halves of the screen; the chosen placement is remembered.
- Both layouts preserve each visible pane's normalized zoom and position while resizing, and bottom placement renders at the full screen width.
- Long-press any primary tab to return to the page/image where that tab started, in addition to the existing tab-closing actions.
- Long-press a page/image reference marker to return it to its opening location; coordinate-backed PDF bookmarks return to their vertical destination as well as their page.

## v0.12.1 changes

- Restored tab taps after the flat-control refactor while retaining tab long-press actions.
- Added consistent visual gaps between adjacent native buttons and explicit spacing between the search mode and book-selector rows.

## v0.12.0 changes

- Buttons and interactive surfaces now share a flat, neutral visual system with restrained 2dp corners, consistent typography, and no elevation.
- Native buttons, dialog actions, tabs, search controls, book filters, page indicators, and library cards use matching pressed-state feedback.
- Active and inactive controls are distinguished with neutral surface tones and text weight, leaving color reserved for book identity markers and search feedback.
- Compact icon actions use consistent 48dp touch targets, while book-color identity dots remain circular.

## v0.11.1 changes

- The top reader chrome is now 70% opaque, allowing more of the page beneath it to remain visible while keeping tab chips solid and distinct.

## v0.11.0 changes

- Search opens with an empty input every time. Navigate/Text drafts survive mode switches only while that overlay remains open.
- Bookmark and filename search use smartcase: lowercase-only queries ignore case, while any uppercase letter makes them case-sensitive. Full-text search remains case-insensitive for speed.
- A visible `Aa` indicator explains when bookmark/filename smartcase has enabled case-sensitive matching.
- Image albums retain zoom and normalized position independently for every touched image in every tab during the current app session; the reference pane keeps its own image positions.
- Image viewports survive tab changes, album navigation, split-pane changes, orientation changes, and folder rescans, while **Reset zoom** clears the current saved positions.

## v0.10.0 changes

- **Add folder** now recursively scans one campaign directory: PDFs at any depth become books, matching same-directory TXT files become TOCs, and every directory containing JPEG, PNG, or WebP files becomes one image album.
- Mixed directories work as expected: their PDFs remain separate books while their direct image children form an album. Nested directories form their own albums, so images are never duplicated into ancestor albums.
- Every album image is a stable, filename-searchable navigation destination and can open in the current tab, a new tab, or the temporary right-hand reference pane.
- Search's **Navigate** mode combines bookmarks, image filenames, album roots, and filename entries for bookmarkless PDFs without letting synthetic file entries affect PDF link naming.
- Albums retain colors, selection, visit counts, and image-anchored tabs across rescans even when added or removed files change page positions.
- Images open fit-to-screen and decode at a bounded, memory-aware resolution independent of viewport size, keeping large maps useful on both phones and tablets without loading unrestricted source bitmaps into memory.
- Image albums are excluded from PDF full-text indexing and PDF-only TOC, annotation, spread, refresh, relink, and indexing actions.

## v0.9.0 changes

- **Add folder** grants persistent access to a campaign directory and imports its PDFs into the Library.
- Newly discovered books join the current table; rescans preserve the selected/off-table state of books already known to NaIgre.
- Exact, case-insensitive filename pairs such as `downtime.pdf` and `downtime.txt` automatically import the TXT TOC.
- Library folders persist, and **Rescan folders** discovers new PDFs, refreshes changed sources, and reimports changed matching TOCs.
- Folder updates preserve logical book UUIDs, colors, visit history, and tabs. Missing files are never forgotten automatically.
- Ambiguous filename collisions and malformed TOCs are skipped and reported without overwriting working metadata.

## v0.8.1 changes

- Fixed an Android 12 startup crash caused by requesting the system-bar insets controller before the window decor view existed.

## v0.8.0 changes

- NaIgre now has **Dark**, **Light**, and **System** themes under the reader menu; Dark remains the default.
- Both themes use a warm, restrained, Gruvbox-inspired neutral palette across the reader, search, library, dialogs, controls, and system bars.
- Book hues are now identity markers rather than general surface fills: tabs, references, results, selectors, and library cards retain consistent book-colored dots on neutral surfaces.
- Search matches retain a subdued gold treatment, while red is reserved for errors and destructive states.
- PDF pages are rendered without theme-driven recoloring.

## v0.7.1 changes

- A single horizontal **All / book** selector now scopes both bookmark and full-text search without changing the table.
- The active book is placed first for quick access, while the chosen scope remains stable when switching tabs.
- Persistent text-search results carry the same selector in the reference pane and rerun immediately when its scope changes.
- Removing the scoped book from the table safely resets search to **All**; the scope remains session-only.

## v0.7.0 changes

- Search now switches between **Bookmarks** and full PDF **Text** in the same overlay.
- Full-text search covers every selected library book and places its persistent, color-coded result list in the temporary reference pane.
- Tapping a text result navigates the active primary tab; `+` opens it in a new tab without dismissing the result list.
- Search snippets emphasize matching terms and show the closest page-level bookmark as section context.
- Selected books are indexed incrementally on one low-priority worker. Partial indexes are searchable immediately and resume after interruption.
- Extracted text is retained by logical book while it is off the table; forgetting a book removes its text index.
- Opening a result highlights matching terms and focuses their first occurrence on the rendered page.
- A changed page count or replacement PDF rebuilds its index. **Rebuild text index** is available for same-page-count content changes.

## v0.6.0 changes

- A full-screen **Library** grid keeps every known PDF as a persistent logical book.
- Selecting and deselecting library tiles forms the current working set; bookmark search covers selected books only.
- Taking a book off the table retains its PDF metadata, TXT TOC, color, and bookmark visit history. Only **Forget book and history** deletes them.
- Library summaries and per-book bookmark indexes are stored separately, so archived outlines are not parsed into memory at startup.
- Bookmark visit identities use normalized hierarchical paths rather than physical page numbers, preserving ranking when revised PDFs move sections.
- PDFs remain in their original location. Size/modification stamps trigger automatic outline and page-count refresh while **Refresh PDF** handles providers without reliable stamps.
- Re-adding a known URI restores that logical book; matching filenames prompt before an annotated replacement is associated with existing history.
- Named tables are deliberately omitted. The selected library books are one persisted, on-the-fly table.
- This alpha storage format starts a new library; the old `book-table.json` and visit-counter format are not migrated.

## v0.5.4 changes

- Page-only PDF links now read their visible label on demand and use strong same/adjacent-page title matches to name the destination tab.
- Link-label extraction runs only after an ambiguous link is tapped, keeping it out of scrolling and rendering work.
- Existing physical-page link tabs are upgraded in place when a better label becomes available; the visible link label is the final naming fallback.
- Diagnostics text can be selected and copied.

## v0.5.3 changes

- Internal links and outline entries retain their canonical MuPDF destination identifiers; exact shared destinations are matched before coordinate heuristics.
- Diagnostics displays the installed version name/code and details from the most recently followed link.
- **Books on table** now displays the actual PDF filename with explicit light-dialog text colors.

## v0.5.2 changes

- Restored MuPDF's path-based opening behavior for persisted Android document descriptors, fixing disagreement between outline and link destination coordinates.
- Existing table entries receive a one-time outline re-index when first opened after upgrading.

## v0.5.1 changes

- Link tab naming no longer falls back to a previous-page bookmark when the destination page has its own bookmark candidates.
- Small producer-specific offsets between a link target and its same-page outline destination are tolerated; uncertain matches use the physical page name instead.

## v0.5.0 changes

- Multiple PDFs can live on a persistent **Books on table** list.
- Tabs and the temporary right-hand reference can cross book boundaries.
- Bookmark search covers every PDF on the table; each result shows a muted book name and book-color dot.
- Tabs and the reference chip use the same per-book color marker. Colors are assigned from a restrained palette and can be changed from the book actions menu.
- PDFs remain in their original storage location. NaIgre retains read access through Android's document picker and offers **Relink PDF** when a file is moved.
- Native outlines and each book's optional TXT TOC are indexed and persisted independently.
- The reader keeps only an active foreground document and one secondary document open, regardless of table size.
- The `⋮` menu moved to the upper-left corner, while Search remains in the upper-right.
- The search overlay now has square corners.

## v0.4.1 changes

- Zero-pixel gutter between pages in spread mode for full-bleed artwork.
- Search is a persistent top-right icon and remains available when reader chrome is hidden.
- Annotation text dialogs preserve immersive fullscreen instead of surfacing Android navigation controls.
- Spread page turns are atomic: the previous complete spread stays visible until both pages of the next spread are ready.
- Background prefetch warms both pages of neighboring spreads.
- Optional **Skip cover** spread alignment: `[1]`, then `[2|3]`, `[4|5]`, … instead of `[1|2]`, `[3|4]`, …
- A bookmark can be opened as a temporary, independently navigable reference on the right.
- Bookmark search learns from explicit result selections and promotes frequently visited locations.
- Internal PDF links open in named, destination-specific tabs.

### Temporary reference pane

One bookmark can be opened beside the normal reader without turning it into another tab workspace.

- the primary pane remains the normal tabbed reader
- the reference occupies the right half in Wide layout and the top half in Tall layout, with independent page turns, page jump, zoom, and pan
- choose Automatic, Wide, or Tall from the reader menu; Automatic follows the available window shape
- use the combined upper-right reference control to identify and close it; tap its page portion to jump to an exact page
- long-press the reference title or page portion to return to its opening location or close it
- opening another bookmark with `▥` replaces the current reference
- `×` closes the reference and restores the configured single/spread layout
- the reference is session-only and is deliberately not persisted
- while it is open, its rendering takes priority over speculative neighboring-page prefetch

### Internal PDF links

Tapping an internal link from either reader pane opens or activates a tab in the primary reader. An open temporary reference remains in place.

Tab naming follows the document structure:

- link and PDF-outline destinations retain their vertical page coordinates when available
- an outline destination within a small tolerance supplies its exact bookmark title
- otherwise the closest bookmark vertically above the target supplies `Bookmark · p123`
- if the destination page has no bookmarks, the nearest bookmark from an earlier page is used
- if that page has bookmarks but none can be positioned confidently, the tab uses the physical page fallback
- for a page-only destination, the tapped link's visible label is extracted on demand and matched conservatively against same- and adjacent-page bookmark titles
- if neither destination metadata nor the visible label identifies a bookmark, the tab uses `Page 123`

Exact bookmark destinations reuse bookmark identity. Other link destinations use page-and-position identity, so repeated links to one spot reuse its tab while different destinations on the same page remain distinct. External links are deliberately ignored for now, and link taps do not affect bookmark-search visit counts.

### Library and working set

NaIgre stores logical books independently from the PDFs that currently back them. The app opens the original Android document URI directly and does not copy PDF files into private storage.

- open **Library** from the upper-left menu
- tap a tile to put a known book on or take it off the current table
- tap **All**, **On table**, a named tag, or **Untagged** to filter the Library without changing the table
- use **Tags…** from an item's `⋮` menu to assign one or more tags; cards show a compact tag summary
- long-press a named tag to edit its items, rename or delete it, or add/replace the current table from that tag
- deleting a tag retains its items; forgetting a tag's items deletes their NaIgre metadata and history after listing any items shared with other tags
- selected books participate in search, tabs, and temporary references
- deselected books retain their compact summary, on-disk bookmark index, TXT TOC, color, and visit history
- use **Relink/replace PDF** for a moved or revised source while retaining book identity
- use **Refresh PDF** to force fresh page/outline metadata
- use **Forget book and history** only when that retained data should actually be deleted

## Interaction model

The reader is intentionally not trying to become a general-purpose PDF editor. The hot path is:

```text
Search → type a few characters → tap result → read
```

No cloud, no PDF editing, no annotation creation, and no decorative navigation animations.

## New in v0.4.0

### Reader chrome

- compact book name at the top
- persistent top-right **Search** icon
- compact bookmark-named tab strip
- page/spread number in the lower-right corner
- center-page tap hides/shows reader chrome
- chrome is layered over the existing reader surface; hiding it does not resize the PDF renderer or toggle Android system bars
- immersive system-bar hiding remains an explicit menu action instead of happening on every chrome toggle

### Bookmark search overlay

- opens directly into the bookmark search field
- keyboard is requested immediately
- dialog window animations are disabled
- PDF outline and imported TXT TOC remain one fuzzy-search index
- results show bookmark name, hierarchy/path when useful, physical page, `+`, and `▥`
- **tap result**: use the current tab
- **tap `+`**: open in a new tab
- **tap `▥`**: open as a temporary reference in the secondary pane
- no long-press is required for the primary workflow
- an empty query lists frequently visited bookmarks first
- typed queries combine fuzzy relevance with a capped frequency boost, so weak matches cannot overtake exact matches solely through usage
- normal result taps, `+`, and `▥` all count as visits; page turns and tab switches do not
- visit counts persist independently for each book

### Stable bookmark tabs

A tab now represents a **bookmark anchor**, not a page number.

Example:

```text
Great Cavern
```

If that tab starts at page 412 and you turn to 413/414, its name remains `Great Cavern`.

Searching `Great Cavern` again does **not** open another copy and does **not** reset the existing tab to page 412. It activates the already-open `Great Cavern` tab at its current page/spread.

This applies whether the result itself or its `+` button is used.

### Disposable tab cleanup

Tabs show only their bookmark names by default.

- tap tab → switch
- `×` → close
- long-press tab → **Return to tab start** / **Close** / **Close others**

No rename/duplicate/pinning UI is added.

### Page turning

Page turning is now available directly on the PDF surface.

Configurable modes:

- **Edge taps**
- **Swipe**
- **Edge taps + swipe** (default)

Behavior:

- left edge → previous page/spread
- right edge → next page/spread
- horizontal swipe → previous/next when approximately at fit-width
- when zoomed in, horizontal swipes remain pan gestures instead of changing pages
- page turns update only the tab's current page; the bookmark/tab name is unchanged

The setting is persisted.

### Page jump

Tap the page number in the upper-right of the primary pane to enter an exact physical page number.

There is deliberately no coarse page slider yet. That will only become compelling if/when a continuous-scroll reader mode is implemented.

### Annotation interaction

Normal annotation interaction is now intentionally minimal:

- tap annotation with a non-empty text payload → show **only that text**
- tap annotation with no text payload → do nothing

Author, type, subject, timestamps, etc. are not shown in the normal reader path.

The old annotation inspector is retained only under **Diagnostics** for development/testing.

### Reader menu

The upper-left `⋮` menu contains secondary actions:

- Library and current selection
- Add PDF
- Add folder
- theme
- reader layout (Automatic / Wide / Tall)
- Import TXT TOC
- Refresh current PDF
- Single/spread toggle
- **Skip cover** spread alignment toggle
- page-turn gesture mode
- Reset zoom
- immersive fullscreen
- diagnostics

### Existing v0.3.0 behavior retained

- MuPDF 1.28.0 rendering
- read-only existing annotation rendering
- native PDF outline extraction
- external flat or hierarchical `title::page` TOC import
- fuzzy bookmark search
- location tabs
- single/spread rendering
- LRU rendered-page cache
- separate foreground and prefetch MuPDF documents/executors
- adjacent-page pre-render
- pinch zoom / pan
- double-tap reset-to-fit
- persistent library, selected working set, and session reopening

## External bookmark format

Flat TOCs remain one bookmark per line:

```text
Great Cavern::126
Temple Entrance::132
Western Galleries::149
```

Markdown-style headings can establish a hierarchy:

```text
# Cult Hideout::40
## Level 2::80
Great Cavern::126
Western Galleries::149

## Level 3
Flooded Shrine::173

---
Appendix::300
```

`#` through `######` set the current hierarchy level. A heading with `::page` is both a navigable bookmark and a parent; one without a page only groups the entries beneath it. Plain entries inherit the active headings. A new heading clears deeper levels, while blank lines leave the hierarchy intact. A line containing only `---` resets it so later entries are flat again.

Page numbers are 1-based physical PDF pages.

The imported TXT TOC remains associated with its logical book across app restarts and while that book is off the table. It can be replaced or removed from the book's **Library** actions.

## Build on Linux

Requirements:

- JDK 17+
- Android SDK Platform 36
- Android Build Tools for API 36
- Android platform-tools (`adb`)

Point the project at your SDK, for example:

```sh
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties
```

Build:

```sh
./gradlew :app:assembleDebug
```

Validate the release variant:

```sh
./gradlew :app:lintRelease :app:assembleRelease
```

The repository deliberately contains no signing key. `assembleRelease` therefore produces an
unsigned release artifact until a stable private V1 key is configured locally. Continue using the
debug APK for upgrade installs during prototyping; do not ship a release signed with Android's
shared debug key.

Install/upgrade:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application ID remains `wund0r.naigre.reader`, so this upgrades the previous phases in place.

## v0.27.0 tablet test checklist

1. Open Diagnostics and confirm build `0.27.0 (44)`.
2. In Wide and Tall layouts, confirm primary tabs remain in the same full-width bottom strip, retain close buttons, and scroll to the selected tab.
3. Hide and restore chrome. Confirm Search remains at the same lower-right position above the tab strip's position while tabs, Menu, and document indicators hide and return.
4. Open a reference in Tall layout and confirm it occupies the upper half, with the primary reader below and next to its bottom tabs. Confirm the reference control is upper-right and the primary page indicator is upper-right within the lower pane.
5. Switch the open reference to Wide layout and confirm the primary pane moves left, the reference moves right, both indicators remain owned by the correct pane, and zoom/position remain usable.
6. On a phone-width Tall layout, confirm the reference title truncates before colliding with Menu while its page count and `×` remain visible.
7. Open Markdown and full-text references in both layouts and confirm their content can scroll clear of the pane controls, Search button, bottom tabs, and system navigation area.

## v0.26.0 tablet test checklist

1. Open Diagnostics and confirm build `0.26.0 (43)`.
2. Open PDF and image references in both Wide and Tall layouts. Confirm there is no separate reference tab and the combined lower-right control shows an ellipsized identity, page position, and `×` without overflowing a narrow pane.
3. Tap the page portion and jump to another page; then use `×` to close the reference.
4. Long-press the title and page portions and confirm both open the reference-actions menu. Use **Return to reference start** after navigating away.
5. Open a Markdown reference and confirm the same control shows its identity and close action without a meaningless page count.
6. Run a full-text search and confirm the control reports search progress/results and closes the result pane; the result header should contain only the query and **Edit**.
7. Hide and restore reader chrome with document references and confirm the combined control follows it. With full-text results, confirm the control remains available as the pane's persistent close path and content stays clear of it.

## v0.19.0 tablet test checklist

1. Open Diagnostics and confirm build `0.19.0 (34)`. The active source and text revisions should match after its one-time index rebuild finishes.
2. Annotate an active PDF without renaming it or changing its page count while NaIgre is in the background. Return and confirm the page, annotations, outline, and eventual text-search results use the revised file.
3. Repeat through **Rescan folders** and confirm the same-file/same-page-count PDF gets a new revision and rebuilds its text index.
4. Use **Refresh PDF** on an unchanged file and confirm Diagnostics records a manual refresh and a new revision even if its provider stamps did not change.
5. Re-add or relink a known PDF and confirm its UUID-backed color, visit ranking, TOC, and existing tabs survive while its source revision changes.
6. Keep the refreshed book in the reference pane during refresh and confirm neither pane briefly returns to an older cached rendering.
7. While indexing is in progress, search for text from the refreshed book and confirm old-revision results are absent rather than mixed with new results.

## v0.18.0 tablet test checklist

1. Open Diagnostics and confirm the build is `0.18.0 (33)`, a 12 MP image ceiling, and the expected device memory class.
2. Open a large map and confirm Diagnostics reports its decoded dimensions and a size no greater than 12 MP.
3. Zoom to 2×–4× and compare small labels and map details with the previous build.
4. Switch repeatedly among a large map, a PDF, and another map; confirm opening and cached tab switching remain responsive.
5. Open a map in the temporary reference pane, change between Wide and Tall layouts, and confirm both panes retain zoom/position without a crash.
6. Put NaIgre in the background and return; confirm the visible map remains intact while non-visible cache entries may be released.

## v0.17.0 tablet test checklist

1. Open Diagnostics and confirm the build is `0.17.0 (32)`.
2. Choose **Interface → Fullscreen above lock screen**, then turn the screen off with the power button.
3. Wake the tablet and confirm NaIgre appears immediately with its system bars hidden and remains usable.
4. Press Home or leave NaIgre and confirm Android presents the normal lock screen rather than exposing another app.
5. Unlock, disable **Fullscreen above lock screen**, lock the tablet again, and confirm the ordinary lock screen appears.
6. Repeat with a Markdown note active and confirm its existing view remains visible immediately after waking.
7. Force-stop NaIgre, lock and wake the tablet, and confirm NaIgre does not launch itself.

## v0.15.0 tablet test checklist

1. Open Diagnostics and confirm the build is `0.15.0 (25)`.
2. In Wide layout, confirm the one-row chrome contains `⋮`, scrolling tabs, the reference marker, and Search, while a bookmark reference opens on the right.
3. Open each reader-menu category and confirm Back returns to the five-entry main menu.
4. Confirm current-PDF maintenance is under **Current book**, while theme, reader layout, and fullscreen are under **Interface**.

## v0.14.0 tablet test checklist

1. Open Diagnostics and confirm the build is `0.14.0 (24)` and **Layout** reports both the selected and resolved modes.
2. Choose **Layout → Wide** and confirm tabs scroll horizontally at the top and a bookmark reference opens on the right.
3. Choose **Layout → Tall** and confirm the full-width header disappears, `⋮` moves to the wider left rail, tabs scroll vertically, and the reference opens below.
4. Open enough primary tabs to overflow the tall rail; confirm they scroll independently and the reference marker remains pinned at the bottom.
5. Tap primary tabs and their close buttons in both layouts, including after scrolling the strip.
6. Zoom and pan both panes, switch Wide/Tall, and confirm their positions remain near the same content after rerendering.
7. Choose **Automatic**, rotate the device, and confirm the resolved mode changes without altering the selected setting.
8. Tap a blank document area and confirm the tall rail hides and returns with the other reader chrome.

## v0.13.1 tablet test checklist

1. Open Diagnostics and confirm the build is `0.13.1 (23)` and the text index reports the expected size.
2. Search a common lowercase term across all PDFs and verify results replace **Searching…** promptly.
3. Repeat with uppercase letters and verify it is similarly fast and case-insensitive; the `Aa` smartcase indicator should appear only in bookmark/filename mode.
4. Run a search while indexing is still progressing and verify results refresh without becoming stuck behind repeated queries.

## v0.13.0 tablet test checklist

1. Open Diagnostics and confirm the build is `0.13.0 (22)`.
2. Open a bookmark with `▥`, long-press its reference marker, and move the reference below and back to the right.
3. In each placement, zoom and pan both panes before switching placement; verify both remain near the same content after resizing.
4. Leave placement below, close and reopen a reference, and verify the bottom placement is remembered.
5. Move several pages away in a primary tab, long-press its tab, and use **Return to tab start**; repeat after restarting the app.
6. Move away inside a reference, long-press its marker, and use **Return to reference start**. For a positioned PDF outline bookmark, verify it returns near that heading rather than merely fitting the page.
7. Open full-text results in the reference pane and verify its long-press menu can move the list below/right and close it.
8. Rotate with a reference open and verify both panes remain usable and the chosen right/bottom placement does not change.

## v0.9.0 tablet test checklist

1. Open Diagnostics and confirm the build is `0.9.0 (16)`.
2. In Library, choose **Add folder**, grant a campaign directory, and confirm every directly contained PDF is added and placed on the table while subdirectories and unrelated files are ignored.
3. Include `downtime.pdf` and `downtime.txt`; confirm the TXT is imported automatically and its bookmarks are searchable.
4. Take one imported book off the table, run **Rescan folders**, and confirm it remains off the table with its history intact.
5. Add another PDF/TXT pair to the directory, rescan, and confirm only the new book is added and selected.
6. Edit a matching TXT, rescan, and confirm the updated TOC replaces the previous parsed TOC.
7. Supply a malformed replacement TXT and confirm NaIgre reports it while retaining the previous working TOC.
8. Replace a PDF without changing its filename, rescan, and confirm its UUID, color, visits, and tabs survive while its page count and outline refresh.
9. Temporarily remove a file, rescan, and confirm NaIgre does not forget the corresponding library book.
10. Restart and confirm the folder remains listed in Diagnostics and can be rescanned without another permission prompt.

## v0.8.1 tablet test checklist

1. Open Diagnostics and confirm the build is `0.8.1 (15)` and the selected/resolved theme is shown.
2. Switch between **Dark**, **Light**, and **System** from the reader menu and confirm the reader is recreated with its tabs and page positions intact.
3. In each theme, inspect the reader chrome, search overlay, persistent text results, Library, ordinary dialogs, buttons, checkboxes, and status/navigation bars for consistent warm neutral colors.
4. Confirm tabs, reference chips, search results, book selectors, and library cards use book-colored markers on neutral surfaces rather than book-colored backgrounds.
5. Confirm different books remain easy to distinguish in both themes and that changing a book color updates every marker.
6. Open a full-text result and confirm matching snippet text and page highlights use gold while the PDF itself retains its original colors.
7. Trigger or inspect an unavailable-book state and confirm red is limited to the error message.
8. With **System** selected, change the tablet’s system appearance and confirm NaIgre follows it after Android recreates the activity.

## v0.7.1 tablet test checklist

1. Put at least two books on the table and verify Search shows one horizontally scrollable row containing **All** and one color-coded chip per book.
2. In **Bookmarks**, choose one book and verify both an empty query and a typed query return only that book.
3. Switch to **Text**, run a query, and verify its persistent right-hand result list uses the same scope.
4. Change scope directly in the right-hand result pane and verify the query reruns, the list returns to the top, and indexing progress describes only that book.
5. Switch primary tabs and verify the selected scope does not change; reopen Search and verify the active book is the first book chip after **All**.
6. Take the scoped book off the table and verify the open text search resets to **All** without showing results from the removed book.
7. Restart and verify the scope defaults to **All**.

## v0.6.0 tablet test checklist

1. Open `0.6.0 (11)` and add PDFs to the new alpha library.
2. Confirm the full-screen Library shows filenames, colors, page/bookmark counts, and selection state.
3. Select two books, press **Read**, and confirm search returns only those two books.
4. Take one book off the table and confirm its tabs close and its results disappear.
5. Select it again and confirm its TXT TOC, color, and frequently visited bookmark order remain.
6. Restart and confirm the selected working set and tabs reopen.
7. Replace a PDF through **Relink/replace PDF** with an annotated copy of the same book and confirm history remains.
8. Modify an active PDF while NaIgre is in the background, return, and confirm its latest annotations appear after automatic refresh.
9. Use **Refresh PDF** and confirm page count/outline changes are reflected without losing visits.
10. Add a different URI with a known filename and verify NaIgre asks whether to retain the existing history or create a separate book.
11. Move a source file, confirm the book remains known when opening fails, then relink it.
12. Use **Forget book and history** and confirm only NaIgre's retained metadata is deleted, not the PDF file.

## v0.5.0 tablet test checklist

Use the same large and graphics-heavy PDFs that passed Phases 1–3.

1. Confirm the new book-name / Search / tabs / page-number layout appears.
2. Center-tap repeatedly and verify chrome show/hide is substantially faster and no longer flashes Android's status/navigation bars.
3. Search a bookmark and tap it; verify the current tab is renamed to the bookmark.
4. Page-turn one or more pages; verify the tab name stays unchanged.
5. Search the same bookmark again; verify it activates the existing tab at its **current** page rather than opening another or jumping back to the anchor page.
6. Use `+` on several different search results to create tabs.
7. Use `+` on a bookmark that is already open; verify it activates the existing tab instead of duplicating it.
8. Select one bookmark repeatedly, reopen search with an empty query, and verify it moves ahead of less-used bookmarks.
9. Enter a query with an exact and a weak fuzzy match; verify the exact match remains ahead regardless of visit frequency.
10. On a page containing several outline headings, follow links to different vertical destinations and verify each tab uses the heading immediately above its target.
11. Tap internal links whose destination coordinates exactly match bookmarks and verify their tabs use those bookmark names without a page suffix.
12. Tap a link into the middle of a section and verify its tab uses `Nearest bookmark · p123`.
13. Tap two different destinations on the same page and verify they remain distinct tabs; tap either destination again and verify its existing tab is activated.
14. Follow a page-only section-reference link and verify its visible label names the tab when it strongly matches a same- or adjacent-page bookmark; verify ambiguous labels still use `Page 123`.
15. Open a reference with `▥`, tap an internal link inside it, and verify the link opens on the left while the reference remains intact.
16. Use `▥` on a distant bookmark; verify it opens on the right while the active tab stays on the left.
17. Navigate, zoom, and page-jump on each side independently.
18. Use `▥` on another bookmark; verify it replaces the right-hand reference.
19. Close the reference and verify the configured single/spread layout is restored.
20. Restart with a reference open and verify the disposable reference is not restored.
21. Long-press a tab and use **Close others**.
22. Test edge-tap page turning.
23. Switch page-turn mode to **Swipe**, then **Edge taps + swipe**, and verify the setting persists after restart.
24. Zoom in and confirm horizontal dragging pans instead of page-turning.
25. Tap the lower-right page indicator and jump to an exact page; verify the tab name remains unchanged.
26. Tap a note/highlight with text; verify only its text appears.
27. Tap an annotation without text; verify no popup appears.
28. In spread mode, enable **Skip cover** and verify page 1 stands alone, followed by `2–3`, `4–5`, etc.; disable it and verify pairing returns to `1–2`, `3–4`, etc.
29. With Skip cover enabled, jump/search to a right-hand page and verify the app displays the correctly aligned spread containing it.
30. Restart the app and verify tabs, active page positions, bookmark visit ranking, imported TOC, spread preference, Skip cover preference, and page-turn mode are restored.
31. Reconfirm Phase-3 cache behavior by revisiting previously rendered tabs.
32. Add at least two PDFs and restart; verify both remain on the table and the active cross-book tabs reopen.
33. Search for bookmarks from both books and verify result book names/colors match their tab colors.
34. Open a result from the second book normally, with `+`, and with `▥`; verify each action targets the right book.
35. Import different TXT TOCs for both books and verify they remain isolated after restart.
36. Change a book color and verify its table row, search results, tabs, and reference chip stay consistent.
37. Move or replace a PDF, use **Relink PDF**, and verify its outline, page bounds, tabs, and reference page are refreshed.
38. Remove a book and verify its tabs, TOC, and visit history disappear without deleting the original PDF.

## Deliberately postponed

- high-resolution rerender when zoom settles
- continuous vertical scrolling
- page slider
- cover-derived book colors
- PDF editing or annotation creation
- cloud sync/storage
- library-management features

The code continues to avoid assuming that the final product must be a generic PDF reader; the priority remains fast local reference lookup.

## Project layout

```text
app/src/main/java/wund0r/naigre/reader/
├── MainActivity.kt
├── navigation/
│   ├── BookmarkEntry.kt
│   ├── BookmarkIndex.kt
│   ├── ExternalTocParser.kt
│   ├── FuzzyMatcher.kt
│   └── ReaderTab.kt
├── pdf/
│   ├── PdfDocument.kt
│   └── MuPdfDocument.kt
└── render/
    ├── PageBitmapCache.kt
    ├── PageTurnMode.kt
    ├── ReaderSurface.kt
    └── SpreadLayout.kt
```
