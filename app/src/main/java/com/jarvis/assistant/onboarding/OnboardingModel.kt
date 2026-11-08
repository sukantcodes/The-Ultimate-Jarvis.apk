package com.jarvis.assistant.onboarding

/**
 * The tutorial stages in display order. [id] is persisted, so an existing id must never be renamed.
 * Nothing in the tutorial blocks the app: every stage except the intro and the final check can be skipped.
 */
enum class OnboardingStep(val id: String, val title: String, val skippable: Boolean) {
    INTRO("intro", "Welcome", false),
    PERMISSIONS("permissions", "Permissions", true),
    PROFILE("profile", "Profile", true),
    AI("ai", "AI connection", true),
    ACCESSIBILITY("accessibility", "Accessibility", true),
    APP_CONTROL("app_control", "App control", true),
    VOICE("voice", "Voice", true),
    FINAL("final", "System check", false);

    companion object {
        fun fromId(id: String?): OnboardingStep? = values().firstOrNull { it.id == id }
        fun next(step: OnboardingStep): OnboardingStep? = values().getOrNull(step.ordinal + 1)
        fun previous(step: OnboardingStep): OnboardingStep? = values().getOrNull(step.ordinal - 1)
    }
}

/** What the user did with a stage. This is a record of the walk-through, never a claim that something works. */
enum class StepState { PENDING, COMPLETED, SKIPPED }

/**
 * Immutable tutorial progress. It is persisted as a short string so that the tutorial can resume after the
 * process is killed, the screen is rotated or the user leaves for Android Settings and comes back.
 */
data class OnboardingProgress(
    val states: Map<OnboardingStep, StepState> = emptyMap(),
    val current: OnboardingStep = OnboardingStep.INTRO,
    val finished: Boolean = false
) {
    fun stateOf(step: OnboardingStep): StepState = states[step] ?: StepState.PENDING

    fun complete(step: OnboardingStep): OnboardingProgress = settle(step, StepState.COMPLETED)

    fun skip(step: OnboardingStep): OnboardingProgress = settle(step, StepState.SKIPPED)

    /** Records [state] for [step] and moves on to the next stage (the final stage stays where it is). */
    private fun settle(step: OnboardingStep, state: StepState): OnboardingProgress {
        val updated = copy(states = states + (step to state))
        return updated.copy(current = OnboardingStep.next(step) ?: step)
    }

    fun goTo(step: OnboardingStep): OnboardingProgress = copy(current = step)

    fun back(): OnboardingProgress = copy(current = OnboardingStep.previous(current) ?: current)

    /**
     * Ends the tutorial. Stages the user never reached are recorded as skipped, so the final system check
     * can tell "skipped" apart from "never offered".
     */
    fun finish(): OnboardingProgress {
        val settled = LinkedHashMap<OnboardingStep, StepState>()
        for (step in OnboardingStep.values()) {
            settled[step] = when {
                step == OnboardingStep.FINAL -> StepState.COMPLETED
                stateOf(step) == StepState.PENDING -> StepState.SKIPPED
                else -> stateOf(step)
            }
        }
        return OnboardingProgress(settled, OnboardingStep.FINAL, true)
    }

    fun encode(): String {
        val encodedStates = states.entries
            .sortedBy { it.key.ordinal }
            .joinToString(",") { "${it.key.id}:${it.value.name}" }
        return "v1;c=${current.id};f=${if (finished) 1 else 0};s=$encodedStates"
    }

    companion object {
        /** Tolerant on purpose: anything unreadable yields a fresh start instead of a crash. */
        fun decode(raw: String?): OnboardingProgress {
            if (raw.isNullOrBlank()) return OnboardingProgress()
            var current = OnboardingStep.INTRO
            var finished = false
            val states = LinkedHashMap<OnboardingStep, StepState>()
            for (part in raw.split(';')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq)
                val value = part.substring(eq + 1)
                when (key) {
                    "c" -> OnboardingStep.fromId(value)?.let { current = it }
                    "f" -> finished = value == "1"
                    "s" -> for (pair in value.split(',')) {
                        val colon = pair.indexOf(':')
                        if (colon <= 0) continue
                        val step = OnboardingStep.fromId(pair.substring(0, colon)) ?: continue
                        val state = StepState.values().firstOrNull { it.name == pair.substring(colon + 1) } ?: continue
                        states[step] = state
                    }
                }
            }
            return OnboardingProgress(states, current, finished)
        }
    }
}

/** Whether the tutorial should open by itself on app start. */
enum class LaunchState { UNDECIDED, PENDING, DONE }

object OnboardingGate {
    /**
     * First decision, taken once. A person who had already finished the earlier first-run welcome before this
     * tutorial existed is an existing user: they are not interrupted, and can replay the tutorial from Settings.
     */
    fun initialState(legacyWelcomeAlreadySeen: Boolean): LaunchState =
        if (legacyWelcomeAlreadySeen) LaunchState.DONE else LaunchState.PENDING
}
