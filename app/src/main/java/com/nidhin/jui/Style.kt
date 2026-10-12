package com.nidhin.jui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat

// MARK: - Colour tokens
//
// Every colour token reads through the active theme, so changing theme repaints
// the whole app without a single call site knowing about it. Never write a
// colour literal outside Theme.kt; the tones (black at a fraction) are the only
// exceptions, because a tone is right in all six themes at once.
//
// The theme is Compose state, so anything that reads a token while composing
// is redrawn when the mood changes — the iPhone app needed `var theme =
// Style.theme.id` on every style to get the same effect; here it comes free.

val sheetColor: Color get() = Style.theme.sheet.compose()
val panelColor: Color get() = Style.theme.panel.compose()
val openDotColor: Color get() = Style.theme.openDot.compose()
val flourishColor: Color get() = Style.theme.flourish.compose()
val folderBaseColor: Color get() = Style.theme.folderBase.compose()
val stickyColor: Color get() = Style.theme.sticky.compose()
val envelopeColor: Color get() = Style.theme.envelope.compose()
val letterColor: Color get() = Style.theme.letterSheet.compose()

// The corner flourish is panel drawn *through* the page (multiplied), and
// deepens a step toward the ink while pressed.
val flourishRest: Color get() = panelColor
val flourishPressed: Color get() = blend(Style.theme.panel, 0.22f, Style.theme.ink).compose()

val brownInk: Color get() = Style.theme.ink.compose()

// The ink ladder — four steps, and only these four. Pick the step that matches
// the role; never reach for a fresh opacity.
val inkStrong: Color get() = brownInk.copy(alpha = 0.85f) // panel rows, settings values, folder names
val inkMuted: Color get() = brownInk.copy(alpha = 0.55f)  // labels, snippets, secondary rows
val inkFaint: Color get() = brownInk.copy(alpha = 0.4f)   // placeholders, empty states, icons, disabled

// The hairline inside a papered surface: black at 12% is a multiply by 0.88,
// so it darkens whatever it lands on and cannot invert.
val panelDividerColor = Color.Black.copy(alpha = 0.12f)

/**
 * Type, handled the way colour is: one function every call site reads
 * through, so the family chosen in settings reaches the chrome and not only
 * the page. The scale is 10 / 11 / 12 / 13 / 14 / 16; 16 is the largest text
 * in the app. Sizes are points (dp), not sp — the page is a ruled grid, and
 * the phone's text-size setting would walk the type off it.
 */
@Composable
fun houseFont(size: Float, color: Color = brownInk): TextStyle {
    val density = LocalDensity.current
    return TextStyle(
        fontFamily = Style.composeFamily,
        fontSize = (size / density.fontScale).sp,
        color = color,
    )
}

@Composable
fun pts(size: Float): TextUnit = (size / LocalDensity.current.fontScale).sp

// MARK: - Style

object Style {
    lateinit var app: Context
        private set
    var density = 2.625f
        private set

    fun init(context: Context) {
        app = context.applicationContext
        density = app.resources.displayMetrics.density
        val prefs = prefs()
        themeState.value = Themes.named(prefs.getString("themeID", null))
        familyState.value = prefs.getString("fontFamily", null) ?: DEFAULT_FONT_FAMILY
    }

    fun prefs() = app.getSharedPreferences("jui", Context.MODE_PRIVATE)

    /** Points (dp) to pixels. */
    fun px(points: Float): Float = points * density

    // The one piece of global state the colours hang off. Set on launch from
    // the preferences and by NotesStore.setTheme thereafter.
    val themeState = mutableStateOf(Themes.cream)
    val theme: Theme get() = themeState.value
    val ink: Int get() = theme.ink

    // MARK: Type
    //
    // User-selectable on the settings page. Courier Prime by default — the
    // face the Figma system is set in, bundled with the app. A phone has no
    // Courier; Cutive Mono (Android's own typewriter face) stands nearest.

    const val DEFAULT_FONT_FAMILY = "Courier Prime"
    val familyState = mutableStateOf(DEFAULT_FONT_FAMILY)
    val fontFamily: String get() = familyState.value

    // The body size, and the top of the type scale.
    const val BASE_SIZE = 16f

    /**
     * The families offered in settings — what every Android phone carries that
     * suits a writing pad, by the name shown, mapped to the system's own name.
     */
    val offeredFamilies: List<Pair<String, String?>> = listOf(
        "Courier Prime" to null, // bundled
        "Cutive Mono" to "serif-monospace",
        "Droid Sans Mono" to "monospace",
        "Noto Serif" to "serif",
        "Roboto" to "sans-serif",
        "Coming Soon" to "casual",
        "Dancing Script" to "cursive",
    )

    private val typefaces = HashMap<String, Typeface>()

    /** The regular face of `family`, falling back to Courier Prime. */
    fun typeface(family: String = fontFamily): Typeface = typefaces.getOrPut(family) {
        val system = offeredFamilies.firstOrNull { it.first == family }?.second
        if (system != null) Typeface.create(system, Typeface.NORMAL)
        else ResourcesCompat.getFont(app, R.font.courier_prime) ?: Typeface.MONOSPACE
    }

    /** The same family with `style` (Typeface.ITALIC, BOLD, BOLD_ITALIC). */
    fun typeface(style: Int, family: String = fontFamily): Typeface =
        if (style == Typeface.NORMAL) typeface(family) else Typeface.create(typeface(family), style)

    private val composeFamilies = HashMap<String, FontFamily>()
    val composeFamily: FontFamily
        get() = fontFamily.let { f -> composeFamilies.getOrPut(f) { FontFamily(typeface(f)) } }

    /** A paint set in the house family at `size` points. */
    fun paint(size: Float = BASE_SIZE, style: Int = Typeface.NORMAL): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(style)
            textSize = px(size)
        }

    // MARK: The page column
    //
    // The column is set by what a phone can hold — about 35 characters of
    // Courier 16 — and the ruling's overhang past the text shrinks with it, so
    // the rules still run wider than the words and stop short of the glass.
    const val SIDE_MARGIN = 30f
    const val RULE_OVERHANG = 18f
    // The first `BACK_BAND` points under the status bar are left clear and the
    // text fades out through them as it scrolls up.
    const val TOP_INSET = 52f
    const val BACK_BAND = 44f

    // MARK: The corner flourish
    const val CORNER_FLOURISH_HEIGHT = 92f
    const val CORNER_FLOURISH_LIFT = 4f
    // Clear paper between the last visible words and the flourish's top.
    const val CORNER_FLOURISH_CLEARANCE = 20f

    // MARK: Ruled paper
    //
    // Three times the type size. Held as the *pitch*, with each line's height
    // forced to it, so the grid survives the font picker: a family with a
    // different natural line height is centred in the same band and still
    // lands on the same rules.
    const val LINE_PITCH = 48f

    data class Metrics(val ascent: Float, val descent: Float) {
        /** The natural line, top of the ascent to the bottom of the descent. */
        val lineHeight get() = descent - ascent
    }

    /** The chosen family's metrics at the body size, in pixels (ascent < 0). */
    val metrics: Metrics
        get() {
            val fm = paint().fontMetrics
            return Metrics(fm.ascent, fm.descent)
        }

    /** Where the ink sits in a pitch band: centred, as TextKit centres it. */
    val inkHeight: Float get() = metrics.lineHeight

    // How far a selection reaches past the ink it covers, top and bottom.
    const val SELECTION_PADDING = 5f
    // How far the caret reaches past the type, top and bottom.
    const val CARET_OVERSHOOT = 2f

    // Black at 10% over a colour is the same arithmetic as multiplying it by
    // 0.9 — so the ruling is drawn as plain black at 10%. One value, right in
    // all six themes and over the grain.
    const val RULE_TONE = 0x1A000000

    // Horizontal rules are gone; the marker survives only so a note that
    // already contains one opens as a blank line.
    const val HR_MARKER = " "
    const val BULLET_PREFIX = "• "

    val bulletIndent: Float get() = paint().measureText(BULLET_PREFIX)

    /** Where a rule lands given the y it would ideally sit at — on a pixel. */
    fun ruleY(y: Float): Float = Math.round(y).toFloat() + 0.5f
}

/** True when the phone's animations are turned off. */
val prefersReducedMotion: Boolean
    get() = Settings.Global.getFloat(
        Style.app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

// MARK: - Folder tile metrics
//
// The air a folder tile keeps above its name: the old sprig's metrics, kept
// because the tile height — and so the grid — is computed from them.
const val FOLDER_MARK_HEIGHT = 32f
const val FOLDER_MARK_WIDTH = 32f * (181f / 271f)
const val FOLDER_MARK_GAP = 14f
const val FOLDER_TITLE_HEIGHT = 16f
const val FOLDER_CELL_PADDING = 9f
const val FOLDER_CELL_HEIGHT =
    FOLDER_MARK_HEIGHT + FOLDER_MARK_GAP + FOLDER_TITLE_HEIGHT + FOLDER_CELL_PADDING * 2
