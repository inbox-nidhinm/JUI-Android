# JUI for Android

The Android port of **JUI**, the writing pad built as a gift for Juilee — a
cream sheet, Courier, a ruled page and no chrome. How it *feels* matters more
than feature count.

**The iPhone app is what this was ported from** (`~/Documents/Claude Code/JUI-iOS`),
and the macOS app (`~/Documents/Claude Code/JUI`) is the source of truth behind
both. Never write to either folder. Their `CLAUDE.md` files (and the Mac's
`CHANGELOG.md`) explain *why* almost every number and oddity exists; this port
keeps the numbers and says so in the comments. Read the matching Swift before
changing anything ported from it.

## Decisions for this port

- **Pixel phones, portrait, light only, Android 10+** (minSdk 29: blend modes
  on hardware canvases, which every paper multiply relies on).
- **Tested on the emulator only** — AVD `JUI_Pixel_9` (Pixel 9, API 36). There
  is no physical device yet.
- **Notes live on the phone** (`filesDir/Notes`), in the Mac's on-disk format:
  `<uuid>.rtf`, `<uuid>.stickies.json`, one level of folders. No sync yet; the
  settings row says "On this phone". Sync (Google Drive or otherwise) is a
  later decision.
- **Sideloaded**, signed with this Mac's debug key (`~/.android/debug.keystore`);
  no Play Store. `./apk.sh` builds the release APK into `apk/JUI.apk`, which is
  committed so the repo always has an installable copy — rebuild and commit it
  with any change meant for the phone. A phone takes an update only from the
  same key, and uninstalling to get past a key change deletes the notes, so
  that keystore must not be lost.
- Everything else is the iPhone app's: home screen, note screen with only the
  word count, the corner flourish over every screen, stickies as crumpled balls
  in the line, search on home, the envelope onboarding, the hidden settings
  page, Courier Prime by default, autocorrect on, smart punctuation off.

### Where Android differs, and why

- **The way back is the system back gesture** (the iPhone's edge swipe). Back
  closes whatever is over the page first: a sticky, search, settings; then a
  note or folder.
- **Moving a note is in its long-press menu** ("Move to…"); there is no
  drag-and-drop onto folder tiles.
- **The launcher icon follows the mood through activity aliases**, switched in
  `MainActivity.onStop` — switching while the app is open ends its task. A
  copy of the icon pinned to the home screen goes grey when the mood changes
  and has to be re-added; the app drawer's is always right.
- **Fonts on offer** are Android's own (Cutive Mono stands in for Courier,
  which phones don't carry), plus the bundled Courier Prime.
- **No undo** in the editor: Android's EditText has none to give.

## Shape of the project

| File | Contents |
|---|---|
| `Theme.kt` | The six palettes, `blend`, `withAlpha` |
| `Style.kt` | Colour tokens, the ink ladder, `houseFont`, `Style` (type, grid metrics, insets), folder tile metrics |
| `Paper.kt` | Paper stocks, surfaces, `Modifier.paper`, `pagePaper`, `PageSheet` |
| `Flourish.kt` | Frame sets and `AnimatedFlourish` |
| `Model.kt` | `StyledText`, `Sticky`, `NoteMeta` — plain data, testable on the JVM |
| `Rtf.kt` | Cocoa-compatible RTF reader and writer |
| `NotesStore.kt` | The single source of truth: files, folders, stickies, search, settings |
| `Caret.kt` | `CaretEditText` — the gliding ink caret every editor uses |
| `Editor.kt` | `PitchSpan`, `BallSpan`, `RuledEditText` (ruling, wash, selection, balls, bullets, italics), `NoteScroll` (scrolling and soft edges) |
| `StickyGeometry.kt` | `StickyText` (14pt at 140%), `StickyCard.stampFrame`, `StampOutline` |
| `StickyCardView.kt` | The open sticky card and its editor |
| `CrumpleSheet.kt`, `CrumpleGL.kt`, `CrumpleArt.kt`, `CrumpleDirector.kt` | The crumpled-ball sticky in OpenGL ES 3: the sheet, its renderer, the card's picture and the ball's, and the director that runs the fold |
| `HomeScreen.kt` | Folders, notes by day, pull to search, long-press menus |
| `SearchPalette.kt` | Search, with the hidden "onboard again" row |
| `Onboarding.kt` | The envelope flow |
| `Controls.kt` | Glyphs, `IconButton`, `ThemePicker`, `HouseField`, `StampButton`, `OnboardingPill` |
| `App.kt` | Navigation, the note screen, corner flourish, settings page, overlays, back |
| `AppIcon.kt` | The mood-following launcher icon |
| `assets/` | Textures and flourish frames, copied from the iPhone app (which copied them from the Mac's build) |
| `res/font/` | Courier Prime, four faces, SIL OFL (`licenses/`) |

## Build, test, look

```bash
./run.sh                 # build, install on the running emulator, launch
./run.sh shot NAME       # …and save build/shots/NAME.png
./apk.sh                 # release APK → apk/JUI.apk (commit it)
./gradlew testDebugUnitTest
```

Start the emulator with
`~/Library/Android/sdk/emulator/emulator -avd JUI_Pixel_9 &`. Java is
Homebrew's `openjdk@17` (`run.sh` sets `JAVA_HOME`). A fresh install starts at
onboarding (`adb shell pm clear com.nidhin.jui.android`).

When changing anything visual, look at it — the iPhone's walkthrough shots in
`JUI-iOS/build/shots/` are the reference.

## Invariants carried over

- **The pitch is the constant** (48 = 3 × the 16pt type). `PitchSpan` lays every
  line out exactly one pitch tall, type centred, from the font's *own* metrics —
  the metrics Android passes a wrapped line are the previous line's, already
  stretched. A paragraph after prose gets one blank pitch *above* it (not below
  the one before, so selection handles stay under the words); bullets get none.
  Classified by the text (does the line start with `• `), never by attributes.
- **The empty line after a trailing Return is laid out at natural height** by
  Android, whatever the spans say. `bandTop` places it on the grid by hand;
  anything that measures lines goes through `bandTop`/`bandCentre`.
- **The ruling is anchored to the text's top**, drawn black at 10%.
- **Caret and selection are sized off the ink**, centred in the band. The system
  caret's drawable is set to nothing (not hidden — that would take the handles
  and magnifier too) and `CaretEditText` draws the ink caret: 0.10s glide,
  solid while moving, blinking only when idle. Every editor is a `CaretEditText`.
- **`docVersion` is the load signal** — bumped only when a different document
  loads, never on typing.
- **Sticky anchors are UTF-16 ranges, remapped in `beforeTextChanged`**, the one
  place every edit passes. Loading must not remap.
- **The ball's room is a `BallSpan` over the space after the words** — layout
  only; the stored text never changes, as the Mac reads the same file.
- **The wash is drawn, never stored**: only italic/bold reach the RTF.
- **Paste is always plain.** Stray spans are stripped after every edit.
- **Never write a colour literal outside `Theme.kt`**; use the ink ladder.
- **Type goes through `houseFont`/`Style`**, in points (dp), never sp — the
  phone's text size would walk the type off the grid.
- **Every raised surface takes paper**: colour, then paper, then clip
  (`Modifier.paper`).
- **Saves happen whenever the app leaves the foreground**, plus the 300ms
  debounce. A never-typed note is never written; an unedited one never
  rewritten (its date orders home).
