---
name: pr-architecture-review
description: Use when reviewing PRs for architecture contract compliance in Ansible Jane. Checks layer discipline, interface contracts, module boundaries, state management, DI patterns, and naming conventions against docs/architecture/service-contracts.md. Auto-loads relevant Kotlin/Android skills based on changed files.
license: Apache-2.0
metadata:
  version: "1.0.0"
---

# PR Architecture Review

Review pull requests against this project's architecture service contracts.

This skill is **project-specific** — it checks changes against the enforceable rules
in `docs/architecture/service-contracts.md`. It complements (does not replace) the
generic `kotlin-project-architecture-review` and `kotlin-project-code-review` skills
by grounding their dimensions in this project's actual contracts.

---

## When to use

- Reviewing any PR to this repository
- Reviewing a branch diff before creating a PR
- Auditing code changes for architecture compliance
- When asked to "check architecture" or "review for contracts"

---

## Review procedure

Follow these steps in order. Do not skip steps.

### Step 1: Load service contracts

Read `docs/architecture/service-contracts.md` in full. This is the source of truth
for all contract checks. If the file has been modified in the PR itself, review
those modifications for correctness first.

### Step 2: Identify changed files

Get the full diff of the PR or branch. Categorize every changed file by layer
and module:

| Layer | Package patterns |
|-------|-----------------|
| UI | `ui/`, `screen/`, `component/`, Composable functions |
| Presentation | `presentation/`, `*ViewModel.kt` |
| Engine | `assistant/engine/`, `ChatEngine`, `ToolRouter`, `ToolExecutor` |
| Repository | `data/`, `*Repository.kt`, `*Manager.kt` |
| Network | `network/`, `*ApiClient.kt`, `*ApiService.kt`, `mcp/` |
| Platform | `platform/`, `androidMain/`, `jvmMain/`, `desktopMain/` |
| Model | `model/`, data classes, sealed classes |
| DI | `di/`, `*Module.kt` |
| Tools | `tools/`, `*LocalTool.kt`, `*McpTool.kt` |
| Test | `*Test.kt`, `test/` |

### Step 3: Load core skills

Always read these skills before reviewing, regardless of which files changed:

- `.agents/skills/kotlin-coroutines-structured-concurrency/SKILL.md`
- `.agents/skills/kotlin-flow-state-event-modeling/SKILL.md`
- `.agents/skills/kotlin-multiplatform-expect-actual/SKILL.md`
- `.agents/skills/android-community/koin-editor.md`

### Step 4: Auto-detect and load extra skills

Based on which files changed, load additional skills:

| Changed files match | Load skill |
|--------------------|------------|
| `ui/`, `presentation/`, `*Screen.kt` | `.agents/skills/compose-skill/SKILL.md` |
| `ui/`, `*Screen.kt` with state hoisting | `.agents/skills/compose-state-hoisting/SKILL.md` |
| `assistant/engine/`, module boundaries | `.agents/skills/kotlin-project-architecture-review/SKILL.md` |
| `data/`, `*Repository.kt`, `network/` | `.agents/skills/kotlin-data-kmp-data-layer/SKILL.md` |
| `platform/`, `expect`/`actual` | `.agents/skills/kotlin-kmp-abstraction-decision/SKILL.md` |
| `*Test.kt`, test infrastructure | `.agents/skills/kotlin-testing-kmp/SKILL.md`, `.agents/skills/compose-ui-testing-patterns/SKILL.md` |
| `*ViewModel.kt`, state management | `.agents/skills/kotlin-flow-state-event-modeling/SKILL.md` |
| Navigation, routing | `.agents/skills/kotlin-navigation-compose-multiplatform/SKILL.md` |
| Compose performance concerns | `.agents/skills/compose-recomposition-performance/SKILL.md` |

Log which skills were loaded and why.

### Step 5: Run contract checks

For each changed file, check against the hard rules in the service contracts.
These are **must-fix** findings — the PR should not merge with violations.

#### 5a. Layer discipline

For every import statement in changed files, verify:
- UI files do not import from `data/`, `network/`, or `platform/`
- Presentation files do not import from `network/`
- Repository files do not import from `presentation/` or `ui/`
- Network files do not import from `presentation/` or `ui/`
- No layer skipping (UI calling Repository, ViewModel calling Network)

**Check method:**
```
grep -n "^import" <changed-file> | check against layer rules
```

#### 5b. Interface contracts

For any new or modified repository:
- Verify a corresponding `IXxxRepository` interface exists
- Verify Koin binding uses `bind IXxxRepository::class`
- Verify the interface is in the same package as the implementation

For any new tool:
- Verify it implements the `LocalTool` interface
- Verify it is registered in `AssistantDiModule` with `bind LocalTool::class`

#### 5c. Module boundaries

- Verify `shared/` files do not import from `app/` or `composeApp/`
- Verify `composeApp/` files do not import from `app/`
- Verify `commonMain` files have no `android.*`, `java.*`, or `javax.*` imports

#### 5d. State management

For new or modified ViewModels:
- Verify `MutableStateFlow` is private
- Verify public state is exposed as `StateFlow<XxxUiState>`
- Verify UiState follows the sealed class pattern with `Idle`/`Loading`/`Success`/`Error`
- Verify one-time events use `Channel` or `SharedFlow`, not UiState variants

#### 5e. DI registration

For any new class that should be injected:
- Verify it is registered in the appropriate Koin module
- Verify repository bindings use interface types
- Verify ViewModel registration uses `viewModelOf()` or explicit `viewModel {}`

#### 5f. Security

- No hardcoded URLs, tokens, or API keys in changed files
- No use of `EncryptedSharedPreferences` (deprecated)
- Sensitive data does not leak into UI or logging

### Step 6: Run soft checks

These are **consider** findings — advisory, not blocking.

#### 6a. File size

Flag files exceeding 400 LOC unless they are in the documented exceptions list
(ChatEngine, ToolRouter, AapApiClient, TokenManager, McpServerManager).

#### 6b. Naming consistency

- Interfaces should follow `IXxxRepository`/`IXxxManager` pattern
- ViewModels should be named `XxxViewModel`
- UiState should be named `XxxUiState`
- Local tools should be named `XxxLocalTool`

#### 6c. Extraction opportunities

Flag when:
- A class constructor takes more than 6 dependencies
- A file has more than 3 groups of private helpers that don't interact
- A ViewModel contains HTTP client instantiation (except documented SettingsViewModel exception)

#### 6d. Pattern consistency

Flag when a new screen or feature diverges from established patterns without justification:
- Missing `Idle`/`Loading`/`Success`/`Error` in UiState
- Manual DI instead of Koin injection
- Direct API calls instead of repository pattern

---

## Output format

Structure findings as follows:

```markdown
## Architecture Review: PR #NNN — <title>

### Contract Violations (must-fix)

- **[LAYER]** `FooViewModel.kt:42` — imports `network.AapApiClient` directly.
  Must access data through a repository.
- **[INTERFACE]** `BarRepository.kt` — no corresponding `IBarRepository` interface.
  Add interface and update Koin binding.
- **[MODULE]** `shared/.../Baz.kt:15` — imports `android.content.Context`.
  Move to `androidMain` or use `expect`/`actual`.

### Recommendations (consider)

- **[SIZE]** `QuxViewModel.kt` is 520 LOC — consider extracting validation logic
  into a dedicated `QuxValidator` class.
- **[PATTERN]** `NewScreen` UiState uses `Boolean isLoading` instead of sealed class.
  Consider migrating to `Idle`/`Loading`/`Success`/`Error` pattern for consistency.

### Skills Loaded

**Core (always):**
- kotlin-coroutines-structured-concurrency
- kotlin-flow-state-event-modeling
- kotlin-multiplatform-expect-actual
- koin-editor

**Auto-detected:**
- compose-skill (ui/ files changed)
- kotlin-data-kmp-data-layer (data/ files changed)

### Verdict

One of:
- **Clean** — no contract violations found
- **Fixable** — N contract violations, M recommendations
- **Needs architecture review** — structural changes detected, recommend also
  running `kotlin-project-architecture-review` skill
```

---

## Severity definitions

### Must-fix (hard rule violation)

The PR introduces a contract violation that will cause architectural drift.
These map to the **hard rules** in `docs/architecture/service-contracts.md`.

Examples:
- ViewModel imports from `network/` package
- New repository without `IXxxRepository` interface
- Koin binding to concrete type instead of interface
- `commonMain` file with Android imports
- `MutableStateFlow` exposed publicly from ViewModel
- New tool not registered in `AssistantDiModule`
- Hardcoded credentials or tokens

### Consider (soft recommendation)

The code works but diverges from established patterns or guidelines.
Not blocking, but worth addressing to prevent drift.

Examples:
- File exceeding 400 LOC (outside documented exceptions)
- Naming inconsistency (e.g., `FooRepo` instead of `FooRepository`)
- Missing test coverage for new repository or ViewModel
- UiState not using sealed class pattern
- Constructor with many dependencies suggesting extraction opportunity

---

## What this skill does NOT cover

- **Line-level code quality** — use `kotlin-kmp-code-review` skill
- **Compose recomposition performance** — use `compose-recomposition-performance` skill
- **Ansible content quality** — use CLAUDE.md Ansible rules
- **General PR correctness** — use the `code-review` superpowers skill
- **Security audit** — use `security-review` skill for deep security analysis

This skill focuses exclusively on **architecture contract compliance** for this
specific project.

---

## Maintenance

When the architecture evolves (new modules, new patterns, new exceptions):
1. Update `docs/architecture/service-contracts.md` with the change
2. Update the relevant check in this skill if needed
3. Bump the version in both files
4. The PR introducing the change should document why the contract changed
