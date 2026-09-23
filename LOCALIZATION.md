# Localization

Stages 1 and 2 provide resource-backed English UI, Russian translations and
supported-language registration. Stage 3 adds verified layout fixes and device
coverage for language changes. Remaining owner acceptance is listed below.
There is no custom language picker or new runtime dependency.

## Language selection

- English defaults live in `values/strings.xml`; Russian lives in
  `values-ru/strings.xml`. English is the fallback when no supported language matches.
- Android 13+ can choose English or Russian through the system's per-app language
  settings. `res/xml/locale_config.xml` advertises those two languages through the
  manifest's `android:localeConfig`; earlier Android versions ignore that attribute.
- On Android 12 and earlier, NaIgre follows the device's language preferences.
  There is no independent in-app override.
- Locale changes use ordinary Activity recreation. Do not add `locale` to
  `configChanges` without also handling all resource-backed UI and generated titles.
- UI language changes do not change search queries, document content, library IDs,
  stored keys or search-index versions, and do not require reindexing.

See Android's [per-app language documentation](https://developer.android.com/guide/topics/resources/app-languages).

## UI text

- Keep complete English defaults in `app/src/main/res/values/strings.xml`.
  Keep Russian translations under `values-ru/strings.xml` in sync with the same keys.
- Use `getString` with numbered placeholders, and `getQuantityString` for counts.
  `MainActivity.quantityString` supplies the count as the default format argument;
  explicitly pass all arguments for messages containing more than one value.
- Plural selection uses the actual total, not the displayed result limit. Russian
  translations include the appropriate `one`, `few`, `many` and `other` forms.
- Keep full sentences together. Separate count phrases may be joined by bullets
  or newlines, but do not append English suffixes or assemble grammatical fragments.
- A few multi-count progress summaries use neutral labels instead of plurals;
  their resource comments explain the narrow lint exemptions.
- Quoted resource values preserve leading spaces/newlines used in summaries.
  Preserve placeholder types and intentional whitespace when translating.
- `NaIgre` and the `Aa` smartcase symbol are non-translatable. Do not translate
  filenames, document text, bookmarks, tag names, stored keys or search terms.

Ordinary controls, menu/error titles, accessibility descriptions, library/search
summaries and expected file errors are resource-backed. Raw exception details,
developer diagnostics, internal invariants and diagnostic-only render statistics
remain English. Parser-internal synthetic Markdown section names remain canonical
metadata, not language-dependent search or bookmark identities.

Expected document failures use `DocumentReadProblem` and `DocumentReadException`.
The UI resolves their resource IDs; diagnostics retain stable problem codes.
Do not identify an error or action by comparing its translated message.

## Russian terminology

- Library: `Библиотека`; table: `стол`; mixed library items: `материалы`.
- Compact search modes: `Закладки` / `Текст`; descriptions explain bookmark/filename
  navigation versus full-text search.
- Layout: `Композиция панелей`, with `Автоматическая`, `Горизонтальная` and
  `Вертикальная` choices. This describes pane arrangement, not screen rotation.
- Reference tab/pane: `Выноска`, with actions such as `Открыть в выноске` and
  `Закрыть выноску`. This names NaIgre's reference pane, not a document footnote.
- Taking an item off the table: `Убрать со стола`. Forgetting its app-owned state:
  `Забыть`. Deleting a tag: `Удалить тег`. Keep these actions distinct and preserve
  confirmations that source files are not deleted.
- Relinking: `Указать файл` / `Указать другой PDF`; this changes NaIgre's source
  association, not the original file.

The owner approved `Выноска` and `Композиция панелей`. Final wording review during
ordinary reading is still useful; automated checks cannot judge translation quality.

## Generated tab names

`ReaderTab` stores a `TabLabelKind` and, where needed, the title's destination page.
The activity formats these only for display. Scrolling a tab must not change the
page in its original title. Assign document titles through `setTextTitle`, and
generated titles through `setTitle`; use `ReaderTab.start` for a new start tab.

Preferences and saved-instance state retain those fields. Older saved labels
remain literal text: do not guess that a bookmark called `Start` or `Page 42` is
an automatically generated label. Such legacy labels stay unchanged; newly
generated labels can follow the active language. No library/index migration or
visit-key changes are required.

Library menu actions use resource IDs as local action identifiers, not their
display text. Those IDs are not persisted. Other menus already use callbacks or
stable positions.

## Verification

```sh
./gradlew :app:testVerificationUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedVerificationAndroidTest
```

The device command targets only the disposable verification package. Tests cover
generated/literal titles, destination-page stability, preferences/Bundle round
trips, legacy labels and visit counts, English/Russian plural forms, placeholder
formatting, Unicode user content, apostrophes and intentional whitespace.
Catalog checks enforce translation coverage, placeholder positions/types, Russian
plural forms and the manifest's English/Russian language registration.

Russian resource tests use scoped configuration contexts, without changing device
language or the public app. They cover regional Russian selection, English fallback,
unchanged branding, counts including teen exceptions and values over 20, plus
destructive-action wording. Search checks cover Cyrillic navigation smartcase and
SQLite full-text case folding, phrases and prefixes. No search algorithms changed.

### Stage 3 device verification — 2026-09-23

- Ran the UI checks on Samsung SM-T860 / Android 12 and Motorola Razr 50 Ultra /
  Android 16. Measured and captured actual app views at 360 and 740 dp widths,
  using English/Russian, light/dark themes and font scales 1.0/1.3. Search reference
  checks exercise Tall at 360 dp and Wide at 740 dp. These are bounded layout
  measurements, not a claim to have tested every physical rotation/fold state.
- Fixed the Library title/action row overflowing in Russian: it stays on one row
  when it fits, or moves import buttons below the title and Read on narrow screens.
- Search modes and the reference Edit action keep padding and grow to fit their
  labels; All keeps its minimum width but may grow. The reference header reserves
  room for the actual Edit width. Duplicate search counts were removed from that
  header; the existing status row still shows results/progress.
- Reviewed captured search, Library, layout-choice and forget-confirmation screens.
  Checks catch clipped labels and overlapping reference controls, exercise query
  retention between search modes, and cancel the destructive-action dialog.
- Android 12: exercised real Activity recreation with test-only locale overrides.
  The tablet's system language was deliberately not changed.
- Android 16: used the native per-app language API to switch English → Russian →
  English. Generated titles changed; literal titles, stored tabs, selection, tags,
  source revision, visit counts and the existing text-index run survived.
- Visually confirmed that Android's app-language settings advertises English and
  Russian for NaIgre Verification. Returned the verification app to System default.
- Synthetic Cyrillic Markdown was rendered and searched through the UI. A generated
  Cyrillic PDF was rendered by MuPDF, extracted and searched in SQLite, including
  case-insensitive words, a phrase and a prefix. No personal documents were used.

`LocalizationTestRunner` applies optional locale/font overrides only to test
Activities and keeps their windows awake. It is packaged only in the test APK.
The tests refuse to overwrite an existing verification catalog, remove their
fixtures, restore preferences/app language, and never change system language or
font settings. UI screenshots are Gradle outputs under
`app/build/outputs/connected_android_test_additional_output/`, not source assets.
Some UI tests use reflection to reach private reader controls; update those test
helpers when renaming/extracting the corresponding controls.

Remaining owner acceptance before release:

1. Read a real campaign in Russian and review terminology, longer filenames and
   uncommon error messages. Check physical rotation/folding and any larger font
   setting you normally use; the automated matrix is intentionally limited.
2. If desired, verify a full system-language change on an Android 12 test profile.
   The current checks cover resource selection and Activity recreation without
   changing the owner's device settings.
3. Language/theme changes recreate the reader. Durable tabs/history are covered;
   preserving transient search/reference panes or session-only viewports across
   recreation was not added as part of localization.
