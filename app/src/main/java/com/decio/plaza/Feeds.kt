package com.decio.plaza

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A pageable list of videos from the signed-in account (home, subscriptions, history, a playlist). */
@Stable
class FeedState(
    private val scope: CoroutineScope,
    /** Warm up the stream data of the first videos once a page lands (Home, Subscriptions). */
    private val prefetchTop: Int = 0,
    /**
     * Home: every refresh should bring different suggestions, as in the YouTube app. The
     * signed-in web feed returns much the same first page each time, so a refresh reads
     * on into the recommendation stream and keeps only videos not yet shown this session.
     */
    private val freshOnRefresh: Boolean = false,
    /** Beranda: rak siaran langsung, dimuat terpisah dari feed. */
    private val liveLoader: (suspend () -> List<VideoItem>)? = null,
    private val loader: suspend (next: String?) -> FeedPage,
) {
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    /** Rak Shorts yang menyertai feed (Beranda); kosong kalau YouTube tidak menyertakannya. */
    var shorts by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    /** Postingan komunitas yang menyertai feed (Beranda). */
    var posts by mutableStateOf<List<ChannelPost>>(emptyList())
        private set
    /** Siaran langsung untuk rak Live di Beranda. */
    var live by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Bumped when a refresh lands; lists scroll back to the top on it. */
    var generation by mutableStateOf(0)
        private set
    /** A pull/button refresh is running (the first load shows its own spinner). */
    var refreshing by mutableStateOf(false)
        private set
    private var next: String? = null
    private var loaded = false
    /** Ids already put in front of the user this session (fresh-refresh feeds only). */
    private val shown = HashSet<String>()
    private var job: Job? = null
    val hasMore get() = next != null

    fun ensureLoaded() { if (!loaded && !loading) refresh() }

    /** Sudah pernah dimuat (berhasil atau gagal); dipakai untuk membedakan "belum mulai" dari "memang kosong". */
    val isLoaded get() = loaded

    fun refresh() {
        loaded = true
        loading = true
        refreshing = videos.isNotEmpty()
        error = null
        job?.cancel()
        job = scope.launch {
            runCatching { if (freshOnRefresh && shown.isNotEmpty()) loadFresh() else loader(null) }
                .onSuccess {
                    videos = it.videos; next = it.next; generation++
                    if (it.shorts.isNotEmpty()) shorts = it.shorts
                    if (it.posts.isNotEmpty()) posts = it.posts
                    liveLoader?.let { load -> launch { runCatching { load() }.onSuccess { l -> if (l.isNotEmpty()) live = l } } }
                    if (freshOnRefresh) it.videos.forEach { v -> shown += v.id }
                    it.videos.take(prefetchTop).forEach { v -> StreamCache.prefetch(v.id) }
                }
                .onFailure { error = friendly(it) }
            loading = false
            refreshing = false
        }
    }

    /**
     * Memuat ulang diam-diam (dipakai Riwayat): tanpa spinner dan tanpa loncat ke atas. Halaman
     * pertama diganti kalau isinya berbeda; halaman-halaman lama yang sudah dimuat tetap ada.
     */
    fun refreshQuietly() {
        if (!loaded || loading) return
        scope.launch {
            runCatching { loader(null) }.onSuccess { page ->
                if (page.videos.isEmpty()) return@onSuccess
                if (page.videos.map { it.id } == videos.take(page.videos.size).map { it.id }) return@onSuccess
                val seen = page.videos.mapTo(HashSet()) { it.id }
                videos = page.videos + videos.filter { it.id !in seen }
            }
        }
    }

    fun loadMore() {
        val token = next ?: return
        if (loading) return
        loading = true
        job = scope.launch {
            runCatching { loader(token) }
                .onSuccess { page ->
                    val seen = videos.mapTo(HashSet()) { it.id }
                    val added = page.videos.filter { seen.add(it.id) }
                    videos = videos + added
                    if (shorts.isEmpty() && page.shorts.isNotEmpty()) shorts = page.shorts
                    if (freshOnRefresh) added.forEach { v -> shown += v.id }
                    next = page.next
                }
                .onFailure { error = friendly(it) }
            loading = false
        }
    }

    /**
     * Unseen videos only: reads up to [FRESH_PAGES] pages into the feed until there are
     * [FRESH_TARGET]. If YouTube has nothing new at all, falls back to the plain first page.
     */
    private suspend fun loadFresh(): FeedPage {
        val first = loader(null)
        val fresh = LinkedHashMap<String, VideoItem>()
        var token = first.next
        first.videos.filter { it.id !in shown }.forEach { fresh[it.id] = it }
        var pages = 1
        while (fresh.size < FRESH_TARGET && token != null && pages < FRESH_PAGES) {
            val page = loader(token)
            page.videos.filter { it.id !in shown }.forEach { fresh.putIfAbsent(it.id, it) }
            token = page.next
            pages++
        }
        if (fresh.isEmpty()) return first
        return FeedPage(fresh.values.toList(), token, first.shorts, first.posts)
    }

    /** Membuang video dari daftar sekarang juga (mis. channel yang baru dihentikan langganannya). */
    fun removeWhere(predicate: (VideoItem) -> Boolean) { videos = videos.filterNot(predicate) }

    /** Tandai usang: [ensureLoaded] berikutnya memuat ulang (daftar lama tetap tampil selagi memuat). */
    fun markStale() { loaded = false }

    fun reset() {
        job?.cancel()
        shown.clear()
        videos = emptyList(); shorts = emptyList(); posts = emptyList(); live = emptyList(); next = null; loaded = false; loading = false; error = null
    }

    private companion object {
        const val FRESH_TARGET = 16
        const val FRESH_PAGES = 5
    }

    private fun friendly(t: Throwable) = when (t) {
        is NotSignedInException -> "You're signed out."
        else -> t.message?.takeIf { it.startsWith("YouTube") } ?: YouTube.friendlyError(t)
    }
}

/** Everything the signed-in tabs show, kept above the tab switcher so it survives tab changes. */
@Stable
class LibraryData(scope: CoroutineScope) {
    val home = FeedState(scope, prefetchTop = 2, freshOnRefresh = true, liveLoader = { InnerTube.liveNow() }) { InnerTube.homeOrFallback(it) }
    /** Channel yang baru dihentikan langganannya; disaring dari feed dan daftar sampai YouTube menyusul. */
    private val unsubscribed = HashSet<String>()
    // Rak Live: siaran langsung dari channel yang dilanggan (diambil dari feed Langganan).
    val subscriptions = FeedState(scope, prefetchTop = 2, liveLoader = { InnerTube.subscriptionsFeed().videos.filter { it.live } }) { token ->
        val page = InnerTube.subscriptionsFeed(token)
        page.copy(videos = page.videos.filterNot { v -> unsubscribed.any { id -> v.channelUrl?.contains(id) == true } })
    }
    /** Naik tiap langganan berubah; tab Langganan memuat ulang karenanya. */
    var subscriptionsVersion by mutableIntStateOf(0)
        private set
    val history = FeedState(scope) { InnerTube.history(it) }
    var channels by mutableStateOf<List<ChannelRef>>(emptyList())
        private set
    var playlists by mutableStateOf<List<PlaylistRef>>(emptyList())
        private set
    private val scope = scope

    /** Naik tiap akun berganti (masuk/keluar); layar yang memuat daftar memuat ulang karenanya. */
    var version by mutableIntStateOf(0)
        private set

    fun loadChannels() = scope.launch {
        runCatching { InnerTube.channels() }.onSuccess { list -> channels = list.filterNot { it.id in unsubscribed } }
    }

    /** Langganan berubah (dari channel, pemutar, atau Shorts): daftar dan feed langsung menyesuaikan. */
    fun onSubscriptionChanged(channelId: String, subscribed: Boolean) {
        if (subscribed) {
            unsubscribed -= channelId
        } else {
            unsubscribed += channelId
            channels = channels.filterNot { it.id == channelId }
            subscriptions.removeWhere { it.channelUrl?.contains(channelId) == true }
        }
        subscriptions.markStale()
        subscriptionsVersion++
    }
    fun loadPlaylists() = scope.launch { runCatching { InnerTube.playlists() }.onSuccess { playlists = it } }

    fun playlistFeed(id: String) = FeedState(scope) { InnerTube.playlist(id, it) }

    fun reset() {
        unsubscribed.clear()
        home.reset(); subscriptions.reset(); history.reset()
        channels = emptyList(); playlists = emptyList()
        version++
    }
}

/**
 * A list state that animates back to the top whenever [signal] changes (tapping the
 * already-selected tab). The value it was created with is ignored, so merely showing a
 * tab never scrolls it.
 */
@Composable
fun rememberScrollToTop(signal: Int): LazyListState {
    val state = rememberLazyListState()
    val initial = remember { signal }
    LaunchedEffect(signal) { if (signal != initial) state.animateScrollToItem(0) }
    return state
}

/** A feed as a column of video cards with infinite scroll and designed loading/error states. */
@Composable
fun FeedList(
    feed: FeedState,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    header: LazyListScope.() -> Unit = {},
    scrollToTop: Int = 0,
    pullToRefresh: Boolean = true,
    /** Collapsing header height: the refresh spinner appears just below it. */
    indicatorTop: Dp = 0.dp,
    /** Beranda: sisipkan rak Shorts setelah beberapa video pertama. */
    showShorts: Boolean = false,
    showLive: Boolean = false,
    /** Beranda: tampilkan rak Postingan. */
    showPosts: Boolean = false,
    /** Short dari rak dibuka di halaman Shorts. */
    onOpenShort: (VideoItem) -> Unit = onOpen,
    /** Daftar ringkas (gambar kecil di kiri) seperti playlist di YouTube, bukan kartu selebar layar. */
    compact: Boolean = false,
    /** Kalau diisi: daftar yang memang kosong menampilkan penjelasan ini dan tombol coba lagi, bukan layar kosong. */
    emptyBody: String? = null,
    /** Layar galat (mis. offline) menawarkan tombol ke Unduhan kalau sudah ada video yang diunduh. */
    onDownloads: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val list = rememberScrollToTop(scrollToTop)
    var postViewer by remember { mutableStateOf<Int?>(null) }
    // Fresh content always starts at the top (first load and every refresh).
    LaunchedEffect(feed.generation) { list.scrollToItem(0) }
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, feed.videos.size) { if (nearEnd && feed.videos.isNotEmpty()) feed.loadMore() }
    PullToRefresh(enabled = pullToRefresh, refreshing = feed.refreshing, onRefresh = feed::refresh, topPadding = indicatorTop) {
    LazyColumn(state = list, contentPadding = contentPadding) {
        header()
        if (((showShorts && feed.shorts.isNotEmpty()) || (showLive && feed.live.isNotEmpty()) || (showPosts && feed.posts.isNotEmpty())) && feed.videos.isNotEmpty()) {
            items(feed.videos.take(SHELF_AFTER), key = { it.id }) { video -> if (compact) CompactVideoRow(video) { onOpen(video) } else VideoCard(video, onClick = { onOpen(video) }) }
            if (showLive && feed.live.isNotEmpty()) item(key = "liveShelf") { LiveShelf(feed.live, onOpen) }
            if (showShorts && feed.shorts.isNotEmpty()) item(key = "shortsShelf") { ShortsShelf(feed.shorts, onOpenShort) }
            if (showPosts && feed.posts.isNotEmpty()) item(key = "postsShelf") { PostsShelf(feed.posts) { postViewer = it } }
            items(feed.videos.drop(SHELF_AFTER), key = { it.id }) { video -> if (compact) CompactVideoRow(video) { onOpen(video) } else VideoCard(video, onClick = { onOpen(video) }) }
        } else {
            items(feed.videos, key = { it.id }) { video -> if (compact) CompactVideoRow(video) { onOpen(video) } else VideoCard(video, onClick = { onOpen(video) }) }
        }
        when {
            // No keys on these trailing items: a keyed loader at index 0 makes LazyColumn
            // hold it in view when videos arrive above it, landing at the bottom of the page.
            feed.error != null && feed.videos.isEmpty() -> item {
                EmptyState(
                    icon = Icons.Rounded.CloudOff,
                    title = tr("Couldn't load this"),
                    body = feed.error.orEmpty(),
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PillButton(tr("Try again"), { feed.refresh() })
                            // Offline: video yang sudah diunduh tetap bisa ditonton.
                            if (onDownloads != null && Downloads.items.any { it.state == DownloadState.Done }) {
                                Spacer(Modifier.height(10.dp))
                                PillButton(tr("Downloads"), onDownloads, Icons.Rounded.Download)
                            }
                        }
                    },
                )
            }
            emptyBody != null && feed.isLoaded && !feed.loading && feed.error == null && feed.videos.isEmpty() && !feed.hasMore -> item {
                EmptyState(
                    icon = Icons.Rounded.VideoLibrary,
                    title = tr("Nothing here yet"),
                    body = emptyBody,
                    action = { PillButton(tr("Try again"), { feed.refresh() }) },
                )
            }
            feed.loading || feed.hasMore -> item {
                Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
    }
    postViewer?.let { at -> PostViewer(feed.posts, at, onClose = { postViewer = null }) }
}

private const val SHELF_AFTER = 3

/** Rak "Live" di Beranda: siaran langsung yang sedang berlangsung, bisa digeser ke samping. */
@Composable
fun LiveShelf(items: List<VideoItem>, onOpen: (VideoItem) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            "Live",
            fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { video ->
                Column(Modifier.width(260.dp).pressable(pressedScale = 0.97f) { onOpen(video) }) {
                    Box {
                        Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 12)
                        DurationPill(0, Modifier.align(Alignment.BottomEnd).padding(8.dp), live = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(video.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(videoMeta(video), color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Rak "Shorts" di Beranda, seperti YouTube: deretan kartu tegak 9:16 yang bisa digeser ke samping. */
@Composable
fun ShortsShelf(shorts: List<VideoItem>, onOpen: (VideoItem) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            "Shorts",
            fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shorts, key = { it.id }) { short -> ShortCard(short, Modifier.width(150.dp), onOpen) }
        }
    }
}

/** Kartu Short tegak 9:16 dengan judul dan jumlah tontonan di atas gradasi (rak Beranda dan tab Shorts channel). */
@Composable
fun ShortCard(short: VideoItem, modifier: Modifier = Modifier, onOpen: (VideoItem) -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .aspectRatio(9f / 16f)
            .pressable(pressedScale = 0.97f) { onOpen(short) }
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceContainerHighest),
    ) {
        Thumbnail(short.thumbnail, Modifier.fillMaxSize(), radius = 0)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        ) {
            if (short.title.isNotBlank()) {
                Text(short.title, color = Color.White, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (short.views >= 0) {
                Text("${formatCount(short.views)} ${tr("views")}", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/**
 * Swipe down at the top to reload (replaces the old refresh button). The indicator uses
 * the theme's colors and sits just under the screen header.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PullToRefresh(enabled: Boolean, refreshing: Boolean, onRefresh: () -> Unit, topPadding: Dp = 0.dp, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val c = MaterialTheme.colorScheme
    val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = pull,
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = pull, isRefreshing = refreshing,
                containerColor = c.primaryContainer, color = c.onPrimaryContainer,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topPadding),
            )
        },
    ) { content() }
}
