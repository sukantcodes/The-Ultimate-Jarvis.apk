package com.jarvis.assistant.tools.files

/** DOCX, XLSX and PPTX are ZIP files of XML parts; these read them without any office library. */
internal object Ooxml {
    fun part(parts: Map<String, ByteArray>, name: String): String? = parts[name]?.let { Text.decode(it) }
    fun natural(a: String, b: String): Int {
        val na = Regex("\\d+").findAll(a).lastOrNull()?.value?.toIntOrNull() ?: 0
        val nb = Regex("\\d+").findAll(b).lastOrNull()?.value?.toIntOrNull() ?: 0
        return if (na != nb) na.compareTo(nb) else a.compareTo(b)
    }
}

class DocxHandler : FileHandler {
    override val id = "docx"
    override fun canHandle(name: String, mime: String?, head: ByteArray) = Mimes.ext(name) in setOf("docx", "docm", "dotx") && SafeZip.looksLikeZip(head)
    override fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument {
        val parts = SafeZip.read(src.bytes, ctx.limits) { it.startsWith("word/") && it.endsWith(".xml") && !it.contains("/_rels/") }
        val main = Ooxml.part(parts, "word/document.xml") ?: throw ExtractionFailedException("This is not a valid Word document (word/document.xml is missing).")
        val sections = arrayListOf(DocSection("", paragraphs(main)))
        parts.keys.filter { it.startsWith("word/header") }.sortedWith(Ooxml::natural).forEach { k -> paragraphs(Ooxml.part(parts, k)!!).takeIf { it.isNotBlank() }?.let { sections += DocSection("Header", it) } }
        parts.keys.filter { it.startsWith("word/footer") }.sortedWith(Ooxml::natural).forEach { k -> paragraphs(Ooxml.part(parts, k)!!).takeIf { it.isNotBlank() }?.let { sections += DocSection("Footer", it) } }
        Ooxml.part(parts, "word/footnotes.xml")?.let { paragraphs(it).takeIf { t -> t.isNotBlank() }?.let { t -> sections += DocSection("Footnotes", t) } }
        return ExtractedDocument(src.name, Mimes.guess("x.docx"), DocKind.DOCUMENT, sections, sizeBytes = src.bytes.size.toLong())
    }

    internal fun paragraphs(xml: String): String {
        val out = StringBuilder(); val para = StringBuilder()
        var heading = 0; var list = false; var tableDepth = 0
        val row = ArrayList<String>(); val cell = StringBuilder(); var inCell = false
        XmlScan.scan(xml, object : XmlEvents {
            override fun start(name: String, attrs: String, selfClosing: Boolean) {
                when (XmlScan.local(name)) {
                    "p" -> { para.setLength(0); heading = 0; list = false }
                    "pStyle" -> { val v = XmlScan.attr(attrs, "w:val") ?: XmlScan.attr(attrs, "val") ?: ""; Regex("(?i)heading\\s*(\\d)").find(v)?.let { heading = it.groupValues[1].toInt() }; if (v.equals("Title", true)) heading = 1 }
                    "numPr" -> list = true
                    "tab" -> para.append('\t')
                    "br", "cr" -> para.append('\n')
                    "tbl" -> tableDepth++
                    "tr" -> row.clear()
                    "tc" -> { inCell = true; cell.setLength(0) }
                }
            }
            override fun end(name: String) {
                when (XmlScan.local(name)) {
                    "p" -> {
                        val t = para.toString().trimEnd()
                        if (inCell) { if (t.isNotBlank()) { if (cell.isNotEmpty()) cell.append(' '); cell.append(t) } }
                        else if (t.isNotBlank()) out.append(if (heading > 0) "#".repeat(heading.coerceAtMost(6)) + " " else if (list) "- " else "").append(t).append("\n\n")
                    }
                    "tc" -> { row.add(cell.toString()); inCell = false }
                    "tr" -> { if (row.any { it.isNotBlank() }) out.append(row.joinToString(" | ")).append('\n') }
                    "tbl" -> { tableDepth--; out.append('\n') }
                }
            }
            override fun text(text: String) { if (isTextNode) para.append(XmlScan.decode(text)) }
            private var isTextNode = false
        }.let { h -> object : XmlEvents { // track <w:t> so only run text is collected
            var inT = false
            override fun start(name: String, attrs: String, selfClosing: Boolean) { if (XmlScan.local(name) == "t") inT = !selfClosing; h.start(name, attrs, selfClosing) }
            override fun end(name: String) { if (XmlScan.local(name) == "t") inT = false; h.end(name) }
            override fun text(text: String) { if (inT) para.append(XmlScan.decode(text)) }
        } })
        return out.toString().trim()
    }
}

class XlsxHandler : FileHandler {
    override val id = "xlsx"
    override fun canHandle(name: String, mime: String?, head: ByteArray) = Mimes.ext(name) in setOf("xlsx", "xlsm", "xltx") && SafeZip.looksLikeZip(head)

    override fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument {
        val parts = SafeZip.read(src.bytes, ctx.limits) { it.startsWith("xl/") && it.endsWith(".xml") || it.endsWith(".rels") && it.startsWith("xl/") }
        val workbook = Ooxml.part(parts, "xl/workbook.xml") ?: throw ExtractionFailedException("This is not a valid Excel file (xl/workbook.xml is missing).")
        val shared = sharedStrings(Ooxml.part(parts, "xl/sharedStrings.xml"))
        val rels = HashMap<String, String>()
        XmlScan.scan(Ooxml.part(parts, "xl/_rels/workbook.xml.rels").orEmpty(), object : XmlEvents {
            override fun start(name: String, attrs: String, selfClosing: Boolean) {
                if (XmlScan.local(name) == "Relationship") { val id = XmlScan.attr(attrs, "Id"); val t = XmlScan.attr(attrs, "Target"); if (id != null && t != null) rels[id] = t.removePrefix("/").let { if (it.startsWith("xl/")) it else "xl/$it" } }
            }
        })
        val sheets = ArrayList<Pair<String, String>>()
        XmlScan.scan(workbook, object : XmlEvents {
            override fun start(name: String, attrs: String, selfClosing: Boolean) {
                if (XmlScan.local(name) == "sheet") {
                    val n = XmlScan.attr(attrs, "name") ?: "Sheet${sheets.size + 1}"; val rid = XmlScan.attr(attrs, "r:id") ?: XmlScan.attr(attrs, "id")
                    sheets += XmlScan.decode(n) to (rels[rid] ?: "xl/worksheets/sheet${sheets.size + 1}.xml")
                }
            }
        })
        val sections = ArrayList<DocSection>(); val tables = ArrayList<TableData>(); val warnings = ArrayList<String>(); var truncated = false
        for ((name, path) in sheets) {
            val xml = Ooxml.part(parts, path) ?: run { warnings += "Sheet \"$name\" could not be found in the file."; null } ?: continue
            val (rows, cut) = sheetRows(xml, shared, ctx.limits.maxSheetRows)
            if (cut) { truncated = true; warnings += "Sheet \"$name\" was cut after ${rows.size} rows (row limit)." }
            tables += TableData(name, rows)
            sections += DocSection("Sheet: $name", if (rows.isEmpty()) "(empty)" else Text.tableText(rows))
        }
        val styles = Ooxml.part(parts, "xl/styles.xml").orEmpty()
        if (Regex("(?i)yy|mm-dd|d-mmm|h:mm").containsMatchIn(styles)) warnings += "Dates and times in spreadsheets are stored as serial numbers, so date cells can appear as plain numbers."
        return ExtractedDocument(src.name, Mimes.guess("x.xlsx"), DocKind.TABLE, sections, warnings, truncated = truncated, tables = tables, meta = mapOf("sheets" to sheets.size.toString()), sizeBytes = src.bytes.size.toLong())
    }

    private fun sharedStrings(xml: String?): List<String> {
        if (xml == null) return emptyList()
        val out = ArrayList<String>(); val cur = StringBuilder(); var inT = false; var inRph = false
        XmlScan.scan(xml, object : XmlEvents {
            override fun start(name: String, attrs: String, selfClosing: Boolean) { when (XmlScan.local(name)) { "si" -> cur.setLength(0); "t" -> inT = !selfClosing; "rPh" -> inRph = true } }
            override fun end(name: String) { when (XmlScan.local(name)) { "si" -> out.add(cur.toString()); "t" -> inT = false; "rPh" -> inRph = false } }
            override fun text(text: String) { if (inT && !inRph) cur.append(XmlScan.decode(text)) }
        })
        return out
    }

    private fun colIndex(ref: String): Int { var n = 0; for (c in ref) { if (c in 'A'..'Z') n = n * 26 + (c - 'A' + 1) else if (c in 'a'..'z') n = n * 26 + (c - 'a' + 1) else break }; return n - 1 }

    private fun sheetRows(xml: String, shared: List<String>, maxRows: Int): Pair<List<List<String>>, Boolean> {
        val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); var cut = false
        var type = ""; var col = 0; var inV = false; var inIs = false; var inT = false; val v = StringBuilder(); val isText = StringBuilder()
        fun put(c: Int, value: String) { while (row.size < c) row.add(""); if (row.size == c) row.add(value) else row[c] = value }
        XmlScan.scan(xml, object : XmlEvents {
            override fun start(name: String, attrs: String, selfClosing: Boolean) {
                when (XmlScan.local(name)) {
                    "row" -> row = ArrayList()
                    "c" -> { type = XmlScan.attr(attrs, "t") ?: ""; col = XmlScan.attr(attrs, "r")?.let { colIndex(it) } ?: row.size; v.setLength(0); isText.setLength(0) }
                    "v" -> inV = !selfClosing
                    "is" -> inIs = !selfClosing
                    "t" -> if (inIs) inT = !selfClosing
                }
            }
            override fun end(name: String) {
                when (XmlScan.local(name)) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "is" -> inIs = false
                    "c" -> {
                        val raw = v.toString().trim()
                        val value = when (type) { "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty(); "inlineStr" -> isText.toString(); "b" -> if (raw == "1") "TRUE" else "FALSE"; else -> XmlScan.decode(raw) }
                        if (value.isNotEmpty()) put(col, value)
                    }
                    "row" -> { if (!cut) { while (row.isNotEmpty() && row.last().isEmpty()) row.removeAt(row.size - 1); rows.add(row); if (rows.size >= maxRows) cut = true } }
                }
            }
            override fun text(text: String) { if (inV) v.append(text) else if (inT) isText.append(XmlScan.decode(text)) }
        })
        while (rows.isNotEmpty() && rows.last().isEmpty()) rows.removeAt(rows.size - 1)
        return rows to cut
    }
}

class PptxHandler : FileHandler {
    override val id = "pptx"
    override fun canHandle(name: String, mime: String?, head: ByteArray) = Mimes.ext(name) in setOf("pptx", "pptm", "potx", "ppsx") && SafeZip.looksLikeZip(head)
    override fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument {
        val parts = SafeZip.read(src.bytes, ctx.limits) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") || it.startsWith("ppt/notesSlides/notesSlide") && it.endsWith(".xml") }
        val slides = parts.keys.filter { it.startsWith("ppt/slides/") }.sortedWith(Ooxml::natural)
        if (slides.isEmpty()) throw ExtractionFailedException("This is not a valid PowerPoint file (no slides found).")
        val sections = ArrayList<DocSection>()
        slides.forEachIndexed { i, k ->
            val n = Regex("(\\d+)\\.xml$").find(k)?.groupValues?.get(1)
            val notes = parts["ppt/notesSlides/notesSlide$n.xml"]?.let { text(Text.decode(it)) }.orEmpty()
            sections += DocSection("Slide ${i + 1}", text(Text.decode(parts[k]!!)).ifBlank { "(no text)" } + if (notes.isNotBlank()) "\n\nNotes: $notes" else "")
        }
        return ExtractedDocument(src.name, Mimes.guess("x.pptx"), DocKind.PRESENTATION, sections, pageCount = slides.size, sizeBytes = src.bytes.size.toLong())
    }
    private fun text(xml: String): String {
        val out = StringBuilder(); val para = StringBuilder(); var inT = false
        XmlScan.scan(xml, object : XmlEvents {
            override fun start(name: String, attrs: String, selfClosing: Boolean) { when (XmlScan.local(name)) { "p" -> para.setLength(0); "t" -> inT = !selfClosing; "br" -> para.append('\n') } }
            override fun end(name: String) { when (XmlScan.local(name)) { "t" -> inT = false; "p" -> { if (para.isNotBlank()) out.append(para.toString().trim()).append('\n') } } }
            override fun text(text: String) { if (inT) para.append(XmlScan.decode(text)) }
        })
        return out.toString().trim()
    }
}
