package com.vietmovietv.app.data

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface MovieApiService {

    @GET("danh-sach/phim-moi-cap-nhat")
    suspend fun getNewMovies(
        @Query("page") page: Int = 1
    ): NewMoviesResponse

    @GET("v1/api/danh-sach/{type}")
    suspend fun getMoviesByType(
        @Path("type") type: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 25
    ): V1ListResponse

    @GET("v1/api/tim-kiem")
    suspend fun searchMovies(
        @Query("keyword") keyword: String,
        @Query("limit") limit: Int = 30
    ): V1ListResponse

    @GET("phim/{slug}")
    suspend fun getMovieDetail(
        @Path("slug") slug: String
    ): MovieDetailResponse

    companion object {
        private const val BASE_URL = "https://phimapi.com/"

        val instance: MovieApiService by lazy {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()

            Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MovieApiService::class.java)
        }
    }
}
