// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.graphics.Bitmap
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.yumenode.ai.draw.DrawPage
import com.yumenode.ai.draw.GgufProbe
import kotlinx.coroutines.CoroutineScope

/**
 * Contrato mínimo que [DrawModelsController] necesita de su host (MainActivity).
 *
 * Solo lo imprescindible para slots de dibujo, LoRA/TAESD, tagger y modelos
 * guardados: Activity (recursos, intents, diálogos), paleta, toast, status,
 * scope, la página de dibujo y las vistas/estado del bloque. El resto de la
 * Activity queda fuera.
 */
interface DrawModelsHost {
    /** Activity para recursos (getString), intents, AlertDialog y helpers dp/rounded/matchWrap. */
    val activity: Activity

    /** Paleta de colores actual. */
    val palette: YumenodeTheme

    /** Mostrar toast. */
    fun toast(msg: String)

    /** Línea de estado global. */
    fun setStatus(text: String, color: Int)

    /** Scope de corrutinas de la Activity. */
    val scope: CoroutineScope

    /** Widgets con estilo (hintText, smallButton). */
    val ui: UiWidgets

    /** Página de dibujo (modelos, LoRA, tagger). */
    val drawPage: DrawPage?

    /** Página de dibujo: método de adjuntos del host (queryDisplayName). */
    val attachmentHandler: AttachmentHandler

    /** Motor de UI del tagger (toggle de carga). */
    val taggerUi: TaggerUi

    // ---- vistas del bloque ----
    val drawSlotsContainer: LinearLayout?
    val drawSlotsHeader: TextView?
    val drawModelStatus: TextView?
    val loraStatusTv: TextView?
    val loraBox: LinearLayout?
    val taggerStatusTv: TextView?
    val taggerBox: LinearLayout?
    val taggerLoadBtn: TextView?
    val savedContainer: LinearLayout
    val modelInfoText: TextView
    val progressBar: ProgressBar

    // ---- estado del bloque ----
    var pendingSlotKey: String?
    var drawSlotsExpanded: Boolean
    var drawSetId: String?
    var modelPath: String?
    var isBusy: Boolean
    val ggufKindCache: MutableMap<String, GgufProbe.Kind>

    /** Hay modelo conversacional cargado (engine o llamaModel). */
    val hasModel: Boolean

    /** Leer imagen de chat guardada en filesDir/chatimg */
    fun readChatImage(name: String): Bitmap?

    // ---- acciones que quedan en la Activity ----
    fun applyBusy(b: Boolean)
    fun setDrawBusy(b: Boolean)
    fun loadChatParamsIntoUi()
    fun refreshDrawModels()
    fun refreshDrawToggle()
}
