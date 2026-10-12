package com.nidhin.jui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RtfTest {
    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    private fun roundTrip(note: StyledText) = assertEquals(note, Rtf.read(Rtf.write(note)))

    @Test fun notRtfIsNull() {
        assertNull(Rtf.read("just words".toByteArray()))
    }

    @Test fun plainTextRoundTrips() {
        roundTrip(StyledText("one\n\ntwo  spaced\tand tabbed\n"))
        roundTrip(StyledText(""))
        roundTrip(StyledText("\n"))
    }

    @Test fun escapesAndNonAsciiRoundTrip() {
        roundTrip(StyledText("{braces} \\back\\slash • café “quotes” — 😀 \u00A0nbsp \u2028line\rcr"))
    }

    @Test fun runsRoundTrip() {
        val text = "plain slanted bold both plain"
        roundTrip(StyledText(text, listOf(
            StyleRun(6, 13, italic = true, bold = false),
            StyleRun(14, 18, italic = false, bold = true),
            StyleRun(19, 23, italic = true, bold = true),
        )))
        // A run at the very start and one running to the end.
        roundTrip(StyledText("ab\ncd", listOf(
            StyleRun(0, 1, italic = true, bold = false),
            StyleRun(3, 5, italic = true, bold = false),
        )))
        // A run that covers a line break.
        roundTrip(StyledText("ab\ncd", listOf(StyleRun(1, 4, italic = true, bold = false))))
    }

    @Test fun writesCocoaHeaderAndFonts() {
        val rtf = String(Rtf.write(StyledText("x", listOf(StyleRun(0, 1, true, false)))))
        assert(rtf.startsWith("{\\rtf1\\ansi\\ansicpg1252\\cocoartf"))
        assert("Courier-Oblique" in rtf)
        assert("\\f1\\i x" in rtf)
    }

    // Written by macOS's textutil, which is Cocoa's own RTF writer — the one
    // NSAttributedString uses on the Mac and the iPhone.
    @Test fun readsCocoaItalicsBoldAndEscapes() {
        val note = assertNotNullAndGet(Rtf.read(fixture("cocoa-textutil.rtf")))
        assertEquals(
            "plain slanted and bold and both\n• café “quotes” — dash 😀 {braces} \\back\n\nlast\n",
            note.text)
        assertEquals(listOf(
            StyleRun(6, 13, italic = true, bold = false),
            StyleRun(18, 22, italic = false, bold = true),
            StyleRun(27, 31, italic = true, bold = true),
        ), note.runs)
    }

    @Test fun readsCocoaPlainText() {
        val note = assertNotNullAndGet(Rtf.read(fixture("cocoa-plain.rtf")))
        assertEquals("one\n• two\n\tthree {x} \\ y\n", note.text)
        assertEquals(emptyList<StyleRun>(), note.runs)
    }

    @Test fun unicodeFallbacksAreSkipped() {
        // \uc1: one fallback character after each \u, which is not text.
        val rtf = "{\\rtf1\\ansi\\uc1 a\\u8226?b\\u-3913?c\\uc2\\u233\\'e9\\'e9d}"
        assertEquals("a•b\uF0B7cé" + "d", Rtf.read(rtf.toByteArray())!!.text)
    }

    @Test fun ignorableDestinationsAreSkipped() {
        val rtf = "{\\rtf1{\\*\\generator Foo;}{\\info{\\title T}}{\\stylesheet{\\s0 Normal;}}body\\par end}"
        assertEquals("body\nend", Rtf.read(rtf.toByteArray())!!.text)
    }

    private fun assertNotNullAndGet(note: StyledText?): StyledText {
        assertNotNull(note)
        return note!!
    }
}
