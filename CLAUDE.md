# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Ansible Jane is a multiplatform app (Android and Desktop) for managing Ansible Automation Platform (AAP). Users authenticate with their AAP instance, browse job templates, launch playbooks, monitor job status, handle workflow approvals, and interact with Jane, an AI assistant that works with local models (Ollama) or frontier providers (OpenAI-compatible, Gemini, OpenRouter) through tool-use and MCP.

Full specification is in `idea.md`.

## Tech Stack (Strictly Enforced)

- **Language:** Kotlin Multiplatform. No Java.
- **UI:** Compose Multiplatform with Material 3 (Material You). No XML layouts, no Fragments.
- **Architecture:** MVVM with Unidirectional Data Flow. ViewModels expose UI state via `StateFlow`.
- **AI Engine:** [Koog](https://github.com/JetBrains/koog) (JetBrains AI Agent Framework) for LLM prompting and streaming.
- **Networking:** Ktor + Kotlin Serialization + Coroutines.
- **DI:** Koin (not Hilt).
- **Security:** DataStore + cryptography-kotlin (AES-256-GCM). Android uses Keystore backing. Desktop uses PKCS12 at `~/.ansiblejane/`. HTTPS only via `network_security_config.xml`. `EncryptedSharedPreferences` is deprecated — do not use. Tink kept in androidMain for migration only.

## AAP Requirements

- **Minimum version:** AAP 2.5+ with Gateway
- All API access goes through the AAP Gateway, which proxies to Controller, EDA Controller, and Platform services
- Controller endpoints use `/api/v2/` base path
- EDA endpoints use `/api/eda/v1/` base path
- Platform/Gateway endpoints use `/api/gateway/v1/` base path

## AAP API Endpoints

All authenticated requests use header: `Authorization: Bearer <TOKEN>`

| Feature | Method | Endpoint |
|---------|--------|----------|
| Validate credentials | GET | `/api/v2/me/` |
| Get templates | GET | `/api/v2/job_templates/` |
| Launch job | POST | `/api/v2/job_templates/{id}/launch/` |
| Job status | GET | `/api/v2/jobs/{id}/` |
| Launch workflow | POST | `/api/v2/workflow_job_templates/{id}/launch/` |
| Workflow job status | GET | `/api/v2/workflow_jobs/{id}/` |
| Workflow approvals | GET | `/api/v2/workflow_approvals/` |
| Approve workflow | POST | `/api/v2/workflow_approvals/{id}/approve/` |
| Deny workflow | POST | `/api/v2/workflow_approvals/{id}/deny/` |
| Get token | POST | `/api/v2/tokens/` |
| EDA activations | GET | `/api/eda/v1/activations/` |
| Platform users | GET | `/api/gateway/v1/users/` |
| Platform services | GET | `/api/gateway/v1/services/` |

Three separate Ktor API service interfaces: `AapApiService` (Controller), `EdaApiService` (EDA), `PlatformApiService` (Gateway).

## Architecture Layers

### Multi-Module Structure

- **shared module (`shared/`):** KMP library -- platform abstractions (`platform/`), networking (Ktor `network/`), data layer (repositories, DataStore, encryption), AI tools and engine. Source sets: `commonMain`, `androidMain`, `jvmMain` (Desktop).
- **composeApp module (`composeApp/`):** Compose Multiplatform UI -- navigation, screens (Dashboard, Templates, Activity, Infrastructure, EDA, Chat), themes, components. Source sets: `commonMain`, `androidMain`, `desktopMain`.
- **app module (`app/`):** Android shell -- `MainActivity`, notification channels, WorkManager (`ApprovalPollingWorker`), deep links, and Android Koin platform wiring. Depends on `:shared` and `:composeApp`. UI/settings live in `composeApp/`.

### Where to Put New Code (Android-first, Desktop stretch goal)

| Target module | When to use | Examples |
|---------------|-------------|----------|
| `shared/commonMain` | Pure logic, no platform APIs | New tools, repository methods, data models, engine features |
| `composeApp/commonMain` | UI that should work on all platforms | New screens, components, ViewModels |
| `app/` | Android-only features, no KMP abstraction needed | AppFunctions, widgets, notification channels, WorkManager, Wear OS |
| `composeApp/androidMain` | UI that uses Android-specific APIs | Camera, biometrics, platform-specific pickers |

Rules:
- **Don't create `expect/actual` unless you plan a desktop implementation.** If a feature is Android-only, put it in `app/` — no ceremony needed.
- **Don't put shared logic in `app/`.** If a tool, repository, or engine feature lives in `app/`, desktop can never reach it.
- Android-only screens plug into the shared nav graph via composable lambda injection (see `assistantContent` pattern in `AppNavigation`).

### Layer Responsibilities

- **Network Layer:** Ktor `HttpClient` with engine abstraction (`HttpEngine` expect/actual), auth interceptor, self-signed cert support via `TlsTrustManager`. Instance discovery via `InstanceDiscovery` detects platform type (AAP/AWX/Jewel) and component versions.
- **Data Layer:** `TokenManager` (DataStore + cryptography-kotlin encryption), repositories with `IRepository` interfaces in shared, `AssistantRepository` for LLM config persistence.
- **Presentation:** ViewModels with `StateFlow<UiState>` (Idle, Loading, Success, Error pattern) in `composeApp`.
- **Platform abstractions (`shared/.../platform/`):** `expect`/`actual` classes for `SecureKeyStorage`, `DataStoreFactory`, `ConnectivityObserver`, `BackgroundWorker`, `PlatformUtils`, `NotificationManager`, `TlsTrustManager`, `HttpEngine`. Approval polling via `BackgroundWorker` is Android-only (WorkManager in `app/`); the desktop `actual` is an intentional no-op stub.

## Kotlin Architecture Contracts

Formal contracts are documented in `docs/architecture/service-contracts.md`. Key rules enforced during PR review:

- **Layer discipline**: UI → Presentation → Engine → Repository → Network → Platform. No skipping.
- **Interface requirement**: Every repository must have an `IXxxRepository` interface. Koin binds to interfaces, never concrete types.
- **Module isolation**: `shared/` has zero dependencies on `app/` or `composeApp/`. `commonMain` never imports Android/JVM APIs.
- **State exposure**: ViewModels expose `StateFlow<XxxUiState>`, never `MutableStateFlow`. UiState uses sealed classes with `Idle`/`Loading`/`Success`/`Error`.
- **Tool contracts**: Local tools implement the `LocalTool` interface. MCP tools are instances of the `McpTool` class. Both satisfy the `Tool` interface.

When reviewing PRs, load the `skills/pr-architecture-review/SKILL.md` skill to check changes against these contracts. It auto-loads relevant Kotlin/Android skills based on which files changed.

## AI Assistant Architecture

The AI assistant provides natural-language interaction with AAP via tool-use LLMs. Module placement after #243:

| Concern | Module | Package |
|---------|--------|---------|
| Engine, LLM providers, tools, DI | `shared/` | `assistant/engine`, `assistant/llm`, `assistant/tools`, `assistant/di` |
| UI + ViewModels | `composeApp/` | `assistant/ui`, `assistant/presentation` |
| Android shell only | `app/` | notifications, WorkManager approval polling, deep links |

Full pipeline flow with component responsibilities is documented in `docs/reference/tool-pipeline-architecture.md`.

### Tool System

Two tool sources, unified via `Tool` interface (`shared/.../tools/ToolSpec.kt`):

- **Local tools** (`shared/.../tools/local/`) — 61 tools that call AAP APIs directly via Ktor. Zero latency, no MCP server required. Covers jobs, inventories, hosts, projects, credentials, EDA, schedules, workflow approvals, platform config, and more. Examples: `list_job_templates`, `launch_job`, `get_host_facts`, `approve_workflow`, `list_platform_services`, `ping`.
- **MCP tools** (`shared/.../tools/McpTool.kt`) — dynamically discovered from connected MCP servers. Each `McpTool` carries an optional `toolset` field for category-based routing. `McpServerConfig` supports per-toolset endpoints (e.g., `/job_management/mcp`) to reduce tool count per connection.

### ToolRouter (`shared/.../engine/ToolRouter.kt`)

Category-based query routing that selects relevant tools per user message:

- **8 categories**: INVENTORY, JOBS, MONITORING, USERS, SECURITY, CONFIGURATION, EDA, PLATFORM — each with keyword sets, resource prefixes, and local tool names.
- **Toolset-to-category mapping** (`TOOLSET_CATEGORY_MAP`): maps MCP server toolset names (e.g., `job_management`, `inventory_management`) to categories for routing. MCP tools with a known toolset use category matching instead of prefix matching.
- **Query matching**: splits user message into words, matches against category keywords, returns tools from matched categories only.
- **Ranking**: scores tools by keyword overlap with query, boosts `list_`/`ping` tools, penalizes destructive tools.
- **Overlap mapping** (`OVERLAP_MAPPING`): when a local tool exists, its MCP equivalent is auto-disabled to avoid duplicates.
- **Read-only enforcement**: `McpServerConfig.readOnly` filters out write actions (`_create`, `_update`, `_delete`, `_launch`, etc.) from read-only MCP servers.
- **Per-tool enable/disable**: `setToolEnabled()` / `isToolEnabled()` for user-facing tool management UI.

### Engine Pipeline (`shared/.../engine/`)

- **ChatEngine** — agentic loop: sends user message + tool schemas to LLM, executes tool calls, re-sends results until LLM produces a text response. Max 10 iterations.
- **ToolExecutor** — executes tool calls with 30s timeout, 2-minute result cache, array capping (max 10 items), and smart truncation (8K char limit).
- **Token optimization** — 3-tier token saving mode (Standard / Token Saver / Tools Only) controls schema detail, tool count caps, and conversation compaction.

## AI Agent Skills

The `skills/` directory contains SKILL.md reference files for Android/Kotlin development. Read the relevant skill file before writing or reviewing code in that area. The full inventory with when-to-use guidance is in `docs/reference/skills-reference.md`.

Quick lookup:
- **Compose UI/state/layout** — `compose-skill/`, `compose-state-*`, `compose-modifier-and-layout-style/`
- **Compose performance** — `compose-recomposition-performance/`, `compose-stability-diagnostics/`, `compose-state-deferred-reads/`
- **Compose animations** — `compose-animations/`
- **Compose side effects** — `compose-side-effects/`
- **Compose testing** — `compose-ui-testing-patterns/`, `android-community/android-unit-test-editor.md`
- **Coroutines/Flow** — `kotlin-coroutines-structured-concurrency/`, `kotlin-flow-state-event-modeling/`
- **Koin DI** — `android-community/koin-editor.md`
- **KMP** — `kotlin-multiplatform-expect-actual/`, `kotlin-types-value-class/`
- **Navigation** — `android-official/navigation-3.md`, `android-official/edge-to-edge.md`
- **Gradle** — `android-community/gradle-configuration.md`

See `skills/README.md` for sources and licenses.

## Development Rules

- **MUST** create all temporary files and directories inside the project directory (e.g., `.tmp/`). NEVER use `/tmp`, `$TMPDIR`, or any system temp directory. Clean up temp files when done.

## Sandbox & Gradle Rules

- **MUST** use `--no-daemon` on ALL Gradle commands when running inside the Claude Code sandbox. The sandbox uses a PID namespace — Gradle daemons acquire `~/.gradle/caches/*.lock` files with sandbox PIDs that become stale and unreleasable from either side, blocking all subsequent Gradle operations. Fix requires manual host-side cleanup: `find ~/.gradle -name "*.lock" -type f -delete`.
- **NEVER** launch long-running Gradle processes (e.g., `composeApp:run`) from the sandbox without `--no-daemon`. Prefer asking the user to run the app from their terminal instead.
- If Gradle fails with `LockTimeoutException` referencing a PID that doesn't exist, the locks are stale from a previous sandbox session. Ask the user to run: `find ~/.gradle -name "*.lock" -type f -delete`

## Reference Projects

- **Kai 9000** — source code is available at `tmp/Kai/`. When the user refers to "Kai", look at this directory for architecture, UI patterns, and implementation reference. It's a KMP project; main code is in `tmp/Kai/composeApp/src/commonMain/`.

## Android Device Testing

- **MUST** invoke the `android-cli` skill (via Skill tool) before ANY emulator or device interaction — deploying, starting/stopping emulators, capturing screenshots, inspecting layouts, or running apps. Never use `adb` directly for tasks the `android` CLI covers. If the skill is not loaded yet in this session, load it before proceeding.
- **MUST** use the `android` CLI (`android run`, `android emulator`, `android screen capture`, `android layout`) for all device interaction. Prefer `android layout` over screenshots for inspecting UI state.
- Use `uiautomator dump` to find elements by `resource-id` (from Compose `testTag`). Never hard-code pixel coordinates — always resolve element positions from the layout tree or resource-ids.
- All Compose screens **MUST** have `testTag` modifiers on interactive elements (fields, buttons, switches) using the convention `field_<name>`, `button_<name>`, `switch_<name>`. The root `AppNavigation` receives a modifier with `testTagsAsResourceId = true` from Android's `MainActivity` so tags appear as `resource-id` in uiautomator.
- Use `adb logcat` to verify network requests and errors instead of relying on screenshots.

## Security Rules

- Never hardcode URLs or tokens
- Only use DataStore + cryptography-kotlin for credentials — never `EncryptedSharedPreferences` (deprecated), plain `SharedPreferences`, or SQLite
- Enforce HTTPS-only via network security config
