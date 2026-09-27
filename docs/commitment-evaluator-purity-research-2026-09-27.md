# Commitment Evaluator Purity — Research Handoff

Date: 2026-09-27
Purpose: validate the smallest safe path from the current shared evaluator to deterministic replayable policy. This is a research brief, not authorization to change code.

## Product outcome

Strlix is a personal commitment OS: natural-language promise → structured policy → deterministic enforcement → explainable log/recovery. The policy evaluator must say what the law decides from explicit inputs; Android lifecycle, persistent stores, AI, and overlays must not be hidden inside that decision. This supports reliable commitments across apps and surfaces, not a YouTube-specific patch.

## Repository evidence checked

- `CommitmentPolicyEvaluator.evaluate(snapshot, quotaGate)` is called by the live Accessibility service and offline `EnforcementTraceEvaluator`.
- `CommitmentSnapshot.dossier` is a function (`() -> AppDossier?`). The live service supplies a store lookup closure; replay leaves it at its default `null`. Thus the same screen/promise can take different entertainment-ban paths in live use and replay.
- The evaluator invokes `quotaGate.evaluate(...)` at `LawStage.SHORT_FORM_QUOTA`.
- `ShortFormQuotaGate.evaluate` reads time/day, hydrates a ledger, mutates counts/signatures/recent-fingerprint maps, and persists a counted play before returning its decision. The evaluator is consequently not referentially transparent and cannot be replayed as a pure function.
- The phone uses `ShortFormQuotaStore`, backed by `SharedPreferences`; replay uses `SeedLedger` and seeds only a count, not signatures or recent dedupe state.
- The repository already has broad quota tests and trace cases. Preserve the present user-visible law unless an observed failure justifies a targeted change: 1..N distinct short plays count and are allowed, N+1 blocks, adult shorts block without consuming quota, long educational exceptions remain allowed, passive surfaces do not count, and repeated Accessibility snapshots must not double-count.
- Current working tree is already heavily modified (many unrelated Android/backend/test/doc paths). Do not reset, stash, blanket-stage, commit, or attribute old edits to a new task.

## Research question

What is the narrowest correct architecture that makes the policy decision deterministic and replayable while preserving exactly-once quota effects across repeated/concurrent Accessibility events, process restarts, local-day boundaries, and storage failures?

## Request for independent researcher

Read the actual current source and tests before proposing a design. Do not edit files, run Azure/model inference, access secrets, deploy, install an APK, or alter the phone. Return evidence with exact file and symbol names; distinguish verified behavior from assumptions.

Analyze these alternatives and recommend one:

1. A pure quota law: immutable quota state + explicit `nowMillis`/day key + screen evidence in; structured decision and proposed state delta out.
2. A serialized runtime adapter that loads state, calls the pure law, atomically/deterministically applies the delta, and returns the committed decision.
3. Whether durable idempotency signatures, an 8-second recent-event window, or both are necessary, and how to keep replay semantics equivalent to production.

Answer specifically:

- What must the evaluator receive so live and trace replay use identical inputs, including a resolved immutable `AppDossier?` and quota snapshot?
- Where should the transaction boundary live, and how should a persistence failure behave? Do not accidentally ALLOW an N+1 play, count adult content, or claim a count that was not applied.
- How can same-event snapshots be deduplicated without treating two distinct videos with identical/ambiguous titles as one play? What happens when there is no stable identity?
- What are the concurrency assumptions of `AccessibilityService.inspectAndEvaluate` today? Verify threading/callers; do not assume single-threaded behavior.
- What does midnight mean (device-local zone, clock changes, timezone changes), and which time value must be frozen for each evaluation?
- How should process death, stale writes, duplicate callbacks, app switching, rejected decisions, and replay of multiple events be represented?
- Should trace input model complete quota state (count + signatures + dedupe identity) or should there be an explicit event-sequence replay? Explain trade-offs.
- Identify any existing behavior where eagerly resolving dossier or quota state would add cost on non-relevant screens; preserve lazy *work* via preflight if needed, but not hidden nondeterministic reads inside the law.
- Provide a file-by-file minimal migration and rollback boundary. No broad policy-order rewrite.

## Required test matrix

Include test names and expected state/decision transitions for:

1. First distinct short under quota; repeat same event inside debounce; same event after debounce; distinct short; exact N; N+1.
2. Adult short at 0, below N, and at N; count/signatures unchanged for adult.
3. Passive home/search/recommendation/comments; no count or signature changes.
4. Long educational video; no quota consumption; an explicitly short-only policy must not gain that exception.
5. Same title in two different apps; same app with distinct video IDs/titles; missing/ambiguous identity.
6. Midnight and local-zone change; injected clock and replay reproduce same day key.
7. Process restart restores count and dedupe state; corrupted persisted state has explicit safe behavior.
8. Two concurrent identical events and two concurrent distinct events near N; exactly one count for duplicate and no quota overshoot.
9. Ledger load/save failure, stale write, and retry; the displayed decision/count must agree with durable or explicitly in-memory state.
10. Live/replay parity with the same dossier and quota fixture; repeated evaluation of the same immutable input yields byte-for-byte equivalent output and no side effects.

## Deliverable format

Return (A) verified current-state map, (B) recommended design with data shapes/pseudocode, (C) rejected alternatives and why, (D) file-by-file implementation scope, (E) tests/exit gates, and (F) open product choices only if code cannot infer them. Keep the proposal compatible with Android’s current storage/lifecycle and do not add a cloud dependency.

## Local action status

The Claude desktop/browser integration was unavailable in this session (`unsupported Codex auth method: apikey`; no browser/window inventory returned). This report records the source-backed handoff so it can be sent to the intended research chat when access is available. No code, Azure resource, phone, or app state was changed here.
