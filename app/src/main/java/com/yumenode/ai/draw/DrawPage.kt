// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai.draw

import com.yumenode.ai.DrawCore
import com.yumenode.ai.GpuSupport
import com.yumenode.ai.GpuTrial
import com.yumenode.ai.DrawMemoryMode
import com.yumenode.ai.InferConfig
import com.yumenode.ai.YumenodeTheme
import com.yumenode.ai.R
import com.yumenode.ai.dp
import com.yumenode.ai.matchWrap
import com.yumenode.ai.toast

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Toast
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Thrown when the user cancels generation (distinct from a real failure). Message is not user-facing; fixed English. */
class GenerationCancelledException : RuntimeException("Generation cancelled")

/**
 * Draw page (text-to-image).
 *
 * Engine is **self-built stable-diffusion.cpp** (libstable-diffusion.so + libponko_sd.so JNI bridge), reading `.gguf` draw models
 * (Anything V5 / ChilloutMix / Pony and other SD1.5-family ones; nearly all have GGUF builds on CivitAI).
 *
 * Two **independent** acceleration paths, stackable:
 *  - **Quantization**: set by the model file itself (Q4_0 costs much less per step than Q8_0). App only reads and shows it, cannot convert on the fly.
 *  - **LoRA**: attach an LCM-LoRA-style "few-step distillation patch", compressing 20 steps to 4~8 (~5x).
 *
 * LoRA files live in filesDir/draw/lora/, activated via the `lora:名字:权重` syntax in the prompt.
 */
class DrawPage(
    private val act: Activity,
    private val scope: CoroutineScope,
    /**
     * Palette (light / dark decided by MainActivity).
     * The three below are just convenience accessors; a theme switch rebuilds the whole Activity and the page is redrawn.
     */
    pal: YumenodeTheme,
) {
    /** Palette; on theme switch call setPalette then build() to rebuild this page's views (native handle and other fields kept) */
    var pal: YumenodeTheme = pal
        private set

    private val primary: Int get() = pal.primary
    private val textColor: Int get() = pal.text
    private val subText: Int get() = pal.subText

    /** Switch palette (applies on the next build()) */
    fun setPalette(p: YumenodeTheme) { pal = p }

    /** Whether an image is being generated (theme switch rebuilds views; not safe to rebuild mid-generation) */
    fun isBusy(): Boolean = generating

    companion object {
        const val DIR_NAME = "draw"
        /** Tagger model / tag table dir: filesDir/tagger */
        const val TAGGER_DIR = "tagger"

        /** Size presets (square, px). sd.cpp requires multiples of 64; all of these are aligned. */
        private val SIZE_PRESETS = listOf(64, 128, 256, 384, 512)
        private const val REQ_IMAGE = 0x5D02
        private const val REQ_TAGGER_IMAGE = 0x5D03

        /**
         * TAESD (Tiny AutoEncoder): distilled mini VAE replacing the full VAE for encode/decode.
         * En CPU es MUCH más rápido que el VAE completo (un orden de magnitud menos
         * de cómputo) y usa mucho menos pico de memoria.
         * El archivo diffusion_pytorch_model.safetensors trae encoder + decoder juntos,
         * que es lo que espera sd.cpp en el slot taesd (prefijo "tae.").
         */
        private const val TAESD_HF_PATH =
            "madebyollin/taesd/resolve/main/diffusion_pytorch_model.safetensors"

        /** Tamaño mínimo razonable del archivo TAESD (fuente única: [ModelSetStore]) */
        private const val TAESD_MIN_BYTES = ModelSetStore.TAESD_MIN_BYTES

        /** LCM-LoRA: official HuggingFace repo path (actual file name is pytorch_lora_weights.safetensors) */
        private const val LORA_HF_PATH =
            "latent-consistency/lcm-lora-sdv1-5/resolve/main/pytorch_lora_weights.safetensors"

        /** Renamed to this after download; referenced in the prompt as `lora:lcm-lora-sdv1-5:1` */
        const val LORA_NAME = "lcm-lora-sdv1-5"

        /** Single file / multi file (fuente única: [ModelSetStore]) */
        const val KIND_SINGLE = ModelSetStore.KIND_SINGLE
        const val KIND_MULTI = ModelSetStore.KIND_MULTI

        /** Family id */
        const val FAM_SD15 = "sd15"
        const val FAM_SDXL = "sdxl"
        const val FAM_SD3 = "sd3"
        const val FAM_FLUX = "flux"
        const val FAM_QWEN = "qwen"
        const val FAM_HIDREAM = "hidream"
        const val FAM_VIDEO = "video"
        const val FAM_OTHER = "other"

        /** Family display name */
        fun familyLabelRes(family: String): Int = when (family) {
            FAM_SD15 -> R.string.s_337
            FAM_SDXL -> R.string.s_338
            FAM_SD3 -> R.string.s_339
            FAM_FLUX -> R.string.s_340
            FAM_QWEN -> R.string.s_341
            FAM_HIDREAM -> R.string.s_342
            FAM_VIDEO -> R.string.s_343
            else -> R.string.s_344
        }

        /** Slot display name */
        fun slotLabelRes(key: String): Int = when (key) {
            "model" -> R.string.s_347
            "diffusion" -> R.string.s_348
            "high_noise" -> R.string.s_349
            "uncond" -> R.string.s_350
            "clip_l" -> R.string.s_351
            "clip_g" -> R.string.s_352
            "clip_vision" -> R.string.s_353
            "t5xxl" -> R.string.s_354
            "llm" -> R.string.s_355
            "llm_vision" -> R.string.s_356
            "vae" -> R.string.s_357
            "taesd" -> R.string.s_442
            else -> R.string.s_358
        }

        /** Which slots a kind needs (key to whether required).
         *  Companion files for multi-file models differ per family; templates here are per family, and slots are listed from the template once the user picks a kind.
         *  Required is only a pre-load check and does not block a try (optional slots may be left empty). */
        fun familySlots(kind: String, family: String): List<Pair<String, Boolean>> {
            val base = when {
                kind == KIND_SINGLE && family == FAM_OTHER -> listOf(
                    "model" to true, "vae" to false, "clip_l" to false, "clip_g" to false,
                    "clip_vision" to false, "t5xxl" to false, "llm" to false, "llm_vision" to false,
                    "diffusion" to false, "high_noise" to false, "uncond" to false, "audio_vae" to false)
                kind == KIND_SINGLE -> listOf("model" to true, "vae" to false)
                family == FAM_SDXL -> listOf(
                    "diffusion" to true, "clip_l" to true, "clip_g" to true, "vae" to true)
                family == FAM_SD3 -> listOf(
                    "diffusion" to true, "clip_l" to true, "clip_g" to true, "t5xxl" to true, "vae" to true)
                family == FAM_FLUX -> listOf(
                    "diffusion" to true, "clip_l" to true, "t5xxl" to true, "vae" to true)
                family == FAM_QWEN -> listOf(
                    "diffusion" to true, "llm" to true, "llm_vision" to true, "vae" to true)
                family == FAM_HIDREAM -> listOf(
                    "diffusion" to true, "high_noise" to false, "clip_l" to false, "clip_g" to false,
                    "t5xxl" to false, "llm" to false, "vae" to true)
                family == FAM_VIDEO -> listOf(
                    "diffusion" to true, "clip_vision" to false, "t5xxl" to false, "llm" to false,
                    "vae" to true, "audio_vae" to false)
                else -> listOf(
                    "diffusion" to true, "vae" to false, "clip_l" to false, "clip_g" to false,
                    "clip_vision" to false, "t5xxl" to false, "llm" to false, "llm_vision" to false,
                    "high_noise" to false, "uncond" to false, "audio_vae" to false)
            }
            // TAESD is an optional extra, shown only for image models:
            // video uses TinyVideoAutoEncoder (different weight format), and Qwen / HiDream VAEs take special paths, so not exposed yet.
            return if (family == FAM_VIDEO || family == FAM_QWEN || family == FAM_HIDREAM) base
            else base + ("taesd" to false)
        }

        /** SharedPreferences file name and keys (UI prefs only) */
        private const val PREFS_NAME = "ponko"
        private const val KEY_USE_LORA = "useLora"

        // Draw param persistence (prompt is not saved per user request; starts over each time)
        private const val KEY_W = "drawW"
        private const val KEY_H = "drawH"
        private const val KEY_STEPS = "drawSteps"
        private const val KEY_CFG = "drawCfg"
        private const val KEY_SEED = "drawSeed"
        private const val KEY_LORA_SCALE = "drawLoraScale"
        private const val KEY_UPSCALE_STRENGTH = "upscaleStrength"
        /** Upscale mode: 0 = img2img redraw, 1 = sd.cpp native hires fix */
        private const val KEY_UPSCALE_MODE = "upscaleMode"
        /** Upscaler index for native hires fix */
        private const val KEY_UPSCALE_UPSCALER = "upscaleUpscaler"
        /** VAE tiling (tiled encode/decode) toggle */
        private const val KEY_VAE_TILING = "vaeTiling"
        /** VAE tiling tile size (px) */
        private const val KEY_VAE_TILE_SIZE = "vaeTileSize"
        /** VAE tiling tile overlap (0–0.5) */
        private const val KEY_VAE_TILE_OVERLAP = "vaeTileOverlap"
        private const val KEY_SAMPLER = "drawSampler"
        private const val KEY_SCHEDULER = "drawScheduler"

        // img2img params (stored separately from text-to-image)
        private const val KEY_I2I_W = "i2iW"
        private const val KEY_I2I_H = "i2iH"
        private const val KEY_I2I_STEPS = "i2iSteps"
        private const val KEY_I2I_CFG = "i2iCfg"
        private const val KEY_I2I_SEED = "i2iSeed"
        private const val KEY_I2I_STRENGTH = "i2iStrength"
        private const val KEY_I2I_LORA_SCALE = "i2iLoraScale"
        private const val KEY_I2I_SAMPLER = "i2iSampler"
        private const val KEY_I2I_SCHEDULER = "i2iScheduler"

        // Tagging (Tagger)
        private const val KEY_TAG_THRESHOLD = "tagThreshold"
        private const val KEY_TAG_TOPK = "tagTopK"
        private const val KEY_TAG_CHAN = "tagChan"   // 0 = BGR (WD default), 1 = RGB
    }

    private val c: Context get() = act

    /** Context handle of the self-built sd.cpp (0 = not loaded) */
    private var sdHandle: Long = 0L
    private var mainModel: File? = null
    private var vaeModel: File? = null

    /** Current model set (single file = main model only; multi file = main model + companion file per slot) */
    private var activeSet: ModelSet? = null

    /**
     * Persistencia de los model sets (JSON en files/draw/sets + ficheros sueltos).
     * Extraido de esta clase para que la logica de disco quede testeable en JVM.
     */
    private val setStore: ModelSetStore = ModelSetStore(c.filesDir, prefs())
    /** Kind of the next import (set by the type menu on the "Models" page) */
    var pendingKind: String = KIND_SINGLE
    var pendingFamily: String = FAM_SD15
    /** true = the next imported file opens a new model set */
    var pendingNewSet: Boolean = false

    /** Inference thread count: **global setting** ([InferConfig]), shared by draw / chat gguf / tagger paths.
     *  Read on demand, not cached — change it in "About → Settings" and the next model load uses the new value. */
    private val nThreads: Int get() = InferConfig.threads(c)

    /** FlashAttention (CLIP + UNet). Measured ~28% slower per step when off (6 steps 125s vs 98s). */
    private var flashAttn: Boolean = true

    /** External (MainActivity) notice: an LLM is currently loaded. Lets the "Generate" button give a more accurate hint. */
    var llmLoaded: Boolean = false

    /** Status callback: sync draw page progress/result to the main UI top bar. (text, isError) */
    var onStatus: ((String, Boolean) -> Unit)? = null

    /** Run mode: true = try GPU (Vulkan), false = CPU only. Set by the "run mode" on the model page. */
    var useGpu: Boolean = false

    /** Backend actually used for drawing ("CPU" / "Vulkan0"), shown in the status line */
    private var activeBackend: String = "CPU"

    /**
     * Whether LoRA acceleration is enabled (attach LCM-LoRA, reduce steps).
     * State persisted to SharedPreferences — otherwise it would be re-checked on every app start.
     */
    var useLora: Boolean = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_USE_LORA, false)
        set(value) {
            field = value
            c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USE_LORA, value).apply()
        }

    /** Currently selected LoRA (null = take the first in the directory) */
    private var loraFile: File? = null

    /**
     * Catalogo de LoRA descargadas/importadas.
     * Extraido de esta clase para que la regla de seleccion quede testeable.
     */
    private val loraStore: LoraStore = LoraStore(c.filesDir, prefs())

    /**
     * Catalogo del tagger (modelo ONNX + tabla de tags) y decision de recarga.
     * Extraido de esta clase para que ambas reglas queden testeables.
     */
    private val taggerStore: TaggerStore = TaggerStore(c.filesDir, prefs())

    /** Callback when the SD pipeline is ready (MainActivity switches to draw mode on it) */
    var onPipelineReady: (() -> Unit)? = null

    /** Whether ready (can generate) */
    fun isReady(): Boolean = sdHandle != 0L

    /**
     * Sync the **already parsed draw params** to [DrawCore] for the API's draw endpoint.
     *
     * Must be called on the main thread: EditText can only be read there, and views must not be relied on after the Activity is destroyed,
     * so DrawCore stores "values", not view references.
     */
    fun syncDrawCore() {
        if (!::stepsEdit.isInitialized || sdHandle == 0L) return
        val lora = if (useLora) activeLora() else null
        DrawCore.updateParams(
            DrawCore.Params(
                steps = DrawParamsLogic.steps(stepsEdit.text.toString()),
                cfg = DrawParamsLogic.cfg(cfgEdit.text.toString()),
                width = DrawParamsLogic.size(widthEdit.text.toString()),
                height = DrawParamsLogic.size(heightEdit.text.toString()),
                negative = negEdit.text.toString(),
                sampler = pickSampler(lora != null),
                scheduler = pickScheduler(lora != null),
                loraPath = lora?.absolutePath,
                loraScale = DrawParamsLogic.loraScale(loraScaleEdit.text.toString()),
            )
        )
    }

    // Views
    private lateinit var statusText: TextView
    private lateinit var quantText: TextView
    private lateinit var modelCardTitle: TextView
    private lateinit var promptEdit: EditText
    private lateinit var negEdit: EditText
    private lateinit var stepsEdit: EditText
    private lateinit var cfgEdit: EditText
    private lateinit var seedEdit: EditText
    private lateinit var loraCheck: CheckBox
    private lateinit var loraHint: TextView
    private lateinit var genBtn: Button

    /** Whether generating (the same button toggles between "Start generation / Cancel generation") */
    private var generating = false
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    // Width / height (free input; sd.cpp requires multiples of 64)
    private lateinit var widthEdit: EditText
    private lateinit var heightEdit: EditText
    /** Preset size button group: one tap fills both width/height inputs with the matching value */
    private val sizePresetButtons = mutableListOf<android.widget.Button>()
    /** Currently selected preset size (0 = custom); null = size does not match a preset */
    private var selectedSizePreset: Int? = null
    /** Custom size row: only shown when "Personalizado" is selected */
    private var customSizeRow: View? = null
    /** Set true when width/height are changed programmatically, so the watcher doesn't clear the preset highlight */
    private var suppressPresetSync = false

    // Sampler / scheduler (item 0 = auto)
    private lateinit var samplerSpinner: android.widget.Spinner
    private lateinit var schedulerSpinner: android.widget.Spinner

    /** LoRA weight */
    private lateinit var loraScaleEdit: EditText

    // Top menu: params / result
    private lateinit var tabParams: TextView
    private lateinit var tabResult: TextView
    private lateinit var paramPane: LinearLayout
    private lateinit var resultPane: LinearLayout
    private lateinit var pager: ViewPager

    // Result panel
    private lateinit var resultImg: ImageView
    private lateinit var resultInfo: TextView
    /** Cancel button (pestaña Resultado): only while generating, hidden otherwise */
    private lateinit var resultCancelBtn: Button
    /** Upscale progress bar (pestaña Resultado): only while upscaling */
    private lateinit var upscaleProgressBar: ProgressBar
    /** Upscale progress text (pestaña Resultado) */
    private lateinit var upscaleProgressText: TextView
    /** Upscale mode: 0 = img2img redraw, 1 = sd.cpp native hires fix */
    private lateinit var upscaleModeSpinner: android.widget.Spinner
    /** Upscaler used by native hires fix */
    private lateinit var upscaleUpscalerSpinner: android.widget.Spinner
    /** VAE tiling toggle */
    private lateinit var vaeTilingCheck: CheckBox
    /** VAE tiling tile size */
    private lateinit var vaeTileSizeEdit: EditText
    /** VAE tiling tile overlap */
    private lateinit var vaeTileOverlapEdit: EditText
    private lateinit var saveBtn: Button
    private lateinit var upscaleBtn: Button
    private lateinit var upscaleStrengthBar: SeekBar
    private lateinit var upscaleStrengthText: TextView
    private lateinit var historyRow: LinearLayout
    private lateinit var histEmpty: TextView

    /** Last generated image (used by "Save to gallery" on the result page) */
    private var lastImage: Bitmap? = null

    // ---- Result panel subpages: result / process (live preview of generation) ----
    private lateinit var tabResResult: TextView
    private lateinit var tabResProcess: TextView
    private lateinit var resultContent: LinearLayout
    private lateinit var processContent: LinearLayout
    private lateinit var previewImg: ImageView
    private lateinit var procStepText: TextView
    private lateinit var procProgressBar: ProgressBar
    /** Current subpage of the result panel: 0=result 1=process */
    private var resultPage = 0
    /** Preview throttle: drop a frame while the previous one is still drawing */
    @Volatile private var previewBusy = false

    // ---- Mode: text-to-image / img2img ----
    private lateinit var tabT2i: TextView
    private lateinit var tabI2i: TextView
    private lateinit var t2iContent: LinearLayout
    private lateinit var i2iContent: LinearLayout

    /** Current params page mode: 0=text-to-image 1=img2img 2=Tagger */
    private var mode = 0

    // ---- img2img-only views ----
    private lateinit var i2iPromptEdit: EditText
    private lateinit var i2iNegEdit: EditText
    private lateinit var i2iWidthEdit: EditText
    private lateinit var i2iHeightEdit: EditText
    private lateinit var i2iStepsEdit: EditText
    private lateinit var i2iCfgEdit: EditText
    private lateinit var i2iSeedEdit: EditText
    private lateinit var i2iStrengthEdit: EditText
    private lateinit var i2iSamplerSpinner: android.widget.Spinner
    private lateinit var i2iSchedulerSpinner: android.widget.Spinner
    private lateinit var i2iLoraCheck: CheckBox
    private lateinit var i2iLoraScaleEdit: EditText
    private lateinit var i2iLoraHint: TextView
    private lateinit var i2iGenBtn: Button
    private lateinit var i2iProgressText: TextView
    private lateinit var i2iProgressBar: ProgressBar
    private lateinit var i2iPreview: ImageView
    private lateinit var i2iPickBtn: Button
    private lateinit var i2iImgHint: TextView

    /** Reference image picked for img2img (memory only; gone when the process ends) */
    private var i2iBitmap: Bitmap? = null

    // ---- Tagger-only views ----
    private lateinit var tabTagger: TextView
    private lateinit var taggerContent: LinearLayout
    private lateinit var taggerPreview: ImageView
    private lateinit var taggerPickBtn: Button
    private lateinit var taggerImgHint: TextView
    private lateinit var taggerRunBtn: Button
    private lateinit var taggerProgressText: TextView
    private lateinit var taggerProgressBar: ProgressBar
    private lateinit var taggerThresholdEdit: EditText
    private lateinit var taggerTopKEdit: EditText
    private lateinit var taggerChanSpinner: android.widget.Spinner

    /** ScrollViews of the params / result panes (used to jump back to the top) */
    private lateinit var paramScroll: ScrollView
    private lateinit var resultScroll: ScrollView
    private lateinit var taggerOut: TextView
    private lateinit var taggerSendT2iBtn: Button
    private lateinit var taggerSendI2iBtn: Button

    /** Input image picked for Tagger (memory only) */
    private var taggerBitmap: Bitmap? = null

    /** Latest tagger result (comma-separated tag text) */
    private var taggerTags: String = ""

    /** "Send to img2img / Send to Tagger" buttons on the result page */
    private lateinit var resultToI2iBtn: Button
    private lateinit var resultToTaggerBtn: Button

    /** Save image to gallery: implementation injected by MainActivity (reuses its MediaStore logic). Params: image + file name */
    var onSaveImage: ((Bitmap, String) -> Unit)? = null

    fun build(): View {
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bgAlt)
        }

        // ---- Top menu: params / result (switch by tap or swipe) ----
        val tabBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pal.surface)
            setPadding(c.dp(16), c.dp(12), c.dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        tabParams = tabItem(c.getString(R.string.s_034))
        tabResult = tabItem(c.getString(R.string.s_203))
        tabParams.setOnClickListener { switchPane(toResult = false) }
        tabResult.setOnClickListener { switchPane(toResult = true) }
        tabBar.addView(tabParams, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(tabResult, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabBar)

        // ---- Params panel ----
        // Content container; each is wrapped in a ScrollView, then handed to ViewPager2 for swipe paging
        paramPane = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(12))
        }
        // Text-to-image content container: prompt/buttons/params below all go in here first,
        // then into the mode container together with the "img2img" page, switched by sub-tabs.
        t2iContent = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

        // ---- Model status (models are selected and loaded on the "Models" page) ----
        val modelCard = card()
        modelCardTitle = title(c.getString(R.string.s_164))
        modelCard.addView(modelCardTitle)
        statusText = body(c.getString(R.string.s_115))
        modelCard.addView(statusText)
        quantText = TextView(c).apply {
            textSize = 11.5f
            setTextColor(subText)
            setPadding(0, c.dp(6), 0, 0)
        }
        modelCard.addView(quantText)
        paramPane.addView(modelCard)

        // ---- Mode sub-tabs: text-to-image / img2img ----
        val modeBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pal.surface)
            setPadding(c.dp(8), c.dp(2), c.dp(8), c.dp(2))
        }
        tabT2i = modeTab(c.getString(R.string.s_207))
        tabI2i = modeTab(c.getString(R.string.s_208))
        tabTagger = modeTab(c.getString(R.string.s_221))
        tabT2i.setOnClickListener { switchMode(0) }
        tabI2i.setOnClickListener { switchMode(1) }
        tabTagger.setOnClickListener { switchMode(2) }
        modeBar.addView(tabT2i, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modeBar.addView(tabI2i, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modeBar.addView(tabTagger, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        paramPane.addView(modeBar, c.matchWrap())

        // ---- Prompt ----
        val promptCard = card()
        promptCard.addView(title(c.getString(R.string.s_108)))
        promptEdit = labeledEdit(c.getString(R.string.s_036), singleLine = false, minLines = 3)
        promptCard.addView(promptEdit, c.matchWrap(top = 6))
        promptCard.addView(smallLabel(c.getString(R.string.s_177)))
        negEdit = labeledEdit(c.getString(R.string.s_216), singleLine = false, minLines = 2)
        promptCard.addView(negEdit, c.matchWrap(top = 4))
        t2iContent.addView(promptCard)

        // ---- Generate / cancel (same button), placed between prompt and params so progress is easy to watch ----
        genBtn = Button(c).apply {
            text = c.getString(R.string.s_098)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { if (generating) doCancel() else generateFromUi() }
        }
        t2iContent.addView(genBtn, c.matchWrap(top = 2))

        // Premium: batch generation button (visible only on premium flavor)
        if (com.yumenode.ai.FeatureToggleManager.enableBatchGeneration) {
            batchBtn = Button(c).apply {
                text = c.getString(R.string.s_575)
                setBackgroundColor(Color.argb(40, 255, 215, 0))
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener { generateBatch() }
            }
            t2iContent.addView(batchBtn, c.matchWrap(top = 2))
        }

        progressText = TextView(c).apply {
            textSize = 12f
            setTextColor(subText)
            visibility = View.GONE
            setPadding(0, c.dp(8), 0, 0)
        }
        t2iContent.addView(progressText)

        progressBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        t2iContent.addView(progressBar, c.matchWrap(top = 4))

        // ---- Params ----
        val paramCard = card()
        paramCard.addView(title(c.getString(R.string.s_065)))

        // Size: preset button group (64→512 + Personalizado), picking a preset fills width/height directly
        widthEdit = smallNumber(loadParam(KEY_W, defOf("t2i", "w", "512")))
        heightEdit = smallNumber(loadParam(KEY_H, defOf("t2i", "h", "512")))
        paramCard.addView(sizePresetRow())

        // Sampler / scheduler
        samplerSpinner = choiceSpinner(
            listOf(c.getString(R.string.s_169)) + SdCppEngine.Sampler.entries.map { it.label }
        )
        schedulerSpinner = choiceSpinner(
            listOf(c.getString(R.string.s_168)) + SdCppEngine.Scheduler.entries.map { it.label }
        )
        samplerSpinner.setSelection(loadInt(KEY_SAMPLER, defOfInt("t2i", "sampler", 0)).coerceIn(0, samplerSpinner.adapter.count - 1), false)
        schedulerSpinner.setSelection(loadInt(KEY_SCHEDULER, defOfInt("t2i", "scheduler", 0)).coerceIn(0, schedulerSpinner.adapter.count - 1), false)
        // Restore first, then attach the listener, so setSelection doesn't write the default back
        samplerSpinner.onItemSelectedListener = persistSpinner(KEY_SAMPLER)
        schedulerSpinner.onItemSelectedListener = persistSpinner(KEY_SCHEDULER)
        paramCard.addView(paramRow(c.getString(R.string.s_191), samplerSpinner, c.getString(R.string.s_015)))
        paramCard.addView(paramRow(c.getString(R.string.s_176), schedulerSpinner, c.getString(R.string.s_014)))

        stepsEdit = smallNumber(loadParam(KEY_STEPS, defOf("t2i", "steps", builtinSteps())))
        cfgEdit = smallNumber(loadParam(KEY_CFG, defOf("t2i", "cfg", builtinCfg())))
        seedEdit = smallNumber(loadParam(KEY_SEED, defOf("t2i", "seed", "-1")))
        bindParam(widthEdit, KEY_W)
        bindParam(heightEdit, KEY_H)
        bindParam(stepsEdit, KEY_STEPS)
        bindParam(cfgEdit, KEY_CFG)
        bindParam(seedEdit, KEY_SEED)
        paramCard.addView(paramRow(c.getString(R.string.s_192), stepsEdit, c.getString(R.string.s_179)))
        paramCard.addView(paramRow(c.getString(R.string.s_013), cfgEdit, c.getString(R.string.s_178)))
        paramCard.addView(paramRow(c.getString(R.string.s_193), seedEdit, c.getString(R.string.s_008)))

        // ---- LoRA acceleration toggle ----
        loraCheck = CheckBox(c).apply {
            text = c.getString(R.string.s_022)
            textSize = 13f
            setTextColor(textColor)
            isChecked = useLora
            setPadding(0, c.dp(10), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                useLora = checked
                // Switching LoRA applies that state's default set (one per on/off, overridable via "Set as default")
                if (checked && activeLora() == null) c.toast(c.getString(R.string.s_186), short = true)
                if (::i2iLoraCheck.isInitialized) i2iLoraCheck.isChecked = checked
                refreshLoraHint()
                if (::i2iContent.isInitialized) {
                    applyDefaults("t2i")
                    applyDefaults("i2i")
                }
            }
        }
        paramCard.addView(loraCheck)
        loraScaleEdit = smallNumber(loadParam(KEY_LORA_SCALE, defOf("t2i", "loraScale", "1.0")))
        bindParam(loraScaleEdit, KEY_LORA_SCALE)
        paramCard.addView(paramRow(c.getString(R.string.s_024), loraScaleEdit, c.getString(R.string.s_037)))
        // ---- VAE tiling: tiled encode/decode (saves memory on large images) ----
        vaeTilingCheck = CheckBox(c).apply {
            text = c.getString(R.string.s_544)
            textSize = 13f
            setTextColor(textColor)
            isChecked = loadParam(KEY_VAE_TILING, "0") == "1"
            setPadding(0, c.dp(10), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs().edit().putString(KEY_VAE_TILING, if (checked) "1" else "0").apply()
                refreshVaeTilingRows()
            }
        }
        paramCard.addView(vaeTilingCheck)
        vaeTileSizeEdit = smallNumber(loadParam(KEY_VAE_TILE_SIZE, "512"))
        vaeTileOverlapEdit = smallNumber(loadParam(KEY_VAE_TILE_OVERLAP, "0.25"))
        bindParam(vaeTileSizeEdit, KEY_VAE_TILE_SIZE)
        bindParam(vaeTileOverlapEdit, KEY_VAE_TILE_OVERLAP)
        val vaeTileSizeRow = paramRow(c.getString(R.string.s_546), vaeTileSizeEdit, c.getString(R.string.s_545))
        val vaeTileOverlapRow = paramRow(c.getString(R.string.s_547), vaeTileOverlapEdit, "")
        paramCard.addView(vaeTileSizeRow)
        paramCard.addView(vaeTileOverlapRow)

        loraHint = TextView(c).apply {
            textSize = 11f
            setTextColor(subText)
            setPadding(0, c.dp(2), 0, 0)
        }
        paramCard.addView(loraHint)
        t2iContent.addView(paramCard)
        t2iContent.addView(defaultButtonsRow("t2i"), c.matchWrap(top = 10))

        // ---- img2img page ----
        i2iContent = buildI2iContent()

        // ---- Tagger page ----
        taggerContent = buildTaggerContent()

        // Mode container: switched via visibility, not a nested ViewPager,
        // otherwise it would fight the outer "params/result" swipe paging for gestures.
        val paramHost = FrameLayout(c)
        paramHost.addView(t2iContent, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        paramHost.addView(i2iContent, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        paramHost.addView(taggerContent, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        paramPane.addView(paramHost, c.matchWrap())

        // ---- Result panel ----
        resultPane = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(12))
        }

        // ---- Sub-tabs inside the result panel: result / process (process page refreshes the preview step by step) ----
        val resBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pal.surface)
            setPadding(c.dp(8), c.dp(2), c.dp(8), c.dp(2))
        }
        tabResResult = modeTab(c.getString(R.string.s_203))
        tabResProcess = modeTab(c.getString(R.string.s_373))
        tabResResult.setOnClickListener { switchResultPage(0) }
        tabResProcess.setOnClickListener { switchResultPage(1) }
        resBar.addView(tabResResult, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        resBar.addView(tabResProcess, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        resultPane.addView(resBar, c.matchWrap())

        resultContent = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        resultPane.addView(resultContent, c.matchWrap())
        processContent = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        resultPane.addView(processContent, c.matchWrap())

        // Process page: step-by-step preview + step progress
        val procCard = card()
        procCard.addView(title(c.getString(R.string.s_373)))
        previewImg = ImageView(c).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(pal.fieldBg)
            setPadding(c.dp(4), c.dp(6), c.dp(4), c.dp(6))
            minimumHeight = c.dp(180)
        }
        procCard.addView(previewImg, c.matchWrap(top = 6))
        procStepText = TextView(c).apply {
            textSize = 12f
            setTextColor(subText)
            text = c.getString(R.string.s_375)
        }
        procCard.addView(procStepText, c.matchWrap(top = 8))
        procProgressBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        procCard.addView(procProgressBar, c.matchWrap(top = 6))
        procCard.addView(TextView(c).apply {
            text = c.getString(R.string.s_374)
            textSize = 11f
            setTextColor(subText)
            setPadding(0, c.dp(6), 0, 0)
        })
        processContent.addView(procCard)

        val resultCard = card()
        resultCard.addView(title(c.getString(R.string.s_148)))
        resultImg = ImageView(c).apply {
            adjustViewBounds = true
            setPadding(c.dp(4), c.dp(10), c.dp(4), c.dp(8))
        }
        resultCard.addView(resultImg, c.matchWrap())
        resultInfo = TextView(c).apply {
            textSize = 12f
            setTextColor(subText)
            text = c.getString(R.string.s_184)
        }
        resultCard.addView(resultInfo)

        // Cancel generation: appears only while generating (hidden otherwise), tap interrupts native inference
        resultCancelBtn = Button(c).apply {
            text = c.getString(R.string.s_536)
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(pal.stop)
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            setOnClickListener {
                doCancel()
                c.toast(c.getString(R.string.s_125), short = true)
            }
        }
        resultCard.addView(resultCancelBtn, c.matchWrap(top = 8))
        resultCard.addView(TextView(c).apply {
            text = c.getString(R.string.s_537)
            textSize = 10.5f
            setTextColor(subText)
            setPadding(0, c.dp(3), 0, 0)
        })

        saveBtn = Button(c).apply {
            text = c.getString(R.string.s_045)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                val b = lastImage
                if (b == null) c.toast(c.getString(R.string.s_183), short = true) else onSaveImage?.invoke(b, saveName())
            }
        }

        // Upscale (2×): redraw this image as the reference via the "img2img" path — same composition, finer detail
        upscaleBtn = Button(c).apply {
            text = c.getString(R.string.s_524)
            textSize = 13f
            setTextColor(primary)
            setBackgroundColor(pal.primarySoft)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { upscaleLast() }
        }
        resultCard.addView(upscaleBtn, c.matchWrap(top = 10))

        // Upscale progress: bar + text (shown only while upscaling)
        upscaleProgressText = TextView(c).apply {
            textSize = 11.5f
            setTextColor(subText)
            visibility = View.GONE
            setPadding(0, c.dp(6), 0, 0)
        }
        resultCard.addView(upscaleProgressText, c.matchWrap(top = 8))
        upscaleProgressBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(primary)
        }
        resultCard.addView(upscaleProgressBar, c.matchWrap(top = 4))

        // Upscale strength: SeekBar (0.05–0.99), value shown live on the right
        val strengthRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        strengthRow.addView(smallLabel(c.getString(R.string.s_529)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        upscaleStrengthText = TextView(c).apply {
            textSize = 12f
            setTextColor(primary)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        strengthRow.addView(upscaleStrengthText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        resultCard.addView(strengthRow, c.matchWrap(top = 10))

        upscaleStrengthBar = SeekBar(c).apply {
            max = 94              // 0.05 → 0.99, paso 0.01
            // roundToInt, no toInt: 0.35f * 100 = 34.999… y toInt() trunca a 34 (→ 0.34)
            progress = ((loadParam(KEY_UPSCALE_STRENGTH, "0.35").toFloatOrNull() ?: 0.35f) * 100f).roundToInt() - 5
            progressTintList = android.content.res.ColorStateList.valueOf(primary)
            thumbTintList = android.content.res.ColorStateList.valueOf(primary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    updateUpscaleStrengthLabel()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val v = (sb?.progress ?: 0) / 100f + 0.05f
                    prefs().edit().putString(KEY_UPSCALE_STRENGTH, v.toString()).apply()
                }
            })
        }
        resultCard.addView(upscaleStrengthBar, c.matchWrap(top = 4))
        resultCard.addView(TextView(c).apply {
            text = c.getString(R.string.s_531)
            textSize = 10.5f
            setTextColor(subText)
            setPadding(0, c.dp(3), 0, 0)
        })
        updateUpscaleStrengthLabel()

        // Upscale mode: img2img redraw or sd.cpp native hires fix
        upscaleModeSpinner = choiceSpinner(
            listOf(c.getString(R.string.s_538), c.getString(R.string.s_539))
        )
        upscaleModeSpinner.setSelection(
            loadInt(KEY_UPSCALE_MODE, 1).coerceIn(0, 1), false)
        upscaleModeSpinner.onItemSelectedListener = persistSpinner(KEY_UPSCALE_MODE)
        resultCard.addView(
            paramRow(c.getString(R.string.s_540), upscaleModeSpinner, c.getString(R.string.s_541)),
            c.matchWrap(top = 10)
        )

        // Upscaler for native hires fix (only meaningful with hires nativo selected; lists all upscalers)
        upscaleUpscalerSpinner = choiceSpinner(
            SdCppEngine.HiresUpscaler.entries.map { it.label })
        upscaleUpscalerSpinner.setSelection(
            loadInt(KEY_UPSCALE_UPSCALER, 0).coerceIn(0, SdCppEngine.HiresUpscaler.entries.size - 1), false)
        upscaleUpscalerSpinner.onItemSelectedListener = persistSpinner(KEY_UPSCALE_UPSCALER)
        resultCard.addView(
            paramRow(c.getString(R.string.s_542), upscaleUpscalerSpinner, c.getString(R.string.s_543)),
            c.matchWrap(top = 10)
        )

        resultCard.addView(saveBtn, c.matchWrap(top = 10))

        // One-tap send of the result image to "img2img" as reference, or to "Tagger" as input
        resultToI2iBtn = Button(c).apply {
            text = c.getString(R.string.s_225)
            textSize = 13f
            setTextColor(primary)
            setBackgroundColor(pal.primarySoft)
            setOnClickListener { sendResultToI2i() }
        }
        resultToTaggerBtn = Button(c).apply {
            text = c.getString(R.string.s_238)
            textSize = 13f
            setTextColor(primary)
            setBackgroundColor(pal.primarySoft)
            setOnClickListener { sendResultToTagger() }
        }
        val sendRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        sendRow.addView(resultToI2iBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sendRow.addView(resultToTaggerBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = c.dp(8) })
        resultCard.addView(sendRow, c.matchWrap(top = 8))
        resultContent.addView(resultCard)

        // ---- Generation history (memory only, cleared when the app process ends) ----
        val histCard = card()
        histCard.addView(title(c.getString(R.string.s_146)))
        histCard.addView(TextView(c).apply {
            text = c.getString(R.string.s_067)
            textSize = 11f
            setTextColor(subText)
            setPadding(0, c.dp(4), 0, 0)
        })
        histEmpty = TextView(c).apply {
            text = c.getString(R.string.s_182)
            textSize = 12f
            setTextColor(subText)
            setPadding(0, c.dp(10), 0, 0)
        }
        histCard.addView(histEmpty)
        historyRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val hsv = android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(historyRow)
        }
        histCard.addView(hsv, c.matchWrap(top = 8))
        val clearHistBtn = Button(c).apply {
            text = c.getString(R.string.s_138)
            textSize = 12f
            setTextColor(subText)
            setOnClickListener {
                if (DrawHistory.size() == 0) {
                    c.toast(c.getString(R.string.s_064), short = true)
                } else {
                    android.app.AlertDialog.Builder(act)
                        .setMessage(c.getString(R.string.s_137))
                        .setPositiveButton(c.getString(R.string.s_136)) { _, _ -> DrawHistory.clear(); refreshHistory() }
                        .setNegativeButton(c.getString(R.string.s_066), null)
                        .show()
                }
            }
        }
        histCard.addView(clearHistBtn, c.matchWrap(top = 10))
        resultContent.addView(histCard)
        refreshHistory()

        // ---- Params / result: ViewPager swipe paging (like the phone home screen) ----
        // Old ViewPager instead of ViewPager2: its PagerAdapter natively supports reusing
        // existing Views; ViewPager2 is a RecyclerView inside and crashes when the same View is attached twice.
        pager = ViewPager(c).apply {
            paramScroll = scrollWrap(paramPane)
            resultScroll = scrollWrap(resultPane)
            adapter = PaneAdapter(listOf(paramScroll, resultScroll))
            addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    highlightTabs(toResult = position == 1)
                }
            })
        }
        root.addView(pager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        refreshLoraHint()
        refreshQuantText()
        refreshVaeTilingRows()
        switchMode(0)
        switchPane(toResult = false)
        switchResultPage(0)
        return root
    }

    // ================= img2img page =================

    /** Build the "img2img" params page (its own set of views, its own persistence keys) */
    private fun buildI2iContent(): LinearLayout {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

        // ---- Reference image ----
        val imgCard = card()
        imgCard.addView(title(c.getString(R.string.s_209)))
        i2iPreview = ImageView(c).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(pal.fieldBg)
            visibility = View.GONE
            setPadding(c.dp(4), c.dp(4), c.dp(4), c.dp(4))
        }
        imgCard.addView(i2iPreview, c.matchWrap(top = 8))
        i2iPickBtn = Button(c).apply {
            text = c.getString(R.string.s_210)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { pickImage() }
        }
        imgCard.addView(i2iPickBtn, c.matchWrap(top = 10))
        i2iImgHint = TextView(c).apply {
            text = c.getString(R.string.s_211)
            textSize = 11.5f
            setTextColor(subText)
            setPadding(0, c.dp(6), 0, 0)
        }
        imgCard.addView(i2iImgHint)
        box.addView(imgCard)

        // ---- Prompt ----
        val promptCard = card()
        promptCard.addView(title(c.getString(R.string.s_108)))
        i2iPromptEdit = labeledEdit(c.getString(R.string.s_036), singleLine = false, minLines = 3)
        promptCard.addView(i2iPromptEdit, c.matchWrap(top = 6))
        promptCard.addView(smallLabel(c.getString(R.string.s_177)))
        i2iNegEdit = labeledEdit(c.getString(R.string.s_216), singleLine = false, minLines = 2)
        promptCard.addView(i2iNegEdit, c.matchWrap(top = 4))
        box.addView(promptCard)

        // ---- Generate / cancel ----
        i2iGenBtn = Button(c).apply {
            text = c.getString(R.string.s_098)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { if (generating) doCancel() else generateFromUiI2i() }
        }
        box.addView(i2iGenBtn, c.matchWrap(top = 2))

        i2iProgressText = TextView(c).apply {
            textSize = 12f
            setTextColor(subText)
            visibility = View.GONE
            setPadding(0, c.dp(8), 0, 0)
        }
        box.addView(i2iProgressText)
        i2iProgressBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        box.addView(i2iProgressBar, c.matchWrap(top = 4))

        // ---- Params ----
        val paramCard = card()
        paramCard.addView(title(c.getString(R.string.s_065)))

        // Redraw strength: lower = closer to the original
        i2iStrengthEdit = smallNumber(loadParam(KEY_I2I_STRENGTH, defOf("i2i", "strength", "0.75")))
        bindParam(i2iStrengthEdit, KEY_I2I_STRENGTH)
        paramCard.addView(paramRow(c.getString(R.string.s_212), i2iStrengthEdit, c.getString(R.string.s_213)))

        // Size: synced to the reference image size (multiple of 64) when picked by default
        i2iWidthEdit = smallNumber(loadParam(KEY_I2I_W, defOf("i2i", "w", "512")))
        i2iHeightEdit = smallNumber(loadParam(KEY_I2I_H, defOf("i2i", "h", "512")))
        bindParam(i2iWidthEdit, KEY_I2I_W)
        bindParam(i2iHeightEdit, KEY_I2I_H)
        paramCard.addView(whRow(c.getString(R.string.s_070), c.getString(R.string.s_068), i2iWidthEdit, i2iHeightEdit))

        i2iSamplerSpinner = choiceSpinner(
            listOf(c.getString(R.string.s_169)) + SdCppEngine.Sampler.entries.map { it.label }
        )
        i2iSchedulerSpinner = choiceSpinner(
            listOf(c.getString(R.string.s_168)) + SdCppEngine.Scheduler.entries.map { it.label }
        )
        i2iSamplerSpinner.setSelection(loadInt(KEY_I2I_SAMPLER, defOfInt("i2i", "sampler", 0)).coerceIn(0, i2iSamplerSpinner.adapter.count - 1), false)
        i2iSchedulerSpinner.setSelection(loadInt(KEY_I2I_SCHEDULER, defOfInt("i2i", "scheduler", 0)).coerceIn(0, i2iSchedulerSpinner.adapter.count - 1), false)
        i2iSamplerSpinner.onItemSelectedListener = persistSpinner(KEY_I2I_SAMPLER)
        i2iSchedulerSpinner.onItemSelectedListener = persistSpinner(KEY_I2I_SCHEDULER)
        paramCard.addView(paramRow(c.getString(R.string.s_191), i2iSamplerSpinner, c.getString(R.string.s_015)))
        paramCard.addView(paramRow(c.getString(R.string.s_176), i2iSchedulerSpinner, c.getString(R.string.s_014)))

        i2iStepsEdit = smallNumber(loadParam(KEY_I2I_STEPS, defOf("i2i", "steps", builtinSteps())))
        i2iCfgEdit = smallNumber(loadParam(KEY_I2I_CFG, defOf("i2i", "cfg", builtinCfg())))
        i2iSeedEdit = smallNumber(loadParam(KEY_I2I_SEED, defOf("i2i", "seed", "-1")))
        bindParam(i2iStepsEdit, KEY_I2I_STEPS)
        bindParam(i2iCfgEdit, KEY_I2I_CFG)
        bindParam(i2iSeedEdit, KEY_I2I_SEED)
        paramCard.addView(paramRow(c.getString(R.string.s_192), i2iStepsEdit, c.getString(R.string.s_179)))
        paramCard.addView(paramRow(c.getString(R.string.s_013), i2iCfgEdit, c.getString(R.string.s_178)))
        paramCard.addView(paramRow(c.getString(R.string.s_193), i2iSeedEdit, c.getString(R.string.s_008)))

        // LoRA (shares the toggle with text-to-image; the two checkboxes stay in sync)
        i2iLoraCheck = CheckBox(c).apply {
            text = c.getString(R.string.s_022)
            textSize = 13f
            setTextColor(textColor)
            isChecked = useLora
            setPadding(0, c.dp(10), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                useLora = checked
                if (checked && activeLora() == null) c.toast(c.getString(R.string.s_186), short = true)
                if (::loraCheck.isInitialized) loraCheck.isChecked = checked
                refreshLoraHint()
                if (::t2iContent.isInitialized) {
                    applyDefaults("t2i")
                    applyDefaults("i2i")
                }
            }
        }
        paramCard.addView(i2iLoraCheck)
        i2iLoraScaleEdit = smallNumber(loadParam(KEY_I2I_LORA_SCALE, defOf("i2i", "loraScale", "1.0")))
        bindParam(i2iLoraScaleEdit, KEY_I2I_LORA_SCALE)
        paramCard.addView(paramRow(c.getString(R.string.s_024), i2iLoraScaleEdit, c.getString(R.string.s_037)))
        i2iLoraHint = TextView(c).apply {
            textSize = 11f
            setTextColor(subText)
            setPadding(0, c.dp(2), 0, 0)
        }
        paramCard.addView(i2iLoraHint)
        paramCard.addView(defaultButtonsRow("i2i"), c.matchWrap(top = 10))
        box.addView(paramCard)

        return box
    }

    /** Switch params page: 0=text-to-image 1=img2img 2=Tagger */
    private fun switchMode(m: Int) {
        if (!::t2iContent.isInitialized) return
        mode = m
        t2iContent.visibility = if (m == 0) View.VISIBLE else View.GONE
        i2iContent.visibility = if (m == 1) View.VISIBLE else View.GONE
        if (::taggerContent.isInitialized) {
            taggerContent.visibility = if (m == 2) View.VISIBLE else View.GONE
        }
        for ((tv, sel) in listOf(
            tabT2i to (m == 0), tabI2i to (m == 1), tabTagger to (m == 2)
        )) {
            tv.setTextColor(if (sel) primary else subText)
            tv.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setBackgroundColor(if (sel) pal.primarySoft else pal.surface)
        }
        refreshModelCard()
    }

    /** Mode sub-tab style (slightly smaller than the top tabs) */
    private fun modeTab(t: String) = TextView(c).apply {
        text = t
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, c.dp(9), 0, c.dp(9))
    }

    // ================= Tagger page =================

    /** Build the "Tagger" params page: pick image → tag → send result to text-to-image/img2img */
    private fun buildTaggerContent(): LinearLayout {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

        // ---- Input image ----
        val imgCard = card()
        imgCard.addView(title(c.getString(R.string.s_209)))
        taggerPreview = ImageView(c).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(pal.fieldBg)
            visibility = View.GONE
            setPadding(c.dp(4), c.dp(4), c.dp(4), c.dp(4))
        }
        imgCard.addView(taggerPreview, c.matchWrap(top = 8))
        taggerPickBtn = Button(c).apply {
            text = c.getString(R.string.s_210)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { pickTaggerImage() }
        }
        imgCard.addView(taggerPickBtn, c.matchWrap(top = 10))
        taggerImgHint = TextView(c).apply {
            text = c.getString(R.string.s_211)
            textSize = 11.5f
            setTextColor(subText)
            setPadding(0, c.dp(6), 0, 0)
        }
        imgCard.addView(taggerImgHint)
        box.addView(imgCard)

        // ---- Params: threshold / max tags ----
        val paramCard = card()
        paramCard.addView(title(c.getString(R.string.s_065)))
        taggerThresholdEdit = smallNumber(loadParam(KEY_TAG_THRESHOLD, prefs().getString("def_tag_threshold", null) ?: "0.35"))
        bindParam(taggerThresholdEdit, KEY_TAG_THRESHOLD)
        paramCard.addView(paramRow(c.getString(R.string.s_226), taggerThresholdEdit, "0 ~ 1"))
        taggerTopKEdit = smallNumber(loadParam(KEY_TAG_TOPK, prefs().getString("def_tag_topk", null) ?: "40"))
        bindParam(taggerTopKEdit, KEY_TAG_TOPK)
        paramCard.addView(paramRow(c.getString(R.string.s_227), taggerTopKEdit, "1 ~ 300"))
        taggerChanSpinner = choiceSpinner(listOf("BGR", "RGB"))
        taggerChanSpinner.setSelection(loadInt(KEY_TAG_CHAN, 0).coerceIn(0, 1), false)
        taggerChanSpinner.onItemSelectedListener = persistSpinner(KEY_TAG_CHAN)
        paramCard.addView(paramRow(c.getString(R.string.s_260), taggerChanSpinner, c.getString(R.string.s_261)))
        paramCard.addView(defaultButtonsRow("tag"), c.matchWrap(top = 10))
        box.addView(paramCard)

        // ---- Run tagger ----
        taggerRunBtn = Button(c).apply {
            text = c.getString(R.string.s_222)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { runTagger() }
        }
        box.addView(taggerRunBtn, c.matchWrap(top = 2))

        taggerProgressText = TextView(c).apply {
            textSize = 12f
            setTextColor(subText)
            visibility = View.GONE
            setPadding(0, c.dp(8), 0, 0)
        }
        box.addView(taggerProgressText)
        taggerProgressBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        box.addView(taggerProgressBar, c.matchWrap(top = 4))

        // ---- Result ----
        val outCard = card()
        outCard.addView(title(c.getString(R.string.s_223)))
        taggerOut = TextView(c).apply {
            textSize = 13f
            setTextColor(textColor)
            setTextIsSelectable(true)
            setPadding(0, c.dp(6), 0, 0)
        }
        outCard.addView(taggerOut)
        taggerSendT2iBtn = Button(c).apply {
            text = c.getString(R.string.s_224)
            textSize = 13f
            setTextColor(primary)
            setBackgroundColor(pal.primarySoft)
            setOnClickListener { sendTags(0) }
        }
        taggerSendI2iBtn = Button(c).apply {
            text = c.getString(R.string.s_225)
            textSize = 13f
            setTextColor(primary)
            setBackgroundColor(pal.primarySoft)
            setOnClickListener { sendTags(1) }
        }
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(taggerSendT2iBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(taggerSendI2iBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = c.dp(8) })
        outCard.addView(row, c.matchWrap(top = 10))
        box.addView(outCard)

        return box
    }

    /** Pick the Tagger input image from the gallery */
    private fun pickTaggerImage() {
        val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        act.startActivityForResult(i, REQ_TAGGER_IMAGE)
    }

    /** Set an image as the Tagger input */
    private fun applyTaggerImage(bmp: Bitmap) {
        taggerBitmap = bmp
        taggerPreview.setImageBitmap(bmp)
        taggerPreview.visibility = View.VISIBLE
        taggerImgHint.text = c.getString(R.string.v_061, bmp.width, bmp.height)
    }

    /** Run tagging (inference on the IO thread, UI updated back on the main thread) */
    private fun runTagger() {
        val bmp = taggerBitmap
        if (bmp == null) {
            c.toast(c.getString(R.string.s_214), short = true)
            return
        }
        val threshold = DrawParamsLogic.taggerThreshold(taggerThresholdEdit.text.toString())
        val topK = DrawParamsLogic.taggerTopK(taggerTopKEdit.text.toString())
        taggerRunBtn.isEnabled = false
        taggerProgressText.visibility = View.VISIBLE
        taggerProgressText.text = c.getString(R.string.s_229)
        taggerProgressBar.visibility = View.VISIBLE
        taggerProgressBar.isIndeterminate = true
        scope.launch {
            var err: String? = null
            var list: List<Pair<String, Float>>? = null
            withContext(Dispatchers.IO) {
                err = taggerReadyError()
                if (err == null) {
                    list = try {
                        TaggerEngine.run(bmp, threshold, topK, rgbOrder = taggerChanSpinner.selectedItemPosition == 1)
                    } catch (t: Throwable) {
                        err = t.message ?: t.javaClass.simpleName
                        null
                    }
                }
            }
            taggerRunBtn.isEnabled = true
            taggerProgressBar.visibility = View.GONE
            taggerProgressText.visibility = View.GONE
            when {
                err != null -> c.toast(c.getString(R.string.s_236, err), short = true)
                list.isNullOrEmpty() -> {
                    taggerTags = ""
                    taggerOut.text = c.getString(R.string.s_230)
                    c.toast(c.getString(R.string.s_230), short = true)
                }
                else -> {
                    val text = list!!.joinToString(", ") { it.first.replace('_', ' ') }
                    taggerTags = text
                    taggerOut.text = text
                    c.toast(c.getString(R.string.s_237, list!!.size), short = true)
                }
            }
        }
    }

    /** Load the tagger model on demand (reload when model name / tag table / backend changed); null = ready */
    private fun ensureTaggerLoaded(): String? {
        val model = taggerModelFile() ?: return c.getString(R.string.s_228)
        val csv = taggerCsvFile() ?: return c.getString(R.string.s_228)
        val sel = TaggerSelection(model.name, csv.name, useGpu)
        if (sel.isReadyWith(currentTaggerSession())) return null
        // Thread count comes from the global setting (InferConfig) — shared with chat gguf and tagger
        val threads = InferConfig.threads(c)
        return TaggerEngine.load(c, model, csv, useGpu, threads)
    }

    /** Estado del tagger en memoria (lo que hay cargado ahora mismo). */
    private fun currentTaggerSession(): TaggerSession {
        val (mName, cName) = TaggerEngine.fileNames()
        return TaggerSession(TaggerEngine.isLoaded(), mName, cName, TaggerEngine.loadedWithGpu())
    }

    /**
     * Manual mode: no auto-load. Only proceeds if already loaded and the model/tag table/backend all match the current
     * selection, otherwise tells the user to tap "Load tagger model" on the "Models" page.
     */
    private fun taggerReadyError(): String? {
        val model = taggerModelFile() ?: return c.getString(R.string.s_228)
        val csv = taggerCsvFile() ?: return c.getString(R.string.s_228)
        val sel = TaggerSelection(model.name, csv.name, useGpu)
        if (!sel.isReadyWith(currentTaggerSession())) return c.getString(R.string.s_259)
        return null
    }

    /** Send tag result to the target page (0=text-to-image 1=img2img), with a confirmation first */
    private fun sendTags(target: Int) {
        val text = taggerTags.ifBlank { taggerOut.text?.toString().orEmpty() }
        if (text.isBlank()) {
            c.toast(c.getString(R.string.s_230), short = true)
            return
        }
        val pageName = c.getString(if (target == 0) R.string.s_207 else R.string.s_208)
        android.app.AlertDialog.Builder(act)
            .setTitle(c.getString(R.string.s_233))
            .setMessage(c.getString(R.string.s_234, pageName))
            .setPositiveButton(c.getString(R.string.s_235)) { _, _ ->
                if (target == 0) {
                    promptEdit.setText(text)
                    switchMode(0)
                    scrollTop(paramScroll)
                    c.toast(c.getString(R.string.s_231), short = true)
                } else {
                    i2iPromptEdit.setText(text)
                    switchMode(1)
                    scrollTop(paramScroll)
                    c.toast(c.getString(R.string.s_232), short = true)
                }
            }
            .setNegativeButton(c.getString(R.string.s_049), null)
            .show()
    }

    /** Result image → img2img reference (and switch there) */
    private fun sendResultToI2i() {
        val b = lastImage
        if (b == null) {
            c.toast(c.getString(R.string.s_183), short = true)
            return
        }
        applyPickedImage(b)
        switchMode(1)
        switchPane(toResult = false)
        scrollTop(paramScroll)
        c.toast(c.getString(R.string.s_239), short = true)
    }

    /** Result image → Tagger input (and switch there) */
    private fun sendResultToTagger() {
        val b = lastImage
        if (b == null) {
            c.toast(c.getString(R.string.s_183), short = true)
            return
        }
        applyTaggerImage(b)
        switchMode(2)
        switchPane(toResult = false)
        scrollTop(paramScroll)
        c.toast(c.getString(R.string.s_240), short = true)
    }

    /** External image → img2img reference (and switch to the img2img page). Called from the long-press menu on chat images. */
    fun sendImageToI2i(b: Bitmap) {
        applyPickedImage(b)
        switchMode(1)
        switchPane(toResult = false)
        scrollTop(paramScroll)
        c.toast(c.getString(R.string.s_239), short = true)
    }

    /** External image → Tagger input (and switch to the Tagger page). Called from the long-press menu on chat images. */
    fun sendImageToTagger(b: Bitmap) {
        applyTaggerImage(b)
        switchMode(2)
        switchPane(toResult = false)
        scrollTop(paramScroll)
        c.toast(c.getString(R.string.s_240), short = true)
    }

    // ---- Tagger model files (imported on the model page, read-only here) ----

    /** Tagger dir: filesDir/tagger */
    private fun taggerDir(): File = taggerStore.dir()

    /** Imported tagger models (.onnx), sorted by name */
    fun listTaggerModels(): List<File> = taggerStore.listModels()

    /** Imported tag tables (.csv) */
    fun listTaggerCsvs(): List<File> = taggerStore.listCsvs()

    /** Currently selected tagger model (last picked first, else the first) */
    fun taggerModelFile(): File? = taggerStore.modelFile()

    /** Currently selected tag table */
    fun taggerCsvFile(): File? = taggerStore.csvFile()

    fun currentTaggerModelName(): String? = taggerStore.currentModelName()
    fun currentTaggerCsvName(): String? = taggerStore.currentCsvName()

    /** Select tagger model (switching unloads the old one; reloaded on the next tag run) */
    fun selectTaggerModel(f: File) {
        taggerStore.selectModel(f)
        TaggerEngine.unload()
    }

    /** Select tag table */
    fun selectTaggerCsv(f: File) {
        taggerStore.selectCsv(f)
        TaggerEngine.unload()
    }

    fun taggerLoaded(): Boolean = TaggerEngine.isLoaded()

    /** Channel order chosen in the Tagger UI (false = BGR, true = RGB); the chat page reuses this setting when tagging to look at images. */
    fun taggerRgbOrder(): Boolean =
        ::taggerChanSpinner.isInitialized && taggerChanSpinner.selectedItemPosition == 1

    /** Unload the tagger model and free memory */
    fun unloadTagger() = TaggerEngine.unload()

    /** "Load tagger model" on the model page: loads per the current model/tag table selection; null=success */
    suspend fun loadTaggerFromUi(onStage: (String) -> Unit = {}): String? {
        val model = taggerModelFile()
        val csv = taggerCsvFile()
        if (model == null || csv == null) return c.getString(R.string.s_228)
        onStage(c.getString(R.string.s_245))
        return withContext(Dispatchers.IO) { ensureTaggerLoaded() }
    }

    /** Tagger status shown on the "Models" page */
    fun taggerSummary(): String {
        val m = taggerModelFile() ?: return c.getString(R.string.s_245)
        val csv = taggerCsvFile() ?: return c.getString(R.string.s_245)
        val n = TaggerEngine.parseCsv(csv).size
        return c.getString(R.string.v_062, m.name, csv.name, n)
    }

    /** Import an ONNX tagger model (single file) */
    suspend fun importTaggerModel(
        uri: Uri,
        onStage: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)? = null
    ): String? = copyToTagger(uri, ".onnx", R.string.s_246, onStage, onProgress)

    /** Import a tag table CSV (single file) */
    suspend fun importTaggerCsv(
        uri: Uri,
        onStage: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)? = null
    ): String? = copyToTagger(uri, ".csv", R.string.s_247, onStage, onProgress)

    private suspend fun copyToTagger(
        uri: Uri,
        ext: String,
        extErr: Int,
        onStage: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)?
    ): String? = withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(uri)
            if (name == null || !name.endsWith(ext, ignoreCase = true)) {
                return@withContext c.getString(extErr)
            }
            withContext(Dispatchers.Main) { onStage(c.getString(R.string.v_033, name)) }
            val dest = File(taggerDir(), name)
            val total = runCatching {
                c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            withContext(Dispatchers.Main) { onProgress?.invoke(0L, total) }
            c.contentResolver.openInputStream(uri)?.use { ins ->
                dest.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var tick = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        done += n
                        if (done - tick >= (1L shl 20)) {
                            tick = done
                            val d = done
                            withContext(Dispatchers.Main) { onProgress?.invoke(d, total) }
                        }
                    }
                }
            } ?: return@withContext c.getString(R.string.s_106)
            TaggerEngine.unload() // files changed, reload next time
            null
        } catch (e: Throwable) {
            c.getString(R.string.v_038, (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Pick a reference image from the gallery/files */
    private fun pickImage() {
        val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        act.startActivityForResult(i, REQ_IMAGE)
    }

    /** Decode the picked image; downsample proportionally past 2048 px to avoid OOM */
    private fun decodePickedImage(uri: Uri): Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > 2048) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        c.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    /** Handle after picking a reference image: preview + sync the output size */
    private fun applyPickedImage(bmp: Bitmap) {
        i2iBitmap = bmp
        i2iPreview.setImageBitmap(bmp)
        i2iPreview.visibility = View.VISIBLE
        val w = DrawParamsLogic.alignSize(bmp.width)
        val h = DrawParamsLogic.alignSize(bmp.height)
        i2iWidthEdit.setText(w.toString())
        i2iHeightEdit.setText(h.toString())
        i2iImgHint.text = c.getString(R.string.v_061, bmp.width, bmp.height)
    }

    private fun refreshI2iLoraHint() {
        if (!::i2iLoraHint.isInitialized) return
        try {
            val a = activeLora()
            i2iLoraHint.text = when {
                a == null -> c.getString(R.string.s_118)
                useLora -> c.getString(R.string.v_028, (a.nameWithoutExtension))
                else -> c.getString(R.string.v_029, (a.nameWithoutExtension))
            }
        } catch (_: Throwable) {}
    }

    // ================= LoRA =================

    /** LoRA dir: filesDir/draw/lora */
    private fun loraDir(): File = loraStore.loraDir()

    /** Downloaded LoRAs (.safetensors) */
    fun listLoras(): List<File> = loraStore.list()

    fun hasLora(): Boolean = loraStore.hasAny()

    /** Active LoRA (the user's pick first, then last selected, then the first one) */
    fun activeLora(): File? = loraStore.active(loraFile)

    /** For UI highlighting: name of the active LoRA */
    fun currentLoraName(): String? = activeLora()?.name

    /** Select a LoRA (persisted choice) */
    fun selectLora(f: File) {
        loraFile = f
        loraStore.select(f)
        refreshLoraHint()
        // Una LoRA de aceleracion (Hyper-SD / LCM / Lightning) cambia los pasos utiles:
        // aplicamos el preset de su familia para no generar con 20 pasos.
        runCatching {
            applyDistilledPreset(f.name)?.let { n -> c.toast(c.getString(R.string.s_523, n), short = true) }
        }
    }

    /** LoRA status shown on the "Models" page */
    fun loraSummary(): String {
        val a = activeLora() ?: return c.getString(R.string.s_117)
        val size = "%.1f".format(a.length() / 1048576.0)
        return if (useLora) c.getString(R.string.v_026, (a.nameWithoutExtension), (size))
        else c.getString(R.string.v_027, (a.nameWithoutExtension), (size))
    }

    fun deleteLora(f: File): Boolean {
        if (loraFile?.absolutePath == f.absolutePath) loraFile = null
        val ok = loraStore.delete(f)
        refreshLoraHint()
        return ok
    }

    private fun refreshLoraHint() {
        try {
            val a = activeLora()
            loraHint.text = when {
                a == null -> c.getString(R.string.s_118)
                useLora -> c.getString(R.string.v_028, (a.nameWithoutExtension))
                else -> c.getString(R.string.v_029, (a.nameWithoutExtension))
            }
        } catch (_: Throwable) {}
        refreshI2iLoraHint()
    }

    /**
     * Download LCM-LoRA. useMirror=true goes through hf-mirror.com (usable from China).
     * null means success; otherwise the error text. onProgress(bytes downloaded, total bytes [-1 if unknown])
     */
    suspend fun downloadLora(useMirror: Boolean, onProgress: (Long, Long) -> Unit): String? {
        val host = if (useMirror) "https://hf-mirror.com" else "https://huggingface.co"
        val dest = File(loraDir(), "$LORA_NAME.safetensors")
        val err = downloadToFile("$host/$LORA_HF_PATH", dest, 100L * 1024, onProgress)
        if (err == null) {
            loraFile = dest
            refreshLoraHint()
        }
        return err
    }

    /**
     * Descargar TAESD (mini autoencoder). Devuelve null si OK, o el mensaje de error.
     *
     * El archivo va a files/draw/taesd/. El usuario tiene que asignarlo al slot
     * "taesd" del juego de modelos (Modelos/API → Modelo de dibujo → Taesd).
     */
    suspend fun downloadTaesd(useMirror: Boolean, onProgress: (Long, Long) -> Unit): String? {
        val host = if (useMirror) "https://hf-mirror.com" else "https://huggingface.co"
        val dir = File(File(c.filesDir, DIR_NAME), "taesd").apply { mkdirs() }
        val dest = File(dir, "taesd.safetensors")
        if (dest.exists() && dest.length() > TAESD_MIN_BYTES) return null
        val err = downloadToFile("$host/$TAESD_HF_PATH", dest, TAESD_MIN_BYTES, onProgress)
        return err
    }

    /** Generic download: stream to .part while downloading, rename to the target when done. null = success. */
    private suspend fun downloadToFile(
        url: String,
        dest: File,
        minBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Yumenode AI/1.0 (Android)")
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) return@withContext c.getString(R.string.v_030, (code))
            val total = conn.contentLengthLong
            conn.inputStream.use { ins ->
                tmp.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastTick = 0L
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        outs.write(buf, 0, r)
                        done += r
                        if (done - lastTick > (1 shl 20)) {
                            lastTick = done
                            onProgress(done, total)
                        }
                    }
                    outs.flush()
                }
            }
            if (tmp.length() < minBytes) {
                runCatching { tmp.delete() }
                return@withContext c.getString(R.string.v_031, (tmp.length()))
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                runCatching { tmp.delete() }
            }
            onProgress(dest.length(), dest.length())
            null
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            c.getString(R.string.v_032, (e.message ?: e.javaClass.simpleName))
        }
    }

    // ================= Model sets (single / multi file; driven by the "Models" page) =================

    /** Draw models already in the private dir: new-style model sets (subdirs under draw/sets) + legacy loose single gguf files */
    fun listSets(): List<ModelSet> = setStore.listSets()

    /** Startup fallback: make the last selected model set (or the first) current */
    fun ensureActiveSet() {
        if (activeSet != null) return
        val all = listSets()
        if (all.isEmpty()) return
        val saved = setStore.selectedSetId()
        val pick = all.firstOrNull { it.id == saved } ?: all.first()
        selectSet(pick)
    }

    /** Model set in use: the in-memory one first, else the one recorded in prefs */
    fun activeSetOrSelected(): ModelSet? {
        ensureActiveSet()
        return activeSet
    }

    fun activeSetId(): String? = activeSet?.id

    fun selectSet(s: ModelSet) {
        activeSet = s
        mainModel = s.mainFile()
        vaeModel = s.files["vae"]
        setStore.selectSet(s)
    }

    fun deleteSet(s: ModelSet): Boolean {
        val ok = setStore.deleteSet(s)
        if (activeSet?.id == s.id) activeSet = null
        return ok
    }

    /**
     * Point the downloaded TAESD at the "taesd" slot of the current model set.
     *
     * El archivo vive en files/draw/taesd/ (fuera del set), asi que se guarda
     * la ruta absoluta — el loader de sets ya distingue entre nombre y ruta
     * absoluta (File.isAbsolute).
     *
     * Devuelve true si se pudo asignar.
     */
    fun assignTaesdToActiveSet(): Boolean {
        val f = taesdFile() ?: return false
        // La ruta se guarda como preferencia global (no en el set): los sets "legacy"
        // (archivos sueltos) no tienen directorio donde escribir el JSON, y el
        // TAESD sirve igual para cualquier modelo SD1.x/SD2.x.
        setStore.setTaesdPath(f.absolutePath)
        // Y ademas se apunta en el set activo, si es uno con directorio (multi-archivo).
        val s = activeSetOrSelected()
        if (s != null && s.dir != null) {
            s.files["taesd"] = f
            setStore.writeSetJson(s)
        }
        return true
    }

    /** Ruta del TAESD activa (preferencia global) o null. */
    fun activeTaesdPath(): String? {
        val pref = setStore.taesdPath()
        if (!pref.isNullOrBlank() && File(pref).exists()) return pref
        // El set activo puede tener su propio taesd (multi-archivo).
        val s = activeSetOrSelected()
        val inSet = s?.files?.get("taesd")
        if (inSet != null && inSet.exists()) return inSet.absolutePath
        return null
    }

    /** Path del TAESD descargado, o null si no está. */
    fun taesdFile(): File? = setStore.taesdFile()

    /** Remove the slot from the set only, don't delete the file (one file may be shared by several slots) */
    fun clearSlot(key: String) {
        val s = activeSetOrSelected() ?: return
        setStore.clearSlot(s, key)
    }

    /** Model set summary: name · single/multi file · family · file count · size */
    fun setSummary(s: ModelSet): String {
        val kindLabel = c.getString(if (s.kind == KIND_SINGLE) R.string.s_345 else R.string.s_346)
        val fam = c.getString(familyLabelRes(s.family))
        val size = "%.2f GB".format(s.sizeBytes() / 1073741824.0)
        return c.getString(R.string.s_365, s.name, "$kindLabel · $fam", s.slotCount(), size)
    }

    /** Required slots (keys) still unfilled in the current model set */
    fun slotMissingIds(s: ModelSet): List<String> =
        familySlots(s.kind, s.family).filter { it.second && s.files[it.first] == null }.map { it.first }

    /** Import a file into a model set.
     *  pendingNewSet = true (user picked a kind from the type menu) → open a new set with this main file;
     *  otherwise (adding a companion file) → put it into the current set.
     *  null means success, otherwise the error text. */
    suspend fun importSetFile(
        uri: Uri,
        slotKey: String?,
        onStage: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(uri) ?: return@withContext c.getString(R.string.s_106)
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in listOf("gguf", "safetensors", "ckpt", "pt")) {
                return@withContext c.getString(R.string.s_368)
            }
            val key = slotKey ?: if (pendingKind == KIND_SINGLE) "model" else "diffusion"
            // TAESD only accepts the two formats sd.cpp can read; .ckpt / .pt are torch-family,
            // and the often-referenced ComfyUI taesd_decoder.pth is decoder-only and unusable anyway, so say so up front.
            if (key == "taesd" && ext !in listOf("safetensors", "gguf")) {
                return@withContext c.getString(R.string.s_445)
            }
            val cur = activeSet
            val useSet: ModelSet = when {
                pendingNewSet || cur == null -> {
                    val dir = setStore.createSetDir(name.substringBeforeLast('.'))
                    val id = dir.name
                    ModelSet(id, id, pendingKind, pendingFamily, dir).also {
                        activeSet = it
                        pendingNewSet = false
                    }
                }
                cur.dir == null -> {
                    // Legacy loose single file (not a model set): adding companion files first requires promoting it to a set.
                    // Main models are often several GB, **not copied, not moved** — keep the original file, store an absolute path reference in the json.
                    val dir = setStore.createSetDir(cur.name)
                    ModelSet(dir.name, cur.name, cur.kind, cur.family, dir, LinkedHashMap(cur.files)).also {
                        activeSet = it
                    }
                }
                else -> cur
            }
            val dir: File = useSet.dir ?: return@withContext c.getString(R.string.s_106)

            withContext(Dispatchers.Main) { onStage(c.getString(R.string.v_033, name)) }
            val dest = File(dir, name)
            val total = runCatching {
                c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            withContext(Dispatchers.Main) { onProgress?.invoke(0L, total) }
            c.contentResolver.openInputStream(uri)?.use { ins ->
                dest.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var tick = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        done += n
                        if (done - tick >= (1L shl 20)) {
                            tick = done
                            val dd = done
                            withContext(Dispatchers.Main) { onProgress?.invoke(dd, total) }
                        }
                    }
                    val dd = done
                    withContext(Dispatchers.Main) { onProgress?.invoke(dd, total) }
                }
            } ?: return@withContext c.getString(R.string.s_106)

            // TAESD needs both encoder and decoder in the weights to work (img2img relies on the encoder).
            // If validation fails, don't register it in the set, so the user doesn't hit a vague native failure only at "Load".
            if (key == "taesd") {
                taesdWeightsProblem(dest)?.let { msg ->
                    runCatching { dest.delete() }
                    return@withContext msg
                }
            }
            useSet.files[key] = dest
            setStore.writeSetJson(useSet)
            selectSet(useSet)
            withContext(Dispatchers.Main) {
                statusText.text = c.getString(R.string.s_161)
            }
            null
        } catch (e: Throwable) {
            c.getString(R.string.v_038, (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Whether the TAESD weights are usable: returns the reason to show the user if not, null if usable.
     *
     * Only validates `.safetensors` (tensor names can be read); `.gguf` is left to native to report.
     * Both **encoder and decoder must be present**: sd.cpp builds both submodules when loading TAESD,
     * with only a decoder, img2img has no encoder and text-to-image may get uninitialized weights.
     */
    private fun taesdWeightsProblem(f: File): String? {
        if (!f.name.endsWith(".safetensors", ignoreCase = true)) return null
        val names = runCatching { safetensorsKeys(f) }.getOrNull() ?: return c.getString(R.string.s_444)
        val hasDec = names.any { it.startsWith("decoder.layers.") || it.startsWith("tae.decoder.layers.") }
        val hasEnc = names.any { it.startsWith("encoder.layers.") || it.startsWith("tae.encoder.layers.") }
        return when {
            !hasDec -> c.getString(R.string.s_444)
            !hasEnc -> c.getString(R.string.s_443)
            else -> null
        }
    }

    /** Read the tensor names listed in the `.safetensors` header. Format: 8-byte little-endian "header length" + a JSON header of that length. */
    private fun safetensorsKeys(f: File): List<String> {
        java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f))).use { ins ->
            val head = ByteArray(8)
            ins.readFully(head)
            var len = 0L
            for (i in 7 downTo 0) len = (len shl 8) or (head[i].toLong() and 0xFFL)
            if (len <= 0L || len > 16L * 1024 * 1024) throw IllegalStateException("safetensors header length=$len")
            val raw = ByteArray(len.toInt())
            ins.readFully(raw)
            val o = org.json.JSONObject(String(raw, Charsets.UTF_8))
            return o.keys().asSequence().filter { it != "__metadata__" }.toList()
        }
    }

    /** Import a local LoRA from a single file (.safetensors). null = success. */
    suspend fun importLoraFile(
        uri: Uri,
        onStage: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(uri)
            if (name == null || !name.endsWith(".safetensors", ignoreCase = true)) {
                return@withContext c.getString(R.string.s_218)
            }
            withContext(Dispatchers.Main) { onStage(c.getString(R.string.v_034, name)) }
            val dest = File(loraDir(), name)
            val total = runCatching {
                c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            withContext(Dispatchers.Main) { onProgress?.invoke(0L, total) }
            c.contentResolver.openInputStream(uri)?.use { ins ->
                dest.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var tick = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        done += n
                        if (done - tick >= (1L shl 20)) {
                            tick = done
                            val d = done
                            withContext(Dispatchers.Main) { onProgress?.invoke(d, total) }
                        }
                    }
                }
            } ?: return@withContext c.getString(R.string.s_106)
            withContext(Dispatchers.Main) {
                loraFile = dest
                refreshLoraHint()
            }
            null
        } catch (e: Throwable) {
            c.getString(R.string.v_038, (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Get the display file name from a content:// URI */
    private fun queryDisplayName(uri: Uri): String? =
        c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
            val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cur.moveToFirst()) cur.getString(idx) else null
        }

    /** Load the currently selected model set (the "Load draw model" button) */
    suspend fun loadSelectedSet(): String? = withContext(Dispatchers.IO) {
        try {
            loadSet(activeSetOrSelected())
        } catch (e: Throwable) {
            runCatching {
                File(File(c.filesDir, DIR_NAME), ".loadstage")
                    .appendText(c.getString(R.string.v_039, (e.javaClass.name), (e.message)) +
                        android.util.Log.getStackTraceString(e) + "\n")
            }
            c.getString(R.string.v_040, (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Main model file name of the current model set */
    fun currentMainName(): String? = activeSetOrSelected()?.name

    /** Quant level of a file (Q4_0 / Q8_0 / …), null if unreadable */
    fun quantOf(f: File): String? = GgufProbe.quantType(f)

    /** Delete a single copied model file (old API, kept for compatibility) */
    fun deleteModel(f: File): Boolean = runCatching { f.delete() }.getOrDefault(false)

    /** Loose gguf files in the private dir (legacy; use listSets for new-style models) */
    fun listModels(): List<File> =
        File(c.filesDir, DIR_NAME)
            .listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()


    /**
     * Los modelos destilados (Turbo / Lightning / Hyper / LCM / SDXS) estan pensados para
     * 1-4 pasos con CFG 1. Con los valores por defecto (20 pasos / CFG 7) tardan ~5x mas
     * y el resultado sale peor: el distilado se pasa de refinamiento.
     * Devuelve los pasos aplicados, o null si el modelo no es de esa familia.
     */
    private fun applyDistilledPreset(vararg names: String?): Int? {
        if (!::stepsEdit.isInitialized || !::cfgEdit.isInitialized) return null
        val steps = DistilledPresets.stepsFor(*names) ?: return null
        val curSteps = stepsEdit.text.toString().toIntOrNull()
        val curCfg = cfgEdit.text.toString().toFloatOrNull()
        if (curSteps == steps && curCfg != null && kotlin.math.abs(curCfg - 1f) < 0.01f) return null
        stepsEdit.setText(steps.toString())
        cfgEdit.setText("1")
        return steps
    }

    private suspend fun loadSet(set: ModelSet?): String? {
        if (set == null) {
            statusText.post { statusText.text = c.getString(R.string.s_114) }
            return c.getString(R.string.s_114)
        }
        // Slot table → sd.cpp path params
        val slots = LinkedHashMap<String, String>()
        set.files.forEach { (k, f) -> slots[k] = f.absolutePath }
        // TAESD global: si esta descargado y el set no trae el suyo, se anade aqui.
        // Asi sirve para los modelos sueltos ("legacy"), que no tienen set donde guardar slots.
        activeTaesdPath()?.let { p ->
            if (!slots.containsKey("taesd")) slots["taesd"] = p
        }
        val missing = slotMissingIds(set).map { c.getString(slotLabelRes(it)) }
        if (missing.isNotEmpty()) {
            val msg = c.getString(R.string.s_363, missing.joinToString("、"))
            statusText.post { statusText.text = msg }
            return msg
        }

        // Crash tracking: write each stage to the file as it goes. A native segfault kills the process outright,
        // but if it already reached the disk, the next launch shows which step crashed.
        val root = File(c.filesDir, DIR_NAME)
        val stageFile = File(root, ".loadstage")
        fun stage(s: String) {
            runCatching { stageFile.appendText("$s\n") }
        }
        runCatching { stageFile.writeText("") }
        stage("2 模型集：${set.id}（${set.kind}/${set.family}，${set.slotCount()} 个文件）")
        slots.forEach { (k, v) -> stage("   槽位 $k = ${File(v).name}（${File(v).length() / 1048576} MB）") }
        activeLora()?.let { stage(c.getString(R.string.v_058, (it.name), (it.length() / 1048576))) }
        stage(c.getString(R.string.v_041, (android.os.Build.SUPPORTED_ABIS.firstOrNull()), (android.os.Build.VERSION.SDK_INT), (android.os.Build.MANUFACTURER), (android.os.Build.MODEL)))
        stage(c.getString(R.string.v_042, (Runtime.getRuntime().maxMemory() / 1048576), (root.usableSpace / 1048576)))

        // Step-by-step probing: write each step to disk before running it
        stage(c.getString(R.string.s_009))
        try {
            SdCppEngine.info().let { stage("3 native OK：$it") }
        } catch (t: Throwable) {
            stage(c.getString(R.string.v_043, (t.message ?: t.javaClass.simpleName)))
            runCatching { stageFile.delete() }
            return c.getString(R.string.v_044, (t.message ?: t.javaClass.simpleName))
        }

        stage(c.getString(R.string.v_045, (nThreads)))
        return try {
            if (sdHandle != 0L) runCatching { SdCppEngine.nativeFree(sdHandle) }
            sdHandle = 0L
            // Backend: use GPU if selected and the device has Vulkan, else CPU.
            // Passing the backend name explicitly turns off sd.cpp's auto_fit, so "CPU selected" never silently runs on GPU.
            val gpuAllowed = GpuSupport.gpuAllowed(prefs())
            val gpuDev = if (useGpu && gpuAllowed) SdCppEngine.vulkanDevice() else null
            if (useGpu && !gpuAllowed) {
                c.toast(c.getString(R.string.s_560), short = true)
            }
            if (useGpu && gpuDev == null) {
                stage("4a no vulkan device -> CPU")
                c.toast(c.getString(R.string.s_332), short = true)
            }
            fun createWith(b: String) = SdCppEngine.create(
                modelPath = slots["model"].orEmpty(),
                vaePath = slots["vae"],
                nThreads = nThreads,
                wtype = SdCppEngine.WTYPE_KEEP,
                flashAttn = flashAttn,
                backend = b,
                slots = SdCppEngine.buildSlots(slots),
                // Weights loaded in stages: only the components the current stage needs are pulled into RAM
                diskWeights = DrawMemoryMode.useDiskWeights(c),
            )
            var used = gpuDev ?: "CPU"
            var h = createWith(used)
            if (h == 0L && used != "CPU") {
                // Vulkan init failed (driver/VRAM) → fall back to CPU, so the user isn't blocked from drawing
                stage("4b vulkan init failed -> CPU")
                used = "CPU"
                h = createWith(used)
                if (h != 0L) c.toast(c.getString(R.string.s_333), short = true)
            }
            activeBackend = used
            if (h == 0L) {
                stage(c.getString(R.string.s_012))
                runCatching { stage(c.getString(R.string.s_010) + SdCppEngine.lastParams().replace("\n", "  |  ")) }
                runCatching { stageFile.delete() }
                return c.getString(R.string.s_059)
            }
            sdHandle = h
            mainModel = set.mainFile()
            vaeModel = set.files["vae"]
            // Register with the process-level singleton: the API's draw endpoint reuses the same handle (UI rebuild / theme switch don't affect it)
            DrawCore.attach(h, mainModel?.name ?: set.name, used)
            stage(c.getString(R.string.s_011))
            runCatching {
                stage(c.getString(R.string.s_010) + SdCppEngine.lastParams().replace("\n", "  |  "))
            }

            val summary = buildString {
                append(c.getString(R.string.s_094)).append(set.name)
                append(" · ").append(activeBackend).append(" · ").append(nThreads).append(c.getString(R.string.s_001))
                append(if (flashAttn) "FA" else c.getString(R.string.s_110))
                if (set.files["taesd"] != null) append(" · TAESD")
                append(" · sd.cpp")
            }
            statusText.post { statusText.text = summary }
            statusText.post { refreshQuantText() }
            // Modelo destilado -> pasos/CFG de su familia (antes de sincronizar con el motor)
            statusText.post {
                runCatching {
                    applyDistilledPreset(set.name, mainModel?.name)?.let { n ->
                        c.toast(c.getString(R.string.s_523, n), short = true)
                    }
                }
            }
            // Param snapshot synced to the API (all views are built by now)
            statusText.post { runCatching { syncDrawCore() } }
            runCatching { stageFile.delete() }
            onPipelineReady?.invoke()
            null
        } catch (e: Throwable) {
            stage(c.getString(R.string.v_046, (e.javaClass.name), (e.message)))
            runCatching { stageFile.appendText(android.util.Log.getStackTraceString(e) + "\n") }
            c.getString(R.string.v_047, (e.message ?: e.javaClass.simpleName))
        }
    }

    fun unloadModel() {
        DrawCore.release()   // clear the reference first, so the API can't get an already-freed handle
        if (sdHandle != 0L) runCatching { SdCppEngine.nativeFree(sdHandle) }
        sdHandle = 0L
        activeBackend = "CPU"
        try {
            statusText.text = c.getString(R.string.s_115)
        } catch (_: Throwable) {}
        refreshQuantText()
    }

    fun hasModel(): Boolean = listSets().isNotEmpty()

    fun modelSummary(): String = when {
        sdHandle != 0L -> {
            val q = mainModel?.let { GgufProbe.quantType(it) }
            c.getString(R.string.s_094) + (mainModel?.name ?: c.getString(R.string.s_155)) +
                (if (q != null) "（$q）" else "") +
                " · CPU · " + nThreads + c.getString(R.string.s_002) +
                (if (activeTaesd() != null) " · TAESD" else "")
        }
        hasModel() -> (activeSetOrSelected()?.name?.let { it + " · " } ?: "") + c.getString(R.string.s_088)
        else -> c.getString(R.string.s_115)
    }

    /** Return the TAESD weights attached to the current model set (UI hint only; the slot table carries it at load time) */
    fun activeTaesd(): File? = activeSetOrSelected()?.files?.get("taesd")

    fun refreshStatus() {
        refreshModelCard()
    }

    /** Top model card follows the subpage: text-to-image/img2img show the draw model, Tagger shows the tagger model */
    private fun refreshModelCard() {
        if (!::modelCardTitle.isInitialized) return
        if (mode == 2) {
            modelCardTitle.text = c.getString(R.string.s_262)
            statusText.text = taggerSummary()
            quantText.text = if (taggerLoaded()) {
                c.getString(R.string.s_263) + " · " + TaggerEngine.backendLabel()
            } else {
                c.getString(R.string.s_264)
            }
        } else {
            modelCardTitle.text = c.getString(R.string.s_164)
            try { statusText.text = modelSummary() } catch (_: Throwable) {}
            refreshQuantText()
        }
    }

    /** Quant level of the current draw model (always visible).
     *  The quant level is baked into the model file (a Q4_0 gguf is Q4_0); the app can't convert it out of thin air, only read it out and show it. */
    private fun refreshQuantText() {
        try {
            val m = mainModel ?: activeSetOrSelected()?.mainFile() ?: listModels().maxByOrNull { it.length() }
            quantText.text = if (m == null) {
                c.getString(R.string.s_103)
            } else {
                val q = GgufProbe.quantType(m) ?: c.getString(R.string.s_119)
                c.getString(R.string.v_048, (q), (m.name))
            }
        } catch (_: Throwable) {}
    }

    // ================= Generation =================

    private fun generateFromUi() {
        persistAll()   // persist params before generating, so "edit then generate" is remembered too
        if (!isReady()) {
            val msg = if (llmLoaded) c.getString(R.string.s_100)
            else c.getString(R.string.s_173)
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
            return
        }
        if (DrawCore.busy) {
            Toast.makeText(c, c.getString(R.string.s_428), Toast.LENGTH_LONG).show()
            return
        }
        val prompt = promptEdit.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(c, c.getString(R.string.s_047), Toast.LENGTH_SHORT).show()
            return
        }
        setGenerating(true)
        progressText.visibility = View.VISIBLE
        progressText.text = c.getString(R.string.s_126)
        curStep = 0
        totalStep = 0
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        startPreview()
        onStatus?.invoke(c.getString(R.string.s_165), false)
        val startedAt = System.currentTimeMillis()
        scope.launch {
            // Report "elapsed" every second, otherwise the backend reports no intermediate progress and it looks stuck
            val ticker = launch {
                while (true) {
                    delay(1000)
                    val sec = (System.currentTimeMillis() - startedAt) / 1000
                    progressText.post {
                        val phase = if (sec < 8) c.getString(R.string.s_127) else c.getString(R.string.s_129)
                        val stepInfo = if (totalStep > 0) c.getString(R.string.v_049, (curStep), (totalStep)) else ""
                        progressText.text = c.getString(R.string.v_050, (phase), (stepInfo), (sec))
                        if (::procStepText.isInitialized) {
                            procStepText.text = c.getString(R.string.v_050, (phase), (stepInfo), (sec))
                        }
                        if (totalStep > 0) {
                            progressBar.progress = (curStep * 100 / totalStep).coerceIn(0, 100)
                            if (::procProgressBar.isInitialized) {
                                procProgressBar.progress = (curStep * 100 / totalStep).coerceIn(0, 100)
                            }
                        }
                    }
                }
            }
            try {
                val img = generateImage(
                    prompt,
                    onProgress = { cur, total ->
                        curStep = cur
                        totalStep = total
                    },
                    onPreview = { st, w, h, rgb -> onPreviewFrame(st, w, h, rgb) },
                )
                ticker.cancel()
                val bmp = toBitmap(img)
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                resultImg.post {
                    DrawHistory.add(img)          // into history first (memory)
                    lastImage = bmp
                    resultImg.setImageBitmap(bmp)
                    resultInfo.text = c.getString(R.string.v_051, (img.width), (img.height), (img.seed), (sec))
                    refreshHistory()
                    switchPane(toResult = true)   // jump to the result page automatically after generating
                }
                val doneTxt = c.getString(R.string.v_052, (img.width), (img.height), (img.seed), (sec))
                progressText.post { progressText.text = doneTxt }
                if (::procStepText.isInitialized) procStepText.post { procStepText.text = doneTxt }
                onStatus?.invoke(c.getString(R.string.v_053, (sec)), false)
            } catch (e: Throwable) {
                ticker.cancel()
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                if (cancelRequested) {
                    val stopTxt = c.getString(R.string.v_054, (sec))
                    progressText.post { progressText.text = stopTxt }
                    if (::procStepText.isInitialized) procStepText.post { procStepText.text = stopTxt }
                    onStatus?.invoke(c.getString(R.string.s_154), false)
                } else {
                    val errTxt = c.getString(R.string.v_059, (sec), (e.message ?: e.javaClass.simpleName)) + "\n" + c.getString(R.string.v_060)
                    progressText.post { progressText.text = errTxt }
                    if (::procStepText.isInitialized) procStepText.post { procStepText.text = errTxt }
                    onStatus?.invoke(c.getString(R.string.v_055, (e.message ?: e.javaClass.simpleName)), true)
                }
            } finally {
                setGenerating(false)
                progressBar.post { progressBar.visibility = View.GONE }
                switchResultPage(0)
            }
        }
    }

    /** Premium: batch generation — prompt the user for a count, then generate N images. */
    private fun generateBatch() {
        val countInput = EditText(c).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = c.getString(R.string.s_576)
            setHintTextColor(Color.argb(100, 255, 255, 255))
            setTextColor(Color.WHITE)
            setPadding(c.dp(12), c.dp(8), c.dp(12), c.dp(8))
        }
        val layout =LinearLayout(c).apply {
            orientation =LinearLayout.VERTICAL
            setPadding(c.dp(24), c.dp(16), c.dp(24), c.dp(8))
        }
        val lbl = TextView(c).apply {
            text = c.getString(R.string.s_577)
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, c.dp(12))
        }
        layout.addView(lbl)
        layout.addView(countInput)
        android.app.AlertDialog.Builder(c)
            .setTitle(R.string.s_575)
            .setView(layout)
            .setPositiveButton(R.string.s_098) { _, _ ->
                val count = countInput.text.toString().trim().toIntOrNull()
                if (count == null || count < 1 || count > 10) {
                    Toast.makeText(c, c.getString(R.string.s_578), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runBatch(count)
            }
            .show()
    }

    /** Premium: run N generations sequentially, saving each to filesDir/images/. */
    private fun runBatch(count: Int) {
        persistAll()
        if (!isReady()) {
            val msg = if (llmLoaded) c.getString(R.string.s_100) else c.getString(R.string.s_173)
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
            return
        }
        if (DrawCore.busy) {
            Toast.makeText(c, c.getString(R.string.s_428), Toast.LENGTH_LONG).show()
            return
        }
        val prompt = promptEdit.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(c, c.getString(R.string.s_047), Toast.LENGTH_SHORT).show()
            return
        }
        setGenerating(true)
        progressText.visibility = View.VISIBLE
        progressText.text = c.getString(R.string.v_050, "batch", "", 0)
        curStep = 0
        totalStep = count
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        startPreview()
        onStatus?.invoke(c.getString(R.string.s_165), false)
        val startedAt = System.currentTimeMillis()
        val ticker = scope.launch {
            while (true) {
                delay(1000)
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                progressText.post {
                    val stepInfo = c.getString(R.string.v_049, (curStep), (totalStep))
                    progressText.text = c.getString(R.string.v_050, "batch", (stepInfo), (sec))
                    if (::procStepText.isInitialized) {
                        procStepText.text = c.getString(R.string.v_050, "batch", (stepInfo), (sec))
                    }
                    if (totalStep > 0) {
                        progressBar.progress = (curStep * 100 / totalStep).coerceIn(0, 100)
                        if (::procProgressBar.isInitialized) {
                            procProgressBar.progress = (curStep * 100 / totalStep).coerceIn(0, 100)
                        }
                    }
                }
            }
        }
        scope.launch {
            try {
                var ok = 0
                for (i in 0 until count) {
                    if (cancelRequested) break
                    curStep = i
                    val img = generateImage(
                        prompt,
                        onProgress = { cur, total -> },
                        onPreview = { st, w, h, rgb -> onPreviewFrame(st, w, h, rgb) },
                    )
                    val bmp = toBitmap(img)
                    resultImg.post {
                        DrawHistory.add(img)
                        lastImage = bmp
                        resultImg.setImageBitmap(bmp)
                        resultInfo.text = c.getString(R.string.v_051, (img.width), (img.height), (img.seed), (System.currentTimeMillis() - startedAt) / 1000)
                    }
                    val out = File(c.filesDir, "images/batch_${System.currentTimeMillis()}_${i}.jpg")
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                    }
                    ok++
                }
                ticker.cancel()
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                val doneTxt = c.getString(R.string.v_052, (ok), (count), (sec))
                progressText.post { progressText.text = doneTxt }
                if (::procStepText.isInitialized) procStepText.post { procStepText.text = doneTxt }
                onStatus?.invoke(c.getString(R.string.v_053, (sec)), false)
                Toast.makeText(c, "Batch done: $ok/$count saved", Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
                ticker.cancel()
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                val errTxt = c.getString(R.string.v_059, (sec), (e.message ?: e.javaClass.simpleName)) + "\n" + c.getString(R.string.v_060)
                progressText.post { progressText.text = errTxt }
                if (::procStepText.isInitialized) procStepText.post { procStepText.text = errTxt }
                onStatus?.invoke(c.getString(R.string.v_055, (e.message ?: e.javaClass.simpleName)), true)
            } finally {
                setGenerating(false)
                progressBar.post { progressBar.visibility = View.GONE }
                switchResultPage(0)
            }
        }
    }

    private var curStep = 0
    private var totalStep = 0

    /** Premium: batch generation button (null in free flavor). */
    private var batchBtn: Button? = null

    /** Whether the user has requested cancellation of the current generation (separates "cancel" from a real failure). */
    @Volatile private var cancelRequested = false

    /**
     * Upscale strength: 0.05 (nearly the original, detail only) → 0.99 (nearly a redraw).
     * SeekBar progress 0..94 mapea a 0.05..0.99。
     */
    private fun currentUpscaleStrength(): Float =
        DrawParamsLogic.upscaleStrength(upscaleStrengthBar.progress)

    /** VAE tiling rows are shown only while the toggle is on */
    private fun refreshVaeTilingRows() {
        if (!::vaeTileSizeEdit.isInitialized) return
        val on = vaeTilingCheck.isChecked
        vaeTileSizeEdit.parent?.let { (it as? View)?.visibility = if (on) View.VISIBLE else View.GONE }
        vaeTileOverlapEdit.parent?.let { (it as? View)?.visibility = if (on) View.VISIBLE else View.GONE }
    }

    /** Read the VAE tiling config (null when off, so the engine does full-frame decode) */
    private fun vaeTilingParams(): Triple<Boolean, Int, Float>? {
        if (!::vaeTilingCheck.isInitialized || !vaeTilingCheck.isChecked) return null
        val size = DrawParamsLogic.vaeTileSize(vaeTileSizeEdit.text.toString())
        val overlap = DrawParamsLogic.vaeTileOverlap(vaeTileOverlapEdit.text.toString())
        return Triple(true, size, overlap)
    }

    /** Refresh the live value next to "upscale strength" */
    private fun updateUpscaleStrengthLabel() {
        if (!::upscaleStrengthText.isInitialized || !::upscaleStrengthBar.isInitialized) return
        val v = currentUpscaleStrength()
        // Locale.US: que el separador decimal sea siempre punto, no coma (evita "0,35")
        upscaleStrengthText.text = c.getString(R.string.s_530, String.format(java.util.Locale.US, "%.2f", v))
    }

    /**
     * Upscale the last image (2×).
     *
     * Goes through the "img2img" path: redraw the current result as the reference image (initImage) at 2× size,
     * strength (adjustable, default 0.35) sets how much is redrawn — smaller hugs the original, detail only.
     * Much more stable than "same-seed re-roll": the same seed at a different resolution doesn't guarantee the same face.
     */
    private fun upscaleLast() {
        val src = lastImage
        if (src == null) {
            c.toast(c.getString(R.string.s_183), short = true)
            return
        }
        if (DrawCore.busy) {
            Toast.makeText(c, c.getString(R.string.s_428), Toast.LENGTH_LONG).show()
            return
        }
        val prompt = promptEdit.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(c, c.getString(R.string.s_047), Toast.LENGTH_SHORT).show()
            return
        }
        persistAll()

        val startedAt = System.currentTimeMillis()
        setGenerating(true)
        progressText.visibility = View.VISIBLE
        progressText.text = c.getString(R.string.s_525)
        curStep = 0
        totalStep = 0
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        // Dedicated upscale progress bar (the one on the result page), shown with the progress text
        if (::upscaleProgressBar.isInitialized) {
            upscaleProgressBar.progress = 0
            upscaleProgressBar.visibility = View.VISIBLE
        }
        if (::upscaleProgressText.isInitialized) {
            upscaleProgressText.visibility = View.VISIBLE
            upscaleProgressText.text = c.getString(R.string.s_525)
        }
        onStatus?.invoke(c.getString(R.string.s_525), false)

        // 2× size, clamped to 2048 (sd.cpp limit); steps use the current value, img2img needs few but enough
        val (w, hgt) = DrawParamsLogic.upscaleTargetSize(src.width, src.height)
        val steps = DrawParamsLogic.steps(stepsEdit.text.toString())
        val cfg = DrawParamsLogic.cfg(cfgEdit.text.toString())
        val seed = seedEdit.text.toString().toLongOrNull()?.takeIf { it >= 0 } ?: System.currentTimeMillis()
        val strength = currentUpscaleStrength()   // user-adjustable: 0.05 = nearly original, 0.99 = nearly a redraw

        // Upscale mode: 0 = img2img redraw (redraw with the original as reference), 1 = sd.cpp native hires fix
        // Native hires fix is a separate route: the first pass draws at the original size as usual, then sd.cpp itself
        // upscales the latent and runs a second denoise pass — composition isn't re-rolled, no extra prompt tokens.
        val mode = if (::upscaleModeSpinner.isInitialized) upscaleModeSpinner.selectedItemPosition else 1
        val useNativeHires = mode == 1
        val upscaler = if (::upscaleUpscalerSpinner.isInitialized) {
            SdCppEngine.HiresUpscaler.entries.getOrElse(
                upscaleUpscalerSpinner.selectedItemPosition) { SdCppEngine.HiresUpscaler.LATENT }
        } else {
            SdCppEngine.HiresUpscaler.LATENT
        }
        // Native hires: first-pass size = original size, target = 2×
        val firstPass = if (useNativeHires) DrawParamsLogic.hiresFirstPassSize(src.width, src.height) else null
        val firstW = firstPass?.first ?: w
        val firstH = firstPass?.second ?: hgt
        // Native hires second-pass denoise strength uses the same slider (0.05–0.99)
        // hires native uses denoising_strength: 0 = keep the upscaled latent fully (upscale only, no detail),
        // 1 = resample everything (composition is lost). Passing the slider's 0.05–0.99 straight through is too extreme,
        // so map it to the range hires actually works well in, 0.15–0.55:
        //   0.15 = nearly original + slight sharpening (conservative)
        //   0.35 = balanced (default)
        //   0.55 = clearly more detail (may shift composition)
        val hiresDenoise = if (useNativeHires) {
            DrawParamsLogic.hiresDenoise(strength)
        } else 0.7f
        val vaeTiling = vaeTilingParams()

        scope.launch {
            val ticker = launch {
                while (true) {
                    delay(1000)
                    val sec = (System.currentTimeMillis() - startedAt) / 1000
                    progressText.post {
                        // Upscale uses its own phase word ("Ampliando…"), not the plain generation "Cargando modelo…"
                        val phase = if (sec < 8) c.getString(R.string.s_525) else c.getString(R.string.s_129)
                        val stepInfo = if (totalStep > 0) c.getString(R.string.v_049, curStep, totalStep) else ""
                        progressText.text = c.getString(R.string.v_050, phase, stepInfo, sec)
                        if (::procStepText.isInitialized) {
                            procStepText.text = c.getString(R.string.v_050, phase, stepInfo, sec)
                        }
                        if (totalStep > 0) {
                            progressBar.progress = (curStep * 100 / totalStep).coerceIn(0, 100)
                            if (::procProgressBar.isInitialized) {
                                procProgressBar.progress = (curStep * 100 / totalStep).coerceIn(0, 100)
                            }
                        }
                        // Upscale progress bar (result page): same data source
                        if (::upscaleProgressBar.isInitialized) {
                            if (totalStep > 0) {
                                upscaleProgressBar.progress = (curStep * 100 / totalStep).coerceIn(0, 100)
                            }
                            if (::upscaleProgressText.isInitialized) {
                                upscaleProgressText.text = c.getString(R.string.v_050, phase, stepInfo, sec)
                            }
                        }
                    }
                }
            }
            try {
                val h = sdHandle
                if (h == 0L) throw IllegalStateException(c.getString(R.string.s_163))
                if (mainModel == null) throw IllegalStateException(c.getString(R.string.s_121))
                val lora = if (useLora) activeLora() else null
                val negative = negEdit.text.toString()
                val sampler = pickSampler(lora != null)
                val scheduler = pickScheduler(lora != null)
                val loraScale = DrawParamsLogic.loraScale(loraScaleEdit.text.toString())

                val bmp = withContext(Dispatchers.IO) {
                    if (useNativeHires) {
                        // Native hires fix: no init_image, sd.cpp upscales the latent and denoises again itself.
                        // First-pass size = original size (keeps composition), target size = 2×.
                        SdCppEngine.render(
                            handle = h,
                            prompt = prompt,
                            negative = negative,
                            loraPath = lora?.absolutePath,
                            loraScale = loraScale,
                            width = firstW,
                            height = firstH,
                            steps = steps,
                            cfg = cfg,
                            seed = seed,
                            sampler = sampler,
                            scheduler = scheduler,
                            cb = { cur, total -> curStep = cur; totalStep = total },
                            hiresEnabled = true,
                            hiresTargetWidth = w,
                            hiresTargetHeight = hgt,
                            hiresSteps = steps,
                            hiresDenoiseStrength = hiresDenoise,
                            hiresUpscaler = upscaler,
                            vaeTilingEnabled = vaeTiling?.first ?: false,
                            vaeTileSize = vaeTiling?.second ?: 512,
                            vaeTileOverlap = vaeTiling?.third ?: 0.25f,
                        )
                    } else {
                        // img2img redraw: original as reference, redraw at 2× size
                        val rgb = SdCppEngine.bitmapToRgb888(src)
                        SdCppEngine.render(
                            handle = h,
                            prompt = prompt,
                            negative = negative,
                            loraPath = lora?.absolutePath,
                            loraScale = loraScale,
                            width = w,
                            height = hgt,
                            steps = steps,
                            cfg = cfg,
                            seed = seed,
                            sampler = sampler,
                            scheduler = scheduler,
                            cb = { cur, total -> curStep = cur; totalStep = total },
                            initImage = rgb,
                            initWidth = src.width,
                            initHeight = src.height,
                            strength = strength,
                            vaeTilingEnabled = vaeTiling?.first ?: false,
                            vaeTileSize = vaeTiling?.second ?: 512,
                            vaeTileOverlap = vaeTiling?.third ?: 0.25f,
                        )
                    }
                } ?: throw IllegalStateException(c.getString(R.string.s_147))

                ticker.cancel()
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                val img = ImageData(bmp, seed)
                resultImg.post {
                    DrawHistory.add(img)
                    lastImage = bmp
                    resultImg.setImageBitmap(bmp)
                    resultInfo.text = c.getString(R.string.v_051, bmp.width, bmp.height, seed, sec)
                    refreshHistory()
                    switchPane(toResult = true)
                }
                val doneTxt = c.getString(R.string.v_052, bmp.width, bmp.height, seed, sec)
                progressText.post { progressText.text = doneTxt }
                if (::procStepText.isInitialized) procStepText.post { procStepText.text = doneTxt }
                // Upscale done: fill the bar, then hide it
                if (::upscaleProgressBar.isInitialized) {
                    upscaleProgressBar.post {
                        upscaleProgressBar.progress = 100
                        upscaleProgressBar.visibility = View.GONE
                    }
                }
                if (::upscaleProgressText.isInitialized) {
                    upscaleProgressText.post { upscaleProgressText.visibility = View.GONE }
                }
                onStatus?.invoke(c.getString(R.string.v_053, sec), false)
            } catch (e: Throwable) {
                ticker.cancel()
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                if (cancelRequested) {
                    val stopTxt = c.getString(R.string.v_054, sec)
                    progressText.post { progressText.text = stopTxt }
                    if (::procStepText.isInitialized) procStepText.post { procStepText.text = stopTxt }
                    onStatus?.invoke(c.getString(R.string.s_154), false)
                } else {
                    val errTxt = c.getString(R.string.v_059, sec, e.message ?: e.javaClass.simpleName) + "\n" + c.getString(R.string.v_060)
                    progressText.post { progressText.text = errTxt }
                    if (::procStepText.isInitialized) procStepText.post { procStepText.text = errTxt }
                    onStatus?.invoke(c.getString(R.string.v_055, e.message ?: e.javaClass.simpleName), true)
                }
            } finally {
                setGenerating(false)
                progressBar.post { progressBar.visibility = View.GONE }
                // Upscale error/cancel: hide the bar too
                if (::upscaleProgressBar.isInitialized) {
                    upscaleProgressBar.post { upscaleProgressBar.visibility = View.GONE }
                }
                if (::upscaleProgressText.isInitialized) {
                    upscaleProgressText.post { upscaleProgressText.visibility = View.GONE }
                }
                switchResultPage(0)
            }
        }
    }

    /** Generation entry reused by the chat page: input is the positive prompt, other params come from the draw page's current settings. */
    suspend fun generateImage(
        prompt: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onPreview: ((Int, Int, Int, ByteArray) -> Unit)? = null,
    ): ImageData {
        // Don't jump the queue while the draw endpoint is generating: two tasks on the same native handle at once get scrambled
        if (DrawCore.busy) throw IllegalStateException(c.getString(R.string.s_428))
        val vaeTiling = vaeTilingParams()
        val steps = DrawParamsLogic.steps(stepsEdit.text.toString())
        val cfg = DrawParamsLogic.cfg(cfgEdit.text.toString())
        val seed = seedEdit.text.toString().toLongOrNull() ?: -1L
        val useSeed = if (seed < 0) System.currentTimeMillis() else seed
        // Width/height: sd.cpp requires multiples of 64, rounded down; clamped to 64~2048 against typos
        val w = DrawParamsLogic.size(widthEdit.text.toString())
        val hgt = DrawParamsLogic.size(heightEdit.text.toString())
        onProgress(0, steps)
        cancelRequested = false

        val h = sdHandle
        if (h == 0L) throw IllegalStateException(c.getString(R.string.s_163))
        if (mainModel == null) throw IllegalStateException(c.getString(R.string.s_121))

        // LoRA: via structured params (sd.cpp native API, no more lora: syntax in the prompt)
        val lora = if (useLora) activeLora() else null
        // EditText must be read on the main thread; grab it first, then enter IO
        val negative = negEdit.text.toString()

        // Sampler/scheduler: default "auto" — LCM when LoRA is on (otherwise LCM-LoRA loses much of its effect), else Euler a + Discrete
        val sampler = pickSampler(lora != null)
        val scheduler = pickScheduler(lora != null)
        // LoRA weight: 0 = not attached, 1.0 is standard
        val loraScale = DrawParamsLogic.loraScale(loraScaleEdit.text.toString())

        // Chat-page generation doesn't go through the UI's setGenerating, so report it manually here:
        // otherwise the API side doesn't know the UI is generating and jumps in on the same native handle
        DrawCore.uiBusy = true
        // Trial: el SIGSEGV al generar mata el proceso; el flag queda y el
        // siguiente arranque apaga el toggle GPU.
        if (activeBackend.startsWith("Vulkan")) GpuTrial.begin(prefs())
        try {
            // Inference is a synchronous blocking native call (hundreds of seconds per image), so never on the caller's thread—
            // callers are basically on Dispatchers.Main, otherwise the whole UI would freeze until the image is done.
            val bmp = withContext(Dispatchers.IO) {
                SdCppEngine.render(
                    handle = h,
                    prompt = prompt,
                    negative = negative,
                    loraPath = lora?.absolutePath,
                    loraScale = loraScale,
                    width = w,
                    height = hgt,
                    steps = steps,
                    cfg = cfg,
                    seed = useSeed,
                    sampler = sampler,
                    scheduler = scheduler,
                    cb = { cur, total -> onProgress(cur, total) },
                    onPreview = onPreview?.let { f ->
                        SdCppEngine.PreviewCallback { st, w, h, rgb -> f(st, w, h, rgb) }
                    },
                    vaeTilingEnabled = vaeTiling?.first ?: false,
                    vaeTileSize = vaeTiling?.second ?: 512,
                    vaeTileOverlap = vaeTiling?.third ?: 0.25f,
                )
            } ?: throw IllegalStateException(c.getString(R.string.s_147))
            onProgress(steps, steps)
            GpuTrial.clear(prefs())
            return ImageData(bmp, useSeed)
        } finally {
            DrawCore.uiBusy = false
        }
    }

    /** Sampler pick: index 0 = auto; the rest follow SdCppEngine.Sampler order */
    private fun pickSampler(hasLora: Boolean): SdCppEngine.Sampler {
        val idx = samplerSpinner.selectedItemPosition
        if (idx <= 0) return if (hasLora) SdCppEngine.Sampler.LCM else SdCppEngine.Sampler.EULER_A
        return SdCppEngine.Sampler.entries.getOrElse(idx - 1) { SdCppEngine.Sampler.EULER_A }
    }

    /** Scheduler pick: index 0 = auto */
    private fun pickScheduler(hasLora: Boolean): SdCppEngine.Scheduler {
        val idx = schedulerSpinner.selectedItemPosition
        if (idx <= 0) return if (hasLora) SdCppEngine.Scheduler.LCM else SdCppEngine.Scheduler.DISCRETE
        return SdCppEngine.Scheduler.entries.getOrElse(idx - 1) { SdCppEngine.Scheduler.DISCRETE }
    }

    // ================= img2img generation =================

    /** img2img sampler (the spinner on the img2img page) */
    private fun pickI2iSampler(hasLora: Boolean): SdCppEngine.Sampler {
        val idx = i2iSamplerSpinner.selectedItemPosition
        if (idx <= 0) return if (hasLora) SdCppEngine.Sampler.LCM else SdCppEngine.Sampler.EULER_A
        return SdCppEngine.Sampler.entries.getOrElse(idx - 1) { SdCppEngine.Sampler.EULER_A }
    }

    /** img2img scheduler */
    private fun pickI2iScheduler(hasLora: Boolean): SdCppEngine.Scheduler {
        val idx = i2iSchedulerSpinner.selectedItemPosition
        if (idx <= 0) return if (hasLora) SdCppEngine.Scheduler.LCM else SdCppEngine.Scheduler.DISCRETE
        return SdCppEngine.Scheduler.entries.getOrElse(idx - 1) { SdCppEngine.Scheduler.DISCRETE }
    }

    /** The "Generate" button on the img2img page */
    private fun generateFromUiI2i() {
        persistAll()
        if (!isReady()) {
            val msg = if (llmLoaded) c.getString(R.string.s_100)
            else c.getString(R.string.s_173)
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
            return
        }
        if (DrawCore.busy) {
            Toast.makeText(c, c.getString(R.string.s_428), Toast.LENGTH_LONG).show()
            return
        }
        if (i2iBitmap == null) {
            Toast.makeText(c, c.getString(R.string.s_214), Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = i2iPromptEdit.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(c, c.getString(R.string.s_047), Toast.LENGTH_SHORT).show()
            return
        }
        setGenerating(true)
        i2iProgressText.visibility = View.VISIBLE
        i2iProgressText.text = c.getString(R.string.s_126)
        curStep = 0
        totalStep = 0
        i2iProgressBar.progress = 0
        i2iProgressBar.visibility = View.VISIBLE
        startPreview()
        onStatus?.invoke(c.getString(R.string.s_165), false)
        val startedAt = System.currentTimeMillis()
        scope.launch {
            val ticker = launch {
                while (true) {
                    delay(1000)
                    val sec = (System.currentTimeMillis() - startedAt) / 1000
                    i2iProgressText.post {
                        val phase = if (sec < 8) c.getString(R.string.s_127) else c.getString(R.string.s_129)
                        val stepInfo = if (totalStep > 0) c.getString(R.string.v_049, (curStep), (totalStep)) else ""
                        i2iProgressText.text = c.getString(R.string.v_050, (phase), (stepInfo), (sec))
                        if (::procStepText.isInitialized) {
                            procStepText.text = c.getString(R.string.v_050, (phase), (stepInfo), (sec))
                        }
                        if (totalStep > 0) {
                            i2iProgressBar.progress = (curStep * 100 / totalStep).coerceIn(0, 100)
                            if (::procProgressBar.isInitialized) {
                                procProgressBar.progress = (curStep * 100 / totalStep).coerceIn(0, 100)
                            }
                        }
                    }
                }
            }
            try {
                val img = generateImageI2i(
                    prompt,
                    onProgress = { cur, total ->
                        curStep = cur
                        totalStep = total
                    },
                    onPreview = { st, w, h, rgb -> onPreviewFrame(st, w, h, rgb) },
                )
                ticker.cancel()
                val bmp = toBitmap(img)
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                resultImg.post {
                    DrawHistory.add(img)
                    lastImage = bmp
                    resultImg.setImageBitmap(bmp)
                    resultInfo.text = c.getString(R.string.v_051, (img.width), (img.height), (img.seed), (sec))
                    refreshHistory()
                    switchPane(toResult = true)
                }
                val i2iDoneTxt = c.getString(R.string.v_052, (img.width), (img.height), (img.seed), (sec))
                i2iProgressText.post { i2iProgressText.text = i2iDoneTxt }
                if (::procStepText.isInitialized) procStepText.post { procStepText.text = i2iDoneTxt }
                onStatus?.invoke(c.getString(R.string.v_053, (sec)), false)
            } catch (e: Throwable) {
                ticker.cancel()
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                if (cancelRequested) {
                    val i2iStopTxt = c.getString(R.string.v_054, (sec))
                    i2iProgressText.post { i2iProgressText.text = i2iStopTxt }
                    if (::procStepText.isInitialized) procStepText.post { procStepText.text = i2iStopTxt }
                    onStatus?.invoke(c.getString(R.string.s_154), false)
                } else {
                    val i2iErrTxt = c.getString(R.string.v_059, (sec), (e.message ?: e.javaClass.simpleName)) + "\n" + c.getString(R.string.v_060)
                    i2iProgressText.post { i2iProgressText.text = i2iErrTxt }
                    if (::procStepText.isInitialized) procStepText.post { procStepText.text = i2iErrTxt }
                    onStatus?.invoke(c.getString(R.string.v_055, (e.message ?: e.javaClass.simpleName)), true)
                }
            } finally {
                setGenerating(false)
                i2iProgressBar.post { i2iProgressBar.visibility = View.GONE }
                switchResultPage(0)
            }
        }
    }

    /** Actual img2img inference: encodes the picked reference image to RGB888 for native, strength controls redraw amount */
    private suspend fun generateImageI2i(
        prompt: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onPreview: ((Int, Int, Int, ByteArray) -> Unit)? = null,
    ): ImageData {
        val steps = DrawParamsLogic.steps(i2iStepsEdit.text.toString())
        val cfg = DrawParamsLogic.cfg(i2iCfgEdit.text.toString())
        val seed = i2iSeedEdit.text.toString().toLongOrNull() ?: -1L
        val useSeed = if (seed < 0) System.currentTimeMillis() else seed
        val w = DrawParamsLogic.size(i2iWidthEdit.text.toString())
        val hgt = DrawParamsLogic.size(i2iHeightEdit.text.toString())
        val strength = DrawParamsLogic.i2iStrength(i2iStrengthEdit.text.toString())
        val vaeTiling = vaeTilingParams()
        onProgress(0, steps)
        cancelRequested = false

        val h = sdHandle
        if (h == 0L) throw IllegalStateException(c.getString(R.string.s_163))
        if (mainModel == null) throw IllegalStateException(c.getString(R.string.s_121))
        val src = i2iBitmap ?: throw IllegalStateException(c.getString(R.string.s_214))

        val lora = if (useLora) activeLora() else null
        val negative = i2iNegEdit.text.toString()
        val sampler = pickI2iSampler(lora != null)
        val scheduler = pickI2iScheduler(lora != null)
        val loraScale = DrawParamsLogic.loraScale(i2iLoraScaleEdit.text.toString())

        if (activeBackend.startsWith("Vulkan")) GpuTrial.begin(prefs())
        val bmp = withContext(Dispatchers.IO) {
            val rgb = SdCppEngine.bitmapToRgb888(src)
            SdCppEngine.render(
                handle = h,
                prompt = prompt,
                negative = negative,
                loraPath = lora?.absolutePath,
                loraScale = loraScale,
                width = w,
                height = hgt,
                steps = steps,
                cfg = cfg,
                seed = useSeed,
                sampler = sampler,
                scheduler = scheduler,
                cb = { cur, total -> onProgress(cur, total) },
                onPreview = onPreview?.let { f ->
                    SdCppEngine.PreviewCallback { st, w, h, rgb -> f(st, w, h, rgb) }
                },
                initImage = rgb,
                initWidth = src.width,
                initHeight = src.height,
                strength = strength,
                vaeTilingEnabled = vaeTiling?.first ?: false,
                vaeTileSize = vaeTiling?.second ?: 512,
                vaeTileOverlap = vaeTiling?.third ?: 0.25f,
            )
        } ?: throw IllegalStateException(c.getString(R.string.s_147))
        onProgress(steps, steps)
        GpuTrial.clear(prefs())
        return ImageData(bmp, useSeed)
    }

    fun toBitmap(img: ImageData): Bitmap = img.bitmap

    /** For the chat page: forwards generation preview frames to the "process" page */
    fun previewFrame(step: Int, w: Int, h: Int, rgb: ByteArray) = onPreviewFrame(step, w, h, rgb)

    /** "Cancel generation" tap: immediate feedback first, then request a native cancel */
    private fun doCancel() {
        cancel()
        progressText.text = c.getString(R.string.s_125)
    }

    /** Uniformly switches the button's "start generation / cancel generation" look and behavior */
    private fun setGenerating(g: Boolean) {
        generating = g
        DrawCore.uiBusy = g   // report to the API side: the UI is generating, don't grab the same handle
        genBtn.post {
            genBtn.isEnabled = true
            genBtn.text = if (g) c.getString(R.string.s_040) else c.getString(R.string.s_098)
            genBtn.setBackgroundColor(if (g) pal.stop else primary)
        }
        if (::i2iGenBtn.isInitialized) i2iGenBtn.post {
            i2iGenBtn.isEnabled = true
            i2iGenBtn.text = if (g) c.getString(R.string.s_040) else c.getString(R.string.s_098)
            i2iGenBtn.setBackgroundColor(if (g) pal.stop else primary)
        }
        // "Cancel generation" button on the result page: shown only while generating
        if (::resultCancelBtn.isInitialized) resultCancelBtn.post {
            resultCancelBtn.visibility = if (g) View.VISIBLE else View.GONE
        }
    }

    fun cancel() {
        cancelRequested = true
        if (sdHandle != 0L) runCatching { SdCppEngine.nativeCancel(sdHandle) }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean {
        if (requestCode != REQ_IMAGE && requestCode != REQ_TAGGER_IMAGE) return false
        if (resultCode != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { decodePickedImage(uri) }
            if (bmp == null) {
                c.toast(c.getString(R.string.s_215), short = true)
            } else if (requestCode == REQ_IMAGE) {
                applyPickedImage(bmp)
            } else {
                applyTaggerImage(bmp)
            }
        }
        return true
    }

    fun release() {
        // Can't free the native handle while generation is still running: sd.cpp's free_sd_ctx destroys the
        // VAE/UNet in use, ggml_runner hits GGML_ASSERT(!graph_active_) and aborts outright,
        // native-crashing the whole process (SIGABRT). Left to native-side deferred release instead.
        if (DrawCore.uiBusy) {
            // Let the engine wind down itself: nativeFree registers it as pending and really frees it when the inference thread exits.
            DrawCore.release()
            if (sdHandle != 0L) runCatching { SdCppEngine.nativeFree(sdHandle) }
            sdHandle = 0L
            return
        }
        DrawCore.release()   // order matters: drop the API-side reference first, then really free the native handle
        if (sdHandle != 0L) runCatching { SdCppEngine.nativeFree(sdHandle) }
        sdHandle = 0L
    }

    // ================= UI helpers =================

    private fun card() = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(pal.surface)
        setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = c.dp(10) }
    }

    private fun title(t: String) = TextView(c).apply {
        text = t
        textSize = 14.5f
        setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun body(t: String) = TextView(c).apply {
        text = t
        textSize = 12.5f
        setTextColor(subText)
        setPadding(0, c.dp(6), 0, 0)
    }

    private fun smallLabel(t: String) = TextView(c).apply {
        text = t
        textSize = 11.5f
        setTextColor(subText)
        setPadding(0, c.dp(8), 0, 0)
    }

    private fun labeledEdit(hint: String, singleLine: Boolean, minLines: Int = 1) = EditText(c).apply {
        this.hint = hint
        textSize = 13f
        setTextColor(textColor)
        setHintTextColor(pal.hint)
        setBackgroundColor(pal.fieldBg)
        setPadding(c.dp(10), c.dp(8), c.dp(10), c.dp(8))
        isSingleLine = singleLine
        if (!singleLine) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            this.minLines = minLines
        }
    }

    private fun smallNumber(def: String) = EditText(c).apply {
        setText(def)
        textSize = 13f
        setTextColor(textColor)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setBackgroundColor(pal.fieldBg)
        setPadding(c.dp(10), c.dp(8), c.dp(10), c.dp(8))
    }

    private fun paramRow(label: String, input: View, hint: String): View {
        val wrap = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(smallLabel(label))
        wrap.addView(input, c.matchWrap(top = 4))
        wrap.addView(TextView(c).apply {
            text = hint
            textSize = 10.5f
            setTextColor(subText)
            setPadding(0, c.dp(3), 0, 0)
        })
        return wrap
    }

    /** One item of the top menu */
    private fun tabItem(t: String) = TextView(c).apply {
        text = t
        textSize = 13.5f
        gravity = Gravity.CENTER
        setPadding(0, c.dp(11), 0, c.dp(11))
    }

    /** Switch the "params / result" panes (animated paging) and highlight the current item */
    private fun switchPane(toResult: Boolean) {
        if (!::paramPane.isInitialized) return
        if (::pager.isInitialized) pager.setCurrentItem(if (toResult) 1 else 0, true)
        highlightTabs(toResult)
    }

    /** Sync the top menu highlight; also updated from here on swipe paging */
    private fun highlightTabs(toResult: Boolean) {
        if (!::tabParams.isInitialized) return
        for ((tv, sel) in listOf(tabParams to !toResult, tabResult to toResult)) {
            tv.setTextColor(if (sel) primary else subText)
            tv.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setBackgroundColor(if (sel) pal.primarySoft else pal.surface)
        }
    }

    /** Switch the "result / process" subpage inside the result panel */
    private fun switchResultPage(p: Int) {
        if (!::resultContent.isInitialized) return
        resultPage = p
        resultContent.visibility = if (p == 0) View.VISIBLE else View.GONE
        processContent.visibility = if (p == 1) View.VISIBLE else View.GONE
        for ((tv, sel) in listOf(tabResResult to (p == 0), tabResProcess to (p == 1))) {
            tv.setTextColor(if (sel) primary else subText)
            tv.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setBackgroundColor(if (sel) pal.primarySoft else pal.surface)
        }
    }

    /** Start generating: clear the previous preview and switch to the "process" page */
    private fun startPreview() {
        if (!::processContent.isInitialized) return
        previewBusy = false
        previewImg.setImageDrawable(null)
        procProgressBar.progress = 0
        procStepText.text = c.getString(R.string.s_126)
        switchPane(toResult = true)
        switchResultPage(1)
    }

    /** Preview frame (native inference thread callback) → throttled onto the "process" page */
    private fun onPreviewFrame(step: Int, w: Int, h: Int, rgb: ByteArray) {
        if (!::previewImg.isInitialized) return
        if (previewBusy) return
        val bmp = SdCppEngine.rgbToBitmap(w, h, rgb) ?: return
        previewBusy = true
        previewImg.post {
            previewImg.setImageBitmap(bmp)
            val total = totalStep
            if (total > 0) {
                procProgressBar.progress = (step * 100 / total).coerceIn(0, 100)
                procStepText.text = c.getString(R.string.s_129) + c.getString(R.string.v_049, (step), (total))
            }
            previewBusy = false
        }
    }

    /** Scroll a panel back to the very top (runs after layout, so the height isn't still 0 right after a page switch) */
    private fun scrollTop(s: ScrollView?) {
        s ?: return
        s.post { s.smoothScrollTo(0, 0) }
    }

    /** Wrap a panel in a vertically scrollable ScrollView (the pager needs a child with a definite height) */
    private fun scrollWrap(inner: View) = ScrollView(c).apply {
        isFillViewport = true
        addView(inner, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Minimal PagerAdapter for ViewPager: reuses the two panes already built, no rebuild */
    private class PaneAdapter(private val panes: List<View>) : PagerAdapter() {
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

    /** Width × height input row */
    /**
     * Size preset button group: 64/128/256/384/512 + "Personalizado".
     *
     * Pick a preset → width/height are filled directly; pick Personalizado → the custom input row appears.
     * Editing width/height by hand switches back to "Personalizado" (so the button state can't disagree with the actual values).
     */
    private fun sizePresetRow(): View {
        val wrap = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(smallLabel(c.getString(R.string.s_532)))

        val presets = SIZE_PRESETS
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sizePresetButtons.clear()
        presets.forEachIndexed { idx, px ->
            val b = android.widget.Button(c).apply {
                text = "$px"
                textSize = 12f
                setTextColor(textColor)
                isAllCaps = false
                setPadding(c.dp(4), c.dp(2), c.dp(4), c.dp(2))
                setOnClickListener {
                    applySizePreset(idx)
                }
            }
            sizePresetButtons.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = c.dp(2)
                marginEnd = c.dp(2)
            })
        }
        // "Personalizado" button: in the same row, slightly larger weight (longer text)
        val customBtn = android.widget.Button(c).apply {
            text = c.getString(R.string.s_533)
            textSize = 11f
            setTextColor(textColor)
            isAllCaps = false
            setPadding(c.dp(4), c.dp(2), c.dp(4), c.dp(2))
            setOnClickListener { applySizePreset(-1) }
        }
        // Added last, so applySizePreset can use index == -1 for "custom" and highlight it uniformly
        sizePresetButtons.add(customBtn)
        row.addView(customBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f).apply {
            marginStart = c.dp(4)
        })
        wrap.addView(row, c.matchWrap(top = 4))

        // Custom width/height input row (hidden by default, appears only when "Personalizado" is picked)
        val customRow = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        customRow.addView(smallLabel(c.getString(R.string.s_534)))
        val fields = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fields.addView(widthEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fields.addView(TextView(c).apply {
            text = " × "
            textSize = 14f
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        fields.addView(heightEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        customRow.addView(fields, c.matchWrap(top = 4))
        customRow.addView(TextView(c).apply {
            text = c.getString(R.string.s_535)
            textSize = 10.5f
            setTextColor(subText)
            setPadding(0, c.dp(3), 0, 0)
        })
        customRow.visibility = View.GONE
        customSizeRow = customRow
        wrap.addView(customRow, c.matchWrap(top = 6))

        // Initial state: work out which preset the current width/height falls on
        val cur = widthEdit.text.toString().toIntOrNull()
        val idx = presets.indexOf(cur).takeIf { it >= 0 && heightEdit.text.toString().toIntOrNull() == cur }
        applySizePreset(idx ?: -1, updateFields = false)

        // User edits width/height by hand → switch to "Personalizado" automatically and show the input row
        // (otherwise the button highlight and the actual values would disagree, misleading the user)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (suppressPresetSync) return
                val w = widthEdit.text.toString().toIntOrNull()
                val h = heightEdit.text.toString().toIntOrNull()
                val match = presets.indexOf(w).takeIf { it >= 0 && h == w }
                if (match == null && selectedSizePreset != null) {
                    applySizePreset(-1, updateFields = false)
                }
            }
        }
        widthEdit.addTextChangedListener(watcher)
        heightEdit.addTextChangedListener(watcher)

        return wrap
    }

    /** Highlight the selected preset button and show/hide the custom input row */
    private fun applySizePreset(index: Int, updateFields: Boolean = true) {
        if (updateFields && index >= 0) {
            val px = SIZE_PRESETS.getOrNull(index) ?: return
            suppressPresetSync = true
            widthEdit.setText(px.toString())
            heightEdit.setText(px.toString())
            suppressPresetSync = false
        }
        selectedSizePreset = if (index >= 0) index else null
        sizePresetButtons.forEachIndexed { i, b ->
            // The last one is "Personalizado": highlight it when index == -1
            val isCustom = i == sizePresetButtons.size - 1
            val on = if (isCustom) index == -1 else i == index
            b.setBackgroundColor(if (on) pal.primary else pal.fieldBg)
            b.setTextColor(if (on) pal.bg else textColor)
        }
        customSizeRow?.visibility = if (index == -1) View.VISIBLE else View.GONE
    }

    private fun whRow(label: String, hint: String, wEdit: EditText, hEdit: EditText): View {
        val wrap = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(smallLabel(label))
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(wEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(c).apply {
            text = " × "
            textSize = 14f
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(hEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        wrap.addView(row, c.matchWrap(top = 4))
        wrap.addView(TextView(c).apply {
            text = hint
            textSize = 10.5f
            setTextColor(subText)
            setPadding(0, c.dp(3), 0, 0)
        })
        return wrap
    }

    private fun choiceSpinner(items: List<String>) = android.widget.Spinner(c).apply {
        adapter = android.widget.ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, items)
    }

    // ---- Param persistence (prompt not saved, starts over each time) ----

    private fun prefs() = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadParam(key: String, def: String): String = prefs().getString(key, def) ?: def

    private fun loadInt(key: String, def: Int): Int = prefs().getInt(key, def)

    /**
     * Param edits are persisted immediately. Binding happens after setText(initial value),
     * so init doesn't write the restored value back.
     */
    private fun bindParam(edit: EditText, key: String) {
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s?.toString().orEmpty()
                if (v.isNotBlank()) prefs().edit().putString(key, v).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, b: Int, cnt: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, cnt: Int) {}
        })
    }

    /**
     * Write all current params to disk — called as a safety net by MainActivity.onPause() and "Start generation".
     * Can't rely on the input fields' TextWatcher alone: tab switches, outright process kills etc. may skip it.
     */
    fun persistAll() {
        if (!::stepsEdit.isInitialized) return
        val e = prefs().edit()
        fun put(k: String, ed: EditText) {
            val v = ed.text.toString()
            if (v.isNotBlank()) e.putString(k, v)
        }
        put(KEY_W, widthEdit)
        put(KEY_H, heightEdit)
        put(KEY_STEPS, stepsEdit)
        put(KEY_CFG, cfgEdit)
        put(KEY_SEED, seedEdit)
        put(KEY_LORA_SCALE, loraScaleEdit)
        if (::upscaleStrengthBar.isInitialized) {
            e.putString(KEY_UPSCALE_STRENGTH, currentUpscaleStrength().toString())
        }
        e.putInt(KEY_SAMPLER, samplerSpinner.selectedItemPosition)
        e.putInt(KEY_SCHEDULER, schedulerSpinner.selectedItemPosition)
        if (::i2iStepsEdit.isInitialized) {
            put(KEY_I2I_W, i2iWidthEdit)
            put(KEY_I2I_H, i2iHeightEdit)
            put(KEY_I2I_STEPS, i2iStepsEdit)
            put(KEY_I2I_CFG, i2iCfgEdit)
            put(KEY_I2I_SEED, i2iSeedEdit)
            put(KEY_I2I_STRENGTH, i2iStrengthEdit)
            put(KEY_I2I_LORA_SCALE, i2iLoraScaleEdit)
            e.putInt(KEY_I2I_SAMPLER, i2iSamplerSpinner.selectedItemPosition)
            e.putInt(KEY_I2I_SCHEDULER, i2iSchedulerSpinner.selectedItemPosition)
        }
        if (::taggerChanSpinner.isInitialized) {
            e.putInt(KEY_TAG_CHAN, taggerChanSpinner.selectedItemPosition)
        }
        e.apply()
        syncDrawCore()   // sync to the API side whenever a param changes (takes effect on the next request)
    }

    /** Persist Spinner selection */
    private fun persistSpinner(key: String) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
            prefs().edit().putInt(key, pos).apply()
        }
        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
    }

    // ---- Defaults (text-to-image / img2img get one set per LoRA on/off; Tagger is not split) ----

    /** Current LoRA state (0=off 1=on), used as the defaults key suffix */
    private fun loraTag(): String = if (useLora) "1" else "0"

    private fun builtinSteps(): String = if (useLora) "6" else "20"
    private fun builtinCfg(): String = if (useLora) "1.8" else "7.0"

    /** Get a page's param defaults: the user-saved default first, otherwise built-in */
    private fun defOf(page: String, name: String, builtin: String): String =
        prefs().getString("def_${page}_${name}_${loraTag()}", null) ?: builtin

    private fun defOfInt(page: String, name: String, builtin: Int): Int =
        prefs().getInt("def_${page}_${name}_${loraTag()}", builtin)

    /** Store the current params of the current page as that page's defaults (for the current LoRA state) */
    fun saveDefaults(page: String) {
        val lg = loraTag()
        val e = prefs().edit()
        fun put(n: String, ed: EditText) { e.putString("def_${page}_${n}_$lg", ed.text.toString()) }
        when (page) {
            "t2i" -> {
                put("w", widthEdit); put("h", heightEdit); put("steps", stepsEdit)
                put("cfg", cfgEdit); put("seed", seedEdit); put("loraScale", loraScaleEdit)
                e.putInt("def_t2i_sampler_$lg", samplerSpinner.selectedItemPosition)
                e.putInt("def_t2i_scheduler_$lg", schedulerSpinner.selectedItemPosition)
            }
            "i2i" -> {
                put("strength", i2iStrengthEdit)
                put("w", i2iWidthEdit); put("h", i2iHeightEdit); put("steps", i2iStepsEdit)
                put("cfg", i2iCfgEdit); put("seed", i2iSeedEdit); put("loraScale", i2iLoraScaleEdit)
                e.putInt("def_i2i_sampler_$lg", i2iSamplerSpinner.selectedItemPosition)
                e.putInt("def_i2i_scheduler_$lg", i2iSchedulerSpinner.selectedItemPosition)
            }
            "tag" -> {
                e.putString("def_tag_threshold", taggerThresholdEdit.text.toString())
                e.putString("def_tag_topk", taggerTopKEdit.text.toString())
                e.putInt("def_tag_chan", taggerChanSpinner.selectedItemPosition)
            }
        }
        e.apply()
        c.toast(c.getString(R.string.s_250), short = true)
    }

    /** Apply the defaults to the given page (current LoRA state) */
    fun applyDefaults(page: String) {
        when (page) {
            "t2i" -> {
                widthEdit.setText(defOf("t2i", "w", "512"))
                heightEdit.setText(defOf("t2i", "h", "512"))
                stepsEdit.setText(defOf("t2i", "steps", builtinSteps()))
                cfgEdit.setText(defOf("t2i", "cfg", builtinCfg()))
                seedEdit.setText(defOf("t2i", "seed", "-1"))
                loraScaleEdit.setText(defOf("t2i", "loraScale", "1.0"))
                samplerSpinner.setSelection(defOfInt("t2i", "sampler", 0), false)
                schedulerSpinner.setSelection(defOfInt("t2i", "scheduler", 0), false)
            }
            "i2i" -> {
                i2iStrengthEdit.setText(defOf("i2i", "strength", "0.75"))
                i2iWidthEdit.setText(defOf("i2i", "w", "512"))
                i2iHeightEdit.setText(defOf("i2i", "h", "512"))
                i2iStepsEdit.setText(defOf("i2i", "steps", builtinSteps()))
                i2iCfgEdit.setText(defOf("i2i", "cfg", builtinCfg()))
                i2iSeedEdit.setText(defOf("i2i", "seed", "-1"))
                i2iLoraScaleEdit.setText(defOf("i2i", "loraScale", "1.0"))
                i2iSamplerSpinner.setSelection(defOfInt("i2i", "sampler", 0), false)
                i2iSchedulerSpinner.setSelection(defOfInt("i2i", "scheduler", 0), false)
            }
            "tag" -> {
                taggerThresholdEdit.setText(prefs().getString("def_tag_threshold", null) ?: "0.35")
                taggerTopKEdit.setText(prefs().getString("def_tag_topk", null) ?: "40")
                taggerChanSpinner.setSelection(prefs().getInt("def_tag_chan", 0).coerceIn(0, 1), false)
            }
        }
    }

    /** "Set as default / restore default" row at the bottom of the params card (needs confirmation) */
    private fun defaultButtonsRow(page: String): LinearLayout {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ghostButton(c.getString(R.string.s_248)) { confirmDefaults(page, true) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ghostButton(c.getString(R.string.s_249)) { confirmDefaults(page, false) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = c.dp(8) })
        return row
    }

    /** Shows a confirmation dialog first, only applies on confirm (save=true stores as default, false restores) */
    private fun confirmDefaults(page: String, save: Boolean) {
        val pageName = c.getString(when (page) {
            "t2i" -> R.string.s_207
            "i2i" -> R.string.s_208
            else -> R.string.s_221
        })
        android.app.AlertDialog.Builder(act)
            .setTitle(c.getString(if (save) R.string.s_248 else R.string.s_249))
            .setMessage(c.getString(if (save) R.string.s_256 else R.string.s_257, pageName))
            .setPositiveButton(c.getString(R.string.s_258)) { _, _ ->
                if (save) saveDefaults(page) else applyDefaults(page)
            }
            .setNegativeButton(c.getString(R.string.s_066), null)
            .show()
    }

    private fun ghostButton(t: String, onClick: () -> Unit) = Button(c).apply {
        text = t
        textSize = 12.5f
        setTextColor(primary)
        setBackgroundColor(pal.primarySoft)
        setOnClickListener { onClick() }
    }

    /** File name used to save the result image */
    private fun saveName(): String = "ponko_${System.currentTimeMillis()}.png"

    // ---- Generation history (memory only, cleared when the process ends) ----

    /** Thumbnail tap: switch the big image to this one */
    private fun showHistoryImage(img: ImageData) {
        lastImage = img.bitmap
        resultImg.setImageBitmap(img.bitmap)
        resultInfo.text = "${img.width}×${img.height} · seed ${img.seed}"
    }

    /** Rebuild the thumbnail list */
    private fun refreshHistory() {
        if (!::historyRow.isInitialized) return
        val items = DrawHistory.list()
        histEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        historyRow.removeAllViews()
        for (img in items) {
            val iv = ImageView(c).apply {
                setImageBitmap(img.bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(pal.mediaBg)
                setOnClickListener { showHistoryImage(img) }
                setOnLongClickListener {
                    android.app.AlertDialog.Builder(act)
                        .setMessage(c.getString(R.string.s_057))
                        .setPositiveButton(c.getString(R.string.s_050)) { _, _ -> DrawHistory.remove(img); refreshHistory() }
                        .setNegativeButton(c.getString(R.string.s_066), null)
                        .show()
                    true
                }
            }
            historyRow.addView(iv, LinearLayout.LayoutParams(c.dp(76), c.dp(76)).apply { rightMargin = c.dp(8) })
        }
    }
}


/** Generation result: Bitmap + size + seed actually used */
class ImageData(val bitmap: Bitmap, val seed: Long) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}

/**
 * Draw result history — memory only, cleared when the app process ends (user asked to "keep until the program is killed").
 * Kept in an object rather than a DrawPage field, so an Activity rebuild (screen rotation,
 * restore after memory reclaim) still sees the same history.
 */
object DrawHistory {
    /** Keep at most this many images, so endless generation doesn't blow up memory (a 512×512 one is ~1MB) */
    private const val MAX = 30
    private val items = mutableListOf<ImageData>()

    /** Newest first */
    fun add(img: ImageData) {
        items.add(0, img)
        while (items.size > MAX) items.removeAt(items.size - 1)
    }

    fun list(): List<ImageData> = items

    fun remove(img: ImageData) {
        items.remove(img)
    }

    fun clear() = items.clear()

    fun size() = items.size
}
