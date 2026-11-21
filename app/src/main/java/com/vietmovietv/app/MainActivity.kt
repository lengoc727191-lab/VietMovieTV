package com.vietmovietv.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.vietmovietv.app.data.FilterOption
import com.vietmovietv.app.data.MovieFilters
import com.vietmovietv.app.data.MovieItem
import com.vietmovietv.app.data.MovieSource
import com.vietmovietv.app.data.MultiSourceRepository
import com.vietmovietv.app.data.WatchHistoryManager
import com.vietmovietv.app.ui.FocusEffects
import com.vietmovietv.app.ui.HomeSection
import com.vietmovietv.app.ui.HomeSectionAdapter
import com.vietmovietv.app.ui.MovieAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Left Navigation Rail
    private lateinit var navRail: LinearLayout
    private lateinit var tvBrandMark: TextView
    private lateinit var navSearch: LinearLayout
    private lateinit var navHome: LinearLayout
    private lateinit var navPopular: LinearLayout
    private lateinit var navDubbed: LinearLayout
    private lateinit var navSeries: LinearLayout
    private lateinit var navSingle: LinearLayout
    private lateinit var navAnime: LinearLayout
    private lateinit var navTvShows: LinearLayout
    private lateinit var navSource: LinearLayout

    private lateinit var allNavItems: List<LinearLayout>
    private lateinit var allNavLabels: List<TextView>
    private lateinit var allNavIndicators: Map<LinearLayout, View>
    private lateinit var lblSource: TextView

    // Home Container (Hero + Swimlanes)
    private lateinit var homeContainer: FrameLayout
    private lateinit var ivHeroBackdrop: ImageView
    private lateinit var tvHeroTag: TextView
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroMeta: TextView
    private lateinit var tvHeroDesc: TextView
    private lateinit var btnHeroPlay: Button
    private lateinit var btnHeroInfo: Button
    private lateinit var rvHomeSections: RecyclerView
    private lateinit var homeProgressBar: ProgressBar
    private lateinit var homeSectionAdapter: HomeSectionAdapter

    // Browse / Search Container
    private lateinit var browseContainer: LinearLayout
    private lateinit var tvBrowseTitle: TextView
    private lateinit var tvBrowseStatus: TextView
    private lateinit var tvBrowseFocusedTitle: TextView
    private lateinit var btnFilterCountry: Button
    private lateinit var btnFilterGenre: Button
    private lateinit var btnResetFilter: Button
    private lateinit var searchBoxContainer: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: Button
    private lateinit var rvBrowseGrid: RecyclerView
    private lateinit var browseProgressBar: ProgressBar
    private lateinit var browseAdapter: MovieAdapter

    private lateinit var historyManager: WatchHistoryManager

    private var currentSource: MovieSource = MovieSource.AUTO
    private var currentMode = "home" // home, popular, thuyet-minh, phim-bo, phim-le, hoat-hinh, tv-shows, search
    private var currentLabel = "Trang chủ"
    private var selectedCountry: FilterOption = MovieFilters.countries[0]
    private var selectedGenre: FilterOption = MovieFilters.genres[0]
    private var lastSearchKeyword = ""
    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true
    private var focusedHeroMovie: MovieItem? = null

    private var railExpanded = false
    private var railAnimator: ValueAnimator? = null
    private var loadJob: Job? = null
    private var heroUpdateJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var lastHeroBackdropUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyManager = WatchHistoryManager(this)
        bindViews()
        setupLeftNavRail()
        setupHomeViews()
        setupBrowseViews()

        loadHomeSwimlanes()
        rvHomeSections.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // Refresh "Đang xem dở" if returning from player on Home
        if (currentMode == "home" && homeSectionAdapter.itemCount > 0) {
            refreshContinueWatchingRow()
        }
    }

    private fun bindViews() {
        navRail = findViewById(R.id.navRail)
        tvBrandMark = findViewById(R.id.tvBrandMark)
        navSearch = findViewById(R.id.navSearch)
        navHome = findViewById(R.id.navHome)
        navPopular = findViewById(R.id.navPopular)
        navDubbed = findViewById(R.id.navDubbed)
        navSeries = findViewById(R.id.navSeries)
        navSingle = findViewById(R.id.navSingle)
        navAnime = findViewById(R.id.navAnime)
        navTvShows = findViewById(R.id.navTvShows)
        navSource = findViewById(R.id.navSource)
        lblSource = findViewById(R.id.lblSource)

        allNavItems = listOf(navSearch, navHome, navPopular, navDubbed, navSeries, navSingle, navAnime, navTvShows, navSource)
        allNavLabels = listOf(
            findViewById(R.id.lblSearch),
            findViewById(R.id.lblHome),
            findViewById(R.id.lblPopular),
            findViewById(R.id.lblDubbed),
            findViewById(R.id.lblSeries),
            findViewById(R.id.lblSingle),
            findViewById(R.id.lblAnime),
            findViewById(R.id.lblTvShows),
            lblSource
        )
        allNavIndicators = mapOf(
            navSearch to findViewById(R.id.indSearch),
            navHome to findViewById(R.id.indHome),
            navPopular to findViewById(R.id.indPopular),
            navDubbed to findViewById(R.id.indDubbed),
            navSeries to findViewById(R.id.indSeries),
            navSingle to findViewById(R.id.indSingle),
            navAnime to findViewById(R.id.indAnime),
            navTvShows to findViewById(R.id.indTvShows)
        )

        homeContainer = findViewById(R.id.homeContainer)
        ivHeroBackdrop = findViewById(R.id.ivHeroBackdrop)
        tvHeroTag = findViewById(R.id.tvHeroTag)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroMeta = findViewById(R.id.tvHeroMeta)
        tvHeroDesc = findViewById(R.id.tvHeroDesc)
        btnHeroPlay = findViewById(R.id.btnHeroPlay)
        btnHeroInfo = findViewById(R.id.btnHeroInfo)
        rvHomeSections = findViewById(R.id.rvHomeSections)
        homeProgressBar = findViewById(R.id.homeProgressBar)

        browseContainer = findViewById(R.id.browseContainer)
        tvBrowseTitle = findViewById(R.id.tvBrowseTitle)
        tvBrowseStatus = findViewById(R.id.tvBrowseStatus)
        tvBrowseFocusedTitle = findViewById(R.id.tvBrowseFocusedTitle)
        btnFilterCountry = findViewById(R.id.btnFilterCountry)
        btnFilterGenre = findViewById(R.id.btnFilterGenre)
        btnResetFilter = findViewById(R.id.btnResetFilter)
        searchBoxContainer = findViewById(R.id.searchBoxContainer)
        etSearch = findViewById(R.id.etSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        rvBrowseGrid = findViewById(R.id.rvBrowseGrid)
        browseProgressBar = findViewById(R.id.browseProgressBar)
    }

    private fun setupLeftNavRail() {
        val navFocusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                setNavRailExpanded(true)
            } else {
                navRail.post {
                    if (!navRail.hasFocus()) {
                        setNavRailExpanded(false)
                    }
                }
            }
        }
        allNavItems.forEach { it.onFocusChangeListener = navFocusListener }

        navHome.setOnClickListener {
            hideKeyboard()
            setActiveNav(navHome)
            currentMode = "home"
            homeContainer.visibility = View.VISIBLE
            browseContainer.visibility = View.GONE
            if (homeSectionAdapter.itemCount == 0) {
                loadHomeSwimlanes()
            } else {
                refreshContinueWatchingRow()
            }
            rvHomeSections.requestFocus()
        }

        navSearch.setOnClickListener {
            setActiveNav(navSearch)
            openBrowseMode("search", "Tìm kiếm")
            etSearch.requestFocus()
            showKeyboard(etSearch)
        }

        navPopular.setOnClickListener { hideKeyboard(); setActiveNav(navPopular); openBrowseMode("popular", "Phim Xem Nhiều") }
        navDubbed.setOnClickListener { hideKeyboard(); setActiveNav(navDubbed); openBrowseMode("thuyet-minh", "Phim Thuyết Minh") }
        navSeries.setOnClickListener { hideKeyboard(); setActiveNav(navSeries); openBrowseMode("phim-bo", "Phim Bộ") }
        navSingle.setOnClickListener { hideKeyboard(); setActiveNav(navSingle); openBrowseMode("phim-le", "Phim Lẻ") }
        navAnime.setOnClickListener { hideKeyboard(); setActiveNav(navAnime); openBrowseMode("hoat-hinh", "Hoạt Hình") }
        navTvShows.setOnClickListener { hideKeyboard(); setActiveNav(navTvShows); openBrowseMode("tv-shows", "TV Shows") }

        navSource.setOnClickListener {
            currentSource = currentSource.next()
            lblSource.text = "Nguồn: ${currentSource.label}"
            Toast.makeText(this, "Đã chuyển sang nguồn: ${currentSource.label}", Toast.LENGTH_SHORT).show()
            if (currentMode == "home") {
                loadHomeSwimlanes()
            } else {
                reloadBrowseView()
            }
        }
    }

    private fun setActiveNav(activeItem: LinearLayout) {
        allNavIndicators.forEach { (item, ind) ->
            val isAct = (item == activeItem)
            item.isSelected = isAct
            ind.visibility = if (isAct) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun setNavRailExpanded(expand: Boolean) {
        if (railExpanded == expand) return
        railExpanded = expand
        val density = resources.displayMetrics.density
        val startW = navRail.layoutParams.width
        val endW = ((if (expand) 205 else 64) * density).toInt()

        railAnimator?.cancel()
        railAnimator = ValueAnimator.ofInt(startW, endW).apply {
            duration = 170L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val lp = navRail.layoutParams
                lp.width = anim.animatedValue as Int
                navRail.layoutParams = lp
            }
            start()
        }

        tvBrandMark.text = if (expand) "VIETMOVIE" else "V"
        allNavLabels.forEach { it.visibility = if (expand) View.VISIBLE else View.GONE }
    }

    private fun setupHomeViews() {
        FocusEffects.attachPopEffect(btnHeroPlay, scaleUp = 1.07f, elevationPx = 14f)
        attachSecondaryFocusColor(btnHeroInfo)

        btnHeroPlay.setOnClickListener {
            focusedHeroMovie?.let { openMovieDetail(it) }
        }
        btnHeroInfo.setOnClickListener {
            focusedHeroMovie?.let { openMovieDetail(it) }
        }

        homeSectionAdapter = HomeSectionAdapter(
            onMovieClick = { movie -> openMovieDetail(movie) },
            onMovieFocused = { movie -> updateHeroSpotlight(movie) }
        )

        rvHomeSections.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        rvHomeSections.adapter = homeSectionAdapter
    }

    private fun attachSecondaryFocusColor(btn: Button) {
        btn.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            val b = v as Button
            if (hasFocus) {
                b.setTextColor(0xFF000000.toInt())
                v.elevation = 12f
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(150L).start()
            } else {
                b.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                v.elevation = 0f
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120L).start()
            }
        }
    }

    private var cachedHomeSections: List<HomeSection> = emptyList()

    private fun loadHomeSwimlanes() {
        loadJob?.cancel()
        homeProgressBar.visibility = View.VISIBLE

        loadJob = lifecycleScope.launch {
            try {
                val recent = historyManager.getRecentMovies()
                val dubbedDef = async { MultiSourceRepository.getMovies(currentSource, "thuyet-minh", 1) }
                val popularDef = async { MultiSourceRepository.getMovies(currentSource, "popular", 1) }
                val seriesDef = async { MultiSourceRepository.getMovies(currentSource, "phim-bo", 1) }
                val singleDef = async { MultiSourceRepository.getMovies(currentSource, "phim-le", 1) }
                val animeDef = async { MultiSourceRepository.getMovies(currentSource, "hoat-hinh", 1) }

                val dubbed = dubbedDef.await()
                val popular = popularDef.await()
                val series = seriesDef.await()
                val single = singleDef.await()
                val anime = animeDef.await()

                val baseSections = mutableListOf<HomeSection>()
                if (dubbed.isNotEmpty()) baseSections.add(HomeSection("Thuyết Minh tuyển chọn", dubbed))
                if (popular.isNotEmpty()) baseSections.add(HomeSection("Phim Xem Nhiều Nhất", popular))
                if (series.isNotEmpty()) baseSections.add(HomeSection("Phim Bộ mới cập nhật", series))
                if (single.isNotEmpty()) baseSections.add(HomeSection("Phim Lẻ chiếu rạp", single))
                if (anime.isNotEmpty()) baseSections.add(HomeSection("Hoạt Hình nổi bật", anime))

                cachedHomeSections = baseSections

                val finalSections = mutableListOf<HomeSection>()
                if (recent.isNotEmpty()) finalSections.add(HomeSection("Đang xem dở", recent))
                finalSections.addAll(baseSections)

                homeSectionAdapter.submitSections(finalSections)

                val firstHero = recent.firstOrNull()
                    ?: dubbed.firstOrNull()
                    ?: popular.firstOrNull()
                    ?: series.firstOrNull()
                if (firstHero != null) {
                    updateHeroSpotlight(firstHero, immediate = true)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Lỗi tải trang chủ: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                homeProgressBar.visibility = View.GONE
            }
        }
    }

    private fun refreshContinueWatchingRow() {
        val recent = historyManager.getRecentMovies()
        val combined = mutableListOf<HomeSection>()
        if (recent.isNotEmpty()) combined.add(HomeSection("Đang xem dở", recent))
        combined.addAll(cachedHomeSections)
        if (combined.isNotEmpty()) {
            homeSectionAdapter.submitSections(combined)
        }
    }

    private fun updateHeroSpotlight(movie: MovieItem, immediate: Boolean = false) {
        focusedHeroMovie = movie
        heroUpdateJob?.cancel()
        heroUpdateJob = lifecycleScope.launch {
            if (!immediate) delay(160L)
            val dubLabel = when (movie.getDubShortTag()) {
                "TM" -> "THUYẾT MINH THẬT"
                "LT" -> "LỒNG TIẾNG THẬT"
                else -> movie.sourceLabel.uppercase().ifBlank { "VIETMOVIE" }
            }
            tvHeroTag.text = "VIETMOVIE  •  $dubLabel"
            tvHeroTitle.text = movie.name

            val ratingStr = if (movie.tmdbRating > 0.0) String.format(Locale.US, "★ %.1f", movie.tmdbRating) else ""
            tvHeroMeta.text = listOf(movie.year, movie.episodeCurrent, movie.quality, ratingStr)
                .filter { it.isNotBlank() }
                .joinToString("   •   ")

            tvHeroDesc.text = listOf(movie.originName, movie.lang)
                .filter { it.isNotBlank() }
                .joinToString("  —  ")
                .ifBlank { "Bấm Xem ngay để mở trình phát chất lượng cao." }

            val bgUrl = movie.thumbUrl.ifBlank { movie.posterUrl }
            if (bgUrl.isNotBlank() && bgUrl != lastHeroBackdropUrl) {
                lastHeroBackdropUrl = bgUrl
                ivHeroBackdrop.load(bgUrl) {
                    crossfade(380)
                    size(1280, 720)
                }
            }
        }
    }

    private fun setupBrowseViews() {
        attachSecondaryFocusColor(btnFilterCountry)
        attachSecondaryFocusColor(btnFilterGenre)
        attachSecondaryFocusColor(btnResetFilter)
        attachSecondaryFocusColor(btnClearSearch)

        browseAdapter = MovieAdapter(
            onMovieClick = { movie -> openMovieDetail(movie) },
            onMovieFocused = { movie ->
                val ratingStr = if (movie.tmdbRating > 0.0) String.format(Locale.US, " • ★ %.1f", movie.tmdbRating) else ""
                val extra = listOf(movie.year, movie.episodeCurrent, movie.lang)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                tvBrowseFocusedTitle.text = "${movie.name}  —  $extra$ratingStr"
                tvBrowseFocusedTitle.visibility = View.VISIBLE
            },
            onLoadMore = {
                if (!isLoading && hasMore && currentMode != "search") {
                    loadBrowsePage(currentMode, currentPage + 1, append = true)
                }
            }
        )

        rvBrowseGrid.layoutManager = GridLayoutManager(this, 6)
        rvBrowseGrid.setHasFixedSize(true)
        rvBrowseGrid.setItemViewCacheSize(24)
        rvBrowseGrid.adapter = browseAdapter

        btnFilterCountry.setOnClickListener { showCountryFilterDialog() }
        btnFilterGenre.setOnClickListener { showGenreFilterDialog() }
        btnResetFilter.setOnClickListener {
            selectedCountry = MovieFilters.countries[0]
            selectedGenre = MovieFilters.genres[0]
            updateFilterButtons()
            reloadBrowseView()
        }

        btnClearSearch.setOnClickListener {
            etSearch.setText("")
            lastSearchKeyword = ""
            etSearch.requestFocus()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                searchDebounceJob?.cancel()
                if (q.isEmpty()) {
                    lastSearchKeyword = ""
                    tvBrowseStatus.text = "Gõ tên phim để tự động hiện kết quả..."
                    return
                }
                if (q.length < 2) return
                searchDebounceJob = lifecycleScope.launch {
                    delay(350L)
                    performSearch(q)
                }
            }
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val q = etSearch.text.toString().trim()
                if (q.isNotEmpty()) {
                    searchDebounceJob?.cancel()
                    performSearch(q)
                    hideKeyboard()
                    rvBrowseGrid.requestFocus()
                }
                true
            } else false
        }

        updateFilterButtons()
    }

    private fun openBrowseMode(mode: String, label: String) {
        currentMode = mode
        currentLabel = label
        currentPage = 1
        hasMore = (mode != "search")
        homeContainer.visibility = View.GONE
        browseContainer.visibility = View.VISIBLE
        tvBrowseTitle.text = label
        tvBrowseFocusedTitle.visibility = View.GONE
        searchBoxContainer.visibility = if (mode == "search") View.VISIBLE else View.GONE

        if (mode == "search") {
            if (lastSearchKeyword.isNotBlank()) {
                performSearch(lastSearchKeyword)
            } else {
                browseAdapter.setMovies(emptyList())
                tvBrowseStatus.text = "Gõ tên phim để tự động hiện kết quả..."
            }
        } else {
            loadBrowsePage(mode, 1, append = false)
            rvBrowseGrid.requestFocus()
        }
    }

    private fun reloadBrowseView() {
        if (currentMode == "search") {
            if (lastSearchKeyword.isNotBlank()) performSearch(lastSearchKeyword)
        } else {
            currentPage = 1
            hasMore = true
            loadBrowsePage(currentMode, 1, append = false)
        }
    }

    private fun loadBrowsePage(mode: String, page: Int, append: Boolean) {
        if (!append) loadJob?.cancel()
        isLoading = true
        val filterStr = buildFilterSummary()
        if (!append) {
            browseProgressBar.visibility = View.VISIBLE
            tvBrowseStatus.text = "Đang tải$filterStr..."
        }

        loadJob = lifecycleScope.launch {
            try {
                val list = MultiSourceRepository.getMovies(
                    source = currentSource,
                    mode = mode,
                    page = page,
                    country = selectedCountry.slug,
                    genre = selectedGenre.slug
                )
                if (list.isEmpty()) hasMore = false
                if (append) {
                    browseAdapter.appendMovies(list)
                } else {
                    browseAdapter.setMovies(list)
                    rvBrowseGrid.scrollToPosition(0)
                }
                currentPage = page
                val srcLabel = if (currentSource == MovieSource.AUTO) "Đa nguồn (ưu tiên TM)" else currentSource.label
                tvBrowseStatus.text = if (!append && list.isEmpty()) {
                    "Không có phim phù hợp$filterStr"
                } else {
                    "$srcLabel$filterStr"
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Lỗi tải dữ liệu: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
                browseProgressBar.visibility = View.GONE
            }
        }
    }

    private fun performSearch(keyword: String) {
        loadJob?.cancel()
        lastSearchKeyword = keyword
        isLoading = true
        browseProgressBar.visibility = View.VISIBLE
        val filterStr = buildFilterSummary()
        tvBrowseStatus.text = "Đang tìm '$keyword'$filterStr..."

        loadJob = lifecycleScope.launch {
            try {
                val list = MultiSourceRepository.searchMovies(
                    source = currentSource,
                    keyword = keyword,
                    country = selectedCountry.slug,
                    genre = selectedGenre.slug
                )
                browseAdapter.setMovies(list)
                rvBrowseGrid.scrollToPosition(0)
                tvBrowseStatus.text = if (list.isEmpty()) {
                    "Không thấy phim nào cho '$keyword'$filterStr"
                } else {
                    "Tìm thấy ${list.size} phim cho '$keyword'$filterStr"
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Lỗi tìm kiếm: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
                browseProgressBar.visibility = View.GONE
            }
        }
    }

    private fun showCountryFilterDialog() {
        val items = MovieFilters.countries.map { it.label }.toTypedArray()
        val checked = MovieFilters.countries.indexOfFirst { it.slug == selectedCountry.slug }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Chọn Quốc gia")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                selectedCountry = MovieFilters.countries[which]
                updateFilterButtons()
                dialog.dismiss()
                reloadBrowseView()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showGenreFilterDialog() {
        val items = MovieFilters.genres.map { it.label }.toTypedArray()
        val checked = MovieFilters.genres.indexOfFirst { it.slug == selectedGenre.slug }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Chọn Thể loại")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                selectedGenre = MovieFilters.genres[which]
                updateFilterButtons()
                dialog.dismiss()
                reloadBrowseView()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun updateFilterButtons() {
        val hasC = selectedCountry.slug.isNotBlank()
        val hasG = selectedGenre.slug.isNotBlank()
        btnFilterCountry.text = if (hasC) "Quốc gia: ${selectedCountry.label} ▾" else "Quốc gia: Tất cả ▾"
        btnFilterGenre.text = if (hasG) "Thể loại: ${selectedGenre.label} ▾" else "Thể loại: Tất cả ▾"
        btnFilterCountry.isSelected = hasC
        btnFilterGenre.isSelected = hasG
        btnResetFilter.visibility = if (hasC || hasG) View.VISIBLE else View.GONE
    }

    private fun buildFilterSummary(): String {
        val parts = mutableListOf<String>()
        if (selectedCountry.slug.isNotBlank()) parts.add(selectedCountry.label)
        if (selectedGenre.slug.isNotBlank()) parts.add(selectedGenre.label)
        return if (parts.isEmpty()) "" else " • ${parts.joinToString(" / ")}"
    }

    private fun openMovieDetail(movie: MovieItem) {
        if (movie.slug.isBlank()) return
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("slug", movie.slug)
            putExtra("title", movie.name)
            putExtra("source", movie.sourceLabel)
        }
        startActivity(intent)
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }
}
