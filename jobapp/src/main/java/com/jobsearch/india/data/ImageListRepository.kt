package com.jobsearch.india.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Fetches the hosted comma-separated list of job-post image URLs (joburls.txt) and caches the
 *  last successful result on-device so the gallery still has content with no network connection. */
class ImageListRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("job_gallery", Context.MODE_PRIVATE)

    var sourceUrl: String
        get() = prefs.getString(KEY_SOURCE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SOURCE_URL, value).apply()

    fun cachedImageUrls(): List<String> = parse(prefs.getString(KEY_CACHED_LIST, "") ?: "")

    suspend fun fetchImageUrls(url: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                requestMethod = "GET"
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val urls = parse(text)
            prefs.edit().putString(KEY_CACHED_LIST, text).apply()
            Result.success(urls)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parse(text: String): List<String> =
        text.split(",", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    companion object {
        private const val KEY_SOURCE_URL = "source_url"
        private const val KEY_CACHED_LIST = "cached_list"
    }
}
