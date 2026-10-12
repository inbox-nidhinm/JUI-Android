package com.nidhin.jui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * The note page, which knows where each sticky's ball sits. Points are window
 * points (dp from the window's top left).
 */
interface BallPage {
    fun markCentre(id: String): PointF?
    /** The ball of the sticky that is out is in the air, not in the line. */
    fun showFlight()
}

// MARK: - Running it

/**
 * Runs the fold, and decides when the real card is on screen. One for the app:
 * the page tells it where a ball is, the card tells it where the card is.
 *
 * The card itself is Compose and can't fold, so the fold is a stand-in: a sheet
 * of a few thousand points drawn by GL, textured with a picture of the card,
 * bent between a crumpled ball and flat. The real card appears only once the
 * sheet has landed flat in exactly its place — which is also why the card's
 * flourish only starts drawing itself after the unfold — and on the way out the
 * sheet takes over from the card the same way round. Main thread only.
 */
object CrumpleDirector {
    /**
     * The sticky whose real card is showing — set once its sheet has landed
     * flat, cleared once a sheet has taken its place on the way out.
     */
    var cardID: String? by mutableStateOf(null)
        private set

    /** Where the open card is, in window points — kept up to date by the card. */
    var cardFrame = RectF()

    /** The note page, which knows where each sticky's ball sits. */
    var page: BallPage? = null

    /** The sticky whose ball has left its place in the line. */
    var flyingID: String? = null
        private set(value) {
            val changed = field != value
            field = value
            if (changed) page?.showFlight()
        }

    val stage = CrumpleScene()
    internal var view: CrumpleStageView? = null

    const val OPEN_SECONDS = 1.15f
    const val CLOSE_SECONDS = 0.95f
    // How long a hand-over keeps both the card and the sheet on screen, so
    // neither leaves before the other has drawn.
    private const val HAND_OVER_MS = 50L

    private val main = Handler(Looper.getMainLooper())
    private val painter = Executors.newSingleThreadExecutor { r -> Thread(r, "JUI-crumple").apply { isDaemon = true } }

    private var running = false
    private var last = 0L
    private var t = 0f
    private var target = 0f
    private var elapsed = 0f
    private var id: String? = null
    private var mark: PointF? = null
    private var card = RectF()
    // Counts sheets loaded, so a face drawn in the background for one is
    // never put on a later one.
    private var generation = 0
    // The open sticky's words, and the face it will be put away with — drawn
    // in the background while it is open, so putting it away doesn't stop to
    // draw. Redrawn on the spot if the words have changed since.
    private var openText = ""
    private class Face(val text: String, val w: Float, val h: Float, val key: String, val image: Bitmap)
    private var closingFace: Face? = null
    private var redraw: Runnable? = null

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            step(frameTimeNanos)
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Opens `id`'s ball out into the card at `frame` (window points). */
    fun open(id: String, text: String, frame: RectF) {
        if (prefersReducedMotion) {
            cardID = id // the card fades in; no fold
            flyingID = id
            return
        }
        if (this.id == id && running) { // put away and taken out again mid-way
            target = 1f
            return
        }
        stop()
        this.id = id
        card = RectF(frame)
        openText = text
        closingFace = null
        mark = page?.markCentre(id)
        // Off at once with plain paper; the words are drawn in the background
        // and pressed on while the ball is still too small to read them.
        val look = StampLook.current
        val paper = CrumpleArt.blank(frame.width(), frame.height(), look)
        stage.load(frame.width(), frame.height(), paper, paper)
        generation += 1
        if (text.isNotEmpty()) {
            val generation = this.generation
            val w = frame.width(); val h = frame.height()
            painter.execute {
                val face = CrumpleArt.stamp(w, h, text, false, look)
                main.post { if (this.generation == generation) stage.setFront(face) }
            }
        }
        t = 0f
        target = 1f
        run()
        // The ball in the scene is drawing now; the one in the line can go.
        main.postDelayed({ if (this.id == id) flyingID = id }, HAND_OVER_MS)
    }

    /** `text` is null when the sticky is gone — deleted, or put away unwritten. */
    fun close(id: String, text: String?) {
        if (prefersReducedMotion) {
            cardID = null
            flyingID = null
            return
        }
        val gone = text == null
        if (this.id == id && running && target == 1f) { // still opening: turn back
            target = 0f
            if (gone) mark = null
            return
        }
        stop()
        this.id = id
        if (cardFrame.width() > 0) card = RectF(cardFrame)
        mark = if (gone) null else page?.markCentre(id)
        val look = StampLook.current
        val ready = closingFace
        val face = if (ready != null && ready.w == card.width() && ready.h == card.height() &&
            ready.key == look.key && (gone || ready.text == text)) {
            ready.image
        } else {
            CrumpleArt.stamp(card.width(), card.height(), text ?: openText, true, look)
        }
        closingFace = null
        stage.load(card.width(), card.height(), face, CrumpleArt.blank(card.width(), card.height(), look))
        generation += 1
        t = 1f
        target = 0f
        flyingID = id
        run()
        main.postDelayed({ if (cardID == id) cardID = null }, HAND_OVER_MS)
    }

    /**
     * Kept up to date by the card as it is written in; the face it will be put
     * away with is redrawn once the typing pauses.
     */
    fun textChanged(text: String) {
        if (text == openText) return
        openText = text
        redraw?.let { main.removeCallbacks(it) }
        val id = this.id
        val work = Runnable { prepareClosingFace(id) }
        redraw = work
        main.postDelayed(work, 400)
    }

    private fun prepareClosingFace(forID: String?) {
        if (forID == null || id != forID || cardID != forID || running || cardFrame.width() <= 0) return
        val text = openText
        val w = cardFrame.width(); val h = cardFrame.height()
        val look = StampLook.current
        painter.execute {
            val image = CrumpleArt.stamp(w, h, text, true, look)
            main.post {
                if (id == forID && cardID == forID) closingFace = Face(text, w, h, look.key, image)
            }
        }
    }

    private fun run() {
        view?.let { v ->
            if (v.width > 0) stage.setViewport(v.width / Style.density, v.height / Style.density)
        }
        elapsed = 0f
        last = System.nanoTime()
        stage.pose(t, mark, card, elapsed)
        view?.requestRender()
        running = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        if (cardID != null && cardID != id) cardID = null
        flyingID = null
    }

    private fun step(now: Long) {
        val dt = minOf(0.05f, (now - last) / 1e9f).coerceAtLeast(0f)
        last = now
        elapsed += dt
        if (t != target) {
            val step = dt / (if (target > t) OPEN_SECONDS else CLOSE_SECONDS)
            t = if (target > t) minOf(target, t + step) else maxOf(target, t - step)
        }
        stage.pose(t, mark, card, elapsed)
        view?.requestRender()
        if (t == target) landed()
    }

    private fun landed() {
        running = false
        val id = this.id
        if (target == 1f) {
            cardID = id // the real card, flat where the sheet is; its flourish starts now
            // Once the card has said where it is, draw the face it will be put
            // away with.
            main.postDelayed({ prepareClosingFace(id) }, 150)
        } else {
            flyingID = null // the ball is back in the line
            this.id = null
        }
        main.postDelayed({
            if (!running) {
                stage.rest()
                view?.requestRender()
            }
        }, HAND_OVER_MS)
    }
}

// MARK: - The stage

/**
 * The stage the sheet is drawn on, over everything, never touched: a
 * translucent GL surface placed on top of the window. It draws only while the
 * director asks, and clears to nothing at rest.
 */
@SuppressLint("ViewConstructor")
class CrumpleStageView(context: Context) : GLSurfaceView(context) {
    private val renderer = CrumpleRenderer()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(Chooser())
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        preserveEGLContextOnPause = true
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                // Compile the paper's shaders now, not on the first tap.
                renderer.init()
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {}

            override fun onDrawFrame(gl: GL10?) {
                renderer.draw(CrumpleDirector.stage, width, height)
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    // Touches pass straight through to the page beneath.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        CrumpleDirector.stage.setViewport(w / Style.density, h / Style.density)
    }

    /** RGBA8 with a depth buffer, multisampled where the GPU allows. */
    private class Chooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig =
            choose(egl, display, 4) ?: choose(egl, display, 0)
            ?: throw IllegalStateException("JUI: no translucent GL config")

        private fun choose(egl: EGL10, display: EGLDisplay, samples: Int): EGLConfig? {
            val attributes = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, EGL10.EGL_SAMPLES, samples,
                EGL10.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, attributes, configs, 1, count) || count[0] == 0) return null
            return configs[0]
        }
    }
}

/**
 * Puts the stage over the whole window for as long as this is composed. It is
 * a View laid on the window's decor rather than an `AndroidView` in the tree:
 * Compose would hand it every touch in its bounds, and the stage must never be
 * touched — beneath it is the page, the scrim and the card.
 */
@Composable
fun CrumpleStage() {
    val host = LocalView.current
    DisposableEffect(host) {
        val root = host.rootView as? ViewGroup
        val stage = CrumpleStageView(host.context)
        root?.addView(stage, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        CrumpleDirector.view = stage
        // A GL surface must hear when the activity pauses and resumes.
        val lifecycle = host.findViewTreeLifecycleOwner()?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> stage.onPause()
                Lifecycle.Event.ON_RESUME -> stage.onResume()
                else -> {}
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            if (CrumpleDirector.view === stage) CrumpleDirector.view = null
            root?.removeView(stage)
        }
    }
}
