package com.nidhin.jui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import android.widget.EditText

// MARK: - Smooth caret (MS Word 2013-style glide)
//
// Ported from the Mac's SmoothCaret, by way of the iPhone. The caret glides to
// its new position instead of jumping, stays solid while moving or typing, and
// blinks only when idle. It is drawn in the house ink — the one thing on the
// page that never stops moving has to be in palette.
//
// Android lets the system caret be replaced cleanly: its drawable is set to
// nothing (not hidden — hiding it would also take away the insertion handle
// and the magnifier), and this one is drawn in `onDraw` at `caretRect()`. The
// note editor sizes that rect off the ink box (RuledEditText); a plain editor
// (a sticky) gets the line's own ink.

/**
 * An EditText that draws the house caret. The note editor and the sticky's
 * editor both are one, so the caret behaves the same everywhere — a new
 * multi-line editor should be one too.
 */
@SuppressLint("AppCompatCustomView")
open class CaretEditText(context: Context) : EditText(context) {
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown: RectF? = null      // where the caret is drawn now
    private var from: RectF? = null       // where a glide started
    private var target: RectF? = null     // where it is going
    private var glide: ValueAnimator? = null
    private var stillSince = 0L           // when it last moved
    private var visible = false

    companion object {
        // How long a move takes, and how long the caret stays solid after one
        // before it starts to blink — both the Mac's.
        const val GLIDE_MS = 100L
        const val HOLD_BEFORE_BLINK_MS = 450L
        const val BLINK_MS = 1100L
        const val WIDTH = 2f
    }

    init {
        setTextCursorDrawable(ColorDrawable(0)) // the system caret, silenced
        applyCaretTheme()
    }

    fun applyCaretTheme() {
        caretPaint.color = Style.ink
        // Selection handles and the magnifier's frame follow the ink.
        textSelectHandle?.setTint(Style.ink)
        textSelectHandleLeft?.setTint(Style.ink)
        textSelectHandleRight?.setTint(Style.ink)
        invalidate()
    }

    /**
     * Where the caret goes, in this view's own coordinates (padding and scroll
     * included), or null when there is none to draw. Sized off the ink, so it
     * doesn't hang down into the leading.
     */
    open fun caretRect(offset: Int): RectF? {
        val layout = layout ?: return null
        val line = layout.getLineForOffset(offset)
        val x = layout.getPrimaryHorizontal(offset) + totalPaddingLeft
        val fm = paint.fontMetrics
        val baseline = layout.getLineBaseline(line) + totalPaddingTop
        return RectF(x, baseline + fm.ascent - Style.px(Style.CARET_OVERSHOOT),
            x + Style.px(WIDTH), baseline + fm.descent + Style.px(Style.CARET_OVERSHOOT))
    }

    /** The caret moved — by typing, a tap, or code. Glide to it. */
    fun caretMoved(animated: Boolean = true) {
        val wanted = if (hasFocus() && isEnabled && selectionStart == selectionEnd && selectionStart >= 0)
            caretRect(selectionEnd) else null
        if (wanted == null) {
            visible = false
            glide?.cancel()
            shown = null
            target = null
            invalidate()
            return
        }
        if (target == wanted && visible) return
        stillSince = SystemClock.uptimeMillis()
        // Glide only from somewhere the caret already was; appearing (focus,
        // a document load) is a cut. Reduced motion is a cut too.
        val start = shown
        target = wanted
        glide?.cancel()
        if (animated && visible && start != null && !prefersReducedMotion) {
            from = RectF(start)
            glide = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = GLIDE_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    val a = from ?: return@addUpdateListener
                    val b = target ?: return@addUpdateListener
                    shown = RectF(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
                        a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t)
                    invalidate()
                }
                start()
            }
        } else {
            shown = RectF(wanted)
        }
        visible = true
        invalidate()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // Called from the constructor, before this class has initialised.
        @Suppress("SENSELESS_COMPARISON")
        if (caretPaint != null) post { caretMoved() }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        post { caretMoved(animated = false) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // A new width rewraps the text under the caret: follow it without a glide.
        if (changed) post { caretMoved(animated = false) }
    }

    /** Solid while moving or typing; blinks only once it has been still. */
    private fun caretAlpha(now: Long): Float {
        val idle = now - stillSince - HOLD_BEFORE_BLINK_MS
        if (idle < 0) return 1f
        val t = (idle % BLINK_MS).toFloat() / BLINK_MS
        return when {
            t < 0.45f -> 1f
            t < 0.55f -> 1f - (t - 0.45f) / 0.10f
            t < 0.95f -> 0f
            else -> (t - 0.95f) / 0.05f
        }
    }

    /** Drawn by subclasses at the right point in their own drawing. */
    protected fun drawCaret(canvas: Canvas) {
        val rect = shown ?: return
        if (!visible || !hasFocus()) return
        val now = SystemClock.uptimeMillis()
        val alpha = caretAlpha(now)
        caretPaint.alpha = (alpha * 255).toInt()
        val r = Style.px(1f)
        canvas.drawRoundRect(rect, r, r, caretPaint)
        // Redraw only as often as the blink needs: every frame while it is
        // fading, otherwise at its next turn.
        val idle = now - stillSince - HOLD_BEFORE_BLINK_MS
        if (idle < 0) {
            postInvalidateDelayed(-idle)
        } else {
            val t = (idle % BLINK_MS).toFloat() / BLINK_MS
            when {
                t < 0.45f -> postInvalidateDelayed(((0.45f - t) * BLINK_MS).toLong())
                t < 0.55f || t >= 0.95f -> postInvalidateOnAnimation()
                else -> postInvalidateDelayed(((0.95f - t) * BLINK_MS).toLong())
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCaret(canvas)
    }
}
