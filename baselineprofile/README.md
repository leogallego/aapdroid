# `:baselineprofile`

Macrobenchmark generators for Baseline Profiles and Startup Profiles (#214).

## What this does

- Precompiles hot paths (Baseline Profile) via ART
- Feeds DEX layout optimization (Startup Profile) for faster cold starts
- Journeys: app launch → Dashboard, Templates, Chat (Jane AI), Settings

`androidx.profileinstaller` ships the profiles in release APKs. `dexLayoutOptimization`
is enabled in `:app`.

## Generate locally

Requires KVM + a Gradle Managed Device (Pixel 6 / API 34 AOSP):

```bash
./gradlew --no-daemon :app:generateReleaseBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
```

Generated rules land under:

- `app/src/release/generated/baselineProfiles/baseline-prof.txt`
- `app/src/release/generated/baselineProfiles/startup-prof.txt`

Commit those files after a successful run so CI/release builds consume them without
re-running the emulator.

**Full tab coverage** needs an AAP instance already saved on the device (DataStore).
Without credentials the generator still covers cold start → Auth, which is enough
for Startup Profile / DEX layout rules.

## CI

`.github/workflows/baseline-profiles.yml` runs on `workflow_dispatch` (and a weekly
schedule). It uploads generated profiles as artifacts; merge them into `main` after
review. Generation is **not** hooked into every PR or `assembleRelease` (avoids
flaky emulator jobs and keeps desktop/KMP targets unaffected).
