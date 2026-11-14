// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.min

/**
 * Document text extraction: pulls **text** out of docx / pptx / xlsx / odt / ods / odp / epub / rtf / html / pdf
 * to feed the chat as context (images, layout, comments, revision history all ignored).
 *
 * Two implementation routes:
 * - **zip family** (OOXML / ODF / EPUB): JDK's built-in zip + XmlPullParser reading tags, zero dependencies;
 * - **PDF**: delegated to pdfbox-android (most common user scenario; writing our own parser isn't worth it,
 *   font encoding / CMap are too easy to trip over);
 * - **97-2003 legacy formats** (.doc / .xls / .ppt) are OLE compound binaries — parsing cost is extreme,
 *   payoff minimal → only prompts to re-save as docx / pdf.
 *
 * All parsing is wrapped in runCatching: bad files return null and the caller supplies the message,
 * never bubbling up to crash the UI.
 */
object DocExtractor {

    /** zip family: just unzip and read XML */
    val ZIP_DOCS = setOf("docx", "pptx", "xlsx", "odt", "ods", "odp", "epub")

    /** Single-file text types (rtf / html) */
    val TEXT_DOCS = setOf("rtf", "html", "htm", "xhtml")

    /** 97-2003 legacy binary Office: can only prompt to re-save */
    val LEGACY_DOCS = setOf("doc", "xls", "ppt")

    /** Extensions this class understands (excludes .txt/.md/.csv etc., those go through the original plain-text path) */
    val ALL: Set<String> = ZIP_DOCS + TEXT_DOCS + LEGACY_DOCS + setOf("pdf")

    /** Raw byte cap for zip / pdf */
    const val MAX_DOC_BYTES = 48L * 1024 * 1024

    /** rtf / html aren't compressed — don't let the user stuff tens of MB in */
    private const val MAX_TEXT_DOC_BYTES = 8L * 1024 * 1024

    /** Max pages to parse from PDF (more than that is far beyond local model context anyway) */
    private const val MAX_PDF_PAGES = 60

    /**
     * Extraction result.
     * @param units Count of structural units (pages / slides / sheets); 0 = no such concept
     * @param unitRes Resource id for the unit label (R.string.s_386 pages / s_387 slides / s_388 sheets)
     */
    class Ok(val text: String, val units: Int = 0, val unitRes: Int = 0)

    fun extract(ctx: Context, file: File, ext: String): Ok? = runCatching {
        when (ext.lowercase()) {
            "docx" -> readWord(file)
            "pptx" -> readPptx(file)
            "xlsx" -> readXlsx(file)
            "odt", "ods", "odp" -> readOdf(file)
            "epub" -> readEpub(file)
            "rtf" -> if (file.length() <= MAX_TEXT_DOC_BYTES)
                Ok(clean(stripRtf(String(file.readBytes(), Charsets.ISO_8859_1)))) else null
            "html", "htm", "xhtml" -> if (file.length() <= MAX_TEXT_DOC_BYTES)
                Ok(clean(htmlToText(String(file.readBytes(), Charsets.UTF_8)))) else null
            "pdf" -> readPdf(ctx, file)
            else -> null
        }
    }.getOrNull()?.takeIf { it.text.isNotBlank() }

    // ==================== zip family ====================

    private fun readWord(file: File): Ok? = withZip(file) { z ->
        val entry = z.getEntry("word/document.xml") ?: return@withZip null
        val sb = StringBuilder()
        z.getInputStream(entry).use { ins ->
            walkXml(ins) { ev, name, text, _ ->
                when (ev) {
                    XmlPullParser.TEXT -> if (name == "t") sb.append(text)
                    XmlPullParser.START_TAG -> if (name == "tab") sb.append('\t')
                    XmlPullParser.END_TAG -> if (name == "p" || name == "br" || name == "cr") sb.append('\n')
                }
            }
        }
        Ok(clean(sb.toString()))
    }

    private fun readPptx(file: File): Ok? = withZip(file) { z ->
        // slide1, slide2 … slide10 must be sorted numerically, not lexicographically
        val slides = z.entries().toList()
            .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
            .sortedBy { it.name.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (slides.isEmpty()) return@withZip null
        val sb = StringBuilder()
        slides.forEachIndexed { idx, entry ->
            if (idx > 0) sb.append("\n\n")
            z.getInputStream(entry).use { ins ->
                walkXml(ins) { ev, name, text, _ ->
                    when (ev) {
                        XmlPullParser.TEXT -> if (name == "t") sb.append(text)
                        XmlPullParser.END_TAG -> if (name == "p") sb.append('\n')
                    }
                }
            }
        }
        Ok(clean(sb.toString()), slides.size, R.string.s_387)
    }

    /** xlsx: read shared string table first, then assemble each sheet's cells row by row (tab-separated) */
    private fun readXlsx(file: File): Ok? = withZip(file) { z ->
        val shared = ArrayList<String>()
        z.getEntry("xl/sharedStrings.xml")?.let { e ->
            z.getInputStream(e).use { ins ->
                val cur = StringBuilder()
                walkXml(ins) { ev, name, text, _ ->
                    when (ev) {
                        XmlPullParser.TEXT -> if (name == "t") cur.append(text)
                        XmlPullParser.END_TAG -> if (name == "si") {
                            shared.add(cur.toString()); cur.setLength(0)
                        }
                    }
                }
            }
        }
        val sheets = z.entries().toList()
            .filter { it.name.startsWith("xl/worksheets/") && it.name.endsWith(".xml") }
            .sortedBy { it.name }
        if (sheets.isEmpty()) return@withZip null

        val sb = StringBuilder()
        var rowBuf = StringBuilder()
        var cellBuf = StringBuilder()
        var cellType = ""
        var reading = false
        sheets.forEachIndexed { idx, entry ->
            if (idx > 0) sb.append("\n\n")
            if (sheets.size > 1) sb.append("Sheet ${idx + 1}\n")
            rowBuf = StringBuilder(); cellBuf = StringBuilder()
            z.getInputStream(entry).use { ins ->
                walkXml(ins) { ev, name, text, p ->
                    when (ev) {
                        XmlPullParser.START_TAG -> when (name) {
                            "c" -> cellType = p.getAttributeValue(null, "t") ?: ""
                            "v", "t" -> reading = true
                        }
                        XmlPullParser.TEXT -> if (reading) cellBuf.append(text)
                        XmlPullParser.END_TAG -> when (name) {
                            "v", "t" -> reading = false
                            "c" -> {
                                val raw = cellBuf.toString()
                                val shown = if (cellType == "s")
                                    shared.getOrNull(raw.trim().toIntOrNull() ?: -1) ?: "" else raw
                                rowBuf.append(shown).append('\t')
                                cellBuf.setLength(0); cellType = ""
                            }
                            "row" -> {
                                sb.append(rowBuf.toString().trimEnd('\t')).append('\n')
                                rowBuf = StringBuilder()
                            }
                        }
                    }
                }
            }
        }
        Ok(clean(sb.toString()), sheets.size, R.string.s_388)
    }

    /** ODF (odt / ods / odp): text:p / text:h in content.xml are the paragraphs */
    private fun readOdf(file: File): Ok? = withZip(file) { z ->
        val entry = z.getEntry("content.xml") ?: return@withZip null
        val sb = StringBuilder()
        z.getInputStream(entry).use { ins ->
            walkXml(ins) { ev, name, text, _ ->
                when (ev) {
                    XmlPullParser.TEXT -> if (name == "p" || name == "h") sb.append(text)
                    XmlPullParser.START_TAG -> if (name == "table-cell") sb.append('\t')
                    XmlPullParser.END_TAG -> if (name == "p" || name == "h" || name == "table-row") sb.append('\n')
                }
            }
        }
        Ok(clean(sb.toString()))
    }

    private fun readEpub(file: File): Ok? = withZip(file) { z ->
        val pages = z.entries().toList()
            .filter { it.name.endsWith(".xhtml") || it.name.endsWith(".html") || it.name.endsWith(".htm") }
            .sortedBy { it.name }
        if (pages.isEmpty()) return@withZip null
        val sb = StringBuilder()
        pages.forEach { entry ->
            z.getInputStream(entry).use { ins ->
                sb.append(htmlToText(String(ins.readBytes(), Charsets.UTF_8))).append("\n\n")
            }
        }
        Ok(clean(sb.toString()), pages.size, R.string.s_386)
    }

    private inline fun <T> withZip(file: File, block: (ZipFile) -> T?): T? =
        runCatching { ZipFile(file).use { block(it) } }.getOrNull()

    // ==================== XML traversal ====================

    /**
     * Minimal XML traversal: one callback per event. `name` is the current tag name, `p` the parser
     * (use it to read attributes).
     *
     * TEXT events don't tell you the owning tag, so this maintains a "most recent unclosed start tag"
     * and uses it as the parent on TEXT — accurate enough for the XML this project reads.
     */
    private inline fun walkXml(ins: InputStream, crossinline onEvent: (Int, String, String, XmlPullParser) -> Unit) {
        val p = Xml.newPullParser()
        // Must enable namespace processing: with it on, name is the "local name" (w:t -> t, table:table-cell -> table-cell);
        // off, you'd get the qualified prefixed name and every tag-name branch below would break.
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(ins, null)
        var ev = p.eventType
        var cur = ""
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    cur = p.name ?: ""
                    onEvent(ev, cur, "", p)
                }
                XmlPullParser.TEXT -> onEvent(ev, cur, p.text ?: "", p)
                XmlPullParser.END_TAG -> {
                    val n = p.name ?: ""
                    onEvent(ev, n, "", p)
                    cur = ""
                }
            }
            ev = p.next()
        }
    }

    // ==================== RTF / HTML ====================

    /** RTF: keep only visible text, skip control groups like font table / color table; use \ansicpgN decide encoding */
    private fun stripRtf(src: String): String {
        val out = StringBuilder()
        var i = 0
        var depth = 0
        var skipDepth = -1
        var cp = "windows-1252"
        val pending = java.io.ByteArrayOutputStream()

        fun flushPending() {
            if (pending.size() == 0) return
            out.append(runCatching { String(pending.toByteArray(), charset(cp)) }.getOrDefault(""))
            pending.reset()
        }

        while (i < src.length) {
            val ch = src[i]
            when {
                ch == '{' -> { depth++; i++ }
                ch == '}' -> {
                    flushPending()
                    if (skipDepth >= 0 && depth <= skipDepth) skipDepth = -1
                    depth--; i++
                }
                ch == '\\' -> {
                    if (i + 1 >= src.length) { i++; continue }
                    val n = src[i + 1]
                    when {
                        n == '\\' || n == '{' || n == '}' -> { flushPending(); out.append(n); i += 2 }
                        n == '\'' && i + 3 < src.length -> {
                            val b = src.substring(i + 2, i + 4).toIntOrNull(16)
                            if (b == null) i += 2 else { pending.write(b); i += 4 }
                        }
                        n == '*' -> { if (skipDepth < 0) skipDepth = depth; i += 2 }
                        n.isLetter() -> {
                            var j = i + 1
                            while (j < src.length && src[j].isLetter()) j++
                            val word = src.substring(i + 1, j)
                            var k = j
                            if (k < src.length && (src[k] == '-' || src[k].isDigit())) {
                                while (k < src.length && (src[k] == '-' || src[k].isDigit())) k++
                            }
                            val param = if (k > j) src.substring(j, k).toIntOrNull() else null
                            if (k < src.length && src[k] == ' ') k++   // single space after control word is a separator
                            if (skipDepth < 0) {
                                when (word) {
                                    "par", "line", "sect", "page" -> { flushPending(); out.append('\n') }
                                    "tab" -> { flushPending(); out.append('\t') }
                                    "ansicpg" -> if (param != null) cp = "windows-$param"
                                    "u" -> if (param != null) {
                                        flushPending()
                                        val code = if (param < 0) param + 65536 else param
                                        out.append(code.toChar())
                                        // the char after \uN is a "fallback for unsupported", consume it
                                        if (k < src.length && src[k] != '\\' && src[k] != '{' && src[k] != '}') k++
                                    }
                                    "fonttbl", "colortbl", "stylesheet", "info", "generator", "listtable",
                                    "listoverridetable", "rsidtbl", "xmlnstbl", "pgptbl", "pict" ->
                                        if (skipDepth < 0) skipDepth = depth
                                }
                            }
                            i = k
                        }
                        else -> i += 2
                    }
                }
                skipDepth >= 0 -> i++
                ch == '\r' || ch == '\n' -> i++
                else -> { flushPending(); out.append(ch); i++ }
            }
        }
        flushPending()
        return out.toString()
    }

    /** HTML/XHTML: strip scripts/style and tags, restore basic entities */
    private fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?is)</(p|div|li|tr|h[1-6]|blockquote|section|article|pre)>"), "\n")
        s = s.replace(Regex("(?is)</?(td|th)[^>]*>"), "\t")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        s = s.replace(Regex("&#(x?)([0-9A-Fa-f]+);")) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else ""
        }
        return s
    }

    // ==================== PDF ====================

    private fun readPdf(ctx: Context, file: File): Ok? = runCatching {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
            val pages = doc.numberOfPages
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper().apply {
                sortByPosition = true
                startPage = 1
                endPage = min(pages, MAX_PDF_PAGES)
            }
            val text = stripper.getText(doc)
            if (text.isBlank()) null else Ok(clean(text), pages, R.string.s_386)
        }
    }.getOrNull()

    // ==================== Cleanup ====================

    /** Strips zero-width chars and extra blank lines, also trims trailing spaces — local models are sensitive to this noise */
    private fun clean(s: String): String = s
        .replace('\u0000', ' ')
        .replace("\uFEFF", "")
        .replace('\u00A0', ' ')
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
