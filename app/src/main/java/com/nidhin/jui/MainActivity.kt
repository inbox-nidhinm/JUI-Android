package com.nidhin.jui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Style.init(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = Notes.store(this)
        store.observe(lifecycle)
        setContent { JuiApp(store) }
    }

    override fun onStop() {
        super.onStop()
        // The icon follows the mood — but only once the app is out of sight:
        // switching the launcher alias ends the task it was opened from.
        AppIcon.sync(this)
    }
}

/** The one store, for as long as the process lives. */
object Notes {
    private var instance: NotesStore? = null

    // On this phone, in the app's own storage, in the same layout as the Mac's
    // vault: <id>.rtf, <id>.stickies.json, and one level of folders.
    fun store(activity: ComponentActivity): NotesStore = instance ?: NotesStore(
        File(activity.filesDir, "Notes").apply { mkdirs() },
        SharedPrefs(Style.prefs()),
    ).also { instance = it }
}
