package com.jarvis.assistant.tools.terminal

import org.junit.Assert.*
import org.junit.Test

class PackageCommandTest {
    private fun run(line: String) = PackageCommand.parse(line) as PackagePlan.Run

    @Test fun pkg_install_maps_to_a_real_termux_install() {
        val p = run("pkg install git nodejs")
        assertTrue(p.script, p.script.contains("pkg install -y git nodejs"))
    }

    @Test fun aliases_and_apt_and_bare_install_work() {
        assertTrue(run("apt install -y node").script.contains("pkg install -y nodejs"))
        assertTrue(run("install python3").script.contains("pkg install -y python"))
        assertTrue(run("jpkg i npm").script.contains("nodejs"))
    }

    @Test fun ai_tools_pull_in_what_they_need_in_order() {
        val s = run("jpkg install gemini").script
        assertTrue(s, s.indexOf("pkg install -y nodejs") in 0 until s.indexOf("npm install -g @google/gemini-cli"))
        val a = run("jpkg install aider").script
        assertTrue(a, a.contains("pkg install -y python clang git") && a.contains("pip install --upgrade aider-chat"))
    }

    @Test fun unknown_names_are_passed_as_termux_packages_but_only_if_safe() {
        assertTrue(run("pkg install htop").script.contains("pkg install -y htop"))
        assertTrue(PackageCommand.parse("pkg install foo;rm") == null)           // chained: real shell, not handled
        assertTrue(PackageCommand.parse("pkg install \$(id)") == null)
        val bad = PackageCommand.parse("pkg install --evil") as PackagePlan.Show
        assertTrue(bad.text.contains("not a valid"))
    }

    @Test fun other_commands_are_left_alone() {
        assertNull(PackageCommand.parse("ls -la"))
        assertNull(PackageCommand.parse("git status"))
        assertFalse(PackageCommand.handles("pkg install a | tee x"))
    }

    @Test fun engine_remove_update_and_lists() {
        assertTrue((PackageCommand.parse("jpkg engine termux") as PackagePlan.Engine).termux)
        assertFalse((PackageCommand.parse("jpkg engine app") as PackagePlan.Engine).termux)
        assertTrue(run("pkg remove git").script.contains("pkg uninstall -y git"))
        assertTrue(run("pkg update").script.contains("pkg upgrade -y"))
        assertTrue((PackageCommand.parse("jpkg ai") as PackagePlan.Show).text.contains("Gemini CLI"))
    }
}
