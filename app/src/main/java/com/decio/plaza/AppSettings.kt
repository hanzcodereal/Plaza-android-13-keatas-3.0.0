package com.decio.plaza

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/** Data developer yang tampil di menu Tentang. */
object AboutInfo {
    const val APP_NAME = "Plaza"
    const val DEVELOPER = "Hanz Dev Official"
    const val SUPPORT_URL = "https://saweria.co/hanzreally"
    const val EMAIL = "hanzzcodee@gmail.com"
}

/**
 * Konten tetap dari YouTube. Bahasa konten dan negara konten bisa dipilih, atau dibiarkan "Bawaan"
 * (mengikuti bahasa dan negara perangkat; kalau tidak ada di daftar, English dan Amerika Serikat).
 * Dipasang ke NewPipe dan ke permintaan InnerTube (hl / gl). Berlaku untuk pemuatan berikutnya.
 */
object LocaleSettings {
    const val DEFAULT = "default"

    data class Language(val code: String, val region: String, val label: String)
    data class Country(val code: String, val label: String)

    val languages = listOf(
        Language("id", "ID", "Bahasa Indonesia"),
        Language("en", "US", "English"),
        Language("ms", "MY", "Bahasa Melayu"),
        Language("es", "ES", "Español"),
        Language("pt", "BR", "Português"),
        Language("ja", "JP", "日本語"),
    )

    val countries = listOf(
        Country("ID", "Indonesia"),
        Country("US", "United States"),
        Country("MY", "Malaysia"),
        Country("SG", "Singapore"),
        Country("GB", "United Kingdom"),
        Country("JP", "Japan"),
        Country("BR", "Brazil"),
        Country("ES", "Spain"),
        Country("IN", "India"),
        Country("AU", "Australia"),
        Country("CA", "Canada"),
        Country("DE", "Germany"),
        Country("FR", "France"),
        Country("IT", "Italy"),
        Country("NL", "Netherlands"),
        Country("KR", "South Korea"),
        Country("TH", "Thailand"),
        Country("VN", "Vietnam"),
        Country("PH", "Philippines"),
        Country("MX", "Mexico"),
        Country("AR", "Argentina"),
        Country("TR", "Turkey"),
        Country("SA", "Saudi Arabia"),
        Country("AE", "United Arab Emirates"),
        Country("EG", "Egypt"),
        Country("NG", "Nigeria"),
        Country("ZA", "South Africa"),
        Country("PK", "Pakistan"),
        Country("BD", "Bangladesh"),
        Country("TW", "Taiwan"),
        Country("HK", "Hong Kong"),
        Country("NZ", "New Zealand"),
        Country("RU", "Russia"),
        Country("PL", "Poland"),
        Country("SE", "Sweden"),
        Country("CH", "Switzerland"),
    )

    private const val KEY_LANGUAGE = "content_language"
    private const val KEY_COUNTRY = "content_country"
    private const val KEY_PROMPTED = "region_prompted"
    private var prefs: SharedPreferences? = null

    /** Saat aplikasi pertama dibuka: tanya wilayah/negara konten sekali. */
    var needsRegionPrompt by mutableStateOf(false)
        private set

    /** Pilihan yang disimpan: [DEFAULT] atau kode bahasa. */
    var languageChoice by mutableStateOf(DEFAULT)
        private set

    /** Pilihan yang disimpan: [DEFAULT] atau kode negara. */
    var countryChoice by mutableStateOf(DEFAULT)
        private set

    /** Kode bahasa YouTube (hl) yang dipakai sekarang. */
    val language: String
        get() = if (languageChoice == DEFAULT) deviceLanguage() else languageChoice

    /** Kode negara konten (gl) yang dipakai sekarang. */
    val country: String
        get() = if (countryChoice == DEFAULT) deviceCountry() else countryChoice

    private fun deviceLanguage(): String {
        val code = java.util.Locale.getDefault().language.let { if (it == "in") "id" else it }
        return code.takeIf { c -> languages.any { it.code == c } } ?: "en"
    }

    private fun deviceCountry(): String =
        java.util.Locale.getDefault().country.takeIf { c -> countries.any { it.code == c } } ?: "US"

    fun init(context: Context) {
        val p = context.getSharedPreferences("locale", Context.MODE_PRIVATE).also { prefs = it }
        languageChoice = p.getString(KEY_LANGUAGE, null) ?: DEFAULT
        countryChoice = p.getString(KEY_COUNTRY, null) ?: DEFAULT
        // Tidak ada layar pilih wilayah di awal: wilayah mengikuti perangkat (bisa diubah di pengaturan).
        needsRegionPrompt = false
        apply()
    }

    /** Pilihan dari layar pertama: [DEFAULT] (ikut perangkat) atau kode negara. */
    fun confirmRegion(choice: String) {
        chooseCountry(choice)
        prefs?.edit()?.putBoolean(KEY_PROMPTED, true)?.apply()
        needsRegionPrompt = false
    }

    fun chooseLanguage(choice: String) {
        languageChoice = choice
        prefs?.edit()?.putString(KEY_LANGUAGE, choice)?.apply()
        apply()
    }

    fun chooseCountry(choice: String) {
        countryChoice = choice
        prefs?.edit()?.putString(KEY_COUNTRY, choice)?.apply()
        apply()
    }

    private fun apply() {
        val region = languages.firstOrNull { it.code == language }?.region
        runCatching { NewPipe.setupLocalization(Localization(language, region), ContentCountry(country)) }
    }
}

@Composable
private fun SettingTitle(text: String) {
    Text(text, fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
}

/** Deretan pilihan yang bisa digeser, gayanya sama dengan pilihan tema di Appearance. */
@Composable
private fun ChoiceChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier
                    .pressable { onSelect(key) }
                    .clip(RoundedCornerShape(if (on) 18.dp else 10.dp))
                    .background(if (on) c.secondaryContainer else c.surfaceContainerHighest)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(
                    label,
                    color = if (on) c.onSecondaryContainer else c.onSurfaceVariant,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

// ── Bahasa (tampilan aplikasi) ─────────────────────────────────────────────────

@Composable
fun LanguageSettings() {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        SettingTitle(tr("Language"))
        Text(tr("Language used in Plaza's screens"), color = c.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        ChoiceChips(
            options = listOf(AppLanguage.ID to "Bahasa Indonesia", AppLanguage.EN to "English"),
            selected = AppLanguage.code,
            onSelect = AppLanguage::choose,
        )
    }
}

// ── Pilih wilayah saat aplikasi pertama dibuka ─────────────────────────────────

/** Bendera dari kode negara (dua huruf → dua penunjuk wilayah). */
private fun flagOf(code: String): String =
    if (code.length != 2) "" else String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (code[1] - 'A')))

/** Layar penuh sekali di awal: pilih wilayah/negara konten. Bisa diubah lagi di Pengaturan → Konten. */
@Composable
fun RegionPrompt() {
    if (!LocaleSettings.needsRegionPrompt) return
    val c = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(c.surface).systemBarsPadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Text(tr("Choose your region"), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Videos and trends follow this region. You can change it later in Content settings."),
                color = c.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f)) {
                item(key = "device") {
                    RegionRow("🌐", tr("Use my device's region")) { LocaleSettings.confirmRegion(LocaleSettings.DEFAULT) }
                }
                items(LocaleSettings.countries, key = { it.code }) { country ->
                    RegionRow(flagOf(country.code), tr(country.label)) { LocaleSettings.confirmRegion(country.code) }
                }
            }
        }
    }
}

@Composable
private fun RegionRow(flag: String, label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerLow)
            .pressable(pressedScale = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, fontSize = 22.sp)
        Spacer(Modifier.width(14.dp))
        Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

// ── Konten (tetap dari YouTube: bahasa dan negara konten) ──────────────────────

@Composable
fun ContentSettings() {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        SettingTitle(tr("Content"))
        Text(tr("Content comes from YouTube. Choose its language and country."), color = c.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text(tr("Content language"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        ChoiceChips(
            options = listOf(LocaleSettings.DEFAULT to tr("Default")) + LocaleSettings.languages.map { it.code to it.label },
            selected = LocaleSettings.languageChoice,
            onSelect = LocaleSettings::chooseLanguage,
        )
        Spacer(Modifier.height(12.dp))
        Text(tr("Content country"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        ChoiceChips(
            options = listOf(LocaleSettings.DEFAULT to tr("Default")) + LocaleSettings.countries.map { it.code to tr(it.label) },
            selected = LocaleSettings.countryChoice,
            onSelect = LocaleSettings::chooseCountry,
        )
        Spacer(Modifier.height(8.dp))
        Text(tr("Applies to lists loaded next."), color = c.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 16.sp)
    }
}

// ── Tentang ────────────────────────────────────────────────────────────────────

@Composable
fun AboutSettings() {
    val c = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val open: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }
    Column(Modifier.fillMaxWidth()) {
        SettingTitle(tr("About"))
        Spacer(Modifier.height(10.dp))
        AboutRow(tr("App name"), AboutInfo.APP_NAME)
        AboutRow(tr("Version"), BuildConfig.VERSION_NAME)
        AboutRow("Developer", AboutInfo.DEVELOPER)
        AboutRow(tr("Support the developer"), AboutInfo.SUPPORT_URL.removePrefix("https://"), link = c.primary) { open(AboutInfo.SUPPORT_URL) }
        AboutRow("Email", AboutInfo.EMAIL, link = c.primary) { open("mailto:${AboutInfo.EMAIL}") }
    }
}

@Composable
private fun AboutRow(
    label: String,
    value: String,
    link: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(120.dp))
        val valueModifier = if (onClick != null) Modifier.weight(1f).pressable { onClick() } else Modifier.weight(1f)
        Text(
            value,
            modifier = valueModifier,
            color = link ?: c.onSurface,
            fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            textDecoration = if (link != null) TextDecoration.Underline else null,
        )
    }
}
