package com.jarvis.assistant.tools.files

import com.google.gson.JsonObject
import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.builtin.int
import com.jarvis.assistant.tools.builtin.str
import com.jarvis.assistant.tools.builtin.bool
import java.io.File

/** Document tools shared by every provider (Custom API, Gemini). They only touch the workspace. */
object DocumentTools {
    private val extractor = DocumentExtractor()
    private val generator = DocumentGenerator(extractor)

    fun all(): List<Tool> = listOf(ReadDocument, SearchDocument, CompareDocuments, InspectArchive, CreateDocument, CreateArchive)

    private fun load(args: JsonObject, ctx: ToolContext, key: String = "path"): Pair<File, ExtractedDocument> {
        val f = ctx.workspace.resolve(args.str(key) ?: throw IllegalArgumentException("$key is required"))
        return f to extractor.extract(f, ExtractOptions(extractArchiveText = true))
    }
    private fun header(d: ExtractedDocument) = buildString {
        append("${d.name} (${d.kind.name.lowercase()}, ${d.sizeBytes} bytes")
        if (d.pageCount > 0) append(", ${d.pageCount} pages/slides"); append(")\n")
        d.meta.forEach { (k, v) -> append("$k: $v\n") }
        d.warnings.forEach { append("Note: $it\n") }
        if (d.needsOcr) append("Note: some content needs OCR and is not included.\n")
    }
    private fun guard(block: () -> ToolResult): ToolResult = try { block() }
        catch (e: UnsupportedFileException) { ToolResult.Error(e.message ?: "Unsupported file") }
        catch (e: ExtractionFailedException) { ToolResult.Error(e.message ?: "Could not read the file") }
        catch (e: GenerationException) { ToolResult.Error(e.message ?: "Could not create the file") }
        catch (e: IllegalArgumentException) { ToolResult.Error(e.message ?: "Invalid arguments") }

    object ReadDocument : Tool {
        override val spec = ToolSpec("read_document", "Read a document (PDF, DOCX, XLSX, PPTX, CSV, JSON, HTML, ZIP, code, text) as text. If it is too long, pass a query to get the most relevant parts; the result always says what was left out.",
            listOf(ToolParam("path", required = true), ToolParam("query", description = "Topic to focus on when the document is long."), ToolParam("max_chars", "integer", "Size budget (default 30000).")), Risk.READ, "documents")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard {
            val (_, d) = load(args, ctx)
            val packed = ContextPacker.pack(d, args.str("query"), (args.int("max_chars") ?: 30_000).coerceIn(1_000, 400_000))
            ToolResult.Ok(header(d) + packed.note + "\n\n" + packed.text)
        }
    }

    object SearchDocument : Tool {
        override val spec = ToolSpec("search_document", "Find the passages of a document that best match a query (keyword ranking).",
            listOf(ToolParam("path", required = true), ToolParam("query", required = true), ToolParam("limit", "integer", "Number of passages (default 5).")), Risk.READ, "documents")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard {
            val (_, d) = load(args, ctx)
            val hits = ChunkIndex(Chunker.chunk(d)).search(args.str("query") ?: "", (args.int("limit") ?: 5).coerceIn(1, 20))
            if (hits.isEmpty()) ToolResult.Ok(header(d) + "No passage matches that query.")
            else ToolResult.Ok(header(d) + hits.joinToString("\n\n") { "— ${it.chunk.label.ifBlank { "part ${it.chunk.id + 1}" }} (score ${"%.2f".format(it.score)})\n${it.chunk.text}" })
        }
    }

    object CompareDocuments : Tool {
        override val spec = ToolSpec("compare_documents", "Compare the text of two files and list the differing lines.",
            listOf(ToolParam("path_a", required = true), ToolParam("path_b", required = true)), Risk.READ, "documents")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard {
            val (_, a) = load(args, ctx, "path_a"); val (_, b) = load(args, ctx, "path_b")
            ToolResult.Ok(FileComparer.compare(a, b))
        }
    }

    object InspectArchive : Tool {
        override val spec = ToolSpec("inspect_archive", "List what is inside a ZIP file without unpacking it, flagging unsafe entries.",
            listOf(ToolParam("path", required = true)), Risk.READ, "documents")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard {
            val f = ctx.workspace.resolve(args.str("path") ?: throw IllegalArgumentException("path is required"))
            if (!f.isFile) return@guard ToolResult.Error("No such file")
            val idx = SafeZip.index(f.readBytes(), ExtractionLimits())
            idx.refusal?.let { return@guard ToolResult.Error(it) }
            ToolResult.Ok(idx.warnings.joinToString("") { "Note: $it\n" } + idx.entries.joinToString("\n") { "${it.name} (${it.size} bytes)" + (it.problem?.let { p -> " [unsafe: $p]" } ?: "") })
        }
    }

    object CreateDocument : Tool {
        override val spec = ToolSpec("create_document", "Create a file in the workspace: txt, md, csv, json, xml, html, css, js, code, docx, xlsx (content is CSV), pptx (slides separated by a line '---'), or pdf. Office and PDF files are reopened to check they are valid.",
            listOf(ToolParam("path", required = true), ToolParam("content", required = true), ToolParam("overwrite", "boolean")), Risk.WRITE, "documents")
        override fun describe(args: JsonObject) = "Create ${args.str("path")}"
        override fun isDestructive(args: JsonObject, ctx: ToolContext) = runCatching { ctx.workspace.resolve(args.str("path") ?: "").exists() }.getOrDefault(false)
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard {
            val rel = args.str("path") ?: throw IllegalArgumentException("path is required")
            val f = ctx.workspace.resolve(rel)
            if (f.exists() && args.bool("overwrite") != true) return@guard ToolResult.Error("$rel already exists. Pass overwrite=true to replace it.")
            val g = generator.create(f.name, args.str("content") ?: "")
            f.parentFile?.mkdirs(); f.writeBytes(g.bytes)
            ToolResult.Ok("Created $rel (${g.bytes.size} bytes). " + g.notes.joinToString(" "), listOf(ToolArtifact(f.path, f.name, g.mime)))
        }
    }

    object CreateArchive : Tool {
        override val spec = ToolSpec("create_archive", "Zip workspace files into a new .zip. Secret-looking files (.env, keys) are refused.",
            listOf(ToolParam("output", required = true), ToolParam("paths", "array", "Workspace files to include.", required = true)), Risk.WRITE, "documents")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard {
            val out = ctx.workspace.resolve(args.str("output") ?: throw IllegalArgumentException("output is required"))
            if (out.exists()) return@guard ToolResult.Error("${args.str("output")} already exists.")
            val paths = args.getAsJsonArray("paths")?.map { it.asString } ?: throw IllegalArgumentException("paths is required")
            val files = LinkedHashMap<String, ByteArray>()
            for (p in paths) { val f = ctx.workspace.resolve(p); if (f.isDirectory) f.walkTopDown().filter { it.isFile }.forEach { files[ctx.workspace.relative(it)] = it.readBytes() } else if (f.isFile) files[ctx.workspace.relative(f)] = f.readBytes() else return@guard ToolResult.Error("No such file: $p") }
            val g = generator.zip(out.name, files)
            out.writeBytes(g.bytes)
            ToolResult.Ok("Created ${args.str("output")} with ${files.size} files.", listOf(ToolArtifact(out.path, out.name, g.mime)))
        }
    }
}
