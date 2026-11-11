package com.jarvis.assistant.tools.files

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GenerationException(message: String) : Exception(message)

/** The output of creating a file: the bytes plus what was checked afterwards. */
class GeneratedFile(val name: String, val mime: String, val bytes: ByteArray, val notes: List<String>, val validated: Boolean)

/** Creates real files (not renamed text). Office and PDF outputs are read back with the extractor to prove they open. */
class DocumentGenerator(private val extractor: DocumentExtractor = DocumentExtractor()) {
    private val textExts = setOf("txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "ts", "kt", "java", "py", "sh", "sql", "yaml", "yml", "svg", "c", "cpp", "go", "rs", "php", "rb", "swift")

    /** [content] is plain text / markdown for documents, CSV text for xlsx, "---" separated slides for pptx. */
    fun create(name: String, content: String): GeneratedFile {
        val ext = Mimes.ext(name)
        if (name.isBlank() || name.contains("..") || name.startsWith("/")) throw GenerationException("Invalid file name.")
        val notes = ArrayList<String>()
        return when {
            ext in textExts -> validateText(name, ext, content, notes)
            ext == "docx" -> check(name, docx(content), notes)
            ext == "xlsx" -> check(name, xlsx(content), notes)
            ext == "pptx" -> check(name, pptx(content), notes)
            ext == "pdf" -> { val (b, n) = pdf(content); notes += n; check(name, b, notes) }
            else -> throw GenerationException("Can not create .$ext files. Supported: ${(textExts + setOf("docx", "xlsx", "pptx", "pdf", "zip")).sorted().joinToString()}.")
        }
    }

    private fun validateText(name: String, ext: String, content: String, notes: MutableList<String>): GeneratedFile {
        var ok = true
        when (ext) {
            "json" -> try { com.google.gson.JsonParser.parseString(content) } catch (e: Exception) { throw GenerationException("The JSON is not valid: ${e.message?.take(100)}") }
            "xml", "svg" -> XmlScan.balancedError(content)?.let { throw GenerationException("The XML is not well-formed: $it") }
            else -> ok = true
        }
        return GeneratedFile(name, Mimes.guess(name), content.toByteArray(Charsets.UTF_8), notes, ok)
    }

    private fun check(name: String, bytes: ByteArray, notes: MutableList<String>): GeneratedFile {
        val doc = try { extractor.extract(FileSource(name, null, bytes)) } catch (e: Exception) { throw GenerationException("The generated file did not pass the check: ${e.message}") }
        if (!doc.hasText) throw GenerationException("The generated file has no readable content.")
        notes += "Reopened the file after creating it: ${doc.text.length} characters read back."
        return GeneratedFile(name, Mimes.guess(name), bytes, notes, true)
    }

    /** Files to zip must be plain relative paths; anything that looks like a secret is refused. */
    fun zip(name: String, files: Map<String, ByteArray>): GeneratedFile {
        if (files.isEmpty()) throw GenerationException("Nothing to put in the archive.")
        val bad = Regex("(?i)(^|/)(\\.env(\\..*)?|id_rsa|id_ed25519|.*\\.(pem|p12|jks|keystore)|\\.git/.*|credentials(\\.json)?|secrets?\\..*)$")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, b) in files) {
                val clean = SafeZip.nameProblem(n); if (clean != null) throw GenerationException("Unsafe name in archive: $n")
                if (bad.containsMatchIn(n)) throw GenerationException("$n looks like a secret or credential file and was not added.")
                z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry()
            }
        }
        val idx = SafeZip.index(out.toByteArray(), ExtractionLimits())
        if (idx.refusal != null) throw GenerationException("The archive failed its check: ${idx.refusal}")
        return GeneratedFile(name, "application/zip", out.toByteArray(), listOf("Archive checked: ${files.size} files."), true)
    }

    // ---------- OOXML ----------
    private fun esc(s: String) = XmlScan.escape(s)
    private fun zipOf(parts: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, c) in parts) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return out.toByteArray()
    }
    private val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    fun docx(content: String): ByteArray {
        val body = StringBuilder()
        for (line in content.lines()) {
            val h = Regex("^(#{1,6})\\s+(.*)").find(line)
            val li = Regex("^\\s*[-*]\\s+(.*)").find(line)
            when {
                line.isBlank() -> {}
                h != null -> body.append("<w:p><w:pPr><w:pStyle w:val=\"Heading${h.groupValues[1].length}\"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">${esc(h.groupValues[2])}</w:t></w:r></w:p>")
                li != null -> body.append("<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t xml:space=\"preserve\">${esc(li.groupValues[1])}</w:t></w:r></w:p>")
                else -> body.append("<w:p><w:r><w:t xml:space=\"preserve\">${esc(line)}</w:t></w:r></w:p>")
            }
        }
        return zipOf(listOf(
            "[Content_Types].xml" to XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
            "_rels/.rels" to XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/document.xml" to XML + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr></w:body></w:document>"
        ))
    }

    private fun colName(i: Int): String { var n = i; val s = StringBuilder(); do { s.insert(0, 'A' + n % 26); n = n / 26 - 1 } while (n >= 0); return s.toString() }

    fun xlsx(csv: String): ByteArray {
        val (rows, _) = Text.parseCsv(csv, if (csv.lineSequence().firstOrNull()?.contains('\t') == true) '\t' else ',', 1_000_000)
        val sheet = StringBuilder()
        rows.forEachIndexed { r, row ->
            sheet.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, v ->
                val ref = colName(c) + (r + 1)
                if (v.isNotEmpty()) { if (v.toDoubleOrNull() != null && !v.startsWith("0") || v == "0") sheet.append("<c r=\"$ref\"><v>$v</v></c>") else sheet.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>") }
            }
            sheet.append("</row>")
        }
        return zipOf(listOf(
            "[Content_Types].xml" to XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>",
            "_rels/.rels" to XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/workbook.xml" to XML + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to XML + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$sheet</sheetData></worksheet>"
        ))
    }

    /** Slides are separated by a line containing only "---". The first line of a slide is its title. */
    fun pptx(content: String): ByteArray {
        val slides = content.split(Regex("(?m)^---\\s*$")).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(content.trim()) }
        val parts = ArrayList<Pair<String, String>>()
        val ct = StringBuilder(XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
        val ids = StringBuilder(); val rels = StringBuilder()
        slides.forEachIndexed { i, s ->
            val lines = s.lines().filter { it.isNotBlank() }.map { it.removePrefix("# ").removePrefix("- ") }
            fun para(t: String) = "<a:p><a:r><a:t>${esc(t)}</a:t></a:r></a:p>"
            fun shape(id: Int, nm: String, y: Int, h: Int, ps: String) = "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$nm\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"457200\" y=\"$y\"/><a:ext cx=\"8229600\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/>$ps</p:txBody></p:sp>"
            val xml = XML + "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>" +
                shape(2, "Title", 365125, 1000000, para(lines.firstOrNull().orEmpty())) + shape(3, "Body", 1600000, 4500000, lines.drop(1).joinToString("") { para(it) }.ifEmpty { "<a:p/>" }) + "</p:spTree></p:cSld></p:sld>"
            parts += "ppt/slides/slide${i + 1}.xml" to xml
            ct.append("<Override PartName=\"/ppt/slides/slide${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
            ids.append("<p:sldId id=\"${256 + i}\" r:id=\"rId${i + 1}\"/>")
            rels.append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${i + 1}.xml\"/>")
        }
        ct.append("</Types>")
        return zipOf(listOf("[Content_Types].xml" to ct.toString(),
            "_rels/.rels" to XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>",
            "ppt/presentation.xml" to XML + "<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:sldIdLst>$ids</p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"6858000\"/></p:presentation>",
            "ppt/_rels/presentation.xml.rels" to XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rels</Relationships>") + parts)
    }

    // ---------- PDF (text, Helvetica, Latin-1 only) ----------
    fun pdf(content: String): Pair<ByteArray, List<String>> {
        val notes = ArrayList<String>()
        val lines = ArrayList<String>()
        for (raw in content.lines()) {
            var l = raw.replace("\t", "    ")
            if (l.any { it.code > 255 }) { l = l.map { if (it.code > 255) '?' else it }.joinToString(""); if (notes.isEmpty()) notes += "Characters outside Latin-1 (such as Bengali, Hindi or emoji) can not be drawn with the built-in PDF font and were replaced with '?'. Use DOCX or HTML for those scripts." }
            while (l.length > 95) { lines += l.take(95); l = l.drop(95) }
            lines += l
        }
        val perPage = 52; val pages = lines.chunked(perPage).ifEmpty { listOf(listOf("")) }
        val out = ByteArrayOutputStream(); val offs = ArrayList<Int>()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(n: Int, body: String) { offs.add(out.size()); w("$n 0 obj\n$body\nendobj\n") }
        w("%PDF-1.4\n")
        val pageObjStart = 4
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [${pages.indices.joinToString(" ") { "${pageObjStart + it * 2} 0 R" }}] /Count ${pages.size} >>")
        obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        pages.forEachIndexed { i, ls ->
            val pn = pageObjStart + i * 2; val cn = pn + 1
            obj(pn, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents $cn 0 R >>")
            val sb = StringBuilder("BT /F1 10 Tf 14 TL 50 800 Td\n")
            ls.forEach { sb.append('(').append(it.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")).append(") Tj T*\n") }
            sb.append("ET")
            val data = sb.toString().toByteArray(Charsets.ISO_8859_1)
            offs.add(out.size()); w("$cn 0 obj\n<< /Length ${data.size} >>\nstream\n"); out.write(data); w("\nendstream\nendobj\n")
        }
        val xref = out.size(); val total = offs.size + 1
        w("xref\n0 $total\n0000000000 65535 f \n"); offs.forEach { w(String.format("%010d 00000 n \n", it)) }
        w("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray() to notes
    }
}
