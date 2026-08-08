# AI Agent Skills

SKILL.md files that ground AI assistants with Android/Kotlin best practices.
These follow the [agentskills.io](https://agentskills.io) open standard.

**Canonical path:** `.agents/skills/` (this directory). See repo-root `AGENTS.md`.

## Sources

### `android-official/` - Google Android Skills
From [android/skills](https://github.com/android/skills) (Apache 2.0)
- **edge-to-edge** - Edge-to-edge display, insets, IME handling for Compose
- **navigation-3** - Jetpack Navigation 3 migration and patterns (incl. Koin recipes)
- **adaptive** - Adaptive Compose layouts, window size classes, canonical patterns
- **migration** - Jetpack Compose migration patterns
- **theming** - Material 3 theming, dynamic color, design tokens

### `android-community/` - Community Android Skills
From [javiercamarenatriguero/android-skills](https://github.com/javiercamarenatriguero/android-skills) (Apache 2.0)
- **compose-editor** - Jetpack Compose patterns and conventions
- **compose-performance-auditor** - Compose recomposition and performance
- **kotlin-coroutines** - Coroutines, Flow, structured concurrency
- **koin-editor** - Koin DI patterns and best practices
- **kotlin-convention** - Kotlin coding conventions
- **android-unit-test-editor** - Unit testing patterns
- **gradle-configuration** - Gradle build configuration
- **accessibility** - Android accessibility patterns
- **retrofit-networking** - Retrofit HTTP client patterns

### `compose-skill/` - Comprehensive Compose Reference
From [Meet-Miyani/compose-skill](https://github.com/Meet-Miyani/compose-skill) (MIT)
- **compose-skill** - Full Compose lifecycle: architecture, state, UI, networking, persistence, performance, accessibility
- **references/** - Detailed guides for architecture, MVI, Koin, coroutines, testing, anti-patterns, etc.

### `compose-expert/` - Compose Multiplatform Expert
From [aldefy/compose-skill](https://github.com/aldefy/compose-skill) (MIT)
- **compose-expert** - Comprehensive Compose reference (v2.3.1) covering state, performance, animations, navigation (incl. Nav 2→3 migration), paging, theming, multiplatform (CMP), TV Compose, accessibility, production crash playbooks, and PR review workflows
- **references/** - 26 topic-specific guides with actual androidx source code references

### chrisbanes skills - Focused Compose & Kotlin Skills
From [chrisbanes/skills](https://github.com/chrisbanes/skills) (Apache 2.0)

**Compose:**
- **compose-animations** - AnimatedVisibility, animate*AsState, rememberTransition, AnimatedContent, Crossfade
- **compose-focus-navigation** - TV/keyboard/desktop focus, D-pad, FocusRequester, key events
- **compose-modifier-and-layout-style** - Modifier chains, layout APIs, modifier parameters
- **compose-recomposition-performance** - Recomposition counts, skippable/restartable, compiler reports
- **compose-side-effects** - LaunchedEffect, DisposableEffect, SideEffect, rememberCoroutineScope
- **compose-slot-api-pattern** - Slot API design for reusable components
- **compose-stability-diagnostics** - Parameter stability, compiler reports, strong skipping (Kotlin 2.0+)
- **compose-state-authoring** - mutableStateOf, remember, mutableStateListOf patterns
- **compose-state-deferred-reads** - Deferring state reads to layout/draw phase
- **compose-state-hoisting** - Where to put state: local, hoisted, state holder, or ViewModel
- **compose-state-holder-ui-split** - Splitting screen composables into state wrapper + pure UI
- **compose-ui-testing-patterns** - UI tests, screenshot tests, semantics assertions

**Kotlin:**
- **kotlin-coroutines-structured-concurrency** - CoroutineScope, launching patterns, exception handling
- **kotlin-flow-state-event-modeling** - StateFlow vs SharedFlow vs Channel, one-shot events
- **kotlin-multiplatform-expect-actual** - KMP expect/actual, interface boundaries, source sets
- **kotlin-types-value-class** - @JvmInline value class vs data class, Compose stability

### mmiani skills - KMP Architecture & Development
From [mmiani/kotlin-kmp-claude-agent-skills](https://github.com/mmiani/kotlin-kmp-claude-agent-skills) (Apache 2.0)

**Architecture & Implementation:**
- **kotlin-project-architecture-review** - 22-dimension architectural review, layering, KMP concerns
- **kotlin-project-feature-implementation** - Pre-coding inspection, layer-by-layer rules, state pipeline
- **kotlin-project-modularization** - Module boundary design, 4 module types, dependency direction
- **kotlin-project-state-management** - State-holder patterns, platform lifecycle differences, effect handling

**UI & Navigation:**
- **kotlin-ui-compose-multiplatform** - 14-dimension shared Compose UI review, CMP patterns
- **kotlin-navigation-compose-multiplatform** - Navigation patterns, typed routes, back-stack, deep links
- **kotlin-ui-adaptive-resources** - 17-dimension adaptive UI, window-size classes, foldables

**Platform Boundaries:**
- **kotlin-platform-kmp-bridges** - expect/actual vs interfaces, mechanism selection, leakage prevention
- **kotlin-platform-app-links-and-deep-links** - Android deep-linking, App Links, intent-filters

**Data, Testing & Build:**
- **kotlin-data-kmp-data-layer** - Repository patterns, source of truth, immutability, threading
- **kotlin-testing-kmp** - KMP test strategy, commonTest, test doubles, screenshot tests
- **kotlin-build-kmp-gradle-governance** - Gradle structure, convention plugins, version catalogs, KMP plugin

**Bug Fix & Refactoring:**
- **kotlin-project-bugfix** - Root-cause diagnostics per bug type, minimal diffs, regression tests
- **kotlin-kmp-refactor-safety** - 4-phase refactoring, parallel implementation prevention, rollback
- **kotlin-kmp-code-review** - 24-priority architect-level review, escalation criteria

### `pr-architecture-review/` - Project Architecture Contract Review
Project-specific (Apache 2.0)
- **pr-architecture-review** - PR review skill that checks changes against `docs/architecture/service-contracts.md`. Verifies layer discipline, interface contracts, module boundaries, state management, DI patterns. Auto-loads relevant Kotlin/Android skills based on changed files.

### `post-pr-review-merge/` - Post-PR Review Fix Loop
Project-specific (Apache 2.0)
- **post-pr-review-merge** - After PR creation: architecture review + Kotlin code review, fix until clean (simple issues even if unrelated; complex unrelated → match/create follow-up issue), report deferred/info/warnings, merge when those need no action and CI is green.

### `kotlin-kmp-abstraction-decision/` - KMP Abstraction Decision Framework
From [vitorpamplona/amethyst](https://github.com/vitorpamplona/amethyst) (MIT)
- **kotlin-kmp-abstraction-decision** - Decision tree for platform abstraction: what goes in commonMain vs expect/actual vs platform-specific. Abstraction tiers (always/sometimes/rarely/never), mechanism selection order, common pitfalls
