package com.decio.plaza

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Trek subtitle milik video yang sedang diputar (kosong selama detailnya belum untuk video ini). */
private fun PlayerState.captionTracks(): List<CaptionTrack> {
    val d = details ?: return emptyList()
    return if (d.item.id == current?.id) d.captions else emptyList()
}

/** Berapa baris subtitle diterjemahkan dalam satu permintaan (dan seberapa jauh ke depan). */
private const val TranslateBatch = 25

/**
 * Baris subtitle di atas video. Berada di bawah tengah; [bottom] menaikkannya dari tepi bawah
 * (lebih tinggi selagi kontrol tampil, supaya tidak tertutup scrubber). Teks asli selalu diunduh;
 * kalau terjemahan otomatis dipilih, baris-barisnya diterjemahkan sedikit demi sedikit di depan
 * posisi putar ([Translator.translateLines]) dan teks asli tampil sampai terjemahannya siap.
 */
@Composable
fun CaptionOverlay(state: PlayerState, bottom: Dp, modifier: Modifier = Modifier) {
    val id = state.current?.id
    val tracks = state.captionTracks()
    val to = Captions.translateTo
    val cues by produceState<List<CaptionCue>>(emptyList(), id, tracks, Captions.enabled, Captions.tag) {
        value = emptyList()
        if (!Captions.enabled) return@produceState
        val track = Captions.pick(tracks) ?: return@produceState
        value = Captions.load(track, null)
    }
    val translated = remember(id, to) { mutableStateMapOf<Int, String>() }
    // Hanya berubah saat baris berganti (bukan tiap detik putar), supaya tidak menyusun ulang terus.
    val started by remember(cues) { derivedStateOf { Captions.startedIndex(cues, state.positionMs) } }
    val active by remember(cues) { derivedStateOf { Captions.activeIndex(cues, state.positionMs) } }
    val bucket = if (started < 0) 0 else started / TranslateBatch
    LaunchedEffect(cues, to, bucket) {
        if (to == null || !Captions.enabled || cues.isEmpty()) return@LaunchedEffect
        for (b in bucket..bucket + 1) {
            val from = b * TranslateBatch
            if (from >= cues.size || translated.containsKey(from)) continue
            val slice = cues.subList(from, minOf(cues.size, from + TranslateBatch))
            val out = Translator.translateLines(slice.map { it.text }, to) ?: continue
            out.forEachIndexed { i, text -> translated[from + i] = text }
        }
    }
    if (!Captions.enabled || cues.isEmpty() || active < 0) return
    val text = (if (to != null) translated[active] else null) ?: cues[active].text
    Text(
        text,
        color = Color.White,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(start = 28.dp, end = 28.dp, bottom = bottom)
            .background(Color.Black.copy(alpha = 0.66f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Pilihan subtitle: mati, trek yang ada, dan terjemahan otomatis ke bahasa lain. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionSheet(state: PlayerState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val tracks = state.captionTracks()
    val current = Captions.pick(tracks)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                tr("Subtitles"),
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
            QualityRow(tr("Off"), null, !Captions.enabled) { Captions.enabled = false; onDismiss() }
            if (tracks.isEmpty()) {
                Text(
                    tr("This video has no subtitles."),
                    color = c.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            tracks.forEach { track ->
                QualityRow(
                    track.label,
                    if (track.auto) tr("Auto-generated") else null,
                    Captions.enabled && Captions.translateTo == null && track == current,
                ) {
                    Captions.tag = track.languageTag
                    Captions.translateTo = null
                    Captions.enabled = true
                    onDismiss()
                }
            }
            if (tracks.isNotEmpty()) {
                Text(
                    tr("Auto-translate"),
                    fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = c.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
                )
                Captions.translateChoices.forEach { (code, name) ->
                    QualityRow(name, null, Captions.enabled && Captions.translateTo == code) {
                        Captions.translateTo = code
                        Captions.enabled = true
                        onDismiss()
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

private val QuickSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

/** Kecepatan putar cepat dan sakelar audio saja, untuk kontrol di atas video. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(state: PlayerState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                tr("Playback speed"),
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
            QuickSpeeds.forEach { v ->
                QualityRow(
                    if (v == 1f) tr("Normal") else PlaybackSpeed.label(v) + "×",
                    null,
                    abs(state.speed - v) < 0.01f,
                ) { state.commitSpeed(v); onDismiss() }
            }
            QualityRow(tr("Audio only"), null, AudioOnly.enabled) { state.setAudioOnly(!AudioOnly.enabled); onDismiss() }
            QualityRow(tr("Autoplay"), null, AutoplaySetting.enabled) { AutoplaySetting.set(!AutoplaySetting.enabled); onDismiss() }
            Spacer(Modifier.height(28.dp))
        }
    }
}
