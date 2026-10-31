# Root Cause + Minimal Fix Design: v09 Typed-Scope Clarification Loop (2026-09-26)

Research/design only. No code edited, nothing deployed, no Azure settings changed, no API calls made, no phone touched. Evidence below comes from: reading the current working tree, running the repo's own offline `node` smoke scripts locally (no network calls — verified before running), and reading files already on disk from prior read-only Azure inspection sessions (`.tmp-deployed-inspect-20260926/`, `.tmp-azure-deploy-*`, `.tmp-azure-candidate-20260926-candidate-typedscope/`). A `MANIFEST.md` already present in the candidate folder independently corroborates this report's root cause (see §6) — it was not written by this pass, and is cited as corroborating evidence, not taken on faith.

## Summary

Under `promise_compiler_v09` (the prompt version required for `scopeContract: "v1"`), picking a clarification option that came from one of three **server-side, hardcoded** option builders — Google-video ambiguity, bare-"shorts" scope ambiguity, and "two worlds" compound-intent ambiguity — permanently fails to clear clarification and never enables Start. This is proven by running the repo's own smoke tests locally with `PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09`:

```
node backend/smoke_clarification_choice_identity.js   -> AssertionError: "all-video pick: clarification cleared", true !== false
node backend/smoke_promise_compiler_normalize.js      -> AssertionError: twoWorldsPicked.canStartCommitment, false !== true
```

Both pass cleanly (140 and 202 assertions) under the default `promise_compiler_v07`. Root cause: these three builder functions attach only the **legacy** `internalPolicyPreview` field to their options, never the new typed `scope` field that the v1 contract's final gate (`applyScopeContract` / `enforceScopeStartGates`) requires. The final gate runs *after* the legacy resolver has already correctly verified the user's pick, and unconditionally overwrites that correct verification because it only understands `.scope`, not `.internalPolicyPreview`.

Separately, live-Azure evidence (already gathered) shows the currently deployed backend runs `promise_compiler_v07` by default and — independent of that — the deployed `promiseCompilerService.js` predates the entire scope-contract-v1 mechanism. A **candidate** fix package exists locally (`.tmp-azure-candidate-20260926-candidate-typedscope/`) but is **not deployed**, and its own manifest already documents this exact bug as a known blocker before any Azure change is made.

---

## 1. Root cause for each reproduced failure, tied to exact file/line references

### Failure 1 — `smoke_clarification_choice_identity.js`: "all-video pick: clarification cleared" (`true !== false`)

**Reproduced locally:**
```
cd backend
PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09 node smoke_clarification_choice_identity.js
```
Fails at the very first `assertVerified` call (line 223 of the smoke file), on the "all video apps and browsers" pick for the min-30 promise.

**Cause, traced exactly:**

1. `normalizeCompiledPromise()` runs the existing legacy-shape resolver first. For a picked option, `resolveClarificationSelection()` (`backend/promiseCompilerService.js:1487-1533`) verifies the id against the snapshot/carried option using `resolveOptionRematerialize()` (which reads `internalPolicyPreview`) and correctly returns `{ ok: true, reason: "snapshot_and_carry_verified", option, preview }`. The caller then sets `dto.canStartCommitment = !dto.requiresStrongerConfirmation` (line 3586) — **correct**, at this point Start should be enabled.
2. Unconditionally, as the **last** step of `normalizeCompiledPromise()` (comment at `promiseCompilerService.js:3602`: *"Last: nothing after this may reopen Start on a missing/unresolved v1 scope"*), `applyScopeContract(dto, parsed, { promptVersion, promiseText, selectedId, verifiedChoice, carriedOption })` runs (line 3603-3609).
3. Inside `applyScopeContract` (`promiseCompilerService.js:731-775`), since `ctx.promptVersion === "promise_compiler_v09"`, `scopeContractFor` returns `"v1"` (line 732, 475-477), so the `contract !== "v1"` early-return (line 734) is skipped.
4. Line 752-753: `const chosen = ctx.verifiedChoice; scope = chosen ? validateAndCanonicalizeScope(chosen.scope, promiseText) : unresolvedScope(...)`. `ctx.verifiedChoice` is exactly the option object `resolveClarificationSelection` already verified in step 1 — but that option (built by `inferGoogleVideoClarification`, see §2) has **no `.scope` field**, only `.internalPolicyPreview`. So `chosen.scope` is `undefined`.
5. `validateAndCanonicalizeScope(undefined, promiseText)` (`promiseCompilerService.js:679-681`, delegating to `validateScope`) returns an `UNRESOLVED` scope object (per the documented contract: "Missing / null / non-object -> UNRESOLVED").
6. `isEnforceableScope(scope)` (line 437-445) is `false` for `UNRESOLVED`. `enforceScopeStartGates(dto, scope)` (line 453-462) therefore forces `out.canStartCommitment = false; out.clarificationRequired = true;` and this is `Object.assign`-ed onto `dto` at line 773 — **silently overwriting the correct result from step 1.**

This is not a missing feature; it is two independent verification passes disagreeing, where the second (correct-by-its-own-contract) pass has no way to recognize a legacy-verified pick as valid.

### Failure 2 — `smoke_promise_compiler_normalize.js`: `twoWorldsPicked.canStartCommitment` (`false !== true`)

**Reproduced locally:**
```
PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09 node smoke_promise_compiler_normalize.js
```
Fails at line 429 of the smoke file, picking option "A" ("Both together") for the compound promise `"allow only calculus, allow only monk 4 hrs"`.

**Identical mechanism**, different producer: `inferCompoundIntentClarification()` (`promiseCompilerService.js:1322-1360`) builds option `A` with `internalPolicyPreview: { scopeKind: "compound_combine", ... }` (line 1339-1344) and **no `.scope`**. Same chain as Failure 1: legacy resolver verifies the pick correctly, `applyScopeContract` then nulls it out because `chosen.scope` is absent.

### Why `smoke_scope_contract_v1.js` passes (90/90) while the other two fail

That suite tests `applyScopeContract`/`validateAndCanonicalizeScope`/`enforceScopeStartGates` directly with fixtures that **already carry a `.scope` field** (i.e., it tests the v1 gate in isolation, correctly). It does not exercise the three legacy option-builder functions, so it never hits the missing-field case. Passing that suite says "the v1 gate is internally correct"; it does not say "every option producer feeds it what it needs" — which is exactly the gap.

---

## 2. Inventory of option producers and whether each can produce a valid v1 scope

| # | Producer | File:Line | Populates `.scope`? | Can satisfy v1 today? |
|---|---|---|---|---|
| 1 | Model's own raw `clarificationOptions` (from the v09 prompt, when the model complies) | `promiseCompilerService.js:3128` → `normalizeClarificationOptions` (`:841-882`) | **Yes, passed through if present** (`:855-858, 880`) | Conditionally — only if the model actually emits a well-formed `scope` object per option. Not independently verified in this pass (would require a live/mocked model call); the candidate's own manifest does not claim to have tested this path either. |
| 2 | `inferGoogleVideoClarification` ("Google video" ambiguity: Chrome/browser vs all apps vs YouTube-only) | `promiseCompilerService.js:1179-1230`, wired at `:3139` | **No** — only `internalPolicyPreview` | **No.** Root cause of Failure 1. |
| 3 | `inferShortFormScopeClarification` (bare "shorts" ambiguity: all short-form vs YouTube-Shorts-only) | `promiseCompilerService.js:1236-1287`, wired at `:3181` | **No** | **No.** Same defect class, not yet reproduced by name in the smoke output above but structurally identical — confirm with a targeted test before considering it fixed (see §4, row for this family). |
| 4 | `inferCompoundIntentClarification` ("two worlds" — allow-only X, allow-only Y) | `promiseCompilerService.js:1322-1360`, wired at `:3194` | **No** | **No.** Root cause of Failure 2. |
| 5 | `authoritativeOptions` (the snapshot echoed back to the user on re-ask / after a verified pick) | `promiseCompilerService.js:3525-3529, 3544, 3577` | **Passes through whatever the snapshot entries already had** — inherits the defect of whichever producer built them | Same as source producer. |

**Conclusion:** every deterministic, server-authored clarification family (2, 3, 4) is currently incompatible with the v1 gate. Only options that originate from the model itself (1) have a chance of satisfying it, and that path is unverified in this pass (no live model call was made, per the task's constraints).

---

## 3. Minimal recommended contract/data-flow fix

**Principle:** do not ask the model, the prompt, or Android to change. Give the deterministic option builders (2, 3, 4) a **typed-scope equivalent of their existing `internalPolicyPreview`**, using the same `expandScopePreset()` machinery the v1 contract already has (`promiseCompilerService.js:531+`), so the v1 gate can validate them without altering their trigger conditions, their legacy fields, or their v07-visible behavior.

**New, small function** (design, not code): `legacyPreviewToTypedScope(internalPolicyPreview, identityHint)` — a server-side mirror of what Android's `LegacyScopeAdapter.kt` already does for the same class of problem (browser-scope review, 2026-09-26): map a known `scopeKind` string (`chrome_browser`, `all_video_apps`, `youtube_only`, `short_form_all_surfaces`, `youtube_shorts_only`, `compound_combine`) to the corresponding typed `ScopeKind` + `expandScopePreset()` call, e.g.:
- `chrome_browser` → `BROWSER_VIDEO` (the candidate prompt's own new kind — `host=BROWSER, identity=VIDEO_ANY, packageMatch=CLASS_RESOLVED`, matching the browser-scope review's fix for "don't pin to Chrome").
- `all_video_apps` → `VIDEO_CLASS_ANY_HOST`.
- `youtube_only` → `APP_ONLY` with `identity=YOUTUBE` (**deliberately preserve the existing app-only conflation here** — do not "fix" it as part of this change, per the same discipline the Android `LegacyScopeAdapter` review already established: a scope-semantics change and a bug-fix-for-a-different-bug must not ship in the same patch).
- `short_form_all_surfaces` → `SHORT_FORM_ANY_HOST`.
- `youtube_shorts_only` → `APP_ONLY` with `identity=YOUTUBE` (or the closest existing kind — do not invent a new one for this change).
- `compound_combine` → keep as-is; this one is not a video/browser scope at all, and `applyScopeContract`'s compound branch already special-cases `scopeKind === "compound_combine"` via `optionScopeKind` elsewhere in the file — confirm that branch still short-circuits before `.scope` is even checked, rather than adding a scope object for it.

**Wire it into exactly one place:** inside `applyScopeContract`, wherever `opt.scope` (or `chosen.scope` / `parsed.scope`) would otherwise be `undefined` but the corresponding option/entry has a resolvable `internalPolicyPreview`, call the bridge before falling to `unresolvedScope`. Concretely, at the three read sites (`:745`, `:753`, `:771`), change the pattern from
```
validateAndCanonicalizeScope(opt.scope, promiseText)
```
to
```
validateAndCanonicalizeScope(opt.scope || legacyPreviewToTypedScope(opt.internalPolicyPreview), promiseText)
```
(and equivalently for `chosen`/`parsed`). This is additive — `opt.scope` still wins when present (never overridden), and nothing about the legacy `internalPolicyPreview`/`scopePackages`/`scopeKind` fields changes for v07 consumers.

**Example option JSON before/after** (Google-video option A, abbreviated):

```json
// Before (today, under v09 — no .scope, fails closed on pick)
{
  "id": "A",
  "label": "Videos inside Chrome / browser",
  "internalPolicyPreview": { "scopeKind": "chrome_browser", "appliesTo": "Chrome / browser video", "packages": [{"packageName":"com.android.chrome","appLabel":"Chrome"}] }
}
```
```json
// After (bridge-derived scope attached at applyScopeContract time, not stored on the option itself)
{
  "id": "A",
  "label": "Video in any browser",
  "internalPolicyPreview": { "scopeKind": "chrome_browser", ... },
  "scope": {
    "version": 1, "kind": "BROWSER_VIDEO", "identity": "VIDEO_ANY",
    "include": [{ "id": "i1", "host": "BROWSER", "identity": "VIDEO_ANY", "surfaces": ["PLAYER_ANY"], "packages": [], "packageMatch": "CLASS_RESOLVED" }],
    "exempt": [], "confidence": 0.9, "evidenceSpans": ["Google video"]
  }
}
```

**Selection/carry behavior — unchanged.** `resolveClarificationSelection`'s fingerprint-based carry/snapshot verification (`:1487-1533`, `scopeFingerprint`/`typedScopeFingerprint`) already does exactly the right thing and needs no change — it is what correctly verified the pick in both failing smoke tests before `applyScopeContract` clobbered the result. The bridge only fills in the *input* to the v1 gate, not the verification logic itself. This directly satisfies design constraint #1 (never weaken fail-closed behavior, never let fresh model output substitute for the verified pick — the bridge runs on the *verified* option, never on a fresh unverified one).

**Trapped-loop guarantee (constraint #2):** once every producer in §2 rows 2-4 has a bridgeable `scopeKind`, every option a user can actually see and tap resolves to either an enforceable typed scope or — only for a genuinely unrecognized/unbridgeable `scopeKind` — an explicit `UNRESOLVED` with `clarificationRequired=true` and **fresh, re-askable options** (already the existing re-ask behavior at `:3543-3545`). No option becomes permanently unselectable; the loop in Failures 1/2 was not "correctly unresolved," it was "incorrectly re-unresolved after being correctly resolved."

---

## 4. Test matrix

| # | Case | Expected under fix | Currently |
|---|---|---|---|
| 1 | Google-video option A (Chrome/browser) picked, v09 | Verified, `canStartCommitment=true`, `scope.kind=BROWSER_VIDEO` | **Fails today** (Failure 1's sibling — same producer, option A of that family is the one actually exercised by the smoke test) |
| 2 | Google-video option B (all apps), v09 | Verified, `scope.kind=VIDEO_CLASS_ANY_HOST` | Fails today (this exact case is the reproduced Failure 1) |
| 3 | Google-video option C (YouTube only), v09 | Verified, `scope.kind=APP_ONLY, identity=YOUTUBE` | Fails today by the same mechanism (not separately reproduced in this pass — same code path, same defect) |
| 4 | Bare-"shorts" option A (all short-form), v09 | Verified, `scope.kind=SHORT_FORM_ANY_HOST` | **Not yet reproduced by a dedicated assertion** — same producer defect (§2 row 3); add a smoke case before claiming fixed |
| 5 | Bare-"shorts" option B (YouTube Shorts only), v09 | Verified, `scope.kind=APP_ONLY` (or agreed mapping) | Same, unreproduced but predicted |
| 6 | Compound "Both together" (A), v09 | Verified, `canStartCommitment=true` | **Fails today** (reproduced Failure 2) |
| 7 | Compound "Only the first" / "Only the second" (B/C), v09 | Verified per existing compound-clause behavior | Not reproduced in this pass — check whether B/C carry a resolvable scope kind at all today (they may be a different code path entirely; confirm before assuming parity with A) |
| 8 | Model-native option with a well-formed `.scope`, v09 | Verified via the existing `opt.scope` pass-through, bridge not invoked | Untested in this pass — no live/mocked model call made |
| 9 | Stale/reordered options (id "A" means something different on turn 2's fresh model output) | Fail-closed via `resolveClarificationSelection`'s snapshot authority — unaffected by this fix | Already passes today under v07 per `smoke_clarification_choice_identity.js`; must still pass after the fix (regression guard) |
| 10 | Missing/corrupt scope on a *genuinely* unbridgeable `internalPolicyPreview` (unknown `scopeKind` string) | `UNRESOLVED`, `clarificationRequired=true`, fresh options offered, never silently narrowed | Must add a fixture for this — confirms the bridge doesn't become a second silent-guess path |
| 11 | v07 full regression | 140 + 202 assertions unchanged, byte-identical decisions | Must re-run both smoke scripts under **no** `PROMISE_COMPILER_PROMPT_VERSION` env override after the fix — this is the actual "preserve legacy behavior" gate |
| 12 | v09 full regression | `smoke_scope_contract_v1.js` still 90/90; `smoke_clarification_choice_identity.js` and `smoke_promise_compiler_normalize.js` reach their v07 assertion counts (140, 202) when run under v09 | The stated exit bar for this fix |
| 13 | Successful v09 Start | End-to-end: promise → clarification → pick → `canStartCommitment=true` → (not exercised here) Android maps/binds/persists — this last leg remains the separately-tracked, still-unverified gap from the prior investigation (`docs/typed-scope-not-reaching-phone-2026-09-26.md`) | Cannot be fully closed by this backend-only fix alone |

---

## 5. Exact commands to verify locally, and rollout gates before any Azure or phone test

**Local verification (safe — no network, no secrets, matches how this report's evidence was gathered):**
```
cd backend
node smoke_scope_contract_v1.js                                              # expect 90/90, unaffected
node smoke_promise_compiler_normalize.js                                     # expect 202/202 (no env override = v07)
node smoke_clarification_choice_identity.js                                  # expect 140/140 (no env override = v07)
PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09 node smoke_promise_compiler_normalize.js        # today: fails; after fix must reach 202
PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09 node smoke_clarification_choice_identity.js     # today: fails; after fix must reach 140
```
Add the new fixtures from §4 rows 4, 5, 7, 8, 10 as explicit assertions in these same files (or a new `smoke_scope_contract_option_bridge.js`) before considering the fix complete — several of the "currently fails" rows above are predicted from the producer inventory, not yet independently reproduced by name.

**Rollout gates, in order — do not skip or reorder:**
1. All commands above green, on the *current* local `backend/` tree, with the bridge implemented.
2. Re-run the Android-side equivalence/unit suite (`:app:testDebugUnitTest`) unaffected — this fix is backend-only, but confirm no Android test asserts on the *absence* of a `scope` field for these option families in a way that would now break.
3. Rebuild the candidate package (mirroring `.tmp-azure-candidate-20260926-candidate-typedscope/MANIFEST.md`'s method: diff against the verified-live baseline, SHA-256 every file, confirm the diff is limited to the intended files) — do this **before** touching any Azure setting.
4. Only after 1-3: deploy the candidate code (this changes the running JS, not yet the prompt version — live behavior stays v07/`scopeContract: "legacy"` until the next gate, per the manifest's own "SAFE with current live config" finding).
5. Only after 4 is confirmed live and healthy (via the sanitized startup-log check already established in the prior investigation): set `PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09` as an App Service setting.
6. Only after 5: restart, confirm the sanitized startup log shows `promptVersion: "promise_compiler_v09"`.
7. Only after 6: run one safe, minimal `/compile-promise` diagnostic call for each option family in §4 (short synthetic test phrases, not real user promises) and confirm `scopeContract: "v1"` + a valid `scope.kind` + `canStartCommitment` behaving correctly for both the initial ask and the follow-up pick.
8. Only after 7: proceed to phone testing (per the phone acceptance items already listed in `docs/typed-scope-not-reaching-phone-2026-09-26.md` and `docs/browser-scope-enforcement-review-2026-09-26.md`).

This sequencing exists specifically because gates 4 and 5 are independent failure modes that this investigation's evidence shows have been conflated before (a deploy happening without the version switch, or vice versa) — collapsing them risks re-creating exactly the confusion this and the prior report exist to resolve.

---

## 6. Versioning question: is it safe to edit the existing `promise_compiler_v09.txt` content in place?

**Recommend: no — bump to a new version identifier (e.g. `promise_compiler_v10`) rather than editing `v09.txt` in place, for these reasons:**

1. **`ALLOWED_PROMPT_VERSIONS` and `scopeContractFor` key on the literal filename**, not a content hash. Two different byte-for-byte contents (16,233 deployed vs. 16,892 local candidate) already both carry the name "v09" in this repo's history — this is the exact silent-drift pattern that made this investigation necessary in the first place (compare `docs/typed-scope-not-reaching-phone-2026-09-26.md`, which had to reverse-engineer *which* v09 was actually live from log evidence rather than reading it off a version label).
2. **The candidate change is not prompt-wording-only.** It introduces a new scope-kind (`BROWSER_VIDEO`) that must exist in the JS enum/preset table (`expandScopePreset`, `scopeOptionLabel`, Android's mirrored `ScopeKindPresets`) for the prompt's new instructions to mean anything. A prompt version and a schema version changing together is a contract change, not a copy edit — exactly the kind of change the project's own versioning discipline (`ALLOWED_PROMPT_VERSIONS` existing as an explicit allow-list at all) implies should get a new identifier.
3. **Low practical risk of *not* bumping, but only by coincidence:** since the deployed server has never successfully run v09 in production (proven in the prior investigation), there is no live telemetry, eval baseline, or user-facing history tied to "v09" that a content edit would silently invalidate. This is a mitigating fact, not a reason to skip the version bump — the next person to compare a future v09 edit against "the v09 baseline" will have no history to compare against either way, so start that history correctly now rather than repeating the ambiguity a third time.
4. If a full version bump is considered too heavy for this specific fix, the **minimum acceptable alternative** is to keep the name `v09` but pin and log its exact SHA-256 at startup (mirroring the discipline the candidate's own `MANIFEST.md` already applies manually) so a future "which v09 is live" question is answered by one log line instead of a multi-file forensic diff like this one.

---

## What remains unverified, and why

- **Whether the model's own raw options (§2 row 1) reliably emit a valid `.scope`.** No live or mocked model call was made in this pass (task constraints exclude paid API calls). The candidate's `smoke_scope_contract_v1.js` tests the *gate*, not the *model's actual compliance rate* with the new prompt's scope-emission instructions.
- **Whether bare-"shorts" (§2 row 3) and compound B/C (§4 row 7) reproduce the identical failure by name** — predicted from the producer inventory and the shared code path, but not independently reproduced with their own assertion output in this pass. Add the fixtures in §4 before treating them as confirmed rather than predicted.
- **Whether the candidate package's SHA-256-verified "byte-identical to live wwwroot" baseline claim (its own `MANIFEST.md`, point 3) is still accurate at the moment this report is read** — that verification was performed by whoever built the candidate, at the time they built it; live Azure state can change between then and when this report is acted on, so re-verify immediately before any deploy rather than trusting the manifest's timestamp indefinitely.
- **Whether Android's mapper/binder/store correctly handle a genuine `scopeContract: "v1"` response once the backend actually produces one** — this is the separate, already-flagged gap from `docs/typed-scope-not-reaching-phone-2026-09-26.md` and is not addressed by this backend-only fix.
