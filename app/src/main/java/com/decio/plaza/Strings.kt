package com.decio.plaza

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Bahasa tampilan aplikasi: Indonesia atau Inggris. Kunci terjemahan adalah teks Inggrisnya. */
object AppLanguage {
    const val ID = "id"
    const val EN = "en"
    private var prefs: SharedPreferences? = null

    var code by mutableStateOf(EN)
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences("locale", Context.MODE_PRIVATE).also { prefs = it }
        code = p.getString("app_language", null) ?: if (java.util.Locale.getDefault().language == "in" || java.util.Locale.getDefault().language == "id") ID else EN
    }

    fun choose(value: String) {
        code = value
        prefs?.edit()?.putString("app_language", value)?.apply()
    }
}

/** Teks tampilan dalam bahasa yang dipilih; teks yang belum diterjemahkan tetap Inggris. */
fun tr(en: String): String = if (AppLanguage.code == AppLanguage.ID) Indonesian[en] ?: en else en

private val Indonesian: Map<String, String> = mapOf(
    "Home" to "Beranda",
    "Subscriptions" to "Langganan",
    "Search" to "Cari",
    "Library" to "Koleksi",
    "Your recommendations, minus the ads" to "Rekomendasimu, tanpa iklan",
    "Sign in with Google to see your own YouTube home feed, or just search." to "Masuk dengan Google untuk melihat beranda YouTube-mu, atau langsung cari saja.",
    "Sign in with Google" to "Masuk dengan Google",
    "Search instead" to "Cari saja",
    "Your subscriptions" to "Langgananmu",
    "Sign in with Google to see new videos from the channels you follow." to "Masuk dengan Google untuk melihat video baru dari channel yang kamu ikuti.",
    "Your library" to "Koleksimu",
    "Sign in with Google for your history, Watch later, liked videos and playlists." to "Masuk dengan Google untuk riwayat, Tonton nanti, video yang disukai, dan playlist.",
    "History" to "Riwayat",
    "Nothing watched yet." to "Belum ada yang ditonton.",
    "Playlists" to "Playlist",
    "Watch later" to "Tonton nanti",
    "Liked videos" to "Video yang disukai",
    "Nothing here yet" to "Belum ada apa-apa",
    "Videos you add to this playlist will show up here." to "Video yang kamu tambahkan ke playlist ini akan muncul di sini.",
    "Search Plaza" to "Cari di Plaza",
    "Clear" to "Hapus",
    "Remove" to "Hapus",
    "Search didn't load" to "Pencarian gagal dimuat",
    "Try again" to "Coba lagi",
    "Search without the ads" to "Cari tanpa iklan",
    "Results play straight away, with no ads and no sign-in." to "Hasil langsung diputar, tanpa iklan dan tanpa masuk akun.",
    "Nothing found" to "Tidak ditemukan",
    "Recent searches" to "Pencarian terakhir",
    "Clear all" to "Hapus semua",
    "Pause" to "Jeda",
    "Play" to "Putar",
    "Sign in to YouTube" to "Masuk ke YouTube",
    "Cancel" to "Batal",
    "Close" to "Tutup",
    "Sign in" to "Masuk",
    "Sign out" to "Keluar",
    "Signed in, but YouTube didn't answer. Check your connection and try again." to "Berhasil masuk, tetapi YouTube tidak merespons. Periksa koneksimu lalu coba lagi.",
    "Your YouTube, without the ads" to "YouTube-mu, tanpa iklan",
    "Sign in to get your recommendations, subscriptions, history and playlists. " to "Masuk untuk mendapatkan rekomendasi, langganan, riwayat, dan playlist. ",
    "Likes, subscribes, Watch later and watch history sync back to your account.\n\n" to "Suka, langganan, Tonton nanti, dan riwayat tontonan disinkronkan kembali ke akunmu.\n\n",
    "Google treats unofficial apps as against its terms and can suspend accounts that use them. " to "Google menganggap aplikasi tidak resmi melanggar ketentuannya dan dapat menangguhkan akun yang memakainya. ",
    "A secondary Google account keeps that risk away from your main one." to "Akun Google kedua menjauhkan risiko itu dari akun utamamu.",
    "Play next" to "Putar berikutnya",
    "Add to queue" to "Tambah ke antrean",
    "Save to Watch later" to "Simpan ke Tonton nanti",
    "Save to playlist" to "Simpan ke playlist",
    "Share" to "Bagikan",
    "Saved" to "Tersimpan",
    "Private" to "Pribadi",
    "Unlisted" to "Tidak publik",
    "Public" to "Publik",
    "New playlist" to "Playlist baru",
    "Playlist name" to "Nama playlist",
    "Create" to "Buat",
    "Queue" to "Antrean",
    "Remove from queue" to "Hapus dari antrean",
    "Nothing queued. Press and hold a video to add it." to "Antrean kosong. Tekan lama sebuah video untuk menambahkannya.",
    "Skip sponsor reads and other segments" to "Lewati sponsor dan segmen lainnya",
    "Show the Shorts tab" to "Tampilkan tab Shorts",
    "The Shorts tab is hidden and no Shorts are loaded" to "Tab Shorts disembunyikan dan Shorts tidak dimuat",
    "Appearance" to "Tampilan",
    "System" to "Sistem",
    "Light" to "Terang",
    "Dark" to "Gelap",
    "Selected" to "Dipilih",
    "Segments from SponsorBlock (sponsor.ajay.app), a community project. Data licensed CC BY-NC-SA 4.0." to "Segmen dari SponsorBlock (sponsor.ajay.app), proyek komunitas. Data berlisensi CC BY-NC-SA 4.0.",
    "Self-promotion" to "Promosi sendiri",
    "Like and subscribe reminder" to "Pengingat suka dan subscribe",
    "Outro and credits" to "Outro dan kredit",
    "Preview and recap" to "Pratinjau dan rekap",
    "Non-music part of a music video" to "Bagian non-musik dari video musik",
    "Filler tangent" to "Bagian selingan",
    "Skip" to "Lewati",
    "Ask" to "Tanya",
    "Show" to "Tampilkan",
    "Off" to "Mati",
    "Couldn't update your subscription." to "Tidak dapat memperbarui langgananmu.",
    "Couldn't load this channel" to "Channel ini tidak dapat dimuat",
    "Videos" to "Video",
    "Subscribed" to "Berlangganan",
    "Subscribe" to "Langganan",
    "Unsubscribe" to "Berhenti langganan",
    "Couldn't load this" to "Tidak dapat memuat ini",
    "Notifications" to "Notifikasi",
    "All" to "Semua",
    "Mentions" to "Sebutan",
    "Today" to "Hari ini",
    "This week" to "Minggu ini",
    "Earlier" to "Sebelumnya",
    "No notifications" to "Belum ada notifikasi",
    "New videos from your subscriptions show up here." to "Video baru dari langgananmu muncul di sini.",
    "Nobody has mentioned you yet." to "Belum ada yang menyebutmu.",
    "Search button" to "Tombol pencarian",
    "Downloads" to "Unduhan",
    "Download" to "Unduh",
    "Downloaded" to "Terunduh",
    "Retry" to "Ulangi",
    "Waiting…" to "Menunggu…",
    "Downloading…" to "Mengunduh…",
    "Downloading video" to "Mengunduh video",
    "Remove download" to "Hapus unduhan",
    "Delete download" to "Hapus unduhan",
    "Already downloaded. Remove it in Library › Downloads." to "Sudah diunduh. Hapus lewat Perpustakaan › Unduhan.",
    "Watch without internet" to "Tonton tanpa internet",
    "No downloads yet" to "Belum ada unduhan",
    "Tap Download on a video page, then watch it here without internet." to "Ketuk Unduh di halaman video, lalu tonton di sini tanpa internet.",
    "Failed, tap to retry" to "Gagal, ketuk untuk mengulang",
    "Search is an icon at the top of Home" to "Pencarian berupa ikon di atas Beranda",
    "Search is a tab in the navigation" to "Pencarian berupa tab di navigasi",
    "Full-screen Shorts" to "Shorts layar penuh",
    "Video fills the whole screen" to "Video memenuhi seluruh layar",
    "Video sits in a card, the navigation stays visible" to "Video dalam kartu, navigasi tetap terlihat",
    "Navigation" to "Navigasi",
    "Top" to "Atas",
    "YouTube has no recommendations for this account yet. Watch a few videos, then try again." to "YouTube belum punya rekomendasi untuk akun ini. Tonton beberapa video, lalu coba lagi.",
    "This video is age-restricted and needs a YouTube account to watch." to "Video ini dibatasi usia dan butuh akun YouTube untuk ditonton.",
    "This video isn't available in your country." to "Video ini tidak tersedia di negaramu.",
    "YouTube is asking to confirm you're not a bot. Try again in a few minutes." to "YouTube meminta konfirmasi bahwa kamu bukan bot. Coba lagi beberapa menit lagi.",
    "This video is private." to "Video ini privat.",
    "This is a members-only video. Plaza can't play members-only videos yet." to "Ini video khusus anggota. Plaza belum bisa memutar video khusus anggota.",
    "This video isn't available." to "Video ini tidak tersedia.",
    "YouTube is rate-limiting requests right now. Try again in a minute." to "YouTube sedang membatasi permintaan. Coba lagi satu menit lagi.",
    "Couldn't reach YouTube. Check your connection." to "Tidak dapat menghubungi YouTube. Periksa koneksimu.",
    "Something went wrong loading this. Try again." to "Terjadi kesalahan saat memuat ini. Coba lagi.",
    "More controls" to "Kontrol lainnya",
    "Audio only is on" to "Hanya audio aktif",
    "Audio only" to "Hanya audio",
    "Chapters" to "Bab",
    "Auto" to "Otomatis",
    "Speed" to "Kecepatan",
    "Quality" to "Kualitas",
    "Fullscreen" to "Layar penuh",
    "Next" to "Berikutnya",
    "Data saver" to "Hemat data",
    "Picture-in-picture is turned off for Plaza in Settings." to "Picture-in-picture dimatikan untuk Plaza di Pengaturan.",
    "Exit fullscreen" to "Keluar layar penuh",
    "Undo" to "Urungkan",
    "More videos" to "Video lainnya",
    "Like" to "Suka",
    "Dislike" to "Tidak suka",
    "Save" to "Simpan",
    "Playlist" to "Playlist",
    "Couldn't like that comment." to "Tidak dapat menyukai komentar itu.",
    "You" to "Kamu",
    "Couldn't post your comment." to "Tidak dapat mengirim komentarmu.",
    "Comments" to "Komentar",
    "Comments are turned off for this video." to "Komentar dinonaktifkan untuk video ini.",
    "No comments yet. Add the first one." to "Belum ada komentar. Jadilah yang pertama.",
    "No comments yet." to "Belum ada komentar.",
    "Close comments" to "Tutup komentar",
    "Sign in to comment." to "Masuk untuk berkomentar.",
    "Cancel reply" to "Batalkan balasan",
    "Post" to "Kirim",
    "Pinned" to "Disematkan",
    "Unlike" to "Batal suka",
    "Hearted by creator" to "Disukai kreator",
    "Reply" to "Balas",
    "Hide replies" to "Sembunyikan balasan",
    "No videos yet" to "Belum ada video",
    "Delete comment?" to "Hapus komentar?",
    "This can't be undone." to "Tindakan ini tidak bisa dibatalkan.",
    "Deleting…" to "Menghapus…",
    "Couldn't delete that comment." to "Komentar tidak bisa dihapus.",
    "Show more replies" to "Tampilkan balasan lainnya",
    "reply" to "balasan",
    "replies" to "balasan",
    "Add a reply…" to "Tambahkan balasan…",
    "Add a comment…" to "Tambahkan komentar…",
    "Every video" to "Setiap video",
    "This video only" to "Video ini saja",
    "Remember for every video" to "Ingat untuk setiap video",
    "YouTube didn't offer any Shorts right now." to "YouTube tidak menawarkan Shorts saat ini.",
    "This Short can't play right now." to "Short ini tidak dapat diputar sekarang.",
    "Sign in to like Shorts." to "Masuk untuk menyukai Shorts.",
    "Sign in to subscribe." to "Masuk untuk berlangganan.",
    "This Short can't play" to "Short ini tidak dapat diputar",
    "Back" to "Kembali",
    "Go to channel" to "Buka channel",
    "This channel can't be opened." to "Channel ini tidak dapat dibuka.",
    "Update" to "Perbarui",
    "Installing" to "Memasang",
    "Retry" to "Ulangi",
    "Language" to "Bahasa",
    "Content" to "Konten",
    "About" to "Tentang",
    "Language used in Plaza's screens" to "Bahasa yang dipakai di tampilan Plaza",
    "Content comes from YouTube. Choose its language and country." to "Konten berasal dari YouTube. Pilih bahasa dan negaranya.",
    "Content language" to "Bahasa konten",
    "Content country" to "Negara konten",
    "Default" to "Bawaan",
    "Applies to lists loaded next." to "Berlaku untuk daftar yang dimuat berikutnya.",
    "United States" to "Amerika Serikat",
    "Singapore" to "Singapura",
    "United Kingdom" to "Inggris",
    "Japan" to "Jepang",
    "Brazil" to "Brasil",
    "Spain" to "Spanyol",
    "India" to "India",
    "Australia" to "Australia",
    "Canada" to "Kanada",
    "Germany" to "Jerman",
    "France" to "Prancis",
    "Italy" to "Italia",
    "Netherlands" to "Belanda",
    "South Korea" to "Korea Selatan",
    "Thailand" to "Thailand",
    "Vietnam" to "Vietnam",
    "Philippines" to "Filipina",
    "Mexico" to "Meksiko",
    "Argentina" to "Argentina",
    "Turkey" to "Turki",
    "Saudi Arabia" to "Arab Saudi",
    "United Arab Emirates" to "Uni Emirat Arab",
    "Egypt" to "Mesir",
    "Nigeria" to "Nigeria",
    "South Africa" to "Afrika Selatan",
    "Pakistan" to "Pakistan",
    "Bangladesh" to "Bangladesh",
    "Taiwan" to "Taiwan",
    "Hong Kong" to "Hong Kong",
    "New Zealand" to "Selandia Baru",
    "Russia" to "Rusia",
    "Poland" to "Polandia",
    "Sweden" to "Swedia",
    "Switzerland" to "Swiss",
    "Choose your region" to "Pilih wilayahmu",
    "Videos and trends follow this region. You can change it later in Content settings." to "Video dan tren mengikuti wilayah ini. Kamu bisa mengubahnya nanti di pengaturan Konten.",
    "Use my device's region" to "Ikuti wilayah perangkat",
    "More options" to "Opsi lainnya",
    "watching" to "menonton",
    "videos" to "video",
    "Posts" to "Postingan",
    "No Shorts yet" to "Belum ada Shorts",
    "No live streams" to "Belum ada siaran langsung",
    "No playlists yet" to "Belum ada playlist",
    "No posts yet" to "Belum ada postingan",
    "Live now" to "Sedang live",
    "New videos" to "Video baru",
    "App name" to "Nama aplikasi",
    "Version" to "Versi",
    "Support the developer" to "Dukung developer",
    "Subtitles" to "Subtitle",
    "views" to "x ditonton",
    "likes" to "suka",
    "subscribers" to "subscriber",
    "less" to "lebih sedikit",
    "…more" to "…selengkapnya",
    "Autoplay" to "Putar otomatis",
    "Play a related video when this one ends" to "Putar video terkait setelah video ini selesai",
    "Playback stops when the video ends" to "Pemutaran berhenti saat video selesai",
    "New-video alerts on" to "Notifikasi video baru dinyalakan",
    "New-video alerts off" to "Notifikasi video baru dimatikan",
    "New-video alerts" to "Notifikasi video baru",
    "Swipe up for more videos" to "Geser ke atas untuk video lainnya",
    "This video has no subtitles." to "Video ini tidak punya subtitle.",
    "Auto-generated" to "Dibuat otomatis",
    "Auto-translate" to "Terjemahan otomatis",
    "Playback speed" to "Kecepatan putar",
    "Normal" to "Normal",
    "Translate" to "Terjemahkan",
    "Translating…" to "Menerjemahkan…",
    "See original" to "Lihat asli",
    "Translation failed, try again" to "Gagal menerjemahkan, coba lagi",
)

private val agoPattern = Regex("(\\d+|an?)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago", RegexOption.IGNORE_CASE)
private val datePattern = Regex("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s+(\\d{1,2}),\\s+(\\d{4})")

private fun agoUnit(unit: String) = when (unit.lowercase()) {
    "second" -> "detik"
    "minute" -> "menit"
    "hour" -> "jam"
    "day" -> "hari"
    "week" -> "minggu"
    "month" -> "bulan"
    else -> "tahun"
}

private fun monthName(abbr: String) = when (abbr.lowercase()) {
    "jan" -> "Jan"; "feb" -> "Feb"; "mar" -> "Mar"; "apr" -> "Apr"; "may" -> "Mei"; "jun" -> "Jun"
    "jul" -> "Jul"; "aug" -> "Agu"; "sep" -> "Sep"; "oct" -> "Okt"; "nov" -> "Nov"; else -> "Des"
}

/**
 * Waktu unggah dari YouTube ("5 years ago", "Streamed 2 weeks ago", "Oct 5, 2025") dalam bahasa
 * Indonesia saat itu yang dipilih ("5 tahun lalu", "Disiarkan 2 minggu lalu", "5 Okt 2025");
 * bahasa Inggris dibiarkan apa adanya.
 */
fun localizeAgo(text: String): String {
    if (AppLanguage.code != AppLanguage.ID) return text
    return agoPattern.replace(text) { m ->
        val n = if (m.groupValues[1].lowercase().startsWith("a")) "1" else m.groupValues[1]
        "$n ${agoUnit(m.groupValues[2])} lalu"
    }
        .let { dateText -> datePattern.replace(dateText) { m -> "${m.groupValues[2]} ${monthName(m.groupValues[1])} ${m.groupValues[3]}" } }
        .replace("Streamed live", "Disiarkan langsung", ignoreCase = true)
        .replace("Streamed", "Disiarkan", ignoreCase = true)
        .replace("Premiered", "Tayang perdana", ignoreCase = true)
        .replace("Updated", "Diperbarui", ignoreCase = true)
        .replace("yesterday", "kemarin", ignoreCase = true)
        .replace("today", "hari ini", ignoreCase = true)
        .replace("just now", "baru saja", ignoreCase = true)
        .replace("(edited)", "(diedit)", ignoreCase = true)
}
