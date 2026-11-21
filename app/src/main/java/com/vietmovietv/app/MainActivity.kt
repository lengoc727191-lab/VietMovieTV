package com.vietmovietv.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vietmovietv.app.data.MovieSource
import com.vietmovietv.app.data.MultiSourceRepository
import com.vietmovietv.app.ui.MovieAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

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

    private var currentSource: MovieSource = MovieSource.AUTO
    private var currentMode = "new"
    private var currentLabel = "Phim mới cập nhật"
    private var lastSearchKeyword = ""
    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
            onLoadMore = {
                if (!isLoading && hasMore && currentMode != "search") {
                    loadCategory(currentMode, currentPage + 1, append = true)
                }
            }
        )

        rvMovies.layoutManager = GridLayoutManager(this, 5)
        rvMovies.adapter = adapter

        tabNew.setOnClickListener { selectCategory("new", "Phim mới cập nhật", tabNew) }
        tabPopular.setOnClickListener { selectCategory("popular", "Phim xem nhiều nhất", tabPopular) }
        tabDubbed.setOnClickListener { selectCategory("thuyet-minh", "Phim Thuyết Minh / Lồng Tiếng", tabDubbed) }
        tabSeries.setOnClickListener { selectCategory("phim-bo", "Phim Bộ", tabSeries) }
        tabSingle.setOnClickListener { selectCategory("phim-le", "Phim Lẻ", tabSingle) }
        tabAnime.setOnClickListener { selectCategory("hoat-hinh", "Hoạt Hình", tabAnime) }
        tabTvShows.setOnClickListener { selectCategory("tv-shows", "TV Shows", tabTvShows) }
        btnSearch.setOnClickListener { showSearchDialog() }

        btnSource.setOnClickListener {
            currentSource = currentSource.next()
            btnSource.text = "Nguồn: ${currentSource.label}"
            Toast.makeText(this, "Đã chuyển sang nguồn: ${currentSource.label}", Toast.LENGTH_SHORT).show()
            if (currentMode == "search" && lastSearchKeyword.isNotBlank()) {
                performSearch(lastSearchKeyword)
            } else {
                currentPage = 1
                hasMore = true
                loadCategory(currentMode, 1, append = false)
            }
        }

        selectCategory("new", "Phim mới cập nhật", tabNew)
        tabNew.requestFocus()
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

    private fun loadCategory(mode: String, page: Int, append: Boolean) {
        isLoading = true
        if (!append) {
            progressBar.visibility = View.VISIBLE
            tvStatus.text = "$currentLabel  •  Đang tải (${currentSource.label})..."
        }

        lifecycleScope.launch {
            try {
                val list = MultiSourceRepository.getMovies(currentSource, mode, page)
                if (list.isEmpty()) {
                    hasMore = false
                }
                if (append) {
                    adapter.appendMovies(list)
                } else {
                    adapter.setMovies(list)
                    rvMovies.scrollToPosition(0)
                }
                currentPage = page
                val srcDesc = if (currentSource == MovieSource.AUTO) {
                    "Tự động gộp KKPhim + OPhim + NguonC"
                } else {
                    "Nguồn ${currentSource.label}"
                }
                tvStatus.text = "$currentLabel  •  $srcDesc"
                if (!append && list.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Nguồn ${currentSource.label} tạm thời không phản hồi, thử đổi nguồn khác!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Lỗi tải dữ liệu: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = "Nhập tên phim cần tìm..."
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(40, 30, 40, 30)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Tìm kiếm đa nguồn")
            .setView(input)
            .setPositiveButton("Tìm") { _, _ ->
                val keyword = input.text.toString().trim()
                if (keyword.isNotEmpty()) performSearch(keyword)
            }
            .setNegativeButton("Hủy", null)
            .create()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val keyword = input.text.toString().trim()
                if (keyword.isNotEmpty()) {
                    dialog.dismiss()
                    performSearch(keyword)
                }
                true
            } else false
        }

        dialog.show()
        input.requestFocus()
    }

    private fun performSearch(keyword: String) {
        updateSelectedTab(btnSearch)
        currentMode = "search"
        lastSearchKeyword = keyword
        hasMore = false
        isLoading = true
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Đang tìm '$keyword' trên ${currentSource.label}..."

        lifecycleScope.launch {
            try {
                val list = MultiSourceRepository.searchMovies(currentSource, keyword)
                adapter.setMovies(list)
                rvMovies.scrollToPosition(0)
                tvStatus.text = if (list.isEmpty()) {
                    "Không tìm thấy phim nào cho '$keyword' (${currentSource.label})"
                } else {
                    "Tìm thấy ${list.size} phim cho '$keyword' (${currentSource.label})"
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
