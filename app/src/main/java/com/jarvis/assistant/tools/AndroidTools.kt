package com.jarvis.assistant.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.gson.JsonObject
import com.jarvis.assistant.tools.builtin.str
import com.jarvis.assistant.tools.server.ServerManager
import com.jarvis.assistant.util.ActivityLauncherHelper
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/** Opens a web address on the phone. Local preview addresses are allowed only for previews this app is running. */
class OpenUrlTool(private val context: Context, private val servers: ServerManager) : Tool {
    override val spec = ToolSpec("open_url", "Open a web address (https, or a running local preview such as http://127.0.0.1:PORT/) in Chrome on the phone so the user can see it.",
        listOf(ToolParam("url", required = true)), Risk.NETWORK, "browser")
    override fun describe(args: JsonObject) = "Open ${args.str("url")?.substringBefore('?')} in the browser"
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = args.str("url")?.trim().orEmpty()
        val uri = try { Uri.parse(raw) } catch (e: Exception) { return ToolResult.Error("That is not a valid address.") }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return ToolResult.Error("Only http and https addresses can be opened.")
        val host = uri.host?.lowercase().orEmpty()
        if (host == "127.0.0.1" || host == "localhost") {
            val ok = servers.running().values.any { it.boundPort == uri.port }
            if (!ok) return ToolResult.Error("No preview is running on that port. Start one with preview_start first.")
        } else if (uri.userInfo != null) return ToolResult.Error("Addresses with a username or password are not opened.")
        val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chrome = Intent(base).setPackage("com.android.chrome")
        val opened = ActivityLauncherHelper.startActivitySafely(context, chrome) || ActivityLauncherHelper.startActivitySafely(context, base)
        return if (opened) ToolResult.Ok("Opened $raw in the browser.") else ToolResult.Error("The browser could not be opened. Open $raw yourself.")
    }
}

/**
 * Asks for permission when Jarvis is not on screen (for example during a voice session while the user is in another
 * app). A heads-up notification offers Allow once / Deny. No answer within two minutes means deny.
 */
object ApprovalNotifier {
    private const val CHANNEL = "tool_approvals"
    const val ACTION = "com.jarvis.assistant.TOOL_APPROVAL"
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Decision>>()
    private var counter = 1000

    fun resolve(id: Int, decision: Decision) { pending.remove(id)?.complete(decision) }

    suspend fun ask(context: Context, request: ConfirmRequest, timeoutMs: Long): Decision {
        if (PermissionPreferences.isAutoApproveAll(context)) return Decision.ALLOW_TASK
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return Decision.DENY
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Permission requests", NotificationManager.IMPORTANCE_HIGH).apply { description = "Asks before Jarvis changes files or runs programs while you are in another app." })
        val id = synchronized(this) { ++counter }
        val answer = CompletableDeferred<Decision>(); pending[id] = answer
        fun action(label: String, d: Decision, code: Int) = NotificationCompat.Action(0, label, PendingIntent.getBroadcast(context, id * 4 + code,
            Intent(ACTION).setPackage(context.packageName).putExtra("id", id).putExtra("decision", d.name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(if (request.destructive) "Confirm: ${request.risk.label}" else "Allow ${request.tool}?")
            .setContentText(request.summary).setStyle(NotificationCompat.BigTextStyle().bigText(request.summary + "\n" + request.risk.label))
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_CALL).setAutoCancel(true).setTimeoutAfter(timeoutMs)
            .addAction(action("Allow once", Decision.ALLOW_ONCE, 0)).addAction(action("Deny", Decision.DENY, 1)).build()
        try { nm.notify(id, n) } catch (e: SecurityException) { pending.remove(id); return Decision.DENY }
        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { answer.await() } ?: Decision.DENY
        pending.remove(id); nm.cancel(id); return result
    }
}

class ToolApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val d = runCatching { Decision.valueOf(intent.getStringExtra("decision") ?: "DENY") }.getOrDefault(Decision.DENY)
        ApprovalNotifier.resolve(intent.getIntExtra("id", -1), d)
    }
}
