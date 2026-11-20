package com.jarvis.assistant.tools.terminal

import android.content.Context
import java.io.File

/**
 * The terminal's backend. Chooses, for every command, between this app's own shell and Termux (a full Linux user
 * space with git, node, npm, python and a package manager). The choice is a setting, so switching needs no restart.
 */
class SwitchingBackend(private val context: Context, private val local: TerminalBackend = ProcessBackend()) : TerminalBackend {
    private val termux by lazy { TermuxBackend(context) }
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val active: TerminalBackend get() = if (useTermux(context)) termux else local

    override val name: String get() = active.name
    override val shell: String get() = active.shell
    override val isRemote: Boolean get() = active.isRemote
    override fun environment(workspaceRoot: File): Map<String, String> = active.environment(workspaceRoot)
    override fun launch(script: String, cwd: File, env: Map<String, String>, onOutput: (String) -> Unit): RunningCommand =
        active.launch(script, cwd, env, onOutput)

    companion object {
        const val PREFS = "tool_permissions"
        const val KEY = "terminal_backend"
        /** Termux is used whenever it is installed, unless the person explicitly picked the app shell ("jpkg engine app"). */
        fun wantsTermux(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) != "app"
        fun useTermux(context: Context) = wantsTermux(context) && TermuxBackend.isInstalled(context)
        fun setTermux(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, if (on) "termux" else "app").apply()
        }
    }
}
