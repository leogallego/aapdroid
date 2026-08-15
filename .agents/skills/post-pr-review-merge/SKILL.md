---
name: post-pr-review-merge
description: >-
  After a PR is created, run architecture review and Kotlin code review, fix
  issues iteratively, report deferred/info/warnings, and merge when CI is green
  and non-blocking findings need no action. Use when a PR was just created, or
  when the user asks to review-fix-merge, post-PR review, or finish a PR.
---

# Post-PR Review, Fix, and Merge

Run this skill **after a PR is created** for the current branch.

## Policy (do not paraphrase)

once a PR is created, do the pr architecture review, code review with kotlin skills, and fix any found issues. iterate until no issues are found. once done, report what you deferred, info or warnings with no actions were found. If there are no actions for the deferred, info or warnings, and CI green then merge.

if the issue found is too complex and not related to the PR, the match to existing issue or create a new one for follow-up. if the issue is simple, even if not related to the PR, fix it anyway.

## When to use

- Immediately after creating a pull request
- User says "review-fix-merge", "post-PR review", "finish the PR", or "merge when clean"
- Closing an issue-pipeline / feature branch after PR creation

## Prerequisites

- A PR already exists for the current branch (`gh pr view` succeeds)
- Working tree is clean enough to commit review fixes (or fix commits are expected)

## Workflow

Copy and track:

```
Post-PR Progress:
- [ ] 1. Identify PR
- [ ] 2. Architecture review
- [ ] 3. Kotlin code review
- [ ] 4. Triage: fix simple (even unrelated); defer complex unrelated via issue match/create
- [ ] 5. Re-review until clean of actionable issues
- [ ] 6. Report deferred / info / warnings (deferred must cite issue #)
- [ ] 7. Wait for CI green
- [ ] 8. Merge (only if gate passes)
```

### 1. Identify PR

```bash
gh pr view --json number,title,url,headRefName,baseRefName,statusCheckRollup,mergeable,reviewDecision
```

If no PR exists, stop and create one first (do not invent a merge target).

### 2. Architecture review

Run `git-review` (ai-skills-git) against `docs/architecture/service-contracts.md`
(see `.git-pipeline.yml` → `architecture.contracts`) end-to-end on this PR's diff.

Classify findings:

| Bucket | Source | Action |
|--------|--------|--------|
| **Must-fix** | Contract violations / hard rules | Fix in this PR |
| **Simple (any scope)** | Local, low-risk, quick fix — even if unrelated to the PR | Fix in this PR anyway |
| **Warning** | Soft recommendations that imply drift risk | Fix if simple; else defer via issue |
| **Info** | Observations, skills loaded, non-blocking notes | Report only |
| **Deferred** | Too complex **and** not related to the PR | Match existing issue or create a new follow-up issue; never silent drop |

### Scope triage (every finding)

For each finding, decide:

1. **Simple?** → fix it in this PR **even if unrelated** to the PR.
2. **Too complex and not related to the PR?** → do **not** expand this PR. Search for an existing GitHub issue; if none matches, create a new follow-up issue. Link it from the review report and PR comment.
3. **Complex but caused by / blocking this PR?** → fix in this PR (or stop for human if unblockable).

**Simple** means: localized change, clear correct fix, no architecture redesign, no broad refactor, low regression risk, can be verified quickly.

**Too complex** means: multi-file redesign, unclear correct approach, needs product/architecture decision, or would dominate the PR diff.

### 3. Code review with Kotlin skills

Always read and apply:

- `.agents/skills/kotlin-kmp-code-review/SKILL.md`

Also load (via contracts companion auto-load + this list) any skills matching
changed files — at minimum the core set from
`docs/architecture/service-contracts.md` → Companion skills for review:

- `.agents/skills/kotlin-coroutines-structured-concurrency/SKILL.md`
- `.agents/skills/kotlin-flow-state-event-modeling/SKILL.md`
- `.agents/skills/kotlin-multiplatform-expect-actual/SKILL.md`
- `.agents/skills/android-community/koin-editor.md`

Plus file-matched skills from the contracts Auto-load table /
`docs/reference/skills-reference.md`.

Treat high-risk / security / correctness / architecture-drift findings as **must-fix**. Treat optional follow-up refactors as **deferred**, **info**, or **warning**.

### 4. Fix issues (or file follow-ups)

For every finding, apply scope triage first.

**If simple (related or not):**

1. Apply the minimal justified fix on the PR branch
2. Keep architecture; no broad refactors
3. Run the relevant tests for touched code (`./gradlew --no-daemon …` in sandbox)
4. Commit when the user session already authorized commits for this PR work, or when continuing an explicit review-fix-merge request
5. Push to the PR branch

**If too complex and not related to the PR:**

1. Search for an existing issue (`gh issue list` / `gh search issues`) matching the problem
2. If a match exists: comment on it with the finding context and link the current PR; mark the finding **Deferred** with that issue number
3. If no match: create a new follow-up issue with title, reproduction/context, why it was deferred from this PR, and acceptance criteria; mark **Deferred** with the new issue number
4. Do not leave complex unrelated findings without an issue link

**If complex and related/blocking:** fix in this PR when feasible; otherwise stop for human with a clear blocker.

### 5. Iterate

Re-run steps 2–4 until:

- Architecture review has **zero must-fix** findings
- Kotlin code review has **zero must-fix / high-risk actionable** findings

Stop iterating when only deferred / info / warning remain.

### 6. Report non-blocking findings

Always produce this report before merge consideration:

```markdown
## Post-PR Review Report — PR #NNN

### Actionable issues
None — iteration complete.

### Deferred (complex + unrelated → tracked)
- … → #NNN existing | #NNN created (or "None")

### Info
- … (or "None")

### Warnings
- … (or "None")

### Simple unrelated fixes applied
- … (or "None")

### Actions required for deferred / info / warnings?
- **No** — every deferred item has a linked issue; info/warnings need no work
- **Yes** — list each required action (blocks merge; e.g. deferred without an issue)

### CI
- status: pending | green | red
- failing checks: …

### Merge decision
- MERGE | DO NOT MERGE — <one-line reason>
```

**Plain English:** Tell the user what still needs a human decision (if anything) versus what is already safe to merge.

### 7. CI gate

Poll PR checks until complete (or fail fast on red):

```bash
gh pr checks
# or
gh pr view --json statusCheckRollup
```

CI is **green** only when required checks have succeeded (no failing/pending required checks).

### 8. Merge gate

Merge **only if all** are true:

1. No remaining must-fix / actionable review issues (simple unrelated fixes already landed)
2. Deferred / info / warnings have **no actions** required before merge
3. Every deferred item links an existing or newly created follow-up issue
4. CI is green
5. `mergeable` is not blocked (resolve conflicts first if needed)

Then merge:

```bash
gh pr merge --squash --delete-branch
```

Prefer squash unless the branch already has an intentional clean history the user asked to preserve. If merge requires maintainer approval or branch protection blocks the agent, report the blocker and stop — do not force.

If any deferred/info/warning still has an action, **do not merge**. Leave the PR open and list the actions.

## Severity mapping cheat sheet

| Finding kind | Examples | Merge impact |
|--------------|----------|--------------|
| Must-fix | Layer skip, missing `IXxxRepository`, public `MutableStateFlow`, security leak | Blocks; fix + re-review |
| Simple (any scope) | Typo, missing null check, obvious local naming fix, tiny test gap | Fix in this PR; then re-review |
| Warning | LOC over limit, naming drift, missing tests for new surface | Fix if simple; else defer via issue |
| Info | Skills loaded, style notes, non-impactful observations | Report only |
| Deferred | Complex + unrelated; needs redesign or product decision | Must link existing or new issue; blocks merge until linked |

## Stop conditions

- **Merge** — gates in step 8 all pass
- **Stop for human** — CI red after fixes, branch protection, merge conflict, or deferred/info/warning still needs action
- **Stop for missing PR** — no PR for current branch

## Related skills

- `git-review` (ai-skills-git) + `docs/architecture/service-contracts.md` — contract review
- `.agents/skills/kotlin-kmp-code-review/SKILL.md` — implementation review
- `.agents/skills/kotlin-project-architecture-review/SKILL.md` — escalate only when structural change warrants it
- `docs/reference/skills-reference.md` — skill lookup by changed files
