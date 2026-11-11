// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity

/**
 * Contrato mínimo que [SettingsPage] necesita de su host (MainActivity).
 *
 * Solo lo imprescindible para la página de ajustes: Activity (recursos,
 * diálogos, dp/rounded), paleta, tema, modo, busy, accesorios, persistencia,
 * versiones, benchmark, idioma. El resto de la Activity queda fuera.
 */
interface SettingsHost {
    /** Activity para recursos (getString), AlertDialog y los helpers dp/rounded. */
    val activity: Activity

    /** Índice de acento actual (0..N-1). */
    val accentIndex: Int

    /** Aplicar nuevo índice de acento. */
    fun applyAccent(index: Int)

    /** Paleta de colores actual. */
    val palette: YumenodeTheme

    /** Widgets de UI auxiliares (botones, switches, etc.). */
    val ui: UiWidgets

    /** Modo de tema actual (0=Claro, 1=Sistema, 2=Oscuro, 3=OLED). */
    val themeMode: Int

    /** Si hay generación en curso (prohibir cambios de tema mientras se dibuja). */
    val drawBusy: Boolean

    /** Aplicar nuevo modo de tema. */
    fun applyThemeMode(mode: Int)

    /** Mostrar toast. */
    fun toast(msg: String)

    /** Versión de la aplicación. */
    fun appVersion(): String

    /** Fecha/hora de instalación. */
    fun installedAt(): String

    /** Leer preferencia booleana. */
    fun prefBool(key: String, def: Boolean): Boolean

    /** Escribir preferencia booleana. */
    fun prefPutBool(key: String, value: Boolean)

    /** Leer preferencia string. */
    fun prefStr(key: String, def: String): String

    /** Escribir preferencia string. */
    fun prefPutStr(key: String, value: String)

    /** Ejecutar benchmark y reportar resultado. */
    fun shareBenchmark(endpoint: String, onResult: (String) -> Unit)

    /** Ejecutar benchmark y notificar progreso. */
    fun runBenchmark(onUpdate: (String) -> Unit)

    /** Establecer idioma de la aplicación. */
    fun setAppLanguage(tag: String?)

    /** Obtener idioma actual de la aplicación. */
    fun currentAppLanguage(): String?
}