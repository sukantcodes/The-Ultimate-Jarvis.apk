package com.jarvis.assistant.automation.studio

interface StudioActionExecutor {
    suspend fun execute(action: AutomationAction, variables: Map<String, String>): ActionExecutionResult
}

class StudioActionDispatcher(
    private val executors: Map<RuleActionType, StudioActionExecutor>
) {
    suspend fun execute(match: RuleMatch): List<ActionExecutionResult> {
        val results = mutableListOf<ActionExecutionResult>()
        for (action in match.rule.actions) {
            val executor = executors[action.type]
                ?: return results + ActionExecutionResult(
                    success = false,
                    message = "No executor registered for ${action.type}."
                )
            val result = executor.execute(action, match.variables)
            results += result
            // Do not run later actions after a failure: prevents cascading side effects.
            if (!result.success) break
        }
        return results
    }
}
