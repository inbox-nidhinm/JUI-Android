package com.nidhin.jui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The home screen: every note and folder, on the same paper as the page.
// It replaces the Mac's past-notes popover — on a phone the list is where you
// start, and a note is somewhere you go.
//
// At the root, folders sit two-per-row above the notes, with an add tile
// flowing after them. A folder opens as the same screen standing inside it.
// Long-press for the Mac's right-click menus — move a note to a folder there.
//
// Search and a new note sit at the foot, where the Mac keeps its controls;
// the corner flourish (drawn by the app, over every screen) opens the hidden
// page, as it does on a note.
@Composable
fun HomeScreen(
    store: NotesStore,
    location: String?, // null = the root, else the folder this screen stands in
    openNote: (String) -> Unit,
    openFolder: (String) -> Unit,
    newNote: (String?) -> Unit,
) {
    var renamingFolder by remember { mutableStateOf<String?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderDraft by remember { mutableStateOf("") }
    var confirmingDelete by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current

    val visibleNotes = store.notes.filter { it.folder == location }

    // Naming ends however editing ends — Return, a tap anywhere else, or
    // leaving the screen — and it ends one way: a name makes the folder, no
    // name means it was never made.
    fun finishCreatingFolder(name: String) {
        if (!creatingFolder) return
        creatingFolder = false
        if (name.isNotBlank()) store.createFolder(name)
        folderDraft = ""
    }

    // A rename left empty, or unchanged, keeps the old name.
    fun finishRenaming(folder: String, name: String) {
        if (renamingFolder != folder) return
        renamingFolder = null
        val clean = name.trim()
        if (clean.isNotEmpty() && clean != folder) store.renameFolder(folder, clean)
        folderDraft = ""
    }

    // Belt and braces for the end-of-editing path: whatever happens, coming
    // back never finds a half-named folder waiting.
    DisposableEffect(Unit) {
        onDispose {
            if (creatingFolder) finishCreatingFolder(folderDraft)
            renamingFolder?.let { finishRenaming(it, folderDraft) }
        }
    }

    // Pull down to search. `pull` is how far the list is drawn past its top;
    // search opens once it passes PULL_TO_SEARCH, once per pull — it re-arms
    // only when the list has settled back.
    val scroll = rememberScrollState()
    val pull = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val density = Style.density
    val pullConnection = remember(store) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Pushing back up while pulled: take the pull back first.
                if (available.y < 0 && pull.value > 0) {
                    val take = max(available.y, -pull.value * density)
                    scope.launch { pull.snapTo(pull.value + take / density) }
                    return Offset(0f, take)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    val next = pull.value + available.y / density * 0.5f // resistance
                    scope.launch { pull.snapTo(next) }
                    if (armed && next >= PULL_TO_SEARCH && !store.searchShown) {
                        armed = false
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        store.openSearch()
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.value > 0) {
                    pull.animateTo(0f, tween(250))
                    armed = true
                    return available
                }
                armed = true
                return Velocity.Zero
            }
        }
    }
    val pullProgress = min(1f, pull.value / PULL_TO_SEARCH)

    Box(Modifier.fillMaxSize()) {
        PageSheet()
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            // Revealed above the list as it is pulled down, firming up as the
            // pull nears the point where search opens.
            GlyphIcon(Glyph.SEARCH, inkFaint, 16f,
                Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    .scale(0.7f + 0.3f * pullProgress).alpha(pullProgress))

            Column(
                Modifier
                    .fillMaxSize()
                    .listMask()
                    .nestedScroll(pullConnection)
                    .verticalScroll(scroll)
                    .offset { IntOffset(0, (pull.value * density).roundToInt()) }
                    // clear of the flourish and the controls at the foot
                    .padding(bottom = 150.dp),
            ) {
                Header(store, location)
                if (location == null) {
                    if (creatingFolder) {
                        Box(
                            Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp).fillMaxWidth()
                                .paper(folderBaseColor, Surface.FOLDER_TILE, RoundedCornerShape(6.dp))
                                .padding(vertical = 12.dp, horizontal = 14.dp)
                        ) {
                            HouseField(
                                folderDraft, { folderDraft = it }, size = 13f, alpha = 0.85f,
                                placeholder = "folder name", focused = true,
                                onEndEditing = ::finishCreatingFolder,
                            )
                        }
                    }
                    FolderGrid(
                        store, renamingFolder, folderDraft, { folderDraft = it },
                        onOpen = { if (renamingFolder != it) openFolder(it) },
                        onAdd = { folderDraft = ""; creatingFolder = true },
                        onRename = { folderDraft = it; renamingFolder = it },
                        onDelete = { folder ->
                            if (store.notes.any { it.folder == folder }) confirmingDelete = folder
                            else store.deleteFolder(folder)
                        },
                        onRenamed = ::finishRenaming,
                    )
                    if (visibleNotes.isNotEmpty()) {
                        Box(Modifier.padding(horizontal = 26.dp, vertical = 14.dp).fillMaxWidth().height(1.dp)
                            .background(panelDividerColor))
                    }
                }
                if (visibleNotes.isEmpty() && (location != null || store.folders.isEmpty())) {
                    Text("no notes yet", style = houseFont(13f, inkFaint),
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 32.dp))
                }
                noteDays(visibleNotes).forEachIndexed { index, (day, notes) ->
                    if (index > 0) {
                        Box(Modifier.padding(horizontal = 26.dp, vertical = 12.dp).fillMaxWidth().height(1.dp)
                            .background(panelDividerColor))
                    }
                    Text(dayName(day), style = houseFont(12f, inkMuted),
                        modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 6.dp, bottom = 4.dp)
                            .semantics { heading() })
                    for (note in notes) NoteRow(store, note, openNote)
                }
            }

            // Search and a new note — the new note lands in this folder, or
            // unfiled at the root.
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(Glyph.SEARCH, "Search") { store.openSearch() }
                IconButton(Glyph.PLUS, location?.let { "New note in $it" } ?: "New note") { newNote(location) }
            }
        }
    }

    confirmingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            containerColor = sheetColor,
            title = { Text("Delete “$name” and the notes in it?", style = houseFont(16f)) },
            text = { Text("There is no trash — this can't be undone.", style = houseFont(13f, inkMuted)) },
            confirmButton = {
                TextButton({ store.deleteFolder(name); confirmingDelete = null }) {
                    Text("Delete Folder", style = houseFont(14f, destructiveColor))
                }
            },
            dismissButton = {
                TextButton({ confirmingDelete = null }) { Text("Cancel", style = houseFont(14f)) }
            },
        )
    }
}

const val PULL_TO_SEARCH = 70f
private const val TOP_FADE = 20f

// MARK: Soft edges
//
// The same edges a note has. The list fades out as it scrolls up under the
// status bar; at the foot it is always faded out by the top of the corner
// flourish, starting a clear band above it, so nothing scrolls over the
// flourish or the controls beside it. A mask pinned to the screen, not the list.
private fun Modifier.listMask(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val h = max(size.height, 1f)
        val d = density
        val flourishTop = h - (Style.CORNER_FLOURISH_LIFT + Style.CORNER_FLOURISH_HEIGHT) * d
        val fadeStart = flourishTop - Style.CORNER_FLOURISH_CLEARANCE * d
        val top = TOP_FADE * d
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                top / h to Color.Black,
                max(top, fadeStart) / h to Color.Black,
                max(top, flourishTop) / h to Color.Transparent,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

// At the root, whose writing pad this is; in a folder, the folder's name. The
// way back out is the back gesture, or the corner flourish, which goes
// straight home.
@Composable
private fun Header(store: NotesStore, location: String?) {
    if (location != null) {
        Text(location, style = houseFont(16f, inkStrong), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = TOP_FADE.dp, bottom = 12.dp)
                .heightIn(min = 44.dp)
                .padding(horizontal = 10.dp, vertical = 12.dp)
                .semantics { heading() })
    } else {
        Text(if (store.displayName.isEmpty()) "notes" else "${store.displayName}'s notes",
            style = houseFont(16f, brownInk),
            modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = TOP_FADE.dp, bottom = 20.dp))
    }
}

// MARK: Folders

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderGrid(
    store: NotesStore,
    renaming: String?,
    draft: String,
    onDraft: (String) -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRenamed: (String, String) -> Unit,
) {
    val items: List<String?> = store.folders + listOf<String?>(null) // null = the add tile
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in items.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in row) {
                    Box(Modifier.weight(1f)) {
                        if (item == null) AddFolderTile(onAdd)
                        else FolderTile(store, item, renaming == item, draft, onDraft,
                            { onOpen(item) }, { onRename(item) }, { onDelete(item) }, { onRenamed(item, it) })
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f)) // keep a lone tile half-width
            }
        }
    }
}

private val tileHeight = (FOLDER_CELL_HEIGHT - FOLDER_CELL_PADDING * 2).dp

// A folder tile: folderBase on its own paper, stepping down to panel under the
// finger. The tiles carry no flourish; the one flourish on this screen is the
// corner's.
@Composable
private fun AddFolderTile(onAdd: () -> Unit) {
    val (interaction, pressed) = rememberPress()
    Box(
        Modifier.fillMaxWidth()
            .paper(if (pressed) panelColor else folderBaseColor, Surface.FOLDER_TILE, RoundedCornerShape(6.dp))
            .combinedClickable(interaction, indication = null, onClick = onAdd)
            .semantics { contentDescription = "New folder" }
            .padding(vertical = FOLDER_CELL_PADDING.dp, horizontal = 10.dp)
            .height(tileHeight),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(Glyph.PLUS, inkFaint, 14f) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    store: NotesStore, folder: String, renaming: Boolean, draft: String, onDraft: (String) -> Unit,
    onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onRenamed: (String) -> Unit,
) {
    val (interaction, pressed) = rememberPress()
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth()
                .paper(if (pressed || menu) panelColor else folderBaseColor, Surface.FOLDER_TILE, RoundedCornerShape(6.dp))
                .combinedClickable(interaction, indication = null, onClick = onOpen, onLongClick = { menu = true })
                .padding(vertical = FOLDER_CELL_PADDING.dp, horizontal = 10.dp)
                .height(tileHeight),
        ) {
            Spacer(Modifier.height((FOLDER_MARK_HEIGHT + FOLDER_MARK_GAP).dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (renaming) {
                    HouseField(draft, onDraft, size = 13f, alpha = 0.85f, focused = true,
                        onEndEditing = onRenamed, modifier = Modifier.height(18.dp))
                } else {
                    Text(folder, style = houseFont(13f, inkStrong), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Box(Modifier.size(6.dp).background(
                        if (store.currentFolder == folder) openDotColor else Color.Transparent, CircleShape))
                }
            }
        }
        HouseMenu(menu, { menu = false }) {
            HouseMenuItem("Rename") { menu = false; onRename() }
            HouseMenuItem("Delete Folder", destructive = true) { menu = false; onDelete() }
        }
    }
}

// MARK: Notes

/**
 * Notes gathered under the day they were last written in, newest day first.
 * Reading a note doesn't move it: only an edit changes its date.
 */
private fun noteDays(notes: List<NoteMeta>): List<Pair<LocalDate, List<NoteMeta>>> {
    val zone = ZoneId.systemDefault()
    val days = ArrayList<Pair<LocalDate, MutableList<NoteMeta>>>()
    for (note in notes) { // already newest first
        val day = Instant.ofEpochMilli(note.modified).atZone(zone).toLocalDate()
        if (days.isNotEmpty() && days.last().first == day) days.last().second += note
        else days += day to mutableListOf(note)
    }
    return days
}

// "today", "yesterday", the weekday within the last week, then the date — with
// the year only when it isn't this one. Lowercase, like the app's own words.
private fun dayName(day: LocalDate): String {
    val today = LocalDate.now()
    val daysAgo = ChronoUnit.DAYS.between(day, today)
    val name = when {
        daysAgo == 0L -> "today"
        daysAgo == 1L -> "yesterday"
        daysAgo in 2..6 -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("d MMMM"))
        else -> day.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))
    }
    return name.lowercase()
}

// The title; the day is in the heading above it. The note last open keeps its
// dot; the panel-toned box comes up only under the finger.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(store: NotesStore, note: NoteMeta, openNote: (String) -> Unit) {
    val (interaction, pressed) = rememberPress()
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 14.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (pressed || menu) panelColor else Color.Transparent, RoundedCornerShape(6.dp))
                .combinedClickable(interaction, indication = null, onClick = { openNote(note.id) },
                    onLongClick = { menu = true })
                .heightIn(min = 28.dp)
                .padding(vertical = 9.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(note.title, style = houseFont(14f, inkStrong), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(6.dp).background(
                if (note.id == store.currentID) openDotColor else Color.Transparent, CircleShape))
        }
        HouseMenu(menu, { menu = false; moving = false }) {
            if (!moving) {
                val targets = store.folders.filter { it != note.folder }
                if (note.folder != null || targets.isNotEmpty()) {
                    HouseMenuItem("Move to…") { moving = true }
                }
                HouseMenuItem("Delete", destructive = true) { menu = false; store.delete(note.id) }
            } else {
                if (note.folder != null) HouseMenuItem("Unfiled") { menu = false; moving = false; store.moveNote(note.id, null) }
                for (folder in store.folders.filter { it != note.folder }) {
                    HouseMenuItem(folder) { menu = false; moving = false; store.moveNote(note.id, folder) }
                }
            }
        }
    }
}

// MARK: Menus — the Mac's right-click menus, on a long press

@Composable
fun HouseMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded, onDismiss,
        containerColor = sheetColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.animateContentSize(),
    ) { content() }
}

@Composable
fun HouseMenuItem(title: String, destructive: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, style = houseFont(14f, if (destructive) destructiveColor else inkStrong)) },
        onClick = onClick,
    )
}
