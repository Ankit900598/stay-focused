# Engineering Review: Browser-Video Scope Enforcement (2026-09-26)

Research/design only — no code, deployment, Azure, commits, or worktree changes were made. Read against the live working tree, not just the design docs (which explicitly ask not to be trusted as "already shipped").

Question: how should Strlix enforce a promise across video-capable browsers (Chrome, Firefox, Brave, Samsung Internet, unknown browsers) without a hardcoded Chrome-only package list, and without false-blocking ordinary browser pages (search, Docs, non-video pages)?

## Executive summary — what's actually implemented vs design-only

The repo already contains a full "Scope Semantics v1" redesign (`docs/scope-semantics-v1.md`, reviewed in `docs/scope-semantics-v1-phase2-review.md`, with a Phase-2A Cursor prompt in `docs/scope-semantics-v1-phase2a-prompt.md`). I verified the working tree against these docs directly rather than trusting the docs' own status claims:

| Layer | Status (verified against code, not docs) |
|---|---|
| Android typed scope engine (`domain/scope/*`: `EnforcementScope`, `ScopeClause`, `ScopeMatcher`, `HostResolver`, `IdentityResolver`, `SiteResolver`, `ScopeKindPresets`, `ScopeSerialization`, `ScopeAppliesToCopy`, `LegacyScopeAdapter`, `ScopeGate`, `SurfaceScopeMapping`) | **Built.** All 11 files exist. `ScopeGate`/`LegacyScopeAdapter` are wired into both `PromiseEnforcementCoordinator` and `PhoneCodexAccessibilityService` (confirmed by grep — both files reference `ScopeGate`). This matches Phase 2A of the review. |
| `ScopeMatcher` surface-family fix (F1: `PLAYER_ANY` must match `PLAYER_SHORT`/`PLAYER_LONG`) | **Fixed.** `ScopeMatcher.kt:79-89` implements `isPlayerFamily()` exactly per the review's F1 finding, with a comment citing the review doc directly. |
| Backend structured-scope validation (`validateScope()`, `SCOPE_KINDS`) | **Partially built, not wired to the live response.** `promiseCompilerService.js:124-433` has `validateScope()` and `SCOPE_KINDS`, used only for `candidateScopes` (line 376) and per-option `optionScope` (line 433). Grep for `dto.scope =` returns **zero matches** — the main compiled DTO sent to Android never carries a `scope` field. |
| Compiler → Android `scope` object end-to-end | **Not built.** `FocusPromise.kt` has no `scope: EnforcementScope?` field — only the legacy `scopePackages: List<String>` / `scopeKind: String?` (line 25, 36). `ConfirmedPromiseBinder.kt:38-40` writes only the three legacy fields (`enforcementScopePackages`, `enforcementContentBrands`, `enforcementScopeKind`) into `StudyWorldSettings` — never `enforcementScope`. |
| `ScopeAppliesToCopy.forScope()` (the doc's "only producer of user-facing scope copy") | **Written, never called.** Grep across the whole `app/` tree finds exactly one match: its own definition. Confirmation-card copy still comes from elsewhere (model prose / legacy `ConfirmationUserCopy`). |
| Backend `explicitlyBlocksKnownPackage` false-block fix (F2) | Not directly checked in this pass — flagged for verification before any further scope work, since the review marks it a blocking prerequisite that runs *upstream* of scope. |

**Bottom line:** the Android-side typed scope *engine* is real and the two hardest correctness bugs the design review found (F1 surface-family, and routing both live callers through one gate) are fixed. But the *pipe* from "user picks a clarification option" to "that typed scope is what gets enforced" does not exist yet — the system still runs entirely on the pre-redesign package/brand/kind model via `LegacyScopeAdapter`, because `enforcementScope` is always `null` in practice (nothing ever sets it). This is exactly consistent with all four pieces of evidence you supplied.

---

## 1. Exact current data flow: clarification choice → persisted scope → Android match → enforcement decision

```
User taps "All video apps and browsers"
  → backend inferGoogleVideoClarification() / model-generated clarificationOptions
    (promiseCompilerService.js:859-910 area; internalPolicyPreview.packages =
     ALL_VIDEO_PLATFORM_PACKAGES — a literal 4-entry array: YouTube, NewPipe,
     Chrome, Vanced)
  → resolveOptionRematerialize(matched option) → rematerializeDtoFromOptionPreview()
    (promiseCompilerService.js:1151+) stamps dto.scopePackages from that literal list
  → response DTO: scopePackages=[4 packages], scopeKind=... (legacy fields only;
    dto.scope is never assigned — confirmed by grep)
  → Android CompiledPromiseMapper.kt maps DTO → FocusPromise{scopePackages, scopeKind, ...}
    (FocusPromise has no `scope` field to receive anything richer even if the
    backend sent one)
  → ConfirmedPromiseBinder.kt:38-40 writes enforcementScopePackages /
    enforcementContentBrands / enforcementScopeKind into StudyWorldSettings
    (enforcementScope is never touched — StudyWorldSettings.enforcementScope,
    if present at all, stays null)
  → at enforcement time: ScopeGate.evaluate(pkg, text, detection,
      settings.enforcementScope ?: LegacyScopeAdapter.from(settings))
    Since enforcementScope is null, LegacyScopeAdapter.from(settings) runs on the
    legacy fields: packages non-empty → PACKAGE_PINNED(packages) — i.e. exactly
    the 4 hardcoded packages, exclusive.
  → ScopeMatcher.match(...) against PACKAGE_PINNED([youtube, newpipe, chrome, vanced])
    → Firefox / Brave / Samsung Internet / any unlisted browser → OutOfScope
      → ENFORCEMENT_SCOPE_ALLOW (silently not enforced)
```

This explains every piece of your evidence in one causal chain:
- **"Broad scope saves a fixed list of four packages"** — proven: `ALL_VIDEO_PLATFORM_PACKAGES` is a literal array, and it is what actually reaches `scopePackages`.
- **"HostResolver recognizes multiple browsers but that doesn't prove policy includes them"** — proven: `HostResolver`/`ScopeMatcher`/`CLASS_RESOLVED` matching is fully built and correct, but it is never reached, because the persisted scope is `PACKAGE_PINNED` (legacy), not a `CLASS_RESOLVED` clause. The engine is real; the pipe to it is missing.
- **"YouTube only" means YouTube app only, ambiguous wording"** — matches the design doc's own diagnosis (§1.2 item 2 in `scope-semantics-v1.md`): `youtube_only` is deliberately still conflated to app-only in `LegacyScopeAdapter` (the review explicitly forbids "fixing" this conflation inside the adapter — it's a compiler-side fix, not yet done).
- **Malformed copy "videos watched in ."** — see §5 below.

---

## 2. Can the proposed structured scope model express "all browser video" across Chrome/Firefox/Brave/Samsung Internet/unknown browsers while excluding search/Docs/non-video pages?

**Yes, and the Android-side matcher already proves it can, in isolation.** The relevant preset (`BROWSER_ONLY` or `VIDEO_CLASS_ANY_HOST`/`CONTENT_ANY_HOST` depending on identity) is defined as:

```
host = BROWSER (or ANY_HOST), identity = VIDEO_ANY (or a named brand),
surfaces = [PLAYER_ANY] (widens to PLAYER_SHORT/PLAYER_LONG per the F1 fix),
packageMatch = CLASS_RESOLVED
```

- **Browser family coverage**: `HostResolver` classifies by package substring/known-browser table (`chrome`, `firefox`/`org.mozilla.firefox`, `brave`, `com.sec.android.app.sbrowser` for Samsung Internet, generic WebView shells), not an enumerated Chrome-only list. `CLASS_RESOLVED` package matching means an *unlisted* browser that `HostResolver` still classifies as `BROWSER` is covered — this is the actual fix for "unknown browsers."
- **Excluding search/Docs/non-video pages**: this is enforced by the **surface axis**, not the host axis. `SurfaceScopeMapping` maps `SurfaceDetector`'s existing `SEARCH`/`HOME`/`CHANNEL`/`COMMENTS` results to `SEARCH`/`BROWSE` scope classes, which are disjoint from `PLAYER_ANY`/`PLAYER_SHORT`/`PLAYER_LONG`. A clause with `surfaces=[PLAYER_ANY]` therefore does not match a Chrome SERP or a Google Docs tab regardless of host — this is exactly what the review's F8 finding flagged as a risk ("`VIDEO_ANY` matches all of Chrome" if a preset uses `surfaces=ANY` instead of the player family) and it is a **configuration discipline requirement on the preset, not a limitation of the model**: any browser-video preset must specify `surfaces=[PLAYER_ANY]`, never `surfaces=[ANY]`.
- **Residual risk (already documented, not yet fixed)**: F8 in the phase-2 review — `VideoPlatformRegistry.isVideoOrStreamingPackage("com.android.chrome")` returns `true`, so any preset expansion that accidentally uses `identity=VIDEO_ANY, surfaces=ANY` (instead of the correct `surfaces=[PLAYER_ANY]`) would put Docs/SERP in scope. This must be asserted by test (`chrome_serp_out_of_video_class_scope`, `chrome_docs_out_of_video_class_scope`), not just reviewed by eye, before this preset ships live.

**Conclusion: the model is expressive enough.** The gap is entirely in wiring (§1), not in the scope algebra's ability to represent "all browser video, any browser, players only."

---

## 3. Smallest safe implementation phases

The existing docs already split this correctly into 2A (done) / 2B (not started). Given 2A is verified done, the remaining minimal path is:

**Phase 2B-1 — Backend: make `dto.scope` real, don't just validate candidates.**
- File: `backend/promiseCompilerService.js`. After the existing `validateScope()` machinery (already present for `candidateScopes`/`optionScope`), call it on the *primary* `parsed.scope` and assign the result to `dto.scope`. If missing/invalid → `dto.scope = UNRESOLVED`, `canStartCommitment=false` (already the documented contract; just needs to actually run on the main path).
- Delete the literal 4-package expansion path used for "all video apps and browsers" (`ALL_VIDEO_PLATFORM_PACKAGES` inside `inferGoogleVideoClarification`/`buildScopeInternalPreview`) and replace it with a `scope` object using `host=ANY_HOST, identity=VIDEO_ANY, surfaces=[PLAYER_ANY], packageMatch=CLASS_RESOLVED`. Each `clarificationOptions[i]` gets its own `.scope`.
- Compatibility risk: **medium**. This changes what the wire response contains; any code still reading only `scopePackages`/`scopeKind` (Android's current `CompiledPromiseMapper`) must keep working during a transition window — keep emitting the legacy fields alongside `scope` for one release (mirrors how `StudyWorldSettings` already plans to keep legacy fields as deprecated derived values).

**Phase 2B-2 — Android: carry `scope` through the DTO → draft → binder → store.**
- `FocusPromise.kt`: add `val scope: EnforcementScope? = null`.
- `CompiledPromiseMapper.kt`: parse `scope` via `ScopeSerialization` (already built); on parse failure, `UNRESOLVED` (never partial).
- `ConfirmedPromiseBinder.kt`: add `enforcementScope = draft.scope` to the existing `.copy(...)`/constructor call at line 38-40. **Do not remove the legacy field writes yet** — both must be written for one release so `LegacyScopeAdapter` remains a valid fallback for old/offline-parsed promises.
- `StudyWorldSettingsStore.kt`: persist `enforcementScope` as JSON (per the original design's §4.2). On read failure → `UNRESOLVED`, never null-to-everything.
- Compatibility risk: **low** — `ScopeGate` already prefers `settings.enforcementScope ?: LegacyScopeAdapter.from(settings)`, so once this field is populated, the already-built and already-tested `CLASS_RESOLVED`/`HostResolver` path activates automatically with no further gate changes.

**Phase 2B-3 — Wire `ScopeAppliesToCopy.forScope()` into the confirmation UI (fixes the malformed-copy bug, §5 below).**
- File: `ui/home/ConfirmationUserCopy.kt` / `ui/home/PromiseUnderstandingCard.kt`. Replace whatever currently renders the "applies to" line for a browser-scope option with `ScopeAppliesToCopy.forScope(option.scope)`. This removes the model-authored sentence fragment entirely for this line.
- Compatibility risk: **low** — purely additive UI rendering change once `option.scope` exists (depends on 2B-1).

**Phase 2B-4 — `PromiseClarificationLaw` scope precondition (the design's "highest severity" item, §5.1 of `scope-semantics-v1.md`).**
- `isConcreteClockPolicy`/`clearGhostClarification` must require `draft.scope?.isEnforceable == true` before auto-clearing clarification. Today clock-concreteness alone can clear clarification with a guessed/legacy scope.
- Compatibility risk: **medium** — this can newly block Start on promises that previously auto-cleared; must be covered by the existing `PhoneSurfaceTraceCatalog` equivalence-style testing before shipping, and a `PromiseClarificationLawTest.kt` (the review notes this file doesn't exist yet).

**Explicitly deferred (per the existing review, and still correct advice):**
- Do not touch `SurfaceDetector` detection heuristics.
- Do not delete `EnforcementScopeLaw`/`LegacyScopeAdapter` yet — they remain the equivalence oracle and the fallback for any promise that never got a compiler-emitted scope (offline parser, old cached sessions).
- Do not convert `SessionLockLaw` to `ScopeMatcher`.
- Verify the F2 WhatsApp/Gmail cue-binding fix (`explicitlyBlocksKnownPackage`) independently before or alongside 2B, since it runs upstream of scope in both live callers and was marked a blocking prerequisite in the phase-2 review, not something this task should assume is already fixed.

---

## 4. Test matrix

| # | Case | Setup | Expected |
|---|---|---|---|
| 1 | Chrome, video player | `BROWSER_ONLY`/`VIDEO_CLASS_ANY_HOST`, Chrome, `LONG_FORM_PLAYER` | InScope → enforced |
| 2 | Firefox, video player | same scope, `org.mozilla.firefox`, `LONG_FORM_PLAYER` | InScope (this is the case that fails today under the legacy 4-package model) |
| 3 | Brave, short player | same scope, `com.brave.browser`, `SHORT_FORM_PLAYER` | InScope |
| 4 | Samsung Internet, video player | same scope, `com.sec.android.app.sbrowser`, `LONG_FORM_PLAYER` | InScope |
| 5 | Unknown/unlisted browser, video player | package not in any known-browser table but `HostResolver` still classifies via generic heuristics (or correctly does not) | If classified BROWSER → InScope; if not classifiable → `Unresolved`/ask, never a silent guess either way — never silently OutOfScope-as-if-verified |
| 6 | Chrome, SERP | same scope, Chrome, `SEARCH` surface | OutOfScope → `ENFORCEMENT_SCOPE_ALLOW` (guards F8) |
| 7 | Chrome, Google Docs | same scope, Chrome, `BROWSE`/non-player surface | OutOfScope |
| 8 | YouTube app, player | `APP_ONLY(YOUTUBE)` | InScope; same scope + Chrome `youtube.com` watch → OutOfScope (proves app-only ≠ content-anywhere) |
| 9 | YouTube app, player | `CONTENT_ANY_HOST(YOUTUBE)` / `VIDEO_CLASS_ANY_HOST` | InScope |
| 10 | NewPipe, player | any `VIDEO_ANY`/`YOUTUBE` identity scope | InScope; also assert NewPipe's `YOUTUBE` identity resolution doesn't leak into an unintended `APP_ONLY` scope (F5) |
| 11 | Unrelated app (e.g. WhatsApp, no video scope) | any browser-video scope active | OutOfScope → not enforced by this promise (separate from any explicit app rule) |
| 12 | Unknown browser lookalike package (e.g. name contains "browser" but isn't one) | `HostResolver` negative case | Must not be misclassified as BROWSER (guards F9/F10) |
| 13 | Missing/ambiguous scope (compiler returns no scope or malformed clause) | any promise | `UNRESOLVED` → Start disabled, ask again — never silently enforce or silently allow-everything |
| 14 | Clarification option carried across turns (ties to the separate clarification-carry bug already under investigation) | pick "all apps and browsers" twice | Both turns must resolve to the same `scope` object, not just the same label text |
| 15 | Confirmation copy for browser-scope option | any browser-video preset | `ScopeAppliesToCopy.forScope()` output is non-empty and contains no raw package id (assert via existing `ConfirmationUserCopy.containsPackageName` helper) |

Items 1-4, 6-7, and 12 should be added as `PhoneSurfaceTraceCatalog` entries (laptop-runnable, no phone needed) before Phase 2B-1 lands, per the repo's own "add a failing trace before fixing law" convention — items 1, 6, 7 in particular should already be expressible today against `ScopeMatcher` directly (bypassing the missing pipe) to prove the *matcher* is correct in isolation, separately from proving the *pipe* is fixed.

---

## 5. Root cause of variable labels and missing-browser-name copy

**Proven:** the string "Apply the rule only to videos watched in ." (and its populated sibling "...in browsers.") does not exist as a literal template anywhere in `app/` or `backend/` (grep found zero source matches) — it is **model-generated prose** (`policyPreview`/`description` text produced by the LLM for a browser-scope clarification option), not output of `ScopeAppliesToCopy.forScope()` (which is written but never called — confirmed by grep, §"Executive summary" table above). This directly matches the phase-1 design's own diagnosis: *"AI prose is load-bearing for enforcement scope"* — here specifically for **display copy**, not enforcement, but with the same root cause.

**Proven mechanism for the empty-name variant:** `sanitizeUserFacingCopy()` (`promiseCompilerService.js:99-116`) strips any token matching `\b(?:com|org|net|io)\.[a-zA-Z0-9_.]+\b` (raw package ids) from model-generated text, and also conditionally strips the literal word "NewPipe" (line 112-114) when the user didn't name it. If the model's own sentence structure was e.g. "...videos watched in {com.android.chrome}." or otherwise produced a package-id-shaped token as the sentence's object, sanitization removes the token but leaves the surrounding sentence fragment ("in .") intact — the sanitizer strips tokens, it does not re-flow or drop the sentence around them.

**Not proven (would need one more capture to confirm which exact case fires):** I could not find the literal raw pre-sanitized model output for this exact request (only the post-render UI text is in the phone-verify captures). Two mechanisms are both consistent with the evidence and are not mutually exclusive:
1. The model occasionally omits the browser-name noun entirely ("in ." with nothing to strip — a pure LLM completion gap), or
2. The model includes a package-id-shaped token that `sanitizeUserFacingCopy` correctly strips per its own contract, leaving a dangling sentence.

**Reproduction recipe (safe, no code changes):** capture the raw `/compile-promise` JSON response (not the rendered UI) for the exact "min-30 minute" browser-scope clarification option across 3-5 repeated compiles of the same promise text. If the raw `policyPreview`/`description` field itself sometimes contains `com.*`-shaped text and sometimes contains a plain word, that confirms mechanism 2 for those cases and mechanism 1 (bare LLM omission) for the rest. This is a strict superset of the capture already recommended in the separate clarification-carry investigation (`docs/clarification-scope-bug-investigation-2026-09-26.md` §6) — one capture session can answer both.

**Fix (already implied by the existing design, not a new recommendation):** this entire class of bug disappears once Phase 2B-3 (§3 above) wires `ScopeAppliesToCopy.forScope(scope)` as the sole producer of this line — a pure function over the typed `ScopeKind`, never a stripped/re-flowed LLM sentence. This is a structural fix, not a sanitizer patch; patching `sanitizeUserFacingCopy` to "fix up" dangling sentences would be treating the symptom and would still leave AI prose load-bearing for scope-adjacent copy.

---

## 6. Release-blocking risks and exit criteria

**Release-blocking (P0) — do not ship "all video apps and browsers" as a promise-scope option until:**
1. `dto.scope` is actually populated on the wire for every clarification option (§3, Phase 2B-1) — today the option's *label* promises browser coverage the *persisted scope* does not deliver. This is a trust violation of the same class as the already-tracked clarification-carry bug: the user is told one thing and given another.
2. Firefox / Brave / Samsung Internet all pass test-matrix items 2-4 on a real device, not just in the `ScopeMatcher` unit suite — `HostResolver`'s package/name heuristics (F9/F10 in the phase-2 review) are pattern-based and have not been phone-verified per this review.
3. The F8 SERP/Docs false-block risk is covered by an explicit passing test (matrix items 6-7) before the `VIDEO_CLASS_ANY_HOST`/browser-video preset is wired into any promise a real tester can select.
4. The malformed "videos watched in ." copy is fixed at its root (Phase 2B-3), not patched at the sanitizer — shipping "browsers." sometimes and "." other times for the *identical* option is a visible trust bug independent of enforcement correctness.

**P1 — should be fixed before wider sideload, not necessarily before this specific fix:**
5. `PromiseClarificationLaw`'s scope precondition (Phase 2B-4) — without it, other unrelated promise phrasings can still auto-clear Start with a guessed/legacy scope, which is a pre-existing gap this task's fix doesn't need to touch but shouldn't ship alongside without at least a tracked follow-up.
6. F2 (WhatsApp/Gmail false-block cue binding) verified fixed or explicitly re-scoped — it runs upstream of the scope gate in both live callers.

**Measurable exit criteria:**
- **Laptop:** `./gradlew.bat :app:testDebugUnitTest` green including all new/updated scope tests; the equivalence property test (`ScopeMatcher(LegacyScopeAdapter.from(s)) == EnforcementScopeLaw.matches(...)` for every existing trace) still passes after 2B-1/2B-2 land, proving old promises with no compiler-emitted scope are unaffected; `node backend/smoke_scope_semantics_v1.js` (or equivalent) passes with `dto.scope` present and validated on the primary compile path, not just candidates.
- **Phone:** for the same "all video apps and browsers" promise, repeat-compile 3x and confirm identical persisted `scope` (not just identical label) each time; open a real 30-minute video in Chrome, Firefox (if installed), and — if available — Brave or Samsung Internet under an active min-length promise and confirm BLOCK in each; open Chrome search results and Google Docs under the same active promise and confirm no overlay/false block; confirm the confirmation-card "applies to" line reads identically and completely (no dangling "in .") across 3 repeated compiles of the same promise.
