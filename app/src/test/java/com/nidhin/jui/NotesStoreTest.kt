package com.nidhin.jui

import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

// The store is the one part of the app with logic worth pinning down in tests:
// everything else is judged by looking at it. Each test gets its own vault in a
// temporary directory and its own preferences, so nothing touches the real
// notes or settings.
class NotesStoreTest {
    private lateinit var vault: File
    private lateinit var prefs: MapPrefs

    class MapPrefs : Prefs {
        val map = HashMap<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getBoolean(key: String) = map[key] as? Boolean ?: false
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    // Nothing runs by itself: saves happen when a test says `saveNow`.
    private val manual = Scheduler { _, _ -> {} }

    @Before fun setUp() {
        vault = Files.createTempDirectory("jui-tests").toFile()
        prefs = MapPrefs()
    }

    @After fun tearDown() {
        vault.deleteRecursively()
        Style.themeState.value = Themes.cream // leave the global as other tests expect
    }

    private fun makeStore() = NotesStore(vault, prefs, manual)

    private fun type(text: String, store: NotesStore) {
        store.textChanged(StyledText(text))
        store.saveNow()
    }

    private fun files(folder: String? = null): List<String> =
        (folder?.let { File(vault, it) } ?: vault).list().orEmpty().filter { !it.startsWith(".") }.sorted()

    // MARK: Titles

    @Test fun titleIsFirstNonEmptyLineTrimmedTo42() {
        assertEquals("hello there", NotesStore.title("\n\n  hello there  \nsecond"))
        assertEquals("Untitled", NotesStore.title(""))
        assertEquals("Untitled", NotesStore.title("   \n\t"))
        assertEquals(42, NotesStore.title("a".repeat(60)).length)
    }

    // MARK: Saving

    @Test fun blankNoteIsNeverWritten() {
        val store = makeStore()
        store.newNote(null)
        store.saveNow()
        assertTrue(files().isEmpty())
    }

    @Test fun typedNoteIsSavedAsRtfAndListed() {
        val store = makeStore()
        type("first line\nmore", store)
        assertEquals(listOf("${store.currentID}.rtf"), files())
        assertEquals(listOf("first line"), store.notes.map { it.title })
        assertEquals(3, store.wordCount)
    }

    @Test fun bulletIsNotAWord() {
        val store = makeStore()
        store.textChanged(StyledText("• one\n• two three"))
        assertEquals(3, store.wordCount)
    }

    @Test fun reopeningRestoresText() {
        val store = makeStore()
        type("• one\nprose", store)
        val id = store.currentID
        val reopened = makeStore()
        assertEquals(id, reopened.currentID)
        assertEquals("• one\nprose", reopened.attr.text)
    }

    @Test fun italicsSurviveARoundTrip() {
        val store = makeStore()
        store.textChanged(StyledText("plain slanted", listOf(StyleRun(6, 13, italic = true, bold = false))))
        store.saveNow()
        val reopened = makeStore()
        assertEquals(listOf(StyleRun(6, 13, italic = true, bold = false)), reopened.attr.runs)
    }

    @Test fun legacyPlainTextOpensAndIsReplacedByRtf() {
        File(vault, "OLD.txt").writeText("from before\nrtf")
        val store = makeStore()
        assertEquals("OLD", store.currentID)
        assertEquals("from before\nrtf", store.attr.text)
        type("from before\nrtf, now", store)
        assertEquals(listOf("OLD.rtf"), files())
    }

    @Test fun oldRulesBecomeBlankLines() {
        val note = StyledText("a\n─────\nb", listOf(StyleRun(8, 9, italic = true, bold = false)))
        val migrated = NotesStore.migrateRules(note)
        assertEquals("a\n${Style.HR_MARKER}\nb", migrated.text)
        assertEquals(listOf(StyleRun(4, 5, italic = true, bold = false)), migrated.runs)
    }

    // Reading a note is not editing it: opening, leaving and saving must not
    // rewrite the file, or the note jumps to the top of the list.
    @Test fun openingAndLeavingANoteDoesNotRewriteIt() {
        val store = makeStore()
        type("older", store)
        val older = store.currentID
        Thread.sleep(1100) // file dates can be to the second
        store.newNote(null)
        type("newer", store)
        val file = File(vault, "$older.rtf")
        val before = file.lastModified()
        Thread.sleep(1100)
        store.open(older)
        store.saveNow()
        store.newNote(null)
        store.saveNow()
        assertEquals(before, file.lastModified())
        assertEquals(listOf("newer", "older"), store.notes.map { it.title })
    }

    @Test fun debouncedSaveRunsThroughTheScheduler() {
        var pending: (() -> Unit)? = null
        val store = NotesStore(vault, prefs) { ms, block ->
            assertEquals(300L, ms)
            pending = block
            ({ pending = null })
        }
        store.textChanged(StyledText("typed"))
        assertTrue(files().isEmpty())
        pending!!.invoke()
        assertEquals(listOf("${store.currentID}.rtf"), files())
    }

    // MARK: Stickies

    @Test fun stickiesTrackEdits() {
        val store = makeStore()
        type("0123456789", store)
        store.addSticky(4, 3) // "456"

        // insert before: shifts
        store.remapStickies(0, 0, 2)
        assertEquals(6, store.stickies[0].start)
        assertEquals(3, store.stickies[0].length)

        // insert inside: grows
        store.remapStickies(7, 0, 1)
        assertEquals(6, store.stickies[0].start)
        assertEquals(4, store.stickies[0].length)

        // delete across the whole anchor: collapses to the edit point, survives
        store.remapStickies(5, 10, 0)
        assertEquals(1, store.stickies.size)
        assertEquals(5, store.stickies[0].start)
        assertEquals(0, store.stickies[0].length)
    }

    @Test fun editAfterAnchorLeavesItAlone() {
        val store = makeStore()
        type("0123456789", store)
        store.addSticky(1, 2)
        store.remapStickies(8, 1, 5)
        assertEquals(1, store.stickies[0].start)
        assertEquals(2, store.stickies[0].length)
    }

    @Test fun emptyStickyIsDiscardedOnClose() {
        val store = makeStore()
        type("words", store)
        val id = store.addSticky(0, 5)
        store.openStickyID = id
        store.closeSticky()
        assertTrue(store.stickies.isEmpty())
        assertNull(store.openStickyID)
    }

    @Test fun writtenStickyIsSavedBesideItsNote() {
        val store = makeStore()
        type("words", store)
        val id = store.addSticky(0, 5)
        store.updateStickyText(id, "a thought")
        store.openStickyID = id
        store.closeSticky()
        store.saveNow()
        assertTrue(files().contains("${store.currentID}.stickies.json"))
        val reopened = makeStore()
        assertEquals(listOf("a thought"), reopened.stickies.map { it.text })
    }

    @Test fun stickyIdsAndDatesAreSwifts() {
        val store = makeStore()
        type("words", store)
        val id = store.addSticky(0, 5)
        assertEquals(id.uppercase(), id)
        val created = store.stickies[0].created!!
        // Seconds since 2001: well past 2025 (≈ 7.6e8) and well short of 2100.
        assertTrue(created > 7.6e8 && created < 3.1e9)
    }

    // The file as the Mac writes it, including the fields the phone never sets:
    // they come back out exactly as they went in.
    @Test fun macStickyFieldsSurviveARewrite() {
        val json = """[{"id":"6A2F0E52-8D0B-4E7B-9C0B-0E6A8E4C6B11","start":0,"length":5,
            "text":"pinned","pinX":120.5,"pinY":-30,"rotation":-2.25,"left":true,"created":781959348.5}]"""
        File(vault, "N.rtf").writeBytes(Rtf.write(StyledText("words here")))
        File(vault, "N.stickies.json").writeText(json)
        val store = makeStore()
        val s = store.stickies.single()
        assertEquals("6A2F0E52-8D0B-4E7B-9C0B-0E6A8E4C6B11", s.id)
        assertEquals(120.5, s.pinX!!, 0.0)
        assertEquals(-30.0, s.pinY!!, 0.0)
        assertEquals(true, s.left)
        store.updateStickyText(s.id, "pinned, edited")
        store.saveNow()
        val o = JSONArray(File(vault, "N.stickies.json").readText()).getJSONObject(0)
        assertEquals("pinned, edited", o.getString("text"))
        assertEquals(-2.25, o.getDouble("rotation"), 0.0)
        assertEquals(781959348.5, o.getDouble("created"), 0.0)
        assertTrue(o.getBoolean("left"))
    }

    @Test fun absentOptionalsAreLeftOut() {
        val json = NotesStore.encodeStickies(listOf(Sticky("A", 1, 2, "t")))
        val o = JSONArray(json).getJSONObject(0)
        assertEquals(setOf("id", "start", "length", "text"), o.keys().asSequence().toSet())
    }

    // MARK: Folders

    @Test fun createFolderUniquesNamesIgnoringCase() {
        val store = makeStore()
        assertEquals("Poems", store.createFolder("Poems"))
        assertEquals("poems 2", store.createFolder("poems"))
        assertEquals("a-b", store.createFolder("  a/b  "))
        assertNull(store.createFolder("   "))
        assertEquals(listOf("a-b", "Poems", "poems 2"), store.folders)
    }

    @Test fun renameThatOnlyChangesCaseKeepsTheName() {
        val store = makeStore()
        store.createFolder("notes")
        assertEquals("Notes", store.renameFolder("notes", "Notes"))
        assertEquals(listOf("Notes"), store.folders)
    }

    @Test fun movingANoteTakesItsStickiesAlong() {
        val store = makeStore()
        store.createFolder("poems")
        type("a poem", store)
        store.addSticky(0, 1)
        store.updateStickyText(store.stickies[0].id, "note")
        store.saveNow()
        val id = store.currentID

        store.moveNote(id, "poems")
        assertEquals(listOf("$id.rtf", "$id.stickies.json"), files("poems"))
        assertEquals("poems", store.notes.first().folder)
        assertEquals("poems", store.currentFolder)
    }

    @Test fun deletingAFolderDeletesItsNotesAndReopensSomethingElse() {
        val store = makeStore()
        type("unfiled", store)
        val keep = store.currentID
        store.createFolder("old")
        store.newNote(null)
        type("doomed", store)
        store.moveNote(store.currentID, "old")

        store.deleteFolder("old")
        assertEquals(emptyList<String>(), store.folders)
        assertEquals(listOf(keep), store.notes.map { it.id })
        assertEquals(keep, store.currentID)
    }

    @Test fun newNoteLandsInTheFolderItWasMadeIn() {
        val store = makeStore()
        store.createFolder("drafts")
        store.newNote("drafts")
        assertTrue(store.focusOnOpen)
        type("in drafts", store)
        assertEquals("drafts", store.notes.first().folder)
    }

    @Test fun hiddenFoldersAreNotFolders() {
        File(vault, ".Trash").mkdirs()
        assertEquals(emptyList<String>(), makeStore().folders)
    }

    @Test fun deletingTheOpenNoteOpensTheNextOrABlankPage() {
        val store = makeStore()
        type("only", store)
        val id = store.currentID
        store.delete(id)
        assertTrue(files().isEmpty())
        assertFalse(store.currentID == id)
        assertEquals("", store.attr.text)
    }

    // MARK: Search

    @Test fun searchFindsTextInOtherNotesWithASnippet() {
        val store = makeStore()
        type("shopping\nbuy oranges", store)
        store.newNote(null)
        type("a different note", store)
        store.openSearch()
        val hits = store.search("ORANGE")
        assertEquals(listOf("shopping"), hits.map { it.title })
        assertEquals("buy oranges", hits.first().snippet)
        assertEquals(2, store.search("").size)
    }

    @Test fun revealOpensTheNoteAtTheMatch() {
        val store = makeStore()
        type("shopping\nbuy oranges", store)
        val id = store.currentID
        store.newNote(null)
        type("elsewhere", store)
        store.openSearch()
        val version = store.docVersion
        store.reveal(id, "orange")
        assertEquals(id, store.currentID)
        assertFalse(store.searchShown)
        assertEquals(13 until 19, store.pendingReveal)
        assertEquals(version + 1, store.docVersion)
    }

    // MARK: Settings

    @Test fun themeAndNamePersist() {
        val store = makeStore()
        store.setTheme(Themes.lilac)
        store.setDisplayName("  Juilee ")
        assertEquals("lilac", prefs.getString("themeID"))
        assertEquals("Juilee", prefs.getString("displayName"))
        assertEquals(Themes.lilac, Style.themeState.value)
    }

    @Test fun fontChangeReloadsButDoesNotSave() {
        val store = makeStore()
        type("words", store)
        val before = File(vault, "${store.currentID}.rtf").lastModified()
        val version = store.docVersion
        store.setFont("Roboto")
        store.saveNow()
        assertEquals(version + 1, store.docVersion)
        assertEquals(before, File(vault, "${store.currentID}.rtf").lastModified())
        assertEquals("Roboto", prefs.getString("fontFamily"))
        store.setFont(Style.DEFAULT_FONT_FAMILY)
    }

    @Test fun onboardingRunsUntilCompleted() {
        assertTrue(makeStore().onboardingShown)
        makeStore().completeOnboarding()
        assertFalse(makeStore().onboardingShown)
    }
}
