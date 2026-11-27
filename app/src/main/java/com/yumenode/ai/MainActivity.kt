// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.util.Log
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.yumenode.ai.draw.DrawPage
import com.yumenode.ai.draw.ModelSet
import com.yumenode.ai.draw.GgufProbe
import com.yumenode.ai.draw.TaggerEngine
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.flow.channelFlow
import net.ladenthin.llama.LlamaIterator
import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.parameters.InferenceParameters
import net.ladenthin.llama.parameters.ModelParameters
import net.ladenthin.llama.value.ChatMessage
import net.ladenthin.llama.value.ContentPart
import net.ladenthin.llama.value.Pair
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
// Manager imports extracted from MainActivity
import com.yumenode.ai.SessionManager
import com.yumenode.ai.QaTurn as ManagerQaTurn
import com.yumenode.ai.ChatManager
import com.yumenode.ai.QaTurn
import com.yumenode.ai.ChatSession
import com.yumenode.ai.AttachmentHandler
import com.yumenode.ai.RoundOut
import com.yumenode.ai.ToolReturn
import com.yumenode.ai.tagRolesGguf
import com.yumenode.ai.FeatureToggleManager

/**
 * On-device chat app that loads a Google LiteRT-LM (.litertlm) model from local
 * storage and runs it via com.google.ai.edge.litertlm.
 *
 * UI: flat modern look — deep surface app bar, card-style settings panel, chat bubbles,
 * bottom input bar, collapsible "thinking" section, and Markdown-rendered replies.
 */
class MainActivity : Activity(), SettingsHost, ThemeAliases, ApiHost, AttachHost, ChatSessionHost, DrawModelsHost, ShellHost, ModelLoadHost, BenchmarkHost, LocaleHost {

    // ---- palette ----
    // ================= Theme =================
    // All UI colors come from pal (see YumenodeTheme); here we just name them so C_XXX stays usable.
    // Use these constants for new controls; don't hardcode colors, or dark mode leaves white blocks.

    /** Theme manager: separates theme state & palette logic from UI */
    override val themeManager = ThemeManager(this) {
        // Theme applied — rebuild UI palette accessors
    }

    /** Current theme mode: 0=system, 1=light, 2=dark (stored in ponko prefs) */
    override val themeMode: Int
        get() = themeManager.themeMode

    /** Current palette */
    val pal: YumenodeTheme
        get() = themeManager.pal

    // ---- Manager dependencies ----
    private val crashHandler = CrashHandler(this)
    internal val sessionManager = SessionManager(this)
    private val attachmentHandlerInternal = AttachmentHandler.create(this, this)
    override val attachmentHandler: AttachmentHandler get() = attachmentHandlerInternal

    /** Barra de entrada del chat: dueña de sus vistas (MainActivity las delega). */
    private lateinit var chatInputBar: ChatInputBar

    /** Contrato mínimo que la barra de entrada necesita de la Activity. */
    private fun createInputBarHost() = object : InputBarHost {
        override val activity: android.app.Activity get() = this@MainActivity
        override val palette: YumenodeTheme get() = this@MainActivity.palette
        override fun chatPrefs(): android.content.SharedPreferences = this@MainActivity.chatPrefs()
        override fun attachPreviewViews(strip: HorizontalScrollView, box: LinearLayout) {
            attachmentHandler.attachViews(strip, box)
        }
        override fun onAttachClick() {
            this@MainActivity.onAttachClick()
        }
        override fun onSendOrStop() {
            sendOrStop()
        }
    }

    /**
     * Botón ↑ del chat: envía el mensaje o detiene la generación en curso.
     *
     * Regresión: en dd3c7e6 se cambió por `modelLoadController.toggleLoad()`, así
     * que pulsar enviar cargaba o descargaba el modelo en vez de enviar.
     */
    internal fun sendOrStop() {
        val job = genJob
        if (job != null && job.isActive) {
            // Detener primero el lado nativo: cancelar solo el Flow deja el hilo
            // de inferencia corriendo y el siguiente envío se cuelga.
            stopRequested = true
            runCatching { ChatCore.requestCancel() }
            drawPageInternal?.cancel()
            job.cancel()
            genJob = null
            if (busy) applyBusy(false)
            setStoppingUi(false)
            toast(getString(R.string.s_083))
        } else {
            doSend()
        }
    }

    internal fun setStoppingUi(stop: Boolean) = chatSessionController.setStoppingUi(stop)

    internal val commandScanner = CommandScanner()

    /** Constructores de widgets con el estilo de Yumenode AI. */
    override val ui by lazy { UiWidgets(this) { pal } }

    // ---- SettingsHost ----
    override val activity: Activity get() = this
    override val palette: YumenodeTheme get() = pal
    override val drawBusy: Boolean get() = drawPageInternal?.isBusy() == true
    override val accentIndex: Int get() = themeManager.accentIndex
    override fun applyThemeMode(mode: Int) = localeController.applyThemeMode(mode)
    override fun applyAccent(index: Int) = localeController.applyAccent(index)
    override fun toast(msg: String) {
        runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }
    override fun prefBool(key: String, def: Boolean) = chatPrefs().getBoolean(key, def)
    override fun prefPutBool(key: String, value: Boolean) {
        chatPrefs().edit().putBoolean(key, value).apply()
    }

    override fun prefStr(key: String, def: String) = chatPrefs().getString(key, def) ?: def
    override fun prefPutStr(key: String, value: String) {
        chatPrefs().edit().putString(key, value).apply()
    }

    /** Envia el ultimo benchmark (anonimo) al endpoint configurado. */
    override fun shareBenchmark(endpoint: String, onResult: (String) -> Unit) {
        val b = lastBench
        if (b == null) { onResult(getString(R.string.s_482)); return }
        val url = endpoint.trim().ifBlank { DEFAULT_BENCH_ENDPOINT }
        if (!url.startsWith("http")) { onResult(getString(R.string.s_483)); return }
        scope.launch {
            val body = JSONObject().apply {
                put("model", b.model); put("backend", b.backend); put("device", b.device)
                put("soc", b.soc); put("version", appVersion())
                put("tps_avg", b.tpsAvg); put("tps_min", b.tpsMin); put("tps_max", b.tpsMax)
                put("latency_min", b.latMin); put("latency_max", b.latMax); put("tokens", b.tokens)
            }.toString()
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10000
                        readTimeout = 10000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..299) getString(R.string.s_484, code) else getString(R.string.s_485, code)
                }.getOrElse { "Error: ${it.message ?: it.javaClass.simpleName}" }
            }
            onResult(msg)
        }
    }

    /** Benchmark local: corre N generaciones cortas sobre el modelo cargado y
     *  reporta tok/s, latencia, modelo, backend y dispositivo. No sube nada. */
    private var lastLocales: android.os.LocaleList? = null

    override fun currentAppLanguage(): String? = localeController.currentAppLanguage()

    override fun setAppLanguage(tag: String?) = localeController.setAppLanguage(tag)

    override fun runBenchmark(onUpdate: (String) -> Unit) {
        benchmarkController.runBenchmark(onUpdate)
    }

    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override var engine: Engine? = null
    override var conversation: Conversation? = null
    private var modelPathInternal: String? = null
    /** El modelo cargado admite 'thinking' (heurístico por nombre). El checkbox
     *  de Thinking se oculta cuando el modelo no soporta razonamiento. */
    override var modelSupportsThinking: Boolean = true
    /** Pensamiento efectivo: el usuario lo activa Y el modelo lo admite. */
    internal val thinkingActive: Boolean get() = modelSupportsThinking && thinkCheck.isChecked
    private var taggerUiInternal = TaggerUi(
        this,
        scope,
        pal,
        { drawPageInternal },
        { taggerStatusTvInternal },
        { taggerBoxInternal },
        { taggerLoadBtnInternal },
        { setDrawBusy(it) },
        { setMainBusy(it) },
        { showDrawProgress(it) },
        { text, color -> setStatus(text, color) },
        { toast(it) },
    )
    /** Multimodal GGUF: mmproj (vision projection) file management delegated to VisionManager */
    private var visionManager: VisionManager? = null

    /** Construcción de parámetros de inferencia (gguf / litertlm) */
    internal val inferenceParamsBuilder: InferenceParamsBuilder by lazy {
        InferenceParamsBuilder(
            chatParams = chatParams,
            drawToolPrompt = drawToolPrompt,
            canDrawFromChat = { canDrawFromChat },
            canTagTurn = { canTagTurn(it) },
            tagHint = { tagHint(it) },
            thinkChecked = { thinkingActive },
        ).apply {
            estimateTokens = { chatManager.estTokens(it).toInt() }
        }
    }

    // ---- Chat params (models page . chat model) ----
    override val chatParams = ChatParams(this) { modelPathInternal }
    /** Temporarily suppress the TextWatcher when backfilling params into inputs, so fields don't overwrite each other */
    private val chatParamsOwnerTv: TextView? get() = modelsPage.chatParamsOwnerTv
    private val chatCtxEdit: EditText? get() = modelsPage.chatCtxEdit
    private val chatMaxOutEdit: EditText? get() = modelsPage.chatMaxOutEdit
    private val chatTempEdit: EditText? get() = modelsPage.chatTempEdit
    private val chatTopKEdit: EditText? get() = modelsPage.chatTopKEdit
    private val chatTopPEdit: EditText? get() = modelsPage.chatTopPEdit
    private val chatRepEdit: EditText? get() = modelsPage.chatRepEdit
    private val chatBudgetEdit: EditText? get() = modelsPage.chatBudgetEdit
    private val chatSeedEdit: EditText? get() = modelsPage.chatSeedEdit
    internal var busy = false
    private val modelLoadController by lazy { ModelLoadController(this) }

    /** Chat message handling: build file blocks, encode files, estimate tokens, prepare to send */
    internal val chatManager = ChatManager(this, attachmentHandler)

    /** Whether the loaded .litertlm model supports image input (reads the model's Capabilities; treats as unsupported on failure). */
    /** true = el modelo cargado soporta entrada visual (delegado a VisionManager) */
    override var visionOk: Boolean
        get() = visionManager?.visionOk ?: false
        set(v) { visionManager?.setVisionOk(v) }

    /** Ruta al mmproj seleccionado (delegado a VisionManager) */
    override var mmprojPath: String?
        get() = visionManager?.mmprojPath
        set(v) { visionManager?.mmprojPath = v }
    /** Which role candidate to return tool results with when borrowing the tagger to see images (0=first choice; remembered once one works) */
    override var tagReturnIdx = 0
    /** Input bar "+" attach button; preview strip and thumbnails for pending images (ChatInputBar property) */
    private val attachButton: TextView get() = chatInputBar.attachButton
    private val attachPreviewStrip: HorizontalScrollView get() = chatInputBar.attachPreviewStrip
    private val attachPreviewBox: LinearLayout get() = chatInputBar.attachPreviewBox

    /** Pending text files to send (already parsed), up to MAX_FILES */
    // private val pendingFiles = ArrayList<PendingFile>()

    /** User-selected files: filename + raw byte count + parsed body (may be truncated) + structure note (e.g. "12 pages") */
    // private class PendingFile(
    //     val name: String,
    //     val bytes: Long,
    //     var text: String,
    //     var truncated: Boolean,
    //     val detail: String? = null,
    // )

    /** File read result: file on success, error (displayable text) on failure */
    // private class FileRead(val file: PendingFile?, val error: String?)

    /** Currently in draw mode: chat page input is treated as a positive prompt for image generation */
    /** Draw command instructions for the LLM (injected only when both draw and chat models are ready). */
    internal val drawToolPrompt = CommandScanner.DRAW_TOOL_PROMPT

    /** When the LLM and draw model are both ready -> the LLM can invoke the draw model via <draw> commands. */
    internal val canDrawFromChat: Boolean
        get() = drawPageInternal?.isReady() == true && (engine != null || llamaModel != null)

    /** Extract <draw>...</draw> from the answer: returns the prompt and match span (content unchanged; caller replaces it) */
    internal fun scanDrawCommand(answer: String, thought: String): DrawHit? =
            commandScanner.scanDrawCommand(answer, thought, canDrawFromChat)

        /** The LLM can't see images but the tagger is loaded -> the system can use <tag> commands to recognize images. */
        internal val canTagFromChat: Boolean
            get() = drawPageInternal?.taggerLoaded() == true && (engine != null || llamaModel != null) && !visionOk

        /** This round has images, the model can't see them, and they haven't been tagged yet -> give the model the tag command instructions this round */
        internal fun canTagTurn(t: QaTurn?): Boolean =
            canTagFromChat && t?.userImage != null

        /** For models that can't see images: tell them they can use <tag> to have the tagger look at the user's images. */
        private val tagToolPrompt = """【图片工具】你自己看不到图片内容，但可以调用本机的打标模型，把用户刚发来的图片转成英文标签。
            用户消息里说明了「带了图片」时就是真的有图：此时必须调用下面的图片工具，
            绝对不要回复「请提供图片」或「我看不到图片」之类的推辞。
            需要知道图里有什么时：不要向用户解释这个工具，不要复述这条规则，不要猜图。
            这一轮你的输出里只写下面这一行命令，不要有任何解释、计划或别的话（参数可省；threshold 是阈值 0.05~0.99，topk 是最多几个标签 1~80）：
            <tag threshold=\"0.35\" topk=\"40\"></tag>
            命令写在正文的最后一行，不要写进思考过程。系统随后会把识别结果发给你，你再根据这些标签回答用户的问题.
        """.trimIndent()

        /** Prompt for models that can't see images, given when this round has images: first note "images attached", then the tool command format */
        internal fun tagHint(t: QaTurn?): String {
            val n = ChatSendLogic.countImages(t?.userImage)
            return getString(R.string.s_329, n) + "\n" + tagToolPrompt
        }

        /** When not making a tool call, strip any leftover <tag ...></tag> commands from the answer (don't show the raw command as body text) */

        /** Extract <tag ...></tag> from the answer: returns params and match span (content unchanged; caller replaces it) */
        internal fun scanTagCommand(answer: String, thought: String): TagHit? =
            commandScanner.scanTagCommand(answer, thought, canTagFromChat)

    /**
     * Run one generation round (both gguf and litertlm paths), streaming output into ai.
     * displayPrefix and thoughtPrefix are content already rendered in the bubble from earlier rounds,
     * so tool calls can keep writing into the same bubble instead of restarting the whole round.
     */


    /** Whether the chat page is currently in "draw mode".
     *  Rule: when an LLM is present (including both models loaded), the chat page always chats;
     *  only when the draw model is ready and no LLM is loaded is chat input treated as a draw prompt. */
    internal val drawMode: Boolean
        get() = drawPageInternal?.isReady() == true && engine == null && llamaModel == null

    /** Cache for .gguf architecture probe results, avoids re-reading the header */
    private val ggufKindCacheInternal = HashMap<String, GgufProbe.Kind>()
    override var genJob: Job? = null
    /** Ultimo resultado del benchmark (para compartir anonimamente). */
    override var lastBench: BenchStats? = null
    /** Footnote '≈X tok/s' de la última generación (solo render en vivo). */
    internal var lastTpsFoot: String? = null

    // QaTurn is imported from ChatState.kt

    private val sessions = mutableListOf<ChatSession>()
    internal lateinit var current: ChatSession

    /** Thinking toggle state at session creation; rebuild the session if it diverges from the checkbox. */
    override var convThinking: Boolean? = null

    /** After an interrupt the LiteRT session may be unusable: flag a silent rebuild before the next send. */
    internal var convNeedsRebuild = false

    /** Whether the <draw> command prompt was injected when the LiteRT session was created.
     *  Loading/unloading the draw model changes this capability, and LiteRT's system prompt is
     *  written only at session creation, so rebuild when capability changes, or the model never knows it can draw. */
    override var convDrawCapable = false

    /** GGUF (llama.cpp) backend instance; non-null means a .gguf model is currently loaded. */
    override var llamaModel: LlamaModel? = null
    internal var ggufIterator: LlamaIterator? = null

    /** User tapped "stop". Native interrupts often throw (e.g. llama.cpp task not found);
     *  this flag separates intentional stops from real errors, so already-output content isn't overwritten with an error message. */
    @Volatile
    internal var stopRequested = false

    internal lateinit var markwonFull: Markwon
    override lateinit var markwonStream: Markwon

    // ---- views ----
    private lateinit var statusTv: TextView
    private lateinit var statusDot: View
    private lateinit var tabChat: View
    private lateinit var tabModels: View

    /** Página de modelos extraída: dueña de sus vistas (MainActivity las delega). */
    private lateinit var modelsPage: ModelsPage

    // Models page: top tabs + left-right swipe
    private val modelsPager: ViewPager get() = modelsPage.modelsPager
    private val modelsTabChat: TextView get() = modelsPage.modelsTabChat
    private val modelsTabDraw: TextView get() = modelsPage.modelsTabDraw
    private val modelsTabTag: TextView get() = modelsPage.modelsTabTag
    private lateinit var tabSettings: View

    // ---- API settings blocks: one each for chat and draw, generated by buildApiBlock(), identical structure ----
    // ApiHost: ApiController logic lives in ApiController.kt; here we only expose views + delegate.
    private val apiController by lazy { ApiController(this) }

    // AttachHost: la lógica de adjuntos vive en AttachController.kt
    private val attachController by lazy { AttachController(this) }

    // ChatSessionHost: la lógica de sesiones/render del chat vive en ChatSessionController.kt
    private val chatSessionController by lazy { ChatSessionController(this) }

    // DrawModelsHost: la lógica de slots de dibujo / LoRA / tagger / modelos guardados vive en DrawModelsController.kt
    internal val drawModelsController by lazy { DrawModelsController(this) }
    internal val benchmarkController by lazy { BenchmarkController(this) }
    internal val localeController by lazy { LocaleController(this) }

    // ShellHost: la navegación / drawer / tabs viven aquí en ShellController.kt
    private val shellController by lazy { ShellController(this) }
    override val drawPage: com.yumenode.ai.draw.DrawPage? get() = drawPageInternal
    override val taggerUi: TaggerUi get() = taggerUiInternal
    override val drawSlotsContainer: LinearLayout? get() = drawSlotsContainerInternal
    override val drawSlotsHeader: TextView? get() = drawSlotsHeaderInternal
    override var drawSlotsExpanded: Boolean
        get() = drawSlotsExpandedInternal
        set(v) { drawSlotsExpandedInternal = v }
    override val drawModelStatus: TextView? get() = drawModelStatusInternal
    override val loraStatusTv: TextView? get() = loraStatusTvInternal
    override val loraBox: LinearLayout? get() = loraBoxInternal
    override val taggerStatusTv: TextView? get() = taggerStatusTvInternal
    override val taggerBox: LinearLayout? get() = taggerBoxInternal
    override val taggerLoadBtn: TextView? get() = taggerLoadBtnInternal
    override val savedContainer: LinearLayout get() = savedContainerInternal
    override val modelInfoText: TextView get() = modelInfoTextInternal
    override val progressBar: ProgressBar get() = progressBarInternal
    override var pendingSlotKey: String?
        get() = pendingSlotKeyInternal
        set(v) { pendingSlotKeyInternal = v }
    override var drawSetId: String?
        get() = drawSetIdInternal
        set(v) { drawSetIdInternal = v }
    override var modelPath: String?
        get() = modelPathInternal
        set(v) { modelPathInternal = v }
    override val ggufKindCache: MutableMap<String, com.yumenode.ai.draw.GgufProbe.Kind> get() = ggufKindCacheInternal
    override fun loadChatParamsIntoUi() = loadChatParamsIntoUiInternal()
    override fun refreshDrawModels() = refreshDrawModelsInternal()
    override fun refreshDrawToggle() = refreshDrawToggleInternal()
    override val hasModel: Boolean get() = engine != null || llamaModel != null

    // ---- ChatSessionHost ----
    override val sessionsManager get() = sessionManager
    override val sessionItems: MutableList<ChatSession> get() = sessions
    override var currentSession: ChatSession
        get() = current
        set(v) { current = v }
    override var loadedEngine: com.google.ai.edge.litertlm.Engine?
        get() = engine
        set(v) { engine = v }
    override var loadedConversation: com.google.ai.edge.litertlm.Conversation?
        get() = conversation
        set(v) { conversation = v }
    override val loadedLlama: net.ladenthin.llama.LlamaModel? get() = llamaModel
    override val inferenceBuilder: InferenceParamsBuilder get() = inferenceParamsBuilder
    override val modelChatParams: ChatParams get() = chatParams
    override val attachments: AttachmentHandler get() = attachmentHandler
    override val markdownFull: io.noties.markwon.Markwon get() = markwonFull
    override val chatViews: LinearLayout get() = chatContainer
    override val scrollManager: ChatScrollManager? get() = chatScrollManager
    override val thinkingBox: android.widget.CheckBox get() = thinkCheck
    override val sendView: TextView get() = sendButton
    override val loadView: TextView get() = loadButton
    override val chatInput: EditText get() = inputEdit
    override var isBusy: Boolean
        get() = busy
        set(v) { busy = v }
    override var thinkingState: Boolean?
        get() = convThinking
        set(v) { convThinking = v }
    override var drawCapableState: Boolean
        get() = convDrawCapable
        set(v) { convDrawCapable = v }
    override val drawerIsOpen: Boolean get() = drawerOpen
    override val drawCapable: Boolean get() = canDrawFromChat
    override fun refreshSessionDrawer() = refreshDrawer()
    override fun sendMessage() { doSend() }
    override fun readChatImageAt(name: String): android.graphics.Bitmap? = readChatImageImpl(name)
    override fun readChatImage(name: String): Bitmap? = readChatImageImpl(name)
    override fun bitmapToJpegFor(bmp: android.graphics.Bitmap, quality: Int): ByteArray = bitmapToJpeg(bmp, quality)
    override fun showChatFullscreen(bmp: android.graphics.Bitmap) = showImageFullscreen(bmp)
    override fun showChatImageMenuFor(bmp: android.graphics.Bitmap, name: String) = showImageMenu(bmp, name)
    override fun attachChatBubble(area: AiArea, bmp: android.graphics.Bitmap, name: String) = attachImageBubble(area, bmp, name)

    override val modelsInitialized: Boolean get() = ::modelsPage.isInitialized
    override val chatApiBlock: ApiBlockView? get() = modelsPage.chatApiBlock
    override val drawApiBlock: ApiBlockView? get() = modelsPage.drawApiBlock
    /** One "xx model: xxx" line on each side, inserted below the API block's address */
    override val chatApiModelTv: TextView get() = modelsPage.chatApiModelTv
    // ---- "Chat model" page split into two sub-pages: model / API ----
    private val chatSubTabModel: TextView get() = modelsPage.chatSubTabModel
    private val chatSubTabApi: TextView get() = modelsPage.chatSubTabApi
    private val chatModelPane: View get() = modelsPage.chatModelPane
    private val chatApiPane: View get() = modelsPage.chatApiPane
    // ---- "Draw model" page split into two sub-pages: model / API ----
    private val drawSubTabModel: TextView get() = modelsPage.drawSubTabModel
    private val drawSubTabApi: TextView get() = modelsPage.drawSubTabApi
    private val drawModelPane: View get() = modelsPage.drawModelPane
    private val drawApiPane: View get() = modelsPage.drawApiPane
    /** The "draw model: xxx" line (draw-side only, inserted below the API block's address) */
    override val drawApiModelTv: TextView get() = modelsPage.drawApiModelTv
    private lateinit var tabDraw: View
    override lateinit var inputBar: View

    /** Draw page (text-to-image / image-to-image), lazily loaded */
    internal var drawPageInternal: DrawPage? = null
    private lateinit var appBarMenu: View
    private lateinit var appBarPlus: View
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerMask: View
    private lateinit var drawerBody: LinearLayout
    private val navIcons = mutableListOf<TextView>()
    private val navLabels = mutableListOf<TextView>()
    private val savedContainerInternal: LinearLayout get() = modelsPage.savedContainer
    private val modelInfoTextInternal: TextView get() = modelsPage.modelInfoText
    private lateinit var tabDownload: View
    private lateinit var tabGallery: View

    /** Draw model status text in the models page (draw models are loaded from the models page) */
    private val drawModelStatusInternal: TextView? get() = modelsPage.drawModelStatus
    /** Container for the copied draw model list */
    private val drawSavedContainer: LinearLayout? get() = modelsPage.drawSavedContainer
    /** Combined load/unload button for the draw model */
    private val drawToggleBtn: TextView? get() = modelsPage.drawToggleBtn
    /** "Import" button for the draw model (disabled during copy) */
    private val drawImportBtn: TextView? get() = modelsPage.drawImportBtn
    /** Draw model import progress bar (shown during local copy) */
    private val drawProgress: ProgressBar? get() = modelsPage.drawProgress
    /** Tagger status text (models page) */
    private val taggerStatusTvInternal: TextView? get() = modelsPage.taggerStatusTv
    /** Import buttons for tagger model / label table */
    private val taggerOnnxBtn: TextView? get() = modelsPage.taggerOnnxBtn
    private val taggerCsvBtn: TextView? get() = modelsPage.taggerCsvBtn
    /** Container for imported tagger model / label table list */
    private val taggerBoxInternal: LinearLayout? get() = modelsPage.taggerBox
    /** Tagger model load/unload button */
    private val taggerLoadBtnInternal: TextView? get() = modelsPage.taggerLoadBtn
    /** Currently selected draw main model (absolute path) */
    /** Currently chosen model set id (multi-file model = a group of files) */
    private var drawSetIdInternal: String? = null
    /** Slot waiting for a file selection result (null = main model file) */
    private var pendingSlotKeyInternal: String? = null
    private val drawSlotsContainerInternal: LinearLayout? get() = modelsPage.drawSlotsContainer
    /** Collapsible header row for the slot panel */
    private var drawSlotsHeaderInternal: TextView? = null
    /** Whether the slot panel is expanded (collapsed by default to save space) */
    private var drawSlotsExpandedInternal = false
    /** LoRA status text (models page) */
    private val loraStatusTvInternal: TextView? get() = modelsPage.loraStatusTv
    /** Installed LoRA list container (models page) */
    private val loraBoxInternal: LinearLayout? get() = modelsPage.loraBox
    /** Last startup found the draw model load crashed mid-way (native crash, uncatchable from Java) */
    private var pendingDrawCrash = false
    /** Stage reached when the last crash happened (from draw/.loadstage) */
    private var pendingDrawStage: String? = null
    override val backendSpinner: Spinner get() = modelsPage.backendSpinner
    override val loadButton: TextView get() = modelsPage.loadButton
    override val thinkCheck: CheckBox get() = chatInputBar.thinkCheck
    private val progressBarInternal: ProgressBar get() = modelsPage.progressBar
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    internal val inputEdit: EditText get() = chatInputBar.inputEdit

    /** Pestaña de descargas (lista del catálogo + progreso). */
    private var downloadPage: DownloadPage? = null
    private var galleryPage: GalleryPage? = null
    private val sendButton: TextView get() = chatInputBar.sendButton
    private lateinit var jumpButton: TextView

    // ---- scroll state (delegado a ChatScrollManager) ----
    internal var chatScrollManager: ChatScrollManager? = null

    // ---- state ----
    private var currentTab = 0
    private var gpuCrashNotice = false
    private var drawerOpen = false
    private val drawerWidthDp = 280

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the crash hook earliest: exceptions during theme parsing / UI build also get logged (a crash inside buildUi would otherwise leave no trace)
        crashHandler.install()
        // Trial GPU colgado = el intento anterior con Vulkan mato el proceso:
        // apagar el toggle solo y seguir en CPU.
        if (GpuTrial.recoverOnStart(chatPrefs())) {
            // toast tras setTheme/super.onCreate, mas abajo no hace falta:
            // se guarda para mostrarlo al final de onCreate.
            gpuCrashNotice = true
        }
        // Theme must be set before setContentView (system bar/window colors come from style, UI colors from pal)
        themeManager.loadThemeMode(chatPrefs())
        themeManager.applyThemeInPlace()
        setTheme(if (themeManager.pal.isDark) R.style.AppTheme_Dark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        lastLocales = resources.configuration.locales
        markwonFull = Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
        // The streaming renderer deliberately omits the table plugin: tables need two-pass
        // async layout (measure column widths then reflow), and re-rendering during streaming makes the bubble height jump; tables get one render after generation ends.
        markwonStream = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
        sessionManager.load()
        // Convert SessionManager's top-level session types to MainActivity's nested types
        sessions.clear()
        sessions.addAll(sessionManager.allSessions().map { ms ->
            ChatSession(ms.id, ms.title).apply {
                ms.turns.forEach { mt ->
                    turns.add(QaTurn(
                        user = mt.user,
                        answer = mt.answer,
                        thought = mt.thought,
                        image = mt.image,
                        userImage = mt.userImage,
                        userFile = mt.userFile,
                    ).apply {
                        fileText = mt.fileText
                        fileStore = mt.fileStore
                    })
                }
            }
        })
        current = sessionManager.currentSession()?.let { c ->
            sessions.find { it.id == c.id } ?: run {
                val s = ChatSession(c.id, c.title)
                sessions.add(s)
                s
            }
        } ?: ChatSession(System.currentTimeMillis(), getString(R.string.s_109))
        buildUi()
        if (gpuCrashNotice) {
            gpuCrashNotice = false
            toast(getString(R.string.s_566))
        }
    }

    /**
     * Apply the theme in place: recompute palette -> update system bar colors -> rebuild the UI with the new palette.
     *
     * We don't call recreate(): Activity reconstruction would discard the chat engine, draw native context,
     * and ONNX tagger session (users would feel it as "changing a setting restarts the app"). Here we only rebuild the View; those objects stay alive.
     */
    override fun applyThemeInPlace() {
        themeManager.applyThemeInPlace()
            // Remember the current page: buildUi() switches back to it at the end
            chatPrefs().edit().putInt("restoreTab", currentTab).apply()
            buildUi()
        }

        /** The manifest declares uiMode, so system light/dark switches don't auto-rebuild; we follow along manually. */
        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            val themeChanged = themeManager.onConfigurationChanged(newConfig)
            val localeChanged = lastLocales != newConfig.locales
            lastLocales = newConfig.locales
            if (themeChanged || localeChanged) applyThemeInPlace()
        }

    /** Self-catching crashes: Java/ART exceptions (including UnsatisfiedLinkError) are written to a file, visible on next launch. */
    private fun installCrashHandler() {
        crashHandler.install()
    }

    /** Read and clear the crash log (shown on the models page) */
    private fun takeCrashLog(): String? {
        return crashHandler.takeCrashLog()
    }

    /** Sync MainActivity's session state into SessionManager */
    private fun syncSessionToManager() {
        val convertedSessions = sessions.map { s ->
            com.yumenode.ai.ChatSession(s.id, s.title).apply {
                s.turns.forEach { t ->
                    turns.add(com.yumenode.ai.QaTurn(
                        user = t.user,
                        answer = t.answer,
                        thought = t.thought,
                        image = t.image,
                        userImage = t.userImage,
                        userFile = t.userFile,
                    ).apply {
                        fileText = t.fileText
                        fileStore = t.fileStore
                    })
                }
            }
        }
        sessionManager.syncFrom(convertedSessions, convertedSessions.find { it.id == current?.id })
    }

    override fun onPause() {
        super.onPause()
        syncSessionToManager()
        sessionManager.save()
        drawPageInternal?.persistAll()   // Persist draw params as a safety net
    }

    override fun onDestroy() {
        super.onDestroy()
        syncSessionToManager()
        try { sessionManager.save() } catch (_: Throwable) {}
        scope.cancel()
        // Draw engine: only leave the handle for the service to keep drawing when the draw API is running; otherwise release normally (don't waste memory).
        // Order matters - disconnect the DrawCore reference first, then actually free.
        if (!ApiService.isRunning(ApiServer.Role.DRAW)) {
            DrawCore.release()
            try { drawPageInternal?.release() } catch (_: Throwable) {}
        }
        if (!ApiService.isRunning(ApiServer.Role.CHAT)) {
            // Chat API not running -> release the model as usual
            try { engine?.close() } catch (_: Throwable) {}
            try { llamaModel?.close() } catch (_: Throwable) {}
            ChatCore.release()
        }
        // Chat API still running: leave the model for the service to keep inferring (closing it would
        // make the API unavailable immediately); here we only drop the UI's own reference.
        engine = null
        llamaModel = null
        conversation = null
    }

    // ================= UI construction =================

    override fun buildUi() {
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(C_BG)
            fitsSystemWindows = true
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }

        main.addView(buildAppBar(), matchWrap())

        // Four content pages: chat / models / draw / about (switched by the bottom menu)
        tabChat = buildChatPage()
        // Order matters: DrawPage must be built and assigned to drawPageInternal before the "models" page.
        // The "models" page reads drawPageInternal's state in place during build (LoRA list / copied draw model list);
        // reversing it yields null and every startup shows "not installed".
        // Reuse the existing DrawPage: a theme change rebuilds only the View; the native context / loaded models
        // stay in the same instance (otherwise every theme change reloads the draw model).
        val dpg = drawPageInternal ?: DrawPage(this, scope, pal)
        dpg.setPalette(pal)
        drawPageInternal = dpg
        modelsPage = ModelsPage(createModelsPageHost())
        tabModels = modelsPage.build()
        tabSettings = buildSettingsPage()
        downloadPage = DownloadPage(
            this, { ui.card() }, { ui.pageTitle(it) }, { ui.hintText(it) },
            onModelsChanged = { refreshSavedModels(); refreshLoraUi() },
            pal = pal,
        )
        tabDownload = downloadPage!!.build()
        galleryPage = GalleryPage(
            this, scope, pal,
            { ui.card() }, { ui.pageTitle(it) }, { ui.hintText(it) },
        )
        tabGallery = galleryPage!!.build()
        // La pestaña de descargas se construye en DownloadPage
        // Draw model ready -> enter draw mode (chat page input becomes a positive prompt)
        dpg.onPipelineReady = {
            // The callback may come from an IO thread; route all UI ops back to the main thread
            runOnUiThread {
                dpg.llmLoaded = false
                updateThinkEnabled()
                drawModelStatusInternal?.text = dpg.modelSummary()
                refreshDrawModelsInternal()
                refreshDrawApiUi()   // The draw API block must show "draw model: xxx" too
            }
        }
        dpg.onSaveImage = { bmp, name -> saveImageToGallery(bmp, name) }
        dpg.onStatus = { text, isError ->
            runOnUiThread { setStatus(text, if (isError) C_ERR else C_WARN) }
        }
        // Note: don't auto-load the draw model at startup - a native load failure would crash the launch flow.
        // Instead, manually tap "load draw model" on the models page (button below).
        // The draw page uses ViewPager for left-right swiping; no outer ScrollView (horizontal/vertical scrolls would conflict)
        tabDraw = runCatching { dpg.build() }.getOrElse { e ->
            // Fallback: a draw page build failure must not take down the whole App; show the cause for diagnosis
            ScrollView(this).apply {
                setBackgroundColor(C_BG)
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.s_471) + "\n" + android.util.Log.getStackTraceString(e)
                    textSize = 12f
                    setTextColor(C_ERR_TEXT)
                    setPadding(36, 36, 36, 36)
                    setTextIsSelectable(true)
                })
            }
        }
        val contentFrame = FrameLayout(this)
        contentFrame.addView(tabChat, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabModels, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabSettings, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabDraw, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabDownload, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabGallery, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        main.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        chatInputBar = ChatInputBar(createInputBarHost())
        inputBar = chatInputBar.build()
        main.addView(inputBar, matchWrap())
        main.addView(buildBottomNav(), matchWrap())

        rootFrame.addView(main, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        buildDrawer(rootFrame)
        setContentView(rootFrame)
        applySystemBarColors(window, themeManager.pal)

        refreshSavedModels()
        restoreSession()
        switchTab(0)
        // Rebuild caused by a theme switch: return to the pre-rebuild page (one-off; normal startup unaffected)
        val restoreTab = chatPrefs().getInt("restoreTab", -1)
        if (restoreTab in 0..5) {
            chatPrefs().edit().remove("restoreTab").apply()
            switchTab(restoreTab)
        }
        handleShareIntent(intent)
    }

    /**
     * "Compartir con Yumenode AI" desde otras apps: el texto cae en el campo de
     * entrada y las imágenes se añaden como adjunto pendiente. Sin esto la app
     * solo podía enviar, nunca recibir.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        // Primero asegurar que la pestaña Chat está montada: switchTab puede
        // reconstruir la barra de entrada y se perdería el texto compartido.
        switchTab(0)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (stream != null) {
            runCatching {
                contentResolver.openInputStream(stream).use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }.getOrNull()?.let { bmp ->
                val jpeg = java.io.ByteArrayOutputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    out.toByteArray()
                }
                attachmentHandler.addPendingImage(bmp, jpeg)
                toast(getString(R.string.s_share_image))
            }
        } else if (!text.isNullOrBlank()) {
            val apply = {
                val current = inputEdit.text?.toString().orEmpty()
                inputEdit.setText(if (current.isBlank()) text else "$current\n$text")
                toast(getString(R.string.s_share_text))
            }
            if (inputEdit.post(apply)) Unit else apply()
        }
    }

    /** Chat page: message list + jump-to-bottom button + smart auto-follow scroll. */
    override fun buildChatPage(): View {
        val chatWrap = FrameLayout(this)
        scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        chatContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(chatContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // Pause auto-follow while the user scrolls up to read; this button jumps back to the bottom
        jumpButton = TextView(this).apply {
            text = "↓"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(C_PRIMARY, 24)
            elevation = dp(4).toFloat()
            visibility = View.GONE
            isClickable = true
        }
        chatWrap.addView(scrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        chatWrap.addView(jumpButton, FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(16), dp(16))
        })

        // Scroll management delegated to ChatScrollManager (auto-follow / jump-to-bottom button / input focus)
        chatScrollManager = ChatScrollManager(this, scrollView, jumpButton) { inputEdit }
        return chatWrap
    }

    override fun buildAppBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_CARD)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            elevation = dp(3).toFloat()
        }

        val r1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appBarMenu = ui.iconButton("☰") { openDrawer() }.apply {
            contentDescription = getString(R.string.s_a11y_menu)
        }
        r1.addView(appBarMenu, wrapWrap())

        // Ícono de la app en el header, justo al lado del nombre
        val appIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        r1.addView(appIcon, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = dp(8)
        })

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        titleBox.addView(TextView(this).apply {
            text = "Yumenode AI"
            textSize = 17f
            setTextColor(C_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrap())
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        statusDot = View(this).apply { background = rounded(C_PRIMARY, 99) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(5) })
        statusTv = TextView(this).apply {
            text = getString(R.string.s_116)
            textSize = 11.5f
            setTextColor(C_SUBTEXT)
        }
        statusRow.addView(statusTv, wrapWrap())
        titleBox.addView(statusRow, matchWrap())

        r1.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // Premium badge: visible only on premium flavor
        if (FeatureToggleManager.isPremium) {
            val badge = TextView(this).apply {
                text = "Premium"
                textSize = 9.5f
                setTextColor(C_PRIMARY)
                background = rounded(C_PRIMARY_SOFT, 8)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            r1.addView(badge, wrapWrap().apply { leftMargin = dp(6) })
        }
        appBarPlus = ui.iconButton("＋") { newSession() }.apply {
            contentDescription = getString(R.string.s_a11y_new_chat)
        }
        r1.addView(appBarPlus, wrapWrap())
        if (FeatureToggleManager.isPremium) {
            val premiumBtn = ui.iconButton("★") { toast("Premium unlocked") }
            r1.addView(premiumBtn, wrapWrap())
        }
        bar.addView(r1, matchWrap())
        return bar
    }

    /** Models page: select/load model + backend selection. */

    /** Models page: a single panel wrapped in a vertically scrollable ScrollView */
    private fun modelsPane(inner: View) = ModelsHelpers.modelsPane(this, inner)

    /** "Chat model" sub-tab style (one size smaller than the top tabs, matching the draw page's result/process) */
    private fun modelsSubTab(t: String) = ModelsHelpers.modelsSubTab(this, t)

    /** Switch sub-page within the "chat model" page: 0=model 1=API */
    private fun switchChatSubPage(p: Int) {
        if (!::modelsPage.isInitialized) return
        ModelsHelpers.switchSubPage(
            pane0 = chatModelPane,
            pane1 = chatApiPane,
            tab0 = chatSubTabModel,
            tab1 = chatSubTabApi,
            selected = p,
            pal = pal,
        )
        if (p == 1) refreshApiUi()
    }

    /** Switch sub-page within the "draw model" page: 0=model 1=API */
    private fun switchDrawSubPage(p: Int) {
        if (!::modelsPage.isInitialized) return
        ModelsHelpers.switchSubPage(
            pane0 = drawModelPane,
            pane1 = drawApiPane,
            tab0 = drawSubTabModel,
            tab1 = drawSubTabApi,
            selected = p,
            pal = pal,
        )
        if (p == 1) refreshDrawApiUi()
    }

    /** Top tab style for the models page (consistent with the draw page) */
    private fun tabItem(t: String) = ModelsHelpers.tabItem(this, t)

    // ==================== API services (chat and draw, identical structure) ====================

    /** Delegated to [ApiBlockBuilder]: the actual widget construction lives there,
     * here we only wire up the Activity's actions.
     */
    private fun buildApiBlock(
        role: ApiServer.Role,
        extraAfterAddress: List<View> = emptyList(),
        extraNotes: List<View> = emptyList(),
    ): ApiBlockView = ApiBlockBuilder(
        activity = this,
        ui = ui,
        pal = pal,
        chatPrefs = chatPrefs(),
        onCopyAddress = { copyApiAddress(it) },
        onNewKey = { newApiKey(it) },
        onClearKey = { clearApiKey(it) },
        onToggle = { toggleApi(it) },
        onRefreshSurfaces = { refreshApiSurfaces() },
    ).build(role, extraAfterAddress, extraNotes)

    // ---- API logic: extracted to ApiController.kt; here we only do compatibility delegation (ModelsHost/buildApiBlock unchanged) ----
    private fun apiPort(role: ApiServer.Role): Int = apiController.apiPort(role)
    private fun refreshApiBlock(role: ApiServer.Role, b: ApiBlockView?) {
        apiController.refreshApiBlock(role, b)
    }

    private fun refreshApiUi() { apiController.refreshApiUi() }

    private fun refreshDrawApiUi() { apiController.refreshDrawApiUi() }

    private fun refreshApiSurfaces() { apiController.refreshApiSurfaces() }

    private fun copyApiAddress(role: ApiServer.Role) { apiController.copyApiAddress(role) }

    private fun newApiKey(role: ApiServer.Role) { apiController.newApiKey(role) }

    private fun clearApiKey(role: ApiServer.Role) { apiController.clearApiKey(role) }

    private fun afterApiConfigChanged(role: ApiServer.Role) { apiController.afterApiConfigChanged(role) }

    private fun toggleApi(role: ApiServer.Role) { apiController.toggleApi(role) }

    /** Switch the models page's "chat model / draw model / tagger model" */
    override fun switchModelsTab(index: Int) {
        if (::modelsPage.isInitialized) modelsPager.setCurrentItem(index, true)
        highlightModelsTabs(index)
        when (index) {
            0 -> { refreshSavedModels(); visionManager?.refreshMmprojUi(); refreshApiUi() }
            1 -> { refreshDrawModelsInternal(); refreshLoraUi(); refreshDrawApiUi() }
            2 -> refreshTaggerUi()
        }
    }

    /** Sync the models page tab highlight; also updated during gesture swiping */
    override fun highlightModelsTabs(selected: Int) {
        if (!::modelsPage.isInitialized) return
        ModelsHelpers.highlightTabs(
            tabs = listOf(modelsTabChat, modelsTabDraw, modelsTabTag),
            selected = selected,
            pal = pal,
        )
    }

    /** Models page view pager adapter: reuses the three already-built panes, no rebuild */
    private class ModelsPaneAdapter(private val panes: List<View>) : PagerAdapter() {
        override fun getCount() = panes.size
        override fun isViewFromObject(view: View, obj: Any) = view === obj
        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val v = panes[position]
            (v.parent as? ViewGroup)?.removeView(v)
            container.addView(v)
            return v
        }

        override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
            (obj as? View)?.let { container.removeView(it) }
        }
    }

    /** About page: character art + description. */
    /** Pestaña de ajustes: vive en SettingsPage.kt */
    private fun buildSettingsPage(): View = SettingsPage(this).build()

    /** Crea el host exhibido a ModelsPage (ViewController/Page pattern) */
    /** Contrato que el importer usa para tocar la UI. */
    private val drawFileImportHost: DrawFileImportHost = object : DrawFileImportHost {
        // Todas las referencias al Activity van calificadas con this@MainActivity:
        // dentro de un objeto anonimo, un `this` sin calificar es el propio objeto.
        override fun setBusy(busy: Boolean) = this@MainActivity.applyBusy(busy)
        override fun setDrawBusy(busy: Boolean) = this@MainActivity.setDrawBusy(busy)
        override fun showDrawProgress(show: Boolean) = this@MainActivity.showDrawProgress(show)
        override fun setStatus(text: String, color: Int) = this@MainActivity.setStatus(text, color)
        override fun toast(msg: String) = this@MainActivity.toast(msg)
        override fun busyMessage(): String = getString(R.string.s_131)
        override fun errorMessage(): String = getString(R.string.s_158)
        override fun okColor(): Int = C_OK
        override fun errColor(): Int = C_ERR
        override fun warnColor(): Int = C_WARN
    }

    /**
     * Importacion de ficheros de dibujo (LoRA / modelo del tagger / tabla de tags).
     * Unifica los tres handlers que eran copias del mismo codigo.
     */
    private val drawFileImporter: DrawFileImporter<Uri> = DrawFileImporter(scope, drawFileImportHost)

    private fun createModelsPageHost(): ModelsHost {
        return object : ModelsHost {
            override val activity: Activity get() = this@MainActivity
            override val filesDir: File get() = this@MainActivity.filesDir
            override val scope: CoroutineScope get() = this@MainActivity.scope
            override val ui: UiWidgets get() = this@MainActivity.ui
            override val palette: YumenodeTheme get() = this@MainActivity.pal
            override val chatParams: ChatParams get() = this@MainActivity.chatParams
            override val pendingDrawCrash: Boolean get() = this@MainActivity.pendingDrawCrash
            override val pendingDrawCrashStage: String? get() = this@MainActivity.pendingDrawStage
            override fun setStatus(text: String, color: Int) { this@MainActivity.setStatus(text, color) }
            override fun takeCrashLog(): String? = this@MainActivity.takeCrashLog()
            override fun toast(msg: String) { this@MainActivity.toast(msg) }
            override fun onPickModel() = this@MainActivity.pickModelFile()
            override fun onToggleLoad() = this@MainActivity.toggleLoad()
            override fun onResetChatParams() = this@MainActivity.resetChatParams()
            override fun onSaveChatParams() = this@MainActivity.saveChatParams()
            override fun onShowDrawTypeMenu() = this@MainActivity.showDrawTypeMenu()
            override fun onToggleDrawSlots() = this@MainActivity.toggleDrawSlots()
            override fun onDrawToggleClickRequested() = this@MainActivity.onDrawToggleClick()
            override fun onPickLoraSource() = this@MainActivity.pickLoraSource()
            override fun onPickTaesdSource() = this@MainActivity.pickTaesdSource()
            override fun onPickLoraFile() = this@MainActivity.pickLoraFile()
            override fun onConfirmDeleteLora() = this@MainActivity.confirmDeleteLora()
            override fun onPickTaggerOnnx() = this@MainActivity.pickTaggerOnnx()
            override fun onPickTaggerCsv() = this@MainActivity.pickTaggerCsv()
            override fun onTaggerToggleClickRequested() = this@MainActivity.onTaggerToggleClick()
            override fun onSwitchChatSubPage(p: Int) = this@MainActivity.switchChatSubPage(p)
            override fun onSwitchDrawSubPage(p: Int) = this@MainActivity.switchDrawSubPage(p)
            override fun onSwitchModelsTab(p: Int) = this@MainActivity.switchModelsTab(p)
            override fun onRefreshApiUi() = this@MainActivity.refreshApiUi()
            override fun onRefreshDrawApiUi() = this@MainActivity.refreshDrawApiUi()
            override fun onRefreshLoraUi() = this@MainActivity.refreshLoraUi()
            override fun onRefreshDrawModels() = this@MainActivity.refreshDrawModelsInternal()
            override fun onRefreshTaggerUi() = this@MainActivity.refreshTaggerUi()
            override fun onRefreshSavedModels() = this@MainActivity.refreshSavedModels()
            override fun onLoadChatParamsIntoUi() = this@MainActivity.loadChatParamsIntoUiInternal()
            override fun onBuildApiBlock(role: ApiServer.Role, extraAfterAddress: List<View>, extraNotes: List<View>): ApiBlockView = this@MainActivity.buildApiBlock(role, extraAfterAddress, extraNotes)
            override fun onSetDrawUseGpu(value: Boolean) { this@MainActivity.drawPageInternal?.useGpu = value }
            override val chatEmbeddings: Boolean
                get() = this@MainActivity.prefBool(KEY_CHAT_EMBEDDINGS, false)
            override fun onSetChatEmbeddings(value: Boolean) {
                this@MainActivity.prefPutBool(KEY_CHAT_EMBEDDINGS, value)
                this@MainActivity.toast(getString(R.string.s_emb_need_reload))
            }
            override fun onPickMmprojFile() { this@MainActivity.visionManager?.pickMmprojFile() }
            override fun onRefreshMmprojUi() { this@MainActivity.visionManager?.refreshMmprojUi() }
            override fun onProgress(pct: Int?) {
                if (pct == null) {
                    this@MainActivity.progressBarInternal.visibility = View.GONE
                } else {
                    this@MainActivity.progressBarInternal.visibility = View.VISIBLE
                    this@MainActivity.progressBarInternal.progress = pct
                }
            }
            override fun onVisionStatus(text: String, color: Int) { this@MainActivity.setStatus(text, color) }
            override fun onVisionError(msg: String) { this@MainActivity.toast(msg) }
            override fun onModelChanged() { if (this@MainActivity.llamaModel != null) this@MainActivity.toast(this@MainActivity.getString(R.string.s_290)) }
        }
    }

    // La pestaña de descargas vive en DownloadPage.kt


    /**
     * Collapsible section in the settings page: returns a container you can fill with content.
     * The header row (title + arrow) expands/collapses on tap.
     */
    /**
     * Collapsible section.
     *
     * @param expanded default expanded state on first use (before any pref is saved)
     * @param prefKey  preference key that remembers the expanded state; **null = not persisted** (always falls back to [expanded]).
     *                 Without a key, closing the section and returning later re-expands it - which was the old problem on the "about" page:
     *                 state lived only in the in-memory `open` variable.
     */


    /** Clickable link on the about page (opens the system browser) */


    // ---- Bottom navigation ----

    override fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C_CARD)
            elevation = dp(6).toFloat()
        }
        val items = listOf(
            Triple("💬", getString(R.string.s_074), 0),
            Triple("🧠", getString(R.string.s_124), 1),
            Triple("🎨", getString(R.string.s_150), 2),
            Triple("⬇️", getString(R.string.s_454), 3),
            Triple("🖼️", getString(R.string.s_499), 4),
            Triple("⚙️", getString(R.string.s_381), 5),
        )
        for ((icon, label, idx) in items) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(0, dp(7), 0, dp(7))
                setOnClickListener { switchTab(idx) }
                contentDescription = label
            }
            val iconTv = TextView(this).apply { text = icon; textSize = 17f; gravity = Gravity.CENTER }
            val labelTv = TextView(this).apply {
                text = label
                textSize = 10f
                maxLines = 2
                gravity = Gravity.CENTER
                setPadding(0, dp(1), 0, 0)
            }
            item.addView(iconTv, matchWrap())
            item.addView(labelTv, matchWrap())
            navIcons += iconTv
            navLabels += labelTv
            nav.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return nav
    }

    override fun switchTab(index: Int) {
        currentTab = index
        tabChat.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabModels.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabDraw.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabDownload.visibility = if (index == 3) View.VISIBLE else View.GONE
        tabGallery.visibility = if (index == 4) View.VISIBLE else View.GONE
        tabSettings.visibility = if (index == 5) View.VISIBLE else View.GONE
        // When switching to the models page, refresh the draw model / LoRA lists so state isn't stale after import
        if (index == 1) { refreshDrawModelsInternal(); refreshLoraUi(); refreshTaggerUi(); refreshApiUi(); refreshDrawApiUi() }
        // Refresh the list when switching to the downloads page
        if (index == 3) { downloadPage?.refresh() }
        if (index == 4) { galleryPage?.refresh() }
        inputBar.visibility = if (index == 0) View.VISIBLE else View.GONE
        val isChat = index == 0
        appBarMenu.visibility = if (isChat) View.VISIBLE else View.INVISIBLE
        appBarPlus.visibility = if (isChat) View.VISIBLE else View.INVISIBLE
        for (i in navIcons.indices) {
            val c = if (i == currentTab) C_PRIMARY else C_SUBTEXT
            navIcons[i].setTextColor(c)
            navLabels[i].setTextColor(c)
        }
        if (index == 0) chatScrollManager?.scrollToBottom()
    }

    // ---- Left chat drawer ----

    /** Filtro del historial de chats (se aplica al dibujar la lista). */
    private var drawerQuery: String = ""
    /** Contenedor solo de la lista (el buscador no se reconstruye al filtrar). */
    private lateinit var drawerList: LinearLayout

    override fun buildDrawer(rootFrame: FrameLayout) {
        drawerMask = View(this).apply {
            setBackgroundColor(C_SCRIM)
            visibility = View.GONE
            alpha = 0f
            setOnClickListener { closeDrawer() }
        }
        rootFrame.addView(drawerMask, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_CARD)
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        drawerPanel.addView(TextView(this).apply {
            text = getString(R.string.s_074)
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(C_PRIMARY)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }, matchWrap())

        // Buscador del historial: filtra por título y por texto de los turnos.
        val search = EditText(this).apply {
            hint = getString(R.string.s_search_chats)
            textSize = 13f
            setSingleLine(true)
            setTextColor(C_TEXT)
            setHintTextColor(C_SUBTEXT)
            contentDescription = getString(R.string.s_search_chats)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(editable: android.text.Editable?) {
                    val q = editable?.toString().orEmpty()
                    if (q != drawerQuery) {
                        drawerQuery = q
                        renderDrawerList()
                    }
                }
                override fun beforeTextChanged(ch: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(ch: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        drawerPanel.addView(search, matchWrap())

        val sv = ScrollView(this)
        drawerBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        sv.addView(drawerBody, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        drawerPanel.addView(sv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        drawerPanel.addView(TextView(this).apply {
            text = "Yumenode AI"
            textSize = 11f
            setTextColor(C_SUBTEXT)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(14))
        }, matchWrap())

        val w = dp(drawerWidthDp)
        rootFrame.addView(drawerPanel, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
        })
        drawerPanel.translationX = -w.toFloat()
    }

    override fun refreshDrawer() {
        val search = drawerPanel.getChildAt(1) as? EditText
        if (search != null && search.text?.toString().orEmpty() != drawerQuery) {
            search.setText(drawerQuery)
        }
        renderDrawerList()
    }

    /**
     * Pinta la lista de chats aplicando [drawerQuery].
     *
     * Coincide por título y por el texto de cualquier turno (pregunta o
     * respuesta); en ese caso se muestra un fragmento del turno encontrado.
     */
    private fun renderDrawerList() {
        drawerBody.removeAllViews()
        drawerBody.addView(ui.actionButton(getString(R.string.s_201)) { closeDrawer(); newSession() },
            matchWrap().apply { setMargins(dp(12), dp(4), dp(12), dp(10)) })

        val q = drawerQuery.trim()
        val matches = ChatSearch.filter(sessions.sortedByDescending { it.id }, q)

        if (q.isNotEmpty()) {
            val countText = if (matches.size == 1) getString(R.string.s_a11y_results_one)
            else getString(R.string.s_a11y_results, matches.size)
            drawerBody.addView(ui.hintText(countText),
                matchWrap().apply { setMargins(dp(16), 0, dp(16), dp(6)) })
        }
        if (matches.isEmpty()) {
            drawerBody.addView(ui.hintText(getString(R.string.s_no_results)),
                matchWrap().apply { setMargins(dp(16), dp(8), dp(16), dp(8)) })
            return
        }

        for (m in matches) {
            val s = m.session
            val snippet = m.snippet
            val isCur = s === current
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(12))
                isClickable = true
                contentDescription = s.title
                if (isCur) setBackgroundColor(C_PRIMARY_SOFT)
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = s.title
                textSize = 14f
                setTextColor(if (isCur) C_PRIMARY else C_TEXT)
                maxLines = 1
            }, matchWrap())
            if (snippet != null) {
                texts.addView(TextView(this).apply {
                    text = snippet
                    textSize = 11f
                    maxLines = 2
                    setTextColor(C_SUBTEXT)
                }, matchWrap())
            }
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = getString(R.string.v_001, (s.turns.size))
                textSize = 11f
                setTextColor(C_SUBTEXT)
                setPadding(0, 0, dp(6), 0)
            }, wrapWrap())
            row.addView(TextView(this).apply {
                text = "🗑"
                textSize = 14f
                setPadding(dp(8), dp(2), dp(8), dp(2))
                isClickable = true
                contentDescription = getString(R.string.s_a11y_delete)
                setOnClickListener { confirmDeleteSession(s) }
            }, wrapWrap())
            row.setOnClickListener { closeDrawer(); switchTo(s) }
            drawerBody.addView(row, matchWrap())
            drawerBody.addView(View(this).apply { setBackgroundColor(C_DIVIDER_LINE) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    setMargins(dp(16), 0, 0, 0)
                })
        }
    }

    override fun openDrawer() {
        if (drawerOpen) return
        refreshDrawer()
        drawerMask.visibility = View.VISIBLE
        drawerMask.alpha = 0f
        drawerPanel.visibility = View.VISIBLE
        drawerPanel.translationX = -dp(drawerWidthDp).toFloat()
        drawerPanel.animate().translationX(0f).setDuration(220).start()
        drawerMask.animate().alpha(1f).setDuration(220).start()
        drawerOpen = true
    }

    override fun closeDrawer() {
        if (!drawerOpen) return
        drawerPanel.animate().translationX(-dp(drawerWidthDp).toFloat()).setDuration(180)
            .withEndAction { drawerPanel.visibility = View.GONE }.start()
        drawerMask.animate().alpha(0f).setDuration(180)
            .withEndAction { drawerMask.visibility = View.GONE }.start()
        drawerOpen = false
    }


    // ================= Chat image sending (.litertlm multimodal) =================
    // Lógica de adjuntos extraída a AttachController.kt; delegación de compatibilidad.
    override fun probeVision(path: String): Boolean = attachController.probeVision(path)

    private fun onAttachClick() = attachController.onAttachClick()

    private fun withLegacyStorage(action: () -> Unit) = attachController.withLegacyStorage(action)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == GalleryPage.REQ_PERM) {
            galleryPage?.refresh()
            return
        }
        if (attachController.onStoragePermissionResult(requestCode, grantResults)) return
    }

    /** Bitmap -> JPEG bytes (extracted to AttachController). */
    private fun bitmapToJpeg(bmp: Bitmap, quality: Int = 88): ByteArray = attachController.bitmapToJpeg(bmp, quality)

    /** Persist user-sent images (JPEG) to disk, restored with the session. */
    internal fun writeChatJpeg(bmp: Bitmap, name: String): Boolean = try {
        val dir = File(filesDir, "chatimg").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        true
    } catch (_: Throwable) {
        false
    }

    /** Encode pending file contents as JSON into the turn, so "regenerate" can restore the attachments */
    /** Install/update time (used to confirm which build is installed) */
    override fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Throwable) {
        ""
    }

    override fun installedAt(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(pi.lastUpdateTime))
    } catch (_: Throwable) {
        ""
    }

    /**
     * Estimate LiteRT session input tokens: system prompt + history + this round's content.
     * File text sent in earlier rounds also stays in the engine's context, so include it.
     */
    /** Detect "context overflow" errors (LiteRT status code 3 / llama.cpp context full) */
    internal fun isContextOverflow(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        return m.contains("too long") || m.contains("maximum number of tokens") ||
            m.contains("out of context") ||
            (m.contains("context") && (m.contains("full") || m.contains("exceed")))
    }

    // ================= Chat model params (adjustable on the models page) =================

    /** Shared SharedPreferences (shared by chat / UI / API settings) */
    override fun chatPrefs() = getSharedPreferences("ponko", MODE_PRIVATE)

    /** Save on models-page input (stored under the current model name); blank/invalid values fall back to defaults */
    private fun saveChatParams() {
        if (chatParams.uiLoading) return
        fun i(ed: EditText?, def: Int) = ed?.text?.toString()?.trim()?.toIntOrNull() ?: def
        fun f(ed: EditText?, def: Float) = ed?.text?.toString()?.trim()?.toFloatOrNull() ?: def
        chatParams.save(
            ctx = i(chatCtxEdit, ChatParams.DEF_CHAT_CTX),
            maxOut = i(chatMaxOutEdit, ChatParams.DEF_CHAT_MAXOUT),
            temp = f(chatTempEdit, ChatParams.DEF_CHAT_TEMP),
            topK = i(chatTopKEdit, ChatParams.DEF_CHAT_TOPK),
            topP = f(chatTopPEdit, ChatParams.DEF_CHAT_TOPP),
            repPenalty = f(chatRepEdit, ChatParams.DEF_CHAT_REP),
            budget = i(chatBudgetEdit, ChatParams.DEF_CHAT_BUDGET),
            seed = i(chatSeedEdit, ChatParams.DEF_CHAT_SEED),
        )
    }

    /** Backfill the current model's params into the models page inputs (called on UI build / model switch) */
    private fun loadChatParamsIntoUiInternal() {
        chatParams.setUiLoading(true)
        chatCtxEdit?.setText(chatParams.ctx().toString())
        chatMaxOutEdit?.setText(chatParams.maxOut().toString())
        chatTempEdit?.setText(chatParams.temp().toString())
        chatTopKEdit?.setText(chatParams.topK().toString())
        chatTopPEdit?.setText(chatParams.topP().toString())
        chatRepEdit?.setText(chatParams.repPenalty().toString())
        chatBudgetEdit?.setText(chatParams.budget().toString())
        chatSeedEdit?.setText(chatParams.seed().toString())
        chatParams.setUiLoading(false)
        val n = chatParams.ownerLabel()
        chatParamsOwnerTv?.text =
            if (n == null) getString(R.string.s_319) else getString(R.string.s_318, n)
    }

    /** Reset the current model's params to built-in defaults (context etc. only take effect after reloading the model) */
    private fun resetChatParams() {
        chatParams.setUiLoading(true)
        chatParams.resetDefaults()
        chatParams.setUiLoading(false)
        loadChatParamsIntoUiInternal()
        toast(getString(R.string.s_317))
    }

    /** Param cell: sub-label + numeric input + hint; keep stores the input into the field */
    private fun numField(label: String, def: String, hint: String, keep: (EditText) -> Unit): LinearLayout =
        UiHelpers.numField(
            context = this,
            label = label,
            value = def,
            hint = hint,
            keep = keep,
            onChanged = { saveChatParams() },
            palette = { pal },
            ui = ui,
        )

    /** Two param cells in one row */
    private fun twoCols(a: View, b: View): LinearLayout =
        UiHelpers.twoCols(context = this, first = a, second = b)

    // ================= small view factories =================

    // ================= status =================

    override fun setStatus(text: String, color: Int) {
        statusTv.text = text
        statusDot.background = rounded(color, 99)
    }

    // ================= model loading =================

    private fun pickModelFile() = drawModelsController.pickModelFile()

    /** GGUF multimodal: pick an mmproj (vision projection) file, .gguf */
    // pickMmprojFile / copyMmprojToPrivate / refreshMmprojUi / mmprojChip / confirmDeleteMmproj
    // → extraídos a VisionManager

    private fun showDrawTypeMenu() = drawModelsController.showDrawTypeMenu()

    private fun pickDrawSlot(key: String) = drawModelsController.pickDrawSlot(key)

    private fun refreshDrawSlots() = drawModelsController.refreshDrawSlots()

    private fun toggleDrawSlots(expand: Boolean? = null) = drawModelsController.toggleDrawSlots(expand)

    private fun pickDrawModelFile() = drawModelsController.pickDrawModelFile()

    private fun pickLoraFile() = drawModelsController.pickLoraFile()

    private fun pickTaggerOnnx() = drawModelsController.pickTaggerOnnx()

    private fun pickTaggerCsv() = drawModelsController.pickTaggerCsv()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == GalleryPage.REQ_DELETE) {
            galleryPage?.refresh()
            return
        }
        if (drawPageInternal?.onActivityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)

        // La camara se atiende siempre: hay que soltar pendingCameraUri aunque se cancele.
        if (requestCode == REQ_TAKE_PHOTO) {
            attachController.handleTakePhotoResult(resultCode)
            return
        }
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK_MODEL -> data?.data?.let { copyModelToPrivate(it) }
            REQ_PICK_MMPROJ -> data?.data?.let { visionManager?.copyMmprojToPrivate(it) }
            AttachmentHandler.REQ_PICK_CHAT_IMAGE -> attachController.handlePickedChatImages(data)
            AttachmentHandler.REQ_PICK_FILE -> attachController.handlePickedFiles(data)
            REQ_PICK_DRAW_MODEL -> handlePickedDrawModel(data)
            REQ_PICK_LORA -> handlePickedLora(data)
            REQ_TAGGER_ONNX -> handlePickedTaggerOnnx(data)
            REQ_TAGGER_CSV -> handlePickedTaggerCsv(data)
        }
    }

    private fun handlePickedDrawModel(data: Intent?) {
        val uri = data?.data ?: return
        val dpg = drawPageInternal ?: return
        val slot = pendingSlotKeyInternal
        pendingSlotKeyInternal = null
        applyBusy(true)
        setDrawBusy(true)
        showDrawProgress(true)
        setStatus(getString(R.string.s_131), C_WARN)
        scope.launch {
            val err = dpg.importSetFile(
                uri,
                slot,
                onStage = { stage -> drawModelStatusInternal?.text = stage },
                onProgress = { done, total -> updateDrawProgress(done, total) }
            )
            applyBusy(false)
            setDrawBusy(false)
            showDrawProgress(false)
            if (err == null) {
                drawSetIdInternal = dpg.activeSetId()
                val set = dpg.activeSetOrSelected()
                drawModelStatusInternal?.text = dpg.modelSummary()
                setStatus(getString(R.string.s_161), C_OK)
                if (set != null && set.kind == "multi" && slot == null) {
                    toast(getString(R.string.s_370))
                    // Newly imported multi-file main model: auto-expand the slot panel so companion files can be added
                    drawSlotsExpandedInternal = true
                } else {
                    toast(getString(R.string.s_091))
                }
            } else {
                drawModelStatusInternal?.text = err
                setStatus(getString(R.string.s_158), C_ERR)
                toast(err)
            }
            refreshDrawModelsInternal()
        }
    
    }

    private fun handlePickedLora(data: Intent?) {
        val uri = data?.data ?: return
        val dpg = drawPageInternal ?: return
        drawFileImporter.import(
            uri = uri,
            importFile = { u, onStage, onProgress -> dpg.importLoraFile(u, onStage, onProgress) },
            onStage = { stage -> loraStatusTvInternal?.text = stage },
            onProgress = { done, total -> updateDrawProgress(done, total) },
            onOk = getString(R.string.s_220),
            onToast = getString(R.string.s_220),
            refresh = { refreshLoraUi() },
        )
    }

    private fun handlePickedTaggerOnnx(data: Intent?) {
        val uri = data?.data ?: return
        val dpg = drawPageInternal ?: return
        drawFileImporter.import(
            uri = uri,
            importFile = { u, onStage, onProgress -> dpg.importTaggerModel(u, onStage, onProgress) },
            onStage = { stage -> taggerStatusTvInternal?.text = stage },
            onProgress = { done, total -> updateDrawProgress(done, total) },
            onOk = getString(R.string.s_267),
            onToast = getString(R.string.s_269),
            refresh = { refreshTaggerUi() },
        )
    }

    private fun handlePickedTaggerCsv(data: Intent?) {
        val uri = data?.data ?: return
        val dpg = drawPageInternal ?: return
        drawFileImporter.import(
            uri = uri,
            importFile = { u, onStage, onProgress -> dpg.importTaggerCsv(u, onStage, onProgress) },
            onStage = { stage -> taggerStatusTvInternal?.text = stage },
            onProgress = { done, total -> updateDrawProgress(done, total) },
            onOk = getString(R.string.s_268),
            onToast = getString(R.string.s_269),
            refresh = { refreshTaggerUi() },
        )
    }

    private fun copyModelToPrivate(uri: Uri) = drawModelsController.copyModelToPrivate(uri)



    // ================= saved models =================

    private fun refreshSavedModels() = drawModelsController.refreshSavedModels()

    // ================= Copied draw models =================

    /** Combined draw model button: unloaded -> load; loaded -> unload. */
    private fun onDrawToggleClick() {
        val dpg = drawPageInternal
        if (dpg == null) {
            toast(getString(R.string.s_167))
            return
        }
        if (dpg.isReady()) {
            dpg.unloadModel()
            updateThinkEnabled()
            drawModelStatusInternal?.text = dpg.modelSummary()
            setStatus(getString(R.string.s_116), C_IDLE)
            toast(getString(R.string.s_160))
            refreshDrawToggleInternal()
            refreshDrawApiUi()   // Model unloaded; the draw API block must revert to "draw model not loaded"
            return
        }
        if (!dpg.hasModel()) {
            toast(getString(R.string.s_185))
            return
        }
        scope.launch {
            drawModelStatusInternal?.text = getString(R.string.s_128)
            dpg.useGpu = (backendSpinner.selectedItem.toString() == "GPU")
            val err = dpg.loadSelectedSet()
            drawModelStatusInternal?.text = if (err == null) dpg.modelSummary() else err
            if (err == null) {
                drawSetIdInternal = dpg.activeSetId()
                dpg.llmLoaded = false
                updateThinkEnabled()
                refreshDrawModelsInternal()
                setStatus(getString(R.string.s_162), C_OK)
                toast(getString(R.string.s_159))
            } else {
                setStatus(getString(R.string.s_156), C_ERR)
                toast(err)
            }
            refreshDrawToggleInternal()
        }
    }

    /** Update the combined button's label and colors based on whether the draw model is loaded.
     *  Consistent with the chat model: loading = solid blue; loaded = light gray outline (so it isn't mistaken for the primary action). */
    private fun refreshDrawToggleInternal() {
        val b = drawToggleBtn ?: return
        if (drawPageInternal?.isReady() == true) {
            b.text = getString(R.string.s_063)
            b.background = rounded(C_SOFT_BTN2, 12,
                strokeDp = 1, strokeColor = C_BORDER)
            b.setTextColor(C_TEXT)
        } else {
            b.text = getString(R.string.s_061)
            b.background = rounded(C_PRIMARY, 12)
            b.setTextColor(Color.WHITE)
        }
    }

    /** List existing draw model sets (tap to select . long-press to delete). Single- and multi-file sets are both here. */
    private fun refreshDrawModelsInternal() {
        refreshDrawToggleInternal()
        val box = drawSavedContainer ?: return
        box.removeAllViews()
        val dpg = drawPageInternal
        val sets = dpg?.listSets().orEmpty()
        if (sets.isEmpty()) {
            box.visibility = View.GONE
            refreshDrawSlots()
            return
        }
        box.visibility = View.VISIBLE
        box.addView(TextView(this).apply {
            text = getString(R.string.s_090)
            textSize = 12f
            setTextColor(C_SUBTEXT)
            setPadding(0, dp(4), 0, 0)
        }, matchWrap())

        dpg?.ensureActiveSet()
        val activeId = dpg?.activeSetId()
        val loadedName = dpg?.currentMainName()
        for (s in sets) {
            val isSel = s.id == activeId || s.id == drawSetIdInternal
            val chip = TextView(this).apply {
                // List styling matches the old version: filename + role + quantization + size (multi-file sets just add one tag; no categorization/family display)
                val isMain = loadedName != null && dpg?.isReady() == true && loadedName == s.name
                val role = if (isMain) getString(R.string.s_099)
                else if (s.kind == DrawPage.KIND_MULTI) getString(R.string.s_346)
                else ""
                val suffix = if (role.isEmpty()) "" else "　[$role]"
                val q = s.mainFile()?.let { dpg?.quantOf(it) }
                val qTag = if (q != null) "　[$q]" else ""
                text = "▶ ${s.mainFile()?.name ?: s.name}$suffix$qTag　${UiHelpers.fmtSize(s.sizeBytes())}"
                textSize = 12.5f
                setTextColor(if (isSel) C_PRIMARY else C_TEXT)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(if (isSel) C_PRIMARY_SOFT else C_CHIP_BG, 10,
                    strokeDp = if (isSel) 1 else 0, strokeColor = C_PRIMARY)
                isClickable = true
            }
            chip.setOnClickListener { selectDrawSet(s) }
            chip.setOnLongClickListener { confirmDeleteDrawSet(s); true }
            box.addView(chip, matchWrap().apply { topMargin = dp(4) })
        }
        refreshDrawSlots()
    }

    private fun selectDrawSet(s: ModelSet) = drawModelsController.selectDrawSet(s)

    // ================= LoRA =================

    private fun refreshLoraUi() = drawModelsController.refreshLoraUi()

    private fun pickLoraSource() = drawModelsController.pickLoraSource()

    private fun pickTaesdSource() = drawModelsController.pickTaesdSource()

    private fun startTaesdDownload(useMirror: Boolean) = drawModelsController.startTaesdDownload(useMirror)

    private fun startLoraDownload(useMirror: Boolean) = drawModelsController.startLoraDownload(useMirror)

    private fun confirmDeleteLora(target: File? = null) = drawModelsController.confirmDeleteLora(target)

    private fun confirmDeleteDrawSet(s: ModelSet) = drawModelsController.confirmDeleteDrawSet(s)

    private fun selectSavedModel(f: File) = drawModelsController.selectSavedModel(f)

    private fun confirmDeleteModel(f: File) = drawModelsController.confirmDeleteModel(f)

    /**
     * Activa el log nativo de llama.cpp (ggml). Sin esto, un fallo en el
     * backend sale como un crash sin contexto: el .so no dice nada.
     */
    override fun enableLlamaLog() {
        try {
            net.ladenthin.llama.LlamaModel.setLogger(
                net.ladenthin.llama.args.LogFormat.TEXT
            ) { _, msg ->
                Log.i("LlamaCpp", msg)
            }
        } catch (_: Throwable) {
            // Si el runtime no lo soporta, se sigue sin log.
        }
    }

    private fun toggleLoad() {
        modelLoadController.toggleLoad()
    }


    // ---- Multi-session management ----

    /** Create a new session (old sessions stay in the list). */
    private fun newSession() = chatSessionController.newSession()

    private fun switchTo(s: ChatSession) = chatSessionController.switchTo(s)

    private fun confirmDeleteSession(s: ChatSession) = chatSessionController.confirmDeleteSession(s)

    // ---- Session switch / rebuild ----

    /**
     * GGUF inference params: include chat history + cache_prompt(true) + fixed slot.
     * llama.cpp matches this round's prompt against the cached KV prefix in the slot, computing only the new part -
     * this is what keeps long conversations from re-prefilling.
     */
    // buildInferenceParams / buildMultimodalParams / configFor → InferenceParamsBuilder

    // buildInferenceParams / buildMultimodalParams / configFor → InferenceParamsBuilder

    /**
     * Rebuild the session after the thinking toggle changes.
     * LiteRT-LM's thinking marker is written into the prompt template at session creation;
     * once the session is running, changing only the per-call ThinkingConfig has no effect (especially off->on),
     * so the session must be rebuilt for the new mode; history is kept via initialMessages.
     */
    internal fun rebuildConversation(silent: Boolean = false, dropLast: Boolean = false) = chatSessionController.rebuildConversation(silent, dropLast)

    /** Rebuild the LiteRT session (if the model is loaded) and re-render the chat area with the current session's content. */
    private fun restoreSession(showHint: Boolean = true) = chatSessionController.restoreSession(showHint)

    /**
     * Regenerate a given round's answer.
     * Later rounds depend on this round's context, so they must be dropped too; then the round is re-fed for generation.
     */
    internal fun regenerate(turn: QaTurn) = chatSessionController.regenerate(turn)

    /** Re-render an existing Q&A round into the chat area (history backfill). */
    /** The thinking toggle is meaningless in draw mode (no chat model involved); gray it out and disable it */
    override fun updateThinkEnabled() {
        // May be triggered by a callback on the IO thread; setEnabled drives a ripple animation, so it must go back to the main thread
        runOnUiThread {
            val visible = modelSupportsThinking
            val enabled = visible && !drawMode
            thinkCheck.visibility = if (visible) View.VISIBLE else View.GONE
            thinkCheck.isEnabled = enabled
            thinkCheck.alpha = if (enabled) 1f else 0.45f
        }
    }

    /** Heurística por nombre para saber si un modelo admite reasoning/thinking.
     *  No hay API fiable en litertlm para 'supportsThinking' (Capabilities solo
     *  expone inputModalities().vision), así que se basa en el nombre del archivo.
     *  Familias razonantes conocidas: DeepSeek-R1, Qwen3, Kimi/K1, etc. */
    override fun guessSupportsThinking(filename: String): Boolean {
        val n = filename.lowercase()
        return n.contains("r1") || n.contains("qwen3") || n.contains("thinking") ||
            n.contains("kimi") || n.contains("deepseek-reasoning")
    }

    /** Lee supportsThinking() real del modelo LiteRT (API 0.18+); si falla, usa la heurística. */
    override fun probeThinking(path: String): Boolean = try {
        ModelInfo.from(path).use {
            (it as? ModelInfo.Llm)?.supportsThinking()
                ?: guessSupportsThinking(path.substringAfterLast('/'))
        }
    } catch (_: Throwable) {
        guessSupportsThinking(path.substringAfterLast('/'))
    }

    private fun renderTurn(t: QaTurn) = chatSessionController.renderTurn(t)

    // ---- Persistence ----

    // ---- Persistence ----

    internal fun doDrawFromChat(prompt: String, dpg: DrawPage) {
        chatSessionController.doDrawFromChat(prompt, dpg)
    }

    /** Write an image to the app's private dir; returns whether it succeeded */
    private fun writeChatImage(bmp: Bitmap, name: String): Boolean = try {
        val dir = File(filesDir, "chatimg").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (_: Throwable) {
        false
    }

    private fun readChatImageImpl(name: String): Bitmap? = try {
        BitmapFactory.decodeFile(File(File(filesDir, "chatimg"), name).absolutePath)
    } catch (_: Throwable) {
        null
    }

    /** Insert an image into the AI bubble; tapping it shows a confirm dialog before saving to the gallery (previously a single tap saved, easy to mis-trigger) */
    /** Full-screen image view: two-finger zoom, drag, double-tap to zoom; tap to close when not zoomed, X top-right closes anytime. */
    private fun showImageFullscreen(bmp: Bitmap) {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ZoomImageView(this).apply { setImageBitmap(bmp) }
        iv.onTapAtFit = { dlg.dismiss() }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(0x66000000, 20)
            isClickable = true
            setOnClickListener { dlg.dismiss() }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(iv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(close, FrameLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(36)
                rightMargin = dp(16)
            })
        }
        dlg.setContentView(root)
        dlg.show()
    }

    /** Image long-press menu: save to gallery / quote / send to image-to-image / send to Tagger. */
    private fun showImageMenu(bmp: Bitmap, name: String) {
        val items = arrayOf(
            getString(R.string.s_045),
            getString(R.string.s_291),
            getString(R.string.s_225),
            getString(R.string.s_238)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_293))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveImageToGallery(bmp, name)
                    1 -> quoteImage(bmp)
                    2 -> { drawPageInternal?.sendImageToI2i(bmp); switchTab(2) }
                    else -> { drawPageInternal?.sendImageToTagger(bmp); switchTab(2) }
                }
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    /** Quote: put the image directly into the input bar as a pending attachment (not persisted; same as selecting it from the input bar). */
    private fun quoteImage(bmp: Bitmap) {
        if (engine == null && llamaModel == null) { toast(getString(R.string.s_273)); return }
        if (!visionOk) { toast(getString(R.string.s_272)); return }
        if (attachmentHandler.getPendingImages().size >= AttachmentHandler.MAX_ATTACH) { toast(getString(R.string.s_279)); return }
        attachmentHandler.addPendingImage(bmp, bitmapToJpeg(bmp))
        switchTab(0)
        toast(getString(R.string.s_292))
    }

    private fun attachImageBubble(ai: AiArea, bmp: Bitmap, name: String) {
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            isClickable = true
            isFocusable = false
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { showImageFullscreen(bmp) }
            setOnLongClickListener { showImageMenu(bmp, name); true }
        }
        ai.root.addView(iv, matchWrap())
        ai.root.addView(TextView(this).apply {
            text = getString(R.string.s_141)
            textSize = 11f
            setTextColor(C_SUBTEXT)
            setPadding(0, dp(4), 0, 0)
        }, matchWrap())
    }

    private fun saveImageToGallery(bmp: Bitmap, name: String) = withLegacyStorage { doSaveImageToGallery(bmp, name) }

    private fun doSaveImageToGallery(bmp: Bitmap, name: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(
                                MediaStore.Images.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/Yumenode AI"
                            )
                        }
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    )
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        true
                    } else false
                } catch (_: Throwable) {
                    false
                }
            }
            toast(if (ok) getString(R.string.s_084) else getString(R.string.s_046))
        }
    }



    internal fun extractText(m: Message): String = chatSessionController.extractText(m)

    /**
     * LiteRT's streaming fragment semantics vary across versions (may be incremental or cumulative).
     * Normalize to avoid cases like a repeated greeting being concatenated over and over:
     *   - identical to accumulated content → duplicate snapshot, discard
     *   new fragment starts with accumulated content → full snapshot, replace with the new fragment
     *   accumulated content ends with the new fragment → trailing duplicate, discard (ignore leading/trailing whitespace)
     *   - otherwise → append as an increment
     */
    internal fun mergeStreamDelta(cur: String, delta: String): String = chatSessionController.mergeStreamDelta(cur, delta)

    /** AI reply card: collapsible thinking section on top, Markdown answer below. */
    internal fun addAiArea(onRegen: (() -> Unit)? = null): AiArea = chatSessionController.addAiArea(onRegen)

    internal fun addUserBubble(text: String, images: List<Bitmap> = emptyList(), files: List<String> = emptyList()) = chatSessionController.addUserBubble(text, images, files)

    override fun addSystemHint(text: String) = chatSessionController.addSystemHint(text)

    // ================= helpers =================



    override fun applyBusy(b: Boolean) = chatSessionController.applyBusy(b)

    /** Disable the draw-model area's buttons during import/copy to prevent duplicate triggers. */
    override fun setDrawBusy(b: Boolean) {
        drawToggleBtn?.isEnabled = !b
        drawToggleBtn?.alpha = if (b) 0.5f else 1f
        drawImportBtn?.isEnabled = !b
        drawImportBtn?.alpha = if (b) 0.5f else 1f
        taggerOnnxBtn?.isEnabled = !b
        taggerOnnxBtn?.alpha = if (b) 0.5f else 1f
        taggerCsvBtn?.isEnabled = !b
        taggerCsvBtn?.alpha = if (b) 0.5f else 1f
        taggerLoadBtnInternal?.isEnabled = !b
        taggerLoadBtnInternal?.alpha = if (b) 0.5f else 1f
    }

    /** Disable the load button in the "chat model" area while busy. */
    private fun setMainBusy(b: Boolean) {
        loadButton.isEnabled = !b
        loadButton.alpha = if (b) 0.5f else 1f
    }

    private fun refreshTaggerUi() = drawModelsController.refreshTaggerUi()

    private fun refreshTaggerToggle() = drawModelsController.refreshTaggerToggle()

    private fun onTaggerToggleClick() = drawModelsController.onTaggerToggleClick()

    private fun listHeader(t: String) = drawModelsController.listHeader(t)

    private fun selectableChip(
        f: File,
        selected: Boolean,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ) = drawModelsController.selectableChip(f, selected, onLongClick, onClick)

    private fun showDrawProgress(show: Boolean) {
        val bar = drawProgress ?: return
        if (show) {
            bar.visibility = View.VISIBLE
            bar.progress = 0
            bar.isIndeterminate = false
        } else {
            bar.visibility = View.GONE
        }
    }

    private fun updateDrawProgress(done: Long, total: Long) {
        val bar = drawProgress ?: return
        if (total > 0) {
            bar.isIndeterminate = false
            bar.progress = ((done * 100 / total).toInt()).coerceIn(0, 100)
        } else {
            bar.isIndeterminate = true
        }
    }


    // ================= ModelLoadHost required overrides =================
    override val appCacheDir: File get() = File(getExternalFilesDir(null), "ai")

    override fun markModelLoaded(path: String) {
        chatParams.markModelLoaded(path)
    }

    override fun markModelUnloaded() {
        chatParams.markModelUnloaded()
    }
}
