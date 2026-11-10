package com.jarvis.assistant.tools.terminal

/**
 * Describes how a Termux-backed [TerminalBackend] would be wired on Android. This is a description of the contract,
 * not an implementation: it contains no Android code, so it can be unit tested and shown in settings.
 *
 * Why Termux at all: the app's own shell (see [ProcessBackend]) can only run what Android ships, so it has no git,
 * node, npm or python. Termux provides a real package-managed Linux user space, so builds of React/Vite projects,
 * `git clone`, `npm install` and similar work there.
 *
 * How the bridge works: the app sends an explicit Intent to Termux's RunCommandService; Termux runs the command in
 * its own environment and can return stdout, stderr and the exit code in a PendingIntent result. Termux keeps its
 * files in its own private folder, so exchanging files means using a folder both apps can reach (shared storage) or
 * having the command read and write only inside the folder named by [EXTRA_WORKDIR].
 */
data class TermuxBridgeSpec(
    val packageName: String = "com.termux",
    val service: String = "com.termux.app.RunCommandService",
    val action: String = ACTION_RUN_COMMAND,
    val permission: String = PERMISSION_RUN_COMMAND,
    /** Executable inside Termux, normally $PREFIX/bin/bash or $PREFIX/bin/sh. */
    val shellPath: String = "/data/data/com.termux/files/usr/bin/bash"
) {
    /** What the person must do once, in Termux itself. The app can not do this for them. */
    val userSetupSteps: List<String> = listOf(
        "Install Termux from F-Droid or its GitHub releases (the Play Store build is outdated).",
        "Open Termux once and run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties",
        "Close all Termux sessions (or run termux-reload-settings) so the setting is read.",
        "In Android settings, grant JARVIS the permission \"Run commands in Termux environment\" ($PERMISSION_RUN_COMMAND).",
        "In Termux run: pkg install git nodejs python"
    )

    /** The Intent extras that make up one command. Values are described, not set. */
    val intentExtras: Map<String, String> = mapOf(
        EXTRA_COMMAND_PATH to "Absolute path of the executable, for example $shellPath",
        EXTRA_ARGUMENTS to "String array of arguments, for example [\"-c\", \"<the command>\"]. Quote every model-supplied piece with ShellQuote.",
        EXTRA_WORKDIR to "Working directory inside Termux's reach",
        EXTRA_BACKGROUND to "true: run without opening a Termux window (required for automatic use)",
        EXTRA_PENDING_INTENT to "PendingIntent that Termux fires with the result bundle (stdout, stderr, exitCode, err, errmsg)"
    )

    val limitations: List<String> = listOf(
        "Output arrives once, when the command ends; it is not streamed line by line.",
        "Android can stop Termux in the background; long builds need Termux's wake lock or the battery restriction lifted.",
        "Termux and JARVIS have separate private storage, so project files must live in a shared folder to be seen by both.",
        "If Termux is not installed, external apps are not allowed, or the permission is missing, the intent fails; the app must show that instead of pretending it ran."
    )

    companion object {
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    }
}
