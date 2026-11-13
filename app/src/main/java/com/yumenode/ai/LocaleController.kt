// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.LocaleManager
import android.content.res.Configuration
import android.os.Build

/**
 * Controlador de idioma y tema: gestiona cambio de idioma y modo de tema.
 *
 * Extraída de MainActivity: currentAppLanguage, setAppLanguage, applyThemeMode, applyAccent.
 */
class LocaleController(private val host: LocaleHost) {

    /** Idioma actual de la aplicación. */
    fun currentAppLanguage(): String? =
        if (Build.VERSION.SDK_INT >= 33) currentAppLocale33(host.activity.getSystemService(LocaleManager::class.java))
        else host.chatPrefs().getString(KEY_APP_LANG, "")?.takeIf { it.isNotBlank() }

    /** Cambiar el idioma de la aplicación. */
    fun setAppLanguage(tag: String?) {
        val t = tag?.trim().orEmpty()
        host.chatPrefs().edit().putString(KEY_APP_LANG, t).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            applyAppLocale33(host.activity.getSystemService(LocaleManager::class.java), t)
        } else {
            val cfg = Configuration(host.activity.resources.configuration)
            cfg.setLocale(if (t.isEmpty()) java.util.Locale.getDefault()
                else java.util.Locale.forLanguageTag(t))
            @Suppress("DEPRECATION")
            host.activity.resources.updateConfiguration(cfg, host.activity.resources.displayMetrics)
            host.applyThemeInPlace()
        }
    }

    /** Aplicar modo de tema (claro/oscuro/sistema). */
    fun applyThemeMode(mode: Int) {
        host.themeManager.setThemeMode(host.activity, mode)
        host.applyThemeInPlace()
    }

    /** Aplicar índice de acento. */
    fun applyAccent(index: Int) {
        host.themeManager.setAccent(host.activity, index)
        host.applyThemeInPlace()
    }
}