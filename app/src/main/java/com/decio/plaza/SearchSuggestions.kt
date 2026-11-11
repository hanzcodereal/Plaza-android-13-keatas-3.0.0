package com.decio.plaza

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray

/**
 * Saran pencarian saat mengetik (seperti Moraliys): dari layanan saran YouTube, tanpa akun.
 * Hasilnya daftar kalimat, paling relevan dulu.
 */
object SearchSuggestions {
    private val cache = object : LinkedHashMap<String, List<String>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 40
    }

    suspend fun fetch(query: String): List<String> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        synchronized(cache) { cache[q.lowercase()] }?.let { return@withContext it }
        val url = "https://suggestqueries.google.com/complete/search".toHttpUrl().newBuilder()
            .addQueryParameter("client", "firefox")
            .addQueryParameter("ds", "yt")
            .addQueryParameter("q", q)
            .addQueryParameter("hl", LocaleSettings.language)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()
        val list = Net.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) emptyList()
            else {
                val items = JSONArray(r.body?.string().orEmpty()).optJSONArray(1)
                if (items == null) emptyList()
                else (0 until items.length()).map { items.optString(it) }
                    .filter { it.isNotBlank() && !it.equals(q, ignoreCase = true) }
                    .distinct()
                    .take(8)
            }
        }
        if (list.isNotEmpty()) synchronized(cache) { cache[q.lowercase()] = list }
        list
    }
}
