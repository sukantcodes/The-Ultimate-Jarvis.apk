package com.jarvis.assistant.tools.files

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.charset.Charset

internal object Text {
    /** Decodes bytes as text: BOM-aware, UTF-8 first, Windows-1252 when the bytes are not valid UTF-8. */
    fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val strict = Charsets.UTF_8.newDecoder()
        return try { strict.decode(java.nio.ByteBuffer.wrap(b)).toString() } catch (e: Exception) { String(b, Charset.forName("windows-1252")) }
    }

    fun looksBinary(b: ByteArray): Boolean {
        val n = minOf(b.size, 8192)
        if (n == 0) return false
        if (b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))) return false
        var bad = 0
        for (i in 0 until n) { val c = b[i].toInt() and 0xFF; if (c == 0 || (c < 9) || (c in 14..31 && c != 27)) bad++ }
        return bad > n / 100
    }

    fun parseCsv(text: String, delimiter: Char, maxRows: Int): Pair<List<List<String>>, Boolean> {
        val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cell = StringBuilder()
        var inQuotes = false; var i = 0; var truncated = false
        fun endCell() { row.add(cell.toString()); cell.setLength(0) }
        fun endRow() { endCell(); if (!(row.size == 1 && row[0].isEmpty())) rows.add(row); row = ArrayList() }
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else inQuotes = false } else cell.append(c)
            } else when {
                c == '"' && cell.isEmpty() -> inQuotes = true
                c == delimiter -> endCell()
                c == '\n' -> { endRow(); if (rows.size >= maxRows) { truncated = true; return rows to true } }
                c == '\r' -> {}
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows to truncated
    }

    fun tableText(rows: List<List<String>>): String = rows.joinToString("\n") { r -> r.joinToString(" | ") { it.replace("\n", " ") } }
}

/** Plain text, Markdown, logs and source code. Also the last resort for anything that is readable text. */
class PlainTextHandler : FileHandler {
    override val id = "text"
    private val exts = setOf("txt", "text", "log", "md", "markdown", "mdx", "rst", "ini", "cfg", "conf", "toml", "yaml", "yml", "properties", "gradle", "kts", "kt", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
        "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "rb", "php", "swift", "sh", "bash", "zsh", "bat", "ps1", "sql", "css", "scss", "less", "vue", "svelte", "dart", "lua", "r", "pl", "scala", "gitignore", "env.example", "tex", "srt", "vtt", "diff", "patch", "lock")
    override fun canHandle(name: String, mime: String?, head: ByteArray): Boolean {
        val ext = Mimes.ext(name)
        if (ext in exts || mime?.startsWith("text/") == true && ext !in setOf("csv", "tsv", "html", "htm", "xml")) return true
        if (name.substringAfterLast('/').lowercase() in setOf("makefile", "dockerfile", "readme", "license", "gemfile", "procfile")) return true
        return false
    }
    override fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument {
        if (Text.looksBinary(src.bytes)) throw UnsupportedFileException("${src.name} looks like a binary file, not text.")
        val ext = Mimes.ext(src.name)
        val kind = when (ext) { "md", "markdown", "mdx" -> DocKind.MARKDOWN; "txt", "text", "log", "rst", "srt", "vtt", "tex" -> DocKind.TEXT; else -> DocKind.CODE }
        return ExtractedDocument(src.name, src.mime ?: Mimes.guess(src.name), kind, listOf(DocSection("", Text.decode(src.bytes))), sizeBytes = src.bytes.size.toLong())
    }
}

/** Any other file whose contents are readable text (unknown extension). Tried last. */
class SniffedTextHandler : FileHandler {
    override val id = "text-sniffed"
    override fun canHandle(name: String, mime: String?, head: ByteArray) = head.isNotEmpty() && !Text.looksBinary(head)
    override fun extract(src: FileSource, ctx: ExtractionContext) =
        ExtractedDocument(src.name, src.mime ?: "text/plain", DocKind.TEXT, listOf(DocSection("", Text.decode(src.bytes))),
            warnings = listOf("The file type is not recognised; it was read as plain text."), sizeBytes = src.bytes.size.toLong())
}

class CsvHandler : FileHandler {
    override val id = "csv"
    override fun canHandle(name: String, mime: String?, head: ByteArray) = Mimes.ext(name) in setOf("csv", "tsv")
    override fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument {
        val tsv = Mimes.ext(src.name) == "tsv"
        val (rows, cut) = Text.parseCsv(Text.decode(src.bytes), if (tsv) '\t' else detectDelimiter(Text.decode(src.bytes)), ctx.limits.maxSheetRows)
        val warnings = if (cut) listOf("Only the first ${rows.size} rows were read (row limit).") else emptyList()
        return ExtractedDocument(src.name, src.mime ?: Mimes.guess(src.name), DocKind.TABLE, listOf(DocSection("", Text.tableText(rows))), warnings, truncated = cut,
            tables = listOf(TableData("data", rows)), meta = mapOf("rows" to rows.size.toString(), "columns" to (rows.maxOfOrNull { it.size } ?: 0).toString()), sizeBytes = src.bytes.size.toLong())
    }
    private fun detectDelimiter(text: String): Char {
        val first = text.lineSequence().firstOrNull().orEmpty()
        return listOf(',', ';', '\t', '|').maxByOrNull { d -> first.count { it == d } }?.takeIf { d -> first.contains(d) } ?: ','
    }
}

class JsonHandler : FileHandler {
    override val id = "json"
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    override fun canHandle(name: String, mime: String?, head: ByteArray) = Mimes.ext(name) in setOf("json", "geojson", "jsonl", "ndjson") || mime == "application/json"
    override fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument {
        val raw = Text.decode(src.bytes)
        if (Mimes.ext(src.name) in setOf("jsonl", "ndjson")) return ExtractedDocument(src.name, "application/x-ndjson", DocKind.DATA, listOf(DocSection("", raw)), sizeBytes = src.bytes.size.toLong())
        return try {
            val el = JsonParser.parseString(raw)
            ExtractedDocument(src.name, "application/json", DocKind.DATA, listOf(DocSection("", gson.toJson(el))), meta = mapOf("structure" to describe(el)), sizeBytes = src.bytes.size.toLong())
        } catch (e: Exception) {
            ExtractedDocument(src.name, "application/json", DocKind.DATA, listOf(DocSection("", raw)), warnings = listOf("This is not valid JSON (${e.message?.take(80)}); it is shown as written."), sizeBytes = src.bytes.size.toLong())
        }
    }
    private fun describe(e: JsonElement): String = when {
        e.isJsonObject -> "object with ${e.asJsonObject.size()} keys: " + e.asJsonObject.keySet().take(12).joinToString()
        e.isJsonArray -> "array of ${e.asJsonArray.size()} items"
        else -> "single value"
    }
}

class XmlHandler : FileHandler {
    override val id = "xml"
    override fun canHandle(name: String, mime: String?, head: ByteArray) = Mimes.ext(name) in setOf("xml", "svg", "rss", "atom", "plist", "xsd", "xsl") || mime == "application/xml"
    override fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument {
        val raw = Text.decode(src.bytes)
        val problem = XmlScan.balancedError(raw)
        return ExtractedDocument(src.name, "application/xml", DocKind.MARKUP, listOf(DocSection("", raw)),
            warnings = listOfNotNull(problem?.let { "The XML is not well-formed: $it" }), sizeBytes = src.bytes.size.toLong())
    }
}

class HtmlHandler : FileHandler {
    override val id = "html"
    override fun canHandle(name: String, mime: String?, head: ByteArray) = Mimes.ext(name) in setOf("html", "htm", "xhtml") || mime == "text/html"
    override fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument {
        val sb = StringBuilder(); val links = ArrayList<String>()
        var skip = 0; var title = ""; var inTitle = false; var heading = 0; var href: String? = null
        val block = setOf("p", "div", "section", "article", "li", "tr", "br", "ul", "ol", "table", "header", "footer", "main", "blockquote", "pre")
        XmlScan.scan(Text.decode(src.bytes), object : XmlEvents {
            override fun start(name: String, attrs: String, selfClosing: Boolean) {
                val n = XmlScan.local(name).lowercase()
                when {
                    n == "script" || n == "style" || n == "noscript" -> if (!selfClosing) skip++
                    n == "title" -> inTitle = true
                    n.length == 2 && n[0] == 'h' && n[1] in '1'..'6' -> { heading = n[1] - '0'; sb.append("\n\n").append("#".repeat(heading)).append(' ') }
                    n == "a" -> href = XmlScan.attr(attrs, "href")
                    n == "li" -> sb.append("\n- ")
                    n in block -> sb.append('\n')
                }
            }
            override fun end(name: String) {
                val n = XmlScan.local(name).lowercase()
                when {
                    n == "script" || n == "style" || n == "noscript" -> if (skip > 0) skip--
                    n == "title" -> inTitle = false
                    n.length == 2 && n[0] == 'h' && n[1] in '1'..'6' -> { heading = 0; sb.append('\n') }
                    n == "a" -> href = null
                    n in block -> sb.append('\n')
                }
            }
            override fun text(text: String) {
                if (skip > 0) return
                val t = XmlScan.decode(text).replace(Regex("\\s+"), " ")
                if (t.isBlank()) return
                if (inTitle) { title += t.trim(); return }
                sb.append(t); href?.let { h -> if (!h.startsWith("#") && !h.startsWith("javascript:")) links.add("${t.trim()} -> $h") }
            }
        })
        val body = sb.toString().replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
        val sections = ArrayList<DocSection>()
        sections += DocSection("", (if (title.isNotBlank()) "# $title\n\n" else "") + body)
        if (links.isNotEmpty()) sections += DocSection("Links", links.distinct().take(200).joinToString("\n"))
        return ExtractedDocument(src.name, "text/html", DocKind.MARKUP, sections, meta = if (title.isNotBlank()) mapOf("title" to title) else emptyMap(), sizeBytes = src.bytes.size.toLong())
    }
}

/** Pictures are not text: report what they are and say honestly that a vision model or OCR is needed to read them. */
class ImageHandler : FileHandler {
    override val id = "image"
    override fun canHandle(name: String, mime: String?, head: ByteArray) = Mimes.ext(name) in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp") || mime?.startsWith("image/") == true
    override fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument {
        val dim = ImageSniffer.dimensions(src.bytes)
        val meta = LinkedHashMap<String, String>()
        dim?.let { meta["width"] = it.first.toString(); meta["height"] = it.second.toString() }
        return ExtractedDocument(src.name, src.mime ?: ImageSniffer.mime(src.bytes) ?: Mimes.guess(src.name), DocKind.IMAGE, emptyList(),
            warnings = listOf("This is an image. Its content can only be read by an image-capable model or by OCR."), needsOcr = true, meta = meta, sizeBytes = src.bytes.size.toLong())
    }
}

object ImageSniffer {
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun be32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun le32(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)

    fun mime(b: ByteArray): String? = when {
        b.size > 8 && u8(b, 0) == 0x89 && b[1] == 'P'.code.toByte() -> "image/png"
        b.size > 3 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 -> "image/jpeg"
        b.size > 6 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "image/gif"
        b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        b.size > 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "image/bmp"
        else -> null
    }

    /** Width and height read from the file header, or null when it can not be determined. No image library needed. */
    fun dimensions(b: ByteArray): Pair<Int, Int>? = try {
        when (mime(b)) {
            "image/png" -> if (b.size >= 24) be32(b, 16) to be32(b, 20) else null
            "image/gif" -> if (b.size >= 10) le16(b, 6) to le16(b, 8) else null
            "image/bmp" -> if (b.size >= 26) le32(b, 18) to kotlin.math.abs(le32(b, 22)) else null
            "image/jpeg" -> {
                var i = 2; var result: Pair<Int, Int>? = null
                while (i + 9 < b.size && result == null) {
                    if (u8(b, i) != 0xFF) { i++; continue }
                    val m = u8(b, i + 1)
                    if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) result = be16(b, i + 7) to be16(b, i + 5)
                    else i += 2 + be16(b, i + 2)
                }
                result
            }
            "image/webp" -> when (String(b, 12, 4, Charsets.ISO_8859_1)) {
                "VP8X" -> (1 + (u8(b, 24) or (u8(b, 25) shl 8) or (u8(b, 26) shl 16))) to (1 + (u8(b, 27) or (u8(b, 28) shl 8) or (u8(b, 29) shl 16)))
                "VP8 " -> (le16(b, 26) and 0x3FFF) to (le16(b, 28) and 0x3FFF)
                "VP8L" -> { val bits = le32(b, 21); ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1) }
                else -> null
            }
            else -> null
        }
    } catch (e: Exception) { null }
}

class ZipArchiveHandler : FileHandler {
    override val id = "zip"
    override fun canHandle(name: String, mime: String?, head: ByteArray) =
        Mimes.ext(name) in setOf("zip", "jar", "apk", "aar") && SafeZip.looksLikeZip(head)
    override fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument {
        val idx = SafeZip.index(src.bytes, ctx.limits)
        idx.refusal?.let { throw ExtractionFailedException(it) }
        val listing = idx.entries.filter { !it.isDirectory }.joinToString("\n") { "${it.name}  (${it.size} bytes)" + (it.problem?.let { p -> "  [unsafe: $p]" } ?: "") }
        val sections = arrayListOf(DocSection("Contents (${idx.entries.count { !it.isDirectory }} files)", listing))
        val warnings = ArrayList(idx.warnings); var truncated = idx.truncated
        if (ctx.options.extractArchiveText && ctx.options.depth < ctx.limits.maxZipDepth) {
            var count = 0
            val safe = idx.entries.filter { !it.isDirectory && it.problem == null && it.size <= ctx.limits.maxNestedBytes && ctx.extractor.canExtract(it.name) }
            val wanted = safe.take(ctx.limits.maxNestedFiles).map { it.name }.toSet()
            if (safe.size > wanted.size) { warnings += "Only the first ${wanted.size} readable files were opened (of ${safe.size})."; truncated = true }
            val data = SafeZip.read(src.bytes, ctx.limits) { it in wanted }
            for ((n, bytes) in data) {
                count++
                try {
                    val d = ctx.extractor.extract(FileSource(n, null, bytes), ExtractOptions(extractArchiveText = true, depth = ctx.options.depth + 1))
                    sections += DocSection(n, d.text); warnings += d.warnings.map { "$n: $it" }
                } catch (e: Exception) { warnings += "$n could not be read: ${e.message}" }
            }
        }
        return ExtractedDocument(src.name, "application/zip", DocKind.ARCHIVE, sections, warnings, truncated = truncated, meta = mapOf("files" to idx.entries.count { !it.isDirectory }.toString()), sizeBytes = src.bytes.size.toLong())
    }
}

/**
 * Old binary .doc files. There is no reliable text layer to parse here, so this collects readable text runs and says
 * clearly that the result is best-effort (formatting, tables and some text can be missing).
 */
class LegacyDocHandler : FileHandler {
    override val id = "doc"
    override fun canHandle(name: String, mime: String?, head: ByteArray) = Mimes.ext(name) == "doc" && head.size > 8 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte()
    override fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument {
        val b = src.bytes; val runs = ArrayList<String>()
        // UTF-16LE runs (how Word stores most text) first, then single-byte runs.
        val sb = StringBuilder(); var i = 0
        fun flush() { if (sb.length >= 12) runs += sb.toString(); sb.setLength(0) }
        while (i + 1 < b.size) {
            val lo = b[i].toInt() and 0xFF; val hi = b[i + 1].toInt() and 0xFF
            if (hi == 0 && (lo in 32..126 || lo == 9 || lo == 10 || lo == 13)) { sb.append(if (lo == 13) '\n' else lo.toChar()); i += 2 }
            else if (hi in 1..0x2F && lo >= 32 && sb.isNotEmpty()) { sb.append(((hi shl 8) or lo).toChar()); i += 2 }
            else { flush(); i++ }
        }
        flush()
        val text = runs.filter { r -> r.count { it.isLetter() } > r.length / 2 }.joinToString("\n")
        return ExtractedDocument(src.name, "application/msword", DocKind.DOCUMENT, listOf(DocSection("", text)),
            warnings = listOf("Best-effort reading of an old .doc file: formatting and tables are lost and some text may be missing. Saving it as .docx gives a complete result."),
            needsOcr = text.isBlank(), sizeBytes = b.size.toLong())
    }
}
