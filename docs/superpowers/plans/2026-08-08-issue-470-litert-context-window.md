# Configurable on-device LiteRT context window (Kai-style) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users set on-device LiteRT context (catalog default → max, 1K steps), persist it, wire `EngineConfig.maxNumTokens`, and use the same value for ChatEngine history budget + Settings GOOD/OK/POOR.

**Architecture:** Persist a per-model context map in `AssistantRepository` (Kai-style **slider source of truth** for every catalog model, including before download). Mirror the active model’s selection onto `LlmProviderConfig.OnDevice.contextTokens` (**engine/ChatEngine authority** for the LOCAL config). Clamp to `[defaultContextTokens, maxContextTokens]` in 1024-token steps. On change, update the active OnDevice config so `AssistantViewModel` closes the cached provider; LiteRT init retries with catalog `defaultContextTokens`, then `maxNumTokens = null`, if the requested size fails.

**Sync rules (dual persist):**
1. Map = slider SoT for all models; `OnDevice.contextTokens` = authority for engine + ChatEngine when LOCAL is configured.
2. `setLocalModelContextTokens` always writes the map **and**, if LOCAL’s `OnDevice.modelId` matches, updates that llm_configs entry (so `activeConfigFlow` invalidates the provider cache).
3. `selectLocalModel` copies clamped map value (or catalog default if unset) into new `OnDevice.contextTokens`.
4. `resolveOnDeviceContextTokens(modelId, contextTokens)` / ChatEngine / LiteRT read **only** from the OnDevice field (0 → catalog default). They do not read the map at inference time.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform Material3 `Slider`, DataStore preferences, LiteRT-LM 0.15.0 `EngineConfig.maxNumTokens`, existing `estimateGpuMemoryMb` / `devicePerformance`.

**Issue:** [#470](https://github.com/leogallego/ansible-jane/issues/470) · Follow-up to #264 / PR #461  
**Out of scope:** #264 PR2 dual-path, #450 compression, #469 download reliability, catalog max > 32K

## Global Constraints

- Defaults stay conservative: E4B → 4096, 12B → 8192 until the user moves the slider
- Floor is catalog `defaultContextTokens` (not below); ceiling is `maxContextTokens` (32768)
- Step size: 1024 tokens
- Backward-compatible serialization: existing OnDevice JSON without `contextTokens` must still decode
- LiteRT types stay in `androidMain` / `jvmMain` only
- UI must not import repository domain types beyond existing presentation mappers (`LocalModelUi`)
- ViewModels expose `StateFlow`; no `MutableStateFlow` to UI
- Use `--no-daemon` on all Gradle commands in sandbox
- Commits/PR trailer: `Assisted-by: Cursor (Grok 4.5)`

## File map

| Path | Change |
|------|--------|
| `shared/.../local/LocalModelContext.kt` (new) | `CONTEXT_TOKEN_STEP`, `clampContextTokens`, `resolveOnDeviceContextTokens(modelId, contextTokens)` — **no** `LlmProviderConfig` import |
| `shared/.../data/AssistantConfig.kt` | `OnDevice.contextTokens: Int = 0` (0 = catalog default) |
| `shared/.../data/IAssistantRepository.kt` | `getModelContextTokens` / `setModelContextTokens` / `modelContextTokensFlow` |
| `shared/.../data/AssistantRepository.kt` | DataStore key `model_context_tokens` JSON map |
| `shared/.../llm/LocalLlmProvider.android.kt` | `maxNumTokens` + fallback + `loadedContextTokens` (clear in `releaseEngine`) |
| `shared/.../llm/LocalLlmProvider.jvm.kt` | Same |
| `composeApp/.../AssistantCapability.kt` | Thin wrapper → `resolveOnDeviceContextTokens` |
| `composeApp/.../AssistantViewModel.kt` | Cache identity includes resolved contextTokens |
| `composeApp/.../LocalModelUi.kt` | Add `defaultContextTokens`, `maxContextTokens` |
| `composeApp/.../SettingsUiState.kt` | `Ready.localModelContextTokens: Map<String, Int>` from flow |
| `composeApp/.../SettingsViewModel.kt` | Slider persist + performance-at-selection; keep on-device helpers cohesive (file already >400 LOC — accept growth for #470; no drive-by split) |
| `composeApp/.../OnDeviceProviderCard.kt` | Slider UI + live badge via VM performance callback only |
| `composeApp/.../AgentTab.kt` / `SettingsScreen.kt` | Wire callbacks |
| `composeApp/src/commonMain/composeResources/values/strings.xml` | Context label (+ short guidance) |
| `docs/architecture/service-contracts.md` or litert design note | Pixel-class guidance |
| Tests + fakes | Clamp/persist/resolve/VM |

---

### Task 1: Clamp helper + OnDevice field + repository map

**Files:**
- Create: `shared/src/commonMain/kotlin/io/github/leogallego/ansiblejane/assistant/local/LocalModelContext.kt`
- Modify: `shared/src/commonMain/kotlin/io/github/leogallego/ansiblejane/assistant/data/AssistantConfig.kt`
- Modify: `shared/src/commonMain/kotlin/io/github/leogallego/ansiblejane/assistant/data/IAssistantRepository.kt`
- Modify: `shared/src/commonMain/kotlin/io/github/leogallego/ansiblejane/assistant/data/AssistantRepository.kt`
- Test: `shared/src/commonTest/kotlin/io/github/leogallego/ansiblejane/assistant/local/LocalModelContextTest.kt`
- Test: `shared/src/commonTest/kotlin/io/github/leogallego/ansiblejane/assistant/data/OnDeviceConfigTest.kt`
- Test: `composeApp/src/commonTest/kotlin/io/github/leogallego/ansiblejane/fakes/FakeAssistantRepository.kt`

**Interfaces:**
- Produces: `const val CONTEXT_TOKEN_STEP = 1024`
- Produces: `fun clampContextTokens(defaultTokens: Int, maxTokens: Int, requested: Int): Int`
- Produces: `fun resolveOnDeviceContextTokens(modelId: String, contextTokens: Int): Int` — catalog lookup + clamp; `contextTokens <= 0` → catalog default; **no** `LlmProviderConfig` import in `assistant.local`
- Produces: `OnDevice(modelId, tokenSavingMode, contextTokens: Int = 0)` — `0` means “use catalog default”
- Produces: `suspend fun getModelContextTokens(modelId: String): Int?` (null = unset)
- Produces: `suspend fun setModelContextTokens(modelId: String, contextTokens: Int)`
- Produces: `val modelContextTokensFlow: Flow<Map<String, Int>>`

- [ ] **Step 1: Write failing clamp + serialization tests**

```kotlin
// LocalModelContextTest.kt
@Test
fun clamp_roundsDownToStep_andBounds() {
    assertEquals(4096, clampContextTokens(4096, 32768, 0))
    assertEquals(4096, clampContextTokens(4096, 32768, 5000)) // rounds toward default step
    assertEquals(8192, clampContextTokens(4096, 32768, 8192))
    assertEquals(32768, clampContextTokens(4096, 32768, 99999))
}

// OnDeviceConfigTest — extend
@Test
fun onDevice_roundTrip_includesContextTokens() {
    val original = LlmProviderConfig.OnDevice(modelId = "gemma-4-e4b-it", contextTokens = 8192)
    val json = Json.encodeToString(LlmProviderConfig.serializer(), original)
    val decoded = Json.decodeFromString(LlmProviderConfig.serializer(), json)
    assertEquals(original, decoded)
}

@Test
fun onDevice_missingContextTokens_defaultsToZero() {
    val json = """{"type":"on_device","modelId":"gemma-4-e4b-it"}"""
    val decoded = Json.decodeFromString(LlmProviderConfig.serializer(), json) as LlmProviderConfig.OnDevice
    assertEquals(0, decoded.contextTokens)
}
```

Clamp rule (match Kai discrete steps):  
`val stepped = defaultTokens + (((requested - defaultTokens).coerceAtLeast(0) / CONTEXT_TOKEN_STEP) * CONTEXT_TOKEN_STEP)`  
then `stepped.coerceIn(defaultTokens, maxTokens)`.

- [ ] **Step 2: Implement helper + OnDevice field + repository APIs**

DataStore key: `stringPreferencesKey("model_context_tokens")` storing `Map<String, Int>` JSON (same Json style as `llm_configs`).

`setModelContextTokens` must clamp using catalog lookup when available; repository may accept already-clamped ints from the VM and store as-is — prefer clamping in a shared helper called from the VM **and** repository for defense.

- [ ] **Step 3: Update FakeAssistantRepository** with in-memory map + flow.

- [ ] **Step 4: Run tests**

```bash
./gradlew :shared:jvmTest --tests '*LocalModelContextTest*' --tests '*OnDeviceConfigTest*' --no-daemon
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(assistant): persist per-model LiteRT context tokens

Add clamp helper, OnDevice.contextTokens, and DataStore map for Kai-style
context preferences (#470).

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 2: ChatEngine budget + provider cache identity

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/io/github/leogallego/ansiblejane/assistant/presentation/AssistantCapability.kt`
- Modify: `composeApp/src/commonMain/kotlin/io/github/leogallego/ansiblejane/assistant/presentation/AssistantViewModel.kt`
- Test: `composeApp/src/commonTest/kotlin/io/github/leogallego/ansiblejane/assistant/presentation/AssistantCapabilityTest.kt`

**Interfaces:**
- Consumes: `resolveOnDeviceContextTokens(modelId, contextTokens)` from Task 1
- Produces: `resolveContextCharsForConfig` thin wrapper → shared resolve
- Produces: `providerCacheIdentity(OnDevice) = "local|${modelId}|${resolvedContextTokens}"`

- [ ] **Step 1: Update AssistantCapabilityTest**

```kotlin
@Test
fun resolveContextChars_usesSelectionWhenSet() {
    val chars = resolveContextCharsForConfig(
        LlmProviderConfig.OnDevice(modelId = "gemma-4-e4b-it", contextTokens = 16384)
    )
    assertEquals(16384, chars)
}

@Test
fun resolveContextChars_zeroMeansCatalogDefault() {
    val chars = resolveContextCharsForConfig(
        LlmProviderConfig.OnDevice(modelId = "gemma-4-e4b-it", contextTokens = 0)
    )
    assertEquals(4096, chars)
}

@Test
fun resolveContextChars_clampsAboveMax() {
    val chars = resolveContextCharsForConfig(
        LlmProviderConfig.OnDevice(modelId = "gemma-4-e4b-it", contextTokens = 99999)
    )
    assertEquals(32768, chars)
}
```

Keep existing E4B/12B/unknown default tests (they use `contextTokens = 0` implicitly).

- [ ] **Step 2: Implement resolve + cache identity**

```kotlin
fun resolveContextCharsForConfig(config: LlmProviderConfig.OnDevice): Int =
    resolveOnDeviceContextTokens(config.modelId, config.contextTokens)
```

```kotlin
is LlmProviderConfig.OnDevice ->
    "local|${config.modelId}|${resolveContextCharsForConfig(config)}"
```

`activeConfigFlow` already closes the cached provider when identity changes — no Settings→engine direct call required.

- [ ] **Step 3: Run tests**

```bash
./gradlew :composeApp:desktopTest --tests '*AssistantCapabilityTest*' --no-daemon
```

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(assistant): use selected LiteRT context for ChatEngine budget

Resolve contextChars from OnDevice.contextTokens and include it in the
provider cache identity so engines re-init on change (#470).

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 3: LiteRT `maxNumTokens` + fallback (android + jvm)

**Files:**
- Modify: `shared/src/androidMain/kotlin/io/github/leogallego/ansiblejane/assistant/llm/LocalLlmProvider.android.kt`
- Modify: `shared/src/jvmMain/kotlin/io/github/leogallego/ansiblejane/assistant/llm/LocalLlmProvider.jvm.kt`

**Interfaces:**
- Consumes: `resolveOnDeviceContextTokens(config.modelId, config.contextTokens)` from Task 1
- Produces: `EngineConfig(..., maxNumTokens = requested)` with fallback: requested → catalog default → `null`
- Produces: track `loadedContextTokens`; clear to `null` in `releaseEngine`; re-init when model or context differs

- [ ] **Step 1: Wire EngineConfig (both platforms)** using shared resolve helper (already in Task 1).

```kotlin
fun initWithBackend(backend: Backend, maxNumTokens: Int?): Engine {
    val instance = Engine(
        EngineConfig(
            modelPath = modelPath,
            backend = backend,
            cacheDir = cacheDir,
            maxNumTokens = maxNumTokens,
        )
    )
    instance.initialize()
    return instance
}

val resolved = resolveOnDeviceContextTokens(config.modelId, config.contextTokens)
val catalogDefault = LOCAL_MODEL_CATALOG.find { it.id == config.modelId }?.defaultContextTokens ?: 4_096
// Try: resolved → (if resolved != default) catalogDefault → null
// On success, set loadedContextTokens to the tokens actually passed (catalogDefault or resolved;
// for null fallback use catalogDefault so ChatEngine/engine metadata stay aligned with a known size).
// Preference in the map/OnDevice stays at the user's requested value (retry next message may succeed).
```

Preserve existing GPU→CPU backend fallback and speculative-decoding flag behavior. Clear `loadedContextTokens` in `releaseEngine`.

- [ ] **Step 2: Manual note** — unit-testing LiteRT Engine is not practical in CI; document in PR that device verification needs a downloaded model (#469).

- [ ] **Step 3: Compile shared android/jvm**

```bash
./gradlew :shared:compileDebugKotlinAndroid :shared:compileKotlinJvm --no-daemon
```

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(assistant): pass maxNumTokens into LiteRT EngineConfig

Wire user-selected context into EngineConfig with null fallback when
init fails at the requested KV size (#470).

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 4: Settings UI slider + ViewModel

**Files:**
- Modify: `composeApp/.../presentation/settings/LocalModelUi.kt`
- Modify: `composeApp/.../presentation/settings/SettingsViewModel.kt`
- Modify: `composeApp/.../ui/settings/OnDeviceProviderCard.kt`
- Modify: `composeApp/.../ui/settings/AgentTab.kt` (if callbacks pass through)
- Modify: `composeApp/.../ui/settings/SettingsScreen.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Test: `composeApp/.../SettingsViewModelTest.kt`
- Test: `composeApp/.../LocalModelUiMapperTest.kt` (if present)

**Interfaces:**
- Consumes: `modelContextTokensFlow`, `setModelContextTokens`, `devicePerformance(modelId, contextTokens)`
- Produces: `fun setLocalModelContextTokens(modelId: String, contextTokens: Int)`
- Produces: UiState includes `localModelContextTokens: Map<String, Int>` (or read from dedicated StateFlow)
- Produces: `localModelPerformance(modelId)` uses selected/clamped tokens
- Produces: `selectLocalModel` writes `OnDevice(modelId, contextTokens = resolved)`

**UI (Kai parity):**
- Label: `Context: 8K` via string `agent_local_context_size` with `%1$s`
- Material3 `Slider`: `valueRange = 0f..steps`, `steps = steps - 1`, persist on `onValueChangeFinished`
- Live performance: while dragging, call VM `localModelPerformance(modelId, previewTokens)` → `ILocalModelRepository.devicePerformance` only (no estimate math in the composable)
- `testTag("slider_local_context_${model.id}")`

Extend `LocalModelUi`:

```kotlin
data class LocalModelUi(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val isRecommended: Boolean,
    val defaultContextTokens: Int,
    val maxContextTokens: Int,
)
```

- [ ] **Step 1: Failing VM test**

```kotlin
@Test
fun setLocalModelContextTokens_persistsAndUpdatesActiveOnDevice() = runTest {
    // given LOCAL active with e4b
    viewModel.selectLocalModel("gemma-4-e4b-it")
    advanceUntilIdle()
    viewModel.setLocalModelContextTokens("gemma-4-e4b-it", 16384)
    advanceUntilIdle()
    val stored = repository.getModelContextTokens("gemma-4-e4b-it")
    assertEquals(16384, stored)
    val active = repository.loadLlmConfig() as LlmProviderConfig.OnDevice
    assertEquals(16384, active.contextTokens)
}
```

- [ ] **Step 2: Implement VM + UI + strings**

When setting tokens for the active on-device model, update both the map and the LOCAL entry in `llm_configs` so `activeConfigFlow` invalidates the provider cache.

Add short guidance string under the slider, e.g.  
`agent_local_context_guidance` = `4K default; 8K–16K OK on ≥12 GB RAM; 32K may be tight or slower.`

- [ ] **Step 3: Run Settings / mapper tests**

```bash
./gradlew :composeApp:desktopTest --tests '*SettingsViewModelTest*' --tests '*LocalModelUiMapperTest*' --tests '*AssistantCapabilityTest*' --no-daemon
```

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): Kai-style on-device context size slider

Add Settings slider with live GOOD/OK/POOR estimates and persist
selection into the active OnDevice config (#470).

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 5: Docs + acceptance sweep

**Files:**
- Modify: `docs/architecture/service-contracts.md` (on-device section) **or** short note in `docs/superpowers/specs/2026-08-07-litert-lm-on-device-design.md`
- Verify all AC checkboxes from #470

- [ ] **Step 1: Document Pixel-class guidance** (4K default; 8K–16K OK on ≥12 GB; 32K may be tight/slower). Unlock the PR1 “contextChars = default only” lock in the design doc with a pointer to #470.

- [ ] **Step 2: Run focused regression suite**

```bash
./gradlew :shared:jvmTest --tests '*LocalModel*' --tests '*OnDevice*' --tests '*IdleRelease*' --no-daemon
./gradlew :composeApp:desktopTest --tests '*AssistantCapability*' --tests '*SettingsViewModel*' --tests '*LocalModelUi*' --no-daemon
```

- [ ] **Step 3: Commit docs**

```bash
git commit -m "$(cat <<'EOF'
docs: document configurable LiteRT context window guidance

Record Pixel-class context recommendations and unlock the PR1 default-only
lock for #470.

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

## Acceptance criteria coverage

| Criterion | Task |
|-----------|------|
| Slider between catalog min/max (1K steps) | 4 |
| Persists across restarts | 1, 4 |
| LiteRT `maxNumTokens` matches selection | 3 |
| ChatEngine `contextChars` uses selection | 2 |
| Performance badge uses selection | 4 |
| Engine re-inits on context change | 2 (cache identity) + 3 (loadedContextTokens) |
| Unit tests clamp/persist/resolve + VM | 1, 2, 4 |
| Pixel guidance documented | 5 |

## Migration / compat

No migration job needed. `contextTokens` default `0` and missing JSON field → catalog default. Empty context map → defaults.

## Test plan (PR)

- [ ] Unit: clamp, OnDevice JSON, resolveContextChars, SettingsVM set/select
- [ ] Compile android + jvm LocalLlmProvider
- [ ] Manual (device with downloaded model): move slider 4K→16K, send message, confirm re-init / no crash; kill app and confirm persistence
)
