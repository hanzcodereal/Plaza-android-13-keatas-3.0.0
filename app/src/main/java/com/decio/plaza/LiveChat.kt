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
data class LiveChatMessage(val id: String, val author: String, val avatar: String?, val text: String, val amount: String? = null)

/** Satu putaran chat: pesan baru, token putaran berikutnya, jeda, dan tanda untuk mengirim pesan. */
class LiveChatPage(val messages: List<LiveChatMessage>, val next: String?, val waitMs: Long, val sendParams: String?)

/** Suka, penonton, dan tanggal siaran langsung seperti yang tertulis di halaman YouTube. */
data class LiveInfo(val viewers: String?, val date: String?, val likes: String?)

internal object LiveChatApi {
    /** Token awal chat dari halaman tonton; null kalau siaran ini tidak punya chat. */
    suspend fun start(videoId: String): String? = withContext(Dispatchers.IO) {
        val root = InnerTube.postAny("next", JSONObject().put("videoId", videoId))
        var token: String? = null
        root.walk { key, obj ->
            if (token == null && key == "liveChatRenderer") token = obj.collectStrings("continuation").firstOrNull()
        }
        token
    }

    suspend fun poll(continuation: String): LiveChatPage = withContext(Dispatchers.IO) {
        val root = InnerTube.postAny("live_chat/get_live_chat", JSONObject().put("continuation", continuation))
        val out = ArrayList<LiveChatMessage>()
        var next: String? = null
        var wait = 4_000L
        var send: String? = null
        root.walk { key, obj ->
            when (key) {
                "liveChatTextMessageRenderer", "liveChatPaidMessageRenderer" -> {
                    val text = runsText(obj.optJSONObject("message"))
                    val amount = obj.optJSONObject("purchaseAmountText")?.optString("simpleText")?.ifBlank { null }
                    if (text.isNotBlank() || amount != null) {
                        val avatars = obj.optJSONObject("authorPhoto")?.optJSONArray("thumbnails")
                        out += LiveChatMessage(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            author = obj.optJSONObject("authorName")?.optString("simpleText").orEmpty(),
                            avatar = avatars?.optJSONObject(avatars.length() - 1)?.optString("url")?.ifBlank { null },
                            text = text,
                            amount = amount,
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

    private fun runsText(o: JSONObject?): String {
        val runs = o?.optJSONArray("runs") ?: return o?.optString("simpleText").orEmpty()
        return (0 until runs.length()).joinToString("") { i ->
            val r = runs.optJSONObject(i)
            r?.optString("text")?.takeIf { it.isNotEmpty() }
                ?: r?.optJSONObject("emoji")?.let { e -> e.optJSONArray("shortcuts")?.optString(0)?.ifBlank { null } ?: e.optString("emojiId") }.orEmpty()
        }
    }
}

/** Chat langsung satu siaran: memuat pesan terus-menerus selagi tampil. */
@Stable
class LiveChatState(private val scope: CoroutineScope) {
    var messages by mutableStateOf<List<LiveChatMessage>>(emptyList())
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
        messages = emptyList(); status = null; canSend = false; sendParams = null
        job = scope.launch {
            try {
                var token = LiveChatApi.start(videoId)
                if (token == null) { status = "Chat langsung tidak tersedia untuk siaran ini."; return@launch }
                while (isActive && token != null) {
                    val page = LiveChatApi.poll(token)
                    if (page.messages.isNotEmpty()) {
                        val seen = messages.mapTo(HashSet()) { it.id }
                        messages = (messages + page.messages.filter { seen.add(it.id) }).takeLast(MAX)
                    }
                    page.sendParams?.let { sendParams = it; canSend = true }
                    token = page.next
                    delay(page.waitMs.coerceIn(1_500L, 8_000L))
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

    private companion object { const val MAX = 150 }
}

/** Kartu chat langsung di halaman tonton siaran langsung (pengganti komentar). */
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
    Column(
        Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(c.surfaceContainerLow).padding(16.dp),
    ) {
        Text("Chat langsung", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        // Tanpa daftar bersarang di dalam daftar halaman: pesan terbaru saja (12 terakhir), menempel
        // di bawah; pesan lama terdorong ke atas dan terpotong halus.
        Box(Modifier.fillMaxWidth().then(if (chat.messages.isEmpty() && chat.status != null) Modifier else Modifier.height(300.dp)).clipToBounds()) {
            when {
                chat.messages.isEmpty() && chat.status == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                }
                chat.messages.isEmpty() -> Text(chat.status.orEmpty(), color = c.onSurfaceVariant, fontSize = 13.5.sp)
                else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                    chat.messages.takeLast(12).forEach { m ->
                        key(m.id) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                                ChannelAvatar(m.avatar, m.author, size = 24)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = c.onSurfaceVariant)) { append(m.author + "  ") }
                                        m.amount?.let { withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = c.primary)) { append("$it  ") } }
                                        append(m.text)
                                    },
                                    fontSize = 13.5.sp, lineHeight = 18.sp,
                                )
                            }
                        }
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
