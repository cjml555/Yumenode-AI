// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.graphics.Bitmap
import com.yumenode.ai.draw.SdCppEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal HTTP/1.1 server: exposes local models on the phone as an **OpenAI-compatible** interface.
 *
 * Uses only JDK built-ins (ServerSocket + thread pool), no third-party HTTP library:
 * just a few routes + SSE needed; writing it ourselves is more controllable
 * (especially streaming and client disconnect handling).
 *
 * **One instance serves one type of interface only** ([Role]):
 * - [Role.CHAT]: `/v1/chat/completions` + `/v1/models` (chat models)
 * - [Role.DRAW]: `/v1/images/generations` + `/v1/models` (drawing models)
 *
 * The two interface types are **independent**: each has its own port, key, switch, and stats
 * (see [ApiService]). Why not cram them onto one port like initially: their service targets,
 * resource usage, and security exposure differ — one image takes tens of seconds and maxes CPU,
 * should only run in trusted networks; chat is for daily use.
 * Wanting to configure port/key separately is natural; hard-binding them together removes that choice.
 *
 * `/health` and `/v1/models` exist on both instances, each reporting **its own type's** model status.
 *
 * Generation all goes to [ChatCore] / [DrawCore]; this only does protocol conversion.
 * Only one request allowed at a time (local models can't do concurrency); busy returns 429 —
 * the client sees standard rate-limit semantics.
 */
class ApiServer(
    /** Listening port ([ApiService] uses it to judge whether config changed, needs restart) */
    val port: Int,
    /** Empty = no validation (user explicitly asked "key can exist or not") */
    val apiKey: String?,
    /** Which type of interface this instance serves */
    private val role: Role,
    private val log: (String) -> Unit,
) {

    enum class Role { CHAT, DRAW }

    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile var running = false
        private set

    /** Number of processed requests (for UI display) */
    @Volatile var served = 0
        private set

    /** Summary of most recent request (for UI display) */
    @Volatile var lastRequest: String? = null
        private set

    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            serverSocket = ss
            running = true
            acceptThread = Thread({
                while (running) {
                    val s = try { ss.accept() } catch (_: Throwable) { break }
                    pool.execute { runCatching { handle(s) } }
                }
            }, "ponko-api-accept").apply { isDaemon = true; start() }
            log("Started, listening 0.0.0.0:$port")
            true
        } catch (e: Throwable) {
            log("Start failed: ${e.message ?: e.javaClass.simpleName} (port occupied?)")
            running = false
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        log("Stopped")
    }

    // ==================== Connection handling ====================

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 20000
            val ins = BufferedInputStream(s.getInputStream())
            val head = readLine(ins) ?: return
            val parts = head.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(ins) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (len in 1..(32 shl 20)) readBytes(ins, len) else ByteArray(0)
            route(s, method, path, headers, body)
        }
    }

    private fun route(s: Socket, method: String, path: String, headers: Map<String, String>, body: ByteArray) {
        if (method == "OPTIONS") { respond(s, 204, null, ByteArray(0)); return }
        // Validation: only validates if key configured (static page /health also passes, so the user can first confirm the service is alive)
        if (!apiKey.isNullOrEmpty() && path != "/health") {
            val auth = headers["authorization"] ?: ""
            val given = auth.removePrefix("Bearer ").trim()
            if (given != apiKey) {
                respondJson(s, 401, OpenAiApi.error("Invalid API key", OpenAiApi.ErrorType.INVALID_REQUEST))
                return
            }
        }
        when {
            method == "GET" && (path == "/health" || path == "/") -> respondJson(s, 200, healthJson())

            method == "GET" && path == "/v1/models" -> respondJson(s, 200, modelsJson())

            method == "POST" && path == "/v1/chat/completions" ->
                if (role == Role.CHAT) chatCompletions(s, body) else wrongRole(s, "/v1/chat/completions", "chat")

            method == "POST" && path == "/v1/images/generations" ->
                if (role == Role.DRAW) imagesGenerations(s, body) else wrongRole(s, "/v1/images/generations", "draw")

            else -> respondJson(s, 404, OpenAiApi.error("Not found: $method $path", OpenAiApi.ErrorType.INVALID_REQUEST))
        }
    }

    /** Request hit the "other interface type's" port — clearly tell the client where to go, don't make it guess */
    private fun wrongRole(s: Socket, path: String, wanted: String) {
        val where = if (wanted == "chat") "Chat model" else "Draw model"
        respondJson(s, 404, OpenAiApi.error(
            "$path is not served on this port. The $wanted API runs on its own port - " +
                "open Yumenode AI, go to Models/API -> $where -> API tab to see its address.",
            OpenAiApi.ErrorType.INVALID_REQUEST))
    }

    /** This instance's status. Both roles each report readiness of their own type of model. */
    private fun healthJson(): JSONObject {
        val o = JSONObject()
            .put("status", "ok")
            .put("role", if (role == Role.CHAT) "chat" else "draw")
        if (role == Role.CHAT) {
            o.put("model", ChatCore.modelName ?: JSONObject.NULL)
                .put("ready", ChatCore.isReady())
                .put("busy", ChatCore.busy)
        } else {
            o.put("model", DrawCore.modelName ?: JSONObject.NULL)
                .put("ready", DrawCore.isReady())
                .put("busy", DrawCore.busy || DrawCore.uiBusy)
        }
        return o
    }

    private fun modelsJson(): JSONObject {
        val arr = JSONArray()
        val name = if (role == Role.CHAT) ChatCore.modelName else DrawCore.modelName
        name?.let {
            arr.put(JSONObject()
                .put("id", it)
                .put("object", "model")
                .put("created", System.currentTimeMillis() / 1000)
                .put("owned_by", "ponko"))
        }
        return JSONObject().put("object", "list").put("data", arr)
    }

    // ==================== /v1/images/generations ====================

    /**
     * OpenAI-compatible text-to-image. Parameters not given fall back to current settings from
     * the app's draw page (steps / CFG / size / negative prompt / sampler / scheduler / LoRA).
     *
     * Only supports `response_format=b64_json`: the device has no publicly accessible URL to serve
     * as image host, so the `url` return form is impossible (given, it reports an error explicitly
     * instead of quietly returning the wrong format).
     */
    private fun imagesGenerations(s: Socket, body: ByteArray) {
        val req = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
        if (req == null) {
            respondJson(s, 400, OpenAiApi.error("Invalid JSON body", OpenAiApi.ErrorType.INVALID_REQUEST))
            return
        }
        val prompt = req.optString("prompt", "").trim()
        if (prompt.isEmpty()) {
            respondJson(s, 400, OpenAiApi.error("prompt is required", OpenAiApi.ErrorType.INVALID_REQUEST))
            return
        }
        if (!DrawCore.isReady()) {
            respondJson(s, 503, OpenAiApi.error("No drawing model loaded in Yumenode AI. Open the app and load one first.", OpenAiApi.ErrorType.SERVICE_UNAVAILABLE))
            return
        }
        if (DrawCore.busy || DrawCore.uiBusy) {
            respondJson(s, 429, OpenAiApi.error("The drawing engine is busy with another request. Retry shortly.", OpenAiApi.ErrorType.RATE_LIMIT))
            return
        }
        val fmt = req.optString("response_format", "b64_json").lowercase()
        if (fmt != "b64_json") {
            respondJson(s, 400, OpenAiApi.error("Only response_format=b64_json is supported (this server has no public URL to serve images from). " +
                    "Pass response_format=\"b64_json\" in your request.", OpenAiApi.ErrorType.INVALID_REQUEST))
            return
        }

        val n = req.optInt("n", 1).coerceIn(1, 4)
        // size: "512x512" / "1024x1024"; unrecognized falls back to the App's current settings
        var w: Int? = null
        var hgt: Int? = null
        val sizeStr = req.optString("size", "").trim()
        if (sizeStr.isNotEmpty()) {
            val m = Regex("^(\\d+)\\s*[xX×]\\s*(\\d+)$").find(sizeStr)
            if (m == null) {
                respondJson(s, 400, OpenAiApi.error("size must look like \"512x512\"", OpenAiApi.ErrorType.INVALID_REQUEST))
                return
            }
            w = m.groupValues[1].toIntOrNull()
            hgt = m.groupValues[2].toIntOrNull()
        }
        val steps = when {
            req.has("steps") -> req.optInt("steps")
            req.has("num_inference_steps") -> req.optInt("num_inference_steps")
            else -> null
        }
        val cfg = when {
            req.has("cfg_scale") -> req.optDouble("cfg_scale").toFloat()
            req.has("guidance_scale") -> req.optDouble("guidance_scale").toFloat()
            else -> null
        }
        val negative = if (req.has("negative_prompt")) req.optString("negative_prompt") else null
        val seed0 = if (req.has("seed")) req.optLong("seed") else -1L

        // ---- Native hires fix (implemented inside sd.cpp) ----
        // hires_scale: 2 = output 2x larger image (latent upscale + second denoising round)
        // hires_denoise: second-round denoise strength 0.05–0.99
        // hires_upscaler: "latent" | "lanczos" | "nearest" | ...
        val hiresScale = if (req.has("hires_scale")) req.optDouble("hires_scale").toFloat() else 0f
        val hiresDenoise = if (req.has("hires_denoise")) req.optDouble("hires_denoise").toFloat() else 0.7f
        // ---- VAE tiling (tiled encode/decode, saves memory) ----
        // vae_tiling: true/false · vae_tile_size: px · vae_tile_overlap: 0–0.5
        val vaeTiling = req.optBoolean("vae_tiling", false)
        val vaeTileSize = req.optInt("vae_tile_size", 512).coerceIn(64, 2048)
        val vaeTileOverlap = req.optDouble("vae_tile_overlap", 0.25).toFloat().coerceIn(0f, 0.5f)

        val hiresUpscaler = when (req.optString("hires_upscaler", "latent").lowercase()) {
            "lanczos" -> SdCppEngine.HiresUpscaler.LANCZOS
            "nearest" -> SdCppEngine.HiresUpscaler.NEAREST
            "latent_bicubic" -> SdCppEngine.HiresUpscaler.LATENT_BICUBIC
            "latent_antialiased" -> SdCppEngine.HiresUpscaler.LATENT_ANTIALIASED
            "latent_nearest" -> SdCppEngine.HiresUpscaler.LATENT_NEAREST
            else -> SdCppEngine.HiresUpscaler.LATENT
        }

        served++
        lastRequest = "#$served draw n=$n · " + prompt.take(40).replace("\n", " ")

        val arr = JSONArray()
        try {
            // n images passed to DrawCore together (one lock hold, generated in a row), preventing UI from sneaking in between
            val results = runBlocking {
                DrawCore.generate(prompt, n, seed0, steps, cfg, w, hgt, negative,
                    hiresScale = hiresScale,
                    hiresDenoise = hiresDenoise,
                    hiresUpscaler = hiresUpscaler,
                    vaeTiling = vaeTiling,
                    vaeTileSize = vaeTileSize,
                    vaeTileOverlap = vaeTileOverlap)
            }
            for (r in results) {
                val baos = java.io.ByteArrayOutputStream()
                r.bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                arr.put(JSONObject().put("b64_json",
                    android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)))
            }
        } catch (e: Throwable) {
            // Getting here mostly means the client disconnected mid-way (can't write) or an engine error
            runCatching {
                respondJson(s, 500, OpenAiApi.error(e.message ?: e.javaClass.simpleName, OpenAiApi.ErrorType.SERVER))
            }
            return
        }
        respondJson(s, 200, JSONObject()
            .put("created", System.currentTimeMillis() / 1000)
            .put("data", arr))
    }

    // ==================== /v1/chat/completions ====================

    private fun chatCompletions(s: Socket, body: ByteArray) {
        val req = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
        if (req == null) {
            respondJson(s, 400, OpenAiApi.error("Invalid JSON body", OpenAiApi.ErrorType.INVALID_REQUEST))
            return
        }
        val messages = OpenAiApi.parseMessages(req.optJSONArray("messages"))
            .map { ChatCore.Msg(it.role, it.text) }
        if (!OpenAiApi.hasUserMessage(messages.map { OpenAiApi.Message(it.role, it.text) })) {
            respondJson(s, 400, OpenAiApi.error("messages must contain a non-empty user message", OpenAiApi.ErrorType.INVALID_REQUEST))
            return
        }
        if (!ChatCore.isReady()) {
            respondJson(s, 503, OpenAiApi.error(
                "No chat model loaded in Yumenode AI. Open the app and load a model first.",
                OpenAiApi.ErrorType.SERVICE_UNAVAILABLE))
            return
        }
        if (ChatCore.busy) {
            respondJson(s, 429, OpenAiApi.error(
                "The model is busy with another request. Retry shortly.",
                OpenAiApi.ErrorType.RATE_LIMIT))
            return
        }

        val stream = req.optBoolean("stream", false)
        val sampling = OpenAiApi.parseSampling(req)
        val temperature = sampling.temperature
        val maxTokens = sampling.maxTokens
        val model = ChatCore.modelName ?: "ponko"
        val id = "chatcmpl-" + System.nanoTime().toString(36)
        val created = System.currentTimeMillis() / 1000
        served++
        val preview = messages.lastOrNull { it.role == "user" }?.text?.take(40)?.replace("\n", " ") ?: ""
        lastRequest = "#$served ${if (stream) "stream" else "once"} · $preview"

        if (!stream) {
            val answer = runCatching {
                runBlocking { ChatCore.generate(messages, temperature, maxTokens) { } }
            }.getOrElse { e ->
                respondJson(s, 500, OpenAiApi.error(e.message ?: e.javaClass.simpleName, OpenAiApi.ErrorType.SERVER))
                return
            }
            val usage = JSONObject()
                .put("prompt_tokens", estimateTokens(messages.sumOf { it.text.length }))
                .put("completion_tokens", estimateTokens(answer.length))
                .put("total_tokens", estimateTokens(messages.sumOf { it.text.length } + answer.length))
            respondJson(s, 200, OpenAiApi.chatCompletion(id, model, created, answer, usage))
            return
        }

        // ---- SSE streaming ----
        val out = s.getOutputStream()
        writeHead(out, 200, "text/event-stream")
        var broke = false
        fun event(obj: JSONObject) {
            if (broke) return
            try { writeChunk(out, OpenAiApi.sseEvent(obj)) } catch (_: Throwable) {
                broke = true
                ChatCore.requestCancel()   // client disconnected, stop computing
            }
        }
        fun deltaEvent(content: String, role: String? = null) {
            event(OpenAiApi.chatChunk(id, model, created, content, role))
        }
        try {
            deltaEvent("", role = "assistant")
            runBlocking {
                ChatCore.generate(messages, temperature, maxTokens) { piece -> deltaEvent(piece) }
            }
            if (!broke) {
                event(OpenAiApi.chatChunkFinish(id, model, created))
                writeChunk(out, OpenAiApi.SSE_DONE)
            }
        } catch (e: Throwable) {
            if (!broke) {
                event(OpenAiApi.error(e.message ?: e.javaClass.simpleName, OpenAiApi.ErrorType.SERVER))
            }
        } finally {
            runCatching { out.write("0\r\n\r\n".toByteArray()); out.flush() }
        }
    }

    /** Rough token estimate (local models have no tokenizer interface, 4 chars ≈ 1 token) — usage field only */
    private fun estimateTokens(chars: Int): Int = TokenEstimator.fromChars(chars)

    // ==================== HTTP output ====================

    private fun respond(s: Socket, code: Int, contentType: String?, body: ByteArray) {
        val out = s.getOutputStream()
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun respondJson(s: Socket, code: Int, obj: JSONObject) =
        respond(s, code, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))

    /** SSE header: chunked transfer, no Content-Length upfront; send while computing */
    private fun writeHead(out: OutputStream, code: Int, contentType: String) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(" OK\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Cache-Control: no-cache\r\n")
        sb.append("Transfer-Encoding: chunked\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun writeChunk(out: OutputStream, text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        out.write(Integer.toHexString(b.size).toByteArray())
        out.write("\r\n".toByteArray())
        out.write(b)
        out.write("\r\n".toByteArray())
        out.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"
        503 -> "Service Unavailable"; else -> "OK"
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun readBytes(ins: InputStream, len: Int): ByteArray {
        val b = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = ins.read(b, off, len - off)
            if (n <= 0) break
            off += n
        }
        return if (off == len) b else b.copyOf(off)
    }
}
