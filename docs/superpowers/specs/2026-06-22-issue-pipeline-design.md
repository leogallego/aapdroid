# Issue Pipeline — Autonomous Issue Execution Skill

**Date**: 2026-06-22
**Status**: Design approved, pending implementation plan

## Overview

A reusable Claude Code skill (`/issue-pipeline`) that takes a list of GitHub issue numbers and autonomously processes each one end-to-end: assess → plan → plan review → implement → implementation review → fix → PR. The only human checkpoint is merge approval.

**Invocation**: `/issue-pipeline #372 #369 #365 #345 #306 #293 #312 #311 #310 #340 #214`

**Input**: One or more GitHub issue numbers.

**Output per issue**: An open PR ready for merge (or a skipped/failed issue with an explanatory comment).

**Output at completion**: A pipeline run summary listing all PRs created, their merge order, skipped issues, and failures.

---

## Design Decisions

These were explicitly chosen during the design phase:

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Execution model | Cross-harness skill (SKILL.md) | Portable across Claude Code, Codex, Gemini CLI, Copilot. Uses task lists for state tracking instead of a Claude Code-specific Workflow script |
| Human checkpoints | Merge only | Maximum autonomy, human approves the final diff |
| Failure handling | Retry 2×, then detach and continue | Stop the current chain, skip dependent issues, start independent chains from `main` |
| Review depth | Full multi-angle, two stages | Plan review (architecture) + implementation review (architecture + code + security + skill-based) |
| Non-clean issues | Skip (pipeline PRs only) | Issues with `pipeline/`-prefixed PRs or external blockers are skipped; non-pipeline PRs are ignored |
| Isolation | Worktree per issue | Prevents cross-issue contamination |
| PR model | Stacked PRs | Within a chain, each issue branches from the previous. Chains are independent |
| Dependency map | Dynamic at runtime | Parsed from issue bodies, not hardcoded |
| Reusability | Parameterized | Works with any list of issues, not tied to a specific milestone |

---

## Foundation Context (Phase 0, Step 0)

Loaded once at pipeline start, carried through all phases and passed to every subagent.

### Always-load (~15,000 tokens)

| # | Source | Tokens (est.) | Purpose |
|---|--------|---------------|---------|
| 1 | `docs/architecture/service-contracts.md` | ~4,400 | Hard rules for all code decisions and reviews |
| 2 | `CLAUDE.md` (project root) | ~3,300 | Tech stack, module placement, security rules |
| 3 | `~/.claude/CLAUDE.md` (non-Ansible sections) | ~1,800 | Git workflow, attribution, temp files, PR rules |
| 4 | `gradle/libs.versions.toml` | ~2,500 | Available dependencies and versions |
| 5 | `build.gradle.kts` (root) | ~130 | Plugin versions |
| 6 | `settings.gradle.kts` | ~110 | Module graph |
| 7 | `gradle.properties` | ~100 | Version name/code, JVM args, Gradle flags |
| 8 | `.github/workflows/ci.yml` | ~625 | What CI checks, so plans and tests align |
| 9 | `scripts/test-all.sh` | ~750 | How to run the full test suite |
| 10 | `MEMORY.md` index | ~1,500 | Index of project decisions and known issues |
| 11 | Git state snapshot | ~200 | Current branch, recent commits, all open PRs |
| 12 | `skills/` live scan (first 10 lines of each `SKILL.md`) | ~500 | Dynamic skill index — extract name, description, and when-to-use from each skill's header |
| 13 | Module/source-set map (precomputed) | ~30 | Where code and tests go per module |
| 14 | Test infrastructure map (precomputed) | ~50 | Where fakes, fixtures, and base test classes live |
| 15 | Lint/format commands (discovered) | ~50 | Project linter commands, discovered from CI config, Makefile, package.json, or common tool configs |

**Module/source-set map** (precomputed at pipeline start by scanning the directory tree):
```
app: main, test, screenshotTest
shared: commonMain, androidMain, jvmMain, commonTest, jvmTest
composeApp: commonMain, androidMain, desktopMain, commonTest
```

**Test infrastructure map** (precomputed at pipeline start):
```
Fakes: app/src/test/kotlin/.../fakes/, composeApp/src/commonTest/kotlin/.../fakes/
Fixtures: TestData.kt in both app/test and composeApp/commonTest
Coroutine test setup: MainDispatcherRule.kt in app/test
```

**Lint/format commands** (discovered at pipeline start by scanning project config):
- Check CI workflow for lint steps (e.g., `ruff check`, `ktlint`, `detekt`, `eslint`)
- Check for config files: `.ruff.toml`, `.editorconfig`, `detekt.yml`, `.eslintrc.*`
- Check `Makefile`, `package.json` scripts, or `scripts/` for lint commands
- Record discovered commands for use in Phase 4

### On-demand (loaded during specific phases when issue keywords match)

| Source | When | Trigger |
|--------|------|---------|
| `docs/superpowers/specs/<matching>.md` | Assess/Plan | Issue touches a previously designed feature |
| `docs/superpowers/plans/<matching>.md` | Plan | Extending or revising an existing plan |
| Individual `skills/*/SKILL.md` | Implement/Review | Based on files being changed |
| Per-module `build.gradle.kts` | Implement | Adding deps or modifying build config |
| `docs/reference/tool-pipeline-architecture.md` | Assess | Issue touches assistant, engine, or tools |
| Individual memory files | Assess | Keyword match between issue and MEMORY.md entries |
| `AndroidManifest.xml` | Implement | Adding activities, permissions, intents |
| Koin DI modules | Implement | Adding new DI bindings |
| Release CI workflows | Implement | Issue modifies release process |
| `docs/specs/<matching>.md` | Assess | Issue references Koog, local tools, or desktop assistant |

---

## Phase 0: Dynamic Triage

Runs once at pipeline start. Takes the raw list of issue numbers and produces an execution plan.

**Inputs**: List of issue numbers.

### Steps

**Step 0 — Load foundation context.** Read all always-load sources listed above. Scan `skills/*/SKILL.md` frontmatter to build the live skill index.

**Step 1 — Fetch issues.** Pull title, body, labels, state, and linked PRs for every issue via GitHub MCP.

**Step 2 — Parse dependencies.** Scan each issue body for:
- Explicit deps: `depends on #(\d+)`, `blocked by #(\d+)`, `after #(\d+)`, `prerequisite.*#(\d+)`, `must land.*#(\d+)`
- Structural refs: `spun out from #(\d+)`, `part of #(\d+)`, `sub-issue of #(\d+)`
- Supersedes: `replaces #(\d+)`, `supersedes #(\d+)`
- Parent tracking: `tracks #(\d+)`

**Step 3 — Classify dependencies.** For each detected reference:
- **Internal dep**: Both issues are in the input list → creates a DAG edge.
- **External blocker**: Referenced issue is NOT in the input list AND is still OPEN → marks current issue as externally blocked.
- **Resolved**: Referenced issue is CLOSED → no constraint.

**Step 4 — Cleanness check.** For each issue:
- Existing open PR **from a previous pipeline run**? Check via `gh pr list --search "closes #N"` and filter to PRs whose head branch starts with `pipeline/`. Non-pipeline PRs (from manual work) are ignored — they don't block the pipeline.
- Issue already closed?
- Externally blocked?
- Any of these → mark as **skip** with reason.

**Step 5 — Build DAG.** From internal deps, build a directed acyclic graph. Detect cycles — if found, report and stop.

**Step 6 — Topological sort into chains.** Group connected components. Within each component, topological sort determines execution order. Independent issues (no edges) become single-issue chains.

**Step 7 — Order chains.** Heuristic for chain execution order:
1. CI/build chains first (lowest risk, no app code changes)
2. Test infrastructure chains next
3. Feature/UI chains last
4. Standalone issues fill gaps between chains

**Step 8 — Risk evaluation per chain.** Analyze each chain and produce a risk assessment:
- **Chain length**: More links = more cascade risk.
- **File overlap**: Do issues in the chain touch the same files? Higher overlap = higher risk if early issues are wrong.
- **Aggregate scope**: A chain of 3 trivial issues is safer than a chain of 3 large ones.
- **Coupling**: Are issues loosely coupled (just ordered by dependency) or tightly coupled (each builds directly on previous code)?

Risk levels:
- **LOW**: Short chain (1-2 issues), distinct files, trivial/small scope.
- **MEDIUM**: 3+ issues, some file overlap, or medium scope issues.
- **HIGH**: 4+ issues, significant file overlap, or large scope issues.

The risk assessment is included in the execution plan output and carried through to the completion report, so the human knows which chains to scrutinize most during merge review.

### Output

```
ExecutionPlan:
  foundation_context: <loaded context object>
  chains: [
    Chain(id=1, issues=[#365, #345], base="main", risk=LOW),
    Chain(id=2, issues=[#372, #369], base="main", risk=LOW),
    Chain(id=3, issues=[#310, #311, #312], base="main", risk=MEDIUM),
  ]
  standalone: [#306, #293, #340, #214]
  skipped: [
    Skip(issue=#372, reason="Existing open PR #394"),
  ]
```

Each chain starts from `main`. Within a chain, each issue branches from the previous issue's branch (stacked PRs). Standalone issues are single-issue chains.

---

## Phase 1: Assess

Runs at the start of each issue. Verifies the issue is still actionable against the current codebase.

**Inputs**: Issue data from Phase 0, current codebase (either `main` or previous issue's branch), foundation context.

### Steps

**Step 1 — Re-read issue body.** The full issue, not just the Phase 0 summary. Identify acceptance criteria, open questions, and referenced files.

**Step 2 — Codebase verification.** For each file, class, or API mentioned in the issue body:
- Does it still exist?
- Is the described problem still present?
- Are the proposed changes still compatible with current code?

**Step 3 — Scope estimation.** Classify the issue:
- **Trivial**: Single file, <50 lines changed, no new tests needed
- **Small**: 2-5 files, <200 lines, straightforward tests
- **Medium**: 5-15 files, new module or significant refactor
- **Large**: 15+ files, architectural changes, multiple test types

**Step 4 — Skill mapping.** Match changed file paths against the live skill index:
- `*Screen.kt` → `compose-skill/`
- `*ViewModel.kt` → `compose-state-*`
- `*.gradle*` → `gradle-configuration`
- `*Test.kt` in `commonTest` → `kotlin-testing-kmp`
- etc.

**Step 5 — On-demand context loading.** Three sources:

*Keyword matching:*
- Load matching specs/plans from `docs/superpowers/`
- Load matching memory files
- Load `tool-pipeline-architecture.md` if issue touches assistant/engine/tools

*File path extraction:* Parse the issue body for explicit file path references (patterns like `src/...`, `app/...`, `*.kt`, `*.yml`, backtick-quoted paths). Read each referenced file directly — this is more reliable than keyword heuristics and ensures the skill sees exactly what the issue author intended.

*Recently merged context:* If the issue body references merged PRs (`PR #NNN`, `#NNN merged`), read their diffs via `gh pr diff #NNN` to understand what recently landed. This provides the "previous session state" that manual prompts carry but automated pipelines normally miss.

**Step 6 — Risk assessment.**
- Does this touch security-sensitive code?
- Does this change public API surface?
- Does this touch CI/CD configuration?
- Could this break existing tests?

**Step 7 — Scope exclusion.** Identify related issues referenced in the body that are NOT in the input list. Record them explicitly as **out of scope** to prevent implementation drift. Example: if issue #71 mentions "follow-up issue #108 exists but is separate," record #108 as excluded. These exclusions are passed to implementation subagents so they don't accidentally address related-but-separate work.

### Output

Assessment report:
```
Assessment:
  codebase_still_matches: bool
  scope: trivial | small | medium | large
  files_to_modify: [list of file paths]
  skills_needed: [list of skill names]
  on_demand_context_loaded: [list of additional docs loaded]
  risks: [list of identified risks]
  blockers_found: [list of unexpected blockers]
  out_of_scope: [list of related issue numbers explicitly excluded]
```

### Decision point

If `codebase_still_matches = false` and the divergence is significant (the core premise of the issue is invalid), mark as **skip** with reason "Issue outdated — codebase has diverged" and comment on the issue.

---

## Phase 2: Update Issue

Leaves a trail on the GitHub issue showing the pipeline assessed it.

### Steps

**Step 1 — Post assessment comment:**
```markdown
## Pipeline Assessment (automated)

- **Scope**: Small (3 files, ~120 lines)
- **Files**: ToolExecutor.kt, ToolExecutorTest.kt
- **Skills**: kotlin-testing-kmp, compose-ui-testing-patterns
- **Risks**: None identified
- **Status**: Proceeding to implementation
```

**Step 2 — Update issue body.** Only if factual inaccuracies were found (e.g., a file path changed). Append a note, don't rewrite the issue.

**Step 3 — Add label.** Add `pipeline/in-progress` label (create it if it doesn't exist).

### Output

Updated issue with assessment comment and `pipeline/in-progress` label.

---

## Phase 3: Plan

Creates a detailed implementation plan before any code is written.

**Inputs**: Assessment report from Phase 1, issue body, foundation context (including service contracts, already loaded in Phase 0).

### Steps

**Step 1 — Load relevant skills.** Read all `SKILL.md` files identified in Phase 1's skill mapping. These inform the plan's approach.

**Step 2 — Generate plan.** Invoke the writing-plans pattern. The plan covers:
- **File-by-file changes**: What changes in each file, why, and the order of changes.
- **New files**: Any new files to create, with their purpose and location following existing module structure.
- **Test strategy**: Which tests to add/modify, where they go (`commonTest` vs `androidTest` vs `desktopTest`), what they cover.
- **Build/CI changes**: If the issue touches `build.gradle.kts`, CI workflows, or dependencies.
- **Migration/compatibility**: Any data migration, API changes, or backward compatibility concerns.

**Step 3 — Save plan.** Write to `docs/superpowers/plans/YYYY-MM-DD-issue-NNN-<slug>.md`.

**Step 4 — Validate plan against scope.** Cross-check that the plan doesn't introduce scope creep beyond what the issue asks for. If the plan touches files not mentioned in the assessment, either update the assessment (legitimate miss) or trim the plan (overreach).

### Output

Written plan file on disk.

### Decision point

If the plan reveals the issue is significantly more complex than estimated (e.g., assessed as Small but plan requires 15+ files), log a warning but proceed. The review phases will catch overreach.

---

## Phase 3.5: Plan Review

Reviews the implementation plan against service contracts and architecture rules **before any code is written**. Catching architectural mistakes here is far cheaper than catching them after implementation.

**Inputs**: Plan file from Phase 3, foundation context (service contracts already loaded).

### Review

Run `git-review` against the plan (contracts in
`docs/architecture/service-contracts.md`). The reviewer checks:
- Does the plan place code in the right modules and source sets?
- Does it create interfaces where service contracts require them?
- Does it follow the layer architecture (UI → Presentation → Engine → Repository → Network → Platform)?
- Does it put tests in the correct source sets (`commonTest` for KMP, `test` for Android-only)?
- Does it respect module boundaries (`shared/` has no deps on `app/` or `composeApp/`)?
- Does the plan's scope match the issue requirements (no overreach)?

### Finding Format

Same structured finding format as Phase 5, but applied to the plan document rather than code:
```
Finding:
  severity: critical | warning | info
  plan_section: "File-by-file changes → ToolExecutor.kt"
  rule: "Module boundaries (service-contracts §3)"
  description: "Plan places new repository in app/ but it should be in shared/commonMain for KMP access"
  suggestion: "Move to shared/src/commonMain/kotlin/.../repository/"
```

### Decision Point

- **Critical findings > 0** → revise the plan (up to 2 attempts). If the plan can't pass architecture review after 2 revisions, that's a **chain failure** — the issue's requirements may be incompatible with the architecture.
- **Warnings** → note them, carry forward to implementation. Phase 5 will re-check.
- **No findings** → proceed to Phase 4.

### Output

Reviewed (and possibly revised) plan file, ready for implementation.

---

## Phase 4: Implement

Executes the plan in an isolated worktree using subagents.

**Inputs**: Plan file, skills list, base branch, foundation context.

### Steps

**Step 1 — Create worktree.** Branch from the appropriate base:
- First issue in a chain → branch from `main`
- Subsequent issues → branch from previous issue's branch
- Branch naming: `pipeline/issue-NNN-<slug>`

**Step 2 — Load all required skills.** Read every `SKILL.md` identified in Phase 1. Pass as context to implementation subagents.

**Step 3 — Dispatch implementation.** Based on scope:
- **Trivial/Small**: Single subagent executes the entire plan sequentially.
- **Medium/Large**: Decompose the plan into independent units and dispatch parallel subagents where possible (e.g., one agent per file group with no cross-dependencies).

Each subagent receives: the full plan (or its assigned section), relevant skill content, foundation context, and instructions to follow the plan exactly — no improvisation, no scope creep.

**Step 4 — Run tests.** Execute the appropriate test commands:
- `./gradlew --no-daemon testDebugUnitTest` for Android unit tests
- `./gradlew --no-daemon :shared:jvmTest` for shared module
- `./gradlew --no-daemon :composeApp:desktopTest` for desktop tests
- If CI-only changes: validate workflow syntax with `actionlint` if available

**Step 5 — Run linters.** Execute the lint commands discovered in Phase 0:
- Run each discovered linter on changed files
- Auto-fix formatting issues where the linter supports it (e.g., `ruff check --fix`, `ktlint -F`)
- Lint failures that can't be auto-fixed are treated the same as test failures

**Step 6 — Verification gate.**
- All tests and linters pass → proceed to Phase 5.
- Tests or linters fail → **Retry loop** (max 2 attempts):
  1. Analyze failure output.
  2. Determine if it's a test bug, lint issue, or implementation bug.
  3. Fix and re-run.
- Still failing after 2 retries → **chain failure** (stop chain, detach, comment on issue).

### Output

Working implementation on the worktree branch, all tests and linters passing.

---

## Phase 5: Implementation Review (Full Multi-Angle)

Reviews the actual code changes after implementation. Four independent review angles run in parallel, each by a separate subagent. This is distinct from Phase 3.5 (plan review) — here we review the code, not the plan.

**Inputs**: The diff on the worktree branch (all commits since branching), foundation context, loaded skills.

### Review Angles

| # | Angle | What it checks | Tool/Skill |
|---|-------|----------------|------------|
| 1 | Architecture | Layer violations, module boundary crossings, interface contracts, state exposure, DI rules, naming, file size thresholds | `git-review` against `service-contracts.md` |
| 2 | Code quality | Bugs, logic errors, edge cases, code duplication, API misuse, missing error handling at boundaries | `/code-review` plugin |
| 3 | Security | Hardcoded secrets, insecure storage, missing HTTPS enforcement, credential handling, injection vectors | `/security-review` plugin |
| 4 | Skill compliance | Check changed files against loaded skills — Compose patterns, test patterns, state management, KMP rules | Relevant skills loaded per file type |

### Finding Format

Each reviewer produces structured findings:
```
Finding:
  severity: critical | warning | info
  file: path/to/File.kt
  line: 42
  rule: "Interface requirement (service-contracts §2)"
  description: "Repository class bound directly, missing IXxxRepository interface"
  suggestion: "Extract interface, bind via Koin to interface"
```

### Aggregation

Merge all findings. Deduplicate (same file + line + similar description). Sort by severity.

### Decision Point

- **Critical findings > 0** → must fix in Phase 6.
- **Warnings only** → fix if straightforward, otherwise note in PR description as known tech debt.
- **Info only** → include in PR description, no action required.

---

## Phase 6: Fix

Addresses review findings and verifies fixes don't introduce regressions.

**Inputs**: Aggregated findings from Phase 5, current implementation on worktree branch.

### Steps

**Step 1 — Fix critical findings.** Apply each fix:
- Make the change.
- Verify the specific finding is resolved (re-check that code section).
- Don't fix unrelated code nearby — scope discipline.

**Step 2 — Fix warnings.** Apply straightforward fixes. Skip warnings that would require significant rework (these go into the PR description as acknowledged debt).

**Step 3 — Re-run tests.** Full test suite via `scripts/test-all.sh`. Catches regressions from the fixes.

**Step 4 — Re-review fixed code.** Run ONLY the review angles that produced critical findings, ONLY on the changed files. Targeted re-check, not a full re-review.

**Step 5 — Convergence check.**
- No remaining criticals → proceed to Phase 7.
- New criticals introduced by fixes → **retry** (attempt 2 of 2).
- Still criticals after 2nd attempt → **chain failure** — stop chain, comment on issue with the unresolvable findings.

### Constraint

Phase 6 must not change the scope of the implementation. If a review finding reveals that the approach is fundamentally wrong, that's a chain failure — don't redesign mid-fix.

### Output

Clean branch with all tests passing and no critical review findings.

---

## Phase 7: Create PR

Packages the work into a reviewable PR with full documentation.

**Inputs**: Clean branch, assessment report, plan, review findings.

### Steps

**Step 1 — Commit.** Commit message format:
```
<type>: <imperative description> (#NNN)

<optional body explaining why, not what>

Assisted-by: <model name> <noreply@anthropic.com>
```
Type follows conventional commits: `feat`, `fix`, `test`, `chore`, `perf`, `refactor`, `docs`.

**Step 2 — Push branch.** `git push -u origin pipeline/issue-NNN-<slug>`

**Step 3 — Create PR.** Target depends on position in chain:
- First issue in chain → targets `main`
- Subsequent issues → targets previous issue's branch (stacked PR)

PR title: `<type>: <description> (#NNN)`

**Step 4 — PR body.** Structured template:
```markdown
## Summary
- <1-3 bullet points of what changed and why>

Closes #NNN

## Changes
- <file-by-file summary of modifications>

## Test plan
- [ ] <what was tested>
- [ ] <test commands run and results>

## Review findings addressed
- **Plan review (Phase 3.5)**: <findings and how they were addressed in the revised plan>
- **Implementation review (Phase 5)**: <findings and how each was resolved>

## Acknowledged debt
- <warnings not addressed, with rationale>

## Stack position
- **Base**: `main` (or `pipeline/issue-XXX-<slug>`)
- **Next**: `pipeline/issue-YYY-<slug>` (or "end of chain")

Assisted-by: <model name> <noreply@anthropic.com>
```

**Step 5 — Post review summary comment.** A comment on the PR consolidating all review rounds: findings from each review angle, what was fixed, what was deferred, and test results. Satisfies the CLAUDE.md rule: "Before merging any PR, ALWAYS post a review summary comment."

**Step 6 — Update GitHub issue.** Post a comment linking to the PR. Replace `pipeline/in-progress` label with `pipeline/awaiting-merge`.

### Output

Open PR with full documentation, ready for human review and merge.

---

## Phase 8: Merge Gate

The only human checkpoint. The skill does NOT wait — after creating the PR it immediately moves to the next issue.

### Merge Order

PRs within a chain must be merged in order (first to last). The PR body's "Stack position" section makes this explicit. GitHub auto-retargets the next PR when the base PR merges.

### What the Human Checks

- The diff
- The review summary comment
- CI status (must be green)
- Whether the changes match the issue requirements

### If the Human Requests Changes

The pipeline has moved on. The human either:
- Makes the changes themselves.
- Re-invokes the skill with just that issue number to apply fixes.

### If the Human Rejects a PR Mid-Chain

Downstream PRs in the same chain become invalid. The human closes them and can re-invoke the skill with the remaining issues as a fresh batch.

---

## Phase 9: Cleanup

Runs after each issue, regardless of outcome.

### Steps

**Step 1 — Worktree.** Keep worktrees alive for PRs (the branch needs them). Clean up worktrees only for skipped issues that produced no PR.

**Step 2 — Update issue labels.**
- PR created → `pipeline/awaiting-merge`
- Skipped → `pipeline/skipped`
- Chain failure → `pipeline/failed`

**Step 3 — Log.** Append to pipeline run summary (in memory, reported at completion):
```
Issue #NNN: PR #XXX created (chain 1, position 2/3)
Issue #NNN: SKIPPED — existing PR #394 detected
Issue #NNN: FAILED — tests failing after 2 retries, stopped chain 2
```

**Step 4 — Move to next issue.** Check execution plan:
- Next issue in current chain → branch from current issue's branch.
- Current chain complete → start next chain from `main`.
- All chains complete → produce final report.

---

## Pipeline Completion Report

When all issues are processed, the skill outputs:

```markdown
## Pipeline Run Complete

### PRs Created (awaiting merge)
- Chain 1: PR #101 (#365) → PR #102 (#345) — merge in order
- Chain 2: PR #103 (#310) → PR #104 (#311) → PR #105 (#312)
- Standalone: PR #106 (#293), PR #107 (#340), PR #108 (#214)

### Skipped
- #372: Existing open PR #394
- #306: Externally blocked by upstream Dependabot issues

### Failed
- (none)

### Merge Instructions
1. Merge Chain 1 in order: PR #101 first, then PR #102
2. Merge Chain 2 in order: PR #103, then PR #104, then PR #105
3. Standalone PRs can merge in any order
```

---

## Failure Handling

### Per-Issue Failures

| Failure type | When | Action |
|-------------|------|--------|
| Issue not clean | Phase 1 (assess) | Skip, comment on issue, move to next |
| Codebase diverged | Phase 1 (assess) | Skip, comment on issue, move to next |
| Plan fails architecture review | Phase 3.5 (plan review) | Revise plan up to 2×. If still failing → chain failure |
| Tests fail | Phase 4 (implement) | Retry 2×. If still failing → chain failure |
| Critical review findings unfixable | Phase 6 (fix) | Retry 2×. If still failing → chain failure |
| Plan reveals excessive complexity | Phase 3 (plan) | Log warning, proceed (plan review will catch overreach) |

### Chain Failures

When a chain failure occurs:
1. Stop processing the current chain.
2. Comment on the failed issue with: what went wrong, which step failed, error output.
3. Mark remaining issues in the chain as **skipped** (dependent on failed issue).
4. Start the next independent chain from `main`.

### Cascade Risk

In stacked PR chains, a problem in issue N affects all downstream PRs (N+1, N+2, ...). This is accepted by design. Mitigations:
- Tests run at every step — failures surface early.
- Chain failures stop the chain immediately — no cascading bad code.
- Independent chains are unaffected.
- The human can reject any PR and re-invoke the pipeline for remaining issues.

---

## Edge Cases

| Scenario | Handling |
|----------|----------|
| Issue is already closed | Skip, log "already closed" |
| Issue has open PR from outside the pipeline | Proceed — only `pipeline/`-prefixed PRs trigger a skip |
| Two issues in the list modify the same file | DAG detection should catch this if they reference each other. If not, the second issue's implementation will see the first's changes (stacked branch) and adapt |
| Input list contains duplicate issue numbers | Deduplicate in Phase 0 |
| Input list contains issues from different repos | Error — pipeline operates on a single repo |
| CI fails on the PR after creation | Not the pipeline's concern — the human checks CI before merging |
| Cycle detected in dependency DAG | Error and stop — report the cycle, don't process any issues |
| All issues in the list are skipped | Report "no actionable issues" and exit |

---

## Constraints and Limits

- **Session context**: A full pipeline run for 11 issues may exceed a single session's context window. The skill is **re-invocable** with the same issue list — Phase 0 re-runs, and the cleanness check (Step 4) detects issues that already have open PRs with the `pipeline/` branch prefix (from a previous run), skips them, and continues with unprocessed issues. The `pipeline/awaiting-merge` label is the signal that an issue was already handled.
- **Token budget**: Phase 5 (4 parallel review agents) is the most token-intensive step. For trivial/small issues, consider reducing to 2 reviewers (architecture + code quality only).
- **Gradle daemon**: All Gradle commands must use `--no-daemon` when running in sandbox.
- **Worktree limit**: Each active worktree consumes disk. The pipeline creates one per issue and only cleans up skipped ones. For large batches, disk usage may be significant.
- **Merge conflicts**: If the human merges PRs out of order (e.g., a standalone PR before a chain PR that touches the same file), the chain PR may need rebasing. The pipeline doesn't handle this — it's a human responsibility.
