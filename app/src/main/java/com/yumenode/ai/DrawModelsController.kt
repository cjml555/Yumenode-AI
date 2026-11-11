// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.yumenode.ai.draw.DrawPage
import com.yumenode.ai.draw.GgufProbe
import com.yumenode.ai.draw.ModelSet
import com.yumenode.ai.draw.TaggerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lógica de slots de dibujo, LoRA/TAESD, tagger y modelos guardados.
 *
 * Extraída tal cual de MainActivity: mismos request codes, paths, textos,
 * orden de listas y descargas. Solo recibe [DrawModelsHost]; el resto de la
 * Activity queda fuera.
 */
class DrawModelsController(private val host: DrawModelsHost) : ThemeAliases {
    override val palette: YumenodeTheme get() = host.palette

    private val activity get() = host.activity

    // ---- delegación al host: MainActivity conserva las vistas y el estado ----
    private val drawPage get() = host.drawPage
    private val ui get() = host.ui
    private val scope get() = host.scope
    private val attachmentHandler get() = host.attachmentHandler
    private val taggerUi get() = host.taggerUi
    private val drawSlotsContainer get() = host.drawSlotsContainer
    private val drawSlotsHeader get() = host.drawSlotsHeader
    private val drawModelStatus get() = host.drawModelStatus
    private val loraStatusTv get() = host.loraStatusTv
    private val loraBox get() = host.loraBox
    private val taggerStatusTv get() = host.taggerStatusTv
    private val taggerBox get() = host.taggerBox
    private val taggerLoadBtn get() = host.taggerLoadBtn
    private val savedContainer get() = host.savedContainer
    private val modelInfoText get() = host.modelInfoText
    private val progressBar get() = host.progressBar
    private val ggufKindCache get() = host.ggufKindCache
    private val filesDir get() = activity.filesDir
    private val contentResolver get() = activity.contentResolver
    private var pendingSlotKey: String?
        get() = host.pendingSlotKey
        set(v) { host.pendingSlotKey = v }
    private var drawSlotsExpanded: Boolean
        get() = host.drawSlotsExpanded
        set(v) { host.drawSlotsExpanded = v }
    private var drawSetId: String?
        get() = host.drawSetId
        set(v) { host.drawSetId = v }
    private var modelPath: String?
        get() = host.modelPath
        set(v) { host.modelPath = v }
    private var busy: Boolean
        get() = host.isBusy
        set(v) { host.isBusy = v }

    private fun getString(id: Int, vararg args: Any?) = activity.getString(id, *args)
    private fun toast(msg: String) = host.toast(msg)

    private fun readChatImage(name: String): Bitmap? = host.readChatImage(name)
    private fun setStatus(text: String, color: Int) = host.setStatus(text, color)
    private fun dp(v: Int) = activity.dp(v)
    private fun rounded(color: Int, radiusDp: Int, strokeDp: Int = 0, strokeColor: Int = 0) =
        activity.rounded(color, radiusDp, strokeDp, strokeColor)
    private fun matchWrap(top: Int = 0) = activity.matchWrap(top)
    private fun applyBusy(b: Boolean) = host.applyBusy(b)
    private fun setDrawBusy(b: Boolean) = host.setDrawBusy(b)
    private fun loadChatParamsIntoUi() = host.loadChatParamsIntoUi()
    private fun refreshDrawModels() = host.refreshDrawModels()
    private fun refreshDrawToggle() = host.refreshDrawToggle()

    /** 对话模型：选一个 .litertlm / .gguf 文件 */
    fun pickModelFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        activity.startActivityForResult(i, REQ_PICK_MODEL)
    }

    /** 「选择绘图模型文件」→ 先挑类型（单文件 / 多文件 × 家族），再选文件。
     *  单文件 = 一个 all-in-one .gguf；多文件 = 主模型 + 若干配套文件（按家族给槽位模板）。 */
    fun showDrawTypeMenu() {
        val single = listOf("sd15", "sdxl", "sd3", "flux", "qwen", "video", "other")
        val multi = listOf("sd3", "flux", "qwen", "hidream", "video", "other")
        val labels = ArrayList<String>()
        val picks = ArrayList<kotlin.Pair<String, String>>()
        for (kind in listOf("single", "multi")) {
            val fams = if (kind == "single") single else multi
            val kindLabel = getString(if (kind == "single") R.string.s_345 else R.string.s_346)
            for (fam in fams) {
                labels.add(kindLabel + " · " + getString(DrawPage.familyLabelRes(fam)))
                picks.add(kotlin.Pair(kind, fam))
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(getString(R.string.s_336))
            .setItems(labels.toTypedArray()) { _, which ->
                val dpg = drawPage ?: return@setItems
                val pick = picks[which]
                dpg.pendingKind = pick.first
                dpg.pendingFamily = pick.second
                dpg.pendingNewSet = true
                pendingSlotKey = null
                pickDrawModelFile()
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    /** 给当前模型集的某个槽位挑一个文件 */
    fun pickDrawSlot(key: String) {
        pendingSlotKey = key
        pickDrawModelFile()
    }

    /** 当前模型集的槽位列表（多文件模型在这里逐个补文件） */
    fun refreshDrawSlots() {
        val box = drawSlotsContainer ?: return
        val hdr = drawSlotsHeader ?: return
        box.removeAllViews()
        val dpg = drawPage ?: return
        val set = dpg.activeSetOrSelected()
        if (set == null) {
            hdr.visibility = View.GONE
            box.visibility = View.GONE
            return
        }
        val spec = DrawPage.familySlots(set.kind, set.family)
        val filled = spec.count { set.files[it.first] != null }
        val miss = spec.count { it.second && set.files[it.first] == null }
        hdr.visibility = View.VISIBLE
        hdr.text = getString(R.string.s_371, filled, spec.size) + if (drawSlotsExpanded) "  \u25be" else "  \u25b8"
        hdr.setTextColor(if (miss > 0) C_WARN else C_PRIMARY)
        if (!drawSlotsExpanded) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.addView(ui.hintText(getString(R.string.s_364)), matchWrap().apply { topMargin = dp(4) })
        for (item in spec) {
            val key = item.first
            val required = item.second
            val f = set.files[key]
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(activity).apply {
                text = getString(DrawPage.slotLabelRes(key)) + if (required) " *" else ""
                textSize = 12f
                setTextColor(if (f != null) C_TEXT else C_SUBTEXT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f))
            row.addView(TextView(activity).apply {
                text = f?.name ?: getString(R.string.s_360)
                textSize = 11.5f
                maxLines = 2
                setTextColor(if (f != null) C_TEXT else C_SUBTEXT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ui.smallButton(getString(R.string.s_361)) { pickDrawSlot(key) })
            if (f != null) {
                row.addView(ui.smallButton(getString(R.string.s_362)) {
                    dpg.clearSlot(key)
                    refreshDrawSlots()
                    drawModelStatus?.text = dpg.modelSummary()
                })
            }
            box.addView(row, matchWrap().apply { topMargin = dp(6) })
        }
    }

    /** 展开 / 收起模型槽位面板（expand = null 表示切换） */
    fun toggleDrawSlots(expand: Boolean? = null) {
        drawSlotsExpanded = expand ?: !drawSlotsExpanded
        refreshDrawSlots()
    }

    /** 绘图模型：选一个文件（单文件 all-in-one，或多文件里的某个槽位） */
    fun pickDrawModelFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        activity.startActivityForResult(i, REQ_PICK_DRAW_MODEL)
    }

    /** 本地 LoRA：选一个 .safetensors 文件 */
    fun pickLoraFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        activity.startActivityForResult(i, REQ_PICK_LORA)
    }

    /** 打标模型：选一个 .onnx 文件 */
    fun pickTaggerOnnx() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        activity.startActivityForResult(i, REQ_TAGGER_ONNX)
    }

    /** 标签表：选一个 .csv 文件 */
    fun pickTaggerCsv() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        activity.startActivityForResult(i, REQ_TAGGER_CSV)
    }

    /** Models can't be loaded from a content:// URI, so copy them to a real file first. */
    fun copyModelToPrivate(uri: Uri) {
        applyBusy(true)
        setStatus(getString(R.string.s_130), C_WARN)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "models").apply { mkdirs() }
                val name = attachmentHandler.queryDisplayName(uri) ?: ("model_${System.currentTimeMillis()}.litertlm")
                val dest = File(dir, name)
                if (!dest.exists()) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException(getString(R.string.s_111))
                    val total = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
                    input.use { ins ->
                        dest.outputStream().use { outs ->
                            val buf = ByteArray(1 shl 16)
                            var copied = 0L
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                outs.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val pct = (copied * 100 / total).toInt()
                                    withContext(Dispatchers.Main) { progressBar.progress = pct }
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    modelPath = dest.absolutePath
                    modelInfoText.text = getString(R.string.v_002, (dest.name), (UiHelpers.fmtSize(dest.length())))
                    setStatus(getString(R.string.s_093), C_WARN)
                    refreshSavedModels()
                    loadChatParamsIntoUi()
                    toast(getString(R.string.v_003, (dest.name)))
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    setStatus(getString(R.string.s_072), C_ERR)
                    toast(getString(R.string.v_004, (e.message)))
                }
            } finally {
                withContext(Dispatchers.Main) { progressBar.visibility = View.GONE; applyBusy(false) }
            }
        }
    }

    /** Scan filesDir/models and render a tappable list of already-copied models. */
    fun refreshSavedModels() {
        savedContainer.removeAllViews()
        val dir = File(filesDir, "models")
        // El downloader guarda en models/gguf y models/litertlm (subcarpetas),
        // así que hay que escanear un nivel más o los modelos descargados no
        // aparecen en la lista.
        val files = listOf(dir, File(dir, "gguf"), File(dir, "litertlm"))
            .flatMap { d ->
                d.listFiles { f ->
                    f.isFile && (
                        f.name.endsWith(".litertlm", ignoreCase = true) ||
                            f.name.endsWith(".gguf", ignoreCase = true)
                        )
                }?.toList() ?: emptyList()
            }
            .sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            savedContainer.visibility = View.GONE
            return
        }
        savedContainer.visibility = View.VISIBLE
        savedContainer.addView(TextView(activity).apply {
            text = getString(R.string.s_089)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }, matchWrap())

        for (f in files) {
            val sel = f.absolutePath == modelPath
            val isGguf = f.name.endsWith(".gguf", ignoreCase = true)
            // .gguf 可能是语言模型也可能是画图模型，读头部元数据判断
            val kind = if (isGguf) ggufKindCache.getOrPut(f.absolutePath) { GgufProbe.probe(f) }
            else null
            val isImageModel = kind == GgufProbe.Kind.IMAGE
            // Descarga interrumpida: el archivo existe pero no llega al 90% del
            // tamaño del catálogo. Se marca y no se deja seleccionar, porque
            // cargarlo solo produce un error opaco del motor.
            val expected = ModelDownloader.expectedSizeForFileName(f.name)
            val incomplete = ModelDownloader.isIncomplete(f)
            val pct = if (incomplete && expected != null)
                (f.length() * 100 / expected).toInt().coerceIn(0, 99) else 100
            val tag = when {
                incomplete -> "incompleto $pct%"
                isImageModel -> getString(R.string.s_151)
                isGguf -> "GGUF"
                else -> "LiteRT"
            }
            val chip = TextView(activity).apply {
                text = "▶ ${f.name}　[$tag] ${UiHelpers.fmtSize(f.length())}"
                textSize = 12.5f
                setTextColor(
                    if (incomplete) C_ERR_TEXT
                    else if (isImageModel) C_IDLE
                    else if (sel) C_PRIMARY
                    else C_TEXT
                )
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(if (sel) C_PRIMARY_SOFT else C_CHIP_BG, 10,
                    strokeDp = if (sel) 1 else 0, strokeColor = C_PRIMARY)
                isClickable = true
            }
            chip.setOnClickListener {
                when {
                    incomplete -> toast(
                        "Descarga incompleta ($pct%). Bórrala (mantén pulsado) y descárgala de nuevo."
                    )
                    isImageModel -> toast(getString(R.string.s_188))
                    else -> selectSavedModel(f)
                }
            }
            chip.setOnLongClickListener { confirmDeleteModel(f); true }
            savedContainer.addView(chip, matchWrap().apply { topMargin = dp(4) })
        }
    }

    /** 点击某个模型集 → 只标记为选用，不加载（与对话模型一致，需再点「加载绘图模型」）。 */
    fun selectDrawSet(s: ModelSet) {
        drawPage?.selectSet(s)
        drawSetId = s.id
        drawModelStatus?.text = drawPage?.modelSummary()
        refreshDrawToggle()
        refreshDrawModels()
    }

    /** 刷新模型页的 LoRA 状态与列表 */
    fun refreshLoraUi() {
        val dpg = drawPage
        loraStatusTv?.text = dpg?.loraSummary() ?: getString(R.string.s_117)
        val box = loraBox ?: return
        box.removeAllViews()
        val all = dpg?.listLoras().orEmpty()
        if (all.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.addView(listHeader(getString(R.string.s_092)))
        val active = dpg?.currentLoraName()
        for (f in all) {
            box.addView(
                selectableChip(f, f.name == active, onLongClick = { confirmDeleteLora(f) }) {
                    dpg?.selectLora(f); refreshLoraUi()
                },
                matchWrap().apply { topMargin = dp(4) }
            )
        }
    }

    /** 选下载源：官方 / hf-mirror 镜像 */
    fun pickLoraSource() {
        AlertDialog.Builder(activity)
            .setTitle(getString(R.string.s_041))
            .setItems(arrayOf(getString(R.string.s_028), getString(R.string.s_029))) { _, which ->
                startLoraDownload(useMirror = which == 0)
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    /** Fuente para el TAESD: directa o mirror. */
    fun pickTaesdSource() {
        AlertDialog.Builder(activity)
            .setTitle(getString(R.string.s_548))
            .setItems(arrayOf(getString(R.string.s_028), getString(R.string.s_029))) { _, which ->
                startTaesdDownload(useMirror = which == 0)
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    fun startTaesdDownload(useMirror: Boolean) {
        val dpg = drawPage ?: return
        val src = if (useMirror) "hf-mirror.com" else "huggingface.co"
        setStatus(getString(R.string.s_549), C_WARN)
        scope.launch {
            val err = dpg.downloadTaesd(useMirror) { done, total ->
                val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                activity.runOnUiThread {
                    setStatus(
                        if (pct >= 0) getString(R.string.v_008, src, pct, UiHelpers.fmtSize(done), UiHelpers.fmtSize(total))
                        else getString(R.string.v_009, src, UiHelpers.fmtSize(done)),
                        C_WARN
                    )
                }
            }
            activity.runOnUiThread {
                if (err == null) {
                    // Ofrecer asignarlo al slot "taesd" del set activo de una vez:
                    // si no, el usuario tiene que buscar el panel de slots a mano.
                    val assigned = dpg.assignTaesdToActiveSet()
                    if (assigned) {
                        toast(getString(R.string.s_552))
                        setStatus(getString(R.string.s_552), C_OK)
                        refreshDrawSlots()
                        drawModelStatus?.text = dpg.modelSummary()
                    } else {
                        toast(getString(R.string.s_550))
                        setStatus(getString(R.string.s_550), C_OK)
                    }
                } else {
                    toast(err)
                    setStatus(err, C_ERR)
                }
            }
        }
    }

    fun startLoraDownload(useMirror: Boolean) {
        val dpg = drawPage ?: return
        val src = if (useMirror) "hf-mirror.com" else "huggingface.co"
        loraStatusTv?.text = getString(R.string.v_007, (src))
        setStatus(getString(R.string.s_018), C_WARN)
        var lastPct = -2
        scope.launch {
            val err = dpg.downloadLora(useMirror) { done, total ->
                val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                if (pct != lastPct) {
                    lastPct = pct
                    activity.runOnUiThread {
                        loraStatusTv?.text = if (pct >= 0)
                            getString(R.string.v_008, (src), (pct), (UiHelpers.fmtSize(done)), (UiHelpers.fmtSize(total)))
                        else
                            getString(R.string.v_009, (src), (UiHelpers.fmtSize(done)))
                    }
                }
            }
            activity.runOnUiThread {
                if (err == null) {
                    toast(getString(R.string.s_020))
                    setStatus(getString(R.string.s_023), C_OK)
                } else {
                    toast(err)
                    setStatus(getString(R.string.s_019), C_ERR)
                }
                refreshLoraUi()
            }
        }
    }

    /** 删除单个 LoRA：target 为空时删当前选中的那个（不再一次清空全部）。 */
    fun confirmDeleteLora(target: File? = null) {
        val dpg = drawPage ?: return
        val f = target ?: dpg.activeLora()
        if (f == null) {
            toast(getString(R.string.s_135))
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(getString(R.string.s_052))
            .setMessage(f.name + getString(R.string.s_026))
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                val ok = dpg.deleteLora(f)
                if (ok) toast(getString(R.string.v_010, 1)) else toast(getString(R.string.s_158))
                refreshLoraUi()
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    /** 长按删除一个模型集（连同它的全部文件）。 */
    fun confirmDeleteDrawSet(s: ModelSet) {
        val dpg = drawPage ?: return
        if (dpg.isReady() && dpg.activeSetId() == s.id) {
            toast(getString(R.string.s_172))
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(getString(R.string.s_056))
            .setMessage(getString(R.string.s_366, s.name, s.slotCount()))
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                val ok = dpg.deleteSet(s)
                if (drawSetId == s.id) drawSetId = null
                toast(if (ok) getString(R.string.v_012, s.name) else getString(R.string.s_053))
                drawModelStatus?.text = dpg.modelSummary()
                refreshDrawModels()
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    fun selectSavedModel(f: File) {
        if (busy) {
            toast(getString(R.string.s_102))
            return
        }
        if (busy) {
            toast(getString(R.string.s_102))
            return
        }
        modelPath = f.absolutePath
        modelInfoText.text = getString(R.string.v_013, (f.name), (UiHelpers.fmtSize(f.length())))
        setStatus(getString(R.string.s_096), C_WARN)
        refreshSavedModels()
        // 参数是按模型分别保存的，切换模型时把该模型那套回填到输入框
        loadChatParamsIntoUi()
    }

    fun confirmDeleteModel(f: File) {
        if (f.absolutePath == modelPath && host.hasModel) {
            toast(getString(R.string.s_172))
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(getString(R.string.s_055))
            .setMessage(getString(R.string.v_014, (f.name), (UiHelpers.fmtSize(f.length()))))
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                f.delete()
                if (modelPath == f.absolutePath) {
                    modelPath = null
                    modelInfoText.text = getString(R.string.s_120)
                }
                refreshSavedModels()
                loadChatParamsIntoUi()
                toast(getString(R.string.s_086))
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    /** 刷新模型页的打标（Tagger）状态：状态行 + 可选列表（模型 / 标签表） */
    fun refreshTaggerUi() {
        val dpg = drawPage
        taggerStatusTv?.text = dpg?.taggerSummary() ?: getString(R.string.s_245)
        dpg?.refreshStatus()
        refreshTaggerToggle()
        val box = taggerBox ?: return
        box.removeAllViews()
        val models = dpg?.listTaggerModels().orEmpty()
        val csvs = dpg?.listTaggerCsvs().orEmpty()
        if (models.isEmpty() && csvs.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val curM = dpg?.currentTaggerModelName()
        val curC = dpg?.currentTaggerCsvName()
        if (models.isNotEmpty()) {
            box.addView(listHeader(getString(R.string.s_252)))
            for (f in models) {
                box.addView(
                    selectableChip(f, f.name == curM) { dpg?.selectTaggerModel(f); refreshTaggerUi() },
                    matchWrap().apply { topMargin = dp(4) }
                )
            }
        }
        if (csvs.isNotEmpty()) {
            box.addView(listHeader(getString(R.string.s_253)))
            for (f in csvs) {
                box.addView(
                    selectableChip(f, f.name == curC) { dpg?.selectTaggerCsv(f); refreshTaggerUi() },
                    matchWrap().apply { topMargin = dp(4) }
                )
            }
        }
    }

    /** 按当前是否已加载切换按钮文案与配色（与「对话/绘图」一致：未加载=蓝底，已加载=浅底） */
    fun refreshTaggerToggle() {
        val b = taggerLoadBtn ?: return
        if (drawPage?.taggerLoaded() == true) {
            b.text = getString(R.string.s_255)
            b.background = rounded(C_SOFT_BTN2, 12,
                strokeDp = 1, strokeColor = C_BORDER)
            b.setTextColor(C_TEXT)
        } else {
            b.text = getString(R.string.s_254)
            b.background = rounded(C_PRIMARY, 12)
            b.setTextColor(Color.WHITE)
        }
    }

    /** 加载 / 卸载打标模型（多个打标模型之间切换用） */
    fun onTaggerToggleClick() = taggerUi.onTaggerToggleClick()

    /** 列表小标题 */
    fun listHeader(t: String) = TextView(activity).apply {
        text = t
        textSize = 12f
        setTextColor(C_SUBTEXT)
        setPadding(0, dp(6), 0, 0)
    }

    /** 可点选的条目（高亮当前选中）；onLongClick 可选（如长按删除这一项） */
    fun selectableChip(
        f: File,
        selected: Boolean,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ) = TextView(activity).apply {
        text = "▶ ${f.name}　${UiHelpers.fmtSize(f.length())}"
        textSize = 12.5f
        setTextColor(if (selected) C_PRIMARY else C_TEXT)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(if (selected) C_PRIMARY_SOFT else C_CHIP_BG, 10,
            strokeDp = if (selected) 1 else 0, strokeColor = C_PRIMARY)
        isClickable = true
        setOnClickListener { onClick() }
        if (onLongClick != null) {
            isLongClickable = true
            setOnLongClickListener { onLongClick(); true }
        }
    }


    /** Escanear etiquetas en imágenes con TaggerEngine */
    fun runTaggerOnImages(names: List<String>, req: TagReq): String {
        val rgbOrder = drawPage?.taggerRgbOrder() == true
        val sb = StringBuilder()
        for ((i, n) in names.withIndex()) {
            val bmp = readChatImage(n) ?: continue
            val tags = TaggerEngine.run(bmp, req.threshold, req.topK, rgbOrder = rgbOrder)
            if (tags.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append("\n")
            if (names.size > 1) sb.append("#${i + 1} ")
            sb.append(tags.joinToString(", ") { it.first })
        }
        return sb.toString()
    }
}
