package com.nidhin.jui

import androidx.compose.ui.graphics.Color

// A theme is a complete recolour of the app. Every colour the app draws comes
// from the active one, so switching swaps the whole surface at once — sheet,
// panel, ink, stickies, rules, the flourish, the onboarding envelope.
//
// Ported from the iPhone app (and the Mac's re-JUI before it) unchanged in
// value: the five alternates hold the cream original's lightness and
// saturation and rotate only hue, so hierarchy and contrast survive in all six.
//
// Held as ARGB ints because the Views and GL need them (text, caret, sticky
// marks); Compose borrows one through `Color(int)`, so each value exists
// exactly once.
data class Theme(
    val id: String,
    val label: String,
    val sheet: Int,       // the page
    val panel: Int,       // panels, palette, settings cards
    val folderBase: Int,  // folder card at rest
    val envelope: Int,    // onboarding envelope
    val letterSheet: Int, // onboarding letter
    val ink: Int,         // every word in the app
    val sticky: Int,      // sticky notes — the anchor tone
    val openDot: Int,     // the dot marking the open note; the picker's ring
    val flourish: Int,    // onboarding's flourish
)

private fun rgb(hex: Int): Int = (0xFF shl 24) or hex

object Themes {
    val cream = Theme(
        "cream", "cream",
        sheet = rgb(0xFDF0D9), panel = rgb(0xECDBBE), folderBase = rgb(0xF3E4CA),
        envelope = rgb(0xEBCEA3), letterSheet = rgb(0xFFFAF3),
        ink = rgb(0x71390D), sticky = rgb(0xF7C82D), openDot = rgb(0xA88D34),
        flourish = rgb(0xF2E3C9))

    val mint = Theme(
        "mint", "mint",
        sheet = rgb(0xDDF9EF), panel = rgb(0xC3E7DA), folderBase = rgb(0xCEEFE3),
        envelope = rgb(0xAAE4CE), letterSheet = rgb(0xF4FEFA),
        ink = rgb(0x176742), sticky = rgb(0x61E7C1), openDot = rgb(0x409C82),
        flourish = rgb(0xCDEEE2))

    val sky = Theme(
        "sky", "sky",
        sheet = rgb(0xD9F1FD), panel = rgb(0xBEDCEC), folderBase = rgb(0xCAE5F3),
        envelope = rgb(0xA3D4EB), letterSheet = rgb(0xF3FBFF),
        ink = rgb(0x0D5A71), sticky = rgb(0x65BFFF), openDot = rgb(0x3478A8),
        flourish = rgb(0xC9E4F2))

    val coral = Theme(
        "coral", "coral",
        sheet = rgb(0xFDE3D9), panel = rgb(0xECCBBE), folderBase = rgb(0xF3D6CA),
        envelope = rgb(0xEBB6A3), letterSheet = rgb(0xFFF6F3),
        ink = rgb(0x711F0D), sticky = rgb(0xFF985E), openDot = rgb(0xA85E34),
        flourish = rgb(0xF2D5C9))

    val lilac = Theme(
        "lilac", "lilac",
        sheet = rgb(0xE6D9FD), panel = rgb(0xCFBEEC), folderBase = rgb(0xD9CAF3),
        envelope = rgb(0xBCA3EB), letterSheet = rgb(0xF7F3FF),
        ink = rgb(0x270D71), sticky = rgb(0xC18FFF), openDot = rgb(0x6834A8),
        flourish = rgb(0xD8C9F2))

    val orchid = Theme(
        "orchid", "orchid",
        sheet = rgb(0xFDD9F8), panel = rgb(0xECBEE6), folderBase = rgb(0xF3CAED),
        envelope = rgb(0xEBA3E2), letterSheet = rgb(0xFFF3FE),
        ink = rgb(0x710D6E), sticky = rgb(0xFF87E5), openDot = rgb(0xA8348F),
        flourish = rgb(0xF2C9EC))

    // order is the order of the dots, in both onboarding and settings
    val all = listOf(cream, mint, sky, coral, lilac, orchid)

    fun named(id: String?): Theme = all.firstOrNull { it.id == id } ?: cream
}

/** This colour moved `fraction` of the way toward `other`, in sRGB. */
fun blend(color: Int, fraction: Float, toward: Int): Int {
    val t = fraction.coerceIn(0f, 1f)
    fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
    fun mix(shift: Int) = (ch(color, shift) + (ch(toward, shift) - ch(color, shift)) * t).toInt()
    return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

/** The colour at `alpha` (0…1) — for the Views; Compose uses `.copy(alpha=)`. */
fun withAlpha(color: Int, alpha: Float): Int =
    ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0xFFFFFF)

fun Int.compose(): Color = Color(this)
