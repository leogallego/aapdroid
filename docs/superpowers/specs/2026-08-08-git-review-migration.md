# Spec: Migrate to `git-review` (ai-skills-git)

**Status:** Implemented (2026-08-14)  
**Date:** 2026-08-08 (updated 2026-08-14)  
**Repo:** Ansible Jane (`remote-aap` / `leogallego/ansible-jane`)  
**Depends on:** [ai-skills-git#21](https://github.com/leogallego/ai-skills-git/pull/21) (`git-review` merged)  
**Audience:** Implementation session in this repo only

---

## 1. Problem

This repo already treats `docs/architecture/service-contracts.md` as SoT and
keeps a thinner project skill `skills/pr-architecture-review/SKILL.md`
(~268 lines) that still duplicates:

- Review **procedure** now in portable `git-review`
- Layer path table, hard/soft checklists, companion skill auto-load map,
  exceptions (SettingsViewModel, 400 LOC list) that should live in contracts
  (or a thin provider)

There is **no** `.git-pipeline.yml` yet; CLAUDE.md still tells agents to load
`skills/pr-architecture-review/SKILL.md`.

## 2. Goals

1. Keep **`docs/architecture/service-contracts.md`** as SoT; add any missing
   **Layer map** (path/package patterns → layer) currently only in the skill.
2. Move companion skill auto-load table into contracts (Option A preferred;
   no separate `review-provider.md` unless contracts feel too long).
3. Add `.git-pipeline.yml` with `architecture.*` and
   `always_load_review_skills` including `git-review` **and** the four core
   Kotlin skills (required, not optional comments).
4. Remove `skills/pr-architecture-review/`.
5. Update CLAUDE.md / skills README / docs references.
6. Keep Kotlin/Compose companion skills; do not fold them into contracts.

## 3. Non-goals

- Rewriting Jane’s 6-layer architecture
- Replacing `kotlin-project-architecture-review` / `kotlin-project-code-review`
- Deep security audit skill changes
- Editing ai-skills-git or ansible-know-mcp in this session
- Inventing a Jane-specific report format (use `git-review` `report-format.md`)

## 4. Target model

```text
ai-skills-git (installed): git-review
this repo:
  docs/architecture/service-contracts.md   # SoT + Layer map + companions
  .git-pipeline.yml:                       # CREATE
    always_load_review_skills:
      - git-review
      - kotlin-coroutines-structured-concurrency
      - kotlin-flow-state-event-modeling
      - kotlin-multiplatform-expect-actual
    architecture:
      contracts: docs/architecture/service-contracts.md
  (no skills/pr-architecture-review/)
  skills/kotlin-* / compose-* / koin remain as companions
```

Prerequisite: `git-review` via `~/.agents/skills` from ai-skills-git
`./scripts/install-agents.sh`.

`koin-editor` is not a top-level skill directory (lives at
`skills/android-community/koin-editor.md`). Keep it in the contracts **Always**
table by path; agents load it when reading contracts. Do not invent a fake
yaml skill name for it.

---

## 5. Contracts content to absorb from the old skill

Jane’s contracts already cover hard/soft rules well. Close these gaps:

### 5a. Explicit Layer map table (paste into contracts §1)

Full parity with old skill Step 2 (includes `*ApiService.kt` and Model wording):

| Layer | Path / package patterns |
|-------|-------------------------|
| UI | `ui/`, `screen/`, `component/`, Composable functions |
| Presentation | `presentation/`, `*ViewModel.kt` |
| Engine | `assistant/engine/`, `ChatEngine`, `ToolRouter`, `ToolExecutor` |
| Repository | `data/`, `*Repository.kt`, `*Manager.kt` |
| Network | `network/`, `*ApiClient.kt`, `*ApiService.kt`, `mcp/` |
| Platform | `platform/`, `androidMain/`, `jvmMain/`, `desktopMain/` |
| Model | `model/` (domain data classes / sealed classes used across layers) |
| DI | `di/`, `*Module.kt` |
| Tools | `tools/`, `*LocalTool.kt`, `*McpTool.kt` |
| Test | `*Test.kt`, `test/`, `commonTest/`, `desktopTest/` |

When multiple rows match, prefer **Test** for `*Test.kt` / test source sets;
otherwise prefer the most specific package path.

Align wording with §1 Layer Architecture (no contradictions). Unmapped paths →
Info (or skip if non-code).

### 5b. Companion skills (Option A — section in contracts)

Paste into contracts as **Companion skills for review**.

**Always load (core):**

| Skill path |
|------------|
| `.agents/skills/kotlin-coroutines-structured-concurrency/SKILL.md` |
| `.agents/skills/kotlin-flow-state-event-modeling/SKILL.md` |
| `.agents/skills/kotlin-multiplatform-expect-actual/SKILL.md` |
| `.agents/skills/android-community/koin-editor.md` |

Also list the three directory-based cores under `.git-pipeline.yml` →
`always_load_review_skills` (plus `git-review`). See §4 for koin path note.

**Auto-load by changed files:**

| Changed files match | Load |
|---------------------|------|
| `ui/`, `presentation/`, `*Screen.kt` | `.agents/skills/compose-skill/SKILL.md` |
| `ui/`, `*Screen.kt` with state hoisting | `.agents/skills/compose-state-hoisting/SKILL.md` |
| `assistant/engine/`, module boundaries (`shared`↔`composeApp`↔`app`) | `.agents/skills/kotlin-project-architecture-review/SKILL.md` |
| `data/`, `*Repository.kt`, `network/` | `.agents/skills/kotlin-data-kmp-data-layer/SKILL.md` |
| `platform/`, `expect`/`actual` | `.agents/skills/kotlin-kmp-abstraction-decision/SKILL.md` |
| `*Test.kt`, test infrastructure | `.agents/skills/kotlin-testing-kmp/SKILL.md`, `.agents/skills/compose-ui-testing-patterns/SKILL.md` |
| `*ViewModel.kt`, state management | `.agents/skills/kotlin-flow-state-event-modeling/SKILL.md` |
| Navigation, routing | `.agents/skills/kotlin-navigation-compose-multiplatform/SKILL.md` |
| Compose performance concerns | `.agents/skills/compose-recomposition-performance/SKILL.md` |

**Escalation:** if the verdict would be structural / unclear contracts / large
engine or module-boundary change, load `kotlin-project-architecture-review`
even if the auto row did not fire, and prefer verdict **Needs architecture
discussion** (`git-review` report format).

### 5c. Named exceptions

Verify SettingsViewModel / ModelFetcher, ChatEngine, ToolRouter, AapApiClient,
TokenManager, McpServerManager are in contracts § exceptions / file size lists
(already present — confirm parity only).

### 5d. Optional check tip + maintenance

Optional under §1: layer-discipline check tip
(`grep -n "^import"` vs Hard Rules).

Replace “update this skill” with “update `service-contracts.md`; bump version
in contracts”.

Update the contracts header that points at
`skills/pr-architecture-review/SKILL.md` → point at `git-review`.

Report format = portable `git-review` `report-format.md` (must-fix /
consider / Info / Verdict). Do not fork a Jane report template.

---

## 6. Create `.git-pipeline.yml`

```yaml
version: 1
default_branch: main
branch_pattern: "<type>/<n>-<slug>"
merge_method: merge
stack_ci: serial
labels: true
attribution: "Assisted-by: Cursor (Model-Name Model-Version)"
test_commands:
  - "./gradlew --no-daemon testDebugUnitTest :shared:jvmTest :composeApp:desktopTest --stacktrace --continue"
lint_commands: []
always_load_review_skills:
  - git-review
  - kotlin-coroutines-structured-concurrency
  - kotlin-flow-state-event-modeling
  - kotlin-multiplatform-expect-actual
architecture:
  contracts: docs/architecture/service-contracts.md
forge:
  provider: github
```

Cores are **required** always-load (not commented optional).
`test_commands` match CI (`.github/workflows/ci.yml`). No separate lint job in
CI → empty `lint_commands`.

---

## 7. Delete / redirect project skill

**Preferred:** delete `skills/pr-architecture-review/`.

Update:

- `CLAUDE.md` (architecture review paragraph)
- `skills/README.md` / `docs/reference/skills-reference.md`
- Active specs/plans that cite the old skill as the live review tool
- `issue-pipeline` / local pipeline text if it hardcodes the name

Historical design docs for the old skill may keep a short “superseded by
`git-review`” note. Kotlin companion skills stay.

---

## 8. Implementation checklist

Work only under remote-aap / Jane:

- [x] Add Layer map (+ companion table + escalation) to contracts
- [x] Retarget contracts header / maintenance to `git-review`
- [x] Create `.git-pipeline.yml` with `git-review` + cores + `architecture.contracts`
- [x] Delete `skills/pr-architecture-review/`
- [x] Update CLAUDE.md + skills reference docs + issue-pipeline
- [x] Grep for leftover `pr-architecture-review` references
- [x] Sanity: layer map + companions classify a sample Jane path set
- [x] Commit / PR (user asks) — https://github.com/leogallego/ansible-jane/pull/499

Suggested branch: `chore/migrate-git-review`  
Suggested commit theme: migrate Jane architecture review to git-review

---

## 9. Acceptance criteria

1. `.git-pipeline.yml` exists with `git-review`, the three core Kotlin skill
   names, and `architecture.contracts`.
2. Path → layer map is in contracts (full parity table in §5a), not only in a skill.
3. Companion Always + Auto tables + escalation are in contracts.
4. No fat `pr-architecture-review` skill remains.
5. CLAUDE/docs instruct `git-review` + contracts.
6. Kotlin/Compose skills still load via assess / companion table / always-load.
7. Sample review Companions include the four Always cores (koin by path) plus
   any Auto matches; report shape matches `git-review` `report-format.md`.

## 10. Handoff blurb

```text
Implement docs/superpowers/specs/2026-08-08-git-review-migration.md in the
Ansible Jane repo (remote-aap) only. Paste full Layer map + companion tables
from §5 into service-contracts (Option A). Create .git-pipeline.yml with
git-review + required core always-load + architecture.contracts. Delete
skills/pr-architecture-review; update CLAUDE/docs. Keep Kotlin companion
skills. Use git-review report format. Do not edit ai-skills-git or
ansible-know-mcp. Do not commit unless I ask.
```
