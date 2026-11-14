// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.yumenode.ai.draw.DrawPage
import kotlinx.coroutines.CoroutineScope
import java.io.File
import net.ladenthin.llama.LlamaModel

/**
 * Contrato mínimo que [ModelLoadController] necesita de su host (MainActivity).
 *
 * Solo lo imprescindible para cargar/descargar modelos: estado del motor,
 * preferencias, vistas del botón de carga, scope, callbacks de UI.
 */
interface ModelLoadHost {
    /** Activity para recursos (getString), AlertDialog y helpers dp/rounded. */
    val activity: Activity
    
    /** Shared preferences holder (for prefs like KEY_GPU_PRIORITY). */
    fun prefBool(key: String, def: Boolean): Boolean = activity.getSharedPreferences("model_load_prefs", Activity.MODE_PRIVATE).getBoolean(key, def)

    /** CoroutineScope para operaciones asíncronas. */
    val scope: CoroutineScope

    /** Palette actual (para helpers C_* y rounded). */
    val palette: YumenodeTheme

    /** Motor LiteRT cargado (null = no cargado). */
    var engine: Engine?

    /** Modelo GGUF cargado (null = no cargado). */
    var llamaModel: LlamaModel?

    /** Conversación LiteRT activa. */
    var conversation: Conversation?

    /** thinking con el que se creó la conversación actual (null = sin conversación). */
    var convThinking: Boolean?

    /** Si la conversación se creó con capacidad de dibujo inyectada. */
    var convDrawCapable: Boolean

    /** Modelo actual admite entrada visual. */
    var visionOk: Boolean

    /** thinking con el que se cargó el modelo actual (heurístico). */
    var modelSupportsThinking: Boolean

    /** Índice de retorno de herramienta tag (0=primero). */
    var tagReturnIdx: Int

    /** Parámetros de chat (ctx, maxOut, temp, …). */
    val chatParams: ChatParams

    /** Ruta del modelo seleccionado (null = ninguno). */
    var modelPath: String?

    /** Ruta al mmproj seleccionado (para gguf multimodal). */
    var mmprojPath: String?

    /** Checkbox de thinking en la barra de entrada. */
    val thinkCheck: CheckBox

    /** Spinner de selección de backend (CPU/GPU). */
    val backendSpinner: Spinner

    /** Botón de cargar/desacargar modelo. */
    val loadButton: TextView

    /** Barra de progreso de carga. */
    val progressBar: ProgressBar

    /** Página de dibujo (para llmLoaded). */
    val drawPage: DrawPage?

    /** Construcción de parámetros de inferencia (configFor). */
    val inferenceBuilder: InferenceParamsBuilder

    /** Sesión actual para reconstruir conversación. */
    val currentSession: ChatSession

    /** Hay modelo conversacional y de dibujo a la vez. */
    val drawCapable: Boolean

    /** Caché de aplicación (para cacheDir). */
    val appCacheDir: File

    /** Preferencias de chat (SharedPreferences "ponko"). */
    fun chatPrefs(): android.content.SharedPreferences

    /** Mostrar toast. */
    fun toast(msg: String)

    /** Set status con color (WARN, OK, ERR, IDLE). */
    fun setStatus(text: String, color: Int)

    /** Aplicar busy global (deshabilita input bar). */
    fun applyBusy(b: Boolean)

    /** Actualizar estado habilitado/deshabilitado del checkbox thinking. */
    fun updateThinkEnabled()

    /** Añadir hint de sistema al chat. */
    fun addSystemHint(text: String)

    /** Marcar modelo como cargado en parámetros. */
    fun markModelLoaded(path: String)

    /** Marcar modelo como descargado en parámetros. */
    fun markModelUnloaded()

    /** Heurística: ¿el modelo soporta thinking? (por nombre de archivo). */
    fun guessSupportsThinking(filename: String): Boolean

    /** Probe: ¿el modelo .litertlm soporta thinking? (lee Capabilities). */
    fun probeThinking(path: String): Boolean

    /** Probe: ¿el modelo .litertlm es multimodal (visión)? */
    fun probeVision(path: String): Boolean

    /** Habilitar log nativo de llama.cpp (best effort). */
    fun enableLlamaLog()
}