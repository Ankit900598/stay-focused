# Strlix Reliability Design — 4 P0/P1 Failures + Secondary Issues (2026-09-25)

Grounded directly in code at `C:\Users\HP\strlix` (not guesses). This is a design + Cursor-ready implementation plan — no code included. Read `PROJECT_CONTEXT.md`, `CURRENT_STATUS.md`, `NEXT_TASKS.md`, `docs/CURRENT_STATE.md`, `AGENTS.md` first.

## 1. Root-cause map by layer

| Layer | Failure | File:Line | Mechanism |
|---|---|---|---|
| **Backend compiler (statelessness)** | #1 Clarification pick lost | `backend/promiseCompilerService.js:3346-3421` | Second `/compile-promise` call re-runs the **full LLM completion from scratch** on the same raw text. The model has zero memory of the options it emitted on turn 1. `dto.clarificationOptions` used for the `selectedId` match (line 2863) is the **freshly regenerated** array from *this* call, not turn 1's array. Nothing in the wire payload (`userPayload = {userPromise, selectedClarificationOptionId}`, line 3362) carries the actual option list or its text back to the model or to a deterministic matcher. |
| **Backend compiler (silent fallback)** | #1 continued | `promiseCompilerService.js:2862-2885` | If `matched` is falsy (id doesn't line up with the newly-generated options), the code does **not always fail closed**. It calls `shouldShowUnrecognizedClarificationChoice` — if that returns `false` (e.g., zero options came back this turn, line 1783 `if (list.length===0) return false`), execution falls into `clearGhostClarificationIfConcrete(dto)` (line 2883) which can **silently unlock `canStartCommitment`** on whatever narrower scope the model happened to produce this turn — exactly "silently swapped to Browser videos only." |
| **Android (compounding statelessness)** | #1 continued | `PromiseCompilerClient.kt:24-49`, `HomeScreenState.kt:299-398` | Android also only sends the bare `selectedClarificationOptionId` string (`understandPromise`, line 335). It never sends the option's full label/description/`internalPolicyPreview` back, and never independently verifies that the *label the user saw* matches the *label the new response claims for that id*. |
| **Surface detector (Shorts misclassification)** | #2 watch page misclassified as Shorts | `SurfaceDetector.kt:423-444` (`looksLikeYoutubeShortsPlayer`) | `engagement >= 3 && (subscribe \|\| normalized.contains("shorts"))` — a normal YouTube watch page under a pre-roll ad legitimately shows Like, Dislike, Comment, Share, Subscribe (4-5 of the 5 engagement tokens) and the word "Shorts" appears anywhere on screen (bottom nav tab). That trips `engagement>=3 && contains("shorts")` even though there is no Shorts URL, no Shorts player copy, and no vertical player evidence. This is invoked from `hasPlatformShortPlayerEvidence` (line 408) for `OFFICIAL_YOUTUBE / CHROME_WEB / NEWPIPE`, which then routes into `classifyPlayerKind` → `SHORT_FORM_PLAYER`, and recommendation-shelf durations get used as if they were `CURRENT_PLAYER` (the report's `durationSource=RECOMMENDATION` BLOCK). |
| **Android UI (guardrail leak)** | #3 Adult guardrail leaks into promise preview | `HomeScreenState.kt:375-385`, `PromiseUnderstanding.kt:149-165` | `buildPromiseUnderstanding` is called with `alwaysBlockedLabels = enabledGuardrails.map(::guardrailBlockedLabel)` (`HomeScreenState.kt:377`). Inside `buildPromiseUnderstanding`, `alwaysBlockedLabels` is unconditionally appended into the **same `blocked` list** used for promise-specific blocks (`PromiseUnderstanding.kt:164`). There is no separate bucket — Life Rules and promise-specific blocks are merged before they ever reach the UI. |
| **Overlay lifecycle** | #4 Overlay disappears on rotation, returns cut off | `PhoneCodexAccessibilityService.kt:2458-2540` | The block overlay is a `WindowManager.addView` window owned by the `AccessibilityService`, sized `MATCH_PARENT`, `TYPE_ACCESSIBILITY_OVERLAY`. The service has **no `onConfigurationChanged` handling** anywhere in the file and no rotation-aware re-layout path. During a physical rotation, the window is torn down and the existing "target left foreground" away-debounce logic (`OverlayLifecycleGate.shouldClearAfterAway`, ~line 2422) only re-shows the overlay on the next qualifying accessibility event — on a static rotated screen that can take the observed 15-20s. Overlay content is also not verified scroll-safe for reduced landscape height. |

## 2. Exact design changes

### Fix 1 — Clarification pick must survive independent LLM calls

1. **Android → backend contract change.** On the second compile call, Android must send, in addition to `selectedClarificationOptionId`:
   - `selectedClarificationOptionFullText`: `{id, label, description, policyPreview, internalPolicyPreview}` — the exact option object the user tapped, already held client-side in `promiseUnderstanding.clarificationOptions` (`PromiseUnderstanding.kt:199-208`).
   - `priorClarificationOptionsSnapshot`: the full array of options shown on turn 1.
2. **Backend short-circuits instead of re-deriving scope.** When `selectedClarificationOptionFullText.internalPolicyPreview` is present and passes `normalizeInternalPolicyPreview` validation, apply it directly via the existing `resolveOptionRematerialize` path and skip asking the model to reclassify scope for that clause. This removes the nondeterminism rather than detecting it after the fact.
3. **If `internalPolicyPreview` is missing/malformed** (older client, or option had no structured preview), fail closed per `PromiseClarificationLaw` — but change the current behavior where `list.length === 0` on turn 2 silently returns "not unrecognized" and falls into `clearGhostClarificationIfConcrete`. A `selectedClarificationOptionId` that cannot be verified against the original snapshot must never take that silent pass-through branch.
4. **Never narrow scope silently.** On verification failure: `clarificationRequired=true`, `canStartCommitment=false`, surface `PromiseClarificationLaw.UNRECOGNIZED_CLARIFICATION_CHOICE`. Never fall back to a narrower package set the user never picked.
5. **Log requirement:** every second-turn compile logs `selected_option_id`, `selected_option_label_client`, `matched_option_label_server`, `verification=structured_preview|unrecognized|fallback_narrowed(FORBIDDEN)`.

### Fix 2 — Evidence ladder for YouTube player classification

- Keep Rank 1 (Shorts/Reel URL) and Rank 2 (Shorts player copy: `shorts player`, `swipe up for next`, `remix this short`, `use this sound`) — already correct.
- **Delete** the generic engagement-count branch in `looksLikeYoutubeShortsPlayer` (`engagement >= 3 && (subscribe || contains("shorts"))`). Like/Dislike/Comment/Share/Subscribe is present on every YouTube watch page, Shorts or not — no engagement-count threshold discriminates a normal watch page from a Shorts player.
- First decision on no-clock ad/pre-roll screens must be `WAIT`, not `BLOCK`. This already exists as intended behavior via `EnforcementContext.mayApplyMediaLengthBlock()` but is bypassed today because the surface classifier mislabels the page before that gate runs. Fixing the classifier fixes the downstream decision without touching the gate — general evidence-quality fix, not a YouTube-only hack.
- Recommendation durations must never populate `durationSource=CURRENT_PLAYER` — the duration parser is already correct; the bug is upstream at the surface-kind decision.

### Fix 3 — Separate "This promise blocks" from "Always-on Life rules"

- Add `alwaysOnLifeRules: List<String>` to `PromiseUnderstanding`. Remove `alwaysBlockedLabels` from the `blocked` builder in `PromiseUnderstanding.kt:164`; assign it to the new field instead.
- Confirm-sheet UI renders two sections: "This promise will block: …" and, only if non-empty, "Always on: adult content" — visually and semantically distinct, never folded together.
- Enforcement is unaffected — this is copy-only. `PermanentGuardrailsStore`/evaluator untouched.

### Fix 4 — Overlay lifecycle across rotation

- Override `onConfigurationChanged(Configuration)` in `PhoneCodexAccessibilityService`. When an overlay is active for `activeOverlayTargetPackage`, actively re-verify foreground and re-show/re-layout immediately rather than waiting on the passive away-debounce path.
- Do not lengthen/shorten `AWAY_DEBOUNCE_MS` — that band-aids the symptom. Give the overlay an active re-show trigger on rotation instead of a passive one.
- Wrap `CommitmentOverlayBuilder` output in a scrollable container so reduced landscape height never crops the primary action button. Verify in both orientations — do not assume from portrait-only testing (the host `MainActivity` is portrait-locked per `docs/CURRENT_STATE.md`, but the overlay window sits over apps like WhatsApp that are not).
- `RuntimeDiag overlayActive` (`syncOverlayActiveFlag`) should self-correct once the teardown/re-show race is fixed; add a checklist assertion that it never reads `false` for more than one debounce cycle while a block should be active.

## 3. File-by-file implementation plan for Cursor

**Backend (`backend/promiseCompilerService.js`)**
1. Extend `/compile-promise` request schema (in `server.js`) to accept `selectedClarificationOptionFullText` and `priorClarificationOptionsSnapshot`.
2. In `compilePromise(...)` (~line 3320+): when `selectedClarificationOptionId` is present, check `selectedClarificationOptionFullText.internalPolicyPreview`; if valid, apply via `resolveOptionRematerialize` and skip re-deriving scope from the model.
3. Change the `matched` branch (line 2862-2885): force fail-closed when `selectedId` is present but unverifiable, instead of falling through to `clearGhostClarificationIfConcrete`.
4. Add the `verification=` log field to the `compile_promise` log block (~line 3430).

**Android — `PromiseCompilerClient.kt`**
5. Add `selectedClarificationOptionFullText` / `priorClarificationOptionsSnapshot` params to `compile(...)`.

**Android — `HomeScreenState.kt`**
6. In `selectClarificationOption`/`understandPromise`, pass the full selected `ClarificationOption` object down to `promiseCompilerClient.compile`, not just the id.

**Android — `SurfaceDetector.kt`**
7. Delete the generic engagement-count branch in `looksLikeYoutubeShortsPlayer` (~lines 433-443); update the KDoc to match. Add the failing trace (item 8 below) first.

**Android — `PhoneSurfaceTraceCatalog.kt`**
8. Add the exact reported ad/pre-roll a11y dump as a new frozen trace before touching `SurfaceDetector.kt`.

**Android — `PromiseUnderstanding.kt`**
9. Add `alwaysOnLifeRules: List<String>` field.
10. In `buildPromiseUnderstanding`, move `alwaysBlockedLabels` out of the `blocked` builder into the new field.

**Android — confirm-sheet UI (e.g. `PromiseUnderstandingCard.kt`)**
11. Render `alwaysOnLifeRules` as its own labeled section.

**Android — `PhoneCodexAccessibilityService.kt`**
12. Override `onConfigurationChanged(Configuration)`; actively re-show/re-verify overlay on rotation.
13. Confirm `CommitmentOverlayBuilder`'s root layout is scroll-safe for reduced-height windows.

## 4. Tests to add before code changes

1. **Clarification carry-through (backend test):** compile the min-30 promise → assert option with `internalPolicyPreview` for "All video apps and browsers." Re-compile with `selectedClarificationOptionId="A"` + full option → assert resulting scope matches exactly. Re-compile with `selectedClarificationOptionId="A"` and no full-text (old client) while mocking zero options on turn 2 → assert `clarificationRequired=true`, `canStartCommitment=false` (currently would incorrectly pass).
2. **`PhoneSurfaceTraceReplayTest`:** add the reported ad-screen trace (normal watch page, pre-roll ad, full engagement row, no clock) → assert surface != `SHORT_FORM_PLAYER`, decision `WAIT`. Add a true Shorts trace as a regression guard.
3. **Guardrail separation:** build understanding for "do not allow whatsapp for 10 minutes" with an adult guardrail enabled → assert `blocked` contains only WhatsApp, `alwaysOnLifeRules` contains the guardrail label, never both merged.
4. **Overlay rotation:** device-only (WindowManager/Configuration dependency) — document as a manual phone-verification step; unit-test only the scroll-safety of `CommitmentOverlayBuilder`'s View hierarchy if feasible in isolation.

## 5. Phone verification checklist (after implementation)

1. Promise: "do not allow videos shorter than 30 minutes for next 1 hour" → pick "All video apps and browsers" → Start. Open **YouTube app** (not Chrome), play a 15-min video with active clock. Expect BLOCK — proves scope truly includes the YouTube app, not silently Chrome-only.
2. Same promise → open a normal YouTube watch page mid-ad, full engagement row visible, no player clock. Expect no overlay, `WAIT`, surface != `SHORT_FORM_PLAYER`. When ad ends and clock appears (e.g. `00:14/38:20`), expect ALLOW.
3. Promise: "do not allow whatsapp for 10 minutes" with adult guardrail enabled. Confirm sheet shows "This promise blocks: WhatsApp" and separately "Always on: adult content."
4. Start WhatsApp block → rotate to landscape mid-block. Overlay should stay/reappear within ~1s (not 15-20s), full button visible/tappable, scrollable if needed. Rotate back — overlay stays correct.
5. Re-run the existing `.tmp-phone-verify-20260925` End Commitment and Drift-count steps to confirm no regression.

## 6. Launch risk ranking

- **P0** — #1 Clarification pick silently narrowed (breaks the core promise→policy contract; user believes they're protected in a scope they aren't).
- **P0** — #2 Shorts misclassification causing false BLOCK via forbidden `RECOMMENDATION` duration source (direct violation of the documented P0 invariant in `docs/CURRENT_STATE.md`).
- **P1** — #4 Overlay rotation gap (real bypass window, but needs the tester to rotate mid-block; lower frequency for a 5-person sideload).
- **P1** — #3 Guardrail leak into preview (trust/copy bug, enforcement itself unaffected; still damaging for a product that promises legible law).
- **P2** — Secondary issues below.

**Secondary issues (flagged, not fully designed — need their own trace-first investigation):**

- `NO_ACTIVE_SESSION_ALLOW` (`PromiseEnforcementCoordinator.kt:36-47`): correct behavior (no session ⇒ ALLOW regardless of Life Rule), but the log line reads as "nothing enforced" when the guardrail path is actually separate. Fix is log/copy clarity only.
- Overlay-attempt-count vs Home "Drifts" mismatch: no drift-specific field found in `RuntimeDiagStore.kt`; likely comes from a different counter (`StudyWorldSettings.lockAttemptThreshold`) than raw overlay-show count. Needs a fresh phone trace correlating both counters before any fix.
- First End Commitment tap did nothing: `onEndCommitment = state::endCommitment` (`HomeScreen.kt:209`) is synchronous with no obvious code-level cause. Do not patch blind — capture a fresh trace of the exact double-tap sequence first (possible touch-interception by a still-attached `FLAG_NOT_FOCUSABLE` overlay window during teardown).
- Saved policy with both `min=30` and `max=30` for "shorter than 30": likely a compiler content-rule normalization bug in `promiseCompilerService.js`, separate from Fix 1. Route to AI Lab track with its own eval case, not bundled into this Android fix set.
- Compile latency 10-17s: mitigate by keeping the existing `understandingInProgress` "Understanding your promise…" state visible with a clear retry path on timeout. Do not trade latency for the Fix 1 verification step — that would reintroduce the bug being fixed.

## 7. Forbidden actions (respected in this design)

- No weakening of emergency/safety paths — none of the above touches emergency exit, safe-app allowlist, or guardrail enforcement logic (Fix 3 is copy-only).
- AI remains counsel — Fix 1's `internalPolicyPreview` short-circuit is a deterministic, code-level application of a scope the user already explicitly confirmed, not new AI authority.
- No YouTube-only hack — Fix 2 removes a wrong generic heuristic and relies on already-existing generic evidence types that apply across the whole `VideoPlatformRegistry`, not a special case for one app.
- No hiding failures with copy — Fix 3 makes the truth more visible (two labeled sections), not suppressed; enforcement behavior is unchanged.
