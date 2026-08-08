# Plan: LiteRT model download timeouts, progress UI, import/resume (#469)

**Date:** 2026-08-08  
**Branch:** `fix/469-litert-download` (from `origin/main`)  
**Scope:** medium — single PR (no stack)

## Research verdict

| Checkpoint | Result |
|------------|--------|
| OkHttp/`llm` timeouts | Confirmed: `named("llm")` has **no** `HttpTimeout`. Android OkHttp defaults ~10s read → mid-stream stall → `OTHER`. |
| HF CDN resume | Confirmed: Hub + CDN `Accept-Ranges: bytes`; `Range: bytes=0-0` → **206** + `Content-Range`. Resume on original Hub URL (follow redirects). |
| CMP float format | Confirmed broken: `%1$.1f GB`. Use `%1$s` + Kotlin-formatted size (issue-293). `%1$d%%` progress likely OK. |
| SAF / disk | Stream copy; ~2× free space if Uri→temp then import. Prefer stream into `.partial` when possible. |

## Out of scope

- #468 AICore / Gemini Nano  
- #264 PR2 async / OnDeviceLarge  
- Kai private `filesDir`, E2B catalog, Play Asset Delivery  

## Implementation (ordered commits)

### A — Timeouts, strings, richer errors

1. **`AssistantDiModule`**: add `single(named("litertDownload"))` with  
   `requestTimeoutMillis = INFINITE`, `connectTimeoutMillis = 30_000`, `socketTimeoutMillis = 120_000`. Wire `LocalModelRepository` to it (keep `llm` unchanged).
2. **`LocalModelDownloadErrorKind`**: add `TIMEOUT`; map `SocketTimeoutException` / timeout messages → TIMEOUT; HTTP non-2xx → NETWORK; keep DISK/HASH/OTHER. Log via `DebugLog.e`.
3. **Strings + UI**:  
   - `agent_local_size_gb` → `%1$s GB` (or full preformatted `%1$s`)  
   - Format size in Kotlin (`"%.1f".format(...)`) before `stringResource`  
   - Distinct strings for TIMEOUT / NETWORK  
4. **Tests**: DI wiring smoke (client qualifier used), error-kind mapping; string formatting unit test where practical.

### B — Resume

1. **`LocalModelFiles.openSink(path, append)`** (android + jvm).  
2. **`runDownload`**: if `.partial` exists with `length > 0`, hash existing bytes, send `Range: bytes={n}-`.  
   - **206**: append; continue hash.  
   - **200**: discard partial, rewrite from 0.  
   - Other: NETWORK error (keep partial).  
3. **Cancel / network fail**: **keep** `.partial` (behavior change); Idle on cancel; do not delete on timeout/network.  
4. Delete `.partial` only on HASH fail or after successful rename.  
5. **Tests**: MockEngine 206 resume; 200 restart; cancel leaves partial.

### C — Import + optional Downloads scan

1. **`ILocalModelRepository.importFromPath(modelId, absolutePath)`** — stream copy + SHA; DISK check; Ready on success.  
2. **UI**: Import button on not-downloaded rows; Android `OpenDocument` (stream Uri → temp under cache/model root, then `importFromPath`); desktop file chooser via existing platform patterns if cheap, else Android-first.  
3. **Optional Downloads scan**: `expect`/`actual` or Android-only helper looks for catalog `fileName` under public Downloads; if found + readable, show “Use existing file” → `importFromPath`. Best-effort (no new dangerous permissions).  
4. **Tests**: import hash OK/fail; fake repo methods for VM.

## File list

| File | Change |
|------|--------|
| `shared/.../di/AssistantDiModule.kt` | `litertDownload` client |
| `shared/.../local/ILocalModelRepository.kt` | TIMEOUT, `importFromPath` |
| `shared/.../local/LocalModelRepository.kt` | timeouts path, resume, import, logging |
| `shared/.../local/LocalModelFiles*.kt` | append sink; optional openSource |
| `composeApp/.../LocalModelUi.kt` | TIMEOUT message map |
| `composeApp/.../OnDeviceProviderCard.kt` | size format, import/use-existing UI |
| `composeApp/.../AgentTab.kt` / `SettingsViewModel.kt` / `SettingsScreen.kt` | wire import + scan |
| `composeApp/.../strings.xml` | size/progress/error/import strings |
| `composeApp/.../fakes/FakeLocalModelRepository.kt` | new API |
| `shared/.../LocalModelRepositoryTest.kt` | resume/import/timeout tests |
| Android-only import/scan helpers under `composeApp/androidMain` or `shared/androidMain` | SAF + Downloads |

## Test plan

```bash
./gradlew --no-daemon :shared:jvmTest --tests '*LocalModelRepositoryTest*'
./gradlew --no-daemon :composeApp:desktopTest --tests '*LocalModelUiMapperTest*'
```

Manual smoke (PR checklist): Wi‑Fi E4B download on phone; cancel+resume; size/progress labels; SAF import; optional Downloads adopt.

## Acceptance mapping

| Criterion | Task |
|-----------|------|
| No spurious mid-stream timeout / clear kind | A |
| Resume after cancel/blip | B |
| Size GB + progress % | A |
| SAF import + hash | C |
| Optional Downloads | C |
| Unit tests | A–C |
| PR smoke checklist | PR body |

## No migration needed

Existing partials from older builds are unused (old code deleted them on cancel/fail). New resume is forward-compatible.
