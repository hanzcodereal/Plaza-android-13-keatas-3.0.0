package com.decio.plaza

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Video lainnya": daftar video terkait, dibuka dengan geser ke atas di fullscreen (seperti YouTube).
 * Digambar di jendela yang sama dengan pemutar, bukan sheet berjendela sendiri, supaya mode layar
 * penuh tidak terganggu: jam, sinyal, dan baterai tetap tersembunyi. Tutup dengan ketuk bagian gelap
 * atau tombol Kembali.
 */
@Composable
fun RelatedSheet(state: PlayerState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val details = state.details?.takeIf { it.item.id == state.current?.id }
    val related = details?.related.orEmpty()
    BackHandler(onBack = onDismiss)
    // Fullscreen stays immersive on this page too: clock, signal and battery hidden while it is open.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pressable(pressedScale = 1f, onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.surfaceContainerLow)
                // A tap on the panel itself isn't a tap on the dark part behind it.
                .pressable(pressedScale = 1f) { },
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.onSurfaceVariant.copy(alpha = 0.4f)),
            )
            Text(
                tr("More videos"),
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp),
            )
            if (related.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                }
            } else {
                LazyColumn {
                    items(related, key = { "rel-" + it.id }) { video ->
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            VideoRow(video, onClick = { state.open(video); onDismiss() }, withDate = true)
                        }
                    }
                }
            }
        }
    }
}
