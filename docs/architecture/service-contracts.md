# Ansible Jane — Architecture Service Contracts

This document defines the enforceable architecture contracts for the Ansible Jane codebase.
These contracts are checked during PR review (see `.agents/skills/pr-architecture-review/SKILL.md`).

Violations of **hard rules** must be fixed before merge. **Soft guidelines** are advisory
and flagged as recommendations.

---

## 1. Layer Architecture

The codebase follows a strict 6-layer architecture. Each layer may only depend on the
layers below it. No layer skipping is permitted.

```
┌─────────────────────────────────────────────────────┐
│ Layer 6: UI (Composables)                           │
│ Renders state, captures user events                 │
│ May depend on: Presentation, Model                  │
├─────────────────────────────────────────────────────┤
│ Layer 5: Presentation (ViewModels)                  │
│ Manages UI state via StateFlow, orchestrates        │
│ May depend on: Repository, Engine, Model            │
├─────────────────────────────────────────────────────┤
│ Layer 4: Engine (ChatEngine, ToolRouter)             │
│ Business rules, tool orchestration, agentic loop    │
│ May depend on: Repository, Tool definitions, Model  │
├─────────────────────────────────────────────────────┤
│ Layer 3: Repository (Data Access)                   │
│ Data abstraction, caching, source coordination      │
│ May depend on: Network, Platform, Model             │
├─────────────────────────────────────────────────────┤
│ Layer 2: Network (HTTP + MCP)                       │
│ HTTP transport, MCP discovery, API facades          │
│ May depend on: Model, Platform                      │
├─────────────────────────────────────────────────────┤
│ Layer 1: Platform & Models                          │
│ Domain models (accessible to all layers above)      │
│ Platform expect/actual (restricted to Repository+)  │
│ May depend on: Kotlin stdlib only                   │
└─────────────────────────────────────────────────────┘
```

### Hard Rules

- **No layer skipping.** UI must not import from Repository, Network, or Platform
  (domain models in `model/` are accessible to all layers). ViewModels must not import
  from Network. Repositories must not import from Presentation.
- **No upward dependencies.** Network never imports from Presentation or UI.
  Repository never imports from Presentation or UI.
- **Composables never call repository or network methods directly.** All data access
  goes through ViewModel state observation (`collectAsState`).
- **ViewModels never instantiate HTTP clients.** Network access goes through
  repositories, which use `IAapApiProvider`.

### Permitted Exception

`SettingsViewModel` may use `ModelFetcher` directly for dynamic LLM model discovery
from user-provided URLs. This is documented and intentional — creating a repository
for one-shot discovery of external endpoints would be over-engineering.

`ModelFetcher` owns HTTP client creation/teardown internally. ViewModels must not call
`createPlatformHttpClient` or import `network` for this flow.

MCP connection lifecycle is exposed through `IMcpConnectionRepository` (implemented by
`McpServerManager`). Unauthorized session events are exposed through
`IAuthRepository.unauthorizedEvent` (backed by network `AuthEvents`). Presentation and
UI must use those interfaces — not `network.mcp` / `AuthEvents` directly.

Nav/session bootstrap (`AppNavigation`) may observe `IAuthRepository.unauthorizedEvent`
and `ITokenManager` instance flows. That is intentional session wiring, not a general
license for UI to call repositories for feature data.

`IMcpConnectionRepository` intentionally lives in `data/` while
`McpServerManager` implements it from `network.mcp/`. This is an explicit exception to
the §2 same-package guideline so presentation never imports `network`.

---

## 2. Interface Contracts

### Hard Rules

- **Every repository must have an interface.** The interface is named `IXxxRepository`
  and lives in the same package as the implementation. The implementation is named
  `XxxRepository`.

  ```kotlin
  // ✅ Correct
  interface IJobRepository { ... }
  class JobRepository(...) : IJobRepository { ... }

  // ❌ Wrong: no interface
  class JobRepository(...) { ... }
  ```

- **Koin binds to interfaces, never concrete types.** Use `bind IXxxRepository::class`.

  ```kotlin
  // ✅ Correct
  single { JobRepository(get()) } bind IJobRepository::class

  // ❌ Wrong: binds to concrete type
  single { JobRepository(get()) }
  ```

- **API service interfaces are grouped by AAP component.** Three interfaces exist:
  - `AapApiService` — Controller endpoints (`/api/v2/`)
  - `EdaApiService` — EDA endpoints (`/api/eda/v1/`)
  - `PlatformApiService` — Gateway endpoints (`/api/gateway/v1/`)

  New API endpoints must be added to the appropriate existing interface. Do not create
  new API service interfaces without an ADR.

### Tool Contracts

- `Tool` is a **sealed interface** (`shared/.../tools/ToolSpec.kt`). All direct subtypes
  must live in the same package (`assistant.tools`) and module (`shared`). This enables
  exhaustive `when` matching on tool types and catches missing branches at compile time.
- Local tools implement the `LocalTool` interface (which extends `Tool`).
- MCP tools are instances of the `McpTool` concrete class, constructed from MCP server
  discovery data at runtime. You do not subclass `McpTool`.
- `CachedMcpTool` is a lazy-connection variant of `McpTool` that also directly extends `Tool`.
- New local tools must be registered in `AssistantDiModule` with `bind LocalTool::class`.
- Test-only tool stubs use the `@TestOnly` `ToolStub` open class
  (`shared/.../tools/ToolStub.kt`) — a direct subtype of `Tool` in the same package,
  satisfying the sealed constraint while keeping test support out of production paths.

### On-device LLM (#264 / #470)

- On-device inference is an `LlmProvider` implementation (`LocalLlmProvider` via
  `LocalLlmProviderFactory`), not a parallel engine path. ChatEngine continues to own
  the tool loop; LiteRT is used with `automaticToolCalling = false` in PR1.
- Model download, SHA-256 verification, readiness, and AVX2 gating live behind
  `ILocalModelRepository` (Koin binds the interface; fakes implement the same interface).
- LiteRT types stay in `androidMain` / `jvmMain` actuals. `commonMain` / `commonTest`
  must not import LiteRT APIs — use bridge types (`BridgedAssistantMessage`, etc.) and
  repository interfaces for tests.
- **Context window (#470):** Users may raise context from catalog `defaultContextTokens`
  to `maxContextTokens` in 1K steps (Kai-style). The per-model map in
  `IAssistantRepository` is the Settings slider source of truth;
  `LlmProviderConfig.OnDevice.contextTokens` is the engine/ChatEngine authority
  (`0` = catalog default). LiteRT receives `EngineConfig.maxNumTokens`; ChatEngine
  budget uses the same resolved value. Pixel-class guidance: 4K default; 8K–16K OK
  on ≥12 GB RAM; 32K may be tight or slower.

---

## 3. Module Boundaries

### Hard Rules

| Module | May depend on | Must not depend on |
|--------|---------------|--------------------|
| `app/` | `shared`, `composeApp` | — |
| `composeApp/` | `shared` | `app/` |
| `shared/` | — | `app/`, `composeApp/` |
| `baselineprofile/` | `:app` (via `targetProjectPath` + Macrobenchmark) | Product logic, `shared/` / `composeApp/` imports |

- **`:baselineprofile` is Android-only instrumentation.** It is a `com.android.test`
  module that drives the installed `:app` APK through UiAutomator to generate Baseline
  and Startup Profiles. It must not host repositories, tools, ViewModels, or other
  product logic — those stay in `shared/` / `composeApp/` / `app/`.

- **`commonMain` must never import Android or JVM APIs.** No `android.*`, `java.*`,
  or `javax.*` imports in `commonMain` source sets.
- **`expect`/`actual` requires both platforms.** Do not create `expect` declarations
  unless you will implement `actual` in both `androidMain` and `jvmMain`. Android-only
  features go in `app/` without ceremony.
- **Shared logic must not live in `app/`.** If a tool, repository, or engine feature
  is in `app/`, desktop can never reach it. Use `shared/commonMain` for reusable logic.
- **`BackgroundWorker` approval polling is Android-only.** Android schedules
  `ApprovalPollingWorker` via WorkManager in `app/`. The JVM/`desktop` `actual` keeps
  the `schedulePolling` / `cancelPolling` surface for expect/actual parity but is an
  intentional no-op (no desktop polling parity planned).

### Source Set Rules

| Source set | Purpose | Allowed imports |
|------------|---------|-----------------|
| `commonMain` | Cross-platform logic | Kotlin stdlib, Ktor, kotlinx.*, Koin |
| `androidMain` | Android platform implementations | Android SDK, AndroidX |
| `jvmMain` | Desktop platform implementations | JVM stdlib, java.* |
| `desktopMain` | Desktop UI specifics | Compose Desktop APIs |

---

## 4. State Management Contracts

### Hard Rules

- **ViewModels expose `StateFlow<XxxUiState>`, never `MutableStateFlow`.** The mutable
  backing field is private.

  ```kotlin
  // ✅ Correct
  private val _uiState = MutableStateFlow<JobUiState>(JobUiState.Idle)
  val uiState: StateFlow<JobUiState> = _uiState.asStateFlow()

  // ❌ Wrong: exposes mutable state
  val uiState = MutableStateFlow<JobUiState>(JobUiState.Idle)
  ```

- **UiState uses sealed classes/interfaces** with the standard variants:
  - `Idle` — initial state before any action
  - `Loading` — operation in progress
  - `Success` — operation completed, holds result data
  - `Error` — operation failed, holds error message/type

  ```kotlin
  sealed interface JobUiState {
      data object Idle : JobUiState
      data object Loading : JobUiState
      data class Success(val jobs: List<Job>) : JobUiState
      data class Error(val message: String) : JobUiState
  }
  ```

- **One-time events are modeled separately from persistent UiState.** Use `Channel`
  or `SharedFlow` for navigation events, snackbars, toasts — not as states in the
  sealed class.

### Soft Guidelines

- Prefer `stateIn` with `SharingStarted.WhileSubscribed(5000)` for repository-backed
  flows to avoid unnecessary upstream work.
- Use `combine` for composing multiple state sources rather than nested `collect` blocks.

---

## 5. Dependency Injection Contracts

### Hard Rules

- **All DI uses Koin.** No Hilt, no Dagger, no manual service locators.
- **DI modules are organized by layer:**
  - `sharedNetworkModule` — HTTP, API discovery
  - `sharedDataModule` + `sharedRepositoryModule` — Data access, repositories
  - `sharedAssistantModule` — Engine, tools, MCP
  - `presentationModule` — ViewModels
- **ViewModels are injected via `koinViewModel()` in Composables** or `viewModelOf(::XxxViewModel)`
  in module definitions.
- **New repositories, tools, and services must be registered in the appropriate Koin module.**
  Do not instantiate collaborators manually in constructors when Koin can provide them.

### Soft Guidelines

- Prefer constructor injection over field injection.
- Use `single {}` for stateful services (repositories, managers).
- Use `factory {}` for stateless utilities.
- Use `named()` qualifier only when multiple instances of the same type are needed.

---

## 6. Naming Conventions

### Hard Rules

- **Interfaces:** `IXxxRepository`, `IXxxManager` (prefix `I`)
- **Backing fields:** `_prefix` single-underscore for private mutable backing fields
  (standard Kotlin convention, e.g., `private val _uiState`)
- **Files:** `snake_case` for Ansible content, `PascalCase` for Kotlin files
- **Packages:** `lowercase` following Kotlin conventions

### Soft Guidelines

- ViewModels: `XxxViewModel`
- UiState: `XxxUiState`
- Screens: `XxxScreen`
- Composable components: descriptive name matching function purpose
- Local tools: `XxxLocalTool` with tool name as `snake_case` (e.g., `list_job_templates`)

---

## 7. File Size and Complexity Guidelines

### Soft Guidelines

- **400 LOC warning threshold.** Files exceeding 400 lines should be examined for
  single-responsibility violations. Not all large files are problems — some domains
  are inherently complex.

- **Documented exceptions** (currently acceptable):
  - `ChatEngine.kt` (~509 LOC) — agentic loop orchestration is inherently complex
  - `ToolRouter.kt` (~790 LOC) — category matching + scoring + meta-search + MCP/local merging
  - `AapApiClient.kt` (~413 LOC) — REST facade with 50+ endpoint wrappers
  - `TokenManager.kt` (~422 LOC) — token lifecycle (candidate for future extraction)
  - `McpServerManager.kt` (~357 LOC) — MCP connection lifecycle

- **Extraction signals:** A file likely needs splitting when it has:
  - More than 3 distinct responsibilities (e.g., networking + caching + validation + formatting)
  - Multiple groups of private helper methods that don't interact
  - Constructor with more than 6 dependencies

---

## 8. Error Handling Contract

### Hard Rules

- **Use `AppError` sealed class** for domain errors. Do not use raw strings or
  platform exceptions as the error model.
- **Transport errors are normalized at the repository boundary.** Ktor exceptions
  become `AppError` variants before reaching ViewModels.
- **User-facing error messages are derived in the Presentation layer.** Repositories
  return structured errors; ViewModels format them for display.

### Soft Guidelines

- Distinguish retryable vs non-retryable errors where relevant.
- Auth failures (401/403) should trigger token refresh or re-authentication flow,
  not generic error display.

---

## 9. Security Contracts

### Hard Rules

- **No hardcoded URLs, tokens, or credentials.** All secrets go through
  `TokenManager` → DataStore + cryptography-kotlin (AES-256-GCM).
- **HTTPS only.** Enforced via `network_security_config.xml`. Self-signed certs
  are supported via `TlsTrustManager` with explicit user opt-in per instance.
- **Never use `EncryptedSharedPreferences`.** It is deprecated in this project.
  Migration shims in `androidMain` are for legacy data only.
- **Sensitive data stays in minimum layers.** Tokens live in `TokenManager` only.
  ViewModels and UI never hold raw tokens.

---

## 10. Test Infrastructure

### Hard Rules

- **Test placement by module and source set.** Tests go in the source set matching
  their dependency scope:

  | Test type | Source set | Framework |
  |-----------|-----------|-----------|
  | Shared logic (repos, network, engine, tools) | `shared/src/commonTest/` | `kotlin.test` |
  | JVM-specific shared logic (MCP, TLS) | `shared/src/jvmTest/` | `kotlin.test` |
  | ViewModel tests | `composeApp/src/commonTest/` | `kotlin.test` |
  | Shared-layer integration tests (backup, serialization) | `composeApp/src/commonTest/` | `kotlin.test` |
  | Compose Desktop screen smoke tests | `composeApp/src/desktopTest/` | `kotlin.test` + Compose MP test API |
  | Android-only features (AppFunctions, widgets, WorkManager) | `app/src/test/` | JUnit4 |
  | Baseline / Startup Profile generators (Macrobenchmark) | `baselineprofile/` (`com.android.test`) | JUnit4 + `BaselineProfileRule` |

- **`kotlin.test` in all KMP-compatible source sets.** `commonTest`, `jvmTest`, and
  `desktopTest` must use `kotlin.test` annotations (`@Test`, `@BeforeTest`, `@AfterTest`).
  JUnit4 annotations (`@Before`, `@After`, `@Rule`, `@RunWith`) are prohibited in these
  source sets — they break desktop compilation.

  ```kotlin
  // ✅ Correct — commonTest / jvmTest / desktopTest
  import kotlin.test.Test
  import kotlin.test.BeforeTest
  import kotlin.test.assertEquals

  // ❌ Wrong — JUnit4 in KMP source sets
  import org.junit.Test
  import org.junit.Before
  import org.junit.Rule
  ```

- **JUnit4 is restricted to Android-only test surfaces.** Allowed locations:
  - `app/src/test/` — Robolectric, Android Compose test rules (`createComposeRule()`),
    or other Android-specific unit/instrumentation helpers
  - `baselineprofile/` — Macrobenchmark Baseline/Startup Profile generators
    (`BaselineProfileRule` requires JUnit4 `@Rule` / `@RunWith`)

  Do not use JUnit4 in KMP-compatible source sets (`commonTest`, `jvmTest`,
  `desktopTest`).

- **MainDispatcher setup follows the source set.** KMP-compatible tests use the
  `setupMainDispatcher()` / `tearDownMainDispatcher()` utility functions in `@BeforeTest` /
  `@AfterTest`. The `MainDispatcherRule` JUnit4 `TestRule` is only for `app/src/test/`.

  ```kotlin
  // ✅ Correct — commonTest ViewModel test
  @BeforeTest
  fun setUp() {
      setupMainDispatcher()
  }

  @AfterTest
  fun tearDown() {
      tearDownMainDispatcher()
  }

  // ❌ Wrong — JUnit4 rule in commonTest
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()
  ```

- **Fakes implement interfaces.** Every fake must implement the corresponding
  `IXxxRepository` or `IXxxManager` interface. Fakes that bypass the interface
  contract cannot catch interface-breaking changes.

  ```kotlin
  // ✅ Correct
  class FakeJobRepository : IJobRepository { ... }

  // ❌ Wrong — no interface
  class FakeJobRepository { ... }
  ```

- **Fakes live alongside their consumers.** Fakes in `app/src/test/fakes/` serve
  `app/` tests. Fakes in `composeApp/src/commonTest/fakes/` serve `composeApp/` tests.
  Cross-module fake sharing is not possible due to Gradle module boundaries — duplication
  is accepted until `testFixtures` consolidation is evaluated.

- **No test classes in production source sets** except `ToolStub`. The
  `@TestOnly`-annotated `ToolStub` class in `shared/commonMain` is the sole class-level
  exception — it satisfies the `Tool` sealed interface constraint while keeping test
  support accessible to all modules. Individual `@TestOnly`-annotated methods on
  production classes (e.g., `ToolRouter.setToolEnabled()`) are permitted when they
  expose test-only configuration that cannot be achieved through constructor injection.

### Soft Guidelines

- Prefer a `TestData` object with factory methods (`TestData.createInventory()`,
  `TestData.createHost()`) over inline data construction. Centralized factories reduce
  duplication and make test data consistent.
- Use a `testKoinModule()` factory function (or equivalent) for wiring fakes into
  Koin in test setup. Default parameters for all fakes keep individual tests concise.
- When migrating tests from `app/` to `composeApp/commonTest/`, verify they compile
  on both Android and Desktop targets before merging.
- Aim for test names that describe the scenario and expected outcome:
  `loadTemplates_withNetworkError_showsErrorState`.

---

## 11. String Resources

### Hard Rules

- **No hardcoded user-facing strings in Composables.** All UI text must use
  `stringResource()` from Compose Multiplatform resources
  (`composeApp/src/commonMain/composeResources/values/strings.xml`).
  Hardcoded strings in `Text()`, `placeholder`, `label`, `contentDescription`,
  and similar parameters are prohibited.

- **Exceptions:** log messages, debug output, and test assertions may use
  hardcoded strings.

---

## Versioning and Maintenance

This document follows the project's semver scheme. Updates require a PR with
rationale for the change. The PR review skill checks against the version in `main`.

### Lifecycle tags

Rules that describe transitional state use parenthetical tags with issue references:

- `(planned: #NNN)` — the rule describes infrastructure that does not exist yet.
  When the referenced issue is resolved, remove the tag and verify the rule is accurate.
- `(deprecated: #NNN)` — the rule describes a pattern being phased out.
  When the referenced issue is resolved, delete the rule entirely.

**Tags must be updated in the same PR that resolves the referenced issue, or in an
immediate follow-up.** Stale tags are treated as contract violations during PR review.
When a deprecated rule's migration is complete, delete the row — do not leave
tombstones or "removed" markers.

| Version | Date | Change |
|---------|------|--------|
| 1.0.0 | 2026-06-18 | Initial contracts derived from codebase audit |
| 1.1.0 | 2026-06-20 | Tool interface sealed (#364), test helpers documented |
| 1.2.0 | 2026-06-21 | Add §10 Test Infrastructure contracts (#392) |
| 1.2.1 | 2026-06-22 | Add lifecycle tags for transitional rules (#392) |
| 1.3.0 | 2026-06-26 | Add §11 String Resources contract (#293) |
| 1.3.1 | 2026-08-05 | Document Android-only BackgroundWorker approval polling (#433) |
| 1.3.2 | 2026-08-06 | Document `:baselineprofile` module + JUnit4 Macrobenchmark exception (#214) |
| 1.3.3 | 2026-08-08 | Document on-device LLM behind `LlmProvider` + `ILocalModelRepository` (#264) |
