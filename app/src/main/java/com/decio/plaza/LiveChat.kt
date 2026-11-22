package com.decio.plaza

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Satu pesan di chat langsung. */
data class LiveChatMessage(
    val id: String,
    val author: String,
    val avatar: String?,
    val text: String,
    val amount: String? = null,
    /** 0 = pesan biasa, 1 = Super Chat, 2 = anggota baru. */
    val kind: Int = 0,
    /** 0 = biasa, 1 = anggota channel, 2 = moderator, 3 = pemilik channel. */
    val role: Int = 0,
    /** Warna Super Chat persis dari YouTube (ARGB); null = pakai warna tema. */
    val headerBg: Long? = null,
    val headerText: Long? = null,
    val bodyBg: Long? = null,
    val bodyText: Long? = null,
)

/** Satu putaran chat: pesan baru, token putaran berikutnya, jeda, dan tanda untuk mengirim pesan. */
class LiveChatPage(val messages: List<LiveChatMessage>, val next: String?, val waitMs: Long, val sendParams: String?)

/** Suka, penonton, dan tanggal siaran langsung seperti yang tertulis di halaman YouTube. */
data class LiveInfo(val viewers: String?, val date: String?, val likes: String?)

internal object LiveChatApi {
    /** Token awal chat dari halaman tonton; null kalau siaran ini tidak punya chat. */
    suspend fun start(videoId: String): String? = withContext(Dispatchers.IO) {
        val root = InnerTube.postAny("next", JSONObject().put("videoId", videoId))
        var token: String? = null
        var all: String? = null
        root.walk { key, obj ->
            if (token == null && key == "liveChatRenderer") token = obj.collectStrings("continuation").firstOrNull()
            // "Live chat" (semua pesan, apa adanya) = pilihan kedua di menu urutan; yang pertama "Top chat" (disaring).
            if (all == null && key == "sortFilterSubMenuRenderer") {
                all = obj.optJSONArray("subMenuItems")?.optJSONObject(1)?.optJSONObject("continuation")
                    ?.optJSONObject("reloadContinuationData")?.optString("continuation")?.ifBlank { null }
            }
        }
        all ?: token
    }

    suspend fun poll(continuation: String): LiveChatPage = withContext(Dispatchers.IO) {
        val root = InnerTube.postAny("live_chat/get_live_chat", JSONObject().put("continuation", continuation))
        val out = ArrayList<LiveChatMessage>()
        var next: String? = null
        var wait = 4_000L
        var send: String? = null
        root.walk { key, obj ->
            when (key) {
                "liveChatTextMessageRenderer", "liveChatPaidMessageRenderer", "liveChatMembershipItemRenderer" -> {
                    val paid = key == "liveChatPaidMessageRenderer"
                    val member = key == "liveChatMembershipItemRenderer"
                    val text = if (member) runsText(obj.optJSONObject("headerSubtext")) else runsText(obj.optJSONObject("message"))
                    val amount = obj.optJSONObject("purchaseAmountText")?.optString("simpleText")?.ifBlank { null }
                    if (text.isNotBlank() || amount != null || member) {
                        val avatars = obj.optJSONObject("authorPhoto")?.optJSONArray("thumbnails")
                        fun color(name: String): Long? = if (obj.has(name)) obj.optLong(name) else null
                        out += LiveChatMessage(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            author = obj.optJSONObject("authorName")?.optString("simpleText").orEmpty(),
                            avatar = avatars?.optJSONObject(avatars.length() - 1)?.optString("url")?.ifBlank { null },
                            text = text,
                            amount = amount,
                            kind = if (paid) 1 else if (member) 2 else 0,
                            role = badgeRole(obj.optJSONArray("authorBadges")),
                            headerBg = color("headerBackgroundColor"),
                            headerText = color("headerTextColor"),
                            bodyBg = color("bodyBackgroundColor"),
                            bodyText = color("bodyTextColor"),
                        )
                    }
                }
                "timedContinuationData", "invalidationContinuationData", "reloadContinuationData" -> if (next == null) {
                    obj.optString("continuation").ifBlank { null }?.let {
                        next = it
                        wait = obj.optLong("timeoutMs", 4_000L)
                    }
                }
                "sendLiveChatMessageEndpoint" -> if (send == null) send = obj.optString("params").ifBlank { null }
            }
        }
        LiveChatPage(out, next, wait, send)
    }

    suspend fun send(params: String, text: String) = withContext(Dispatchers.IO) {
        val segments = JSONArray().put(JSONObject().put("text", text))
        InnerTube.post(
            "live_chat/send_message",
            JSONObject().put("params", params)
                .put("richMessage", JSONObject().put("textSegments", segments))
                .put("clientMessageId", UUID.randomUUID().toString()),
        )
        Unit
    }

    suspend fun info(videoId: String): LiveInfo = withContext(Dispatchers.IO) {
        val root = InnerTube.postAny("next", JSONObject().put("videoId", videoId))
        var viewers: String? = null
        var date: String? = null
        var likes: String? = null
        root.walk { key, obj ->
            when (key) {
                "videoViewCountRenderer" -> if (viewers == null) viewers = obj.text("viewCount") ?: obj.text("shortViewCount")
                "videoPrimaryInfoRenderer" -> if (date == null) date = obj.text("dateText")
                "likeCountEntity" -> if (likes == null) {
                    likes = obj.optJSONObject("likeCountIfIndifferent")?.optString("content")?.ifBlank { null }
                        ?: obj.optJSONObject("expandedLikeCountIfIndifferent")?.optString("content")?.ifBlank { null }
                }
            }
        }
        LiveInfo(viewers, date, likes)
    }

    /** Peran penulis dari lencananya: pemilik 3, moderator 2, anggota 1 (lencana bergambar sendiri), lainnya 0. */
    private fun badgeRole(badges: JSONArray?): Int {
        var role = 0
        if (badges == null) return role
        for (i in 0 until badges.length()) {
            val b = badges.optJSONObject(i)?.optJSONObject("liveChatAuthorBadgeRenderer") ?: continue
            when {
                b.optJSONObject("icon")?.optString("iconType") == "OWNER" -> role = maxOf(role, 3)
                b.optJSONObject("icon")?.optString("iconType") == "MODERATOR" -> role = maxOf(role, 2)
                b.has("customThumbnail") -> role = maxOf(role, 1)
            }
        }
        return role
    }

    private fun runsText(o: JSONObject?): String {
        val runs = o?.optJSONArray("runs") ?: return o?.optString("simpleText").orEmpty()
        return (0 until runs.length()).joinToString("") { i ->
            val r = runs.optJSONObject(i)
            r?.optString("text")?.takeIf { it.isNotEmpty() }
                ?: r?.optJSONObject("emoji")?.let { e ->
                    // Emoji biasa: hurufnya langsung; emoji buatan channel: kode singkatnya (:nama:).
                    val id = e.optString("emojiId")
                    if (!e.optBoolean("isCustomEmoji") && id.isNotBlank()) id else e.optJSONArray("shortcuts")?.optString(0).orEmpty()
                }.orEmpty()
        }
    }
}

/** Chat langsung satu siaran: memuat pesan terus-menerus selagi tampil, tiap 5 detik. */
@Stable
class LiveChatState(private val scope: CoroutineScope) {
    var messages by mutableStateOf<List<LiveChatMessage>>(emptyList())
        private set
    /** Bertambah tiap ada pesan baru; pemicu gulir otomatis ke pesan terbaru. */
    var tick by mutableStateOf(0)
        private set
    /** Keterangan kalau chat tidak bisa dimuat; null = lancar. */
    var status by mutableStateOf<String?>(null)
        private set
    var canSend by mutableStateOf(false)
        private set
    private var sendParams: String? = null
    private var job: Job? = null

    fun start(videoId: String) {
        stop()
        messages = emptyList(); status = null; canSend = false; sendParams = null; tick = 0
        job = scope.launch {
            try {
                var token = LiveChatApi.start(videoId)
                if (token == null) { status = "Chat langsung tidak tersedia untuk siaran ini."; return@launch }
                var failures = 0
                while (isActive && token != null) {
                    val cycleStart = System.currentTimeMillis()
                    try {
                        val page = LiveChatApi.poll(token)
                        failures = 0
                        val seen = messages.mapTo(HashSet()) { it.id }
                        val fresh = page.messages.filter { seen.add(it.id) }
                        page.sendParams?.let { sendParams = it; canSend = true }
                        token = page.next
                        if (fresh.isNotEmpty()) {
                            if (messages.isEmpty() || fresh.size == 1) {
                                // Putaran pertama (pesan yang sudah ada) dan pesan tunggal: langsung tampil.
                                messages = (messages + fresh).takeLast(MAX)
                                tick++
                            } else {
                                // Pesan satu putaran tampil bergantian sepanjang jedanya (seperti di YouTube),
                                // bukan sekaligus, supaya chat mengalir halus.
                                val gap = (POLL_MS * 8 / 10 / fresh.size).coerceIn(60L, 600L)
                                for (m in fresh) {
                                    messages = (messages + m).takeLast(MAX)
                                    tick++
                                    delay(gap)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Satu putaran gagal tidak menghentikan chat: coba lagi, makin pelan, menyerah setelah 6 kali.
                        failures++
                        if (failures >= 6) throw e
                    }
                    // Data baru diambil tiap 5 detik (dihitung dari awal putaran); kalau sedang gagal, jedanya bertambah.
                    val wait = if (failures == 0) POLL_MS else minOf(15_000L, POLL_MS * (failures + 1))
                    delay((wait - (System.currentTimeMillis() - cycleStart)).coerceAtLeast(500L))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (messages.isEmpty()) status = "Chat langsung gagal dimuat."
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    suspend fun send(text: String) {
        val p = sendParams ?: throw IOException("Chat ini tidak bisa dikirimi pesan.")
        LiveChatApi.send(p, text)
    }

    private companion object {
        const val MAX = 300
        const val POLL_MS = 5_000L
    }
}

/** Satu baris chat seperti di YouTube: pesan biasa, Super Chat berwarna, atau anggota baru. */
@Composable
private fun ChatRow(m: LiveChatMessage) {
    val c = MaterialTheme.colorScheme
    when (m.kind) {
        1 -> {
            val head = m.headerBg?.let { Color(it.toInt()) } ?: c.primary
            val headText = m.headerText?.let { Color(it.toInt()) } ?: c.onPrimary
            val body = m.bodyBg?.let { Color(it.toInt()) } ?: head
            val bodyText = m.bodyText?.let { Color(it.toInt()) } ?: headText
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp))) {
                Row(Modifier.fillMaxWidth().background(head).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChannelAvatar(m.avatar, m.author, size = 32)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(m.author, color = headText.copy(alpha = 0.85f), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(m.amount.orEmpty(), color = headText, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                if (m.text.isNotBlank()) {
                    Text(m.text, color = bodyText, fontSize = 13.5.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().background(body).padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }
        2 -> {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F9D58)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelAvatar(m.avatar, m.author, size = 28)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(m.author, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(m.text.ifBlank { "Anggota baru!" }, color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
        }
        else -> {
            val nameColor = when (m.role) {
                3 -> Color(0xFF1A1A1A)
                2 -> Color(0xFF5E84F1)
                1 -> Color(0xFF2BA640)
                else -> c.onSurfaceVariant
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                ChannelAvatar(m.avatar, m.author, size = 24)
                Spacer(Modifier.width(8.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(
                            if (m.role == 3) SpanStyle(fontWeight = FontWeight.Bold, color = nameColor, background = Color(0xFFFFD600))
                            else SpanStyle(fontWeight = FontWeight.Bold, color = nameColor),
                        ) { append(if (m.role == 3) " ${m.author} " else m.author) }
                        append("  ")
                        append(m.text)
                    },
                    fontSize = 13.5.sp, lineHeight = 18.sp,
                )
            }
        }
    }
}

/** Kartu chat langsung di halaman tonton siaran langsung (pengganti komentar), tampil seperti chat YouTube. */
@Composable
fun LiveChatCard(videoId: String) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val chat = remember(videoId) { LiveChatState(scope) }
    DisposableEffect(videoId) {
        chat.start(videoId)
        onDispose { chat.stop() }
    }
    var text by remember(videoId) { mutableStateOf("") }
    var sendError by remember(videoId) { mutableStateOf<String?>(null) }
    var sending by remember(videoId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Di dasar daftar = pesan baru ikut tergulir; kalau pengguna menggulir ke atas, daftar tidak ditarik turun.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(chat.tick) {
        if (atBottom && chat.messages.isNotEmpty()) listState.scrollToItem(chat.messages.lastIndex)
    }
    Column(
        Modifier.padding(top = 16.dp).fillMaxWidth()
            .glassSurface(RoundedCornerShape(24.dp), c.surfaceContainerLow).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chat langsung", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("Diperbarui tiap 5 detik", color = c.onSurfaceVariant, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().then(if (chat.messages.isEmpty() && chat.status != null) Modifier else Modifier.height(360.dp)).clipToBounds()) {
            when {
                chat.messages.isEmpty() && chat.status == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                }
                chat.messages.isEmpty() -> Text(chat.status.orEmpty(), color = c.onSurfaceVariant, fontSize = 13.5.sp)
                else -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(chat.messages, key = { it.id }) { m -> ChatRow(m) }
                    }
                    if (!atBottom) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                                .clip(CircleShape).background(c.primary)
                                .clickable { scope.launch { listState.animateScrollToItem(chat.messages.lastIndex) } }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) { Text("↓ Pesan terbaru", color = c.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            !Account.signedIn -> Text("Masuk dengan Google untuk ikut chat.", color = c.onSurfaceVariant, fontSize = 13.sp)
            !chat.canSend -> Text("Chat ini tidak bisa dikirimi pesan (dimatikan atau khusus anggota).", color = c.onSurfaceVariant, fontSize = 13.sp)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(200) },
                    modifier = Modifier.weight(1f), singleLine = true, shape = CircleShape,
                    placeholder = { Text("Ketik pesan…") },
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(c.primary)
                        .clickable(enabled = !sending && text.isNotBlank()) {
                            val message = text.trim()
                            sending = true
                            scope.launch {
                                runCatching { chat.send(message) }
                                    .onSuccess { text = ""; sendError = null }
                                    .onFailure { sendError = "Pesan gagal terkirim." }
                                sending = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.Send, "Kirim", tint = c.onPrimary, modifier = Modifier.size(22.dp)) }
            }
        }
        sendError?.let { Text(it, color = c.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}
