# Root Cause + Minimal Fix Design: v09 Typed-Scope Clarification Loop — Revision 2 (2026-09-26)

Research/design only. No code edited, nothing deployed, no Azure settings changed, no API calls made, no phone touched. This revision corrects five issues raised against the first draft (`docs/v09-typed-scope-clarification-loop-2026-09-26.md`) after re-inspecting the exact DTO flow, `canonicalizeScope`, and both platforms' preset tables. The reproduced root cause (§1 of the prior report) stands unchanged and is not repeated in full here except where the correction requires re-stating it.

## Corrected finding, up front

The most important correction: **`canonicalizeScope()` (`backend/promiseCompilerService.js:601-660`) discards any clause structure it is handed and rebuilds it from scratch via `expandScopePreset(kind, identity, sites, packages)`** (comment at line 598-599: *"validateScope() output -> the one clause set the preset table defines for its kind. Model-written clauses are discarded"*). This means my prior draft's proposed bridge — constructing an ad hoc `scope` object with custom `surfaces`/`include` clauses — would silently be thrown away and rebuilt from the *kind name alone*. Any fix must work by choosing the right **kind name** (and, where necessary, adding a new one), not by constructing custom clause JSON. This single fact changes the design for points 1 and 2 below.

---

## 1. Compound intent ("Both together" / "Only the first" / "Only the second") — corrected design

**Confirmed by re-reading the code, not assumed:** `rematerializeDtoFromOptionPreview` (`promiseCompilerService.js:1621-1627`) already special-cases `scopeKind === "compound_combine"` and returns `true` **without touching `dto.scopePackages`/`contentRules` at all** — it is a deliberate no-op under the legacy (v07) contract. `resolveOptionRematerialize` (`:1373-1378`) does the same. **This confirms the user's framing exactly: "Both together" was never designed as a surface-scope choice.** It is a *promise-text resolution* choice — it decides which clause(s) of a compound sentence get compiled at all — and the actual enforcement scope for whatever gets compiled comes from the ordinary compiler pipeline (`parsed.scope`, the model's output for the resolved text), not from the clarification option itself. Options B ("Only the first") and C ("Only the second") don't even carry an `internalPolicyPreview` (`:1345-1358` — only `label`/`description`/`policyPreview`), confirming they were never meant to answer a scope question either.

**Why my prior draft's "bridge to a fake scope" idea is wrong, and why a "global/unrestricted" sentinel is also wrong:** Attaching *any* scope to the compound option itself — invented, widened, or a `LEGACY_ALL`-style "matches everything" sentinel — would be answering a question ("does this screen fall under this promise?") that this option was never asked to answer, and risks exactly the "widen scope from an unrelated pick" failure mode this whole investigation is about. Separately, I confirmed the "everything matches" sentinel Android uses (`LegacyScopeAdapter.LEGACY_ALL`, `PACKAGE_PINNED` with empty `packages` + `packageMatch=CLASS_RESOLVED`) **does not transfer to the backend**: `canonicalizeScope`'s `PACKAGE_PINNED` branch (`:655-658`) explicitly rejects empty `packages` (`return fail("MALFORMED_CLAUSE")`). The backend has no existing kind that canonicalizes successfully with no identity and no packages except `HOST_WITH_CATEGORY_EXEMPTIONS` (`:652-653`, `canonicalIdentity = null`, no check) — and that kind's preset is hardcoded to adult/movie/video-on-browser clauses (`:578-586`), which is not "unrestricted," it's a specific, unrelated three-clause set. So there is **no existing backend kind that means "unrestricted / not a scope question."**

**Minimal safe fix — do not add a new kind, use the branch that already exists for exactly this purpose:** `applyScopeContract` should detect the compound-intent family and route it to the **same branch already used when there is no selection at all** (`promiseCompilerService.js:770-772`, `scope = validateAndCanonicalizeScope(parsed && parsed.scope, promiseText)`), instead of trying to read `chosen.scope` (`:753`). Concretely: when the verified pick's `internalPolicyPreview.scopeKind === "compound_combine"` (option A) — or, for options B/C, when the resolution is known to have come from the compound-intent path at all (see open question below) — treat it exactly like "no clarification scope override; derive scope from what was actually compiled" rather than "this option declares a scope." This requires threading one new boolean into the `ctx` object passed to `applyScopeContract` at the call site (`:3603-3609`), e.g. `isCompoundIntentPick`, set by the caller wherever it already knows `needsCompoundIntentClarification(promiseText)` was true for this request. No new `ScopeKind` value, no new preset row, no change to `expandScopePreset` or `ScopeKindPresets` on either platform.

**Open question, explicitly unresolved:** for option A ("Both together"), `parsed` at the point `applyScopeContract` runs is the compile of the **full original two-clause promise** (no `resolveCompoundCompileText` narrowing, since `id === "A"` short-circuits that function to return the full text unchanged — `promiseCompilerService.js:1312-1319`), so `parsed.scope` should reflect the model's honest attempt at the combined scope. For options B/C, `resolveCompoundCompileText` narrows to just that clause **before the model is even called** (`compilingSingleClause` flag, `:3606-3609` region referenced in the prior report), so `parsed.scope` there is a compile of the *narrowed* clause — which is correct in principle but was **not independently verified in this pass**: no smoke fixture in the current test files exercises a B or C pick end-to-end under v09. This must be added (see §6, test row 3) before treating B/C as fixed.

---

## 2. "YouTube Shorts only" — corrected design, `APP_ONLY` is confirmed unsafe as specified

**You were right to block this.** Re-reading both preset tables directly:
- Android `ScopeKindPresets.appOnly()` (`app/.../scope/ScopeKindPresets.kt:57-79`): `surfaces = setOf(ScopeSurfaceClass.ANY)` — **hardcoded**, no parameter to narrow it.
- Backend `expandScopePreset`, `case "APP_ONLY"` (`promiseCompilerService.js:534-538`): `["ANY"]` — **also hardcoded**.

Given `canonicalizeScope` rebuilds from the kind name alone (see the corrected finding above), there is **no way to make `APP_ONLY(identity=YOUTUBE)` mean "just Shorts within the YouTube app"** — the kind itself always expands to "the whole app, every surface." I did not attempt to prove downstream content-rule gating "saves" this (e.g., whether `ShortFormQuotaGate`/a `contentType=short_form_video` rule independently restricts the actual BLOCK/count action to short-form surfaces regardless of scope's own surface axis) — that would require tracing `EntertainmentBanGate`/`MediaLengthEnforcement`/`ShortFormQuotaGate` interaction in depth, which is out of scope for this pass and, per your instruction, the correct response to "can't prove it's safe" is not to ship it, not to spend more effort proving/disproving it retroactively.

**Smallest accurate typed representation:** neither platform's preset catalog has a row for "one named app, short-form surface only." This **is** a schema change — a new `ScopeKind` value is required, e.g. `APP_ONLY_SHORT_FORM` (naming TBD by whoever implements), with preset:
```
host = NATIVE_APP, identity = {given}, surfaces = [PLAYER_SHORT], packageMatch = PINNED
```
This must be added as one new row in **both** `ScopeKindPresets.expand`/`appOnly()`-adjacent function (Android) and `expandScopePreset`'s switch (backend) — per design rule 6 ("adding a phrasing = a new preset row, never a new code branch"), this is exactly the kind of change that convention exists to make cheap and safe. It also needs a `canonicalizeScope` branch (mirroring the existing `APP_ONLY` identity check at `:624-628`) and a `scopeOptionLabel` case (`:704-716`) and Android's mirrored label function.

**Do not repurpose an existing kind's identity/parameter space to fake this** (e.g., do not overload `SHORT_FORM_ANY_HOST` with a package filter — that kind's whole meaning is `host=ANY_HOST`, cross-app; forcing it to one package would be exactly the kind of silent semantic drift this investigation exists to stop).

---

## 3. Bridge trust boundary — explicit allowlist and read-site rules

**Allowlist (exhaustive — these are the only `internalPolicyPreview.scopeKind` string literals that appear in the three deterministic, server-authored builder functions today):**

| `scopeKind` literal | Producer | File:line |
|---|---|---|
| `chrome_browser` | `inferGoogleVideoClarification` (option A) | `promiseCompilerService.js:1196-1200` |
| `all_video_apps` | `inferGoogleVideoClarification` (option B) | `:1210-1214` |
| `youtube_only` | `inferGoogleVideoClarification` (option C) | `:1222-1226` |
| `short_form_all_surfaces` | `inferShortFormScopeClarification` (option A) | `:1265-1270` |
| `youtube_shorts_only` | `inferShortFormScopeClarification` (option B) | `:1278-1283` |
| `compound_combine` | `inferCompoundIntentClarification` (option A) | `:1339-1344` |

**Read-site rules:**

| Read site | May use the bridge? | Why |
|---|---|---|
| `applyScopeContract` line `:745` (mapping every `dto.clarificationOptions[i].scope` for display) | **Yes, allowlist-only.** For each option, if `opt.scope` is absent, check `opt.internalPolicyPreview.scopeKind` against the six-entry allowlist above; if it matches, bridge to the corresponding typed kind for display/label purposes. If `opt.internalPolicyPreview.scopeKind` is present but **not** in the allowlist (e.g., it came from a raw-model-output option, or from `resolveOptionRematerialize`'s other regex-over-label/description fallback path — see below), leave `scope` as `UNRESOLVED`. Never bridge a model-authored option. | This map runs before any pick is made — it is safe to attach a display-only typed scope to a deterministic option the server itself constructed, but not to anything sourced from free text. |
| `applyScopeContract` line `:753` (`chosen.scope`, the verified pick) | **Yes, allowlist-only, and only after `resolveClarificationSelection` has already verified the pick** (which it has, by construction — `ctx.verifiedChoice` is only ever set at `:3553` inside the `applied` branch of the snapshot/carry verification block). | This is the actual fail-closed-sensitive site. Verification order matters: the bridge must run **after**, never instead of, the existing fingerprint-based snapshot/carry check — it supplies the *typed-scope input* to a pick that is already independently proven to be the exact option the user tapped. It does not weaken that proof. |
| `applyScopeContract` line `:771` (`parsed.scope`, used when there is no clarification selection at all, or — per §1's fix — reused for the compound-intent family) | **No bridge, ever.** `parsed` here is raw model output for the whole (or compound-narrowed) promise text. This must go through `validateAndCanonicalizeScope` directly — model-declared typed scope, or `UNRESOLVED`. Bridging here would be exactly "deriving scope from raw model output," which is forbidden. |
| `resolveOptionRematerialize`'s **other** fallback path (not shown in this report's file/line detail, referenced in the browser-scope review as regexing `label + description + policyPreview` for phrases like `/youtube only|only youtube/`, `/all video apps/`, `/chrome|browser/`) | **No bridge.** This path exists for options that arrived without a structured `internalPolicyPreview` at all — i.e., by construction, *not* one of the six allowlisted server-authored kinds. Any option resolved through this path must remain `scope = UNRESOLVED` under the v1 contract, even though it may still work under the legacy v07 contract (which tolerates prose-derived scope, per the pre-existing, separately-tracked "AI prose is load-bearing for scope" issue in `docs/scope-semantics-v1.md §1.2(4)`). Fixing that path's own reliance on prose is out of scope for this fix. |

**Net effect:** the bridge is a narrow, six-entry lookup table (`legacyScopeKindToTypedKind: Record<string, ScopeKind>`), never a general-purpose "make something out of whatever text is available" mechanism. Anything not in the table stays `UNRESOLVED` and re-asks — satisfying constraint #2 (no permanent loop) only for the six known-safe cases, and correctly preserving fail-closed for everything else.

---

## 4. `LegacyScopeAdapter` analogy — what transfers, what doesn't

| `LegacyScopeAdapter` behavior | Transfers to the compiler-clarification bridge? | Why / why not |
|---|---|---|
| Mapping a small set of known legacy `scopeKind` strings to a typed preset call (`isYouTubeAppOnlyScopeKind` → `APP_ONLY`, etc.) | **Yes — this is the core pattern being reused.** | Same shape of problem: a small, closed set of legacy string labels, each with one obviously-correct typed equivalent. |
| Deliberately preserving the `youtube_only` conflation ("do NOT fix it in the adapter") | **Yes, applies identically here.** `youtube_only` bridges to `APP_ONLY(YOUTUBE)` exactly as today's legacy behavior does — this fix must not silently change what "YouTube only" has always meant, even though the browser-scope review separately flagged that meaning as arguably wrong. That is a different, already-tracked fix. | Same discipline: don't bundle a semantics fix with a plumbing fix. |
| `LEGACY_ALL` — the "everything matches" sentinel for empty packages + empty brands | **Does NOT transfer**, confirmed by re-reading `canonicalizeScope`'s `PACKAGE_PINNED` branch, which rejects empty packages (`:655-658`). Android's own preset builder (`ScopeKindPresets.packagePinned`, no validation) is more permissive than the backend's canonicalizer — this is a genuine platform asymmetry, not just a naming difference. | This is exactly why §1's fix routes around needing a sentinel at all, rather than trying to port `LEGACY_ALL`'s shape to the backend. |
| Operating on **stored settings fields** (`enforcementScopePackages`/`brands`/`kind` — already-resolved, already-narrowed, one canonical value per field) | Only partially analogous. | The compiler-side bridge operates on **clarification options before a pick is confirmed** (plural, multiple candidates in flight, need-to-re-ask semantics) — a different shape of problem from adapting one already-settled settings row. The allowlist-and-fail-closed discipline transfers; the single-object adaptation shape does not need to. |

---

## 5. `BROWSER_VIDEO` — already shipped; separating schema from prompt-version changes

**Correction to the prior report: `BROWSER_VIDEO` is not new candidate-only work — it already exists in shipped code on both platforms, unconditionally, independent of any prompt version:**
- Backend: `ScopeKind` enum list includes it (`promiseCompilerService.js:150`), `expandScopePreset`'s switch has a case for it (`:548-549`), `canonicalizeScope` has a branch for it (`:636-639`), and `scopeOptionLabel` has copy for it (`:707`).
- Android: `ScopeKindPresets.expand`'s `when` has `ScopeKind.BROWSER_VIDEO -> browserVideo()` (`ScopeKindPresets.kt:44`), with a full preset implementation (`:113-123`).

**This means the `ScopeKind` enum/schema is not gated behind the v09-vs-v07 prompt switch at all** — the JS/Kotlin code always supports `BROWSER_VIDEO` regardless of which prompt is active; it is simply unreachable today because (a) no deterministic builder emits it (the Google-video builder still emits `chrome_browser`, which canonicalizes to the Chrome-pinned `BROWSER_ONLY`-shaped preset, not `BROWSER_VIDEO`), and (b) the live-deployed v09 prompt text doesn't instruct the model to use it either.

**Revised recommendation on versioning:** separate the two kinds of change explicitly:
- **Schema change (none needed for `BROWSER_VIDEO` itself — already shipped):** if the fix in this report also *starts using* `BROWSER_VIDEO` as the bridge target for `chrome_browser` (recommended — see the mapping table in §6, which maps `chrome_browser → BROWSER_VIDEO` rather than a Chrome-pinned kind, matching the intent "browser video, not Chrome-only" from the earlier browser-scope review), that is a **behavior change to what "Chrome/browser video" resolves to**, not a schema change — the enum value already exists. This should be called out clearly in any implementation PR as an intentional behavior change riding along with the bug fix, separate from the plumbing fix itself, so a reviewer can evaluate it on its own merits.
- **Schema change (real, required):** the new `APP_ONLY_SHORT_FORM`-equivalent kind from §2 **is** a schema change — a new enum value on both platforms plus new preset/canonicalize/label branches.
- **Prompt-content change (v09.txt wording):** telling the model about `BROWSER_VIDEO` in prose is a prompt-content change to an *already-declared* schema — the enum value predates this prompt edit. This weakens (but does not eliminate) the prior report's "bump to v10" argument for that specific edit, since it's arguably a completeness fix to v09's own contract rather than a new contract.
- **Standing recommendation, unchanged:** regardless of the above nuance, the fact that two byte-different files are both labeled `promise_compiler_v09.txt` today (16,233 deployed vs. 16,892 candidate) remains a live versioning hazard on its own, independent of what changed between them. At minimum, pin and log the SHA-256 of whichever `v09.txt` is actually loaded at startup (extending the existing sanitized `promise_compiler_startup` log line) so this ambiguity cannot recur silently. If the §2 schema change (new `ScopeKind`) ships in the same prompt revision that also teaches the model about it, that combination — new enum + prompt text describing it — is the point at which bumping to `promise_compiler_v10` becomes clearly warranted, since a new enum value is exactly the kind of contract expansion a version bump exists to signal.

---

## 6. Concrete option-to-scope mapping table (revised)

| `internalPolicyPreview.scopeKind` (server-authored, allowlisted) | Bridge target `ScopeKind` | Identity | Notes |
|---|---|---|---|
| `chrome_browser` | `BROWSER_VIDEO` *(behavior-change note, §5)* | `VIDEO_ANY` | Not Chrome-pinned — matches the already-shipped `browserVideo()`/`BROWSER_VIDEO` preset exactly. |
| `all_video_apps` | `VIDEO_CLASS_ANY_HOST` | `VIDEO_ANY` | Direct match, no behavior change. |
| `youtube_only` | `APP_ONLY` | `YOUTUBE` | Preserves the existing app-only conflation deliberately (§4). |
| `short_form_all_surfaces` | `SHORT_FORM_ANY_HOST` | `SHORT_FORM_ANY` | Direct match. |
| `youtube_shorts_only` | **new kind** (§2) — not `APP_ONLY` | `YOUTUBE` | Requires the new preset row before this bridge entry can exist at all. Until that row ships, this option must remain `UNRESOLVED` under v1 (fail closed, re-ask) rather than being bridged incorrectly. |
| `compound_combine` (option A) | *(not bridged as an option scope — routed to `parsed.scope`, §1)* | n/a | No table entry; handled by control flow, not a kind mapping. |
| compound options B/C (no `internalPolicyPreview` at all) | *(same as A — routed to `parsed.scope` of the narrowed clause compile, §1)* | n/a | Unverified end-to-end in this pass — see §1's open question and the test below. |

---

## 7. Tests

Carried forward unchanged from the prior report (must still pass — regression guards):
- v07 full regression: `node backend/smoke_promise_compiler_normalize.js` (202/202) and `node backend/smoke_clarification_choice_identity.js` (140/140) with no `PROMISE_COMPILER_PROMPT_VERSION` override.
- Stale/reordered-option fail-closed cases already in `smoke_clarification_choice_identity.js` (duplicate snapshot ids, carry-vs-snapshot mismatch, id missing from snapshot, promise edited after options shown) — unaffected by this design, must stay green.
- Unknown/unresolvable-scope fail-closed: a snapshot entry whose `scopeKind` (or absence of one) is **not** in the §3 allowlist must resolve to `UNRESOLVED`, `canStartCommitment=false`, options re-offered — this is now a *stronger* requirement than before, since it must also cover "structurally present `internalPolicyPreview` but not one of the six allowlisted kinds," not just "no preview at all."

New/corrected for this revision:

| # | Case | Expected |
|---|---|---|
| 1 | Google-video options A/B/C picked individually under v09 | Each resolves to `BROWSER_VIDEO` / `VIDEO_CLASS_ANY_HOST` / `APP_ONLY(YOUTUBE)` respectively, `canStartCommitment=true` |
| 2 | Bare-"shorts" option A ("all short-form") picked under v09 | Resolves to `SHORT_FORM_ANY_HOST`, `canStartCommitment=true` |
| 3 | Bare-"shorts" option B ("YouTube Shorts only") picked under v09, **before** the new kind exists | Must fail closed (`UNRESOLVED`, re-ask) rather than resolving to `APP_ONLY` — this is the regression test that proves the unsafe mapping was never shipped |
| 4 | Same, **after** the new `APP_ONLY_SHORT_FORM`-equivalent kind ships | Resolves to the new kind, `canStartCommitment=true`; **and** a companion enforcement-level test asserting a long-form YouTube video under this scope is `OutOfScope` (not merely "in scope but allowed by a downstream content rule") — this directly proves the surface-narrowing claim from §2, not just that the DTO looks right |
| 5 | Compound "Both together" (A) picked under v09 | `canStartCommitment=true`; resulting `dto.scope` equals `validateAndCanonicalizeScope(parsed.scope, ...)` for the **full, un-narrowed** promise text, not a value derived from the option object |
| 6 | Compound "Only the first" (B) and "Only the second" (C) picked under v09 | `canStartCommitment=true` for each; resulting `dto.scope` reflects the compile of the **narrowed** single-clause text. **Currently unverified — add this fixture; do not assume it passes by analogy to A.** |
| 7 | An option with `internalPolicyPreview.scopeKind` set to a value **not** in the six-entry allowlist (simulating a future or malformed producer) | Bridge does not fire; `scope=UNRESOLVED`; fail closed | Proves the allowlist is a real boundary, not just documentation |
| 8 | A clarification option sourced from raw model output with a well-formed `.scope` already attached | Bridge is never consulted (short-circuited by `opt.scope` presence); the model's own scope is validated directly | Proves the bridge only fills a gap, never overrides a model-declared scope |
| 9 | `resolveOptionRematerialize`'s label/description regex-fallback path (non-allowlisted, prose-derived) produces a `preview` under legacy resolution | Under v1, this must **not** be bridged; `scope=UNRESOLVED` even though the legacy (v07) path may have resolved it via prose | Proves point 3's boundary holds even when the legacy engine "succeeds" by prose-matching |

---

## 8. Implementation status (2026-09-26, local only — not deployed, not phone-verified)

Implemented in `backend/promiseCompilerService.js` + Android `domain/scope/*`; covered by
`backend/smoke_v09_clarification_scope.js` and `AppShortFormOnlyScopeTest` /
`TypedScopeContractFixtureTest`.

- Compound picks: `verifyCompoundClauseSelection` runs in `compilePromise` before
  `resolveCompoundCompileText` and before any model call. It requires a non-empty snapshot
  with unique ids, exactly one entry for the pick, a prior promise text that matches under
  `promiseBindingKey`, a carry (if sent) whose canonical projection + internal preview equal
  the snapshot entry, and a snapshot entry equal to the server-recomputed compound option.
  Failure returns a fail-closed re-ask with fresh server-built options; the model is not
  called (`meta.modelCalled=false`). Verification state is passed to normalize as a
  server-local fourth argument, never read from the request. `resolveClarificationSelection`
  is unchanged.
- Compound scope source: a verified compound pick takes `scope` from the parse of the text
  actually compiled (full text for A, narrowed clause for B/C). Invalid/missing parse →
  UNRESOLVED, Start closed. `compound_combine` has no bridge entry.
- Bridge: `DETERMINISTIC_SCOPE_BRIDGE` (5 entries: `chrome_browser`→`BROWSER_VIDEO`,
  `all_video_apps`→`VIDEO_CLASS_ANY_HOST`, `youtube_only`→`APP_ONLY(YOUTUBE)`,
  `short_form_all_surfaces`→`SHORT_FORM_ANY_HOST`, `youtube_shorts_only`→
  `APP_SHORT_FORM_ONLY(YOUTUBE)`). An option gets a bridged scope only when re-running the
  Google-video / bare-shorts builder on the unchanged promise reproduces it (id, label —
  builder or canonical v1 label, description, policy previews, recommended, and
  `internalPolicyPreview` fingerprint). A pick uses the bridge only when the prior promise
  text is present and matches.
- **Deviation from case 8 above (stricter):** a well-formed `option.scope` does not
  short-circuit the bridge. When the option is deterministic, the re-derived scope wins and an
  own `scope` that disagrees fails closed (`snapshot_typed_scope_not_reproducible`). For
  non-deterministic options the own `scope` is validated + canonicalized as before.
- `chrome_browser` → `BROWSER_VIDEO` is an intended change under v1 only: Chrome-pinned
  becomes "video players in any verified browser" (search/docs/home stay out). The v07
  legacy path still pins Chrome.
- Short-form eligibility is now a pure function of promise text (`SHORTS_NOT_BARE_RE`),
  no longer dependent on `parsed.quotas`.
- New kind `APP_SHORT_FORM_ONLY` (identities YOUTUBE / INSTAGRAM / FACEBOOK / TIKTOK /
  SNAPCHAT; NATIVE_APP, surfaces `[PLAYER_SHORT]`, package pinned) in backend + Android.
  YouTube Shorts → InScope; long-form YouTube, Instagram reels, and YouTube Shorts in Chrome →
  OutOfScope (real `SurfaceDetector` → `ScopeMatcher`).

**Provenance limit:** stateless recomputation proves an option is the canonical server-built
option for the unchanged promise text. It does not prove the option was previously displayed
to this user. The final user confirmation before Start is preserved and remains required.

Still unverified: live v09 model emission of option scopes, deployed behaviour, phone flow.
