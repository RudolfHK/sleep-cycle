package de.sleeptracker.importer

import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** A spreadsheet-like table: header row plus data rows (cells as text, missing = ""). */
data class Table(val header: List<String>, val rows: List<List<String>>, val kind: Kind) {
    enum class Kind { CSV, XLSX }
}

/** Reads CSV or XLSX bytes into a [Table]. Pure JVM, no dependencies. */
object TableReader {

    const val MAX_BYTES = 30 * 1024 * 1024

    fun read(bytes: ByteArray): Table {
        require(bytes.size <= MAX_BYTES) { "File too large" }
        return if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) readXlsx(bytes) else readCsv(bytes)
    }

    // --- CSV ----------------------------------------------------------------------------------

    fun readCsv(bytes: ByteArray): Table {
        var text = decode(bytes)
        text = text.removePrefix("﻿")
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        val delimiter = listOf(';', ',', '\t').maxBy { d -> countOutsideQuotes(firstLine, d) }
        val records = parseCsv(text, delimiter).filter { r -> r.any { it.isNotBlank() } }
        if (records.isEmpty()) return Table(emptyList(), emptyList(), Table.Kind.CSV)
        return Table(records.first().map(::cleanText), records.drop(1).map { r -> r.map(::cleanText) }, Table.Kind.CSV)
    }

    /** UTF-8 if valid, otherwise Windows-1252 (older exports opened in Excel). */
    private fun decode(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, charset("windows-1252"))
    }

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var inQuotes = false
        var n = 0
        for (c in line) {
            if (c == '"') inQuotes = !inQuotes else if (c == d && !inQuotes) n++
        }
        return n
    }

    /** RFC 4180 parser (quoted fields, doubled quotes, newlines inside quotes, CRLF). */
    fun parseCsv(text: String, delimiter: Char): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                delimiter -> { row += field.toString(); field.clear() }
                '\r' -> Unit
                '\n' -> { row += field.toString(); field.clear(); out += row; row = ArrayList() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); out += row }
        return out
    }

    // --- XLSX ---------------------------------------------------------------------------------

    fun readXlsx(bytes: ByteArray): Table {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            var total = 0L
            while (e != null) {
                if (!e.isDirectory && (e.name.endsWith(".xml") || e.name.endsWith(".rels"))) {
                    val data = zip.readBytes()
                    total += data.size
                    require(total < 200L * 1024 * 1024) { "Spreadsheet too large" }
                    entries[e.name.removePrefix("/")] = data
                }
                e = zip.nextEntry
            }
        }
        val shared = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings) ?: emptyList()
        val sheetPath = firstSheetPath(entries) ?: error("No worksheet found")
        val sheet = entries[sheetPath] ?: error("Worksheet missing")
        val rows = parseSheet(sheet, shared).filter { r -> r.any { it.isNotBlank() } }
        if (rows.isEmpty()) return Table(emptyList(), emptyList(), Table.Kind.XLSX)
        return Table(rows.first().map(::cleanText), rows.drop(1).map { r -> r.map(::cleanText) }, Table.Kind.XLSX)
    }

    private fun xml(bytes: ByteArray): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        // No external entities / DTDs (files come from outside the app).
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun Element.children(tag: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && (n.tagName == tag || n.tagName.endsWith(":$tag"))) out += n
        }
        return out
    }

    private fun Element.descendants(tag: String): List<Element> {
        val nl = getElementsByTagName(tag)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> =
        xml(bytes).documentElement.children("si").map { si ->
            // Plain <t> or rich text runs <r><t>…</t></r>; phonetic runs (<rPh>) are ignored.
            val direct = si.children("t")
            if (direct.isNotEmpty()) direct.joinToString("") { it.textContent }
            else si.children("r").joinToString("") { r -> r.children("t").joinToString("") { it.textContent } }
        }

    private fun firstSheetPath(entries: Map<String, ByteArray>): String? {
        val workbook = entries["xl/workbook.xml"]
        val rels = entries["xl/_rels/workbook.xml.rels"]
        if (workbook != null && rels != null) {
            val firstSheet = xml(workbook).documentElement.descendants("sheet").firstOrNull()
            val rid = firstSheet?.getAttribute("r:id")?.takeIf { it.isNotEmpty() }
            if (rid != null) {
                val target = xml(rels).documentElement.descendants("Relationship").firstOrNull { it.getAttribute("Id") == rid }
                    ?.getAttribute("Target")
                if (target != null) {
                    val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
                    if (entries.containsKey(path)) return path
                }
            }
        }
        return entries.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.minOrNull()
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (row in xml(bytes).documentElement.descendants("row")) {
            val cells = HashMap<Int, String>()
            var nextCol = 0
            for (c in row.children("c")) {
                val ref = c.getAttribute("r")
                val col = if (ref.isNotEmpty()) columnIndex(ref) else nextCol
                nextCol = col + 1
                val type = c.getAttribute("t")
                val v = c.children("v").firstOrNull()?.textContent ?: ""
                cells[col] = when (type) {
                    "s" -> v.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> c.children("is").firstOrNull()?.let { isEl ->
                        isEl.children("t").joinToString("") { it.textContent }.ifEmpty {
                            isEl.children("r").joinToString("") { r -> r.children("t").joinToString("") { it.textContent } }
                        }
                    } ?: ""
                    "b" -> if (v == "1") "true" else "false"
                    else -> v // numbers (incl. date serials), "str" formula results, "e" errors
                }
            }
            val width = (cells.keys.maxOrNull() ?: -1) + 1
            out += List(width) { cells[it] ?: "" }
        }
        return out
    }

    /** "AB12" → 27 (0-based column). */
    fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return n - 1
    }

    /**
     * Repairs artefacts of CSVs that went through Excel/Sheets: `_xHHHH_` escapes, a BOM decoded
     * as Latin-1 ("ï»¿") and UTF-8 em/en dashes decoded as Latin-1.
     */
    fun cleanText(s: String): String {
        var t = Regex("_x([0-9A-Fa-f]{4})_").replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        t = t.removePrefix("﻿").removePrefix("ï»¿")
        t = t.replace("â\u0080\u0094", "—").replace("â\u0080\u0093", "–").replace("â€”", "—").replace("â€“", "–")
        return t.trim()
    }
}
