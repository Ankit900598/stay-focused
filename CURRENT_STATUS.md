# Current Status

Read this after `PROJECT_CONTEXT.md`.

## Current Repo

Path:

```text
C:\Users\HP\strlix
```

Current app package:

```text
com.phonecodex.app
```

The name may change later. Do not rename packages casually.

## Latest Verified Checkpoint — 2026-09-27 evening (release audit)

- Full Android unit suite, forced fresh rerun at 16:03 IST on the current tree: **884 tests, 0 failures, 0 errors, 0 skipped**. No source file changed after that run. Debug APK built 16:01, SHA-256 `E0917876…7C1E6A17`; this same APK is installed on the phone.
- All 12 backend `smoke_*.js` scripts re-run at 19:25 IST, all exit 0 (local; mock/fake endpoints, no Azure call).
- Hosted `/health` re-checked read-only at 19:24 IST: compiler `pc-lab-astra` / **`promise_compiler_v07`**, classifier `pc-lab-terra` / `v04`, vision OFF, app secret required. **Hosted is not v09.** `/health` exposes no code revision/build id, so a deploy cannot yet be proven from it. There is no staging slot; `phonecodex-backend` is the only web app.
- Quota phone test (daily limit 2, long educational allowed) on the 16:01 APK: passive screens PASS, distinct shorts count once PASS, play N+1 blocks PASS. **FAIL: pausing/resuming one Short counted it twice and then false-blocked it** (title hint picks up the "Pause video" label). Long lecture was allowed but via `COMMENTS`/passive, not `ALLOW_LONG_EDUCATIONAL`. Unreadable-quota handling is unit-tested only.
- After that test the phone was restored: all 9 preference files match the pre-test backup byte-for-byte, the new quota file was deleted, **no commitment is active**, and **Accessibility is bound** (`dumpsys accessibility`; Home says Protected, no timed commitment). Evidence: `.tmp-phone-verify-20260927-quota/` (local, ignored).
- Working tree: **73 modified + 85 untracked entries**, nothing staged, no secrets found in tracked or untracked source (keyword hits are env reads/error strings only). Local evidence folders `.tmp-*` are ignored.

### Earlier checkpoint — 2026-09-27 morning

- Full Android unit suite was forced to rerun: **833 tests, 0 failures, 0 errors, 0 skipped**.
- All 12 backend `smoke_*.js` scripts passed; `smoke_scoped_app_block.js` passed 114 assertions.
- Phone-tested a v09 Shorts-only promise using the local backend and Azure lab deployment `pc-lab-astra`: YouTube Home stayed available; the Shorts player showed the structured-surface BLOCK overlay; a regular 2:52 YouTube player stayed unobstructed. This does not prove a 40-minute lecture, Chrome, or other social apps.
- The test session was ended through the app. Session storage is empty, all seven app rules are restored to `AI_DECIDE`, and Accessibility is bound with a fresh heartbeat.
- Rebuilt/reinstalled the Azure-configured debug APK; installed SHA-256 matched the build. Local backend stopped and `adb reverse tcp:8787` removed.
- Azure `/health` confirms compiler deployment `pc-lab-astra` is still on `promise_compiler_v07`; classifier is `pc-lab-terra` / `v04`. The v09 change was **not** deployed to the hosted backend.
- At that time the working tree was **62 modified + 42 untracked** (superseded above). Local Azure/phone captures and screenshots are retained but ignored by Git.
- A local Logcat artifact (`.tmp-phone-verify-20260925/18_D_logcat.txt`) contains password-like strings. Values were not printed; keep the file local and do not share or stage it.

## Built So Far

- Android Kotlin app exists and runs on the phone.
- Accessibility-based foreground detection exists.
- Overlay-based warn/block experience exists.
- Local policy models and `PolicyEngine` exist.
- Session and permanent guardrail concepts exist.
- Backend classifier exists.
- Azure OpenAI path exists for AI classification experiments.
- AI lab has prompt/eval infrastructure for classifier and promise compiler research.
- Home / Advanced **Last decision** explains the latest ALLOW / WARN / BLOCK / LOCK without Logcat. Engineering detail + sanitized copy report. Display only; PolicyEngine is still law.

## Active Risk

The most important risk is reliability on the phone:

- overlay flicker
- false blocks
- false allows
- slow UI
- noisy accessibility text
- Chrome/YouTube edge cases
- PiP/floating video escapes
- tamper by disabling Accessibility

Every task should improve measured reliability, clarity, or speed.

Offline **Phone Surface Trace Replay** (`docs/enforcement-trace-replay.md`) freezes real Accessibility dumps and asserts ALLOW/WARN/BLOCK/LOCK/WAIT without the phone. Add a failing trace before fixing the next phone bug. This does not replace overlay / bind / Xiaomi checks.

Home now has a **Protection Reliability Gate**: Protected only if Accessibility is actually bound (alive or fresh heartbeat) **and** there is an active commitment or an enabled life rule. “Installed” is not “protecting.”

Hosted Understand is live on App Service `phonecodex-backend` (HTTPS). POSTs require `STRLIX_BACKEND_APP_SECRET`. Compiler `pc-lab-astra`, classifier `pc-lab-terra`, vision OFF. PolicyEngine is still law.

## What Is Not Product-Ready

- Full natural-language Promise Compiler enforcement is not fully product-ready.
- Vision classification is future work.
- Full phone operator AI is long-term.
- Payments/accountability penalties are future work.
- Public launch is not ready.

## How To Verify Work

Prefer this loop:

```text
build -> install/run on phone -> start commitment -> test real app -> inspect logs -> record pass/fail
```

For Android work, phone verification still matters for overlay and Accessibility bind. Run `PhoneSurfaceTraceReplayTest` first so decision-law bugs fail on a laptop.

For AI work, evals matter more than one impressive example.

## Current Default Work Split

- Android/main app: reliability, UX clarity, speed, policy wiring.
- AI lab: promise understanding, classifier evals, ambiguity handling.
- Azure/backend: stable API, cost/latency tracking, model experiments.
- Product/strategy: keep the whole system aligned with the commitment OS vision.
