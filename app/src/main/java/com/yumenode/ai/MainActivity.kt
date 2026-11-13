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

/**
 * On-device chat app that loads a Google LiteRT-LM (.litertlm) model from local
 * storage and runs it via com.google.ai.edge.litertlm.
 *
 * UI: flat modern look — deep surface app bar, card-style settings panel, chat bubbles,
 * bottom input bar, collapsible "thinking" section, and Markdown-rendered replies.
 */
class MainActivity : Activity(), SettingsHost, ThemeAliases, ApiHost, AttachHost, ChatSessionHost, DrawModelsHost, ShellHost, ModelLoadHost, BenchmarkHost, LocaleHost {

    // ---- palette ----
    // ================= 主题 =================
    // 所有界面颜色都来自 pal（见 YumenodeTheme）；这里只做取名，方便沿用 C_XXX。
    // 新增控件请用这些常量，不要再写死色值，否则深色模式会留下白块。

    /** 主题管理器：separates theme state & palette logic from UI */
    override val themeManager = ThemeManager(this) {
        // Theme applied — rebuild UI palette accessors
    }

    /** 当前主题模式：0=跟随系统 1=浅色 2=深色（存在 ponko 偏好里） */
    override val themeMode: Int
        get() = themeManager.themeMode

    /** 当前配色表 */
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
            modelLoadController.toggleLoad()
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
    /** 多模态 gguf：视觉投影文件（mmproj）管理委托ado a VisionManager */
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

    // ---- 对话参数（模型页 · 对话模型）----
    override val chatParams = ChatParams(this) { modelPathInternal }
    /** 回填参数到输入框时临时屏蔽 TextWatcher，免得多字段被互相覆盖 */
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

    /** Chat 消息处理：构造文件块、编码文件、估算 token、准备发送 */
    internal val chatManager = ChatManager(this, attachmentHandler)

    /** 当前加载的 .litertlm 模型是否支持图像输入（读模型自带 Capabilities，失败按不支持处理）。 */
    /** true = el modelo cargado soporta entrada visual (delegado a VisionManager) */
    override var visionOk: Boolean
        get() = visionManager?.visionOk ?: false
        set(v) { visionManager?.setVisionOk(v) }

    /** Ruta al mmproj seleccionado (delegado a VisionManager) */
    override var mmprojPath: String?
        get() = visionManager?.mmprojPath
        set(v) { visionManager?.mmprojPath = v }
    /** 「借打标看图」时工具结果用哪个角色候选回传（0=首选，试错后记住可用的那个） */
    override var tagReturnIdx = 0
    /** 输入栏「＋」附件按钮；待发送图片的预览条与缩略图（propiedad de ChatInputBar) */
    private val attachButton: TextView get() = chatInputBar.attachButton
    private val attachPreviewStrip: HorizontalScrollView get() = chatInputBar.attachPreviewStrip
    private val attachPreviewBox: LinearLayout get() = chatInputBar.attachPreviewBox

    /** 待发送的文本文件（已解析出文字），最多 MAX_FILES 个 */
    // private val pendingFiles = ArrayList<PendingFile>()

    /** 用户选的文件：文件名 + 原始字节数 + 解析出的正文（可能已截断）+ 结构说明（如「12 页」） */
    // private class PendingFile(
    //     val name: String,
    //     val bytes: Long,
    //     var text: String,
    //     var truncated: Boolean,
    //     val detail: String? = null,
    // )

    /** 文件读取结果：成功给 file，失败给 error（可直接显示的文案） */
    // private class FileRead(val file: PendingFile?, val error: String?)

    /** 当前处于绘图模式：对话页的输入会被当作正面提示词去生成图片 */
    /** 语言模型可用的绘图命令说明（仅当绘图模型与语言模型同时就绪时注入）。 */
    internal val drawToolPrompt = CommandScanner.DRAW_TOOL_PROMPT

    /** 语言模型与绘图模型同时就绪 → 语言模型可以用 <draw> 命令调绘图模型。 */
    internal val canDrawFromChat: Boolean
        get() = drawPageInternal?.isReady() == true && (engine != null || llamaModel != null)

    /** 从回答里取出 <draw>…</draw> 命令：返回提示词与命中位置（不改内容，由调用方替换） */
    internal fun scanDrawCommand(answer: String, thought: String): DrawHit? =
            commandScanner.scanDrawCommand(answer, thought, canDrawFromChat)

        /** 语言模型看不见图、但打标模型已加载 → 可以用 <tag> 命令让系统去识别图片。 */
        internal val canTagFromChat: Boolean
            get() = drawPageInternal?.taggerLoaded() == true && (engine != null || llamaModel != null) && !visionOk

        /** 这一轮带图、模型自己看不见、且还没识别过 → 本轮就把打标命令说明给模型 */
        internal fun canTagTurn(t: QaTurn?): Boolean =
            canTagFromChat && t?.userImage != null

        /** 看不见图的模型：告诉它可以用 <tag> 命令让打标模型去看用户发的图。 */
        private val tagToolPrompt = """【图片工具】你自己看不到图片内容，但可以调用本机的打标模型，把用户刚发来的图片转成英文标签。
            用户消息里说明了「带了图片」时就是真的有图：此时必须调用下面的图片工具，
            绝对不要回复「请提供图片」或「我看不到图片」之类的推辞。
            需要知道图里有什么时：不要向用户解释这个工具，不要复述这条规则，不要猜图。
            这一轮你的输出里只写下面这一行命令，不要有任何解释、计划或别的话（参数可省；threshold 是阈值 0.05~0.99，topk 是最多几个标签 1~80）：
            <tag threshold=\"0.35\" topk=\"40\"></tag>
            命令写在正文的最后一行，不要写进思考过程。系统随后会把识别结果发给你，你再根据这些标签回答用户的问题.
        """.trimIndent()

        /** 看不见图的模型：本轮带图时给它的提示（先告知「带了图」，再给工具命令格式） */
        internal fun tagHint(t: QaTurn?): String {
            val n = ChatSendLogic.countImages(t?.userImage)
            return getString(R.string.s_329, n) + "\n" + tagToolPrompt
        }

        /** 不做工具调用时，把回答里可能残留的 <tag …></tag> 命令抹掉（别把命令原文当正文显示给用户） */

        /** 从回答里取出 <tag …></tag> 命令：返回参数与命中位置（不改内容，由调用方替换） */
        internal fun scanTagCommand(answer: String, thought: String): TagHit? =
            commandScanner.scanTagCommand(answer, thought, canTagFromChat)

    /**
     * 跑一轮生成（gguf / litertlm 两条路径），把输出流式渲染进 ai。
     * displayPrefix、thoughtPrefix 是前面几轮已经落在气泡里的内容，这样工具调用可以
     * 在同一个气泡里接着往下写，而不是把整轮重来一遍。
     */


    /** 对话页当前是否「出图模式」。
     *  规则：语言模型在场时（包括两种模型同时加载），对话页一律走聊天；
     *  只有「绘图模型已就绪、且没有加载语言模型」时，对话页输入才当成绘图提示词。 */
    internal val drawMode: Boolean
        get() = drawPageInternal?.isReady() == true && engine == null && llamaModel == null

    /** .gguf 架构探测结果缓存，避免重复读头部 */
    private val ggufKindCacheInternal = HashMap<String, GgufProbe.Kind>()
    override var genJob: Job? = null
    /** Ultimo resultado del benchmark (para compartir anonimamente). */
    override var lastBench: BenchStats? = null
    /** Footnote '≈X tok/s' de la última generación (solo render en vivo). */
    internal var lastTpsFoot: String? = null

    // QaTurn is imported from ChatState.kt

    private val sessions = mutableListOf<ChatSession>()
    internal lateinit var current: ChatSession

    /** 当前会话创建时的思考开关状态；与复选框不一致时需重建会话。 */
    override var convThinking: Boolean? = null

    /** 中断后 LiteRT 会话可能已不可用：标记下一条消息发送前静默重建。 */
    internal var convNeedsRebuild = false

    /** 创建 LiteRT 会话时是否注入了 <draw> 绘图命令提示。
     *  加载/卸载绘图模型会改变这个能力，而 LiteRT 的 system 提示只在建会话时写入，
     *  所以能力变化后必须重建会话，否则模型根本不知道自己能画图。 */
    override var convDrawCapable = false

    /** GGUF（llama.cpp）后端实例；非空表示当前加载的是 .gguf 模型。 */
    override var llamaModel: LlamaModel? = null
    internal var ggufIterator: LlamaIterator? = null

    /** 用户点了「停止」。native 中断后往往抛异常（如 llama.cpp 的 task not found），
     *  用它把「主动中断」和「真的出错」分开，避免已输出的内容被错误信息覆盖。 */
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

    // 模型页：顶部页签 + 左右翻页
    private val modelsPager: ViewPager get() = modelsPage.modelsPager
    private val modelsTabChat: TextView get() = modelsPage.modelsTabChat
    private val modelsTabDraw: TextView get() = modelsPage.modelsTabDraw
    private val modelsTabTag: TextView get() = modelsPage.modelsTabTag
    private lateinit var tabSettings: View

    // ---- API 设置区块：对话 / 绘图各一套，由 buildApiBlock() 生成，结构完全一致 ----
    // ApiHost：ApiController 的逻辑在 ApiController.kt；这里只暴露视图 + 委托。
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
    /** 两边各有一行「xx模型：xxx」，插在 API 区块的地址下面 */
    override val chatApiModelTv: TextView get() = modelsPage.chatApiModelTv
    // ---- 「对话模型」页内再分两个子页：模型 / API ----
    private val chatSubTabModel: TextView get() = modelsPage.chatSubTabModel
    private val chatSubTabApi: TextView get() = modelsPage.chatSubTabApi
    private val chatModelPane: View get() = modelsPage.chatModelPane
    private val chatApiPane: View get() = modelsPage.chatApiPane
    // ---- 「绘图模型」页内再分两个子页：模型 / API ----
    private val drawSubTabModel: TextView get() = modelsPage.drawSubTabModel
    private val drawSubTabApi: TextView get() = modelsPage.drawSubTabApi
    private val drawModelPane: View get() = modelsPage.drawModelPane
    private val drawApiPane: View get() = modelsPage.drawApiPane
    /** 「绘图模型：xxx」那一行（绘图侧独有，插在 API 区块的地址下面） */
    override val drawApiModelTv: TextView get() = modelsPage.drawApiModelTv
    private lateinit var tabDraw: View
    override lateinit var inputBar: View

    /** 绘图页（文生图/图生图），懒加载 */
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

    /** 模型页里的绘图模型状态文本（模型统一在模型页加载） */
    private val drawModelStatusInternal: TextView? get() = modelsPage.drawModelStatus
    /** 已复制的绘图模型列表容器 */
    private val drawSavedContainer: LinearLayout? get() = modelsPage.drawSavedContainer
    /** 绘图模型的「加载/卸载」二合一按钮 */
    private val drawToggleBtn: TextView? get() = modelsPage.drawToggleBtn
    /** 绘图模型的「导入」按钮（复制期间禁用） */
    private val drawImportBtn: TextView? get() = modelsPage.drawImportBtn
    /** 绘图模型导入进度条（本地复制时显示） */
    private val drawProgress: ProgressBar? get() = modelsPage.drawProgress
    /** 打标（Tagger）状态文本（模型页） */
    private val taggerStatusTvInternal: TextView? get() = modelsPage.taggerStatusTv
    /** 打标模型 / 标签表导入按钮 */
    private val taggerOnnxBtn: TextView? get() = modelsPage.taggerOnnxBtn
    private val taggerCsvBtn: TextView? get() = modelsPage.taggerCsvBtn
    /** 已导入的打标模型 / 标签表列表容器 */
    private val taggerBoxInternal: LinearLayout? get() = modelsPage.taggerBox
    /** 打标模型 加载/卸载 按钮 */
    private val taggerLoadBtnInternal: TextView? get() = modelsPage.taggerLoadBtn
    /** 当前选定的绘图主模型（绝对路径） */
    /** 当前选用的模型集 id（多文件模型 = 一组文件） */
    private var drawSetIdInternal: String? = null
    /** 正在等文件选择结果的槽位（null = 主模型文件） */
    private var pendingSlotKeyInternal: String? = null
    private val drawSlotsContainerInternal: LinearLayout? get() = modelsPage.drawSlotsContainer
    /** 槽位面板的折叠标题行 */
    private var drawSlotsHeaderInternal: TextView? = null
    /** 槽位面板是否展开（默认收起，省地方） */
    private var drawSlotsExpandedInternal = false
    /** LoRA 状态文本（模型页） */
    private val loraStatusTvInternal: TextView? get() = modelsPage.loraStatusTv
    /** 已安装 LoRA 列表容器（模型页） */
    private val loraBoxInternal: LinearLayout? get() = modelsPage.loraBox
    /** 上次启动时发现「加载绘图模型」中途崩了（native 崩溃，Java 层捕不到） */
    private var pendingDrawCrash = false
    /** 上次崩溃时已执行的阶段（来自 draw/.loadstage） */
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
        // 最早挂上崩溃钩子：连主题解析 / 建界面阶段的异常也能落盘（崩在 buildUi 里否则没日志）
        crashHandler.install()
        // Trial GPU colgado = el intento anterior con Vulkan mato el proceso:
        // apagar el toggle solo y seguir en CPU.
        if (GpuTrial.recoverOnStart(chatPrefs())) {
            // toast tras setTheme/super.onCreate, mas abajo no hace falta:
            // se guarda para mostrarlo al final de onCreate.
            gpuCrashNotice = true
        }
        // 主题必须在 setContentView 之前定好（系统栏/窗口底色来自 style，界面颜色来自 pal）
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
        // 流式渲染器故意不装表格插件：表格是两遍异步布局（先测列宽再重排），
        // 边生成边重渲染会让气泡高度反复跳变（上下抽搯）。表格放到生成结束后一次渲染。
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
     * 原地换主题：重算配色 → 改系统栏颜色 → 用新配色把界面重建一遍。
     *
     * 不走 recreate()：Activity 重建会把 chat 引擎、绘图 native 上下文、ONNX 打标会话
     * 一起丢掉（用户感受就是"设置一动就重启"）。这里只重建 View，这些对象都留着。
     */
    override fun applyThemeInPlace() {
        themeManager.applyThemeInPlace()
            // 记住当前页：buildUi() 末尾会把界面切回来
            chatPrefs().edit().putInt("restoreTab", currentTab).apply()
            buildUi()
        }

        /** manifest 里声明了 uiMode，系统切深浅色不会自动重建，这里手动跟上。 */
        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            val themeChanged = themeManager.onConfigurationChanged(newConfig)
            val localeChanged = lastLocales != newConfig.locales
            lastLocales = newConfig.locales
            if (themeChanged || localeChanged) applyThemeInPlace()
        }

    /** 崩溃自捕获：Java/ART 层异常（含 UnsatisfiedLinkError）写进文件，下次启动可见。 */
    private fun installCrashHandler() {
        crashHandler.install()
    }

    /** 读并清空崩溃日志（供模型页展示） */
    private fun takeCrashLog(): String? {
        return crashHandler.takeCrashLog()
    }

    /** 将 MainActivity 的 session 状态同步到 SessionManager */
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
        drawPageInternal?.persistAll()   // 绘图参数兜底落盘
    }

    override fun onDestroy() {
        super.onDestroy()
        syncSessionToManager()
        try { sessionManager.save() } catch (_: Throwable) {}
        scope.cancel()
        // 绘图引擎：只有「绘图 API 在跑」时才把句柄留给服务继续出图，否则正常释放（别白占内存）。
        // 顺序不能反——先断开 DrawCore 的引用，再真正 free。
        if (!ApiService.isRunning(ApiServer.Role.DRAW)) {
            DrawCore.release()
            try { drawPageInternal?.release() } catch (_: Throwable) {}
        }
        if (!ApiService.isRunning(ApiServer.Role.CHAT)) {
            // 对话 API 没开 → 按老规矩把模型放掉
            try { engine?.close() } catch (_: Throwable) {}
            try { llamaModel?.close() } catch (_: Throwable) {}
            ChatCore.release()
        }
        // 对话 API 还开着：模型留给服务继续推理（关掉它会让 API 立刻不可用），
        // 这里只断开界面自己的引用
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

        // 四页内容：对话 / 模型 / 绘图 / 关于（底部菜单切换）
        tabChat = buildChatPage()
        // 注意顺序：DrawPage 必须先建好并赋给 drawPageInternal，再建「模型」页。
        // 「模型」页构建时就地读取 drawPageInternal 的状态（LoRA 列表 / 已复制绘图模型清单），
        // 顺序反了会拿到 null，每次启动都显示成「未安装」。
        // 复用已存在的 DrawPage：换主题时重建的是视图，native 上下文 / 已加载的模型
        // 都留在同一个实例里（否则换一次主题就要重新加载绘图模型）。
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
        // 绘图模型就绪 → 进入绘图模式（对话页输入即正面提示词）
        dpg.onPipelineReady = {
            // 回调可能来自 IO 线程，UI 操作统一回主线程
            runOnUiThread {
                dpg.llmLoaded = false
                updateThinkEnabled()
                drawModelStatusInternal?.text = dpg.modelSummary()
                refreshDrawModelsInternal()
                refreshDrawApiUi()   // 绘图接口那边要跟着显示「绘图模型：xxx」
            }
        }
        dpg.onSaveImage = { bmp, name -> saveImageToGallery(bmp, name) }
        dpg.onStatus = { text, isError ->
            runOnUiThread { setStatus(text, if (isError) C_ERR else C_WARN) }
        }
        // 注意：不在启动时自动加载绘图模型——native 加载失败会直接崩掉启动流程。
        // 改为在「模型」页手动点「加载绘图模型」（见下方按钮）。
        // 绘图页内部用 ViewPager 做左右翻页，外层不能再套 ScrollView（横竖滑动会打架）
        tabDraw = runCatching { dpg.build() }.getOrElse { e ->
            // 兜底：绘图页构建失败也不能让整个 App 挂掉，把原因显示出来便于定位
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
        // 主题切换导致的重建：回到重建前那一页（一次性，正常启动不受影响）
        val restoreTab = chatPrefs().getInt("restoreTab", -1)
        if (restoreTab in 0..5) {
            chatPrefs().edit().remove("restoreTab").apply()
            switchTab(restoreTab)
        }
    }

    /** 对话页：消息列表 + 回到底部按钮 + 智能跟随滚动。 */
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

        // 用户上滑阅读时暂停自动跟随；此按钮一键回到底部
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

        // 滚动管理委托给 ChatScrollManager（auto-follow / jump-to-bottom 按钮 / foco del input）
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
        appBarMenu = ui.iconButton("☰") { openDrawer() }
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
        appBarPlus = ui.iconButton("＋") { newSession() }
        r1.addView(appBarPlus, wrapWrap())
        bar.addView(r1, matchWrap())
        return bar
    }

    /** 模型页：选择/加载模型 + 后端选择。 */

    /** 模型页：单个面板包一层可纵向滚动的 ScrollView */
    private fun modelsPane(inner: View) = ModelsHelpers.modelsPane(this, inner)

    /** "对话模型"子页签样式（比顶部页签再小一号，同绘图页的 结果/过程） */
    private fun modelsSubTab(t: String) = ModelsHelpers.modelsSubTab(this, t)

    /** 切换「对话模型」页内的子页：0=模型 1=API */
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

    /** 切换「绘图模型」页内的子页：0=模型 1=API */
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

    /** 模型页顶部页签样式（与绘图页一致） */
    private fun tabItem(t: String) = ModelsHelpers.tabItem(this, t)

    // ==================== API 服务（对话 / 绘图两套，结构完全一致） ====================

    /** 委托给 [ApiBlockBuilder]：真正建控件的活儿都在那边，
     * 这里只负责把 Activity 上的动作接过去。
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

    // ---- API 逻辑：已提取到 ApiController.kt；这里只做兼容委托（ModelsHost/buildApiBlock 不变） ----
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

    /** 切换模型页的「对话模型 / 绘图模型 / 打标模型」 */
    override fun switchModelsTab(index: Int) {
        if (::modelsPage.isInitialized) modelsPager.setCurrentItem(index, true)
        highlightModelsTabs(index)
        when (index) {
            0 -> { refreshSavedModels(); visionManager?.refreshMmprojUi(); refreshApiUi() }
            1 -> { refreshDrawModelsInternal(); refreshLoraUi(); refreshDrawApiUi() }
            2 -> refreshTaggerUi()
        }
    }

    /** 同步模型页页签高亮；手势翻页时也由它更新 */
    override fun highlightModelsTabs(selected: Int) {
        if (!::modelsPage.isInitialized) return
        ModelsHelpers.highlightTabs(
            tabs = listOf(modelsTabChat, modelsTabDraw, modelsTabTag),
            selected = selected,
            pal = pal,
        )
    }

    /** 模型页的翻页适配器：直接复用已建好的三个面板，不重建 */
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

    /** 关于页：角色原图 + 说明。 */
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
     * 设置页里的可折叠分区：返回可往里塞内容的容器。
     * 标题行（标题 + 箭头）点一下展开 / 收起。
     */
    /**
     * 可折叠分区。
     *
     * @param expanded 首次使用（还没有存过偏好）时的默认展开状态
     * @param prefKey  记住展开状态的偏好键；**null = 不持久化**（每次都回到 [expanded]）。
     *                 不传 key 的话用户合上分区、切走再回来就会弹回去——这正是「关于」页
     *                 之前的问题：状态只存在 `open` 这个内存变量里。
     */


    /** 关于页的可点击链接（点开系统浏览器） */


    // ---- 底部导航 ----

    override fun buildBottomNav(): View = shellController.buildBottomNav()

    override fun switchTab(index: Int) {
        currentTab = index
        tabChat.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabModels.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabDraw.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabDownload.visibility = if (index == 3) View.VISIBLE else View.GONE
        tabGallery.visibility = if (index == 4) View.VISIBLE else View.GONE
        tabSettings.visibility = if (index == 5) View.VISIBLE else View.GONE
        // 切到「模型」页时刷新绘图模型 / LoRA 列表，避免导入后状态滞后
        if (index == 1) { refreshDrawModelsInternal(); refreshLoraUi(); refreshTaggerUi(); refreshApiUi(); refreshDrawApiUi() }
        // 切到下载页时刷新列表
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

    // ---- 左侧对话抽屉 ----

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
        drawerBody.removeAllViews()
        drawerBody.addView(ui.actionButton(getString(R.string.s_201)) { closeDrawer(); newSession() },
            matchWrap().apply { setMargins(dp(12), dp(4), dp(12), dp(10)) })

        for (s in sessions.sortedByDescending { it.id }) {
            val isCur = s === current
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(12))
                isClickable = true
                if (isCur) setBackgroundColor(C_PRIMARY_SOFT)
            }
            row.addView(TextView(this).apply {
                text = s.title
                textSize = 14f
                setTextColor(if (isCur) C_PRIMARY else C_TEXT)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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


    // ================= 聊天发图（.litertlm 多模态） =================
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

    /** Bitmap → JPEG 字节（extraído a AttachController）。 */
    private fun bitmapToJpeg(bmp: Bitmap, quality: Int = 88): ByteArray = attachController.bitmapToJpeg(bmp, quality)

    /** 用户发的图片也落盘（JPEG），随会话一起恢复。 */
    internal fun writeChatJpeg(bmp: Bitmap, name: String): Boolean = try {
        val dir = File(filesDir, "chatimg").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        true
    } catch (_: Throwable) {
        false
    }

    /** 把待发文件的原始内容编码成 JSON 存进轮次，供「重新生成」还原附件 */
    /** 安装/更新时间（用来确认装的是哪一版） */
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
     * 估算 LiteRT 会话的输入 token：系统提示 + 历史 + 本轮要发的内容。
     * 历史里之前发过的文件正文也还留在引擎的上下文里，所以一并算上。
     */
    /** 判断是不是「上下文塞不下」这类错误（LiteRT Status Code 3 / llama.cpp 的 context 满） */
    internal fun isContextOverflow(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        return m.contains("too long") || m.contains("maximum number of tokens") ||
            m.contains("out of context") ||
            (m.contains("context") && (m.contains("full") || m.contains("exceed")))
    }

    // ================= 对话模型参数（模型页可调） =================

    /** 通用 SharedPreferences（chat/UI/API 设置共用） */
    override fun chatPrefs() = getSharedPreferences("ponko", MODE_PRIVATE)

    /** 模型页输入即存盘（存到当前模型名下）；空值/非法值回落到默认 */
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

    /** 把当前模型的参数回填到模型页的输入框（建界面 / 切模型时调） */
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

    /** 把当前模型的参数恢复成内置默认值（上下文等要重新加载模型才生效） */
    private fun resetChatParams() {
        chatParams.setUiLoading(true)
        chatParams.resetDefaults()
        chatParams.setUiLoading(false)
        loadChatParamsIntoUiInternal()
        toast(getString(R.string.s_317))
    }

    /** 参数格：小标题 + 数字输入 + 说明；keep 用来把输入框存进字段 */
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

    /** 一行放两格参数 */
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

    /** gguf 多模态：选一个 mmproj（视觉投影）文件，.gguf */
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
                    // 新导入的多文件主模型：自动把槽位面板展开，方便接着补配套文件
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

    // ================= 已复制的绘图模型 =================

    /** 绘图模型的二合一按钮：未加载 → 加载；已加载 → 卸载。 */
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
            refreshDrawApiUi()   // 模型卸载了，绘图接口那边要变回「尚未加载绘图模型」
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

    /** 按「绘图模型是否已加载」更新二合一按钮的文案与配色。
     *  与对话模型保持一致：加载=蓝色实心；已加载=浅灰描边（避免误以为还是主操作）。 */
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

    /** 列出已有的绘图模型集（点击选用 · 长按删除）。单文件与多文件都在这。 */
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
                // 列表样式与旧版一致：文件名 + 角色 + 量化 + 体积（多文件集只多一个标记，不做分类/家族展示）
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


    // ---- 多对话管理 ----

    /** 新建一个对话（旧对话保留在列表中）。 */
    private fun newSession() = chatSessionController.newSession()

    private fun switchTo(s: ChatSession) = chatSessionController.switchTo(s)

    private fun confirmDeleteSession(s: ChatSession) = chatSessionController.confirmDeleteSession(s)

    // ---- 会话切换/重建 ----

    /**
     * GGUF 推理参数：带上对话历史 + cache_prompt(true) + 固定 slot。
     * llama.cpp 会把本轮 prompt 与 slot 里已缓存的 KV 做前缀匹配，只计算新增部分 ——
     * 这是长对话不重复 prefill 的关键。
     */
    // buildInferenceParams / buildMultimodalParams / configFor → InferenceParamsBuilder

    // buildInferenceParams / buildMultimodalParams / configFor → InferenceParamsBuilder

    /**
     * 思考开关变化后重建会话。
     * LiteRT-LM 的思考标记是在会话创建时写入提示模板的，
     * 会话跑起来后仅改 per-call 的 ThinkingConfig 不会生效（尤其是 关→开），
     * 所以必须按新模式重建会话；历史通过 initialMessages 保留。
     */
    internal fun rebuildConversation(silent: Boolean = false, dropLast: Boolean = false) = chatSessionController.rebuildConversation(silent, dropLast)

    /** 重建 LiteRT 会话（若模型已加载）并把聊天区重绘为当前对话的内容。 */
    private fun restoreSession(showHint: Boolean = true) = chatSessionController.restoreSession(showHint)

    /**
     * 重新生成某一轮的回答。
     * 该轮之后的轮次依赖这轮的上下文，必须一并丢弃；然后把它重新送进去生成。
     */
    internal fun regenerate(turn: QaTurn) = chatSessionController.regenerate(turn)

    /** 把一轮已有问答重绘到聊天区（历史回填）。 */
    /** 绘图模式下思考开关无意义（不经过对话模型），置灰禁用 */
    override fun updateThinkEnabled() {
        // 可能由 IO 线程上的回调触发；setEnabled 会驱动 Ripple 动画，必须回主线程
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

        // ---- 持久化 ----

    // ---- 持久化 ----

    internal fun doDrawFromChat(prompt: String, dpg: DrawPage) {
        chatSessionController.doDrawFromChat(prompt, dpg)
    }

    /** 把图片写进应用私有目录，返回是否成功 */
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

    /** 在 AI 气泡里插入图片；点图弹确认框再存相册（以前点一下就存，容易误触） */
    /** 全屏看图：双指缩放、拖动、双击放大；未放大时点图关闭，右上角 ✕ 随时关闭。 */
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

    /** 图片长按菜单：保存到相册 / 引用 / 发送至图生图 / 发送至 Tagger。 */
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

    /** 引用：把图片直接放进输入栏待发送附件（不落盘，等同于在输入栏选了这张图）。 */
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
     * LiteRT 的流式片段语义在版本间不完全一致（可能是增量片段，也可能带累积内容）。
     * 统一兼容，避免出现「您好，有什么可以帮你？」被反复拼接的情况：
     *   - 与已累积内容完全相同     → 重复快照，丢弃
     *   - 新片段以已累积内容开头   → 全量快照，用新片段替换
     *   - 已累积内容以新片段结尾   → 尾部重复片段，丢弃（忽略首尾空白）
     *   - 其余                     → 按增量追加
     */
    internal fun mergeStreamDelta(cur: String, delta: String): String = chatSessionController.mergeStreamDelta(cur, delta)

    /** AI reply card: collapsible thinking section on top, Markdown answer below. */
    internal fun addAiArea(onRegen: (() -> Unit)? = null): AiArea = chatSessionController.addAiArea(onRegen)

    internal fun addUserBubble(text: String, images: List<Bitmap> = emptyList(), files: List<String> = emptyList()) = chatSessionController.addUserBubble(text, images, files)

    override fun addSystemHint(text: String) = chatSessionController.addSystemHint(text)

    // ================= helpers =================



    override fun applyBusy(b: Boolean) = chatSessionController.applyBusy(b)

    /** 导入/复制期间禁用「绘图模型」区的按钮，避免重复触发。 */
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

    /** 忙碌时禁用「对话模型」区域的加载按钮。 */
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