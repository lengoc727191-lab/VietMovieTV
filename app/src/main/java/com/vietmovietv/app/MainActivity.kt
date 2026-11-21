package com.vietmovietv.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.vietmovietv.app.data.FilterOption
import com.vietmovietv.app.data.MovieFilters
import com.vietmovietv.app.data.MovieSource
import com.vietmovietv.app.data.MultiSourceRepository
import com.vietmovietv.app.ui.FocusEffects
import com.vietmovietv.app.ui.MovieAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var ivHomeBackdrop: ImageView
    private lateinit var rvMovies: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var adapter: MovieAdapter

    private lateinit var tabNew: Button
    private lateinit var tabPopular: Button
    private lateinit var tabDubbed: Button
    private lateinit var tabSeries: Button
    private lateinit var tabSingle: Button
    private lateinit var tabAnime: Button
    private lateinit var tabTvShows: Button
    private lateinit var btnSource: Button
    private lateinit var btnSearch: Button

    private lateinit var searchBarContainer: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var btnCloseSearch: Button

    private lateinit var btnFilterCountry: Button
    private lateinit var btnFilterGenre: Button
    private lateinit var btnResetFilter: Button

    private var currentSource: MovieSource = MovieSource.AUTO
    private var currentMode = "new"
    private var currentLabel = "Phim mới cập nhật"
    private var selectedCountry: FilterOption = MovieFilters.countries[0]
    private var selectedGenre: FilterOption = MovieFilters.genres[0]
    private var lastSearchKeyword = ""
    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true

    private var loadJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var backdropJob: Job? = null
    private var lastBackdropUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ivHomeBackdrop = findViewById(R.id.ivHomeBackdrop)
        rvMovies = findViewById(R.id.rvMovies)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        tabNew = findViewById(R.id.tabNew)
        tabPopular = findViewById(R.id.tabPopular)
        tabDubbed = findViewById(R.id.tabDubbed)
        tabSeries = findViewById(R.id.tabSeries)
        tabSingle = findViewById(R.id.tabSingle)
        tabAnime = findViewById(R.id.tabAnime)
        tabTvShows = findViewById(R.id.tabTvShows)
        btnSource = findViewById(R.id.btnSource)
        btnSearch = findViewById(R.id.btnSearch)

        searchBarContainer = findViewById(R.id.searchBarContainer)
        etSearch = findViewById(R.id.etSearch)
        btnCloseSearch = findViewById(R.id.btnCloseSearch)

        btnFilterCountry = findViewById(R.id.btnFilterCountry)
        btnFilterGenre = findViewById(R.id.btnFilterGenre)
        btnResetFilter = findViewById(R.id.btnResetFilter)

        // Attach smooth Netflix-style elevation & scale pop to EVERY pill button
        listOf(
            tabNew, tabPopular, tabDubbed, tabSeries, tabSingle, tabAnime, tabTvShows,
            btnSource, btnSearch, btnCloseSearch, btnFilterCountry, btnFilterGenre, btnResetFilter
        ).forEach { FocusEffects.attachPopEffect(it, scaleUp = 1.08f, elevationPx = 14f) }

        adapter = MovieAdapter(
            onMovieClick = { movie ->
                if (movie.slug.isNotBlank()) {
                    val intent = Intent(this, DetailActivity::class.java).apply {
                        putExtra("slug", movie.slug)
                        putExtra("title", movie.name)
                        putExtra("source", movie.sourceLabel)
                    }
                    startActivity(intent)
                }
            },
            onMovieFocused = { movie ->
                val bgUrl = movie.thumbUrl.ifBlank { movie.posterUrl }
                updateBackdropSmoothly(bgUrl)
            },
            onLoadMore = {
                if (!isLoading && hasMore && currentMode != "search") {
                    loadCategory(currentMode, currentPage + 1, append = true)
                }
            }
        )

        rvMovies.layoutManager = GridLayoutManager(this, 5)
        rvMovies.setHasFixedSize(true)
        rvMovies.setItemViewCacheSize(20)
        rvMovies.adapter = adapter

        tabNew.setOnClickListener { closeSearchBar(); selectCategory("new", "Phim mới cập nhật", tabNew) }
        tabPopular.setOnClickListener { closeSearchBar(); selectCategory("popular", "Phim xem nhiều nhất", tabPopular) }
        tabDubbed.setOnClickListener { closeSearchBar(); selectCategory("thuyet-minh", "Phim Thuyết Minh", tabDubbed) }
        tabSeries.setOnClickListener { closeSearchBar(); selectCategory("phim-bo", "Phim Bộ", tabSeries) }
        tabSingle.setOnClickListener { closeSearchBar(); selectCategory("phim-le", "Phim Lẻ", tabSingle) }
        tabAnime.setOnClickListener { closeSearchBar(); selectCategory("hoat-hinh", "Hoạt Hình", tabAnime) }
        tabTvShows.setOnClickListener { closeSearchBar(); selectCategory("tv-shows", "TV Shows", tabTvShows) }

        btnSearch.setOnClickListener { toggleSearchBar() }
        btnCloseSearch.setOnClickListener {
            closeSearchBar()
            if (currentMode == "search") {
                selectCategory("new", "Phim mới cập nhật", tabNew)
            }
            tabNew.requestFocus()
        }

        btnSource.setOnClickListener {
            currentSource = currentSource.next()
            btnSource.text = "Nguồn: ${currentSource.label}"
            Toast.makeText(this, "Đã chuyển sang nguồn: ${currentSource.label}", Toast.LENGTH_SHORT).show()
            reloadCurrentView()
        }

        btnFilterCountry.setOnClickListener { showCountryFilterDialog() }
        btnFilterGenre.setOnClickListener { showGenreFilterDialog() }
        btnResetFilter.setOnClickListener {
            selectedCountry = MovieFilters.countries[0]
            selectedGenre = MovieFilters.genres[0]
            updateFilterButtons()
            reloadCurrentView()
        }

        setupInstantSearch()
        updateFilterButtons()
        selectCategory("new", "Phim mới cập nhật", tabNew)
        tabNew.requestFocus()
    }

    private fun updateBackdropSmoothly(url: String) {
        if (url.isBlank() || url == lastBackdropUrl) return
        backdropJob?.cancel()
        backdropJob = lifecycleScope.launch {
            delay(180L)
            lastBackdropUrl = url
            ivHomeBackdrop.load(url) {
                crossfade(450)
                size(960, 540)
            }
        }
    }

    private fun setupInstantSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                searchDebounceJob?.cancel()
                if (query.isEmpty()) {
                    lastSearchKeyword = ""
                    if (currentMode == "search") {
                        tvStatus.text = "Gõ tên phim để tự động hiện kết quả..."
                    }
                    return
                }
                if (query.length < 2) return
                searchDebounceJob = lifecycleScope.launch {
                    delay(350L)
                    performSearch(query)
                }
            }
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = etSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchDebounceJob?.cancel()
                    performSearch(query)
                    hideKeyboard()
                    rvMovies.requestFocus()
                }
                true
            } else false
        }
    }

    private fun toggleSearchBar() {
        if (searchBarContainer.visibility == View.VISIBLE) {
            etSearch.requestFocus()
            showKeyboard(etSearch)
        } else {
            searchBarContainer.visibility = View.VISIBLE
            updateSelectedTab(btnSearch)
            etSearch.requestFocus()
            showKeyboard(etSearch)
        }
    }

    private fun closeSearchBar() {
        if (searchBarContainer.visibility == View.VISIBLE) {
            searchDebounceJob?.cancel()
            hideKeyboard()
            etSearch.setText("")
            lastSearchKeyword = ""
            searchBarContainer.visibility = View.GONE
        }
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun showCountryFilterDialog() {
        val items = MovieFilters.countries.map { it.label }.toTypedArray()
        val checkedIdx = MovieFilters.countries.indexOfFirst { it.slug == selectedCountry.slug }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Chọn Quốc gia")
            .setSingleChoiceItems(items, checkedIdx) { dialog, which ->
                selectedCountry = MovieFilters.countries[which]
                updateFilterButtons()
                dialog.dismiss()
                reloadCurrentView()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showGenreFilterDialog() {
        val items = MovieFilters.genres.map { it.label }.toTypedArray()
        val checkedIdx = MovieFilters.genres.indexOfFirst { it.slug == selectedGenre.slug }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Chọn Thể loại")
            .setSingleChoiceItems(items, checkedIdx) { dialog, which ->
                selectedGenre = MovieFilters.genres[which]
                updateFilterButtons()
                dialog.dismiss()
                reloadCurrentView()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun updateFilterButtons() {
        val hasCountry = selectedCountry.slug.isNotBlank()
        val hasGenre = selectedGenre.slug.isNotBlank()
        btnFilterCountry.text = if (hasCountry) "Quốc gia: ${selectedCountry.label} ▾" else "Quốc gia: Tất cả ▾"
        btnFilterGenre.text = if (hasGenre) "Thể loại: ${selectedGenre.label} ▾" else "Thể loại: Tất cả ▾"
        btnFilterCountry.isSelected = hasCountry
        btnFilterGenre.isSelected = hasGenre
        btnResetFilter.visibility = if (hasCountry || hasGenre) View.VISIBLE else View.GONE
    }

    private fun reloadCurrentView() {
        if (currentMode == "search" && lastSearchKeyword.isNotBlank()) {
            performSearch(lastSearchKeyword)
        } else {
            currentPage = 1
            hasMore = true
            loadCategory(currentMode, 1, append = false)
        }
    }

    private fun updateSelectedTab(selectedButton: Button) {
        val tabs = listOf(tabNew, tabPopular, tabDubbed, tabSeries, tabSingle, tabAnime, tabTvShows, btnSearch)
        tabs.forEach { it.isSelected = (it == selectedButton) }
    }

    private fun selectCategory(mode: String, label: String, button: Button) {
        updateSelectedTab(button)
        currentMode = mode
        currentLabel = label
        currentPage = 1
        hasMore = true
        loadCategory(mode, 1, append = false)
    }

    private fun buildFilterSummary(): String {
        val parts = mutableListOf<String>()
        if (selectedCountry.slug.isNotBlank()) parts.add(selectedCountry.label)
        if (selectedGenre.slug.isNotBlank()) parts.add(selectedGenre.label)
        return if (parts.isEmpty()) "" else " [${parts.joinToString(" • ")}]"
    }

    private fun loadCategory(mode: String, page: Int, append: Boolean) {
        if (!append) loadJob?.cancel()
        isLoading = true
        val filterSummary = buildFilterSummary()
        if (!append) {
            progressBar.visibility = View.VISIBLE
            tvStatus.text = "$currentLabel$filterSummary • Đang tải..."
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
                if (list.isEmpty()) {
                    hasMore = false
                }
                if (append) {
                    adapter.appendMovies(list)
                } else {
                    adapter.setMovies(list)
                    rvMovies.scrollToPosition(0)
                    list.firstOrNull()?.let { first ->
                        updateBackdropSmoothly(first.thumbUrl.ifBlank { first.posterUrl })
                    }
                }
                currentPage = page
                val srcDesc = if (currentSource == MovieSource.AUTO) "Đa nguồn (ưu tiên TM)" else currentSource.label
                tvStatus.text = if (!append && list.isEmpty()) {
                    "Không có phim phù hợp với bộ lọc$filterSummary"
                } else {
                    "$currentLabel$filterSummary • $srcDesc"
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Lỗi tải dữ liệu: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun performSearch(keyword: String) {
        loadJob?.cancel()
        updateSelectedTab(btnSearch)
        currentMode = "search"
        lastSearchKeyword = keyword
        hasMore = false
        isLoading = true
        progressBar.visibility = View.VISIBLE
        val filterSummary = buildFilterSummary()
        tvStatus.text = "Đang tìm '$keyword'$filterSummary..."

        loadJob = lifecycleScope.launch {
            try {
                val list = MultiSourceRepository.searchMovies(
                    source = currentSource,
                    keyword = keyword,
                    country = selectedCountry.slug,
                    genre = selectedGenre.slug
                )
                adapter.setMovies(list)
                rvMovies.scrollToPosition(0)
                list.firstOrNull()?.let { first ->
                    updateBackdropSmoothly(first.thumbUrl.ifBlank { first.posterUrl })
                }
                tvStatus.text = if (list.isEmpty()) {
                    "Không thấy phim nào cho '$keyword'$filterSummary"
                } else {
                    "Tìm thấy ${list.size} phim cho '$keyword'$filterSummary"
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Lỗi tìm kiếm: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
                progressBar.visibility = View.GONE
            }
        }
    }
}
