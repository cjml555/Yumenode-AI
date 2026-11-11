// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import com.yumenode.ai.draw.DrawPage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Lógica de sesiones de chat y render de turnos (borbujas, tarjetas de respuesta).
 *
 * Extraída tal cual de MainActivity: mismo orden de mensajes, mismos textos e IDs
 * de vistas. Solo recibe [ChatSessionHost]; el resto de la Activity queda fuera.
 * [ChatCore] ya cubre texto/stream: aquí no se duplica ninguna lógica de él.
 */

class ChatSessionController(private val host: ChatSessionHost) : ThemeAliases {
    override val palette: YumenodeTheme get() = host.palette

    private val activity get() = host.activity

    // ---- delegación al host: MainActivity conserva las vistas y el estado ----
    private val sessionManager get() = host.sessionsManager
    private val sessions get() = host.sessionItems
    private var current get() = host.currentSession
        set(v) { host.currentSession = v }
    private var engine get() = host.loadedEngine
        set(v) { host.loadedEngine = v }
    private var conversation get() = host.loadedConversation
        set(v) { host.loadedConversation = v }
    private val llamaModel get() = host.loadedLlama
    private val inferenceParamsBuilder get() = host.inferenceBuilder
    private val chatParams get() = host.modelChatParams
    private val attachmentHandler get() = host.attachments
    private val markwonFull get() = host.markdownFull
    private val chatContainer get() = host.chatViews
    private val chatScrollManager get() = host.scrollManager
    private val thinkCheck get() = host.thinkingBox
    private val sendButton get() = host.sendView
    private val loadButton get() = host.loadView
    private val inputEdit get() = host.chatInput
    private var busy get() = host.isBusy
        set(v) { host.isBusy = v }
    private var convThinking get() = host.thinkingState
        set(v) { host.thinkingState = v }
    private var convDrawCapable get() = host.drawCapableState
        set(v) { host.drawCapableState = v }
    private val drawerOpen get() = host.drawerIsOpen
    private val canDrawFromChat get() = host.drawCapable
    private val markwonStream get() = host.markwonStream
    private var genJob get() = host.genJob
        set(v) { host.genJob = v }
    private val scope get() = host.scope

    private fun getString(id: Int, vararg args: Any?) = activity.getString(id, *args)
    private fun toast(msg: String) = host.toast(msg)
    private fun dp(v: Int) = activity.dp(v)
    private fun rounded(color: Int, radiusDp: Int, strokeDp: Int = 0, strokeColor: Int = 0) =
        activity.rounded(color, radiusDp, strokeDp, strokeColor)
    private fun matchWrap(top: Int = 0) = activity.matchWrap(top)
    private fun refreshDrawer() = host.refreshSessionDrawer()
    private fun doSend() = host.sendMessage()
    private fun readChatImage(name: String) = host.readChatImageAt(name)
    private fun bitmapToJpeg(bmp: Bitmap, quality: Int = 88) = host.bitmapToJpegFor(bmp, quality)
    private fun showImageFullscreen(bmp: Bitmap) = host.showChatFullscreen(bmp)
    private fun showImageMenu(bmp: Bitmap, name: String) = host.showChatImageMenuFor(bmp, name)
    private fun attachImageBubble(ai: AiArea, bmp: Bitmap, name: String) = host.attachChatBubble(ai, bmp, name)

    /** 从一条 LiteRT 消息里取出文本（delega en ChatCore, sin duplicar). */
    fun extractText(m: Message): String = ChatCore.messageText(m)

    /**
     * LiteRT 的流式片段语义在版本间不完全一致（增量/累积）；兼容逻辑在 ChatCore。
     * （注释与 MainActivity 原版一致。）
     */
    fun mergeStreamDelta(cur: String, delta: String): String = ChatCore.mergeStream(cur, delta)

    fun setStoppingUi(stop: Boolean) {
        if (stop) {
            sendButton.text = "■"
            sendButton.textSize = 16f
            sendButton.background = rounded(C_ERR, 22)
        } else {
            sendButton.text = "↑"
            sendButton.textSize = 19f
            sendButton.background = rounded(C_PRIMARY, 22)
        }
        sendButton.isEnabled = true
        sendButton.alpha = 1f
    }
    fun newSession() {
        if (busy) { toast(getString(R.string.s_144)); return }
        sessionManager.save()
        val s = ChatSession(System.currentTimeMillis(), getString(R.string.s_109))
        sessions += s
        current = s
        restoreSession()
        sessionManager.save()
        toast(getString(R.string.s_095))
    }
    fun switchTo(s: ChatSession) {
        if (s === current) return
        if (busy) { toast(getString(R.string.s_144)); return }
        sessionManager.save()
        current = s
        restoreSession()
        sessionManager.save()
    }
    fun confirmDeleteSession(s: ChatSession) {
        if (sessions.size <= 1) {
            AlertDialog.Builder(activity)
                .setTitle(getString(R.string.s_139))
                .setMessage(getString(R.string.s_187))
                .setPositiveButton(getString(R.string.s_136)) { _, _ ->
                    s.turns.clear()
                    s.title = getString(R.string.s_109)
                    restoreSession()
                    sessionManager.save()
                    if (drawerOpen) refreshDrawer()
                    toast(getString(R.string.s_101))
                }
                .setNegativeButton(getString(R.string.s_066), null)
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(getString(R.string.s_054))
            .setMessage(s.title)
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                val wasCurrent = s === current
                sessions.remove(s)
                if (wasCurrent) { current = sessions.last(); restoreSession() }
                sessionManager.save()
                if (drawerOpen) refreshDrawer()
                toast(getString(R.string.s_086))
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }
    fun rebuildConversation(silent: Boolean = false, dropLast: Boolean = false) {
        val eng = engine ?: return
        val thinking = thinkCheck.isChecked
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = try {
            eng.createConversation(inferenceParamsBuilder.configFor(current, thinking, dropLast = dropLast))
        } catch (e: Throwable) {
            toast(getString(R.string.v_017, (e.message)))
            eng.createConversation(
                ConversationConfig(
                    thinkingConfig = ThinkingConfig(enableThinking = thinking, thinkingTokenBudget = chatParams.thinkBudgetOrUnlimited())
                )
            )
        }
        convThinking = thinking
        convDrawCapable = canDrawFromChat
        if (!silent) {
            addSystemHint(
                if (thinking) getString(R.string.s_204)
                else getString(R.string.s_085)
            )
        }
    }
    fun restoreSession(showHint: Boolean = true) {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        convThinking = null
        val eng = engine
        if (eng != null && llamaModel == null) {
            val thinking = thinkCheck.isChecked
            conversation = eng.createConversation(inferenceParamsBuilder.configFor(current, thinking))
            convThinking = thinking
            convDrawCapable = canDrawFromChat
        }
        chatContainer.removeAllViews()
        chatScrollManager?.resetForRebuild()
        if (current.turns.isEmpty()) {
            if (showHint) {
                addSystemHint(getString(R.string.s_032) +
                    getString(R.string.s_033))
            }
        } else {
            for (t in current.turns) renderTurn(t)
        }
        chatScrollManager?.scrollToBottom(force = true)
    }
    fun regenerate(turn: QaTurn) {
        if (busy) {
            toast(getString(R.string.s_144))
            return
        }
        val idx = current.turns.indexOf(turn)
        if (idx < 0) return
        val text = turn.user
        // 忠实重发：这一轮带的图片与文件要一起还原，否则「重新生成」会退化成纯文字提问
        val imgNames = turn.userImage?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        val imgPairs = imgNames.mapNotNull { n ->
            readChatImage(n)?.let { bmp -> bmp to bitmapToJpeg(bmp) }
        }
        val fNames = turn.userFile?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        val dropped = current.turns.size - idx - 1
        while (current.turns.size > idx) current.turns.removeAt(current.turns.size - 1)
        restoreSession(showHint = false)
        if (dropped > 0) toast(getString(R.string.v_018, (dropped)))
        attachmentHandler.clearPendingImages()
        attachmentHandler.clearPendingFiles()
        for (p in imgPairs) attachmentHandler.addPendingImage(p.first, p.second)
        // 文件：把上一轮的原始内容还原到附件条（走和正常发送完全一样的路径）
        val restored = attachmentHandler.restoreFiles(turn.fileStore)
        if (restored > 0) toast(getString(R.string.s_320, (restored)))
        else if (fNames.isNotEmpty()) toast(getString(R.string.s_321))
        inputEdit.setText(text)
        doSend()
    }
    fun renderTurn(t: QaTurn) {
        val names = t.userImage?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        val files = t.userFile?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        if (names.isEmpty()) {
            addUserBubble(t.user, emptyList(), files)
        } else {
            addUserBubble(t.user, names.mapNotNull { readChatImage(it) }, files)
        }
        val ai = addAiArea { regenerate(t) }
        val imgName = t.image
        if (imgName != null) {
            val bmp = readChatImage(imgName)
            if (bmp != null) attachImageBubble(ai, bmp, imgName) else ai.answer.text = getString(R.string.s_003)
        }
        if (t.thought.isNotEmpty()) {
            ai.thoughtBox.visibility = View.VISIBLE
            ai.thoughtBody.text = t.thought
            ai.thoughtHeader.text = getString(R.string.s_205)
        }
        if (t.answer.isNotEmpty()) {
            markwonFull.setMarkdown(ai.answer, t.answer)
        } else {
            ai.answer.text = getString(R.string.s_005)
            ai.answer.setTextColor(C_SUBTEXT)
        }
    }
    fun addAiArea(onRegen: (() -> Unit)? = null): AiArea {
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(C_CARD, 16)
            elevation = dp(1).toFloat()
        }

        val thoughtBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(C_THOUGHT_BG, 10)
            visibility = View.GONE
        }
        val thoughtHeader = TextView(activity).apply {
            text = getString(R.string.s_205)
            textSize = 12.5f
            setTextColor(C_THOUGHT_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
        }
        val thoughtBody = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(C_THOUGHT_TEXT)
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = View.GONE
        }
        thoughtHeader.setOnClickListener {
            val show = thoughtBody.visibility != View.VISIBLE
            thoughtBody.visibility = if (show) View.VISIBLE else View.GONE
            thoughtHeader.text = if (show) getString(R.string.s_206) else getString(R.string.s_205)
            if (show) chatScrollManager?.scrollToBottom()
        }
        thoughtBox.addView(thoughtHeader, matchWrap())
        thoughtBox.addView(thoughtBody, matchWrap().apply { topMargin = dp(6) })
        wrap.addView(thoughtBox, matchWrap())

        val answer = TextView(activity).apply {
            textSize = 16f
            setTextColor(C_TEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            // 不给 isFocusable = false：那会把 setTextIsSelectable 设的焦点属性覆盖掉，
            // 导致长按无法选中复制。防键盘焦点抢夺由 renderAnswer/renderThought 里的焦点守卫负责。
        }
        wrap.addView(answer, matchWrap().apply { topMargin = dp(4) })

        // 「重新生成」：对回答不满意时，丢掉这一轮（及其后）的回答重问一次
        val regenButton = TextView(activity).apply {
            text = getString(R.string.s_031)
            textSize = 12.5f
            setTextColor(C_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
            isClickable = true
            isFocusable = false
            visibility = if (onRegen != null) View.VISIBLE else View.GONE
            setOnClickListener { onRegen?.invoke() }
        }
        wrap.addView(regenButton, matchWrap())

        chatContainer.addView(wrap, matchWrap().apply {
            topMargin = dp(8)
            bottomMargin = dp(2)
        })
        chatScrollManager?.scrollToBottom()
        return AiArea(wrap, thoughtBox, thoughtHeader, thoughtBody, answer, regenButton)
    }
    fun addUserBubble(text: String, images: List<Bitmap> = emptyList(), files: List<String> = emptyList()) {
        if (files.isNotEmpty()) {
            val strip = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            for (nm in files) {
                strip.addView(TextView(activity).apply {
                    // 注意要写 this.text：本函数的 text 参数会把 TextView.text 遮住
                    this.text = "📄 $nm"
                    textSize = 12f
                    setTextColor(C_TEXT)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    background = rounded(C_PRIMARY_SOFT, 12)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(6) })
            }
            chatContainer.addView(strip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        if (images.isNotEmpty()) {
            val strip = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            val w = if (images.size == 1) dp(160) else dp(112)
            for (b in images) {
                strip.addView(ImageView(activity).apply {
                    setImageBitmap(b)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    isClickable = true
                    setOnClickListener { showImageFullscreen(b) }
                    setOnLongClickListener {
                        showImageMenu(b, "ponko_${System.currentTimeMillis()}.png"); true
                    }
                }, LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(6)
                })
            }
            chatContainer.addView(strip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        if (text.isEmpty()) {
            chatScrollManager?.scrollToBottom()
            return
        }
        val tv = TextView(activity).apply {
            this.text = text
            textSize = 15.5f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(C_PRIMARY, 16)
            elevation = dp(1).toFloat()
        }
        chatContainer.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END
            leftMargin = dp(56)
            topMargin = dp(8)
            bottomMargin = dp(2)
        })
        chatScrollManager?.scrollToBottom()
    }
    fun addSystemHint(text: String) {
        chatContainer.addView(TextView(activity).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(C_SUBTEXT)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }, matchWrap())
        chatScrollManager?.scrollToBottom()
    }
    fun applyBusy(b: Boolean) {
        busy = b
        loadButton.isEnabled = !b
        loadButton.alpha = if (b) 0.5f else 1f
        // 生成过程中不禁用输入框：可以照常打字（此时发送键是「停止」，只是不发送）
    }

    /** Set status with color (e.g., WARN, OK, IDLE, ERROR) */
    fun setStatus(text: String, color: Int) {
        host.setStatus(text, color)
    }

    /** Draw chat response from prompt, using host for UI and host for rendering */
    fun doDrawFromChat(prompt: String, dpg: DrawPage) {
        if (busy) { toast(getString(R.string.s_133)); return }
        val turn = QaTurn(prompt)
        current.turns += turn
        if (current.title == getString(R.string.s_109)) current.title = prompt.take(18)

        inputEdit.setText("")
        addUserBubble(prompt)
        chatScrollManager?.jumpToBottom()
        applyBusy(true)
        setStoppingUi(true)
        setStatus(getString(R.string.s_166), C_WARN)

        val ai = addAiArea()
        ai.regenButton.visibility = View.GONE
        markwonStream.setMarkdown(ai.answer, getString(R.string.s_202))

        genJob = scope.launch {
            var curStep = 0
            var totalStep = 0
            val drawStartedAt = System.currentTimeMillis()
            val ticker = scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val sec = (System.currentTimeMillis() - drawStartedAt) / 1000
                    val stepInfo = if (totalStep > 0) getString(R.string.v_019, curStep, totalStep) else ""
                    markwonStream.setMarkdown(ai.answer, getString(R.string.v_020, stepInfo, sec))
                }
            }
            try {
                val img = dpg.generateImage(
                    prompt,
                    onProgress = { cur, total ->
                        curStep = cur
                        totalStep = total
                        host.activity.runOnUiThread {
                            if (cur == total || cur % 2 == 0) setStatus(getString(R.string.v_021, cur, total), C_WARN)
                        }
                    },
                    onPreview = { st, w, h, rgb -> dpg.previewFrame(st, w, h, rgb) },
                )
                ticker.cancel()
                val bmp = dpg.toBitmap(img)
                val name = "ponko_${System.currentTimeMillis()}.png"
                val saved = withContext(kotlinx.coroutines.Dispatchers.IO) { writeChatImage(bmp, name) }
                turn.image = if (saved) name else null
                turn.answer = "🎨 seed=${img.seed}"
                markwonStream.setMarkdown(ai.answer, "")
                attachImageBubble(ai, bmp, name)
                setStatus(getString(R.string.s_153), C_OK)
            } catch (e: com.yumenode.ai.draw.GenerationCancelledException) {
                ticker.cancel()
                turn.answer = getString(R.string.s_004)
                markwonStream.setMarkdown(ai.answer, getString(R.string.s_004))
                setStatus(getString(R.string.s_082), C_IDLE)
            } catch (e: CancellationException) {
                ticker.cancel()
                turn.answer = getString(R.string.s_004)
                markwonStream.setMarkdown(ai.answer, getString(R.string.s_004))
                setStatus(getString(R.string.s_082), C_IDLE)
                throw e
            } catch (e: Throwable) {
                ticker.cancel()
                turn.answer = getString(R.string.v_022, e.message)
                markwonStream.setMarkdown(ai.answer, getString(R.string.v_023, e.message))
                setStatus(getString(R.string.s_152), C_ERR)
            } finally {
                ticker.cancel()
                ai.regenButton.visibility = View.VISIBLE
                sessionManager.save()
                applyBusy(false)
                setStoppingUi(false)
                chatScrollManager?.scrollToBottom()
            }
        }
    }

    /** 把图片写进应用私有目录，返回是否成功 */
    private fun writeChatImage(bmp: Bitmap, name: String): Boolean = try {
        val dir = File(host.activity.filesDir, "chatimg").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (_: Throwable) {
        false
    }
}
