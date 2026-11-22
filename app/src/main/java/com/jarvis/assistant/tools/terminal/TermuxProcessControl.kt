package com.jarvis.assistant.tools.terminal

import java.util.UUID

/**
 * Process tracking for commands that run inside Termux. Termux is another app, so this app never gets a pid or a
 * handle for what it started, and cancelling used to mean "stop waiting" while the command kept running. These two
 * scripts close that gap without any Android code, so they can be tested with a real shell:
 *
 *  - [track] makes the shell that runs a command write its own pid to `~/.jarvis-run/<token>.pid` and delete the file
 *    when the command ends. The script text also carries a `jv_tok_<token>` marker, so the pid can be recognised later.
 *  - [killScript] reads that file, checks the pid is still *that* command (pids are reused), stops the whole process
 *    tree under it (children first discovered breadth-first, polite signal first, forceful after a short grace) and
 *    releases the Termux wake lock when none of our commands is left.
 *
 * Only a validated token ever reaches a script, never text from the person or a model.
 */
object TermuxProcessControl {
    const val RUN_DIR = ".jarvis-run"
    private val TOKEN = Regex("^[0-9a-f]{12,32}$")

    fun newToken(): String = UUID.randomUUID().toString().replace("-", "").take(20)

    fun isValidToken(token: String): Boolean = TOKEN.matches(token)

    /** Prepends pid tracking to [script]. The shell running the result is the one recorded. */
    fun track(script: String, token: String): String {
        require(isValidToken(token)) { "Bad process token." }
        return TRACK.replace("§", "$").replace("TOKEN", token).replace("RUNDIR", RUN_DIR) + script
    }

    /** A script that stops the command tracked under [token], and everything it started. Safe to run twice. */
    fun killScript(token: String): String {
        require(isValidToken(token)) { "Bad process token." }
        return KILL.replace("§", "$").replace("TOKEN", token).replace("RUNDIR", RUN_DIR)
    }

    private val TRACK = """
        : jv_tok_TOKEN
        __jv_pf="§HOME/RUNDIR/TOKEN.pid"
        mkdir -p "§HOME/RUNDIR" 2>/dev/null
        echo §§ > "§__jv_pf" 2>/dev/null
        trap 'rm -f "§__jv_pf"' EXIT

    """.trimIndent() + "\n"

    private val KILL = """
        f="§HOME/RUNDIR/TOKEN.pid"
        [ -f "§f" ] || exit 0
        root="§(cat "§f" 2>/dev/null)"
        case "§root" in ''|*[!0-9]*) rm -f "§f"; exit 0;; esac
        if ! tr '\0' ' ' < "/proc/§root/cmdline" 2>/dev/null | grep -q "jv_tok_TOKEN"; then rm -f "§f"; exit 0; fi
        all="§root"; frontier="§root"
        while [ -n "§frontier" ]; do
          next=""
          for d in /proc/[0-9]*; do
            s="§(cat "§d/stat" 2>/dev/null)" || continue
            s="§{s##*) }"
            set -- §s
            pp="§2"
            for p in §frontier; do
              if [ "§pp" = "§p" ]; then next="§next §{d#/proc/}"; break; fi
            done
          done
          all="§all §next"; frontier="§next"
        done
        kill -TERM §all 2>/dev/null
        n=0
        while [ §n -lt 10 ]; do
          alive=0
          for p in §all; do kill -0 "§p" 2>/dev/null && alive=1; done
          [ §alive = 0 ] && break
          sleep 0.3
          n=§((n+1))
        done
        kill -KILL §all 2>/dev/null
        rm -f "§f"
        busy=0
        for o in "§HOME"/RUNDIR/*.pid; do
          [ -f "§o" ] || continue
          op="§(cat "§o" 2>/dev/null)"
          case "§op" in ''|*[!0-9]*) continue;; esac
          kill -0 "§op" 2>/dev/null && busy=1
        done
        if [ "§busy" = 0 ]; then command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock >/dev/null 2>&1; fi
        exit 0

    """.trimIndent() + "\n"
}
