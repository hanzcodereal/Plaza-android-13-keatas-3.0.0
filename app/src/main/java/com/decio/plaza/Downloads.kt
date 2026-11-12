package com.decio.plaza

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class DownloadState { Queued, Downloading, Done, Failed }

/** Satu video yang diunduh: metadata, berkas di penyimpanan aplikasi, dan kemajuannya. */
data class DownloadItem(
    val id: String,
    val title: String,
    val channel: String,
    /** Gambar mini: alamat web selama mengunduh, lalu berkas lokal (file://) agar tampil tanpa internet. */
    val thumbnailUri: String?,
    val durationSec: Long,
    val height: Int,
    val videoPath: String?,
    val audioPath: String?,
    val totalBytes: Long,
    val doneBytes: Long,
    val state: DownloadState,
    val error: String?,
    val addedAt: Long,
) {
    val progress: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Unduhan video untuk ditonton tanpa internet, seperti di YouTube. Video dan audio diunduh sebagai
 * dua berkas di penyimpanan aplikasi (potongan 4 MB, supaya tidak diperlambat YouTube), lalu
 * diputar dari berkas itu kalau perangkat sedang offline.
 */
object Downloads {
    private const val MAX_HEIGHT = 720
    private const val CHUNK = 4L * 1024 * 1024

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private val canceled = ConcurrentHashMap.newKeySet<String>()
    private var lastReport = 0L

    var items by mutableStateOf<List<DownloadItem>>(emptyList())
        private set

    private fun dir(context: Context) = File(context.filesDir, "downloads").also { it.mkdirs() }

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val raw = runCatching { File(dir(app), "index.json").readText() }.getOrNull() ?: return
        items = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::fromJson) }
        }.getOrDefault(emptyList()).map {
            // Unduhan yang terputus saat aplikasi ditutup dilanjutkan dari awal.
            if (it.state == DownloadState.Downloading) it.copy(state = DownloadState.Queued, doneBytes = 0) else it
        }
        if (items.any { it.state == DownloadState.Queued }) kick()
    }

    /** Video yang sudah selesai diunduh dan berkasnya masih ada; null kalau tidak. */
    fun local(id: String): DownloadItem? = items.firstOrNull {
        it.id == id && it.state == DownloadState.Done && it.videoPath?.let { p -> File(p).exists() } == true
    }

    /** Tidak ada internet yang berfungsi (tidak tersambung, atau tersambung tapi tanpa akses). */
    fun isOffline(): Boolean {
        val cm = appContext?.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Rincian video dari metadata unduhan, hanya saat offline (tanpa internet halaman video tidak bisa dimuat). */
    fun offlineDetails(id: String): VideoDetails? {
        if (appContext == null || !isOffline()) return null
        val d = local(id) ?: return null
        return VideoDetails(
            item = VideoItem(
                id = d.id, title = d.title, channel = d.channel, channelUrl = null, thumbnail = d.thumbnailUri,
                avatar = null, durationSec = d.durationSec, views = -1, uploaded = null,
            ),
            description = "", likes = 0, subscribers = 0, related = emptyList(),
            playable = true, quality = "${d.height}p", qualities = emptyList(),
        )
    }

    fun start(context: Context, video: VideoItem) {
        init(context)
        val existing = items.firstOrNull { it.id == video.id }
        if (existing != null && existing.state != DownloadState.Failed) return
        canceled.remove(video.id)
        val item = DownloadItem(
            id = video.id, title = video.title, channel = video.channel, thumbnailUri = video.thumbnail,
            durationSec = video.durationSec, height = 0, videoPath = null, audioPath = null,
            totalBytes = 0, doneBytes = 0, state = DownloadState.Queued, error = null, addedAt = System.currentTimeMillis(),
        )
        items = listOf(item) + items.filter { it.id != video.id }
        save()
        kick()
    }

    /** Membatalkan unduhan yang berjalan atau menghapus yang sudah selesai, beserta berkasnya. */
    fun remove(context: Context, id: String) {
        init(context)
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.state == DownloadState.Queued || item.state == DownloadState.Downloading) canceled += id
        items = items.filter { it.id != id }
        deleteFiles(context, id)
        save()
    }

    // ── Pekerja ──

    private fun kick() {
        val app = appContext ?: return
        if (worker?.isActive == true) return
        runCatching { DownloadService.start(app) }
        worker = scope.launch {
            while (true) {
                val next = items.firstOrNull { it.state == DownloadState.Queued || it.state == DownloadState.Downloading } ?: break
                try {
                    download(app, next)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (canceled.remove(next.id)) {
                        deleteFiles(app, next.id)
                    } else {
                        patch(next.id) { it.copy(state = DownloadState.Failed, error = YouTube.friendlyError(e)) }
                        save()
                    }
                }
            }
            runCatching { DownloadService.stop(app) }
        }
    }

    private fun download(app: Context, item: DownloadItem) {
        patch(item.id) { it.copy(state = DownloadState.Downloading, error = null, doneBytes = 0) }
        val info = StreamCache.getOrLoad(item.id)
        val choice = choose(info) ?: throw IOException("Tidak ada aliran video yang bisa diunduh.")
        val total = sizeOf(choice.videoUrl) + (choice.audioUrl?.let(::sizeOf) ?: 0L)
        patch(item.id) { it.copy(height = choice.height, totalBytes = total) }
        val folder = dir(app)
        val videoFile = File(folder, "${item.id}_v.${if (choice.videoWebm) "webm" else "mp4"}")
        val audioFile = File(folder, "${item.id}_a.${if (choice.audioM4a) "m4a" else "webm"}")
        fetchFile(choice.videoUrl, videoFile, item.id) { done -> report(item.id, done) }
        val videoBytes = videoFile.length()
        if (choice.audioUrl != null) {
            fetchFile(choice.audioUrl, audioFile, item.id) { done -> report(item.id, videoBytes + done) }
        }
        val thumb = item.thumbnailUri?.takeIf { it.startsWith("http") }?.let { saveThumbnail(it, File(folder, "${item.id}.jpg")) }
        patch(item.id) {
            it.copy(
                state = DownloadState.Done, doneBytes = videoBytes + (if (choice.audioUrl != null) audioFile.length() else 0L),
                totalBytes = videoBytes + (if (choice.audioUrl != null) audioFile.length() else 0L),
                videoPath = videoFile.absolutePath, audioPath = if (choice.audioUrl != null) audioFile.absolutePath else null,
                thumbnailUri = thumb ?: it.thumbnailUri,
            )
        }
        save()
    }

    private class Choice(val videoUrl: String, val audioUrl: String?, val height: Int, val videoWebm: Boolean, val audioM4a: Boolean)

    /** Video terbaik sampai 720p (H.264 dulu) dengan audio terbaik; kalau tak ada yang terpisah, aliran gabungan. */
    private fun choose(info: StreamInfo): Choice? {
        val videos = info.videoOnlyStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height > 0 }
        val audios = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedWith(
                compareBy<AudioStream> { if (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL) 0 else 1 }
                    .thenBy { if (it.format == MediaFormat.M4A) 0 else 1 }
                    .thenByDescending { it.averageBitrate },
            )
        val audio = audios.firstOrNull()
        if (videos.isNotEmpty() && audio != null) {
            val under = videos.filter { it.height <= MAX_HEIGHT }
            val best = if (under.isNotEmpty()) under.maxOf { it.height } else videos.minOf { it.height }
            val video = videos.filter { it.height == best }.minByOrNull { if (it.format == MediaFormat.MPEG_4) 0 else 1 }
            if (video != null) {
                return Choice(video.content, audio.content, video.height, video.format == MediaFormat.WEBM, audio.format == MediaFormat.M4A)
            }
        }
        val muxed = info.videoStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.height } ?: return null
        return Choice(muxed.content, null, muxed.height.coerceAtLeast(0), muxed.format == MediaFormat.WEBM, true)
    }

    private fun sizeOf(url: String): Long = Uri.parse(url).getQueryParameter("clen")?.toLongOrNull() ?: 0L

    /** Mengunduh [url] ke [target] dalam potongan; berhenti kalau unduhan [id] dibatalkan. */
    private fun fetchFile(url: String, target: File, id: String, onProgress: (Long) -> Unit) {
        target.delete()
        val clen = Uri.parse(url).getQueryParameter("clen")?.toLongOrNull() ?: -1L
        var offset = 0L
        try {
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (id in canceled) throw IOException("Dibatalkan")
                    val end = offset + CHUNK - 1
                    val chunkUrl = if (url.contains("?")) "$url&range=$offset-$end" else "$url?range=$offset-$end"
                    val request = Request.Builder().url(chunkUrl).header("User-Agent", Net.USER_AGENT).build()
                    var got = 0L
                    Net.client.newCall(request).execute().use { r ->
                        if (r.code == 416) return@use
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        val body = r.body ?: throw IOException("Respons kosong")
                        body.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                got += n
                                onProgress(offset + got)
                                if (id in canceled) throw IOException("Dibatalkan")
                            }
                        }
                    }
                    offset += got
                    if (got < CHUNK || (clen > 0 && offset >= clen)) break
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    private fun saveThumbnail(url: String, file: File): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()
        Net.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val bytes = r.body?.bytes() ?: return@use null
            file.writeBytes(bytes)
            Uri.fromFile(file).toString()
        }
    }.getOrNull()

    /** Memperbarui kemajuan paling sering tiap 0,4 detik, supaya layar tidak digambar ulang terus. */
    private fun report(id: String, done: Long) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 400) return
        lastReport = now
        patch(id) { it.copy(doneBytes = done) }
    }

    private fun patch(id: String, change: (DownloadItem) -> DownloadItem) {
        items = items.map { if (it.id == id) change(it) else it }
    }

    private fun deleteFiles(context: Context, id: String) {
        dir(context).listFiles()?.filter { it.name.startsWith("${id}_") || it.name == "$id.jpg" }?.forEach { it.delete() }
    }

    private fun save() {
        val app = appContext ?: return
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        runCatching { File(dir(app), "index.json").writeText(array.toString()) }
    }

    private fun toJson(d: DownloadItem) = JSONObject()
        .put("id", d.id).put("title", d.title).put("channel", d.channel)
        .put("thumb", d.thumbnailUri ?: JSONObject.NULL).put("duration", d.durationSec).put("height", d.height)
        .put("video", d.videoPath ?: JSONObject.NULL).put("audio", d.audioPath ?: JSONObject.NULL)
        .put("total", d.totalBytes).put("done", d.doneBytes).put("state", d.state.name)
        .put("error", d.error ?: JSONObject.NULL).put("added", d.addedAt)

    private fun fromJson(o: JSONObject): DownloadItem? {
        val id = o.optString("id").ifBlank { return null }
        fun str(k: String) = if (o.isNull(k)) null else o.optString(k).ifBlank { null }
        return DownloadItem(
            id = id, title = o.optString("title"), channel = o.optString("channel"), thumbnailUri = str("thumb"),
            durationSec = o.optLong("duration"), height = o.optInt("height"), videoPath = str("video"), audioPath = str("audio"),
            totalBytes = o.optLong("total"), doneBytes = o.optLong("done"),
            state = runCatching { DownloadState.valueOf(o.optString("state")) }.getOrDefault(DownloadState.Failed),
            error = str("error"), addedAt = o.optLong("added"),
        )
    }
}

/** Layanan latar depan: menjaga unduhan tetap berjalan (dan menampilkan kemajuannya) saat aplikasi ditutup. */
class DownloadService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Downloads"), NotificationManager.IMPORTANCE_LOW))
        val first = notification(manager)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, first)
        job?.cancel()
        job = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                manager.notify(NOTIFICATION, notification(manager))
                delay(1_000)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(manager: NotificationManager): Notification {
        val active = Downloads.items.firstOrNull { it.state == DownloadState.Downloading }
            ?: Downloads.items.firstOrNull { it.state == DownloadState.Queued }
        val percent = ((active?.progress ?: 0f) * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr("Downloading video"))
            .setContentText(active?.title ?: "")
            .setProgress(100, percent, active == null || active.totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 7421

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}

/** Ubin "Unduhan" di Perpustakaan: membuka halaman Unduhan; menampilkan jumlah video yang diunduh. */
@Composable
fun DownloadsTile(onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val done = Downloads.items.count { it.state == DownloadState.Done }
    val busy = Downloads.items.count { it.state == DownloadState.Queued || it.state == DownloadState.Downloading }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(c.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Bentuk dan warna sama dengan ikon Tonton nanti dan Video yang disukai.
        Box(
            Modifier.size(52.dp).clip(CookieShape).background(c.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Download, null, tint = androidx.compose.material3.contentColorFor(c.primaryContainer)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("Downloads"), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Text(
                when {
                    busy > 0 -> "${tr("Downloading…")} ($busy)"
                    done > 0 -> "$done ${tr("videos")} · ${tr("Watch without internet")}"
                    else -> tr("Watch without internet")
                },
                color = c.onSurfaceVariant, fontSize = 13.sp,
            )
        }
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "${bytes / 1_000_000L} MB"
    bytes > 0 -> "${maxOf(1L, bytes / 1_000L)} KB"
    else -> ""
}

/** Halaman Unduhan: daftar video yang diunduh (dengan kemajuan), ketuk untuk menonton tanpa internet. */
@Composable
fun DownloadsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpen: (VideoItem) -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val list = Downloads.items
    Column(Modifier.fillMaxSize().background(c.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).pressable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = c.onSurface)
            }
            Text(tr("Downloads"), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        }
        if (list.isEmpty()) {
            EmptyState(
                Icons.Rounded.Download, tr("No downloads yet"),
                tr("Tap Download on a video page, then watch it here without internet."),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
                items(list, key = { it.id }) { d ->
                    val video = VideoItem(
                        id = d.id, title = d.title, channel = d.channel, channelUrl = null, thumbnail = d.thumbnailUri,
                        avatar = null, durationSec = d.durationSec, views = -1, uploaded = null,
                    )
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                when (d.state) {
                                    DownloadState.Done -> onOpen(video)
                                    DownloadState.Failed -> Downloads.start(context, video)
                                    else -> {}
                                }
                            }
                            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.width(150.dp)) {
                            Thumbnail(d.thumbnailUri, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 12)
                            if (d.durationSec > 0) DurationPill(d.durationSec, Modifier.align(Alignment.BottomEnd).padding(5.dp), small = true)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(d.channel, color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                            when (d.state) {
                                DownloadState.Done -> Text(
                                    listOfNotNull(d.height.takeIf { it > 0 }?.let { "${it}p" }, sizeText(d.totalBytes).ifBlank { null }).joinDots(),
                                    color = c.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp),
                                )
                                DownloadState.Failed -> Text(
                                    tr("Failed, tap to retry"), color = c.error, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                else -> {
                                    Spacer(Modifier.height(6.dp))
                                    if (d.totalBytes > 0) LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text(
                                        if (d.state == DownloadState.Queued) tr("Waiting…") else "${(d.progress * 100).toInt()}%",
                                        color = c.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                        Box(Modifier.size(44.dp).pressable { Downloads.remove(context, d.id) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Delete, tr("Delete download"), tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}
