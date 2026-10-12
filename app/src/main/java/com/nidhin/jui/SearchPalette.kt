package com.nidhin.jui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.min

// Search — the Mac's ⌘K palette, opened from the home screen's search button
// or by pulling the list down. Results *and* commands share one list; the
// keyboard's Go fires the first row, a tap fires any row.
//
// Two cards, not one divided card, as in the frames: the field is what you act
// on, the results are what happens because of it, and separating them lets the
// results grow and shrink without the field appearing to resize.
@Composable
fun SearchPalette(store: NotesStore, onReveal: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val results = store.search(query)
    val q = query.trim().lowercase()
    // Needs three characters and a real prefix of "onboard", so it can't
    // surface by accident while someone is searching their notes.
    val showOnboardAgain = q.length >= 3 && ("onboard again".startsWith(q) || q.startsWith("onboard"))

    fun reveal(id: String) {
        focus.clearFocus()
        store.reveal(id, query)
        onReveal()
    }

    // Go opens the top match, or runs the only command if nothing matched.
    fun activate() {
        val first = results.firstOrNull()
        if (first != null && query.isNotEmpty()) reveal(first.id)
        else if (results.isEmpty() && showOnboardAgain) store.replayOnboarding()
    }

    val card = RoundedCornerShape(12.dp)
    Column(
        Modifier.widthIn(max = 460.dp).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The query card. Papered on the background rather than over the whole
        // card: an overlay would multiply the rows and their ink in too.
        Row(
            Modifier.fillMaxWidth().pagePaper(shape = card).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.SEARCH, inkFaint, 14f)
            Box(Modifier.padding(start = 10.dp).weight(1f)) {
                HouseField(
                    query, { query = it }, size = 14f,
                    placeholder = "search", placeholderAlpha = 0.55f, // inkMuted — a step firmer here
                    focused = true, imeAction = ImeAction.Go,
                    capitalization = KeyboardCapitalization.None,
                    onSubmit = ::activate,
                )
            }
        }

        // The results card.
        Column(Modifier.fillMaxWidth().pagePaper(shape = card)) {
            if (results.isEmpty()) {
                Text(if (store.notes.isEmpty()) "no notes yet" else "no matches",
                    style = houseFont(12f, inkFaint).copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(14.dp))
            } else {
                // hug the results instead of reserving empty space
                val rows = results.sumOf { if (it.snippet.isEmpty()) 48 else 64 }
                Column(
                    Modifier.heightIn(max = min(rows + 16, 300).dp).verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    results.forEachIndexed { index, hit ->
                        ResultRow(hit, highlighted = index == 0 && query.isNotEmpty()) { reveal(hit.id) }
                    }
                }
            }

            // The one command, "onboard again", is properly hidden and has to
            // be typed for.
            if (showOnboardAgain) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(panelDividerColor))
                Column(Modifier.padding(vertical = 8.dp)) {
                    CommandRow("onboard again") { focus.clearFocus(); store.replayOnboarding() }
                }
            }
        }
    }
}

// The first row carries the highlight box, because it is what Go opens.
@Composable
private fun ResultRow(hit: SearchHit, highlighted: Boolean, onTap: () -> Unit) {
    val (interaction, pressed) = rememberPress()
    Column(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth()
            .background(if (highlighted || pressed) sheetColor else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(interaction, indication = null, onClick = onTap)
            .heightIn(min = 32.dp)
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row {
            hit.folder?.let {
                Text("$it/", style = houseFont(13f, inkMuted), maxLines = 1, modifier = Modifier.padding(end = 4.dp))
            }
            Text(hit.title, style = houseFont(13f, brownInk), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (hit.snippet.isNotEmpty()) {
            Text(hit.snippet, style = houseFont(11f, inkMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CommandRow(title: String, action: () -> Unit) {
    val (interaction, pressed) = rememberPress()
    Box(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth()
            .background(if (pressed) sheetColor else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(interaction, indication = null, onClick = action)
            .heightIn(min = 28.dp)
            .padding(vertical = 8.dp, horizontal = 12.dp),
    ) { Text(title, style = houseFont(12f, inkMuted)) }
}
