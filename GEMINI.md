# Jarvis Development & Engineering Guidelines

These rules are permanent and mandatory for all implementations in this repository:

## 1. Strict Theme Synchronization
- **Zero Static Hex Colors**: Never use static hardcoded hex colors (e.g. `#00E5FF`, `#232C3D`, or `@color/...`) in layouts or Kotlin views.
- **Dynamic Theme Attributes**: All colors, strokes, cards, badges, and tinting must dynamically sync with the user-selected theme using `ThemeManager`:
  - `ThemeManager.getThemeAccentColor()`
  - `ThemeManager.getThemeSecondaryColor()`
  - `ThemeManager.getThemeSuccessColor()`
  - `ThemeManager.getThemeWarningColor()`
  - `ThemeManager.createDynamicBadgeDrawable()`
  - `ThemeManager.createDynamicCardDrawable()`
  - `ThemeManager.tintSwitch()`
  - XML theme attributes: `?attr/themeAccent`, `?attr/themeSecondary`, `?attr/themeBg`, `?attr/themeCardBg`, `?attr/themeCardBorder`, `?attr/themeTextPrimary`, `?attr/themeTextSecondary`.

## 2. Strongest Production-Grade Implementation Standards
- **Zero Pseudocode & Zero Placeholders**: Every feature must be fully typed, null-safe, fully compiled Kotlin.
- **Robust Error Handling**: Handle short audio buffers, missing permissions, API key rotation, network fallbacks, and lifecycle state changes gracefully without crashing or false-negative failures.
- **Attention to Detail**: Implement small nuances (animations, padding, touch targets >= 44dp, micro-interactions, responsive scaling on displays from 320dp to 600dp+).

## 3. High-Performance Agent Swarm Execution
- Use the most powerful available models for technical work.
- When splitting tasks across swarm workers, ensure isolated scopes, clean interfaces, and full verification.
- Always verify all unit tests and builds pass cleanly with 100% green CI before completion.
