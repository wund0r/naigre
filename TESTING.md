# Testing NaIgre

## Current release candidate: 26.09.0 (63)

Build results do not substitute for device acceptance. Record the date, device,
Android version, source revision and cold/warm index/cache conditions alongside results.
No connected tablet was visible during initial release preparation; do not assume
the device checklist has passed.

### Preparation results — 2026-09-22

- Fresh `testVerificationUnitTest` run: 23 tests, 0 failures/errors/skips.
- Debug and release lint: 0 errors, 37 warnings each; warnings remain outside this release-preparation change.
- Debug, verification and signed release APK builds passed. Unsigned release and
  development/test builds also passed with no release credentials configured.
- Public APK verified as `wund0r.naigre.reader`, `26.09.0 (63)`, label `NaIgre`,
  non-debuggable, with the configured private release certificate. ZIP alignment
  and bundled license assets checked. Debug/verification IDs and labels verified
  by inspecting their APKs (coexistence on a device is still pending).
- Missing signing configuration, wrong password, absent key alias and the Android
  debug certificate were rejected. Existing signing-directory overwrite was refused.
- Historical README checklists were preserved verbatim below; `git diff --check` passed.
- Signing-key backup confirmed by the owner. Backup restoration/fingerprint was
  not independently checked by the agent.
- Runtime dependency versions reviewed; repository notices expanded for MuPDF's
  embedded libraries/data and Kotlin's third-party code. Exact upstream source
  links are retained in NOTICE; no source-download/build automation is required.
- Pending: device/instrumentation acceptance, real release-to-release update,
  Obtainium installation/update, independent key backup verification, clean tagged
  source and the final corresponding-source/native-notice handoff before publication.
- No APK was installed, no user data was reset, and no tag/release was published.

### Automated checks

```sh
./gradlew :app:testVerificationUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:lintRelease :app:assembleRelease
./gradlew :app:connectedVerificationAndroidTest
```

The first two commands need no release key; with no signing configuration, the
release APK is unsigned and is **not distributable**. The last command requires
a connected device/emulator and installs only the disposable
`wund0r.naigre.reader.verification` app/test package. It must never target the
production package or user campaign data.

Debug installs use `wund0r.naigre.reader.debug` and the label **NaIgre Debug**.
Verification uses **NaIgre Verification**. Both should coexist with public NaIgre.
Tests use synthetic fixtures; do not add personal PDFs/maps to Git.

### Signed release acceptance

Use [RELEASING.md](RELEASING.md) to prepare the actual signed APK.

1. Verify Diagnostics reports the candidate version/build code. Check launcher name
   **NaIgre**, package identity and signing certificate using the release task.
2. On a clean test profile/device, install the signed APK. Do not uninstall the
   user's existing prototype without an explicit go-ahead.
3. Add a representative large PDF, a note with nested headings/tables and a large
   map album. Try a recursive campaign folder with a matching TXT TOC.
4. Test Navigate/Full text with All/one-book scope. Verify progressive coverage,
   counts, Show more, foreground/background result tabs and persistent results.
5. Follow PDF links, use document/search references in Wide/Tall layouts, switch
   tabs, and check map zoom/pan plus Markdown folding and jumps.
6. Close/replace the search reference and verify temporary highlights disappear
   while real PDF annotations remain. Run the v0.37.0 folding cases below.
7. Background, rotate, restart and lock/wake (including opt-in above-lockscreen
   mode). Useful saved state should return; session-only folds/viewports may reset.
8. Refresh/relink a changed source, rescan a folder, edit tags and remove an item
   from the table. Verify histories and other books remain usable.
9. Exercise a missing/malformed document and missing source permission; errors must
   not erase the library or crash the app.
10. Compare a few repeated cached tab switches and search opens with the previous
    build. Note first-result time and reference responsiveness during indexing/scans.
    Investigate reproducible slowdowns rather than imposing arbitrary timing thresholds.
11. Install the next higher-code candidate signed with the same key over this
    one (`adb install -r`, no uninstall). Confirm library, tags, visits and tabs
    survive. Reinstalling the identical APK alone is not a version-upgrade test.
12. On the final published candidate, check Obtainium prerelease detection,
    installation and a subsequent update. This stays pending until releases exist.

### Release-workflow negative checks

- Without `NAIGRE_SIGNING_PROPERTIES`, `:app:checkReleaseKey` and
  `:app:preparePublicRelease` fail clearly; debug/tests and unsigned release checks still work.
- Wrong credentials/alias and an Android debug certificate are rejected.
- APK verification checks package, version, launcher label, non-debuggable state
  and the actual signature against the configured certificate.
- `:app:createReleaseKey` refuses an existing output directory and a directory
  inside the repository. Never test destructive/reset operations against a real key.
- A dirty worktree may produce a local candidate, but `release-info.txt` and the
  task output must disclose it. Commit and rebuild before publishing.

## Historical feature acceptance checklists

These checklists were moved verbatim from the former README. They describe the UI
and behavior at their original versions, not necessarily today's layout. Retain
them as regression history; use the current checklist above for release acceptance.

## v0.37.0 tablet test checklist

1. Open full-text results in PDF and Markdown tabs, including a background tab. Close the search reference, switch tabs, and confirm temporary highlights stay cleared. Existing PDF annotations must remain.
2. Repeat by replacing the search reference with a document reference and by starting a new query. Close the reference immediately after opening a result; a delayed highlight must not return.
3. In a Markdown note with nested headings and a table, collapse a heading (`▸`). Tap again: every descendant heading is visible, but all body text and tables remain hidden (`▿`). Tap again: all content, including tables, appears (`▾`). The next tap collapses it. A heading without subheadings skips headings-only mode.
4. In headings-only mode, open a child so its text is visible. The next parent tap must collapse everything; reopening it starts with headings only again. Opening the whole subtree must clear individual child folds. Open the same note in another tab/reference and verify their folds are independent.
5. Find a folded heading in Navigate or text inside it in Full text. Open the result and confirm its heading path and body are visible. The next parent tap should collapse the subtree. Check the same path using an already-open result's checkmark and Return to tab/reference start.
6. Scroll through a folded note, switch tabs, change Wide/Tall layout, and hide/show chrome. Confirm folds and reading position survive. Heading taps must not toggle chrome; scrolling, text selection, and links should still work.
7. Update a note externally and refresh it. Its changed content should display with folds reset. Restarting the app also resets session-only folds.

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
