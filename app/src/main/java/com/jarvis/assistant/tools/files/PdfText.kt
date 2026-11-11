package com.jarvis.assistant.tools.files

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Text extraction from PDF without a PDF library: finds objects, inflates Flate streams, reads text-showing operators
 * and maps font codes through ToUnicode CMaps. Scanned pages (no text layer), encrypted files and files that keep
 * objects inside compressed object streams are reported honestly instead of returning empty or garbled text.
 */
class PdfHandler : FileHandler {
    override val id = "pdf"
    override fun canHandle(name: String, mime: String?, head: ByteArray) = head.size > 4 && String(head, 0, 5, Charsets.ISO_8859_1).startsWith("%PDF")

    private class Obj(val num: Int, val dict: String, val stream: ByteArray?)

    override fun extract(src: FileSource, ctx: ExtractionContext): ExtractedDocument {
        val raw = String(src.bytes, Charsets.ISO_8859_1)
        val warnings = ArrayList<String>()
        if (Regex("/Encrypt\\b").containsMatchIn(raw.takeLast(4096)) || Regex("/Encrypt\\s+\\d+\\s+\\d+\\s+R").containsMatchIn(raw))
            throw ExtractionFailedException("${src.name} is encrypted or password protected, so its text can not be read.")
        val objs = HashMap<Int, Obj>()
        val order = ArrayList<Int>()
        val re = Regex("(\\d+)\\s+(\\d+)\\s+obj\\b")
        var pos = 0
        while (true) {
            val m = re.find(raw, pos) ?: break
            val num = m.groupValues[1].toInt(); val bodyStart = m.range.last + 1
            val end = raw.indexOf("endobj", bodyStart).let { if (it < 0) raw.length else it }
            val s = raw.indexOf("stream", bodyStart).takeIf { it in 0 until end }
            val dict = raw.substring(bodyStart, s ?: end)
            var stream: ByteArray? = null
            if (s != null) {
                var a = s + 6; if (a < raw.length && raw[a] == '\r') a++; if (a < raw.length && raw[a] == '\n') a++
                val len = Regex("/Length\\s+(\\d+)(?!\\s+\\d+\\s+R)").find(dict)?.groupValues?.get(1)?.toIntOrNull()
                var b = if (len != null && a + len <= src.bytes.size && raw.startsWith("endstream", (a + len).let { var k = it; while (k < raw.length && raw[k].isWhitespace()) k++; k })) a + len else raw.indexOf("endstream", a).let { if (it < 0) end else it }
                if (b < a) b = a
                stream = src.bytes.copyOfRange(a, b)
                pos = maxOf(b, end).coerceAtMost(raw.length)
                val e2 = raw.indexOf("endobj", b); pos = if (e2 < 0) raw.length else e2
            } else pos = end
            objs[num] = Obj(num, dict, stream); order += num
            if (objs.size > 200_000) throw ExtractionFailedException("${src.name} has too many objects.")
        }
        if (objs.isEmpty()) throw ExtractionFailedException("${src.name} does not look like a readable PDF.")
        if (objs.values.any { it.dict.contains("/ObjStm") }) warnings += "Some objects are stored in compressed object streams; text inside them may be missing."

        fun decoded(o: Obj): ByteArray? {
            val st = o.stream ?: return null
            if (!o.dict.contains("FlateDecode") && !o.dict.contains("/Fl ")) return if (o.dict.contains("/Filter")) null else st
            return inflate(st, ctx.limits.maxBytes)
        }
        fun ref(s: String): Int? = Regex("(\\d+)\\s+\\d+\\s+R").find(s)?.groupValues?.get(1)?.toIntOrNull()

        // ToUnicode map per font object
        val cmapCache = HashMap<Int, Map<Int, String>>()
        fun cmapOf(fontNum: Int): Map<Int, String>? = cmapCache.getOrPut(fontNum) {
            val f = objs[fontNum] ?: return@getOrPut emptyMap()
            val tu = Regex("/ToUnicode\\s+(\\d+)\\s+\\d+\\s+R").find(f.dict)?.groupValues?.get(1)?.toIntOrNull() ?: return@getOrPut emptyMap()
            val data = objs[tu]?.let { decoded(it) } ?: return@getOrPut emptyMap()
            parseCMap(String(data, Charsets.ISO_8859_1))
        }.takeIf { it.isNotEmpty() }

        val pages = order.mapNotNull { objs[it] }.filter { Regex("/Type\\s*/Page(?![a-zA-Z])").containsMatchIn(it.dict) }
        val sections = ArrayList<DocSection>(); var blank = 0; var total = 0L
        val limit = ctx.limits.maxPdfPages
        var truncated = false
        for ((i, p) in pages.withIndex()) {
            if (i >= limit) { truncated = true; warnings += "Only the first $limit pages were read (page limit)."; break }
            // fonts available on the page
            val resText = Regex("/Resources\\s+(\\d+)\\s+\\d+\\s+R").find(p.dict)?.groupValues?.get(1)?.toIntOrNull()?.let { objs[it]?.dict } ?: p.dict
            val fontDict = Regex("/Font\\s*(<<.*?>>|(\\d+)\\s+\\d+\\s+R)", RegexOption.DOT_MATCHES_ALL).find(resText)?.let { m ->
                m.groupValues[2].toIntOrNull()?.let { objs[it]?.dict } ?: m.groupValues[1]
            }.orEmpty()
            val fonts = HashMap<String, Map<Int, String>?>()
            Regex("/([A-Za-z0-9_.+-]+)\\s+(\\d+)\\s+\\d+\\s+R").findAll(fontDict).forEach { fonts[it.groupValues[1]] = cmapOf(it.groupValues[2].toInt()) }
            val contentRefs = Regex("/Contents\\s*(\\[[^\\]]*\\]|\\d+\\s+\\d+\\s+R)").find(p.dict)?.groupValues?.get(1).orEmpty()
            val nums = Regex("(\\d+)\\s+\\d+\\s+R").findAll(contentRefs).map { it.groupValues[1].toInt() }.toList()
            val sb = StringBuilder()
            for (n in nums) { val d = objs[n]?.let { decoded(it) } ?: continue; sb.append(contentText(String(d, Charsets.ISO_8859_1), fonts)).append('\n') }
            val text = sb.toString().replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
            if (text.isBlank()) blank++ else { sections += DocSection("Page ${i + 1}", text); total += text.length }
        }
        val scanned = pages.isNotEmpty() && blank == minOf(pages.size, limit)
        if (blank > 0 && !scanned) warnings += "$blank page(s) have no text layer (images or scans); they need OCR to be read."
        if (scanned) warnings += "No text layer was found; this looks like a scanned PDF and needs OCR."
        if (pages.isEmpty()) warnings += "No pages were found in this PDF."
        if (sections.isNotEmpty() && sections.sumOf { it.text.count { c -> c == '�' } } > total / 20) warnings += "A lot of the text could not be decoded; the result may be garbled."
        return ExtractedDocument(src.name, "application/pdf", DocKind.PDF, sections, warnings, needsOcr = blank > 0, truncated = truncated, pageCount = pages.size, sizeBytes = src.bytes.size.toLong())
    }

    private fun inflate(b: ByteArray, max: Long): ByteArray? {
        val inf = Inflater(); inf.setInput(b)
        val out = ByteArrayOutputStream(); val buf = ByteArray(16384)
        return try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > max) throw ZipLimitException("A PDF stream expands beyond the size limit.")
            }
            out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) { if (out.size() > 0) out.toByteArray() else null } finally { inf.end() }
    }

    internal fun parseCMap(s: String): Map<Int, String> {
        val map = HashMap<Int, String>()
        fun u(hex: String): String { val h = hex.trim(); val sb = StringBuilder(); var i = 0; while (i + 4 <= h.length) { sb.append(h.substring(i, i + 4).toInt(16).toChar()); i += 4 }; if (sb.isEmpty() && h.isNotEmpty()) sb.append(h.toInt(16).toChar()); return sb.toString() }
        Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { blk ->
            Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(blk.groupValues[1]).forEach { map[it.groupValues[1].toInt(16)] = u(it.groupValues[2]) }
        }
        Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { blk ->
            Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<([0-9A-Fa-f]+)>|\\[(.*?)\\])", RegexOption.DOT_MATCHES_ALL).findAll(blk.groupValues[1]).forEach { m ->
                val lo = m.groupValues[1].toInt(16); val hi = minOf(m.groupValues[2].toInt(16), lo + 65535)
                if (m.groupValues[4].isNotEmpty()) { val base = m.groupValues[4]; val start = base.toInt(16); val w = base.length; for (c in lo..hi) map[c] = u((start + c - lo).toString(16).padStart(w, '0')) }
                else Regex("<([0-9A-Fa-f]+)>").findAll(m.groupValues[5]).forEachIndexed { k, x -> if (lo + k <= hi) map[lo + k] = u(x.groupValues[1]) }
            }
        }
        return map
    }

    /** Walks one content stream and returns the text shown by Tj, TJ, ' and " operators with line breaks at moves. */
    internal fun contentText(c: String, fonts: Map<String, Map<Int, String>?>): String {
        val out = StringBuilder(); var cmap: Map<Int, String>? = null
        val stack = ArrayList<Any>()
        var i = 0
        fun show(bytes: ByteArray) {
            val m = cmap
            if (m != null) { var k = 0; val two = m.keys.any { it > 255 }
                while (k < bytes.size) { if (two && k + 1 < bytes.size) { val code = ((bytes[k].toInt() and 0xFF) shl 8) or (bytes[k + 1].toInt() and 0xFF); out.append(m[code] ?: ""); k += 2 } else { val code = bytes[k].toInt() and 0xFF; out.append(m[code] ?: code.toChar().toString()); k++ } } }
            else out.append(String(bytes, Charsets.ISO_8859_1))
        }
        while (i < c.length) {
            val ch = c[i]
            when {
                ch.isWhitespace() -> i++
                ch == '%' -> { while (i < c.length && c[i] != '\n' && c[i] != '\r') i++ }
                ch == '(' -> { val (b, e) = literal(c, i); stack.add(b); i = e }
                ch == '<' && i + 1 < c.length && c[i + 1] == '<' -> { i = c.indexOf(">>", i).let { if (it < 0) c.length else it + 2 } }
                ch == '<' -> { val e = c.indexOf('>', i).let { if (it < 0) c.length else it }; val h = c.substring(i + 1, e).filter { !it.isWhitespace() }.let { if (it.length % 2 == 1) it + "0" else it }
                    stack.add(ByteArray(h.length / 2) { k -> h.substring(k * 2, k * 2 + 2).toInt(16).toByte() }); i = e + 1 }
                ch == '[' -> { stack.add("["); i++ }
                ch == ']' -> { stack.add("]"); i++ }
                ch == '/' -> { var e = i + 1; while (e < c.length && !c[e].isWhitespace() && c[e] !in "/[]<>()") e++; stack.add("/" + c.substring(i + 1, e)); i = e }
                else -> {
                    var e = i; while (e < c.length && !c[e].isWhitespace() && c[e] !in "/[]<>()%") e++
                    val tok = c.substring(i, e); i = maxOf(e, i + 1)
                    val num = tok.toDoubleOrNull()
                    if (num != null) { stack.add(num); continue }
                    when (tok) {
                        "Tf" -> { val n = stack.getOrNull(stack.size - 2) as? String; cmap = n?.removePrefix("/")?.let { fonts[it] } }
                        "Tj" -> (stack.lastOrNull() as? ByteArray)?.let { show(it) }
                        "'" , "\"" -> { out.append('\n'); (stack.lastOrNull() as? ByteArray)?.let { show(it) } }
                        "TJ" -> { val st = stack.lastIndexOf("[")
                            if (st >= 0) for (k in st + 1 until stack.size) { when (val x = stack[k]) { is ByteArray -> show(x); is Double -> if (x < -200) out.append(' ') } } }
                        "Td", "TD" -> { val y = stack.lastOrNull() as? Double; if (y != null && y != 0.0) out.append('\n') }
                        "T*", "ET" -> out.append('\n')
                        "Tm" -> out.append('\n')
                    }
                    stack.clear()
                }
            }
        }
        return out.toString()
    }

    private fun literal(c: String, start: Int): Pair<ByteArray, Int> {
        val b = ByteArrayOutputStream(); var depth = 1; var i = start + 1
        while (i < c.length && depth > 0) {
            val ch = c[i]
            when {
                ch == '\\' && i + 1 < c.length -> { i++; val n = c[i]
                    when (n) { 'n' -> b.write(10); 'r' -> b.write(13); 't' -> b.write(9); 'b' -> b.write(8); 'f' -> b.write(12); '\n' -> {}; '\r' -> { if (i + 1 < c.length && c[i + 1] == '\n') i++ }
                        in '0'..'7' -> { var v = n - '0'; var k = 0; while (k < 2 && i + 1 < c.length && c[i + 1] in '0'..'7') { i++; v = v * 8 + (c[i] - '0'); k++ }; b.write(v and 0xFF) }
                        else -> b.write(n.code and 0xFF) } }
                ch == '(' -> { depth++; b.write('('.code) }
                ch == ')' -> { depth--; if (depth > 0) b.write(')'.code) }
                else -> b.write(ch.code and 0xFF)
            }
            i++
        }
        return b.toByteArray() to i
    }
}
