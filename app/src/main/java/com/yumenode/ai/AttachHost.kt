// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.view.View

/**
 * Contrato mínimo que [AttachController] necesita de su host (MainActivity).
 *
 * Solo lo imprescindible para el bloque de adjuntos/archivos: Activity
 * (recursos, permisos, lifecycle, cámara), toast, paleta, la barra de entrada
 * (ancla del drawer), el [AttachmentHandler], el scope de corrutinas y si hay
 * modelo cargado. El resto de la Activity queda fuera.
 */
interface AttachHost {
    /** Activity para recursos (getString), permisos, cámara y MediaStore. */
    val activity: Activity

    /** Paleta de colores actual. */
    val palette: YumenodeTheme

    /** Mostrar toast. */
    fun toast(msg: String)

    /** Barra de entrada: ancla visual del drawer de adjuntar. */
    val inputBar: View

    /** Manejo de adjuntos pendientes (picks, import, previews). */
    val attachmentHandler: AttachmentHandler

    /** Scope de corrutinas de la Activity. */
    val scope: kotlinx.coroutines.CoroutineScope

    /** Hay modelo conversacional cargado (engine o llamaModel). */
    val hasModel: Boolean
}
