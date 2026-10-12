package com.nidhin.jui

import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

// Where you are: the home screen, a folder, or a note.
sealed class Route {
    data class Folder(val name: String) : Route()
    data object Note : Route()
}

private val searchCurve = CubicBezierEasing(0f, 0f, 0.35f, 1f)

@Composable
fun JuiApp(store: NotesStore) {
    val path = remember { mutableStateListOf<Route>() }
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyboardShown = WindowInsets.ime.getBottom(density) > 0

    fun goHome() {
        path.clear()
    }

    // Leaving a note — by the back gesture or the flourish — saves it.
    LaunchedEffect(Unit) {
        var wasNote = false
        snapshotFlow { path.lastOrNull() == Route.Note }.collect { isNote ->
            if (wasNote && !isNote) store.saveNow()
            wasNote = isNote
        }
    }

    // The sticky's card comes and goes through the crumpled sheet: put away,
    // it crushes back into its ball; opened, it lifts and opens out flat into
    // the place the card will take.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val areaW = maxWidth.value
        val areaH = maxHeight.value
        val statusBar = WindowInsets.statusBars.getTop(density) / density.density
        val navBar = WindowInsets.navigationBars.getBottom(density) / density.density
        LaunchedEffect(Unit) {
            var old: String? = null
            snapshotFlow { store.openStickyID }.collect { new ->
                val was = old
                old = new
                if (was != null && was != new) {
                    CrumpleDirector.close(was, store.stickies.firstOrNull { it.id == was }?.text)
                }
                if (new != null && new != was) {
                    // Opened to read, keyboard down: where the card will sit.
                    val text = store.stickies.firstOrNull { it.id == new }?.text ?: ""
                    CrumpleDirector.open(new, text,
                        StickyCard.stampFrame(text, areaW, areaH, statusBar, navBar, editing = false))
                }
            }
        }

        // Everything recedes behind search and onboarding, so the card reads
        // as above the sheet rather than on it.
        val blur by animateDpAsState(
            if (store.searchShown || store.onboardingShown) 8.dp else 0.dp, tween(600, easing = searchCurve), label = "blur")
        val holder = rememberSaveableStateHolder()
        Box(Modifier.fillMaxSize().blur(blur).outsideTapEndsEditing { focus.clearFocus() }) {
            AnimatedContent(
                targetState = path.toList(),
                transitionSpec = {
                    if (targetState.size >= initialState.size) {
                        slideInHorizontally(tween(320)) { it } togetherWith slideOutHorizontally(tween(320)) { -it / 3 }
                    } else {
                        slideInHorizontally(tween(320)) { -it / 3 } togetherWith slideOutHorizontally(tween(320)) { it }
                    }
                },
                label = "stack",
            ) { stack ->
                val top = stack.lastOrNull()
                holder.SaveableStateProvider(stack.joinToString("/") { it.toString() }) {
                    when (top) {
                        Route.Note -> NoteScreen(store)
                        is Route.Folder -> Home(store, top.name, path)
                        null -> Home(store, null, path)
                    }
                }
            }
        }

        AnimatedVisibility(store.hiddenShown, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            SettingsPage(store)
        }

        // One flourish over every screen, so it holds still in its corner
        // while pages slide past beneath it. It steps aside while the keyboard
        // is up, when the foot of the screen belongs to the text, and while a
        // sticky is open.
        val flourishShown = Flourish.frames(FlourishSet.CORNER).isNotEmpty() && !store.onboardingShown &&
            store.openStickyID == null && !store.searchShown && (!keyboardShown || store.hiddenShown)
        AnimatedVisibility(flourishShown, Modifier.align(Alignment.BottomStart),
            enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            CornerFlourish(store, path.isEmpty()) {
                focus.clearFocus()
                hideKeyboardIn(context)
                when {
                    store.hiddenShown -> store.hiddenShown = false
                    path.isNotEmpty() -> goHome() // leaving a note saves it
                    else -> store.hiddenShown = true
                }
            }
        }

        // The scrim and the card come and go separately: the page dims with a
        // plain fade while the sticky unfolds from its ball on the stage above
        // it, and the real card takes over once it lies flat.
        AnimatedVisibility(store.openStickyID != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(sheetColor.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    hideKeyboardIn(context)
                    store.closeSticky()
                })
        }
        CrumpleStage()
        CrumpleDirector.cardID?.let { id ->
            androidx.compose.runtime.key(id) { StickyCardView(store, id) }
        }

        // first run: above everything, and nothing behind it is reachable
        // until it completes
        AnimatedVisibility(store.onboardingShown, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            OnboardingView(store)
        }

        // Search: a dimmed page, and the palette sliding down from above the
        // screen and back up, solid all the way — a card arriving, not one
        // fading in.
        AnimatedVisibility(store.searchShown, enter = fadeIn(tween(600, easing = searchCurve)),
            exit = fadeOut(tween(600, easing = searchCurve))) {
            Box(Modifier.fillMaxSize().background(sheetColor.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    focus.clearFocus()
                    store.searchShown = false
                })
        }
        AnimatedVisibility(
            store.searchShown,
            Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(tween(600, easing = searchCurve)) { -(it + 200) },
            exit = slideOutVertically(tween(600, easing = searchCurve)) { -(it + 200) },
        ) {
            Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp)) {
                SearchPalette(store) { if (path.lastOrNull() != Route.Note) path.add(Route.Note) }
            }
        }
    }

    // The way back. Whatever is over the page goes first; then a note or a
    // folder; at home with nothing open, the app goes to the background.
    BackHandler(store.openStickyID != null || store.searchShown || store.hiddenShown || path.isNotEmpty()) {
        when {
            store.openStickyID != null -> { hideKeyboardIn(context); store.closeSticky() }
            store.searchShown -> { focus.clearFocus(); store.searchShown = false }
            store.hiddenShown -> store.hiddenShown = false
            else -> path.removeAt(path.lastIndex)
        }
    }
}

@Composable
private fun Home(store: NotesStore, location: String?, path: MutableList<Route>) {
    HomeScreen(
        store, location,
        openNote = { id -> store.open(id); path.add(Route.Note) },
        openFolder = { path.add(Route.Folder(it)) },
        newNote = { folder -> store.newNote(folder); path.add(Route.Note) },
    )
}

// MARK: - The corner flourish
//
// Decoration, and a way home. In a note or a folder it goes straight back to
// the home screen; on home it opens the hidden page, and on the hidden page it
// closes it again. Multiplied through the page, like every flourish in the
// app; it deepens a step while pressed. It draws itself on each time a note is
// opened — the key carries the document's load count — and once per opening.
@Composable
private fun CornerFlourish(store: NotesStore, atHome: Boolean, onTap: () -> Unit) {
    val (interaction, pressed) = rememberPress()
    val h = Style.CORNER_FLOURISH_HEIGHT
    val replay = "corner-${store.docVersion}"
    Box(
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(start = 12.dp, bottom = Style.CORNER_FLOURISH_LIFT.dp)
            .size((h * Flourish.aspect(FlourishSet.CORNER)).dp, h.dp)
            .clickable(interaction, indication = null, onClick = onTap)
            .semantics {
                contentDescription = if (store.hiddenShown) "Close settings" else if (atHome) "Settings" else "Home"
            },
    ) {
        androidx.compose.runtime.key(replay) {
            AnimatedFlourish(
                Modifier.fillMaxSize(),
                tint = if (pressed) flourishPressed else flourishRest,
                play = Play.OnceInSession(replay),
                blend = BlendMode.Multiply,
            )
        }
    }
}

// MARK: - The hidden page
//
// Dedication, mood, and the app's few settings. Papered like the page it
// covers, and closed by the same flourish that opened it.
@Composable
private fun SettingsPage(store: NotesStore) {
    var nameDraft by remember { mutableStateOf(store.displayName) }
    val focus = LocalFocusManager.current
    // Commit the name when the page goes, however it goes.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { store.setDisplayName(nameDraft) } }
    Box(Modifier.fillMaxSize().paper(sheetColor, Surface.PAGE)
        .clickable(remember { MutableInteractionSource() }, indication = null) { focus.clearFocus() }) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars)),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text(
                if (store.displayName.isEmpty()) "Juilee's personal writing pad <3"
                else "${store.displayName}'s personal writing pad <3",
                style = houseFont(16f).copy(textAlign = TextAlign.Center),
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(22.dp))
            ThemePicker(store)
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 110.dp),
            ) {
                // name — the same value onboarding collects
                SettingsRow("name") {
                    Box(Modifier.settingsWell()) {
                        HouseField(nameDraft, { nameDraft = it }, size = 13f, placeholder = "your name",
                            capitalization = KeyboardCapitalization.Words,
                            onSubmit = { store.setDisplayName(nameDraft); focus.clearFocus() },
                            onEndEditing = { store.setDisplayName(it) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Where the notes live. On this phone for now: there is no
                // shared folder to pick, as there is in the iPhone's iCloud Drive.
                SettingsRow("notes") {
                    Row(Modifier.settingsWell(), verticalAlignment = Alignment.CenterVertically) {
                        Text(store.vaultDisplay, style = houseFont(13f, inkStrong), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        GlyphIcon(Glyph.FOLDER, inkFaint, 12f)
                    }
                }
                Spacer(Modifier.height(10.dp))
                SettingsRow("font") {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        Row(Modifier.settingsWell().clickable(remember { MutableInteractionSource() }, indication = null) { open = true },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(store.fontFamily, style = houseFont(13f, inkStrong), modifier = Modifier.weight(1f))
                            GlyphIcon(Glyph.CHEVRONS, inkFaint, 12f)
                        }
                        HouseMenu(open, { open = false }) {
                            for ((family, _) in Style.offeredFamilies) {
                                HouseMenuItem(if (family == store.fontFamily) "$family  ✓" else family) {
                                    open = false
                                    store.setFont(family)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// A fixed-width label and a control filling the rest, on a card of the page's
// own paper, a shade deeper — the same as the search cards.
@Composable
private fun SettingsRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().pagePaper(shape = RoundedCornerShape(10.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = houseFont(12f, inkMuted), modifier = Modifier.width(44.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

// The recessed well a settings control sits in. Deliberately not papered: it
// is a hole in the card rather than another surface on top of it.
private fun Modifier.settingsWell(): Modifier = this
    .fillMaxWidth()
    .background(sheetColor, RoundedCornerShape(6.dp))
    .padding(horizontal = 10.dp, vertical = 9.dp)

// MARK: - A note
//
// The page and nothing else on it but the word count. The way back is the back
// gesture, or the corner flourish, which goes straight home.
@Composable
private fun NoteScreen(store: NotesStore) {
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    val keyboardShown = ime > 0
    Box(Modifier.fillMaxSize()) {
        // The paper, pinned to the screen. Grain is not something the eye
        // tracks, so a sheet that stays put while the words move over it reads
        // as a page being written on; the ruling, which the eye does track, is
        // drawn by the editor and travels with the text.
        PageSheet()
        val blur by animateDpAsState(if (store.openStickyID != null) 8.dp else 0.dp, tween(250), label = "blur")
        NoteEditor(store, Modifier.fillMaxSize().padding(bottom = with(density) { ime.toDp() }).blur(blur))
        if (store.openStickyID == null && !store.hiddenShown) {
            Text(
                "${store.wordCount}",
                style = houseFont(14f, inkFaint),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(end = 22.dp, bottom = if (keyboardShown) 14.dp else 16.dp)
                    .semantics { contentDescription = "${store.wordCount} words" },
            )
        }
    }
}

/** Keeps the editor in step with the store; one per editor view. */
private class EditorSync {
    var version = -1
    var theme = ""
    var family = ""
    var blocked: Boolean? = null
}

@Composable
private fun NoteEditor(store: NotesStore, modifier: Modifier) {
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.getTop(density).toFloat()
    val navBar = WindowInsets.navigationBars.getBottom(density).toFloat()
    val keyboardUp = WindowInsets.ime.getBottom(density) > 0
    val sync = remember { EditorSync() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            NoteScroll(ctx, store).also { scroll ->
                scroll.editor.ballArt = { CrumpleArt.marginBall() }
                CrumpleDirector.page = object : BallPage {
                    override fun markCentre(id: String): PointF? {
                        val (x, y) = scroll.editor.ballCentre(id) ?: return null
                        val at = IntArray(2)
                        scroll.editor.getLocationInWindow(at)
                        return PointF((at[0] + x) / Style.density, (at[1] + y) / Style.density)
                    }
                    override fun showFlight() { scroll.editor.flyingID = CrumpleDirector.flyingID }
                }
            }
        },
        update = { scroll ->
            val editor = scroll.editor
            scroll.statusBar = statusBar
            scroll.navBar = navBar
            scroll.keyboardUp = keyboardUp
            val blocked = store.editorBlocked
            if (sync.blocked != blocked) {
                sync.blocked = blocked
                editor.isFocusable = !blocked
                editor.isFocusableInTouchMode = !blocked
                if (blocked) { editor.clearFocus(); editor.hideKeyboard() }
            }
            if (sync.theme != store.themeID) {
                sync.theme = store.themeID
                editor.applyTheme()
            }
            sync.family = store.fontFamily
            if (sync.version != store.docVersion) {
                sync.version = store.docVersion
                editor.load(store.attr)
                // Where a note arrives: at a search hit if it came from search,
                // with the keyboard up if it is new, and otherwise at the end
                // of what was written with the keyboard down — reading first,
                // until the page is tapped.
                val reveal = store.pendingReveal
                store.pendingReveal = null
                val focusNow = store.focusOnOpen
                store.focusOnOpen = false
                editor.post {
                    val length = editor.text?.length ?: 0
                    if (reveal != null) {
                        editor.showKeyboard()
                        editor.setSelection(reveal.first.coerceIn(0, length), (reveal.last + 1).coerceIn(0, length))
                    } else {
                        editor.setSelection(length)
                        if (focusNow) editor.showKeyboard()
                    }
                    scroll.post { scroll.scrollToCaret() }
                }
            }
            // read so a change to the stickies redraws their balls and wash
            store.stickies.size
            editor.syncStickies()
        },
    )
}

/** A tap anywhere outside the field being edited ends its editing. */
private fun Modifier.outsideTapEndsEditing(end: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val field = OutsideTap.field ?: return@awaitEachGesture
        if (!field.contains(down.position)) end()
    }
}

fun hideKeyboardIn(context: android.content.Context) {
    val activity = context as? android.app.Activity ?: return
    val view = activity.currentFocus ?: activity.window.decorView
    view.clearFocus()
    context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        ?.hideSoftInputFromWindow(view.windowToken, 0)
}
