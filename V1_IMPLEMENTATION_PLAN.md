# NaIgre: implementation plan for V1 reliability and maintainability

This is a handoff for an implementing agent. The goal is to keep navigation and search fast across PDFs, Markdown notes, and image albums while fixing specific correctness risks. The app remains a prototype; implement the bounded changes below in order, with verification alongside each change.

Baseline reviewed: `965b1cc` (`added code`), app version `0.29.4 (52)`. The application ID and namespace are `wund0r.naigre.reader`. Recheck the working tree and applicable `AGENTS.md` instructions before starting. The earlier concern about untracked application code is resolved: this commit includes the application, Gradle files, and roadmap.

Kotlin source paths below are relative to `app/src/main/java/wund0r/naigre/reader/`.

The prior review ran `:app:lintRelease :app:assembleRelease` successfully, with 0 errors and 38 warnings. Database scanning was reproduced with a desktop SQLite fixture; the UI/concurrency failure paths were identified through code inspection and still need targeted reproduction. Treat those findings as starting points, not proof that every symptom has been observed on a device.

Preserve these product behaviors throughout:

- Search remains available with one tap, including when reader chrome is hidden. Keep immediate feedback and no interface animations.
- Navigate search retains smartcase and visit ranking. Full-text search remains case-insensitive, with existing query semantics, ranking, snippets, book scope, and explicit progressive result counts.
- Preserve logical book UUIDs, tags, colors, external TOCs, visits, table selection, and useful tab restoration. Files remain referenced through their original Android document URIs; never modify source files.
- Removing a book from the table retains its library metadata/history and reusable text index. Forgetting removes app-owned data only, after the existing confirmation.
- Preserve source-revision checks, bitmap pinning, bounded image decoding, current reference placement, and session-only image/Markdown viewport retention.
- A derived full-text database can be rebuilt on schema upgrade. Do not clear the library, external TOC data, visits, or preferences to simplify implementation. A framework for historical alpha-schema migrations is unnecessary.

Do not expand this work into a Compose/fragment migration, a dependency-injection framework, a general document-plugin architecture, image tiling, new file formats, named tables, or a redesign of the reader interface. No target for reducing `MainActivity` line count: extract code when doing so gives state or work a clear owner. Existing executors are a sufficient foundation.

## 0. Establish a repeatable baseline

Read `README.md`, the current-priority portion of `ROADMAP.md`, and the implementation areas listed below. Preserve unrelated working-tree changes. Use small reviewable patches; this plan does not authorize publishing, pushing or changing signing keys.

Prepare deterministic synthetic fixtures for database and state tests. Keep personal PDFs and maps out of Git. For device acceptance, use one representative large PDF with links/TOC, a Markdown note with headings and a table, and an album with a large map. Record the device, Android version, fixture sizes, and whether indexes/caches are warm.

Add only the test support needed for these fixes: local JVM tests for pure state decisions and Android tests for actual SQLite/repository behavior. Avoid a large UI test framework. Use controllable executors/latches to test ordering; avoid sleep-based race tests.

Deliverable: a baseline build, a concise record of the fixtures/checks, and any necessary small test harness. Add regression cases with the stage that fixes them.

## 1. Fix full-text index writes and failed extraction

Status: implemented for `v0.30.0 (53)`. The schema/repository and coordinator cases passed on a Samsung SM-T860 running Android 12 as part of the 11-test Android regression suite. A desktop SQLite fixture with 6,000 unrelated retained rows measured the old versus v4 write paths at approximately 0.68/0.009 seconds for 1,000 pages, 2.39/0.021 seconds for 3,000 pages, and 5.86/0.044 seconds for 6,000 pages. These timings isolate database overhead and are not tablet or PDF-extraction benchmarks.

Primary files: `search/TextSearchIndexRepository.kt` and `MainActivity.kt` (`scheduleBookTextIndex`, `deleteBookTextIndex`, progress/error reporting).

Problem: `storePages()` deletes by `book_id` and `page_index` before every insert. Those equality filters scan the FTS table. With the current schema and statements, a desktop in-memory fixture took approximately 5.44 seconds for 6,000 pages, versus 0.05 seconds for inserts alone. This isolates database overhead; it is not a tablet or PDF-extraction benchmark. Separately, extraction exceptions currently become empty strings and are counted as successfully indexed pages.

Implementation:

1. Keep FTS4 and add an ordinary page-identity table with an integer primary key and a unique `(book_id, page_index)` lookup. Use its integer ID as the FTS `rowid`. Preserve the current FTS columns/query behavior unless a change is necessary for this fix.
2. Locate an existing page through the ordinary index, then replace its FTS row by `rowid`. For refresh/forget, retrieve the book's row IDs through that index and delete matching FTS rows in safe batches. Do not put new indexes directly on FTS shadow tables or derive row IDs from potentially colliding hashes.
3. Update page identities, FTS rows, and progress in the same transaction. Preserve source-revision validation and resumability. Repeated batches must not create duplicate matches or move completed progress backward. A superseded extraction job must not write into a newer index generation, including a forced rebuild of the same source revision.
4. Bump the derived text-database version and rebuild it using the existing upgrade pattern. Leave library/history storage untouched. Make one logical index job per book authoritative, including during Activity replacement; old job cleanup must never clear a newer job's token.
5. Remove the catch-and-empty-string fallback. For the initial implementation, stop indexing that book at the failed batch, keep earlier committed batches searchable, and report the book/page failure. Continue indexing other books. Successfully extracted empty text still counts as success.
6. Keep the failed book incomplete. Suppress repeated automatic retries for the same failed revision within the current run; explicit rebuild or a new source revision can retry, and a subsequent app run can resume. Use the existing rebuild action initially. Per-page skipping/backfill is deferred.
7. Put extraction scheduling/progress/failure ownership in a small `TextIndexCoordinator` if needed to enforce these rules. Keep it free of views and Activity references. Do not broaden this into a rewrite of search rendering.

Acceptance:

- Storing the same batch twice produces one searchable row per page and correct progress.
- Interruption/restart resumes from committed progress. Stale jobs cannot overwrite a force-rebuilt index or remove the replacement job's registration.
- A failure in the second batch preserves the first batch's results and reports incomplete coverage; another book finishes normally. A genuinely blank page does not report an error.
- Refresh and forgetting affect only the intended book. Taking a book off the table preserves its index.
- Actual SQLite query plans show indexed page-identity/row-ID lookup rather than a full FTS scan for each inserted page.
- Run synthetic scaling checks at 1,000, 3,000, and 6,000 pages, including an unrelated retained book. Record timings without turning noisy wall-clock thresholds into unit-test assertions.

## 2. Make full-text queries cancellable

Status: implemented for `v0.31.0 (54)`. Queries use immutable request snapshots and Android `CancellationSignal` through the FTS candidate and snippet cursors. Explicit replacements cancel current work; indexing progress retains only one newest follow-up without interrupting a useful query. Deterministic held-query, coalescing, close, and repository cancellation cases passed on a Samsung SM-T860 running Android 12; the complete Android regression suite ran 11 tests successfully.

Primary files: `TextSearchIndexRepository.kt`; `MainActivity.kt` (`refreshTextSearchResults`, `submitTextSearchWork`, `closeReference`, indexing-progress refresh).

Implementation:

1. Capture an immutable query request: text, selected book IDs, page counts, source revisions, result limit, request ID, and submission time. Worker code must not read a mutable UI search session.
2. Give each request an Android `CancellationSignal`. Cancel obsolete SQL work and check cancellation during candidate ranking and snippet batches. `Future.cancel(false)` alone does not stop a running query.
3. Explicit query edits, scope changes, reference closure, and lifecycle teardown supersede the old request. Apply results only if the request/session and current book revisions remain valid. Expected cancellation must not show a search-failed message.
4. Keep indexing-progress updates coalesced. Progress-only notifications should mark a rerun as needed while a useful query is running, rather than repeatedly cancelling it and starving first results. Run at most the current query plus the newest pending update.
5. Retain existing ranking, exact match counts, and Show more behavior. Add queue-wait and execution time to diagnostics so total user waiting time is visible.

Acceptance:

- Start a deliberately held query A, request B, and verify A is cancelled/ignored and B can complete without A finishing its full scan.
- Closing the search reference stops outstanding query work without an error dialog or later UI updates.
- Frequent indexing notifications still allow useful results to appear.
- Scope/source changes during a query cannot publish obsolete results; Show more preserves ranking and correct totals.

## 3. Centralize library/table changes and reject stale work

Status: implemented for `v0.32.0 (55)`. Table membership is committed before index hydration and all individual, tag-bulk, import, and forget flows use one transition owner. Per-book selection/content generations reject obsolete scan, hydration, refresh, relink, TOC, and forget completions; accepted per-book index writes and deletes are serialized. Pure JVM regression coverage verifies membership ordering, tab identity, reference survival, operation invalidation, and current-field merge rules.

Primary files: `MainActivity.kt` (`selectBook`, `deselectBook`, `applyBulkTableSelection`, `forgetLibraryItems`, `applyFolderScanResult`, hydration, refresh/relink/TOC handlers); `table/BookRecord.kt`; `navigation/ReaderTab.kt`.

Implementation:

1. Introduce a small `TableSessionController` or equivalent owner of table-selection transitions. Calculate surviving tabs, active tab, reference closure, and affected book IDs in one place. Route individual add/remove, tag add/replace, forgetting, and import-driven selection through the same rules.
2. Record selection intent synchronously on the main thread. Hydration fulfills that intent; its completion must not independently reselect a removed book or replace a newer selection. A later individual add/remove modifies the current intended set, including while a bulk action is loading indexes.
3. Keep user/session state mutations on the main thread. Background operations receive snapshots and return typed results. Track per-book source/index operation identity so an older load, scan, refresh, or relink cannot supersede a newer one or resurrect a forgotten item.
4. Merge valid source-derived changes into the current book. Preserve current tags/colors and other fields the operation does not own. Apply the same rule to hydration, color-dialog callbacks, TOC import/removal, and refresh handlers; do not merely patch folder scanning.
5. Protect persisted indexes as well as UI state. Today some background paths save an index before their UI result is accepted. Ensure stale jobs cannot overwrite an accepted newer index or recreate an index after forgetting. Serialize accepted persistence effects and check operation identity before committing them.
6. Preserve existing `ReaderTab` instances for surviving tabs: viewport maps currently use `IdentityHashMap`. A reducer that recreates every tab would lose those positions. Add persistent tab IDs only if a demonstrated need requires them.
7. Cancel pending document opens/renders for removed books even if the handle has not opened yet. Removing/forgetting must work during loading as well as after rendering.
8. Replace broad rendering invalidation with affected-book/pane invalidation where practical. If a global reset is still needed, capture viewports and explicitly redraw every surviving document pane. A text-search reference must retain its query/results while its scope updates.

Acceptance (use deterministic delayed-work tests for ordering):

- Scan starts, user edits a book's tags/color, changed scan result arrives: source metadata updates and user edits survive.
- An older hydration/refresh finishes after a relink, TOC replacement, or forget: it cannot restore stale state or write stale index data.
- Bulk tag selection is loading; user makes an individual selection change: final membership honors the later action without losing unrelated intended selections.
- Main pane A and reference B are open; remove/forget A while keeping B: B remains visible, correctly positioned, and usable.
- Repeat the preceding case through a tag table-replacement action and with PDF, Markdown, and image references.
- Removing a book during its pending open cannot reopen it later.
- Surviving tabs keep active selection, origin pages, and image/Markdown viewports. The empty-table state is usable.

## 4. Protect catalog recovery and move persistence off the UI thread

Status: implemented for `v0.33.0 (56)`. Catalog startup now has explicit loading, missing, loaded, and recoverable-failure states; current-schema validation never turns malformed or unsupported bytes into an empty library. One application-scoped FIFO owns catalog/index reads, JSON serialization, atomic writes, hydration, and deletion across Activity recreation. Selected indexes hydrate asynchronously with the active item first, and missing/corrupt item indexes remain distinct and recoverable without hiding other books. Instrumentation uses the isolated `wund0r.naigre.reader.verification` package so device tests cannot uninstall or clear the personal app.

Primary files: `BookLibraryRepository.kt`; `MainActivity.kt` (`onCreate`, `persistBooks`, hydration and library mutation handlers).

Implementation:

1. Represent catalog loading explicitly: loading, no existing catalog, loaded, or failed. Only the missing-file case creates an empty library automatically. Read/parse/unsupported-schema failures preserve the original catalog and expose Retry plus diagnostics.
2. While initial loading or recovery is unresolved, prevent normal writes from replacing the catalog. Show the interface/loading or recovery state first. Do not restore an empty session and persist it over saved tabs while catalog/index loading is still pending.
3. Read and validate the catalog's version. Current-version files must load. Do not build a historical migration framework; unsupported versions follow the recoverable-error path rather than silently defaulting fields.
4. Keep a missing/corrupt individual book index distinct from a catalog failure. Preserve the item's identity/metadata and other usable books. Do not report hydration success or search readiness for an unloaded index; use the existing refresh/rescan/relink paths where appropriate.
5. Add a serialized storage owner using application context and immutable snapshots. Catalog reads, serialization, file writes, `fsync`, index hydration, and index deletion must not run on or block the main thread. A UI save must not wait on a repository monitor held by a worker.
6. Maintain ordered index/catalog writes and ordered deletion. Coalescing redundant catalog-only saves is allowed, but must not reorder them across index writes or forgetting. Older snapshots must not become the last durable state.
7. Preserve atomic file replacement. Handle normal Activity recreation without competing storage writers or losing accepted saves. Do not block lifecycle callbacks waiting for disk work; document the small process-kill window inherent in asynchronous persistence.
8. Surface actionable persistence failures through existing status/diagnostics. A destructive reset/export UI is outside this stage; retrying a failed load must be safe.

Acceptance:

- Inject malformed catalog JSON/read failure: the original bytes and saved tabs remain unchanged; normal add/save actions cannot overwrite them. Retry can recover after the underlying problem is fixed.
- A missing catalog starts normally. One failed per-book index does not erase or prevent use of the rest of the library.
- Submit saves A then B with controlled delays: reloading produces B. Accepted index updates and subsequent forgetting cannot race into stale files.
- Open the app with several selected indexes: its initial interface appears before index hydration completes, with clear loading feedback.
- Recreate the Activity during loading/saving and verify one consistent durable result.
- Use debug StrictMode or equivalent tracing to confirm the changed library flows perform no catalog/index disk I/O on the main thread. Treat this as a development check, not a production crash policy.

## 5. Separate maintenance work from interactive rendering

Status: implemented for `v0.34.0 (57)`. Three activity-scoped, bounded FIFO lanes now isolate primary rendering, secondary reference/speculative rendering, and background source maintenance. Campaign scans, file-provider inspection, document import, TOC parsing, relink/refresh, and freshness work no longer occupy an interactive render queue; maintenance also has a separate serialized Markdown parser. Temporary maintenance handles close locally; primary and secondary handles remain on their owning workers. Speculative prefetch restores secondary-worker priority after every task, Diagnostics exposes scan/reference timing, and deterministic JVM coverage holds maintenance while secondary work proceeds.

Primary files: `work/ReaderWorkCoordinator.kt`; `MainActivity.kt` (folder scanning/import, `secondaryDocument`, `renderReference`, prefetch and teardown).

Implementation:

1. Move recursive folder enumeration, document inspection for import, and source-maintenance work to a bounded maintenance executor. Persist accepted results through the storage owner from stage 4.
2. Reserve the existing secondary-document executor for reference rendering and speculative prefetch. Continue suppressing speculative prefetch while a reference pane is open. A whole folder scan must never sit in front of an interactive reference request.
3. Keep each MuPDF handle owned and used by one executor; do not share a handle between maintenance, rendering, and indexing workers. Cancel obsolete work and close handles on their owning worker.
4. Set thread priorities deliberately at worker creation or restore temporary changes. Folder scanning currently lowers the shared worker's priority without restoring it.
5. Extract a small document-session owner if necessary to encapsulate primary/secondary handle lifetime and request generations. Keep cache pin/unpin and surface-clear ordering explicit; retain the existing pixel/cache budgets.

Acceptance:

- Hold a folder scan in a test; an uncached reference request reaches its rendering worker without waiting for that scan to finish.
- On a device, open a reference and turn its pages during a campaign scan. Record request-to-display latency and verify the primary reader remains usable.
- Rapid navigation, reference closure, rotation, and Activity destruction do not apply obsolete renders, leave handles open, or recycle a displayed bitmap.
- Added workers do not create unbounded simultaneous PDF opens or image decodes.

## 6. Run the V1 regression and performance pass

Use the same device and fixtures from stage 0, with warm/cold conditions recorded. Keep measurements simple and useful:

- Request-to-visible Search overlay time, with chrome shown and hidden.
- Cached tab switching and ordinary page navigation.
- Full-text first-result time, including queue wait, with indexes complete and building.
- Full-text result scrolling with a large outline and a mixed table.
- Reference opening/page changes during a folder scan.
- Visible map detail and peak memory under main/reference image use, using the existing decode policy.

Run a small repeated sample (for example, ten warm interactions) and compare median/worst latency with the baseline. Do not invent a universal millisecond requirement before measuring the representative device. Cached interaction should not gain new document I/O or extraction; obsolete requests should not delay current ones. Report any repeatable regression instead of hiding it in average timings.

One known secondary candidate is `TextSearchResultAdapter.getView()` calling `BookmarkIndex.contextAtOrBefore()` over the complete bookmark list for each bound row. If measurements show meaningful result-scrolling cost, add a small per-book/page context cache or lookup built when the bookmark index changes. Invalidate it on outline/TOC/source changes and preserve the existing conservative coordinate-free context behavior. Do not extend this into a new fuzzy-search algorithm or result-list redesign.

Run the existing V1 acceptance list in `ROADMAP.md`, including internal-link tab names, external TOC hierarchy, Markdown tables/search jumps, source refresh/relink, tags, and lock/wake in fullscreen. Mark device-dependent checks as pending if no authorized test device is available; build success is not a substitute.

Required final checks:

```sh
./gradlew :app:testVerificationUnitTest :app:lintRelease :app:assembleRelease
```

Run `:app:connectedVerificationAndroidTest` when a test device/emulator is available and the SQLite/repository instrumentation tests have been added. The verification variant has a separate application ID; also use unique test storage inside it, never the user's campaign library, for corruption/reset tests. Build a debug APK for tablet upgrade testing; preserve the existing signing policy. Update the diagnostic version for delivered test builds using the repository's current versioning convention.

Deliver a short final report listing implemented stages, test/build results, device and timing comparisons, pending validation, and remaining known limitations. Update `README.md`, `CHANGELOG.md`, and `ROADMAP.md` according to their current roles to reflect actual behavior and completed work; retain unfinished items honestly.

Completion means all stages' correctness criteria are met, the original reader/search workflows remain usable, and the performance checks show no unexplained regression. Shared UI factories, typed menu actions, renaming `PdfDocument`, splitting catalog summaries from full indexes, and other broad cleanup can follow when those areas next change; they are not prerequisites for this pass.
