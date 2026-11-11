package com.decio.plaza

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ── Posisi tombol pencarian ────────────────────────────────────────────────────

/** Tombol pencarian: di navigasi bawah (bawaan) atau sebagai ikon di bagian atas Beranda. */
object SearchPlacement {
    private const val KEY = "search_on_top"
    private var prefs: android.content.SharedPreferences? = null

    var top by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE)
        prefs = p
        top = p.getBoolean(KEY, false)
    }

    fun set(onTop: Boolean) {
        top = onTop
        prefs?.edit()?.putBoolean(KEY, onTop)?.apply()
    }
}

// ── Notifikasi ─────────────────────────────────────────────────────────────────

/** Satu notifikasi akun: siapa, apa, kapan, dan video yang dituju. */
data class AppNotification(
    val id: String,
    /** Nama channel (tebal); kosong kalau pesannya tidak punya bentuk "Nama mengupload: judul". */
    val name: String,
    /** Sisa pesan, mis. "Mengupload: judul video". */
    val text: String,
    val ago: String?,
    val avatar: String?,
    val thumbnail: String?,
    val videoId: String?,
    val minutesAgo: Long,
    val isMention: Boolean,
)

internal object NotificationsApi {
    suspend fun unseenCount(): Int = withContext(Dispatchers.IO) {
        val root = InnerTube.postAny("notification/get_unseen_count", JSONObject())
        var count = root.optInt("unseenCount", -1)
        if (count < 0) root.walk { key, obj ->
            if (count < 0 && key == "updateNotificationsUnseenCountAction") count = obj.optInt("unseenCount", -1)
        }
        count.coerceAtLeast(0)
    }

    suspend fun inbox(): List<AppNotification> = withContext(Dispatchers.IO) {
        val root = InnerTube.postAny(
            "notification/get_notification_menu",
            JSONObject().put("notificationsMenuRequestType", "NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX"),
        )
        val out = LinkedHashMap<String, AppNotification>()
        root.walk { key, obj ->
            if (key == "notificationRenderer") {
                val message = obj.text("shortMessage").orEmpty().ifBlank { return@walk }
                val id = obj.optString("notificationId").ifBlank { UUID.randomUUID().toString() }
                val (name, text) = splitMessage(message)
                val ago = obj.text("sentTimeText")
                val videoId = obj.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")?.ifBlank { null }
                    ?: obj.collectStrings("url").firstNotNullOfOrNull { Regex("[?&]v=([\\w-]{11})").find(it)?.groupValues?.get(1) }
                out.getOrPut(id) {
                    AppNotification(
                        id = id, name = name, text = text, ago = ago,
                        avatar = lastUrl(obj.optJSONObject("thumbnail")?.optJSONArray("thumbnails")),
                        thumbnail = lastUrl(obj.optJSONObject("videoThumbnail")?.optJSONArray("thumbnails")),
                        videoId = videoId, minutesAgo = agoMinutes(ago),
                        isMention = Regex("(?i)(menyebut|mentioned|mention)").containsMatchIn(message),
                    )
                }
            }
        }
        out.values.toList()
    }

    private fun lastUrl(arr: JSONArray?): String? =
        arr?.optJSONObject(arr.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("//")) "https:$it" else it }

    /** "Day Helder mengupload: judul" → ("Day Helder", "Mengupload: judul"). */
    private fun splitMessage(message: String): Pair<String, String> {
        val colon = message.indexOf(':')
        if (colon > 0) {
            val head = message.substring(0, colon).trim()
            val space = head.lastIndexOf(' ')
            if (space > 0) {
                val verb = head.substring(space + 1).replaceFirstChar { it.uppercase() }
                return head.substring(0, space) to (verb + message.substring(colon))
            }
        }
        return "" to message
    }

    /** "19 menit yang lalu" → 19; dipakai untuk mengelompokkan (Hari ini, Minggu ini, …). */
    fun agoMinutes(text: String?): Long {
        val t = text?.lowercase() ?: return Long.MAX_VALUE
        val n = Regex("\\d+").find(t)?.value?.toLongOrNull() ?: 1L
        return when {
            Regex("detik|second").containsMatchIn(t) -> 0
            Regex("menit|minute").containsMatchIn(t) -> n
            Regex("jam|hour").containsMatchIn(t) -> n * 60
            Regex("hari|day").containsMatchIn(t) -> n * 1_440
            Regex("minggu|week").containsMatchIn(t) -> n * 10_080
            Regex("bulan|month").containsMatchIn(t) -> n * 43_200
            Regex("tahun|year").containsMatchIn(t) -> n * 525_600
            else -> Long.MAX_VALUE
        }
    }
}

/** Keadaan notifikasi akun: jumlah yang belum dilihat (lencana lonceng) dan isi kotak masuk. */
object Notifications {
    var unseen by mutableIntStateOf(0)
        private set
    var items by mutableStateOf<List<AppNotification>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun clear() { unseen = 0; items = null; error = null }

    suspend fun refreshCount() {
        if (!Account.signedIn) { unseen = 0; return }
        runCatching { NotificationsApi.unseenCount() }.onSuccess { unseen = it }
    }

    suspend fun load() {
        if (!Account.signedIn || loading) return
        loading = true
        error = null
        runCatching { NotificationsApi.inbox() }
            .onSuccess { items = it; unseen = 0 }
            .onFailure { error = YouTube.friendlyError(it) }
        loading = false
    }
}

// ── Ikon di header ─────────────────────────────────────────────────────────────

private val BadgeRed = Color(0xFFE62117)

/** Tombol bulat header: ikon warna aplikasi (primary), latar mengikuti tema (primaryContainer). */
@Composable
fun ThemedCircleAction(icon: ImageVector, contentDescription: String, badge: Int = 0, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(44.dp).pressable(onClick = onClick)) {
        Box(
            Modifier.matchParentSize().clip(CircleShape).background(c.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription, tint = c.primary, modifier = Modifier.size(22.dp)) }
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp)
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .clip(CircleShape).background(BadgeRed).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) }
        }
    }
}

/** Lonceng notifikasi dengan lencana jumlah yang belum dilihat; hanya tampil setelah masuk. */
@Composable
fun NotificationAction(onClick: () -> Unit) {
    val signedIn = Account.signedIn
    LaunchedEffect(signedIn, Account.profile?.name) {
        if (!signedIn) { Notifications.clear(); return@LaunchedEffect }
        while (true) {
            Notifications.refreshCount()
            delay(120_000)
        }
    }
    if (!signedIn) return
    ThemedCircleAction(Icons.Outlined.Notifications, tr("Notifications"), badge = Notifications.unseen, onClick = onClick)
}

@Composable
fun SearchAction(onClick: () -> Unit) {
    ThemedCircleAction(Icons.Rounded.Search, tr("Search"), onClick = onClick)
}

// ── Layar Notifikasi ───────────────────────────────────────────────────────────

/** Halaman Notifikasi: pilihan Semua / Sebutan, dikelompokkan Hari ini, Minggu ini, dan lebih lama. */
@Composable
fun NotificationsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpen: (VideoItem) -> Unit) {
    val c = MaterialTheme.colorScheme
    var mentionsOnly by remember { mutableStateOf(false) }
    LaunchedEffect(Account.signedIn, Account.profile?.name) { Notifications.load() }
    val all = Notifications.items
    val shown = all?.filter { !mentionsOnly || it.isMention }
    val menu = LocalVideoMenu.current
    Column(Modifier.fillMaxSize().background(c.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).pressable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = c.onSurface)
            }
            Text(tr("Notifications"), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(false to tr("All"), true to tr("Mentions")).forEach { (mentions, label) ->
                val on = mentionsOnly == mentions
                Text(
                    label,
                    color = if (on) c.surface else c.onSurface,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (on) c.onSurface else c.surfaceContainerHighest)
                        .clickable { mentionsOnly = mentions }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            shown == null && Notifications.error != null -> EmptyState(
                Icons.Rounded.CloudOff, tr("Couldn't load this"), Notifications.error.orEmpty(),
            )
            shown == null -> Box(Modifier.fillMaxSize().padding(top = 60.dp), Alignment.TopCenter) {
                CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(36.dp))
            }
            shown.isEmpty() -> EmptyState(
                Icons.Outlined.Notifications, tr("No notifications"),
                tr(if (mentionsOnly) "Nobody has mentioned you yet." else "New videos from your subscriptions show up here."),
            )
            else -> {
                val groups = listOf(
                    tr("Today") to shown.filter { it.minutesAgo < 1_440 },
                    tr("This week") to shown.filter { it.minutesAgo in 1_440 until 10_080 },
                    tr("Earlier") to shown.filter { it.minutesAgo >= 10_080 },
                ).filter { it.second.isNotEmpty() }
                LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
                    groups.forEach { (title, list) ->
                        item(key = "h:$title") {
                            Text(title, color = c.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
                        }
                        items(list, key = { it.id }) { n ->
                            val video = n.videoId?.let {
                                VideoItem(
                                    id = it, title = n.text.substringAfter(": ", n.text), channel = n.name, channelUrl = null,
                                    thumbnail = n.thumbnail, avatar = n.avatar, durationSec = 0, views = -1, uploaded = n.ago,
                                )
                            }
                            NotificationRow(n, onClick = { video?.let(onOpen) }, onMenu = { video?.let(menu) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(n: AppNotification, onClick: () -> Unit, onMenu: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ChannelAvatar(n.avatar, n.name.ifBlank { n.text }, size = 52)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (n.name.isNotBlank()) Text(n.name, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(n.text, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            n.ago?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
        if (n.thumbnail != null) {
            Spacer(Modifier.width(10.dp))
            Thumbnail(n.thumbnail, Modifier.width(112.dp).aspectRatio(16f / 9f), radius = 10)
        }
        Box(Modifier.size(40.dp).pressable(onClick = onMenu), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MoreVert, tr("More options"), tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
