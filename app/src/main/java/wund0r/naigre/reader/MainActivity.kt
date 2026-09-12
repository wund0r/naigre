// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import wund0r.naigre.reader.navigation.BookmarkEntry
import wund0r.naigre.reader.navigation.BookmarkIndex
import wund0r.naigre.reader.navigation.BookmarkSource
import wund0r.naigre.reader.navigation.ExternalTocParser
import wund0r.naigre.reader.navigation.FuzzyMatcher
import wund0r.naigre.reader.navigation.ReaderSessionRepository
import wund0r.naigre.reader.navigation.ReaderTab
import wund0r.naigre.reader.markdown.MarkdownDocument
import wund0r.naigre.reader.markdown.MarkdownEngine
import wund0r.naigre.reader.pdf.ImageCollectionDocument
import wund0r.naigre.reader.pdf.ImageDecodePolicy
import wund0r.naigre.reader.pdf.MuPdfDocument
import wund0r.naigre.reader.pdf.PdfAnnotationInfo
import wund0r.naigre.reader.pdf.PdfDocument
import wund0r.naigre.reader.pdf.PdfLinkInfo
import wund0r.naigre.reader.pdf.RenderedPdfPage
import wund0r.naigre.reader.render.PageBitmapCache
import wund0r.naigre.reader.render.PageCacheKey
import wund0r.naigre.reader.render.PageTurnMode
import wund0r.naigre.reader.render.ReaderSurface
import wund0r.naigre.reader.render.MarkdownSurface
import wund0r.naigre.reader.render.SpreadLayout
import wund0r.naigre.reader.search.ExtractedTextPage
import wund0r.naigre.reader.search.TextSearchHit
import wund0r.naigre.reader.search.TextSearchIndexRepository
import wund0r.naigre.reader.search.TextSearchSnapshot
import wund0r.naigre.reader.table.BookRecord
import wund0r.naigre.reader.table.BookLibraryRepository
import wund0r.naigre.reader.table.CURRENT_BOOK_INDEX_VERSION
import wund0r.naigre.reader.table.DocumentMetadata
import wund0r.naigre.reader.table.DocumentMetadataReader
import wund0r.naigre.reader.table.ImageFileRecord
import wund0r.naigre.reader.table.LibraryFolderRecord
import wund0r.naigre.reader.table.LibraryItemKind
import wund0r.naigre.reader.table.LibraryTagRecord
import wund0r.naigre.reader.table.sourceRevisionKey
import wund0r.naigre.reader.table.sourceMetadataChanged
import wund0r.naigre.reader.table.sourceMetadataChangeSummary
import wund0r.naigre.reader.table.sourceMetadataNeedsUpdate
import wund0r.naigre.reader.theme.ReaderThemeMode
import wund0r.naigre.reader.theme.UiPalette
import java.util.UUID
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val CHROME_TOUCH_SIZE_DP = 48
        private const val CHROME_SURFACE_HEIGHT_DP = 44
        private const val CHROME_VERTICAL_INSET_DP =
            (CHROME_TOUCH_SIZE_DP - CHROME_SURFACE_HEIGHT_DP) / 2
        private const val NAVIGATE_RESULT_PAGE_SIZE = 100
        private const val REQUEST_OPEN_DOCUMENT = 1001
        private const val REQUEST_OPEN_TOC = 1002
        private const val REQUEST_RELINK_SOURCE = 1003
        private const val REQUEST_OPEN_FOLDER = 1004
        private const val PREFS = "pdf-jump"
        private const val PREF_SPREAD = "phase4-spread"
        private const val PREF_SPREAD_SKIP_COVER = "phase4-spread-skip-cover"
        private const val PREF_PAGE_TURN_MODE = "phase4-page-turn-mode"
        private const val PREF_READER_LAYOUT = "reader-layout"
        private const val PREF_THEME = "reader-theme"
        private const val STATE_PAGE = "page"
        private const val STATE_SPREAD = "spread"
        private const val STATE_SPREAD_SKIP_COVER = "spread-skip-cover"
        private const val IMAGE_CACHE_WIDTH_SENTINEL = 1

        private val BOOK_COLORS = intArrayOf(
            0xff79a7d3.toInt(),
            0xffd08b73.toInt(),
            0xff82b39a.toInt(),
            0xffb397d6.toInt(),
            0xffd1ad64.toInt(),
            0xff70b4bb.toInt(),
            0xffc383a1.toInt(),
            0xff9aa86e.toInt(),
        )
        private val BOOK_COLOR_NAMES = arrayOf(
            "Blue", "Coral", "Green", "Violet", "Amber", "Teal", "Rose", "Olive",
        )
    }

    private data class BookmarkRowHolder(
        val colorDot: View,
        val title: TextView,
        val detail: TextView,
        val plus: Button,
        val alongside: Button,
    )

    private data class TextSearchRowHolder(
        val colorDot: View,
        val title: TextView,
        val detail: TextView,
        val snippet: TextView,
        val plus: Button,
    )

    private enum class SearchMode {
        BOOKMARKS,
        TEXT,
    }

    private enum class ReaderLayoutMode(val label: String) {
        AUTOMATIC("Automatic"),
        WIDE("Wide"),
        TALL("Tall"),
    }

    private enum class LibraryFilterKind {
        ALL,
        ON_TABLE,
        TAG,
        UNTAGGED,
    }

    private data class LibraryFilter(
        val kind: LibraryFilterKind,
        val tagId: String? = null,
    )

    private data class TextSearchSession(
        val query: String,
        var scopeBookId: String?,
        var bookIds: List<String>,
        val results: MutableList<TextSearchHit> = mutableListOf(),
        var resultLimit: Int = TextSearchIndexRepository.RESULT_PAGE_SIZE,
        var totalMatches: Int = 0,
        var indexedPages: Int = 0,
        var totalPages: Int = 0,
        var readyBooks: Int = 0,
        var searchInFlight: Boolean = false,
        var error: String? = null,
        val preciseContexts: MutableMap<String, BookmarkEntry?> = HashMap(),
    )

    private data class SearchHighlightTarget(
        val bookId: String,
        val pageIndex: Int,
        val terms: List<String>,
        val caseSensitive: Boolean,
        val anchorKey: String,
    )

    private data class ReferenceLocation(
        val bookId: String,
        var pageIndex: Int,
        val label: String,
        val anchorKey: String,
        var originPageIndex: Int = pageIndex,
        var originDestinationY: Float? = null,
        var pendingDestinationY: Float? = originDestinationY,
    )

    private data class PendingViewportRestore(
        val bookId: String,
        val pageIndex: Int,
        val viewport: ReaderSurface.ViewportState,
    )

    private data class EdgeInsets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private data class FolderDocument(
        val parentUri: String,
        val parentName: String,
        val relativeDirectory: String,
        val uri: Uri,
        val fileName: String,
        val mimeType: String?,
        val size: Long?,
        val lastModified: Long?,
    )

    private data class FolderBookChange(
        val book: BookRecord,
        val isNew: Boolean,
        val contentChanged: Boolean,
        val textIndexChanged: Boolean,
        val tocImported: Boolean,
        val rejectedTocLines: Int,
    )

    private data class FolderScanResult(
        val folderCount: Int,
        val discoveredPdfCount: Int,
        val discoveredMarkdownCount: Int,
        val discoveredAlbumCount: Int,
        val discoveredImageCount: Int,
        val changes: List<FolderBookChange>,
        val unchangedCount: Int,
        val skipped: List<String>,
        val failures: List<String>,
    )

    private val renderWorker = Executors.newSingleThreadExecutor()
    private val prefetchWorker = Executors.newSingleThreadExecutor()
    private val textIndexWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "naigre-text-index")
    }
    private val textSearchWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "naigre-text-query")
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var pdf: PdfDocument? = null
    @Volatile private var pdfBookId: String? = null
    @Volatile private var prefetchPdf: PdfDocument? = null
    @Volatile private var prefetchBookId: String? = null
    private var currentPage = 0
    private var spreadMode = false
    private var spreadSkipCover = false
    private var readerLayoutMode = ReaderLayoutMode.AUTOMATIC
    private var chromeVisible = true
    private var immersive = false
    private var destroying = false
    @Volatile private var renderGeneration = 0
    @Volatile private var primaryOpenGeneration = 0
    @Volatile private var primaryOpenTargetBookId: String? = null
    @Volatile private var prefetchGeneration = 0
    @Volatile private var referenceRenderGeneration = 0
    @Volatile private var lastStatus = ""
    @Volatile private var lastLinkDiagnostic = "No link followed in this process"
    @Volatile private var lastSourceRefreshDiagnostic = "No source refresh in this process"
    @Volatile private var lastTextSearchDiagnostic = "No full-text search in this process"

    private lateinit var pageCache: PageBitmapCache
    private lateinit var imageDecodePolicy: ImageDecodePolicy
    private var deviceMemoryClassMb = 0L
    private var lowRamDevice = false
    private lateinit var uiPalette: UiPalette
    private var themeMode = ReaderThemeMode.DARK

    private val bookmarkIndex = BookmarkIndex()
    private val bookmarkVisitCounts = HashMap<String, Int>()
    private lateinit var bookRepository: BookLibraryRepository
    private lateinit var documentMetadataReader: DocumentMetadataReader
    private lateinit var readerSessionRepository: ReaderSessionRepository
    private lateinit var textSearchIndex: TextSearchIndexRepository
    private lateinit var markdownEngine: MarkdownEngine
    private val books = mutableListOf<BookRecord>()
    private val libraryFolders = mutableListOf<LibraryFolderRecord>()
    private val libraryTags = mutableListOf<LibraryTagRecord>()
    private val selectedBookIds = linkedSetOf<String>()
    private val unavailableBookIds = HashSet<String>()
    private val unavailableBookErrors = HashMap<String, String>()
    private var pendingTocBookId: String? = null
    private var pendingRelinkBookId: String? = null
    private var libraryDialog: Dialog? = null
    private var libraryAdapter: BaseAdapter? = null
    private var librarySelectionLabel: TextView? = null
    private var libraryTagStrip: LinearLayout? = null
    private var libraryEmptyLabel: TextView? = null
    private var libraryVisibleBooks: List<BookRecord> = emptyList()
    private var libraryFilter = LibraryFilter(LibraryFilterKind.ALL)
    private var bulkTableSelectionGeneration = 0L
    private var hasResumed = false
    private var folderScanInProgress = false
    private var searchMode = SearchMode.BOOKMARKS
    private var searchScopeBookId: String? = null
    private var textSearchSession: TextSearchSession? = null
    private var textSearchQueryGeneration = 0L
    private var textSearchFuture: Future<*>? = null
    private val textSearchProgressRefresh = Runnable {
        if (!destroying && textSearchSession != null) refreshTextSearchResults()
    }
    private val textIndexTokens = ConcurrentHashMap<String, Long>()
    private val textIndexTokenSequence = AtomicLong()
    private val textIndexErrors = ConcurrentHashMap<String, String>()
    @Volatile private var activeSearchHighlight: SearchHighlightTarget? = null
    @Volatile private var searchHighlightGeneration = 0L

    private val tabs = mutableListOf<ReaderTab>()
    private var activeTabIndex = 0
    private val primaryImageViewports = IdentityHashMap<ReaderTab, MutableMap<String, ReaderSurface.ViewportState>>()
    private val referenceImageViewports = HashMap<String, ReaderSurface.ViewportState>()
    private val primaryMarkdownViewports = IdentityHashMap<ReaderTab, MarkdownSurface.ViewportState>()
    private var pendingPrimaryViewportRestore: PendingViewportRestore? = null
    private var pendingReferenceViewportRestore: PendingViewportRestore? = null
    private var referenceMarkdownViewport: MarkdownSurface.ViewportState? = null

    private lateinit var root: FrameLayout
    private lateinit var readerContainer: LinearLayout
    private lateinit var primaryPane: FrameLayout
    private lateinit var referencePane: FrameLayout
    private lateinit var referenceDivider: View
    private lateinit var tabBar: LinearLayout
    private lateinit var bottomChrome: LinearLayout
    private lateinit var bottomTabScroll: HorizontalScrollView
    private lateinit var searchButton: ImageButton
    private lateinit var menuButton: Button
    private lateinit var pageIndicator: TextView
    private lateinit var referenceIndicatorContainer: LinearLayout
    private lateinit var referenceIndicatorTitle: TextView
    private lateinit var referencePageIndicator: TextView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var sourceRecoveryActions: LinearLayout
    private lateinit var sourceRelinkButton: Button
    private lateinit var readerSurface: ReaderSurface
    private lateinit var referenceSurface: ReaderSurface
    private lateinit var primaryMarkdownSurface: MarkdownSurface
    private lateinit var referenceMarkdownSurface: MarkdownSurface
    private lateinit var referenceSearchContainer: LinearLayout
    private lateinit var referenceSearchTitle: TextView
    private lateinit var referenceSearchScopeRow: LinearLayout
    private lateinit var referenceSearchStatus: TextView
    private lateinit var referenceSearchMoreButton: Button
    private lateinit var referenceSearchList: ListView
    private lateinit var textSearchResultAdapter: BaseAdapter

    @Volatile private var referenceLocation: ReferenceLocation? = null
    private var primaryDisplayedPageKeys: Set<PageCacheKey> = emptySet()
    private var primaryPendingPageKeys: Set<PageCacheKey> = emptySet()
    private var referenceDisplayedPageKeys: Set<PageCacheKey> = emptySet()
    private var referencePendingPageKeys: Set<PageCacheKey> = emptySet()

    private var pageTurnMode: PageTurnMode = PageTurnMode.BOTH

    override fun onCreate(savedInstanceState: Bundle?) {
        themeMode = ReaderThemeMode.fromStored(
            getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_THEME, null),
        )
        uiPalette = UiPalette.resolve(this, themeMode)
        setTheme(if (uiPalette.isDark) R.style.AppThemeDark else R.style.AppThemeLight)
        super.onCreate(savedInstanceState)

        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        deviceMemoryClassMb = activityManager.memoryClass.toLong()
        lowRamDevice = activityManager.isLowRamDevice
        imageDecodePolicy = ImageDecodePolicy.forDevice(deviceMemoryClassMb, lowRamDevice)
        val cacheMb = min(128L, max(48L, deviceMemoryClassMb / 5L))
        pageCache = PageBitmapCache(cacheMb * 1024L * 1024L)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        currentPage = savedInstanceState?.getInt(STATE_PAGE, 0) ?: 0
        spreadMode = savedInstanceState?.getBoolean(STATE_SPREAD)
            ?: prefs.getBoolean(
                PREF_SPREAD,
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            )
        spreadSkipCover = savedInstanceState?.getBoolean(STATE_SPREAD_SKIP_COVER)
            ?: prefs.getBoolean(PREF_SPREAD_SKIP_COVER, false)
        pageTurnMode = runCatching {
            PageTurnMode.valueOf(prefs.getString(PREF_PAGE_TURN_MODE, PageTurnMode.BOTH.name)!!)
        }.getOrDefault(PageTurnMode.BOTH)
        readerLayoutMode = runCatching {
            ReaderLayoutMode.valueOf(
                prefs.getString(PREF_READER_LAYOUT, ReaderLayoutMode.AUTOMATIC.name)!!,
            )
        }.getOrDefault(ReaderLayoutMode.AUTOMATIC)
        bookRepository = BookLibraryRepository(this)
        documentMetadataReader = DocumentMetadataReader(this)
        readerSessionRepository = ReaderSessionRepository(this)
        textSearchIndex = TextSearchIndexRepository.shared(this)
        markdownEngine = MarkdownEngine(this)
        try {
            val library = bookRepository.load()
            books += library.books
            selectedBookIds += library.selectedBookIds
            libraryFolders += library.folders
            libraryTags += library.tags
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not read the library: ${t.message}", Toast.LENGTH_LONG).show()
        }
        restoreTabs(savedInstanceState)
        bookmarkVisitCounts.putAll(readerSessionRepository.loadBookmarkVisits())
        rebuildBookmarkIndex()

        buildUi()

        if (tabs.isNotEmpty()) {
            activateCurrentTab()
        } else {
            setStatus(if (books.isEmpty()) "Add a file or folder to start" else "Select a library item to start")
            updateEmptyState()
            if (selectedBookIds.isEmpty()) mainHandler.post { showLibrary() }
        }
        mainHandler.post { scheduleSelectedBookTextIndexes() }
    }

    override fun onResume() {
        super.onResume()
        if (hasResumed) checkActiveSourceFreshness()
        hasResumed = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        stageViewportRestoresForLayout()
        ++renderGeneration
        ++referenceRenderGeneration
        ++prefetchGeneration
        primaryPendingPageKeys = emptySet()
        referencePendingPageKeys = emptySet()
        super.onConfigurationChanged(newConfig)
        applyReaderLayout()
        renderTabBar()
        root.requestApplyInsets()
        val desiredSpread = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (spreadMode != desiredSpread) {
            spreadMode = desiredSpread
            persistSession()
        }
        afterReaderLayout {
            renderCurrent()
            renderReference()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersive) {
            // The keyguard and dialogs may restore the system bars while this window is not
            // focused. Re-hide them only after the decor view has regained focus.
            window.decorView.post {
                if (!isDestroyed && immersive && window.decorView.hasWindowFocus()) {
                    applyImmersiveToWindow(window, true)
                }
            }
        }
    }

    override fun onPause() {
        capturePrimaryImageViewport()
        captureReferenceImageViewport()
        persistSession()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::pageCache.isInitialized) pageCache.trimUnpinned()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::pageCache.isInitialized) pageCache.trimUnpinned()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        capturePrimaryImageViewport()
        captureReferenceImageViewport()
        outState.putInt(STATE_PAGE, currentPage)
        outState.putBoolean(STATE_SPREAD, spreadMode)
        outState.putBoolean(STATE_SPREAD_SKIP_COVER, spreadSkipCover)
        readerSessionRepository.saveTabsToInstanceState(outState, tabs, activeTabIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroying = true
        libraryDialog?.dismiss()
        ++renderGeneration
        ++prefetchGeneration
        ++referenceRenderGeneration
        clearAllDisplayedPages()
        pageCache.pin(emptyList())

        submitRenderWork {
            pdf?.close()
            pdf = null
            pdfBookId = null
        }
        submitPrefetchWork {
            prefetchPdf?.close()
            prefetchPdf = null
            prefetchBookId = null
        }
        renderWorker.shutdown()
        prefetchWorker.shutdown()
        mainHandler.removeCallbacks(textSearchProgressRefresh)
        textSearchFuture?.cancel(true)
        textIndexWorker.shutdownNow()
        textSearchWorker.shutdownNow()
        pageCache.clear()
        super.onDestroy()
    }

    @Deprecated("Uses the platform file picker with the legacy result callback to keep dependencies minimal")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_OPEN_TOC) pendingTocBookId = null
            if (requestCode == REQUEST_RELINK_SOURCE) pendingRelinkBookId = null
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_OPEN_DOCUMENT -> addLibraryDocument(uri, data.flags)
            REQUEST_RELINK_SOURCE -> relinkSource(uri, data.flags)
            REQUEST_OPEN_TOC -> importExternalToc(uri, data.flags)
            REQUEST_OPEN_FOLDER -> addLibraryFolder(uri, data.flags)
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        root = FrameLayout(this).apply {
            setBackgroundColor(uiPalette.background)
        }

        readerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        primaryPane = FrameLayout(this)
        readerSurface = ReaderSurface(this).apply {
            setBackgroundColor(uiPalette.background)
            setUiColors(uiPalette.pagePlaceholder, colorWithAlpha(uiPalette.searchHighlight, 0x66))
            pageTurnMode = this@MainActivity.pageTurnMode
            onAnnotationTap = { showSingleAnnotation(it) }
            onLinkTap = { link -> pdfBookId?.let { openPdfLink(it, link) } }
            onBlankTap = { toggleChrome() }
            onPageTurn = { direction -> moveBy(direction) }
        }
        primaryPane.addView(
            readerSurface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        primaryMarkdownSurface = MarkdownSurface(this).apply {
            setUiColors(uiPalette.background, uiPalette.textPrimary)
            visibility = View.GONE
            onBlankTap = { toggleChrome() }
        }
        primaryPane.addView(
            primaryMarkdownSurface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        readerContainer.addView(
            primaryPane,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )

        referenceDivider = View(this).apply {
            setBackgroundColor(uiPalette.divider)
            visibility = View.GONE
        }
        readerContainer.addView(
            referenceDivider,
            LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT),
        )

        referencePane = FrameLayout(this).apply { visibility = View.GONE }
        referenceSurface = ReaderSurface(this).apply {
            setBackgroundColor(uiPalette.background)
            setUiColors(uiPalette.pagePlaceholder, colorWithAlpha(uiPalette.searchHighlight, 0x66))
            pageTurnMode = this@MainActivity.pageTurnMode
            onAnnotationTap = { showSingleAnnotation(it) }
            onLinkTap = { link -> referenceLocation?.bookId?.let { openPdfLink(it, link) } }
            onBlankTap = { toggleChrome() }
            onPageTurn = { direction -> moveReferenceBy(direction) }
        }
        referencePane.addView(
            referenceSurface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        referenceMarkdownSurface = MarkdownSurface(this).apply {
            setUiColors(uiPalette.background, uiPalette.textPrimary)
            visibility = View.GONE
            onBlankTap = { toggleChrome() }
        }
        referencePane.addView(
            referenceMarkdownSurface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        referenceSearchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(70), dp(12), dp(10))
            setBackgroundColor(uiPalette.surface)
            visibility = View.GONE
        }
        val searchResultsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        referenceSearchTitle = TextView(this).apply {
            textSize = 18f
            maxLines = 2
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(uiPalette.textPrimary)
        }
        searchResultsHeader.addView(
            referenceSearchTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        searchResultsHeader.addView(Button(this).apply {
            text = "Edit"
            textSize = 14f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(uiPalette.textPrimary)
            background = chromeButtonBackground(uiPalette.surfaceRaised)
            contentDescription = "Edit full-text search"
            setOnClickListener {
                searchMode = SearchMode.TEXT
                showBookmarkSearch(textSearchSession?.query.orEmpty())
            }
        }, LinearLayout.LayoutParams(dp(64), dp(48)))
        referenceSearchContainer.addView(
            searchResultsHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            },
        )

        referenceSearchScopeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        referenceSearchContainer.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                referenceSearchScopeRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        })

        referenceSearchStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(uiPalette.textSecondary)
            setPadding(0, dp(3), 0, dp(7))
        }
        referenceSearchMoreButton = Button(this).apply {
            text = "Show more"
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(uiPalette.accent)
            background = actionBackground()
            elevation = 0f
            stateListAnimator = null
            visibility = View.GONE
            contentDescription = "Load more full-text search results"
            setOnClickListener {
                val session = textSearchSession ?: return@setOnClickListener
                if (session.searchInFlight || session.results.size >= session.totalMatches) {
                    return@setOnClickListener
                }
                session.resultLimit = min(
                    session.resultLimit + TextSearchIndexRepository.RESULT_PAGE_SIZE,
                    session.totalMatches,
                )
                refreshTextSearchResults()
            }
        }
        referenceSearchContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                referenceSearchStatus,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                referenceSearchMoreButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)),
            )
        })

        referenceSearchList = ListView(this).apply {
            divider = ColorDrawable(uiPalette.divider)
            dividerHeight = dp(1)
            cacheColorHint = Color.TRANSPARENT
        }
        textSearchResultAdapter = TextSearchResultAdapter()
        referenceSearchList.adapter = textSearchResultAdapter
        referenceSearchContainer.addView(
            referenceSearchList,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        referencePane.addView(
            referenceSearchContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        readerContainer.addView(
            referencePane,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
        applyReferencePaneLayout()

        root.addView(
            readerContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        emptyStateContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        emptyHint = TextView(this).apply {
            text = "Add a PDF, Markdown note, or campaign folder from ⋮"
            textSize = 18f
            setTextColor(uiPalette.textSecondary)
            gravity = Gravity.CENTER
        }
        emptyStateContainer.addView(emptyHint)
        sourceRecoveryActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        fun recoveryButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
        sourceRecoveryActions.addView(
            recoveryButton("Retry") {
                activeBook()?.let {
                    setStatus("Retrying ${it.title}…")
                    activateCurrentTab(forceReload = true)
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)),
        )
        sourceRelinkButton = recoveryButton("Relink") {
            activeBook()?.let { book ->
                if (book.kind == LibraryItemKind.IMAGE_COLLECTION) {
                    rescanLibraryFolders()
                } else {
                    chooseRelinkSource(book.id)
                }
            }
        }
        sourceRecoveryActions.addView(
            sourceRelinkButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                marginStart = dp(6)
            },
        )
        sourceRecoveryActions.addView(
            recoveryButton("Library") { showLibrary() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                marginStart = dp(6)
            },
        )
        emptyStateContainer.addView(
            sourceRecoveryActions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        root.addView(
            emptyStateContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        menuButton = Button(this).apply {
            text = "⋮"
            textSize = 22f
            gravity = Gravity.CENTER
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = chromeButtonBackground(colorWithAlpha(uiPalette.surface, 0xe6))
            contentDescription = "Reader menu"
            setOnClickListener { showReaderMenu() }
        }
        root.addView(
            menuButton,
            FrameLayout.LayoutParams(
                dp(CHROME_TOUCH_SIZE_DP),
                dp(CHROME_TOUCH_SIZE_DP),
                Gravity.TOP or Gravity.START,
            ).apply {
                topMargin = dp(8)
                marginStart = dp(8)
            },
        )

        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottomTabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        bottomTabScroll.addView(
            tabBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        bottomChrome = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedBackground(colorWithAlpha(uiPalette.surface, 0xb3), 0f)
            addView(
                bottomTabScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(CHROME_SURFACE_HEIGHT_DP),
                ),
            )
        }
        root.addView(
            bottomChrome,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        applyTabStripLayout()

        // Search is deliberately outside bottomChrome: it remains reachable at the
        // same physical position even while the primary tab strip is hidden.
        searchButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(uiPalette.textPrimary)
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            contentDescription = "Search bookmarks, filenames, or document text"
            background = chromeButtonBackground(colorWithAlpha(uiPalette.surface, 0xe6))
            isEnabled = false
            setOnClickListener { showBookmarkSearch() }
        }
        root.addView(
            searchButton,
            FrameLayout.LayoutParams(
                dp(CHROME_TOUCH_SIZE_DP),
                dp(CHROME_TOUCH_SIZE_DP),
                Gravity.BOTTOM or Gravity.END,
            ).apply {
                bottomMargin = dp(68)
                marginEnd = dp(8)
            },
        )

        pageIndicator = object : TextView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
                val sharesTopWithMenu =
                    !hasReferencePane() || resolvedReaderLayout() == ReaderLayoutMode.WIDE
                val menuClearance = if (sharesTopWithMenu) dp(64) else 0
                val safeWidth = (availableWidth - menuClearance).coerceAtLeast(0)
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(safeWidth, MeasureSpec.AT_MOST),
                    heightMeasureSpec,
                )
            }
        }.apply {
            textSize = 15f
            maxWidth = dp(360)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(uiPalette.textPrimary)
            minHeight = 0
            minimumHeight = 0
            background = chromeButtonBackground(colorWithAlpha(uiPalette.surface, 0xe6))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { showPageJumpDialog() }
        }
        primaryPane.addView(
            pageIndicator,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(CHROME_TOUCH_SIZE_DP),
                Gravity.TOP or Gravity.END,
            ).apply {
                marginEnd = dp(8)
                topMargin = dp(8)
            },
        )

        referenceIndicatorTitle = TextView(this).apply {
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(uiPalette.textPrimary)
            setPadding(dp(12), 0, dp(5), 0)
            gravity = Gravity.CENTER_VERTICAL
            setOnLongClickListener {
                showReferenceActions()
                true
            }
        }
        referencePageIndicator = TextView(this).apply {
            textSize = 15f
            maxLines = 1
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(uiPalette.textPrimary)
            setPadding(dp(7), 0, dp(7), 0)
            minHeight = 0
            minimumHeight = 0
            gravity = Gravity.CENTER_VERTICAL
            setOnLongClickListener {
                showReferenceActions()
                true
            }
        }
        val referenceCloseButton = TextView(this).apply {
            text = "×"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(uiPalette.textSecondary)
            contentDescription = "Close reference"
            setOnClickListener { closeReference() }
        }
        referenceIndicatorContainer = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
                val menuClearance = if (
                    resolvedReaderLayout() == ReaderLayoutMode.TALL
                ) dp(64) else 0
                val usableWidth = (availableWidth - menuClearance).coerceAtLeast(0)
                val minimumUsefulWidth = min(availableWidth, dp(140))
                val cappedWidth = min(
                    max(usableWidth, minimumUsefulWidth),
                    dp(360),
                )
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(cappedWidth, MeasureSpec.EXACTLY),
                    heightMeasureSpec,
                )
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 0
            visibility = View.GONE
            setOnLongClickListener {
                showReferenceActions()
                true
            }
            addView(
                referenceIndicatorTitle,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f,
                ),
            )
            addView(
                referencePageIndicator,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                referenceCloseButton,
                LinearLayout.LayoutParams(dp(CHROME_TOUCH_SIZE_DP), dp(CHROME_TOUCH_SIZE_DP)),
            )
        }
        referencePane.addView(
            referenceIndicatorContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(CHROME_TOUCH_SIZE_DP),
                Gravity.TOP or Gravity.END,
            ).apply {
                marginEnd = dp(8)
                topMargin = dp(8)
            },
        )

        root.setOnApplyWindowInsetsListener { _, insets ->
            val edges = edgeInsets(insets)
            val tall = resolvedReaderLayout() == ReaderLayoutMode.TALL
            (searchButton.layoutParams as FrameLayout.LayoutParams).also { params ->
                params.bottomMargin = dp(68) + edges.bottom
                params.marginEnd = dp(8) + edges.right
                searchButton.layoutParams = params
            }
            (pageIndicator.layoutParams as FrameLayout.LayoutParams).also { params ->
                val primaryTouchesTop = !hasReferencePane() || !tall
                params.topMargin = dp(8) + if (primaryTouchesTop) edges.top else 0
                params.marginEnd = dp(8) + if (
                    !hasReferencePane() || tall
                ) edges.right else 0
                pageIndicator.layoutParams = params
            }
            (referenceIndicatorContainer.layoutParams as FrameLayout.LayoutParams).also { params ->
                params.topMargin = dp(8) + edges.top
                params.marginEnd = dp(8) + edges.right
                referenceIndicatorContainer.layoutParams = params
            }
            val referenceIndicatorOffset = if (textSearchSession != null) dp(60) else 0
            val referenceBottomControls = if (tall) 0 else dp(124) + edges.bottom
            referenceSearchContainer.setPadding(
                dp(12) + if (tall) edges.left else 0,
                dp(12) + edges.top + referenceIndicatorOffset,
                dp(12) + edges.right,
                dp(10) + referenceBottomControls,
            )
            bottomChrome.setPadding(
                dp(8) + edges.left,
                dp(8),
                dp(8) + edges.right,
                dp(8) + edges.bottom,
            )
            (menuButton.layoutParams as FrameLayout.LayoutParams).also { params ->
                params.topMargin = dp(8) + edges.top
                params.marginStart = dp(8) + edges.left
                menuButton.layoutParams = params
            }
            applyMarkdownInsets()
            // The PDF remains edge-to-edge; only interactive chrome avoids system UI/cutouts.
            insets
        }

        setContentView(root)
        applySystemBarIconTheme(window)
        root.requestApplyInsets()
        renderTabBar()
        updatePageIndicator()
        updateNavigationControls()
        applyChromeVisibility()
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun controlSurfaceBackground(color: Int): GradientDrawable = roundedBackground(
        color,
        2f * resources.displayMetrics.density,
    )

    private fun controlBackground(color: Int): Drawable = RippleDrawable(
        ColorStateList.valueOf(colorWithAlpha(uiPalette.textPrimary, 0x33)),
        controlSurfaceBackground(color),
        controlSurfaceBackground(Color.WHITE),
    )

    private fun outlinedControlBackground(color: Int, outlineColor: Int): Drawable {
        val strokeWidth = max(1, resources.displayMetrics.density.roundToInt())
        val content = controlSurfaceBackground(color).apply {
            setStroke(strokeWidth, outlineColor)
        }
        return RippleDrawable(
            ColorStateList.valueOf(colorWithAlpha(uiPalette.textPrimary, 0x33)),
            content,
            controlSurfaceBackground(Color.WHITE),
        )
    }

    private fun actionBackground(): Drawable = RippleDrawable(
        ColorStateList.valueOf(colorWithAlpha(uiPalette.textPrimary, 0x33)),
        ColorDrawable(Color.TRANSPARENT),
        ColorDrawable(Color.WHITE),
    )

    private fun chromeButtonBackground(color: Int): Drawable {
        val verticalInset =
            (CHROME_VERTICAL_INSET_DP * resources.displayMetrics.density).roundToInt()
        return InsetDrawable(controlBackground(color), 0, verticalInset, 0, verticalInset)
    }

    private fun chromeOutlinedButtonBackground(color: Int, outlineColor: Int): Drawable {
        val verticalInset = (2f * resources.displayMetrics.density).roundToInt()
        return InsetDrawable(
            outlinedControlBackground(color, outlineColor),
            0,
            verticalInset,
            0,
            verticalInset,
        )
    }

    private fun circleBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun blendColors(baseColor: Int, tintColor: Int, tintFraction: Float): Int {
        val fraction = tintFraction.coerceIn(0f, 1f)
        val baseFraction = 1f - fraction
        return Color.argb(
            Color.alpha(baseColor),
            (Color.red(baseColor) * baseFraction + Color.red(tintColor) * fraction).roundToInt(),
            (Color.green(baseColor) * baseFraction + Color.green(tintColor) * fraction).roundToInt(),
            (Color.blue(baseColor) * baseFraction + Color.blue(tintColor) * fraction).roundToInt(),
        )
    }

    private fun tabBackgroundColor(bookColor: Int?, active: Boolean): Int {
        val surface = if (active) uiPalette.surfaceSelected else uiPalette.surfaceRaised
        val color = bookColor ?: return surface
        return blendColors(surface, color, if (active) 0.30f else 0.17f)
    }

    private fun resolvedReaderLayout(mode: ReaderLayoutMode = readerLayoutMode): ReaderLayoutMode {
        if (mode != ReaderLayoutMode.AUTOMATIC) return mode
        val configuration = resources.configuration
        val density = resources.displayMetrics.density
        val widthDp = configuration.screenWidthDp.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels / density).roundToInt()
        val heightDp = configuration.screenHeightDp.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels / density).roundToInt()
        return if (widthDp >= heightDp) ReaderLayoutMode.WIDE else ReaderLayoutMode.TALL
    }

    private fun applyReaderLayout() {
        applyReferencePaneLayout()
        applyTabStripLayout()
        applyMarkdownInsets()
    }

    private fun applyMarkdownInsets() {
        if (!::primaryMarkdownSurface.isInitialized || !::referenceMarkdownSurface.isInitialized) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val edges = if (::root.isInitialized) {
            root.rootWindowInsets?.let(::edgeInsets) ?: EdgeInsets(0, 0, 0, 0)
        } else {
            EdgeInsets(0, 0, 0, 0)
        }
        val tall = resolvedReaderLayout() == ReaderLayoutMode.TALL
        val hasReference = hasReferencePane()
        val primaryTouchesTop = !hasReference || !tall
        val primaryContainsSearch = !hasReference || tall
        val primaryTop = (if (primaryTouchesTop) edges.top else 0) +
            if (primaryTouchesTop && chromeVisible) dp(68) else dp(20)
        val primaryBottom = if (primaryContainsSearch) {
            dp(124) + edges.bottom
        } else {
            dp(32) + edges.bottom + if (chromeVisible) dp(60) else 0
        }
        val referenceTop = edges.top +
            if (chromeVisible && hasReference) dp(68) else dp(20)
        val referenceBottom = if (tall) {
            dp(20)
        } else {
            dp(124) + edges.bottom
        }
        primaryMarkdownSurface.setContentInsets(
            edges.left + dp(20),
            primaryTop,
            edges.right + dp(20),
            primaryBottom,
        )
        referenceMarkdownSurface.setContentInsets(
            edges.left + dp(20),
            referenceTop,
            edges.right + dp(20),
            referenceBottom,
        )
    }

    private fun applyReferencePaneLayout() {
        if (
            !::readerContainer.isInitialized || !::primaryPane.isInitialized ||
            !::referenceDivider.isInitialized || !::referencePane.isInitialized ||
            !::referenceSearchContainer.isInitialized
        ) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()

        val dockRight = resolvedReaderLayout() == ReaderLayoutMode.WIDE
        readerContainer.orientation = if (dockRight) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        primaryPane.layoutParams = if (dockRight) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        referenceDivider.layoutParams = if (dockRight) {
            LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
        referencePane.layoutParams = if (dockRight) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        readerContainer.removeAllViews()
        if (dockRight) {
            readerContainer.addView(primaryPane)
            readerContainer.addView(referenceDivider)
            readerContainer.addView(referencePane)
        } else {
            readerContainer.addView(referencePane)
            readerContainer.addView(referenceDivider)
            readerContainer.addView(primaryPane)
        }
        referenceSearchContainer.setPadding(
            dp(12),
            dp(72),
            dp(12),
            if (dockRight) dp(134) else dp(10),
        )
        readerContainer.requestLayout()
        if (::root.isInitialized) root.requestApplyInsets()
    }

    private fun applyTabStripLayout() {
        if (
            !::tabBar.isInitialized || !::bottomChrome.isInitialized ||
            !::bottomTabScroll.isInitialized || !::menuButton.isInitialized
        ) return
        tabBar.orientation = LinearLayout.HORIZONTAL
        tabBar.gravity = Gravity.CENTER_VERTICAL
        bottomChrome.visibility = if (chromeVisible) View.VISIBLE else View.INVISIBLE
        menuButton.visibility = if (chromeVisible) View.VISIBLE else View.INVISIBLE
        updateReferenceIndicator()
    }

    private fun afterReaderLayout(action: () -> Unit) {
        if (!::readerContainer.isInitialized) return
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                readerContainer.viewTreeObserver.takeIf { it.isAlive }
                    ?.removeOnPreDrawListener(this)
                readerContainer.post {
                    if (!isDestroyed) action()
                }
                return true
            }
        }
        readerContainer.viewTreeObserver.addOnPreDrawListener(listener)
        readerContainer.requestLayout()
    }

    @Suppress("DEPRECATION")
    private fun edgeInsets(insets: WindowInsets): EdgeInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return EdgeInsets(edges.left, edges.top, edges.right, edges.bottom)
        }

        var left = insets.systemWindowInsetLeft
        var top = insets.systemWindowInsetTop
        var right = insets.systemWindowInsetRight
        var bottom = insets.systemWindowInsetBottom
        if (Build.VERSION.SDK_INT >= 28) {
            insets.displayCutout?.let { cutout ->
                left = max(left, cutout.safeInsetLeft)
                top = max(top, cutout.safeInsetTop)
                right = max(right, cutout.safeInsetRight)
                bottom = max(bottom, cutout.safeInsetBottom)
            }
        }
        return EdgeInsets(left, top, right, bottom)
    }

    private fun chooseLibraryDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/pdf", "text/markdown", "text/x-markdown", "text/plain"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_DOCUMENT)
    }

    private fun chooseLibraryFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
        startActivityForResult(intent, REQUEST_OPEN_FOLDER)
    }

    private fun chooseExternalToc() {
        val bookId = activeTabOrNull()?.bookId ?: return
        chooseExternalToc(bookId)
    }

    private fun chooseExternalToc(bookId: String) {
        pendingTocBookId = bookId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_TOC)
    }

    private fun chooseRelinkSource(bookId: String) {
        val book = bookById(bookId) ?: return
        if (book.kind == LibraryItemKind.IMAGE_COLLECTION) return
        pendingRelinkBookId = bookId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (book.kind == LibraryItemKind.PDF) "application/pdf" else "*/*"
            if (book.kind == LibraryItemKind.MARKDOWN) {
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("text/markdown", "text/x-markdown", "text/plain"),
                )
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_RELINK_SOURCE)
    }

    private fun persistReadPermission(uri: Uri, resultFlags: Int) {
        val readFlag = resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        require(readFlag != 0) { "The document provider did not grant read access" }
        contentResolver.takePersistableUriPermission(uri, readFlag)
    }

    private fun addLibraryFolder(uri: Uri, resultFlags: Int) {
        try {
            persistReadPermission(uri, resultFlags)
        } catch (t: Throwable) {
            showError("Could not keep access to folder", t)
            return
        }
        val folder = LibraryFolderRecord(
            uri = uri.toString(),
            label = documentMetadataReader.displayName(uri) ?: "Library folder",
        )
        val existingIndex = libraryFolders.indexOfFirst { it.uri == folder.uri }
        if (existingIndex >= 0) {
            libraryFolders[existingIndex] = folder
        } else {
            libraryFolders += folder
        }
        persistBooks()
        scanLibraryFolders(listOf(folder))
    }

    private fun rescanLibraryFolders() {
        if (libraryFolders.isEmpty()) {
            Toast.makeText(this, "No library folders have been added yet", Toast.LENGTH_SHORT).show()
            return
        }
        scanLibraryFolders(libraryFolders.toList())
    }

    private fun scanLibraryFolders(folders: List<LibraryFolderRecord>) {
        if (folderScanInProgress) {
            Toast.makeText(this, "A folder scan is already running", Toast.LENGTH_SHORT).show()
            return
        }
        folderScanInProgress = true
        refreshLibraryUi()
        setStatus("Scanning ${folders.size} library folder${if (folders.size == 1) "" else "s"}…")
        val knownBooks = books.toList()

        submitPrefetchWork {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val result = scanFolderDocuments(folders, knownBooks)
                mainHandler.post {
                    if (isDestroyed) return@post
                    folderScanInProgress = false
                    applyFolderScanResult(result)
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    if (isDestroyed) return@post
                    folderScanInProgress = false
                    refreshLibraryUi()
                    showError("Folder scan failed", t)
                }
            }
        }
    }

    private fun scanFolderDocuments(
        folders: List<LibraryFolderRecord>,
        knownBooks: List<BookRecord>,
    ): FolderScanResult {
        val failures = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val documents = folders.flatMap { folder ->
            runCatching { listFolderDocuments(folder) }
                .getOrElse {
                    failures += "${folder.label}: ${it.message ?: it.javaClass.simpleName}"
                    emptyList()
                }
        }
        val uniqueDocuments = documents.distinctBy { it.uri.toString() }
        val pdfDocuments = uniqueDocuments
            .filter {
                it.fileName.endsWith(".pdf", ignoreCase = true) ||
                    it.mimeType.equals("application/pdf", ignoreCase = true)
            }
            .sortedWith { left, right -> naturalCompare(left.relativePath(), right.relativePath()) }
        val markdownDocuments = uniqueDocuments
            .filter { isMarkdownFile(it.fileName, it.mimeType) }
            .sortedWith { left, right -> naturalCompare(left.relativePath(), right.relativePath()) }
        val imageGroups = uniqueDocuments
            .filter(::isSupportedImage)
            .groupBy { it.parentUri }
            .values
            .sortedWith { left, right ->
                naturalCompare(left.first().relativeDirectory, right.first().relativeDirectory)
            }
        val textDocuments = uniqueDocuments.filter { it.fileName.endsWith(".txt", ignoreCase = true) }
        val textByFolderAndStem = textDocuments.groupBy { folderStemKey(it) }
        val pdfNameCounts = pdfDocuments.groupingBy { it.fileName.lowercase(Locale.ROOT) }.eachCount()
        val markdownNameCounts = markdownDocuments.groupingBy { it.fileName.lowercase(Locale.ROOT) }.eachCount()
        val workingBooks = knownBooks.toMutableList()
        val usedColors = workingBooks.mapTo(HashSet()) { it.color }
        var colorCursor = workingBooks.size
        val changes = mutableListOf<FolderBookChange>()
        var unchanged = 0

        fun takeBookColor(): Int {
            BOOK_COLORS.firstOrNull { it !in usedColors }?.let { color ->
                usedColors += color
                return color
            }
            return BOOK_COLORS[colorCursor++.mod(BOOK_COLORS.size)]
        }

        for (pdfDocument in pdfDocuments) {
            val exactMatches = workingBooks.filter {
                it.kind == LibraryItemKind.PDF && it.uri == pdfDocument.uri.toString()
            }
            if (exactMatches.size > 1) {
                skipped += "${pdfDocument.fileName}: duplicate stored URI"
                continue
            }
            var existing = exactMatches.singleOrNull()
            if (existing == null) {
                if ((pdfNameCounts[pdfDocument.fileName.lowercase(Locale.ROOT)] ?: 0) == 1) {
                    val filenameMatches = workingBooks.filter {
                        it.kind == LibraryItemKind.PDF &&
                            it.fileName.equals(pdfDocument.fileName, ignoreCase = true)
                    }
                    when {
                        filenameMatches.size == 1 -> existing = filenameMatches.single()
                        filenameMatches.size > 1 -> {
                            skipped += "${pdfDocument.fileName}: matches multiple known books"
                            continue
                        }
                    }
                }
            }

            val matchingTexts = textByFolderAndStem[folderStemKey(pdfDocument)].orEmpty()
            val tocDocument = matchingTexts.singleOrNull()
            if (matchingTexts.size > 1) {
                skipped += "${pdfDocument.fileName}: multiple matching TXT files; PDF was still imported"
            }

            try {
                val change = prepareFolderBookChange(
                    existing = existing,
                    pdfDocument = pdfDocument,
                    tocDocument = tocDocument,
                    newBookColor = ::takeBookColor,
                    failures = failures,
                )
                if (change == null) {
                    unchanged++
                    continue
                }
                changes += change
                existing?.let { old -> workingBooks.removeAll { it.id == old.id } }
                workingBooks += change.book
            } catch (t: Throwable) {
                failures += "${pdfDocument.fileName}: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        for (markdownDocument in markdownDocuments) {
            val exactMatches = workingBooks.filter {
                it.kind == LibraryItemKind.MARKDOWN && it.uri == markdownDocument.uri.toString()
            }
            if (exactMatches.size > 1) {
                skipped += "${markdownDocument.fileName}: duplicate stored note URI"
                continue
            }
            var existing = exactMatches.singleOrNull()
            if (existing == null && (markdownNameCounts[markdownDocument.fileName.lowercase(Locale.ROOT)] ?: 0) == 1) {
                val filenameMatches = workingBooks.filter {
                    it.kind == LibraryItemKind.MARKDOWN &&
                        it.fileName.equals(markdownDocument.fileName, ignoreCase = true)
                }
                when {
                    filenameMatches.size == 1 -> existing = filenameMatches.single()
                    filenameMatches.size > 1 -> {
                        skipped += "${markdownDocument.fileName}: matches multiple known notes"
                        continue
                    }
                }
            }
            try {
                val change = prepareMarkdownBookChange(existing, markdownDocument, ::takeBookColor)
                if (change == null) {
                    unchanged++
                    continue
                }
                changes += change
                existing?.let { old -> workingBooks.removeAll { it.id == old.id } }
                workingBooks += change.book
            } catch (t: Throwable) {
                failures += "${markdownDocument.fileName}: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        var discoveredImageCount = 0
        for (group in imageGroups) {
            val first = group.first()
            discoveredImageCount += group.size
            val exactMatches = workingBooks.filter {
                it.kind == LibraryItemKind.IMAGE_COLLECTION && it.uri == first.parentUri
            }
            if (exactMatches.size > 1) {
                skipped += "${first.parentName}: duplicate stored album URI"
                continue
            }
            val existing = exactMatches.singleOrNull()
            try {
                val change = prepareImageAlbumChange(existing, group, ::takeBookColor)
                if (change == null) {
                    unchanged++
                    continue
                }
                changes += change
                existing?.let { old -> workingBooks.removeAll { it.id == old.id } }
                workingBooks += change.book
            } catch (t: Throwable) {
                failures += "${first.relativeDirectory.ifBlank { first.parentName }}: " +
                    (t.message ?: t.javaClass.simpleName)
            }
        }

        return FolderScanResult(
            folderCount = folders.size,
            discoveredPdfCount = pdfDocuments.size,
            discoveredMarkdownCount = markdownDocuments.size,
            discoveredAlbumCount = imageGroups.size,
            discoveredImageCount = discoveredImageCount,
            changes = changes,
            unchangedCount = unchanged,
            skipped = skipped,
            failures = failures,
        )
    }

    private fun prepareMarkdownBookChange(
        existing: BookRecord?,
        markdownDocument: FolderDocument,
        newBookColor: () -> Int,
    ): FolderBookChange? {
        val hydrated = existing?.let(bookRepository::hydrate)
        val bookId = existing?.id ?: UUID.randomUUID().toString()
        val opened = MarkdownDocument(contentResolver, markdownDocument.uri, markdownEngine)
        val headings = outlineEntries(opened, bookId, BookmarkSource.MARKDOWN_HEADING)
        val updated = BookRecord(
            id = bookId,
            title = cleanBookName(markdownDocument.fileName),
            fileName = markdownDocument.fileName,
            uri = markdownDocument.uri.toString(),
            kind = LibraryItemKind.MARKDOWN,
            color = hydrated?.color ?: newBookColor(),
            pageCount = opened.pageCount,
            pdfBookmarks = headings,
            storedBookmarkCount = headings.distinctBy { it.identityKey }.size,
            indexVersion = CURRENT_BOOK_INDEX_VERSION,
            indexLoaded = true,
            sourceSize = markdownDocument.size,
            sourceLastModified = markdownDocument.lastModified,
            sourceFingerprint = opened.content.fingerprint,
            sourceRevisionToken = if (
                hydrated == null || hydrated.uri != markdownDocument.uri.toString() ||
                hydrated.sourceFingerprint != opened.content.fingerprint
            ) {
                UUID.randomUUID().toString()
            } else {
                hydrated.sourceRevisionToken
            },
            tagIds = hydrated?.tagIds.orEmpty(),
        )
        val contentChanged = hydrated == null ||
            hydrated.uri != updated.uri ||
            hydrated.sourceFingerprint != updated.sourceFingerprint ||
            hydrated.indexVersion < CURRENT_BOOK_INDEX_VERSION
        val metadataChanged = hydrated != null && (
            hydrated.fileName != updated.fileName ||
                hydrated.sourceSize != updated.sourceSize ||
                hydrated.sourceLastModified != updated.sourceLastModified
            )
        if (!contentChanged && !metadataChanged) return null
        if (contentChanged) bookRepository.saveIndex(updated)
        return FolderBookChange(
            book = updated,
            isNew = existing == null,
            contentChanged = contentChanged,
            textIndexChanged = hydrated != null && (
                hydrated.uri != updated.uri ||
                    hydrated.sourceFingerprint != updated.sourceFingerprint ||
                    hydrated.pageCount != updated.pageCount
                ),
            tocImported = false,
            rejectedTocLines = 0,
        )
    }

    private fun prepareFolderBookChange(
        existing: BookRecord?,
        pdfDocument: FolderDocument,
        tocDocument: FolderDocument?,
        newBookColor: () -> Int,
        failures: MutableList<String>,
    ): FolderBookChange? {
        val existingHydrated = existing?.let(bookRepository::hydrate)
        val pdfMetadata = DocumentMetadata(
            fileName = pdfDocument.fileName,
            size = pdfDocument.size,
            lastModified = pdfDocument.lastModified,
        )
        val pdfNeedsRefresh = existing == null ||
            existing.uri != pdfDocument.uri.toString() ||
            existingHydrated?.indexLoaded != true ||
            existing.indexVersion < CURRENT_BOOK_INDEX_VERSION ||
            sourceMetadataChanged(existing, pdfMetadata)
        var updated: BookRecord
        var contentChanged = false
        if (pdfNeedsRefresh) {
            val bookId = existing?.id ?: UUID.randomUUID().toString()
            var document: PdfDocument? = null
            try {
                document = openDocument(pdfDocument.uri)
                val outline = outlineEntries(document, bookId)
                updated = if (existingHydrated == null) {
                    BookRecord(
                        id = bookId,
                        title = cleanBookName(pdfDocument.fileName),
                        fileName = pdfDocument.fileName,
                        uri = pdfDocument.uri.toString(),
                        color = newBookColor(),
                        pageCount = document.pageCount,
                        pdfBookmarks = outline,
                        storedBookmarkCount = outline.distinctBy { it.identityKey }.size,
                        sourceSize = pdfDocument.size,
                        sourceLastModified = pdfDocument.lastModified,
                        sourceRevisionToken = UUID.randomUUID().toString(),
                    )
                } else {
                    existingHydrated.copy(
                        title = cleanBookName(pdfDocument.fileName),
                        fileName = pdfDocument.fileName,
                        uri = pdfDocument.uri.toString(),
                        pageCount = document.pageCount,
                        pdfBookmarks = outline,
                        externalBookmarks = existingHydrated.externalBookmarks.filter {
                            it.pageIndex < document.pageCount
                        },
                        indexVersion = CURRENT_BOOK_INDEX_VERSION,
                        indexLoaded = true,
                        sourceSize = pdfDocument.size,
                        sourceLastModified = pdfDocument.lastModified,
                        sourceRevisionToken = UUID.randomUUID().toString(),
                    )
                }
                contentChanged = true
            } finally {
                document?.close()
            }
        } else {
            updated = checkNotNull(existingHydrated).copy(
                title = cleanBookName(pdfDocument.fileName),
                fileName = pdfDocument.fileName,
                sourceSize = pdfDocument.size ?: existingHydrated.sourceSize,
                sourceLastModified = pdfDocument.lastModified ?: existingHydrated.sourceLastModified,
            )
        }

        var tocImported = false
        var rejectedTocLines = 0
        if (tocDocument != null) {
            val matchingToc = tocDocument
            try {
                val text = contentResolver.openInputStream(matchingToc.uri).use { input ->
                    requireNotNull(input) { "Could not open ${matchingToc.fileName}" }
                    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                val fingerprint = textFingerprint(text)
                val tocChanged = existing == null ||
                    existing.externalTocUri != matchingToc.uri.toString() ||
                    existing.externalTocFingerprint != fingerprint ||
                    existing.pageCount != updated.pageCount
                if (tocChanged) {
                    val parsed = ExternalTocParser.parse(text, updated.pageCount, updated.id)
                    require(parsed.entries.isNotEmpty() || parsed.rejectedLines == 0) {
                        "${matchingToc.fileName} contained no valid TOC entries"
                    }
                    updated = updated.copy(
                        externalBookmarks = parsed.entries,
                        externalTocLabel = matchingToc.fileName,
                        externalTocUri = matchingToc.uri.toString(),
                        externalTocSize = matchingToc.size,
                        externalTocLastModified = matchingToc.lastModified,
                        externalTocFingerprint = fingerprint,
                    )
                    tocImported = true
                    rejectedTocLines = parsed.rejectedLines
                } else if (
                    updated.externalTocSize != matchingToc.size ||
                    updated.externalTocLastModified != matchingToc.lastModified
                ) {
                    updated = updated.copy(
                        externalTocSize = matchingToc.size,
                        externalTocLastModified = matchingToc.lastModified,
                    )
                }
            } catch (t: Throwable) {
                failures += "${matchingToc.fileName}: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        val metadataChanged = existing != null && (
                existing.fileName != updated.fileName ||
                existing.sourceSize != updated.sourceSize ||
                existing.sourceLastModified != updated.sourceLastModified ||
                existing.externalTocSize != updated.externalTocSize ||
                existing.externalTocLastModified != updated.externalTocLastModified
            )
        if (existing != null && !contentChanged && !tocImported && !metadataChanged) return null

        if (existing == null || contentChanged || tocImported) bookRepository.saveIndex(updated)
        return FolderBookChange(
            book = updated,
            isNew = existing == null,
            contentChanged = contentChanged,
            textIndexChanged = existing != null && (
                contentChanged || existing.sourceRevisionKey() != updated.sourceRevisionKey()
                ),
            tocImported = tocImported,
            rejectedTocLines = rejectedTocLines,
        )
    }

    private fun prepareImageAlbumChange(
        existing: BookRecord?,
        documents: List<FolderDocument>,
        newBookColor: () -> Int,
    ): FolderBookChange? {
        require(documents.isNotEmpty()) { "Image album has no images" }
        val first = documents.first()
        val images = documents
            .sortedWith { left, right -> naturalCompare(left.fileName, right.fileName) }
            .map { document ->
                ImageFileRecord(
                    uri = document.uri.toString(),
                    fileName = document.fileName,
                    relativePath = document.fileName,
                    size = document.size,
                    lastModified = document.lastModified,
                )
            }
        val hydrated = existing?.let(bookRepository::hydrate)
        val bookId = existing?.id ?: UUID.randomUUID().toString()
        val bookmarks = imageBookmarks(bookId, images)
        val albumPath = first.relativeDirectory.ifBlank { first.parentName }
        val sizes = images.mapNotNull { it.size }
        val updated = BookRecord(
            id = bookId,
            title = albumPath,
            fileName = albumPath,
            uri = first.parentUri,
            kind = LibraryItemKind.IMAGE_COLLECTION,
            color = hydrated?.color ?: newBookColor(),
            pageCount = images.size,
            pdfBookmarks = bookmarks,
            imageFiles = images,
            storedBookmarkCount = bookmarks.size,
            indexVersion = CURRENT_BOOK_INDEX_VERSION,
            indexLoaded = true,
            sourceSize = sizes.sum().takeIf { sizes.size == images.size },
            sourceLastModified = images.mapNotNull { it.lastModified }.maxOrNull(),
            sourceRevisionToken = if (
                hydrated == null || hydrated.title != albumPath || hydrated.imageFiles != images
            ) {
                UUID.randomUUID().toString()
            } else {
                hydrated.sourceRevisionToken
            },
            tagIds = hydrated?.tagIds.orEmpty(),
        )
        val changed = hydrated == null ||
            hydrated.title != updated.title || hydrated.fileName != updated.fileName ||
            hydrated.imageFiles != updated.imageFiles || hydrated.indexVersion < CURRENT_BOOK_INDEX_VERSION
        if (!changed) return null
        bookRepository.saveIndex(updated)
        return FolderBookChange(
            book = updated,
            isNew = existing == null,
            contentChanged = true,
            textIndexChanged = false,
            tocImported = false,
            rejectedTocLines = 0,
        )
    }

    private fun imageBookmarks(bookId: String, images: List<ImageFileRecord>): List<BookmarkEntry> =
        images.mapIndexed { pageIndex, image ->
            BookmarkEntry(
                bookId = bookId,
                title = image.fileName,
                pageIndex = pageIndex,
                path = image.relativePath,
                source = BookmarkSource.IMAGE_FILE,
                stableKey = "image:${image.uri}",
            )
        }

    private fun listFolderDocuments(folder: LibraryFolderRecord): List<FolderDocument> {
        val treeUri = Uri.parse(folder.uri)
        data class PendingDirectory(
            val documentId: String,
            val uri: Uri,
            val name: String,
            val relativePath: String,
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val queue = ArrayDeque<PendingDirectory>()
        queue += PendingDirectory(rootId, rootDocumentUri, folder.label, "")
        val visited = HashSet<String>()
        val result = mutableListOf<FolderDocument>()
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            if (!visited.add(parent.documentId)) continue
            require(visited.size <= 5_000) { "Folder tree contains too many directories" }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId)
            val cursor = requireNotNull(contentResolver.query(childrenUri, projection, null, null, null)) {
                "Could not list ${parent.relativePath.ifBlank { folder.label }}"
            }
            cursor.use { rows ->
                val idColumn = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = if (rows.isNull(nameColumn)) "" else rows.getString(nameColumn)
                    if (name.isBlank()) continue
                    val documentId = rows.getString(idColumn)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val mimeType = if (rows.isNull(mimeColumn)) null else rows.getString(mimeColumn)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val path = if (parent.relativePath.isBlank()) name else "${parent.relativePath}/$name"
                        queue += PendingDirectory(documentId, uri, name, path)
                        continue
                    }
                    result +=
                        FolderDocument(
                            parentUri = parent.uri.toString(),
                            parentName = parent.name,
                            relativeDirectory = parent.relativePath,
                            uri = uri,
                            fileName = name,
                            mimeType = mimeType,
                            size = sizeColumn.takeIf { column -> column >= 0 && !rows.isNull(column) }
                                ?.let(rows::getLong)?.takeIf { value -> value >= 0L },
                            lastModified = modifiedColumn.takeIf { column -> column >= 0 && !rows.isNull(column) }
                                ?.let(rows::getLong)?.takeIf { value -> value > 0L },
                        )
                    require(result.size <= 20_000) { "Folder tree contains too many files" }
                }
            }
        }
        return result
    }

    private fun folderStemKey(document: FolderDocument): String =
        "${document.parentUri}|${document.fileName.substringBeforeLast('.', document.fileName).lowercase(Locale.ROOT)}"

    private fun FolderDocument.relativePath(): String =
        if (relativeDirectory.isBlank()) fileName else "$relativeDirectory/$fileName"

    private fun isSupportedImage(document: FolderDocument): Boolean {
        val mime = document.mimeType?.lowercase(Locale.ROOT)
        if (mime == "image/jpeg" || mime == "image/png" || mime == "image/webp") return true
        return document.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("jpg", "jpeg", "png", "webp")
    }

    private fun isMarkdownFile(fileName: String, mimeType: String?): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension == "md" || extension == "markdown") return true
        val mime = mimeType?.lowercase(Locale.ROOT)
        return (mime == "text/markdown" || mime == "text/x-markdown") &&
            !fileName.endsWith(".txt", ignoreCase = true)
    }

    private fun naturalCompare(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftDigit = left[leftIndex].isDigit()
            val rightDigit = right[rightIndex].isDigit()
            if (leftDigit && rightDigit) {
                val leftEnd = left.indexOfFirstFrom(leftIndex) { !it.isDigit() }
                val rightEnd = right.indexOfFirstFrom(rightIndex) { !it.isDigit() }
                val leftNumber = left.substring(leftIndex, leftEnd).trimStart('0').ifEmpty { "0" }
                val rightNumber = right.substring(rightIndex, rightEnd).trimStart('0').ifEmpty { "0" }
                if (leftNumber.length != rightNumber.length) return leftNumber.length.compareTo(rightNumber.length)
                val numberComparison = leftNumber.compareTo(rightNumber)
                if (numberComparison != 0) return numberComparison
                leftIndex = leftEnd
                rightIndex = rightEnd
                continue
            }
            val comparison = left[leftIndex].lowercaseChar().compareTo(right[rightIndex].lowercaseChar())
            if (comparison != 0) return comparison
            leftIndex++
            rightIndex++
        }
        return (left.length - leftIndex).compareTo(right.length - rightIndex)
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) if (predicate(this[index])) return index
        return length
    }

    private fun textFingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun applyFolderScanResult(result: FolderScanResult) {
        capturePrimaryImageViewport()
        captureReferenceImageViewport()
        val addedChanges = mutableListOf<FolderBookChange>()
        val appliedChanges = mutableListOf<FolderBookChange>()
        val previousUris = HashMap<String, String>()
        for (change in result.changes) {
            if (change.isNew) {
                if (books.any { it.id == change.book.id || it.uri == change.book.uri }) continue
                books += change.book
                selectedBookIds += change.book.id
                addedChanges += change
                appliedChanges += change
                continue
            }
            val current = bookById(change.book.id) ?: continue
            previousUris[current.id] = current.uri
            val retained = if (current.id in selectedBookIds) {
                change.book
            } else {
                change.book.copy(
                    pdfBookmarks = emptyList(),
                    externalBookmarks = emptyList(),
                    imageFiles = emptyList(),
                    storedBookmarkCount = change.book.bookmarkCount,
                    indexLoaded = false,
                )
            }
            if (replaceBook(retained)) {
                unavailableBookIds.remove(current.id)
                unavailableBookErrors.remove(current.id)
                appliedChanges += change
            }
        }

        if (tabs.isEmpty()) {
            addedChanges.firstOrNull()?.book?.let { tabs += ReaderTab(it.id, 0, "Start") }
        }
        appliedChanges.forEach { change ->
            val pageCount = change.book.pageCount
            val destinations = change.book.pdfBookmarks.associateBy { it.identityKey }
            tabs.filter { it.bookId == change.book.id }.forEach { tab ->
                val destination = tab.anchorKey?.let(destinations::get)
                tab.pageIndex = destination?.pageIndex ?: tab.pageIndex.coerceIn(0, pageCount - 1)
                if (destination != null) {
                    tab.label = destination.title
                    tab.originPageIndex = destination.pageIndex
                } else {
                    tab.originPageIndex = tab.originPageIndex.coerceIn(0, pageCount - 1)
                }
            }
            referenceLocation?.takeIf { it.bookId == change.book.id }?.let { reference ->
                val destination = destinations[reference.anchorKey]
                reference.pageIndex = destination?.pageIndex
                    ?: reference.pageIndex.coerceIn(0, pageCount - 1)
                if (destination != null) {
                    reference.originPageIndex = destination.pageIndex
                    reference.originDestinationY = destination.destinationY
                    reference.pendingDestinationY = destination.destinationY
                } else {
                    reference.originPageIndex = reference.originPageIndex.coerceIn(0, pageCount - 1)
                    reference.originDestinationY = null
                    reference.pendingDestinationY = null
                }
            }
            val oldUri = previousUris[change.book.id]
            if (oldUri != null && oldUri != change.book.uri) releaseExactPersistedPermission(oldUri)
        }

        persistBooks()
        persistSession()
        rebuildBookmarkIndex()
        refreshLibraryUi()

        appliedChanges.forEach { change ->
            if (change.book.id !in selectedBookIds) {
                if (change.textIndexChanged) deleteBookTextIndex(change.book.id)
                return@forEach
            }
            when {
                !isTextSearchable(change.book) -> deleteBookTextIndex(change.book.id)
                change.isNew -> scheduleBookTextIndex(change.book)
                change.textIndexChanged -> scheduleBookTextIndex(change.book, force = true)
                change.contentChanged -> scheduleBookTextIndex(change.book)
            }
        }
        refreshTextSearchScope()

        val changedDocumentIds = appliedChanges.filter { it.contentChanged }.mapTo(HashSet()) { it.book.id }
        appliedChanges.filter { !it.isNew && it.contentChanged }.takeIf { it.isNotEmpty() }?.let { refreshed ->
            lastSourceRefreshDiagnostic = "Folder rescan refreshed " +
                refreshed.joinToString { "${it.book.title} (${it.book.sourceRevisionKey().take(12)})" }
        }
        val activeChanged = pdfBookId in changedDocumentIds
        val referenceChanged = referenceLocation?.bookId in changedDocumentIds
        if (prefetchBookId in changedDocumentIds) submitPrefetchWork {
            if (prefetchBookId in changedDocumentIds) {
                prefetchPdf?.close()
                prefetchPdf = null
                prefetchBookId = null
            }
        }
        if (activeChanged) {
            invalidateRendering(clearCache = true)
            activateCurrentTab(forceReload = true)
        } else {
            if (referenceChanged) {
                ++referenceRenderGeneration
                referenceSurface.clearPages()
                referenceDisplayedPageKeys = emptySet()
                referencePendingPageKeys = emptySet()
                updateCachePins()
            }
            changedDocumentIds.forEach(pageCache::removeBook)
            renderTabBar()
            updatePageIndicator()
            updateEmptyState()
            if (pdf == null && tabs.isNotEmpty()) activateCurrentTab()
        }
        if (referenceChanged) renderReference()

        showFolderScanSummary(result, appliedChanges)
    }

    private fun showFolderScanSummary(
        result: FolderScanResult,
        appliedChanges: List<FolderBookChange>,
    ) {
        val added = appliedChanges.count { it.isNew }
        val refreshed = appliedChanges.count { !it.isNew && it.contentChanged }
        val tocImported = appliedChanges.count { it.tocImported }
        val unchanged = result.unchangedCount + appliedChanges.count {
            !it.isNew && !it.contentChanged && !it.tocImported
        }
        val rejectedLines = appliedChanges.sumOf { it.rejectedTocLines }
        val summary = buildString {
            append("${result.folderCount} folder${if (result.folderCount == 1) "" else "s"} scanned")
            append(" · ${result.discoveredPdfCount} PDF${if (result.discoveredPdfCount == 1) "" else "s"} found")
            append(" · ${result.discoveredMarkdownCount} note${if (result.discoveredMarkdownCount == 1) "" else "s"}")
            append(" · ${result.discoveredAlbumCount} album${if (result.discoveredAlbumCount == 1) "" else "s"}")
            append(" (${result.discoveredImageCount} images)")
            append("\n$added added · $refreshed refreshed · $unchanged unchanged")
            append("\n$tocImported matching TOC${if (tocImported == 1) "" else "s"} imported")
            if (rejectedLines > 0) append(" · $rejectedLines invalid line${if (rejectedLines == 1) "" else "s"} ignored")
            if (result.skipped.isNotEmpty()) append("\n${result.skipped.size} warning${if (result.skipped.size == 1) "" else "s"}")
            if (result.failures.isNotEmpty()) append(" · ${result.failures.size} failure${if (result.failures.size == 1) "" else "s"}")
        }
        setStatus(summary.replace('\n', ' '))
        val details = result.skipped + result.failures
        if (details.isEmpty()) {
            Toast.makeText(this, summary.replace('\n', ' '), Toast.LENGTH_LONG).show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Folder scan complete")
                .setMessage(summary + "\n\n" + details.take(12).joinToString("\n") { "• $it" })
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun releaseExactPersistedPermission(uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        if (contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return
        runCatching {
            contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun addLibraryDocument(uri: Uri, resultFlags: Int) {
        val metadata = documentMetadataReader.query(uri)
        val fileName = metadata.fileName.orEmpty()
        val mimeType = contentResolver.getType(uri).orEmpty()
        when {
            isMarkdownFile(fileName, mimeType) -> addMarkdown(uri, resultFlags, metadata)
            fileName.endsWith(".pdf", ignoreCase = true) || mimeType.equals("application/pdf", true) ->
                addPdf(uri, resultFlags, metadata)
            else -> showError(
                "Unsupported file",
                IllegalArgumentException("Choose a PDF, .md, or .markdown file"),
            )
        }
    }

    private fun addPdf(uri: Uri, resultFlags: Int, suppliedMetadata: DocumentMetadata? = null) {
        val existing = books.indexOfFirst {
            it.kind == LibraryItemKind.PDF && it.uri == uri.toString()
        }
        try {
            persistReadPermission(uri, resultFlags)
        } catch (t: Throwable) {
            showError("Could not keep access to PDF", t)
            return
        }
        if (existing >= 0) {
            val known = books[existing]
            updateKnownBookSource(
                bookId = known.id,
                uri = uri,
                metadata = suppliedMetadata ?: documentMetadataReader.query(uri),
                selectAndOpen = true,
                forceTextReindex = true,
                refreshReason = "PDF re-added",
            )
            return
        }

        val metadata = suppliedMetadata ?: documentMetadataReader.query(uri)
        val fileName = metadata.fileName ?: "Selected PDF"
        val matches = books.filter {
            it.kind == LibraryItemKind.PDF && it.fileName.equals(fileName, ignoreCase = true)
        }
        if (matches.isNotEmpty()) {
            val choices = matches.map { "Use ${it.title} and keep its history" } + "Create a separate book"
            AlertDialog.Builder(this)
                .setTitle("$fileName is already known")
                .setItems(choices.toTypedArray()) { _, which ->
                    if (which < matches.size) {
                        updateKnownBookSource(
                            matches[which].id,
                            uri,
                            metadata,
                            selectAndOpen = true,
                            forceTextReindex = true,
                        )
                    } else {
                        createLibraryBook(uri, metadata)
                    }
                }
                .setOnCancelListener {
                    runCatching {
                        contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                .show()
            return
        }
        createLibraryBook(uri, metadata)
    }

    private fun addMarkdown(uri: Uri, resultFlags: Int, metadata: DocumentMetadata) {
        val existing = books.firstOrNull {
            it.kind == LibraryItemKind.MARKDOWN && it.uri == uri.toString()
        }
        try {
            persistReadPermission(uri, resultFlags)
        } catch (t: Throwable) {
            showError("Could not keep access to note", t)
            return
        }
        if (existing != null) {
            unavailableBookIds.remove(existing.id)
            unavailableBookErrors.remove(existing.id)
            selectBook(existing.id, openAfter = true)
            Toast.makeText(this, "Known note selected", Toast.LENGTH_SHORT).show()
            return
        }
        createMarkdownBook(uri, metadata)
    }

    private fun createMarkdownBook(uri: Uri, metadata: DocumentMetadata) {
        val id = UUID.randomUUID().toString()
        val fileName = metadata.fileName ?: "Selected note.md"
        val title = cleanBookName(fileName)
        val color = nextBookColor()
        setStatus("Adding $title…")
        submitRenderWork {
            var opened: PdfDocument? = null
            try {
                opened = MarkdownDocument(contentResolver, uri, markdownEngine)
                val markdown = opened as MarkdownDocument
                val outline = outlineEntries(markdown, id, BookmarkSource.MARKDOWN_HEADING)
                val record = BookRecord(
                    id = id,
                    title = title,
                    fileName = fileName,
                    uri = uri.toString(),
                    kind = LibraryItemKind.MARKDOWN,
                    color = color,
                    pageCount = markdown.pageCount,
                    pdfBookmarks = outline,
                    storedBookmarkCount = outline.distinctBy { it.identityKey }.size,
                    sourceSize = metadata.size,
                    sourceLastModified = metadata.lastModified,
                    sourceFingerprint = markdown.content.fingerprint,
                    sourceRevisionToken = UUID.randomUUID().toString(),
                )
                bookRepository.saveIndex(record)
                mainHandler.post {
                    if (isDestroyed) return@post
                    books += record
                    selectedBookIds += record.id
                    persistBooks()
                    rebuildBookmarkIndex()
                    refreshLibraryUi()
                    tabs += ReaderTab(record.id, 0, "Start")
                    activeTabIndex = tabs.lastIndex
                    currentPage = 0
                    persistSession()
                    scheduleBookTextIndex(record)
                    refreshTextSearchScope()
                    activateCurrentTab()
                    Toast.makeText(this, "$title added to the library and table", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                runCatching {
                    contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                showError("Add note failed", t)
            } finally {
                opened?.close()
            }
        }
    }

    private fun createLibraryBook(uri: Uri, metadata: DocumentMetadata) {
        val id = UUID.randomUUID().toString()
        val fileName = metadata.fileName ?: "Selected PDF"
        val title = cleanBookName(fileName)
        val color = nextBookColor()
        setStatus("Adding $title…")

        submitRenderWork {
            var opened: PdfDocument? = null
            try {
                opened = openDocument(uri)
                val outline = outlineEntries(opened, id)
                val record = BookRecord(
                    id = id,
                    title = title,
                    fileName = fileName,
                    uri = uri.toString(),
                    color = color,
                    pageCount = opened.pageCount,
                    pdfBookmarks = outline,
                    storedBookmarkCount = outline.distinctBy { it.identityKey }.size,
                    sourceSize = metadata.size,
                    sourceLastModified = metadata.lastModified,
                    sourceRevisionToken = UUID.randomUUID().toString(),
                )
                bookRepository.saveIndex(record)
                mainHandler.post {
                    if (isDestroyed) return@post
                    books += record
                    selectedBookIds += record.id
                    persistBooks()
                    rebuildBookmarkIndex()
                    refreshLibraryUi()
                    tabs += ReaderTab(record.id, 0, "Start")
                    activeTabIndex = tabs.lastIndex
                    currentPage = 0
                    persistSession()
                    scheduleBookTextIndex(record)
                    refreshTextSearchScope()
                    activateCurrentTab()
                    Toast.makeText(this, "$title added to the library and table", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                showError("Add PDF failed", t)
            } finally {
                opened?.close()
            }
        }
    }

    private fun relinkSource(uri: Uri, resultFlags: Int) {
        val bookId = pendingRelinkBookId.also { pendingRelinkBookId = null } ?: return
        val old = bookById(bookId) ?: return
        if (books.any { it.id != bookId && it.uri == uri.toString() }) {
            showError("Relink failed", IllegalArgumentException("That source is already used by another library item"))
            return
        }
        val metadata = documentMetadataReader.query(uri)
        val mimeType = contentResolver.getType(uri)
        if (
            old.kind == LibraryItemKind.MARKDOWN &&
            !isMarkdownFile(metadata.fileName.orEmpty(), mimeType)
        ) {
            showError("Relink failed", IllegalArgumentException("Choose a .md or .markdown file"))
            return
        }
        try {
            persistReadPermission(uri, resultFlags)
        } catch (t: Throwable) {
            showError("Could not keep access to source", t)
            return
        }
        if (old.kind == LibraryItemKind.PDF) {
            updateKnownBookSource(
                bookId = bookId,
                uri = uri,
                metadata = metadata,
                selectAndOpen = bookId in selectedBookIds,
                forceTextReindex = true,
                refreshReason = "PDF relinked or replaced",
            )
        } else {
            updateKnownMarkdownSource(old, uri, metadata)
        }
    }

    private fun updateKnownMarkdownSource(
        old: BookRecord,
        uri: Uri,
        metadata: DocumentMetadata,
    ) {
        require(old.kind == LibraryItemKind.MARKDOWN)
        setStatus("Relinking ${old.title}…")
        submitPrefetchWork {
            var document: PdfDocument? = null
            try {
                val hydrated = if (old.indexLoaded) old else bookRepository.hydrate(old)
                val opened = MarkdownDocument(contentResolver, uri, markdownEngine)
                document = opened
                val headings = outlineEntries(opened, old.id, BookmarkSource.MARKDOWN_HEADING)
                val updated = hydrated.copy(
                    title = metadata.fileName?.let(::cleanBookName) ?: hydrated.title,
                    fileName = metadata.fileName ?: hydrated.fileName,
                    uri = uri.toString(),
                    pageCount = opened.pageCount,
                    pdfBookmarks = headings,
                    storedBookmarkCount = headings.distinctBy { it.identityKey }.size,
                    indexVersion = CURRENT_BOOK_INDEX_VERSION,
                    indexLoaded = true,
                    sourceSize = metadata.size,
                    sourceLastModified = metadata.lastModified,
                    sourceFingerprint = opened.content.fingerprint,
                    sourceRevisionToken = UUID.randomUUID().toString(),
                )
                bookRepository.saveIndex(updated)
                mainHandler.post {
                    if (isDestroyed || bookById(old.id) == null) return@post
                    val retained = if (old.id in selectedBookIds) {
                        updated
                    } else {
                        updated.copy(
                            pdfBookmarks = emptyList(),
                            externalBookmarks = emptyList(),
                            imageFiles = emptyList(),
                            storedBookmarkCount = updated.bookmarkCount,
                            indexLoaded = false,
                        )
                    }
                    if (!replaceBook(retained)) return@post
                    lastSourceRefreshDiagnostic = sourceRefreshDiagnostic(
                        book = old,
                        metadata = metadata,
                        reason = "Note relinked or replaced",
                        nextRevision = updated.sourceRevisionKey(),
                    )
                    unavailableBookIds.remove(old.id)
                    unavailableBookErrors.remove(old.id)
                    if (old.uri != updated.uri) releaseExactPersistedPermission(old.uri)

                    val destinations = headings.associateBy { it.identityKey }
                    tabs.filter { it.bookId == old.id }.forEach { tab ->
                        val destination = tab.anchorKey?.let(destinations::get)
                        tab.pageIndex = destination?.pageIndex
                            ?: tab.pageIndex.coerceIn(0, updated.pageCount - 1)
                        tab.originPageIndex = destination?.pageIndex
                            ?: tab.originPageIndex.coerceIn(0, updated.pageCount - 1)
                        if (destination != null) tab.label = destination.title
                    }
                    referenceLocation?.takeIf { it.bookId == old.id }?.let { reference ->
                        val destination = reference.anchorKey?.let(destinations::get)
                        reference.pageIndex = destination?.pageIndex
                            ?: reference.pageIndex.coerceIn(0, updated.pageCount - 1)
                        reference.originPageIndex = destination?.pageIndex
                            ?: reference.originPageIndex.coerceIn(0, updated.pageCount - 1)
                    }

                    persistBooks()
                    persistSession()
                    rebuildBookmarkIndex()
                    if (old.id in selectedBookIds) {
                        scheduleBookTextIndex(updated, force = true)
                    } else {
                        deleteBookTextIndex(old.id)
                    }
                    refreshTextSearchScope()
                    refreshLibraryUi()
                    discardSecondaryDocument(old.id)
                    if (activeTabOrNull()?.bookId == old.id) {
                        activateCurrentTab(forceReload = true)
                    } else if (referenceLocation?.bookId == old.id) {
                        renderReference()
                    }
                    Toast.makeText(this, "${updated.title} relinked", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                if (uri.toString() != old.uri) releaseExactPersistedPermission(uri.toString())
                showError("Note relink failed", t)
            } finally {
                document?.close()
            }
        }
    }

    private fun updateKnownBookSource(
        bookId: String,
        uri: Uri,
        metadata: DocumentMetadata,
        selectAndOpen: Boolean,
        forceTextReindex: Boolean = false,
        refreshReason: String = "PDF source updated",
    ) {
        val old = bookById(bookId) ?: return
        require(old.kind == LibraryItemKind.PDF) { "Only PDFs can be relinked" }
        val wasPrimary = pdfBookId == bookId
        setStatus("Updating ${old.title}…")
        val work = {
            var opened: PdfDocument? = null
            try {
                val hydratedOld = if (old.indexLoaded) old else bookRepository.hydrate(old)
                if (wasPrimary && pdfBookId == bookId) {
                    pdf?.close()
                    pdf = null
                    pdfBookId = null
                } else if (!wasPrimary) {
                    prefetchPdf?.close()
                    prefetchPdf = null
                    prefetchBookId = null
                }
                opened = openDocument(uri)
                val updated = hydratedOld.copy(
                    title = metadata.fileName?.let(::cleanBookName) ?: old.title,
                    fileName = metadata.fileName ?: old.fileName,
                    uri = uri.toString(),
                    pageCount = opened.pageCount,
                    pdfBookmarks = outlineEntries(opened, bookId),
                    externalBookmarks = hydratedOld.externalBookmarks.filter { it.pageIndex < opened.pageCount },
                    indexVersion = CURRENT_BOOK_INDEX_VERSION,
                    indexLoaded = true,
                    sourceSize = metadata.size,
                    sourceLastModified = metadata.lastModified,
                    sourceRevisionToken = UUID.randomUUID().toString(),
                )
                bookRepository.saveIndex(updated)
                mainHandler.post {
                    if (selectAndOpen) selectedBookIds += bookId
                    val retained = if (bookId in selectedBookIds) {
                        updated
                    } else {
                        updated.copy(
                            pdfBookmarks = emptyList(),
                            externalBookmarks = emptyList(),
                            imageFiles = emptyList(),
                            storedBookmarkCount = updated.bookmarkCount,
                            indexLoaded = false,
                        )
                    }
                    if (isDestroyed || replaceBook(retained).not()) return@post
                    lastSourceRefreshDiagnostic = sourceRefreshDiagnostic(
                        book = old,
                        metadata = metadata,
                        reason = refreshReason,
                        nextRevision = updated.sourceRevisionKey(),
                    )
                    unavailableBookIds.remove(bookId)
                    unavailableBookErrors.remove(bookId)
                    persistBooks()
                    if (old.uri != updated.uri) runCatching {
                        contentResolver.releasePersistableUriPermission(
                            Uri.parse(old.uri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    rebuildBookmarkIndex()
                    refreshLibraryUi()
                    val textIndexChanged = forceTextReindex ||
                        old.uri != updated.uri || old.pageCount != updated.pageCount ||
                        old.sourceRevisionKey() != updated.sourceRevisionKey()
                    if (bookId in selectedBookIds) {
                        scheduleBookTextIndex(
                            updated,
                            force = textIndexChanged,
                        )
                    } else if (textIndexChanged) {
                        deleteBookTextIndex(bookId)
                    }
                    refreshTextSearchScope()
                    tabs.filter { it.bookId == bookId }.forEach {
                        it.pageIndex = it.pageIndex.coerceIn(0, updated.pageCount - 1)
                        it.originPageIndex = it.originPageIndex.coerceIn(0, updated.pageCount - 1)
                    }
                    referenceLocation?.takeIf { it.bookId == bookId }?.let {
                        it.pageIndex = it.pageIndex.coerceIn(0, updated.pageCount - 1)
                        it.originPageIndex = it.originPageIndex.coerceIn(0, updated.pageCount - 1)
                    }
                    val reloadPrimary = wasPrimary
                    val reloadReference = referenceLocation?.bookId == bookId
                    if (reloadPrimary) {
                        invalidateRendering(clearCache = true)
                    } else {
                        if (reloadReference) {
                            referenceSurface.clearPages()
                            referenceDisplayedPageKeys = emptySet()
                            referencePendingPageKeys = emptySet()
                            updateCachePins()
                        }
                        pageCache.removeBook(bookId)
                    }
                    if (prefetchBookId == bookId) submitPrefetchWork {
                        if (prefetchBookId == bookId) {
                            prefetchPdf?.close()
                            prefetchPdf = null
                            prefetchBookId = null
                        }
                    }
                    if (selectAndOpen && reloadPrimary) {
                        val existingTab = tabs.indexOfFirst { it.bookId == bookId }
                        if (existingTab >= 0) activeTabIndex = existingTab
                        activateCurrentTab(forceReload = true)
                    } else if (selectAndOpen) {
                        openBookInTab(bookId)
                    } else if (reloadPrimary) {
                        activateCurrentTab(forceReload = true)
                    }
                    if (reloadReference) renderReference()
                    persistSession()
                    Toast.makeText(this, "${updated.title} updated", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                if (uri.toString() != old.uri) runCatching {
                    contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                showError("PDF update failed", t)
                if (wasPrimary) mainHandler.post {
                    if (!isDestroyed && activeTabOrNull()?.bookId == bookId) {
                        activateCurrentTab(forceReload = true)
                    }
                }
            } finally {
                opened?.close()
            }
            Unit
        }
        if (wasPrimary) submitRenderWork(work) else submitPrefetchWork(work)
    }

    private fun importExternalToc(uri: Uri, resultFlags: Int) {
        val bookId = pendingTocBookId.also { pendingTocBookId = null } ?: return
        val book = bookById(bookId) ?: return
        val sourceAccessPersisted = runCatching { persistReadPermission(uri, resultFlags) }.isSuccess
        val metadata = documentMetadataReader.query(uri)
        val name = metadata.fileName ?: "bookmark file"
        setStatus("Importing $name…")

        submitRenderWork {
            try {
                val hydrated = bookRepository.hydrate(book)
                require(hydrated.indexLoaded) { "Could not load the existing bookmark index" }
                val text = contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open bookmark file" }
                    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                val parsed = ExternalTocParser.parse(text, hydrated.pageCount, hydrated.id)
                val imported = hydrated.copy(
                    externalBookmarks = parsed.entries,
                    externalTocLabel = name,
                    externalTocUri = uri.toString().takeIf { sourceAccessPersisted },
                    externalTocSize = metadata.size.takeIf { sourceAccessPersisted },
                    externalTocLastModified = metadata.lastModified.takeIf { sourceAccessPersisted },
                    externalTocFingerprint = textFingerprint(text),
                    indexLoaded = true,
                )
                bookRepository.saveIndex(
                    imported,
                )

                mainHandler.post {
                    if (isDestroyed) return@post
                    val current = bookById(bookId) ?: return@post
                    replaceBook(if (bookId in selectedBookIds) imported else imported.copy(
                        pdfBookmarks = emptyList(),
                        externalBookmarks = emptyList(),
                        imageFiles = emptyList(),
                        storedBookmarkCount = imported.bookmarkCount,
                        indexLoaded = false,
                    ))
                    persistBooks()
                    rebuildBookmarkIndex()
                    renderTabBar()
                    val rejected = if (parsed.rejectedLines > 0) " · ${parsed.rejectedLines} rejected" else ""
                    Toast.makeText(this, "Imported ${parsed.entries.size} bookmarks$rejected", Toast.LENGTH_LONG).show()
                    setStatus("Bookmark index: ${bookmarkIndex.size} entries")
                }
            } catch (t: Throwable) {
                showError("TOC import failed", t)
            }
        }
    }

    private fun outlineEntries(
        document: PdfDocument,
        bookId: String,
        source: BookmarkSource = BookmarkSource.PDF_OUTLINE,
    ): List<BookmarkEntry> =
        document.outline().map {
            BookmarkEntry(
                bookId = bookId,
                title = it.title,
                pageIndex = it.pageIndex,
                path = it.path,
                source = source,
                destinationY = it.targetY,
                destinationKey = it.destinationKey,
                stableKey = if (source == BookmarkSource.MARKDOWN_HEADING) it.destinationKey else null,
            )
        }

    private fun openDocument(uri: Uri): PdfDocument {
        val descriptor = requireNotNull(contentResolver.openFileDescriptor(uri, "r")) {
            "Could not open PDF"
        }
        return MuPdfDocument(descriptor)
    }

    private fun openDocument(book: BookRecord): PdfDocument = when (book.kind) {
        LibraryItemKind.PDF -> openDocument(Uri.parse(book.uri))
        LibraryItemKind.MARKDOWN -> MarkdownDocument(contentResolver, Uri.parse(book.uri), markdownEngine)
        LibraryItemKind.IMAGE_COLLECTION -> ImageCollectionDocument(
            contentResolver,
            book.imageFiles,
            imageDecodePolicy.maxPixels,
        )
    }

    private fun activateCurrentTab(
        forceReload: Boolean = false,
        forceMetadataRefresh: Boolean = false,
        refreshReason: String? = null,
    ) {
        val tab = activeTabOrNull() ?: run {
            updateEmptyState()
            return
        }
        val book = bookById(tab.bookId) ?: return
        val hadPendingOpen = primaryOpenTargetBookId != null
        val request = ++primaryOpenGeneration
        unavailableBookIds.remove(book.id)
        unavailableBookErrors.remove(book.id)
        currentPage = tab.pageIndex.coerceIn(0, book.pageCount - 1)
        tab.pageIndex = currentPage
        renderTabBar()
        updatePageIndicator()
        updateEmptyState()
        persistSession()
        if (!forceReload && !hadPendingOpen && pdfBookId == book.id && pdf != null) {
            renderCurrent()
            return
        }

        primaryOpenTargetBookId = book.id
        ++renderGeneration
        ++prefetchGeneration
        clearPrimaryDisplayedPages()
        if (forceMetadataRefresh) {
            discardSecondaryDocument(book.id)
            if (referenceLocation?.bookId == book.id) {
                ++referenceRenderGeneration
                referenceSurface.clearPages()
                referenceDisplayedPageKeys = emptySet()
                referencePendingPageKeys = emptySet()
            }
            updateCachePins()
            pageCache.removeBook(book.id)
        }
        updateEmptyState()
        setStatus("Opening ${book.title}…")
        submitRenderWork {
            if (request != primaryOpenGeneration || destroying) return@submitRenderWork
            var opened: PdfDocument? = null
            try {
                pdf?.close()
                pdf = null
                pdfBookId = null
                val liveDocument = openDocument(book)
                opened = liveDocument
                val metadata = if (book.kind != LibraryItemKind.IMAGE_COLLECTION) {
                    documentMetadataReader.query(Uri.parse(book.uri))
                } else {
                    DocumentMetadata(null, null, null)
                }
                val liveFingerprint = (liveDocument as? MarkdownDocument)?.content?.fingerprint
                val fileBacked = book.kind != LibraryItemKind.IMAGE_COLLECTION
                val sourceChanged = fileBacked && (
                    sourceMetadataChanged(book, metadata) ||
                        liveFingerprint != null && liveFingerprint != book.sourceFingerprint
                    )
                val sourceMetadataNeedsUpdate = fileBacked &&
                    sourceMetadataNeedsUpdate(book, metadata)
                val shouldRefreshIndex = isTextSearchable(book) && (
                    forceMetadataRefresh || sourceChanged || !book.indexLoaded ||
                        book.indexVersion < CURRENT_BOOK_INDEX_VERSION
                    )
                val refreshedOutline = if (shouldRefreshIndex) {
                    try {
                        outlineEntries(
                            liveDocument,
                            book.id,
                            if (book.kind == LibraryItemKind.MARKDOWN) {
                                BookmarkSource.MARKDOWN_HEADING
                            } else {
                                BookmarkSource.PDF_OUTLINE
                            },
                        )
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    null
                }
                if (refreshedOutline != null) {
                    bookRepository.saveIndex(
                        book.copy(
                            pdfBookmarks = refreshedOutline,
                            externalBookmarks = book.externalBookmarks.filter {
                                it.pageIndex < liveDocument.pageCount
                            },
                            indexVersion = CURRENT_BOOK_INDEX_VERSION,
                            indexLoaded = true,
                            sourceFingerprint = liveFingerprint ?: book.sourceFingerprint,
                        ),
                    )
                }
                if (request != primaryOpenGeneration || destroying) return@submitRenderWork
                pdf = liveDocument
                pdfBookId = book.id
                primaryOpenTargetBookId = null
                opened = null
                mainHandler.post {
                    if (isDestroyed || request != primaryOpenGeneration || activeTabOrNull()?.bookId != book.id) return@post
                    unavailableBookIds.remove(book.id)
                    unavailableBookErrors.remove(book.id)
                    bookById(book.id)?.let { current ->
                        if (
                            refreshedOutline != null || sourceMetadataNeedsUpdate ||
                            forceMetadataRefresh || sourceChanged
                        ) {
                            val updated = current.copy(
                                    fileName = metadata.fileName ?: current.fileName,
                                    title = metadata.fileName?.let(::cleanBookName) ?: current.title,
                                    pageCount = if (refreshedOutline != null) liveDocument.pageCount else current.pageCount,
                                    pdfBookmarks = refreshedOutline ?: current.pdfBookmarks,
                                    externalBookmarks = if (refreshedOutline != null) {
                                        current.externalBookmarks.filter { it.pageIndex < liveDocument.pageCount }
                                    } else {
                                        current.externalBookmarks
                                    },
                                    indexVersion = if (refreshedOutline != null) {
                                        CURRENT_BOOK_INDEX_VERSION
                                    } else {
                                        current.indexVersion
                                    },
                                    storedBookmarkCount = current.bookmarkCount,
                                    indexLoaded = refreshedOutline != null || current.indexLoaded,
                                    sourceSize = metadata.size ?: current.sourceSize,
                                    sourceLastModified = metadata.lastModified ?: current.sourceLastModified,
                                    sourceFingerprint = liveFingerprint ?: current.sourceFingerprint,
                                    sourceRevisionToken = if (forceMetadataRefresh || sourceChanged) {
                                        UUID.randomUUID().toString()
                                    } else {
                                        current.sourceRevisionToken
                                    },
                            )
                            val revisionChanged =
                                current.sourceRevisionKey() != updated.sourceRevisionKey()
                            replaceBook(updated)
                            if (refreshedOutline != null) {
                                val destinations = refreshedOutline.associateBy { it.identityKey }
                                tabs.filter { it.bookId == book.id }.forEach { refreshedTab ->
                                    val destination = refreshedTab.anchorKey?.let(destinations::get)
                                    refreshedTab.pageIndex = destination?.pageIndex
                                        ?: refreshedTab.pageIndex.coerceIn(0, liveDocument.pageCount - 1)
                                    refreshedTab.originPageIndex = destination?.pageIndex
                                        ?: refreshedTab.originPageIndex.coerceIn(0, liveDocument.pageCount - 1)
                                    if (destination != null) refreshedTab.label = destination.title
                                }
                                referenceLocation?.takeIf { it.bookId == book.id }?.let { refreshedReference ->
                                    val destination = destinations[refreshedReference.anchorKey]
                                    refreshedReference.pageIndex = destination?.pageIndex
                                        ?: refreshedReference.pageIndex.coerceIn(0, liveDocument.pageCount - 1)
                                    refreshedReference.originPageIndex = destination?.pageIndex
                                        ?: refreshedReference.originPageIndex.coerceIn(0, liveDocument.pageCount - 1)
                                }
                                currentPage = activeTab().pageIndex.coerceIn(0, liveDocument.pageCount - 1)
                            }
                            persistBooks()
                            rebuildBookmarkIndex()
                            if (isTextSearchable(updated)) {
                                scheduleBookTextIndex(
                                    updated,
                                    force = forceMetadataRefresh || sourceChanged || revisionChanged,
                                )
                            }
                            if (forceMetadataRefresh || sourceChanged || revisionChanged) {
                                lastSourceRefreshDiagnostic = sourceRefreshDiagnostic(
                                    book = current,
                                    metadata = metadata,
                                    reason = refreshReason ?: if (sourceChanged) {
                                        "Source stamps changed"
                                    } else {
                                        "Explicit refresh"
                                    },
                                    nextRevision = updated.sourceRevisionKey(),
                                )
                                discardSecondaryDocument(book.id)
                            }
                            refreshTextSearchScope()
                        }
                    }
                    updateEmptyState()
                    updatePageIndicator()
                    renderCurrent()
                    if ((forceMetadataRefresh || sourceChanged) && referenceLocation?.bookId == book.id) {
                        renderReference()
                    }
                }
            } catch (t: Throwable) {
                if (request == primaryOpenGeneration && !destroying) mainHandler.post {
                    if (request != primaryOpenGeneration || isDestroyed) return@post
                    primaryOpenTargetBookId = null
                    unavailableBookIds += book.id
                    unavailableBookErrors[book.id] = sourceUnavailableReason(t)
                    updatePageIndicator()
                    updateEmptyState()
                    refreshLibraryUi()
                    setStatus("Could not open ${book.title}: ${sourceUnavailableReason(t)}")
                }
            } finally {
                opened?.close()
            }
        }
    }

    private fun checkActiveSourceFreshness() {
        val book = activeBook() ?: return
        val expectedRevision = book.sourceRevisionKey()
        if (book.kind == LibraryItemKind.MARKDOWN) {
            val expectedFingerprint = book.sourceFingerprint
            submitPrefetchWork {
                try {
                    val liveFingerprint = MarkdownDocument(
                        contentResolver,
                        Uri.parse(book.uri),
                        markdownEngine,
                    ).use { it.content.fingerprint }
                    if (liveFingerprint != expectedFingerprint) mainHandler.post {
                        val current = activeBook()
                        if (
                            !isDestroyed && current?.id == book.id &&
                            current.sourceFingerprint == expectedFingerprint &&
                            current.sourceRevisionKey() == expectedRevision
                        ) {
                            setStatus("${book.title} changed · refreshing…")
                            activateCurrentTab(
                                forceReload = true,
                                forceMetadataRefresh = true,
                                refreshReason = "Note fingerprint changed on resume",
                            )
                        }
                    }
                } catch (_: Throwable) {
                    // Keep the already-rendered note visible. Opening or explicitly refreshing
                    // it remains the authoritative source-access check.
                }
            }
            return
        }
        if (book.kind != LibraryItemKind.PDF) return
        submitRenderWork {
            if (destroying) return@submitRenderWork
            val metadata = runCatching {
                documentMetadataReader.query(Uri.parse(book.uri))
            }.getOrNull() ?: return@submitRenderWork
            if (sourceMetadataChanged(book, metadata)) mainHandler.post {
                if (
                    !isDestroyed && activeTabOrNull()?.bookId == book.id &&
                    activeBook()?.sourceRevisionKey() == expectedRevision
                ) {
                    setStatus("${book.title} changed · refreshing…")
                    activateCurrentTab(
                        forceReload = true,
                        forceMetadataRefresh = true,
                        refreshReason = "Resume detected ${sourceMetadataChangeSummary(book, metadata)}",
                    )
                }
            }
        }
    }

    private fun sourceRefreshDiagnostic(
        book: BookRecord,
        metadata: DocumentMetadata,
        reason: String,
        nextRevision: String,
    ): String = buildString {
        append(reason)
        append(" · ${book.title}")
        val metadataChange = sourceMetadataChangeSummary(book, metadata)
        if (metadataChange != "an explicit source change") append(" · $metadataChange")
        append(" · ${book.sourceRevisionKey().take(12)}→${nextRevision.take(12)}")
    }

    private fun sourceUnavailableReason(throwable: Throwable): String {
        val detail = throwable.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        return when {
            throwable is SecurityException -> "Read permission is no longer available"
            detail.contains("ENOENT", ignoreCase = true) ||
                detail.contains("not found", ignoreCase = true) -> "The file or folder could not be found"
            detail.isNotEmpty() -> detail.take(180)
            else -> "The source could not be opened"
        }
    }

    private fun cleanBookName(label: String): String = when {
        label.endsWith(".markdown", ignoreCase = true) -> label.dropLast(9)
        label.endsWith(".pdf", ignoreCase = true) || label.endsWith(".md", ignoreCase = true) ->
            label.substringBeforeLast('.')
        else -> label
    }

    private fun rebuildBookmarkIndex() {
        val destinations = buildList {
            books
                .asSequence()
                .filter { it.id in selectedBookIds && it.indexLoaded }
                .forEach { book ->
                    val bookmarks = (book.pdfBookmarks + book.externalBookmarks)
                        .distinctBy { it.identityKey }
                    if (
                        book.kind == LibraryItemKind.IMAGE_COLLECTION ||
                        book.kind == LibraryItemKind.MARKDOWN || bookmarks.isEmpty()
                    ) {
                        add(
                            BookmarkEntry(
                                bookId = book.id,
                                title = book.fileName,
                                pageIndex = 0,
                                path = book.fileName,
                                source = BookmarkSource.FILE_ROOT,
                                stableKey = "file-root",
                            ),
                        )
                    }
                    addAll(bookmarks)
                }
        }
        bookmarkIndex.replace(
            destinations,
            emptyList(),
        )
        if (::searchButton.isInitialized) updateNavigationControls()
    }

    private fun updateNavigationControls() {
        val enabled = selectedBookIds.isNotEmpty()
        searchButton.isEnabled = enabled
        searchButton.alpha = if (enabled) 1f else 0.35f
        searchButton.imageAlpha = if (chromeVisible) 0xff else 0xcc
        searchButton.background = chromeButtonBackground(
            colorWithAlpha(uiPalette.surface, if (chromeVisible) 0xe6 else 0x99),
        )
    }

    private fun updateEmptyState() {
        if (!::emptyHint.isInitialized) return
        val active = activeBook()
        val loadedActiveBook = active != null && pdf != null && pdfBookId == active.id
        val sourceUnavailable = active != null && active.id in unavailableBookIds
        emptyHint.text = when {
            books.isEmpty() -> "Add a PDF, Markdown note, or campaign folder from ⋮"
            selectedBookIds.isEmpty() -> "Select items from Library"
            active == null -> "Choose a library item from ⋮"
            sourceUnavailable -> buildString {
                append("${active.fileName} is unavailable")
                unavailableBookErrors[active.id]?.let { append("\n$it") }
            }
            else -> "Opening ${active.title}…"
        }
        emptyHint.setTextColor(if (sourceUnavailable) uiPalette.error else uiPalette.textSecondary)
        sourceRecoveryActions.visibility = if (sourceUnavailable) View.VISIBLE else View.GONE
        sourceRelinkButton.text = if (active?.kind == LibraryItemKind.IMAGE_COLLECTION) {
            "Rescan"
        } else {
            "Relink"
        }
        emptyStateContainer.visibility = if (loadedActiveBook) View.GONE else View.VISIBLE
        pageIndicator.visibility = if (
            !loadedActiveBook || !chromeVisible || active?.kind == LibraryItemKind.MARKDOWN
        ) View.INVISIBLE else View.VISIBLE
        updateReferenceIndicator()
    }

    private fun showPageJumpDialog() = showPageJumpDialog(currentPage) { pageIndex ->
        jumpCurrentTab(pageIndex)
    }

    private fun showReferencePageJumpDialog() {
        val reference = referenceLocation ?: return
        val pageCount = bookById(reference.bookId)?.pageCount ?: return
        showPageJumpDialog(reference.pageIndex, pageCount) { pageIndex -> jumpReference(pageIndex) }
    }

    private fun showPageJumpDialog(initialPage: Int, onJump: (Int) -> Unit) {
        val pageCount = activeBook()?.pageCount ?: return
        showPageJumpDialog(initialPage, pageCount, onJump)
    }

    private fun showPageJumpDialog(initialPage: Int, pageCount: Int, onJump: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setText((initialPage + 1).toString())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Go to page")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val requested = input.text.toString().toIntOrNull()
                if (requested == null || requested !in 1..pageCount) {
                    Toast.makeText(this, "Page must be 1–$pageCount", Toast.LENGTH_SHORT).show()
                } else {
                    onJump(requested - 1)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            input.post {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
    }

    private fun hasReferencePane(): Boolean = referenceLocation != null || textSearchSession != null

    private fun imageUri(bookId: String, pageIndex: Int): String? =
        bookById(bookId)
            ?.takeIf { it.kind == LibraryItemKind.IMAGE_COLLECTION }
            ?.imageFiles
            ?.getOrNull(pageIndex)
            ?.uri

    private fun capturePrimaryImageViewport() {
        val tab = activeTabOrNull() ?: return
        if (bookById(tab.bookId)?.kind == LibraryItemKind.MARKDOWN && primaryMarkdownSurface.visibility == View.VISIBLE) {
            val viewport = primaryMarkdownSurface.captureViewportState() ?: return
            primaryMarkdownViewports[tab] = viewport
            tab.pageIndex = viewport.sectionIndex
            currentPage = viewport.sectionIndex
            return
        }
        val displayed = primaryDisplayedPageKeys.singleOrNull() ?: return
        if (displayed.bookId != tab.bookId) return
        val uri = imageUri(displayed.bookId, displayed.pageIndex) ?: return
        val viewport = readerSurface.captureViewportState() ?: return
        primaryImageViewports.getOrPut(tab, ::HashMap)[uri] = viewport
    }

    private fun captureReferenceImageViewport() {
        referenceLocation?.takeIf { bookById(it.bookId)?.kind == LibraryItemKind.MARKDOWN }?.let { reference ->
            if (referenceMarkdownSurface.visibility == View.VISIBLE) {
                val viewport = referenceMarkdownSurface.captureViewportState() ?: return
                referenceMarkdownViewport = viewport
                reference.pageIndex = viewport.sectionIndex
                return
            }
        }
        val displayed = referenceDisplayedPageKeys.singleOrNull() ?: return
        val uri = imageUri(displayed.bookId, displayed.pageIndex) ?: return
        val viewport = referenceSurface.captureViewportState() ?: return
        referenceImageViewports["${displayed.bookId}|$uri"] = viewport
    }

    private fun stageViewportRestoresForLayout() {
        capturePrimaryImageViewport()
        captureReferenceImageViewport()
        pendingPrimaryViewportRestore = primaryDisplayedPageKeys.singleOrNull()?.let { displayed ->
            readerSurface.captureViewportState()?.let { viewport ->
                PendingViewportRestore(displayed.bookId, displayed.pageIndex, viewport)
            }
        }
        pendingReferenceViewportRestore = referenceDisplayedPageKeys.singleOrNull()?.let { displayed ->
            referenceSurface.captureViewportState()?.let { viewport ->
                PendingViewportRestore(displayed.bookId, displayed.pageIndex, viewport)
            }
        }
    }

    private fun primaryImageViewport(bookId: String, pageIndex: Int): ReaderSurface.ViewportState? {
        val tab = activeTabOrNull()?.takeIf { it.bookId == bookId } ?: return null
        val uri = imageUri(bookId, pageIndex) ?: return null
        return primaryImageViewports[tab]?.get(uri)
    }

    private fun referenceImageViewport(bookId: String, pageIndex: Int): ReaderSurface.ViewportState? {
        val uri = imageUri(bookId, pageIndex) ?: return null
        return referenceImageViewports["$bookId|$uri"]
    }

    private fun setPrimaryPages(
        bookId: String,
        firstPage: Int,
        leftPage: RenderedPdfPage?,
        rightPage: RenderedPdfPage?,
    ) {
        val pending = pendingPrimaryViewportRestore
        val pendingViewport = pending
            ?.takeIf { it.bookId == bookId && it.pageIndex == firstPage }
            ?.viewport
        if (pending != null) pendingPrimaryViewportRestore = null
        readerSurface.setPages(
            leftPage,
            rightPage,
            resetTransform = true,
            viewportState = pendingViewport ?: primaryImageViewport(bookId, firstPage),
        )
    }

    private fun setReferencePage(bookId: String, pageIndex: Int, page: RenderedPdfPage) {
        val pending = pendingReferenceViewportRestore
        val pendingViewport = pending
            ?.takeIf { it.bookId == bookId && it.pageIndex == pageIndex }
            ?.viewport
        if (pending != null) pendingReferenceViewportRestore = null
        referenceSurface.setPages(
            page,
            null,
            resetTransform = true,
            viewportState = pendingViewport ?: referenceImageViewport(bookId, pageIndex),
        )
        val reference = referenceLocation
        if (reference != null && reference.bookId == bookId && reference.pageIndex == pageIndex) {
            reference.pendingDestinationY?.let { targetY ->
                referenceSurface.focusOnY(pageIndex, targetY)
                reference.pendingDestinationY = null
            }
        }
    }

    private fun clearCurrentImageViewports() {
        activeTabOrNull()?.let { tab ->
            primaryMarkdownViewports.remove(tab)
            val saved = primaryImageViewports[tab]
            if (saved != null) {
                imageUri(tab.bookId, tab.pageIndex)?.let { saved.remove(it) }
                primaryDisplayedPageKeys.singleOrNull()
                    ?.takeIf { it.bookId == tab.bookId }
                    ?.let { imageUri(it.bookId, it.pageIndex) }
                    ?.let { saved.remove(it) }
            }
        }
        referenceLocation?.let { reference ->
            if (bookById(reference.bookId)?.kind == LibraryItemKind.MARKDOWN) referenceMarkdownViewport = null
            imageUri(reference.bookId, reference.pageIndex)?.let { uri ->
                referenceImageViewports.remove("${reference.bookId}|$uri")
            }
            referenceDisplayedPageKeys.singleOrNull()
                ?.let { displayed -> imageUri(displayed.bookId, displayed.pageIndex)?.let { displayed.bookId to it } }
                ?.let { (bookId, uri) -> referenceImageViewports.remove("$bookId|$uri") }
        }
    }

    private fun effectiveSpreadMode(): Boolean =
        spreadMode && !hasReferencePane() && activeBook()?.kind == LibraryItemKind.PDF

    private fun visibleFirstPage(pageIndex: Int, pageCount: Int): Int =
        SpreadLayout.firstPage(pageIndex, pageCount, effectiveSpreadMode(), spreadSkipCover)

    private fun visibleSecondPage(firstPage: Int, pageCount: Int): Int? =
        SpreadLayout.secondPage(firstPage, pageCount, effectiveSpreadMode(), spreadSkipCover)

    private fun adjacentSpreadFirst(firstPage: Int, direction: Int, pageCount: Int): Int? =
        SpreadLayout.adjacentFirst(firstPage, direction, pageCount, spreadSkipCover)

    private fun moveBy(direction: Int) {
        val document = pdf ?: return
        if (pdfBookId != activeTabOrNull()?.bookId) return
        val next = if (effectiveSpreadMode()) {
            val first = visibleFirstPage(currentPage, document.pageCount)
            adjacentSpreadFirst(first, direction, document.pageCount) ?: return
        } else {
            (currentPage + direction).coerceIn(0, document.pageCount - 1)
        }
        if (next == currentPage) return
        capturePrimaryImageViewport()
        currentPage = next
        activeTab().pageIndex = currentPage
        persistSession()
        // Deliberately do not rebuild tabs: page movement never changes tab identity/name.
        renderCurrent()
    }

    private fun moveReferenceBy(direction: Int) {
        val reference = referenceLocation ?: return
        val pageCount = bookById(reference.bookId)?.pageCount ?: return
        val next = (reference.pageIndex + direction).coerceIn(0, pageCount - 1)
        if (next == reference.pageIndex) return
        captureReferenceImageViewport()
        reference.pageIndex = next
        reference.pendingDestinationY = null
        renderReference()
    }

    private fun jumpCurrentTab(pageIndex: Int) {
        val document = pdf ?: return
        if (pdfBookId != activeTabOrNull()?.bookId) return
        val clamped = pageIndex.coerceIn(0, document.pageCount - 1)
        if (clamped == currentPage) return
        capturePrimaryImageViewport()
        currentPage = clamped
        activeTab().pageIndex = clamped
        persistSession()
        renderCurrent()
    }

    private fun jumpReference(pageIndex: Int) {
        val reference = referenceLocation ?: return
        val pageCount = bookById(reference.bookId)?.pageCount ?: return
        val clamped = pageIndex.coerceIn(0, pageCount - 1)
        if (clamped == reference.pageIndex) return
        captureReferenceImageViewport()
        reference.pageIndex = clamped
        reference.pendingDestinationY = null
        renderReference()
    }

    private fun openPdfLink(bookId: String, link: PdfLinkInfo) {
        val book = bookById(bookId) ?: return
        val targetPage = link.targetPageIndex.coerceIn(0, book.pageCount - 1)
        val immediateMatch = bookmarkIndex.matchDestination(
            bookId = bookId,
            pageIndex = targetPage,
            targetY = link.targetY,
            destinationKey = link.destinationKey,
        )
        val pageOnlyDestination = link.targetY == null || link.targetY == 0f
        if (immediateMatch == null || pageOnlyDestination && !immediateMatch.exactDestination) {
            resolvePdfLinkLabel(book, link)
            return
        }
        finishOpeningPdfLink(bookId, link, sourceText = null)
    }

    private fun resolvePdfLinkLabel(book: BookRecord, link: PdfLinkInfo) {
        setStatus("Reading link label…")
        val usePrimaryDocument = pdfBookId == book.id && pdf != null
        val work = {
            val sourceText = try {
                val document = if (usePrimaryDocument) {
                    pdf?.takeIf { pdfBookId == book.id }
                } else {
                    secondaryDocument(book)
                }
                document?.textInRect(link.sourcePageIndex, link.bounds)
            } catch (_: Throwable) {
                null
            }
            mainHandler.post {
                if (!isDestroyed) finishOpeningPdfLink(book.id, link, sourceText)
            }
            Unit
        }
        if (usePrimaryDocument) submitRenderWork(work) else submitPrefetchWork(work)
    }

    private fun finishOpeningPdfLink(bookId: String, link: PdfLinkInfo, sourceText: String?) {
        clearActiveSearchHighlight()
        capturePrimaryImageViewport()
        val book = bookById(bookId) ?: return
        val targetPage = link.targetPageIndex.coerceIn(0, book.pageCount - 1)
        val bookmarkMatch = bookmarkIndex.matchDestination(
            bookId = bookId,
            pageIndex = targetPage,
            targetY = link.targetY,
            destinationKey = link.destinationKey,
            sourceText = sourceText,
        )
        val pageCandidates = book.pdfBookmarks.filter { it.pageIndex == targetPage }
        val positionedCandidates = pageCandidates.count { it.destinationY != null }
        val keyMatches = link.destinationKey?.let { key ->
            pageCandidates.count { it.destinationKey == key }
        } ?: 0
        val sourceLabel = sourceText
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length <= 64) it else "${it.take(63)}…" }
        lastLinkDiagnostic = buildString {
            append("${book.title} · page ${targetPage + 1}")
            append(" · linkY=${link.targetY ?: "none"}")
            append("\nKey: ${link.destinationKey?.take(120) ?: "none"}")
            append("\nLabel: ${sourceText?.take(120) ?: "none"}")
            append(" · page bookmarks=${pageCandidates.size}")
            append(" · positioned=$positionedCandidates")
            append(" · destination-key matches=$keyMatches")
            append(" · result=${bookmarkMatch?.entry?.title ?: sourceLabel ?: "physical page fallback"}")
        }
        val nearestBookmark = bookmarkMatch?.entry
        val exactBookmark = nearestBookmark?.takeIf { bookmarkMatch.exactDestination }
        val destinationAnchorKey = link.destinationKey
            ?.let { "$bookId|link-uri:$it" }
            ?: link.targetY
                ?.takeIf { it.isFinite() }
                ?.let { "$bookId|link:$targetPage:${it.roundToInt()}" }
            ?: "$bookId|link:$targetPage"
        val anchorKey = exactBookmark?.identityKey ?: destinationAnchorKey
        val physicalPageLabel = "Page ${targetPage + 1}"
        val label = when {
            exactBookmark != null -> exactBookmark.title
            nearestBookmark != null -> "${nearestBookmark.title} · p${targetPage + 1}"
            sourceLabel != null -> "$sourceLabel · p${targetPage + 1}"
            else -> physicalPageLabel
        }

        var existing = tabs.indexOfFirst { it.anchorKey == anchorKey }
        if (existing < 0 && anchorKey != destinationAnchorKey) {
            // Upgrade a tab created by an older naming fallback instead of duplicating it
            // when the newly resolved bookmark changes its canonical anchor identity.
            existing = tabs.indexOfFirst { it.anchorKey == destinationAnchorKey }
        }
        if (existing >= 0) {
            val existingTab = tabs[existing]
            if (existingTab.label.startsWith("Page ") && label != physicalPageLabel) {
                existingTab.label = label
                existingTab.anchorKey = anchorKey
                persistSession()
            }
            switchToTab(existing)
            return
        }

        tabs += ReaderTab(bookId, targetPage, label, anchorKey)
        activeTabIndex = tabs.lastIndex
        currentPage = targetPage
        persistSession()
        activateCurrentTab()
    }

    private fun openBookmark(entry: BookmarkEntry, newTabRequested: Boolean) {
        clearActiveSearchHighlight()
        capturePrimaryImageViewport()
        val existing = tabs.indexOfFirst { it.anchorKey == entry.identityKey }
        if (existing >= 0) {
            switchToTab(existing)
            return
        }

        val book = bookById(entry.bookId) ?: return
        val clamped = entry.pageIndex.coerceIn(0, book.pageCount - 1)
        if (newTabRequested) {
            tabs += ReaderTab(entry.bookId, clamped, entry.title, entry.identityKey)
            activeTabIndex = tabs.lastIndex
        } else {
            val tab = activeTabOrNull() ?: run {
                tabs += ReaderTab(entry.bookId, clamped, entry.title, entry.identityKey)
                activeTabIndex = tabs.lastIndex
                tabs.last()
            }
            primaryMarkdownViewports.remove(tab)
            tab.bookId = entry.bookId
            tab.pageIndex = clamped
            tab.label = entry.title
            tab.anchorKey = entry.identityKey
            tab.originPageIndex = clamped
        }

        currentPage = clamped
        persistSession()
        activateCurrentTab()
    }

    private fun openBookmarkAlongside(entry: BookmarkEntry) {
        val book = bookById(entry.bookId) ?: return
        val existing = referenceLocation
        if (existing?.anchorKey == entry.identityKey) return

        val enteringSplit = !hasReferencePane()
        if (enteringSplit) {
            stageViewportRestoresForLayout()
            ++renderGeneration
            primaryPendingPageKeys = emptySet()
        } else {
            capturePrimaryImageViewport()
            captureReferenceImageViewport()
        }
        val switchingBook = existing != null && existing.bookId != entry.bookId
        val primaryWasShowingTwoPages = enteringSplit &&
            visibleSecondPage(
                visibleFirstPage(currentPage, activeBook()?.pageCount ?: 1),
                activeBook()?.pageCount ?: 1,
            ) != null
        textSearchSession = null
        textSearchResultAdapter.notifyDataSetChanged()
        referenceLocation = ReferenceLocation(
            bookId = entry.bookId,
            pageIndex = entry.pageIndex.coerceIn(0, book.pageCount - 1),
            label = entry.title,
            anchorKey = entry.identityKey,
            originPageIndex = entry.pageIndex.coerceIn(0, book.pageCount - 1),
            originDestinationY = entry.destinationY,
            pendingDestinationY = entry.destinationY,
        )
        referenceMarkdownViewport = null
        ++prefetchGeneration
        if (switchingBook) {
            referenceSurface.clearPages()
            referenceDisplayedPageKeys = emptySet()
            referencePendingPageKeys = emptySet()
            updateCachePins()
        }
        updateReferenceUi()
        root.requestApplyInsets()

        if (enteringSplit) {
            if (primaryWasShowingTwoPages) readerSurface.resetTransform()
            afterReaderLayout {
                renderCurrent()
                renderReference()
            }
        } else {
            renderReference()
        }
    }

    private fun closeReference(renderPrimary: Boolean = true) {
        if (!hasReferencePane()) return
        stageViewportRestoresForLayout()
        ++renderGeneration
        referenceLocation = null
        textSearchSession = null
        ++textSearchQueryGeneration
        ++referenceRenderGeneration
        ++prefetchGeneration
        referenceSurface.clearPages()
        referenceMarkdownSurface.clear()
        referenceMarkdownViewport = null
        referenceDisplayedPageKeys = emptySet()
        referencePendingPageKeys = emptySet()
        pendingReferenceViewportRestore = null
        updateReferenceUi()
        updateCachePins()
        root.requestApplyInsets()

        if (renderPrimary && pdf != null) {
            readerSurface.resetTransform()
            afterReaderLayout {
                renderCurrent(keepCurrentVisualOnMiss = !spreadMode)
            }
        }
    }

    private fun updateReferenceUi() {
        val reference = referenceLocation
        val search = textSearchSession
        val visible = reference != null || search != null
        referenceDivider.visibility = if (visible) View.VISIBLE else View.GONE
        referencePane.visibility = if (visible) View.VISIBLE else View.GONE
        val markdownReference = reference?.bookId?.let(::bookById)?.kind == LibraryItemKind.MARKDOWN
        referenceSurface.visibility = if (reference != null && !markdownReference) View.VISIBLE else View.GONE
        referenceMarkdownSurface.visibility = if (reference != null && markdownReference) View.VISIBLE else View.GONE
        referenceSearchContainer.visibility = if (search != null) View.VISIBLE else View.GONE
        if (search != null) {
            referenceSearchTitle.text = "“${search.query}”"
            populateSearchScopeRow(referenceSearchScopeRow, search.scopeBookId) { bookId ->
                changeSearchScope(bookId)
            }
            updateTextSearchStatus()
        }
        updatePageIndicator()
    }

    private fun updateReferenceIndicator() {
        if (
            !::referenceIndicatorContainer.isInitialized ||
            !::referenceIndicatorTitle.isInitialized ||
            !::referencePageIndicator.isInitialized
        ) return

        val reference = referenceLocation
        val search = textSearchSession
        if (reference == null && search == null) {
            referenceIndicatorContainer.visibility = View.GONE
            return
        }

        if (reference != null) {
            val book = bookById(reference.bookId)
            val title = listOfNotNull(
                book?.title?.takeIf(String::isNotBlank),
                reference.label.takeIf { it.isNotBlank() && it != book?.title },
            ).joinToString(" · ").ifBlank { "Reference" }
            val pageCount = book?.pageCount ?: 0
            val isMarkdown = book?.kind == LibraryItemKind.MARKDOWN

            referenceIndicatorTitle.text = title
            referencePageIndicator.text = if (isMarkdown) "" else "${reference.pageIndex + 1} / $pageCount"
            referencePageIndicator.visibility = if (isMarkdown) View.GONE else View.VISIBLE
            referencePageIndicator.isClickable = !isMarkdown
            referencePageIndicator.contentDescription = if (isMarkdown) null else {
                "Go to reference page. Current page ${reference.pageIndex + 1} of $pageCount"
            }
            referencePageIndicator.setOnClickListener(
                if (isMarkdown) null else View.OnClickListener { showReferencePageJumpDialog() },
            )
            referenceIndicatorContainer.background = chromeButtonBackground(
                tabBackgroundColor(book?.color, active = true),
            )
            referenceIndicatorContainer.contentDescription =
                "$title${if (isMarkdown) "" else ", page ${reference.pageIndex + 1} of $pageCount"}. Long press for reference actions"
        } else if (search != null) {
            val resultCount = search.totalMatches
            val resultLabel = when {
                search.error != null -> "Failed"
                search.searchInFlight && resultCount == 0 -> "Searching…"
                else -> "$resultCount result${if (resultCount == 1) "" else "s"}"
            }
            referenceIndicatorTitle.text = "Search · ${search.query}"
            referencePageIndicator.text = resultLabel
            referencePageIndicator.visibility = View.VISIBLE
            referencePageIndicator.isClickable = false
            referencePageIndicator.contentDescription = null
            referencePageIndicator.setOnClickListener(null)
            referenceIndicatorContainer.background = chromeButtonBackground(
                tabBackgroundColor(search.scopeBookId?.let(::bookById)?.color, active = true),
            )
            referenceIndicatorContainer.contentDescription =
                "Search ${search.query}, $resultLabel. Long press for reference actions"
        }

        // Search results keep their close action available even when document chrome
        // was hidden before the search opened. Document references follow chrome.
        val show = hasReferencePane() && (chromeVisible || search != null)
        referenceIndicatorContainer.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        if (index == activeTabIndex && pdfBookId == tabs[index].bookId) return
        capturePrimaryImageViewport()
        activeTabIndex = index
        currentPage = tabs[index].pageIndex
        persistSession()
        activateCurrentTab()
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1 || index !in tabs.indices) return
        capturePrimaryImageViewport()
        val removed = tabs.removeAt(index)
        primaryMarkdownViewports.remove(removed)
        primaryImageViewports.remove(removed)
        activeTabIndex = when {
            index < activeTabIndex -> activeTabIndex - 1
            activeTabIndex >= tabs.size -> tabs.lastIndex
            else -> activeTabIndex
        }
        currentPage = activeTab().pageIndex
        persistSession()
        activateCurrentTab()
    }

    private fun closeOtherTabs(index: Int) {
        if (index !in tabs.indices || tabs.size <= 1) return
        capturePrimaryImageViewport()
        val kept = tabs[index]
        tabs.filter { it !== kept }.forEach(primaryImageViewports::remove)
        tabs.filter { it !== kept }.forEach(primaryMarkdownViewports::remove)
        tabs.clear()
        tabs += kept
        activeTabIndex = 0
        currentPage = kept.pageIndex
        persistSession()
        activateCurrentTab()
    }

    private fun showTabActions(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Return to tab start · ${pageLocationLabel(tab.bookId, tab.originPageIndex)}" to {
            returnTabToStart(index)
        }
        if (tabs.size > 1) {
            actions += "Close" to { closeTab(index) }
            actions += "Close others" to { closeOtherTabs(index) }
        }
        if (actions.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(tab.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun showReferenceActions() {
        if (!hasReferencePane()) return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        referenceLocation?.let { reference ->
            actions += "Return to reference start · ${pageLocationLabel(reference.bookId, reference.originPageIndex)}" to {
                returnReferenceToStart()
            }
        }
        actions += "Close reference" to { closeReference() }
        val title = referenceLocation?.label ?: textSearchSession?.let { "Search · ${it.query}" } ?: "Reference"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun pageLocationLabel(bookId: String, pageIndex: Int): String =
        when (bookById(bookId)?.kind) {
            LibraryItemKind.IMAGE_COLLECTION -> "image ${pageIndex + 1}"
            LibraryItemKind.MARKDOWN -> bookmarkIndex.contextAtOrBefore(bookId, pageIndex)?.title ?: "start"
            else -> "p${pageIndex + 1}"
        }

    private fun returnTabToStart(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val book = bookById(tab.bookId) ?: return
        capturePrimaryImageViewport()
        primaryMarkdownViewports.remove(tab)
        tab.pageIndex = tab.originPageIndex.coerceIn(0, book.pageCount - 1)
        activeTabIndex = index
        currentPage = tab.pageIndex
        persistSession()
        activateCurrentTab()
    }

    private fun returnReferenceToStart() {
        val reference = referenceLocation ?: return
        val book = bookById(reference.bookId) ?: return
        captureReferenceImageViewport()
        referenceMarkdownViewport = null
        reference.pageIndex = reference.originPageIndex.coerceIn(0, book.pageCount - 1)
        reference.pendingDestinationY = reference.originDestinationY
        renderReference()
    }

    private fun setReaderLayoutMode(mode: ReaderLayoutMode) {
        if (mode == readerLayoutMode) return
        val paneDirectionChanges = hasReferencePane() && resolvedReaderLayout() != resolvedReaderLayout(mode)
        if (paneDirectionChanges) {
            stageViewportRestoresForLayout()
            ++renderGeneration
            ++referenceRenderGeneration
            ++prefetchGeneration
            primaryPendingPageKeys = emptySet()
            referencePendingPageKeys = emptySet()
        }
        readerLayoutMode = mode
        applyReaderLayout()
        renderTabBar()
        persistSession()
        updateCachePins()
        root.requestApplyInsets()
        applyChromeVisibility()
        if (paneDirectionChanges) {
            afterReaderLayout {
                if (pdf != null) renderCurrent(keepCurrentVisualOnMiss = true)
                if (referenceLocation != null) renderReference()
            }
        }
    }

    private fun activeTab(): ReaderTab {
        check(tabs.isNotEmpty()) { "There is no active reader tab" }
        activeTabIndex = activeTabIndex.coerceIn(0, tabs.lastIndex)
        return tabs[activeTabIndex]
    }

    private fun activeTabOrNull(): ReaderTab? {
        if (tabs.isEmpty()) return null
        activeTabIndex = activeTabIndex.coerceIn(0, tabs.lastIndex)
        return tabs[activeTabIndex]
    }

    private fun activeBook(): BookRecord? = activeTabOrNull()?.let { bookById(it.bookId) }

    private fun bookById(bookId: String): BookRecord? = books.firstOrNull { it.id == bookId }

    private fun selectedBooks(): List<BookRecord> = books.filter { it.id in selectedBookIds }

    private fun normalizeSearchScope(): String? {
        val normalized = searchScopeBookId?.takeIf { it in selectedBookIds }
        searchScopeBookId = normalized
        return normalized
    }

    private fun booksForSearchScope(scopeBookId: String?): List<BookRecord> =
        if (scopeBookId == null) selectedBooks() else selectedBooks().filter { it.id == scopeBookId }

    private fun booksForTextSearchScope(scopeBookId: String?): List<BookRecord> =
        booksForSearchScope(scopeBookId).filter(::isTextSearchable)

    private fun isTextSearchable(book: BookRecord): Boolean =
        book.kind == LibraryItemKind.PDF || book.kind == LibraryItemKind.MARKDOWN

    private fun searchScopeBookIds(): Set<String>? = normalizeSearchScope()?.let(::setOf)

    private fun orderedSearchScopeBooks(): List<BookRecord> {
        val selected = selectedBooks()
        val activeId = activeTabOrNull()?.bookId
        val active = selected.firstOrNull { it.id == activeId } ?: return selected
        return listOf(active) + selected.filterNot { it.id == active.id }
    }

    private fun populateSearchScopeRow(
        row: LinearLayout,
        selectedBookId: String?,
        onSelected: (String?) -> Unit,
    ) {
        val density = resources.displayMetrics.density
        val chipHeight = (48 * density).roundToInt()
        val allChipWidth = (56 * density).roundToInt()
        val chipMargin = (4 * density).roundToInt()
        val horizontalPadding = (12 * density).roundToInt()
        val chipMaxWidth = (220 * density).roundToInt()
        row.removeAllViews()

        fun addChip(book: BookRecord?) {
            val bookId = book?.id
            val selected = bookId == selectedBookId
            val label: CharSequence = if (book == null) {
                "All"
            } else {
                SpannableString("●  ${book.title}").apply {
                    setSpan(
                        ForegroundColorSpan(book.color),
                        0,
                        1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            row.addView(
                Button(this).apply {
                    text = label
                    textSize = 13f
                    isAllCaps = false
                    maxLines = 1
                    maxWidth = chipMaxWidth
                    ellipsize = TextUtils.TruncateAt.END
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(horizontalPadding, 0, horizontalPadding, 0)
                    typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (selected) uiPalette.textPrimary else uiPalette.textSecondary)
                    background = chromeButtonBackground(
                        if (selected) uiPalette.surfaceSelected else uiPalette.surfaceRaised,
                    )
                    contentDescription = if (book == null) {
                        "Search all table items"
                    } else {
                        "Search ${book.title}"
                    }
                    setOnClickListener { onSelected(bookId) }
                },
                LinearLayout.LayoutParams(
                    if (book == null) allChipWidth else ViewGroup.LayoutParams.WRAP_CONTENT,
                    chipHeight,
                ).apply {
                    marginEnd = chipMargin
                },
            )
        }

        addChip(null)
        orderedSearchScopeBooks().forEach(::addChip)
    }

    private fun changeSearchScope(bookId: String?) {
        val normalized = bookId?.takeIf { it in selectedBookIds }
        searchScopeBookId = normalized
        val session = textSearchSession ?: return
        if (session.scopeBookId == normalized) {
            updateReferenceUi()
            return
        }
        session.scopeBookId = normalized
        session.results.clear()
        session.resultLimit = TextSearchIndexRepository.RESULT_PAGE_SIZE
        session.totalMatches = 0
        session.indexedPages = 0
        session.readyBooks = 0
        session.searchInFlight = false
        session.preciseContexts.clear()
        textSearchResultAdapter.notifyDataSetChanged()
        if (::referenceSearchList.isInitialized) referenceSearchList.setSelection(0)
        scheduleSelectedBookTextIndexes()
        refreshTextSearchResults()
        updateReferenceUi()
    }

    private fun restoreTabs(state: Bundle?) {
        val availablePageCounts = selectedBooks().associate { it.id to it.pageCount }
        val restored = readerSessionRepository.restoreTabs(state, availablePageCounts)
        tabs += restored.tabs
        if (tabs.isEmpty()) selectedBooks().firstOrNull()?.let { tabs += ReaderTab(it.id, 0, "Start") }
        if (tabs.isNotEmpty()) {
            activeTabIndex = restored.activeTabIndex.coerceIn(0, tabs.lastIndex)
            currentPage = tabs[activeTabIndex].pageIndex
        }
    }

    private fun recordBookmarkVisit(entry: BookmarkEntry) {
        val previous = bookmarkVisitCounts[entry.visitKey] ?: 0
        bookmarkVisitCounts[entry.visitKey] =
            readerSessionRepository.recordBookmarkVisit(entry.visitKey, previous)
    }

    private fun persistSession() {
        readerSessionRepository.saveTabs(tabs, activeTabIndex)
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_SPREAD, spreadMode)
            .putBoolean(PREF_SPREAD_SKIP_COVER, spreadSkipCover)
            .putString(PREF_PAGE_TURN_MODE, pageTurnMode.name)
            .putString(PREF_READER_LAYOUT, readerLayoutMode.name)
            .apply()
    }

    private fun renderTabBar() {
        if (!::tabBar.isInitialized) return
        tabBar.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val tabsAreClosable = tabs.size > 1
        tabBar.orientation = LinearLayout.HORIZONTAL
        tabBar.gravity = Gravity.CENTER_VERTICAL

        tabs.forEachIndexed { index, tab ->
            val active = index == activeTabIndex
            val book = bookById(tab.bookId)
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                minimumHeight = dp(44)
                background = controlBackground(tabBackgroundColor(book?.color, active))
                isClickable = true
                setOnClickListener { switchToTab(index) }
                setOnLongClickListener {
                    showTabActions(index)
                    true
                }
            }

            val label = TextView(this).apply {
                text = tab.label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(260)
                textSize = 14f
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (active) uiPalette.textPrimary else uiPalette.textSecondary)
                setPadding(dp(12), dp(7), dp(if (tabsAreClosable) 7 else 12), dp(7))
                setOnClickListener { switchToTab(index) }
                setOnLongClickListener {
                    showTabActions(index)
                    true
                }
            }
            holder.addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            if (tabsAreClosable) {
                holder.addView(TextView(this).apply {
                    text = "×"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(uiPalette.textSecondary)
                    contentDescription = "Close ${tab.label}"
                    setPadding(dp(5), dp(4), dp(8), dp(4))
                    setOnClickListener { closeTab(index) }
                })
            }

            tabBar.addView(
                holder,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    rightMargin = dp(6)
                },
            )
        }

        bottomTabScroll.post {
            val selected = tabBar.getChildAt(activeTabIndex)
            if (selected != null) bottomTabScroll.scrollTo(max(0, selected.left - dp(12)), 0)
        }
    }

    private fun showBookmarkSearch(initialQuery: String = "") {
        if (selectedBookIds.isEmpty()) {
            Toast.makeText(this, "Put at least one item on the table first.", Toast.LENGTH_LONG).show()
            return
        }
        normalizeSearchScope()

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(uiPalette.surface)
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            setHintTextColor(uiPalette.textMuted)
            setTextColor(uiPalette.textPrimary)
            textSize = 19f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(initialQuery)
            setSelection(text.length)
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
        val smartCaseIndicator = TextView(this).apply {
            text = "Aa"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(uiPalette.searchMatch)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = controlSurfaceBackground(uiPalette.surfaceSelected)
            contentDescription = "Case-sensitive search is on"
            tooltipText = "Case-sensitive search"
            visibility = View.GONE
        }
        inputRow.addView(
            smartCaseIndicator,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
                marginEnd = dp(4)
            },
        )
        inputRow.addView(Button(this).apply {
            text = "×"
            textSize = 20f
            contentDescription = "Close search"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        container.addView(inputRow)

        val modeAndScopeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bookmarkMode = TextView(this).apply {
            text = "Navigate"
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(10), 0, dp(10), 0)
            contentDescription = "Jump to bookmarks and filenames"
        }
        val textMode = TextView(this).apply {
            text = "Full text"
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(10), 0, dp(10), 0)
            contentDescription = "Search document text"
        }
        modeAndScopeRow.addView(bookmarkMode, LinearLayout.LayoutParams(dp(78), dp(48)))
        modeAndScopeRow.addView(
            textMode,
            LinearLayout.LayoutParams(dp(70), dp(48)).apply { marginStart = dp(2) },
        )
        modeAndScopeRow.addView(
            View(this).apply { setBackgroundColor(uiPalette.divider) },
            LinearLayout.LayoutParams(dp(1), dp(24)).apply {
                marginStart = dp(7)
                marginEnd = dp(7)
            },
        )

        val scopeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeAndScopeRow.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    scopeRow,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        container.addView(
            modeAndScopeRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            },
        )

        val summary = TextView(this).apply {
            textSize = 12f
            setTextColor(uiPalette.textMuted)
            setPadding(dp(4), 0, 0, dp(6))
        }
        val bookmarkMoreButton = Button(this).apply {
            text = "Show more"
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(uiPalette.accent)
            background = actionBackground()
            elevation = 0f
            stateListAnimator = null
            visibility = View.GONE
            contentDescription = "Load more navigation results"
        }
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                bookmarkMoreButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)),
            )
        })
        var summaryBase = ""
        var currentScopeSummary = ""
        var bookmarkResultLimit = NAVIGATE_RESULT_PAGE_SIZE
        var bookmarkTotalMatches = 0

        fun updateSmartCaseIndicator(query: String) {
            val caseSensitive = searchMode == SearchMode.BOOKMARKS && FuzzyMatcher.isSmartCase(query)
            smartCaseIndicator.visibility = if (caseSensitive) View.VISIBLE else View.GONE
            summary.text = summaryBase + if (caseSensitive) " · Aa case-sensitive" else ""
        }

        val list = ListView(this).apply {
            divider = ColorDrawable(uiPalette.divider)
            dividerHeight = dp(1)
            cacheColorHint = Color.TRANSPARENT
        }
        val results = mutableListOf<BookmarkEntry>()
        val adapter = BookmarkResultAdapter(
            items = results,
            onOpen = { entry ->
                recordBookmarkVisit(entry)
                dialog.dismiss()
                openBookmark(entry, newTabRequested = false)
            },
            onNewTab = { entry ->
                recordBookmarkVisit(entry)
                dialog.dismiss()
                openBookmark(entry, newTabRequested = true)
            },
            onAlongside = { entry ->
                recordBookmarkVisit(entry)
                dialog.dismiss()
                openBookmarkAlongside(entry)
            },
        )
        list.adapter = adapter
        container.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(560)))

        val textAction = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(18), dp(8), dp(26))
        }
        val textExplanation = TextView(this).apply {
            text = "Search results stay in the reference pane. Full-text search ignores case; plain words are combined and the final word also matches prefixes."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(uiPalette.textSecondary)
            setPadding(dp(8), 0, dp(8), dp(16))
        }
        textAction.addView(textExplanation)
        val runTextSearch = Button(this).apply {
            text = "Search selected documents"
            setOnClickListener {
                val query = input.text.toString().trim()
                if (query.length < 2 || TextSearchIndexRepository.matchExpression(query) == null) return@setOnClickListener
                dialog.dismiss()
                showTextSearchReference(query)
            }
        }
        textAction.addView(
            runTextSearch,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)),
        )
        container.addView(textAction)

        fun updateBookmarkResults(query: String, resetLimit: Boolean = false) {
            if (resetLimit) bookmarkResultLimit = NAVIGATE_RESULT_PAGE_SIZE
            val snapshot = bookmarkIndex.searchSnapshot(
                query,
                limit = bookmarkResultLimit,
                visitCounts = bookmarkVisitCounts,
                bookIds = searchScopeBookIds(),
            )
            results.clear()
            results.addAll(snapshot.entries)
            bookmarkTotalMatches = snapshot.totalMatches
            adapter.notifyDataSetChanged()
            val matchLabel = if (bookmarkTotalMatches > results.size) {
                "${results.size} of $bookmarkTotalMatches matches"
            } else {
                "$bookmarkTotalMatches match${if (bookmarkTotalMatches == 1) "" else "es"}"
            }
            summaryBase = "Destinations · $matchLabel · $currentScopeSummary"
            bookmarkMoreButton.visibility = if (results.size < bookmarkTotalMatches) {
                View.VISIBLE
            } else {
                View.GONE
            }
            updateSmartCaseIndicator(query)
        }

        bookmarkMoreButton.setOnClickListener {
            if (results.size >= bookmarkTotalMatches) return@setOnClickListener
            bookmarkResultLimit = min(
                bookmarkResultLimit + NAVIGATE_RESULT_PAGE_SIZE,
                bookmarkTotalMatches,
            )
            updateBookmarkResults(input.text.toString())
        }

        fun updateTextButton(query: String) {
            val enabled = query.trim().length >= 2 &&
                TextSearchIndexRepository.matchExpression(query) != null &&
                booksForTextSearchScope(searchScopeBookId).isNotEmpty()
            runTextSearch.isEnabled = enabled
            runTextSearch.alpha = if (enabled) 1f else 0.45f
        }

        fun applyMode(mode: SearchMode) {
            searchMode = mode
            val scopedBooks = if (mode == SearchMode.TEXT) {
                booksForTextSearchScope(searchScopeBookId)
            } else {
                booksForSearchScope(searchScopeBookId)
            }
            currentScopeSummary = searchScopeBookId?.let { bookById(it)?.title } ?: run {
                "${scopedBooks.size} ${if (mode == SearchMode.TEXT) "document" else "item"}" +
                    if (scopedBooks.size == 1) "" else "s"
            }
            val query = input.text.toString()
            input.hint = if (mode == SearchMode.BOOKMARKS) {
                "Search bookmarks and filenames"
            } else {
                "Search document text"
            }
            bookmarkMode.background = if (mode == SearchMode.BOOKMARKS) {
                chromeButtonBackground(uiPalette.accent)
            } else {
                chromeOutlinedButtonBackground(uiPalette.surfaceRaised, uiPalette.accent)
            }
            textMode.background = if (mode == SearchMode.TEXT) {
                chromeButtonBackground(uiPalette.accent)
            } else {
                chromeOutlinedButtonBackground(uiPalette.surfaceRaised, uiPalette.accent)
            }
            bookmarkMode.setTextColor(
                if (mode == SearchMode.BOOKMARKS) uiPalette.onAccent else uiPalette.textPrimary,
            )
            textMode.setTextColor(
                if (mode == SearchMode.TEXT) uiPalette.onAccent else uiPalette.textPrimary,
            )
            bookmarkMode.isSelected = mode == SearchMode.BOOKMARKS
            textMode.isSelected = mode == SearchMode.TEXT
            bookmarkMode.typeface = if (mode == SearchMode.BOOKMARKS) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textMode.typeface = if (mode == SearchMode.TEXT) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            list.visibility = if (mode == SearchMode.BOOKMARKS) View.VISIBLE else View.GONE
            textAction.visibility = if (mode == SearchMode.TEXT) View.VISIBLE else View.GONE
            if (mode == SearchMode.TEXT) {
                bookmarkMoreButton.visibility = View.GONE
                summaryBase = if (scopedBooks.isEmpty()) {
                    "Full text · selected images have no searchable text"
                } else {
                    "Full text · $currentScopeSummary"
                }
            }
            updateSmartCaseIndicator(query)
            runTextSearch.text = if (searchScopeBookId == null) "Search all text" else "Search this document"
            if (mode == SearchMode.BOOKMARKS) updateBookmarkResults(query) else updateTextButton(query)
        }

        lateinit var renderScopeRow: () -> Unit
        renderScopeRow = {
            populateSearchScopeRow(scopeRow, searchScopeBookId) { bookId ->
                changeSearchScope(bookId)
                bookmarkResultLimit = NAVIGATE_RESULT_PAGE_SIZE
                renderScopeRow()
                applyMode(searchMode)
            }
        }

        bookmarkMode.setOnClickListener { applyMode(SearchMode.BOOKMARKS) }
        textMode.setOnClickListener { applyMode(SearchMode.TEXT) }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                if (searchMode == SearchMode.BOOKMARKS) {
                    updateBookmarkResults(query, resetLimit = true)
                } else {
                    updateTextButton(query)
                }
                updateSmartCaseIndicator(query)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            if (searchMode == SearchMode.TEXT && runTextSearch.isEnabled) {
                runTextSearch.performClick()
            } else if (searchMode == SearchMode.BOOKMARKS && results.isNotEmpty()) {
                adapter.getItem(0).let { entry ->
                    recordBookmarkVisit(entry)
                    dialog.dismiss()
                    openBookmark(entry, newTabRequested = false)
                }
            }
            true
        }

        renderScopeRow()
        applyMode(searchMode)

        dialog.setContentView(container)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.25f)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setWindowAnimations(R.style.NaigreNoWindowAnimation)
            window.attributes = window.attributes.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP
                windowAnimations = R.style.NaigreNoWindowAnimation
            }
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.setOnShowListener {
            input.requestFocus()
            input.post {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
    }

    private inner class BookmarkResultAdapter(
        private val items: List<BookmarkEntry>,
        private val onOpen: (BookmarkEntry) -> Unit,
        private val onNewTab: (BookmarkEntry) -> Unit,
        private val onAlongside: (BookmarkEntry) -> Unit,
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): BookmarkEntry = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val item = getItem(position)

            val row: LinearLayout
            val holder: BookmarkRowHolder
            if (convertView is LinearLayout && convertView.tag is BookmarkRowHolder) {
                row = convertView
                holder = convertView.tag as BookmarkRowHolder
            } else {
                row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(4), 0, dp(4))
                    isClickable = true
                }

                val colorDot = View(this@MainActivity)
                row.addView(
                    colorDot,
                    LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                        marginStart = dp(5)
                        marginEnd = dp(3)
                    },
                )

                val textColumn = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                }
                val title = TextView(this@MainActivity).apply {
                    textSize = 17f
                    maxLines = 2
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(uiPalette.textPrimary)
                }
                val detail = TextView(this@MainActivity).apply {
                    textSize = 12f
                    maxLines = 2
                    setTextColor(uiPalette.textSecondary)
                }
                textColumn.addView(title)
                textColumn.addView(detail)
                row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val plus = Button(this@MainActivity).apply {
                    text = "+"
                    textSize = 22f
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(0, 0, 0, 0)
                    setTextColor(uiPalette.accent)
                    background = actionBackground()
                    elevation = 0f
                    stateListAnimator = null
                }
                row.addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)))
                val alongside = Button(this@MainActivity).apply {
                    text = "▥"
                    textSize = 20f
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(0, 0, 0, 0)
                    setTextColor(uiPalette.accent)
                    background = actionBackground()
                    elevation = 0f
                    stateListAnimator = null
                }
                row.addView(alongside, LinearLayout.LayoutParams(dp(48), dp(48)))
                holder = BookmarkRowHolder(colorDot, title, detail, plus, alongside)
                row.tag = holder
            }

            val book = bookById(item.bookId)
            row.setBackgroundColor(
                if ((position + 1) % 2 == 0) uiPalette.surfaceRaised else uiPalette.surface,
            )
            holder.colorDot.background = circleBackground(book?.color ?: uiPalette.textMuted)
            holder.title.text = item.title
            holder.detail.text = when {
                book == null -> "Missing library item"
                item.source == BookmarkSource.FILE_ROOT && book.kind == LibraryItemKind.IMAGE_COLLECTION ->
                    "Image album · ${book.pageCount} image${if (book.pageCount == 1) "" else "s"}"
                item.source == BookmarkSource.FILE_ROOT ->
                    when (book.kind) {
                        LibraryItemKind.MARKDOWN -> "Markdown note · no headings"
                        else -> "PDF · ${book.pageCount} pages · no bookmarks"
                    }
                book.kind == LibraryItemKind.IMAGE_COLLECTION ->
                    "${book.title} · image ${item.pageNumber}/${book.pageCount}"
                book.kind == LibraryItemKind.MARKDOWN -> {
                    val path = if (item.path != item.title) "${item.path} · " else ""
                    "${book.title} · ${path}heading"
                }
                else -> {
                    val path = if (item.path != item.title) "${item.path} · " else ""
                    "${book.title} · ${path}p${item.pageNumber}"
                }
            }
            holder.plus.contentDescription = "Open ${item.title} in a new tab"
            holder.alongside.contentDescription = "Open ${item.title} alongside"
            row.setOnClickListener { onOpen(item) }
            holder.plus.setOnClickListener { onNewTab(item) }
            holder.alongside.setOnClickListener { onAlongside(item) }
            return row
        }
    }

    private fun showTextSearchReference(query: String) {
        val normalized = query.trim()
        if (normalized.length < 2 || TextSearchIndexRepository.matchExpression(normalized) == null) return
        normalizeSearchScope()
        if (booksForTextSearchScope(searchScopeBookId).isEmpty()) {
            Toast.makeText(this, "Selected images have no searchable text", Toast.LENGTH_SHORT).show()
            return
        }

        val enteringSplit = !hasReferencePane()
        if (enteringSplit) {
            stageViewportRestoresForLayout()
            ++renderGeneration
            primaryPendingPageKeys = emptySet()
        } else {
            capturePrimaryImageViewport()
            captureReferenceImageViewport()
        }
        val primaryWasShowingTwoPages = enteringSplit &&
            visibleSecondPage(
                visibleFirstPage(currentPage, activeBook()?.pageCount ?: 1),
                activeBook()?.pageCount ?: 1,
            ) != null

        searchMode = SearchMode.TEXT
        referenceLocation = null
        ++referenceRenderGeneration
        ++prefetchGeneration
        referenceSurface.clearPages()
        referenceDisplayedPageKeys = emptySet()
        referencePendingPageKeys = emptySet()
        val session = TextSearchSession(
            query = normalized,
            scopeBookId = searchScopeBookId,
            bookIds = booksForTextSearchScope(searchScopeBookId).map { it.id },
            totalPages = booksForTextSearchScope(searchScopeBookId).sumOf { it.pageCount },
        )
        textSearchSession = session
        textSearchResultAdapter.notifyDataSetChanged()
        referenceSearchList.setSelection(0)
        updateCachePins()
        updateReferenceUi()
        root.requestApplyInsets()
        submitPrefetchWork {
            if (textSearchSession === session && referenceLocation == null) {
                prefetchPdf?.close()
                prefetchPdf = null
                prefetchBookId = null
            }
        }

        if (enteringSplit) {
            if (primaryWasShowingTwoPages) readerSurface.resetTransform()
            afterReaderLayout { renderCurrent() }
        }
        scheduleSelectedBookTextIndexes()
        refreshTextSearchResults()
    }

    private fun refreshTextSearchResults() {
        val session = textSearchSession ?: return
        val selected = booksForTextSearchScope(session.scopeBookId)
        val nextBookIds = selected.map { it.id }
        if (session.bookIds != nextBookIds) {
            session.results.clear()
            session.resultLimit = TextSearchIndexRepository.RESULT_PAGE_SIZE
            session.totalMatches = 0
            session.indexedPages = 0
            session.readyBooks = 0
            session.preciseContexts.clear()
            textSearchResultAdapter.notifyDataSetChanged()
            if (::referenceSearchList.isInitialized) referenceSearchList.setSelection(0)
        }
        session.bookIds = nextBookIds
        session.totalPages = selected.sumOf { it.pageCount }
        session.error = null
        val bookIds = session.bookIds.toList()
        if (session.results.removeAll { it.bookId !in bookIds }) {
            textSearchResultAdapter.notifyDataSetChanged()
        }
        val pageCounts = selected.associate { it.id to it.pageCount }
        val sourceRevisions = selected.associate { it.id to it.sourceRevisionKey() }
        val generation = ++textSearchQueryGeneration
        session.searchInFlight = true
        updateTextSearchStatus()

        submitTextSearchWork {
            try {
                val startedAt = SystemClock.elapsedRealtime()
                val snapshot = textSearchIndex.search(
                    session.query,
                    bookIds,
                    pageCounts,
                    sourceRevisions,
                    resultLimit = session.resultLimit,
                )
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                mainHandler.post {
                    if (
                        isDestroyed || generation != textSearchQueryGeneration ||
                        textSearchSession !== session
                    ) return@post
                    applyTextSearchSnapshot(session, snapshot, elapsedMs)
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    if (
                        isDestroyed || generation != textSearchQueryGeneration ||
                        textSearchSession !== session
                    ) return@post
                    session.searchInFlight = false
                    session.error = t.message ?: t.javaClass.simpleName
                    lastTextSearchDiagnostic = "Failed: ${session.error}"
                    updateTextSearchStatus()
                }
            }
        }
    }

    private fun applyTextSearchSnapshot(
        session: TextSearchSession,
        snapshot: TextSearchSnapshot,
        elapsedMs: Long,
    ) {
        session.results.clear()
        session.results.addAll(snapshot.hits)
        session.totalMatches = snapshot.totalMatches
        session.indexedPages = snapshot.indexedPages
        session.totalPages = snapshot.totalPages
        session.readyBooks = snapshot.readyBooks
        session.searchInFlight = false
        lastTextSearchDiagnostic = buildString {
            append("${elapsedMs} ms")
            append(" · ${snapshot.hits.size}/${snapshot.totalMatches} shown")
            append(" · ${snapshot.indexedPages}/${snapshot.totalPages} parts")
            append(" · ${session.bookIds.size} document")
            if (session.bookIds.size != 1) append("s")
        }
        textSearchResultAdapter.notifyDataSetChanged()
        updateTextSearchStatus()
    }

    private fun updateTextSearchStatus() {
        if (!::referenceSearchStatus.isInitialized) return
        val session = textSearchSession ?: return
        val failed = session.bookIds.count(textIndexErrors::containsKey)
        val documentLabel = "${session.bookIds.size} document" +
            if (session.bookIds.size == 1) "" else "s"
        val matchLabel = if (session.totalMatches > session.results.size) {
            "${session.results.size} of ${session.totalMatches} matches"
        } else {
            "${session.totalMatches} match${if (session.totalMatches == 1) "" else "es"}"
        }
        referenceSearchStatus.text = when {
            session.error != null -> "Search failed: ${session.error}"
            session.totalPages == 0 -> "Selected images have no searchable text"
            session.searchInFlight && session.results.isEmpty() -> "Searching $documentLabel…"
            session.searchInFlight -> "Updating… · $matchLabel"
            session.indexedPages < session.totalPages -> buildString {
                append("$matchLabel so far")
                append(" · indexing ${session.indexedPages}/${session.totalPages} parts")
                if (failed > 0) append(" · $failed book${if (failed == 1) "" else "s"} unavailable")
            }
            else -> "$matchLabel · ${session.readyBooks}/${session.bookIds.size} documents indexed"
        }
        referenceSearchMoreButton.visibility = if (
            !session.searchInFlight && session.error == null &&
            session.results.size < session.totalMatches
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        updateReferenceIndicator()
    }

    private inner class TextSearchResultAdapter : BaseAdapter() {
        override fun getCount(): Int = textSearchSession?.results?.size ?: 0
        override fun getItem(position: Int): TextSearchHit = checkNotNull(textSearchSession).results[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val item = getItem(position)

            val row: LinearLayout
            val holder: TextSearchRowHolder
            if (convertView is LinearLayout && convertView.tag is TextSearchRowHolder) {
                row = convertView
                holder = convertView.tag as TextSearchRowHolder
            } else {
                row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(5), 0, dp(5))
                    isClickable = true
                }
                val colorDot = View(this@MainActivity)
                row.addView(
                    colorDot,
                    LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                        marginStart = dp(4)
                        marginEnd = dp(4)
                    },
                )
                val textColumn = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(7), dp(5), dp(7), dp(5))
                }
                val title = TextView(this@MainActivity).apply {
                    textSize = 15f
                    maxLines = 2
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(uiPalette.textPrimary)
                }
                val detail = TextView(this@MainActivity).apply {
                    textSize = 12f
                    maxLines = 1
                    setTextColor(uiPalette.textSecondary)
                }
                val snippet = TextView(this@MainActivity).apply {
                    textSize = 14f
                    maxLines = 4
                    setTextColor(uiPalette.textPrimary)
                    setPadding(0, dp(4), 0, 0)
                }
                textColumn.addView(title)
                textColumn.addView(detail)
                textColumn.addView(snippet)
                row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val plus = Button(this@MainActivity).apply {
                    text = "+"
                    textSize = 22f
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(0, 0, 0, 0)
                    setTextColor(uiPalette.accent)
                    background = actionBackground()
                    elevation = 0f
                    stateListAnimator = null
                }
                row.addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)))
                holder = TextSearchRowHolder(colorDot, title, detail, snippet, plus)
                row.tag = holder
            }

            val book = bookById(item.bookId)
            val contextKey = "${item.bookId}|${item.pageIndex}"
            val preciseContextKnown = textSearchSession?.preciseContexts?.containsKey(contextKey) == true
            val context = if (preciseContextKnown) {
                textSearchSession?.preciseContexts?.get(contextKey)
            } else {
                bookmarkIndex.contextAtOrBefore(item.bookId, item.pageIndex)
            }
            val markdown = book?.kind == LibraryItemKind.MARKDOWN
            val parentPath = context?.path
                ?.substringBeforeLast(" › ", missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
            val bookAndContext = buildString {
                append(book?.title ?: if (markdown) "Missing note" else "Missing book")
                parentPath?.let {
                    append(" · ")
                    append(it)
                }
            }
            row.setBackgroundColor(
                if ((position + 1) % 2 == 0) uiPalette.surfaceRaised else uiPalette.surface,
            )
            holder.colorDot.background = circleBackground(book?.color ?: uiPalette.textMuted)
            holder.title.text = context?.title ?: if (markdown) "Start" else "Page ${item.pageIndex + 1}"
            holder.detail.text = if (markdown) {
                "$bookAndContext · note section"
            } else {
                "$bookAndContext · p${item.pageIndex + 1}" +
                    when {
                        context == null -> ""
                        preciseContextKnown -> " · section"
                        else -> " · near section"
                    }
            }
            holder.snippet.text = styledTextSnippet(item.snippet)
            holder.plus.contentDescription = "Open text result in a new tab"
            row.setOnClickListener { openTextSearchHit(item, newTabRequested = false) }
            holder.plus.setOnClickListener { openTextSearchHit(item, newTabRequested = true) }
            return row
        }
    }

    private fun styledTextSnippet(marked: String): CharSequence {
        val plain = StringBuilder(marked.length)
        val ranges = ArrayList<IntRange>()
        var rangeStart: Int? = null
        for (character in marked) {
            when (character) {
                TextSearchIndexRepository.MATCH_START -> rangeStart = plain.length
                TextSearchIndexRepository.MATCH_END -> rangeStart?.let { start ->
                    if (plain.length > start) ranges += start until plain.length
                    rangeStart = null
                }
                else -> plain.append(character)
            }
        }
        val styled = SpannableString(plain.toString())
        for (range in ranges) {
            styled.setSpan(
                StyleSpan(Typeface.BOLD),
                range.first,
                range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            styled.setSpan(
                ForegroundColorSpan(uiPalette.searchMatch),
                range.first,
                range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return styled
    }

    private fun openTextSearchHit(hit: TextSearchHit, newTabRequested: Boolean) {
        val session = textSearchSession ?: return
        val book = bookById(hit.bookId) ?: return
        capturePrimaryImageViewport()
        val pageIndex = hit.pageIndex.coerceIn(0, book.pageCount - 1)
        val context = bookmarkIndex.contextAtOrBefore(hit.bookId, pageIndex)
        val label = if (book.kind == LibraryItemKind.MARKDOWN) {
            context?.title ?: "Start"
        } else {
            context?.title?.let { "$it · p${pageIndex + 1}" } ?: "Page ${pageIndex + 1}"
        }
        val anchorKey = "${hit.bookId}|text-${if (book.kind == LibraryItemKind.MARKDOWN) "section" else "page"}:$pageIndex"
        activeSearchHighlight = SearchHighlightTarget(
            bookId = hit.bookId,
            pageIndex = pageIndex,
            terms = TextSearchIndexRepository.highlightTerms(session.query),
            caseSensitive = false,
            anchorKey = anchorKey,
        )
        ++searchHighlightGeneration

        val existing = tabs.indexOfFirst { it.anchorKey == anchorKey }
        if (existing >= 0) {
            tabs[existing].pageIndex = pageIndex
            tabs[existing].originPageIndex = pageIndex
            if (existing == activeTabIndex) {
                currentPage = pageIndex
                persistSession()
                activateCurrentTab()
            } else {
                switchToTab(existing)
            }
            return
        }

        if (newTabRequested || activeTabOrNull() == null) {
            tabs += ReaderTab(hit.bookId, pageIndex, label, anchorKey)
            activeTabIndex = tabs.lastIndex
        } else {
            primaryMarkdownViewports.remove(activeTab())
            activeTab().apply {
                bookId = hit.bookId
                this.pageIndex = pageIndex
                this.label = label
                this.anchorKey = anchorKey
                this.originPageIndex = pageIndex
            }
        }
        currentPage = pageIndex
        persistSession()
        activateCurrentTab()
    }

    private fun renderCurrent(keepCurrentVisualOnMiss: Boolean = false) {
        val document = pdf ?: return
        val bookId = pdfBookId ?: return
        if (activeTabOrNull()?.bookId != bookId) return
        if (document is MarkdownDocument) {
            renderCurrentMarkdown(document, bookId)
            return
        }
        primaryMarkdownSurface.visibility = View.GONE
        readerSurface.visibility = View.VISIBLE
        val generation = ++renderGeneration
        ++prefetchGeneration
        activeTab().pageIndex = currentPage
        persistSession()
        updatePageIndicator()
        setStatus("Preparing page ${currentPage + 1}…")

        readerSurface.post {
            if (isDestroyed || generation != renderGeneration) return@post
            val surfaceWidth = primarySurfaceWidth()
            val first = visibleFirstPage(currentPage, document.pageCount)
            val second = visibleSecondPage(first, document.pageCount)
            val targetWidth = if (second != null) max(180, surfaceWidth / 2) else surfaceWidth
            val firstKey = pageCacheKey(bookId, first, targetWidth)
            val secondKey = second?.let { pageCacheKey(bookId, it, targetWidth) }
            val visibleKeys = listOfNotNull(firstKey, secondKey).toSet()
            // The old visual unit remains on screen while a cache miss renders. Keep both
            // generations pinned so trimming can never recycle a Bitmap still being drawn.
            primaryPendingPageKeys = visibleKeys
            updateCachePins()

            val cachedLeft = pageCache.get(firstKey)
            val cachedRight = secondKey?.let { pageCache.get(it) }
            val cacheComplete = cachedLeft != null && (secondKey == null || cachedRight != null)

            if (cacheComplete) {
                // A spread is one visual unit: swap both pages at once. Never expose a
                // half-rendered spread just because one side happened to be cached first.
                setPrimaryPages(bookId, first, cachedLeft, cachedRight)
                primaryDisplayedPageKeys = visibleKeys
                primaryPendingPageKeys = emptySet()
                updateCachePins()
                showRenderedStatus(document, first, second, fromCache = true)
                schedulePrimarySearchHighlights(document, bookId, first, second, generation)
                schedulePrefetch(bookId, document.pageCount, surfaceWidth, first)
                return@post
            }

            if (!effectiveSpreadMode() && secondKey == null) {
                // True single-page mode can show an immediately available cached page.
                if (cachedLeft != null) {
                    setPrimaryPages(bookId, first, cachedLeft, null)
                    primaryDisplayedPageKeys = setOf(firstKey)
                } else if (!keepCurrentVisualOnMiss) {
                    clearPrimaryDisplayedPages()
                }
                updateCachePins()
            }
            // In spread mode, keep the previous complete visual unit visible until the
            // new unit is ready. This also covers the standalone cover/last page.

            setStatus("Rendering page ${first + 1}${second?.let { "–${it + 1}" } ?: ""}…")
            submitRenderWork {
                try {
                    if (generation != renderGeneration || document !== pdf || pdfBookId != bookId || destroying) return@submitRenderWork
                    val leftPage = cachedLeft ?: renderAndCache(document, bookId, firstKey)
                    if (generation != renderGeneration || document !== pdf || pdfBookId != bookId || destroying) return@submitRenderWork
                    val rightPage = if (secondKey != null) cachedRight ?: renderAndCache(document, bookId, secondKey) else null

                    if (generation != renderGeneration || document !== pdf || pdfBookId != bookId || destroying) return@submitRenderWork
                    mainHandler.post {
                        if (isDestroyed || generation != renderGeneration || document !== pdf || pdfBookId != bookId) return@post
                        updateCachePins()
                        setPrimaryPages(bookId, first, leftPage, rightPage)
                        primaryDisplayedPageKeys = visibleKeys
                        primaryPendingPageKeys = emptySet()
                        updateCachePins()
                        showRenderedStatus(document, first, second, fromCache = false)
                        schedulePrimarySearchHighlights(document, bookId, first, second, generation)
                        schedulePrefetch(bookId, document.pageCount, surfaceWidth, first)
                    }
                } catch (t: Throwable) {
                    showError("Render failed", t)
                }
            }
        }
    }

    private fun renderCurrentMarkdown(document: MarkdownDocument, bookId: String) {
        ++renderGeneration
        ++prefetchGeneration
        val tab = activeTab()
        currentPage = currentPage.coerceIn(0, document.pageCount - 1)
        tab.pageIndex = currentPage
        val highlight = activeSearchHighlight?.takeIf {
            it.bookId == bookId && it.pageIndex == currentPage && it.anchorKey == tab.anchorKey
        }
        val restored = primaryMarkdownViewports[tab]
            ?.takeIf { it.sectionIndex == currentPage && highlight == null }
        readerSurface.clearPages()
        readerSurface.visibility = View.GONE
        primaryMarkdownSurface.visibility = View.VISIBLE
        primaryDisplayedPageKeys = emptySet()
        primaryPendingPageKeys = emptySet()
        updateCachePins()
        primaryMarkdownSurface.showDocument(
            engine = markdownEngine,
            nextDocument = document,
            sectionIndex = currentPage,
            restoredViewport = restored,
            highlightTerms = highlight?.terms.orEmpty(),
            caseSensitive = highlight?.caseSensitive ?: false,
            highlightColor = colorWithAlpha(uiPalette.searchHighlight, 0xcc),
            highlightTextColor = uiPalette.textPrimary,
        )
        persistSession()
        val section = document.content.sections[currentPage]
        setStatus("${activeBook()?.title ?: "Note"} · ${section.title}")
        updatePageIndicator()
        updateEmptyState()
    }

    private fun renderReference() {
        val reference = referenceLocation ?: return
        val book = bookById(reference.bookId) ?: return
        val generation = ++referenceRenderGeneration
        updatePageIndicator()

        if (book.kind == LibraryItemKind.MARKDOWN) {
            referenceSurface.visibility = View.GONE
            referenceMarkdownSurface.visibility = View.VISIBLE
            referencePendingPageKeys = emptySet()
            referenceDisplayedPageKeys = emptySet()
            updateCachePins()
            setStatus("Opening note reference…")
            submitPrefetchWork {
                try {
                    if (generation != referenceRenderGeneration || reference !== referenceLocation || destroying) {
                        return@submitPrefetchWork
                    }
                    val document = secondaryDocument(book) as MarkdownDocument
                    mainHandler.post {
                        if (
                            isDestroyed || generation != referenceRenderGeneration ||
                            reference !== referenceLocation
                        ) return@post
                        referenceMarkdownSurface.showDocument(
                            engine = markdownEngine,
                            nextDocument = document,
                            sectionIndex = reference.pageIndex,
                            restoredViewport = referenceMarkdownViewport
                                ?.takeIf { it.sectionIndex == reference.pageIndex },
                            highlightColor = colorWithAlpha(uiPalette.searchHighlight, 0xcc),
                            highlightTextColor = uiPalette.textPrimary,
                        )
                        updatePageIndicator()
                        setStatus("${book.title} · ${reference.label}")
                    }
                } catch (t: Throwable) {
                    if (generation == referenceRenderGeneration && reference === referenceLocation) {
                        showError("Reference note failed", t)
                    }
                }
            }
            return
        }
        referenceMarkdownSurface.visibility = View.GONE
        referenceSurface.visibility = View.VISIBLE

        referenceSurface.post {
            if (
                isDestroyed ||
                generation != referenceRenderGeneration ||
                reference !== referenceLocation
            ) return@post

            val key = pageCacheKey(reference.bookId, reference.pageIndex, referenceSurfaceWidth())
            val requestedKeys = setOf(key)
            referencePendingPageKeys = requestedKeys
            updateCachePins()

            pageCache.get(key)?.let { cached ->
                setReferencePage(reference.bookId, reference.pageIndex, cached)
                referenceDisplayedPageKeys = requestedKeys
                referencePendingPageKeys = emptySet()
                updateCachePins()
                updatePageIndicator()
                return@post
            }

            setStatus("Rendering reference page ${reference.pageIndex + 1}…")
            submitPrefetchWork {
                try {
                    if (
                        generation != referenceRenderGeneration ||
                        reference !== referenceLocation ||
                        destroying
                    ) return@submitPrefetchWork

                    val document = secondaryDocument(book)
                    if (
                        generation != referenceRenderGeneration ||
                        reference !== referenceLocation ||
                        document !== prefetchPdf ||
                        prefetchBookId != reference.bookId ||
                        destroying
                    ) return@submitPrefetchWork
                    val rendered = document.renderPage(key.pageIndex, key.targetWidthPx)
                    if (
                        generation != referenceRenderGeneration ||
                        reference !== referenceLocation ||
                        document !== prefetchPdf ||
                        prefetchBookId != reference.bookId ||
                        destroying
                    ) {
                        rendered.bitmap.recycle()
                        return@submitPrefetchWork
                    }
                    val page = pageCache.put(key, rendered)

                    mainHandler.post {
                        if (
                            isDestroyed ||
                            generation != referenceRenderGeneration ||
                            reference !== referenceLocation ||
                            document !== prefetchPdf ||
                            prefetchBookId != reference.bookId
                        ) return@post
                        updateCachePins()
                        setReferencePage(reference.bookId, reference.pageIndex, page)
                        referenceDisplayedPageKeys = requestedKeys
                        referencePendingPageKeys = emptySet()
                        updateCachePins()
                        updatePageIndicator()
                    }
                } catch (t: Throwable) {
                    val stillCurrent = generation == referenceRenderGeneration &&
                        reference === referenceLocation && !destroying
                    if (stillCurrent) mainHandler.post {
                        if (
                            generation == referenceRenderGeneration &&
                            reference === referenceLocation
                        ) {
                            referencePendingPageKeys = emptySet()
                            updateCachePins()
                            showError("Reference render failed", t)
                        }
                    }
                }
            }
        }
    }

    private fun schedulePrimarySearchHighlights(
        document: PdfDocument,
        bookId: String,
        firstPage: Int,
        secondPage: Int?,
        renderRequest: Int,
    ) {
        if (document is MarkdownDocument) return
        val target = activeSearchHighlight
        if (
            target == null || target.bookId != bookId ||
            activeTabOrNull()?.anchorKey != target.anchorKey ||
            target.pageIndex != firstPage && target.pageIndex != secondPage ||
            target.terms.isEmpty()
        ) {
            readerSurface.clearSearchHighlights()
            return
        }

        val highlightRequest = searchHighlightGeneration
        readerSurface.clearSearchHighlights()
        submitRenderWork {
            try {
                if (
                    destroying || document !== pdf || pdfBookId != bookId ||
                    renderRequest != renderGeneration || highlightRequest != searchHighlightGeneration ||
                    activeSearchHighlight != target
                ) return@submitRenderWork
                val bounds = document.searchText(
                    target.pageIndex,
                    target.terms,
                    caseSensitive = target.caseSensitive,
                )
                mainHandler.post {
                    if (
                        isDestroyed || document !== pdf || pdfBookId != bookId ||
                        renderRequest != renderGeneration || highlightRequest != searchHighlightGeneration ||
                        activeSearchHighlight != target
                    ) return@post
                    val preciseContext = bounds.firstOrNull()?.let { firstBounds ->
                        bookmarkIndex.matchDestination(
                            bookId = target.bookId,
                            pageIndex = target.pageIndex,
                            targetY = firstBounds.top,
                            destinationKey = null,
                        )?.entry
                    }
                    if (preciseContext != null) {
                        textSearchSession?.takeIf {
                            TextSearchIndexRepository.highlightTerms(it.query) == target.terms
                        }?.let { session ->
                            session.preciseContexts["${target.bookId}|${target.pageIndex}"] = preciseContext
                            textSearchResultAdapter.notifyDataSetChanged()
                        }
                        val anchorKey = "${target.bookId}|text-page:${target.pageIndex}"
                        tabs.firstOrNull { it.anchorKey == anchorKey }?.let { tab ->
                            tab.label = "${preciseContext.title} · p${target.pageIndex + 1}"
                            renderTabBar()
                            persistSession()
                        }
                    }
                    readerSurface.showSearchHighlights(target.pageIndex, bounds, focusFirst = true)
                }
            } catch (_: Throwable) {
                // Navigation remains useful when a malformed text layer cannot be highlighted.
            }
        }
    }

    private fun clearActiveSearchHighlight() {
        activeSearchHighlight = null
        ++searchHighlightGeneration
        if (::readerSurface.isInitialized) readerSurface.clearSearchHighlights()
    }

    private fun primarySurfaceWidth(): Int {
        val fullWidth = readerContainer.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        return if (hasReferencePane() && resolvedReaderLayout() == ReaderLayoutMode.WIDE) {
            max(180, fullWidth / 2)
        } else {
            max(320, fullWidth)
        }
    }

    private fun referenceSurfaceWidth(): Int {
        val fullWidth = readerContainer.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        return if (resolvedReaderLayout() == ReaderLayoutMode.WIDE) {
            max(180, fullWidth / 2)
        } else {
            max(320, fullWidth)
        }
    }

    private fun pageCacheKey(bookId: String, pageIndex: Int, targetWidthPx: Int): PageCacheKey =
        bookById(bookId).let { book ->
            PageCacheKey(
                bookId = bookId,
                sourceRevision = book?.sourceRevisionKey().orEmpty(),
                pageIndex = pageIndex,
                targetWidthPx = if (book?.kind == LibraryItemKind.IMAGE_COLLECTION) {
                IMAGE_CACHE_WIDTH_SENTINEL
                } else {
                    targetWidthPx
                },
            )
        }

    private fun secondaryDocument(book: BookRecord): PdfDocument {
        if (prefetchBookId == book.id) prefetchPdf?.let { return it }
        prefetchPdf?.close()
        prefetchPdf = null
        prefetchBookId = null
        return openDocument(book).also {
            prefetchPdf = it
            prefetchBookId = book.id
        }
    }

    private fun discardSecondaryDocument(bookId: String) {
        if (prefetchBookId != bookId) return
        submitPrefetchWork {
            if (prefetchBookId == bookId) {
                prefetchPdf?.close()
                prefetchPdf = null
                prefetchBookId = null
            }
        }
    }

    private fun renderAndCache(document: PdfDocument, bookId: String, key: PageCacheKey): RenderedPdfPage {
        pageCache.get(key)?.let { return it }
        val rendered = document.renderPage(key.pageIndex, key.targetWidthPx)
        if (document !== pdf || pdfBookId != bookId || destroying) {
            rendered.bitmap.recycle()
            error("Document changed during render")
        }
        return pageCache.put(key, rendered)
    }

    private fun schedulePrefetch(bookId: String, pageCount: Int, surfaceWidth: Int, first: Int) {
        // The second MuPDF document/executor is promoted from speculative work to the
        // user-requested reference pane while split view is active.
        if (hasReferencePane()) return
        val book = bookById(bookId) ?: return
        val generation = ++prefetchGeneration

        val keys = if (spreadMode) {
            buildList {
                listOf(-1, +1).forEach { direction ->
                    val neighborFirst = adjacentSpreadFirst(first, direction, pageCount) ?: return@forEach
                    val neighborSecond = visibleSecondPage(neighborFirst, pageCount)
                    val width = if (neighborSecond != null) max(180, surfaceWidth / 2) else surfaceWidth
                    add(pageCacheKey(bookId, neighborFirst, width))
                    if (neighborSecond != null) add(pageCacheKey(bookId, neighborSecond, width))
                }
            }
        } else {
            listOf(first - 1, first + 1)
                .filter { it in 0 until pageCount }
                .map { pageCacheKey(bookId, it, surfaceWidth) }
        }.distinct()

        submitPrefetchWork {
            if (generation != prefetchGeneration || destroying) return@submitPrefetchWork
            val document = try {
                secondaryDocument(book)
            } catch (_: Throwable) {
                return@submitPrefetchWork
            }
            for (key in keys) {
                if (
                    generation != prefetchGeneration || document !== prefetchPdf ||
                    prefetchBookId != bookId || destroying
                ) return@submitPrefetchWork
                if (pageCache.get(key) != null) continue
                try {
                    val rendered = document.renderPage(key.pageIndex, key.targetWidthPx)
                    if (
                        generation != prefetchGeneration || document !== prefetchPdf ||
                        prefetchBookId != bookId || destroying
                    ) {
                        rendered.bitmap.recycle()
                        return@submitPrefetchWork
                    }
                    pageCache.put(key, rendered)
                } catch (_: Throwable) {
                    // Prefetch is opportunistic; foreground rendering remains authoritative.
                }
            }
        }
    }

    private fun showRenderedStatus(document: PdfDocument, first: Int, second: Int?, fromCache: Boolean) {
        val visible = if (second != null) "${first + 1}–${second + 1}" else "${first + 1}"
        val cacheMb = pageCache.bytes() / (1024.0 * 1024.0)
        val source = if (fromCache) "cached" else "rendered"
        val unit = if (activeBook()?.kind == LibraryItemKind.IMAGE_COLLECTION) "Image" else "Page"
        val decodedSize = if (unit == "Image") {
            primaryDisplayedPageKeys.firstOrNull { it.pageIndex == first }
                ?.let(pageCache::info)
                ?.let { " · ${it.width}×${it.height}" }
                .orEmpty()
        } else {
            ""
        }
        setStatus(
            "$unit $visible / ${document.pageCount}$decodedSize · " +
                "${bookmarkIndex.size} destinations · $source · " +
                "cache ${pageCache.size()} pages / %.0f MB".format(cacheMb),
        )
        updatePageIndicator()
    }

    private fun updatePageIndicator() {
        if (!::pageIndicator.isInitialized) return
        val document = pdf
        if (
            document == null || pdfBookId != activeTabOrNull()?.bookId ||
            activeBook()?.kind == LibraryItemKind.MARKDOWN
        ) {
            pageIndicator.text = ""
            pageIndicator.visibility = View.INVISIBLE
        } else {
            val first = visibleFirstPage(currentPage, document.pageCount)
            val second = visibleSecondPage(first, document.pageCount)
            val active = activeBook()
            pageIndicator.text = if (active?.kind == LibraryItemKind.IMAGE_COLLECTION) {
                val imageName = active.imageFiles.getOrNull(first)?.fileName.orEmpty()
                "${first + 1} / ${document.pageCount}" +
                    imageName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            } else if (second != null) {
                "${first + 1}–${second + 1} / ${document.pageCount}"
            } else {
                "${first + 1} / ${document.pageCount}"
            }
            pageIndicator.visibility = if (chromeVisible) View.VISIBLE else View.INVISIBLE
        }
        updateReferenceIndicator()
        updateEmptyState()
    }

    private fun invalidateRendering(clearCache: Boolean) {
        ++renderGeneration
        ++prefetchGeneration
        ++referenceRenderGeneration
        primaryPendingPageKeys = emptySet()
        referencePendingPageKeys = emptySet()
        if (::readerSurface.isInitialized) clearAllDisplayedPages()
        if (::pageCache.isInitialized && clearCache) {
            pageCache.pin(emptyList())
            pageCache.clear()
        }
    }

    private fun clearPrimaryDisplayedPages() {
        readerSurface.clearPages()
        if (::primaryMarkdownSurface.isInitialized) primaryMarkdownSurface.clear()
        primaryDisplayedPageKeys = emptySet()
    }

    private fun clearAllDisplayedPages() {
        clearPrimaryDisplayedPages()
        referenceSurface.clearPages()
        if (::referenceMarkdownSurface.isInitialized) referenceMarkdownSurface.clear()
        referenceDisplayedPageKeys = emptySet()
        primaryPendingPageKeys = emptySet()
        referencePendingPageKeys = emptySet()
    }

    private fun updateCachePins() {
        pageCache.pin(
            primaryDisplayedPageKeys +
                primaryPendingPageKeys +
                referenceDisplayedPageKeys +
                referencePendingPageKeys,
        )
    }

    private fun inspectAnnotations() {
        val document = pdf ?: return
        val bookId = pdfBookId ?: return
        if (bookId != activeTabOrNull()?.bookId) return
        val first = visibleFirstPage(currentPage, document.pageCount)
        val second = visibleSecondPage(first, document.pageCount)
        val pages = buildList {
            add(first)
            if (second != null) add(second)
        }
        setStatus("Reading annotations…")

        submitRenderWork {
            try {
                if (document !== pdf || pdfBookId != bookId) return@submitRenderWork
                val annotations = pages.flatMap { document.annotations(it) }
                mainHandler.post {
                    if (isDestroyed || document !== pdf || pdfBookId != bookId) return@post
                    showAnnotationDiagnostics(annotations)
                    val visible = pages.joinToString(" & ") { (it + 1).toString() }
                    setStatus("${annotations.size} annotation(s) on page(s) $visible")
                }
            } catch (t: Throwable) {
                showError("Annotation inspection failed", t)
            }
        }
    }

    private fun showSingleAnnotation(item: PdfAnnotationInfo) {
        val text = item.contents?.trim().orEmpty()
        if (text.isEmpty()) return
        val dialog = AlertDialog.Builder(this)
            .setMessage(text)
            .setPositiveButton("Close", null)
            .create()
        if (immersive) {
            // Prevent a dialog from momentarily pulling the navigation bar back in.
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        dialog.show()
        dialog.window?.let { window ->
            val params = window.attributes
            params.windowAnimations = 0
            window.attributes = params
            if (immersive) {
                applyImmersiveToWindow(window, true)
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                window.decorView.post { applyImmersiveToWindow(window, true) }
            }
        }
        dialog.setOnDismissListener {
            if (immersive) applyImmersiveMode(true)
        }
    }

    private fun showAnnotationDiagnostics(items: List<PdfAnnotationInfo>) {
        val message = if (items.isEmpty()) {
            "No PDF annotations were reported on the visible page(s)."
        } else {
            items.mapIndexed { index, item ->
                buildString {
                    append("#${index + 1} · page ${item.pageNumber} · ${item.type}")
                    item.author?.let { append("\nAuthor: $it") }
                    item.subject?.let { append("\nSubject: $it") }
                    item.contents?.let { append("\nContents: $it") }
                }
            }.joinToString("\n\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Annotation diagnostics")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        applyChromeVisibility()
    }

    private fun applyChromeVisibility() {
        if (!::bottomChrome.isInitialized) return
        val visibility = if (chromeVisible) View.VISIBLE else View.INVISIBLE
        bottomChrome.visibility = visibility
        menuButton.visibility = visibility
        pageIndicator.visibility =
            if (
                chromeVisible && pdf != null && pdfBookId == activeTabOrNull()?.bookId &&
                activeBook()?.kind != LibraryItemKind.MARKDOWN
            ) View.VISIBLE else View.INVISIBLE
        updateNavigationControls()
        updateReferenceIndicator()
        applyMarkdownInsets()
        root.requestApplyInsets()
        // Deliberately do not alter system bars or resize the bitmap reader surface.
    }

    private fun nextBookColor(): Int {
        val used = books.mapTo(HashSet()) { it.color }
        return BOOK_COLORS.firstOrNull { it !in used } ?: BOOK_COLORS[books.size % BOOK_COLORS.size]
    }

    private fun replaceBook(updated: BookRecord): Boolean {
        val index = books.indexOfFirst { it.id == updated.id }
        if (index < 0) return false
        books[index] = updated
        return true
    }

    private fun persistBooks() {
        try {
            bookRepository.save(books, selectedBookIds, libraryFolders, libraryTags)
        } catch (t: Throwable) {
            showError("Could not save the library", t)
        }
    }

    private fun selectBook(bookId: String, openAfter: Boolean = false) {
        val book = bookById(bookId) ?: return
        if (bookId in selectedBookIds && book.indexLoaded) {
            scheduleBookTextIndex(book)
            if (openAfter) openBookInTab(bookId)
            refreshLibraryUi()
            return
        }
        if (book.indexLoaded) {
            selectedBookIds += bookId
            persistBooks()
            rebuildBookmarkIndex()
            refreshLibraryUi()
            scheduleBookTextIndex(book)
            refreshTextSearchScope()
            if (openAfter) openBookInTab(bookId)
            return
        }

        setStatus("Loading ${book.title} index…")
        submitPrefetchWork {
            try {
                val hydrated = bookRepository.hydrate(book)
                mainHandler.post {
                    if (isDestroyed || bookById(bookId) == null) return@post
                    replaceBook(hydrated)
                    selectedBookIds += bookId
                    persistBooks()
                    rebuildBookmarkIndex()
                    refreshLibraryUi()
                    scheduleBookTextIndex(hydrated)
                    refreshTextSearchScope()
                    if (openAfter) openBookInTab(bookId)
                }
            } catch (t: Throwable) {
                showError("Could not load ${book.title}", t)
            }
        }
    }

    private fun deselectBook(bookId: String) {
        val book = bookById(bookId) ?: return
        if (bookId !in selectedBookIds) return
        val removedPrimary = pdfBookId == bookId
        val removedSecondary = prefetchBookId == bookId
        val previouslyActive = activeTabOrNull()
        if (referenceLocation?.bookId == bookId) closeReference(renderPrimary = false)

        selectedBookIds -= bookId
        textIndexTokens.remove(bookId)
        tabs.filter { it.bookId == bookId }.forEach(primaryImageViewports::remove)
        tabs.filter { it.bookId == bookId }.forEach(primaryMarkdownViewports::remove)
        tabs.removeAll { it.bookId == bookId }
        referenceImageViewports.keys.removeAll { it.startsWith("$bookId|") }
        if (tabs.isEmpty()) {
            selectedBooks().firstOrNull()?.let { tabs += ReaderTab(it.id, 0, "Start") }
        }
        activeTabIndex = previouslyActive
            ?.takeIf { it.bookId != bookId }
            ?.let(tabs::indexOf)
            ?.takeIf { it >= 0 }
            ?: if (tabs.isEmpty()) 0 else activeTabIndex.coerceIn(0, tabs.lastIndex)
        currentPage = activeTabOrNull()?.pageIndex ?: 0

        // Save the full index before releasing its in-memory representation.
        persistBooks()
        replaceBook(
            book.copy(
                pdfBookmarks = emptyList(),
                externalBookmarks = emptyList(),
                imageFiles = emptyList(),
                storedBookmarkCount = book.bookmarkCount,
                indexLoaded = false,
            ),
        )
        persistSession()
        rebuildBookmarkIndex()
        refreshTextSearchScope()
        if (removedPrimary) {
            invalidateRendering(clearCache = true)
        } else {
            ++prefetchGeneration
            pageCache.removeBook(bookId)
            updateCachePins()
        }
        refreshLibraryUi()

        if (removedSecondary) submitPrefetchWork {
            if (prefetchBookId == bookId) {
                prefetchPdf?.close()
                prefetchPdf = null
                prefetchBookId = null
            }
        }
        if (removedPrimary) {
            if (tabs.isNotEmpty()) {
                activateCurrentTab(forceReload = true)
            } else {
                ++primaryOpenGeneration
                primaryOpenTargetBookId = null
                submitRenderWork {
                    pdf?.close()
                    pdf = null
                    pdfBookId = null
                }
                renderTabBar()
                updatePageIndicator()
                updateEmptyState()
            }
        } else {
            renderTabBar()
            updatePageIndicator()
            updateEmptyState()
        }
    }

    private fun refreshLibraryUi() {
        normalizeLibraryFilter()
        libraryVisibleBooks = libraryBooksForCurrentFilter()
        libraryTagStrip?.let(::populateLibraryTagStrip)
        librarySelectionLabel?.text = librarySummaryLabel()
        libraryEmptyLabel?.text = libraryEmptyMessage()
        libraryAdapter?.notifyDataSetChanged()
        updateNavigationControls()
        updateEmptyState()
    }

    private fun normalizeLibraryFilter() {
        if (
            libraryFilter.kind == LibraryFilterKind.TAG &&
            libraryTags.none { it.id == libraryFilter.tagId }
        ) {
            libraryFilter = LibraryFilter(LibraryFilterKind.ALL)
        }
    }

    private fun libraryBooksForCurrentFilter(): List<BookRecord> = when (libraryFilter.kind) {
        LibraryFilterKind.ALL -> books.toList()
        LibraryFilterKind.ON_TABLE -> books.filter { it.id in selectedBookIds }
        LibraryFilterKind.TAG -> books.filter { libraryFilter.tagId in it.tagIds }
        LibraryFilterKind.UNTAGGED -> books.filter { it.tagIds.isEmpty() }
    }

    private fun libraryEmptyMessage(): String = when {
        books.isEmpty() ->
            "No campaign files are known yet.\nAdd a PDF, Markdown note, or folder to start your library."
        libraryFilter.kind == LibraryFilterKind.ON_TABLE -> "No library items are currently on the table."
        libraryFilter.kind == LibraryFilterKind.UNTAGGED -> "Every library item has at least one tag."
        libraryFilter.kind == LibraryFilterKind.TAG -> {
            val name = libraryTags.firstOrNull { it.id == libraryFilter.tagId }?.name ?: "this tag"
            "No library items have the tag “$name”."
        }
        else -> "No library items match this filter."
    }

    private fun librarySummaryLabel(): String = buildString {
        append("${libraryVisibleBooks.size} shown · ${selectedBookIds.size} on table · ${books.size} known")
        if (libraryFolders.isNotEmpty()) {
            append(" · ${libraryFolders.size} folder${if (libraryFolders.size == 1) "" else "s"}")
        }
        if (folderScanInProgress) append(" · scanning…")
    }

    private fun populateLibraryTagStrip(row: LinearLayout) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        row.removeAllViews()

        fun addFilter(label: String, filter: LibraryFilter, longPress: (() -> Unit)? = null) {
            val active = libraryFilter == filter
            row.addView(
                Button(this).apply {
                    text = label
                    textSize = 13f
                    isAllCaps = false
                    maxLines = 1
                    maxWidth = dp(220)
                    ellipsize = TextUtils.TruncateAt.END
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (active) uiPalette.textPrimary else uiPalette.textSecondary)
                    background = chromeButtonBackground(
                        if (active) uiPalette.surfaceSelected else uiPalette.surfaceRaised,
                    )
                    setPadding(dp(12), 0, dp(12), 0)
                    contentDescription = "Show $label library items" +
                        if (longPress == null) "" else ". Long press for tag actions"
                    setOnClickListener {
                        libraryFilter = filter
                        refreshLibraryUi()
                    }
                    if (longPress != null) setOnLongClickListener {
                        longPress()
                        true
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(CHROME_TOUCH_SIZE_DP),
                ).apply { marginEnd = dp(4) },
            )
        }

        addFilter("All", LibraryFilter(LibraryFilterKind.ALL))
        addFilter("On table", LibraryFilter(LibraryFilterKind.ON_TABLE))
        libraryTags.sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { tag ->
            addFilter(tag.name, LibraryFilter(LibraryFilterKind.TAG, tag.id)) {
                showLibraryTagActions(tag.id)
            }
        }
        addFilter("Untagged", LibraryFilter(LibraryFilterKind.UNTAGGED)) {
            showUntaggedActions()
        }
    }

    private fun refreshTextSearchScope() {
        val normalizedScope = normalizeSearchScope()
        val session = textSearchSession ?: return
        if (selectedBookIds.isEmpty()) {
            closeReference()
            return
        }
        session.scopeBookId = normalizedScope
        session.preciseContexts.clear()
        scheduleSelectedBookTextIndexes()
        refreshTextSearchResults()
        updateReferenceUi()
    }

    private fun openBookInTab(bookId: String) {
        val book = bookById(bookId) ?: return
        if (bookId !in selectedBookIds) {
            selectBook(bookId, openAfter = true)
            return
        }
        capturePrimaryImageViewport()
        val existing = tabs.indexOfFirst { it.bookId == bookId }
        if (existing >= 0) {
            if (existing == activeTabIndex) activateCurrentTab() else switchToTab(existing)
            return
        }
        tabs += ReaderTab(bookId, 0, "Start")
        activeTabIndex = tabs.lastIndex
        currentPage = 0
        activateCurrentTab()
    }

    private fun showLibrary() {
        if (libraryDialog?.isShowing == true) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val contentHorizontalPadding = dp(18)
        val contentTopPadding = dp(12)
        val contentBottomPadding = dp(12)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(uiPalette.background)
            setPadding(
                contentHorizontalPadding,
                contentTopPadding,
                contentHorizontalPadding,
                contentBottomPadding,
            )
            setOnApplyWindowInsetsListener { view, insets ->
                val edges = edgeInsets(insets)
                view.setPadding(
                    contentHorizontalPadding + edges.left,
                    contentTopPadding + edges.top,
                    contentHorizontalPadding + edges.right,
                    contentBottomPadding + edges.bottom,
                )
                insets
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Library"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(uiPalette.textPrimary)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "Add file"
            setOnClickListener { chooseLibraryDocument() }
        })
        header.addView(Button(this).apply {
            text = "Add folder"
            setOnClickListener { chooseLibraryFolder() }
        })
        header.addView(Button(this).apply {
            text = "Read"
            setOnClickListener { returnToReaderFromLibrary() }
        })
        content.addView(header)

        val tagStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        libraryTagStrip = tagStrip
        content.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    tagStrip,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(CHROME_TOUCH_SIZE_DP),
            ).apply { topMargin = dp(6) },
        )
        normalizeLibraryFilter()
        libraryVisibleBooks = libraryBooksForCurrentFilter()
        populateLibraryTagStrip(tagStrip)

        librarySelectionLabel = TextView(this).apply {
            text = librarySummaryLabel()
            textSize = 14f
            setTextColor(uiPalette.textSecondary)
            setPadding(0, dp(2), 0, dp(10))
        }
        content.addView(librarySelectionLabel)

        val gridFrame = FrameLayout(this)
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(220)
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(2), dp(2), dp(2), dp(12))
            clipToPadding = false
        }
        val empty = TextView(this).apply {
            text = libraryEmptyMessage()
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(uiPalette.textSecondary)
        }
        libraryEmptyLabel = empty
        gridFrame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        gridFrame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        grid.emptyView = empty

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = libraryVisibleBooks.size
            override fun getItem(position: Int): BookRecord = libraryVisibleBooks[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val book = getItem(position)
                val selected = book.id in selectedBookIds
                return LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    minimumHeight = dp(150)
                    setPadding(dp(14), dp(10), dp(10), dp(12))
                    background = controlBackground(
                        if (selected) uiPalette.surfaceSelected else uiPalette.surfaceRaised,
                    )
                    elevation = 0f
                    setOnClickListener { requestBookSelectionToggle(book.id) }

                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            View(this@MainActivity).apply { background = circleBackground(book.color) },
                            LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(8) },
                        )
                        addView(CheckBox(this@MainActivity).apply {
                            isChecked = selected
                            isClickable = false
                            text = if (selected) "On table" else "In library"
                            setTextColor(uiPalette.textSecondary)
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(Button(this@MainActivity).apply {
                            text = "⋮"
                            contentDescription = "Actions for ${book.title}"
                            setOnClickListener { showBookActions(book.id) }
                        }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    })

                    addView(TextView(this@MainActivity).apply {
                        text = book.fileName
                        textSize = 17f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(uiPalette.textPrimary)
                        maxLines = 2
                    })
                    val tagNames = libraryTags
                        .filter { it.id in book.tagIds }
                        .map { it.name }
                        .sortedBy { it.lowercase(Locale.ROOT) }
                    if (tagNames.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                        val visibleTags = tagNames.take(2).joinToString(" · ")
                        text = visibleTags + if (tagNames.size > 2) " · +${tagNames.size - 2}" else ""
                        textSize = 12f
                        setTextColor(uiPalette.textSecondary)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(4), 0, 0)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = when (book.kind) {
                            LibraryItemKind.IMAGE_COLLECTION ->
                                "Image album · ${book.pageCount} image${if (book.pageCount == 1) "" else "s"}"
                            LibraryItemKind.MARKDOWN ->
                                "Markdown note · ${book.bookmarkCount} heading${if (book.bookmarkCount == 1) "" else "s"}"
                            LibraryItemKind.PDF -> {
                                val toc = book.externalTocLabel?.let { " · TXT: $it" }.orEmpty()
                                "${book.pageCount} pages · ${book.bookmarkCount} bookmarks$toc"
                            }
                        }
                        textSize = 12f
                        setTextColor(uiPalette.textSecondary)
                        maxLines = 2
                        setPadding(0, dp(5), 0, 0)
                    })
                    if (book.id in unavailableBookIds) addView(TextView(this@MainActivity).apply {
                        text = "Source unavailable"
                        textSize = 12f
                        setTextColor(uiPalette.error)
                        setPadding(0, dp(5), 0, 0)
                    })
                }
            }
        }
        libraryAdapter = adapter
        grid.adapter = adapter
        content.addView(gridFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        val dialog = Dialog(
            this,
            if (uiPalette.isDark) R.style.FullScreenDialogThemeDark else R.style.FullScreenDialogThemeLight,
        ).apply {
            setContentView(content)
            setOnDismissListener {
                if (libraryDialog === this) {
                    libraryDialog = null
                    libraryAdapter = null
                    librarySelectionLabel = null
                    libraryTagStrip = null
                    libraryEmptyLabel = null
                    libraryVisibleBooks = emptyList()
                }
            }
        }
        libraryDialog = dialog
        dialog.show()
        dialog.window?.let { libraryWindow ->
            applySystemBarIconTheme(libraryWindow)
            libraryWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            content.requestApplyInsets()
        }
    }

    private fun requestBookSelectionToggle(bookId: String) {
        val book = bookById(bookId) ?: return
        if (bookId !in selectedBookIds) {
            selectBook(bookId)
            return
        }
        val hasOpenState = tabs.any { it.bookId == bookId } || referenceLocation?.bookId == bookId
        if (!hasOpenState) {
            deselectBook(bookId)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Take ${book.title} off the table?")
            .setMessage(
                "Its open tabs and temporary reference will close. Metadata, color, and visit " +
                    "history will remain in the library.",
            )
            .setPositiveButton("Take off table") { _, _ -> deselectBook(bookId) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun returnToReaderFromLibrary() {
        val first = selectedBooks().firstOrNull()
        if (first == null) {
            Toast.makeText(this, "Select at least one item", Toast.LENGTH_SHORT).show()
            return
        }
        libraryDialog?.dismiss()
        if (tabs.isEmpty()) openBookInTab(first.id) else activateCurrentTab()
    }

    private fun showBookActions(bookId: String) {
        val book = bookById(bookId) ?: return
        val actions = buildList {
            add(if (book.id in selectedBookIds) "Open" else "Put on table and open")
            if (book.id in selectedBookIds) add("Take off table")
            add("Tags…")
            if (book.kind == LibraryItemKind.PDF) {
                add(if (book.externalTocLabel == null) "Import TXT TOC" else "Replace TXT TOC")
                if (book.externalTocLabel != null) add("Remove TXT TOC")
            }
            add("Choose color")
            if (book.kind == LibraryItemKind.PDF) {
                add("Refresh PDF")
                add("Rebuild text index")
                add("Relink/replace PDF")
            } else if (book.kind == LibraryItemKind.MARKDOWN) {
                add("Refresh note")
                add("Rebuild text index")
                add("Relink/replace note")
            } else {
                add("Rescan source folder")
            }
            add("Forget item and history")
        }
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Open", "Put on table and open" -> {
                        libraryDialog?.dismiss()
                        selectBook(book.id, openAfter = true)
                    }
                    "Take off table" -> requestBookSelectionToggle(book.id)
                    "Tags…" -> showBookTagEditor(book.id)
                    "Import TXT TOC", "Replace TXT TOC" -> withHydratedBook(book.id) {
                        chooseExternalToc(it.id)
                    }
                    "Remove TXT TOC" -> withHydratedBook(book.id) { hydrated ->
                        val updated = hydrated.copy(
                            externalBookmarks = emptyList(),
                            externalTocLabel = null,
                            externalTocUri = null,
                            externalTocSize = null,
                            externalTocLastModified = null,
                            externalTocFingerprint = null,
                        )
                        submitPrefetchWork {
                            try {
                                bookRepository.saveIndex(updated)
                                mainHandler.post {
                                    val retained = if (book.id in selectedBookIds) updated else updated.copy(
                                        pdfBookmarks = emptyList(),
                                        externalBookmarks = emptyList(),
                                        imageFiles = emptyList(),
                                        storedBookmarkCount = updated.bookmarkCount,
                                        indexLoaded = false,
                                    )
                                    if (isDestroyed || !replaceBook(retained)) return@post
                                    persistBooks()
                                    rebuildBookmarkIndex()
                                    refreshLibraryUi()
                                    renderTabBar()
                                }
                            } catch (t: Throwable) {
                                showError("Could not remove TXT TOC", t)
                            }
                        }
                    }
                    "Choose color" -> showBookColorDialog(book.id)
                    "Refresh PDF" -> refreshLibraryBook(book.id)
                    "Refresh note" -> refreshLibraryBook(book.id)
                    "Rebuild text index" -> rebuildBookTextIndex(book.id)
                    "Relink/replace PDF", "Relink/replace note" -> chooseRelinkSource(book.id)
                    "Rescan source folder" -> rescanLibraryFolders()
                    "Forget item and history" -> confirmForgetBook(book.id)
                }
            }
            .show()
    }

    private fun showBookTagEditor(bookId: String) {
        val book = bookById(bookId) ?: return
        val orderedTags = libraryTags.sortedBy { it.name.lowercase(Locale.ROOT) }
        if (orderedTags.isEmpty()) {
            showCreateTagDialog(assignBookId = bookId)
            return
        }
        val checked = BooleanArray(orderedTags.size) { index -> orderedTags[index].id in book.tagIds }
        AlertDialog.Builder(this)
            .setTitle("Tags for ${book.fileName}")
            .setMultiChoiceItems(
                orderedTags.map { it.name }.toTypedArray(),
                checked,
            ) { _, which, selected -> checked[which] = selected }
            .setPositiveButton("Save") { _, _ ->
                updateBookTags(
                    bookId,
                    orderedTags.filterIndexed { index, _ -> checked[index] }
                        .mapTo(linkedSetOf()) { it.id },
                )
            }
            .setNeutralButton("New tag") { _, _ ->
                updateBookTags(
                    bookId,
                    orderedTags.filterIndexed { index, _ -> checked[index] }
                        .mapTo(linkedSetOf()) { it.id },
                )
                mainHandler.post { showCreateTagDialog(assignBookId = bookId) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateBookTags(bookId: String, tagIds: Set<String>) {
        val book = bookById(bookId) ?: return
        val knownIds = libraryTags.mapTo(HashSet()) { it.id }
        val normalized = tagIds.filterTo(linkedSetOf()) { it in knownIds }
        if (book.tagIds == normalized) return
        replaceBook(book.copy(tagIds = normalized))
        persistBooks()
        refreshLibraryUi()
    }

    private fun showCreateTagDialog(assignBookId: String? = null) {
        val input = EditText(this).apply {
            hint = "Tag name"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("New library tag")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = normalizedTagName(input.text?.toString().orEmpty())
                if (name.isEmpty()) {
                    Toast.makeText(this, "Tag name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val existing = libraryTags.firstOrNull { it.name.equals(name, ignoreCase = true) }
                val tag = existing ?: LibraryTagRecord(UUID.randomUUID().toString(), name).also(libraryTags::add)
                if (assignBookId != null) {
                    val book = bookById(assignBookId)
                    if (book != null && tag.id !in book.tagIds) {
                        replaceBook(book.copy(tagIds = book.tagIds + tag.id))
                    }
                }
                persistBooks()
                refreshLibraryUi()
                if (existing != null) {
                    Toast.makeText(this, "Existing tag assigned", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun normalizedTagName(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")

    private fun showLibraryTagActions(tagId: String) {
        val tag = libraryTags.firstOrNull { it.id == tagId } ?: return
        val tagged = books.filter { tag.id in it.tagIds }
        val actions = arrayOf(
            "Use only this tag on table",
            "Add tag to table",
            "Edit tagged items…",
            "Rename tag",
            "Delete tag",
            "Forget tagged items…",
        )
        AlertDialog.Builder(this)
            .setTitle("${tag.name} · ${tagged.size} item${if (tagged.size == 1) "" else "s"}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> updateTableFromLibraryItems(tagged, replace = true, label = tag.name)
                    1 -> updateTableFromLibraryItems(tagged, replace = false, label = tag.name)
                    2 -> showTagMembershipEditor(tag.id)
                    3 -> showRenameTagDialog(tag.id)
                    4 -> confirmDeleteTag(tag.id)
                    5 -> confirmForgetLibraryItems(tagged, tag)
                }
            }
            .show()
    }

    private fun showUntaggedActions() {
        val untagged = books.filter { it.tagIds.isEmpty() }
        val actions = arrayOf(
            "Use only untagged items on table",
            "Add untagged items to table",
            "Forget untagged items…",
        )
        AlertDialog.Builder(this)
            .setTitle("Untagged · ${untagged.size} item${if (untagged.size == 1) "" else "s"}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> updateTableFromLibraryItems(untagged, replace = true, label = "Untagged")
                    1 -> updateTableFromLibraryItems(untagged, replace = false, label = "Untagged")
                    2 -> confirmForgetLibraryItems(untagged, tag = null)
                }
            }
            .show()
    }

    private fun showTagMembershipEditor(tagId: String) {
        val tag = libraryTags.firstOrNull { it.id == tagId } ?: return
        if (books.isEmpty()) {
            Toast.makeText(this, "The library is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val orderedBooks = books.sortedBy { it.fileName.lowercase(Locale.ROOT) }
        val checked = BooleanArray(orderedBooks.size) { index -> tag.id in orderedBooks[index].tagIds }
        AlertDialog.Builder(this)
            .setTitle("Items tagged ${tag.name}")
            .setMultiChoiceItems(
                orderedBooks.map { it.fileName }.toTypedArray(),
                checked,
            ) { _, which, selected -> checked[which] = selected }
            .setPositiveButton("Save") { _, _ ->
                val includedIds = orderedBooks.indices
                    .filterTo(HashSet()) { checked[it] }
                    .mapTo(HashSet()) { orderedBooks[it].id }
                books.indices.forEach { index ->
                    val book = books[index]
                    val nextTags = if (book.id in includedIds) book.tagIds + tag.id else book.tagIds - tag.id
                    if (nextTags != book.tagIds) books[index] = book.copy(tagIds = nextTags)
                }
                persistBooks()
                refreshLibraryUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameTagDialog(tagId: String) {
        val tag = libraryTags.firstOrNull { it.id == tagId } ?: return
        val input = EditText(this).apply {
            setText(tag.name)
            setSingleLine(true)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Rename tag")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = normalizedTagName(input.text?.toString().orEmpty())
                when {
                    name.isEmpty() -> Toast.makeText(this, "Tag name cannot be empty", Toast.LENGTH_SHORT).show()
                    libraryTags.any { it.id != tag.id && it.name.equals(name, ignoreCase = true) } ->
                        Toast.makeText(this, "A tag with that name already exists", Toast.LENGTH_SHORT).show()
                    else -> {
                        val index = libraryTags.indexOfFirst { it.id == tag.id }
                        if (index >= 0) libraryTags[index] = tag.copy(name = name)
                        persistBooks()
                        refreshLibraryUi()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteTag(tagId: String) {
        val tag = libraryTags.firstOrNull { it.id == tagId } ?: return
        val count = books.count { tag.id in it.tagIds }
        AlertDialog.Builder(this)
            .setTitle("Delete tag ${tag.name}?")
            .setMessage(
                "The tag will be removed from $count item${if (count == 1) "" else "s"}. " +
                    "Library items, table selection, tabs, and history will be kept.",
            )
            .setPositiveButton("Delete tag") { _, _ ->
                libraryTags.removeAll { it.id == tag.id }
                books.indices.forEach { index ->
                    val book = books[index]
                    if (tag.id in book.tagIds) books[index] = book.copy(tagIds = book.tagIds - tag.id)
                }
                if (libraryFilter.tagId == tag.id) libraryFilter = LibraryFilter(LibraryFilterKind.ALL)
                persistBooks()
                refreshLibraryUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmForgetLibraryItems(items: List<BookRecord>, tag: LibraryTagRecord?) {
        if (items.isEmpty()) {
            Toast.makeText(this, "There are no matching library items", Toast.LENGTH_SHORT).show()
            return
        }
        val targetIds = items.mapTo(HashSet()) { it.id }
        val multipleTagged = items.filter { book ->
            book.tagIds.count { id -> libraryTags.any { it.id == id } } > 1
        }
        val message = buildString {
            append(
                "NaIgre will permanently remove ${items.size} item${if (items.size == 1) "" else "s"}, " +
                    "including their tabs, metadata, colors, tags, and visit history. Source files will not be deleted.",
            )
            if (multipleTagged.isNotEmpty()) {
                append("\n\nAlso tagged elsewhere and still forgotten:")
                multipleTagged.sortedBy { it.fileName.lowercase(Locale.ROOT) }.forEach { book ->
                    val otherTags = libraryTags
                        .filter { it.id in book.tagIds && it.id != tag?.id }
                        .joinToString { it.name }
                    append("\n• ${book.fileName}")
                    if (otherTags.isNotBlank()) append(" — $otherTags")
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (tag == null) "Forget all untagged items?" else "Forget everything tagged ${tag.name}?",
            )
            .setMessage(message)
            .setPositiveButton("Forget ${items.size}") { _, _ ->
                forgetLibraryItems(targetIds, deleteTagId = tag?.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateTableFromLibraryItems(
        items: List<BookRecord>,
        replace: Boolean,
        label: String,
    ) {
        if (items.isEmpty()) {
            Toast.makeText(this, "There are no matching library items", Toast.LENGTH_SHORT).show()
            return
        }
        val requestedIds = items.mapTo(HashSet()) { it.id }
        val desiredIds = linkedSetOf<String>()
        books.forEach { book ->
            if (book.id in requestedIds || (!replace && book.id in selectedBookIds)) {
                desiredIds += book.id
            }
        }
        val booksToHydrate = books.filter { it.id in desiredIds && !it.indexLoaded }
        if (desiredIds == selectedBookIds && booksToHydrate.isEmpty()) {
            Toast.makeText(this, "$label is already on the table", Toast.LENGTH_SHORT).show()
            return
        }

        val generation = ++bulkTableSelectionGeneration
        setStatus("Preparing $label…")
        submitPrefetchWork {
            val hydrated = HashMap<String, BookRecord>()
            val failures = mutableListOf<String>()
            booksToHydrate.forEach { book ->
                runCatching { bookRepository.hydrate(book) }
                    .onSuccess { hydrated[book.id] = it }
                    .onFailure { failures += book.fileName }
            }
            mainHandler.post {
                if (isDestroyed || generation != bulkTableSelectionGeneration) return@post
                applyBulkTableSelection(desiredIds, hydrated)
                val action = if (replace) "Table now uses" else "Added to table:"
                val failureSuffix = if (failures.isEmpty()) {
                    ""
                } else {
                    " · ${failures.size} index load failure${if (failures.size == 1) "" else "s"}"
                }
                Toast.makeText(this, "$action $label$failureSuffix", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyBulkTableSelection(
        desiredIds: LinkedHashSet<String>,
        hydratedBooks: Map<String, BookRecord>,
    ) {
        capturePrimaryImageViewport()
        captureReferenceImageViewport()
        val previousActive = activeTabOrNull()
        val removedIds = selectedBookIds.filterTo(HashSet()) { it !in desiredIds }
        val removedPrimary = pdfBookId in removedIds
        val removedSecondary = prefetchBookId in removedIds

        hydratedBooks.values.forEach(::replaceBook)
        if (referenceLocation?.bookId in removedIds) closeReference(renderPrimary = false)
        tabs.filter { it.bookId in removedIds }.forEach(primaryImageViewports::remove)
        tabs.filter { it.bookId in removedIds }.forEach(primaryMarkdownViewports::remove)
        tabs.removeAll { it.bookId in removedIds }
        removedIds.forEach { bookId ->
            referenceImageViewports.keys.removeAll { it.startsWith("$bookId|") }
            textIndexTokens.remove(bookId)
            pageCache.removeBook(bookId)
            val book = bookById(bookId) ?: return@forEach
            if (book.indexLoaded) {
                replaceBook(
                    book.copy(
                        pdfBookmarks = emptyList(),
                        externalBookmarks = emptyList(),
                        imageFiles = emptyList(),
                        storedBookmarkCount = book.bookmarkCount,
                        indexLoaded = false,
                    ),
                )
            }
        }

        selectedBookIds.clear()
        selectedBookIds += desiredIds
        if (tabs.isEmpty()) {
            selectedBooks().firstOrNull()?.let { tabs += ReaderTab(it.id, 0, "Start") }
        }
        activeTabIndex = previousActive
            ?.let { previous -> tabs.indexOfFirst { it === previous } }
            ?.takeIf { it >= 0 }
            ?: if (tabs.isEmpty()) 0 else activeTabIndex.coerceIn(0, tabs.lastIndex)
        currentPage = activeTabOrNull()?.pageIndex ?: 0

        persistBooks()
        persistSession()
        rebuildBookmarkIndex()
        refreshTextSearchScope()
        refreshLibraryUi()
        updateCachePins()

        if (removedSecondary) submitPrefetchWork {
            if (prefetchBookId in removedIds) {
                prefetchPdf?.close()
                prefetchPdf = null
                prefetchBookId = null
            }
        }
        when {
            removedPrimary -> {
                invalidateRendering(clearCache = true)
                if (tabs.isNotEmpty()) {
                    activateCurrentTab(forceReload = true)
                } else {
                    ++primaryOpenGeneration
                    primaryOpenTargetBookId = null
                    submitRenderWork {
                        pdf?.close()
                        pdf = null
                        pdfBookId = null
                    }
                }
            }
            pdf == null && tabs.isNotEmpty() -> activateCurrentTab()
            else -> {
                renderTabBar()
                updatePageIndicator()
                updateEmptyState()
            }
        }
    }

    private fun forgetLibraryItems(bookIds: Set<String>, deleteTagId: String?) {
        val forgotten = books.filter { it.id in bookIds }
        if (forgotten.isEmpty()) return
        ++bulkTableSelectionGeneration
        capturePrimaryImageViewport()
        captureReferenceImageViewport()
        val previousActive = activeTabOrNull()
        val forgottenIds = forgotten.mapTo(HashSet()) { it.id }
        val removedPrimary = pdfBookId in forgottenIds
        val removedSecondary = prefetchBookId in forgottenIds

        if (referenceLocation?.bookId in forgottenIds) closeReference(renderPrimary = false)
        selectedBookIds.removeAll(forgottenIds)
        tabs.filter { it.bookId in forgottenIds }.forEach(primaryImageViewports::remove)
        tabs.filter { it.bookId in forgottenIds }.forEach(primaryMarkdownViewports::remove)
        tabs.removeAll { it.bookId in forgottenIds }
        forgottenIds.forEach { bookId ->
            unavailableBookIds.remove(bookId)
            unavailableBookErrors.remove(bookId)
            referenceImageViewports.keys.removeAll { it.startsWith("$bookId|") }
            bookmarkVisitCounts.keys.removeAll { it.startsWith("$bookId|") }
            readerSessionRepository.deleteBookmarkVisits(bookId)
            pageCache.removeBook(bookId)
            textIndexTokens.remove(bookId)
        }
        books.removeAll { it.id in forgottenIds }
        if (deleteTagId != null) {
            libraryTags.removeAll { it.id == deleteTagId }
            if (libraryFilter.tagId == deleteTagId) libraryFilter = LibraryFilter(LibraryFilterKind.ALL)
        }
        forgotten.forEach { book ->
            releaseExactPersistedPermission(book.uri)
            runCatching { bookRepository.delete(book.id) }
            deleteBookTextIndex(book.id)
        }

        if (tabs.isEmpty()) {
            selectedBooks().firstOrNull()?.let { tabs += ReaderTab(it.id, 0, "Start") }
        }
        activeTabIndex = previousActive
            ?.let { previous -> tabs.indexOfFirst { it === previous } }
            ?.takeIf { it >= 0 }
            ?: if (tabs.isEmpty()) 0 else activeTabIndex.coerceIn(0, tabs.lastIndex)
        currentPage = activeTabOrNull()?.pageIndex ?: 0

        persistBooks()
        persistSession()
        rebuildBookmarkIndex()
        refreshTextSearchScope()
        refreshLibraryUi()
        updateCachePins()

        if (removedSecondary) submitPrefetchWork {
            if (prefetchBookId in forgottenIds) {
                prefetchPdf?.close()
                prefetchPdf = null
                prefetchBookId = null
            }
        }
        when {
            removedPrimary -> {
                invalidateRendering(clearCache = true)
                if (tabs.isNotEmpty()) {
                    activateCurrentTab(forceReload = true)
                } else {
                    ++primaryOpenGeneration
                    primaryOpenTargetBookId = null
                    submitRenderWork {
                        pdf?.close()
                        pdf = null
                        pdfBookId = null
                    }
                    renderTabBar()
                    updatePageIndicator()
                    updateEmptyState()
                }
            }
            pdf == null && tabs.isNotEmpty() -> activateCurrentTab()
            else -> {
                renderTabBar()
                updatePageIndicator()
                updateEmptyState()
            }
        }
        if (books.isEmpty()) setStatus("Library is empty")
    }

    private fun withHydratedBook(bookId: String, action: (BookRecord) -> Unit) {
        val book = bookById(bookId) ?: return
        if (book.indexLoaded) {
            action(book)
            return
        }
        setStatus("Loading ${book.title} metadata…")
        submitPrefetchWork {
            try {
                val hydrated = bookRepository.hydrate(book)
                mainHandler.post {
                    if (isDestroyed || !replaceBook(hydrated)) return@post
                    action(hydrated)
                }
            } catch (t: Throwable) {
                showError("Could not load ${book.title}", t)
            }
        }
    }

    private fun refreshLibraryBook(bookId: String) {
        val book = bookById(bookId) ?: return
        if (book.kind == LibraryItemKind.IMAGE_COLLECTION) {
            rescanLibraryFolders()
            return
        }
        if (book.kind == LibraryItemKind.MARKDOWN) {
            if (pdfBookId == bookId && activeTabOrNull()?.bookId == bookId) {
                setStatus("Refreshing ${book.title}…")
                activateCurrentTab(
                    forceReload = true,
                    forceMetadataRefresh = true,
                    refreshReason = "Manual note refresh",
                )
            } else {
                refreshMarkdownBook(book)
            }
            return
        }
        if (pdfBookId == bookId && activeTabOrNull()?.bookId == bookId) {
            setStatus("Refreshing ${book.title}…")
            activateCurrentTab(
                forceReload = true,
                forceMetadataRefresh = true,
                refreshReason = "Manual PDF refresh",
            )
            return
        }
        updateKnownBookSource(
            bookId = book.id,
            uri = Uri.parse(book.uri),
            metadata = documentMetadataReader.query(Uri.parse(book.uri)),
            selectAndOpen = false,
            forceTextReindex = true,
            refreshReason = "Manual PDF refresh",
        )
    }

    private fun refreshMarkdownBook(book: BookRecord) {
        setStatus("Refreshing ${book.title}…")
        submitPrefetchWork {
            try {
                val hydrated = if (book.indexLoaded) book else bookRepository.hydrate(book)
                val document = MarkdownDocument(contentResolver, Uri.parse(book.uri), markdownEngine)
                val metadata = documentMetadataReader.query(Uri.parse(book.uri))
                val headings = outlineEntries(document, book.id, BookmarkSource.MARKDOWN_HEADING)
                val updated = hydrated.copy(
                    title = metadata.fileName?.let(::cleanBookName) ?: hydrated.title,
                    fileName = metadata.fileName ?: hydrated.fileName,
                    pageCount = document.pageCount,
                    pdfBookmarks = headings,
                    storedBookmarkCount = headings.distinctBy { it.identityKey }.size,
                    indexVersion = CURRENT_BOOK_INDEX_VERSION,
                    indexLoaded = true,
                    sourceSize = metadata.size ?: hydrated.sourceSize,
                    sourceLastModified = metadata.lastModified ?: hydrated.sourceLastModified,
                    sourceFingerprint = document.content.fingerprint,
                    sourceRevisionToken = UUID.randomUUID().toString(),
                )
                bookRepository.saveIndex(updated)
                mainHandler.post {
                    if (isDestroyed || !replaceBook(updated)) return@post
                    unavailableBookIds.remove(book.id)
                    unavailableBookErrors.remove(book.id)
                    lastSourceRefreshDiagnostic = sourceRefreshDiagnostic(
                        book = hydrated,
                        metadata = metadata,
                        reason = "Manual note refresh",
                        nextRevision = updated.sourceRevisionKey(),
                    )
                    val destinations = headings.associateBy { it.identityKey }
                    tabs.filter { it.bookId == book.id }.forEach { refreshedTab ->
                        val destination = refreshedTab.anchorKey?.let(destinations::get)
                        refreshedTab.pageIndex = destination?.pageIndex
                            ?: refreshedTab.pageIndex.coerceIn(0, updated.pageCount - 1)
                        refreshedTab.originPageIndex = destination?.pageIndex
                            ?: refreshedTab.originPageIndex.coerceIn(0, updated.pageCount - 1)
                        if (destination != null) refreshedTab.label = destination.title
                    }
                    persistBooks()
                    rebuildBookmarkIndex()
                    scheduleBookTextIndex(updated, force = true)
                    refreshTextSearchScope()
                    refreshLibraryUi()
                    Toast.makeText(this, "${updated.title} refreshed", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                showError("Note refresh failed", t)
            }
        }
    }

    private fun rebuildBookTextIndex(bookId: String) {
        val book = bookById(bookId) ?: return
        if (!isTextSearchable(book)) return
        textIndexErrors.remove(bookId)
        scheduleBookTextIndex(book, force = true)
        Toast.makeText(this, "Rebuilding text index for ${book.title}", Toast.LENGTH_SHORT).show()
        if (textSearchSession != null && bookId in selectedBookIds) refreshTextSearchResults()
    }

    private fun showBookColorDialog(bookId: String) {
        val book = bookById(bookId) ?: return
        val selected = BOOK_COLORS.indexOf(book.color).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Color for ${book.title}")
            .setSingleChoiceItems(
                BOOK_COLOR_NAMES.mapIndexed { index, name ->
                    SpannableString("●  $name").apply {
                        setSpan(
                            ForegroundColorSpan(BOOK_COLORS[index]),
                            0,
                            1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }.toTypedArray(),
                selected,
            ) { dialog, which ->
                replaceBook(book.copy(color = BOOK_COLORS[which]))
                persistBooks()
                renderTabBar()
                updateReferenceUi()
                if (textSearchSession != null) textSearchResultAdapter.notifyDataSetChanged()
                refreshLibraryUi()
                dialog.dismiss()
            }
            .show()
    }

    private fun confirmForgetBook(bookId: String) {
        val book = bookById(bookId) ?: return
        AlertDialog.Builder(this)
            .setTitle("Forget ${book.title}?")
            .setMessage(
                "Source files are not deleted, but NaIgre will permanently remove this item's " +
                    "tabs, metadata, color, and visit history.",
            )
            .setPositiveButton("Forget") { _, _ -> forgetBook(book) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun forgetBook(book: BookRecord) {
        forgetLibraryItems(setOf(book.id), deleteTagId = null)
    }

    private fun showReaderMenu() {
        val currentBook = activeBook()
        val readingSummary = when (currentBook?.kind) {
            LibraryItemKind.PDF -> "${if (spreadMode) "Spread" else "Single"}, ${pageTurnMode.label}"
            LibraryItemKind.MARKDOWN -> "Continuous text"
            else -> pageTurnMode.label
        }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Reading view · $readingSummary" to { showReadingViewMenu() }
        actions += "Interface · ${themeMode.label}, ${readerLayoutMode.label}" to { showInterfaceMenu() }
        currentBook?.takeIf { it.kind != LibraryItemKind.IMAGE_COLLECTION }?.let { book ->
            actions += "Current book · ${book.fileName}" to { showCurrentBookMenu(book.id) }
        }
        actions += "Library · ${selectedBookIds.size}/${books.size} on table" to { showLibraryMenu() }
        actions += "Diagnostics · ${appVersionLabel()}" to { showDiagnostics() }
        showReaderActionList(actions)
    }

    private fun showLibraryMenu() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Open library" to { showLibrary() }
        actions += "Add file" to { chooseLibraryDocument() }
        actions += "Add folder" to { chooseLibraryFolder() }
        if (libraryFolders.isNotEmpty()) {
            actions += "Rescan folders · ${libraryFolders.size}" to { rescanLibraryFolders() }
        }
        showReaderActionList(actions, title = "Library", returnToMainOnCancel = true)
    }

    private fun showReadingViewMenu() {
        val currentBook = activeBook()
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (currentBook?.kind == LibraryItemKind.PDF) {
            actions += "Page layout · ${if (spreadMode) "Spread" else "Single"}" to {
                setSpread(!spreadMode)
            }
            actions += "Skip cover in spreads · ${if (spreadSkipCover) "On" else "Off"}" to {
                setSpreadSkipCover(!spreadSkipCover)
            }
        }
        if (currentBook?.kind != LibraryItemKind.MARKDOWN) {
            actions += "Page turns · ${pageTurnMode.label}" to { showPageTurnModeDialog() }
        }
        if (currentBook != null && currentBook.kind != LibraryItemKind.MARKDOWN) {
            actions += "Reset zoom" to {
                clearCurrentImageViewports()
                readerSurface.resetTransform()
                referenceSurface.resetTransform()
            }
        }
        showReaderActionList(actions, title = "Reading view", returnToMainOnCancel = true)
    }

    private fun showInterfaceMenu() {
        val actions = listOf<Pair<String, () -> Unit>>(
            "Theme · ${themeMode.label}" to { showThemeDialog() },
            "Reader layout · ${readerLayoutMode.label}" to { showReaderLayoutDialog() },
            "Fullscreen + above lock screen · ${if (immersive) "On" else "Off"}" to {
                applyImmersiveMode(!immersive)
            },
        )
        showReaderActionList(actions, title = "Interface", returnToMainOnCancel = true)
    }

    private fun showCurrentBookMenu(bookId: String) {
        val book = bookById(bookId)?.takeIf { it.kind != LibraryItemKind.IMAGE_COLLECTION } ?: return
        val actions = if (book.kind == LibraryItemKind.PDF) {
            listOf<Pair<String, () -> Unit>>(
                (if (book.externalTocLabel == null) "Import TXT TOC" else "Replace TXT TOC") to {
                    withHydratedBook(book.id) { chooseExternalToc(it.id) }
                },
                "Refresh PDF" to { refreshLibraryBook(book.id) },
                "Rebuild text index" to { rebuildBookTextIndex(book.id) },
                "Relink/replace PDF" to { chooseRelinkSource(book.id) },
            )
        } else {
            listOf(
                "Refresh note" to { refreshLibraryBook(book.id) },
                "Rebuild text index" to { rebuildBookTextIndex(book.id) },
                "Relink/replace note" to { chooseRelinkSource(book.id) },
            )
        }
        showReaderActionList(
            actions,
            title = "Current book · ${book.fileName}",
            returnToMainOnCancel = true,
        )
    }

    private fun showReaderActionList(
        actions: List<Pair<String, () -> Unit>>,
        title: String? = null,
        returnToMainOnCancel: Boolean = false,
    ) {
        val builder = AlertDialog.Builder(this)
        if (title != null) builder.setTitle(title)
        builder.setItems(actions.map { it.first }.toTypedArray()) { _, which ->
            actions.getOrNull(which)?.second?.invoke()
        }
        if (returnToMainOnCancel) builder.setOnCancelListener { showReaderMenu() }
        builder.show()
    }

    private fun setSpread(enabled: Boolean) {
        if (spreadMode == enabled) return
        spreadMode = enabled
        persistSession()
        readerSurface.resetTransform()
        renderCurrent()
    }

    private fun setSpreadSkipCover(enabled: Boolean) {
        if (spreadSkipCover == enabled) return
        spreadSkipCover = enabled
        persistSession()
        if (spreadMode) {
            readerSurface.resetTransform()
            renderCurrent()
        }
    }

    private fun showPageTurnModeDialog() {
        val modes = PageTurnMode.entries
        val selected = modes.indexOf(pageTurnMode)
        AlertDialog.Builder(this)
            .setTitle("Page turning")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), selected) { dialog, which ->
                pageTurnMode = modes[which]
                readerSurface.pageTurnMode = pageTurnMode
                referenceSurface.pageTurnMode = pageTurnMode
                persistSession()
                dialog.dismiss()
            }
            .show()
    }

    private fun showThemeDialog() {
        val modes = ReaderThemeMode.entries
        val selected = modes.indexOf(themeMode)
        AlertDialog.Builder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), selected) { dialog, which ->
                val next = modes[which]
                dialog.dismiss()
                if (next == themeMode) return@setSingleChoiceItems
                themeMode = next
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_THEME, next.name)
                    .apply()
                persistSession()
                recreate()
            }
            .show()
    }

    private fun showReaderLayoutDialog() {
        val modes = ReaderLayoutMode.entries
        val selected = modes.indexOf(readerLayoutMode)
        AlertDialog.Builder(this)
            .setTitle("Reader layout")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                setReaderLayoutMode(modes[which])
            }
            .show()
    }

    private fun applyImmersiveMode(enabled: Boolean) {
        immersive = enabled
        applyShowWhenLocked(enabled)
        applyImmersiveToWindow(window, enabled)
    }

    private fun applyShowWhenLocked(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(enabled)
        } else {
            @Suppress("DEPRECATION")
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }
    }

    private fun applyImmersiveToWindow(target: Window, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            target.decorView.windowInsetsController?.let { controller ->
                val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                controller.setSystemBarsAppearance(if (uiPalette.isDark) 0 else lightBars, lightBars)
                if (enabled) {
                    controller.hide(WindowInsets.Type.systemBars())
                } else {
                    controller.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            target.decorView.systemUiVisibility =
                (if (uiPalette.isDark) 0 else {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }) or
                if (enabled) {
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                } else {
                    View.SYSTEM_UI_FLAG_VISIBLE
                }
        }
    }

    private fun applySystemBarIconTheme(target: Window) {
        if (Build.VERSION.SDK_INT >= 30) {
            target.decorView.windowInsetsController?.let { controller ->
                val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                controller.setSystemBarsAppearance(if (uiPalette.isDark) 0 else lightBars, lightBars)
            }
        } else {
            @Suppress("DEPRECATION")
            val lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            target.decorView.systemUiVisibility =
                (target.decorView.systemUiVisibility and lightBars.inv()) or
                if (uiPalette.isDark) 0 else lightBars
        }
    }

    private fun showDiagnostics() {
        val document = pdf
        val version = appVersionLabel()
        val message = buildString {
            append("NaIgre $version")
            append("\n\n${lastStatus.ifBlank { "No status yet" }}")
            append("\n\nLibrary items: ${books.size}")
            append(" (${books.count { it.kind == LibraryItemKind.PDF }} PDFs, ")
            append("${books.count { it.kind == LibraryItemKind.MARKDOWN }} notes, ")
            append("${books.count { it.kind == LibraryItemKind.IMAGE_COLLECTION }} albums)")
            append("\nLibrary folders: ${libraryFolders.size}")
            append("\nLibrary tags: ${libraryTags.size}")
            append("\nSelected items: ${selectedBookIds.size}")
            append("\nTheme: ${themeMode.label} → ${if (uiPalette.isDark) "Dark" else "Light"}")
            append("\nIndexed bookmarks: ${bookmarkIndex.size}")
            append("\nText index: %.1f MB".format(textSearchIndex.databaseBytes() / (1024.0 * 1024.0)))
            append("\nText indexing jobs: ${textIndexTokens.size}")
            append("\nTabs: ${tabs.size}")
            append("\nLayout: ${readerLayoutMode.label} → ${resolvedReaderLayout().label}")
            append("\nDevice memory class: $deviceMemoryClassMb MB")
            if (lowRamDevice) append(" · low-RAM")
            append("\nImage decode ceiling: %.1f MP / %.0f MB".format(
                imageDecodePolicy.maxPixels / 1_000_000.0,
                imageDecodePolicy.maxBitmapBytes / (1024.0 * 1024.0),
            ))
            if (imageDecodePolicy.reducedForMemory) append(" · memory safeguard")
            activeBook()?.let { active ->
                val revision = active.sourceRevisionKey()
                append("\nActive source revision: ${revision.take(12)}")
                append(" · size ${active.sourceSize ?: "unknown"}")
                append(" · modified ${active.sourceLastModified ?: "unknown"}")
                if (isTextSearchable(active)) {
                    val indexedRevision = textSearchIndex.progress(active.id)?.sourceRevision
                    append("\nActive text revision: ${indexedRevision?.take(12) ?: "not indexed"}")
                    append(if (indexedRevision == revision) " · current" else " · rebuilding")
                }
            }
            append("\nLast source refresh: $lastSourceRefreshDiagnostic")
            textSearchSession?.let { search ->
                append("\nSearch reference: “${search.query}” · ${search.results.size}/${search.totalMatches} shown")
                append(" · ${search.indexedPages}/${search.totalPages} parts")
                append(" · scope ${search.scopeBookId?.let { bookById(it)?.title } ?: "All"}")
            }
            append("\nLast text search: $lastTextSearchDiagnostic")
            if (document != null) {
                append("\nActive parts: ${document.pageCount}")
                referenceLocation?.let { reference ->
                    append("\nReference: ${reference.label} · page ${reference.pageIndex + 1}")
                }
                append("\nSpread: $spreadMode")
                append("\nSkip cover: $spreadSkipCover")
                append("\nPage turns: ${pageTurnMode.label}")
                append("\nCache: ${pageCache.size()} pages / %.0f of %.0f MB".format(
                    pageCache.bytes() / (1024.0 * 1024.0),
                    pageCache.capacityBytes() / (1024.0 * 1024.0),
                ))
                listOf(
                    "Primary" to primaryDisplayedPageKeys,
                    "Reference" to referenceDisplayedPageKeys,
                ).forEach { (label, keys) ->
                    val key = keys.singleOrNull() ?: return@forEach
                    if (bookById(key.bookId)?.kind != LibraryItemKind.IMAGE_COLLECTION) return@forEach
                    pageCache.info(key)?.let { info ->
                        append("\n$label image: ${info.width}×${info.height} · %.1f MP / %.0f MB".format(
                            info.width.toLong() * info.height / 1_000_000.0,
                            info.bytes / (1024.0 * 1024.0),
                        ))
                    }
                }
            }
            append("\n\nLast link: $lastLinkDiagnostic")
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Diagnostics · $version")
            .setMessage(message)
            .setPositiveButton("Close", null)
        if (document != null && activeBook()?.kind == LibraryItemKind.PDF) {
            builder.setNeutralButton("Annotations") { _, _ -> inspectAnnotations() }
        }
        val dialog = builder.show()
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    @Suppress("DEPRECATION")
    private fun appVersionLabel(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName ?: "unknown"} ($code)"
    }.getOrDefault("unknown")

    private fun scheduleSelectedBookTextIndexes() {
        val activeId = activeTabOrNull()?.bookId
        val scopedId = normalizeSearchScope()
        selectedBooks()
            .filter(::isTextSearchable)
            .sortedBy {
                when (it.id) {
                    scopedId -> 0
                    activeId -> 1
                    else -> 2
                }
            }
            .forEach { scheduleBookTextIndex(it) }
    }

    private fun scheduleBookTextIndex(book: BookRecord, force: Boolean = false) {
        if (!isTextSearchable(book)) return
        val sourceRevision = book.sourceRevisionKey()
        val token = textIndexTokenSequence.incrementAndGet()
        if (force) {
            textIndexTokens[book.id] = token
        } else if (textIndexTokens.putIfAbsent(book.id, token) != null) {
            return
        }
        textIndexErrors.remove(book.id)

        submitTextIndexWork {
            var document: PdfDocument? = null
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                if (textIndexTokens[book.id] != token || destroying) return@submitTextIndexWork
                var progress = textSearchIndex.prepareBook(
                    book.id,
                    book.pageCount,
                    sourceRevision,
                    force,
                )
                if (progress.complete) {
                    return@submitTextIndexWork
                }
                notifyTextIndexProgress(book.id)

                val openedDocument = openDocument(book)
                document = openedDocument
                require(openedDocument.pageCount == book.pageCount) {
                    "Document structure changed; refresh ${book.title}"
                }
                var nextPage = progress.indexedPages
                while (
                    nextPage < openedDocument.pageCount && !destroying &&
                    textIndexTokens[book.id] == token && !Thread.currentThread().isInterrupted
                ) {
                    val endExclusive = min(nextPage + 12, openedDocument.pageCount)
                    val batch = ArrayList<ExtractedTextPage>(endExclusive - nextPage)
                    for (pageIndex in nextPage until endExclusive) {
                        if (textIndexTokens[book.id] != token || destroying) return@submitTextIndexWork
                        val text = runCatching { openedDocument.textForSearch(pageIndex) }.getOrDefault("")
                        batch += ExtractedTextPage(pageIndex, text)
                    }
                    if (textIndexTokens[book.id] != token || destroying) return@submitTextIndexWork
                    progress = textSearchIndex.storePages(
                        book.id,
                        openedDocument.pageCount,
                        sourceRevision,
                        batch,
                    )
                    nextPage = progress.indexedPages
                    if (progress.complete || nextPage % 60 < batch.size) {
                        notifyTextIndexProgress(book.id)
                    }
                }
            } catch (t: Throwable) {
                if (textIndexTokens[book.id] == token && !destroying) {
                    textIndexErrors[book.id] = t.message ?: t.javaClass.simpleName
                    notifyTextIndexProgress(book.id)
                }
            } finally {
                document?.close()
                textIndexTokens.remove(book.id, token)
            }
        }
    }

    private fun notifyTextIndexProgress(bookId: String) {
        mainHandler.post {
            if (isDestroyed) return@post
            val session = textSearchSession
            if (session != null && bookId in session.bookIds) {
                mainHandler.removeCallbacks(textSearchProgressRefresh)
                mainHandler.postDelayed(textSearchProgressRefresh, 150L)
            }
        }
    }

    private fun deleteBookTextIndex(bookId: String) {
        textIndexTokens[bookId] = textIndexTokenSequence.incrementAndGet()
        submitTextIndexWork {
            runCatching { textSearchIndex.deleteBook(bookId) }
            textIndexTokens.remove(bookId)
            mainHandler.post {
                if (isDestroyed) return@post
                bookById(bookId)
                    ?.takeIf { it.id in selectedBookIds && isTextSearchable(it) }
                    ?.let(::scheduleBookTextIndex)
            }
        }
    }

    private fun submitTextIndexWork(block: () -> Unit) {
        try {
            textIndexWorker.execute(block)
        } catch (_: RejectedExecutionException) {
            // Lifecycle teardown can race queued indexing notifications.
        }
    }

    private fun submitTextSearchWork(block: () -> Unit) {
        try {
            textSearchFuture?.cancel(false)
            textSearchFuture = textSearchWorker.submit(block)
        } catch (_: RejectedExecutionException) {
            // Lifecycle teardown can race queued search requests.
        }
    }

    private fun submitRenderWork(block: () -> Unit) {
        try {
            renderWorker.execute(block)
        } catch (_: RejectedExecutionException) {
            // Lifecycle teardown can race a queued UI callback; dropping late work is correct.
        }
    }

    private fun submitPrefetchWork(block: () -> Unit) {
        try {
            prefetchWorker.execute(block)
        } catch (_: RejectedExecutionException) {
            // Lifecycle teardown can race a queued UI callback; dropping late work is correct.
        }
    }

    private fun setStatus(message: String) {
        lastStatus = message
    }

    private fun showError(title: String, throwable: Throwable) {
        if (throwable.message == "Document changed during render") return
        mainHandler.post {
            if (isDestroyed) return@post
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(throwable.message ?: throwable.javaClass.simpleName)
                .setPositiveButton("Close", null)
                .show()
            setStatus("$title: ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }
}
