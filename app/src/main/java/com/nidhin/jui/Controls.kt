package com.nidhin.jui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// MARK: - Glyphs
//
// The few marks the chrome needs, drawn rather than taken from an icon font, so
// they sit in the ink like everything else. Drawn in a 24-unit box.

enum class Glyph { SEARCH, PLUS, FOLDER, CHEVRONS }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Float = 16f, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size.dp)) {
        val u = this.size.width / 24f
        val stroke = Stroke(width = 2.2f * u, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.SEARCH -> {
                drawCircle(color, radius = 7.5f * u, center = Offset(10f * u, 10f * u), style = stroke)
                drawLine(color, Offset(15.5f * u, 15.5f * u), Offset(21.5f * u, 21.5f * u), 2.4f * u, StrokeCap.Round)
            }
            Glyph.PLUS -> {
                drawLine(color, Offset(12f * u, 3f * u), Offset(12f * u, 21f * u), 2.2f * u, StrokeCap.Round)
                drawLine(color, Offset(3f * u, 12f * u), Offset(21f * u, 12f * u), 2.2f * u, StrokeCap.Round)
            }
            Glyph.FOLDER -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(2.5f * u, 6f * u); lineTo(9f * u, 6f * u); lineTo(11f * u, 8.5f * u)
                    lineTo(21.5f * u, 8.5f * u); lineTo(21.5f * u, 19.5f * u); lineTo(2.5f * u, 19.5f * u); close()
                }
                drawPath(p, color, style = Stroke(width = 2f * u, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            Glyph.CHEVRONS -> {
                drawLine(color, Offset(7f * u, 9f * u), Offset(12f * u, 4.5f * u), 2.2f * u, StrokeCap.Round)
                drawLine(color, Offset(12f * u, 4.5f * u), Offset(17f * u, 9f * u), 2.2f * u, StrokeCap.Round)
                drawLine(color, Offset(7f * u, 15f * u), Offset(12f * u, 19.5f * u), 2.2f * u, StrokeCap.Round)
                drawLine(color, Offset(12f * u, 19.5f * u), Offset(17f * u, 15f * u), 2.2f * u, StrokeCap.Round)
            }
        }
    }
}

// MARK: - Icon buttons

// The page's buttons: a faint glyph on bare sheet at rest, firming up on a
// papered tile while pressed or while whatever it opens is open. 44 across
// with a 10 radius, the Mac's own size — which is also a finger's.
@Composable
fun IconButton(glyph: Glyph, label: String, active: Boolean = false, action: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val raised = active || pressed
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(44.dp)
            .then(if (raised) Modifier.paper(panelColor, Surface.BUTTON, shape) else Modifier)
            .clickable(interaction, indication = null, role = Role.Button, onClick = action)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (raised) inkStrong else inkFaint, 16f)
    }
}

/** True while a press is down, for the few styles that change under the finger. */
@Composable
fun rememberPress(): Pair<MutableInteractionSource, Boolean> {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return interaction to pressed
}

// MARK: - The row of mood dots
//
// Each dot is its theme's sticky colour, edged in that theme's open-note dot —
// a shade darker than the fill, so every dot has a definite edge against the
// paper, the pale ones especially. The active one is drawn larger rather than
// ringed. Tapping applies the theme immediately — there is no confirm step.
//
// The tap target is wider and taller than the dot; the spacing is folded into
// each target rather than laid between them.
@Composable
fun ThemePicker(store: NotesStore, diameter: Float = 24f, spacing: Float = 18f) {
    Row {
        for (theme in Themes.all) {
            val chosen = store.themeID == theme.id
            val scale by animateFloatAsState(if (chosen) SELECTED_SCALE else 1f, tween(150), label = "dot")
            Box(
                Modifier
                    .width((diameter + spacing).dp)
                    .height(maxOf(44f, diameter * SELECTED_SCALE).dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { store.setTheme(theme) }
                    .semantics { contentDescription = theme.label; selected = chosen },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(diameter.dp)
                        .scale(scale)
                        .background(theme.sticky.compose(), CircleShape)
                        .border(maxOf(1f, diameter * 0.05f).dp, theme.openDot.compose(), CircleShape))
            }
        }
    }
}

// The chosen mood, grown in place: its neighbours don't move.
const val SELECTED_SCALE = 1.4f

// MARK: - House text field
//
// The app's only single-line field: onboarding name, settings name, search,
// folder name, folder rename. Typewriter input (no autocorrect), the house
// family through the same resolution as everything else, and submit and
// end-of-editing callbacks.
//
// A tap anywhere outside the field ends its editing, as tapping away from a
// field does on the Mac (see `OutsideTap`). `onEndEditing` is where a name is
// committed or dropped: Return only ends editing, so it happens in one place.
@Composable
fun HouseField(
    text: String,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
    size: Float = 13f,
    alpha: Float = 1f, // which step of the ink ladder the text sits at
    centered: Boolean = false,
    placeholder: String = "",
    // inkFaint for every field but search, whose prompt is the muted step
    placeholderAlpha: Float = 0.4f,
    focused: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    onSubmit: () -> Unit = {},
    onEndEditing: (String) -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val latestText by rememberUpdatedState(text)
    val end by rememberUpdatedState(onEndEditing)
    val style = houseFont(size, brownInk.copy(alpha = alpha)).copy(
        textAlign = if (centered) TextAlign.Center else TextAlign.Start)
    // Only on the false → true transition, so a recomposition can't steal
    // focus back after someone has tapped away.
    LaunchedEffect(focused) { if (focused) runCatching { focus.requestFocus() } }
    BasicTextField(
        value = text,
        onValueChange = onText,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(brownInk),
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onSubmit() }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onGloballyPositioned { bounds = it.boundsInWindow(); if (hasFocus) OutsideTap.field = bounds }
            .onFocusChanged { state ->
                if (state.isFocused && !hasFocus) {
                    hasFocus = true
                    OutsideTap.field = bounds
                } else if (!state.isFocused && hasFocus) {
                    hasFocus = false
                    if (OutsideTap.field == bounds) OutsideTap.field = null
                    end(latestText)
                }
            },
        decorationBox = { inner ->
            Box(contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
                if (text.isEmpty() && placeholder.isNotEmpty()) {
                    androidx.compose.material3.Text(
                        placeholder, style = houseFont(size, brownInk.copy(alpha = placeholderAlpha)).copy(
                            textAlign = if (centered) TextAlign.Center else TextAlign.Start),
                        modifier = Modifier.fillMaxWidth())
                }
                inner()
            }
        },
    )
}

/** Where the field being edited is, so a tap anywhere else can end it. */
object OutsideTap {
    var field: Rect? = null
}

// MARK: - The stamp's buttons
//
// Delete and done: the hand-drawn marks from the Figma system (node 330:433),
// on a faint tile — a tone, not a colour: black at 6% is a soft step down from
// whatever it sits on in every theme. Tinted the ink's muted step, so they
// follow every theme as the ink does. Under the finger the mark settles a
// little.
@Composable
fun StampButton(delete: Boolean, action: () -> Unit) {
    val (interaction, pressed) = rememberPress()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(120), label = "press")
    val side = StickyCard.BUTTON_SIDE
    Box(
        Modifier
            .size(side.dp)
            .scale(scale)
            .background(Color.Black.copy(alpha = 0.06f), RoundedCornerShape((14f * side / 60f).dp))
            .clickable(interaction, indication = null, role = Role.Button, onClick = action)
            .semantics { contentDescription = if (delete) "Delete sticky note" else "Done" },
    ) {
        Image(
            painterResource(if (delete) R.drawable.stamp_delete else R.drawable.stamp_done),
            contentDescription = null,
            colorFilter = ColorFilter.tint(inkMuted),
            modifier = Modifier.size(side.dp),
        )
    }
}

// MARK: - The onboarding pill
//
// The mood's own colour — the one its dot shows — on the sticky paper, so the
// button follows the mood as it is picked. A step deeper while pressed; faded
// while there is nothing to go on with.
@Composable
fun OnboardingPill(title: String, enabled: Boolean = true, action: () -> Unit) {
    val (interaction, pressed) = rememberPress()
    val fill = if (pressed) blend(Style.theme.sticky, 0.15f, Style.theme.ink).compose() else stickyColor
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .height(40.dp)
            .then(Modifier.paper(fill.copy(alpha = 1f), Surface.STICKY, shape))
            .let { if (!enabled) it.background(sheetColor.copy(alpha = 0.55f), shape) else it }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            title,
            style = houseFont(14f, if (enabled) brownInk else inkFaint),
            modifier = Modifier.padding(horizontal = 28.dp),
        )
    }
}
