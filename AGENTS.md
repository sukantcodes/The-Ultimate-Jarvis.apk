# Jarvis Permanent Rulebook & Engineering Guidelines

These rules are permanent, mandatory, and inviolable for all tasks and implementations in this repository. Every agent, swarm worker, and assistant working on this project must strictly abide by these directives without exception.

---

## 1. Absolute Theme Synchronization & Dynamic Color Harmony
- **Zero Static Hex Colors**: Never hardcode static hex color codes (e.g., `#00E5FF`, `#232C3D`, `#121212`, `#FFFFFF`, or static `@color/...` references) in XML layouts, drawables, or programmatic Kotlin/Java views.
- **Dynamic Theme Attributes**: All colors, strokes, card backgrounds, badges, text colors, and tinting must dynamically synchronize with the user-selected application theme using `ThemeManager` and Android theme attributes:
  - `ThemeManager.getThemeAccentColor()`
  - `ThemeManager.getThemeSecondaryColor()`
  - `ThemeManager.getThemeSuccessColor()`
  - `ThemeManager.getThemeWarningColor()`
  - `ThemeManager.createDynamicBadgeDrawable()`
  - `ThemeManager.createDynamicCardDrawable()`
  - `ThemeManager.tintSwitch()`
  - XML Theme Attributes:
    - `?attr/themeAccent`
    - `?attr/themeSecondary`
    - `?attr/themeBg`
    - `?attr/themeCardBg`
    - `?attr/themeCardBorder`
    - `?attr/themeTextPrimary`
    - `?attr/themeTextSecondary`
- **Instant Reactive Theme Adaptation**: When a user changes the theme in Settings, all open views, bottom sheets, chat bubbles, HUD overlays, and notification cards must update to the new theme palette seamlessly.

---

## 2. Strongest Production-Grade Implementation Standards
- **Zero Pseudocode & Zero Placeholders**: Every feature must be fully typed, null-safe, fully compiled, and production-ready Kotlin. Never leave placeholder comments (`// TODO`), dummy stubs, or unfinished mock branches.
- **Ultra-Strong Core Logic**: Every algorithm, state machine, and engine must be built using enterprise-grade architectural patterns (Clean Architecture, MVVM, MVI, StateFlow/SharedFlow, Coroutines).
- **Bulletproof Resilience & Error Handling**:
  - Gracefully handle audio buffer underflows, phone microphone calibration variances, missing/revoked Android permissions, API key rotation, token limits, network dropouts, and process lifecycle teardown/recreation.
  - Zero crashes, zero unhandled exceptions, and zero silent failures.
  - Fail-open and graceful degradation for critical user paths (e.g., voice wake-word matching energy fallbacks).

---

## 3. Relentless Attention to Small-to-Small Nuances (100% Perfection)
- **Micro-Interactions & Visual Polish**: Every interaction must feel fluid, premium, and cybernetic:
  - Smooth scale/alpha/spring animations on card state changes, button presses, and phase completions.
  - Pulsing status indicators and animated progress bars for active operations.
- **Strict Touch Target & Accessibility Standards**:
  - Minimum touch target dimension of **44dp × 44dp** (Android/Material guidelines: 48dp preferred) on all interactive buttons, icons, switches, and chips.
  - Ample inner padding and touch hit-rect expansion (`TouchDelegate` if necessary).
- **Responsive Display Scaling**:
  - Perfect rendering across all screen sizes and densities: from ultra-compact displays (320dp width) to standard phones (360–410dp) and large displays/foldables (600dp+).
  - No congested rows, overlapping text, or clipped cards. Multi-line wrapping and responsive layout managers (`FlexboxLayout`, constraint chaining, or scrollable containers) must be used where appropriate.

---

## 4. High-Performance Execution & Most Powerful Models
- **Top-Tier Model Utilization**: Always run the most powerful available models for all coding, architectural reasoning, and technical implementations.
- **Autonomous & Self-Aware Execution**: Jarvis operates with 101% self-awareness over its codebase and app blueprint, capable of modifying its own runtime settings and configuration autonomously.
- **Isolated Scopes & Clean Interfaces**: When delegating tasks across swarm workers or subagents, enforce strictly separated scopes, unambiguous interfaces, and comprehensive verification.

---

## 5. Absolute 100% Error-Free Quality Assurance & CI Verification
- **Zero Regression Tolerance**: Every change must be verified against existing unit tests, lint checks, and compiler standards.
- **100% Green CI Guarantee**: All feature branches and commits must be pushed and validated with 100% passing checks in GitHub Actions CI (`assembleDebug` and `testDebugUnitTest`) before considering the task complete.
- **Clean Repository State**: Maintain a spotless Git tree, clear commit messages, and pristine directory structure.
