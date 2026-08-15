# Issue Pipeline Skill — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a cross-harness SKILL.md that an LLM agent follows to process GitHub issues end-to-end: triage → assess → plan → review → implement → review → fix → PR.

**Architecture:** Single SKILL.md with companion prompt templates for subagent dispatch (plan reviewer, implementation reviewer, implementer). The skill uses task lists for state tracking — each issue becomes a task, each phase updates its status.

**Tech Stack:** Markdown skill file, compatible with Claude Code, Codex, Gemini CLI, Copilot.

## Global Constraints

- The skill file lives at `skills/issue-pipeline/SKILL.md` (project-local, later extracted to own repo)
- Companion files live alongside: `skills/issue-pipeline/*.md`
- The skill must be project-agnostic — no hardcoded file paths, project names, or tool names
- Foundation context discovery must be dynamic (scan project files, not assume their existence)
- All GitHub operations use `gh` CLI or MCP tools (the skill describes the action, not the specific tool)
- The skill must handle both single-issue and multi-issue invocations
- Commit trailers use `Assisted-by:` format per user's global CLAUDE.md

## File Structure

```
skills/issue-pipeline/
├── SKILL.md                    # Main skill — the pipeline checklist
├── triage-prompt.md            # Prompt template for triage subagent (Phase 0)
├── plan-reviewer-prompt.md     # Prompt template for plan review subagent (Phase 3.5)
├── implementer-prompt.md       # Prompt template for implementation subagent (Phase 4)
├── reviewer-prompt.md          # Prompt template for implementation review subagent (Phase 5)
└── templates/
    ├── assessment-comment.md   # GitHub issue comment template (Phase 2)
    ├── pr-body.md              # PR body template (Phase 7)
    ├── review-summary.md       # PR review summary comment template (Phase 7)
    └── completion-report.md    # Pipeline completion report template (Phase 9)
```

**Why separate prompt files:** Each subagent needs a focused, self-contained prompt. Inlining 5 different multi-paragraph prompts in the SKILL.md would make it unreadable. The SKILL.md references them by relative path.

**Why templates/:** Separating output templates from logic keeps the SKILL.md focused on the pipeline flow. Templates contain the exact markdown structure for comments and PR bodies.

---

### Task 1: Skill Skeleton and Phase 0 (Dynamic Triage)

**Files:**
- Create: `skills/issue-pipeline/SKILL.md`
- Create: `skills/issue-pipeline/triage-prompt.md`

**Interfaces:**
- Consumes: GitHub issue numbers from user invocation
- Produces: Execution plan (chains, standalone issues, skipped issues) used by all subsequent phases

- [ ] **Step 1: Create skill directory**

```bash
mkdir -p skills/issue-pipeline/templates
```

- [ ] **Step 2: Write SKILL.md header and metadata**

Write the frontmatter, overview, when-to-use section, and the Iron Law. The skill must establish:
- Name: `issue-pipeline`
- Description: one line for skill index
- When to use: user provides issue numbers, wants autonomous implementation
- The Iron Law: "No implementation without triage, plan, and plan review first"
- Invocation format: `/issue-pipeline #NNN #NNN ...` or `/issue-pipeline NNN NNN ...`

The header should also include the pipeline phases overview as a numbered list so the agent can see the full flow at a glance before diving into details.

- [ ] **Step 3: Write Phase 0 — Foundation Context Discovery**

This section instructs the agent to discover project context dynamically. It must NOT hardcode any file paths. Instead, it provides a discovery algorithm:

1. Read `CLAUDE.md` (project root) — always exists in a Claude Code project
2. Read global `CLAUDE.md` — always exists
3. Scan for architecture docs: `find . -path "*/architecture/*" -name "*.md"`
4. Scan for CI config: `ls .github/workflows/*.yml` or `.gitlab-ci.yml` or `Jenkinsfile`
5. Scan for dependency manifests: `ls gradle/libs.versions.toml package.json requirements.txt Cargo.toml go.mod`
6. Scan for test scripts: `ls scripts/test* Makefile` and check `package.json` scripts
7. Scan for lint config: `ls .ruff.toml .eslintrc* detekt.yml .editorconfig`
8. Scan for project skills: `find . -path "*/skills/*/SKILL.md"` — read first 10 lines of each
9. Read `MEMORY.md` index if it exists
10. Capture git state: `git branch --show-current`, `git log --oneline -10`, `gh pr list --state open`
11. Scan module structure: `find . -name "src" -type d | head -20` to map source sets

Each discovery step uses `if exists, read it; otherwise skip`. This makes the skill work on any project.

- [ ] **Step 4: Write Phase 0 — Issue Fetching and Dependency Parsing**

Document the steps for:
1. Fetching all issues (via `gh issue view #N` or GitHub MCP `issue_read`)
2. Parsing dependency patterns from issue bodies (the regex patterns from the spec)
3. Classifying as internal dep / external blocker / resolved
4. Cleanness check — only skip `pipeline/`-prefixed PRs

- [ ] **Step 5: Write Phase 0 — DAG Building, Chain Sorting, Risk Evaluation**

Document:
1. How to build the DAG from classified dependencies
2. How to group connected components into chains
3. Topological sort within chains
4. Chain ordering heuristic (CI/build → tests → features)
5. Risk evaluation per chain (LOW/MEDIUM/HIGH based on length, file overlap, scope)
6. The execution plan output format

- [ ] **Step 6: Write triage-prompt.md**

This is the prompt template for a subagent that handles Phase 0 steps 1-8 (everything after foundation context loading). The main skill loads foundation context, then dispatches this subagent with:
- The list of issue numbers
- The foundation context
- Instructions to return a structured execution plan

The prompt must instruct the subagent to:
- Fetch all issues
- Parse dependencies
- Build the DAG
- Sort into chains
- Evaluate risk
- Return the execution plan as structured output

- [ ] **Step 7: Verify Phase 0 completeness**

Read the spec's Phase 0 section. Check every step, output field, and decision point is covered in the SKILL.md and triage-prompt.md. Verify:
- All 9 steps (0-8) from the spec are present
- Foundation context discovery is fully dynamic (no hardcoded paths)
- The execution plan output format matches the spec
- Cycle detection is documented
- Skip reasons are documented

- [ ] **Step 8: Commit**

```bash
git add skills/issue-pipeline/SKILL.md skills/issue-pipeline/triage-prompt.md
git commit -m "feat: add issue-pipeline skill skeleton and Phase 0 triage

Assisted-by: <model name> <noreply@anthropic.com>"
```

---

### Task 2: Per-Issue Phases 1-2 (Assess and Update)

**Files:**
- Modify: `skills/issue-pipeline/SKILL.md`
- Create: `skills/issue-pipeline/templates/assessment-comment.md`

**Interfaces:**
- Consumes: Execution plan from Task 1, individual issue data
- Produces: Assessment report (scope, files, skills, risks, exclusions) used by Phase 3

- [ ] **Step 1: Write the per-issue loop structure**

Before the Phase 1 details, write the loop instruction that tells the agent:
- "For each issue in execution order (chains first, then standalone):"
- Create a task for the issue: `TaskCreate` with the issue title
- Set task to `in_progress`
- Execute Phases 1-9
- Set task to `completed` (or update with skip/fail status)

This is the state tracking mechanism — the task list shows pipeline progress.

- [ ] **Step 2: Write Phase 1 — Assess**

Document all 7 steps from the spec:
1. Re-read issue body (full, not summary)
2. Codebase verification (check referenced files exist)
3. Scope estimation (Trivial/Small/Medium/Large with criteria)
4. Skill mapping (file patterns → skill names, project-agnostic)
5. On-demand context loading (keyword matching + file path extraction + merged PR diffs)
6. Risk assessment (security, API surface, CI, tests)
7. Scope exclusion (identify out-of-scope related issues)

Include the assessment report output format and the decision point (skip if codebase diverged).

Make the skill mapping project-agnostic: instead of hardcoding `*Screen.kt → compose-skill`, instruct the agent to match changed file patterns against the skill index built in Phase 0. The skill index already has name + description — the agent uses those to determine relevance.

- [ ] **Step 3: Write Phase 2 — Update Issue**

Document:
1. Post assessment comment (reference the template file)
2. Update issue body only if inaccuracies found
3. Add `pipeline/in-progress` label

- [ ] **Step 4: Write assessment-comment.md template**

Create `templates/assessment-comment.md` with the exact markdown structure. Use `{{placeholder}}` syntax for dynamic fields:

```markdown
## Pipeline Assessment (automated)

- **Scope**: {{scope}} ({{file_count}} files, ~{{line_count}} lines)
- **Files**: {{file_list}}
- **Skills**: {{skills_list}}
- **Risks**: {{risks_or_none}}
- **Out of scope**: {{excluded_issues_or_none}}
- **Status**: {{status}}
```

- [ ] **Step 5: Verify Phases 1-2 completeness**

Check every step, output field, and decision point from the spec is covered. Verify:
- All 7 Phase 1 steps present
- Assessment report has all fields (including `out_of_scope`)
- Decision point for codebase divergence is documented
- Phase 2 has all 3 steps
- Template uses the correct structure from the spec

- [ ] **Step 6: Commit**

```bash
git add skills/issue-pipeline/SKILL.md skills/issue-pipeline/templates/assessment-comment.md
git commit -m "feat(issue-pipeline): add Phases 1-2 assess and update

Assisted-by: <model name> <noreply@anthropic.com>"
```

---

### Task 3: Phases 3-3.5 (Plan and Plan Review)

**Files:**
- Modify: `skills/issue-pipeline/SKILL.md`
- Create: `skills/issue-pipeline/plan-reviewer-prompt.md`

**Interfaces:**
- Consumes: Assessment report from Task 2
- Produces: Reviewed implementation plan file, used by Phase 4

- [ ] **Step 1: Write Phase 3 — Plan**

Document:
1. Load relevant skills (full SKILL.md content, not just index)
2. Generate plan using the writing-plans pattern (reference it by name, don't embed it)
3. Save plan to `docs/superpowers/plans/YYYY-MM-DD-issue-NNN-<slug>.md`
4. Validate plan against scope (check for overreach)

The skill should instruct: "If your environment has a `writing-plans` skill, invoke it. Otherwise, generate a plan covering: file-by-file changes, new files, test strategy, build/CI changes, migration/compatibility."

- [ ] **Step 2: Write Phase 3.5 — Plan Review**

Document:
1. Dispatch a plan review subagent (reference `plan-reviewer-prompt.md`)
2. The reviewer checks the plan against foundation context (service contracts, architecture docs)
3. Finding format (severity, plan_section, rule, description, suggestion)
4. Decision point: critical → revise (2 attempts max) → chain failure if unresolvable
5. Warnings → carry forward

- [ ] **Step 3: Write plan-reviewer-prompt.md**

Prompt template for the plan review subagent. It receives:
- The plan file content
- The foundation context (architecture docs, service contracts)
- The assessment report (so it knows the intended scope)

It must check:
- Code placement (right modules, right source sets)
- Interface requirements
- Layer architecture compliance
- Test placement
- Module boundary respect
- Scope match (plan vs issue requirements)

It returns findings in the structured format.

- [ ] **Step 4: Verify Phases 3-3.5 completeness**

Check against spec. Verify:
- Plan generation covers all 5 areas from the spec
- Plan review checks all 6 items from the spec
- Revision loop (up to 2 attempts) is documented
- Chain failure on unresolvable plan issues is documented
- The plan reviewer prompt is self-contained (doesn't assume session context)

- [ ] **Step 5: Commit**

```bash
git add skills/issue-pipeline/SKILL.md skills/issue-pipeline/plan-reviewer-prompt.md
git commit -m "feat(issue-pipeline): add Phases 3-3.5 plan and plan review

Assisted-by: <model name> <noreply@anthropic.com>"
```

---

### Task 4: Phase 4 (Implement)

**Files:**
- Modify: `skills/issue-pipeline/SKILL.md`
- Create: `skills/issue-pipeline/implementer-prompt.md`

**Interfaces:**
- Consumes: Reviewed plan from Task 3, skills list, base branch
- Produces: Working implementation on worktree branch, all tests/linters passing

- [ ] **Step 1: Write Phase 4 — Implement**

Document:
1. Create worktree (branch from appropriate base — first in chain from `main`, subsequent from previous issue's branch). Branch naming: `pipeline/issue-NNN-<slug>`
2. Load all required skills (full content, passed to subagents)
3. Dispatch implementation — scope-based:
   - Trivial/Small: single subagent, entire plan
   - Medium/Large: decompose into independent units, parallel subagents
4. Run tests — instruct the agent to use the test commands discovered in Phase 0. Don't hardcode test commands.
5. Run linters — use the lint commands discovered in Phase 0. Auto-fix where supported.
6. Verification gate — pass/retry/chain-failure logic

Make the worktree instructions harness-agnostic: "Create an isolated working copy. In Claude Code, use `EnterWorktree`. In other environments, use `git worktree add`."

- [ ] **Step 2: Write implementer-prompt.md**

Prompt template for implementation subagents. Each receives:
- Their section of the plan (or the full plan for Trivial/Small)
- The relevant skill content
- The foundation context
- The out-of-scope exclusions list
- Explicit instruction: "Follow the plan exactly. No improvisation, no scope creep. If the plan is unclear, ask — don't guess."

The prompt must also instruct:
- Commit after each logical unit of work
- Use the commit message format from the spec
- Run tests after implementation to verify before reporting done

- [ ] **Step 3: Verify Phase 4 completeness**

Check against spec. Verify:
- Worktree branching logic (first in chain vs subsequent) is correct
- Scope-based dispatch (trivial/small vs medium/large) is documented
- Test and lint steps reference Phase 0 discovery (not hardcoded commands)
- Verification gate has correct retry/failure logic
- The implementer prompt is self-contained

- [ ] **Step 4: Commit**

```bash
git add skills/issue-pipeline/SKILL.md skills/issue-pipeline/implementer-prompt.md
git commit -m "feat(issue-pipeline): add Phase 4 implement with subagent dispatch

Assisted-by: <model name> <noreply@anthropic.com>"
```

---

### Task 5: Phases 5-6 (Implementation Review and Fix)

**Files:**
- Modify: `skills/issue-pipeline/SKILL.md`
- Create: `skills/issue-pipeline/reviewer-prompt.md`

**Interfaces:**
- Consumes: Implementation diff on worktree branch
- Produces: Clean branch with no critical findings, all tests passing

- [ ] **Step 1: Write Phase 5 — Implementation Review**

Document the 4 review angles:
1. Architecture review (using `git-review` + `docs/architecture/service-contracts.md`)
2. Code quality review (using `/code-review` or equivalent)
3. Security review (using `/security-review` or equivalent)
4. Skill compliance (checking changed files against loaded skills)

Make review tool references harness-agnostic: "If your environment has a `code-review` skill/plugin, use it. Otherwise, review the diff for: bugs, logic errors, edge cases, code duplication, API misuse."

Document:
- Finding format (severity, file, line, rule, description, suggestion)
- Aggregation (merge, deduplicate, sort by severity)
- Decision point (critical → must fix, warnings → fix if easy, info → note only)

- [ ] **Step 2: Write reviewer-prompt.md**

Prompt template for review subagents. Each review angle gets a separate invocation of this prompt with a different `review_angle` parameter. The prompt receives:
- The diff (all commits since branching)
- The foundation context
- The specific angle to review from (architecture / code quality / security / skill compliance)
- For skill compliance: the loaded skill content for the changed file types

It returns findings in the structured format.

- [ ] **Step 3: Write Phase 6 — Fix**

Document:
1. Fix critical findings (one at a time, verify each)
2. Fix straightforward warnings (skip rework-heavy ones)
3. Re-run tests and linters
4. Re-review ONLY the angles that had criticals, ONLY changed files
5. Convergence check (no criticals → proceed, new criticals → retry, still failing → chain failure)
6. The scope constraint: "Do not change the implementation scope. If a finding reveals the approach is fundamentally wrong, that's a chain failure."

- [ ] **Step 4: Verify Phases 5-6 completeness**

Check against spec. Verify:
- All 4 review angles present
- Finding format matches spec
- Aggregation logic documented
- Fix phase has all 5 steps
- Convergence check has correct retry/failure logic
- Scope constraint is explicit
- Reviewer prompt is self-contained and parameterized by angle

- [ ] **Step 5: Commit**

```bash
git add skills/issue-pipeline/SKILL.md skills/issue-pipeline/reviewer-prompt.md
git commit -m "feat(issue-pipeline): add Phases 5-6 review and fix

Assisted-by: <model name> <noreply@anthropic.com>"
```

---

### Task 6: Phases 7-9 (PR, Merge Gate, Cleanup) and Completion

**Files:**
- Modify: `skills/issue-pipeline/SKILL.md`
- Create: `skills/issue-pipeline/templates/pr-body.md`
- Create: `skills/issue-pipeline/templates/review-summary.md`
- Create: `skills/issue-pipeline/templates/completion-report.md`

**Interfaces:**
- Consumes: Clean branch, assessment, plan, review findings
- Produces: Open PR, pipeline completion report

- [ ] **Step 1: Write Phase 7 — Create PR**

Document all 6 steps:
1. Commit (format from spec, with `Assisted-by:` trailer)
2. Push branch
3. Create PR (target depends on chain position — stacked PR logic)
4. PR body (reference `templates/pr-body.md`)
5. Post review summary comment (reference `templates/review-summary.md`)
6. Update GitHub issue (link PR, change label to `pipeline/awaiting-merge`)

- [ ] **Step 2: Write Phase 8 — Merge Gate**

Document:
- The skill does NOT wait — immediately moves to next issue
- Merge order (within chains, in order; standalone, any order)
- What the human checks
- How to handle human-requested changes (re-invoke skill)
- How to handle PR rejection mid-chain (close downstream PRs)

- [ ] **Step 3: Write Phase 9 — Cleanup**

Document:
1. Worktree handling (keep for PRs, clean for skipped)
2. Label updates (`pipeline/awaiting-merge`, `pipeline/skipped`, `pipeline/failed`)
3. Log entry for pipeline summary
4. Move-to-next logic (next in chain → branch from current, chain complete → next chain from `main`, all done → completion report)

- [ ] **Step 4: Write completion report section**

Document the final output the skill produces when all issues are processed. Reference `templates/completion-report.md`.

- [ ] **Step 5: Write pr-body.md template**

Create `templates/pr-body.md` with `{{placeholder}}` syntax for all dynamic fields. Must include all sections from the spec: Summary, Closes, Changes, Test plan, Review findings (both plan and implementation), Acknowledged debt, Stack position, Assisted-by.

- [ ] **Step 6: Write review-summary.md template**

Create `templates/review-summary.md` — the PR comment that consolidates all review rounds. Sections: Plan Review findings, Implementation Review findings (per angle), What was fixed, What was deferred, Test results.

- [ ] **Step 7: Write completion-report.md template**

Create `templates/completion-report.md` — the final pipeline output. Sections: PRs Created (by chain), Skipped (with reasons), Failed (with reasons), Merge Instructions.

- [ ] **Step 8: Verify Phases 7-9 completeness**

Check against spec. Verify:
- PR body has all sections from the spec
- Review summary satisfies the CLAUDE.md rule about review comments before merge
- Stacked PR targeting logic is correct
- Merge gate correctly documents the async model
- Cleanup handles all three outcomes (PR created, skipped, failed)
- Completion report matches the spec format
- All templates use consistent `{{placeholder}}` syntax

- [ ] **Step 9: Commit**

```bash
git add skills/issue-pipeline/SKILL.md skills/issue-pipeline/templates/
git commit -m "feat(issue-pipeline): add Phases 7-9 PR, merge gate, cleanup, and templates

Assisted-by: <model name> <noreply@anthropic.com>"
```

---

### Task 7: Failure Handling, Edge Cases, and Constraints

**Files:**
- Modify: `skills/issue-pipeline/SKILL.md`

**Interfaces:**
- Consumes: All phases (failure handling is cross-cutting)
- Produces: Complete skill with all edge cases documented

- [ ] **Step 1: Write Failure Handling section**

Document the per-issue failure table from the spec:
- Issue not clean → skip
- Codebase diverged → skip
- Plan fails review → revise 2×, then chain failure
- Tests fail → retry 2×, then chain failure
- Review findings unfixable → retry 2×, then chain failure
- Excessive complexity → log warning, proceed

Document the chain failure procedure:
1. Stop current chain
2. Comment on failed issue (what, which phase, error output)
3. Mark remaining chain issues as skipped
4. Start next independent chain from `main`

Document cascade risk acknowledgment and mitigations.

- [ ] **Step 2: Write Edge Cases section**

Document all edge cases from the spec as a reference table the agent can consult.

- [ ] **Step 3: Write Constraints and Limits section**

Document:
- Session context limits and re-invocability (detect `pipeline/` branches + `pipeline/awaiting-merge` labels)
- Token budget advisory (reduce reviewers for trivial/small issues)
- Sandbox constraints (if applicable)
- Worktree disk usage
- Merge conflict responsibility

- [ ] **Step 4: Write Re-invocation section**

This is critical for the skill to work across sessions. Document how the skill detects prior state:
- Scan for `pipeline/`-prefixed branches
- Check for `pipeline/awaiting-merge` labels on issues
- Check for `pipeline/in-progress` labels (interrupted previous run)
- Skip issues that are already handled, continue with unprocessed ones

- [ ] **Step 5: Final self-review against spec**

Read the entire spec from top to bottom. For each section, verify the SKILL.md covers it:
- [ ] Design decisions table — all choices reflected in skill behavior
- [ ] Foundation context — dynamic discovery, not hardcoded
- [ ] Phase 0 — all 9 steps
- [ ] Phase 1 — all 7 steps including scope exclusion
- [ ] Phase 2 — all 3 steps
- [ ] Phase 3 — all 4 steps
- [ ] Phase 3.5 — plan review with revision loop
- [ ] Phase 4 — all 6 steps including lint
- [ ] Phase 5 — all 4 review angles
- [ ] Phase 6 — all 5 steps with convergence check
- [ ] Phase 7 — all 6 steps
- [ ] Phase 8 — async merge gate
- [ ] Phase 9 — all 4 steps
- [ ] Completion report format
- [ ] Failure handling — all failure types and chain failure procedure
- [ ] Edge cases — all 8 scenarios
- [ ] Constraints — all 5 limits
- [ ] Re-invocability

Fix any gaps inline.

- [ ] **Step 6: Commit**

```bash
git add skills/issue-pipeline/SKILL.md
git commit -m "feat(issue-pipeline): add failure handling, edge cases, and constraints

Assisted-by: <model name> <noreply@anthropic.com>"
```

---

### Task 8: Integration Test — Dry Run

**Files:**
- No files modified — this is a verification task

**Interfaces:**
- Consumes: Complete skill from Tasks 1-7
- Produces: Verified skill ready for real use

- [ ] **Step 1: Read the complete SKILL.md end-to-end**

Read the entire file. Check for:
- Internal consistency (phase references match, template names match file names)
- No broken cross-references between SKILL.md and companion files
- No placeholder text left behind
- The flow reads naturally for an agent encountering it for the first time

- [ ] **Step 2: Simulate Phase 0 with 3 test issues**

Mentally walk through Phase 0 with a hypothetical input: `#100 #101 #102` where:
- #100 has no dependencies
- #101 says "depends on #100"
- #102 says "blocked by #99" (external, open)

Expected output:
- Chain 1: [#100, #101]
- Skipped: #102 (externally blocked by #99)

Verify the SKILL.md instructions would produce this result.

- [ ] **Step 3: Simulate Phase 1-2 with a trivial issue**

Walk through Phases 1-2 with a hypothetical trivial issue (e.g., "add unit test for function X"). Verify:
- Scope would be correctly estimated as Trivial/Small
- Skill mapping would find relevant test skills
- Assessment comment template would render correctly
- The label would be applied

- [ ] **Step 4: Simulate chain failure scenario**

Walk through a scenario where Phase 4 tests fail after 2 retries. Verify:
- The chain failure procedure is clear
- The issue comment would contain useful information
- Remaining chain issues would be marked as skipped
- The next independent chain would start from `main`

- [ ] **Step 5: Verify re-invocability**

Simulate invoking the skill a second time with the same issue list after a partial first run (3 of 5 issues processed). Verify:
- Phase 0 would detect the 3 existing `pipeline/` PRs
- Those 3 issues would be skipped
- The remaining 2 would be processed normally

- [ ] **Step 6: Final commit with any fixes from dry run**

If the dry run revealed any issues, fix them and commit:

```bash
git add skills/issue-pipeline/
git commit -m "fix(issue-pipeline): address issues found in dry run verification

Assisted-by: <model name> <noreply@anthropic.com>"
```

If no issues found:

```bash
echo "Dry run passed — skill is ready for real use"
```
