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
import com.vietmovietv.app.data.MovieApiService
import com.vietmovietv.app.ui.MovieAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var rvMovies: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var adapter: MovieAdapter

    private lateinit var tabNew: Button
    private lateinit var tabSeries: Button
    private lateinit var tabSingle: Button
    private lateinit var tabAnime: Button
    private lateinit var tabTvShows: Button
    private lateinit var btnSearch: Button

    private var currentMode = "new" // new, phim-bo, phim-le, hoat-hinh, tv-shows, search
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
        tabSeries = findViewById(R.id.tabSeries)
        tabSingle = findViewById(R.id.tabSingle)
        tabAnime = findViewById(R.id.tabAnime)
        tabTvShows = findViewById(R.id.tabTvShows)
        btnSearch = findViewById(R.id.btnSearch)

        adapter = MovieAdapter(
            onMovieClick = { movie ->
                val slug = movie.slug
                if (!slug.isNullOrBlank()) {
                    val intent = Intent(this, DetailActivity::class.java)
                    intent.putExtra("slug", slug)
                    intent.putExtra("title", movie.name ?: "")
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
        tabSeries.setOnClickListener { selectCategory("phim-bo", "Phim Bộ", tabSeries) }
        tabSingle.setOnClickListener { selectCategory("phim-le", "Phim Lẻ", tabSingle) }
        tabAnime.setOnClickListener { selectCategory("hoat-hinh", "Hoạt Hình", tabAnime) }
        tabTvShows.setOnClickListener { selectCategory("tv-shows", "TV Shows", tabTvShows) }
        btnSearch.setOnClickListener { showSearchDialog() }

        selectCategory("new", "Phim mới cập nhật", tabNew)
        tabNew.requestFocus()
    }

    private fun updateSelectedTab(selectedButton: Button) {
        val tabs = listOf(tabNew, tabSeries, tabSingle, tabAnime, tabTvShows, btnSearch)
        tabs.forEach { it.isSelected = (it == selectedButton) }
    }

    private fun selectCategory(mode: String, label: String, button: Button) {
        updateSelectedTab(button)
        currentMode = mode
        currentPage = 1
        hasMore = true
        tvStatus.text = label
        loadCategory(mode, 1, append = false)
    }

    private fun loadCategory(mode: String, page: Int, append: Boolean) {
        isLoading = true
        if (!append) progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                if (mode == "new") {
                    val res = MovieApiService.instance.getNewMovies(page)
                    val list = res.items ?: emptyList()
                    if (list.isEmpty()) hasMore = false
                    if (append) {
                        adapter.appendMovies(list)
                    } else {
                        adapter.setMovies(list)
                        rvMovies.scrollToPosition(0)
                    }
                } else {
                    val res = MovieApiService.instance.getMoviesByType(mode, page)
                    val cdn = res.data?.cdnImage ?: "https://phimimg.com"
                    val list = res.data?.items ?: emptyList()
                    if (list.isEmpty()) hasMore = false
                    if (append) {
                        adapter.appendMovies(list, cdn)
                    } else {
                        adapter.setMovies(list, cdn)
                        rvMovies.scrollToPosition(0)
                    }
                }
                currentPage = page
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Lỗi kết nối API: ${e.message}", Toast.LENGTH_LONG).show()
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
            .setTitle("Tìm kiếm phim")
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
        hasMore = false
        isLoading = true
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Kết quả tìm kiếm: "$keyword""

        lifecycleScope.launch {
            try {
                val res = MovieApiService.instance.searchMovies(keyword)
                val cdn = res.data?.cdnImage ?: "https://phimimg.com"
                val list = res.data?.items ?: emptyList()
                adapter.setMovies(list, cdn)
                rvMovies.scrollToPosition(0)
                if (list.isEmpty()) {
                    tvStatus.text = "Không tìm thấy phim nào cho "$keyword""
                } else {
                    tvStatus.text = "Tìm thấy ${list.size} phim cho "$keyword""
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
