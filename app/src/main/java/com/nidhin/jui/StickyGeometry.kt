package com.nidhin.jui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LineHeightSpan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Forces every line it covers to exactly `height` pixels, with the type centred
 * in it — what the iPhone app gets from a fixed line height plus a baseline
 * offset. Android's own leading puts all the extra below the glyphs.
 */
class CentredLineSpan(private val height: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        val natural = fm.descent - fm.ascent
        val extra = height - natural
        val above = extra / 2
        fm.ascent -= above
        fm.descent = fm.ascent + height
        fm.top = fm.ascent
        fm.bottom = fm.descent
    }
}

// MARK: - Sticky text
//
// The sticky card's writing: plain text in the house family, set a step below
// the page at 140% leading. One place measures and lays it out — the card's
// editor, the card's height, and the picture of the card the crumpled sheet is
// textured with all go through here, so the hand-over can't be seen.
object StickyText {
    const val TEXT_SIZE = 14f
    const val LEADING = 1.4f // line height as a multiple of the type size
    val LINE: Float = Math.round(TEXT_SIZE * LEADING).toFloat() // points
    const val INSET_TOP = 4f
    const val INSET_BOTTOM = 4f
    /** The fade at the foot of a sticky too long for the screen. */
    const val FADE_BAND = 20f

    fun paint(density: Float = Style.density): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Style.typeface()
        textSize = TEXT_SIZE * density
        color = Style.ink
    }

    fun lineSpan(density: Float = Style.density) = CentredLineSpan((LINE * density).roundToInt())

    /** The text laid out at `widthPx`, every line exactly `LINE` tall. */
    fun layout(text: CharSequence, widthPx: Int, density: Float = Style.density): StaticLayout {
        val spanned = SpannableString(text)
        spanned.setSpan(lineSpan(density), 0, spanned.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint(density), max(widthPx, 1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setUseLineSpacingFromFallbacks(false)
            .build()
    }

    /**
     * How tall `text` sets at `width` points, insets included — what the card
     * measures to size itself. Counted in lines rather than read off the
     * layout's height: the empty line after a trailing newline is laid out at
     * the font's natural height, and it is still a whole line to write on.
     */
    fun height(text: String, width: Float): Float {
        val lines = layout(text, (width * Style.density).roundToInt()).lineCount.coerceAtLeast(1)
        return ceil(lines * LINE) + INSET_TOP + INSET_BOTTOM
    }
}

// MARK: - The sticky card's geometry
//
// An open sticky is the Mac's margin stamp, brought to the middle of the page.
// Its place is worked out here, not in the card's layout, so the crumpled sheet
// can open out into exactly its place before the card is there — keep them one.
object StickyCard {
    const val SIDE = 280f
    const val TEXT_TOP = 18f
    const val TEXT_SIDES = 20f

    // The flourish, as fractions of the stamp's *width*, kept to the Mac's
    // right-hand overhang.
    const val FLOURISH_WIDTH = 0.30f
    const val FLOURISH_RIGHT = 1.0315f // where its right edge lands
    const val FLOURISH_OVERHANG = 0.0575f // past the foot
    // Clear paper between the last line and the top of the flourish.
    const val FLOURISH_GAP = 18f

    val flourishHeight: Float
        get() = SIDE * FLOURISH_WIDTH / max(Flourish.aspect(FlourishSet.STICKY), 0.01f)

    // The part of the flourish that sits on the stamp; the rest runs off it.
    val flourishOnStamp: Float get() = flourishHeight - SIDE * FLOURISH_OVERHANG

    val textWidth: Float get() = SIDE - TEXT_SIDES * 2

    fun textHeight(cardHeight: Float): Float = cardHeight - TEXT_TOP - FLOURISH_GAP - flourishOnStamp

    // Clear air between the stamp and the status bar (and the keyboard, and the
    // navigation bar).
    private const val MARGIN = 20f
    // Opened to read, a stamp that fits is dead centre on the screen, and a
    // tall one keeps well clear of the top. While it is written in it sits a
    // little below centre in the room the keyboard leaves.
    private const val READING_DROP = 0f
    private const val EDITING_DROP = 24f
    private const val READING_TOP_CLEARANCE = 60f
    const val BUTTON_GAP = 14f
    const val BUTTON_SIDE = 44f
    private const val BUTTONS_BAND = BUTTON_GAP + BUTTON_SIDE

    /**
     * Where the stamp sits for `text`, in points within `areaW × areaH` (the
     * whole window above the keyboard); `safeTop`/`safeBottom` are the bars.
     */
    fun stampFrame(
        text: String, areaW: Float, areaH: Float, safeTop: Float, safeBottom: Float, editing: Boolean,
    ): RectF {
        val minTop = safeTop + MARGIN + if (editing) 0f else READING_TOP_CLEARANCE
        val maxBottom = areaH - safeBottom - MARGIN
        val wanted = TEXT_TOP + StickyText.height(text, textWidth) + FLOURISH_GAP + flourishOnStamp
        val tallest = maxBottom - minTop - BUTTONS_BAND
        val height = min(max(SIDE, wanted), max(SIDE * 0.6f, tallest))
        val centred = (areaH - height) / 2 + if (editing) EDITING_DROP else READING_DROP
        val top = min(max(centred, minTop), maxBottom - BUTTONS_BAND - height)
        val left = (areaW - SIDE) / 2
        return RectF(left, top, left + SIDE, top + height)
    }
}

/**
 * The postage-stamp outline shared by the ball and the card: the rectangle with
 * semicircular perforations, evenly distributed for whatever size it is. Filled
 * even-odd, clipped to `rect`, so the perforations are punched out.
 */
object StampOutline {
    fun path(rect: RectF, radius: Float = 4f, pitch: Float = 15f, cornerGap: Float = 6f): Path {
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(rect, Path.Direction.CW)
        fun punch(length: Float, point: (Float) -> Pair<Float, Float>) {
            val usable = length - cornerGap * 2
            if (usable < pitch) return
            val count = max(1, (usable / pitch).roundToInt())
            val step = usable / count
            for (i in 0 until count) {
                val (x, y) = point(cornerGap + (i + 0.5f) * step)
                path.addCircle(x, y, radius, Path.Direction.CW)
            }
        }
        punch(rect.width()) { rect.left + it to rect.top }
        punch(rect.width()) { rect.left + it to rect.bottom }
        punch(rect.height()) { rect.left to rect.top + it }
        punch(rect.height()) { rect.right to rect.top + it }
        return path
    }
}
