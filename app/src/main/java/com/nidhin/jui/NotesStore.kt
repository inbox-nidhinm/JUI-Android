package com.nidhin.jui

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The few settings the store keeps — SharedPreferences in the app, a map in tests. */
interface Prefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String): Boolean
    fun putBoolean(key: String, value: Boolean)
}

class SharedPrefs(private val sp: SharedPreferences) : Prefs {
    override fun getString(key: String): String? = sp.getString(key, null)
    override fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()
    override fun getBoolean(key: String): Boolean = sp.getBoolean(key, false)
    override fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
}

/** Runs `block` after `ms`; the function returned cancels it. */
fun interface Scheduler {
    fun after(ms: Long, block: () -> Unit): () -> Unit
}

object MainScheduler : Scheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    override fun after(ms: Long, block: () -> Unit): () -> Unit {
        val r = Runnable(block)
        handler.postDelayed(r, ms)
        return { handler.removeCallbacks(r) }
    }
}

// The single source of truth for everything. Every screen takes `store`
// directly; there is no other model layer.
//
// Persistence is plain files in the vault, the same layout the Mac and the
// iPhone use:
//
//   <vault>/<uuid>.rtf             a note (RTF so italics survive)
//   <vault>/<uuid>.stickies.json   its sticky annotations, if any
//   <vault>/<folder>/              a real subdirectory, one level only
//
// On Android the vault is a folder in the app's own storage, on the phone
// only for now. The layout is the Mac's, so syncing it later is a matter of
// where the folder is, not what is in it.
class NotesStore(
    dir: File,
    private val prefs: Prefs,
    private val scheduler: Scheduler = MainScheduler,
) {
    var dir: File = dir
        private set

    var notes by mutableStateOf<List<NoteMeta>>(emptyList())
        private set
    var currentID by mutableStateOf(newID())
        private set
    // Bumped only when a different document is loaded into the editor, never
    // on typing — the editor watches this to know when to reset. Bumping it
    // on keystrokes wipes the undo history and fights the caret.
    var docVersion by mutableIntStateOf(0)
        private set
    // Set by `newNote` so the editor opens with the keyboard up; the editor
    // clears it once it has taken focus. Opening an existing note leaves the
    // keyboard down until the page is tapped.
    var focusOnOpen = false

    var attr by mutableStateOf(StyledText())
        private set
    private var cancelSave: (() -> Unit)? = null
    // Whether the open note has been edited since it was last written. A note
    // is written only when it has: opening, reading and leaving one must not
    // touch its file, because the file's modification date is what orders the
    // home screen — rewriting on every visit sent whatever was last *read* to
    // the top. Every edit goes through `scheduleSave`, which sets this.
    private var unsaved = false

    var fontFamily by mutableStateOf(Style.fontFamily)
        private set

    // MARK: Folders (real subdirectories of the vault, one level deep)

    var folders by mutableStateOf<List<String>>(emptyList())
        private set
    var currentFolder by mutableStateOf<String?>(null)
        private set

    fun baseDir(folder: String?): File =
        if (folder.isNullOrEmpty()) dir else File(dir, folder)

    fun folderOf(id: String): String? =
        if (id == currentID) currentFolder else notes.firstOrNull { it.id == id }?.folder

    /** Where the notes are, as the phone would name it. */
    val vaultDisplay: String get() = "On this phone"

    // The open note's file date when it was last read or written here, so a
    // newer one can be recognised as an edit made elsewhere.
    private var lastSeenModified: Long? = null

    // MARK: First run & display name

    // Held behind a getter: a `private set` would clash with setDisplayName.
    private var displayNameState by mutableStateOf(prefs.getString("displayName") ?: "")
    val displayName: String get() = displayNameState
    var onboardingShown by mutableStateOf(!prefs.getBoolean("onboardingCompleted"))
        private set

    // typing is suspended while a full-screen overlay is up
    val editorBlocked: Boolean get() = hiddenShown || onboardingShown || openStickyID != null

    fun setDisplayName(raw: String) {
        val clean = raw.trim()
        if (clean == displayName) return
        displayNameState = clean
        prefs.putString("displayName", clean)
    }

    fun completeOnboarding() {
        prefs.putBoolean("onboardingCompleted", true)
        onboardingShown = false
    }

    // Replays the first-run flow on demand — the hidden "onboard again" row in
    // search. Deliberately leaves onboardingCompleted alone: the only way out of
    // onboarding is to finish it, so abandoning a replay by quitting must not
    // re-trigger it on next launch.
    fun replayOnboarding() {
        searchShown = false
        hiddenShown = false
        saveNow()
        onboardingShown = true
    }

    /**
     * Saves whenever the app leaves the foreground — Android may kill it
     * without a quit — and picks up whatever changed meanwhile on coming back.
     */
    fun observe(lifecycle: Lifecycle) {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = refreshFromDisk()
            override fun onPause(owner: LifecycleOwner) = saveNow()
            override fun onStop(owner: LifecycleOwner) = saveNow()
        })
    }

    var wordCount by mutableIntStateOf(0)
        private set

    private fun updateWordCount() {
        // The bullet is furniture, not a word.
        wordCount = attr.text.split(Regex("\\s+")).count { it.isNotEmpty() && it != "•" }
    }

    fun textChanged(new: StyledText) {
        attr = new
        updateWordCount()
        scheduleSave()
    }

    // The hidden page — dedication and settings — toggled by the corner
    // flourish; while it's up the editor underneath is frozen.
    var hiddenShown by mutableStateOf(false)

    // MARK: Sticky annotations

    var stickies by mutableStateOf<List<Sticky>>(emptyList())
        private set
    // The sticky whose card is open over the page, if any.
    var openStickyID by mutableStateOf<String?>(null)

    private fun stickiesFile(id: String, folder: String?) = File(baseDir(folder), "$id.stickies.json")

    private fun loadStickies(id: String, folder: String?): List<Sticky> {
        val file = stickiesFile(id, folder)
        if (!file.exists()) return emptyList()
        return runCatching { decodeStickies(file.readText()) }.getOrDefault(emptyList())
    }

    fun addSticky(start: Int, length: Int): String {
        val sticky = Sticky(id = newID(), start = start, length = length, text = "", created = now2001())
        stickies = stickies + sticky
        scheduleSave()
        return sticky.id
    }

    fun updateStickyText(id: String, text: String) {
        val i = stickies.indexOfFirst { it.id == id }
        if (i < 0 || stickies[i].text == text) return
        stickies = stickies.toMutableList().also { it[i] = it[i].copy(text = text) }
        scheduleSave()
    }

    fun removeSticky(id: String) {
        stickies = stickies.filter { it.id != id }
        if (openStickyID == id) openStickyID = null
        scheduleSave()
    }

    /**
     * Closes the open sticky card. A sticky that was never written in goes
     * with it, as an untouched one does on the Mac when clicked away from.
     */
    fun closeSticky() {
        val id = openStickyID ?: return
        openStickyID = null
        val sticky = stickies.firstOrNull { it.id == id }
        if (sticky != null && sticky.text.isBlank()) removeSticky(id)
    }

    /**
     * Shift anchors through a text edit so they keep tracking their text: the
     * `editLength` characters at `editStart` became `replacementLength` new
     * ones. Anchors inside a deleted range collapse to the edit point but
     * survive.
     */
    fun remapStickies(editStart: Int, editLength: Int, replacementLength: Int) {
        if (stickies.isEmpty()) return
        val delta = replacementLength - editLength
        val editEnd = editStart + editLength
        fun map(position: Int): Int = when {
            position <= editStart -> position
            position >= editEnd -> position + delta
            else -> editStart
        }
        var changed = false
        val updated = stickies.map { s ->
            val newStart = map(s.start)
            val newEnd = maxOf(map(s.start + s.length), newStart)
            if (newStart != s.start || newEnd - newStart != s.length) {
                changed = true
                s.copy(start = newStart, length = newEnd - newStart)
            } else s
        }
        if (changed) {
            stickies = updated
            scheduleSave()
        }
    }

    // MARK: Search

    private var dropCache: (() -> Unit)? = null

    var searchShown: Boolean
        get() = searchShownState
        set(value) {
            val old = searchShownState
            searchShownState = value
            if (value || !old) return
            // Dropped a beat after the palette closes, so the rows keep their
            // snippets while they fade out.
            dropCache?.invoke()
            dropCache = scheduler.after(1000) { if (!searchShownState) searchCache.clear() }
        }
    private var searchShownState by mutableStateOf(false)

    /**
     * Consumed by the editor after a `reveal`: the match to select, `first`
     * its first character and `last + 1` the end (an IntRange is inclusive).
     */
    var pendingReveal: IntRange? = null
    private val searchCache = HashMap<String, String>()

    fun openSearch() {
        hiddenShown = false
        openStickyID = null
        searchCache.clear()
        for (meta in notes) searchCache[meta.id] = plainTextForNote(meta.id)
        searchShown = true
    }

    fun search(query: String): List<SearchHit> {
        val q = query.trim(' ', '\t')
        val hits = ArrayList<SearchHit>()
        for (meta in notes) {
            val text = if (meta.id == currentID) attr.text else searchCache[meta.id] ?: ""
            if (q.isEmpty()) {
                hits += SearchHit(meta.id, meta.title, "", meta.folder)
                continue
            }
            val at = text.indexOf(q, ignoreCase = true)
            if (at >= 0) {
                val line = lineAround(text, at).trim()
                hits += SearchHit(meta.id, meta.title, line.take(64), meta.folder)
            } else if (meta.title.contains(q, ignoreCase = true)) {
                hits += SearchHit(meta.id, meta.title, "", meta.folder)
            }
        }
        return hits
    }

    // open a note from search and remember where to scroll/flash
    fun reveal(id: String, query: String) {
        searchShown = false
        saveNow()
        load(id, notes.firstOrNull { it.id == id }?.folder, revealing = query)
    }

    private fun plainTextForNote(id: String): String {
        val folder = folderOf(id)
        val rtf = rtfFile(id, folder)
        return if (rtf.exists()) plainText(rtf) else plainText(txtFile(id, folder))
    }

    // MARK: Folder management

    // Folder names are compared without case, as the Mac's filesystem would:
    // "Poems" and "poems" could both be made here and then collide the moment
    // the vault met a Mac.
    private fun folderExists(name: String, except: String? = null): Boolean {
        val items = dir.list().orEmpty()
        return items.any { item ->
            item.lowercase() == name.lowercase() && item.lowercase() != except?.lowercase()
        }
    }

    fun createFolder(name: String): String? {
        val clean = sanitizeFolderName(name)
        if (clean.isEmpty()) return null
        var candidate = clean
        var counter = 2
        while (folderExists(candidate)) {
            candidate = "$clean $counter"
            counter++
        }
        if (!File(dir, candidate).mkdirs()) return null
        refreshList()
        return candidate
    }

    fun renameFolder(name: String, newName: String): String? {
        val clean = sanitizeFolderName(newName)
        if (clean.isEmpty() || clean == name) return name
        // A rename that only changes case is not a collision with itself.
        var candidate = clean
        var counter = 2
        while (folderExists(candidate, except = name)) {
            candidate = "$clean $counter"
            counter++
        }
        if (!File(dir, name).renameTo(File(dir, candidate))) return null
        if (currentFolder == name) currentFolder = candidate
        refreshList()
        return candidate
    }

    // Deleting a folder deletes what is in it — no trash, no undo, the same
    // deal `delete` offers for one note. The whole directory goes, so a stray
    // file of another kind can't leave a ghost folder behind.
    fun deleteFolder(name: String) {
        // Cancel the open note's pending save first if it is one of the ones
        // going, or the debounce writes it straight back into a directory that
        // no longer exists.
        val losingCurrent = folderOf(currentID) == name
        if (losingCurrent) cancelPendingSave()
        File(dir, name).deleteRecursively()
        if (currentFolder == name) currentFolder = null
        refreshList()
        if (losingCurrent) openMostRecentOrBlank()
    }

    fun moveNote(id: String, folder: String?) {
        val from = folderOf(id)
        if (from == folder) return
        if (id == currentID) saveNow() // files must exist at the old location
        baseDir(folder).mkdirs()
        for (name in listOf("$id.rtf", "$id.txt", "$id.stickies.json")) {
            val src = File(baseDir(from), name)
            if (src.exists()) src.renameTo(File(baseDir(folder), name))
        }
        if (id == currentID) currentFolder = folder
        refreshList()
    }

    private fun sanitizeFolderName(raw: String): String =
        raw.trim().replace("/", "-").replace(":", "-")

    // MARK: Settings

    fun setFont(family: String) {
        if (family == fontFamily) return
        fontFamily = family
        Style.familyState.value = family
        prefs.putString("fontFamily", family)
        // The page restyles on load: leading is derived from the pitch and the
        // family's own line height, so it changes with the family.
        docVersion++
        // Not saved: the family is re-applied to every note on load, so a
        // font change is not an edit and must not reorder the notes.
    }

    var themeID by mutableStateOf(Style.theme.id)
        private set

    // Deliberately does NOT bump docVersion the way setFont does: a theme
    // change recolours the page in place, so selection, scroll position and
    // undo all survive.
    fun setTheme(theme: Theme) {
        if (theme.id == themeID) return
        Style.themeState.value = theme
        themeID = theme.id
        prefs.putString("themeID", theme.id)
    }

    // MARK: The note list

    // Titles are cached against the modification date they were read at.
    // `saveNow` ends in `refreshList`, and saves fire on every pause in typing,
    // so without this every pause re-parsed every note in the vault.
    private var titleCache = HashMap<String, Pair<Long, String>>()

    fun refreshList() {
        val seen = HashSet<String>()
        val metas = ArrayList<NoteMeta>()
        val folderNames = ArrayList<String>()
        val freshTitles = HashMap<String, Pair<Long, String>>()

        fun scanFiles(directory: File, folder: String?) {
            val files = directory.listFiles().orEmpty()
            // rtf first so a leftover legacy .txt with the same id is ignored
            for (file in files.sortedBy { it.extension }) {
                if (!file.isFile || file.extension !in setOf("rtf", "txt")) continue
                val id = file.nameWithoutExtension
                if (!seen.add(id)) continue
                val modified = file.lastModified()
                val hit = titleCache[file.path]
                val title = if (hit != null && hit.first == modified) hit.second else title(plainText(file))
                freshTitles[file.path] = modified to title
                metas += NoteMeta(id, title, modified, folder)
            }
        }

        for (item in dir.listFiles().orEmpty()) {
            // Hidden folders are not folders of notes.
            if (item.isDirectory && !item.name.startsWith(".")) {
                folderNames += item.name
                scanFiles(item, item.name)
            }
        }
        scanFiles(dir, null)

        titleCache = freshTitles
        folders = folderNames.sortedWith(String.CASE_INSENSITIVE_ORDER)
        notes = metas.sortedByDescending { it.modified }
    }

    private fun plainText(file: File): String {
        if (!file.exists()) return ""
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return ""
        if (file.extension == "rtf") Rtf.read(bytes)?.let { return it.text }
        return String(bytes, Charsets.UTF_8)
    }

    // MARK: Getting a document into the editor
    //
    // Every route in goes through `load` or `loadBlank`, so none can forget a
    // step. `docVersion` is bumped last, after `pendingReveal` is set, or the
    // editor reloads before it can read it.

    private fun loadAttr(id: String, folder: String?): StyledText {
        val rtf = rtfFile(id, folder)
        if (rtf.exists()) {
            runCatching { rtf.readBytes() }.getOrNull()?.let(Rtf::read)?.let { return migrateRules(it) }
        }
        val txt = txtFile(id, folder)
        if (txt.exists()) {
            runCatching { txt.readText() }.getOrNull()?.let { return StyledText(it) }
        }
        return StyledText()
    }

    private fun load(id: String, folder: String?, revealing: String? = null) {
        openStickyID = null
        unsaved = false
        currentID = id
        currentFolder = folder
        attr = loadAttr(id, folder)
        stickies = loadStickies(id, folder)
        lastSeenModified = modified(rtfFile(id, folder))
        updateWordCount()
        if (revealing != null) {
            val q = revealing.trim(' ', '\t')
            val at = if (q.isEmpty()) -1 else attr.text.indexOf(q, ignoreCase = true)
            pendingReveal = if (at >= 0) at until at + q.length else null
        }
        docVersion++
    }

    /** A blank page. Nothing reaches disk until something is typed into it. */
    private fun loadBlank(folder: String?) {
        openStickyID = null
        unsaved = false
        currentID = newID()
        currentFolder = folder
        attr = StyledText()
        stickies = emptyList()
        updateWordCount()
        docVersion++
    }

    private fun openMostRecentOrBlank() {
        val latest = notes.firstOrNull()
        if (latest != null) load(latest.id, latest.folder) else loadBlank(null)
    }

    fun open(id: String) {
        saveNow()
        load(id, notes.firstOrNull { it.id == id }?.folder)
    }

    fun delete(id: String) {
        val losingCurrent = id == currentID
        if (losingCurrent) cancelPendingSave() // a pending save would resurrect the file
        val folder = folderOf(id)
        rtfFile(id, folder).delete()
        txtFile(id, folder).delete()
        stickiesFile(id, folder).delete()
        refreshList()
        if (losingCurrent) openMostRecentOrBlank()
    }

    /**
     * A blank page in `folder` (null = unfiled) — wherever the home screen is
     * standing when the plus is pressed. It opens with the keyboard up.
     */
    fun newNote(folder: String?) {
        hiddenShown = false
        saveNow()
        loadBlank(folder)
        focusOnOpen = true
    }

    private fun rtfFile(id: String, folder: String?) = File(baseDir(folder), "$id.rtf")
    private fun txtFile(id: String, folder: String?) = File(baseDir(folder), "$id.txt")

    private fun scheduleSave() {
        unsaved = true
        cancelSave?.invoke()
        cancelSave = scheduler.after(300) { saveNow() }
    }

    private fun cancelPendingSave() {
        cancelSave?.invoke()
        cancelSave = null
    }

    fun saveNow() {
        cancelPendingSave()
        if (!unsaved) return // nothing edited: leave the file, and its date, alone
        unsaved = false
        val file = rtfFile(currentID, currentFolder)
        val hadFile = file.exists() || txtFile(currentID, currentFolder).exists()
        // Don't create a file for a note that was never typed into.
        if (attr.length == 0 && !hadFile) return
        baseDir(currentFolder).mkdirs()
        writeAtomically(file, Rtf.write(attr))
        txtFile(currentID, currentFolder).delete() // legacy plain text
        val sFile = stickiesFile(currentID, currentFolder)
        if (stickies.isEmpty()) sFile.delete()
        else writeAtomically(sFile, encodeStickies(stickies).toByteArray(Charsets.UTF_8))
        lastSeenModified = modified(file)
        refreshList()
    }

    // MARK: Where the notes live

    private fun modified(file: File): Long? = file.lastModified().takeIf { it > 0 }

    /**
     * Re-reads the folder after coming back to the foreground. The open note
     * is reloaded if its file is newer than the one read here, unless it has
     * edits not yet saved (those win, and overwrite it on the next save) or a
     * sticky is open on it.
     */
    fun refreshFromDisk() {
        refreshList()
        if (unsaved || openStickyID != null) return
        val current = modified(rtfFile(currentID, currentFolder)) ?: return
        val seen = lastSeenModified ?: return
        if (current > seen) load(currentID, currentFolder)
    }

    // Last, so every property above is in place before the first note opens.
    init {
        dir.mkdirs()
        refreshList()
        notes.firstOrNull()?.let { open(it.id) }
    }

    companion object {
        fun title(text: String): String {
            val first = text.split('\n', '\r', ' ', ' ')
                .map { it.trim(' ', '\t', ' ') }
                .firstOrNull { it.isNotEmpty() } ?: ""
            return if (first.isEmpty()) "Untitled" else first.take(42)
        }

        private fun newID() = UUID.randomUUID().toString().uppercase()

        // Swift's Date encodes as seconds since 2001-01-01 UTC.
        private const val REFERENCE_EPOCH = 978_307_200.0

        private fun now2001(): Double = System.currentTimeMillis() / 1000.0 - REFERENCE_EPOCH

        /** The line holding `index`, without its line break. */
        private fun lineAround(text: String, index: Int): String {
            val breaks = charArrayOf('\n', '\r', ' ', ' ')
            val start = text.lastIndexOfAny(breaks, index - 1).let { if (index == 0) 0 else it + 1 }
            val end = text.indexOfAny(breaks, index).let { if (it < 0) text.length else it }
            return text.substring(start, end)
        }

        // Old fixed-width "───…" rules become the blank-line marker.
        internal fun migrateRules(note: StyledText): StyledText {
            var result = note
            val text = note.text
            val ranges = ArrayList<IntRange>()
            var lineStart = 0
            while (lineStart <= text.length) {
                val nl = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
                val content = text.substring(lineStart, nl)
                if (content.length >= 3 && content.all { it == '─' }) ranges += lineStart until nl
                if (nl >= text.length) break
                lineStart = nl + 1
            }
            for (r in ranges.reversed()) result = replace(result, r.first, r.last + 1, Style.HR_MARKER)
            return result
        }

        /** `note` with [start, end) replaced by upright `with`, its runs moved. */
        private fun replace(note: StyledText, start: Int, end: Int, with: String): StyledText {
            val delta = with.length - (end - start)
            val runs = note.runs.mapNotNull { r ->
                when {
                    r.end <= start -> r
                    r.start >= end -> r.copy(start = r.start + delta, end = r.end + delta)
                    else -> {
                        // Clipped to what is left either side of the edit.
                        val a = r.start.coerceAtMost(start)
                        val b = if (r.end > end) r.end + delta else start
                        if (b > a) r.copy(start = a, end = b) else null
                    }
                }
            }
            return StyledText(note.text.substring(0, start) + with + note.text.substring(end), runs)
        }

        private fun writeAtomically(file: File, bytes: ByteArray) {
            val tmp = File(file.parentFile, ".${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
        }

        // MARK: Stickies on disk
        //
        // `[Sticky]` as Swift's JSONEncoder writes it: absent optionals are
        // left out rather than written as null.

        fun encodeStickies(list: List<Sticky>): String {
            val array = JSONArray()
            for (s in list) {
                val o = JSONObject()
                o.put("id", s.id)
                o.put("start", s.start)
                o.put("length", s.length)
                o.put("text", s.text)
                s.pinX?.let { o.put("pinX", it) }
                s.pinY?.let { o.put("pinY", it) }
                s.rotation?.let { o.put("rotation", it) }
                s.left?.let { o.put("left", it) }
                s.created?.let { o.put("created", it) }
                array.put(o)
            }
            return array.toString()
        }

        fun decodeStickies(json: String): List<Sticky> {
            val array = JSONArray(json)
            return (0 until array.length()).map { k ->
                val o = array.getJSONObject(k)
                fun dbl(key: String): Double? = if (o.has(key) && !o.isNull(key)) o.getDouble(key) else null
                Sticky(
                    id = o.getString("id"),
                    start = o.getInt("start"),
                    length = o.getInt("length"),
                    text = o.optString("text", ""),
                    pinX = dbl("pinX"),
                    pinY = dbl("pinY"),
                    rotation = dbl("rotation"),
                    left = if (o.has("left") && !o.isNull("left")) o.getBoolean("left") else null,
                    created = dbl("created"),
                )
            }
        }
    }
}
