package com.decio.plaza

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Putar otomatis (seperti YouTube): selesai satu video dan antrean kosong, pemutar lanjut ke
 * video terkait pertama yang belum diputar. Hidup secara bawaan; sakelarnya ada di pengaturan
 * akun dan di lembar kecepatan pemutar.
 */
object AutoplaySetting {
    private const val KEY = "autoplay_next"
    private var prefs: SharedPreferences? = null

    var enabled by mutableStateOf(true)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        enabled = prefs?.getBoolean(KEY, true) != false
    }

    fun set(on: Boolean) {
        if (on == enabled) return
        enabled = on
        prefs?.edit()?.putBoolean(KEY, on)?.apply()
    }
}

/**
 * Sakelar Putar otomatis seperti di YouTube: lintasan kecil dengan tombol bergambar play. Ada di
 * baris atas video dan di judul "Video lainnya"; mengubah [AutoplaySetting] untuk semua video.
 */
@Composable
fun AutoplaySwitch(modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    val on = AutoplaySetting.enabled
    val thumb by animateDpAsState(if (on) 18.dp else 2.dp, label = "autoplayThumb")
    Box(
        modifier
            .size(40.dp, 22.dp)
            .pressable(enabled = enabled) { AutoplaySetting.set(!on) }
            .clip(CircleShape)
            .background(if (on) c.primaryContainer else Color.Black.copy(alpha = 0.38f)),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumb)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (on) c.onPrimaryContainer else Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PlayArrow, null, tint = if (on) c.primaryContainer else Color.DarkGray, modifier = Modifier.size(13.dp))
        }
    }
}
