package com.decio.plaza

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Suka / tidak suka postingan: dipakai bersama oleh kartu di daftar dan tampilan layar penuh. */
object PostReactions {
    class Mark(val liked: Boolean, val disliked: Boolean, val likes: String?)

    val marks = mutableStateMapOf<String, Mark>()

    fun of(post: ChannelPost): Mark = marks[post.id] ?: Mark(post.liked, post.disliked, post.likes)

    private fun bump(text: String?, delta: Int): String? {
        if (text.isNullOrBlank()) return if (delta > 0) "1" else null
        val n = text.toIntOrNull() ?: return text
        return (n + delta).coerceAtLeast(0).takeIf { it > 0 }?.toString()
    }

    fun toggleLike(scope: CoroutineScope, post: ChannelPost, onMessage: (String) -> Unit) {
        if (!Account.signedIn) { onMessage("Masuk dulu untuk menyukai postingan."); return }
        val before = of(post)
        marks[post.id] = if (before.liked) Mark(false, false, bump(before.likes, -1)) else Mark(true, false, bump(before.likes, 1))
        scope.launch {
            runCatching { InnerTube.reactToPost(post, if (before.liked) "removelike" else "like") }
                .onFailure { marks[post.id] = before; onMessage("Gagal memperbarui suka.") }
        }
    }

    fun toggleDislike(scope: CoroutineScope, post: ChannelPost, onMessage: (String) -> Unit) {
        if (!Account.signedIn) { onMessage("Masuk dulu untuk memberi tidak suka."); return }
        val before = of(post)
        marks[post.id] = if (before.disliked) Mark(false, false, before.likes)
        else Mark(false, true, if (before.liked) bump(before.likes, -1) else before.likes)
        scope.launch {
            runCatching { InnerTube.reactToPost(post, if (before.disliked) "removelike" else "dislike") }
                .onFailure { marks[post.id] = before; onMessage("Gagal memperbarui tidak suka.") }
        }
    }
}

private fun sharePost(context: android.content.Context, post: ChannelPost) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/post/${post.id}")
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Bagikan postingan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Baris tombol: suka, tidak suka, komentar, bagikan. */
@Composable
fun PostActionsRow(
    post: ChannelPost,
    onComments: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    active: Color = MaterialTheme.colorScheme.primary,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val mark = PostReactions.of(post)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PostAction(Icons.Rounded.ThumbUp, "Suka", mark.likes, if (mark.liked) active else tint) { PostReactions.toggleLike(scope, post, onMessage) }
        PostAction(Icons.Rounded.ThumbDown, "Tidak suka", null, if (mark.disliked) active else tint) { PostReactions.toggleDislike(scope, post, onMessage) }
        PostAction(Icons.Rounded.ChatBubbleOutline, "Komentar", post.comments, tint, onComments)
        PostAction(Icons.Rounded.Share, "Bagikan", null, tint) { sharePost(context, post) }
    }
}

@Composable
private fun PostAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: String?, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(22.dp))
        if (!count.isNullOrBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(count, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Kartu postingan di daftar (halaman channel): selebar layar tanpa bingkai seperti YouTube, gambar utuh tidak dipotong. */
@Composable
fun PostCard(post: ChannelPost, showAuthor: Boolean, onOpen: () -> Unit, onComments: () -> Unit, onMessage: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(top = 14.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showAuthor && post.author != null) {
                ChannelAvatar(post.authorAvatar, post.author, size = 32)
                Spacer(Modifier.width(8.dp))
                Text(post.author, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
            }
            if (post.ago != null) Text(post.ago, color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (post.text.isNotBlank()) {
            Text(
                post.text, fontSize = 14.5.sp, lineHeight = 20.sp, maxLines = 8, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (post.images.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Box {
                AsyncImage(
                    model = post.images.first(), contentDescription = null, contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().background(c.surfaceContainerHighest),
                )
                if (post.images.size > 1) {
                    Text(
                        "1/${post.images.size}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        PostActionsRow(post, onComments, onMessage, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).background(c.surfaceContainerLow))
    }
}

/** Rak "Postingan" di Beranda: kartu kecil yang bisa digeser; ketuk membuka layar penuh. */
@Composable
fun PostsShelf(posts: List<ChannelPost>, onOpen: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            "Postingan",
            fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(posts.size, key = { posts[it].id }) { i ->
                val post = posts[i]
                Column(
                    Modifier.width(260.dp).clip(RoundedCornerShape(18.dp)).background(c.surfaceContainerLow)
                        .clickable { onOpen(i) }.padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChannelAvatar(post.authorAvatar, post.author.orEmpty(), size = 28)
                        Spacer(Modifier.width(8.dp))
                        Text(post.author.orEmpty(), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (post.text.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(post.text, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (post.images.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        AsyncImage(
                            model = post.images.first(), contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceContainerHighest),
                        )
                    }
                    if (!post.likes.isNullOrBlank() || !post.comments.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            listOfNotNull(post.likes?.let { "$it suka" }, post.comments?.let { "$it komentar" }).joinDots(),
                            color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Postingan layar penuh: geser ke atas/bawah untuk postingan berikutnya, geser ke samping untuk
 * gambar kedua dan seterusnya. Gambar tampil utuh (tidak dipotong), lengkap dengan suka,
 * tidak suka, komentar, dan bagikan.
 */
@Composable
fun PostViewer(posts: List<ChannelPost>, start: Int, onClose: () -> Unit, onMessage: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val say: (String) -> Unit = onMessage ?: { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    if (posts.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var commentsFor by remember { mutableStateOf<ChannelPost?>(null) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Jendela dialog memenuhi seluruh layar: tanpa bingkai, tanpa redup, latar hitam.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
                setDimAmount(0f)
                WindowCompat.setDecorFitsSystemWindows(this, false)
            }
        }
        val pager = rememberPagerState(initialPage = start.coerceIn(0, posts.lastIndex)) { posts.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VerticalPager(pager, Modifier.fillMaxSize(), key = { posts.getOrNull(it)?.id ?: it }) { page ->
                PostPage(posts[page], onComments = { commentsFor = it }, onMessage = say)
            }
            Box(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp).size(44.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Tutup", tint = Color.White) }
            commentsFor?.let { post -> PostCommentsSheet(post) { commentsFor = null } }
        }
    }
}

@Composable
private fun PostPage(post: ChannelPost, onComments: (ChannelPost) -> Unit, onMessage: (String) -> Unit) {
    var expanded by remember(post.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, top = 8.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            if (post.author != null) {
                ChannelAvatar(post.authorAvatar, post.author, size = 32)
                Spacer(Modifier.width(8.dp))
                Text(post.author, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            if (post.ago != null) {
                Spacer(Modifier.width(8.dp))
                Text(post.ago, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (post.images.isNotEmpty()) {
                val images = rememberPagerState { post.images.size }
                HorizontalPager(images, Modifier.fillMaxSize()) { i ->
                    AsyncImage(
                        model = post.images[i], contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (post.images.size > 1) {
                    Text(
                        "${images.currentPage + 1}/${post.images.size}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(post.images.size) { i ->
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == images.currentPage) Color.White else Color.White.copy(alpha = 0.35f)))
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Text(post.text, color = Color.White, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (post.images.isNotEmpty() && post.text.isNotBlank()) {
            Text(
                post.text, color = Color.White, fontSize = 14.sp, lineHeight = 19.sp,
                maxLines = if (expanded) 12 else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        PostActionsRow(
            post, onComments = { onComments(post) }, onMessage = onMessage,
            modifier = Modifier.padding(horizontal = 8.dp),
            tint = Color.White.copy(alpha = 0.85f), active = Color(0xFFFF5C7A),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostCommentsSheet(post: ChannelPost, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var page by remember(post.id) { mutableStateOf<CommentsPage?>(null) }
    var error by remember(post.id) { mutableStateOf<String?>(null) }
    var reload by remember(post.id) { mutableIntStateOf(0) }
    var draft by remember(post.id) { mutableStateOf("") }
    var sending by remember(post.id) { mutableStateOf(false) }
    var sendError by remember(post.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(post.id, reload) {
        runCatching { InnerTube.postComments(post) }
            .onSuccess { p ->
                page = p
                ChannelNames.resolve(scope, p.comments.mapNotNull { it.channelId })
            }
            .onFailure { error = YouTube.friendlyError(it) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 600.dp).padding(bottom = 16.dp)) {
            Text(
                "Komentar" + (page?.total?.takeIf { it >= 0 }?.let { " · ${formatCount(it.toLong())}" } ?: ""),
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            val p = page
            Box(Modifier.weight(1f, fill = false)) {
                when {
                    error != null -> Text(error.orEmpty(), color = c.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                    p == null -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                        CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                    }
                    p.comments.isEmpty() -> Text(
                        if (p.disabled) "Komentar tidak tersedia untuk postingan ini." else "Belum ada komentar.",
                        color = c.onSurfaceVariant, modifier = Modifier.padding(20.dp),
                    )
                    else -> LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                        items(p.comments, key = { it.id }) { comment ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                ChannelAvatar(comment.avatar, comment.displayName(), size = 36)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        listOfNotNull(comment.displayName(), comment.ago).joinDots(),
                                        color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    )
                                    Text(comment.text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
                                    if (comment.likes > 0) Text("${formatCount(comment.likes.toLong())} suka", color = c.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
            // Kotak komentar di bawah, seperti di video.
            when {
                !Account.signedIn -> Text("Masuk dengan Google untuk menulis komentar.", color = c.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                p?.createParams == null -> if (p != null) Text("Komentar baru tidak bisa ditulis di postingan ini.", color = c.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                else -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        modifier = Modifier.weight(1f), shape = CircleShape, maxLines = 3,
                        placeholder = { Text("Tulis komentar…") },
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(c.primary)
                            .clickable(enabled = !sending && draft.isNotBlank()) {
                                val text = draft.trim()
                                val params = p.createParams ?: return@clickable
                                sending = true
                                scope.launch {
                                    runCatching { InnerTube.createPostComment(params, text) }
                                        .onSuccess { draft = ""; sendError = null; reload++ }
                                        .onFailure { sendError = YouTube.friendlyError(it) }
                                    sending = false
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Rounded.Send, "Kirim", tint = c.onPrimary, modifier = Modifier.size(22.dp)) }
                }
            }
            sendError?.let { Text(it, color = c.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
    }
}
