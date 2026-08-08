# Skills Reference

How skills are organized, loaded, and when to use them.

## How Skills Work

There are three tiers of skills, each loaded differently:

| Tier | Location | How to load | When loaded |
|------|----------|-------------|-------------|
| **Plugins** | Installed via Claude Code plugin system | Automatic — always available as invocable skills | Session start |
| **User skills** | `~/.claude/skills/` | `Skill: <name>` (invocable via Skill tool) | On demand |
| **Project skills** | `.agents/skills/` in repo | `Read` the SKILL.md file directly (Cursor also auto-discovers this path) | On demand, before relevant work |

**Key distinction:** Plugin and user skills are invocable via the `Skill` tool. Project skills are reference files — read them with the `Read` tool before doing related work. Prefer `.agents/skills/...` paths; do not cite the root `skills/` compat symlink in new docs.

## Loading Protocol

### Every session
1. Load `android-cli` skill (invocable, user-level) — required for any device interaction

### Before implementation work
1. Read relevant project skills from `.agents/skills/` (see lookup table below)
2. Plugin skills (superpowers, code-review, etc.) are already available

### Before code review
1. Read `.agents/skills/kotlin-kmp-code-review/SKILL.md` for KMP review checklist
2. Read domain-specific skills for the area under review (Compose, coroutines, etc.)

### Adding new skills
Official Google/Android skills can be installed via:
```bash
android skills list          # see available skills
android skills add --skill=<name> --project=.  # install to project
```

## Plugins (always available)

Configured in `~/.claude/settings.json`. These provide invocable skills and tools.

| Plugin | Source | Key Skills |
|--------|--------|------------|
| `superpowers` | claude-plugins-official | TDD, debugging, brainstorming, git worktrees, code review, plan writing |
| `code-review` | claude-plugins-official | PR review workflows |
| `commit-commands` | claude-plugins-official | Commit, push, PR creation |
| `feature-dev` | claude-plugins-official | Guided feature development |
| `code-simplifier` | claude-plugins-official | Code quality and simplification |
| `claude-code-setup` | claude-plugins-official | Automation recommendations |
| `claude-md-management` | claude-plugins-official | CLAUDE.md audit and improvement |
| `security-guidance` | claude-plugins-official | Security review |
| `context7` | claude-plugins-official | Library documentation lookup |
| `github` | claude-plugins-official | GitHub MCP (issues, PRs, code search) |
| `playwright` | claude-plugins-official | Browser automation and E2E testing |
| `ansible-*` (6 plugins) | claude-ansible-skills | Ansible development (docs, scaffolding, review, zen) |

## User Skills (~/.claude/skills/)

Installed globally, available in all projects. Invoke via `Skill: <name>`.

| Skill | Source | Purpose |
|-------|--------|---------|
| `android-cli` | [android/skills](https://github.com/android/skills) | Deploy apps, manage emulators, screenshots, layout inspection, SDK management |
| `ai-gitignore` | — | Create/maintain .gitignore for AI-generated content |
| `appfunctions` | [android/skills](https://github.com/android/skills) | Identify key user workflows for Android AppFunctions |
| `r8-analyzer` | [android/skills](https://github.com/android/skills) | Analyze build files and R8 keep rules for redundancies |
| `styles` | [android/skills](https://github.com/android/skills) | Integrate Jetpack Compose Styles API |
| `testing-setup` | [android/skills](https://github.com/android/skills) | Create testing strategy for native Android apps |
| `verified-email` | [android/skills](https://github.com/android/skills) | Implement verified email retrieval with Credential Manager |

## Project Skills (`.agents/skills/`)

Bundled with this repo under `.agents/skills/`. Read with `Read` tool before relevant work. See also `AGENTS.md`.

### Quick Lookup by Task

Paths below are relative to `.agents/skills/`.

| Task | Skills to read |
|------|---------------|
| **Compose UI/state/layout** | `compose-expert/`, `compose-state-authoring/`, `compose-state-hoisting/`, `compose-modifier-and-layout-style/` |
| **Compose performance** | `compose-recomposition-performance/`, `compose-stability-diagnostics/`, `compose-state-deferred-reads/` |
| **Compose animations** | `compose-animations/` |
| **Compose side effects** | `compose-side-effects/` |
| **Compose testing** | `compose-ui-testing-patterns/`, `android-community/android-unit-test-editor.md` |
| **Coroutines/Flow** | `kotlin-coroutines-structured-concurrency/`, `kotlin-flow-state-event-modeling/` |
| **Koin DI** | `android-community/koin-editor.md` |
| **KMP architecture** | `kotlin-multiplatform-expect-actual/`, `kotlin-types-value-class/`, `kotlin-kmp-abstraction-decision/` |
| **KMP refactoring** | `kotlin-kmp-refactor-safety/` |
| **KMP code review** | `kotlin-kmp-code-review/` |
| **KMP Gradle/build** | `kotlin-build-kmp-gradle-governance/` |
| **KMP testing** | `kotlin-testing-kmp/` |
| **Navigation** | `kotlin-navigation-compose-multiplatform/`, `android-official/navigation-3.md` |
| **Edge-to-edge** | `android-official/edge-to-edge.md` |
| **Gradle config** | `android-community/gradle-configuration.md` |
| **Architecture review** | `kotlin-project-architecture-review/` |
| **PR contract review** | `pr-architecture-review/` (project-specific, checks `docs/architecture/service-contracts.md`) |
| **Post-PR review → merge** | `post-pr-review-merge/` (architecture + Kotlin review, fix loop, merge when CI green) |
| **Feature implementation** | `kotlin-project-feature-implementation/` |
| **Bug fixing** | `kotlin-project-bugfix/` |

### Sources

Project skills come from these repos:

| Source | Skills | License | Link |
|--------|--------|---------|------|
| **android/skills** (Google) | `android-official/` — adaptive, edge-to-edge, migration, navigation-3, theming | Apache 2.0 | [GitHub](https://github.com/android/skills) |
| **aldefy/compose-skill** | `compose-expert/` — comprehensive Compose/CMP expert with 27 source-code reference files from `androidx/androidx` | Apache 2.0 | [GitHub](https://github.com/aldefy/compose-skill) |
| **chrisbanes/skills** | 12 `compose-*` + 4 `kotlin-*` skills — animations, focus, modifiers, performance, side effects, state, testing, coroutines, flow, expect/actual, value class | Apache 2.0 | [GitHub](https://github.com/chrisbanes/skills) |
| **mmiani/kotlin-kmp-claude-agent-skills** | 15 `kotlin-*` skills — KMP architecture, code review, refactor safety, Gradle governance, testing, navigation, bridges, modularization | Apache 2.0 | [GitHub](https://github.com/mmiani/kotlin-kmp-claude-agent-skills) |
| **javiercamarenatriguero/android-skills** | `android-community/` — Compose editor, Koin, coroutines, Kotlin convention, unit testing, Gradle, performance auditor | Apache 2.0 | [GitHub](https://github.com/javiercamarenatriguero/android-skills) |
| **Meet-Miyani/compose-skill** | `compose-skill/` — comprehensive Compose/KMP with 12 sub-references (MVI, Nav, Ktor, DataStore, Paging, Coil) | MIT | [GitHub](https://github.com/Meet-Miyani/compose-skill) |
| **anhvt52/jetpack-compose-skills** | Compose best practices + M3 migration + accessibility. Used: accessibility reference | MIT | [GitHub](https://github.com/anhvt52/jetpack-compose-skills) |
| **new-silvermoon/awesome-android-agent-skills** | 16 Android agent skills. Used: Retrofit networking, Gradle build performance | Apache 2.0 | [GitHub](https://github.com/new-silvermoon/awesome-android-agent-skills) |

### Skills available but not yet imported

| Source | What's new for us | Link |
|--------|------------------|------|
| **Kotlin/kotlin-agent-skills** (official) | AGP 9 migration, immutable collections 0.5.x migration, Java→Kotlin | [GitHub](https://github.com/Kotlin/kotlin-agent-skills) |
| **skydoves/compose-performance-skills** | CI stability enforcement with ComposeGuard for CMP | [GitHub](https://github.com/skydoves/compose-performance-skills) |
| **vitorpamplona/amethyst** | `desktop-expert` skill from a real KMP Desktop app (rare) | [GitHub](https://github.com/vitorpamplona/amethyst/tree/main/.claude/skills) |
