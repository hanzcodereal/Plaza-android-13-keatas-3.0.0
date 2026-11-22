package com.decio.plaza

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Gaya tampilan: Bawaan atau Liquid Glass. Liquid Glass memberi latar yang hidup (cahaya warna tema yang
 * bergerak pelan) dan permukaan kaca: isi tembus pandang, kilau di atas, dan tepi bercahaya. Pilihan
 * ini terpisah dari tema warna dan mode terang/gelap, jadi semua tema warna tetap bisa dipakai.
 */
object GlassSettings {
    private var prefs: SharedPreferences? = null

    var enabled by mutableStateOf(false)
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).also { prefs = it }
        enabled = p.getBoolean("glass", false)
    }

    fun choose(on: Boolean) {
        enabled = on
        prefs?.edit()?.putBoolean("glass", on)?.apply()
    }
}

/** Outline bentuk apa pun jadi Path, supaya bisa digambar dengan drawPath. */
private fun Outline.toPath(): Path = when (this) {
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
}

private val GlassSheen = listOf(0f to Color.White.copy(alpha = 0.26f), 0.55f to Color.Transparent, 1f to Color.White.copy(alpha = 0.06f))

private fun glassRim(w: Float, h: Float) = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.85f),
    0.45f to Color.White.copy(alpha = 0.12f),
    1f to Color.White.copy(alpha = 0.45f),
    start = Offset.Zero,
    end = Offset(w, h),
)

/**
 * Isi kaca di dalam [shape]: tinta tema yang tembus pandang (lebih pekat di bawah, seperti cahaya jatuh
 * dari atas), kilau putih di bagian atas, dan tepi yang terang di sudut kiri atas dan kanan bawah.
 * Digambar di dalam ukuran penggambaran saat ini; [alpha] 0 sampai 1 untuk memudar.
 */
internal fun DrawScope.drawGlassBody(shape: Shape, tint: Color, alpha: Float = 1f) {
    if (alpha <= 0f) return
    val path = shape.createOutline(size, layoutDirection, this).toPath()
    drawPath(path, Brush.verticalGradient(listOf(tint.copy(alpha = 0.70f), tint.copy(alpha = 0.50f))), alpha = alpha)
    drawGlassRim(path, alpha)
}

/** Hanya kilau dan tepi bercahaya di atas [path] (tanpa isi): dipakai juga untuk kaca buram bilah Shorts. */
internal fun DrawScope.drawGlassRim(path: Path, alpha: Float = 1f) {
    if (alpha <= 0f) return
    drawPath(path, Brush.verticalGradient(*GlassSheen.toTypedArray()), alpha = alpha)
    drawPath(path, glassRim(size.width, size.height), alpha = alpha, style = Stroke(width = 2.2.dp.toPx()))
}

/**
 * Permukaan berbentuk [shape] berwarna [tint]: polos seperti biasa, atau kaca di gaya Liquid Glass.
 * Pengganti `.clip(shape).background(tint)`. Bentuk dan kuas dihitung sekali per ukuran, bukan tiap
 * gambar, jadi tetap ringan di daftar yang menggulir.
 */
fun Modifier.glassSurface(shape: Shape, tint: Color): Modifier =
    if (!GlassSettings.enabled) clip(shape).background(tint)
    else clip(shape).drawWithCache {
        val path = shape.createOutline(size, layoutDirection, this).toPath()
        val body = Brush.verticalGradient(listOf(tint.copy(alpha = 0.70f), tint.copy(alpha = 0.50f)))
        val sheen = Brush.verticalGradient(*GlassSheen.toTypedArray())
        val rim = glassRim(size.width, size.height)
        val stroke = Stroke(width = 2.2.dp.toPx())
        onDrawBehind {
            drawPath(path, body)
            drawPath(path, sheen)
            drawPath(path, rim, style = stroke)
        }
    }

/** Seperti [glassSurface] tapi tanpa memotong isinya (untuk elemen yang percikannya boleh keluar dari tepi). */
fun Modifier.glassBackground(shape: Shape, tint: Color): Modifier =
    if (!GlassSettings.enabled) background(tint, shape)
    else drawWithCache {
        val path = shape.createOutline(size, layoutDirection, this).toPath()
        val body = Brush.verticalGradient(listOf(tint.copy(alpha = 0.70f), tint.copy(alpha = 0.50f)))
        val sheen = Brush.verticalGradient(*GlassSheen.toTypedArray())
        val rim = glassRim(size.width, size.height)
        val stroke = Stroke(width = 2.2.dp.toPx())
        onDrawBehind {
            drawPath(path, body)
            drawPath(path, sheen)
            drawPath(path, rim, style = stroke)
        }
    }

/** Gerak pelan latar Liquid Glass: 0..1 bolak-balik tiap ±26 detik; diam (0,5) kalau gerakan dikurangi. */
@Composable
fun rememberGlassDrift(): State<Float> {
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "glassBackdrop")
    val drift = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    return if (reduced) androidx.compose.runtime.remember { mutableStateOf(0.5f) } else drift
}

/** Tiga cahaya lembut dari warna tema (bergeser dengan [t] 0..1) di seluruh area gambar; [fade] 0..1 untuk memudar. */
internal fun DrawScope.drawGlassBlobs(first: Color, second: Color, third: Color, t: Float, strength: Float, fade: Float = 1f) {
    if (fade <= 0f) return
    val w = size.width
    val h = size.height
    val big = maxOf(w, h)
    drawRect(
        Brush.radialGradient(
            listOf(first.copy(alpha = 0.34f * strength * fade), Color.Transparent),
            center = Offset(w * (0.10f + 0.35f * t), h * (0.10f + 0.12f * t)),
            radius = big * 0.62f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            listOf(second.copy(alpha = 0.26f * strength * fade), Color.Transparent),
            center = Offset(w * (0.95f - 0.30f * t), h * (0.45f + 0.15f * t)),
            radius = big * 0.55f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            listOf(third.copy(alpha = 0.40f * strength * fade), Color.Transparent),
            center = Offset(w * (0.25f + 0.30f * t), h * (0.95f - 0.12f * t)),
            radius = big * 0.6f,
        ),
    )
}

/** Kekuatan cahaya latar: penuh di tema gelap, lebih lembut di tema terang. */
@Composable
fun glassBlobStrength(): Float = if (isDarkTheme()) 1f else 0.7f

/**
 * Latar halaman: warna [base] polos, atau di Liquid Glass warna itu ditambah tiga cahaya lembut dari warna
 * tema yang bergeser sangat pelan, supaya kaca di atasnya punya sesuatu untuk dipantulkan. Gerakan hanya
 * memicu penggambaran ulang, bukan komposisi ulang.
 */
@Composable
fun Modifier.pageBackdrop(base: Color): Modifier {
    if (!GlassSettings.enabled) return background(base)
    val c = MaterialTheme.colorScheme
    val drift = rememberGlassDrift()
    val strength = glassBlobStrength()
    val first = c.primary
    val second = c.tertiary
    val third = c.primaryContainer
    return drawBehind {
        drawRect(base)
        drawGlassBlobs(first, second, third, drift.value, strength)
    }
}
