# NaIgre

A fast, local, read-only reference reader for Android: find information across PDF books, Markdown notes and image albums without losing your place.

NaIgre is built for looking things up at the table, especially at TTRPG sessions. It searches bookmarks (and other text) in multiple books at the same time and allows to present multiple pages on one screen by using tabs and split-screen.

**Status: public beta.** A version in the source tree does not mean an APK has been published yet.

This README describes the **26.09.1 release candidate**. See GitHub Releases for
published APKs; the `26.09.0` APK does not include the new language and library UI features below.

## Install

Requires **Android 8.0 or newer (API 26+)**. Phones and tablets are supported.

### Download an APK

1. Visit [GitHub Releases](https://github.com/wund0r/naigre/releases).
2. Choose a release and download its `NaIgre-YY.MM.PATCH.apk` asset. The source-code ZIP is not an installable app.
3. Open the APK and, if Android asks, allow your browser or file manager to install apps.
4. For updates, install the newer signed APK over the existing public build. Do not uninstall first.

### Updates with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) checks GitHub Releases for updates;
NaIgre itself has no updater or network permission.

1. Install Obtainium from its official project.
2. Add `https://github.com/wund0r/naigre` as the app source and allow prereleases while NaIgre is in beta.
3. Let Obtainium install NaIgre and approve Android's installation permission when requested.

[Add NaIgre to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22wund0r.naigre.reader%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fwund0r%2Fnaigre%22%2C%22author%22%3A%22wund0r%22%2C%22name%22%3A%22NaIgre%22%2C%22additionalSettings%22%3A%7B%22includePrereleases%22%3Atrue%7D%7D)
pre-fills the source with prereleases enabled. Obtainium's
[update behavior](https://wiki.obtainium.imranr.dev/app_tracking/) depends on Android
and your settings; an update may still require confirmation.

## NaIgre 101

### Interface language

NaIgre supports English and Russian, following the device's language
preferences. On Android 13+, you can also choose a language just for NaIgre in
Android's app language settings. On Android 12 and earlier, changing NaIgre's
language requires changing the device language; there is no in-app language picker.
Documents, filenames and tags are never translated. Technical diagnostics remain
English.

### Getting started

1. Download files you need to reference, put them in one directory on your device.
2. Open Naigre. In **Library**, use **Add folder** to add the campaign directory (or **Add File** for individual file)
3. Folder import searches recursively:
    - PDFs and notes become individual items;
    - Directories containing images become albums, with each image a navigation entry;
    - Matching `book.pdf` / `book.txt` pairs can supply an external TOC;
4. Select the items you want **on the table**, then choose **Read**.
5. Tap the bottom-right Search button, type part of a heading or filename and open a result.
6. Hold Search to go directly to **Full text**. Its results stay in a reference pane
   while you open matches and compare passages.

The **library** remembers known items and their metadata. The **table** is the
selected subset searched during the current session. Taking an item off the table
retains its tags, TOC and visit history. Library tags let you filter or add/replace
a whole campaign's table in one action.

Library cards share the same size, with a compact preview on the left: first PDF
page, first album image, or the first few Markdown headings. Images fit without
cropping. Previews load in the background and are cached; missing sources show a
cached preview or a placeholder. Detected source updates/explicit refreshes update
previews too. Forgetting an item clears its cached previews, not its source files.

## Useful controls

| Action | Behavior |
| --- | --- |
| Tap Search | Open Navigate: fuzzy bookmark/heading search, image names and navigation entries for documents without bookmarks |
| Hold Search | Open Full text: search PDF text and Markdown across the table |
| Tap a result's `+` | Open its tab in the foreground |
| Hold `+` | Open in the background; the action becomes `✓` |
| Tap `✓` | Switch to that result's existing tab |
| Tap a navigation result's reference action `▥` | Open it in the reference pane |
| Hold a tab or document reference indicator | Tab/reference actions, including returning to its starting position |
| Tap a blank document area | Hide/show reader controls; Search remains available at bottom-right |
| Tap a Markdown heading | Cycle collapsed → descendant headings only → fully expanded; a leaf heading simply toggles |
| Tap the page indicator | Jump directly to a page |

Book colors identify the same item across library, tabs and search. In the book's
**⋮ → Choose color**, pick a preset or **Custom…**: adjust hue, saturation and
brightness, or enter `#RRGGBB`. The swatch and tab preview update immediately;
**Apply** saves the color, while **Cancel** leaves it unchanged.

The search book-selector row narrows either search mode to one item. Switching modes retains
your query; reopening Search normally starts empty.

Navigate uses smartcase: lowercase is case-insensitive, capitals make it
case-sensitive. Visit frequency helps order navigation results. Full-text search
is case-insensitive. Use **Show more** when further matches are available. Images have no OCR/text search.

The reference pane is on the right in Wide layout and above the main reader in
Tall layout. Change layout/theme/fullscreen in **Menu → Interface**.
The Library's top-left **⋮** menu also provides Reading view, Interface and
Diagnostics, without needing to open a book. Changing theme keeps the Library
open with the same tag filter.
Its filter strip groups All, On table and Untagged first; custom tags follow a
divider and have outline tag icons. The whole strip scrolls horizontally.

Markdown supports headings, tables and session-only folding. Heading/search jumps
reveal folded destinations. If you reveal a child's body in headings-only mode,
the next parent tap collapses the subtree.

## Files, privacy and recovery

- Files remain at their original Android document-provider locations; NaIgre
  references them instead of importing permanent copies. It never edits originals.
- Library metadata, visit counts and derived text indexes stay in app-private storage.
  NaIgre has no analytics, cloud sync or internet permission.
- Source changes are checked when reopening/resuming. If a provider does not report
  changes reliably, use the item's refresh action; rescan folders after changing
  folder contents or matching TOCs.
- Moving/deleting a source or revoking permission can break access. Relink a moved
  PDF/note, or reselect/rescan its folder. NaIgre keeps the library entry for recovery.
- **Forget item and history** removes NaIgre's data for that item, not the source.
  Forgetting a tagged item removes it globally, even if it has other tags; review
  the confirmation.
- App backup is disabled. Uninstalling or clearing app storage loses local metadata.
- **Fullscreen above lock screen** is opt-in. Enabling it intentionally makes
  NaIgre's visible content usable while the device remains locked.

## External TOC files

Flat entries use a title and a 1-based physical PDF page number:

```text
Great Cavern::126
Temple Entrance::132
```

Optional headings add hierarchy without changing flat-entry syntax:

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

`#` through `######` set the hierarchy. A heading with `::page` is navigable;
one without a page only groups later entries. Same/higher headings clear deeper
levels; blank lines keep the hierarchy; `---` resets it. Leading `#` is a heading,
not a comment. Printed page labels may differ from the PDF's physical page index.

Import or replace a TXT TOC from the item's Library menu. Folder import can
associate a same-stem TXT automatically. The association survives removing the
book from the table and restarting NaIgre.

## Current limits

- Read-only: no annotation creation, document editing or Obsidian-style wiki links.
- No OCR: scanned PDFs need an existing text layer for full-text results; maps are searched by name.
- Password-protected PDFs are not supported.
- Markdown notes are limited to 8 MB; embedded local assets are not loaded.
- Large maps use bounded-resolution decoding, not tiles; extreme zoom can still look soft.
- Image/Markdown viewports and Markdown folds are session-only.
- Add documents from inside the Library. Android's **Open with** and Share integration are not implemented.
- No cloud sync or library backup/export. This is still beta software.

## Report a problem

Open a [GitHub issue](https://github.com/wund0r/naigre/issues) with:

- Version and build code from **Menu → Diagnostics**.
- Device model and Android version.
- Steps, expected behavior and what happened instead.
- File type, approximate size/page count, and whether full-text indexing had finished.
- Relevant diagnostics (the text is selectable) or a screenshot.

Redact private filenames, annotations and note text. Do not upload commercial books
or personal campaigns; a small synthetic/public-domain reproducer is preferable.

## Build and contribute

On Linux, install a **JDK 17+** and **Android SDK Platform / Build Tools 36**.
Set `ANDROID_HOME` to your SDK directory, or set `sdk.dir` in your untracked
`local.properties`, then run:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This installs `wund0r.naigre.reader.debug`, not the public app. Neither a release
key nor signing credentials are needed.

- [Testing and device checklists](TESTING.md)
- [Signing and manual releases](RELEASING.md)
- [Changelog](CHANGELOG.md)
- [Product roadmap](ROADMAP.md)

NaIgre uses MuPDF, Markwon and commonmark-java. Application code is
[AGPL-3.0-or-later](LICENSE); third-party licensing and source references are in
[NOTICE](NOTICE). Review those requirements when distributing your own build.
