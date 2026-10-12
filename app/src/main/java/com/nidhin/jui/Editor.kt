package com.nidhin.jui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - The ruled grid, as spans
//
// The pitch is the constant: every line is laid out exactly one pitch tall
// with the type centred in it, so every family lands on the same rules. A
// paragraph break is exactly one blank rule — added *above* the paragraph that
// follows prose, never below the one before, so the selection handles (which
// hang from a line's bottom) stay under the words. Bullets take consecutive
// rules: nothing is added after a bullet.
//
// Classified by the text itself (does the line before start with the bullet),
// never by attributes — the same rule the Mac and iPhone use on load.

/**
 * One span over the whole note; it decides each line's height from the text.
 * The font's own ascent and descent are measured once, here: the metrics
 * Android hands a wrapped line are the line before's, already stretched.
 */
class PitchSpan(private val pitch: Int) : LineHeightSpan {
    private val ascent: Int
    private val descent: Int

    init {
        val fm = Style.paint().fontMetricsInt
        ascent = fm.ascent
        descent = fm.descent
    }

    override fun chooseHeight(
        text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int,
        fm: android.graphics.Paint.FontMetricsInt,
    ) {
        val above = (pitch - (descent - ascent)) / 2
        fm.ascent = ascent - above
        fm.descent = fm.ascent + pitch
        fm.ascent -= gapAbove(text, start, pitch)
        fm.top = fm.ascent
        fm.bottom = fm.descent
    }

    companion object {
        /** The blank rule above a line: a pitch, when it starts a paragraph after prose. */
        fun gapAbove(text: CharSequence, lineStart: Int, pitch: Int): Int {
            if (lineStart <= 0 || lineStart > text.length || text[lineStart - 1] != '\n') return 0
            var prev = lineStart - 1
            while (prev > 0 && text[prev - 1] != '\n') prev--
            return if (startsWithBullet(text, prev)) 0 else pitch
        }

        fun startsWithBullet(text: CharSequence, at: Int): Boolean {
            val p = Style.BULLET_PREFIX
            if (at + p.length > text.length) return false
            for (i in p.indices) if (text[at + i] != p[i]) return false
            return true
        }
    }
}

/**
 * The room for a sticky's ball in the line. The note's text can't change — it
 * is the file the Mac reads too — so the room is made in layout alone: the
 * space after a sticky's words is laid out wide, as one space, the ball's slot
 * and one more space, and the ball is drawn in it.
 */
class BallSpan(val balls: Int, private val widthPx: Int) : ReplacementSpan() {
    override fun getSize(
        paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?,
    ): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return widthPx
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float,
        top: Int, y: Int, bottom: Int, paint: Paint,
    ) {} // the ball is drawn by the page, over the words

    companion object {
        /** The ball's slot in the line, a little wider than the 24pt ball. */
        const val SLOT = 30f
        const val BALL = 24f

        fun width(balls: Int): Float = Style.paint().measureText(" ") * 2 + Style.px(SLOT) * balls

        /**
         * Where a sticky's ball goes: the first whitespace at or after the end
         * of its words (trailing spaces in the anchor don't count, and a
         * half-selected word is finished first) — or the end of the text.
         */
        fun spot(sticky: Sticky, text: CharSequence): Int {
            val length = text.length
            var end = (sticky.start + sticky.length).coerceIn(0, length)
            val start = sticky.start.coerceIn(0, length)
            while (end > start && isBlank(text[end - 1])) end--
            while (end < length && !isBlank(text[end])) end++
            return end
        }

        /** A space that can be widened to hold the ball. */
        fun widens(c: Char) = c == ' ' || c == ' '

        private fun isBlank(c: Char) =
            c == ' ' || c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ' ' || c == ' '
    }
}

/** Bullet paragraphs hang: wrapped lines line up after the bullet. */
class BulletMarginSpan(indent: Int) : LeadingMarginSpan.Standard(0, indent)

// MARK: - Converting the note

object NoteText {
    /** The stored note as editable text: characters plus italic/bold spans. */
    fun toEditable(styled: StyledText): SpannableStringBuilder {
        val out = SpannableStringBuilder(styled.text)
        for (run in styled.runs) {
            val style = when {
                run.italic && run.bold -> Typeface.BOLD_ITALIC
                run.italic -> Typeface.ITALIC
                run.bold -> Typeface.BOLD
                else -> continue
            }
            if (run.end > run.start && run.end <= out.length) {
                out.setSpan(StyleSpan(style), run.start, run.end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            }
        }
        return out
    }

    /** What is stored: the characters and where they lean, nothing else. */
    fun toStyled(text: Spanned): StyledText {
        val n = text.length
        val italic = BooleanArray(n)
        val bold = BooleanArray(n)
        for (span in text.getSpans(0, n, StyleSpan::class.java)) {
            val s = text.getSpanStart(span).coerceAtLeast(0)
            val e = text.getSpanEnd(span).coerceAtMost(n)
            val i = span.style == Typeface.ITALIC || span.style == Typeface.BOLD_ITALIC
            val b = span.style == Typeface.BOLD || span.style == Typeface.BOLD_ITALIC
            for (k in s until e) {
                if (i) italic[k] = true
                if (b) bold[k] = true
            }
        }
        val runs = ArrayList<StyleRun>()
        var k = 0
        while (k < n) {
            if (!italic[k] && !bold[k]) { k++; continue }
            val start = k
            val i = italic[k]
            val b = bold[k]
            while (k < n && italic[k] == i && bold[k] == b) k++
            runs += StyleRun(start, k, i, b)
        }
        return StyledText(text.toString(), runs)
    }
}

// MARK: - The text view

/**
 * The page's editor. Laid out at its full height inside `NoteScroll`, which
 * scrolls it, so the words can run up under the status bar and fade there
 * rather than being clipped at a padding line.
 */
@SuppressLint("ViewConstructor")
class RuledEditText(context: Context, private val store: NotesStore) : CaretEditText(context) {
    private val pitch: Int get() = Style.px(Style.LINE_PITCH).roundToInt()
    private val rulePaint = Paint().apply { strokeWidth = 1f; color = Style.RULE_TONE }
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ballPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Sticky id → where its ball is drawn, in this view's coordinates. */
    private val balls = LinkedHashMap<String, RectF>()
    /** The sticky whose ball is in the air (opening, open, or going back). */
    var flyingID: String? = null
        set(value) { field = value; invalidate() }

    /** The ball in the line, rendered once per mood (Crumple.kt). */
    var ballArt: () -> Bitmap? = { null }

    private var loading = false
    private var applying = false
    /** Italic (or upright) for the next words typed, toggled with nothing selected. */
    private var pendingItalic: Boolean? = null

    // A wash of the sticky colour over words that carry one: a tint, not a
    // fill — at a quarter the sticky colour is unmistakably the sticky's, and
    // the ink and the ruling read straight through it.
    companion object {
        const val WASH_ALPHA = 0.26f
        const val BALL_HIT_W = 30f
        const val BALL_HIT_H = 40f
    }

    init {
        background = null
        gravity = Gravity.TOP or Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(Style.BASE_SIZE))
        typeface = Style.typeface()
        setTextColor(Style.ink)
        highlightColor = 0 // the selection is drawn here, sized off the ink
        includeFontPadding = false
        isFallbackLineSpacing = false
        // Typewriter input, but autocorrect stays on: it is a phone keyboard.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        isVerticalScrollBarEnabled = false
        setHorizontallyScrolling(false)
    }

    // MARK: Loading

    fun load(styled: StyledText) {
        loading = true
        typeface = Style.typeface()
        setTextColor(Style.ink)
        val editable = NoteText.toEditable(styled)
        setText(editable, BufferType.EDITABLE)
        restyle()
        loading = false
        pendingItalic = null
        syncStickies()
    }

    /** The paragraph furniture, rebuilt from the text: the grid and the bullets' hang. */
    private fun restyle() {
        val e = text ?: return
        applying = true
        e.getSpans(0, e.length, PitchSpan::class.java).forEach { e.removeSpan(it) }
        e.setSpan(PitchSpan(pitch), 0, e.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        // Collected, then applied — never written from inside a walk.
        val wanted = ArrayList<IntArray>()
        var start = 0
        while (start <= e.length) {
            var end = start
            while (end < e.length && e[end] != '\n') end++
            if (PitchSpan.startsWithBullet(e, start)) wanted += intArrayOf(start, min(end + 1, e.length))
            if (end >= e.length) break
            start = end + 1
        }
        val existing = e.getSpans(0, e.length, BulletMarginSpan::class.java)
        val same = existing.size == wanted.size && existing.zip(wanted).all { (s, w) ->
            e.getSpanStart(s) == w[0] && e.getSpanEnd(s) == w[1]
        }
        if (!same) {
            existing.forEach { e.removeSpan(it) }
            val indent = Style.bulletIndent.roundToInt()
            for (w in wanted) e.setSpan(BulletMarginSpan(indent), w[0], w[1], Spanned.SPAN_PARAGRAPH)
        }
        applying = false
    }

    fun applyTheme() {
        setTextColor(Style.ink)
        applyCaretTheme()
        invalidate()
    }

    // MARK: Edits

    private val watcher = object : TextWatcher {
        private var bullet: Int = -1   // "- " typed at a line's start: where the dash is
        private var newline: Int = -1  // Return typed: where
        private var inserted: IntRange? = null

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            if (loading || applying) return
            // Every edit passes here — typing, autocorrect, dictation, paste,
            // the bullet rewrites — so this is where sticky anchors are kept
            // tracking their text. Loading a document must not remap.
            store.remapStickies(start, count, after)
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (loading || applying) return
            bullet = -1
            newline = -1
            inserted = if (count > before) (start + before) until (start + count) else null
            if (count - before != 1) return
            val at = start + count - 1
            val c = s[at]
            if (c == ' ' && at >= 1 && s[at - 1] == '-' && (at - 1 == 0 || s[at - 2] == '\n')) bullet = at - 1
            if (c == '\n') newline = at
        }

        override fun afterTextChanged(e: Editable) {
            if (loading || applying) return
            pendingItalic?.let { italic -> inserted?.let { applyLean(e, it, italic) } }
            when {
                bullet >= 0 -> makeBullet(e, bullet)
                newline >= 0 -> continueBullet(e, newline)
            }
            bullet = -1
            newline = -1
            inserted = null
            stripStrays(e)
            restyle()
            store.textChanged(NoteText.toStyled(e))
            syncStickies()
            caretMoved()
        }
    }

    // Typing "- " at the start of a line turns it into a bullet.
    private fun makeBullet(e: Editable, dash: Int) {
        e.replace(dash, dash + 2, Style.BULLET_PREFIX) // passes through the watcher: remapped
        setSelection(min(dash + Style.BULLET_PREFIX.length, e.length))
    }

    // Return continues a list; Return on an empty bullet leaves it.
    private fun continueBullet(e: Editable, at: Int) {
        var lineStart = at
        while (lineStart > 0 && e[lineStart - 1] != '\n') lineStart--
        val line = e.subSequence(lineStart, at).toString()
        if (line.trim() == "•") {
            // empty bullet → remove it and the Return, and drop back to prose
            e.delete(lineStart, at + 1)
            setSelection(lineStart)
            return
        }
        if (!line.startsWith(Style.BULLET_PREFIX)) return
        e.insert(at + 1, Style.BULLET_PREFIX)
        setSelection(min(at + 1 + Style.BULLET_PREFIX.length, e.length))
    }

    // Safety net for anything that slips in styled: only italic and bold are
    // part of this page. Paste is always plain (see onTextContextMenuItem);
    // this catches whatever else arrives. A keyboard's composing underline is
    // its own business and is left alone.
    private fun stripStrays(e: Editable) {
        val strays = e.getSpans(0, e.length, android.text.style.CharacterStyle::class.java).filter {
            it !is StyleSpan && it !is BallSpan && it !is android.text.style.SuggestionSpan &&
                it !is android.text.style.UnderlineSpan && (e.getSpanFlags(it) and Spanned.SPAN_COMPOSING) == 0
        }
        if (strays.isEmpty()) return
        applying = true
        strays.forEach { e.removeSpan(it) }
        applying = false
    }

    override fun onTextContextMenuItem(id: Int): Boolean =
        super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id)

    // MARK: Italics
    //
    // Bold and underline are not part of this page; italics are, as on the
    // Mac. They arrive through the selection menu, and Ctrl+I on a keyboard.

    fun italicize() {
        val e = text ?: return
        val s = selectionStart
        val t = selectionEnd
        if (s < 0) return
        if (s == t) {
            val current = pendingItalic ?: isItalicAt(e, max(0, s - 1))
            pendingItalic = !current
            return
        }
        val all = (s until t).all { isItalicAt(e, it) }
        applyLean(e, s until t, !all)
        store.textChanged(NoteText.toStyled(e))
    }

    private fun isItalicAt(e: Spanned, i: Int): Boolean =
        e.getSpans(i, i + 1, StyleSpan::class.java).any {
            e.getSpanStart(it) <= i && e.getSpanEnd(it) > i &&
                (it.style == Typeface.ITALIC || it.style == Typeface.BOLD_ITALIC)
        }

    /** Italic on or off over `range`, keeping bold, by rebuilding the runs there. */
    private fun applyLean(e: Editable, range: IntRange, italic: Boolean) {
        if (range.isEmpty()) return
        val styled = NoteText.toStyled(e)
        val n = e.length
        val it = BooleanArray(n)
        val b = BooleanArray(n)
        for (r in styled.runs) for (k in r.start until r.end) { it[k] = r.italic; b[k] = r.bold }
        for (k in range) if (k < n) it[k] = italic
        applying = true
        e.getSpans(0, n, StyleSpan::class.java).forEach { e.removeSpan(it) }
        var k = 0
        while (k < n) {
            if (!it[k] && !b[k]) { k++; continue }
            val start = k
            val i0 = it[k]
            val b0 = b[k]
            while (k < n && it[k] == i0 && b[k] == b0) k++
            val style = if (i0 && b0) Typeface.BOLD_ITALIC else if (i0) Typeface.ITALIC else Typeface.BOLD
            e.setSpan(StyleSpan(style), start, k, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
        applying = false
    }

    override fun onKeyShortcut(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_I) {
            italicize()
            return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    // The selection menu: a selection can become a sticky, or go italic.
    private val editMenu = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, 1, 0, "Sticky Note")
            menu.add(Menu.NONE, 2, 1, "Italic")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val s = selectionStart
            val t = selectionEnd
            when (item.itemId) {
                1 -> {
                    if (t > s) {
                        val id = store.addSticky(s, t - s)
                        mode.finish()
                        clearFocus()
                        hideKeyboard()
                        store.openStickyID = id
                    }
                    return true
                }
                2 -> {
                    italicize()
                    mode.finish()
                    return true
                }
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    init {
        addTextChangedListener(watcher)
        customSelectionActionModeCallback = editMenu
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        post { imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
    }

    // MARK: Where lines are

    /** The top of the pitch band that holds `line`'s words, in view coordinates. */
    fun bandTop(line: Int): Float {
        val layout = layout ?: return totalPaddingTop.toFloat()
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        if (bottom - top >= pitch - 1) return (bottom - pitch + totalPaddingTop).toFloat()
        // The empty line after a trailing Return (or an empty note) is laid out
        // at the font's natural height; place it on the grid by hand.
        val above = if (line > 0) layout.getLineBottom(line - 1) else 0
        val gap = PitchSpan.gapAbove(text ?: "", layout.getLineStart(line), pitch)
        return (above + gap + totalPaddingTop).toFloat()
    }

    private fun bandCentre(line: Int): Float = bandTop(line) + pitch / 2f

    // The caret is the ink's height plus a little overshoot, wherever it is,
    // centred in its band — never the line's own height, most of which is
    // leading and would hang the caret down toward the next rule.
    override fun caretRect(offset: Int): RectF? {
        val layout = layout ?: return null
        val line = layout.getLineForOffset(offset)
        val x = layout.getPrimaryHorizontal(offset) + totalPaddingLeft
        val half = Style.inkHeight / 2 + Style.px(Style.CARET_OVERSHOOT)
        val c = bandCentre(line)
        return RectF(x, c - half, x + Style.px(WIDTH), c + half)
    }

    // MARK: Drawing

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (layout != null) {
            drawRuling(canvas)
            drawWash(canvas)
            drawSelection(canvas)
        }
        super.onDraw(canvas)
        drawBalls(canvas)
        drawCaret(canvas)
    }

    // The ruled page: hairlines anchored to the top of the text and to nothing
    // else — never to a line, whose height moves as you type. Drawn as black at
    // 10%, the same arithmetic as a 10% multiply over the paper.
    private fun drawRuling(canvas: Canvas) {
        val clip = canvas.clipBounds
        val left = totalPaddingLeft - Style.px(Style.RULE_OVERHANG)
        val right = width - totalPaddingRight + Style.px(Style.RULE_OVERHANG)
        val anchor = totalPaddingTop.toFloat()
        val p = pitch.toFloat()
        var y = anchor + max(0f, ((clip.top - anchor) / p).toInt().toFloat()) * p
        rulePaint.color = Style.RULE_TONE
        while (y <= clip.bottom + 1) {
            val ry = Style.ruleY(y)
            canvas.drawLine(left, ry, right, ry, rulePaint)
            y += p
        }
    }

    /** One band per line of `start until end`, sized as the selection is. */
    private fun bands(start: Int, end: Int, trim: Boolean): List<RectF> {
        val layout = layout ?: return emptyList()
        val e = text ?: return emptyList()
        val out = ArrayList<RectF>()
        if (end <= start) return out
        val height = Style.inkHeight + Style.px(Style.SELECTION_PADDING) * 2
        val first = layout.getLineForOffset(start)
        val last = layout.getLineForOffset(max(start, end - 1))
        for (line in first..last) {
            val ls = max(start, layout.getLineStart(line))
            var le = min(end, layout.getLineEnd(line))
            if (trim) while (le > ls && e[le - 1].isWhitespace()) le--
            else if (le > ls && e[le - 1] == '\n') le--
            if (le <= ls && trim) continue
            val x0 = layout.getPrimaryHorizontal(ls)
            val x1 = if (le < layout.getLineEnd(line) || !trim) layout.getPrimaryHorizontal(le) else layout.getLineRight(line)
            val endX = if (!trim && end > layout.getLineEnd(line) - 1 && line < last)
                max(x1, layout.getLineRight(line) + paint.measureText(" ")) else x1
            val c = bandCentre(line)
            out += RectF(min(x0, endX) + totalPaddingLeft, c - height / 2, max(x0, endX) + totalPaddingLeft, c + height / 2)
        }
        return out
    }

    private fun drawWash(canvas: Canvas) {
        val e = text ?: return
        washPaint.color = withAlpha(Style.theme.sticky, WASH_ALPHA)
        val r = Style.px(3f)
        val pad = Style.px(2f)
        for (sticky in store.stickies) {
            val s = sticky.start.coerceIn(0, e.length)
            val t = (s + max(sticky.length, 0)).coerceIn(0, e.length)
            for (band in bands(s, t, trim = true)) {
                band.inset(-pad, 0f)
                canvas.drawRoundRect(band, r, r, washPaint)
            }
        }
    }

    private fun drawSelection(canvas: Canvas) {
        val s = selectionStart
        val t = selectionEnd
        if (s < 0 || s == t || !hasFocus()) return
        selectionPaint.color = withAlpha(Style.ink, 0.2f)
        for (band in bands(min(s, t), max(s, t), trim = false)) canvas.drawRect(band, selectionPaint)
    }

    private fun drawBalls(canvas: Canvas) {
        val art = ballArt()
        for ((id, hit) in balls) {
            if (id == flyingID) continue
            if (art != null) {
                val w = art.width.toFloat()
                val h = art.height.toFloat()
                val scale = Style.px(BallSpan.BALL + 6) / max(w, 1f)
                val dw = w * scale
                val dh = h * scale
                canvas.drawBitmap(art, null,
                    RectF(hit.centerX() - dw / 2, hit.centerY() - dh / 2, hit.centerX() + dw / 2, hit.centerY() + dh / 2),
                    ballPaint)
            } else {
                ballPaint.color = Style.theme.sticky
                canvas.drawCircle(hit.centerX(), hit.centerY(), Style.px(BallSpan.BALL / 2), ballPaint)
            }
        }
    }

    // MARK: Stickies

    /**
     * Makes room in the line for each ball and works out where each is drawn.
     * Only the widened spaces that changed are touched.
     */
    fun syncStickies() {
        val e = text ?: return
        val length = e.length
        val sorted = store.stickies.sortedBy { it.start }
        val spots = HashMap<String, Int>()
        val room = HashMap<Int, Int>()
        for (sticky in sorted) {
            val at = BallSpan.spot(sticky, e)
            spots[sticky.id] = at
            if (at < length && BallSpan.widens(e[at])) room[at] = (room[at] ?: 0) + 1
        }
        val existing = e.getSpans(0, length, BallSpan::class.java)
        val have = existing.associateBy { e.getSpanStart(it) }
        if (have.keys != room.keys || room.any { (k, n) -> have[k]?.balls != n }) {
            applying = true
            existing.forEach { e.removeSpan(it) }
            for ((at, n) in room) {
                e.setSpan(BallSpan(n, BallSpan.width(n).roundToInt()), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            applying = false
        }
        val layout = layout
        balls.clear()
        if (layout == null) {
            post { if (this.layout != null) { syncStickies(); invalidate() } }
            return
        }
        val perSpot = HashMap<Int, Int>()
        val space = paint.measureText(" ")
        val slot = Style.px(BallSpan.SLOT)
        for (sticky in sorted) {
            val at = spots[sticky.id] ?: length
            val nth = perSpot[at] ?: 0
            perSpot[at] = nth + 1
            val line = layout.getLineForOffset(min(at, length))
            val edge = (if (at < length) layout.getPrimaryHorizontal(at) else layout.getLineRight(line)) + totalPaddingLeft
            val cx = min(edge + space + slot * (nth + 0.5f), width - Style.px(BallSpan.BALL) / 2 - Style.px(4f))
            val cy = bandCentre(line)
            val w = Style.px(BALL_HIT_W) / 2
            val h = Style.px(BALL_HIT_H) / 2
            balls[sticky.id] = RectF(cx - w, cy - h, cx + w, cy + h)
        }
        invalidate()
    }

    /** Where `id`'s ball sits, in this view's coordinates. */
    fun ballCentre(id: String): Pair<Float, Float>? {
        syncStickies() // a sticky just made has no ball yet
        return balls[id]?.let { it.centerX() to it.centerY() }
    }

    // A tap on a ball opens its sticky, and doesn't reach the text beneath.
    private var downOnBall: String? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downOnBall = balls.entries.firstOrNull { it.value.contains(event.x, event.y) }?.key
                if (downOnBall != null) return true
            }
            MotionEvent.ACTION_MOVE -> if (downOnBall != null) return true
            MotionEvent.ACTION_UP -> {
                val id = downOnBall
                downOnBall = null
                if (id != null) {
                    if (balls[id]?.contains(event.x, event.y) == true) {
                        clearFocus()
                        hideKeyboard()
                        store.openStickyID = id
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> if (downOnBall != null) { downOnBall = null; return true }
        }
        return super.onTouchEvent(event)
    }
}

// MARK: - The scrolling page

/**
 * Scrolls the page, and gives it soft edges: text that runs on past the edge
 * of what is showing fades out as it reaches that edge, so the eye reads
 * "there is more" rather than "the words are cut". A mask over everything the
 * page draws — the words and the ruling — and not over the paper, which is
 * pinned to the screen behind it.
 */
@SuppressLint("ViewConstructor")
class NoteScroll(context: Context, store: NotesStore) : ScrollView(context) {
    val editor = RuledEditText(context, store)
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    var statusBar = 0f
        set(value) { if (field != value) { field = value; updateInsets() } }
    var navBar = 0f
        set(value) { if (field != value) { field = value; updateInsets() } }
    /** True while the keyboard is up; the view then ends at its top. */
    var keyboardUp = false
        set(value) {
            if (field == value) return
            field = value
            updateInsets()
            invalidate()
            // The keyboard came up over the caret: bring it back into view.
            if (value && editor.hasFocus()) post { revealCaret() }
        }

    companion object {
        const val FADE_BAND = 56f
        const val KEYBOARD_CLEARANCE = 64f
        // Enough that the end of a note stops above where the fade begins: the
        // flourish, plus its lift, plus the clear band above it.
        val RESTING_CLEARANCE =
            Style.CORNER_FLOURISH_LIFT + Style.CORNER_FLOURISH_HEIGHT + Style.CORNER_FLOURISH_CLEARANCE + 12f
    }

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setBackgroundColor(0)
        addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setLayerType(LAYER_TYPE_HARDWARE, null)
        updateInsets()
    }

    private fun updateInsets() {
        val side = Style.px(Style.SIDE_MARGIN).roundToInt()
        val bottom = if (keyboardUp) Style.px(KEYBOARD_CLEARANCE) else navBar + Style.px(RESTING_CLEARANCE)
        editor.setPadding(side, (statusBar + Style.px(Style.TOP_INSET)).roundToInt(), side, bottom.roundToInt())
    }

    // Whatever the editor asks to see — the caret, as it types or moves — is
    // seen with room around it: clear of the band under the status bar, and
    // above the keyboard (or the flourish) by the page's own clearance.
    override fun requestChildRectangleOnScreen(child: android.view.View, rect: android.graphics.Rect, immediate: Boolean): Boolean {
        val r = android.graphics.Rect(rect)
        r.top -= (statusBar + Style.px(Style.BACK_BAND)).roundToInt()
        r.bottom += (if (keyboardUp) Style.px(KEYBOARD_CLEARANCE) else navBar + Style.px(RESTING_CLEARANCE)).roundToInt()
        return super.requestChildRectangleOnScreen(child, r, immediate)
    }

    // The page shrinks frame by frame as the keyboard rises; keep the caret
    // in view the whole way up.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h < oldh && editor.hasFocus()) post { revealCaret() }
    }

    private fun revealCaret() {
        val c = editor.caretRect(editor.selectionEnd) ?: return
        val r = android.graphics.Rect(c.left.toInt(), c.top.toInt(), c.right.toInt() + 1, c.bottom.toInt())
        requestChildRectangleOnScreen(editor, r, false)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        val h = height.toFloat()
        if (h <= 0) return super.draw(canvas)
        val top = scrollY.toFloat()
        val save = canvas.saveLayer(0f, top, width.toFloat(), top + h, null)
        super.draw(canvas)
        // The words are gone by the bottom of the status bar and fully back a
        // little below the clear band under it. At the foot, with the keyboard
        // up, they fade into its top when there is more below; without it they
        // are always gone by the top of the corner flourish.
        val band = Style.px(FADE_BAND)
        val topClear = max(0f, statusBar + Style.px(Style.BACK_BAND) - band)
        val topSolid = topClear + band
        val flourishTop = h - navBar - Style.px(Style.CORNER_FLOURISH_LIFT + Style.CORNER_FLOURISH_HEIGHT)
        val foot = if (keyboardUp) h else flourishTop
        val footBand = if (keyboardUp) band else Style.px(Style.CORNER_FLOURISH_CLEARANCE)
        val below = max(0f, (getChildAt(0)?.height ?: 0) - h - scrollY)
        val bottomFade = if (keyboardUp) min(1f, below / footBand) else 1f
        val bottomSolid = max(topSolid, foot - footBand)
        fun a(fade: Float) = ((1f - fade) * 255).roundToInt() shl 24
        val opaque = 0xFF shl 24
        val stops = floatArrayOf(0f, topClear, topSolid, bottomSolid, foot, h).map { (it / h).coerceIn(0f, 1f) }
        maskPaint.shader = LinearGradient(0f, top, 0f, top + h,
            intArrayOf(a(1f), a(1f), opaque, opaque, a(bottomFade), a(bottomFade)),
            stops.toFloatArray(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, top, width.toFloat(), top + h, maskPaint)
        canvas.restoreToCount(save)
    }

    /** Brings the caret into view after a load or a jump. */
    fun scrollToCaret() {
        val rect = editor.caretRect(editor.selectionEnd) ?: return
        val bottomRoom = if (keyboardUp) Style.px(KEYBOARD_CLEARANCE) else navBar + Style.px(RESTING_CLEARANCE)
        val target = (rect.bottom + bottomRoom - height).roundToInt()
        scrollTo(0, target.coerceIn(0, max(0, editor.height - height)))
    }

}
