// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.Toast

/**
 * UI helpers: dp conversion, Toast, rounded background, common LayoutParams.
 *
 * All are Context extension functions so Activities / Context holders can call them directly,
 * instead of each class writing its own copy (previously MainActivity, DrawPage and AttachmentHandler each had a dp/toast/rounded).
 */

/** dp → px (truncando; ver [UiScale]) */
fun Context.dp(v: Int): Int = UiScale.dpToPx(v, resources.displayMetrics.density)

/** Show a Toast; short = true uses short duration */
fun Context.toast(msg: String, short: Boolean = false) {
    Toast.makeText(this, msg, if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
}

/** Rounded background (optional stroke). radiusDp is in dp, no manual conversion. */
fun Context.rounded(
    color: Int,
    radiusDp: Int,
    strokeDp: Int = 0,
    strokeColor: Int = 0
): GradientDrawable {
    val d = resources.displayMetrics.density
    return GradientDrawable().apply {
        cornerRadius = radiusDp * d
        setColor(color)
        if (strokeDp > 0) setStroke((strokeDp * d).toInt(), strokeColor)
    }
}

/** MATCH_PARENT × WRAP_CONTENT, optional top margin */
fun Context.matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(top) }

/** WRAP_CONTENT × WRAP_CONTENT, con margen izquierdo opcional (dp) */
fun Context.wrapWrap(leftMargin: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.WRAP_CONTENT,
    LinearLayout.LayoutParams.WRAP_CONTENT
).apply { this.leftMargin = dp(leftMargin) }
