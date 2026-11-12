package com.decio.plaza

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ── Home / Subscriptions / Library ─────────────────────────────────────────────
// Signed in, these show the account's real feeds. Signed out, they explain what signing
// in brings rather than showing sample content.

@Composable
fun HomeScreen(
    lib: LibraryData,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onSearch: () -> Unit,
    onSignIn: () -> Unit,
    onAccount: () -> Unit,
    scrollToTop: Int = 0,
    onOpenShort: (VideoItem) -> Unit = onOpen,
    onNotifications: () -> Unit = {},
    onOpenDownloads: (() -> Unit)? = null,
) {
    val signedIn = Account.signedIn
    // lib.version: setelah ganti akun, daftar dimuat ulang (reset terjadi setelah masuk selesai).
    LaunchedEffect(signedIn, lib.version) { if (signedIn) lib.home.ensureLoaded() }
    CollapsingHeaderLayout(
        resetKey = scrollToTop to lib.home.generation,
        header = {
            ScreenHeader(title = "Plaza", brandMark = true) {
                UpdateAction()
                NotificationAction(onNotifications)
                if (SearchPlacement.top) SearchAction(onSearch)
                AccountAction(onAccount)
            }
        },
    ) { top ->
        if (signedIn) {
            FeedList(lib.home, contentPadding.withTop(top), onOpen, scrollToTop = scrollToTop, indicatorTop = top, showShorts = ShortsSetting.enabled, showLive = true, showPosts = true, onOpenShort = onOpenShort, onDownloads = onOpenDownloads,
                emptyBody = tr("YouTube has no recommendations for this account yet. Watch a few videos, then try again."))
        } else {
            LazyColumn(contentPadding = contentPadding.withTop(top)) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Subscriptions,
                        title = tr("Your recommendations, minus the ads"),
                        body = tr("Sign in with Google to see your own YouTube home feed, or just search."),
                        action = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PillButton(tr("Sign in with Google"), onSignIn)
                                Spacer(Modifier.height(12.dp))
                                TextLink(tr("Search instead"), onSearch)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SubscriptionsScreen(
    lib: LibraryData,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onSignIn: () -> Unit,
    onAccount: () -> Unit,
    scrollToTop: Int = 0,
    onOpenShort: (VideoItem) -> Unit = onOpen,
) {
    val signedIn = Account.signedIn
    val openChannel = LocalChannelOpener.current
    // Muat ulang tiap tab dibuka dan tiap langganan berubah, supaya yang sudah dihentikan tidak tersisa.
    LaunchedEffect(signedIn, lib.subscriptionsVersion, lib.version) {
        if (signedIn) {
            lib.subscriptions.ensureLoaded()
            lib.loadChannels()
        }
    }
    // The first feed loaded counts as seen; green dots appear only for uploads after it.
    LaunchedEffect(lib.subscriptions.videos) { if (signedIn) NewContentDots.baseline(lib.subscriptions.videos) }
    CollapsingHeaderLayout(
        resetKey = scrollToTop to lib.subscriptions.generation,
        header = { ScreenHeader(title = tr("Subscriptions")) { AccountAction(onAccount) } },
    ) { top ->
        if (signedIn) {
            FeedList(lib.subscriptions, contentPadding.withTop(top), onOpen, header = {
                // Always present (empty until loaded) so its arrival never shifts the list.
                item(key = "channels") {
                    if (lib.channels.isNotEmpty()) ChannelRail(lib.channels, lib.subscriptions.videos) { id ->
                        NewContentDots.markSeen(lib.subscriptions.videos, id)
                        openChannel("https://www.youtube.com/channel/$id")
                    }
                }
            }, scrollToTop = scrollToTop, indicatorTop = top,
                showShorts = ShortsSetting.enabled, showLive = true, showPosts = true, onOpenShort = onOpenShort)
        } else {
            LazyColumn(contentPadding = contentPadding.withTop(top)) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Subscriptions,
                        title = tr("Your subscriptions"),
                        body = tr("Sign in with Google to see new videos from the channels you follow."),
                        action = { PillButton(tr("Sign in with Google"), onSignIn) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRail(channels: List<ChannelRef>, videos: List<VideoItem>, onOpen: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
    ) {
        items(channels, key = { it.id }) { ch ->
            Column(Modifier.width(64.dp).pressable(pressedScale = 0.95f) { onOpen(ch.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    ChannelAvatar(ch.avatar, ch.name, size = 56)
                    if (NewContentDots.hasNew(videos, ch.id)) {
                        // A new upload since the channel was last opened: the small green dot.
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(15.dp)
                                .clip(CircleShape)
                                .background(c.surface)
                                .padding(2.5.dp)
                                .clip(CircleShape)
                                .background(androidx.compose.ui.graphics.Color(0xFF2EC27E)),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(ch.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LibraryScreen(
    lib: LibraryData,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    onSignIn: () -> Unit,
    onAccount: () -> Unit,
    scrollToTop: Int = 0,
    onOpenDownloads: () -> Unit = {},
) {
    val signedIn = Account.signedIn
    LaunchedEffect(signedIn, lib.version) {
        if (signedIn) {
            lib.history.ensureLoaded()
            // Tontonan terbaru ikut masuk begitu Perpustakaan dibuka; diam-diam, tanpa spinner dan tanpa loncat.
            lib.history.refreshQuietly()
            if (lib.playlists.isEmpty()) lib.loadPlaylists()
        }
    }
    CollapsingHeaderLayout(
        resetKey = scrollToTop to lib.history.generation,
        header = { ScreenHeader(title = tr("Library")) { AccountAction(onAccount) } },
    ) { top ->
        PullToRefresh(
            enabled = signedIn,
            refreshing = lib.history.refreshing,
            onRefresh = { lib.history.refresh(); lib.loadPlaylists() },
            topPadding = top,
        ) {
        LazyColumn(state = rememberScrollToTop(scrollToTop), contentPadding = contentPadding.withTop(top)) {
            if (!signedIn) {
                // Unduhan selalu ada (juga sebelum masuk dan saat offline).
                item(key = "downloadsTile") { DownloadsTile(onOpenDownloads) }
                item {
                    EmptyState(
                        icon = Icons.Rounded.VideoLibrary,
                        title = tr("Your library"),
                        body = tr("Sign in with Google for your history, Watch later, liked videos and playlists."),
                        action = { PillButton(tr("Sign in with Google"), onSignIn) },
                    )
                }
                return@LazyColumn
            }
            item(key = "historyTitle") { SectionTitle(tr("History")) }
            item(key = "history") {
                val history = lib.history.videos
                // Baris Riwayat: begitu ada tontonan terbaru (atau daftar dimuat ulang), langsung kembali ke
                // awal supaya yang paling baru terlihat, tanpa perlu menggulir dulu.
                val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(history.firstOrNull()?.id, lib.history.generation) { rowState.scrollToItem(0) }
                when {
                    history.isNotEmpty() -> LazyRow(
                        state = rowState,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(history.take(20), key = { "h-" + it.id }) { video -> HistoryCard(video) { onOpen(video) } }
                    }
                    lib.history.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp, modifier = Modifier.size(28.dp))
                    }
                    else -> Text(
                        lib.history.error ?: tr("Nothing watched yet."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            item(key = "playlistsTitle") { SectionTitle(tr("Playlists")) }
            // Unduhan ada di bagian Playlist, tepat di atas Tonton nanti dan Video yang disukai.
            item(key = "downloadsTile") { DownloadsTile(onOpenDownloads) }
            val pinned = listOf(
                PlaylistRef("WL", tr("Watch later"), null, null),
                PlaylistRef("LL", tr("Liked videos"), null, null),
            )
            items((pinned + lib.playlists).chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { pl -> PlaylistTile(pl, Modifier.weight(1f)) { onOpenPlaylist(pl) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        }
    }
}

@Composable
private fun HistoryCard(video: VideoItem, onClick: () -> Unit) {
    val menu = LocalVideoMenu.current
    val c = MaterialTheme.colorScheme
    Column(Modifier.width(168.dp).prefetchOnPress(video.id).pressable(pressedScale = 0.97f, onLongClick = { menu(video) }, onClick = onClick)) {
        Box {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 20)
            DurationPill(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(6.dp), small = true, live = video.live)
            ResumeBar(video.id, Modifier.align(Alignment.BottomStart), fallback = video.watched)
        }
        Spacer(Modifier.height(7.dp))
        // Judul di kiri, titik tiga di kanan (seperti di YouTube).
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(video.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (video.channel.isNotBlank()) {
                    Text(
                        video.channel, color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Box(Modifier.size(32.dp).pressable { menu(video) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreVert, tr("More options"), tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun PlaylistTile(playlist: PlaylistRef, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    val (icon, tone) = when (playlist.id) {
        "WL" -> Icons.Rounded.WatchLater to c.primaryContainer
        "LL" -> Icons.Rounded.ThumbUp to c.primaryContainer
        else -> Icons.AutoMirrored.Rounded.PlaylistPlay to c.secondaryContainer
    }
    Column(
        modifier = modifier
            .height(132.dp)
            .pressable(pressedScale = 0.97f, onClick = onClick)
            .clip(shape)
            .background(c.surfaceContainerLow)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (playlist.thumbnail != null) {
            Thumbnail(playlist.thumbnail, Modifier.size(width = 72.dp, height = 44.dp), radius = 12)
        } else {
            Box(Modifier.size(52.dp).clip(CookieShape).background(tone), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = contentColorFor(tone))
            }
        }
        Column {
            Text(playlist.title, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            playlist.subtitle?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/** A playlist's videos (Watch later, Liked, or the user's own). */
@Composable
fun PlaylistScreen(playlist: PlaylistRef, feed: FeedState, contentPadding: PaddingValues, onBack: () -> Unit, onOpen: (VideoItem) -> Unit) {
    LaunchedEffect(playlist.id) { feed.ensureLoaded() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    CollapsingHeaderLayout(
        resetKey = feed.generation,
        header = {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleAction(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                playlist.title,
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = (-0.8).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        },
    ) { top ->
        if (feed.videos.isEmpty() && !feed.loading && feed.error == null && !feed.hasMore) {
            Box(Modifier.padding(top = top)) { EmptyState(Icons.Rounded.VideoLibrary, tr("Nothing here yet"), tr("Videos you add to this playlist will show up here.")) }
        } else {
            FeedList(feed, contentPadding.withTop(top), onOpen, indicatorTop = top, compact = true)
        }
    }
    }
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        modifier = Modifier.pressable(onClick = onClick).padding(8.dp),
    )
}

// ── Search ─────────────────────────────────────────────────────────────────────

/** Lives above the tab switcher so results survive switching tabs. */
@Stable
class SearchState(private val scope: CoroutineScope) {
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
        private set
    var results by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var generation by mutableStateOf(0)
        private set
    private var job: Job? = null

    fun submit() {
        val q = query.trim()
        if (q.isEmpty()) return
        SearchHistory.add(q)
        submitted = q
        loading = true
        error = null
        job?.cancel()
        job = scope.launch {
            runCatching { YouTube.search(q) }
                .onSuccess { results = it; generation++ }
                .onFailure { error = YouTube.friendlyError(it) }
            loading = false
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(state: SearchState, contentPadding: PaddingValues, onOpen: (VideoItem) -> Unit, scrollToTop: Int = 0) {
    val c = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val view = LocalView.current
    // Search and Enter both dismiss the keyboard — every field, every time. A hide issued
    // while the key event is still being dispatched can be undone by the IME, so the
    // window-level hide is posted to run after it.
    fun submit() {
        focus.clearFocus(force = true)
        keyboard?.hide()
        view.postDelayed({
            view.context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }, 120)
        state.submit()
    }
    var focused by remember { mutableStateOf(false) }
    // The keyboard closed by Back or its own button leaves the field focused; drop the focus
    // with it so the history gives way to the results again.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (!imeVisible && focused) focus.clearFocus() }
    // Past searches, filtered by what's typed: while typing, and on the empty page.
    // Saran dari YouTube muncul selagi mengetik (ditunda sebentar supaya tidak meminta tiap huruf).
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(state.query, focused) {
        if (state.query.isBlank() || !focused) {
            suggestions = emptyList()
        } else {
            kotlinx.coroutines.delay(250)
            suggestions = runCatching { SearchSuggestions.fetch(state.query) }.getOrDefault(emptyList())
        }
    }
    val typing = state.query.isNotBlank()
    val history = SearchHistory.matching(state.query).let { if (typing) it.take(3) else it }
    val extra = suggestions.filter { s -> history.none { it.equals(s, ignoreCase = true) } }
    val showHistory = (history.isNotEmpty() || extra.isNotEmpty()) && (focused || state.submitted.isEmpty())
    // The search bar rides with the header: both slide away together while scrolling results.
    CollapsingHeaderLayout(
        resetKey = Triple(scrollToTop, state.generation, showHistory),
        header = { Column {
        ScreenHeader(title = tr("Search"))
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(60.dp)
                .clip(CircleShape)
                .background(c.surfaceContainerHigh)
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = c.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (state.query.isEmpty()) {
                    Text(tr("Search Plaza"), color = c.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    singleLine = true,
                    textStyle = OwnDirectionText.merge(TextStyle(color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)),
                    cursorBrush = SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }),
                    // Hardware Enter doesn't go through the IME action; treat it the same.
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { e ->
                        if (e.key == Key.Enter || e.key == Key.NumPadEnter) {
                            if (e.type == KeyEventType.KeyDown) submit()
                            true
                        } else false
                    },
                )
            }
            if (state.query.isNotEmpty()) {
                Box(
                    modifier = Modifier.size(44.dp).pressable { state.query = "" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, tr("Clear"), tint = c.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.height(8.dp))
        } },
    ) { top ->
        Box(Modifier.fillMaxSize()) {
        val listShown = showHistory || (state.results.isNotEmpty() && state.error == null)
        Box(Modifier.padding(top = if (listShown) 0.dp else top)) {
        when {
            showHistory -> SearchHistoryList(
                history = history,
                suggestions = extra,
                showHeader = !typing,
                contentPadding = contentPadding.withTop(top),
                onSearch = { state.query = it; submit() },
                onFill = { state.query = it },
            )
            state.loading && state.results.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), Alignment.Center) {
                CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(36.dp))
            }
            state.error != null -> EmptyState(
                icon = Icons.Rounded.CloudOff,
                title = tr("Search didn't load"),
                body = state.error.orEmpty(),
                action = { PillButton(tr("Try again"), { state.submit() }) },
            )
            state.submitted.isEmpty() -> EmptyState(
                icon = Icons.Rounded.TravelExplore,
                title = tr("Search without the ads"),
                body = tr("Results play straight away, with no ads and no sign-in."),
            )
            state.results.isEmpty() -> EmptyState(
                icon = Icons.Rounded.History,
                title = tr("Nothing found"),
                body = "No videos matched “${state.submitted}”.",
            )
            else -> {
                val list = rememberScrollToTop(scrollToTop)
                LaunchedEffect(state.generation) { list.scrollToItem(0) }
                LazyColumn(state = list, contentPadding = contentPadding.withTop(top)) {
                items(state.results, key = { it.id }) { video ->
                    VideoCard(video, onClick = { onOpen(video) })
                }
            }
            }
        }
        }
        }
    }
}

@Composable
private fun SearchHistoryList(
    history: List<String>,
    suggestions: List<String>,
    showHeader: Boolean,
    contentPadding: PaddingValues,
    onSearch: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    LazyColumn(contentPadding = contentPadding) {
        if (showHeader && history.isNotEmpty()) item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
            ) {
                SectionTitle(tr("Recent searches"), Modifier.weight(1f).alignByBaseline())
                Box(Modifier.alignByBaseline()) { TextLink(tr("Clear all")) { SearchHistory.clear() } }
            }
        }
        items(history, key = { "h:" + it }) { query ->
            SuggestionRow(query, Icons.Rounded.History, onClick = { onSearch(query) }, onFill = { onFill(query) }, onRemove = { SearchHistory.remove(query) })
        }
        items(suggestions, key = { "s:" + it }) { query ->
            SuggestionRow(query, Icons.Rounded.Search, onClick = { onSearch(query) }, onFill = { onFill(query) }, onRemove = null)
        }
    }
}

/** Satu baris saran: ikon, teks, (hapus untuk riwayat), dan panah untuk memasukkan teksnya ke kolom cari. */
@Composable
private fun LazyItemScope.SuggestionRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, onFill: () -> Unit, onRemove: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .animateItem()
            .fillMaxWidth()
            .pressable { onClick() }
            .height(52.dp)
            .padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            text,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onRemove != null) {
            Box(
                modifier = Modifier.size(44.dp).pressable { onRemove() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, tr("Remove"), tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        }
        Box(
            modifier = Modifier.size(44.dp).pressable { onFill() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.NorthWest, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
    }
}
