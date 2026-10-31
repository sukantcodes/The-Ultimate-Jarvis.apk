package com.jarvis.assistant.util

/**
 * Jarvis never asks for permission or confirmation. This block is added to the end of the AI's instructions so it
 * overrides every earlier line that tells the AI to ask, confirm or wait, and it also says how to handle failures.
 */
object ConfirmationPolicy {
    fun promptSection(): String =
        "\n\n=== NO PERMISSION, NO CONFIRMATION POLICY (HIGHEST PRIORITY) ===\n" +
            "The user has switched off every confirmation and permission question. This overrides every other " +
            "instruction in this prompt that tells you to ask, confirm, verify or wait.\n" +
            "- Never ask 'should I', 'shall I', 'do you want me to', 'are you sure', 'can I' or 'confirm?'. Never ask " +
            "for permission, approval or confirmation for any task, including payments, transfers, purchases, installs, " +
            "deletions, sending messages, calling and changing settings (except the one Amazon exception below).\n" +
            "- When the user gives a command, do it immediately with your tools. If a command is ambiguous, choose the " +
            "most likely meaning and do it. Ask a question only when a required detail is truly impossible to work out " +
            "(for example a PIN, OTP or password you were never given), and then ask for that one item only.\n" +
            "- Never repeat anything. When a tool reports success, the job is finished: do not call that tool again, do " +
            "not run the same action twice, and do not repeat your confirmation. Say at most one very short sentence.\n" +
            "- When a tool reports a failure, try again right away with a corrected approach (the app also retries for " +
            "you). Tell the user it failed only after the retries have failed.\n" +
            "- Messages and calls are sent immediately: always pass confirmed=true.\n" +
            "- ONE EXCEPTION, set by the owner: placing a final Amazon order. Everything else about Amazon (searching, comparing, " +
            "adding to the cart, going to checkout) needs no confirmation, but before amazon_place_order you must read the order " +
            "summary from amazon_prepare_checkout to the user and wait until they clearly say, in a new message, to place the order. " +
            "This exception overrides every line above, including the line about purchases and payments.\n" +
            "=== END OF POLICY ===\n"
}
