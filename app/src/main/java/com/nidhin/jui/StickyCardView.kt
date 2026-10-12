package com.nidhin.jui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.max
import kotlin.math.roundToInt

/** The postage stamp as a Compose shape: the rectangle, perforations punched out. */
object StampShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val d = density.density
        // Perforations sized in points; clipped to the rect by the caller's layer.
        val path = StampOutline.path(RectF(0f, 0f, size.width, size.height), 4f * d, 15f * d, 6f * d)
        return Outline.Generic(path.asComposePath())
    }
}

// MARK: - Sticky text

/**
 * The sticky card's writing surface: plain text in the house family, a step
 * below the page at 140% leading, with typewriter input and the house caret.
 * The card sizes itself to the text, so this only scrolls when the text is
 * longer than the screen can show — and then its foot fades, except while it
 * is being written in: a line fading out under the caret reads as the words
 * disappearing as they are typed.
 */
@SuppressLint("ViewConstructor")
class StickyEditText(context: Context) : CaretEditText(context) {
    var onChange: (String) -> Unit = {}
    var onEditing: (Boolean) -> Unit = {}
    private var applying = false
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    init {
        background = null
        gravity = Gravity.TOP or Gravity.START
        includeFontPadding = false
        isFallbackLineSpacing = false
        setPadding(0, Style.px(StickyText.INSET_TOP).roundToInt(), 0, Style.px(StickyText.INSET_BOTTOM).roundToInt())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        isVerticalScrollBarEnabled = false
        applyLook()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable) {
                if (applying) return
                spanLines(e)
                onChange(e.toString())
                caretMoved()
            }
        })
        setOnFocusChangeListener { _, focused -> onEditing(focused); invalidate() }
    }

    fun applyLook() {
        typeface = Style.typeface()
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.px(StickyText.TEXT_SIZE))
        setTextColor(Style.ink)
        highlightColor = withAlpha(Style.ink, 0.2f)
        applyCaretTheme()
        text?.let { spanLines(it) }
    }

    fun setPlain(value: String) {
        if (text?.toString() == value) return
        applying = true
        setText(value)
        text?.let { spanLines(it) }
        applying = false
    }

    // Every line exactly the sticky's line, type centred — as the card measures it.
    private fun spanLines(e: Editable) {
        applying = true
        e.getSpans(0, e.length, CentredLineSpan::class.java).forEach { e.removeSpan(it) }
        e.setSpan(StickyText.lineSpan(), 0, e.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        applying = false
    }

    fun focusAndShowKeyboard() {
        requestFocus()
        setSelection(text?.length ?: 0)
        val imm = context.getSystemService(InputMethodManager::class.java)
        post { imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
    }

    override fun draw(canvas: Canvas) {
        val band = Style.px(StickyText.FADE_BAND)
        val layout = layout
        val below = if (layout == null) 0f
            else max(0f, layout.height + totalPaddingTop + totalPaddingBottom - height - scrollY.toFloat())
        if (hasFocus() || below <= 0f || height <= 0) return super.draw(canvas)
        val top = scrollY.toFloat()
        val h = height.toFloat()
        val save = canvas.saveLayer(0f, top, width.toFloat(), top + h, null)
        super.draw(canvas)
        val fade = minOf(1f, below / band)
        maskPaint.shader = LinearGradient(0f, top + h - band, 0f, top + h,
            0xFF000000.toInt(), ((1f - fade) * 255).roundToInt() shl 24, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, top, width.toFloat(), top + h, maskPaint)
        canvas.restoreToCount(save)
    }
}

// MARK: - The sticky card
//
// An open sticky: the Mac's margin stamp, brought to the middle of the page
// because a phone has no margin to hold it. Sticky colour, sticky paper, the
// flourish pressed into the bottom-right corner and run off its edge.
//
// It is square at least, and grows to show everything written on it — opened
// to be read, it shows the whole note without a keyboard. The keyboard comes
// up when the text is tapped, or straight away for a sticky just made, which
// has nothing to read yet. Taller than the screen allows, it stops growing and
// its text scrolls instead.
@Composable
fun StickyCardView(store: NotesStore, id: String) {
    var draft by remember(id) { mutableStateOf(store.stickies.firstOrNull { it.id == id }?.text ?: "") }
    var editing by remember(id) { mutableStateOf(false) }
    val startsEditing = remember(id) { draft.isEmpty() } // just made: straight to writing
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.getTop(density) / density.density
    val navBar = WindowInsets.navigationBars.getBottom(density) / density.density
    val ime = WindowInsets.ime.getBottom(density) / density.density
    var editor by remember { mutableStateOf<StickyEditText?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val areaW = maxWidth.value
        // The whole window down to the keyboard, so the card can centre itself
        // on the screen rather than on the safe area.
        val areaH = maxHeight.value - ime
        val keyboardUp = ime > 0
        val frame = StickyCard.stampFrame(draft, areaW, areaH, statusBar, if (keyboardUp) 0f else navBar, editing)
        LaunchedEffect(frame) { CrumpleDirectorFrame.update(frame) }
        // Grows with the text and steps up for the keyboard, smoothly.
        val top by animateFloatAsState(frame.top, tween(250), label = "top")
        val height by animateFloatAsState(frame.height(), tween(200), label = "height")
        val side = StickyCard.SIDE
        val textHeight = StickyCard.textHeight(height)
        val scrolls = StickyText.height(draft, StickyCard.textWidth) > textHeight + 0.5f

        Box(
            Modifier
                .offset { IntOffset((frame.left * density.density).roundToInt(), (top * density.density).roundToInt()) }
                .size(side.dp, height.dp)
                .drawBehind {
                    // A soft shadow under the stamp: black at 8%, lifted 4.
                    drawIntoCanvas { c ->
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = 0
                            setShadowLayer(10f * density.density, 0f, 4f * density.density, 0x14000000)
                        }
                        c.nativeCanvas.drawRect(0f, 0f, size.width, size.height, p)
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        clip = true
                        shape = StampShape
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .paper(stickyColor, Surface.STICKY),
            ) {
                // Draws itself on every time the stamp is opened — the card is
                // made fresh for each opening, only once the crumpled sheet has
                // opened out flat — and stays the same while it is open, so
                // typing, growing and the keyboard don't restart it. Drawn
                // *through* the gold, not onto it.
                AnimatedFlourish(
                    Modifier
                        .offset((side * (StickyCard.FLOURISH_RIGHT - StickyCard.FLOURISH_WIDTH)).dp,
                            (height - StickyCard.flourishOnStamp).dp)
                        .size((side * StickyCard.FLOURISH_WIDTH).dp, StickyCard.flourishHeight.dp),
                    set = FlourishSet.STICKY, tint = panelColor, play = Play.Always, blend = BlendMode.Multiply,
                )
            }
            AndroidView(
                factory = { ctx ->
                    StickyEditText(ctx).also { tv ->
                        tv.setPlain(draft)
                        editor = tv
                        if (startsEditing) tv.post { tv.focusAndShowKeyboard() }
                    }
                },
                update = { tv ->
                    tv.onChange = { text ->
                        draft = text
                        store.updateStickyText(id, text)
                        CrumpleDirector.textChanged(text)
                    }
                    tv.onEditing = { editing = it }
                    tv.setPlain(draft)
                    tv.isVerticalScrollBarEnabled = false
                    if (Style.theme.id != tv.tag) { tv.tag = Style.theme.id; tv.applyLook() }
                    tv.isVerticalFadingEdgeEnabled = false
                    if (!scrolls) tv.scrollTo(0, 0)
                },
                modifier = Modifier
                    .offset(StickyCard.TEXT_SIDES.dp, StickyCard.TEXT_TOP.dp)
                    .size(StickyCard.textWidth.dp, textHeight.dp),
            )
        }

        // Only while writing. A sticky opened to be read is just the stamp;
        // tapping the page around it puts it away. They hang below the stamp
        // rather than being laid out with it, so they don't move it.
        AnimatedVisibility(
            editing, enter = fadeIn(tween(150)), exit = fadeOut(tween(150)),
            modifier = Modifier.fillMaxWidth()
                .offset { IntOffset(0, ((top + height + StickyCard.BUTTON_GAP) * density.density).roundToInt()) },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)) {
                StampButton(delete = true) {
                    editor?.let { hideKeyboardFrom(it) }
                    store.removeSticky(id)
                }
                StampButton(delete = false) {
                    editor?.let { hideKeyboardFrom(it) }
                    store.updateStickyText(id, draft)
                    store.closeSticky()
                }
            }
        }
    }
    DisposableEffect(id) { onDispose { editor?.let { hideKeyboardFrom(it) } } }
}

/** The sheet that takes over from the card when it is put away starts from wherever the card last was. */
object CrumpleDirectorFrame {
    fun update(frame: RectF) { CrumpleDirector.cardFrame = RectF(frame) }
}

fun hideKeyboardFrom(view: android.view.View) {
    view.clearFocus()
    view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
}
