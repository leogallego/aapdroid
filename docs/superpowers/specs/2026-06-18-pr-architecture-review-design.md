# PR Architecture Review Skill — Design Spec

**Date:** 2026-06-18
**Status:** Superseded (2026-08-14) — live review is `git-review` +
`docs/architecture/service-contracts.md` (see
`docs/superpowers/specs/2026-08-08-git-review-migration.md`). This doc remains
historical design notes for the former `skills/pr-architecture-review/` skill.

## Problem

The Ansible Jane codebase has a clean 6-layer architecture with 17 repository interfaces,
consistent Koin DI, and proper module boundaries. However, these patterns are implicit —
they exist in code but are not formally documented as enforceable contracts. Future Claude
sessions reviewing PRs have no systematic way to verify new code adheres to these patterns.

Additionally, the project has ~20 Kotlin/Android skills in `skills/` but no guidance on
which to load for a given review. Reviewers either load none (missing relevant checks)
or must manually decide (inconsistent coverage).

## Solution

Three deliverables:

### 1. Architecture Service Contracts (`docs/architecture/service-contracts.md`)

Formal documentation of the project's architecture rules, derived from a comprehensive
codebase audit. Covers:

- 6-layer architecture with dependency rules (hard rules, no exceptions)
- Interface contracts (IXxxRepository pattern, Koin binding requirements)
- Module boundaries (shared/composeApp/app isolation)
- State management contracts (StateFlow exposure, UiState sealed classes)
- DI contracts (Koin modules, registration patterns)
- Naming conventions
- File size guidelines with documented exceptions
- Error handling and security contracts

Rules are categorized as **hard rules** (must-fix violations) or **soft guidelines**
(advisory recommendations).

### 2. PR Review Skill (`skills/pr-architecture-review/SKILL.md`)

A skill that Claude sessions invoke when reviewing PRs. The skill:

1. Loads the service contracts as its source of truth
2. Analyzes which files changed and categorizes them by layer/module
3. Always loads a core skill set (coroutines, Flow, KMP expect/actual, Koin)
4. Auto-detects additional skills from changed file patterns (Compose for UI changes,
   data layer skill for repository changes, etc.)
5. Runs hard rule checks against every changed file
6. Runs soft guideline checks for pattern drift
7. Produces structured findings with clear must-fix vs consider categorization

### 3. CLAUDE.md Section

A concise "Kotlin Architecture Contracts" section in the project CLAUDE.md that
summarizes the key rules and points to the full contracts document. Ensures every
Claude session is aware of the contracts without needing to discover them.

## Architecture Decisions

### Why not CI enforcement?

The project's current size (~313 Kotlin files) doesn't justify custom lint rules or
import-graph CI checks. The skill-based review approach is lighter, adapts as the
architecture evolves, and catches semantic violations (not just syntactic ones) that
static analysis would miss.

### Why separate contracts doc instead of inline in CLAUDE.md?

CLAUDE.md is already large. The contracts doc has detailed rationale, examples, and
versioning that would bloat CLAUDE.md. The summary section in CLAUDE.md is sufficient
for awareness; the full doc is loaded by the review skill when needed.

### Why auto-detect skills instead of loading all?

Loading all ~20 skills would overwhelm context. Auto-detection based on changed files
ensures relevant expertise without noise. The core set (coroutines, Flow, KMP, Koin)
covers patterns that affect every PR regardless of domain.

## Audit Findings

The codebase audit found the architecture is already in excellent shape:

- **17 repository interfaces** with consistent IXxxRepository naming
- **Clean 6-layer separation** with no layer skipping
- **1 intentional violation** (SettingsViewModel ModelFetcher) — documented as exception
- **5 files over 400 LOC** — all justified (ChatEngine, ToolRouter, AapApiClient,
  TokenManager, McpServerManager)
- **Consistent Koin DI** with 4 modules and interface bindings
- **Perfect platform isolation** — no Android/JVM imports in commonMain

The contracts codify this existing good state to prevent regression.
