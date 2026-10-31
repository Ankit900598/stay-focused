# Root-Cause Investigation: Strlix Promise-Scope Bug (2026-09-26)

Research/design only — no code, deployment, commits, or working-tree resets were made. Findings separate PROVEN (verified in code + phone evidence) from HYPOTHESIS (plausible, needs one more targeted trace).

Product invariant under test: a clarification choice means exactly the option the user saw and tapped. A later AI response must never change that choice's meaning. If the selected scope cannot be verified, keep Start disabled and ask again. Never silently narrow a promise and create false allows.

## 1. Root-cause analysis

**PROVEN — the Fix-1 carry-through machinery from `docs/reliability-design-2026-09-25.md` is implemented correctly on the Android side, but the backend's priority order is backwards.**

- Android (`HomeScreenState.kt:352-369`) correctly caches `lastClarificationOptionsRawJson` from the prior compile, looks up the tapped option (`findOptionInSnapshotJson`, line 488-499), and sends both `selectedClarificationOptionFullTextJson` and `priorClarificationOptionsSnapshotJson` on the follow-up compile. `PromiseCompilerClient.kt:62-83` serializes both fields onto the wire correctly. This part works as designed.
- The bug is in `backend/promiseCompilerService.js:3039-3061`. The comment at line 3017-3023 states the intended priority is `matched (fresh this-turn) > carried > snapshot`, but that is **exactly backwards from the product invariant**. Concretely:
  - Line 3040: `matched = (dto.clarificationOptions || []).find(o => id matches)` — `dto.clarificationOptions` here is the **freshly regenerated array from this turn's independent LLM call**, not turn 1's array.
  - If `matched` is truthy (the fresh regeneration happens to include an option with the same letter, e.g. "A"), the code takes the `else` branch at line 3121-3176 and uses `matched.label` / `matched.policyPreview` — **the carried/snapshot verification (`carriedOption`, `snapshotMatch`) is never consulted in this branch at all.** It's only checked at line 3048, inside `if (!effectiveMatch)` — which only runs when the fresh regeneration did *not* produce a same-ID option.
  - So: when the model's second, independent completion happens to emit *any* option under id "A" — even one with a completely different meaning than what the user tapped — the deterministic layer trusts it and stamps `"Using your choice: " + matched.label` (line 3170) with the wrong label.

**PROVEN by direct phone evidence** — not a hypothesis, this is exactly what happened:

| File | User-facing text captured |
|---|---|
| `.tmp-phone-verify-20260926/1b_ui.xml` | Turn 1 shows option A = **"All video apps and browsers"** |
| `.tmp-phone-verify-20260926b/06_after_pick.xml` | After tapping A: *"Your selection A has no earlier option list attached, so I need to confirm which video sources you mean. · Using your choice: **Browser video only**"* |
| `.tmp-phone-verify-20260926b/52_after_pick.xml` | Same promise, same tap: *"Your selection A has no previously defined option set in this request... · Using your choice: **All apps and browsers**"* |
| `.tmp-phone-verify-20260926b/62_pick.xml` | Same again: *"Your selection A arrived without its earlier option text... · Using your choice: **Browser video only**"* |

Three runs of the *identical* promise + identical tap on "A" produced two different resolved scopes. This is direct proof of nondeterministic turn-2 regeneration racing the carried/snapshot verification, matching the code-level finding above.

**Secondary contributing factor (PROVEN, lower severity) — the model itself is never told what the carried option means.** `backend/promiseCompilerService.js:3619-3631` inserts `userPayload.selectedClarificationOption = {id, label, description, policyPreview}` into the message sent to the model on turn 2, but the active system prompt (`evals/prompts/promise_compiler_v09.txt`) has **zero references** to `selectedClarificationOption`. The model has no instruction on how to use that field, which is why its own generated caution text says things like *"Your selection A has no earlier option list attached"* — the model is telling the truth about its own blind spot. This doesn't change the deterministic-layer bug (the actual authority), but it means the model's own prose is actively misleading even in cases where the deterministic layer resolved correctly (see `52_after_pick.xml`).

**HYPOTHESIS (not yet proven) — the contradictory `BLOCK:gt:30` alongside structured `min=30`.** I traced the deterministic length-direction logic (`inferMediaLengthIntent`, `promiseCompilerService.js:1766-1797`, and `reconcileMediaLengthRules`, line 1843-1879) by hand against "do not allow videos shorter than 30 minutes for next 1 hour":
- `LENGTH_POLARITY_CUE_RE` (line 1725-1726) correctly matches "do not allow" as a **block** cue (block-phrases-first ordering), so `lengthPolarity` returns `"block"`, and `blocksShorter` evaluates `true` → `minBlock=30`, `maxBlock=null`. This is **correct**, not inverted.
- `reconcileMediaLengthRules` then computes `single = {side:'min', minutes:30}` and **filters out any `side==='max'` rule** (line 1856-1861). By hand-tracing, this deterministic path should already delete a contradictory `gt:30` rule.
- This makes it unlikely the contradiction lives in the enforced `contentRules`/structured `min` field. More likely a **display-layer divergence**: the model's own free-text `userFacingConfirmation.understood` (→ Android's `understoodSummary`) is generated independently of the deterministic reconciliation pass, and can narrate the wrong direction even while `contentRules`/`min` are correct — matching `06_after_pick.xml`'s "I understood: block individual videos longer than 30 minutes for one hour."
- **Not proven** — I only have rendered UI XML, not the raw `/compile-promise` JSON body for that exact request. See item 6 for the exact capture needed to close this out.

## 2. Safe choice-identity contract

Core principle: **the option object the user tapped is the only authoritative source of scope for that pick.** IDs are ephemeral per-turn labels, not stable identifiers — never trust them across two independent model completions without verification.

1. **Authoritative source, ranked (revised from the current backwards order):**
   - **Rank 1 — Carried full option** (`selectedClarificationOptionFullText`, client-echoed, verified structurally via `resolveOptionRematerialize`). This is literally the object the user saw and tapped. Always wins if present and structurally valid.
   - **Rank 2 — Snapshot lookup** (`priorClarificationOptionsSnapshot`, indexed by id), used only if the carried object is absent (older client) but the snapshot is present.
   - **Rank 3 — Fresh this-turn match** (`dto.clarificationOptions` regenerated this call), used only as a last resort when neither of the above is available — exactly reverse the current priority order at `promiseCompilerService.js:3039-3061`.
   - **Never let Rank 3 override Rank 1/2, even when Rank 3 also matches the ID.** If a carried option is present, it wins outright; the fresh array is not consulted for that id.
2. **Cross-check, don't just trust carry-through blindly.** When both a carried option and a fresh same-ID match exist, and their `label`/`policyPreview`/`internalPolicyPreview.scopeKind` disagree, treat this as a **stale/reused-ID mismatch**: use the carried option (Rank 1 wins), but log a distinct signal (`optionVerification = "carried_over_fresh_mismatch"`) so this race is observable in telemetry instead of silently invisible.
3. **Missing carry-through + missing snapshot + no fresh match → fail closed** (already correctly implemented at line 3062-3074 — keep it).
4. **Duplicate IDs within the same options array**: treat as unusable — reject an array containing duplicate ids rather than silently picking `Array.find`'s first match.
5. **Reordered options across turns**: irrelevant under this contract, because matching is by id equality against the carried/snapshot object, never by position. No change needed as long as Rank 1/2 priority is fixed.
6. **Edited promise text between turn 1 and turn 2**: the carried option's `internalPolicyPreview` scope should still apply, but the rest of the compiled policy (duration, content rules) must come from the edited text. This appears to already be the design intent (`rematerializeDtoFromOptionPreview` only overwrites `scopePackages`/`contentBrands`/`scopeKind`) — verify it's preserved when the priority logic is reordered.
7. **Any resolution that cannot be verified against Rank 1 or Rank 2 must never silently fall through to a narrower/different scope** — extend the existing fail-closed path (line 3062-3074, "no match at all") to also cover "matched-but-disputed" (item 2), which today has no fail-closed path.

## 3. Regression matrix

| Scenario | Turn 1 options | Turn 2 (fresh regen) | Carried sent? | Snapshot sent? | Expected resolution | Current behavior |
|---|---|---|---|---|---|---|
| All video apps/browsers (this bug) | A=All apps+browsers, B=Chrome, C=YouTube | Regen emits different A | Yes | Yes | Use carried "All apps and browsers" | **BUG** — sometimes uses fresh "Browser video only" (proven) |
| Chrome only | A=Chrome, B=All apps, C=YouTube | Regen matches A=Chrome | Yes | Yes | Chrome only | Correct only by coincidence, not by design |
| YouTube app only | C=YouTube | Regen empty options | Yes | Yes | YouTube only via fallback | Correct today (fresh empty → falls into `!effectiveMatch` branch) |
| YouTube content anywhere | scopeKind=`youtube_content` | Regen mismatched scopeKind | Yes | Yes | youtube_content preserved | Same bug class — untested, add to trace catalog |
| Short-form across apps | A=All short-form surfaces, B=YouTube Shorts only | Regen swaps meaning of A/B | Yes | Yes | Carried option's `expandShortForm` wins | Same bug class — quota scope can silently flip too |
| Unrelated app scope (e.g. WhatsApp block, no clarification) | none | n/a | n/a | n/a | Path doesn't run when `selectedId` empty | Correct; not exercised |
| Conflicting fresh AI options (matched exists but disagrees with carried) | any | disagreeing match | Yes | Yes | Carried wins, log `carried_over_fresh_mismatch` | **Not implemented today** |
| Absent carry (old client) | any | fresh empty | No | maybe | Snapshot fallback if present, else fail closed | Correct |
| Mismatched carry vs snapshot | any | any | Yes (corrupted) | Yes | Should be flagged, not silently used | **Not implemented** — carried always wins unconditionally, no cross-validation |
| Stale response race (second tap before first response returns) | any | irrelevant | depends | | `understandingRequestGate`/`requestVersion` discards stale results | **Already handled correctly** (`HomeScreenState.kt:332,373-376,406-409`) |
| Offline fallback | n/a | n/a | n/a | n/a | Local parser never claims a compiler-driven scope pick | Correct (`applyLocalUnderstanding`, line 457-481) |
| Displayed vs saved vs enforced consistency | any | any | any | any | All three should agree | **Likely inconsistent today** — see §4 |

Priority for the trace catalog: rows 1, 4, 5, and 7 directly reproduce the reported bug and should become mocked-LLM backend fixtures before any code change, per the repo's "add a failing trace before fixing law" rule.

## 4. Contradictory duration rule — diagnosis and recommendation

**Diagnosis (hypothesis):** the deterministic direction logic resolves correctly for this exact phrasing (see §1), making it unlikely the enforced `contentRules`/`min` field is actually contradictory. More likely the model's free-text `understood` summary diverges from the deterministic reconciliation — it is generated independently and can narrate the wrong direction while the underlying enforcement stays correct.

**Recommendation (prevent contradictory serialized/displayed policy without touching enforcement):**
1. After `reconcileMediaLengthRules` resolves the final `side`, run a consistency check against the model's free-text `understood`/`interpretationNotes`; if they narrate the opposite direction, strip/replace that clause before it reaches Android rather than passing it through `sanitizeUserFacingCopy` untouched.
2. Add an explicit log field (`lengthDirectionConsistent: bool`) comparing deterministic direction vs. the model's stated direction, so this bug class is observable in logs.
3. Do not touch `reconcileMediaLengthRules`/`inferMediaLengthIntent` — the trace found them correct for this phrasing; changing them risks regressing already-passing polarity handling. Confirm the diagnosis first with a raw JSON capture (§6) before writing any fix.

## 5. Phased implementation plan

**Phase 0 — Reproduce deterministically.** Add a backend unit test harness that mocks the LLM completion to return two different fresh option arrays across two calls for the same input, and assert the resolved scope matches the carried option, not the fresh mismatch. Should fail against current code. New file, e.g. `backend/smoke_promise_compiler_clarification_carry.js`.

**Phase 1 — Fix backend priority order (`backend/promiseCompilerService.js`).** Rewrite lines 3039-3177: check `carriedOption`/`snapshotMatch` before `matched`; only fall back to `matched` when neither is usable. Add the mismatch-detection branch (`carried_over_fresh_mismatch`). Add duplicate-id rejection in `resolveOptionRematerialize`. Tests: Phase 0 harness + regression-matrix rows 1, 4, 5, 7.

**Phase 2 — Teach the model about the carried option (prompt-level, secondary).** Update the active system prompt (`evals/prompts/promise_compiler_v09.txt`, confirm via `PROMPT_VERSION`/`PROMPT_PATH`) to explain the `selectedClarificationOption` field and instruct the model not to re-litigate scope ambiguity when present. AI-lab track work per `NEXT_TASKS.md` routing — not bundled into the Android/backend fix.

**Phase 3 — Diagnose and fix the display/duration divergence** only after §6's raw-JSON capture confirms the diagnosis. Likely touches wherever `userFacingConfirmation.understood` is finalized relative to `reconcileMediaLengthRules`, plus `CompiledPromiseMapper.kt` if Android independently derives `understoodSummary`.

**Phase 4 — Phone acceptance traces:**
1. Repeat the exact min-30 promise 3 times, picking "A" each time. Expect identical resolved scope on all 3 runs.
2. Repeat with "B" (Chrome only) and "C" (YouTube only) 3 times each.
3. Short-form scope promise, picking each option 3 times — verify `expandShortForm` stays consistent.
4. Capture the raw `/compile-promise` JSON (not just UI XML) before and after picking an option for the min-30 promise; diff `contentRules`/`understoodSummary`/`blockedSummaries` for direction consistency.
5. Re-run the already-passing suite (stale-overlay clearing, page-change re-evaluation, hidden-controls WAIT, media-duration direction, Strict/Locked exit) to confirm no regression.

## 6. Does the last phone verification support proceeding, or is a narrower repro needed?

**A narrower repro is needed before Phase 1 lands.** I have strong UI-level (rendered XML) evidence of the scope-carry bug, but no raw backend JSON response for any of the three captured runs. Before changing the priority order, capture the raw `/compile-promise` request+response bodies (not just the Android-rendered confirm sheet) for at least one repeat of the min-30 promise, so the fix can be verified against ground truth (`dto.clarificationOptions` this-turn, `selectedClarificationOptionFullText` as sent, `optionVerification` value). This same capture also resolves the open question in §4 (display vs. enforced contradiction).

The rest of the `.tmp-phone-verify-20260926b/` suite (stale-overlay clearing, page-change re-evaluation, hidden-controls WAIT, media-duration direction, Strict/Locked exit) is unrelated to this bug class and does **not** need to be re-run before starting Phase 0/1.
