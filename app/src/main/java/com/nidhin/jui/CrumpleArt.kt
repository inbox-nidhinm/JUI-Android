package com.nidhin.jui

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Pictures of the card

/**
 * What the card is drawn with, read on the main thread — the mood, the
 * typeface and the artwork — so the picture itself can be drawn anywhere.
 */
class StampLook(
    val sticky: Int,
    val panel: Int,
    val ink: Int,
    val typeface: Typeface,
    val flourish: Bitmap?,
    val key: String,
) {
    companion object {
        val current: StampLook
            get() {
                Surface.STICKY.paper.bitmap // loaded here, so other threads only read it
                return StampLook(
                    sticky = Style.theme.sticky, panel = Style.theme.panel, ink = Style.theme.ink,
                    typeface = Style.typeface(),
                    flourish = Flourish.frames(FlourishSet.STICKY).lastOrNull(),
                    key = "${Style.theme.id}-${Style.fontFamily}")
            }
    }
}

object CrumpleArt {
    // Twice the card's points: the sheet is only ever this sharp while it is
    // moving, and lies flat for a twentieth of a second before the card takes
    // over — more would draw pixels nobody sees.
    private const val SCALE = 2f

    /**
     * The card as it looks: sticky colour, sticky paper, the flourish (when it
     * has drawn itself) and the words, cut to the stamp. Drawn the way the
     * card lays itself out, so the hand-over can't be seen. Safe off the main
     * thread, given a `look` read on it. Sizes in points.
     */
    fun stamp(widthPts: Float, heightPts: Float, text: String?, flourish: Boolean,
              look: StampLook = StampLook.current): Bitmap {
        val w = max(1, (widthPts * SCALE).roundToInt())
        val h = max(1, (heightPts * SCALE).roundToInt())
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        canvas.clipRect(rect)
        canvas.clipPath(StampOutline.path(rect,
            radius = 4 * SCALE, pitch = 15 * SCALE, cornerGap = 6 * SCALE))
        canvas.drawColor(look.sticky)
        Surface.STICKY.draw(canvas, rect, SCALE)
        val last = look.flourish
        if (flourish && last != null) {
            val fw = StickyCard.SIDE * StickyCard.FLOURISH_WIDTH
            val left = StickyCard.SIDE * (StickyCard.FLOURISH_RIGHT - StickyCard.FLOURISH_WIDTH)
            val top = heightPts - StickyCard.flourishOnStamp
            val frame = RectF(left * SCALE, top * SCALE,
                (left + fw) * SCALE, (top + StickyCard.flourishHeight) * SCALE)
            // Tinted panel, drawn *through* the gold, not onto it.
            val tinted = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(look.panel, PorterDuff.Mode.SRC_IN)
                blendMode = BlendMode.MULTIPLY
            }
            canvas.drawBitmap(last, null, frame, tinted)
        }
        if (!text.isNullOrEmpty()) words(canvas, text, heightPts, look)
        return bitmap
    }

    /**
     * The back of the card, and the face before its words are drawn: just the
     * paper. The same for every sticky of a size, so kept.
     */
    fun blank(widthPts: Float, heightPts: Float, look: StampLook = StampLook.current): Bitmap {
        val key = "${look.key}-${widthPts.roundToInt()}x${heightPts.roundToInt()}"
        synchronized(blanks) {
            blanks[key]?.let { return it }
        }
        val image = stamp(widthPts, heightPts, null, false, look)
        synchronized(blanks) {
            if (blanks.size > 8) blanks.clear()
            blanks[key] = image
        }
        return image
    }

    private val blanks = HashMap<String, Bitmap>()

    /**
     * The words in the card's text box, laid out exactly as the card's text
     * view lays them out (StickyText's line, inset and font) — and, when they
     * run on past the box, faded at its foot as the text view fades them.
     */
    private fun words(canvas: Canvas, text: String, cardHeight: Float, look: StampLook) {
        val boxW = StickyCard.textWidth * SCALE
        val boxH = max(1f, StickyCard.textHeight(cardHeight)) * SCALE
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = look.typeface
            textSize = StickyText.TEXT_SIZE * SCALE
            color = look.ink
        }
        val spanned = SpannableString(text)
        spanned.setSpan(StickyText.lineSpan(SCALE), 0, spanned.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        val layout = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, boxW.roundToInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setUseLineSpacingFromFallbacks(false)
            .build()
        val used = layout.lineCount * StickyText.LINE * SCALE
        val below = max(0f, ceil(used) + (StickyText.INSET_TOP + StickyText.INSET_BOTTOM) * SCALE - boxH)

        val left = StickyCard.TEXT_SIDES * SCALE
        val top = StickyCard.TEXT_TOP * SCALE
        val layer = canvas.saveLayer(left, top, left + boxW, top + boxH, null)
        canvas.clipRect(left, top, left + boxW, top + boxH)
        canvas.translate(left, top + StickyText.INSET_TOP * SCALE)
        layout.draw(canvas)
        canvas.translate(-left, -(top + StickyText.INSET_TOP * SCALE))
        val band = StickyText.FADE_BAND * SCALE
        val fade = min(1f, below / band)
        if (fade > 0f) {
            // Erase towards the foot: nothing at the top of the band, `fade`
            // of the ink at the bottom.
            val eraser = Paint().apply {
                shader = LinearGradient(0f, top + boxH - band, 0f, top + boxH,
                    Color.argb(0, 0, 0, 0), Color.argb((fade * 255).roundToInt(), 0, 0, 0),
                    Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
            canvas.drawRect(left, top + boxH - band, left + boxW, top + boxH, eraser)
        }
        canvas.restoreToCount(layer)
    }

    /**
     * The card's soft shadow, for while the sheet lies flat — with the card's
     * own footprint left clear, so it never shows through a lifting corner.
     * A pixel to a point; sizes in points.
     */
    fun shadow(widthPts: Float, heightPts: Float): Bitmap {
        val pad = 30f
        val w = (widthPts + pad * 2).roundToInt().coerceAtLeast(1)
        val h = (heightPts + pad * 2).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val card = RectF(pad, pad, pad + widthPts, pad + heightPts)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(20f, 0f, 0f, Color.argb((0.08f * 255).roundToInt(), 0, 0, 0))
        }
        canvas.drawRect(card, paint)
        canvas.drawRect(card, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        return bitmap
    }

    /**
     * The ball in the line, in the current mood: a blank card, crumpled, at
     * rest. Rendered once per mood, offscreen, by the same renderer as the
     * stage. Main thread (it reads the mood).
     */
    fun marginBall(): Bitmap? {
        val key = Style.theme.id
        balls[key]?.let { return it }
        val side = CrumpleSheet.BALL_DIAMETER + 6
        val scale = min(3f, Style.density)
        val scene = CrumpleScene()
        scene.setViewport(side, side)
        val paper = blank(StickyCard.SIDE, StickyCard.SIDE)
        scene.load(StickyCard.SIDE, StickyCard.SIDE, paper, paper)
        val centre = PointF(side / 2, side / 2)
        scene.pose(0f, centre, RectF(centre.x - 1, centre.y - 1, centre.x + 1, centre.y + 1), 0f)
        // Drawn at twice the size and filtered down: a pbuffer may have no
        // multisampling, and a 30-point ball shows every stair on its edge.
        val px = (side * scale).roundToInt()
        val big = px * 2
        val drawn = Offscreen.render(big, big) { renderer -> renderer.draw(scene, big, big) } ?: return null
        val image = Bitmap.createScaledBitmap(drawn, px, px, true)
        if (image !== drawn) drawn.recycle()
        balls[key] = image
        return image
    }

    private val balls = HashMap<String, Bitmap>()
}

/**
 * A GL ES 3 context of its own, on a pbuffer, for drawing a scene to a bitmap —
 * the ball in the line. Made per call, which happens once per mood.
 */
private object Offscreen {
    fun render(width: Int, height: Int, draw: (CrumpleRenderer) -> Unit): Bitmap? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        val previousDisplay = EGL14.eglGetCurrentDisplay()
        val previousContext = EGL14.eglGetCurrentContext()
        val previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val config = chooseConfig(display, samples = 4) ?: chooseConfig(display, samples = 0) ?: return null
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return null
        val surface = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        try {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            val renderer = CrumpleRenderer()
            renderer.init()
            draw(renderer)
            GLES30.glFinish()
            val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            pixels.rewind()
            raw.copyPixelsFromBuffer(pixels)
            // GL's rows run bottom-up.
            val flipped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(flipped).apply { scale(1f, -1f, width / 2f, height / 2f); drawBitmap(raw, 0f, 0f, null) }
            raw.recycle()
            return flipped
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            if (previousContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(previousDisplay, previousDraw, previousRead, previousContext)
            }
        }
    }

    private fun chooseConfig(display: android.opengl.EGLDisplay, samples: Int): EGLConfig? {
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, EGL14.EGL_SAMPLES, samples,
            EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
        return configs[0]
    }
}
