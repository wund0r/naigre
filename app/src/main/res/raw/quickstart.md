# NaIgre: help

NaIgre lets you read several files at once and switch between them quickly. It only reads local files and does not modify them.

## Quickstart

Here's how to get started. More features are described below.

1. Tap **Add file** at the top and choose a PDF you'd like to read.
2. Tap **Read** at the top to open the book. You can turn pages by swiping or tapping the left and right edges of the screen. But page turning isn't the main way to get around!
3. Tap the Search button at the bottom right (it's always there) to open the search overlay.
4. Enter a heading from the book to see matching results. If your PDF has no table of contents, I'll explain below how to add one quickly and easily.
5. Tap a result to jump to it. **+** opens the result in a new tab (tabs are at the bottom), while **◨** opens it in the reference pane, splitting the screen in two.
6. Hold the Search button to search the full text. Results will appear in the reference pane almost immediately.
7. Like it? Gather all your campaign files into one folder and tap **Add folder** to search everything, everywhere, all at once!

Here's an example campaign folder:

```
mines_of_phyrphyrphyr/
├── rules.pdf
├── monsters.pdf
├── monsters.txt <- custom table of contents, explained below
├── notes.md
├── house_rules.md
└── maps/
    ├── road.jpg
    ├── tavern.jpg
    └── cult_hideout.jpg
```

> NaIgre reads files directly — don't remove them from your device.

Now you can move between your campaign materials in a flash! More features are described below.

## What files are supported?

- **PDF:** links, bookmarks, and annotations are supported.
- **Image folders:** each folder is an album, and you can find any image in it by filename. One album counts as one book.
- **Markdown:** one `.md` note counts as one book. NaIgre treats Markdown headings as bookmarks, so you can find them quickly. Embedded images are not displayed. Use wiki links below to open files.

## Links from your notes

`[[downtime.pdf#page=12|Pazuzu]]` displays **Pazuzu** and opens physical PDF page 12. `[[maps/level 3.jpg]]` displays **level 3.jpg** and opens that image. You can also link to a PDF or Markdown note without a page number to open its start.

Tap a link to open it in a new tab or switch to its existing tab; an existing tab keeps its reading position. Hold the link to open it in the reference pane. NaIgre searches files on the table first. If a match is only in the Library, it asks before adding the book or image album to the table. If several files match, choose the one you want.

Paths such as `maps/level 3.jpg` match campaign-relative paths recorded when adding a folder. Rescan older folder imports to record PDF/Markdown paths. Inside a Markdown table cell, escape the alias separator: `[[downtime.pdf#page=12\|Pazuzu]]`.

## Interface tips (and more)

- Tap **+** in a search result to open it in a new tab. Hold it to open the result in a new tab in the background, so you can open several tabs from one search. Results that are already open show **✓** instead of **+**; tap it to switch to the corresponding tab.
- Hold a tab name or reference indicator for more actions.
- Tap headings in Markdown notes to fold them. It works here too :)
- Tap the page number at the top right to jump to a specific page.
- Turn on fullscreen in **Menu → Interface**. The app will even appear over the lock screen, so you can read without unlocking your tablet or phone.
- The app supports landscape and portrait orientations; the reference pane moves to a different position when the layout changes.
- Change the theme in **Menu → Interface**.
- **Menu → Reading view** lets you read PDFs as spreads and skip the cover if the pages don't line up properly.
- You can use Syncthing or Resilio Sync to keep your campaign folder in sync across devices.

## Campaign management: table and tags

By default, every item you add goes on the **table**, meaning you can access them all at once. If you don't need a particular book on the table right now, uncheck it in the **Library**.

If you'd like separate tables for different campaigns, use **tags**. Assign tags to an item from its **⋮** menu. Your tags appear at the top. Tap a tag to show its items, or hold it for group actions and to select all its items.

Each item gets its own color by default, so it's easy to tell which book a search result came from. You can choose a custom color for a book from its **⋮** menu in the Library.

You can also **Forget** a book. This removes it and all its associated data from NaIgre, but leaves the original file untouched: the app is read-only.

## If your PDF has no table of contents

You can fix that without editing the PDF. Put a `.txt` file next to it: NaIgre reads each line as a bookmark.

The syntax is a bookmark name, two colons, and a page number:

```text
Cult Hideout::40
Level 2::80
Great Cavern::123
Appendix::150
```

Use the PDF's actual page numbers, starting at 1, rather than the numbers printed on the pages: they don't always match.

If your table of contents needs a hierarchy, use `#` headings as in Markdown:

```text
# Cult Hideout::40
## Level 2::80
Great Cavern::123
---
Appendix::150
```

In this example, searching for “great cavern” returns “Cult Hideout > Level 2 > Great Cavern”. Headings without page numbers group entries; `---` resets the hierarchy. Put a matching `.txt` file, such as `book_name.txt`, beside `book_name.pdf` to import the table of contents automatically when you add the folder. Or import the `.txt` file manually from the book's **⋮ → Import txt TOC** menu.

## Files and troubleshooting

- Changed a file? NaIgre will probably show the new version automatically. If it doesn't, you can rescan the item from the book's **⋮** menu.
- Full-text search in PDFs needs a text layer. It won't work on a scanned book until you run OCR on it with another tool.
- Uninstalling the app or clearing its data removes your library information and history. The files and notes themselves are not deleted.
- Have feedback? Message @wund0r on Telegram or email naigre@testchamber.one.
