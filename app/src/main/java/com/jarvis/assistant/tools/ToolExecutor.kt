package com.jarvis.assistant.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs a tool call requested by any AI provider (custom API or Gemini). The provider only asks; this class
 * validates, applies the permission policy, runs the tool, redacts secrets and writes the audit log. A call whose
 * id was already executed returns its first result instead of running twice.
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val policy: PermissionPolicy,
    private val audit: AuditLog,
    private val confirmer: () -> Confirmer,
    private val redactor: SecretRedactor,
    private val workspace: Workspace,
    private val defaultTimeoutMs: Long = 120_000L
) {
    private val done = ConcurrentHashMap<String, ToolResult>()
    @Volatile var onProgress: (String) -> Unit = {}
    @Volatile var cancelled: Boolean = false

    suspend fun execute(call: ToolCall, timeoutMs: Long = defaultTimeoutMs): ToolResult {
        val executionKey = "${call.source}:${call.id}:${call.name}:${call.args}"
        done[executionKey]?.let { return it }
        val result = run(call, timeoutMs)
        // Only remember real outcomes: a denied call can be asked again and approved.
        if (result !is ToolResult.Denied) done[executionKey] = result
        return result
    }

    private suspend fun run(call: ToolCall, timeoutMs: Long): ToolResult {
        val tool = registry.get(call.name)
            ?: return ToolResult.Error("There is no tool named \"${call.name}\".").also { audit.record(call.name, call.source, Risk.READ, "n/a", "unknown tool", "error") }
        val spec = tool.spec
        val missing = spec.params.filter { it.required && !call.args.has(it.name) }.map { it.name }
        if (missing.isNotEmpty()) return ToolResult.Error("Missing required argument(s): ${missing.joinToString()}.")
        val executionJob = currentCoroutineContext()[Job]
        val ctx = object : ToolContext {
            override val workspace: Workspace = this@ToolExecutor.workspace
            override fun progress(message: String) = onProgress(redactor.redact(message))
            override val cancelled: Boolean get() = this@ToolExecutor.cancelled || executionJob?.isActive == false
        }
        val summary = try { tool.describe(call.args) } catch (e: Exception) { spec.description }
        val destructive = try { tool.isDestructive(call.args, ctx) } catch (e: Exception) { true }
        val effectiveRisk = if (destructive) Risk.DESTRUCTIVE else spec.risk
        val mode = policy.modeFor(effectiveRisk, spec.group)

        val exactRequest = spec.name + ":" + call.args.toString()
        val decision: String = when {
            mode == Mode.DENY -> return deny(call, effectiveRisk, summary, "This kind of action is switched off in Permissions.")
            mode == Mode.ASK && policy.consumeNext(exactRequest) && !destructive -> "allowed (next identical call)"
            mode == Mode.ASK && policy.hasSessionGrant(spec.name) && !destructive -> "allowed (session grant)"
            mode == Mode.ASK -> {
                val d = confirmer().confirm(ConfirmRequest(spec.name, spec.group, effectiveRisk, redactor.redact(summary), destructive, call.source))
                when (d) {
                    Decision.DENY -> return deny(call, effectiveRisk, summary, "You declined this action.")
                    Decision.ALLOW_ONCE -> "allowed (once)"
                    Decision.ALLOW_NEXT -> {
                        if (!destructive) policy.grantNext(exactRequest)
                        "allowed (next identical call)"
                    }
                    Decision.ALLOW_SESSION -> {
                        if (!destructive) policy.grantForSession(spec.name)
                        "allowed (session)"
                    }
                    Decision.ALLOW_ALWAYS -> {
                        if (!destructive) {
                            policy.setMode(effectiveRisk, Mode.ALLOW, spec.group)
                            "allowed (always)"
                        } else "allowed (once)"
                    }
                }
            }
            else -> "allowed (policy)"
        }

        val result = try {
            withTimeout(timeoutMs) { withContext(Dispatchers.IO) { tool.run(call.args, ctx) } }
        } catch (e: TimeoutCancellationException) {
            ToolResult.Error("The tool took longer than ${timeoutMs / 1000} seconds and was stopped.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Workspace.PathRejected) {
            ToolResult.Error(e.message ?: "That path is not allowed.")
        } catch (e: Exception) {
            ToolResult.Error("${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        }
        val safe = when (result) {
            is ToolResult.Ok -> result.copy(output = redactor.redact(result.output).take(MAX_OUTPUT))
            is ToolResult.Error -> result.copy(message = redactor.redact(result.message).take(MAX_OUTPUT))
            is ToolResult.Denied -> result
        }
        audit.record(spec.name, call.source, effectiveRisk, decision, summary, if (safe.isOk) "ok" else safe.text)
        return safe
    }

    private fun deny(call: ToolCall, risk: Risk, summary: String, reason: String): ToolResult {
        audit.record(call.name, call.source, risk, "denied", summary, reason)
        return ToolResult.Denied(reason)
    }

    companion object { const val MAX_OUTPUT = 60_000 }
}
