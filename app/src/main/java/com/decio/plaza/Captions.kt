package com.decio.plaza

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Satu trek subtitle milik video: [url] diambil dari NewPipe, [auto] = dibuat otomatis oleh YouTube. */
@Immutable
data class CaptionTrack(val url: String, val languageTag: String, val auto: Boolean) {
    val label: String
        get() {
            val name = Locale.forLanguageTag(languageTag).getDisplayLanguage(Locale.getDefault())
            return name.ifBlank { languageTag }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
}

/** Satu baris subtitle yang tampil dari [startMs] sampai [endMs]. */
@Immutable
data class CaptionCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Subtitle (CC): pilihan pengguna (hidup/mati, bahasa, terjemahan otomatis) berlaku untuk semua
 * video selama aplikasi jalan. Trek diunduh dan di-parse di sini; baris yang tampil digambar oleh
 * [CaptionOverlay] di atas video.
 */
object Captions {
    var enabled by mutableStateOf(false)
    /** Bahasa trek yang dipilih; null = trek asli pertama (bukan otomatis kalau ada). */
    var tag by mutableStateOf<String?>(null)
    /** Kode bahasa tujuan terjemahan otomatis YouTube (`tlang`); null = tanpa terjemahan. */
    var translateTo by mutableStateOf<String?>(null)

    /** Bahasa tujuan terjemahan yang ditawarkan: kode to nama. */
    val translateChoices: List<Pair<String, String>> = listOf(
        "id" to "Indonesia",
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "pt" to "Português",
        "ru" to "Русский",
        "ar" to "العربية",
        "hi" to "हिन्दी",
        "ja" to "日本語",
        "ko" to "한국어",
        "zh-Hans" to "中文",
    )

    fun pick(tracks: List<CaptionTrack>): CaptionTrack? =
        tracks.firstOrNull { it.languageTag == tag } ?: tracks.firstOrNull { !it.auto } ?: tracks.firstOrNull()

    private val cache = ConcurrentHashMap<String, List<CaptionCue>>()

    /** Mengunduh dan mem-parse [track] (opsional diterjemahkan YouTube ke [translateTo]). Kosong kalau gagal. */
    suspend fun load(track: CaptionTrack, translateTo: String?): List<CaptionCue> = withContext(Dispatchers.IO) {
        val base = track.url.toHttpUrlOrNull() ?: return@withContext emptyList()
        val url = base.newBuilder().apply { if (translateTo != null) setQueryParameter("tlang", translateTo) }.build().toString()
        cache[url]?.let { return@withContext it }
        val request = okhttp3.Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()
        val body: String? = try {
            Net.client.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
        } catch (e: Exception) {
            null
        }
        if (body == null) return@withContext emptyList()
        val cues = parse(body)
        if (cues.isNotEmpty()) {
            if (cache.size > 12) cache.clear()
            cache[url] = cues
        }
        cues
    }

    /** Indeks cue terakhir yang sudah mulai pada [ms] (bisa sudah selesai), atau -1. */
    fun startedIndex(cues: List<CaptionCue>, ms: Long): Int {
        var lo = 0
        var hi = cues.size - 1
        var index = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= ms) { index = mid; lo = mid + 1 } else hi = mid - 1
        }
        return index
    }

    /** Indeks cue yang sedang tampil pada [ms] (yang mulai paling akhir di antara yang masih berlaku), atau -1. */
    fun activeIndex(cues: List<CaptionCue>, ms: Long): Int {
        var i = startedIndex(cues, ms)
        var steps = 0
        while (i >= 0 && steps < 6) {
            if (ms < cues[i].endMs) return i
            i--
            steps++
        }
        return -1
    }

    // ── Parser (WebVTT, dan XML YouTube: TTML / srv3 / transkrip lama) ──

    fun parse(text: String): List<CaptionCue> {
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val raw = if (t.startsWith("WEBVTT")) parseVtt(t) else parseXml(t)
        val sorted = raw.sortedBy { it.startMs }
        val out = ArrayList<CaptionCue>(sorted.size)
        for (cue in sorted) {
            val last = out.lastOrNull()
            // Subtitle otomatis YouTube mengulang baris yang sama; yang identik dan menyambung digabung.
            if (last != null && last.text == cue.text && cue.startMs <= last.endMs) {
                out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, cue.endMs))
            } else {
                out += cue
            }
        }
        return out
    }

    private val vttTime = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{3})")

    private fun vttMs(s: String): Long? {
        val m = vttTime.find(s) ?: return null
        val (h, mi, se, ms) = m.destructured
        val hours = if (h.isEmpty()) 0L else h.toLong()
        return ((hours * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + ms.toLong()
    }

    private fun parseVtt(t: String): List<CaptionCue> {
        val out = ArrayList<CaptionCue>()
        for (block in t.replace("\r\n", "\n").split("\n\n")) {
            val lines = block.lines()
            val i = lines.indexOfFirst { "-->" in it }
            if (i < 0) continue
            val parts = lines[i].split("-->")
            if (parts.size < 2) continue
            val start = vttMs(parts[0]) ?: continue
            val end = vttMs(parts[1]) ?: continue
            val body = clean(lines.drop(i + 1).joinToString("\n"))
            if (body.isNotBlank()) out += CaptionCue(start, end, body)
        }
        return out
    }

    private val pTag = Regex("<p\\b([^>]*)>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val textTag = Regex("<text\\b([^>]*)>(.*?)</text>", RegexOption.DOT_MATCHES_ALL)

    private fun attr(attrs: String, name: String): String? =
        Regex("\\b" + name + "=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    /** "00:00:01.200", "1.2s", "1200ms" → milidetik. */
    private fun clock(v: String): Long? {
        val s = v.trim()
        if (s.endsWith("ms")) return s.dropLast(2).toDoubleOrNull()?.toLong()
        if (s.endsWith("s")) return s.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
        return vttMs(s)
    }

    private fun parseXml(t: String): List<CaptionCue> {
        val out = ArrayList<CaptionCue>()
        for (m in pTag.findAll(t)) {
            val attrs = m.groupValues[1]
            val start: Long
            val end: Long
            val tt = attr(attrs, "t")
            val dd = attr(attrs, "d")
            if (tt != null && dd != null) {
                // srv3: t / d dalam milidetik.
                start = tt.toLongOrNull() ?: continue
                end = start + (dd.toLongOrNull() ?: 0L)
            } else {
                start = attr(attrs, "begin")?.let { clock(it) } ?: continue
                end = attr(attrs, "end")?.let { clock(it) }
                    ?: attr(attrs, "dur")?.let { clock(it) }?.let { start + it }
                    ?: (start + 2000L)
            }
            val body = clean(m.groupValues[2])
            if (body.isNotBlank()) out += CaptionCue(start, end, body)
        }
        if (out.isNotEmpty()) return out
        // Format transkrip lama: <text start="1.2" dur="3.4">…</text> (detik).
        for (m in textTag.findAll(t)) {
            val attrs = m.groupValues[1]
            val start = attr(attrs, "start")?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: continue
            val dur = attr(attrs, "dur")?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 2000L
            val body = clean(m.groupValues[2])
            if (body.isNotBlank()) out += CaptionCue(start, start + dur, body)
        }
        return out
    }

    private val tags = Regex("<[^>]*>")
    private val lineBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val numericEntity = Regex("&#(\\d+);")

    private fun clean(s: String): String =
        s.replace(lineBreak, "\n")
            .replace(tags, "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace(numericEntity) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
}

/**
 * Terjemahan teks (komentar). Kalau kunci Gemini terpasang saat build (rahasia GEMINI_API_KEY,
 * lihat app/build.gradle.kts) dipakai Gemini; kalau kosong atau gagal, jatuh ke endpoint publik
 * Google Translate. Hasil disimpan sementara supaya komentar yang sama tidak diterjemahkan dua kali.
 */
object Translator {
    private val cache = ConcurrentHashMap<String, String>()
    private val lineCache = ConcurrentHashMap<String, List<String>>()
    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"

    /** Bahasa tujuan: bahasa tampilan aplikasi (Indonesia atau Inggris). */
    val target: String get() = AppLanguage.code

    /** Terjemahan [text] ke [to], atau null kalau gagal (jaringan, ditolak). */
    suspend fun translate(text: String, to: String = target): String? = withContext(Dispatchers.IO) {
        val source = text.trim().take(1800)
        if (source.isEmpty()) return@withContext null
        val key = to + "\u0000" + source
        cache[key]?.let { return@withContext it }
        val prompt = "Translate this YouTube comment into natural, casual ${languageName(to)}. " +
            "Keep the emojis, @mentions, hashtags, line breaks and the tone as they are. " +
            "If it is already in ${languageName(to)}, return it unchanged. " +
            "Reply with ONLY the translation, with no quotes, notes or explanation.\n\nComment:\n$source"
        val result = (if (BuildConfig.GEMINI_API_KEY.isNotBlank()) geminiText(prompt, json = false) else null) ?: google(source, to)
        if (result != null) {
            if (cache.size > 300) cache.clear()
            cache[key] = result
        }
        result
    }

    /**
     * Menerjemahkan [lines] (baris subtitle) sekaligus; hasilnya selalu sebanyak [lines], atau null
     * kalau gagal. Gemini mengembalikan array JSON; tanpa kunci dipakai Google Translate per baris.
     */
    suspend fun translateLines(lines: List<String>, to: String): List<String>? = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()
        val flat = lines.map { it.replace('\n', ' ') }
        val key = to + "\u0000" + flat.joinToString("\u0001")
        lineCache[key]?.let { return@withContext it }
        var out: List<String>? = null
        if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
            val prompt = "Translate these subtitle lines into natural spoken ${languageName(to)}. " +
                "Return ONLY a JSON array of exactly ${flat.size} strings, in the same order, one per input line. " +
                "Do not merge or split lines. If a line is already in ${languageName(to)}, keep it.\n\n" +
                JSONArray(flat).toString()
            out = parseStringArray(geminiText(prompt, json = true), flat.size)
        }
        if (out == null) {
            val joined = google(flat.joinToString("\n"), to)
            val parts = joined?.split("\n")
            if (parts != null && parts.size == flat.size) out = parts.map { it.trim() }
        }
        if (out != null) {
            if (lineCache.size > 200) lineCache.clear()
            lineCache[key] = out
        }
        out
    }

    private fun languageName(code: String) = if (code == AppLanguage.ID) "Indonesian" else "English"

    private fun parseStringArray(text: String?, size: Int): List<String>? {
        if (text == null) return null
        return try {
            val arr = JSONArray(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            if (arr.length() != size) null else List(size) { arr.optString(it, "").trim() }
        } catch (e: Exception) {
            null
        }
    }

    /** Satu permintaan ke Gemini; teks jawabannya, atau null kalau gagal. */
    private fun geminiText(prompt: String, json: Boolean): String? {
        val config = org.json.JSONObject().put("temperature", 0.2)
        if (json) config.put("responseMimeType", "application/json")
        val payload = org.json.JSONObject()
            .put("contents", JSONArray().put(org.json.JSONObject().put("parts", JSONArray().put(org.json.JSONObject().put("text", prompt)))))
            .put("generationConfig", config)
        val request = okhttp3.Request.Builder()
            .url(GEMINI_URL)
            .header("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val body: String? = try {
            Net.client.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
        } catch (e: Exception) {
            null
        }
        if (body == null) return null
        return try {
            org.json.JSONObject(body)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text").trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun google(source: String, to: String): String? {
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("client", "gtx")
            ?.addQueryParameter("sl", "auto")
            ?.addQueryParameter("tl", to)
            ?.addQueryParameter("dt", "t")
            ?.addQueryParameter("q", source)
            ?.build()
            ?: return null
        val request = okhttp3.Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()
        val body: String? = try {
            Net.client.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
        } catch (e: Exception) {
            null
        }
        if (body == null) return null
        return try {
            val segments = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segments.length()) sb.append(segments.getJSONArray(i).optString(0, ""))
            sb.toString().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    // ── Perlu diterjemahkan? (tebakan di perangkat, tanpa jaringan) ──

    private val indonesianWords = setOf(
        "yang", "dan", "di", "ini", "itu", "tidak", "nggak", "ngga", "gak", "ga", "enggak", "aku", "saya", "kamu", "kau", "dia", "kita", "kami",
        "mereka", "nya", "banget", "bgt", "sih", "aja", "saja", "udah", "sudah", "belum", "dengan", "untuk", "dari", "ke", "ada", "adalah",
        "mau", "kalo", "kalau", "apa", "apaan", "siapa", "kenapa", "bisa", "jadi", "juga", "lagi", "nih", "tuh", "deh", "dong", "ya", "yaa",
        "kok", "bang", "kak", "bro", "wkwk", "wkwkwk", "haha", "lucu", "bagus", "keren", "mantap", "terima", "kasih", "makasih", "semoga",
        "sangat", "sekali", "banyak", "orang", "anak", "video", "kayak", "seperti", "karena", "soalnya", "tapi", "tetapi", "atau", "dalam",
        "pada", "akan", "harus", "boleh", "lebih", "paling", "masih", "pernah", "selalu", "sama", "semua", "setiap", "gimana", "bagaimana",
        "dulu", "sekarang", "hari", "waktu", "kapan", "dimana", "mana", "punya", "buat", "bikin", "liat", "lihat", "nonton", "dengar", "suka",
        "sayang", "cinta", "rindu", "kangen", "pagi", "malam", "siang", "sore", "indonesia", "bahasa", "pak", "bu", "mas", "mbak", "gue", "lu",
        "lo", "elu", "emang", "memang", "banget", "sampe", "sampai", "terus", "trus", "abis", "habis", "cuma", "cuman", "doang", "kek", "yuk",
        "ayo", "mari", "jangan", "usah", "perlu", "bisa", "tahu", "tau", "paham", "ngerti", "kasihan", "kasian", "sedih", "senang", "seru",
    )
    private val englishWords = setOf(
        "the", "is", "are", "was", "were", "and", "you", "your", "this", "that", "it", "its", "to", "of", "a", "an", "in", "on", "for", "with",
        "i", "my", "me", "we", "they", "he", "she", "so", "but", "just", "not", "be", "have", "has", "had", "do", "does", "did", "love", "like",
        "good", "great", "best", "nice", "wow", "omg", "lol", "very", "really", "can", "will", "would", "what", "who", "why", "how", "when",
        "from", "at", "as", "if", "or", "all", "no", "yes", "one", "more", "when", "who", "video", "thanks", "thank", "please", "watch",
        "funny", "amazing", "awesome", "episode", "still", "ever", "back", "now", "here", "there", "out", "up", "about", "than", "then",
    )

    /**
     * Apakah komentar ini layak diberi tombol Terjemahkan: bukan sekadar emoji/angka, dan tidak
     * tampak sudah berbahasa [to]. Tebakan dari aksara dan kata-kata umum, jadi komentar yang
     * sangat pendek atau campuran bisa meleset; tombol tampil kalau ragu.
     */
    fun needsTranslation(text: String, to: String = target): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 2) return false
        val nonLatin = letters.count { Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }
        if (nonLatin * 10 > letters.length * 3) return true
        val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        val id = words.count { it in indonesianWords }
        val en = words.count { it in englishWords }
        return if (to == AppLanguage.ID) !(id >= 1 && id >= en) else !(en >= 1 && en >= id)
    }
}
