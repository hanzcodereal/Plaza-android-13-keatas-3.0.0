package com.decio.plaza

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/** Opens a channel page from anywhere (player channel row, card avatars). Provided by the shell. */
val LocalChannelOpener = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Isi satu tab channel (Video, Shorts, Live, Playlist, Postingan): dimuat saat tab pertama kali dibuka. */
@Stable
class TabData {
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
    var playlists by mutableStateOf<List<PlaylistRef>>(emptyList())
    var posts by mutableStateOf<List<ChannelPost>>(emptyList())
    var loading by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var next: String? = null
    val size get() = videos.size + playlists.size + posts.size
}

private val TabOrder = listOf("all", "videos", "shorts", "live", "playlists", "posts")

@Stable
class ChannelState(val url: String, private val scope: CoroutineScope) {
    var details by mutableStateOf<ChannelDetails?>(null)
        private set
    var meta by mutableStateOf<ChannelMeta?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var subscribed by mutableStateOf<Boolean?>(null)
        private set
    /** Tab yang sedang dibuka. */
    var tab by mutableStateOf("all")
        private set
    val data: Map<String, TabData> = TabOrder.associateWith { TabData() }

    /** Tab yang dimiliki channel ini, berurutan; Video selalu ada. */
    val tabs: List<String>
        get() {
            // Semua tab tampil (Semua, Video, Shorts, Playlist, Postingan); Live hanya untuk channel yang pernah siaran
            // langsung, menurut data tab dari YouTube.
            return TabOrder.filter { it != "live" || meta?.tabs?.containsKey("live") == true }
        }
    val current: TabData get() = data.getValue(tab)

    init { load() }

    fun load() {
        loading = true
        error = null
        scope.launch {
            runCatching { YouTube.channel(url) }
                .onSuccess { d ->
                    details = d
                    if (Account.signedIn) launch { subscribed = runCatching { InnerTube.channelSubscribed(d.id) }.getOrNull() }
                    launch { meta = runCatching { InnerTube.channelMeta(d.id) }.getOrNull() }
                    loadTab("all", false)
                }
                .onFailure { error = YouTube.friendlyError(it) }
            loading = false
        }
    }

    fun select(kind: String) {
        tab = kind
        val t = data.getValue(kind)
        if (!t.loaded && !t.loading) loadTab(kind, false)
    }

    fun loadMore() = loadTab(tab, true)

    fun retryTab() {
        val t = current
        t.error = null
        loadTab(tab, false)
    }

    /** Nama channel sesuai bahasa aplikasi (dari YouTube langsung); cadangannya nama dari NewPipe. */
    val displayName: String
        get() = meta?.name?.takeIf { it.isNotBlank() } ?: details?.name.orEmpty()

    private fun fill(d: ChannelDetails, items: List<VideoItem>) = items.map {
        it.copy(channel = it.channel.ifBlank { displayName.ifBlank { d.name } }, avatar = it.avatar ?: d.avatar, channelUrl = it.channelUrl ?: d.url)
    }

    /** Memuat tab Live lebih awal (kalau channel punya) supaya foto profil bisa diberi tanda LIVE. */
    fun preloadLive() {
        val t = data["live"] ?: return
        if (meta?.tabs?.containsKey("live") != true || t.videos.isNotEmpty() || t.loading) return
        loadTab("live", false)
    }

    private fun loadTab(kind: String, more: Boolean) {
        val d = details ?: return
        val t = data.getValue(kind)
        if (t.loading) return
        if (more && t.next == null) return
        t.loading = true
        if (!more) t.error = null
        val token = if (more) t.next else null
        // "all" = semua unggahan; bila daftar unggahan gagal, cadangannya tab Video.
        val tabKind = if (kind == "all") "videos" else kind
        val params = meta?.tabs?.get(tabKind) ?: InnerTube.CHANNEL_TAB_FALLBACK.getValue(tabKind)
        scope.launch {
            var chain = false
            runCatching {
                when (kind) {
                    "playlists" -> {
                        val (list, n) = InnerTube.channelPlaylists(d.id, params, token)
                        val seen = HashSet<String>().apply { if (more) t.playlists.forEach { add(it.id) } }
                        t.playlists = if (more) t.playlists + list.filter { seen.add(it.id) } else list
                        t.next = if (list.isEmpty()) null else n
                    }
                    "posts" -> {
                        val (list, n) = InnerTube.channelPosts(d.id, params, token)
                        val seen = HashSet<String>().apply { if (more) t.posts.forEach { add(it.id) } }
                        t.posts = if (more) t.posts + list.filter { seen.add(it.id) } else list
                        t.next = if (list.isEmpty()) null else n
                    }
                    else -> {
                        val page = when (kind) {
                            // Tab Video asli YouTube dengan params dari halaman channel itu sendiri (semua halaman lanjutan).
                            "all" -> {
                                val uploads = runCatching { InnerTube.channelUploads(d.id, token) }.getOrNull()
                                if (uploads != null && (uploads.videos.isNotEmpty() || more)) uploads
                                else InnerTube.channelTabFeed(d.id, params, token)
                            }
                            "videos" -> InnerTube.channelTabFeed(d.id, params, token)
                            "shorts" -> InnerTube.channelShorts(d.id, params, token)
                            else -> InnerTube.channelTabFeed(d.id, params, token)
                        }
                        val items = fill(d, page.videos)
                        val seen = HashSet<String>().apply { if (more) t.videos.forEach { add(it.id) } }
                        t.videos = if (more) t.videos + items.filter { seen.add(it.id) } else items
                        // Halaman yang isinya tersaring semua (Shorts, khusus anggota) tidak menghentikan daftar.
                        t.next = page.next?.takeIf { it != token }
                        chain = items.isEmpty() && t.next != null
                    }
                }
                t.loaded = true
            }.onFailure {
                val m = meta
                // Tab yang tidak dimiliki channel ini: kosong, bukan galat.
                val missing = !more && kind != "videos" && kind != "all" && m != null && m.tabs.isNotEmpty() && kind !in m.tabs
                if (missing) t.loaded = true
                else if (!more || t.size == 0) t.error = YouTube.friendlyError(it)
            }
            t.loading = false
            if (chain) loadTab(kind, true)
        }
    }

    fun toggleSubscribe(onError: (String) -> Unit) {
        val id = details?.id ?: return
        val before = subscribed ?: return
        subscribed = !before
        scope.launch {
            runCatching { InnerTube.setSubscribed(id, !before) }
                .onSuccess { SubscriptionSync.changed(id, !before) }
                .onFailure { subscribed = before; onError(tr("Couldn't update your subscription.")) }
        }
    }
}

@Composable
fun ChannelScreen(
    state: ChannelState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpen: (VideoItem) -> Unit,
    onMessage: (String) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit = {},
    /** Short dari channel: dibuka di halaman Shorts, bukan di pemutar biasa. */
    onOpenShort: (VideoItem) -> Unit = onOpen,
) {
    val c = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    var viewerAt by remember { mutableStateOf<Int?>(null) }
    // Cek diam-diam apakah channel sedang siaran langsung (tab Live dimuat begitu daftar tab diketahui).
    LaunchedEffect(state.meta) { state.preloadLive() }
    val cur = state.current
    val nearEnd by remember {
        derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4 }
    }
    LaunchedEffect(nearEnd, cur.size, state.tab) { if (nearEnd && cur.size > 0) state.loadMore() }
    Box(Modifier.fillMaxSize().background(c.surface)) {
    CollapsingHeaderLayout(
        header = {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleAction(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                state.displayName,
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = (-0.6).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        },
    ) { top ->
        val d = state.details
        when {
            d == null && state.error != null -> Box(Modifier.padding(top = top)) { EmptyState(
                Icons.Rounded.CloudOff, tr("Couldn't load this channel"), state.error.orEmpty(),
                action = { PillButton(tr("Try again"), state::load) },
            ) }
            d == null -> Box(Modifier.fillMaxWidth().padding(top = top + 60.dp), Alignment.Center) {
                CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(36.dp))
            }
            else -> LazyColumn(state = list, contentPadding = contentPadding.withTop(top)) {
                item(key = "header") { Column {
                    // Banner penuh selebar layar dan lebih tinggi, seperti di YouTube.
                    if (d.banner != null) {
                        Thumbnail(d.banner, Modifier.fillMaxWidth().aspectRatio(3.4f), radius = 0)
                        Spacer(Modifier.height(14.dp))
                    }
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Channel yang sedang siaran langsung: cincin merah + LIVE; ketuk untuk menonton.
                            val liveNow = state.data["live"]?.videos?.firstOrNull { it.live }
                            Box(Modifier.pressable(enabled = liveNow != null) { liveNow?.let(onOpen) }) {
                                LiveAvatar(d.avatar, state.displayName, size = 76, live = liveNow != null)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                // Urutan: nama, @username, lalu pelanggan dan jumlah video.
                                val m = state.meta
                                Text(state.displayName, fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                m?.handle?.let { Text(it, color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                val subs = m?.subscribers?.takeIf { it > 0 } ?: d.subscribers.takeIf { it > 0 }
                                val stats = listOfNotNull(
                                    subs?.let { "${formatCount(it)} ${tr("subscribers")}" },
                                    m?.videoCount?.takeIf { it >= 0 }?.let { "${formatCount(it)} ${tr("videos")}" },
                                )
                                if (stats.isNotEmpty()) Text(stats.joinDots(), color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        state.subscribed?.let { subscribed ->
                            Spacer(Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChannelSubscribeButton(subscribed, Modifier.weight(1f)) { state.toggleSubscribe(onMessage) }
                                if (subscribed) {
                                    Spacer(Modifier.width(10.dp))
                                    BellButton(d.id, size = 46.dp)
                                }
                            }
                        }
                        // Bio asli dari YouTube; cadangannya dari NewPipe.
                        val bio = state.meta?.description?.takeIf { it.isNotBlank() } ?: d.description
                        if (bio.isNotBlank()) {
                            var bioOpen by remember(d.id) { mutableStateOf(false) }
                            Spacer(Modifier.height(12.dp))
                            Column(Modifier.fillMaxWidth().pressable(pressedScale = 0.99f) { bioOpen = !bioOpen }) {
                                Text(
                                    bio, color = c.onSurfaceVariant, fontSize = 13.5.sp, lineHeight = 19.sp,
                                    maxLines = if (bioOpen) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                                )
                                if (bioOpen || bio.length > 110 || bio.contains('\n')) {
                                    Text(
                                        if (bioOpen) tr("less") else tr("…more"),
                                        color = c.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                } }
                item(key = "tabs") {
                    ChannelTabRow(state.tabs, state.tab, state::select)
                }
                when (state.tab) {
                    "shorts" -> items(cur.videos.chunked(3), key = { row -> "s" + row.first().id }) { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { short -> ShortCard(short, Modifier.weight(1f), onOpenShort) }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    "playlists" -> items(cur.playlists, key = { "p" + it.id }) { pl -> ChannelPlaylistRow(pl) { onOpenPlaylist(pl) } }
                    "posts" -> itemsIndexed(cur.posts, key = { _, post -> "c" + post.id }) { i, post ->
                        PostCard(post, showAuthor = false, onOpen = { viewerAt = i }, onComments = { viewerAt = i }, onMessage = onMessage)
                    }
                    // Daftar ringkas (gambar kecil di kiri) seperti di YouTube: video tidak memenuhi layar.
                    else -> items(cur.videos, key = { it.id }) { video -> CompactVideoRow(video) { onOpen(video) } }
                }
                if (cur.error != null && cur.size == 0) item(key = "tabError") {
                    EmptyState(Icons.Rounded.CloudOff, tr("Couldn't load this"), cur.error.orEmpty(), action = { PillButton(tr("Try again"), state::retryTab) })
                } else if (cur.loaded && !cur.loading && cur.size == 0) item(key = "tabEmpty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), Alignment.Center) {
                        Text(
                            when (state.tab) {
                                "shorts" -> tr("No Shorts yet")
                                "live" -> tr("No live streams")
                                "playlists" -> tr("No playlists yet")
                                "posts" -> tr("No posts yet")
                                else -> tr("No videos yet")
                            },
                            color = c.onSurfaceVariant, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else if (cur.loading || cur.next != null) item(key = "tabLoading") {
                    Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) {
                        CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
    viewerAt?.let { at -> PostViewer(state.data.getValue("posts").posts, at, onClose = { viewerAt = null }, onMessage = onMessage) }
    }
}

/** Tab channel seperti YouTube: Video · Shorts · Live · Playlist · Postingan, dengan garis di bawah yang terpilih. */
@Composable
private fun ChannelTabRow(tabs: List<String>, selected: String, onSelect: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
    ) {
        tabs.forEach { kind ->
            val on = kind == selected
            Column(
                Modifier.clickable { onSelect(kind) }.padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    when (kind) {
                        "all" -> tr("All")
                        "shorts" -> "Shorts"
                        "live" -> "Live"
                        "playlists" -> tr("Playlists")
                        "posts" -> tr("Posts")
                        else -> tr("Videos")
                    },
                    color = if (on) c.onSurface else c.onSurfaceVariant,
                    fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                Box(Modifier.height(3.dp).width(36.dp).clip(CircleShape).background(if (on) c.onSurface else Color.Transparent))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.surfaceContainerHighest))
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ChannelPlaylistRow(playlist: PlaylistRef, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(160.dp)) {
            Thumbnail(playlist.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 12)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!playlist.subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(playlist.subtitle, color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun CompactVideoRow(video: VideoItem, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val menu = LocalVideoMenu.current
    Row(
        Modifier.fillMaxWidth().prefetchOnPress(video.id)
            .pressable(pressedScale = 0.98f, onLongClick = { menu(video) }, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(Modifier.width(168.dp)) {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 12)
            DurationPill(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(5.dp), small = true, live = video.live)
            ResumeBar(video.id, Modifier.align(Alignment.BottomStart), fallback = video.watched)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(video.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(video.views.takeIf { it >= 0 }?.let { "${formatCount(it)} ${tr("views")}" }, video.uploaded)
            if (meta.isNotEmpty()) {
                Text(meta.joinDots(), color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun ChannelSubscribeButton(subscribed: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier
            .squishOn(subscribed)
            .height(46.dp)
            .pressable(onClick = onClick)
            .clip(CircleShape)
            .background(if (subscribed) c.secondaryContainer else c.onSurface),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (subscribed) tr("Subscribed") else tr("Subscribe"), color = if (subscribed) c.onSecondaryContainer else c.surface, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    }
}
