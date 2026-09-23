# Third-party notices

These are text notices, not vendored library source. They are committed with the
app and bundled under `assets/licenses/` by the existing APK build. No source
download or source-archive task is part of the build.

`NOTICE` at the repository root lists the exact runtime versions and their source
links. When updating a dependency, review its notices along with that inventory.

## Provenance

- `Apache-2.0.txt`: Apache 2.0 license text from Markwon v4.6.2.
- `commonmark-BSD-2-Clause.txt`: commonmark-java 0.13.0 `LICENSE.txt`.
- `kotlin/`: Kotlin v2.2.10 `license/` files for its copyright and the GWT,
  Guava, ThreeTenBP and Boost-derived code in the standard library.
- `mupdf/thirdparty/` and `mupdf/resources/`: matching paths from MuPDF 1.28.0,
  native revision `205b8cf43551279d1215e88fe2845c5d595bade9`, with its pinned
  submodules. Some notices cover optional upstream components, not features
  enabled by NaIgre (for example, their presence does not mean NaIgre has OCR).
- `mupdf/Hyphenation-notices.txt`: the files in MuPDF's
  `resources/hyphen/license/`, with their original names and text.
- `mupdf/CMap-notices.txt`: `%%Copyright:` blocks from
  `resources/cmaps/`, grouping identical blocks and listing the source files.
- `mupdf/Embedded-code-notices.txt`: license comment blocks in MuPDF's native
  code and FreeType's embedded hashing/zlib code, with source filenames.
- `mupdf/Bidi-notice.txt`: the opening notice in `source/fitz/bidi-imp.h`.

The original license texts and upstream source references remain authoritative.
Keep corresponding-source links on the release page as described in RELEASING.md;
retaining these notices alone is not a substitute for providing source access.
