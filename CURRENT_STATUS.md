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
