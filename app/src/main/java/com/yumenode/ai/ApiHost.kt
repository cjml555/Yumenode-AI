// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.content.SharedPreferences
import android.widget.TextView

/**
 * Contrato mínimo que [ApiController] necesita de su host (MainActivity).
 *
 * Solo lo imprescindible: contexto (recursos, clipboard, permisos, lifecycle),
 * prefs, paleta, toast y las vistas del bloque API. La ApiController nunca
 * accede al motor, a la sesión de chat ni al resto de la Activity.
 */
interface ApiHost {
    /** Activity para recursos (getString), clipboard, permisos y servicios. */
    val activity: Activity

    /** Prefs compartidas («ponko»): puertos, keys y switches de la API. */
    fun chatPrefs(): SharedPreferences

    /** Paleta de colores actual. */
    val palette: YumenodeTheme

    /** Mostrar toast. */
    fun toast(msg: String)

    /** 是否已构建模型页（两边模型名那行存在与否）。 */
    val modelsInitialized: Boolean

    /** 对话侧 API 区块（未构建时为 null）。 */
    val chatApiBlock: ApiBlockView?

    /** 绘图侧 API 区块（未构建时为 null）。 */
    val drawApiBlock: ApiBlockView?

    /** 「对话模型：xxx」那一行。 */
    val chatApiModelTv: TextView

    /** 「绘图模型：xxx」那一行。 */
    val drawApiModelTv: TextView
}
