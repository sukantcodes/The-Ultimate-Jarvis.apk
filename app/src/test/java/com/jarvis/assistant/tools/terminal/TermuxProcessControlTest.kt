package com.jarvis.assistant.tools.terminal

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class TermuxProcessControlTest {
    private val bash = listOf("/bin/bash", "/usr/bin/bash").firstOrNull { File(it).canExecute() }

    @Test fun tokens_are_validated_so_nothing_else_reaches_a_script() {
        assertTrue(TermuxProcessControl.isValidToken(TermuxProcessControl.newToken()))
        for (bad in listOf("", "abc", "../../etc", "abcdef0123456789; rm -rf /", "ABCDEF0123456789ABCD")) {
            assertFalse(bad, TermuxProcessControl.isValidToken(bad))
            assertThrows(IllegalArgumentException::class.java) { TermuxProcessControl.killScript(bad) }
            assertThrows(IllegalArgumentException::class.java) { TermuxProcessControl.track("true", bad) }
        }
    }

    @Test fun tracking_records_the_shell_and_marks_the_script() {
        val t = TermuxProcessControl.newToken()
        val s = TermuxProcessControl.track("sleep 1\n", t)
        assertTrue(s.contains("jv_tok_$t"))
        assertTrue(s.contains("echo \$\$ >"))
        assertTrue(s.contains("trap 'rm -f"))
        assertTrue(s.endsWith("sleep 1\n"))
        assertFalse(s.contains("§"))
        assertFalse(TermuxProcessControl.killScript(t).contains("§"))
    }

    @Test fun the_kill_script_stops_a_whole_process_tree_and_removes_the_pid_file() {
        assumeTrue(bash != null && File("/proc/self/stat").exists())
        val home = Files.createTempDirectory("jv-home").toFile()
        val t = TermuxProcessControl.newToken()
        val script = TermuxProcessControl.track("sleep 611 &\n( sleep 612 & sleep 613 & wait ) &\nsleep 614\n", t)
        val run = ProcessBuilder(bash, "-c", script).also { it.environment()["HOME"] = home.path }.redirectErrorStream(true).start()
        val pidFile = File(home, ".jarvis-run/$t.pid")
        val deadline = System.currentTimeMillis() + 5000
        while (!pidFile.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("the shell records its pid", pidFile.exists())
        Thread.sleep(300)
        val kill = ProcessBuilder(bash, "-c", TermuxProcessControl.killScript(t)).also { it.environment()["HOME"] = home.path }.redirectErrorStream(true).start()
        assertTrue(kill.waitFor(20, TimeUnit.SECONDS))
        assertTrue("the tracked shell is gone", run.waitFor(10, TimeUnit.SECONDS))
        assertFalse(pidFile.exists())
        val left = ProcessBuilder("ps", "-eo", "args=").start().inputStream.bufferedReader().readLines().count { it.trim().matches(Regex("sleep 61[1-4]")) }
        assertEquals("no sleeper survives", 0, left)
    }

    @Test fun a_stale_pid_file_never_kills_an_unrelated_process() {
        assumeTrue(bash != null && File("/proc/self/stat").exists())
        val home = Files.createTempDirectory("jv-home").toFile()
        val t = TermuxProcessControl.newToken()
        val innocent = ProcessBuilder("sleep", "615").start()
        try {
            val pid = ProcessTree.pidOf(innocent) ?: error("no pid")
            File(home, ".jarvis-run").mkdirs(); File(home, ".jarvis-run/$t.pid").writeText("$pid\n")
            val kill = ProcessBuilder(bash, "-c", TermuxProcessControl.killScript(t)).also { it.environment()["HOME"] = home.path }.start()
            assertTrue(kill.waitFor(20, TimeUnit.SECONDS))
            assertTrue("unrelated process survives", innocent.isAlive)
            assertFalse(File(home, ".jarvis-run/$t.pid").exists())
        } finally { innocent.destroyForcibly() }
    }
}
