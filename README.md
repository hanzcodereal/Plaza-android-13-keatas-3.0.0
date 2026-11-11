# Plaza

Pemutar YouTube tanpa iklan untuk Android, dibuat dengan Material 3 Expressive.

Tanpa iklan. Bisa dipakai tanpa masuk akun, atau masuk dengan Google untuk mendapat rekomendasi,
langganan, riwayat, dan playlist milikmu sendiri; suka, langganan, dan riwayat tontonan ikut
tersinkron ke akun. Segmen sponsor dilewati otomatis dengan [SponsorBlock](https://sponsor.ajay.app).

## Unduh

**Plaza.apk**: rilis terbaru dari repositori milikmu sendiri (atur `UPDATE_REPO` di `Updater.kt`).
Buka di HP, lalu izinkan pemasangan dari browser. Pembaruan terpasang di atas versi sebelumnya.

Menerbitkan build baru (tautan tetap sama): naikkan `versionName` dan `versionCode` di
`app/build.gradle.kts`, lalu jalankan `scripts/publish-apk.sh`.

## Fitur

**Menonton**
- **Cari** di YouTube tanpa perlu masuk akun, dengan saran pencarian dan riwayat pencarian.
- **Pemutaran** hingga 1080p; video dan audio diambil sebagai aliran terpisah lewat manifes DASH
  buatan sendiri (menghindari pembatasan kecepatan YouTube).
- **Siaran langsung (Live)** bisa ditonton, lengkap dengan lencana LIVE dan obrolan langsung.
- **Kontrol pemutar:** maju/mundur 10 detik, tombol putar/jeda yang berubah bentuk, penggeser
  durasi, kontrol yang tersembunyi otomatis, dan pratinjau bingkai saat menggeser.
- **Mini-player** yang menyatu dengan pemutar: video tumbuh dari gambar mini. Geser pemutar ke
  bawah untuk memperkecil, atau geser mini-player ke atas untuk memperbesar.
- **Layar penuh lanskap** dengan memutar HP atau menekan tombol layar penuh; geser kiri untuk
  kecerahan dan kanan untuk volume.
- **Audio saja** dan **suara latar** dengan notifikasi media (layar kunci juga bisa dikontrol).
- **Kecepatan putar**, **pilihan kualitas** (Otomatis atau tinggi apa pun yang ditawarkan YouTube,
  diingat), dan **subtitle (CC)**.
- **Lanjutkan menonton:** posisi terakhir diingat dan tampil sebagai garis tipis di gambar mini;
  sesi yang sedang dibuka dipulihkan setelah aplikasi tertutup.
- **Antrean:** tekan lama video untuk Putar berikutnya atau Tambah ke antrean.
- **Komentar** dengan balasan, suka komentar, dan menulis komentar atau balasan (saat masuk akun).
- **SponsorBlock** per kategori: Lewati (dengan pudar suara dan Urungkan), Tanya (tombol Lewati),
  Tampilkan di penggeser, atau Mati.

**Shorts**
- Umpan vertikal layar penuh (milikmu saat masuk akun, umum jika tidak). Geser antar Short, ketuk
  untuk jeda; bilah tab berubah jadi bilah kaca buram dengan Suka, Komentar, Bagikan, dan channel
  (ketuk untuk berlangganan atau membuka channel).
- 10 Short berikutnya yang belum dilihat disiapkan di perangkat, jadi langsung main.
- **Dua tampilan:** layar penuh, atau kartu (kotak persegi selebar layar di bawah jam dan ikon
  status, video utuh dengan latar buram, tanpa terpotong di sisi).
- Shorts juga tampil sebagai rak di Beranda, dan bisa dimatikan di pengaturan.

**Akun dan koleksi**
- **Masuk dengan Google (opsional):** rekomendasi Beranda, umpan Langganan, riwayat, Tonton nanti,
  video yang disukai, dan playlist. Suka, langganan, Simpan, dan riwayat tontonan tersinkron ke akun.
  Sesi disimpan terenkripsi dengan kunci Android Keystore. Catatan: Google bisa menangguhkan akun
  yang dipakai dengan klien tidak resmi; akun kedua lebih aman daripada akun utama.
- **Koleksi:** Riwayat, Playlist, Tonton nanti, dan Video yang disukai. Tombol **Unduhan** ada di
  bagian Playlist, tepat di atas Tonton nanti dan Video yang disukai.
- **Unduhan:** simpan video untuk ditonton tanpa internet; Unduhan selalu bisa dibuka, juga saat
  belum masuk akun atau sedang offline.
- **Halaman channel** ala YouTube: @username, jumlah pelanggan, jumlah video, dan tab Video,
  Shorts, Live, Playlist, serta Postingan komunitas.
- **Notifikasi tanpa membuka aplikasi:** channel yang kamu langgan memberi notifikasi video baru,
  siaran langsung, dan postingan, lengkap dengan gambar mini. Lonceng per channel juga tersedia.
- **Halaman Notifikasi:** gabungan kotak masuk YouTube dan semua kabar terbaru dari channel yang
  dilanggan (video, live, postingan), dikelompokkan Hari ini, Minggu ini, dan Lebih lama.
- Penanda titik pada konten baru, dan tarik layar untuk menyegarkan.

**Tampilan dan pengaturan**
- **Warna mengikuti wallpaper** (Material You), pilihan tema warna, mode terang/gelap, kapsul
  navigasi melayang, dan tanpa riak sentuh.
- **Ikon aplikasi** di layar utama mengikuti tema yang dipilih (termasuk ikon gaya YouTube).
- **Dua bahasa tampilan:** Indonesia dan Inggris; tanggal video mengikuti bahasa aplikasi.
- **Wilayah dan bahasa konten:** pilihan negara saat pertama dibuka, bisa diubah di pengaturan.
- **Tentang:** nama aplikasi, versi, developer, dan tautan dukungan.
- **Pembaruan sendiri:** aplikasi mengecek rilis terbaru dan memasangnya.
- Dukungan layar terbagi (split screen), jendela gelembung, dan tata letak kanan-ke-kiri (RTL).

## Peta jalan

1. ✅ Kerangka, pencarian, pemutaran, kontrol di atas video, mini-player, audio latar
2. ✅ Transisi mini-player ke pemutar; geser bawah untuk memperkecil, geser atas untuk memperbesar
3. ✅ Komentar, pilihan kualitas, layar penuh lanskap
4. ✅ Masuk dengan Google dengan sinkronisasi penuh
5. ✅ Antrean, menulis komentar, SponsorBlock, bilah tab ala Castii
6. ✅ Tab Shorts
7. ✅ Unduhan, subtitle, notifikasi latar belakang, halaman channel, siaran langsung

## Membangun

Kebutuhan: Android SDK (compileSdk 36) dan JDK 17+. JDK bawaan Android Studio bisa dipakai:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # diperkecil dengan R8, ditandatangani dengan kunci debug
```

Nilai kelancaran di build **release**; Compose di build debug berjalan tanpa optimasi.

## Struktur proyek

```
app/src/main/java/com/decio/plaza/
  YouTube.kt          pencarian + detail video (NewPipeExtractor), cache dan pemilihan aliran
  PlaybackService.kt  layanan sesi Media3, sumber media DASH/penggabungan
  PlayerState.kt      keadaan pemutaran sisi UI di atas MediaController
  PlayerUi.kt         layar tonton, kontrol, penggeser, mini-player
  MainActivity.kt     kerangka aplikasi: tab, kapsul navigasi, lembar pemutar
  Screens.kt          Beranda, Langganan, Cari, Koleksi
  Shorts.kt           tab Shorts: umpan, kumpulan pemutar yang dimuat lebih dulu, bilah kaca
  Notifications.kt    halaman Notifikasi dan lencana lonceng
  NewVideoAlerts.kt   notifikasi latar belakang (WorkManager)
  Downloads.kt        Unduhan
  UiKit.kt            komponen bersama dan bentuk ekspresif
  Theme.kt, Motion.kt, Net.kt
docs/
  design/             mockup yang disetujui (buka mockup.html di browser) dan iterasinya
CLAUDE.md             keputusan produk dan aturan kerja untuk pengembangan dibantu AI
```

## Kredit

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor): data YouTube, GPL-3.0
- [AndroidX Media3](https://github.com/androidx/media): pemutaran
- [SponsorBlock](https://sponsor.ajay.app): segmen sponsor, CC BY-NC-SA 4.0
- [essopsp](https://github.com/essopsp): ide tab Shorts
- [@o0ir4](https://www.threads.com/@o0ir4): melaporkan tata letak di HP berbahasa Arab (kanan-ke-kiri)
- [nsk-47](https://github.com/nsk-47): menemukan celah di bawah Tampilan pada ukuran huruf besar

## Lisensi

GPL-3.0. Lihat [LICENSE](LICENSE). Wajib karena NewPipeExtractor berlisensi GPL-3.0.

Plaza tidak berafiliasi dengan atau didukung oleh YouTube maupun Google. Ini proyek pribadi untuk
dipasang manual (sideload), tidak didistribusikan lewat Play Store.

## Tentang

Plaza 4.0.0, oleh Hanz Dev Official.

- Dukungan: https://saweria.co/hanzreally
- Email: hanzzcodee@gmail.com
