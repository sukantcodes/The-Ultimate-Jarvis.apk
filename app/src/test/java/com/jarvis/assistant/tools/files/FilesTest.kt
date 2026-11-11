package com.jarvis.assistant.tools.files

import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.args
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun zipOf(vararg e: Pair<String, String>) = ByteArrayOutputStream().also { b -> ZipOutputStream(b).use { z -> e.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } } }.toByteArray()

class FilesTest {
    private val ex = DocumentExtractor()
    private val gen = DocumentGenerator(ex)
    private fun read(name: String, b: ByteArray) = ex.extract(FileSource(name, null, b))

    @Test fun textAndCsvAndJson() {
        assertEquals("hello", read("a.txt", "hello".toByteArray()).text)
        val csv = read("a.csv", "name,age\n\"Smith, J\",42\n".toByteArray())
        assertEquals(listOf("Smith, J", "42"), csv.tables[0].rows[1])
        val j = read("a.json", "{\"a\":1}".toByteArray()); assertTrue(j.text.contains("\"a\": 1"))
        assertTrue(read("bad.json", "{oops".toByteArray()).warnings.isNotEmpty())
    }

    @Test fun windows1252FallbackAndBinaryRefused() {
        assertTrue(read("a.txt", byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())).text.endsWith("é"))
        try { read("a.txt", ByteArray(500) { 0 }); fail() } catch (e: UnsupportedFileException) {}
    }

    @Test fun htmlDropsScriptsKeepsHeadings() {
        val d = read("a.html", "<html><title>T</title><body><script>evil()</script><h1>Head</h1><p>Body &amp; more</p></body></html>".toByteArray())
        assertTrue(d.text.contains("# Head")); assertTrue(d.text.contains("Body & more")); assertFalse(d.text.contains("evil"))
    }

    @Test fun docxRoundTrip() {
        val g = gen.create("r.docx", "# Title\n\nSome paragraph text.\n- item one\n- item two")
        val d = read("r.docx", g.bytes); assertTrue(d.text.contains("# Title")); assertTrue(d.text.contains("item two")); assertTrue(g.validated)
    }

    @Test fun xlsxRoundTrip() {
        val g = gen.create("r.xlsx", "item,qty\napple,3\n\"a,b\",5\n")
        val d = read("r.xlsx", g.bytes); assertEquals(listOf("a,b", "5"), d.tables[0].rows[2])
    }

    @Test fun pptxRoundTrip() {
        val d = read("r.pptx", gen.create("r.pptx", "Intro\nwelcome\n---\nPlan\n- one\n- two").bytes)
        assertEquals(2, d.pageCount); assertTrue(d.text.contains("Slide 2")); assertTrue(d.text.contains("two"))
    }

    @Test fun pdfRoundTripAndNonLatinWarning() {
        val g = gen.create("r.pdf", "Hello PDF world\nSecond (line) here")
        val d = read("r.pdf", g.bytes); assertTrue(d.text.contains("Hello PDF world")); assertTrue(d.text.contains("Second (line) here"))
        assertTrue(gen.create("b.pdf", "বাংলা").notes.any { it.contains("Latin-1") })
    }

    @Test fun pdfWithoutTextNeedsOcr() {
        val pdf = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n3 0 obj\n<< /Type /Page /Parent 2 0 R >>\nendobj\n%%EOF"
        val d = read("s.pdf", pdf.toByteArray()); assertTrue(d.needsOcr); assertFalse(d.hasText)
    }

    @Test fun pdfEncryptedRefused() {
        try { read("e.pdf", "%PDF-1.4\n1 0 obj\n<< /Encrypt 5 0 R >>\nendobj".toByteArray()); fail() } catch (e: ExtractionFailedException) { assertTrue(e.message!!.contains("encrypted")) }
    }

    @Test fun pdfToUnicodeMapping() {
        val m = PdfHandler().parseCMap("beginbfchar <01> <0048> <02> <0069> endbfchar")
        assertEquals("Hi", PdfHandler().contentText("BT /F1 12 Tf <0102> Tj ET", mapOf("F1" to m)).trim())
    }

    @Test fun zipListsAndReadsAndRejectsSlip() {
        val d = ex.extract(FileSource("a.zip", null, zipOf("x/a.txt" to "inside text", "b.bin" to "q")), ExtractOptions(extractArchiveText = true))
        assertTrue(d.text.contains("x/a.txt")); assertTrue(d.text.contains("inside text"))
        val bad = ex.extract(FileSource("a.zip", null, zipOf("../evil.txt" to "x")), ExtractOptions(extractArchiveText = true))
        assertTrue(bad.text.contains("unsafe")); assertFalse(bad.text.contains("[../evil.txt]"))
    }

    @Test fun zipBombRefused() {
        val big = "0".repeat(2_000_000)
        val e = DocumentExtractor(limits = ExtractionLimits(maxZipTotalBytes = 1_000_000, maxZipEntryBytes = 1_000_000))
        try { e.extract(FileSource("b.zip", null, zipOf("a.txt" to big)), ExtractOptions(extractArchiveText = true)); fail() } catch (x: ExtractionFailedException) {}
    }

    @Test fun truncationIsReported() {
        val e = DocumentExtractor(limits = ExtractionLimits(maxChars = 100))
        val d = e.extract(FileSource("a.txt", null, "x".repeat(500).toByteArray())); assertTrue(d.truncated); assertEquals(100, d.text.length); assertTrue(d.warnings.any { it.contains("cut") })
    }

    @Test fun unsupportedTypeIsReported() {
        try { read("a.exe", byteArrayOf(0x4D, 0x5A, 0, 0, 1, 2, 3, 0, 0, 0, 0, 0)); fail() } catch (e: UnsupportedFileException) {}
    }

    @Test fun imageDimensionsFromHeader() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(), 0, 0, 1, 0, 0, 0, 0, 50)
        val d = read("p.png", png); assertEquals("256", d.meta["width"]); assertEquals("50", d.meta["height"]); assertTrue(d.needsOcr)
    }

    @Test fun retrievalFindsRelevantChunkAndPackerSaysWhatIsMissing() {
        val text = (1..60).joinToString("\n\n") { "Paragraph $it about gardening and soil." } + "\n\nThe quarterly revenue of Acme was 42 million dollars."
        val d = ExtractedDocument("r.txt", "text/plain", DocKind.TEXT, listOf(DocSection("", text)))
        val top = ChunkIndex(Chunker.chunk(d, 500, 50)).search("quarterly revenue Acme").first()
        assertTrue(top.chunk.text.contains("42 million"))
        val p = ContextPacker.pack(d, "Acme revenue", 800); assertFalse(p.complete); assertTrue(p.text.contains("42 million")); assertTrue(p.note.contains("not included"))
        assertTrue(ContextPacker.pack(d, null, 10_000_000).complete)
    }

    @Test fun chunkerCoversEverything() {
        val t = "abcdefghij ".repeat(1000); val cs = Chunker.chunk("s", "", t, 500, 50)
        assertTrue(cs.last().start + cs.last().text.length == t.length); assertTrue(cs.zipWithNext().all { (a, b) -> b.start <= a.start + a.text.length })
    }

    @Test fun plannerIsHonestAboutImagesAndScans() = runBlocking {
        val p = AttachmentPlanner()
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(), 0, 0, 0, 8, 0, 0, 0, 8)
        assertEquals(AttachmentRoute.UNREADABLE, p.plan(FileSource("a.png", null, png), ModelCapabilities(vision = false), null).route)
        assertEquals(AttachmentRoute.IMAGE_NATIVE, p.plan(FileSource("a.png", null, png), ModelCapabilities(vision = true), null).route)
        val ocr = object : OcrEngine { override suspend fun recognize(bytes: ByteArray, mime: String) = "text from image" }
        val r = AttachmentPlanner(ocr = ocr).plan(FileSource("a.png", null, png), ModelCapabilities(vision = false), null)
        assertEquals(AttachmentRoute.OCR_THEN_TEXT, r.route); assertTrue(r.packed!!.text.contains("text from image"))
        val txt = p.plan(FileSource("a.txt", null, "x".repeat(5000).toByteArray()), ModelCapabilities(textContextChars = 1000), null)
        assertEquals(AttachmentRoute.TEXT, txt.route); assertFalse(txt.packed!!.complete); assertTrue(txt.explanation.contains("fit"))
        assertEquals(AttachmentRoute.UNREADABLE, p.plan(FileSource("a.exe", null, byteArrayOf(0x4D, 0x5A, 0, 0, 0, 0, 0, 0, 0)), ModelCapabilities(), null).route)
    }

    @Test fun generatorRefusesBadJsonXmlAndSecrets() {
        try { gen.create("a.json", "{x"); fail() } catch (e: GenerationException) {}
        try { gen.create("a.xml", "<a><b></a>"); fail() } catch (e: GenerationException) {}
        try { gen.create("../a.txt", "x"); fail() } catch (e: GenerationException) {}
        try { gen.zip("o.zip", mapOf(".env" to "K=1".toByteArray())); fail() } catch (e: GenerationException) { assertTrue(e.message!!.contains("secret")) }
        assertTrue(gen.zip("o.zip", mapOf("a/b.txt" to "x".toByteArray())).validated)
    }

    @Test fun toolsWorkInsideWorkspaceOnly() = runBlocking {
        val dir = Files.createTempDirectory("jarvis-doc").toFile(); val ctx = object : ToolContext { override val workspace = Workspace(dir) }
        val made = DocumentTools.CreateDocument.run(args("""{"path":"out/r.docx","content":"# Hi\n\nbody text"}"""), ctx); assertTrue(made.text, made.isOk)
        assertTrue(DocumentTools.ReadDocument.run(args("""{"path":"out/r.docx"}"""), ctx).text.contains("body text"))
        assertFalse(DocumentTools.CreateDocument.run(args("""{"path":"out/r.docx","content":"x"}"""), ctx).isOk)
        assertTrue(DocumentTools.SearchDocument.run(args("""{"path":"out/r.docx","query":"body"}"""), ctx).isOk)
        assertTrue(DocumentTools.CreateArchive.run(args("""{"output":"z.zip","paths":["out"]}"""), ctx).isOk)
        assertTrue(DocumentTools.InspectArchive.run(args("""{"path":"z.zip"}"""), ctx).text.contains("out/r.docx"))
        assertFalse(DocumentTools.ReadDocument.run(args("""{"path":"nope.pdf"}"""), ctx).isOk)
        try { DocumentTools.ReadDocument.run(args("""{"path":"../../etc/passwd"}"""), ctx); fail() } catch (e: Exception) {}
    }
}

class XmlBalanceTest {
    @Test fun selfClosingChildrenAreBalanced() {
        assertNull(XmlScan.balancedError("<a><b/><c x=\"1\"/><d></d></a>"))
        assertNotNull(XmlScan.balancedError("<a><b/></c>"))
        assertNotNull(XmlScan.balancedError("<a><b></a>"))
    }
}
