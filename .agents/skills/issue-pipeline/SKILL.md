---
name: issue-pipeline
description: Autonomous end-to-end issue processing — triage, plan, review, implement, review, fix, PR
---

# Issue Pipeline

Autonomous issue execution skill. Takes GitHub issue numbers, processes each end-to-end: assess, plan, plan review, implement, implementation review, fix, PR. The only human checkpoint is merge approval.

## When to Use

- User provides one or more GitHub issue numbers and wants autonomous implementation
- User says "process these issues", "implement these", "work through this backlog"
- User invokes `/issue-pipeline #NNN #NNN ...`

## Invocation

```
/issue-pipeline #372 #369 #365
/issue-pipeline 372 369 365
```

Input: one or more GitHub issue numbers (with or without `#` prefix).

Output per issue: an open PR ready for merge (or a skipped/failed issue with an explanatory comment).

Output at completion: a pipeline run summary listing all PRs, merge order, skips, and failures.

## The Iron Law

**No implementation without triage, plan, and plan review first.**

Every issue passes through all phases in order. No skipping phases. No "this one is simple enough to just code." The pipeline exists because humans underestimate complexity — the phases catch what intuition misses.

## Pipeline Phases

0. **Dynamic Triage** — load foundation context, fetch issues, parse dependencies, build DAG, sort into chains
1. **Assess** — verify issue against current codebase, estimate scope, map skills, load on-demand context
2. **Update Issue** — post assessment comment on GitHub issue, add `pipeline/in-progress` label
3. **Plan** — generate implementation plan, save to disk
4. **Plan Review** — review plan against architecture rules, revise if needed
5. **Implement** — create worktree, dispatch subagents, run tests and linters
6. **Implementation Review** — 4-angle parallel review (architecture, code quality, security, skill compliance)
7. **Fix** — address review findings, re-test, re-review
8. **Create PR** — commit, push, create PR with full documentation
9. **Cleanup** — update labels, log results, move to next issue
10. **Merge Gate** — human reviews and approves PRs
11. **Sequential Merge** — orchestrate sequential auto-merge with branch updates, clean up worktrees

Phase 0 runs once. Phases 1-9 run per issue. Phases 10-11 run once after all PRs are created.

---

## Phase 0: Dynamic Triage

Runs once at pipeline start. Produces an execution plan that governs the rest of the run.

### Step 0 — Load Foundation Context

Discover and load project context dynamically. Do NOT hardcode any file paths — each step checks if the source exists before reading.

**Always-load sources** (read each if it exists):

1. **Project instructions** — read `CLAUDE.md` in the project root
2. **Global instructions** — read `~/.claude/CLAUDE.md` (non-project-specific sections: git workflow, attribution, temp files, PR rules)
3. **Architecture docs** — `find . -path "*/architecture/*" -name "*.md" -not -path "*/node_modules/*"` — read each file found
4. **CI configuration** — check in order: `.github/workflows/*.yml`, `.gitlab-ci.yml`, `Jenkinsfile`, `.circleci/config.yml`. Read whichever exists.
5. **Dependency manifests** — check for: `gradle/libs.versions.toml`, `build.gradle.kts` (root), `settings.gradle.kts`, `package.json`, `requirements.txt`, `Cargo.toml`, `go.mod`, `pyproject.toml`. Read whichever exist.
6. **Build properties** — check for: `gradle.properties`, `.env.example`, `Makefile`. Read whichever exist.
7. **Test scripts** — check for: `scripts/test*`, test-related entries in `Makefile` or `package.json` scripts. Record discovered test commands.
8. **Lint configuration** — check for: `.ruff.toml`, `.eslintrc*`, `detekt.yml`, `.editorconfig`, `ktlint` in CI, `biome.json`. Record discovered lint commands.
9. **Project skills** — `find . -path "*/skills/*/SKILL.md" -not -path "*/node_modules/*"` — read first 10 lines of each to build a skill index (name, description, when-to-use).
10. **Memory index** — if `MEMORY.md` exists (in project root or `~/.claude/projects/*/memory/`), read the index.
11. **Git state** — capture:
    - `git branch --show-current`
    - `git log --oneline -10`
    - `gh pr list --state open` (or equivalent GitHub MCP call)
12. **Module structure** — `find . -name "src" -type d -not -path "*/node_modules/*" -not -path "*/.gradle/*" | head -20` — map modules and source sets.
13. **Test infrastructure** — scan for test base classes, fakes directories, fixture files, test utilities. Record locations.

Store all loaded context as the **foundation context object**. This is passed to every subagent in subsequent phases.

### Step 1 — Fetch Issues

For each issue number in the input list:

1. Fetch via `gh issue view <N> --json title,body,labels,state,number` or GitHub MCP `issue_read`.
2. Store: number, title, body, labels, state, linked PRs.
3. Deduplicate — if the same issue number appears twice, keep one copy.
4. Validate — all issues must be from the current repository. If any issue returns an error, report it and remove from the list.

### Step 2 — Parse Dependencies

Scan each issue body for dependency references using these patterns:

**Hard dependencies** (creates a DAG edge):
- `depends on #(\d+)`
- `blocked by #(\d+)`
- `after #(\d+)`
- `prerequisite.*#(\d+)`
- `must land.*#(\d+)`

**Structural references** (creates a DAG edge):
- `spun out from #(\d+)`
- `part of #(\d+)`
- `sub-issue of #(\d+)`

**Supersedes** (informational, no edge):
- `replaces #(\d+)`
- `supersedes #(\d+)`

**Parent tracking** (informational, no edge):
- `tracks #(\d+)`

All pattern matching is case-insensitive.

### Step 3 — Classify Dependencies

For each detected reference:

| Classification | Condition | Effect |
|----------------|-----------|--------|
| **Internal dep** | Both issues are in the input list | Creates a DAG edge (referenced issue must complete first) |
| **External blocker** | Referenced issue is NOT in the input list AND is still OPEN | Marks current issue as externally blocked |
| **Resolved** | Referenced issue is CLOSED | No constraint |

To check if a referenced issue is open/closed: `gh issue view <N> --json state` or GitHub MCP.

### Step 4 — Cleanness Check

For each issue, check:

1. **Existing pipeline PR** — `gh pr list --search "closes #N"` (or equivalent). Filter results to PRs whose head branch contains `-pipeline-issue-`. Only pipeline-created PRs trigger a skip. Non-pipeline PRs (from manual work) are ignored.
2. **Already closed** — if issue state is `closed`, skip.
3. **Externally blocked** — if classified as externally blocked in Step 3, skip.
4. **Already labeled** — if issue has `pipeline/awaiting-merge` label, skip (handled in a previous run).

For each skip, record the reason.

### Step 5 — Build DAG

From internal dependencies (Step 3), build a directed acyclic graph:
- Nodes = actionable issues (not skipped)
- Edges = "issue A must complete before issue B"

**Cycle detection**: run a topological sort. If a cycle is detected, report the cycle (list the issue numbers involved) and **stop the pipeline**. Do not process any issues.

### Step 6 — Sort into Chains

1. Find connected components in the DAG. Each component becomes a **chain**.
2. Within each chain, topological sort determines execution order.
3. Issues with no edges (no dependencies, nothing depends on them) become **standalone** issues (single-issue chains).

### Step 7 — Order Chains

Heuristic for which chains to execute first:

1. **CI/build chains** — issues touching CI config, build scripts, dependency updates. Lowest risk, no app code changes.
2. **Test infrastructure chains** — issues adding test utilities, fixtures, or base classes.
3. **Feature/UI chains** — issues adding or modifying functionality.
4. **Standalone issues** — fill gaps between chains, ordered by issue number.

Within a priority tier, shorter chains go first.

### Step 8 — Risk Evaluation

Evaluate each chain and assign a risk level:

| Risk | Criteria |
|------|----------|
| **LOW** | 1-2 issues, distinct files, trivial/small scope |
| **MEDIUM** | 3+ issues, some file overlap, or medium scope |
| **HIGH** | 4+ issues, significant file overlap, large scope, or tightly coupled changes |

Factors:
- **Chain length**: more links = more cascade risk
- **File overlap**: do issues in the chain touch the same files?
- **Aggregate scope**: a chain of 3 trivial issues is safer than a chain of 3 large ones
- **Coupling**: loosely coupled (just ordered by dependency) vs tightly coupled (each builds on previous code)

### Phase 0 Output

Produce and display the execution plan:

```
Execution Plan:
  Foundation context: loaded (N sources, ~M tokens)
  Chains:
    Chain 1: [#365, #345] — base: main, risk: LOW
    Chain 2: [#310, #311, #312] — base: main, risk: MEDIUM
  Standalone: [#306, #293, #340, #214]
  Skipped:
    #372: existing pipeline PR #394
    #102: externally blocked by #99 (open)
```

Each chain starts from `main`. Within a chain, each issue branches from the previous issue's branch (stacked PRs). Standalone issues branch directly from `main`.

### Triage Subagent

For projects with many issues, dispatch Phase 0 Steps 1-8 to a triage subagent using the prompt template at `triage-prompt.md`. Pass it:
- The list of issue numbers
- The foundation context from Step 0

The main agent loads foundation context (Step 0) directly, then delegates the rest to the triage subagent.

---

## Per-Issue Execution Loop

After Phase 0 produces the execution plan, process issues in execution order:

1. **Chains first**, in the order determined by Phase 0 Step 7.
2. Within each chain, issues execute in topological order (dependencies before dependents).
3. **Standalone issues** after all chains complete.

For each issue:

1. Create a task for tracking: `TaskCreate` with subject `Pipeline: #NNN — <issue title>`.
2. Set task to `in_progress`.
3. Execute Phases 1 through 9 (per-issue phases).
4. On completion: set task to `completed`.
5. On skip: update task description with skip reason, set to `completed`.
6. On failure: update task description with failure details, set to `completed`.

The task list is the pipeline's state tracker. At any point, the task list shows which issues are done, in progress, or pending.

### Branch Base Selection

- **First issue in a chain** — branch from `main` (or the project's default branch).
- **Subsequent issues in a chain** — branch from the previous issue's branch (stacked PRs).
- **Standalone issues** — branch from `main`.

---

## Phase 1: Assess

Runs at the start of each issue. Verifies the issue is still actionable against the current codebase.

### Step 1 — Re-read Issue

Read the full issue body, not just the Phase 0 summary. Identify:
- Acceptance criteria (explicit or implied)
- Open questions or ambiguities
- Referenced files, classes, or APIs
- Referenced PRs (especially recently merged ones)

### Step 2 — Codebase Verification

For each file, class, or API mentioned in the issue body:
- Does it still exist? (`find` or `grep` for it)
- Is the described problem still present?
- Are the proposed changes still compatible with current code?

If the issue is in a chain and branches from a previous issue's branch, verify against that branch (not `main`).

### Step 3 — Scope Estimation

Classify the issue:

| Scope | Criteria |
|-------|----------|
| **Trivial** | Single file, <50 lines changed, no new tests needed |
| **Small** | 2-5 files, <200 lines, straightforward tests |
| **Medium** | 5-15 files, new module or significant refactor |
| **Large** | 15+ files, architectural changes, multiple test types |

### Step 4 — Skill Mapping

Match anticipated changed file paths against the skill index built in Phase 0 (Step 0.9). For each skill in the index, check if its description or when-to-use keywords relate to the files this issue will touch.

Do NOT hardcode file-pattern-to-skill mappings. The skill index is dynamic — use the name and description from each skill's frontmatter to determine relevance.

Record all matched skills for loading in Phase 3 (planning) and Phase 5 (implementation).

### Step 5 — On-Demand Context Loading

Three sources of additional context:

**Keyword matching:**
- Scan issue title and body for keywords that match architecture docs, specs, plans, or memory entries loaded in Phase 0.
- Load matching files from `docs/superpowers/specs/`, `docs/superpowers/plans/`, or equivalent.
- Load matching memory files if the memory index suggests relevance.

**File path extraction:**
- Parse the issue body for explicit file path references: patterns like `src/...`, `app/...`, `*.kt`, `*.py`, backtick-quoted paths.
- Read each referenced file directly — this is more reliable than keyword heuristics.

**Recently merged context:**
- If the issue body references merged PRs (`PR #NNN`, `#NNN merged`, `landed in #NNN`), read their diffs via `gh pr diff <N>` to understand what recently changed.
- This provides the "previous session state" that manual prompts carry but automated pipelines normally miss.

### Step 6 — Risk Assessment

Check each risk factor:

| Factor | Check |
|--------|-------|
| Security-sensitive code | Does this touch encryption, auth, credential storage, network config? |
| Public API surface | Does this change interfaces, exported functions, or API contracts? |
| CI/CD configuration | Does this modify build scripts, workflows, or deploy config? |
| Existing tests | Could this break existing tests? Check for tests covering modified files. |

### Step 7 — Scope Exclusion

Identify related issues referenced in the body that are NOT in the pipeline input list. Record them explicitly as **out of scope** to prevent implementation drift.

Examples:
- "Follow-up issue #108 exists but is separate" → record #108 as excluded.
- "See also #200 for the UI side" → record #200 as excluded.
- "This is part of the work tracked in #50" → record #50 as excluded (unless #50 is in the input list).

These exclusions are passed to implementation subagents so they don't accidentally address related-but-separate work.

### Assessment Output

```
Assessment:
  codebase_still_matches: true/false
  scope: trivial | small | medium | large
  files_to_modify: [list of file paths]
  skills_needed: [list of skill names]
  on_demand_context_loaded: [list of additional docs loaded]
  risks: [list of identified risks]
  blockers_found: [list of unexpected blockers]
  out_of_scope: [list of related issue numbers explicitly excluded]
```

### Decision Point

If `codebase_still_matches = false` and the divergence is significant (the core premise of the issue is invalid):
1. Mark issue as **skip** with reason "Issue outdated — codebase has diverged."
2. Post a comment on the issue explaining what changed.
3. Move to the next issue.

Minor divergences (e.g., a file was renamed but the concept is the same) do not trigger a skip — note the change and adapt.

---

## Phase 2: Update Issue

Leaves a trail on the GitHub issue showing the pipeline assessed it.

### Step 1 — Post Assessment Comment

Post a comment on the GitHub issue using the template at `templates/assessment-comment.md`. Fill in all `{{placeholder}}` fields from the assessment output.

Use `gh issue comment <N> --body "<comment>"` or GitHub MCP `add_issue_comment`.

### Step 2 — Update Issue Body

Only if factual inaccuracies were found during assessment (e.g., a file path changed, a class was renamed). Append a note at the end of the issue body — do NOT rewrite the original content.

If nothing needs correction, skip this step.

### Step 3 — Add Label

Add the `pipeline/in-progress` label to the issue.
- If the label doesn't exist in the repository, create it first (color: `#0E8A16`, description: "Issue being processed by the pipeline").
- Use `gh issue edit <N> --add-label "pipeline/in-progress"` or GitHub MCP.

---

## Phase 3: Plan

Creates a detailed implementation plan before any code is written.

### Step 1 — Load Relevant Skills

Read the full content of every `SKILL.md` identified in Phase 1 Step 4 (skill mapping). These inform the plan's approach — the plan should follow the patterns and conventions documented in the matched skills.

### Step 2 — Generate Plan

If your environment has a `writing-plans` skill, invoke it. Otherwise, generate a plan covering all of the following areas:

**File-by-file changes:**
- What changes in each existing file, why, and the order of changes.
- Group related changes so the reviewer can follow the logic.

**New files:**
- Any new files to create, with their purpose and location.
- Follow existing module structure and naming conventions from the foundation context.

**Test strategy:**
- Which tests to add or modify.
- Where they go (language/framework-specific test directories discovered in Phase 0).
- What they cover (unit, integration, UI, screenshot).
- Whether tests are KMP-compatible or platform-specific.

**Build/CI changes:**
- If the issue touches dependency manifests, build config, or CI workflows.
- Specific changes needed and compatibility concerns.

**Migration/compatibility:**
- Any data migration, API changes, or backward compatibility concerns.
- If none, explicitly state "No migration needed."

### Step 3 — Save Plan

Write the plan to a file:
- Path pattern: `docs/superpowers/plans/YYYY-MM-DD-issue-NNN-<slug>.md`
- `<slug>` is derived from the issue title (lowercase, hyphens, no special characters, max 40 chars).
- Use today's date.

If the `docs/superpowers/plans/` directory doesn't exist, use whatever plans directory exists in the project, or create one at a sensible location.

### Step 4 — Validate Plan Against Scope

Cross-check the plan against the Phase 1 assessment:
- Does the plan touch files not mentioned in the assessment's `files_to_modify` list?
  - If yes and the additions are legitimate (the assessment missed them): update the assessment.
  - If yes and the additions are overreach (the plan goes beyond the issue): trim the plan.
- Does the plan address the issue's acceptance criteria?
- Does the plan accidentally address any issue in the `out_of_scope` list? If so, remove that work from the plan.

### Step 5 — Scope Decomposition (Large Issues Only)

If the plan reveals the issue is Large scope (15+ files, multiple concerns, or architectural changes), decompose it into multiple sequential sub-PRs instead of one giant PR.

**When to decompose:**
- The plan touches 15+ files across distinct concerns (e.g., data layer + UI + tests)
- The plan has natural seams — groups of changes that are independently reviewable and mergeable
- A single PR would be too large for meaningful code review

**How to decompose:**

If `writing-plans` was used in Step 2, its scope check may have already split the plan into sub-plans (one per subsystem). Each sub-plan becomes a sub-PR.

If the plan was not already split, decompose it manually:
- Identify natural seams — groups of changes that are independently buildable and testable
- Each group should touch a distinct set of files where possible
- Order by dependency — foundational changes (data layer, interfaces) first, then consumers (UI, integration)
- Each group gets its own plan section with file list and acceptance criteria

**Effect on pipeline execution:**

The decomposed tasks form an **internal chain** for this issue — the same chain semantics from Phase 0 apply:
- Each task gets its own worktree and branch: `pipeline-issue-NNN-task-T-<slug>`
- Each task branches from the previous task's branch (stacked PRs)
- Each task goes through Phases 5-8 independently (implement, review, fix, create PR)
- All PRs reference the parent issue with `Part of #NNN` in the PR body
- The last PR in the chain carries `Closes #NNN`

Update the plan file to reflect the decomposition — mark each task's boundary clearly with the files it touches and its acceptance criteria.

### Decision Point (Non-Large Issues)

If the scope is Trivial, Small, or Medium: proceed as a single PR. The plan review will catch any overreach.

---

## Phase 4: Plan Review

Reviews the implementation plan against architecture rules and project conventions **before any code is written**. Catching mistakes here is far cheaper than after implementation.

### Dispatch Plan Reviewer

Dispatch a subagent using the prompt template at `plan-reviewer-prompt.md`. Pass it:
- The plan file content
- The foundation context (architecture docs, service contracts, project instructions)
- The assessment report (so it knows the intended scope)

### Finding Format

The reviewer produces structured findings:

```
Finding:
  severity: critical | warning | info
  plan_section: "<section heading where the issue was found>"
  rule: "<rule name and source>"
  description: "<what's wrong>"
  suggestion: "<how to fix it>"
```

### Decision Point

| Findings | Action |
|----------|--------|
| **Critical findings > 0** | Revise the plan to address each critical finding. Re-submit for review. Max 2 revision attempts. |
| **Still critical after 2 revisions** | **Chain failure** — the issue's requirements may be incompatible with the architecture. Stop the chain. |
| **Warnings only** | Note them and carry forward to implementation. Phase 6 will re-check the actual code. |
| **No findings** | Proceed to Phase 5. |

### Revision Loop

When revising the plan:
1. Read each critical finding carefully.
2. Modify the plan to address the finding.
3. Update the plan file on disk.
4. Re-dispatch the plan reviewer with the updated plan.
5. If the revision introduces new criticals, count this as one of the 2 attempts.

---

## Phase 5: Implement

Executes the reviewed plan in an isolated working copy.

### Step 1 — Create Isolated Working Copy

Create an isolated workspace for this issue:

- **In Claude Code**: use `EnterWorktree` with name `pipeline-issue-NNN-<slug>`. This creates a branch named `worktree-pipeline-issue-NNN-<slug>` automatically. Do not attempt to rename the branch — push it as-is.
- **In other environments**: use `git worktree add .claude/worktrees/pipeline-issue-NNN-<slug> -b worktree-pipeline-issue-NNN-<slug>` or equivalent.

Branch from the appropriate base:
- **First issue in a chain** → branch from `main` (or project default branch).
- **Subsequent issues in a chain** → branch from the previous issue's branch.
- **Standalone issues** → branch from `main`.

Branch naming convention: `worktree-pipeline-issue-NNN-<slug>` (the `worktree-` prefix is added automatically by `EnterWorktree`)

### Step 2 — Load Required Skills

Read the full content of every `SKILL.md` identified in Phase 1 Step 4. Pass the skill content to implementation subagents as context.

### Step 3 — Dispatch Implementation

Based on the scope estimated in Phase 1:

**Trivial or Small scope:**
- Single subagent executes the entire plan.
- Use the prompt template at `implementer-prompt.md`.
- Pass: the full plan, loaded skills, foundation context, out-of-scope exclusions.

**Medium or Large scope:**
- Decompose the plan into independent units (groups of files with no cross-dependencies).
- Dispatch parallel subagents, one per unit.
- Each subagent receives its section of the plan plus the full foundation context.
- Wait for all subagents to complete before proceeding.

Each subagent must follow the plan exactly — no improvisation, no scope creep. If the plan is unclear, the subagent should ask (or flag the ambiguity) rather than guess.

### Step 4 — Run Tests

Execute test commands discovered in Phase 0 (Step 0.7). Do NOT hardcode test commands — use whatever the project provides.

Common patterns to look for in foundation context:
- A test script (e.g., `scripts/test-all.sh`, `make test`, `npm test`)
- Build tool test tasks (e.g., `./gradlew test`, `cargo test`, `pytest`)
- CI workflow test steps (replicate what CI runs)

If the project uses Gradle in a sandbox environment, add `--no-daemon` to all Gradle commands.

Run the tests that cover the modified files. If test infrastructure is minimal, run the full suite.

### Step 5 — Run Linters

Execute lint commands discovered in Phase 0 (Step 0.8). Do NOT hardcode lint commands.

- Run each discovered linter on changed files.
- Auto-fix formatting issues where the linter supports it (e.g., `--fix` flags).
- Lint failures that can't be auto-fixed are treated the same as test failures.

If no linters were discovered in Phase 0, skip this step.

### Step 6 — Verification Gate

| Outcome | Action |
|---------|--------|
| All tests and linters pass | Proceed to Phase 6 (Implementation Review) |
| Tests or linters fail | Enter retry loop (max 2 attempts) |

**Retry loop:**
1. Analyze the failure output — determine if it's a test bug, lint issue, or implementation bug.
2. Fix the identified issue.
3. Re-run tests and linters.
4. If still failing after 2 retries → **chain failure**: stop the chain, comment on the issue with the failure output, mark remaining chain issues as skipped, start the next independent chain from `main`.

---

## Phase 6: Implementation Review

Reviews the actual code changes after implementation. Four independent review angles run in parallel, each by a separate subagent. This is distinct from Phase 4 (plan review) — here we review the code, not the plan.

### Diff Capture

Get the diff of all changes since branching:
```bash
git diff $(git merge-base HEAD <base-branch>)..HEAD
```
Where `<base-branch>` is `main` (for first in chain / standalone) or the previous issue's branch (for subsequent in chain).

### Prerequisite — Load Core Review Skills

Before dispatching reviewers, load the project's core review skills. These are skills that apply to **every** review regardless of which files changed.

Check the foundation context for a project-specific architecture review skill (e.g., `pr-architecture-review`). If it documents "always load" or "core" skills, load all of them. These typically cover foundational patterns like concurrency, state management, multiplatform abstractions, and DI — areas where violations are subtle and file-pattern matching alone won't catch them.

Pass these core skills to the architecture and skill-compliance reviewers alongside the dynamically matched skills from Phase 1.

### Review Angles

Dispatch 4 subagents in parallel, each using the prompt template at `reviewer-prompt.md` with a different `review_angle`:

| # | Angle | What It Checks |
|---|-------|----------------|
| 1 | **architecture** | Layer violations, module boundary crossings, interface contracts, state exposure, DI rules, naming, file placement, error handling contracts |
| 2 | **code-quality** | Bugs, logic errors, edge cases, code duplication, API misuse, missing error handling at system boundaries, file size and complexity |
| 3 | **security** | Hardcoded secrets, insecure storage, missing HTTPS enforcement, credential handling, injection vectors, deprecated security APIs |
| 4 | **skill-compliance** | Changed files checked against loaded skills — framework patterns, test patterns, state management, KMP rules |

**Token budget optimization**: for Trivial or Small scope issues, reduce to 2 reviewers (architecture + code-quality only). Security and skill-compliance add value mainly for Medium/Large changes.

Each subagent receives:
- The diff
- The foundation context
- The specific angle to review from
- Core review skills (always, for architecture and skill-compliance angles)
- Dynamically matched skills from Phase 1 (for skill-compliance angle)

### Finding Format

Each reviewer produces structured findings:

```
Finding:
  severity: critical | warning | info
  file: path/to/File.kt
  line: 42
  rule: "<rule name and source>"
  description: "<what's wrong>"
  suggestion: "<how to fix it>"
```

### Aggregation

After all reviewers complete:
1. Merge all findings into a single list.
2. Deduplicate — if two reviewers flag the same file + line with similar descriptions, keep the one with higher severity.
3. Sort by severity: critical first, then warning, then info.

### Decision Point

| Findings | Action |
|----------|--------|
| **Critical findings > 0** | Must fix in Phase 7 |
| **Warnings only** | Fix each warning, or explicitly justify it as an intentional design decision in the PR description. No silent skips. |
| **Info only** | Non-actionable — include in PR description for reviewer awareness, no action required |
| **No findings** | Skip Phase 7, proceed directly to Phase 8 |

---

## Phase 7: Fix

Addresses review findings and verifies fixes don't introduce regressions.

### Step 1 — Fix Critical Findings

For each critical finding, in order:
1. Read the finding's file and line.
2. Make the change suggested (or an equivalent fix that addresses the same rule violation).
3. Verify the specific finding is resolved — re-check that code section.
4. Do NOT fix unrelated code nearby — scope discipline applies to fixes too.

### Step 2 — Fix or Justify Warnings

For each warning, choose one:
- **Fix it** — apply the fix if it's straightforward and low-risk.
- **Justify it** — if the warning reflects an intentional design decision (not just difficulty of fixing), document the rationale in the PR description under "Acknowledged debt" with a clear explanation of why the current approach was chosen.

Every warning must be resolved one way or the other. "Too hard to fix" is not a justification — if the fix is genuinely too large for this PR, create a follow-up issue and reference it.

### Step 3 — Re-run Tests and Linters

Run the full test suite using the commands from Phase 0 discovery. Also re-run linters. This catches regressions introduced by the fixes.

### Step 4 — Targeted Re-review

Run ONLY the review angles that produced critical findings, ONLY on the files that were changed during the fix phase. This is a targeted re-check, not a full re-review.

Use the same `reviewer-prompt.md` template but scope the diff to just the fix commits.

### Step 5 — Convergence Check

| Outcome | Action |
|---------|--------|
| No remaining criticals | Proceed to Phase 8 |
| New criticals introduced by fixes | Retry — this counts as attempt 2 of 2 |
| Still criticals after 2nd attempt | **Chain failure** — stop the chain, comment on the issue with the unresolvable findings |

### Scope Constraint

Phase 7 must NOT change the scope of the implementation. Fixes address specific review findings — they don't redesign the approach. If a review finding reveals that the approach is fundamentally wrong, that's a **chain failure**, not a redesign opportunity.

---

## Phase 8: Create PR

Packages the work into a reviewable PR with full documentation.

### Step 1 — Commit

If there are uncommitted changes, commit them. Use the conventional commit format:

```
<type>: <imperative description> (#NNN)

<optional body explaining why, not what>

Assisted-by: <model name> <noreply@anthropic.com>
```

Types: `feat`, `fix`, `test`, `chore`, `perf`, `refactor`, `docs`.

Use the project's attribution trailer format (check project instructions). If none specified, use `Assisted-by:`.

### Step 2 — Push Branch

```bash
git push -u origin HEAD
```

### Step 3 — Create PR

The PR target depends on position in chain:
- **First issue in chain** → targets `main` (or project default branch).
- **Subsequent issues in chain** → targets the previous issue's branch (stacked PR).
- **Standalone issues** → targets `main`.

PR title format: `<type>: <description> (#NNN)`

Use `gh pr create` or GitHub MCP `create_pull_request`.

### Step 4 — PR Body

Use the template at `templates/pr-body.md`. Fill in all `{{placeholder}}` fields from:
- The assessment report (Phase 1)
- The plan (Phase 3)
- The review findings (Phases 4 and 6)
- The fix actions (Phase 7)
- The test results (Phases 5 and 7)

### Step 5 — Post Review Summary Comment

Post a comment on the PR using the template at `templates/review-summary.md`. This consolidates all review rounds into one comment, satisfying the rule: "Before merging any PR, ALWAYS post a review summary comment."

Use `gh pr comment <N> --body "<comment>"` or GitHub MCP `add_issue_comment` (with PR number as issue number).

### Step 6 — Update GitHub Issue

1. Post a comment on the issue linking to the PR: `Implementation PR: #<pr_number>`
2. Replace the `pipeline/in-progress` label with `pipeline/awaiting-merge`.
   - Remove: `gh issue edit <N> --remove-label "pipeline/in-progress"`
   - Add: `gh issue edit <N> --add-label "pipeline/awaiting-merge"`
   - If `pipeline/awaiting-merge` doesn't exist, create it (color: `#1D76DB`, description: "Pipeline PR created, awaiting human merge").

---

## Phase 9: Cleanup

Runs after each issue completes (or is skipped/failed), regardless of outcome.

### Step 1 — Worktree Handling

- **PR created** → keep the worktree alive (the branch needs it until merge).
- **Skipped** (no PR created) → clean up the worktree.
- **Failed** (no PR created) → clean up the worktree.

In Claude Code: use `ExitWorktree` with `action: "keep"` for PRs, `action: "remove"` for skips/failures.

In other environments: `git worktree remove <path>` for skips/failures.

### Step 2 — Update Issue Labels

| Outcome | Label |
|---------|-------|
| PR created | `pipeline/awaiting-merge` (already set in Phase 8) |
| Skipped | `pipeline/skipped` (color: `#E4E669`, description: "Skipped by pipeline") |
| Failed | `pipeline/failed` (color: `#D93F0B`, description: "Pipeline processing failed") |

Create labels if they don't exist.

### Step 3 — Log Result

Append to the pipeline run summary (held in memory, reported at completion):

```
Issue #NNN: PR #XXX created (chain C, position P/T)
Issue #NNN: SKIPPED — <reason>
Issue #NNN: FAILED — <phase>, <error summary>
```

### Step 4 — Move to Next Issue

Check the execution plan:
- **Next issue in current chain** → branch from current issue's branch (stacked PR).
- **Current chain complete** → start next chain from `main`.
- **All chains and standalones complete** → produce the completion report and proceed to Phase 10.

---

## Phase 10: Merge Gate

The human checkpoint. After all per-issue phases complete and all PRs are created, the pipeline presents the full list of PRs for human review before any merging begins.

### What the Pipeline Presents

```
Pipeline complete. PRs ready for review:
  Standalone: #410 (test: add ToolExecutor tests), #411 (chore: migrate strings), #412 (chore: Gradle lockfiles)
  Merge order: #410 → #411 → #412

Review each PR, then approve sequential merge? (y/n)
```

### What the Human Checks

For each PR:
- The diff
- The review summary comment on the PR
- CI status (must be green)
- Whether the changes match the issue requirements

### If the Human Requests Changes

The human either:
1. Makes the changes themselves on the branch.
2. Re-invokes the pipeline with just that issue number to apply fixes.
3. Removes the PR from the merge list and proceeds with the rest.

### If the Human Rejects a PR Mid-Chain

Downstream PRs in the same chain become invalid. The human closes them and can re-invoke the pipeline with the remaining issues as a fresh batch.

### If the Human Approves

Proceed to Phase 11 (Sequential Merge) with the approved list of PRs.

---

## Phase 11: Sequential Merge

Orchestrates the sequential auto-merge workflow to avoid wasted CI cycles caused by branch protection rules.

### Why Sequential Merging

Branch protection rules ("Require branches to be up to date before merging") mean that when a PR merges into `main`, `main`'s HEAD moves and all other open PRs are now "behind" — even if they touch completely different files. GitHub requires the PR branch to include the latest `main` commit before merging, so CI can validate the combined state.

Updating multiple PRs at once is wasteful: PR A merges → `main` moves → PR B (just updated) is behind again → needs another update + CI cycle. **Sequential is the only way to avoid throwaway CI runs.**

### Merge Sequence

Use MCP GitHub tools for all merge operations — `gh` CLI may be unavailable in sandbox mode.

For the list of approved PRs, in merge order:

1. **First PR:**
   a. Merge via MCP `merge_pull_request` with `merge_method: "squash"`.
   b. After merge completes, sync local state:
      ```bash
      git checkout main && git pull
      ```

2. **For each subsequent PR:**
   a. Verify the previous PR merged (MCP `pull_request_read` with `method: "get"`, check state is `MERGED`).
   b. Update the next PR's branch via MCP `update_pull_request_branch`. This rebases the PR onto the new `main` HEAD.
   c. Wait for CI to pass on the updated branch (poll with MCP `pull_request_read` with `method: "get_check_runs"` every ~60 seconds until all checks succeed).
   d. Merge via MCP `merge_pull_request` with `merge_method: "squash"`.
   e. Sync local state: `git checkout main && git pull`
   f. Repeat for the next PR.

### Merge Order

- **Standalone PRs** — merge in the order they were created (issue number order).
- **Chain PRs** — merge in topological order (dependencies first). When the base PR merges, GitHub auto-retargets the next PR in the stack to `main`.

### Timeout

If a PR's CI doesn't pass within 30 minutes of the branch update, log a warning and skip to the next PR. The stuck PR retains `pipeline/awaiting-merge` for human attention.

### Interruption

The human can intervene at any point during the merge sequence:
- Request changes on a PR — the pipeline detects the state change (via MCP `pull_request_read`) and skips it
- Close a PR — the pipeline skips it and moves to the next
- If a chain PR is skipped, downstream chain PRs are also skipped

### Post-Merge Worktree Cleanup

After all approved PRs are merged, clean up worktrees that were kept alive during Phase 9:

For each merged PR:
- Remove the worktree: `ExitWorktree` with `action: "remove"`, or `git worktree remove <path>`.
- The branch has been squash-merged into `main` — the worktree is no longer needed.

For PRs that were skipped during merge (CI timeout, human intervention):
- Keep the worktree alive — the branch may still need work.

---

## Pipeline Completion Report

When all issues are processed, output the completion report using the template at `templates/completion-report.md`.

The report includes:
- All PRs created, organized by chain with merge order
- All skipped issues with reasons
- All failed issues with failure details
- Merge instructions (chain order, standalone flexibility)
- Risk levels for each chain

Display the report to the user. This is the final output of the pipeline.

---

## Failure Handling

### Per-Issue Failures

| Failure Type | When | Action |
|-------------|------|--------|
| Issue not clean | Phase 1 (assess) | Skip, comment on issue, move to next |
| Codebase diverged | Phase 1 (assess) | Skip, comment on issue explaining what changed, move to next |
| Plan fails architecture review | Phase 4 (plan review) | Revise plan up to 2 times. If still failing → chain failure |
| Tests fail | Phase 5 (implement) | Retry fix 2 times. If still failing → chain failure |
| Linters fail (unfixable) | Phase 5 (implement) | Retry fix 2 times. If still failing → chain failure |
| Critical review findings unfixable | Phase 7 (fix) | Retry fix 2 times. If still failing → chain failure |
| Large scope discovered | Phase 3 (plan) | Decompose into sub-PRs via Step 5. Each task follows Phases 5-8 independently |

### Chain Failure Procedure

When a chain failure occurs:

1. **Stop processing the current chain.** Do not attempt the next issue in the chain.
2. **Comment on the failed issue** with:
   - What went wrong (the specific error or finding)
   - Which phase failed
   - Relevant error output or review findings
3. **Mark remaining issues in the chain** as skipped with reason "Dependent on failed issue #NNN."
4. **Update labels**: `pipeline/failed` on the failed issue, `pipeline/skipped` on remaining chain issues.
5. **Start the next independent chain** from `main`. Chain failures do not affect other chains.

### Cascade Risk

In stacked PR chains, a problem in issue N affects all downstream PRs (N+1, N+2, ...). This is accepted by design. Mitigations:

- Tests and linters run at every step — failures surface early.
- Chain failures stop the chain immediately — no cascading bad code.
- Independent chains are unaffected.
- The human can reject any PR and re-invoke the pipeline for remaining issues.

---

## Edge Cases

| Scenario | Handling |
|----------|----------|
| Issue is already closed | Skip, log "already closed" |
| Issue has open PR from outside the pipeline | Proceed — only PRs with `-pipeline-issue-` in the branch name trigger a skip |
| Two issues modify the same file | If they reference each other, DAG detection catches it. If not, the second issue sees the first's changes (stacked branch) and adapts |
| Input list contains duplicate issue numbers | Deduplicate in Phase 0 Step 1 |
| Input list contains issues from different repos | Error — pipeline operates on a single repo. Report and stop |
| CI fails on the PR after creation | Human checks CI during Phase 10 review. Phase 11 waits for CI to pass after branch updates before merging |
| Cycle detected in dependency DAG | Report the cycle (list issue numbers) and stop. Do not process any issues |
| All issues in the list are skipped | Report "No actionable issues found" and exit with the completion report listing all skips |

---

## Constraints and Limits

### Session Context

A full pipeline run for many issues may exceed a single session's context window. The skill is **re-invocable** — invoke it again with the same issue list and it picks up where it left off.

### Re-invocability

When invoked, Phase 0 always runs fresh. The cleanness check (Step 4) detects prior state:

1. **Existing pipeline branch** — `gh pr list --search "closes #N"` finds a PR with `-pipeline-issue-` in the head branch → skip this issue.
2. **`pipeline/awaiting-merge` label** — issue was handled in a previous run → skip.
3. **`pipeline/in-progress` label** — a previous run started but didn't finish this issue. Remove the label and re-process from Phase 1.
4. **`pipeline/failed` label** — a previous run failed on this issue. Remove the label and re-process (the underlying cause may have been fixed).
5. **`pipeline/skipped` label** — a previous run skipped this issue. Re-evaluate skip conditions — if the skip condition is resolved, process the issue; otherwise skip again.

This means: the user can safely re-invoke `/issue-pipeline #100 #101 #102 #103 #104` after a partial first run. Issues that already have PRs are skipped. Issues that failed or were interrupted are re-processed.

### Token Budget

Phase 6 (4 parallel review agents) is the most token-intensive step. For Trivial or Small scope issues, reduce to 2 reviewers (architecture + code-quality only) to conserve tokens.

### Sandbox Constraints

If running in a sandboxed environment:
- Add `--no-daemon` to all Gradle commands.
- Create temporary files in the project directory, not system temp directories.
- Be aware that sandbox PID namespaces may cause stale lock files.

### Worktree Disk Usage

Each active worktree consumes disk space. The pipeline creates one per issue and only cleans up skipped/failed ones. For large batches (10+ issues), monitor disk usage.

### Merge Conflicts

Phase 11 handles sequential merging with branch updates (`update_pull_request_branch`) to avoid merge conflicts. If the human bypasses Phase 11 and merges PRs out of order, remaining PRs may need manual rebasing.

---
