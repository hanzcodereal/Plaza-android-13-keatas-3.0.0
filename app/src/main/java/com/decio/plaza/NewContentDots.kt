package com.decio.plaza

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Titik hijau di foto profil channel pada tab Langganan saat ada video baru, seperti di YouTube.
 * "Baru" = video terbaru channel itu di feed Langganan bukan yang terakhir dilihat. Dilihat = foto
 * channel diketuk. Pada pemakaian pertama isi feed yang ada dianggap sudah dilihat, jadi titik
 * hanya muncul untuk unggahan setelah itu.
 */
object NewContentDots {
    private var prefs: SharedPreferences? = null
    private val seen = mutableStateMapOf<String, String>()
    private var baselined by mutableStateOf(false)

    fun init(context: Context) {
        val p = context.getSharedPreferences("new_content_dots", Context.MODE_PRIVATE)
        prefs = p
        baselined = p.getBoolean("baselined", false)
        p.all.forEach { (key, value) -> if (value is String) seen[key] = value }
    }

    private fun channelOf(video: VideoItem): String? = video.channelUrl?.substringAfter("/channel/", "")?.ifBlank { null }

    /** The newest video of [channelId] in the feed (the feed lists newest first). */
    fun newestIn(videos: List<VideoItem>, channelId: String): String? =
        videos.firstOrNull { channelOf(it) == channelId }?.id

    fun hasNew(videos: List<VideoItem>, channelId: String): Boolean {
        if (!baselined) return false
        val newest = newestIn(videos, channelId) ?: return false
        return seen[channelId] != newest
    }

    fun markSeen(videos: List<VideoItem>, channelId: String) {
        val newest = newestIn(videos, channelId) ?: return
        seen[channelId] = newest
        prefs?.edit()?.putString(channelId, newest)?.apply()
    }

    /** First feed ever loaded: everything in it counts as seen. */
    fun baseline(videos: List<VideoItem>) {
        if (baselined || videos.isEmpty()) return
        val edit = prefs?.edit()
        for (id in videos.mapNotNull(::channelOf).distinct()) {
            val newest = newestIn(videos, id) ?: continue
            seen[id] = newest
            edit?.putString(id, newest)
        }
        edit?.putBoolean("baselined", true)?.apply()
        baselined = true
    }
}
