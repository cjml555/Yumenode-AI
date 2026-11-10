// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * Extracción literal (sin cambios de comportamiento) de tema/locale desde MainActivity:
 * color de barras de sistema, luminancia y locale por app (API 33+).
 *
 * [applyThemeInPlace] queda en la Activity: persiste restoreTab y llama a buildUi (shell).
 * installCrashHandler/takeCrashLog quedan en la Activity: son delegados de una línea
 * a [CrashHandler], que ya existe como archivo propio.
 */

/** Luminancia percibida (0..1) de un color ARGB, usada para decidir iconos claros/oscuros. */
internal fun barLuminance(c: Int): Float {
    val r = (c shr 16) and 0xFF
    val g = (c shr 8) and 0xFF
    val b = c and 0xFF
    return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
}

/** 状态栏 / 导航栏配色（跟随主题） */
internal fun applySystemBarColors(window: Window, pal: YumenodeTheme) {
    window.statusBarColor = pal.statusBar
    window.navigationBarColor = pal.navBar

    // Android 15+ (targetSdk 35+) ignora los colores de barra y dibuja el
    // contenido debajo: el contraste depende del fondo real (p.bg), no de
    // p.statusBar/p.navBar. Por eso se calcula por luminancia.
    val edgeToEdge = Build.VERSION.SDK_INT >= 35
    val lightStatus = barLuminance(if (edgeToEdge) pal.bg else pal.statusBar) > 0.55f
    val lightNav = barLuminance(if (edgeToEdge) pal.bg else pal.navBar) > 0.55f

    if (Build.VERSION.SDK_INT >= 30) {
        val ic = window.insetsController ?: return
        val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        var appearance = 0
        if (lightStatus) appearance = appearance or android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        if (lightNav) appearance = appearance or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        ic.setSystemBarsAppearance(appearance, mask)
    } else {
        var flags = window.decorView.systemUiVisibility
        flags = if (lightStatus) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        flags = if (lightNav) flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        window.decorView.systemUiVisibility = flags
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun currentAppLocale33(lm: LocaleManager?): String? {
    val ls = lm?.applicationLocales
    return if (ls == null || ls.isEmpty) null else ls[0].toLanguageTag().substringBefore('-')
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun applyAppLocale33(lm: LocaleManager?, tag: String) {
    lm?.applicationLocales =
        if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
        else LocaleList.forLanguageTags(tag)
}
