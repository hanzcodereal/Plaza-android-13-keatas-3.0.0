package com.decio.plaza

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Hasil terakhir pencatatan tontonan ke akun (riwayat YouTube). Ditampilkan di lembar Akun supaya
 * terlihat apakah tontonan benar-benar terkirim, dan kalau gagal, kenapa.
 */
object WatchDiagnostics {
    var last by mutableStateOf("Belum ada tontonan yang dicatat sejak aplikasi dibuka.")
        private set

    fun report(text: String) { last = text }
}
