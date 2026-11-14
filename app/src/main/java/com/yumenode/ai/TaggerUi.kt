// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.yumenode.ai.draw.DrawPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * TaggerUi — encapsulates UI logic for the "Tagger" (tagging) model.
 *
 * Extracted from MainActivity: refreshTaggerUi, refreshTaggerToggle, onTaggerToggleClick.
 */
class TaggerUi(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pal: YumenodeTheme,
    private val getDrawPage: () -> DrawPage?,
    private val getTaggerStatusTv: () -> TextView?,
    private val getTaggerBox: () -> LinearLayout?,
    private val getTaggerLoadBtn: () -> TextView?,
    private val setDrawBusy: (Boolean) -> Unit,
    private val setMainBusy: (Boolean) -> Unit,
    private val showDrawProgress: (Boolean) -> Unit,
    private val setStatus: (String, Int) -> Unit,
    private val toast: (String) -> Unit,
) {

    /** Refresh the "tagging model" section: status row + selectable list (model / tag table) */
    fun refreshTaggerUi() {
        val drawPage = getDrawPage() ?: return
        getTaggerStatusTv()?.text = drawPage.taggerSummary() ?: context.getString(R.string.s_245)
        drawPage.refreshStatus()
        refreshTaggerToggle()
        val box = getTaggerBox() ?: return
        box.removeAllViews()
        val models = drawPage.listTaggerModels().orEmpty()
        val csvs = drawPage.listTaggerCsvs().orEmpty()
        if (models.isEmpty() && csvs.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val curM = drawPage.currentTaggerModelName()
        val curC = drawPage.currentTaggerCsvName()
        if (models.isNotEmpty()) {
            box.addView(listHeader(context.getString(R.string.s_252)))
            for (f in models) {
                box.addView(
                    selectableChip(f, f.name == curM) { drawPage.selectTaggerModel(f); refreshTaggerUi() },
                    context.matchWrap().apply { topMargin = context.dp(4) }
                )
            }
        }
        if (csvs.isNotEmpty()) {
            box.addView(listHeader(context.getString(R.string.s_253)))
            for (f in csvs) {
                box.addView(
                    selectableChip(f, f.name == curC) { drawPage.selectTaggerCsv(f); refreshTaggerUi() },
                    context.matchWrap().apply { topMargin = context.dp(4) }
                )
            }
        }
    }

    /** Toggle button text and color by current load state (same as chat/draw: not loaded = blue bg, loaded = light bg) */
    private fun refreshTaggerToggle() {
        val b = getTaggerLoadBtn() ?: return
        if (getDrawPage()?.taggerLoaded() == true) {
            b.text = context.getString(R.string.s_255)
            b.background = context.rounded(pal.softBtn2, 12, strokeDp = 1, strokeColor = pal.border)
            b.setTextColor(pal.text)
        } else {
            b.text = context.getString(R.string.s_254)
            b.background = context.rounded(pal.primary, 12)
            b.setTextColor(Color.WHITE)
        }
    }

    /** Load / unload the tagging model (used to switch between multiple tagger models) */
    fun onTaggerToggleClick() {
        val drawPage = getDrawPage() ?: return
        if (drawPage.taggerLoaded()) {
            drawPage.unloadTagger()
            refreshTaggerUi()
            return
        }
        setMainBusy(true)
        setDrawBusy(true)
        showDrawProgress(true)
        setStatus(context.getString(R.string.s_131), pal.warn)
        scope.launch {
            val err = drawPage.loadTaggerFromUi { stage ->
                getTaggerStatusTv()?.text = stage
            }
            setMainBusy(false)
            setDrawBusy(false)
            showDrawProgress(false)
            if (err == null) {
                setStatus(context.getString(R.string.s_265), pal.ok)
                context.toast(context.getString(R.string.s_266))
            } else {
                setStatus(context.getString(R.string.s_158), pal.err)
                toast(err)
            }
            refreshTaggerUi()
        }
    }

    // ------------------------- helpers -------------------------
    private fun listHeader(t: String): TextView = TextView(context).apply {
        text = t
        textSize = 12f
        setTextColor(pal.subText)
        setPadding(0, context.dp(6), 0, 0)
    }

    private fun selectableChip(
        file: File,
        selected: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = "▶ ${file.name} ${UiHelpers.fmtSize(file.length())}"
        textSize = 12.5f
        setTextColor(if (selected) pal.primary else pal.text)
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        background = if (selected) {
            context.rounded(pal.primary, 12)
        } else {
            context.rounded(pal.chipBg, 10, strokeDp = 1, strokeColor = pal.border)
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

}