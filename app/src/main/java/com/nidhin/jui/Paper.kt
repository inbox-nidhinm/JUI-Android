package com.nidhin.jui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode as AndroidBlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt

// Paper.
//
// Three photographed stocks — the page, the sticky notes, the panels — applied
// as *blend layers over themed colour*, never as pictures in their own right.
// They are measurably neutral (R = G = B), so the colour comes from the theme
// underneath and only the tone comes from the paper: one file serves all six
// palettes. A texture is always a multiply at a fraction, never drawn opaque.
//
// Every number here (strength, fit) is carried over from the iPhone app, and
// from the Mac before it, where each was recovered from the Figma system.
enum class Paper(val file: String) {
    SHEET("sheet"),  // the page itself
    STICKY("sticky"), // sticky notes
    UI("ui");         // panels, the search palette, folder cards

    val bitmap: Bitmap? by lazy {
        runCatching {
            Style.app.assets.open("Textures/$file.jpg").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    val image: ImageBitmap? by lazy { bitmap?.asImageBitmap() }

    /** The photograph's size in points: one image pixel is one point, as on iOS. */
    val size: Size get() = bitmap?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size(1f, 1f)
}

sealed class Fit {
    /** The whole photograph across the surface. */
    data object Cover : Fit()
    /**
     * Drawn at a fixed multiple of the photograph's own size and cropped to
     * the surface. Lower numbers mean bigger grain.
     */
    data class Window(val k: Float) : Fit()

    /** Both in points. Shared by the Views and Compose so the grain agrees. */
    fun scale(surface: Size, image: Size): Float {
        val cover = max(surface.width / image.width, surface.height / image.height)
        return when (this) {
            Cover -> cover
            is Window -> max(k, cover)
        }
    }
}

// How hard each surface takes its paper. Strength sets the tone and fit sets
// the grain size; both belong to the surface, not the stock.
enum class Surface(val paper: Paper, val strength: Float) {
    PAGE(Paper.SHEET, 0.50f),
    STICKY(Paper.STICKY, 0.46f),
    FOLDER_PANEL(Paper.UI, 0.93f),
    FOLDER_TILE(Paper.UI, 0.49f),
    PAGE_CARD(Paper.SHEET, 0.50f), // as the page
    BUTTON(Paper.UI, 0.75f),
    ENVELOPE(Paper.UI, 0.85f),
    LETTER(Paper.UI, 0.40f);

    val fit: Fit
        get() = when (this) {
            // One image pixel to about one screen pixel: the same paper held
            // a little closer than on the Mac, not a different stock.
            STICKY -> Fit.Window(0.5f)
            // The page's paper at the page's own grain: the scale the
            // photograph is drawn at to cover the screen, held for a card a
            // fraction of its size — covering just the card would blow the
            // grain up.
            PAGE_CARD -> {
                val dm = Style.app.resources.displayMetrics
                Fit.Window(Fit.Cover.scale(
                    Size(dm.widthPixels / dm.density, dm.heightPixels / dm.density), Paper.SHEET.size))
            }
            else -> Fit.Cover
        }

    // MARK: Drawing it by hand, for the Views and bitmaps

    /**
     * Aspect-fill multiply over what is already in `canvas` — draw the colour
     * first, into the same canvas. `rect` in pixels; `density` is pixels per
     * point for that canvas.
     */
    fun draw(canvas: Canvas, rect: RectF, density: Float = Style.density) {
        val bitmap = paper.bitmap ?: return
        if (rect.width() <= 0 || rect.height() <= 0) return
        val surfacePts = Size(rect.width() / density, rect.height() / density)
        val scale = fit.scale(surfacePts, paper.size) * density
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val dst = RectF(rect.centerX() - w / 2, rect.centerY() - h / 2,
            rect.centerX() + w / 2, rect.centerY() + h / 2)
        canvas.save()
        canvas.clipRect(rect)
        canvas.drawBitmap(bitmap, null, dst, multiplyPaint(strength))
        canvas.restore()
    }

    companion object {
        fun multiplyPaint(alpha: Float) = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            blendMode = AndroidBlendMode.MULTIPLY
            this.alpha = (alpha * 255).roundToInt()
        }
    }
}

/** The surface's paper multiplied over whatever this scope has drawn so far. */
fun DrawScope.drawPaper(surface: Surface) {
    val image = surface.paper.image ?: return
    val pts = Size(size.width / density, size.height / density)
    val scale = surface.fit.scale(pts, surface.paper.size) * density
    val w = image.width * scale
    val h = image.height * scale
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        alpha = surface.strength,
        blendMode = BlendMode.Multiply,
    )
}

/**
 * Colour, then paper, then clip — the order every raised surface takes, or the
 * grain squares off behind the corner radius. The iPhone app's
 * `color.papered(surface).clipShape(shape)` in one modifier.
 */
fun Modifier.paper(color: Color, surface: Surface, shape: Shape = RectangleShape): Modifier =
    drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        if (outline is Outline.Rectangle) {
            drawRect(color)
            drawPaper(surface)
        } else {
            clipPath(path) {
                drawRect(color)
                drawPaper(surface)
            }
        }
    }

/**
 * Cards made of the page's own paper, a shade deeper: the sheet with the
 * page's grain, and the mood's panel colour multiplied into it — a multiply
 * darkens the grain along with the paper, where a plain layer of colour laid
 * over it washed the grain out. Search and the settings rows take it at 0.7;
 * the onboarding letter, which should read as fresh paper, only faintly.
 */
fun Modifier.pagePaper(tint: Float = 0.7f, shape: Shape = RectangleShape): Modifier =
    drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        clipPath(path) {
            drawRect(sheetColor)
            drawPaper(Surface.PAGE_CARD)
            drawRect(panelColor.copy(alpha = tint), blendMode = BlendMode.Multiply)
        }
    }

/** A full-bleed sheet of the page. */
@Composable
fun PageSheet(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().paper(sheetColor, Surface.PAGE))
}

/** A bitmap's whole area, for drawing into. */
fun Bitmap.bounds() = Rect(0, 0, width, height)
