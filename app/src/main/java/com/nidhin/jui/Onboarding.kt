package com.nidhin.jui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

// First run, as drawn in Figma (node 243:120): a letter tucked into an
// envelope. The title shows through the flap's V; after the flourish draws
// itself the letter slides up out of the envelope to reveal the controls, and
// the remaining steps swap the letter's contents.
//
// The envelope's geometry is the design's own, in its own units (see D),
// multiplied by one scale factor so the composition keeps its proportions.
//
// As on the iPhone: the letter's contents are stacked rather than placed at
// the design's offsets (at phone scale the design's positions put one button
// on top of the next), and there is no vault step — there is nothing to choose.

private object D {
    const val ENV_W = 915f
    const val ENV_H = 593f
    const val NOTCH = 250f // depth of the flap's V, apex on centre

    const val LETTER_W = 882f
    const val LETTER_H = 777f
    const val LETTER_X = 16f
    const val LETTER_TUCKED = -184f // Screen One
    const val LETTER_RAISED = -465f // Screens Two–Four

    const val CONTENT_Y = 200f
    const val CONTENT_W = 640f

    const val SPRIG_W = 105.877f
    const val SPRIG_H = 158.092f
    const val SPRIG_GROWTH = 1.25f
    const val SPRIG_CX = 458f
    const val SPRIG_CY = 427f

    // tallest the composition ever gets: raised letter above the envelope
    const val ASSEMBLY_H = -LETTER_RAISED + ENV_H
}

// Fixed sizes from the app's own scale, held regardless of how small the
// envelope is drawn — scaling type with it would make the title unreadable.
private object T {
    const val TITLE = 16f
    const val CONTROL = 14f
    const val BACK = 12f
    const val CONTROL_H = 40f
    const val CONTROL_RADIUS = 10f
    const val CONTROL_PAD_H = 20f
}

/** The envelope's front: a rectangle with a V taken out of its top. */
private fun envelopeFront(notch: Float) = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width / 2, notch)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}

private enum class Step { WELCOME, NAME, MOOD }

private val easeInOut = FastOutSlowInEasing
private val easeOut = LinearOutSlowInEasing
private val easeIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)

@Composable
fun OnboardingView(store: NotesStore) {
    var step by remember { mutableStateOf(Step.WELCOME) }
    var finishing by remember { mutableStateOf(false) }
    // On a replay the name is already known; seeding it means tapping straight
    // through changes nothing.
    var name by remember { mutableStateOf(store.displayName) }
    var nameFocused by remember { mutableStateOf(false) }
    val letter = remember { Animatable(D.LETTER_TUCKED) }
    val presence = remember { Animatable(0f) } // 0 = sunk, small and clear; 1 = here
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val keyboard = WindowInsets.ime.getBottom(density) > 0

    val canAdvance = when (step) {
        Step.WELCOME, Step.MOOD -> true
        Step.NAME -> name.isNotBlank()
    }

    LaunchedEffect(Unit) {
        if (prefersReducedMotion) {
            presence.snapTo(1f)
            letter.snapTo(D.LETTER_RAISED)
            return@LaunchedEffect
        }
        launch { presence.animateTo(1f, tween(550, easing = easeOut)) }
        // The letter waits for the flourish to finish drawing, timed off the
        // sequence's own length.
        delay(((Flourish.duration + 0.25f) * 1000).toLong())
        letter.animateTo(D.LETTER_RAISED, tween(750, easing = easeInOut))
    }

    fun go(next: Step) {
        if (next != Step.NAME) focus.clearFocus()
        step = next
        nameFocused = next == Step.NAME
    }

    // The flow closes the way it opened, in reverse: the letter slides back
    // into the envelope, then the envelope sinks away and shrinks as it fades.
    fun finish() {
        if (finishing) return
        store.setDisplayName(name)
        focus.clearFocus()
        if (prefersReducedMotion) {
            store.completeOnboarding()
            return
        }
        finishing = true
        nameFocused = false
        scope.launch {
            letter.animateTo(D.LETTER_TUCKED, tween(700, easing = easeInOut))
            presence.animateTo(0f, tween(500, easing = easeIn))
            store.completeOnboarding()
        }
    }

    fun advance() {
        if (!canAdvance) return
        when (step) {
            Step.WELCOME -> go(Step.NAME)
            Step.NAME -> { store.setDisplayName(name); go(Step.MOOD) }
            Step.MOOD -> finish()
        }
    }

    fun back() = when (step) {
        Step.WELCOME -> {}
        Step.NAME -> go(Step.WELCOME)
        Step.MOOD -> go(Step.NAME)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // It also swallows every tap, so nothing behind can be reached
            // until this is finished.
            .background(sheetColor.copy(alpha = 0.8f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { focus.clearFocus() },
    ) {
        // One factor for the whole scene; on a phone it is width that binds.
        val w = maxWidth.value
        val h = maxHeight.value
        val s = max(min(w * 0.94f / D.ENV_W, h * 0.84f / D.ASSEMBLY_H), 0.2f)
        val p = presence.value
        // While the name is being typed the keyboard takes the bottom of the
        // screen; the envelope steps up out of its way.
        val lift = if (step == Step.NAME && keyboard) -h * 0.12f else 0f
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 0.5f + 0.5f * p
                    scaleX = scale
                    scaleY = scale
                    translationY = (h * 0.55f * (1 - p) + lift) * density.density
                    alpha = p
                },
            contentAlignment = Alignment.Center,
        ) {
            // Keeps the letter-plus-envelope composition centred, which is why
            // the envelope drifts down as the letter rises.
            Box(Modifier.offset(y = (-letter.value * s / 2).dp).size((D.ENV_W * s).dp, (D.ENV_H * s).dp)) {
                // the envelope's inside, visible once the letter has risen past it
                Box(Modifier.fillMaxSize().paper(envelopeColor, Surface.ENVELOPE))

                // The letter: the notes' own paper, faintly tinted — the first
                // sheet of the pad.
                Box(
                    Modifier
                        .offset((D.LETTER_X * s).dp, (letter.value * s).dp)
                        // Unbounded and pinned to the top: the letter is taller
                        // than the envelope it sits in, and a plain size is cut
                        // down to the envelope's — which left "back" hanging
                        // off the foot of the letter.
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .size((D.LETTER_W * s).dp, (D.LETTER_H * s).dp)
                        .pagePaper(tint = 0.3f),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        // Unbounded: the controls keep a finger's size however
                        // small the letter is drawn.
                        Modifier.padding(top = (D.CONTENT_Y * s).dp).width((D.CONTENT_W * s).dp)
                            .wrapContentHeight(Alignment.Top, unbounded = true)
                            .alpha(if (finishing) 0f else 1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AnimatedContent(step, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                            label = "step") { shown ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                when (shown) {
                                    Step.WELCOME -> Title("a calm place\nto write")
                                    Step.NAME -> {
                                        Title("let’s start\nwith your name,")
                                        Spacer(Modifier.height(16.dp))
                                        Box(
                                            Modifier.width((D.CONTENT_W * s).dp).height(T.CONTROL_H.dp)
                                                .background(sheetColor, RoundedCornerShape(T.CONTROL_RADIUS.dp))
                                                .padding(horizontal = T.CONTROL_PAD_H.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            HouseField(
                                                name, { name = it }, size = T.CONTROL, centered = true,
                                                placeholder = "your name", focused = nameFocused,
                                                imeAction = ImeAction.Next,
                                                capitalization = KeyboardCapitalization.Words,
                                                onSubmit = { if (canAdvance) advance() },
                                            )
                                        }
                                    }
                                    Step.MOOD -> {
                                        Title("pick your\nmood")
                                        Spacer(Modifier.height(12.dp))
                                        ThemePicker(store, diameter = 24f, spacing = 14f)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(if (step == Step.WELCOME) 34.dp else 20.dp))
                        OnboardingPill(if (step == Step.WELCOME) "begin" else "next", canAdvance, ::advance)
                        if (step != Step.WELCOME) {
                            Box(
                                Modifier.height(40.dp)
                                    .clickable(remember { MutableInteractionSource() }, indication = null) { back() }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("back", style = houseFont(T.BACK, inkMuted)) }
                        }
                    }
                }

                // Papered then clipped: the paper has to stop at the flap's V.
                // Scenery, so it takes no taps.
                Box(Modifier.fillMaxSize().paper(envelopeColor, Surface.ENVELOPE, envelopeFront(D.NOTCH * s * density.density)))

                val flourishH = D.SPRIG_H * D.SPRIG_GROWTH * s
                AnimatedFlourish(
                    Modifier
                        .offset(((D.SPRIG_CX - D.SPRIG_W / 2) * s).dp, ((D.SPRIG_CY - D.SPRIG_H / 2) * s).dp)
                        .size((flourishH * Flourish.aspect(FlourishSet.CORNER)).dp, flourishH.dp),
                    tint = flourishColor, play = Play.Always,
                )
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = houseFont(T.TITLE, brownInk).copy(textAlign = TextAlign.Center, lineHeight = pts(20f)))
}

