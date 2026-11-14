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

    /** Whether the Models page has been built (presence of the model-name row on both sides). */
    val modelsInitialized: Boolean

    /** Chat-side API block (null while not built). */
    val chatApiBlock: ApiBlockView?

    /** Draw-side API block (null while not built). */
    val drawApiBlock: ApiBlockView?

    /** The "chat model: xxx" row. */
    val chatApiModelTv: TextView

    /** The "draw model: xxx" row. */
    val drawApiModelTv: TextView
}
