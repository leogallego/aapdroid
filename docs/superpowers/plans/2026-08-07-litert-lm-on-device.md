# LiteRT-LM On-Device LLM (#264) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship on-device Gemma 4 (E4B/12B) via LiteRT-LM behind Jane’s existing `LlmProvider` + ChatEngine, with model download and Simple-tier wiring in PR1, then dual-path streaming and `OnDeviceLarge` in PR2.

**Architecture:** Adapter pattern (same as Koog Phase 1). `LocalLlmProvider` implements `LlmProvider.generateStream` and synthesizes `StreamFrame`s from LiteRT `sendMessage()` in PR1 so ChatEngine stays unchanged. Model files and download live behind `ILocalModelRepository`; platform RAM/disk/AVX/dirs via `DeviceResources` expect/actual. PR2 adds async auto-tool calling and extends `#453` with `OnDeviceLarge`.

**Tech Stack:** Kotlin Multiplatform, LiteRT-LM `0.15.0` (`litertlm-android` + `litertlm-jvm`), Koin, DataStore, Compose Multiplatform, `kotlin.test`, existing ChatEngine / ToolRouter / #330 / #453.

**Spec:** `docs/superpowers/specs/2026-08-07-litert-lm-on-device-design.md`

## Global Constraints

- LiteRT only in `shared/androidMain` + `shared/jvmMain` — never `commonMain`
- ChatEngine public API unchanged in PR1
- `onDevice=true` → `ModelCapability.Simple` for all on-device models in PR1 (including 12B if downloaded)
- Do not fake 12B as `ModelCapability.Full`
- Public HF catalog URLs must be **pinned commit** + SHA-256 (fill real values at implement time from `litert-community`)
- No hardcoded secrets; catalog URLs are public artifacts
- UI strings via `stringResource` / `composeResources`
- Repository interface + Koin `bind`; fakes implement interfaces
- Gradle in sandbox: always `--no-daemon`
- Package root: `io.github.leogallego.ansiblejane`
- Worktree: `.claude/worktrees/issue-264-litert-lm` on `feat/264-litert-lm`
- Kai reference: `tmp/Kai/` for Engine/download/idle/AVX2 — **not** for tool-loop ownership

---

## File structure (PR1)

| File | Responsibility |
|------|----------------|
| `gradle/libs.versions.toml` | `litert-lm = "0.15.0"` + android/jvm library coords |
| `shared/build.gradle.kts` | androidMain + jvmMain deps |
| `shared/.../platform/DeviceResources.kt` | expect: RAM, disk, model dir, AVX2 |
| `shared/androidMain/.../DeviceResources.android.kt` | actual |
| `shared/jvmMain/.../DeviceResources.jvm.kt` | actual |
| `shared/.../assistant/local/LocalModel.kt` | catalog model + enums + performance math |
| `shared/.../assistant/local/LocalModelCatalog.kt` | E4B + 12B entries |
| `shared/.../assistant/local/ILocalModelRepository.kt` | download/readiness API |
| `shared/.../assistant/local/LocalModelRepository.kt` | download + SHA-256 |
| `shared/.../assistant/data/AssistantConfig.kt` | `LlmProviderConfig.OnDevice` |
| `shared/.../assistant/data/LlmProviderDefinitions.kt` | `KnownProvider.LOCAL` |
| `shared/.../assistant/data/AssistantRepository.kt` | OnDevice key / stripApiKey branches |
| `shared/.../assistant/llm/LocalLlmProviderFactory.kt` | expect factory |
| `shared/androidMain/.../llm/LocalLlmProvider.android.kt` | LiteRT + StreamFrame bridge |
| `shared/jvmMain/.../llm/LocalLlmProvider.jvm.kt` | LiteRT + StreamFrame bridge |
| `shared/.../assistant/di/AssistantDiModule.kt` | bind `ILocalModelRepository` |
| `composeApp/.../AssistantViewModel.kt` | OnDevice provider + `onDevice=true` |
| `composeApp/.../ui/settings/AgentTab.kt` | Local provider card + download UI |
| `composeApp/.../composeResources/values/strings.xml` | local LLM strings |
| Tests under `shared/commonTest`, `shared/jvmTest`, `composeApp/commonTest` | as per tasks |

PR2 adds: `ModelCapability.OnDeviceLarge`, ToolRouter branch, `DestructiveToolLookup`, OpenApiTool adapters, async path.

---

# Part A — PR1 (E4B MVP)

### Task 1: Gradle LiteRT-LM 0.15.0

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `shared/build.gradle.kts`

- [ ] **Step 1: Add version catalog entries**

In `[versions]` add:

```toml
litert-lm = "0.15.0"
```

In `[libraries]` near the AI / LLM block add:

```toml
litert-lm-android = { group = "com.google.ai.edge.litertlm", name = "litertlm-android", version.ref = "litert-lm" }
litert-lm-jvm = { group = "com.google.ai.edge.litertlm", name = "litertlm-jvm", version.ref = "litert-lm" }
```

- [ ] **Step 2: Wire source-set dependencies**

In `shared/build.gradle.kts` `androidMain.dependencies`:

```kotlin
implementation(libs.litert.lm.android)
```

In `jvmMain.dependencies`:

```kotlin
implementation(libs.litert.lm.jvm)
```

(Adjust accessor names to match how the catalog generates them — typically `libs.litert.lm.android`.)

- [ ] **Step 3: Resolve dependencies**

Run:

```bash
./gradlew :shared:dependencies --configuration androidRuntimeClasspath --no-daemon 2>&1 | rg -i litert | head -20
```

Expected: `litertlm-android:0.15.0` appears. If resolution fails, stop and fix coordinates before continuing.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml shared/build.gradle.kts
git commit -m "$(cat <<'EOF'
build: add LiteRT-LM 0.15.0 for Android and JVM (#264)

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 2: DeviceResources expect/actual

**Files:**
- Create: `shared/src/commonMain/kotlin/io/github/leogallego/ansiblejane/platform/DeviceResources.kt`
- Create: `shared/src/androidMain/kotlin/io/github/leogallego/ansiblejane/platform/DeviceResources.android.kt`
- Create: `shared/src/jvmMain/kotlin/io/github/leogallego/ansiblejane/platform/DeviceResources.jvm.kt`
- Create: `shared/src/commonTest/kotlin/io/github/leogallego/ansiblejane/platform/DeviceResourcesTest.kt` (common API smoke via fake if needed — prefer jvmTest for AVX)
- Create: `shared/src/jvmTest/kotlin/io/github/leogallego/ansiblejane/platform/DeviceResourcesJvmTest.kt`

**Interfaces:**
- Produces: `expect class DeviceResources` with `totalMemoryBytes()`, `freeDiskBytes(path)`, `modelStorageDirectory()`, `hasAvx2Support()`

- [ ] **Step 1: Write failing jvm test for AVX helper behavior**

```kotlin
// shared/src/jvmTest/.../DeviceResourcesJvmTest.kt
package io.github.leogallego.ansiblejane.platform

import kotlin.test.Test
import kotlin.test.assertTrue

class DeviceResourcesJvmTest {
    @Test
    fun modelStorageDirectory_isUnderAnsibleJane() {
        val dir = DeviceResources().modelStorageDirectory()
        assertTrue(dir.replace('\\', '/').contains(".ansiblejane/litert_models"))
    }

    @Test
    fun hasAvx2Support_returnsBooleanWithoutThrowing() {
        DeviceResources().hasAvx2Support() // must not throw on this machine
    }
}
```

- [ ] **Step 2: Run test — expect fail (class missing)**

```bash
./gradlew :shared:jvmTest --tests 'io.github.leogallego.ansiblejane.platform.DeviceResourcesJvmTest' --no-daemon
```

Expected: FAIL compile — `DeviceResources` unresolved.

- [ ] **Step 3: Implement expect + actuals**

```kotlin
// commonMain
package io.github.leogallego.ansiblejane.platform

expect class DeviceResources() {
    fun totalMemoryBytes(): Long
    fun freeDiskBytes(absolutePath: String): Long
    fun modelStorageDirectory(): String
    /** Android always true; desktop x86_64 Linux checks /proc/cpuinfo; else true. */
    fun hasAvx2Support(): Boolean
}
```

Android actual: `ActivityManager.MemoryInfo`, `StatFs`, `context.filesDir/litert_models`, `hasAvx2Support() = true`.  
(Obtain Context the same way other platform actuals do — follow `DataStoreFactory.android.kt` / `PlatformUtils.android.kt` patterns.)

JVM actual: `/proc/meminfo` MemTotal or `OperatingSystemMXBean`; `File(path).usableSpace`; `~/.ansiblejane/litert_models`; AVX2 via `/proc/cpuinfo` flags containing `avx2` when `os.arch` is amd64/x86_64, else `true`.

- [ ] **Step 4: Re-run jvmTest — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/io/github/leogallego/ansiblejane/platform/DeviceResources.kt \
  shared/src/androidMain/kotlin/io/github/leogallego/ansiblejane/platform/DeviceResources.android.kt \
  shared/src/jvmMain/kotlin/io/github/leogallego/ansiblejane/platform/DeviceResources.jvm.kt \
  shared/src/jvmTest/kotlin/io/github/leogallego/ansiblejane/platform/DeviceResourcesJvmTest.kt
git commit -m "$(cat <<'EOF'
feat(platform): DeviceResources for on-device model storage (#264)

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 3: LocalModel catalog + device performance math

**Files:**
- Create: `shared/src/commonMain/kotlin/io/github/leogallego/ansiblejane/assistant/local/LocalModel.kt`
- Create: `shared/src/commonMain/kotlin/io/github/leogallego/ansiblejane/assistant/local/LocalModelCatalog.kt`
- Create: `shared/src/commonTest/kotlin/io/github/leogallego/ansiblejane/assistant/local/LocalModelCatalogTest.kt`

**Interfaces:**
- Produces: `LocalModel`, `OnDeviceTier`, `DevicePerformance`, `estimateGpuMemoryMb`, `calculateDevicePerformance`, `LOCAL_MODEL_CATALOG`

- [ ] **Step 1: Write failing tests**

```kotlin
package io.github.leogallego.ansiblejane.assistant.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalModelCatalogTest {
    @Test
    fun catalog_hasE4bAndLarge_only() {
        assertEquals(2, LOCAL_MODEL_CATALOG.size)
        assertTrue(LOCAL_MODEL_CATALOG.any { it.onDeviceTier == OnDeviceTier.E4B })
        assertTrue(LOCAL_MODEL_CATALOG.any { it.onDeviceTier == OnDeviceTier.LARGE })
    }

    @Test
    fun estimateGpuMemoryMb_includesBaselineAndExtraKv() {
        val model = LOCAL_MODEL_CATALOG.first { it.onDeviceTier == OnDeviceTier.E4B }
        val atDefault = estimateGpuMemoryMb(model, model.defaultContextTokens)
        val higher = estimateGpuMemoryMb(model, model.defaultContextTokens + 1024)
        assertTrue(higher > atDefault)
    }

    @Test
    fun calculateDevicePerformance_thresholds() {
        // 2.5x → GOOD, 1.85x → OK, below → POOR
        assertEquals(DevicePerformance.GOOD, calculateDevicePerformance(2_500L * 1024 * 1024, 1000))
        assertEquals(DevicePerformance.OK, calculateDevicePerformance(1_900L * 1024 * 1024, 1000))
        assertEquals(DevicePerformance.POOR, calculateDevicePerformance(1_000L * 1024 * 1024, 1000))
    }

    @Test
    fun catalog_entries_havePinnedUrlAndSha256() {
        LOCAL_MODEL_CATALOG.forEach { m ->
            assertTrue(m.downloadUrl.contains("/resolve/"), m.id)
            assertTrue(m.sha256.length == 64, m.id)
        }
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

```bash
./gradlew :shared:compileCommonMainKotlinMetadata :shared:allTests --tests '*LocalModelCatalogTest' --no-daemon
```

(Use the project’s usual shared test task if `allTests` differs — e.g. `:shared:cleanAllTests :shared:allTests`.)

- [ ] **Step 3: Implement catalog**

```kotlin
// LocalModel.kt
enum class OnDeviceTier { E4B, LARGE }
enum class DevicePerformance { GOOD, OK, POOR }

data class LocalModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val downloadUrl: String, // pinned HF commit URL
    val sha256: String,
    val gpuMemoryMb: Int,
    val defaultContextTokens: Int,
    val maxContextTokens: Int,
    val kvPerTokenBytes: Int,
    val onDeviceTier: OnDeviceTier,
    val isRecommended: Boolean = false,
)

fun estimateGpuMemoryMb(model: LocalModel, contextTokens: Int): Int {
    val modelFileMb = (model.sizeBytes / (1024 * 1024)).toInt()
    val extraTokens = (contextTokens - model.defaultContextTokens).coerceAtLeast(0)
    val extraMemoryMb = (extraTokens.toLong() * model.kvPerTokenBytes) / (1024 * 1024)
    return modelFileMb + model.gpuMemoryMb + extraMemoryMb.toInt()
}

fun calculateDevicePerformance(totalMemoryBytes: Long, estimatedGpuMemoryMb: Int): DevicePerformance {
    val gpuMemoryBytes = estimatedGpuMemoryMb.toLong() * 1024 * 1024
    val ratio = totalMemoryBytes.toDouble() / gpuMemoryBytes.toDouble()
    return when {
        ratio >= 2.5 -> DevicePerformance.GOOD
        ratio >= 1.85 -> DevicePerformance.OK
        else -> DevicePerformance.POOR
    }
}
```

Fill `LOCAL_MODEL_CATALOG` with Gemma 4 E4B + 12B from HuggingFace `litert-community` — **immutable commit URLs + SHA-256** (look up at implement time; do not ship `resolve/main` floating URLs). Size/GPU/KV fields from issue #264 / Kai as starting points; verify against HF file sizes.

- [ ] **Step 4: Tests PASS → Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(assistant): local model catalog and device performance (#264)

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 4: ILocalModelRepository + download + SHA-256

**Files:**
- Create: `shared/.../assistant/local/ILocalModelRepository.kt`
- Create: `shared/.../assistant/local/LocalModelRepository.kt`
- Create: `shared/.../assistant/local/Sha256.kt` (expect/actual or pure Kotlin hashing available on both)
- Create: `shared/src/commonTest/.../local/LocalModelRepositoryTest.kt`
- Modify: `shared/.../assistant/di/AssistantDiModule.kt` (or `DataModule.kt`) — `single { LocalModelRepository(get()) } bind ILocalModelRepository::class`

**Interfaces:**
- Produces: API from spec (`downloadState`, `catalog`, `isReady`, `modelPath`, `download`, `cancelDownload`, `delete`, `devicePerformance`, `hasAvx2Support`)

- [ ] **Step 1: Write failing tests with temp dir injection**

Design `LocalModelRepository` to accept `DeviceResources` + optional `HttpClient` + overrideable base dir for tests.

```kotlin
@Test
fun download_verifiesSha256_andMarksReady() = runTest {
    // Serve known bytes with matching sha256 via MockEngine / in-memory
    // assert isReady(id) && modelPath non-null
}

@Test
fun download_rejectsShaMismatch_deletesPartial() = runTest {
    // Wrong sha → Error state, file absent
}

@Test
fun download_failsWhenDiskBelowSizePlus500Mb() = runTest {
    // Fake DeviceResources.freeDiskBytes returns too small
}
```

- [ ] **Step 2: Run — FAIL**

- [ ] **Step 3: Implement repository**

Rules from spec:
- Free space ≥ `sizeBytes + 500L * 1024 * 1024`
- Streaming SHA-256 while writing to `modelStorageDirectory()/id/fileName`
- `cancelDownload()` cancels the coroutine job
- `downloadState`: Idle → Downloading → Succeeded → Idle (or stay Succeeded briefly); Error on failure
- `isReady` = file exists and size > 0 (optionally re-hash on first ready check — YAGNI: trust verified download)

Use Ktor client already in shared for HTTP GET. No hardcoded tokens.

- [ ] **Step 4: Register in Koin**

```kotlin
single { LocalModelRepository(get()) } bind ILocalModelRepository::class
```

Ensure `DeviceResources` is provided in platform Koin modules (add `single { DeviceResources() }` where other platform types are bound).

- [ ] **Step 5: Tests PASS → Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(assistant): local model download repository with SHA-256 (#264)

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 5: OnDevice config + KnownProvider.LOCAL

**Files:**
- Modify: `shared/.../assistant/data/AssistantConfig.kt`
- Modify: `shared/.../assistant/data/LlmProviderDefinitions.kt`
- Modify: `shared/.../assistant/data/AssistantRepository.kt`
- Modify: `shared/.../assistant/engine/ModelCapability.kt` — add `KnownProvider.LOCAL` to `resolve` `when` (map to Simple when `!onDevice` as well, or require onDevice)
- Create: `shared/src/commonTest/.../data/OnDeviceConfigTest.kt`
- Modify: `shared/src/commonTest/.../engine/ModelCapabilityTest.kt`

- [ ] **Step 1: Failing serialization + capability tests**

```kotlin
@Test
fun onDevice_roundTripsThroughJson() {
    val cfg = LlmProviderConfig.OnDevice(modelId = "gemma-4-e4b-it")
    val json = Json.encodeToString(LlmProviderConfig.serializer(), cfg)
    val decoded = Json.decodeFromString(LlmProviderConfig.serializer(), json)
    assertEquals(cfg, decoded)
}

@Test
fun resolve_localProvider_onDevice_isSimple() {
    assertEquals(
        ModelCapability.Simple,
        ModelCapabilityResolver.resolve(KnownProvider.LOCAL, "gemma-4-e4b-it", onDevice = true),
    )
}
```

Also encode a map with only `OpenAiCompatible` and ensure it still decodes (regression).

- [ ] **Step 2: Implement**

```kotlin
// AssistantConfig.kt
@Serializable
@SerialName("on_device")
data class OnDevice(
    val modelId: String,
    override val tokenSavingMode: TokenSavingMode = TokenSavingMode.TOOLS_ONLY,
) : LlmProviderConfig
```

```kotlin
// LlmProviderDefinitions.kt — add before CUSTOM or after ABBENAY
LOCAL(
    displayName = "On-device",
    baseUrl = "",
    defaultModels = emptyList(),
    requiresApiKey = false,
    urlEditable = false,
),
```

Update `AssistantRepository`:
- `saveLlmConfig` key for OnDevice → `KnownProvider.LOCAL.name` (or `LOCAL.name + ":" + modelId` if multiple models need separate cards — prefer **one LOCAL config** with selected `modelId`)
- `stripApiKey` / `withApiKey` → OnDevice unchanged
- `fromUrl` must not match LOCAL (`baseUrl` empty already skipped)

Update `ModelCapabilityResolver.resolve` `when` to include `KnownProvider.LOCAL -> ModelCapability.Simple` (even if `onDevice` false, LOCAL is Simple).

- [ ] **Step 3: Tests PASS → Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(assistant): OnDevice LlmProviderConfig and KnownProvider.LOCAL (#264)

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 6: StreamFrame bridge + LocalLlmProvider sync path (fakeable)

**Files:**
- Create: `shared/commonMain/.../llm/LocalLlmProviderFactory.kt` (expect)
- Create: `shared/commonMain/.../llm/LiteRtMessageBridge.kt` — pure mapping helpers if possible without LiteRT types
- Create: `shared/androidMain/.../llm/LocalLlmProvider.android.kt`
- Create: `shared/jvmMain/.../llm/LocalLlmProvider.jvm.kt`
- Create: `shared/jvmTest/.../llm/LiteRtStreamFrameBridgeTest.kt`

**Spike (do first in this task):** Write a tiny jvm main/scratch or unit test that creates Engine + Conversation with `automaticToolCalling = false` and one no-op/schema tool — confirm `Message.toolCalls` populates. Document result in a short comment on the provider. If schema-only fails, use execute-stub that throws.

- [ ] **Step 1: Write bridge unit tests (no real Engine)**

Define an internal data model for bridge input so tests stay in jvmTest without needing GPU:

```kotlin
internal data class BridgedToolCall(val id: String, val name: String, val argumentsJson: String)
internal data class BridgedAssistantMessage(val text: String?, val toolCalls: List<BridgedToolCall>)

fun bridgedMessageToStreamFrames(msg: BridgedAssistantMessage): List<StreamFrame> {
    val frames = mutableListOf<StreamFrame>()
    msg.text?.takeIf { it.isNotEmpty() }?.let { frames += StreamFrame.TextDelta(it) }
    msg.toolCalls.forEach { tc ->
        frames += StreamFrame.ToolCallComplete(id = tc.id, name = tc.name, content = tc.argumentsJson)
    }
    frames += StreamFrame.End(/* meta with nulls — match Koog End signature used elsewhere */)
    return frames
}
```

Test: text-only; tool-only; text+tools; empty.

- [ ] **Step 2: FAIL → implement bridge → PASS**

- [ ] **Step 3: Implement `LocalLlmProvider` actuals**

Responsibilities:
- `generateStream`: map Koog `Prompt` → LiteRT history + system; `sanitizeForLiteRt`; `sendMessage` on `Dispatchers.IO`; map to frames via bridge; emit as Flow
- `automaticToolCalling = false`
- Tool schemas registered; execute stubs throw if called
- Engine init GPU→CPU; MTP flag when available
- Idle release 5 min; 750 ms drain on model swap
- `engineState: StateFlow<LocalEngineState>`
- `isAvailable()` / `modelInfo(isLocal=true)`
- Close releases engine

Factory:

```kotlin
// commonMain
expect object LocalLlmProviderFactory {
    fun create(
        config: LlmProviderConfig.OnDevice,
        modelRepository: ILocalModelRepository,
    ): LlmProvider
}
```

- [ ] **Step 4: Compile android + jvm**

```bash
./gradlew :shared:compileDebugKotlinAndroid :shared:compileKotlinJvm --no-daemon
```

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(assistant): LocalLlmProvider sync path and StreamFrame bridge (#264)

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 7: Wire AssistantViewModel + context budget

**Files:**
- Modify: `composeApp/.../assistant/presentation/AssistantViewModel.kt`
- Modify: `composeApp/src/commonTest/...` (existing AssistantViewModel tests or new)
- Modify: `composeApp/src/commonTest/fakes/FakeAssistantRepository.kt` if needed for OnDevice

- [ ] **Step 1: Failing test — OnDevice sets onDevice=true and Simple**

Use a fake ToolRouter/capturing spy if the project has one; otherwise unit-test a small extracted helper:

```kotlin
fun resolveCapabilityForConfig(config: LlmProviderConfig): ModelCapability = when (config) {
    is LlmProviderConfig.OnDevice ->
        ModelCapabilityResolver.resolve(KnownProvider.LOCAL, config.modelId, onDevice = true)
    is LlmProviderConfig.OpenAiCompatible ->
        ModelCapabilityResolver.resolve(KnownProvider.fromUrl(config.url), config.model, onDevice = false)
}
```

Prefer extracting this helper from the ViewModel to keep the test in commonTest without full VM setup.

- [ ] **Step 2: Update `getOrCreateProvider`**

```kotlin
private fun getOrCreateProvider(config: LlmProviderConfig, trustSelfSigned: Boolean): LlmProvider {
    when (config) {
        is LlmProviderConfig.OnDevice -> {
            val key = "local|${config.modelId}"
            cachedProvider?.let { if (cachedProviderKey == key) return it }
            cachedProvider?.close()
            return LocalLlmProviderFactory.create(config, localModelRepository).also {
                cachedProvider = it
                cachedProviderKey = key
            }
        }
        is LlmProviderConfig.OpenAiCompatible -> { /* existing */ }
    }
}
```

Inject `ILocalModelRepository` into `AssistantViewModel` via Koin.

Replace `onDevice = false` stub with real branch. Set `contextChars` from catalog:

```kotlin
val contextChars = when (config) {
    is LlmProviderConfig.OnDevice ->
        LOCAL_MODEL_CATALOG.find { it.id == config.modelId }?.defaultContextTokens ?: 4_096
    else -> when (mode) { /* existing STANDARD/TOKEN_SAVER/TOOLS_ONLY */ }
}
```

- [ ] **Step 3: Tests PASS → Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(assistant): wire OnDevice provider and onDevice capability (#264)

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 8: Settings UI — Local provider card + download

**Files:**
- Modify: `composeApp/.../ui/settings/AgentTab.kt`
- Modify: `composeApp/.../presentation/settings/SettingsViewModel.kt` (download actions)
- Modify: `composeApp/.../composeResources/values/strings.xml`
- Create/modify tests for SettingsViewModel download state if patterns exist

- [ ] **Step 1: Add string resources** (no hardcoded UI copy)

Examples keys: `agent_local_title`, `agent_local_download`, `agent_local_cancel`, `agent_local_delete`, `agent_local_performance_good|ok|poor`, `agent_local_avx_unsupported`, `agent_local_disk_insufficient`, `agent_local_loading_model`.

- [ ] **Step 2: SettingsViewModel API**

```kotlin
fun downloadLocalModel(modelId: String)
fun cancelLocalModelDownload()
fun deleteLocalModel(modelId: String)
fun selectLocalModel(modelId: String) // saves OnDevice config + activates LOCAL
val localDownloadState: StateFlow<LocalModelDownloadState> // from repo
val localEngineState: StateFlow<...> // if exposed
```

- [ ] **Step 3: AgentTab LOCAL card**

Special-case `KnownProvider.LOCAL`: no URL/API key fields; model list from `LOCAL_MODEL_CATALOG` with download progress, performance label, Activate when `isReady`. Hide/disable on desktop when `!hasAvx2Support()` with explanation.

- [ ] **Step 4: Manual smoke checklist** (document in commit body)

- Open Settings → Agent → On-device  
- Download E4B (or skip if no network in CI)  
- Activate → Assistant send “ping” / list hosts  

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): on-device LLM settings card and model download (#264)

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 9: PR1 hardening + AC checklist

- [ ] **Step 1: Idle release / GPU drain / MTP** — verify present in provider; add jvmTest with virtual time for idle scheduling if feasible  
- [ ] **Step 2: Run shared + composeApp common tests**

```bash
./gradlew :shared:allTests :composeApp:desktopTest --no-daemon
```

(Adjust to project’s green baseline; fix failures you introduced.)

- [ ] **Step 3: Update service-contracts.md** — short note that on-device LLM lives behind `LlmProvider` + `ILocalModelRepository` (resolve `planned: #264` if added)

- [ ] **Step 4: PR1 AC self-check** against spec § Acceptance → PR1

- [ ] **Step 5: Commit + open PR1** (title/body reference #264; Assisted-by trailer on PR body)

---

# Part B — PR2 (after PR1 interfaces stable)

> Second agent may start here. Do not begin until PR1’s `LocalLlmProvider`, catalog tiers, and `ILocalModelRepository` are on the base branch.

### Task 10: OnDeviceLarge capability + ToolRouter

**Files:**
- Modify: `ModelCapability.kt` / `ModelCapabilityTest.kt`
- Modify: `ToolRouter.kt` / `ToolRouterTest.kt`
- Modify: `AssistantViewModel.kt` (MCP budget ≤5, mode ceiling)

- [ ] **Step 1: Failing tests**

```kotlin
@Test
fun resolve_onDeviceLarge_isOnDeviceLarge() {
    assertEquals(
        ModelCapability.OnDeviceLarge,
        ModelCapabilityResolver.resolve(
            KnownProvider.LOCAL,
            "gemma-4-12b-it",
            onDevice = true,
            onDeviceTier = OnDeviceTier.LARGE,
        ),
    )
}

@Test
fun effectiveMode_onDeviceLarge_ceilingsToTokenSaver() {
    assertEquals(
        TokenSavingMode.TOKEN_SAVER,
        ModelCapabilityResolver.effectiveTokenSavingMode(
            ModelCapability.OnDeviceLarge,
            TokenSavingMode.STANDARD,
        ),
    )
    assertEquals(
        TokenSavingMode.TOOLS_ONLY,
        ModelCapabilityResolver.effectiveTokenSavingMode(
            ModelCapability.OnDeviceLarge,
            TokenSavingMode.TOOLS_ONLY,
        ),
    )
}
```

ToolRouter: LARGE allows MCP, total tools ≤ 15; E4B still no MCP / ≤ 10.

- [ ] **Step 2: Implement enum + resolver + ToolRouter branch + VM budget**

- [ ] **Step 3: PASS → Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(toolrouter): OnDeviceLarge capability for 12B on-device (#264)

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 11: DestructiveToolLookup + async/sync path switch

**Files:**
- Create: `shared/.../llm/DestructiveToolLookup.kt`
- Modify: `LocalLlmProvider` actuals
- Modify: DI to provide lookup from tool list

- [ ] **Step 1: Unit test path selection pure function**

```kotlin
fun selectLocalInferencePath(toolNames: List<String>, lookup: DestructiveToolLookup): LocalInferencePath =
    if (toolNames.isEmpty() || toolNames.none { lookup.isDestructive(it) }) {
        LocalInferencePath.AsyncAutoTools
    } else {
        LocalInferencePath.SyncManual
    }
```

- [ ] **Step 2: Wire async `sendMessageAsync` + OpenApiTool adapters calling `ToolExecutor`**

- Max tool rounds + timeout on async path  
- Emit tool-activity on `SharedFlow` collected by AssistantViewModel  
- Sync path remains PR1 behavior  

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(assistant): LiteRT dual-path inference for on-device (#264)

Assisted-by: Cursor (Grok 4.5)
EOF
)"
```

---

### Task 12: PR2 AC + manual prototype

- [ ] E4B read-only query streams tokens  
- [ ] Destructive tool still confirms via ChatEngine  
- [ ] 12B can include MCP tools within caps  
- [ ] Tests green  
- [ ] Open PR2 linking #264  

---

## Self-review (plan vs spec)

| Spec requirement | Task |
|------------------|------|
| Gradle litert android+jvm 0.15.0 | Task 1 |
| DeviceResources expect/actual | Task 2 |
| Catalog E4B+12B, SHA, performance math | Task 3 |
| ILocalModelRepository download/cancel/SHA | Task 4 |
| OnDevice config + LOCAL + serialization | Task 5 |
| LocalLlmProvider sync StreamFrame bridge | Task 6 |
| VM onDevice=true + contextChars | Task 7 |
| Settings download UI | Task 8 |
| Idle/MTP/AC/contracts note | Task 9 |
| OnDeviceLarge + ToolRouter/VM | Task 10 |
| Dual-path + OpenApiTool + tool events | Task 11 |
| PR2 AC / prototype | Task 12 |
| No ChatEngine API widen PR1 | Tasks 6–9 |
| Do not fake Full for 12B | Task 10 |

**Placeholder scan:** HF commit hashes are intentionally filled at Task 3 implement time (external lookup) — not left as runtime TBD. LiteRT schema spike is an explicit Step in Task 6.

---

## Execution handoff

**Plan complete and saved to `docs/superpowers/plans/2026-08-07-litert-lm-on-device.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — Fresh subagent per task, review between tasks, fast iteration  

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch with checkpoints  

**Which approach?**

Recommend starting with **Part A / Task 1** only; park Part B until PR1 is mergeable.
