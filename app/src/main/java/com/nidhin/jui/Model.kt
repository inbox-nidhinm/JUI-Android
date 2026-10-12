package com.nidhin.jui

// Plain data, no Android types, so the store and the RTF codec can be tested
// on the JVM.

/** A run of italic and/or bold over [start, end) of a note's text. */
data class StyleRun(val start: Int, val end: Int, val italic: Boolean, val bold: Boolean)

/**
 * A note as stored: its characters and where they lean. Offsets are UTF-16
 * code units, the same units as the Mac's NSString ranges and the stickies'
 * anchors. Runs are sorted, non-overlapping and never empty; text with no
 * runs is upright throughout.
 */
data class StyledText(val text: String = "", val runs: List<StyleRun> = emptyList()) {
    val length: Int get() = text.length
}

data class NoteMeta(
    val id: String,
    val title: String,
    val modified: Long, // epoch millis
    val folder: String?, // null = unfiled (vault root)
)

data class SearchHit(val id: String, val title: String, val snippet: String, val folder: String?)

/**
 * An annotation anchored to a character range of the note, not to pixels.
 * Same file format as the Mac and iPhone apps, so a vault copied between them
 * keeps its stickies. The phone never pins one, but it keeps pinX/pinY/
 * rotation/left as it found them — and `created`, the day the Mac stamps on a
 * sticky's card, which must not be dropped when the file is rewritten.
 *
 * `id` is the UUID's uppercase string, as Swift writes it; `created` is
 * seconds since 2001-01-01 UTC, Swift's default Date encoding.
 */
data class Sticky(
    val id: String,
    val start: Int,
    val length: Int,
    val text: String,
    val pinX: Double? = null,
    val pinY: Double? = null,
    val rotation: Double? = null,
    val left: Boolean? = null,
    val created: Double? = null,
)
