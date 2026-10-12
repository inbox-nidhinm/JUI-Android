package com.nidhin.jui

import java.nio.charset.Charset

// Notes are RTF so italics survive, and the same RTF the Mac and the iPhone
// write: a vault copied between the three keeps every slanted word. Android
// has no RTF reader, so this is one — enough of the format for what Cocoa's
// NSAttributedString writes (and reads back), and nothing more. Only the
// characters and their italic/bold survive a read; fonts, sizes, colours and
// paragraph styles are rebuilt by the page on every load, as on the other two,
// so there is nothing to keep of them.
object Rtf {

    // MARK: Reading

    /** The note in `bytes`, or null when they are not RTF at all. */
    fun read(bytes: ByteArray): StyledText? {
        // ISO-8859-1 maps each byte to one char, so raw bytes survive until a
        // \'hh or a stray 8-bit character is decoded through the code page.
        val src = String(bytes, Charsets.ISO_8859_1)
        if (!src.trimStart().startsWith("{\\rtf")) return null
        return Reader(src).run()
    }

    // The character state a group carries, and restores on leaving it.
    private data class State(
        var italic: Boolean = false,
        var bold: Boolean = false,
        var font: Int = -1,
        var uc: Int = 1, // how many fallback characters follow each \uN
        var skip: Boolean = false, // inside a destination that isn't text
        var fontTable: Boolean = false,
    )

    private class Reader(private val src: String) {
        private var i = 0
        private var state = State()
        private val stack = ArrayDeque<State>()
        private val text = StringBuilder()
        private val runs = ArrayList<StyleRun>()
        // Fonts by number, as the font table names them: which lean by name.
        private val fontItalic = HashMap<Int, Boolean>()
        private val fontBold = HashMap<Int, Boolean>()
        private var tableFont = -1
        private val tableName = StringBuilder()
        private var charset: Charset = cp1252
        // Fallback characters still to drop after a \uN.
        private var pendingSkip = 0
        // The first token of a group decides whether it is a destination.
        private var groupStart = false

        fun run(): StyledText {
            while (i < src.length) {
                val c = src[i]
                when (c) {
                    '{' -> {
                        stack.addLast(state.copy())
                        groupStart = true
                        i++
                        continue
                    }
                    '}' -> {
                        if (state.fontTable) endFontEntry()
                        state = stack.removeLastOrNull() ?: State()
                        i++
                    }
                    '\\' -> controlSymbolOrWord()
                    '\r', '\n' -> i++ // line breaks in the source are not text
                    else -> {
                        i++
                        if (pendingSkip > 0) {
                            pendingSkip--
                        } else {
                            emitByte(c)
                        }
                    }
                }
                groupStart = false
            }
            return StyledText(text.toString(), runs.toList())
        }

        private fun controlSymbolOrWord() {
            i++ // the backslash
            if (i >= src.length) return
            val c = src[i]
            if (c.isLetter() && c.code < 128) {
                val nameStart = i
                while (i < src.length && src[i].isLetter() && src[i].code < 128) i++
                val name = src.substring(nameStart, i)
                var param: Int? = null
                if (i < src.length && (src[i] == '-' || src[i].isDigit())) {
                    val pStart = i
                    if (src[i] == '-') i++
                    while (i < src.length && src[i].isDigit()) i++
                    param = src.substring(pStart, i).toIntOrNull()
                }
                if (i < src.length && src[i] == ' ') i++ // the delimiter is not text
                val atStart = groupStart
                groupStart = false
                word(name, param, atStart)
                return
            }
            i++
            groupStart = false
            when (c) {
                '\'' -> {
                    val hex = src.substring(i, minOf(i + 2, src.length))
                    i += hex.length
                    val byte = hex.toIntOrNull(16) ?: return
                    if (pendingSkip > 0) {
                        pendingSkip--
                        return
                    }
                    emitByte(byte.toChar())
                }
                '\n', '\r' -> emit('\n') // Cocoa's paragraph break: a backslash and a newline
                '\\', '{', '}' -> emit(c)
                '~' -> emit(' ')
                '_' -> emit('‑')
                '-' -> {} // optional hyphen: nothing to show
                '*' -> state.skip = true // an ignorable destination
                else -> {}
            }
        }

        private fun word(name: String, param: Int?, atGroupStart: Boolean) {
            if (atGroupStart && name in destinations) {
                if (name == "fonttbl") {
                    state.fontTable = true
                    state.skip = true
                } else {
                    state.skip = true
                }
                return
            }
            // A \uN or a symbol word counts as one fallback character too.
            if (pendingSkip > 0 && name != "u") {
                // Control words are not characters, except for those that
                // stand for one.
                if (name in symbols) {
                    pendingSkip--
                    return
                }
            }
            when (name) {
                "ansicpg" -> param?.let { cp -> runCatching { Charset.forName("windows-$cp") }.getOrNull()?.let { charset = it } }
                "f" -> {
                    if (state.fontTable) {
                        endFontEntry()
                        tableFont = param ?: -1
                    } else {
                        state.font = param ?: -1
                    }
                }
                "i" -> state.italic = param != 0
                "b" -> state.bold = param != 0
                "plain" -> {
                    state.italic = false
                    state.bold = false
                    state.font = -1
                }
                "uc" -> state.uc = param ?: 1
                "u" -> {
                    val code = param ?: return
                    emit((if (code < 0) code + 65536 else code).toChar())
                    pendingSkip = state.uc
                }
                "par" -> emit('\n')
                "line" -> emit(' ')
                "tab" -> emit('\t')
                "emdash" -> emit('—')
                "endash" -> emit('–')
                "lquote" -> emit('‘')
                "rquote" -> emit('’')
                "ldblquote" -> emit('“')
                "rdblquote" -> emit('”')
                "bullet" -> emit('•')
                "emspace" -> emit(' ')
                "enspace" -> emit(' ')
                else -> {}
            }
        }

        private fun endFontEntry() {
            if (tableFont >= 0) {
                val name = tableName.toString().trim().trimEnd(';').lowercase()
                fontItalic[tableFont] = "italic" in name || "oblique" in name
                fontBold[tableFont] = "bold" in name
            }
            tableFont = -1
            tableName.setLength(0)
        }

        /** A byte from the source, through the document's code page. */
        private fun emitByte(c: Char) {
            if (state.fontTable) {
                if (c == ';') {
                    endFontEntry()
                } else {
                    tableName.append(c)
                }
                return
            }
            if (c.code < 128) emit(c)
            else emit(String(byteArrayOf(c.code.toByte()), charset).firstOrNull() ?: c)
        }

        private fun emit(c: Char) {
            if (state.skip) return
            val italic = state.italic || fontItalic[state.font] == true
            val bold = state.bold || fontBold[state.font] == true
            val at = text.length
            text.append(c)
            if (!italic && !bold) return
            val last = runs.lastOrNull()
            if (last != null && last.end == at && last.italic == italic && last.bold == bold) {
                runs[runs.size - 1] = last.copy(end = at + 1)
            } else {
                runs += StyleRun(at, at + 1, italic, bold)
            }
        }
    }

    // Groups opened by these hold no text of the note.
    private val destinations = setOf(
        "fonttbl", "colortbl", "expandedcolortbl", "stylesheet", "info", "pict", "header",
        "footer", "headerl", "headerr", "footerl", "footerr", "footnote", "listtable",
        "listoverridetable", "revtbl", "rsidtbl", "generator", "xmlnstbl", "themedata",
        "colorschememapping", "latentstyles", "datastore", "fldinst", "object", "nonshppict",
        "shppict", "author", "title", "subject", "operator", "company", "doccomm",
        "keywords", "comment", "creatim", "revtim", "printim", "buptim",
    )

    // Words that stand for a character, so count as a \uN fallback.
    private val symbols = setOf(
        "par", "line", "tab", "emdash", "endash", "lquote", "rquote", "ldblquote",
        "rdblquote", "bullet", "emspace", "enspace",
    )

    private val cp1252: Charset = Charset.forName("windows-1252")

    // MARK: Writing

    // The font table Cocoa writes for Courier, in the order it writes it. The
    // Mac restyles every run into its own family on load, keeping only italic
    // and bold, so the names only have to carry the lean — and \i and \b say
    // it again, for any reader that looks at those instead.
    private const val HEADER =
        "{\\rtf1\\ansi\\ansicpg1252\\cocoartf2822\n" +
            "\\cocoatextscaling0\\cocoaplatform0{\\fonttbl\\f0\\fmodern\\fcharset0 Courier;" +
            "\\f1\\fmodern\\fcharset0 Courier-Oblique;\\f2\\fmodern\\fcharset0 Courier-Bold;\n" +
            "\\f3\\fmodern\\fcharset0 Courier-BoldOblique;}\n" +
            "{\\colortbl;\\red255\\green255\\blue255;}\n" +
            "{\\*\\expandedcolortbl;;}\n" +
            "\\pard\\tx720\\pardirnatural\\partightenfactor0\n"

    fun write(note: StyledText): ByteArray {
        val out = StringBuilder(HEADER)
        val text = note.text
        // Each character's lean, read off the runs.
        val italic = BooleanArray(text.length)
        val bold = BooleanArray(text.length)
        for (run in note.runs) {
            for (k in run.start.coerceAtLeast(0) until run.end.coerceAtMost(text.length)) {
                if (run.italic) italic[k] = true
                if (run.bold) bold[k] = true
            }
        }
        var curItalic = false
        var curBold = false
        out.append("\n\\f0\\fs32 \\cf0 ")
        for (k in text.indices) {
            if (italic[k] != curItalic || bold[k] != curBold) {
                val font = (if (italic[k]) 1 else 0) + (if (bold[k]) 2 else 0)
                out.append("\n\\f").append(font)
                if (italic[k] != curItalic) out.append(if (italic[k]) "\\i" else "\\i0")
                if (bold[k] != curBold) out.append(if (bold[k]) "\\b" else "\\b0")
                out.append(' ')
                curItalic = italic[k]
                curBold = bold[k]
            }
            when (val c = text[k]) {
                '\\' -> out.append("\\\\")
                '{' -> out.append("\\{")
                '}' -> out.append("\\}")
                '\n' -> out.append("\\\n")
                '\r' -> out.append("\\uc0\\u13 ") // kept, not made a paragraph
                '\t' -> out.append("\\tab ")
                ' ' -> out.append("\\line ")
                else -> if (c.code in 0x20..0x7E) out.append(c)
                else out.append("\\uc0\\u").append(c.code).append(' ')
            }
        }
        out.append('}')
        return out.toString().toByteArray(Charsets.US_ASCII)
    }
}
