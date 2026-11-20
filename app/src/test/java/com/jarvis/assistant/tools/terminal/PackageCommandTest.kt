package com.jarvis.assistant.tools.terminal

import org.junit.Assert.*
import org.junit.Test

class PackageCommandTest {
    private fun run(line: String) = PackageCommand.parse(line) as PackagePlan.Run

    @Test fun pkg_install_maps_to_a_real_termux_install() {
        val p = run("pkg install git nodejs")
        assertTrue(p.script, p.script.contains("pk_safe git"))
        assertTrue(p.script, p.script.contains("install_node nodejs\n"))
    }

    @Test fun node_installs_are_verified_and_can_fall_back() {
        val s = run("pkg install nodejs").script
        // the helper must check that node really starts, repair the package system and fall back to the other Node build
        assertTrue(s, s.contains("install_node()") && s.contains("node_ok") && s.contains("heal()") && s.contains("nodejs-lts"))
        assertTrue(s, s.contains("pkg update -y"))
    }

    @Test fun node_lts_is_its_own_package_and_not_an_alias_of_nodejs() {
        assertTrue(run("pkg install nodejs-lts").script.contains("install_node nodejs-lts\n"))
        assertTrue(run("pkg install node").script.contains("install_node nodejs-lts\n"))
        assertTrue(run("pkg remove node").script.contains("pkg uninstall -y nodejs nodejs-lts"))
    }

    @Test fun aliases_and_apt_and_bare_install_work() {
        assertTrue(run("apt install -y node").script.contains("install_node nodejs-lts\n"))
        assertTrue(run("install python3").script.contains("pk_safe python"))
        assertTrue(run("jpkg i npm").script.contains("install_node nodejs-lts"))
    }

    @Test fun ai_tools_pull_in_what_they_need_in_order() {
        val s = run("jpkg install gemini").script
        assertTrue(s, s.lastIndexOf("\nensure_node\n") in 0 until s.indexOf("npm install -g --no-fund --no-audit @google/gemini-cli"))
        val a = run("jpkg install aider").script
        assertTrue(a, a.contains("pk_safe python clang git") && a.contains("pip install --upgrade --no-input aider-chat"))
    }

    @Test fun antigravity_cli_installs_glibc_node_and_the_termux_port() {
        for (name in listOf("agy", "antigravity", "antigravity-cli")) {
            val s = run("jpkg install $name").script
            assertTrue(s, s.contains("\nensure_glibc\n") && s.contains("\nensure_node\n"))
            assertTrue(s, s.contains("npm install -g --no-fund --no-audit @bash0816/agy-termux"))
            assertTrue(s, s.contains("command -v agy"))
            // glibc must be in place before the npm tool is installed
            assertTrue(s, s.lastIndexOf("\nensure_glibc\n") in 0 until s.indexOf("npm install -g --no-fund --no-audit @bash0816/agy-termux"))
        }
        assertTrue((PackageCommand.parse("jpkg ai") as PackagePlan.Show).text.contains("Antigravity CLI"))
    }

    @Test fun codex_comes_from_termux_itself_not_npm() {
        val s = run("jpkg install codex").script
        assertTrue(s, s.contains("pk_safe codex") && !s.contains("npm install -g"))
    }

    @Test fun tools_that_cannot_run_on_android_explain_why_instead_of_failing() {
        for (name in listOf("claude", "claude-code", "opencode", "copilot", "kilo")) {
            val plan = PackageCommand.parse("jpkg install $name")
            assertTrue("$name: $plan", plan is PackagePlan.Show)
            assertTrue((plan as PackagePlan.Show).text.contains("Android"))
        }
        // asked together with something installable: the rest still installs and the skipped one is reported in the script
        val mixed = run("jpkg install claude codex").script
        assertTrue(mixed, mixed.contains("Skipped") && mixed.contains("pk_safe codex") && !mixed.contains("npm install -g --no-fund --no-audit @anthropic-ai"))
    }

    @Test fun every_install_ends_by_checking_the_tool_is_really_there() {
        val s = run("jpkg install gemini").script
        assertTrue(s, s.contains("command -v gemini") && s.contains("Checking what was installed"))
    }

    @Test fun unknown_names_are_passed_as_termux_packages_but_only_if_safe() {
        assertTrue(run("pkg install htop").script.contains("pk_safe htop"))
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
