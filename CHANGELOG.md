# NaIgre changelog

## v26.09.0 — public beta candidate (not yet published)

- Restored 12 dp left/right padding for book selectors in the search overlay and search reference pane.
- First public-release preparation using `YY.MM.PATCH` calendar versions. Earlier prototype entries below retain their original version numbers.
- Public APKs use a permanent release signing key; development and device-verification apps have separate package IDs and launcher labels.
- Added a manual signed-APK preparation command with existing tests, release lint, certificate/identity checks, checksums and a source-revision report. Nothing is uploaded automatically.
- Reworked the README around installation, Obtainium updates, first-use controls, file privacy/recovery and known limitations. Historical device checklists now live in TESTING.md; signing/publishing instructions live in RELEASING.md.
- Includes the existing PDF/Markdown/map library, cross-book search, reference panes and the recent Markdown folding/search-highlight fixes documented below. Android “Open with” remains deferred.

Existing prototype testers must reinstall once when switching signing keys, then re-add files. This clears app-owned setup/history but does not delete original documents. Subsequent public builds are intended to update in place.

## v0.37.0 changes

- Markdown heading taps cycle from collapsed to all descendant headings without body text, then to the complete subtree, then back to collapsed. Headings without subheadings keep the two-state open/close behavior.
- Revealing a child's text or navigating to a search result makes the next parent tap collapse the subtree. Expanding the complete subtree clears individual child folds.
- Fold markers distinguish closed (`▸`), headings-only (`▿`), and expanded/mixed (`▾`) sections. Per-tab/reference session state and scroll anchoring are retained.

## v0.36.0 changes

- Closing or replacing the full-text search reference clears temporary search highlights, including saved background-tab highlights and pending highlight results. PDF annotations remain unchanged.
- Markdown headings have fold/unfold controls. Folding a heading hides its body and descendants; reopening it retains individually folded children.
- Fold state and scroll position are retained independently for each tab and document reference during the session. Heading and full-text result navigation reveal the destination, while search continues to index the complete note.

## v0.35.0 changes

- Tapping Search now always opens Navigate; holding Search opens Full text directly.
- Tapping a result `+` keeps the existing foreground-tab behavior, while holding it adds the result as an unrendered background tab.
- Results with an existing tab show `✓`; tapping it switches to that tab. Full-text results retain the search reference while switching and remember session search highlighting for background tabs.

## v0.34.2 changes

- Compacted full-text search references: Edit now shares the reference pane's top control row with the search indicator, and the duplicated query header is gone.
- Standardized the search reference filter and status rows at the app's 48 dp control height.

## v0.34.1 changes

- Search now occupies the fixed bottom-right position inside the tab row instead of a separate floating row above it. It remains visible when tab chrome is hidden and overlays the full-width tab scroller without permanently taking width from tab labels.
- The tab strip has trailing scroll space equal to the Search target plus a small gap, allowing the final tab and its unchanged trailing close action to scroll completely clear of Search.
- Wide full-text and Markdown references now reserve only the bottom tab-row height. The obsolete floating-Search clearance no longer removes roughly one result row from the reference pane.

## v0.34.0 changes

- Primary rendering, secondary reference/prefetch rendering, and source maintenance now use three separate bounded FIFO workers. Recursive campaign scans can no longer sit ahead of an interactive reference request.
- Folder enumeration, direct-file inspection, new-document outline extraction, TOC import, source relink/refresh, and freshness checks run on the background maintenance lane. Maintenance Markdown parsing has its own engine so its serialized parser cannot hold up an interactive note open. Accepted catalog/index effects still pass through the process-owned storage queue.
- Primary and secondary document handles remain confined to their owning render workers. Maintenance uses temporary local handles that close on the same worker, including failure and obsolete-result paths.
- Speculative neighbor-page prefetch temporarily lowers only its own task priority and restores the secondary worker afterward. The previous campaign-scan priority change could leave later reference work at background priority.
- Diagnostics now records campaign-scan preparation/queue/work time and reference layout/queue/render/total time. Deterministic JVM coverage verifies that blocked maintenance cannot delay the secondary reference lane and that each lane remains FIFO.

## v0.33.0 changes

- The reader interface now appears immediately in an explicit library-loading state. Catalog summaries load before selected per-book indexes, with the active item hydrated first instead of synchronously loading every selected index during Activity creation.
- Malformed, unreadable, truncated, or unsupported-version catalogs enter a non-destructive recovery screen with **Retry library** and Diagnostics. Until recovery succeeds, NaIgre will not replace the original catalog or saved tabs with temporary empty state.
- One application-scoped FIFO now owns catalog/index reads, JSON serialization, atomic `fsync` writes, hydration, and deletion. Immutable accepted snapshots remain ordered across Activity recreation; lifecycle callbacks never wait for storage completion.
- A missing or corrupt per-book index is reported on that library item without erasing it or blocking other books. PDFs and Markdown notes rebuild through their existing source refresh path; image albums direct the user to rescan their campaign folder.
- Android instrumentation now targets the disposable `wund0r.naigre.reader.verification` application ID, preventing Gradle test cleanup from uninstalling or clearing the personal NaIgre installation.

## v0.32.0 changes

- Individual, tag-bulk, folder-import, and forget actions now commit table membership through one transition controller before any index hydration finishes. A later tap therefore remains authoritative while an earlier bulk action is still loading.
- Per-book operation identities reject obsolete folder scans, hydrations, refreshes, relinks, and TOC changes. Accepted source results merge into the current library record so concurrent color and tag edits survive, while accepted index saves and forget deletions run in order.
- Removing a book preserves the same surviving tab objects and their image/Markdown viewport state, keeps an unrelated reference pane alive, and invalidates pending primary and secondary opens for removed books.
- Campaign rescans now close inspected Markdown documents promptly and rebuild text whose persisted source revision changed even when the parsed Markdown content stayed equivalent.

## v0.31.0 changes

- Full-text searches now carry immutable query, scope, source-revision, result-limit, and timing snapshots. Results are accepted only while that complete request still matches the current search session and library sources.
- Replacing a query, changing scope, closing its reference pane, or destroying the Activity now cancels active SQLite FTS and snippet work through `CancellationSignal`; expected cancellation is silent rather than appearing as a search failure.
- Text-index progress no longer repeatedly cancels a useful running query. Progress refreshes coalesce into one newest follow-up request, while explicit user actions still take priority immediately.
- Diagnostics separates full-text queue wait from actual query execution time. Source changes also clear already-displayed results from an older revision while the replacement index is searched.

## v0.30.0 changes

- Full-text page replacement now resolves `(book, page)` through an ordinary indexed identity table and addresses FTS rows by integer row ID. This removes the full FTS-table scan previously performed for every extracted page; the derived text database rebuilds once on upgrade without affecting the library, TOCs, tags, visits, or reader state.
- Text extraction is owned by one process-level coordinator, so Activity recreation cannot start an overlapping job for the same book. Every rebuild receives a persisted run identity, preventing a superseded worker from writing into a newer forced rebuild even when the source revision is unchanged.
- A text-extraction exception now leaves the book visibly incomplete instead of recording the failed page as empty. Earlier committed batches remain searchable, other books continue indexing, Diagnostics names the failed book and page, and automatic retries for that revision are suppressed until an explicit rebuild or app restart.

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
- This beta storage format starts a new library; the old `book-table.json` and visit-counter format are not migrated.

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
