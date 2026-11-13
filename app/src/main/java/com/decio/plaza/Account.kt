package com.decio.plaza

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

@Immutable
data class Profile(val name: String, val handle: String?, val avatar: String?)

@Immutable
data class PlaylistRef(val id: String, val title: String, val thumbnail: String?, val subtitle: String?)

/** A playlist a video can be saved to, and whether it's in it ([contains] null: not known). */
@Immutable
data class PlaylistChoice(val id: String, val title: String, val contains: Boolean?, val privacy: String?)

@Immutable
data class ChannelRef(val id: String, val name: String, val avatar: String?)

/** One page of a feed plus the token for the next page, if any. */
@Immutable
data class FeedPage(
    val videos: List<VideoItem>,
    val next: String?,
    val shorts: List<VideoItem> = emptyList(),
    val posts: List<ChannelPost> = emptyList(),
    /** Chip penyaring bawaan YouTube di atas feed (Musik, Berita, Live, …), tiap chip dengan token halamannya. */
    val chips: List<FeedChip> = emptyList(),
)

/** Satu chip penyaring beranda dari YouTube; [token] memuat feed yang sudah disaring. */
@Immutable
data class FeedChip(val label: String, val token: String)

/** Pembuat sebuah Short: nama channel, foto profil, dan id channel (feed Short tidak menyertakannya). */
@Immutable
data class ShortOwner(val name: String, val avatar: String?, val channelId: String?)

/** Satu channel di hasil pencarian. */
@Immutable
data class ChannelResult(val id: String, val name: String, val avatar: String?, val handle: String?, val subscribers: String?)

/** Satu halaman hasil pencarian: video (+ rak Shorts) dari [feed], channel yang cocok, dan playlist. */
@Immutable
data class SearchPage(
    val feed: FeedPage,
    val channels: List<ChannelResult>,
    val playlists: List<PlaylistRef>,
)

/** Isi halaman channel: username @, jumlah video, dan tab yang dimiliki channel (jenis → params). */
@Immutable
data class ChannelMeta(
    val handle: String?,
    val videoCount: Long,
    val tabs: Map<String, String>,
    val subscribers: Long = -1,
    /** Nama channel sesuai bahasa aplikasi (seperti di aplikasi YouTube); bisa beda dari nama bawaan channel. */
    val name: String? = null,
    /** Bio channel apa adanya dari YouTube (deskripsi lengkap). */
    val description: String? = null,
)

/** Satu postingan komunitas channel. */
@Immutable
data class ChannelPost(
    val id: String,
    val text: String,
    val ago: String?,
    val likes: String?,
    val image: String?,
    /** Semua gambar postingan (ukuran penuh, tanpa potongan); lebih dari satu = digeser. */
    val images: List<String> = emptyList(),
    val author: String? = null,
    val authorAvatar: String? = null,
    val authorId: String? = null,
    /** Jumlah komentar sebagai teks ("12"). */
    val comments: String? = null,
    val commentBrowseId: String? = null,
    val commentParams: String? = null,
    val liked: Boolean = false,
    val disliked: Boolean = false,
    val likeParams: String? = null,
)

enum class LikeStatus { Like, Dislike, None }

@Immutable
data class VideoAccountState(val like: LikeStatus, val subscribed: Boolean?, val channelId: String?)

class NotSignedInException : IOException("Not signed in")

/**
 * The signed-in Google session (user chose full sync, 2026-10-04).
 *
 * Sign-in happens on Google's own page in a WebView; afterwards only the youtube.com
 * cookies are kept, encrypted with a Keystore key ([SecureStore]). Requests are authorized
 * the way youtube.com itself does it (SAPISIDHASH). Cookies YouTube rotates in responses
 * are merged back in so the session stays alive.
 */
object Account {
    var profile by mutableStateOf<Profile?>(null)
        private set
    val signedIn get() = profile != null

    private var store: SecureStore? = null
    private val cookies = linkedMapOf<String, String>()
    private var clientVersion = FALLBACK_CLIENT_VERSION
    private var visitorData: String? = null

    fun init(context: Context) {
        val s = SecureStore(context, "account").also { store = it }
        s.get(KEY_COOKIES)?.let { parseCookieHeader(it, cookies) }
        s.get(KEY_CLIENT_VERSION)?.let { clientVersion = it }
        visitorData = s.get(KEY_VISITOR)
        val name = s.get(KEY_NAME)
        if (name != null && cookies.containsKey("SAPISID")) {
            profile = Profile(name, s.get(KEY_HANDLE), s.get(KEY_AVATAR))
        }
    }

    /** True once the WebView's cookies prove a completed YouTube sign-in. */
    fun looksSignedIn(cookieHeader: String?): Boolean {
        val names = cookieHeader.orEmpty().split(';').map { it.substringBefore('=').trim() }.toSet()
        return "SAPISID" in names && "LOGIN_INFO" in names
    }

    /** Takes over the WebView session: stores the cookies, then loads the account's name. */
    suspend fun completeSignIn(cookieHeader: String) = withContext(Dispatchers.IO) {
        synchronized(cookies) {
            cookies.clear()
            parseCookieHeader(cookieHeader, cookies)
        }
        persistCookies()
        refreshClientConfig()
        val menu = InnerTube.post("account/account_menu", JSONObject())
        var found: Profile? = null
        menu.walk { key, obj ->
            if (key == "activeAccountHeaderRenderer" && found == null) {
                found = Profile(
                    name = obj.text("accountName") ?: "YouTube account",
                    handle = obj.text("channelHandle"),
                    avatar = obj.optJSONObject("accountPhoto")?.thumbnails()?.lastOrNull(),
                )
            }
        }
        val p = found ?: Profile("YouTube account", null, null)
        store?.apply {
            put(KEY_NAME, p.name); put(KEY_HANDLE, p.handle); put(KEY_AVATAR, p.avatar)
        }
        withContext(Dispatchers.Main) { profile = p }
    }

    fun signOut() {
        synchronized(cookies) { cookies.clear() }
        store?.clear()
        profile = null
        android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
    }

    /** youtube.com embeds the current web client version; requests with a stale one get odd results. */
    private fun refreshClientConfig() {
        runCatching {
            val request = Request.Builder().url("https://www.youtube.com/?hl=en")
                .header("User-Agent", Net.USER_AGENT)
                .header("Cookie", cookieHeader())
                .build()
            Net.client.newCall(request).execute().use { r ->
                absorbSetCookies(r.headers("Set-Cookie"))
                val html = r.body?.string().orEmpty()
                Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { clientVersion = it }
                Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { visitorData = it }
            }
            store?.put(KEY_CLIENT_VERSION, clientVersion)
            store?.put(KEY_VISITOR, visitorData)
        }
    }

    internal fun clientVersion() = clientVersion
    internal fun visitorData() = visitorData

    internal fun cookieHeader(): String = synchronized(cookies) { cookies.entries.joinToString("; ") { "${it.key}=${it.value}" } }

    /** youtube.com's own request signature: SHA-1 of "timestamp SAPISID origin". */
    internal fun authorization(origin: String = ORIGIN): String? {
        val ts = System.currentTimeMillis() / 1000
        fun hash(secret: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("$ts $secret $origin".toByteArray())
            return "${ts}_" + digest.joinToString("") { "%02x".format(it) }
        }
        val (sapisid, p1, p3) = synchronized(cookies) {
            Triple(cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"], cookies["__Secure-1PAPISID"], cookies["__Secure-3PAPISID"])
        }
        sapisid ?: return null
        return buildString {
            append("SAPISIDHASH ").append(hash(sapisid))
            p1?.let { append(" SAPISID1PHASH ").append(hash(it)) }
            p3?.let { append(" SAPISID3PHASH ").append(hash(it)) }
        }
    }

    /** Merges rotated cookies from a response (Google refreshes __Secure-*PSIDTS regularly). */
    internal fun absorbSetCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        var changed = false
        synchronized(cookies) {
            setCookies.forEach { header ->
                val pair = header.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isEmpty()) return@forEach
                if (cookies[name] != value) { cookies[name] = value; changed = true }
            }
        }
        if (changed) persistCookies()
    }

    private fun persistCookies() = store?.put(KEY_COOKIES, cookieHeader())

    private fun parseCookieHeader(header: String, into: MutableMap<String, String>) {
        header.split(';').forEach { part ->
            val name = part.substringBefore('=').trim()
            if (name.isNotEmpty()) into[name] = part.substringAfter('=', "").trim()
        }
    }

    const val ORIGIN = "https://www.youtube.com"
    private const val FALLBACK_CLIENT_VERSION = "2.20260901.00.00"
    private const val KEY_COOKIES = "cookies"
    private const val KEY_CLIENT_VERSION = "client_version"
    private const val KEY_VISITOR = "visitor"
    private const val KEY_NAME = "name"
    private const val KEY_HANDLE = "handle"
    private const val KEY_AVATAR = "avatar"
}

/**
 * YouTube's internal web API ("InnerTube"), signed in. Reads: home, subscriptions,
 * channels, history, playlists. Writes: like/dislike, subscribe, Watch later, watch history
 * and watch time.
 */
object InnerTube {
    private const val BASE = "https://www.youtube.com/youtubei/v1/"
    private val JSON_TYPE = "application/json".toMediaType()

    /** Signed in when there's a session, otherwise anonymous (public pages like channels). */
    internal fun postAny(endpoint: String, body: JSONObject): JSONObject =
        if (Account.signedIn) post(endpoint, body) else post(endpoint, body, anonymous = true)

    internal fun post(endpoint: String, body: JSONObject, anonymous: Boolean = false): JSONObject {
        val auth = if (anonymous) null else Account.authorization() ?: throw NotSignedInException()
        body.put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "WEB")
                    .put("clientVersion", Account.clientVersion())
                    .put("hl", LocaleSettings.language)
                    .put("gl", LocaleSettings.country)
                    .apply { Account.visitorData()?.let { put("visitorData", it) } },
            ),
        )
        val request = Request.Builder()
            .url("$BASE$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", Net.USER_AGENT)
            .apply { if (auth != null) { header("Cookie", Account.cookieHeader()); header("Authorization", auth) } }
            .header("Origin", Account.ORIGIN)
            .header("X-Origin", Account.ORIGIN)
            .header("Referer", Account.ORIGIN + "/")
            .header("X-Goog-AuthUser", "0")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", Account.clientVersion())
            .apply { Account.visitorData()?.let { header("X-Goog-Visitor-Id", it) } }
            .build()
        Net.client.newCall(request).execute().use { r ->
            if (auth != null) Account.absorbSetCookies(r.headers("Set-Cookie"))
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw IOException("YouTube rejected the session (${r.code}). Try signing in again.")
            if (!r.isSuccessful) throw IOException("YouTube returned ${r.code}")
            return JSONObject(text)
        }
    }

    // ── Reads ──────────────────────────────────────────────────────────────────

    suspend fun browse(browseId: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation) else JSONObject().put("browseId", browseId)
        parseFeed(post("browse", body))
    }

    suspend fun home(next: String? = null) = browse("FEwhat_to_watch", next)

    /**
     * Beranda akun; kalau kosong (akun baru yang belum punya riwayat tontonan), pakai beranda umum
     * YouTube, lalu pencarian populer, supaya layar tidak kosong. Halaman cadangan tidak punya halaman lanjutan.
     */
    suspend fun homeOrFallback(next: String? = null): FeedPage {
        val own = home(next)
        if (next != null || own.videos.isNotEmpty()) return own
        val general = runCatching {
            withContext(Dispatchers.IO) { parseFeed(post("browse", JSONObject().put("browseId", "FEwhat_to_watch"), anonymous = true)) }
        }.getOrNull()
        if (general != null && general.videos.isNotEmpty()) return general.copy(next = null, chips = emptyList())
        val popular = runCatching {
            withContext(Dispatchers.IO) { parseFeed(post("search", JSONObject().put("query", "video populer hari ini"), anonymous = true)) }
        }.getOrNull()
        return popular?.takeIf { it.videos.isNotEmpty() }?.copy(next = null, chips = emptyList()) ?: own
    }

    /** Siaran langsung yang sedang berlangsung (pencarian dengan filter "Live"), tanpa perlu akun. */
    suspend fun liveNow(): List<VideoItem> = withContext(Dispatchers.IO) {
        parseFeed(postAny("search", JSONObject().put("query", "live").put("params", "EgJAAQ%3D%3D")))
            .videos.filter { it.live }.take(12)
    }

    /** A channel's uploads (Videos tab), members-only ones filtered out like everywhere else. */
    /**
     * Views and age of a video's related list, straight from YouTube's watch page data. The extractor
     * often returns related videos without a date; these fill it in (matched by video id).
     */
    internal fun relatedMeta(videoId: String): Map<String, VideoItem> {
        val out = LinkedHashMap<String, VideoItem>()
        post("next", JSONObject().put("videoId", videoId)).forEachObject { obj ->
            if (obj.has("videoId") && obj.has("title") && (obj.has("viewCountText") || obj.has("publishedTimeText"))) {
                parseVideoRenderer(obj)?.let { out.putIfAbsent(it.id, it) }
            } else if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO") {
                parseLockup(obj)?.let { out.putIfAbsent(it.id, it) }
            }
        }
        return out
    }

    /**
     * Semua unggahan channel (video, Shorts, siaran langsung) dari daftar unggahan resmi YouTube ("UU…"),
     * terbaru dulu; halaman berikutnya lewat [continuation].
     */
    suspend fun channelUploads(channelId: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation)
        else JSONObject().put("browseId", "VLUU" + channelId.removePrefix("UC"))
        parseFeed(postAny("browse", body))
    }

    suspend fun channelVideos(channelId: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation)
        else JSONObject().put("browseId", channelId).put("params", CHANNEL_VIDEOS_TAB)
        parseFeed(postAny("browse", body))
    }

    /** Tab channel yang dikenal, dengan params bawaan kalau halaman channel tidak menyebutkannya. */
    internal val CHANNEL_TAB_FALLBACK = mapOf(
        "videos" to CHANNEL_VIDEOS_TAB,
        "shorts" to "EgZzaG9ydHPyBgUKA5oBAA%3D%3D",
        "live" to "EgdzdHJlYW1z8gYECgJ6AA%3D%3D",
        "playlists" to "EglwbGF5bGlzdHPyBgQKAkIA",
        "posts" to "Egljb21tdW5pdHnyBgQKAkoA",
    )

    private val HANDLE = Regex("^@[A-Za-z0-9._\\-]+$")
    private val VIDEO_COUNT = Regex("(?i)\\d[\\d.,\\s]*(rb|jt|k|m|b)?\\s*videos?\\b")
    private val SUB_COUNT = Regex("(?i)\\d[\\d.,\\s]*(rb|jt|k|m|b)?\\s*(subscribers?|pelanggan)\\b")

    /** Username @, jumlah video, dan tab-tab dari halaman utama channel. */
    suspend fun channelMeta(channelId: String): ChannelMeta = withContext(Dispatchers.IO) {
        parseChannelMeta(postAny("browse", JSONObject().put("browseId", channelId)))
    }

    internal fun parseChannelMeta(root: JSONObject): ChannelMeta {
        val tabs = LinkedHashMap<String, String>()
        var handle: String? = null
        var count = -1L
        var subs = -1L
        var name: String? = null
        var description: String? = null
        root.walk { key, obj ->
            when (key) {
                "tabRenderer" -> {
                    val endpoint = obj.optJSONObject("endpoint")
                    val url = endpoint?.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")?.optString("url").orEmpty()
                    val params = endpoint?.optJSONObject("browseEndpoint")?.optString("params").orEmpty()
                    val kind = when {
                        url.endsWith("/videos") -> "videos"
                        url.endsWith("/shorts") -> "shorts"
                        url.endsWith("/streams") -> "live"
                        url.endsWith("/playlists") -> "playlists"
                        url.endsWith("/community") || url.endsWith("/posts") -> "posts"
                        else -> null
                    }
                    if (kind != null && params.isNotBlank()) tabs.putIfAbsent(kind, params)
                }
                "channelMetadataRenderer" -> {
                    if (handle == null) obj.optString("vanityChannelUrl").substringAfterLast('/').takeIf { it.startsWith("@") }?.let { handle = it }
                    if (description == null) description = obj.optString("description").trim().ifBlank { null }
                }
            }
        }
        root.optJSONObject("header")?.walk { key, obj ->
            when (key) {
                "pageHeaderViewModel" -> {
                    if (name == null) {
                        name = obj.optJSONObject("title")?.optJSONObject("dynamicTextViewModel")?.optJSONObject("text")
                            ?.optString("content")?.trim()?.ifBlank { null }
                    }
                    if (description == null) {
                        description = obj.optJSONObject("description")?.optJSONObject("descriptionPreviewViewModel")
                            ?.optJSONObject("description")?.optString("content")?.trim()?.ifBlank { null }
                    }
                }
                "c4TabbedHeaderRenderer" -> {
                    if (name == null) name = obj.text("title")?.trim()?.ifBlank { null }
                    if (handle == null) obj.text("channelHandleText")?.trim()?.takeIf { HANDLE.matches(it) }?.let { handle = it }
                    if (count < 0) obj.text("videosCountText")?.let { count = parseViews(it) }
                    if (subs < 0) obj.text("subscriberCountText")?.let { subs = parseViews(it) }
                }
                "contentMetadataViewModel" -> obj.collectStrings("content").forEach { s ->
                    val t = s.trim()
                    if (handle == null && HANDLE.matches(t)) handle = t
                    else if (subs < 0 && SUB_COUNT.containsMatchIn(t)) subs = parseViews(t)
                    else if (count < 0 && VIDEO_COUNT.containsMatchIn(t)) count = parseViews(t)
                }
            }
        }
        return ChannelMeta(handle, count, tabs, subs, name, description)
    }

    /** Video atau siaran langsung dari satu tab channel (Video / Live), halaman berikutnya lewat [continuation]. */
    suspend fun channelTabFeed(channelId: String, params: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        parseFeed(postAny("browse", channelTabBody(channelId, params, continuation)))
    }

    /** Tab Shorts channel. */
    suspend fun channelShorts(channelId: String, params: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val page = parseFeed(postAny("browse", channelTabBody(channelId, params, continuation)))
        FeedPage(page.shorts, page.next)
    }

    suspend fun channelPlaylists(channelId: String, params: String, continuation: String? = null): Pair<List<PlaylistRef>, String?> = withContext(Dispatchers.IO) {
        parsePlaylistTab(postAny("browse", channelTabBody(channelId, params, continuation)))
    }

    suspend fun channelPosts(channelId: String, params: String, continuation: String? = null): Pair<List<ChannelPost>, String?> = withContext(Dispatchers.IO) {
        parsePosts(postAny("browse", channelTabBody(channelId, params, continuation)))
    }

    private fun channelTabBody(channelId: String, params: String, continuation: String?): JSONObject =
        if (continuation != null) JSONObject().put("continuation", continuation)
        else JSONObject().put("browseId", channelId).put("params", params)

    internal fun parsePlaylistTab(root: JSONObject): Pair<List<PlaylistRef>, String?> {
        val out = LinkedHashMap<String, PlaylistRef>()
        var next: String? = null
        root.walk { key, obj ->
            when (key) {
                "gridPlaylistRenderer", "playlistRenderer" -> {
                    val id = obj.optString("playlistId").ifBlank { return@walk }
                    out.getOrPut(id) {
                        PlaylistRef(
                            id, obj.text("title") ?: "",
                            obj.thumbnails()?.lastOrNull()?.let(::absoluteUrl) ?: obj.firstImageSource(),
                            obj.text("videoCountText") ?: obj.text("videoCountShortText"),
                        )
                    }
                }
                "lockupViewModel" -> if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                    val id = obj.optString("contentId").ifBlank { return@walk }
                    val meta = obj.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                    out.getOrPut(id) {
                        PlaylistRef(
                            id = id,
                            title = meta?.optJSONObject("title")?.optString("content").orEmpty(),
                            thumbnail = obj.firstImageSource(),
                            subtitle = obj.collectStrings("text").firstOrNull { it.contains("video", ignoreCase = true) },
                        )
                    }
                }
                "continuationItemRenderer", "continuationItemViewModel" ->
                    obj.collectStrings("token").firstOrNull()?.let { next = it }
            }
        }
        return out.values.toList() to next
    }

    internal fun parsePosts(root: JSONObject): Pair<List<ChannelPost>, String?> {
        val out = LinkedHashMap<String, ChannelPost>()
        var next: String? = null
        root.walk { key, obj ->
            when (key) {
                "backstagePostRenderer", "postRenderer" -> {
                    val id = obj.optString("postId").ifBlank { return@walk }
                    out.getOrPut(id) { parsePost(id, obj) }
                }
                "continuationItemRenderer", "continuationItemViewModel" ->
                    obj.collectStrings("token").firstOrNull()?.let { next = it }
            }
        }
        return out.values.toList() to next
    }

    /** Alamat gambar ukuran penuh, tanpa potongan (YouTube menyisipkan -c-fcrop di akhir alamat). */
    internal fun fullSizeImage(url: String): String {
        val u = if (url.startsWith("//")) "https:$url" else url
        return if (u.contains("ggpht") || u.contains("googleusercontent")) u.substringBefore("=") + "=s1600" else u
    }

    private fun lastThumb(arr: JSONArray?): String? =
        arr?.optJSONObject(arr.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }

    internal fun parsePost(id: String, obj: JSONObject): ChannelPost {
        val images = ArrayList<String>()
        val attachment = obj.optJSONObject("backstageAttachment")
        attachment?.walk { k, o ->
            if (k == "backstageImageRenderer") lastThumb(o.optJSONObject("image")?.optJSONArray("thumbnails"))?.let { images += fullSizeImage(it) }
        }
        if (images.isEmpty()) {
            attachment?.collectStrings("url")
                ?.firstOrNull { it.startsWith("http") && (it.contains("ggpht") || it.contains("ytimg") || it.contains("googleusercontent")) }
                ?.let { images += fullSizeImage(it) }
        }
        val actions = obj.optJSONObject("actionButtons")?.optJSONObject("commentActionButtonsRenderer")
        val likeButton = actions?.optJSONObject("likeButton")?.optJSONObject("toggleButtonRenderer")
        val dislikeButton = actions?.optJSONObject("dislikeButton")?.optJSONObject("toggleButtonRenderer")
        val reply = actions?.optJSONObject("replyButton")?.optJSONObject("buttonRenderer")
        val browse = reply?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        return ChannelPost(
            id = id,
            text = obj.text("contentText").orEmpty(),
            ago = obj.text("publishedTimeText"),
            likes = obj.text("voteCount"),
            image = images.firstOrNull(),
            images = images.distinct(),
            author = obj.text("authorText"),
            authorAvatar = lastThumb(obj.optJSONObject("authorThumbnail")?.optJSONArray("thumbnails"))?.let { if (it.startsWith("//")) "https:$it" else it },
            authorId = obj.optJSONObject("authorEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf { it.startsWith("UC") },
            comments = reply?.text("text"),
            commentBrowseId = browse?.optString("browseId")?.takeIf { it.isNotBlank() },
            commentParams = browse?.optString("params")?.takeIf { it.isNotBlank() },
            liked = likeButton?.optBoolean("isToggled") == true,
            disliked = dislikeButton?.optBoolean("isToggled") == true,
            likeParams = likeButton?.collectStrings("likeParams")?.firstOrNull(),
        )
    }

    /** Suka / tidak suka / batalkan suka untuk satu postingan komunitas. [action]: like, dislike, removelike. */
    suspend fun reactToPost(item: ChannelPost, action: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("target", JSONObject().put("postId", item.id))
        item.likeParams?.let { body.put("params", it) }
        post("like/$action", body)
        Unit
    }

    /** Komentar teratas satu postingan (halaman pertama), beserta tanda untuk menulis komentar baru. */
    suspend fun postComments(item: ChannelPost): CommentsPage = withContext(Dispatchers.IO) {
        val body = JSONObject().put("browseId", item.commentBrowseId ?: "FEpost_detail")
        item.commentParams?.let { body.put("params", it) }
        val page = postAny("browse", body)
        var token: String? = null
        var create: String? = null
        page.forEachObject { o ->
            if (token == null && o.optString("sectionIdentifier") == "comment-item-section") token = o.collectStrings("token").firstOrNull()
            if (create == null) create = o.optJSONObject("createCommentEndpoint")?.optString("createCommentParams")?.ifBlank { null }
        }
        val start = token ?: return@withContext CommentsPage(emptyList(), total = -1, disabled = true, next = null, createParams = create)
        var cont = runCatching { postAny("browse", JSONObject().put("continuation", start)) }.getOrNull()
        var parsed = cont?.let { CommentFeed.parsePage(it) }
        if (parsed == null || parsed.comments.isEmpty()) {
            cont = postAny("next", JSONObject().put("continuation", start))
            parsed = CommentFeed.parsePage(cont)
        }
        cont?.forEachObject { o ->
            if (create == null) create = o.optJSONObject("createCommentEndpoint")?.optString("createCommentParams")?.ifBlank { null }
        }
        parsed.copy(createParams = create)
    }

    /** Menulis komentar baru di postingan. */
    suspend fun createPostComment(createParams: String, text: String) = withContext(Dispatchers.IO) {
        post("comment/create_comment", JSONObject().put("commentText", text).put("createCommentParams", createParams))
        Unit
    }

    /** The channel page's Videos tab selector (as on youtube.com, 2026-10-06). */
    private const val CHANNEL_VIDEOS_TAB = "EgZ2aWRlb3PyBgQKAjoA"
    suspend fun subscriptionsFeed(next: String? = null) = browse("FEsubscriptions", next)
    suspend fun history(next: String? = null) = browse("FEhistory", next)
    suspend fun playlist(id: String, next: String? = null) = browse("VL$id", next)

    suspend fun channels(): List<ChannelRef> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, ChannelRef>()
        post("browse", JSONObject().put("browseId", "FEchannels")).walk { key, obj ->
            if (key == "channelRenderer" || key == "gridChannelRenderer") {
                val id = obj.optString("channelId").ifBlank { return@walk }
                out.getOrPut(id) {
                    ChannelRef(id, obj.text("title") ?: "", obj.thumbnails()?.lastOrNull()?.let(::absoluteUrl))
                }
            }
        }
        out.values.toList()
    }

    suspend fun playlists(): List<PlaylistRef> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, PlaylistRef>()
        post("browse", JSONObject().put("browseId", "FEplaylist_aggregation")).walk { key, obj ->
            when (key) {
                "gridPlaylistRenderer", "playlistRenderer" -> {
                    val id = obj.optString("playlistId").ifBlank { return@walk }
                    out.getOrPut(id) {
                        PlaylistRef(id, obj.text("title") ?: "", obj.thumbnails()?.lastOrNull()?.let(::absoluteUrl), obj.text("videoCountText") ?: obj.text("videoCountShortText"))
                    }
                }
                "lockupViewModel" -> if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                    val id = obj.optString("contentId").ifBlank { return@walk }
                    val meta = obj.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                    out.getOrPut(id) {
                        PlaylistRef(
                            id = id,
                            title = meta?.optJSONObject("title")?.optString("content").orEmpty(),
                            thumbnail = obj.firstImageSource(),
                            subtitle = obj.collectStrings("text").firstOrNull { it.contains("video", ignoreCase = true) },
                        )
                    }
                }
            }
        }
        out.values.filterNot { it.id == "WL" || it.id == "LL" }
    }

    /** Like state and subscription for a video, from the signed-in watch page data. */
    suspend fun videoState(videoId: String): VideoAccountState = withContext(Dispatchers.IO) {
        val next = post("next", JSONObject().put("videoId", videoId))
        var like = LikeStatus.None
        var subscribed: Boolean? = null
        var channelId: String? = null
        next.walk { key, obj ->
            when (key) {
                "likeStatusEntity" -> like = when (obj.optString("likeStatus")) {
                    "LIKE" -> LikeStatus.Like
                    "DISLIKE" -> LikeStatus.Dislike
                    else -> like
                }
                "subscribeButtonRenderer" -> {
                    if (obj.has("subscribed")) subscribed = obj.optBoolean("subscribed")
                    obj.optString("channelId").ifBlank { null }?.let { channelId = it }
                }
                "subscriptionStateEntity" -> if (obj.has("subscribed")) subscribed = obj.optBoolean("subscribed")
                "videoOwnerRenderer" -> obj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")?.takeIf { it.startsWith("UC") }?.let { if (channelId == null) channelId = it }
            }
        }
        VideoAccountState(like, subscribed, channelId)
    }

    /** Whether the account follows [channelId] (from the channel page's subscribe button). */
    suspend fun channelSubscribed(channelId: String): Boolean? = withContext(Dispatchers.IO) {
        var subscribed: Boolean? = null
        post("browse", JSONObject().put("browseId", channelId)).walk { key, obj ->
            if ((key == "subscribeButtonRenderer" || key == "subscriptionStateEntity") && obj.has("subscribed") && subscribed == null) {
                subscribed = obj.optBoolean("subscribed")
            }
        }
        subscribed
    }

    // ── Shorts ─────────────────────────────────────────────────────────────────

    /**
     * One page of the Shorts feed; [token] null for the first. YouTube's Shorts feed is no browse
     * page but the "reel sequence" that follows one short (reel/reel_watch_sequence, as on
     * youtube.com/shorts). The first page starts from a seed: signed in, the first short on your
     * Home (so the sequence is yours); else, or with none there, one from a public "shorts"
     * search — the sequence then works anonymously (checked 2026-10-28). The seed plays first.
     */
    suspend fun shorts(token: String? = null): FeedPage = withContext(Dispatchers.IO) {
        if (token != null) return@withContext parseShorts(postAny("reel/reel_watch_sequence", JSONObject().put("sequenceParams", token)))
        val seed = shortsSeed() ?: throw IOException("YouTube didn't offer any Shorts right now.")
        val page = parseShorts(postAny("reel/reel_watch_sequence", JSONObject().put("sequenceParams", reelSequenceParams(seed))))
        page.copy(videos = listOf(shortItem(seed, null)) + page.videos.filter { it.id != seed })
    }

    private fun shortsSeed(): String? {
        if (Account.signedIn) {
            runCatching { reelIds(post("browse", JSONObject().put("browseId", "FEwhat_to_watch"))).firstOrNull() }
                .getOrNull()?.let { return it }
        }
        // A different public short each time, so the generic feed doesn't always start the same way.
        return reelIds(postAny("search", JSONObject().put("query", "shorts"))).take(10).randomOrNull()
    }

    /** Every short a response links to (Home's Shorts shelf, search results), in order. */
    private fun reelIds(root: JSONObject): List<String> {
        val ids = LinkedHashSet<String>()
        root.walk { key, obj -> if (key == "reelWatchEndpoint") obj.optString("videoId").takeIf { it.length == 11 }?.let(ids::add) }
        return ids.toList()
    }

    /**
     * The sequence's start, as youtube.com encodes it (the ReelSequence protobuf, per YouTube.js):
     * 1 = the seed's id, 5 = params { 3 = 5 }, 10 = 25. Written by hand; no protobuf library.
     */
    internal fun reelSequenceParams(shortId: String): String {
        val id = shortId.toByteArray()
        val bytes = byteArrayOf(0x0A, id.size.toByte()) + id + byteArrayOf(0x2A, 0x02, 0x18, 0x05, 0x50, 25)
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * A reel sequence: each `entries[]` item's command carries a reelWatchEndpoint (the short's
     * id and its 9:16 first frame); the next page is `continuationEndpoint`'s token. Titles and
     * channels aren't in it; they come with the streams (YouTube.details).
     */
    internal fun parseShorts(root: JSONObject): FeedPage {
        val videos = LinkedHashMap<String, VideoItem>()
        val entries = root.optJSONArray("entries")
        for (i in 0 until (entries?.length() ?: 0)) {
            val reel = entries?.optJSONObject(i)?.optJSONObject("command")?.optJSONObject("reelWatchEndpoint") ?: continue
            val id = reel.optString("videoId").takeIf { it.length == 11 } ?: continue
            val frame = reel.optJSONObject("thumbnail")?.collectStrings("url")?.firstOrNull()
            videos.putIfAbsent(id, shortItem(id, frame))
        }
        val next = root.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token")?.ifBlank { null }
        return FeedPage(videos.values.toList(), next)
    }

    /** A short before its details load: just its id and its first frame (9:16). */
    private fun shortItem(id: String, frame: String?) = VideoItem(
        id = id, title = "", channel = "", channelUrl = null,
        thumbnail = frame ?: "https://i.ytimg.com/vi/$id/oar2.jpg",
        avatar = null, durationSec = 0, views = 0, uploaded = null,
    )

    /** Pembuat sebuah Short, dari data halaman tontonnya (ringan: satu permintaan JSON, tanpa mengurai aliran). */
    suspend fun shortOwner(videoId: String): ShortOwner? = withContext(Dispatchers.IO) {
        var owner: ShortOwner? = null
        postAny("next", JSONObject().put("videoId", videoId)).walk { key, obj ->
            if (key == "videoOwnerRenderer" && owner == null) {
                val name = obj.text("title")
                if (name != null) {
                    owner = ShortOwner(
                        name = name,
                        avatar = obj.thumbnails()?.lastOrNull()?.let(::absoluteUrl),
                        channelId = obj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                            ?.optString("browseId")?.takeIf { it.startsWith("UC") },
                    )
                }
            }
        }
        owner
    }

    /**
     * Halaman hasil pencarian asli YouTube (bahasa dan nama channel sama dengan aplikasi YouTube): video, dan di
     * halaman pertama juga rak Shorts, kartu channel yang cocok, dan playlist. Halaman berikutnya lewat [continuation].
     */
    suspend fun searchPage(query: String, continuation: String? = null): SearchPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation) else JSONObject().put("query", query)
        val root = postAny("search", body)
        val feed = parseFeed(root)
        if (continuation != null) SearchPage(feed, emptyList(), emptyList())
        else SearchPage(feed, parseChannelResults(root).take(3), parsePlaylistTab(root).first.take(6))
    }

    /** Short tambahan (pencarian #shorts) kalau rak Shorts bawaan hasil pencarian sedikit. */
    suspend fun searchMoreShorts(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        parseFeed(postAny("search", JSONObject().put("query", "$query #shorts"))).shorts
    }

    /** Kartu channel di hasil pencarian: nama, @handle, jumlah pelanggan, foto profil. */
    private fun parseChannelResults(root: JSONObject): List<ChannelResult> {
        val out = LinkedHashMap<String, ChannelResult>()
        root.walk { key, obj ->
            if (key == "channelRenderer") {
                val id = obj.optString("channelId").takeIf { it.startsWith("UC") } ?: return@walk
                val name = obj.text("title") ?: return@walk
                val texts = listOfNotNull(obj.text("subscriberCountText"), obj.text("videoCountText"))
                out.putIfAbsent(
                    id,
                    ChannelResult(
                        id = id, name = name,
                        avatar = obj.thumbnails()?.lastOrNull()?.let(::absoluteUrl),
                        handle = texts.firstOrNull { it.startsWith("@") },
                        subscribers = texts.firstOrNull { !it.startsWith("@") },
                    ),
                )
            }
        }
        return out.values.toList()
    }

    // ── Writes ─────────────────────────────────────────────────────────────────

    suspend fun setLike(videoId: String, status: LikeStatus) = withContext(Dispatchers.IO) {
        val endpoint = when (status) {
            LikeStatus.Like -> "like/like"
            LikeStatus.Dislike -> "like/dislike"
            LikeStatus.None -> "like/removelike"
        }
        post(endpoint, JSONObject().put("target", JSONObject().put("videoId", videoId)))
        Unit
    }

    suspend fun setSubscribed(channelId: String, subscribed: Boolean) = withContext(Dispatchers.IO) {
        post(
            if (subscribed) "subscription/subscribe" else "subscription/unsubscribe",
            JSONObject().put("channelIds", JSONArray().put(channelId)),
        )
        Unit
    }

    suspend fun setInWatchLater(videoId: String, add: Boolean) = setInPlaylist("WL", videoId, add)

    suspend fun setInPlaylist(playlistId: String, videoId: String, add: Boolean) = withContext(Dispatchers.IO) {
        val action = if (add) JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", videoId)
        else JSONObject().put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID").put("removedVideoId", videoId)
        post("browse/edit_playlist", JSONObject().put("playlistId", playlistId).put("actions", JSONArray().put(action)))
        Unit
    }

    /**
     * The account's playlists for [videoId], Watch later first, each saying whether the video is
     * in it: what youtube.com's "Save to…" dialog lists (GitHub issue #12). If that answer can't
     * be read, the Library's playlists stand in, without knowing which hold the video.
     */
    suspend fun saveChoices(videoId: String): List<PlaylistChoice> = withContext(Dispatchers.IO) {
        val answer = post(
            "playlist/get_add_to_playlist",
            JSONObject().put("videoIds", JSONArray().put(videoId)).put("excludeWatchLater", false),
        )
        parseSaveChoices(answer).ifEmpty {
            Log.w("Plaza", "playlists: no choices in the Save dialog's answer, using the Library's list")
            playlists().map { PlaylistChoice(it.id, it.title, contains = null, privacy = null) }
        }
    }

    /** A new playlist holding [videoId]; returns its id. [privacy]: PRIVATE, UNLISTED or PUBLIC. */
    suspend fun createPlaylist(title: String, privacy: String, videoId: String): String = withContext(Dispatchers.IO) {
        val answer = post(
            "playlist/create",
            JSONObject().put("title", title).put("privacyStatus", privacy).put("videoIds", JSONArray().put(videoId)),
        )
        answer.optString("playlistId").ifBlank { throw IOException("YouTube didn't create the playlist") }
    }

    /** One video being watched: the tracking URL for its watch-time pings. */
    class WatchSession(val videoId: String, val watchtimeUrl: String, val cpn: String)

    /**
     * Starts watching [videoId] on the account, as the web player does: fetch the signed-in
     * player response and ping its playback URL, which puts the video in watch history.
     * Returns the session for [pingWatchtime], or null if YouTube gave no tracking URLs.
     */
    fun startWatch(videoId: String): WatchSession? {
        // Seperti browser: alamat pencatatan diambil dari halaman tonton yang dimuat dengan cookie akun,
        // jadi terikat ke akun yang masuk. Kalau gagal, coba API pemutar web, lalu klien iOS.
        val notes = ArrayList<String>()
        var source = "halaman"
        var tracking = pageTracking(videoId)
        if (tracking == null) {
            notes += "halaman: kosong"
            val web = runCatching { post("player", JSONObject().put("videoId", videoId)) }
            tracking = web.getOrNull()?.optJSONObject("playbackTracking")
            source = "web"
            if (tracking == null) {
                notes += "web: " + (
                    web.exceptionOrNull()?.message
                        ?: web.getOrNull()?.optJSONObject("playabilityStatus")?.optString("status")?.ifBlank { null }
                        ?: "kosong"
                    )
                tracking = iosTracking(videoId)
                source = "iOS"
            }
        }
        if (tracking == null) {
            WatchDiagnostics.report("Gagal: YouTube tidak memberi alamat pencatatan (${notes.joinToString("; ")})")
            return null
        }
        val cpn = (1..16).map { CPN_ALPHABET[Random.nextInt(CPN_ALPHABET.length)] }.joinToString("")
        var recorded = false
        var failure: String? = null
        tracking.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl")?.ifBlank { null }?.let { base ->
            runCatching {
                ping(
                    base,
                    "ver=2&cpn=$cpn&cmt=0&el=detailpage&c=WEB&cver=${Account.clientVersion()}" +
                        "&hl=${LocaleSettings.language}&cr=${LocaleSettings.country}",
                )
            }.onSuccess { recorded = true }.onFailure { failure = it.message }
        }
        val tried = if (notes.isEmpty()) "" else " · gagal dulu: ${notes.joinToString("; ")}"
        WatchDiagnostics.report(
            if (recorded) "Tercatat ke akun ($source)$tried"
            else "Gagal mengirim ke YouTube ($source): ${failure ?: "tidak ada alamat pencatatan"}$tried",
        )
        val watchtime = tracking.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl")?.ifBlank { null } ?: return null
        return WatchSession(videoId, watchtime, cpn)
    }

    /** Alamat pencatatan dari halaman tonton youtube.com, dimuat dengan cookie akun (cara browser). */
    private fun pageTracking(videoId: String): JSONObject? = runCatching {
        val request = Request.Builder().url("https://www.youtube.com/watch?v=$videoId&hl=${LocaleSettings.language}")
            .header("User-Agent", Net.USER_AGENT)
            .header("Cookie", Account.cookieHeader())
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        Net.client.newCall(request).execute().use { r ->
            Account.absorbSetCookies(r.headers("Set-Cookie"))
            if (!r.isSuccessful) return@use null
            val html = r.body?.string().orEmpty()
            val key = "\"playbackTracking\":"
            val at = html.indexOf(key)
            if (at < 0) return@use null
            val start = html.indexOf('{', at + key.length)
            if (start < 0) null else JSONObject(balancedObject(html, start))
        }
    }.getOrNull()

    /** Potongan objek JSON yang dimulai di [start]: sampai kurung kurawal penutupnya (mengabaikan isi teks). */
    private fun balancedObject(text: String, start: Int): String {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
            } else if (ch == '"') {
                inString = true
            } else if (ch == '{') {
                depth++
            } else if (ch == '}') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
            i++
        }
        throw IllegalStateException("objek JSON tidak lengkap")
    }

    private fun iosTracking(videoId: String): JSONObject? {
        val client = JSONObject()
            .put("clientName", "IOS").put("clientVersion", "20.10.4")
            .put("deviceMake", "Apple").put("deviceModel", "iPhone16,2")
            .put("osName", "iPhone").put("osVersion", "18.3.2.22D82")
            .put("hl", LocaleSettings.language).put("gl", LocaleSettings.country)
        val body = JSONObject().put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true)
            .put("context", JSONObject().put("client", client))
        val request = Request.Builder().url("${BASE}player?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)")
            .header("X-YouTube-Client-Name", "5").header("X-YouTube-Client-Version", "20.10.4")
            .build()
        return runCatching {
            Net.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optJSONObject("playbackTracking")
            }
        }.getOrNull()
    }

    /**
     * Reports what was played since the last ping: the stretches of the video ([starts] →
     * [ends], seconds), where playback is now ([positionSec]) and how long the session has
     * run ([elapsedSec]). The same parameters the web player sends; YouTube's
     * recommendations are built on this watch time.
     */
    fun pingWatchtime(session: WatchSession, positionSec: Double, starts: List<Double>, ends: List<Double>, playing: Boolean, elapsedSec: Double, final: Boolean) {
        fun f(x: Double) = String.format(java.util.Locale.US, "%.3f", x)
        val rt = f(elapsedSec)
        val query = buildString {
            append("ver=2&cpn=").append(session.cpn)
            append("&cmt=").append(f(positionSec))
            append("&st=").append(starts.joinToString(",") { f(it) })
            append("&et=").append(ends.joinToString(",") { f(it) })
            append("&state=").append(if (playing) "playing" else "paused")
            append("&rt=").append(rt).append("&rtn=").append(rt).append("&rti=").append(elapsedSec.toInt())
            append("&lact=").append(Random.nextInt(800, 4000))
            append("&volume=100&muted=0&fs=0&hl=en_US&cr=US&c=WEB&cver=").append(Account.clientVersion())
            if (final) append("&final=1")
        }
        ping(session.watchtimeUrl, query)
    }

    private fun ping(base: String, query: String) {
        val request = Request.Builder().url(base + (if ('?' in base) "&" else "?") + query)
            .header("User-Agent", Net.USER_AGENT)
            .header("Cookie", Account.cookieHeader())
            .header("Referer", Account.ORIGIN + "/")
            .build()
        Net.client.newCall(request).execute().use { r ->
            Account.absorbSetCookies(r.headers("Set-Cookie"))
            if (!r.isSuccessful) throw IOException("stats ping ${r.code}")
        }
    }

    private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    // ── Parsing ────────────────────────────────────────────────────────────────

    /**
     * Pulls every video out of a browse response, whichever renderer YouTube used:
     * the classic videoRenderer family or the newer lockupViewModel. Shorts are skipped.
     */
    internal fun parseFeed(root: JSONObject): FeedPage {
        val videos = LinkedHashMap<String, VideoItem>()
        val shorts = LinkedHashMap<String, VideoItem>()
        val posts = LinkedHashMap<String, ChannelPost>()
        val chips = ArrayList<FeedChip>()
        var next: String? = null
        root.walk { key, obj ->
            when (key) {
                "chipCloudChipRenderer" -> {
                    val label = obj.text("text").orEmpty()
                    val token = obj.optJSONObject("navigationEndpoint")?.optJSONObject("continuationCommand")
                        ?.optString("token")?.ifBlank { null }
                    if (label.isNotBlank() && token != null && !obj.optBoolean("isSelected")) chips += FeedChip(label, token)
                }
                "backstagePostRenderer", "postRenderer" -> obj.optString("postId").takeIf { it.isNotBlank() }?.let { id -> posts.getOrPut(id) { parsePost(id, obj) } }
                "reelItemRenderer", "shortsLockupViewModel" -> parseShortCard(key, obj)?.let { shorts.putIfAbsent(it.id, it) }
                // Members-only videos can't be played (anonymous extraction), and full movies/TV
                // aren't wanted (Derek), so neither is ever shown.
                "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" ->
                    if (!isMembersOnly(obj) && !isMovie(obj)) parseVideoRenderer(obj)?.let { videos.putIfAbsent(it.id, it) }
                "lockupViewModel" -> if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" && !isMembersOnly(obj) && !isMovie(obj)) {
                    parseLockup(obj)?.let { videos.putIfAbsent(it.id, it) }
                }
                // Only the feed's own "load more" item (old and new names); chip filters
                // carry tokens too and must not be mistaken for the next page.
                "continuationItemRenderer", "continuationItemViewModel" ->
                    obj.collectStrings("token").firstOrNull()?.let { next = it }
            }
        }
        return FeedPage(videos.values.toList(), next, shorts.values.toList(), posts.values.toList(), chips)
    }

    /** Kartu Short di feed atau tab Shorts channel: renderer lama (reelItemRenderer) atau baru (shortsLockupViewModel). */
    private fun parseShortCard(key: String, o: JSONObject): VideoItem? {
        val id: String
        val title: String
        val views: String?
        val frame: String?
        if (key == "reelItemRenderer") {
            id = o.optString("videoId").takeIf { it.length == 11 } ?: return null
            title = o.text("headline").orEmpty()
            views = o.text("viewCountText")
            frame = o.thumbnails()?.lastOrNull()
        } else {
            id = o.collectStrings("videoId").firstOrNull { it.length == 11 } ?: return null
            val overlay = o.optJSONObject("overlayMetadata")
            title = overlay?.optJSONObject("primaryText")?.optString("content").orEmpty()
            views = overlay?.optJSONObject("secondaryText")?.optString("content")?.ifBlank { null }
            frame = o.optJSONObject("thumbnailViewModel")?.collectStrings("url")?.firstOrNull()
                ?: o.optJSONObject("thumbnail")?.collectStrings("url")?.firstOrNull()
        }
        return VideoItem(
            id = id, title = title, channel = "", channelUrl = null,
            thumbnail = frame?.let(::absoluteUrl) ?: "https://i.ytimg.com/vi/$id/oar2.jpg",
            avatar = null, durationSec = 0, views = parseViews(views), uploaded = null,
        )
    }

    /** Siaran langsung yang sedang berlangsung: lencana LIVE (renderer lama dan lockup baru). */
    internal fun isLiveNow(o: JSONObject): Boolean =
        o.collectStrings("style").any { it == "BADGE_STYLE_TYPE_LIVE_NOW" || it == "LIVE" } ||
            o.collectStrings("badgeStyle").any { it.contains("LIVE") }

    /** YouTube's "Members only" badge: BADGE_MEMBERS_ONLY (lockups) / BADGE_STYLE_TYPE_MEMBERS_ONLY (older renderers). */
    internal fun isMembersOnly(o: JSONObject): Boolean =
        o.collectStrings("badgeStyle").any { it.contains("MEMBERS_ONLY") } ||
            o.collectStrings("style").any { it == "BADGE_STYLE_TYPE_MEMBERS_ONLY" }

    /**
     * Full movies and TV (Primetime Channels like HBO Max / Shudder, YouTube Movies): a paid-offer
     * badge ("Try now", "Buy or rent") or a content rating (R, PG-13, TV-MA), which ordinary
     * uploads never carry. Older renderers: BADGE_STYLE_TYPE_YPC / BADGE_STYLE_TYPE_MEDIA;
     * lockups: a BADGE_DEFAULT whose text is the rating (not every movie has one) or a
     * BADGE_COMMERCE offer — ordinary uploads use that style only for "Fundraiser".
     */
    internal fun isMovie(o: JSONObject): Boolean {
        if (o.collectStrings("style").any { it == "BADGE_STYLE_TYPE_YPC" || it == "BADGE_STYLE_TYPE_MEDIA" }) return true
        var movie = false
        o.walk { key, badge ->
            if (key != "badgeViewModel") return@walk
            val text = badge.optString("badgeText")
            if (CONTENT_RATING.matches(text) ||
                (badge.optString("badgeStyle") == "BADGE_COMMERCE" && !text.equals("Fundraiser", ignoreCase = true))
            ) movie = true
        }
        return movie
    }

    private val CONTENT_RATING = Regex("^(G|PG|PG-13|R|NC-17|NR|TV-(Y|Y7|G|PG|14|MA))$")

    private fun parseVideoRenderer(o: JSONObject): VideoItem? {
        val id = o.optString("videoId").ifBlank { return null }
        // Siaran terjadwal belum bisa diputar.
        if (o.has("upcomingEventData")) return null
        val byline = o.optJSONObject("ownerText") ?: o.optJSONObject("shortBylineText") ?: o.optJSONObject("longBylineText")
        val channelRun = byline?.optJSONArray("runs")?.optJSONObject(0)
        val channelId = channelRun?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
        val duration = o.text("lengthText")
            ?: o.optString("lengthSeconds").toLongOrNull()?.let { formatDuration(it * 1000) }
            ?: o.optJSONArray("thumbnailOverlays")?.let { arr ->
                (0 until arr.length()).firstNotNullOfOrNull { arr.optJSONObject(it)?.optJSONObject("thumbnailOverlayTimeStatusRenderer")?.let { r -> r.text("text") } }
            }
        // playlistVideoRenderer packs "views • age" into videoInfo runs.
        val infoRuns = o.optJSONObject("videoInfo")?.optJSONArray("runs")?.let { runs ->
            (0 until runs.length()).map { runs.optJSONObject(it).optString("text") }.filter { it.isNotBlank() && it.trim() != "•" }
        }.orEmpty()
        val avatar = o.optJSONObject("channelThumbnailSupportedRenderers")
            ?.optJSONObject("channelThumbnailWithLinkRenderer")?.thumbnails()?.lastOrNull()
            ?: o.optJSONObject("channelThumbnail")?.thumbnails()?.lastOrNull()
        return VideoItem(
            id = id,
            title = o.text("title") ?: return null,
            channel = channelRun?.optString("text").orEmpty(),
            channelUrl = channelId?.takeIf { it.startsWith("UC") }?.let { "https://www.youtube.com/channel/$it" },
            thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            avatar = avatar?.let(::absoluteUrl),
            durationSec = parseDuration(duration),
            views = parseViews(o.text("viewCountText") ?: o.text("shortViewCountText") ?: infoRuns.firstOrNull { it.contains("view") } ?: infoRuns.firstOrNull()),
            uploaded = o.text("publishedTimeText") ?: infoRuns.firstOrNull { it.contains("ago") } ?: infoRuns.getOrNull(1),
            watched = watchedFraction(o),
            live = isLiveNow(o),
        )
    }

    /** The red resume bar's share of a video (renderers: percentDurationWatched, lockups: startPercent). */
    private fun watchedFraction(o: JSONObject): Float? {
        var percent: Int? = null
        o.walk { key, obj ->
            if (percent != null) return@walk
            when (key) {
                "thumbnailOverlayResumePlaybackRenderer" -> percent = obj.optInt("percentDurationWatched", -1).takeIf { it >= 0 }
                "thumbnailOverlayProgressBarViewModel" -> percent = obj.optInt("startPercent", -1).takeIf { it >= 0 }
            }
        }
        return percent?.takeIf { it in 1..99 }?.let { it / 100f }
    }

    private fun parseLockup(o: JSONObject): VideoItem? {
        val id = o.optString("contentId").ifBlank { return null }
        val meta = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = meta.optJSONObject("title")?.optString("content")?.ifBlank { null } ?: return null
        // metadataRows: [ [channel], [views, age] ] as text parts.
        val rows = mutableListOf<List<String>>()
        meta.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")?.let { arr ->
            for (r in 0 until arr.length()) {
                val ps = arr.optJSONObject(r)?.optJSONArray("metadataParts") ?: continue
                val row = mutableListOf<String>()
                for (p in 0 until ps.length()) ps.optJSONObject(p)?.optJSONObject("text")?.optString("content")?.ifBlank { null }?.let(row::add)
                if (row.isNotEmpty()) rows += row
            }
        }
        val parts = rows.flatten()
        // YouTube answers in the content language (Indonesian: "235 rb x ditonton", "2 tahun yang lalu"),
        // so besides the English words the rows are read by position: channel first, then views, then age.
        val stats = if (rows.size >= 2) rows.drop(1).flatten() else parts.drop(1)
        val agePart = parts.firstOrNull { it.contains("ago") } ?: if (stats.size >= 2) stats[1] else stats.firstOrNull { it.contains("lalu") }
        val viewsPart = parts.firstOrNull { it.contains("view") } ?: if (stats.size >= 2) stats[0] else stats.firstOrNull { it != agePart }
        val avatar = meta.optJSONObject("image")?.collectStrings("url")?.firstOrNull()
        // Durasi ada di lencana gambar. Teksnya bisa berformat "12:34" atau (bahasa Indonesia) "12.34",
        // dan kuncinya bisa berganti, jadi semua teks di gambar diperiksa.
        val duration = o.optJSONObject("contentImage")?.let { img ->
            val all = ArrayList<String>()
            collectAllStrings(img, all)
            all.firstOrNull { DURATION.matches(cleanClock(it)) }?.let(::cleanClock)
        }
        return VideoItem(
            id = id,
            title = title,
            channel = (parts.firstOrNull { !it.contains("view") && !it.contains("ago") } ?: parts.firstOrNull()).orEmpty(),
            channelUrl = o.collectStrings("browseId").firstOrNull { it.startsWith("UC") }?.let { "https://www.youtube.com/channel/$it" },
            thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            avatar = avatar?.let(::absoluteUrl),
            durationSec = parseDuration(duration),
            views = parseViews(viewsPart),
            uploaded = agePart,
            watched = watchedFraction(o),
            live = isLiveNow(o),
        )
    }

    private val DURATION = Regex("^\\d{1,2}([:.]\\d{2}){1,2}$")

    private fun cleanClock(text: String): String = text.replace("\u200e", "").replace('\u00a0', ' ').trim()

    private fun collectAllStrings(node: Any?, out: MutableList<String>) {
        when (node) {
            is String -> out += node
            is JSONObject -> node.keys().forEach { collectAllStrings(node.opt(it), out) }
            is JSONArray -> for (i in 0 until node.length()) collectAllStrings(node.opt(i), out)
        }
    }

    private fun parseDuration(text: String?): Long {
        val t = cleanClock(text ?: return 0)
        if (!DURATION.matches(t)) return 0
        return t.split(':', '.').fold(0L) { acc, part -> acc * 60 + (part.toLongOrNull() ?: 0) }
    }

    private val VIEWS_NUMBER = Regex("(\\d[\\d.,]*)\\s*(rb|jt|ribu|juta|miliar|md|k|m|b)?(?![a-z])")
    private val LOCALIZED_VIEWS = Regex("ditonton|tontonan|tayangan")

    /**
     * "1,234,567 views", "1.2M views", "No views" (and Indonesian "235 rb x ditonton", "1,2 jt x ditonton",
     * where "M" is a billion) → count, or -1 when unknown.
     */
    internal fun parseViews(text: String?): Long {
        val t = text?.lowercase()?.trim() ?: return -1
        if (t.startsWith("no ") || t.startsWith("tidak ada") || t.startsWith("belum")) return 0
        val m = VIEWS_NUMBER.find(t) ?: return -1
        val suffix = m.groupValues[2]
        val digits = m.groupValues[1]
        val localized = LOCALIZED_VIEWS.containsMatchIn(t) || suffix in setOf("rb", "ribu", "jt", "juta", "miliar", "md")
        val n = when {
            suffix.isEmpty() -> digits.replace(".", "").replace(",", "")
            localized -> digits.replace(".", "").replace(',', '.')
            else -> digits.replace(",", "")
        }.toDoubleOrNull() ?: return -1
        val mult = when (suffix) {
            "k", "rb", "ribu" -> 1e3
            "jt", "juta" -> 1e6
            "m" -> if (localized) 1e9 else 1e6
            "b", "miliar", "md" -> 1e9
            else -> 1.0
        }
        return (n * mult).toLong()
    }

    private fun absoluteUrl(url: String) = if (url.startsWith("//")) "https:$url" else url
}

// ── JSON helpers ───────────────────────────────────────────────────────────────

/** Depth-first visit of every object-valued key in the tree. */
internal fun JSONObject.walk(visit: (key: String, obj: JSONObject) -> Unit) {
    val keys = keys()
    while (keys.hasNext()) {
        val k = keys.next()
        when (val v = opt(k)) {
            is JSONObject -> { visit(k, v); v.walk(visit) }
            is JSONArray -> v.walkArray(visit)
        }
    }
}

private fun JSONArray.walkArray(visit: (key: String, obj: JSONObject) -> Unit) {
    for (i in 0 until length()) {
        when (val v = opt(i)) {
            is JSONObject -> v.walk(visit)
            is JSONArray -> v.walkArray(visit)
        }
    }
}

/** Every JSONObject in the tree, including objects that sit directly in arrays (which [walk] skips). */
internal fun JSONObject.forEachObject(visit: (JSONObject) -> Unit) {
    visit(this)
    val keys = keys()
    while (keys.hasNext()) {
        when (val v = opt(keys.next())) {
            is JSONObject -> v.forEachObject(visit)
            is JSONArray -> v.forEachObjectInArray(visit)
        }
    }
}

private fun JSONArray.forEachObjectInArray(visit: (JSONObject) -> Unit) {
    for (i in 0 until length()) {
        when (val v = opt(i)) {
            is JSONObject -> v.forEachObject(visit)
            is JSONArray -> v.forEachObjectInArray(visit)
        }
    }
}

/** YouTube text: {"simpleText": "..."} or {"runs": [{"text": "..."}]}. */
internal fun JSONObject.text(key: String): String? {
    val o = optJSONObject(key) ?: return optString(key).ifBlank { null }
    o.optString("simpleText").ifBlank { null }?.let { return it }
    o.optString("content").ifBlank { null }?.let { return it }
    val runs = o.optJSONArray("runs") ?: return null
    return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }.ifBlank { null }
}

/** URLs from {"thumbnail": {"thumbnails": [...]}} or {"thumbnails": [...]}, smallest first. */
internal fun JSONObject.thumbnails(): List<String>? {
    val arr = optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: optJSONArray("thumbnails") ?: return null
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("url")?.ifBlank { null } }
}

private fun JSONObject.firstImageSource(): String? = collectStrings("url").firstOrNull { it.contains("ytimg") || it.contains("ggpht") }

/** Every string value stored under [field] anywhere below this object. */
internal fun JSONObject.collectStrings(field: String): List<String> {
    val out = mutableListOf<String>()
    fun visitObj(o: JSONObject) {
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = o.opt(k)) {
                is String -> if (k == field && v.isNotBlank()) out += v
                is JSONObject -> visitObj(v)
                is JSONArray -> for (i in 0 until v.length()) (v.opt(i) as? JSONObject)?.let(::visitObj)
            }
        }
    }
    visitObj(this)
    return out
}

/** The Save dialog's playlists (`playlistAddToOptionRenderer`s, Watch later among them). */
internal fun parseSaveChoices(answer: JSONObject): List<PlaylistChoice> {
    val out = LinkedHashMap<String, PlaylistChoice>()
    answer.walk { key, obj ->
        if (key != "playlistAddToOptionRenderer") return@walk
        val id = obj.optString("playlistId").ifBlank { return@walk }
        out.getOrPut(id) {
            PlaylistChoice(
                id = id,
                title = obj.text("title") ?: "",
                contains = when (obj.optString("containsSelectedVideos")) {
                    "ALL" -> true
                    "NONE" -> false
                    else -> null
                },
                privacy = obj.optString("privacy").ifBlank { null },
            )
        }
    }
    return out.values.toList()
}
