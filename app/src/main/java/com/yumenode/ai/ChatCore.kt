// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.os.Process
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.parameters.InferenceParameters
import net.ladenthin.llama.value.Pair as LlamaPair

/**
 * Shared entry point for the chat engine: both the app UI and the "chat API" service obtain models
 * and run generation from here.
 *
 * Why this layer: the model was originally a MainActivity private field; Activity destruction closes it;
 * but the API service needs to keep serving after the app goes to background, so the model must live
 * on a process-level singleton. MainActivity just registers the reference ([attachLiteRt] / [attachGguf] /
 * [release]), while view rebuilds or theme switches don't affect it here.
 *
 * Serialization: the local model can only run one request at a time (llama.cpp's KV slot and LiteRT's
 * conversation are not thread-safe); all generation goes through [lock], exposes [busy], and UI and API
 * avoid each other.
 */
object ChatCore {

    /** OpenAI-style single message (role: system / user / assistant) */
    class Msg(val role: String, val text: String)

    @Volatile private var engine: Engine? = null
    @Volatile private var llama: LlamaModel? = null

    /** Currently loaded model name (API's /v1/models uses it as id) */
    @Volatile var modelName: String? = null
        private set

    /** Whether model supports image input (currently API text-only, kept for future multimodal interface) */
    @Volatile var visionOk = false
        private set

    /** Whether currently generating (UI avoids accordingly, don't fight API for the same KV slot) */
    @Volatile var busy = false
        private set

    /** User/client requested interruption of current generation */
    @Volatile private var cancelFlag = false

    /** Premium: GPU thread priority boost for inference (toggle en Ajustes, leído al cargar modelo). */
    @Volatile var gpuPriority: Boolean = false
        private set

    /** Called by the model loader to sync the preference into the engine. */
    fun setGpuPriority(on: Boolean) { gpuPriority = on }

    private val lock = Mutex()

    /** LiteRT conversation cache: the client resends full history each round; if it matches, reuse the
 * conversation, saving repeated prefill. */
    private class Cached(val prior: List<Msg>, val sys: String, val conv: Conversation)
    private var cache: Cached? = null

    fun attachLiteRt(eng: Engine, name: String, vision: Boolean) {
        clearCache()
        engine = eng
        llama = null
        modelName = name
        visionOk = vision
    }

    fun attachGguf(model: LlamaModel, name: String, vision: Boolean) {
        clearCache()
        llama = model
        engine = null
        modelName = name
        visionOk = vision
    }

    /** Model unloaded (user unloaded in UI, or process exiting) — only drops references, does not close
 * (owner closes). */
    fun release() {
        clearCache()
        engine = null
        llama = null
        modelName = null
        visionOk = false
        gpuPriority = false
    }

    fun isReady(): Boolean = engine != null || llama != null

    fun requestCancel() {
        cancelFlag = true
    }

    private fun clearCache() {
        cache?.let { c -> runCatching { c.conv.close() } }
        cache = null
    }

    /**
     * Generate once. messages are in OpenAI style; system is extracted separately (LiteRT uses systemInstruction,
     * gguf has no system role, so it merges into the first user message).
     * Returns full answer; streaming fragments are passed via the [onDelta] callback (thinking content already stripped).
     */
    suspend fun generate(
        messages: List<Msg>,
        temperature: Float?,
        maxTokens: Int?,
        onDelta: (String) -> Unit,
        /** JSON Schema para decodificación restringida (solo GGUF llama.cpp). */
        jsonSchema: String? = null,
    ): String = lock.withLock {
        busy = true
        cancelFlag = false
        try {
            withContext(Dispatchers.IO) {
                val eng = engine
                if (eng != null) runLiteRt(eng, messages, temperature, maxTokens, onDelta)
                else {
                    val lm = llama ?: throw IllegalStateException("未加载对话模型")
                    runGguf(lm, messages, temperature, maxTokens, onDelta, jsonSchema)
                }
            }
        } finally {
            busy = false
        }
    }

    /** true si el backend cargado admite decodificación restringida (GGUF). */
    fun supportsJsonSchema(): Boolean = llama != null

    // ==================== LiteRT-LM path ====================

    private suspend fun runLiteRt(
        eng: Engine,
        messages: List<Msg>,
        temperature: Float?,
        maxTokens: Int?,
        onDelta: (String) -> Unit,
    ): String {
        val sys = messages.filter { it.role == "system" }.joinToString("\n") { it.text }.trim()
        val turns = messages.filter { it.role != "system" && it.text.isNotEmpty() }
        require(turns.isNotEmpty()) { "请求里没有 user 消息" }
        val last = turns.last()
        val prior = turns.dropLast(1)

        val conv = cache?.takeIf { it.prior == prior && it.sys == sys }?.conv ?: run {
            clearCache()
            val initial = ArrayList<Message>()
            prior.forEach { m ->
                initial.add(if (m.role == "assistant") Message.model(m.text) else Message.user(m.text))
            }
            eng.createConversation(
                ConversationConfig(
                    systemInstruction = if (sys.isEmpty()) null else Contents.of(sys),
                    initialMessages = initial,
                    samplerConfig = SamplerConfig(
                        topK = 40,
                        topP = 0.95,
                        temperature = (temperature ?: 0.7f).toDouble(),
                        seed = (System.nanoTime() and 0x7FFFFFFF).toInt(),
                    ),
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    maxOutputToken = maxTokens,
                )
            ).also { cache = Cached(prior, sys, it) }
        }

        val sb = StringBuilder()
        val flow: Flow<Message> = conv.sendMessageAsync(
            last.text,
            thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
        )
        flow.collect { msg ->
            if (cancelFlag) throw InterruptedException("cancelled")
            val delta = mergeStream(sb.toString(), messageText(msg))
            if (delta.length > sb.length) {
                val add = delta.substring(sb.length)
                sb.setLength(0); sb.append(delta)
                onDelta(add)
            }
        }
        return sb.toString()
    }

    // ==================== gguf (llama.cpp) path ====================

    private fun runGguf(
        lm: LlamaModel,
        messages: List<Msg>,
        temperature: Float?,
        maxTokens: Int?,
        onDelta: (String) -> Unit,
        jsonSchema: String? = null,
    ): String {
        val sys = messages.filter { it.role == "system" }.joinToString("\n") { it.text }.trim()
        val turns = messages.filter { it.role != "system" && it.text.isNotEmpty() }
        require(turns.isNotEmpty()) { "请求里没有 user 消息" }
        // llama.cpp chat template only understands user/assistant; system is merged into the first user message
        val msgs = ArrayList<LlamaPair<String, String>>()
        turns.forEachIndexed { i, m ->
            val role = if (m.role == "assistant") "assistant" else "user"
            var text = m.text
            if (i == 0 && sys.isNotEmpty()) text = "$sys\n\n$text"
            msgs.add(LlamaPair(role, text))
        }
        val params = InferenceParameters.empty()
            .withMessages(null, msgs)
            // cache_prompt + fixed slot: client resends history prefix each round, llama.cpp reuses it, no repeat prefill
            .withCachePrompt(true)
            .withSlotId(0)
            .withNPredict(maxTokens ?: 1024)
            .withTemperature(temperature ?: 0.7f)
            .withTopK(40)
            .withTopP(0.95f)
            .withRepeatPenalty(1.1f)
            .withSeed((System.nanoTime() and 0x7FFFFFFF).toInt())

        // Decodificación restringida: la gramática derivada del JSON Schema hace
        // que el modelo solo pueda emitir un objeto válido (tool calling).
        if (!jsonSchema.isNullOrBlank()) {
            runCatching { params.withJsonSchema(jsonSchema) }
        }

        val sb = StringBuilder()
        // gguf thinking content is mixed into the main stream; use the same state machine as the UI to split, only pass main text to client
        val splitter = ReasoningSplitter(
            onThought = {},
            onAnswer = { d -> if (d.isNotEmpty()) { sb.append(d); onDelta(d) } },
        )
        // Priority GPU: boost thread priority on inference (only when premium+enabled)
        if (gpuPriority) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        }
        val it = lm.generateChat(params).iterator()
        try {
            while (it.hasNext()) {
                if (cancelFlag) break
                val piece = it.next()
                if (piece.text.isNotEmpty()) splitter.feed(piece.text)
            }
        } finally {
            runCatching { it.close() }
        }
        splitter.finish()
        return sb.toString()
    }

    // ==================== Two small utilities shared with UI ====================

    /** Extracts text from one LiteRT message */
    fun messageText(m: Message): String =
        m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /**
     /** Compatible with LiteRT stream fragment semantics (incremental / cumulative snapshot), avoiding
      * "Hello, how can I help you?" being repeatedly appended:
      *   - Identical to accumulated → discard;
      *   - New fragment starts with accumulated → full snapshot, replace;
      *   - Accumulated ends with new fragment → tail duplicate, discard;
      *   - Otherwise → append. */
     */
    fun mergeStream(cur: String, delta: String): String {
        if (cur.isEmpty() || delta.isEmpty()) return cur + delta
        if (delta == cur) return cur
        if (delta.startsWith(cur)) return delta
        if (cur.endsWith(delta)) return cur
        val t = delta.trim()
        if (t.isNotEmpty() && cur.endsWith(t)) return cur
        return cur + delta
    }
}
