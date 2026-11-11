// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.graphics.Color
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.yumenode.ai.draw.DrawPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.ladenthin.llama.LlamaModel
import android.util.Log
import net.ladenthin.llama.args.LogFormat

/**
 * Controlador de carga/descarga de modelos de lenguaje.
 *
 * Extraído de MainActivity: `toggleLoad`, `unloadModel`, `enableLlamaLog`.
 * Mantiene toda la lógica de selección de backend (LiteRT vs llama.cpp),
 * manejo de GPU, y limpieza de estado al descargar.
 */
class ModelLoadController(private val host: ModelLoadHost) : ThemeAliases {

    override val palette: YumenodeTheme get() = host.palette

    /** Cargar el modelo seleccionado o descargar si ya hay uno cargado. */
    fun toggleLoad() {
        if (host.engine != null || host.llamaModel != null) {
            unloadModel()
            return
        }
        val path = host.modelPath
            ?: run { host.toast(host.activity.getString(R.string.s_175)); return }
        val isGguf = ModelLoadDecision.isGguf(path)
        val backendName = host.backendSpinner.selectedItem.toString()
        val thinking = host.thinkCheck.isChecked
        host.applyBusy(true)
        host.setStatus(host.activity.getString(R.string.s_127), C_WARN)
        host.progressBar.visibility = android.view.View.VISIBLE
        host.progressBar.isIndeterminate = true
        host.scope.launch(Dispatchers.IO) {
            try {
                if (isGguf) {
                    host.enableLlamaLog()
                    loadGguf(path, backendName, thinking)
                } else {
                    loadLiteRt(path, backendName, thinking)
                }
                host.activity.runOnUiThread {
                    host.loadButton.text = host.activity.getString(R.string.s_062)
                    host.loadButton.background =
                        host.activity.rounded(C_SOFT_BTN2, 12, strokeDp = 1, strokeColor = C_BORDER)
                    host.loadButton.setTextColor(C_TEXT)
                    host.drawPage?.llmLoaded = true
                    host.updateThinkEnabled()
                    host.addSystemHint(host.activity.getString(R.string.s_077))
                }
            } catch (e: Throwable) {
                host.activity.runOnUiThread {
                    host.setStatus(host.activity.getString(R.string.s_058), C_ERR)
                    host.toast(host.activity.getString(R.string.v_016, e.message))
                }
            } finally {
                host.activity.runOnUiThread {
                    host.progressBar.visibility = android.view.View.GONE
                    host.progressBar.isIndeterminate = false
                    host.applyBusy(false)
                }
            }
        }
    }

    /** Descargar el modelo actualmente cargado. */
    fun unloadModel() {
        try { host.engine?.close() } catch (_: Throwable) {}
        try { host.llamaModel?.close() } catch (_: Throwable) {}
        ChatCore.release()
        host.engine = null
        host.llamaModel = null
        host.markModelUnloaded()
        host.modelSupportsThinking = true
        host.tagReturnIdx = 0
        host.conversation = null
        host.convThinking = null
        host.visionOk = false
        host.drawPage?.llmLoaded = false
        host.loadButton.text = host.activity.getString(R.string.s_060)
        host.loadButton.background = host.activity.rounded(C_PRIMARY, 12)
        host.loadButton.setTextColor(Color.WHITE)
        host.setStatus(host.activity.getString(R.string.s_079), C_IDLE)
        host.addSystemHint(host.activity.getString(R.string.s_080))
        host.updateThinkEnabled()
    }

    /** Habilitar log nativo de llama.cpp (best effort; sin fallar si el runtime no lo soporta). */
    fun enableLlamaLog() {
        try {
            LlamaModel.setLogger(LogFormat.TEXT) { _, msg ->
                Log.i("LlamaCpp", msg)
            }
        } catch (_: Throwable) {
            // Si el runtime no lo soporta, se sigue sin log.
        }
    }

    // ── carga GGUF (llama.cpp) ──

    private fun loadGguf(path: String, backendName: String, thinking: Boolean) {
        host.enableLlamaLog()
        val cpus = InferConfig.threads(host.activity)
        val gpuWanted = ModelLoadDecision.wantsGpu(backendName)
        val gpuAllowed = GpuSupport.gpuAllowed(host.chatPrefs())
        val gpuLayers = ModelLoadDecision.gpuLayers(backendName, gpuAllowed)
        if (gpuLayers != 0) GpuTrial.begin(host.chatPrefs())
        val params = ModelLoadSpecs.llamaParams(
            path = path,
            ctxSize = host.chatParams.ctx(),
            maxOut = host.chatParams.maxOut(),
            threads = cpus,
            mmprojPath = host.mmprojPath,
            gpuLayers = gpuLayers,
            cacheTypeK = KvCacheConfig.type(host.activity),
            cacheTypeV = KvCacheConfig.type(host.activity),
        )
        val m = LlamaModel(params)
        val vok = try { m.supportsVision() } catch (_: Throwable) { false }
        val onGpu =
            ModelLoadDecision.actuallyOnGpu(backendName, GpuSupport.isVulkanBuiltIn(), gpuAllowed)
        host.activity.runOnUiThread {
            host.llamaModel = m
            GpuTrial.clear(host.chatPrefs())
            host.visionOk = vok
            ChatCore.attachGguf(m, path.substringAfterLast('/'), vok)
            host.modelSupportsThinking =
                host.guessSupportsThinking(path.substringAfterLast('/'))
            host.markModelLoaded(path)
            host.tagReturnIdx = 0
            host.conversation = null
            host.convThinking = null
            host.setStatus(
                if (onGpu) host.activity.getString(R.string.s_078_gpu)
                else host.activity.getString(R.string.s_078),
                C_OK,
            )
            if (gpuWanted && !onGpu) {
                host.toast(
                    host.activity.getString(
                        if (GpuSupport.isVulkanBuiltIn()) R.string.s_560 else R.string.s_016,
                    )
                )
            }
        }
    }

    // ── carga LiteRT (Engine) ──

    private fun loadLiteRt(path: String, backendName: String, thinking: Boolean) {
        val gpuWanted = ModelLoadDecision.wantsGpu(backendName)
        var usedBackendName = backendName
        val vok = host.probeVision(path)
        val cfg = ModelLoadSpecs.engineConfig(
            path = path,
            maxTokens = host.chatParams.ctx(),
            gpuWanted = gpuWanted,
            visionOk = vok,
            maxImages = AttachmentHandler.MAX_ATTACH,
            cacheDir = host.appCacheDir.absolutePath,
        )
        val eng: Engine = try {
            val g = Engine(cfg)
            g.initialize()
            g
        } catch (e: Throwable) {
            if (!gpuWanted) throw e
            host.activity.runOnUiThread {
                host.toast(host.activity.getString(R.string.s_335))
            }
            usedBackendName = "CPU"
            val cpu = Engine(
                ModelLoadSpecs.cpuEngineConfig(
                    path = path,
                    maxTokens = host.chatParams.ctx(),
                    visionOk = vok,
                    maxImages = AttachmentHandler.MAX_ATTACH,
                    cacheDir = host.appCacheDir.absolutePath,
                )
            )
            cpu.initialize()
            cpu
        }
        val conv =
            eng.createConversation(host.inferenceBuilder.configFor(host.currentSession, thinking))
        host.activity.runOnUiThread {
            host.engine = eng
            host.llamaModel = null
            ChatCore.attachLiteRt(eng, path.substringAfterLast('/'), vok)
            host.modelSupportsThinking = host.probeThinking(path)
            host.markModelLoaded(path)
            host.tagReturnIdx = 0
            host.conversation = conv
            host.convThinking = thinking
            host.visionOk = vok
            host.convDrawCapable = host.drawCapable
            host.setStatus(
                host.activity.getString(R.string.v_015, usedBackendName),
                C_OK,
            )
        }
    }
}