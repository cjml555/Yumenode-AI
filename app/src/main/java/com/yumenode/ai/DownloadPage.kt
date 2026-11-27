// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.WorkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pestaña «Descargar modelos libres».
 *
 * Extraída de MainActivity: construye la lista del catálogo, la barra de
 * progreso por modelo y delega la descarga en [ModelDownloader]. Los estilos
 * (card, título, hint) se inyectan como lambdas para no arrastrar toda la
 * Activity a este archivo.
 */
class DownloadPage(
    private val ctx: Activity,
    private val makeCard: () -> LinearLayout,
    private val makePageTitle: (String) -> TextView,
    private val makeHint: (String) -> TextView,
    /** Se invoca al terminar una descarga para refrescar la lista de modelos. */
    private val onModelsChanged: () -> Unit,
    /** Paleta activa (los colores cambian con el tema). */
    private val pal: YumenodeTheme,
) : ThemeAliases {

    override val palette: YumenodeTheme get() = pal

    private var scrollView: ScrollView? = null
    /** Job que sigue el estado de la descarga en WorkManager (se cancela al reconstruir). */
    private var downloadJob: Job? = null
    /** Scope de los observadores de reenganche (se cancela al reconstruir). */
    private val reattach = CoroutineScope(Dispatchers.Main + Job())
    /** Botón de descarga por modelo, para marcar los que ya están bajando. */
    private val buttonsByKey = HashMap<String, TextView>()
    private var container: LinearLayout? = null

    /** Pestaña activa del downloader. */
    private var selectedKind = ModelDownloader.ModelKind.CHAT
    private val kinds = arrayOf(
        ModelDownloader.ModelKind.CHAT,
        ModelDownloader.ModelKind.MMPROJ,
        ModelDownloader.ModelKind.DRAW,
        ModelDownloader.ModelKind.TAGGER,
        ModelDownloader.ModelKind.LORA,
    )

    fun build(): View = with(ctx) {
        downloadJob?.cancel()
        buttonsByKey.clear()
        val sv = ScrollView(ctx).apply {
            clipToPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val card = makeCard()
        card.addView(makePageTitle(ctx.getString(R.string.s_454)))
        card.addView(makeHint(ctx.getString(R.string.s_455)), matchWrap().apply { topMargin = dp(6) })

        val kindSwitch = SegmentedSwitch(
            ctx,
            arrayOf(
                ctx.getString(R.string.s_074),
                ctx.getString(R.string.s_510),
                ctx.getString(R.string.s_511),
                ctx.getString(R.string.s_221),
                ctx.getString(R.string.s_522),
            ),
            pal,
            kinds.indexOf(selectedKind),
        )
        kindSwitch.onChanged = { i ->
            selectedKind = kinds[i]
            container?.let { renderList(it) }
        }
        card.addView(kindSwitch, matchWrap().apply { topMargin = dp(10) })

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(list, matchWrap().apply { topMargin = dp(8) })

        sv.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        scrollView = sv
        container = list
        refresh()
        sv
    }

    /** Repuebla la lista (al volver a la pestaña o al terminar una descarga). */
    fun refresh() {
        container?.let { renderList(it) }
    }

    private fun renderList(container: LinearLayout) = with(ctx) {

        container.removeAllViews()
        val models = ModelDownloader.getAvailableModels().filter { it.kind == selectedKind }
        for (info in models) {
            val already = ModelDownloader.isModelDownloaded(ctx, info.name)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(C_CARD, 8)
            }
            val infoLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleTv = TextView(ctx).apply {
                text = info.name
                textSize = 14f
                setTextColor(C_TEXT)
                typeface = Typeface.DEFAULT_BOLD
            }
            val descTv = TextView(ctx).apply {
                text = "${info.description} · ${Formatters.size(info.sizeBytes)}"
                textSize = 12f
                setTextColor(C_SUBTEXT)
            }
            val formatBadge = TextView(ctx).apply {
                text = when (info.kind) {
                    ModelDownloader.ModelKind.CHAT ->
                        if (info.supportsThinking) "🧠 ${info.format.displayName}" else info.format.displayName
                    ModelDownloader.ModelKind.MMPROJ -> "mmproj"
                    ModelDownloader.ModelKind.DRAW -> "Stable Diffusion"
                    ModelDownloader.ModelKind.TAGGER ->
                        info.remoteName?.substringAfterLast('.')?.uppercase() ?: "ONNX"
                    ModelDownloader.ModelKind.LORA -> "LoRA"
                }
                textSize = 11f
                setTextColor(
                    when (info.kind) {
                        ModelDownloader.ModelKind.CHAT ->
                            if (info.format == ModelDownloader.ModelFormat.GGUF) 0xFF4CAF50.toInt() else 0xFF2196F3.toInt()
                        ModelDownloader.ModelKind.MMPROJ -> 0xFF9C27B0.toInt()
                        ModelDownloader.ModelKind.DRAW -> 0xFFFF7A00.toInt()
                        ModelDownloader.ModelKind.TAGGER -> 0xFF00BCD4.toInt()
                        ModelDownloader.ModelKind.LORA -> 0xFFE91E63.toInt()
                    }
                )
                background = rounded(Color.WHITE, 4)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                gravity = Gravity.CENTER
            }
            infoLayout.addView(titleTv)
            infoLayout.addView(descTv)
            infoLayout.addView(formatBadge, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
            row.addView(infoLayout, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // Cada modelo vive en su propio contenedor vertical: así la barra de
            // progreso queda justo debajo del modelo pulsado (antes se añadía al
            // final de la lista y quedaba fuera de pantalla).
            val itemWrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

            val actionBtn = if (already) {
                TextView(ctx).apply {
                    text = getString(R.string.s_462)
                    textSize = 12f
                    setTextColor(0xFF4CAF50.toInt())
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                }
            } else {
                TextView(ctx).apply {
                    val btn = this
                    text = getString(R.string.s_463)
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = rounded(C_PRIMARY, 8)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    setOnClickListener {
                        // La notificación de progreso necesita POST_NOTIFICATIONS en 13+
                        if (Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            requestPermissions(
                                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
                            )
                        }
                        val btnLabel = btn.text
                        btn.isEnabled = false
                        btn.alpha = 0.6f
                        btn.text = ctx.getString(R.string.s_473)

                        val pb = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                            max = 100
                            progress = 0
                            setIndeterminate(false)
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                        }
                        val pbText = TextView(ctx).apply {
                            text = "0%"
                            textSize = 11f
                            setTextColor(C_SUBTEXT)
                            setPadding(dp(8), dp(2), dp(8), dp(2))
                        }
                        val pbRow = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(pb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                            addView(pbText, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        }
                        if (pbRow.parent == null) {
                            itemWrap.addView(pbRow, matchWrap().apply { topMargin = dp(6) })
                        }
                        pbRow.visibility = View.VISIBLE
                        // La barra puede quedar bajo el borde: desplazar hasta ella
                        itemWrap.post {
                            scrollView?.smoothScrollTo(0, itemWrap.bottom)
                        }

                        // La descarga va a WorkManager: sobrevive a cerrar la app
                        // o a que Android mate el proceso (se reanuda desde el .parts).
                        toast("Descargando ${info.name}...")
                        ModelDownloads.enqueue(ctx, info.name, info.name)
                        downloadJob?.cancel()
                        // Progreso y resultado llegan por flujos EN MEMORIA: los
                        // Flow de WorkManager consultan su base de datos y con la
                        // descarga activa dejaban la UI sin hilo principal (ANR).
                        downloadJob = CoroutineScope(Dispatchers.Main).launch {
                            launch {
                                ModelDownloads.progress.collect { map ->
                                    val pct = map[info.name] ?: return@collect
                                    pb.isIndeterminate = false
                                    pb.progress = pct
                                    pbText.text = "$pct%"
                                }
                            }
                            ModelDownloads.finished.collect { map ->
                                val st = map[info.name] ?: return@collect
                                pbRow.visibility = View.GONE
                                btn.isEnabled = true
                                btn.alpha = 1f
                                btn.text = btnLabel
                                when {
                                    st == "ok" -> {
                                        toast("Descarga completada: ${info.name}")
                                        refresh()
                                        // El modelo recién bajado debe aparecer ya
                                        // seleccionable en la pestaña Models/API.
                                        onModelsChanged()
                                    }
                                    st == "cancelada" -> toast("Descarga cancelada")
                                    else -> toast("Error: ${st.removePrefix("error:")}")
                                }
                                downloadJob?.cancel()
                            }
                        }
                    }
                }
            }
            row.addView(actionBtn, wrapWrap().apply { leftMargin = dp(8) })
            // Se guarda para marcar las descargas activas en una sola pasada.
            buttonsByKey[info.name] = actionBtn
            itemWrap.addView(row, matchWrap())
            container.addView(itemWrap, matchWrap().apply { topMargin = dp(6) })
        }
        if (models.isEmpty()) {
            container.addView(makeHint(getString(R.string.s_513)), matchWrap().apply { topMargin = dp(16) })
        }

        // Descargas ya en curso: una sola consulta a WorkManager, fuera del hilo
        // principal (consultarlo por tarjeta congelaba la UI).
        reattach.launch {
            val active = withContext(Dispatchers.IO) { ModelDownloads.activeKeys(ctx) }
            for (key in active) {
                buttonsByKey[key]?.let { b ->
                    b.isEnabled = false
                    b.alpha = 0.6f
                    b.text = "Descargando…"
                }
            }
        }
        }
}
