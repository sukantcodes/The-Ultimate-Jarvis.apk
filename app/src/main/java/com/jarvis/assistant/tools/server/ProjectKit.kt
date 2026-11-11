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
    enum class Template(val label: String) { STATIC("Blank page"), LANDING("Landing page"), APP("Interactive app (to-do list)"), GAME("Canvas game (Snake, touch + keyboard)") }

    fun create(dir: File, template: Template, title: String): List<String> {
        val t = title.trim().ifEmpty { "My site" }.replace("<", "&lt;").replace(">", "&gt;")
        val files = when (template) {
            Template.STATIC -> mapOf("index.html" to page(t, "<main><h1>$t</h1><p>Start building here.</p></main>"), "style.css" to baseCss(), "script.js" to "// Your code goes here.\n")
            Template.LANDING -> mapOf("index.html" to page(t, """<header class="hero"><h1>$t</h1><p>A short sentence that says what you do.</p><a class="btn" href="#contact">Get in touch</a></header>
<section class="cards"><article><h2>Fast</h2><p>Loads instantly with no build step.</p></article><article><h2>Simple</h2><p>Plain HTML and CSS you can edit.</p></article><article><h2>Yours</h2><p>Export it and host it anywhere.</p></article></section>
<footer id="contact"><p>Contact: hello@example.com</p></footer>"""), "style.css" to baseCss() + "\n.hero{padding:4rem 1rem;text-align:center;background:linear-gradient(135deg,#1e293b,#334155);color:#fff}\n.btn{display:inline-block;margin-top:1rem;padding:.75rem 1.5rem;border-radius:.5rem;background:#38bdf8;color:#0f172a;text-decoration:none;font-weight:600}\n.cards{display:grid;gap:1rem;grid-template-columns:repeat(auto-fit,minmax(14rem,1fr));padding:2rem 1rem;max-width:60rem;margin:auto}\n.cards article{border:1px solid #cbd5e1;border-radius:.75rem;padding:1rem}\nfooter{text-align:center;padding:2rem 1rem;color:#475569}\n", "script.js" to "")
            Template.APP -> mapOf("index.html" to page(t, "<main><h1>$t</h1><form id=\"f\"><input id=\"in\" placeholder=\"Add a task\" aria-label=\"New task\" required><button>Add</button></form><ul id=\"list\"></ul></main>"), "style.css" to baseCss() + "\nform{display:flex;gap:.5rem}input{flex:1;padding:.6rem}li{padding:.4rem 0;cursor:pointer}li.done{text-decoration:line-through;opacity:.6}\n",
                "script.js" to "const list=document.getElementById('list');let items=[];try{items=JSON.parse(localStorage.getItem('items')||'[]')}catch(e){}\nfunction save(){try{localStorage.setItem('items',JSON.stringify(items))}catch(e){}}\nfunction render(){list.innerHTML='';items.forEach((it,i)=>{const li=document.createElement('li');li.textContent=it.text;if(it.done)li.className='done';li.onclick=()=>{it.done=!it.done;save();render()};list.appendChild(li)})}\ndocument.getElementById('f').onsubmit=e=>{e.preventDefault();const v=document.getElementById('in');items.push({text:v.value,done:false});v.value='';save();render()};render();\n")
            Template.GAME -> mapOf("index.html" to page(t, "<main class=\"g\"><h1>$t</h1><p id=\"score\"></p><canvas id=\"game\" width=\"400\" height=\"400\"></canvas><p class=\"hint\">Arrow keys / WASD, or swipe on the screen.</p></main>"),
                "style.css" to baseCss() + "\n.g{text-align:center;max-width:26rem}canvas{width:100%;max-width:400px;aspect-ratio:1;border-radius:.75rem;touch-action:none;background:#0f172a}.hint{color:#64748b;font-size:.9rem}\n",
                "script.js" to GAME_JS)
        }
        dir.mkdirs(); files.forEach { (n, c) -> File(dir, n).writeText(c) }
        return files.keys.toList()
    }

    private val GAME_JS = """const c=document.getElementById('game'),x=c.getContext('2d'),N=20;let S,dir,next,food,score,best=0,timer,over;
try{best=+localStorage.getItem('best')||0}catch(e){}
function reset(){S=[{x:10,y:10},{x:9,y:10},{x:8,y:10}];dir={x:1,y:0};next=dir;score=0;over=false;place();clearInterval(timer);timer=setInterval(step,110);hud()}
function place(){do{food={x:Math.floor(Math.random()*N),y:Math.floor(Math.random()*N)}}while(S.some(s=>s.x===food.x&&s.y===food.y))}
function hud(){document.getElementById('score').textContent='Score '+score+'  Best '+best}
function step(){dir=next;const h={x:S[0].x+dir.x,y:S[0].y+dir.y};
 if(h.x<0||h.y<0||h.x>=N||h.y>=N||S.some(s=>s.x===h.x&&s.y===h.y)){over=true;clearInterval(timer);if(score>best){best=score;try{localStorage.setItem('best',best)}catch(e){}}hud();draw();return}
 S.unshift(h);if(h.x===food.x&&h.y===food.y){score++;place();hud()}else S.pop();draw()}
function draw(){const u=c.width/N;x.fillStyle='#0f172a';x.fillRect(0,0,c.width,c.height);x.fillStyle='#f43f5e';x.fillRect(food.x*u+2,food.y*u+2,u-4,u-4);
 S.forEach((s,i)=>{x.fillStyle=i?'#22c55e':'#86efac';x.fillRect(s.x*u+1,s.y*u+1,u-2,u-2)});
 if(over){x.fillStyle='rgba(0,0,0,.6)';x.fillRect(0,0,c.width,c.height);x.fillStyle='#fff';x.font='bold 28px system-ui';x.textAlign='center';x.fillText('Game over',c.width/2,c.height/2-6);x.font='16px system-ui';x.fillText('Tap or press Space to play again',c.width/2,c.height/2+22)}}
function turn(nx,ny){if(over){reset();return}if(nx===-dir.x&&ny===-dir.y)return;next={x:nx,y:ny}}
addEventListener('keydown',e=>{const k={ArrowUp:[0,-1],ArrowDown:[0,1],ArrowLeft:[-1,0],ArrowRight:[1,0],w:[0,-1],s:[0,1],a:[-1,0],d:[1,0]}[e.key];if(k){e.preventDefault();turn(k[0],k[1])}else if(e.key===' '&&over)reset()});
let t0=null;c.addEventListener('touchstart',e=>{t0=e.touches[0];e.preventDefault()},{passive:false});
c.addEventListener('touchend',e=>{if(!t0)return;const t=e.changedTouches[0],dx=t.clientX-t0.clientX,dy=t.clientY-t0.clientY;if(Math.max(Math.abs(dx),Math.abs(dy))<20){if(over)reset();return}Math.abs(dx)>Math.abs(dy)?turn(dx>0?1:-1,0):turn(0,dy>0?1:-1);t0=null});
c.addEventListener('click',()=>{if(over)reset()});reset();
"""

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
