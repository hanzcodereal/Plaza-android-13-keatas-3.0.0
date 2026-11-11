package com.decio.plaza

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Perubahan langganan akun (dari halaman channel, pemutar, atau Shorts). LibraryData mendengarkan
 * ini supaya tab Langganan langsung ikut: channel yang dihentikan hilang dari daftar dan videonya
 * dari feed; lonceng channel itu juga dimatikan.
 */
object SubscriptionSync {
    var onChanged: ((channelId: String, subscribed: Boolean) -> Unit)? = null

    fun changed(channelId: String, subscribed: Boolean) {
        if (!subscribed) NewVideoAlerts.disable(channelId)
        onChanged?.invoke(channelId, subscribed)
    }
}

/** Satu video dari RSS channel. [published] dalam milidetik epoch. */
private data class FeedEntry(val videoId: String, val title: String, val author: String, val channelId: String, val published: Long)

/**
 * Lonceng: notifikasi saat channel yang dilanggan mengunggah video baru. Channel yang loncengnya
 * hidup disimpan di perangkat; WorkManager memeriksa RSS publik tiap channel (sekitar tiap 15
 * menit, Android boleh menundanya saat hemat daya) dan memberi tahu untuk video yang terbit setelah
 * lonceng dinyalakan. Tidak butuh akun maupun server.
 */
object NewVideoAlerts {
    const val ACTION_OPEN = "com.decio.plaza.OPEN_VIDEO"
    const val EXTRA_ID = "video_id"
    const val EXTRA_TITLE = "video_title"
    const val EXTRA_CHANNEL = "video_channel"
    const val EXTRA_CHANNEL_ID = "video_channel_id"

    private const val PREFS = "new_video_alerts"
    private const val KEY_IDS = "channels"
    private const val CHANNEL = "new_videos"
    private const val WORK = "new-video-check"
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    /** Channel yang loncengnya hidup. */
    var enabled by mutableStateOf<Set<String>>(emptySet())
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()
        // Selalu terjadwal: notifikasi datang tanpa aplikasi dibuka (langganan akun + lonceng channel).
        schedule(app)
    }

    /** Menyalakan/mematikan lonceng; true kalau sekarang hidup. */
    fun toggle(channelId: String): Boolean {
        val p = prefs ?: return false
        if (channelId in enabled) {
            disable(channelId)
            return false
        }
        enabled = enabled + channelId
        // Hanya video yang terbit setelah lonceng dinyalakan yang diberitahukan.
        p.edit().putStringSet(KEY_IDS, enabled).putLong("seen_$channelId", System.currentTimeMillis()).apply()
        appContext?.let { schedule(it) }
        return true
    }

    fun disable(channelId: String) {
        val p = prefs ?: return
        if (channelId !in enabled) return
        enabled = enabled - channelId
        p.edit().putStringSet(KEY_IDS, enabled).remove("seen_$channelId").apply()
    }

    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NewVideoWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Satu putaran pemeriksaan semua channel berlonceng. */
    suspend fun check(context: Context) = withContext(Dispatchers.IO) {
        val p = prefs ?: return@withContext
        for (id in p.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()) {
            val seen = p.getLong("seen_$id", System.currentTimeMillis())
            val entries = fetch(id) ?: continue
            val fresh = entries.filter { it.published > seen }.sortedBy { it.published }
            if (fresh.isEmpty()) continue
            p.edit().putLong("seen_$id", fresh.maxOf { it.published }).apply()
            fresh.takeLast(3).forEach { notify(context, it) }
        }
        checkSubscriptions(context)
    }

    private const val KEY_SUB_SEEN = "sub_seen"
    private val RECENT = Regex("(?i)\\b(second|minute|hour|detik|menit|jam|just now|baru saja)")

    /**
     * Masuk dengan akun: satu permintaan ke feed Langganan memberi tahu video baru dan siaran
     * langsung dari semua channel yang diikuti, tanpa aplikasi dibuka. Putaran pertama hanya
     * mencatat apa yang sudah ada.
     */
    private suspend fun checkSubscriptions(context: Context) {
        val p = prefs ?: return
        if (!Account.signedIn) return
        val page = runCatching { InnerTube.subscriptionsFeed() }.getOrNull() ?: return
        val ids = page.videos.map { it.id }
        val seen = p.getStringSet(KEY_SUB_SEEN, null)
        if (seen == null) {
            p.edit().putStringSet(KEY_SUB_SEEN, ids.toSet()).apply()
            return
        }
        val fresh = page.videos.filter { it.id !in seen && (it.live || (it.uploaded != null && RECENT.containsMatchIn(it.uploaded))) }
        p.edit().putStringSet(KEY_SUB_SEEN, (ids + seen).take(400).toSet()).apply()
        fresh.take(3).forEach { v ->
            show(context, v.id, v.title, v.channel, v.channelUrl?.substringAfterLast('/'), v.live)
        }
    }

    private val entryTag = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)

    private fun field(xml: String, tag: String): String? =
        Regex("<" + tag + ">([^<]*)</" + tag + ">").find(xml)?.groupValues?.get(1)?.trim()

    private fun unescape(s: String) =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    private fun fetch(channelId: String): List<FeedEntry>? {
        val request = okhttp3.Request.Builder()
            .url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
            .header("User-Agent", Net.USER_AGENT)
            .build()
        val body: String? = try {
            Net.client.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
        } catch (e: Exception) {
            null
        }
        if (body == null) return null
        val out = ArrayList<FeedEntry>()
        for (m in entryTag.findAll(body)) {
            val xml = m.groupValues[1]
            val videoId = field(xml, "yt:videoId") ?: continue
            val title = field(xml, "title")?.let(::unescape) ?: continue
            val published = try {
                java.time.OffsetDateTime.parse(field(xml, "published") ?: continue).toInstant().toEpochMilli()
            } catch (e: Exception) {
                continue
            }
            val author = field(xml, "name")?.let(::unescape).orEmpty()
            out += FeedEntry(videoId, title, author, channelId, published)
        }
        return out
    }

    private fun notify(context: Context, e: FeedEntry) = show(context, e.videoId, e.title, e.author, e.channelId, false)

    private fun thumbnail(videoId: String): Bitmap? = try {
        val request = okhttp3.Request.Builder()
            .url("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
            .header("User-Agent", Net.USER_AGENT)
            .build()
        Net.client.newCall(request).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
        }
    } catch (e: Exception) {
        null
    }

    private fun show(context: Context, videoId: String, title: String, author: String, channelId: String?, live: Boolean) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("New videos"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN
            putExtra(EXTRA_ID, videoId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_CHANNEL, author)
            putExtra(EXTRA_CHANNEL_ID, channelId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, videoId.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (live) tr("Live now") + ": " + title else title
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(author.ifBlank { "Plaza" })
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
        val picture = thumbnail(videoId)
        if (picture != null) builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(text))
        else builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        manager.notify(videoId.hashCode(), builder.build())
    }
}

class NewVideoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        NewVideoAlerts.check(applicationContext)
        return Result.success()
    }
}

/** Video yang dibuka dari notifikasi; PlazaApp memutarnya lalu mengosongkannya. */
object OpenRequest {
    var item by mutableStateOf<VideoItem?>(null)

    fun from(intent: Intent?) {
        if (intent?.action != NewVideoAlerts.ACTION_OPEN) return
        val id = intent.getStringExtra(NewVideoAlerts.EXTRA_ID) ?: return
        val channelId = intent.getStringExtra(NewVideoAlerts.EXTRA_CHANNEL_ID)
        item = VideoItem(
            id = id,
            title = intent.getStringExtra(NewVideoAlerts.EXTRA_TITLE).orEmpty(),
            channel = intent.getStringExtra(NewVideoAlerts.EXTRA_CHANNEL).orEmpty(),
            channelUrl = channelId?.let { "https://www.youtube.com/channel/$it" },
            thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            avatar = null,
            durationSec = 0,
            views = 0,
            uploaded = null,
        )
    }
}

/** Tombol lonceng di samping Langganan; hanya tampil untuk channel yang sudah dilanggan. */
@Composable
fun BellButton(channelId: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    if (channelId == null || !channelId.startsWith("UC")) return
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val on = channelId in NewVideoAlerts.enabled
    Box(
        modifier
            .size(size)
            .pressable {
                val nowOn = NewVideoAlerts.toggle(channelId)
                Toast.makeText(context, if (nowOn) tr("New-video alerts on") else tr("New-video alerts off"), Toast.LENGTH_SHORT).show()
            }
            .clip(CircleShape)
            .background(if (on) c.primaryContainer else c.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (on) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
            tr("New-video alerts"),
            tint = if (on) c.onPrimaryContainer else c.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}
