package com.jarvis.assistant.tools.server

import com.jarvis.assistant.tools.files.XmlEvents
import com.jarvis.assistant.tools.files.XmlScan
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Starter websites. They are plain HTML/CSS/JS so they run with no build step and no downloads. */
object ProjectScaffolder {
    enum class Template(val label: String) { STATIC("Blank page"), LANDING("Landing page"), APP("Interactive app (to-do list)") }

    fun create(dir: File, template: Template, title: String): List<String> {
        val t = title.trim().ifEmpty { "My site" }.replace("<", "&lt;").replace(">", "&gt;")
        val files = when (template) {
            Template.STATIC -> mapOf("index.html" to page(t, "<main><h1>$t</h1><p>Start building here.</p></main>"), "style.css" to baseCss(), "script.js" to "// Your code goes here.\n")
            Template.LANDING -> mapOf("index.html" to page(t, """<header class="hero"><h1>$t</h1><p>A short sentence that says what you do.</p><a class="btn" href="#contact">Get in touch</a></header>
<section class="cards"><article><h2>Fast</h2><p>Loads instantly with no build step.</p></article><article><h2>Simple</h2><p>Plain HTML and CSS you can edit.</p></article><article><h2>Yours</h2><p>Export it and host it anywhere.</p></article></section>
<footer id="contact"><p>Contact: hello@example.com</p></footer>"""), "style.css" to baseCss() + "\n.hero{padding:4rem 1rem;text-align:center;background:linear-gradient(135deg,#1e293b,#334155);color:#fff}\n.btn{display:inline-block;margin-top:1rem;padding:.75rem 1.5rem;border-radius:.5rem;background:#38bdf8;color:#0f172a;text-decoration:none;font-weight:600}\n.cards{display:grid;gap:1rem;grid-template-columns:repeat(auto-fit,minmax(14rem,1fr));padding:2rem 1rem;max-width:60rem;margin:auto}\n.cards article{border:1px solid #cbd5e1;border-radius:.75rem;padding:1rem}\nfooter{text-align:center;padding:2rem 1rem;color:#475569}\n", "script.js" to "")
            Template.APP -> mapOf("index.html" to page(t, "<main><h1>$t</h1><form id=\"f\"><input id=\"in\" placeholder=\"Add a task\" aria-label=\"New task\" required><button>Add</button></form><ul id=\"list\"></ul></main>"), "style.css" to baseCss() + "\nform{display:flex;gap:.5rem}input{flex:1;padding:.6rem}li{padding:.4rem 0;cursor:pointer}li.done{text-decoration:line-through;opacity:.6}\n",
                "script.js" to "const list=document.getElementById('list');let items=[];try{items=JSON.parse(localStorage.getItem('items')||'[]')}catch(e){}\nfunction save(){try{localStorage.setItem('items',JSON.stringify(items))}catch(e){}}\nfunction render(){list.innerHTML='';items.forEach((it,i)=>{const li=document.createElement('li');li.textContent=it.text;if(it.done)li.className='done';li.onclick=()=>{it.done=!it.done;save();render()};list.appendChild(li)})}\ndocument.getElementById('f').onsubmit=e=>{e.preventDefault();const v=document.getElementById('in');items.push({text:v.value,done:false});v.value='';save();render()};render();\n")
        }
        dir.mkdirs(); files.forEach { (n, c) -> File(dir, n).writeText(c) }
        return files.keys.toList()
    }

    private fun page(title: String, body: String) = "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n<title>$title</title>\n<link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n$body\n<script src=\"script.js\"></script>\n</body>\n</html>\n"
    private fun baseCss() = "*{box-sizing:border-box}body{margin:0;font-family:system-ui,sans-serif;line-height:1.5;color:#0f172a;background:#f8fafc}main{max-width:40rem;margin:2rem auto;padding:0 1rem}"
}

data class BuildReport(val ok: Boolean, val problems: List<String>, val notes: List<String>, val files: Int, val bytes: Long) {
    fun text(): String = (if (ok) "Project check passed" else "Project check found ${problems.size} problem(s)") + " ($files files, $bytes bytes).\n" +
        problems.joinToString("") { "Problem: $it\n" } + notes.joinToString("") { "Note: $it\n" }
}

/**
 * Checks a static site without running it: entry page present, HTML well-formed, every local link/script/stylesheet/
 * image it references exists. A framework project (package.json with a build script) is reported as needing a build,
 * which this phone workspace only does when Node is installed, and that is said plainly rather than faked.
 */
object ProjectBuilder {
    fun check(dir: File, nodeAvailable: Boolean = false): BuildReport {
        val problems = ArrayList<String>(); val notes = ArrayList<String>()
        val all = dir.walkTopDown().filter { it.isFile && !it.relativeTo(dir).path.startsWith(".") }.toList()
        val pkg = File(dir, "package.json")
        if (pkg.isFile) {
            val hasBuild = Regex("\"build\"\\s*:").containsMatchIn(pkg.readText())
            if (hasBuild && !File(dir, "dist/index.html").isFile && !File(dir, "build/index.html").isFile)
                notes += if (nodeAvailable) "This project needs a build step (npm run build) before it can be previewed." else "This project needs a build step, and Node.js is not available on this device, so it can not be built or previewed here. Export it and build it on a computer."
        }
        val entry = listOf("index.html", "dist/index.html", "build/index.html").map { File(dir, it) }.firstOrNull { it.isFile }
        if (entry == null) { problems += "There is no index.html."; return BuildReport(false, problems, notes, all.size, all.sumOf { it.length() }) }
        for (html in all.filter { it.extension.lowercase() in setOf("html", "htm") }) {
            val text = html.readText(); val rel = html.relativeTo(dir).path
            XmlScan.balancedError(text.replace(Regex("(?is)<!doctype[^>]*>"), "").let { stripVoid(it) })?.let { problems += "$rel is not well-formed: $it" }
            XmlScan.scan(text, object : XmlEvents {
                override fun start(name: String, attrs: String, selfClosing: Boolean) {
                    val n = XmlScan.local(name).lowercase()
                    val ref = when (n) { "script", "img", "source", "audio", "video", "iframe" -> XmlScan.attr(attrs, "src"); "link" -> XmlScan.attr(attrs, "href"); "a" -> XmlScan.attr(attrs, "href"); else -> null } ?: return
                    if (n == "a" && !(ref.endsWith(".html") || ref.endsWith("/"))) return
                    val t = localTarget(html.parentFile, dir, ref) ?: return
                    if (!t.exists()) problems += "$rel refers to ${ref.take(80)} but that file does not exist."
                }
            })
        }
        all.filter { it.extension.lowercase() == "css" }.forEach { css ->
            Regex("url\\(\\s*['\"]?([^)'\"]+)['\"]?\\s*\\)").findAll(css.readText()).forEach { m -> localTarget(css.parentFile, dir, m.groupValues[1])?.let { t -> if (!t.exists()) problems += "${css.relativeTo(dir).path} refers to ${m.groupValues[1].take(80)} which does not exist." } }
        }
        all.filter { it.extension.lowercase() == "json" }.forEach { j -> try { com.google.gson.JsonParser.parseString(j.readText()) } catch (e: Exception) { problems += "${j.relativeTo(dir).path} is not valid JSON." } }
        if (all.sumOf { it.length() } > 20L * 1024 * 1024) notes += "The project is larger than 20 MB."
        return BuildReport(problems.isEmpty(), problems.distinct(), notes, all.size, all.sumOf { it.length() })
    }

    private fun stripVoid(html: String) = html.replace(Regex("<(meta|link|img|br|hr|input|source|area|base|col|embed|wbr|track)\\b([^>]*?)/?>", RegexOption.IGNORE_CASE), "<$1$2/>")

    private fun localTarget(base: File, root: File, ref: String): File? {
        val r = ref.trim()
        if (r.isEmpty() || r.startsWith("#") || r.startsWith("data:") || r.startsWith("mailto:") || r.startsWith("javascript:") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(r) || r.startsWith("//")) return null
        val path = try { URI(r).path } catch (e: Exception) { return null } ?: return null
        val f = if (path.startsWith("/")) File(root, path.trimStart('/')) else File(base, path)
        return if (f.isDirectory) File(f, "index.html") else f
    }
}

/** Snapshots and exports of a project, as ZIP files kept in the workspace. */
object ProjectArchive {
    private val skip = Regex("(^|/)(\\.git|node_modules|\\.snapshots)(/|$)")

    fun zip(dir: File): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                if (skip.containsMatchIn(rel) || java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
                z.putNextEntry(ZipEntry(rel)); z.write(f.readBytes()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun snapshot(dir: File, label: String): File {
        val snaps = File(dir.parentFile, ".snapshots").also { it.mkdirs() }
        val f = File(snaps, "${dir.name}-${label.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { System.currentTimeMillis().toString() }}.zip")
        f.writeBytes(zip(dir)); return f
    }

    fun list(dir: File): List<File> = File(dir.parentFile, ".snapshots").listFiles { f -> f.name.startsWith(dir.name + "-") && f.extension == "zip" }?.sortedBy { it.name }.orEmpty()

    /** Replaces the project's files with the snapshot. Existing files not in the snapshot are removed. */
    fun restore(dir: File, snapshot: File) {
        val limits = com.jarvis.assistant.tools.files.ExtractionLimits()
        val data = com.jarvis.assistant.tools.files.SafeZip.read(snapshot.readBytes(), limits) { true }
        dir.walkTopDown().filter { it.isFile }.filter { !skip.containsMatchIn(it.relativeTo(dir).path.replace(File.separatorChar, '/')) }.forEach { it.delete() }
        val canon = dir.canonicalFile
        for ((name, bytes) in data) {
            val t = File(canon, name).canonicalFile
            if (!t.path.startsWith(canon.path + File.separator)) throw IllegalStateException("Unsafe entry in snapshot.")
            t.parentFile.mkdirs(); t.writeBytes(bytes)
        }
    }
}
