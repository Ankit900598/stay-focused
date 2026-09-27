# Cursor Task — Pure Commitment Evaluation Boundary v1

## Outcome

Advance Strlix’s core product loop: natural-language promise → confirmed structured policy → deterministic phone behavior → truthful feedback/recovery. This is a cross-app runtime foundation, not a YouTube/WhatsApp special case. AI remains counsel; deterministic policy remains law.

## Current repo evidence (checked 2026-09-27)

- `CommitmentPolicyEvaluator.evaluate(snapshot, quotaGate)` is called by `PhoneCodexAccessibilityService.inspectAndEvaluate()` and by offline `EnforcementTraceEvaluator`.
- `CommitmentSnapshot.dossier` is a closure. Live supplies `appDossierStore.merged(packageName, screenText)`; replay supplies no dossier. Identical trace inputs can therefore take different entertainment-ban decisions.
- `CommitmentPolicyEvaluator` calls `ShortFormQuotaGate.evaluate()` for a configured quota. The gate computes the local day, loads ledger state, mutates count/signature/recent-dedupe maps, and persists before returning. The evaluator is not pure and repeat evaluation can change state.
- `ShortFormQuotaStore` persists count + day signatures with `SharedPreferences.apply()`. `EnforcementTraceEvaluator.SeedLedger` seeds only the count, not dedupe signatures. Existing `ShortFormQuotaGateTest` verifies clock dedupe, count boundaries, adult-not-counted, and cross-app quota behavior.
- Live calls are currently made directly inside `AccessibilityService.onAccessibilityEvent()` or via `mainHandler.post`/the content debounce runnable; classifier networking is on `classifierExecutor` and results are posted back to `mainHandler`. Preserve this separation; verify every direct evaluator caller before relying on a serialization assumption.
- The repo is already heavily dirty. Preserve all pre-existing changes. Do not reset, stash, overwrite, blanket-stage, commit, or claim unrelated paths as yours.

## Task

Make the policy decision reproducible from explicit immutable inputs and outputs, while preserving today’s user-visible behavior. Keep the edit focused on the evaluation boundary and the minimum callers/tests it requires. Do not use model calls or introduce services.

### 1. Make evaluator inputs explicit

- Replace the lazy `dossier: () -> AppDossier?` in `CommitmentSnapshot` with a resolved immutable `AppDossier?` (or an equally explicit value object). The law must not read Android stores, the clock, or mutable runtime state.
- Add an immutable quota-state input that contains every value needed by the law for this event: local day key already resolved, current count, and the persisted dedupe/signature state needed to decide whether this is a duplicate. Pass event time explicitly if the law needs it; never call `System.currentTimeMillis()` or derive the machine zone inside pure policy.
- The law returns a `CommitmentDecision` plus an explicit proposed quota transition/delta. Re-evaluating the same snapshot + quota state + event time must yield equivalent output and must not mutate input, store, or gate state.
- Keep Android collection, store reads/writes, foreground lifecycle, event debouncing, overlays, logging, and AI outside the law.

### 2. Put effects behind one runtime boundary

- Introduce the smallest runtime adapter needed by live evaluation: resolve dossier and quota state; call the pure law; apply the quota transition exactly once; return the committed decision. Do not build a general transaction framework.
- Preserve quota behavior: distinct short-form plays 1..N are allowed/count once; N+1 blocks; adult content blocks without consuming quota; passive surfaces never count; long-educational exception remains exactly as currently specified; cross-app daily count remains shared.
- Preserve the existing 8-second Accessibility-thrash dedupe and same-day signature behavior unless tests demonstrate a defect. Do not silently redefine “same video” identity. When evidence is insufficient for stable identity, do not claim certainty or invent a cross-video dedupe key; preserve the current safe behavior and report the limitation.
- Verify and document actual caller threading. If the live calls are all serialized on the service main thread, use that existing serialization rather than adding locks/executors. If any caller can race, use a narrow atomic boundary for read → pure evaluation → commit. Do not move disk I/O onto every raw accessibility event.
- Store failure must not produce a false count in UI/logs or allow a play beyond N. Define an explicit conservative failure result and test it. `SharedPreferences.apply()` does not return a durable-write result; do not pretend it provides transactional durability. Keep any stronger durability change minimal and evidence-based.
- Replay must receive equivalent dossier/quota fixtures and run the same pure law. For sequence-sensitive quota cases, add an event-sequence replay helper rather than pretending isolated snapshots prove idempotency.

### 3. Preserve the existing law and product contract

- Do not alter precedence among safe/emergency apps, permanent guardrails, session/scope, structured surface gates, quota/media rules, app rules, or advisory AI except where required to move state effects outside the law.
- AI cannot override safe/emergency handling, permanent guardrails, confirmed deterministic rules, or quota exhaustion.
- Don’t add app-specific exceptions, new cloud APIs, vision, or prompt changes.
- Do not install on phone or alter phone settings in this implementation task. Produce a separate phone-verification checklist after code and tests pass.

## Required regression coverage

Add/update tests for:

1. Same immutable evaluator inputs produce equal decision + quota delta on repeated calls and do not mutate any input.
2. First short count, same short repeated within 8 seconds, repeat after debounce, same signature later that day, distinct short, exact N, N+1.
3. Adult short at 0, below N, and at N: BLOCK, count/signatures unchanged.
4. Passive home/search/recommendation/comments: no count/signature changes.
5. Long educational exception: allowed and not counted; preserve current short-only semantics.
6. Cross-app shared daily count; two distinct events at N−1 must not overshoot N if the runtime can receive concurrent calls.
7. Midnight/day-key transition with explicit injected instant + zone/day key; replay reproduces the same result.
8. Process recreation restores count and dedupe/signatures; malformed stored state has explicit tested handling.
9. Storage read/write failure: no phantom counted result and no fail-open past quota. State precisely what `SharedPreferences.apply()` can and cannot guarantee.
10. Live/replay parity for dossier-sensitive entertainment decision and quota decision with identical explicit fixtures.
11. Structural regression test: the live service and replay both call the same law/runtime seam; they do not independently reimplement policy gates.
12. Verify all current `CommitmentSnapshot` and evaluator callers compile and that existing policy order tests remain green.

## Required validation

Run focused quota/evaluator/trace tests first; then the full Android unit suite, all backend smoke scripts only if shared backend files were changed, and `:app:assembleDebug`. Report exact commands, current counts, failures, and changed files. Do not repeat historical totals as current results.

Do not deploy Azure, edit `.env`, use secrets, call a model, stage, or commit. Report pre-existing dirty files separately from files changed for this task.

## Completion response

Return:

1. Root cause and the exact boundary changed.
2. Before/after data flow (live and replay).
3. Files changed by this task only.
4. Test/build commands and exact results.
5. Remaining quota identity/durability limitations; do not call an in-memory or `apply()` write “transactional.”
6. Phone-verification checklist with expected decisions and log codes, but do not perform it in this task.
7. State clearly whether this advances the commitment runtime toward conversational assistant/voice and separately consented connectors, rather than presenting this as the whole product.
