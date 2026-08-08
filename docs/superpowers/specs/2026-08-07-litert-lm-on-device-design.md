# On-device LLM via LiteRT-LM — Design (#264)

**Date:** 2026-08-07  
**Issue:** [#264](https://github.com/leogallego/ansible-jane/issues/264)  
**Status:** Approved for implementation planning  
**Prerequisites (done):** [#330](https://github.com/leogallego/ansible-jane/issues/330) schema compression (PR #455), [#453](https://github.com/leogallego/ansible-jane/issues/453) model-capability tiers (PR #457)

## Summary

Add on-device inference with [LiteRT-LM](https://developers.google.com/edge/litert-lm) v0.15.0 (Gemma 4 E4B / 12B) so Jane can run offline with tool calling, no API key or network required for the LLM itself (AAP API calls still need connectivity when tools hit the controller).

Delivery is **one design, two sequential PRs**:

| PR | Scope |
|----|--------|
| **PR1 — E4B MVP** | Gradle deps, platform resources, model download + SHA-256, engine lifecycle, `LocalLlmProvider` sync/manual tool path via existing `LlmProvider` + ChatEngine, Settings UI, `onDevice=true` → `Simple` (E4B **and** 12B use the same Simple policy in PR1) |
| **PR2 — Dual-path + 12B** | Async streaming + auto tool calling for read-only, `OpenApiTool` adapters, path switch on destructive tools, **extend #453** so 12B is not forced into Simple’s no-MCP / TOOLS_ONLY ceiling |

A second agent may own PR2 **after** PR1 interfaces land; do not parallelize both from day one.

## Goals

- Offline assistant with local tool calling on Android and Desktop (JVM)
- Preserve ChatEngine confirmation, repeat detection, and `ToolExecutor` caching/capping on the sync path
- Reuse #330 stripped schemas and #453 Simple-tier filtering (`onDevice = true`)
- On-demand model download with integrity checks and basic device guidance

## Non-goals

- Fine-tuned Gemma on Jane schemas
- NPU backend (`Backend.NPU()`)
- `@Tool` annotation migration (stay on OpenAPI JSON / `OpenApiTool`)
- Lazy Minimal two-call schema loading (#330 deferred)
- #450 result compression (optional helper, not required)

## Architecture decision

**Approach: adapter behind existing `LlmProvider` (same pattern as Koog Phase 1).**

- `LocalLlmProvider` implements `generateStream(prompt, tools, maxTokens): Flow<StreamFrame>`
- ChatEngine API stays unchanged
- PR1 always uses LiteRT `sendMessage()` with `automaticToolCalling = false` and synthesizes `StreamFrame`s
- PR2 adds an internal branch for async auto-tool calling when no destructive tools are present

Rejected alternatives:

1. **Kai-style LiteRT-owned tool loop from day one** — bypasses ChatEngine; duplicates confirmation/caching; fights layer contracts  
2. **Widen `LlmProvider` with sync/capability flags** — touches every provider + ChatEngine before E4B is proven  

## Contract compliance

Checked against `docs/architecture/service-contracts.md` and `skills/pr-architecture-review/SKILL.md`.

| Rule | How this design complies |
|------|---------------------------|
| Layer discipline | UI → VM → `ILocalModelRepository` / `LlmProvider`; ChatEngine never imports LiteRT |
| Module / `commonMain` | LiteRT only in `shared/androidMain` + `shared/jvmMain`; common holds interfaces + catalog data |
| `expect`/`actual` both platforms | `DeviceResources` (and provider factory) implemented for Android and JVM |
| Repository interfaces | `ILocalModelRepository` + Koin `bind` |
| Platform not used from UI | RAM/disk/AVX/dirs behind repository |
| Security “no hardcoded URLs” | Public HuggingFace **artifact catalog** (pinned commit + SHA-256) — not secrets; documented exception |
| Android-only FG download UX | Notification/service in `app/` or `androidMain`; desktop no-op |
| Strings / UiState | `stringResource`; sealed download state |
| Tests | `kotlin.test` in KMP source sets; fakes implement interfaces |

Optional follow-up in the implementing PR: add a short § note to `service-contracts.md` for on-device LLM placement (`planned: #264` → resolved).

## Module & layer layout

```
UI (AgentTab — local provider card, download progress)
  → Presentation (AssistantViewModel, SettingsViewModel)
    → Engine (ChatEngine, ToolRouter)          // unchanged public API
    → ILocalModelRepository                    // download, paths, readiness
    → LlmProvider (LocalLlmProvider)           // VM creates; ChatEngine consumes
         ↓ actual androidMain / jvmMain
    LiteRT Engine / Conversation
  → Platform expect/actual (RAM, disk, AVX2, model dirs)
```

### Key types & files (intended)

| Concern | Location |
|---------|----------|
| `LlmProvider` (unchanged) | `shared/.../llm/LlmProvider.kt` |
| Local provider factory / expect | `shared/commonMain/.../llm/` |
| LiteRT actual + StreamFrame bridge | `shared/androidMain` + `shared/jvmMain` |
| `ILocalModelRepository` / `LocalModelRepository` | `shared/.../assistant/local/` |
| `LocalModelCatalog` | `shared/commonMain/.../assistant/local/` — pure data |
| `DeviceResources` expect/actual | `shared/.../platform/` |
| `LlmProviderConfig.OnDevice` + `KnownProvider.LOCAL` | `AssistantConfig.kt` / `LlmProviderDefinitions.kt` |
| DI | `sharedAssistantModule` + platform modules |
| Download notification (Android) | `app/` or `shared/androidMain` |

Dependency (Gradle):

```text
com.google.ai.edge.litertlm:litertlm-android:0.15.0
com.google.ai.edge.litertlm:litertlm-jvm:0.15.0
```

## Model management

### Catalog

Two models only (E2B dropped — unreliable for AAP function calling):

| Tier | Model | Role |
|------|-------|------|
| Medium (minimum) | Gemma 4 E4B IT | Default / MVP |
| Large (recommended) | Gemma 4 12B IT | PR2 MCP + richer agentic |

Each catalog entry includes: `id`, display name, file name, `sizeBytes`, **pinned HF commit URL**, **SHA-256**, `gpuMemoryMb`, `defaultContextTokens`, `maxContextTokens`, `kvPerTokenBytes`, and `onDeviceTier` (`E4B` | `LARGE`).

GPU memory estimate (Kai / issue formula):

```text
estimateGpuMemoryMb = modelFileMb + gpuMemoryMb
  + max(0, contextTokens - defaultContextTokens) * kvPerTokenBytes / 1MiB
DevicePerformance: GOOD ≥ 2.5× RAM headroom, OK ≥ 1.85×, else POOR
```

### Repository API

Download progress is **transient**; on-disk readiness is queried separately (do not overload one sealed type for both).

```kotlin
interface ILocalModelRepository {
    val downloadState: StateFlow<LocalModelDownloadState>
    fun catalog(): List<LocalModel>
    fun isReady(modelId: String): Boolean
    fun modelPath(modelId: String): String?
    suspend fun download(modelId: String)
    fun cancelDownload()
    suspend fun delete(modelId: String)
    fun devicePerformance(modelId: String, contextTokens: Int): DevicePerformance
    fun hasAvx2Support(): Boolean
}

/** Transient download machine. After success or idle, use [ILocalModelRepository.isReady]. */
sealed interface LocalModelDownloadState {
    data object Idle : LocalModelDownloadState
    data class Downloading(val modelId: String, val bytesReceived: Long, val totalBytes: Long) : LocalModelDownloadState
    data class Succeeded(val modelId: String) : LocalModelDownloadState  // then typically return to Idle
    data class Error(val modelId: String, val message: String) : LocalModelDownloadState
}

enum class DevicePerformance { GOOD, OK, POOR }
```

### Platform (`DeviceResources`)

- Total RAM, free disk  
- Storage: Android `filesDir/litert_models/`; Desktop `~/.ansiblejane/litert_models/`  
- AVX2: Linux x86_64 via `/proc/cpuinfo`; non-x86 → allow; Android → always true  

### Download rules

- Require free space ≥ `sizeBytes + 500 MB` before start  
- Streaming SHA-256; mismatch → delete partial file + `Error`  
- Progress exposed on `downloadState`; support `cancelDownload()`  
- Android may show a foreground download notification; desktop has no notification parity  

### Config & capability

```kotlin
@Serializable
@SerialName("on_device")
data class OnDevice(
    val modelId: String,
    override val tokenSavingMode: TokenSavingMode = TokenSavingMode.TOOLS_ONLY,
) : LlmProviderConfig
```

- `KnownProvider.LOCAL` — no API key, URL not editable. `AssistantViewModel` must branch on `OnDevice` **before** `KnownProvider.fromUrl` (OnDevice has no URL).  
- Register the new polymorphic serializer subtype so existing `OpenAiCompatible` configs keep deserializing.  
- **PR1:** `resolve(..., onDevice = true)` → always `Simple` for every on-device model (including 12B if downloaded). Matches #453 today.  
- **PR2 must extend #453** — see [12B policy vs Simple](#12b-policy-vs-453-simple). Until that lands, 12B must not claim MCP / TOKEN_SAVER behavior.  
- Device tier UI: show GOOD/OK/POOR; **block download only on insufficient disk**; POOR is a warning  
- **Context budget:** when active provider is OnDevice, set ChatEngine `contextChars` from the user-selected on-device context (#470), falling back to catalog `defaultContextTokens` when unset (E4B → 4096, 12B → 8192). Settings exposes a Kai-style slider (`defaultContextTokens`…`maxContextTokens`, 1K steps); LiteRT `EngineConfig.maxNumTokens` uses the same selection. Do not leave cloud TOOLS_ONLY’s 4K budget as the only knob once LARGE is active in PR2.

## PR1 — Inference bridge (sync / manual)

### Engine lifecycle

1. Resolve path via `ILocalModelRepository.modelPath`  
2. Initialize LiteRT `Engine`: try `Backend.GPU()`, catch → `Backend.CPU()`  
3. On GPU, enable MTP when the API is available (`ExperimentalFlags.enableSpeculativeDecoding = true`)  
4. Recreate `Conversation` per turn (Kai pattern) with system instruction + history  
5. Idle release after 5 minutes; 750 ms GPU drain between model swaps  
6. `modelInfo()` → `ModelInfo(name = modelId, isLocal = true)`  
7. `isAvailable()` → model file ready and (desktop) AVX2 OK  
8. Expose lightweight engine phase for Settings/Assistant (`Uninitialized` / `Loading` / `Ready` / `Error`) via `StateFlow` on the provider or repository — `isAvailable()` alone is not enough for “Loading model…” UI  

### Tool mode (PR1 only)

```text
automaticToolCalling = false
sendMessage(userText) on Dispatchers.IO
→ Message with text and/or toolCalls
→ emit synthetic StreamFrames, then End
```

| LiteRT result | Emitted `StreamFrame`s |
|---------------|-------------------------|
| Text | `TextDelta` (full text once — no token streaming in PR1) |
| `toolCalls[]` | one `ToolCallComplete` per call (`id`, `name`, args JSON) |
| Done | `StreamFrame.End` (token meta optional / null) |

ChatEngine then owns: destructive confirmation → `ToolExecutor` → tool result message → next iteration. The #453 parse-failure retry (once without tools) still applies.

### Prompt / schema bridge

- Map Koog `Prompt` → LiteRT messages (system → `systemInstruction`; user/assistant history; tool results flattened Kai-style)  
- Run `sanitizeForLiteRt` on all strings (strip UTF-16 surrogates — JNI/UTF-8 crash avoidance)  
- Register tool schemas on the conversation so the model can emit structured calls, but do **not** run Jane tools inside LiteRT in PR1 — ChatEngine executes  
- **Prototype risk:** confirm whether LiteRT accepts schema-only / no-op `OpenApiTool` entries when `automaticToolCalling = false`, or whether descriptions must be supplied another way. Spike early in PR1; if execute stubs are required, stubs must throw if invoked (should be unreachable with auto-calling off)  
- Schemas are already #330-stripped upstream when mode/capability demand it  

### ViewModel wiring

```kotlin
when (config) {
    is LlmProviderConfig.OnDevice ->
        LocalLlmProviderFactory.create(config, localModelRepository)
    is LlmProviderConfig.OpenAiCompatible ->
        /* existing Gemini / Koog path */
}

ModelCapabilityResolver.resolve(
    provider = KnownProvider.LOCAL, // or from config
    model = config.modelId,
    onDevice = true,
)
```

### Errors

Map timeout / OOM / engine failures to existing LLM exceptions only (`LlmTimeoutException`, `LlmServerException`). Do not introduce a new exception type in PR1. Do not leak raw platform exceptions past the provider boundary.

## PR2 — Dual-path + 12B policy

### Path selection (inside `LocalLlmProvider`)

`LlmProvider.generateStream` only receives Koog `ToolDescriptor`s (no `isDestructive`). For PR2, inject a `DestructiveToolLookup` **instance** at provider construction (backed by the local/MCP tool registry already known to DI):

```kotlin
fun interface DestructiveToolLookup {
    fun isDestructive(toolName: String): Boolean
}

// lookup: DestructiveToolLookup injected into LocalLlmProvider
val hasDestructive = tools.any { lookup.isDestructive(it.name) }
if (tools.isEmpty() || !hasDestructive) {
    // Async: sendMessageAsync Flow + automaticToolCalling = true
    // OpenApiTool adapters → ToolExecutor (cache / array cap / truncate)
} else {
    // Sync: PR1 path — ChatEngine confirmation + loop
}
```

Do not widen the `LlmProvider` interface for this flag.

| Feature | Async (read-only / no tools) | Sync (any destructive) |
|---------|------------------------------|-------------------------|
| API | `sendMessageAsync()` Flow | `sendMessage()` |
| Tool loop | LiteRT + `OpenApiTool` | ChatEngine |
| Streaming | Real `TextDelta` chunks | Thinking animation |
| Confirm / repeat / token accounting | Timeout + max tool rounds inside provider; emit tool-activity events | ChatEngine |

### OpenApiTool adapter

- `getToolDescriptionJsonString()` from the stripped OpenAPI schema  
- `execute(params)` → `runBlocking { toolExecutor.execute(...) }` on LiteRT’s worker thread  
- **Required for PR2 UI parity:** adapters (or provider) emit tool-executing / tool-finished events onto a `SharedFlow` that `AssistantViewModel` already (or newly) collects — silent auto-tool loops are not acceptable  

### 12B policy vs #453 Simple

**Contradiction to resolve in PR2 (not optional):** Today `onDevice = true` ⇒ `ModelCapability.Simple`, and Simple **always** skips MCP and forces `TOOLS_ONLY`. Issue #264’s 12B row (MCP ≤5, total cap 15, TOKEN_SAVER) **cannot** work with that boolean.

PR2 extends the capability model (preferred shape):

```kotlin
enum class OnDeviceTier { E4B, LARGE }  // from catalog

// resolve():
//   onDevice + E4B   → Simple          (local only, cap 10, TOOLS_ONLY ceiling)
//   onDevice + LARGE → OnDeviceLarge   // new tier OR dedicated policy branch
//   !onDevice        → existing #453 heuristics
```

| | E4B (`Simple`) | 12B (`OnDeviceLarge`) |
|--|----------------|------------------------|
| MCP | No | Yes, budget ≤5 |
| Total tool cap | 10 | 15 |
| Default / ceiling mode | TOOLS_ONLY | TOKEN_SAVER ceiling (user may go more aggressive, not less) |
| Schemas | Stripped (#330) | Per effective mode (#330 mapping) |
| `onDevice` | true | true |

Do **not** fake 12B as `ModelCapability.Full` — that drops hard caps and treats on-device like frontier cloud.

PR2 must update more than the enum:

1. `ModelCapabilityResolver.resolve` — accept catalog `OnDeviceTier` (or modelId → tier); `KnownProvider.LOCAL` must be exhaustive in the `when` (compile break otherwise).  
2. `effectiveTokenSavingMode` — `OnDeviceLarge` ceiling = `TOKEN_SAVER`.  
3. `ToolRouter` — today `capability != Simple` admits MCP with no on-device hard cap. Add an `OnDeviceLarge` branch: allow MCP, hard-cap total tools at 15, keep complexity filter optional (or lighter than Simple).  
4. `AssistantViewModel` MCP budget — align with ≤5 for OnDeviceLarge.  
5. Tests in `ModelCapabilityTest` / `ToolRouterTest` for both tiers.

PR1 does not implement `OnDeviceLarge`; downloading 12B in PR1 still routes as Simple.

## Testing strategy

| Layer | Coverage |
|-------|----------|
| `shared/commonTest` | Catalog, SHA helper with fixture bytes, `DevicePerformance` math, `OnDevice` serialization, `onDevice=true` capability |
| `shared/jvmTest` | StreamFrame bridge from fake LiteRT `Message`; optional engine smoke if CI can host the artifact |
| `composeApp/commonTest` | VM routes OnDevice → `onDevice=true`; download UiState with `FakeLocalModelRepository` |
| Manual / prototype (issue AC) | E4B with ≤10 AAP tools; destructive confirm; idle release; Android + Desktop |

Fakes must implement `ILocalModelRepository`. No LiteRT types in `commonMain` tests.

## Acceptance criteria mapping

### PR1

- [ ] `LocalLlmProvider` implements `LlmProvider`
- [ ] Load and run inference on E4B (and load 12B file if downloaded)
- [ ] Sync path: manual tool calling with ChatEngine
- [ ] On-demand download with progress UI + SHA-256 verification
- [ ] Device capability check with GOOD/OK/POOR recommendation
- [ ] Tool filtering via `onDevice=true` (Simple / local-only / cap 10)
- [ ] Thinking animation for sync path (no token streaming yet)
- [ ] MTP enabled by default on GPU backends when API available
- [ ] 5-minute idle release
- [ ] Works on Android and Desktop (JVM), with AVX2 gate on desktop x86_64

### PR2

- [ ] Async path: streaming + auto tool calling for read-only queries
- [ ] Sync path retained when destructive tools are present
- [ ] `OpenApiTool` adapters preserve caching / truncation / array capping
- [ ] Tool-activity events visible in UI on async path
- [ ] `#453` extended: `OnDeviceLarge` (or equivalent) for 12B — MCP ≤5, total cap 15, TOKEN_SAVER ceiling; E4B remains Simple
- [ ] Streaming UI for async path
- [ ] Async path bounded by timeout + max tool rounds

### Prototype verification (issue — may be manual)

- [ ] E4B tool-calling reliability with ~10 representative AAP schemas
- [ ] 12B with up to 15 tools + MCP — reliability and latency
- [ ] Async path end-to-end with adapters
- [ ] Context-window behavior when conversation exceeds model limit

## Implementation notes for agents

1. Worktree: `.claude/worktrees/issue-264-litert-lm` on branch `feat/264-litert-lm`  
2. Prefer Kai reference at `tmp/Kai/` for Engine/download/idle/AVX2 patterns — **not** for tool-loop ownership  
3. Do not expand ChatEngine’s public API for PR1  
4. Keep files focused; extract if `LocalLlmProvider` actual grows past ~400 LOC without a documented exception  
5. After PR1 merges (or its interfaces stabilize), a second agent may implement PR2 against this spec  
6. Spike LiteRT schema registration (`automaticToolCalling = false`) in the first PR1 task that touches the engine  
7. PR2’s first task should be the `#453` / `OnDeviceLarge` extension + tests — dual-path depends on correct tool sets  

## Spec review log

### Pass 2 (2026-08-07) — findings addressed in-doc

| Severity | Finding | Resolution |
|----------|---------|------------|
| **Must-fix** | 12B MCP + TOKEN_SAVER contradicts `#453` `onDevice → Simple` (no MCP, TOOLS_ONLY) | PR2 explicitly extends capability with `OnDeviceLarge`; PR1 keeps Simple for all on-device |
| **Must-fix** | Download `Ready` conflated with on-disk readiness | Split: `isReady()` vs transient `Succeeded` / `Idle` |
| Med | No `cancelDownload` | Added |
| Med | No engine loading UI state | Added Loading/Ready phase requirement |
| Med | Async path silent tools / unbounded loop | Required tool-activity `SharedFlow` + timeout/max rounds |
| Med | Context chars stuck at cloud TOOLS_ONLY 4K | Tie OnDevice context budget to catalog defaults |
| Med | Package path ambiguous | Locked to `assistant/local/` |
| Med | Schema-only tools unverified with LiteRT | Early PR1 spike called out |
| Low | `DestructiveToolLookup` shown as static call | Fixed to injected instance |
| Low | Polymorphic config migration | Noted serializer registration |
| Low | GPU estimate formula omitted | Documented Kai/issue formula |

### Pass 3 (2026-08-07) — conflict re-check

| Check | Result |
|-------|--------|
| PR1 vs #453 `onDevice → Simple` | **Solved** — intentional; E4B and 12B both Simple until PR2 |
| PR2 12B vs Simple no-MCP / TOOLS_ONLY | **Solved in design** — `OnDeviceLarge` + ToolRouter/VM/resolver touch list (not enum-only) |
| Fake-as-`Full` anti-pattern | Explicitly forbidden |
| Residual ambiguity | Unlocked by #470 — user-selectable context (defaults remain catalog defaults) |
| Remaining risks (not conflicts) | LiteRT schema-only spike; pinned HF URLs/SHAs at implement time; async tool-event wiring |

**Verdict:** Conflict solved. Spec ready for implementation planning.

## References

- Issue #264 (LiteRT-LM on-device)  
- Issue #330 / PR #455 (schema token reduction)  
- Issue #453 / PR #457 (model-capability tiers)  
- Kai 9000: `tmp/Kai/composeApp/.../LiteRTInferenceEngine.kt`, `LocalModelCatalog.kt`  
- Prior Jane pattern: `docs/superpowers/specs/2026-05-19-koog-phase1-llm-provider-design.md`  
- Contracts: `docs/architecture/service-contracts.md`  
