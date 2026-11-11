package com.decio.plaza

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Ikon aplikasi di layar utama mengikuti tema pilihan: tiap tema punya alias peluncur sendiri dengan
 * ikon berwarna tema itu (warna utama untuk logo, warna kontainer untuk latar). Hanya satu alias
 * yang aktif; yang lain dimatikan. Mengganti tema mengganti ikon (launcher bisa butuh beberapa detik).
 */
object AppIcon {
    private val keys = listOf("Youtube", "CreamRose", "Erica", "LilacAir", "Jai", "Pumpkin", "Moonstone", "Iris", "Harbor")
    private const val DEFAULT_KEY = "Youtube"

    private fun keyFor(seed: String?): String {
        if (seed == null) return DEFAULT_KEY
        val hex = normalizedThemeSeed(seed)
        val name = PLAZA_THEMES.firstOrNull { it.hex.equals(hex, ignoreCase = true) }?.name ?: return DEFAULT_KEY
        return name.replace(" ", "").takeIf { it in keys } ?: DEFAULT_KEY
    }

    private fun component(context: Context, key: String) = ComponentName(context.packageName, "com.decio.plaza.Icon$key")

    private fun isEnabled(pm: PackageManager, cn: ComponentName, key: String): Boolean {
        val state = pm.getComponentEnabledSetting(cn)
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && key == DEFAULT_KEY)
    }

    /** Pasang ikon untuk tema [seed]; hanya mengubah alias yang memang berbeda. Aman dipanggil berulang. */
    fun apply(context: Context, seed: String?) {
        runCatching {
            val pm = context.applicationContext.packageManager
            val target = keyFor(seed)
            // Aktifkan yang baru dulu, baru matikan yang lain, supaya aplikasi tidak pernah tanpa ikon.
            val targetCn = component(context, target)
            if (!isEnabled(pm, targetCn, target)) {
                pm.setComponentEnabledSetting(targetCn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
            keys.filter { it != target }.forEach { key ->
                val cn = component(context, key)
                if (isEnabled(pm, cn, key)) {
                    pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            }
        }
    }
}
