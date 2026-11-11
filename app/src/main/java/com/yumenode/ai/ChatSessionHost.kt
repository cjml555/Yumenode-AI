// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.graphics.Bitmap
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import io.noties.markwon.Markwon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import net.ladenthin.llama.LlamaModel

/**
 * Contrato mínimo que [ChatSessionController] necesita de su host (MainActivity).
 *
 * Solo lo imprescindible para sesiones y render del chat: Activity (recursos,
 * diálogos, dp/rounded), toast, paleta, persistencia de sesiones, los motores
 * cargados, el contenedor de burbujas, el scroll, el estado busy y las acciones
 * que el bloque no debe duplicar (enviar, imágenes, drawer). El resto de la
 * Activity queda fuera.
 */
interface ChatSessionHost {
    /** Activity para recursos (getString), AlertDialog y los helpers dp/rounded. */
    val activity: Activity

    /** Paleta de colores actual. */
    val palette: YumenodeTheme

    /** Mostrar toast. */
    fun toast(msg: String)

    /** Persistencia de sesiones (sessions.json). */
    val sessionsManager: SessionManager

    /** Sesiones del chat (mutable; la Activity conserva la lista). */
    val sessionItems: MutableList<ChatSession>

    /** Sesión actual. */
    var currentSession: ChatSession

    /** Motor LiteRT cargado (null = no cargado). */
    var loadedEngine: Engine?

    /** Conversación LiteRT activa. */
    var loadedConversation: Conversation?

    /** Modelo GGUF cargado (null = no cargado). */
    val loadedLlama: LlamaModel?

    /** Construcción de parámetros de inferencia (configFor). */
    val inferenceBuilder: InferenceParamsBuilder

    /** Parámetros de chat (thinkBudgetOrUnlimited, …). */
    val modelChatParams: ChatParams

    /** Manejo de adjuntos pendientes. */
    val attachments: AttachmentHandler

    /** Render Markdown de respuestas finales. */
    val markdownFull: Markwon

    /** Contenedor vertical de burbujas del chat. */
    val chatViews: LinearLayout

    /** Gestor de scroll de la lista de chat (null hasta buildUi). */
    val scrollManager: ChatScrollManager?

    /** Checkbox de thinking. */
    val thinkingBox: CheckBox

    /** Botón enviar/detener. */
    val sendView: TextView

    /** Botón de cargar modelo (applyBusy lo habilita/deshabilita). */
    val loadView: TextView

    /** Campo de entrada de texto. */
    val chatInput: EditText

    /** Generando en curso. */
    var isBusy: Boolean

    /** thinking con el que se creó la conversación actual (null = sin conversación). */
    var thinkingState: Boolean?

    /** Si la conversación se creó con capacidad de dibujo inyectada. */
    var drawCapableState: Boolean

    /** El drawer lateral de sesiones está abierto. */
    val drawerIsOpen: Boolean

    /** Hay modelo conversacional y de dibujo a la vez (canDrawFromChat). */
    val drawCapable: Boolean

    /** Refrescar el drawer de sesiones (tras borrar/crear). */
    fun refreshSessionDrawer()

    /** Enviar el contenido actual del campo de entrada (doSend). */
    fun sendMessage()

    /** Leer una imagen guardada en filesDir/chatimg/. */
    fun readChatImageAt(name: String): Bitmap?

    /** Bitmap → JPEG bytes (compresión igual que la Activity). */
    fun bitmapToJpegFor(bmp: Bitmap, quality: Int = 88): ByteArray

    /** Ver imagen en pantalla completa. */
    fun showChatFullscreen(bmp: Bitmap)

    /** Menú de imagen (guardar / citar / enviar a dibujo/tagger). */
    fun showChatImageMenuFor(bmp: Bitmap, name: String)

    /** Insertar imagen en una tarjeta de respuesta. */
    fun attachChatBubble(area: AiArea, bmp: Bitmap, name: String)

    /** Render Markdown de respuestas finales. */
    val markwonStream: Markwon

    /** Job en curso (para cancelar). */
    var genJob: Job?

    /** CoroutineScope para operaciones asíncronas. */
    val scope: CoroutineScope

    /** Set status with color */
    fun setStatus(text: String, color: Int)
}