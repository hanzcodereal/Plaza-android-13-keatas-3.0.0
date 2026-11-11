package com.decio.plaza

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Layar pembuka hanya tampil sekali per aplikasi dibuka (bukan saat layar diputar). */
object LaunchSplashState {
    var done by mutableStateOf(false)
}

/**
 * Layar pembuka: hanya logo dan nama Plaza di tengah, warnanya mengikuti tema di Pengaturan
 * (terang, gelap, atau AMOLED). Sebentar saja, lalu memudar ke aplikasi.
 */
@Composable
fun LaunchSplash() {
    if (LaunchSplashState.done) return
    val c = MaterialTheme.colorScheme
    val alpha = remember { Animatable(1f) }
    val pop = remember { Animatable(0.82f) }
    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
        delay(1_000)
        alpha.animateTo(0f, tween(300))
        LaunchSplashState.done = true
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(c.surface)
            // Selama tampil, sentuhan tidak menembus ke aplikasi di bawahnya.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.graphicsLayer { scaleX = 2.4f; scaleY = 2.4f }) { BrandMark() }
            Spacer(Modifier.height(44.dp))
            Text(
                stringResource(R.string.app_name),
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp,
                color = c.onSurface,
            )
        }
        // Di bawah: versi aplikasi dalam kapsul kecil, lalu "by Hanz Dev Official" tepat di bawahnya.
        val context = LocalContext.current
        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (version.isNotBlank()) {
                Text(
                    "Versi $version",
                    color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surfaceContainerHigh)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                "by Hanz Dev Official",
                color = c.primary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp,
            )
        }
    }
}
