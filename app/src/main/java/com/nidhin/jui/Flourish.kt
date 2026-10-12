package com.nidhin.jui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Three sets ship: the same drawing at the three angles the artwork uses —
// upright for the page corner, rotated for folder tiles and for stickies.
// Frames are white silhouettes tinted at draw time, so one set serves all six
// themes.
enum class FlourishSet(val dir: String) { CORNER("corner"), FOLDER("folder"), STICKY("sticky") }

/** When a flourish draws itself is the caller's decision, not the view's. */
sealed class Play {
    /** Already there. Show the finished drawing and never animate. */
    data object Never : Play()
    /** Draws itself the first time this key appears, then holds. */
    data class OnceInSession(val key: String) : Play()
    /** Draws itself every time it appears. */
    data object Always : Play()
}

object Flourish {
    private val cache = HashMap<FlourishSet, List<Bitmap>>()
    private val images = HashMap<FlourishSet, List<ImageBitmap>>()
    internal val played = HashSet<String>()

    // Counted off what actually shipped, sorted by the number in the name (not
    // the string), so a re-export of a different length just works.
    fun frames(set: FlourishSet): List<Bitmap> = cache.getOrPut(set) {
        val dir = "FlourishFrames/${set.dir}"
        val names = Style.app.assets.list(dir).orEmpty().filter { it.endsWith(".png") }
        names.sortedBy { n -> n.filter(Char::isDigit).toIntOrNull() ?: 0 }
            .mapNotNull { n -> Style.app.assets.open("$dir/$n").use { BitmapFactory.decodeStream(it) } }
    }

    fun images(set: FlourishSet): List<ImageBitmap> =
        images.getOrPut(set) { frames(set).map { it.asImageBitmap() } }

    /** Width over height, taken from the artwork rather than written down. */
    fun aspect(set: FlourishSet): Float =
        frames(set).firstOrNull()?.let { it.width.toFloat() / it.height } ?: 1f

    // 30 frames over 2.5s in the source, which is 12fps.
    const val FRAME_MILLIS = 1000L / 12

    /** How long the sequence takes, in seconds, from the frames shipped. */
    val duration: Float get() = frames(FlourishSet.CORNER).size * FRAME_MILLIS / 1000f
}

/**
 * The flourish, stepped frame by frame — stop-motion is discrete, and
 * interpolating between frames would take the character out of it.
 * `blend` is how it lands on what is beneath: multiply, as every flourish in
 * the app is drawn *through* its surface, or plain over.
 */
@Composable
fun AnimatedFlourish(
    modifier: Modifier = Modifier,
    set: FlourishSet = FlourishSet.CORNER,
    tint: Color,
    play: Play,
    blend: BlendMode = BlendMode.SrcOver,
) {
    val all = remember(set) { Flourish.images(set) }
    val last = (all.size - 1).coerceAtLeast(0)
    var index by remember(play) {
        mutableIntStateOf(
            when (play) {
                Play.Never -> last
                Play.Always -> 0
                is Play.OnceInSession -> if (play.key in Flourish.played) last else 0
            })
    }
    LaunchedEffect(play) {
        if (all.size <= 1) return@LaunchedEffect
        when (play) {
            Play.Never -> return@LaunchedEffect
            is Play.OnceInSession -> {
                if (play.key in Flourish.played) return@LaunchedEffect
                Flourish.played += play.key
            }
            Play.Always -> {}
        }
        if (prefersReducedMotion) {
            index = last
            return@LaunchedEffect
        }
        index = 0
        while (index < last) {
            delay(Flourish.FRAME_MILLIS)
            index += 1
        }
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val frame = all.getOrNull(index) ?: return@Canvas // no frames: nothing, not a box
        drawImage(
            frame,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(frame.width, frame.height),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
            blendMode = blend,
        )
    }
}
