# AGENTS.md

Instructions for AI agents (Cursor, Claude Code, Codex, Gemini CLI, Copilot CLI) working in this repo.

## Project skills (canonical)

**Canonical location:** `.agents/skills/<skill-name>/SKILL.md`

Always load project skills from `.agents/skills/`. Do **not** use the root `skills/` path in instructions, reviews, or new docs.

| Path | Role |
|------|------|
| `.agents/skills/` | Canonical project skills (auto-discovered by Cursor / Codex / Gemini) |
| `skills/` | Compat symlink → `.agents/skills/` only — do not cite this path |

Inventory and when-to-use guidance: `docs/reference/skills-reference.md`  
Sources and licenses: `.agents/skills/README.md`

## Must-load skills for common tasks

| Task | Skill |
|------|-------|
| After creating a PR (review → fix → merge) | `.agents/skills/post-pr-review-merge/SKILL.md` |
| PR architecture / service contracts | `.agents/skills/pr-architecture-review/SKILL.md` |
| Kotlin/KMP implementation review | `.agents/skills/kotlin-kmp-code-review/SKILL.md` |
| Issue end-to-end pipeline | `.agents/skills/issue-pipeline/SKILL.md` |

Before writing or reviewing Android/Kotlin code, read the matching skills under `.agents/skills/` (Compose, coroutines, Flow, Koin, testing, etc.). See the lookup table in `docs/reference/skills-reference.md`.

## Project instructions

- Primary project guide: `CLAUDE.md`
- Architecture contracts: `docs/architecture/service-contracts.md`
- Tool pipeline: `docs/reference/tool-pipeline-architecture.md`

## Skill layout rules

- One skill per directory: `.agents/skills/<name>/SKILL.md`
- Frontmatter `name:` must match the directory name (lowercase kebab-case)
- Add new skills under `.agents/skills/` only
- Do **not** create `.cursor/skills/` or `.claude/skills/` copies or symlinks — Cursor already discovers `.agents/skills/`, and extra discovery roots can duplicate skills in context
- Keep the root `skills/` symlink as compat only; never document it as the canonical path
- Update `.agents/skills/README.md` and `docs/reference/skills-reference.md` when adding a skill

## Adding skills

1. Create or edit under `.agents/skills/<name>/`
2. Update README + skills-reference
3. Leave discovery to `.agents/skills/` (+ `AGENTS.md` / `CLAUDE.md`)
