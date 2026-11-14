// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

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
import com.yumenode.ai.SessionManager
import com.yumenode.ai.QaTurn as ManagerQaTurn
import com.yumenode.ai.ChatManager
import com.yumenode.ai.QaTurn
import com.yumenode.ai.ChatSession
import com.yumenode.ai.AttachmentHandler
import com.yumenode.ai.RoundOut
import com.yumenode.ai.ToolReturn
import com.yumenode.ai.tagRolesGguf

/*
 * Pipeline de envio de chat, extraido de MainActivity.
 *
 * Son extensiones sobre MainActivity: comparten su estado (modelo cargado,
 * conversacion, vista de burbujas) sin obligar a una interfaz de 40 miembros.
 * Al vivir en su propio archivo, el flujo (doSend -> runRound) queda junto y
 * MainActivity deja de ser el contenedor de todo.
 */

    internal suspend fun MainActivity.runRound(
        turn: QaTurn,
        ai: AiArea,
        gguf: Boolean,
        jpegs: List<ByteArray>,
        overText: String,
        displayPrefix: String,
        thoughtPrefix: String,
        toolReturn: ToolReturn?,
        dropLastForBudget: Boolean,
        hideStream: Boolean,
    ): RoundOut {
        val answerBuf = StringBuilder()
        val thoughtBuf = StringBuilder()
        var lastRender = 0L

        fun renderAnswer() {
            // The first round may be hidden initially (waiting to confirm whether a tool is called); see hideStream
            if (hideStream) return
            val now = SystemClock.uptimeMillis()
            if (now - lastRender < 120) return
            lastRender = now
            val md = displayPrefix + answerBuf
            if (md.isNotEmpty()) {
                // Streaming re-render may steal focus from the input box; give it back while the user is typing
                val hadFocus = inputEdit.hasFocus()
                markwonStream.setMarkdown(ai.answer, md)
                if (hadFocus && !inputEdit.hasFocus()) inputEdit.requestFocus()
            }
        }

        fun renderThought() {
            val now = SystemClock.uptimeMillis()
            if (now - lastRender < 90) return
            val hadFocus = inputEdit.hasFocus()
            ai.thoughtBody.text = thoughtPrefix + thoughtBuf
            ai.thoughtHeader.text = if (ai.thoughtBody.visibility == View.VISIBLE)
                getString(R.string.s_206) else getString(R.string.s_205)
            if (hadFocus && !inputEdit.hasFocus()) inputEdit.requestFocus()
        }

        // "Tool return" round: the template/engine may not understand system or tool roles (it throws),
        // so retry with a different candidate role; once a usable one is found it's remembered and no more trial-and-error.
        var attempt = if (toolReturn == null) 0 else tagReturnIdx.coerceIn(0, 2)
        var genStart = 0L
        lastTpsFoot = null
        while (true) {
            answerBuf.setLength(0)
            thoughtBuf.setLength(0)
            try {
                genStart = SystemClock.uptimeMillis()
                if (gguf) {
                    val lm = llamaModel ?: return RoundOut("", "")
                    val extra: List<net.ladenthin.llama.value.Pair<String, String>> = if (toolReturn == null) {
                        emptyList()
                    } else {
                        listOf(
                            net.ladenthin.llama.value.Pair("assistant", toolReturn.modelText),
                            net.ladenthin.llama.value.Pair(tagRolesGguf[attempt], toolReturn.text),
                        )
                    }
                    // generateChat applies the model's own chat template; cache_prompt=true + a fixed slot
                    // make llama.cpp reuse the KV prefix computed last round, so long conversations stop re-prefilling.
                    // If this round has images and the model can see them → multimodal messages (ContentPart); otherwise the plain-text path
                    val params = if (visionOk && jpegs.isNotEmpty()) {
                        inferenceParamsBuilder.buildMultimodalParams(current, jpegs, extra)
                    } else {
                        inferenceParamsBuilder.buildInferenceParams(current, extra)
                    }

                    // GGUF models mix thinking content into the text stream (as <|channel>thought…<channel|> or …),
                    // a state machine separates the two streams: thinking goes to the collapsible section, body to Markdown rendering.
                    fun onThoughtDelta(d: String) {
                        if (d.isEmpty()) return
                        thoughtBuf.append(d)
                        turn.thought = thoughtPrefix + thoughtBuf
                        if (ai.thoughtBox.visibility != View.VISIBLE) ai.thoughtBox.visibility = View.VISIBLE
                        renderThought()
                    }

                    fun onAnswerDelta(d: String) {
                        if (d.isEmpty()) return
                        answerBuf.append(d)
                        turn.answer = displayPrefix + answerBuf
                        renderAnswer()
                    }

                    // With thinking mode on, start in the thinking section: models like Gemma have `<|channel>thought>`
                    // baked into the prompt template, so it never appears in the output stream — the boundary can only be detected via the end marker.
                    val splitter = ReasoningSplitter(
                        ::onThoughtDelta,
                        ::onAnswerDelta,
                        assumeThinking = thinkingActive,
                    )

                    val flow = channelFlow {
                        withContext(Dispatchers.IO) {
                            val iterable = lm.generateChat(params)
                            val it = iterable.iterator()
                            ggufIterator = it
                            try {
                                // After a user interrupt the native iterator throws (e.g. "task not found"),
                                // so we wind down normally instead of treating it as an error
                                val next = {
                                    try {
                                        if (it.hasNext()) it.next() else null
                                    } catch (e: Throwable) {
                                        if (!stopRequested) throw e
                                        null
                                    }
                                }
                                while (true) {
                                    val piece = next() ?: break
                                    if (piece.text.isNotEmpty()) send(piece.text)
                                }
                            } finally {
                                runCatching { it.close() }
                                ggufIterator = null
                            }
                        }
                    }
                    flow.collect { piece ->
                        splitter.feed(piece)
                        chatScrollManager?.scrollToBottom()
                    }
                    splitter.finish()
                    renderThought()
                } else {
                    // Flow emits INCREMENTAL chunks (not snapshots): accumulate.
                    // Reasoning text arrives on channels["thought"], answer text in contents.
                    val items = ArrayList<Content>()
                    if (overText.isNotEmpty()) items.add(Content.Text(overText))
                    // Don't feed images to a model that can't see them (images only go to the tagger)
                    if (visionOk) jpegs.forEach { items.add(Content.ImageBytes(it)) }
                    // LiteRT sessions are incrementally accumulated: once history + this round exceed the model context (4096),
                    // native throws "Input token ids too long" and this round is dead.
                    // So estimate before sending: over budget → rebuild the session, and configFor trims the oldest turns to fit.
                    if (chatParams.dirty ||
                        chatManager.estimateConversationTokens(
                            current, overText, dropLast = dropLastForBudget, 
                            canDrawFromChat = canDrawFromChat, 
                            drawToolPrompt = drawToolPrompt
                        ) > chatParams.inputTokenBudget()
                    ) {
                        rebuildConversation(silent = true, dropLast = dropLastForBudget)
                        chatParams.setDirty(false)
                    }
                    val conv = conversation ?: throw IllegalStateException(getString(R.string.s_042))
                    val tc = ThinkingConfig(
                        enableThinking = thinkingActive,
                        thinkingTokenBudget = chatParams.thinkBudgetOrUnlimited(),
                    )
                    val flow = when {
                        toolReturn == null -> {
                            val ask: Contents = if (items.size == 1 && items[0] is Content.Text) {
                                Contents.of(overText)
                            } else {
                                Contents.of(items)
                            }
                            conv.sendMessageAsync(ask, thinkingConfig = tc)
                        }
                        // Tool results are returned preferentially as "tool return" (LiteRT-LM native frames),
                        // falling back to a system message if the template rejects it, and finally to a plain text message.
                        attempt == 0 -> conv.sendMessageAsync(
                            Message.tool(Contents.of(Content.ToolResponse("tagger", toolReturn.text))),
                            thinkingConfig = tc,
                        )
                        attempt == 1 -> conv.sendMessageAsync(
                            Message.system(toolReturn.text),
                            thinkingConfig = tc,
                        )
                        else -> conv.sendMessageAsync(Message.user(toolReturn.text), thinkingConfig = tc)
                    }
                    flow.collect { msg ->
                        val delta = extractText(msg)
                        val thoughtDelta = msg.channels["thought"]
                        if (!thoughtDelta.isNullOrEmpty()) {
                            val cur = thoughtBuf.toString()
                            val merged = mergeStreamDelta(cur, thoughtDelta)
                            if (merged != cur) {
                                thoughtBuf.setLength(0)
                                thoughtBuf.append(merged)
                                turn.thought = thoughtPrefix + merged
                                if (ai.thoughtBox.visibility != View.VISIBLE) ai.thoughtBox.visibility = View.VISIBLE
                                renderThought()
                            }
                        }
                        if (delta.isNotEmpty()) {
                            val cur = answerBuf.toString()
                            val merged = mergeStreamDelta(cur, delta)
                            if (merged != cur) {
                                answerBuf.setLength(0)
                                answerBuf.append(merged)
                                turn.answer = displayPrefix + merged
                                renderAnswer()
                            }
                        }
                        chatScrollManager?.scrollToBottom()
                    }
                    renderThought()
                }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Retry with a different role only when nothing has been emitted yet, to avoid losing partial output
                val retry = toolReturn != null && !stopRequested && attempt < 2 &&
                    answerBuf.isEmpty() && thoughtBuf.isEmpty() && !isContextOverflow(e)
                if (!retry) throw e
                attempt++
                tagReturnIdx = attempt
                // That message may already be in the engine's session; rebuild once to discard this round's dirty state
                if (!gguf) runCatching { rebuildConversation(silent = true) }
            }
        }

        // Round wrap-up: render once with the full renderer (including tables) and fold this round's content into the turn's answer
        val ans = answerBuf.toString()
        val th = thoughtBuf.toString()
        val full = displayPrefix + ans
        turn.answer = full
        turn.thought = thoughtPrefix + th
        if (!hideStream) when {
            ans.isEmpty() && th.isEmpty() && displayPrefix.isEmpty() -> {
                ai.answer.text = getString(R.string.s_007)
                ai.answer.setTextColor(C_SUBTEXT)
            }
            ans.isEmpty() && displayPrefix.isEmpty() && th.isNotEmpty() -> {
                // Modelo sin canal de respuesta (típico: no-reasoning con "Thinking
                // mode" activo) → no dejar la burbuja vacía: usar lo generado como
                // respuesta. Antes solo se veía "(model returned reasoning only)".
                markwonFull.setMarkdown(ai.answer, th)
                ai.answer.setTextColor(C_TEXT)
                turn.answer = th
            }
            full.isNotEmpty() -> {
                // tps: estimación ~4 chars/token; nota itálica al pie (no persistida en turn.answer)
                val elapsed = SystemClock.uptimeMillis() - genStart
                val foot = if (elapsed >= 50 && ans.isNotEmpty()) {
                    val tok = ans.length / 4.0
                    "\n\n_≈" + "%.1f".format(tok / (elapsed / 1000.0)) + " tok/s · " +
                        "%.1f".format(elapsed / 1000.0) + "s_"
                } else null
                lastTpsFoot = foot
                markwonFull.setMarkdown(ai.answer, if (foot != null) full + foot else full)
            }
        }
        ai.thoughtBody.text = thoughtPrefix + th
        chatScrollManager?.scrollToBottom()
        return RoundOut(ans, th)
    }

    internal fun MainActivity.doSend() {
        val text = inputEdit.text.toString().trim()
        val attachBmps = attachmentHandler.getPendingImages().map { it.bmp }
        val attachJpegs = attachmentHandler.getPendingImages().map { it.jpeg }
        // Files attached this round: send normally if present in the attachment bar ("regenerate" also restores the previous round's files back into the bar first)
        if (text.isEmpty() && attachJpegs.isEmpty() && attachmentHandler.getPendingFiles().isEmpty()) return
        // Files are "text" — any model can read them, unaffected by multimodal limits
        val fileBlock = chatManager.buildFileBlock(attachmentHandler.getPendingFiles())
        val fileNames = attachmentHandler.getPendingFiles().map { it.name }
        var sendText = ChatSendLogic.composeSendText(text, fileBlock)
        // A new round cannot be started while generating (picking images or quoting is fine during generation, sending is not)
        if (busy) { toast(getString(R.string.s_133)); return }

        // Sending images: the model must either see them itself (.litertlm multimodal / gguf with mmproj),
        // or borrow the tagger's sight (when blind, use <tag> to have the tagger recognize them)
        if (attachJpegs.isNotEmpty()) {
            if (engine == null && llamaModel == null) { toast(getString(R.string.s_273)); return }
            if (!visionOk && !canTagFromChat) { toast(getString(R.string.s_272)); return }
        }

        // Draw mode: treat input as a positive prompt and generate using the draw page's params (except the positive prompt itself)
        val dpg = drawPage
        if (drawMode && dpg != null) {
            doDrawFromChat(text, dpg)
            return
        }

        val isGgufRun = llamaModel != null
        if (conversation == null && !isGgufRun) { toast(getString(R.string.s_174)); return }
        if (busy) {
            toast(getString(R.string.s_133))
            return
        }
        var conv: Conversation? = null
        if (!isGgufRun) {
            val needThinkingRebuild = convThinking != thinkCheck.isChecked
            // A change in draw capability (loading/unloading the draw model) also requires a rebuild:
            // the <draw> hint in the system prompt is written only at session creation; without a rebuild the model won't know it can draw
            val needCapRebuild = convDrawCapable != canDrawFromChat
            if (convNeedsRebuild || needThinkingRebuild || needCapRebuild) {
                // The previous round was interrupted: after cancelProcess() the session may be unusable, so silently rebuild from history
                convNeedsRebuild = false
                // Only a thinking-toggle change needs a visible hint to the user; interrupt recovery and capability changes rebuild silently
                rebuildConversation(silent = !needThinkingRebuild)
            }
            conv = conversation ?: run { toast(getString(R.string.s_042)); return }
        }

        val turn = QaTurn(text)
        current.turns += turn
        // The model can't see images: append the available tagging-command instructions at the end of this round's message (not part of history)
        // Note: this check must wait until turn.userImage is written, or the hint never gets added
        if (fileNames.isNotEmpty()) {
            turn.userFile = fileNames.joinToString("|")
            turn.fileText = fileBlock
            // Store the files' raw content in this turn, so "regenerate" can restore the attachments back into the bar
            turn.fileStore = chatManager.encodeFiles(attachmentHandler.getPendingFiles())
        }
        if (current.title == getString(R.string.s_109)) {
            current.title = ChatSendLogic.computeSeedTitle(
                text = text,
                firstFileName = fileNames.firstOrNull(),
                defaultTitle = getString(R.string.s_278),
                isPlaceholderTitle = true,
            )
        }

        if (attachJpegs.isNotEmpty()) {
            val names = ArrayList<String>()
            for ((idx, img) in attachmentHandler.getPendingImages().withIndex()) {
                val nm = "u_${System.currentTimeMillis()}_$idx.jpg"
                if (writeChatJpeg(img.bmp, nm)) names.add(nm)
            }
            if (names.isNotEmpty()) turn.userImage = names.joinToString("|")
            attachmentHandler.clearPendingImages()
        }
        // The model can't see images: when this round has images, first tell it "this message has images", then give the tagging-tool instructions,
        // otherwise it will just ask "please provide the image" (this segment is round-local, not part of history)
        if (canTagTurn(turn)) sendText += "\n\n" + tagHint(turn)
        attachmentHandler.clearPendingFiles()

        inputEdit.setText("")
        addUserBubble(text, attachBmps, fileNames)
        chatScrollManager?.jumpToBottom()
        applyBusy(true)
        setStoppingUi(true)
        setStatus(getString(R.string.s_143), C_WARN)

        val ai = addAiArea { regenerate(turn) }
        ai.regenButton.visibility = View.GONE   // Show only after generation finishes, to avoid confusion with "stop"
        var cancelled = false
        stopRequested = false

        genJob = scope.launch {
            var drawReq: String? = null
            var shown = ""          // Current body in the bubble (previous rounds + this round's output)
            var thoughtShown = ""   // Content already in the thinking section
            try {
                // Agent loop: generate → run the model's requested tools → feed results back into context and generate again
                var overText = sendText   // Text for this round (round 1 = input + file bodies + tool instructions)
                // Send tagger results back as a tool return (system / tool role, never posing as a user message)
                var toolReturn: ToolReturn? = null
                var round = 0
                var autoTagged = false
                var tagRan = false      // Whether tagging actually ran this round (used for the fallback correction)
                var nudgeUsed = false
                // Images present but model is blind: show a placeholder on the first round (this round's content stays hidden so it doesn't vanish and redraw when the command hits)
                if (canTagTurn(turn)) markwonStream.setMarkdown(ai.answer, getString(R.string.s_323))
                while (true) {
                    val out = runRound(
                        turn = turn,
                        ai = ai,
                        gguf = isGgufRun,
                        jpegs = attachJpegs,
                        overText = overText,
                        displayPrefix = shown,
                        thoughtPrefix = thoughtShown,
                        toolReturn = toolReturn,
                        dropLastForBudget = round == 0,
                        // Blind model with images: hide the first round — it may babble a bit before emitting <tag>,
                        // and showing it would make that segment vanish and redraw once the command hits
                        hideStream = round == 0 && canTagTurn(turn),
                    )
                    var roundText = out.answer

                    // ① Draw command takes priority: hand it to the draw model (actual drawing goes in finally, must wait for busy to clear)
                    val drawHit = scanDrawCommand(roundText, out.thought)
                    if (drawHit != null) {
                        if (drawHit.inAnswer) {
                            roundText = roundText.replaceRange(
                                drawHit.start, drawHit.end, getString(R.string.s_200, drawHit.prompt)
                            )
                            thoughtShown += out.thought
                        } else {
                            thoughtShown += out.thought.replaceRange(
                                drawHit.start, drawHit.end, getString(R.string.s_199, drawHit.prompt)
                            )
                        }
                        shown += roundText
                        drawReq = drawHit.prompt
                        break
                    }

                    // ② Tag command: when the model is blind, have it borrow the tagger to see the image, then answer with the labels
                    val tagHit = if (round < MAX_TAG_ROUNDS) scanTagCommand(roundText, out.thought) else null
                    // Fallback: small models often ignore the image tool and just ask "please provide the image". When this round has images and it looks like a refusal,
                    // the app calls the tagger for it (threshold/count use defaults 0.35 / 40) and returns the result as a "tool return" anyway.
                    val autoTagRes: String? = if (tagHit == null && round == 0 && !autoTagged && canTagFromChat &&
                        attachJpegs.isNotEmpty() && commandScanner.looksLikeBlindRefusal(roundText)
                    ) {
                        autoTagged = true
                        val autoNames = turn.userImage?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
                        val r = if (autoNames.isEmpty()) "" else {
                            // Don't display the tagging result: first clear this round's refusal text
                            shown = ""
                            turn.answer = ""
                            markwonFull.setMarkdown(ai.answer, "")
                            withContext(Dispatchers.IO) {
                                runCatching { drawModelsController.runTaggerOnImages(autoNames, TagReq(0.35f, 40)) }.getOrDefault("")
                            }
                        }
                        r.ifEmpty { null }
                    } else null
                    if (tagHit == null && autoTagRes == null) {
                        val clean = commandScanner.stripTagCommand(roundText)
                        // The model treated the tool result as new input and asked the user "please ask your question": nudge it explicitly and retry once (only once)
                        if (tagRan && !nudgeUsed && commandScanner.looksLikeQuestionAsk(clean)) {
                            nudgeUsed = true
                            shown = ""
                            turn.answer = ""
                            markwonFull.setMarkdown(ai.answer, "")
                            thoughtShown += out.thought
                            overText = getString(R.string.s_331, turn.user.take(200))
                            round++
                            continue
                        }
                        // No usable tool call: strip any leftover <tag> command (when the model can see images and the tagging path is closed)
                        shown += clean
                        thoughtShown += out.thought
                        break
                    }
                    if (tagHit == null) {
                        // Fallback path: the model didn't call the tool, but the tagging result is already available — send it back as a tool return directly (labels not shown)
                        thoughtShown += out.thought
                        val autoBlock = getString(R.string.s_328, autoTagRes, turn.user.take(200))
                        overText = autoBlock
                        // gguf: refill with the clean form "model only output a <tag> command", don't carry the previous round's refusal into context
                        val autoCmd = "<tag threshold=\"0.35\" topk=\"40\"></tag>"
                        toolReturn = ToolReturn(if (isGgufRun) autoCmd else out.answer, autoBlock)
                        tagRan = true
                        round++
                        chatScrollManager?.scrollToBottom()
                        continue
                    }
                    // Replace the command with a one-line note: if it was in the body it stays in the body; if it was in the thought it goes to the thought section and the body gets a line
                    val _tagHit = tagHit!! // safe: already null-checked above at line 4930
                    fun swapLine(replacement: String): String = if (_tagHit.inAnswer) {
                        thoughtShown += out.thought
                        roundText.replaceRange(_tagHit.start, _tagHit.end, replacement)
                    } else {
                        thoughtShown += out.thought.replaceRange(
                            _tagHit.start, _tagHit.end, getString(R.string.s_323)
                        )
                        (roundText + "\n\n" + replacement).trim()
                    }

                    val names = turn.userImage?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
                    // Don't display the tagging result: first clear this round's call trace from the bubble; the model gives the final answer directly after recognition
                    shown = ""
                    turn.answer = ""
                    markwonFull.setMarkdown(ai.answer, "")
                    val res = if (names.isEmpty()) {
                        "\u0000" + getString(R.string.s_326)
                    } else {
                        withContext(Dispatchers.IO) {
                            runCatching { drawModelsController.runTaggerOnImages(names, tagHit.req) }.getOrElse { e ->
                                "\u0000" + (e.message ?: "")
                            }
                        }
                    }
                    if (res.startsWith("\u0000")) {
                        // No images this round, or tagging failed: replace the command with the reason and end this round
                        shown += swapLine(getString(R.string.s_325, res.removePrefix("\u0000")))
                        break
                    }
                    if (res.isEmpty()) {
                        shown += swapLine(getString(R.string.s_327))
                        break
                    }
                    // Success: don't show the labels; leave the bubble empty and wait for the model to give a final answer based on them
                    thoughtShown += if (tagHit.inAnswer) out.thought else {
                        out.thought.replaceRange(_tagHit.start, _tagHit.end, getString(R.string.s_323))
                    }
                    val block = getString(R.string.s_328, res, turn.user.take(200))
                    overText = block
                    // gguf: refill with the clean form "model only output a <tag> command", don't carry the previous round's rambling into context
                    val cmdEcho = "<tag threshold=\"${tagHit.req.threshold}\" topk=\"${tagHit.req.topK}\"></tag>"
                    toolReturn = ToolReturn(if (isGgufRun) cmdEcho else out.answer, block)
                    tagRan = true
                    round++
                    chatScrollManager?.scrollToBottom()
                }
                turn.answer = shown.trimEnd()
                if (turn.answer.isNotEmpty()) {
                    markwonFull.setMarkdown(ai.answer, turn.answer + (lastTpsFoot ?: ""))
                } else if (turn.thought.isEmpty()) {
                    // The first round was hidden and the model said nothing: give a hint rather than leaving an empty bubble
                    ai.answer.text = getString(R.string.s_007)
                    ai.answer.setTextColor(C_SUBTEXT)
                }
                sessionManager.save()
                setStatus(getString(R.string.s_081), C_OK)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Throwable) {
                if (stopRequested) {
                    // Intentional interrupt: after the native side is interrupted it often throws (e.g. "task not found"),
                    // so treat it as "interrupted" and keep whatever is already on screen
                    cancelled = true
                } else if (isContextOverflow(e)) {
                    // Context overflow: give an actionable hint instead of dumping a status code at the user
                    ai.answer.text = getString(R.string.s_302)
                    ai.answer.setTextColor(C_ERR)
                    setStatus(getString(R.string.s_145), C_ERR)
                    toast(getString(R.string.s_302))
                } else {
                    ai.answer.text = getString(R.string.v_024, (e.message))
                    ai.answer.setTextColor(C_ERR)
                    setStatus(getString(R.string.s_145), C_ERR)
                    toast(getString(R.string.v_025, (e.message)))
                }
            } finally {
                ai.regenButton.visibility = View.VISIBLE
                sessionManager.save()
                applyBusy(false)
                setStoppingUi(false)
                if (cancelled) {
                    if (turn.answer.isNotEmpty()) {
                        markwonFull.setMarkdown(ai.answer, turn.answer)
                    } else if (turn.thought.isEmpty()) {
                        ai.answer.text = getString(R.string.s_004)
                        ai.answer.setTextColor(C_SUBTEXT)
                    }
                    setStatus(getString(R.string.s_082), C_IDLE)
                } else if (drawReq != null) {
                    // Hand off to the draw model for image generation (must start after busy clears, or it gets blocked as "generating")
                    val dpg = drawPage
                    if (dpg != null) doDrawFromChat(drawReq, dpg)
                }
                chatScrollManager?.scrollToBottom()
            }
        }
    }
