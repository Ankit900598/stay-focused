# Security Addendum to v2: Compound-Clause Pre-Verification Bypass + Bridge Provenance (2026-09-26)

Research/design only. No code edited, nothing deployed, no Azure settings changed, no API calls made, no phone touched. This addendum extends `docs/v09-typed-scope-clarification-loop-2026-09-26-v2.md` — it does not replace it. `compound_combine` stays out of the scope-mapping table (v2 §6 already excludes it; this addendum does not add it back).

## 1. Confirmed: compound B/C narrows and compiles *before* any verification runs

Re-read directly from `backend/promiseCompilerService.js:3997-4130` (`compilePromise`). Exact sequence, as written today:

```
4015-4034  selectedClarificationOptionId, selectedClarificationOptionFullText,
           priorClarificationOptionsSnapshot, priorClarificationPromiseText
           are read straight from the untrusted request body — no check yet.

4036-4039  compileText = resolveCompoundCompileText(trimmed, selectedClarificationOptionId)
           compilingSingleClause = compileText !== trimmed

4046-4061  if (selectedClarificationOptionId && !compilingSingleClause) { ...forward carry to model... }
           — i.e. carry is forwarded to the MODEL only when NOT a compound narrowing.

4063-4110  the model is called and parsed with compileText (already narrowed for B/C).

4112-4129  normalizeCompiledPromise(parsed, compileText, {
             selectedClarificationOptionId: compilingSingleClause ? "" : selectedClarificationOptionId,
             selectedClarificationOptionFullText: compilingSingleClause ? null : selectedClarificationOptionFullText,
             priorClarificationOptionsSnapshot: compilingSingleClause ? null : priorClarificationOptionsSnapshot,
             priorClarificationPromiseText: compilingSingleClause ? undefined : priorClarificationPromiseText,
           })
```

**`resolveCompoundCompileText(rawText, selectedOptionId)` itself (`:1312-1319`)** does exactly this and nothing more:
```js
function resolveCompoundCompileText(rawText, selectedOptionId) {
  const text = String(rawText || "").trim();
  const id = String(selectedOptionId || "").trim().toUpperCase();
  if (!id || id === "A" || !needsCompoundIntentClarification(text)) return text;
  const exclusive = splitCompoundClauses(text).filter(isExclusiveCompoundClause);
  if (id === "B") return exclusive[0] || text;
  if (id === "C") return exclusive[1] || exclusive[0] || text;
  return text;
}
```

**Confirmed, precisely: this function is a pure re-derivation from the *current request's own* `rawText` and the *bare, unverified* `selectedOptionId` string.** It never touches `priorClarificationOptionsSnapshot`, never touches `selectedClarificationOptionFullText`, never touches `priorClarificationPromiseText` — those three fields exist in scope in `compilePromise` at this point (`:4022-4034`) but are **not read until line 4120-4128, after the model has already been called on the narrowed text and after `compilingSingleClause` has already forced them to `null`/`undefined`/`""` for the normalize step.** By the time anything resembling verification could run (`resolveClarificationSelection` inside `normalizeCompiledPromise`), `selectedClarificationOptionId` has been reset to `""` for the compound-narrowing case, so `resolveClarificationSelection` is **never even called** for a B/C pick — not "called and it happens to pass," structurally never invoked at all.

**Concrete exploit shape, proven by code inspection, not merely hypothesized:** any request with `promise: <current text>, selectedClarificationOptionId: "B"` — with no snapshot, no carried option, no prior-promise-text, or with any of those fields wrong/stale/absent — causes the server to silently compile "the first exclusive clause of whatever text is submitted now," with the same effective result as if the caller had legitimately seen and picked "Only the first" on a real turn 1. This holds even if:
- the promise text was edited since any options were shown (there is no `priorClarificationPromiseText` check on this path at all — that field is discarded, never compared, for a compound pick);
- no options were ever shown to this caller in this session (no snapshot presence check on this path);
- the id doesn't correspond to anything the caller actually saw (no snapshot-membership check on this path).

This is a strictly worse gap than the A-path clarification-loop bug in v1/v2 — those failed *closed* (stuck asking again). This fails **open**: it silently produces a *different compiled policy* than the one whose options were (or weren't) actually shown, with no error, no re-ask, and no log signal distinguishing it from a legitimate pick.

## 2. Safe sequence: verify identity first, narrow and compile second

**Principle:** reuse the existing verification primitives (`clarificationOptionKey`, `promiseBindingKey`, snapshot-uniqueness, carried-vs-snapshot agreement — all already implemented for `resolveClarificationSelection`, `:1487-1533`) but as a **separate, sibling check** run *before* `resolveCompoundCompileText`, not as a modification to `resolveClarificationSelection` itself. Do not generalize `resolveClarificationSelection` to tolerate "no preview" — that function's contract (require a rematerializable preview) is correct and must stay strict for every family that *does* carry a scope/policy preview (Google-video, shorts, `compound_combine` itself). Compound options B/C structurally carry no `internalPolicyPreview` (`:1345-1358` — only `label`/`description`/`policyPreview`), so a *different*, narrower check is needed: prove **identity** (this is the exact option object the user was shown), not **rematerializability** (there is nothing to rematerialize — the "preview" is just "which clause of text").

**New function (design only): `verifyCompoundClauseSelection({ selectedId, snapshot, carried, priorPromiseText, promiseText })`**, called in `compilePromise` immediately after the four fields are read (`:4015-4034`) and *before* `resolveCompoundCompileText` is ever invoked, whenever `needsCompoundIntentClarification(trimmed)` is true and `selectedClarificationOptionId` is non-empty (covers A/B/C uniformly — A doesn't narrow, but identity-verifying it too costs nothing and closes the same class of gap for A's own promise-text-changed case, which today is *also* only checked inside `normalizeCompiledPromise`, i.e., after the (no-op, for A) narrowing already happened):

1. **Promise-text-unchanged, first and unconditional:** if `priorPromiseText` was supplied, `promiseBindingKey(priorPromiseText) !== promiseBindingKey(trimmed)` → fail closed immediately, reason `promise_text_changed`. If the caller supplied *no* `priorPromiseText` at all, treat as **fail closed**, not as "unverifiable, skip the check" — a compound clause pick without a prior-promise-text anchor is exactly the forgery shape in §1 and must never be allowed to proceed to narrowing.
2. **Snapshot presence and uniqueness:** `snapshot` must be a non-empty array; every entry's `clarificationOptionKey` must be present and unique (mirrors `resolveClarificationSelection:1510-1514` exactly) → else fail closed, reasons `no_verifiable_choice` / `snapshot_duplicate_ids`.
3. **Exact one matching entry:** exactly one snapshot entry's key equals `selectedId` → else fail closed, reason `snapshot_missing_id`.
4. **Carried-vs-snapshot agreement, identity-only (no preview to fingerprint):** if `carried` is present, its `(id, label, description)` — normalized the same way `sanitizeUserFacingCopy`/trim already would — must equal the matched snapshot entry's `(id, label, description)` byte-for-byte after normalization → else fail closed, reason `carry_snapshot_mismatch`. (This is intentionally a plain-field comparison, not a `scopeFingerprint`/`typedScopeFingerprint` comparison — those are policy/scope fingerprints and don't apply to B/C, which carry no policy preview at all.)
5. **Server-side re-derivation match (this is also §3's provenance answer — see below):** re-run `inferCompoundIntentClarification(trimmed)` fresh, server-side, against the now-confirmed-unchanged promise text; the resulting option at `selectedId` must exist and its `(label, description)` must equal the verified snapshot entry's `(label, description)` → else fail closed, reason `snapshot_not_reproducible`. This step is what actually proves the snapshot entry the client echoed back is the same one the server itself would have produced for this exact promise text — not merely that the client's own two copies (`carried` vs `snapshot`) agree with each other, which by itself proves nothing about server origin (a forger can make two fields agree with each other trivially).

Only if all five checks pass does `compilePromise` proceed to call `resolveCompoundCompileText(trimmed, selectedId)` and compile the narrowed text. **If any check fails, `compilePromise` must not narrow, must not call the model on partial text, and must return the equivalent of today's `unrecognized_fail_closed` shape** — `clarificationRequired=true`, `canStartCommitment=false`, `optionVerification="unrecognized_fail_closed"`, `optionVerificationReason` set to the specific reason above, with the (server-freshly-rebuilt, since the snapshot may itself be unverified) compound options re-offered so the user can pick again. This mirrors the existing fail-closed shape used elsewhere in the file (`:3531-3550`) rather than inventing a new response shape.

**Composability with v2 §1 (explicitly preserved, per the task's instruction):** this check answers *"is this narrowing-and-compile step legitimate?"* — a data-flow/identity question, decided before any compilation happens. v2 §1's fix answers a *different* question decided *after* compilation succeeds: *"once the (now-legitimately-narrowed) text is compiled, where does the resulting typed surface scope come from?"* — answer: `validateAndCanonicalizeScope(parsed.scope, ...)`, never the option's own `.scope`/`.internalPolicyPreview`. These are two independent, composable gates in series, not one merged mechanism: gate 1 (this addendum) can reject a forged/stale/mismatched B/C pick without ever knowing or caring what "scope" means; gate 2 (v2 §1) can correctly source scope from the model's compile of a clause that gate 1 has already proven legitimate. Neither gate can substitute for the other.

## 3. Bridge provenance: a matching `scopeKind` string does not prove server origin — fix by re-derivation, not by a trust tag

**Confirmed vulnerability, traced exactly:** `normalizeClarificationOptions` (`:841-882`) is called on **both** the model's raw output (`clarificationOptions = normalizeClarificationOptions(parsed.clarificationOptions)`, `:3128`) **and** the three deterministic builders' own output (`normalizeClarificationOptions(googleClarify.options)` `:3139`, `normalizeClarificationOptions(shortsClarify.options)` `:3181`, `normalizeClarificationOptions(compoundClarify.options)` `:3194`). The function itself carries no notion of which caller invoked it, and it explicitly passes through `item.internalPolicyPreview || item.policyPreviewStructured` (`:866-869`) verbatim from whatever object it was given. **Nothing stops the model from emitting `clarificationOptions: [{ id: "A", label: "...", internalPolicyPreview: { scopeKind: "all_video_apps", ... } }]` in its own raw JSON**, which would pass through `normalizeClarificationOptions` identically to a genuinely server-built option. A v2-§3-style plain allowlist check on the `scopeKind` string alone cannot distinguish these two cases — it only proves the *string* is one of the six known-safe values, not that the *object* was constructed by the deterministic JS builder rather than echoed/hallucinated by the model.

**Rejected fix: a plain origin tag (e.g. `option.scopeOrigin = "server_builder"`).** This does not close the gap on its own, for a reason specific to this system's statelessness: at **turn 2**, the tag the server would check arrives *inside the client's own request body* (`selectedClarificationOptionFullText`, `priorClarificationOptionsSnapshot` — both read directly from `options.*` at `:4022-4030`, i.e., attacker-controlled input). A tag is only trustworthy if something the attacker cannot forge ties it to the server's turn-1 output — a plain string field fails that requirement; the client (or a hallucinating model whose output later gets echoed back by the client) can simply include the same tag on a fabricated option.

**Recommended fix: prove provenance by re-derivation, not by a trust marker — reusing exactly the mechanism already designed in §2 step 5.** Since `inferGoogleVideoClarification`, `inferShortFormScopeClarification`, and `inferCompoundIntentClarification` are pure, deterministic functions of the promise text alone (no model call, no randomness), the server can — *after* confirming the promise text is unchanged (the same `promiseBindingKey` check used in §2 and already present in `resolveClarificationSelection`) — simply **re-run the relevant builder function itself and compare its fresh output to the carried/snapshot option being relied on**, at the point `applyScopeContract`'s bridge would otherwise fire (v2 §3's read sites `:745` and `:753`). Concretely, extend the allowlist check from v2 §3 from *"does `opt.internalPolicyPreview.scopeKind` match one of the six strings"* to:

> *"does `opt.internalPolicyPreview.scopeKind` match one of the six strings, **and** does re-running the corresponding builder (`inferGoogleVideoClarification`/`inferShortFormScopeClarification`/`inferCompoundIntentClarification`) against the verified-unchanged promise text produce an option at the same `id` whose `(label, description, internalPolicyPreview)` matches `opt`'s byte-for-byte (after the same normalization used elsewhere)?"*

Only if **both** conditions hold does the bridge fire. This requires no new secret, no HMAC, no server-side session state, and no change to the stateless request/response architecture — it is the same "recompute deterministically and compare" pattern the codebase already uses for scope fingerprinting (`typedScopeFingerprint`, `scopeFingerprint`) and for the new §2 check, just applied one layer earlier, at the bridge itself rather than only at the compound-narrowing gate. It correctly rejects a model-hallucinated or client-forged option carrying a coincidentally-matching `scopeKind` string, because re-running the real builder against the real (verified) promise text will either produce `null` (the triggering phrase/pattern isn't actually present) or produce a *different* label/description than the forged one — either way, the equality check fails and the bridge correctly refuses to fire, falling through to "no server-authored match → check `opt.scope` directly → else `UNRESOLVED`," exactly as v2 §3 already specifies for non-allowlisted options.

**Round-trip survival, explained precisely (why this doesn't need a new Android-side field):** the re-derivation check above needs no marker to survive the Android snapshot/carry round trip, because it doesn't rely on a marker at all — it relies on the *promise text* surviving unchanged (already required, already checked via `priorClarificationPromiseText`/`promiseBindingKey`) and the *option's own visible fields* (`label`, `description`, `internalPolicyPreview`) surviving unchanged. Both already do, by construction, for reasons specific to how the client caches and echoes them:
- `HomeScreenState.kt:352-369` (Android) caches `lastClarificationOptionsRawJson` as the **verbatim JSON string** of the entire `clarificationOptions` array from the last successful compile — not a typed Kotlin model that could drop unrecognized fields, a raw string.
- `findOptionInSnapshotJson` (`HomeScreenState.kt:488-499`) extracts the matching entry via `org.json.JSONArray`/`JSONObject.toString()` — again raw JSON passthrough, not field-by-field reconstruction, so every field the server originally sent (including `internalPolicyPreview` in full) survives untouched.
- On the way back into the server, `selectedClarificationOptionFullText`/`priorClarificationOptionsSnapshot` are read as generic JS objects (`typeof options.selectedClarificationOptionFullText === "object"`, `:4022-4030`) with no schema-based field stripping.
- `stripPackageLeaksFromDto`'s own option-rebuilding map (`:2578-2592`) uses `{...o, label: ..., ...}` — object-spread **first**, so any field not explicitly overwritten (including `internalPolicyPreview`'s full structure, modulo the one `appliesTo` sub-field it deliberately scrubs) survives that transform too.

So the re-derivation check works with what already reliably round-trips today — `label`, `description`, `internalPolicyPreview` — and needs no new field, no signature, and no Android-side change at all. This is the smallest safe fix for the provenance gap, consistent with not expanding the design.

## 4. Tests

All tests below are additive to v2 §7's list (which still applies unchanged) and must run under both the `--write-fixtures` offline harness style already used by `smoke_clarification_choice_identity.js` and as unit-level backend tests, with **no live model call** (the re-derivation check and the identity check are both pure functions and must be testable without Azure).

| # | Case | Expected |
|---|---|---|
| 1 | Compound B picked with correct, unchanged snapshot + matching carried option + matching prior promise text | Passes §2's five checks; narrows and compiles the first clause; `canStartCommitment` reflects that compile's own gates (not forced true or false by this check alone) |
| 2 | Compound B picked with **no** `priorClarificationOptionsSnapshot` at all | Fail closed, reason `no_verifiable_choice` — **this is the exact case that silently succeeds today (§1's proven exploit) and must now fail** |
| 3 | Compound B picked with a snapshot, but **no** `priorClarificationPromiseText` | Fail closed — per §2 step 1's "no anchor supplied = fail closed" rule, not "skip the check" |
| 4 | Compound B picked with a snapshot and prior-promise-text, but the **current** promise text has been edited since | Fail closed, reason `promise_text_changed` |
| 5 | Compound B picked with a snapshot containing duplicate ids | Fail closed, reason `snapshot_duplicate_ids` |
| 6 | Compound B picked with a snapshot that does not contain id "B" at all | Fail closed, reason `snapshot_missing_id` |
| 7 | Compound B picked with a carried option whose label/description disagree with the snapshot's own "B" entry | Fail closed, reason `carry_snapshot_mismatch` |
| 8 | Compound B picked with a snapshot entry for "B" whose `(label, description)` do **not** match what `inferCompoundIntentClarification` would produce fresh from the (verified-unchanged) promise text — simulating a forged or tampered snapshot entry | Fail closed, reason `snapshot_not_reproducible` — this is the test that proves §2 step 5 / §3's re-derivation check, not just steps 1-4 |
| 9 | Compound C, mirroring tests 1-8 for the second clause | Same expectations, second-clause variant |
| 10 | Compound A ("Both together") run through the same new pre-check | Passes (A never narrows) and still separately exercises v2 §1's `parsed.scope`-sourcing fix downstream — confirms the two gates compose without interfering |
| 11 | A Google-video, shorts, or `compound_combine` option whose `internalPolicyPreview.scopeKind` matches one of the six allowlisted strings, but which was placed in the model's raw `clarificationOptions` output (simulating model hallucination or replay of a prior server response) | The bridge must **not** fire — re-running the corresponding builder against the actual promise text either returns `null` (phrase not present) or a differing label/description — falls through to `opt.scope` / `UNRESOLVED`. This directly tests §3. |
| 12 | Same as 11, but the promise text genuinely *does* contain the triggering phrase (e.g., literally contains "google video") and the model's raw option happens to exactly reproduce what the builder would produce | Bridge fires — this is intentionally the same outcome as if the builder had produced it, since re-derivation success is the actual proof of correctness, regardless of which code path constructed the object first; document this as a deliberate, acceptable convergence, not a hole. |

**Carried forward from v1/v2, unchanged and still required:** v07 full regression (140/202 assertions), the existing A-path stale/reordered/duplicate/mismatched/promise-edited fail-closed cases, and the unknown-`scopeKind`-not-in-allowlist fail-closed case. This addendum's tests are additive, not substitutive.
