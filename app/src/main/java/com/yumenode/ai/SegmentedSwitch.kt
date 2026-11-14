// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Multi-segment slider (drag or tap both work).
 *
 * The track divides evenly into [labels].size segments; the thumb snaps to the nearest one on release and calls onChanged.
 * Appearance (track / thumb / text color) all come from [YumenodeTheme], auto-following light/dark.
 */
class SegmentedSwitch @JvmOverloads constructor(
    context: Context,
    private val labels: Array<String> = arrayOf("", ""),
    private val pal: YumenodeTheme = YumenodeTheme.light(),
    private var index: Int = 0,
    attr: AttributeSet? = null,
    /** Segment change callback (assignable after construction); fires only on internal segment switches */
    var onChanged: (Int) -> Unit = {},
) : View(context, attr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    private val density = resources.displayMetrics.density
    private fun dpV(v: Float) = UiScale.dpToPxFloat(v, density)

    private val trackHeight = dpV(42f)
    private val innerPad = dpV(3.5f)
    private val textSize = dpV(12.5f)

    /** Current continuous slider position (0 .. n-1) */
    private var pos = index.toFloat()
    private var downRawX = 0f
    private var downPos = 0f
    private var downRawY = 0f
    private var draggingHoriz = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var anim: ValueAnimator? = null

    /**
     * Pending segment to fire. Notified only after the snap animation finishes -- external code (e.g. theme change)
     * rebuilds the whole page, so firing early would cut the animation short and look like a dropped frame.
     */
    private var pendingNotify: Int? = null

    init {
        isClickable = true
        contentDescription = labels.joinToString(" / ")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (trackHeight + dpV(2f)).toInt())
    }

    private fun stepWidth(): Float = (width - innerPad * 2f) / labels.size.toFloat()

    /** Selected-segment text color: picks dark/light based on the thumb's brightness, avoiding invisible same-color combos */
    private fun onThumbText(): Int {
        val c = pal.switchThumb
        val lum = (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) +
            0.114 * android.graphics.Color.blue(c)) / 255.0
        return if (lum > 0.55) 0xFF07101F.toInt() else 0xFFFFFFFF.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = trackHeight
        val r = h / 2f
        val n = labels.size

        // track
        trackPaint.color = pal.switchTrack
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        // thumb
        val sw = stepWidth()
        val thumbL = innerPad + pos * sw
        thumbPaint.color = pal.switchThumb
        rect.set(thumbL + innerPad, innerPad, thumbL + sw - innerPad, h - innerPad)
        val tr = (h - innerPad * 2f) / 2f
        canvas.drawRoundRect(rect, tr, tr, thumbPaint)
        strokePaint.color = pal.border
        strokePaint.strokeWidth = dpV(1f)
        canvas.drawRoundRect(rect, tr, tr, strokePaint)

        // segment labels: highlight whichever is nearest to the thumb
        val near = pos.roundToInt().coerceIn(0, n - 1)
        textPaint.textSize = textSize
        val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        for (i in 0 until n) {
            val selected = i == near
            textPaint.color = if (selected) onThumbText() else pal.subText
            textPaint.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val cx = innerPad + i * sw + sw / 2f
            canvas.drawText(labels[i], cx, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (labels.size < 2) return false
        val sw = stepWidth()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                // Drag started before the previous snap finished: flush its callback first so that choice is not swallowed
                flushNotify()
                downRawX = event.rawX
                downRawY = event.rawY
                draggingHoriz = false
                pos = ((event.x - innerPad) / sw - 0.5f).coerceIn(0f, (labels.size - 1).toFloat())
                downPos = pos
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                // Solo nos quedamos el gesto si es horizontal: un scroll vertical debe
                // seguir desplazando la pagina aunque empiece encima del switch.
                if (!draggingHoriz && kotlin.math.abs(dx) > kotlin.math.abs(dy) + touchSlop) {
                    draggingHoriz = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (draggingHoriz) {
                    pos = (downPos + dx / sw).coerceIn(0f, (labels.size - 1).toFloat())
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!draggingHoriz) performClick()
                snapTo(pos.roundToInt().coerceIn(0, labels.size - 1))
                draggingHoriz = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // La pagina se quedo el gesto (scroll vertical): no cambiar de opcion.
                parent?.requestDisallowInterceptTouchEvent(false)
                draggingHoriz = false
                snapTo(index)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun snapTo(target: Int) {
        anim?.cancel()
        if (target != index) {
            index = target
            pendingNotify = target
        }
        anim = ValueAnimator.ofFloat(pos, target.toFloat()).apply {
            duration = 140L
            addUpdateListener {
                pos = it.animatedValue as Float
                invalidate()
            }
            // Fire the callback after the animation completes: let the thumb settle first, then external code does the heavy lifting
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) = flushNotify()
            })
            start()
        }
    }

    /** Flush the pending segment callback (called at animation end / next touch / silent set before) */
    private fun flushNotify() {
        val t = pendingNotify ?: return
        pendingNotify = null
        onChanged(t)
    }

    /** Set the segment directly from outside (does not fire the callback) */
    fun setIndexSilently(i: Int) {
        if (i !in labels.indices) return
        pendingNotify = null
        anim?.cancel()
        index = i
        pos = i.toFloat()
        invalidate()
    }

    fun currentIndex(): Int = index
}
