# Scope Semantics v1 — Phase 2 Design Review

**Status:** Design review only. No code changed by this document.
**Date:** 2026-09-22
**Author:** CTO/research track
**Reviews:** Phase 1 as merged in the working tree (`domain/scope/*` + out-of-scope extras)
**Companion:** `docs/scope-semantics-v1.md` (the original design)
**Implementation prompt:** `docs/scope-semantics-v1-phase2a-prompt.md`

**Verified state at review time:** `./gradlew.bat :app:testDebugUnitTest` → BUILD SUCCESSFUL,
**636 tests, 0 failures, 0 errors**, including 78 new scope tests
(`ScopeMatcherTest` 33, `ScopeSerializationTest` 12, `ScopeKindPresetsTest` 11,
`IdentityResolverTest` 9, `SiteResolverTest` 6, `ScopeAppliesToCopyTest` 4, `HostResolverTest` 3).

> `JAVA_HOME` is not set on this machine. Use:
> `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` before `.\gradlew.bat`.

---

## 0. Two things that changed the shape of this review

**Phase 1 shipped more than Phase 1.** Beyond `domain/scope/*`, the working tree also modifies
`SafeAppsCatalog`, `PhoneCodexAccessibilityService`, `EnforcementTraceEvaluator`,
`FocusPromiseParser`, and `PromiseIntentRules` — a WhatsApp/Gmail "explicit block overrides safe
default" feature. That feature sits **directly in the path Phase 2 rewires**, runs **upstream of
scope**, and carries a live false-block bug (§2, F2).

**There are now two scope systems in the tree at once.** `EnforcementScopeLaw.matches()` is still
the live authority for all five gates; `ScopeMatcher` is dead code. Correct for Phase 1 — but it
means Phase 2 is a **semantics flip**, not an addition, and the two systems disagree on the most
common input (§2, F4).

---

## 1. Exact current gate order, and where scope is applied

### 1.1 `PhoneCodexAccessibilityService.onAccessibilityEvent` (live phone path)

| # | Step | Scope? |
|---|---|---|
| 1 | Own-package / overlay-retain early returns | — |
| 2 | **`effectiveAppRuleEarly`** = stored rule, upgraded to `BLOCK` if stored BLOCK **or** `PromiseIntentRules.explicitlyBlocksKnownPackage(goal, pkg)` | — |
| 3 | `shouldAllowSafePackage(pkg, effectiveAppRuleEarly)` → safe ALLOW unless explicit BLOCK **and** pkg is *overridable* | — |
| 4 | No active session → allow | — |
| 5 | `LOCKED && effectiveAppRuleEarly != BLOCK && SessionLockLaw.mayUseAppWhileLocked(...)` → allow unrelated | **✅ scope #1** |
| 6 | `maybeResearchApp` (dossier) | — |
| 7 | **`EntertainmentBanGate.mustEvaluate(...)`** → if false: appRule BLOCK ? BLOCK : `ENFORCEMENT_SCOPE_ALLOW` | **✅ scope #2** |
| 8 | `scopedAppRule == BLOCK` → `applyAppRuleBehavior` → BLOCK | — |
| 9 | `EnforcementEventGate.shouldSkipContentAndAi` (blank text) | — |
| 10 | `applyAppRuleBehavior(pkg, text, scopedAppRule)` | — |
| 11 | Content signals → AI → quota | — |

### 1.2 `PromiseEnforcementCoordinator.evaluate` (law under test)

| # | Step | Scope? |
|---|---|---|
| 1 | `!hasActiveSession` → `NO_ACTIVE_SESSION_ALLOW` | — |
| 2 | **`EntertainmentBanGate.mustEvaluate`** → false ⇒ `ENFORCEMENT_SCOPE_ALLOW` (or `APP_RULE_BLOCK`) | **✅ scope #2** |
| 3 | `BlankVideoTreeGate.evaluate` | **✅ scope #3** |
| 4 | `entertainmentBan` | — |
| 5 | `MediaLengthEnforcement.decideDetailed` | **✅ scope #4** |
| 6 | `SurfaceEnforcementGate.evaluate` | — (goal-text only) |
| 7 | `applyAppRuleAfterContent` | — |
| 8 | (caller) `ShortFormQuotaGate` | **✅ scope #5** |

### 1.3 The five scope call sites

| Call site | Line | Behavior when scope does **not** match |
|---|---|---|
| `SessionLockLaw.shouldKeepLockingPackage` | 21 | falls through to package heuristics |
| `EntertainmentBanGate.mustEvaluate` | 64 | **OR-ed** with `isLifestyleEntertainmentBan(goal)` and `shouldOverrideNamedScopeAllow(...)` |
| `BlankVideoTreeGate.evaluate` | 55 | returns `NONE` |
| `MediaLengthEnforcement.decideDetailed` | 80 | returns `NONE` |
| `ShortFormQuotaGate` | 113 | returns `NONE` |

> **The critical one is `mustEvaluate`.** It is *not* a pure scope check — it is
> `scope.matches(...) OR monk-mode-bypass`. Monk mode deliberately ignores scope so unknown apps
> stay guilty. A naive "delete the `matches()` calls, add one coordinator gate" **silently kills
> monk-mode unknown-app blocking.**

---

## 2. Findings that must be resolved before Phase 2 code

Ranked by blast radius. **F1–F4 are blockers.**

### F1 — `PLAYER_ANY` is matched as a literal, not a family *(critical, false-allow)*

`ScopeMatcher.surfaceAxisMatches` does exact set membership:

```kotlin
if (ScopeSurfaceClass.ANY in clause.surfaces) return true
return ctx.surfaceClass in clause.surfaces
```

`ScopeKindPresets.browserOnly` emits `surfaces = {PLAYER_ANY, BROWSE, SEARCH}`. But
`SurfaceScopeMapping` sends:

- `LONG_FORM_PLAYER → PLAYER_LONG`
- `SHORT_FORM_PLAYER → PLAYER_SHORT`

`PLAYER_LONG ∉ {PLAYER_ANY, BROWSE, SEARCH}` ⇒ **`OutOfScope`**.

**Concrete failure:** `BROWSER_ONLY(YOUTUBE)` + Chrome playing a 52-minute lecture ⇒ out of scope ⇒
`ENFORCEMENT_SCOPE_ALLOW`. That is the exact "YouTube only in Chrome" promise this redesign exists
to serve, and it silently does nothing.

**Why tests miss it:** `row04` and `row06` both pass `ACTIVE_VIDEO_PLAYER` (→ `PLAYER_ANY`), which
matches literally. No `BROWSER_ONLY` test uses a short or long player.

Also hits `HOST_WITH_CATEGORY_EXEMPTIONS` clause `i3` (`surfaces = {PLAYER_ANY}`): a long video on an
unrecognised site escapes.

**Fix:** `PLAYER_ANY` in a *clause* must mean the player family
(`PLAYER_ANY ⊇ {PLAYER_ANY, PLAYER_SHORT, PLAYER_LONG}`), with a test asserting all three detections
match a `PLAYER_ANY` clause.

### F2 — `explicitlyBlocksKnownPackage` false-blocks WhatsApp/Gmail *(critical, already live)*

The cue regex is `\b(no|block|ban|forbid|stop|disable|except)\b|...` scanned in an **80-character
window on either side** of the app alias.

```
goal: "allow whatsapp and gmail, no shorts"
      index("whatsapp") = 6 → window = whole string → matches \bno\b → TRUE
```

⇒ WhatsApp is force-`BLOCK`ed, overrides its safe default, and (per the `LOCKED` change) stays
blocked in a locked session. **The user explicitly allowed it.**

More false-blocks: `"no shorts, keep gmail working"`, `"stop wasting time, gmail is fine"`,
`"block reels but docs ok"`. Bare `no` and `stop` are extremely common filler.

This contradicts the product's own north star — *"blocked UI, not blocked life"* — for precisely the
connector apps named in `PRODUCT_MEMORY.md` (Gmail, WhatsApp). Existing tests cover positives plus
one clean negative (`"whatsapp ok"`), which passes only because no cue appears anywhere in it.

Not scope's bug — but **Phase 2 must not inherit it**, because it runs upstream of where
`ScopeMatcher` will sit. Fix: require the cue to be *syntactically bound* to the alias (a negation
governing that app), not merely co-occurring within 80 chars — or gate it behind a compiler-emitted
`PACKAGE_PINNED` scope instead of goal text.

### F3 — `mustEvaluate` is scope OR monk-bypass *(high, regression)*

See §1.3. Phase 2 must split it into two explicit inputs — `scopeMatch` and `monkOverridesScope` —
not collapse them into one.

### F4 — Empty-scope semantics invert *(high, mass regression)*

| System | scope absent | Result |
|---|---|---|
| `EnforcementScopeLaw.matches()` | `packages.isEmpty() && brands.isEmpty()` | `true` — **everything in scope** |
| `ScopeMatcher.match()` | `scope == null` | `Unresolved` — **nothing in scope** |

Every trace in `PhoneSurfaceTraceCatalog` that builds a plain `StudyWorldSettings` has no scope.
Flipping the authority in one step turns all of them from "enforced" to "not enforced" — 80+ silent
false-allows, and the replay test would catch only those asserting BLOCK, not those asserting ALLOW
*for the right reason*.

**This is why Phase 2 must not be a single flip.** See §3.

### F4b — Coordinator-only wiring misses the live phone path *(high, false proof)*

`PromiseEnforcementCoordinator` is the law under unit tests, but the AccessibilityService also calls
`EntertainmentBanGate.mustEvaluate`, `BlankVideoTreeGate`, `MediaLengthEnforcement`, and
`ShortFormQuotaGate` directly. If Phase 2A only routes the coordinator through `ScopeGate`, the test
path can become clean while the phone path still runs old `EnforcementScopeLaw` checks.

**Required fix:** Phase 2A must evaluate the same `ScopeGate` in both:

- `PromiseEnforcementCoordinator.evaluate`
- `PhoneCodexAccessibilityService.inspectAndEvaluate`, before the direct blank/media/quota/content
  gate chain

The direct gates can then drop their internal `EnforcementScopeLaw.matches()` calls without changing
behavior, because both callers have already performed the same typed scope preflight.

### F5 — `IdentityResolver.YOUTUBE_PACKAGES` contains NewPipe *(medium)*

`org.schabi.newpipe` resolves as identity `YOUTUBE`. Correct for `CONTENT_ANY_HOST` (row 9);
`APP_ONLY` is saved only by the `PINNED` package fence. If any future preset uses `YOUTUBE` +
`CLASS_RESOLVED`, NewPipe silently enters an "app only" scope. Document the coupling; add a guard
test.

### F6 — `PINNED` has an undocumented browser escape hatch *(medium)*

`packageAxisMatches` bypasses the package fence when `host == BROWSER && sites match`. Inert today
(`APP_ONLY` pins `host = NATIVE_APP`), but it is an implicit rule that will surprise the next preset
combining `ANY_HOST` + `PINNED` + `sites`.

### F7 — `ADULT_ANY` is text-only *(medium)*

`AdultContentLaw.isHardBlock(screenText)` with no package signal. A porn app with a near-blank a11y
tree resolves `false` ⇒ `HOST_WITH_CATEGORY_EXEMPTIONS` won't match ⇒ out of scope. This is exactly
the `BlankVideoTreeGate` / `VIDEO_APP_DURATION_UNAVAILABLE` case. Scope and blank-tree law must be
ordered so blank-tree still fires.

### F8 — `VIDEO_ANY` matches all of Chrome *(medium)*

`VideoPlatformRegistry.isVideoOrStreamingPackage("com.android.chrome")` is `true` (registered with
`enforcesMediaSurfaces = true`). So `VIDEO_CLASS_ANY_HOST` (`surfaces = ANY`) puts Google Docs and
SERP in scope. Harmless today (passive surfaces PASS downstream); after Phase 2, scope becomes the
gate. Constrain that preset to the player family.

### F9 / F10 — resolver greediness *(low)*

`HostResolver` substring hints (`brave`, `chrome`, `opera`, `browser`, `webview`) classify any
package containing those substrings as a browser. `SiteResolver.HOST_TOKEN` can match non-host
dotted tokens. Low probability, but `host` is now load-bearing — one negative-case test each.

---

## 3. Phase 2 wiring plan, file by file

**Governing principle: never flip semantics and change behavior in the same commit.** Split Phase 2
into **2A** (additive, provably no-op) and **2B** (flip).

### Phase 2A — introduce scope without changing any decision

**New: `domain/scope/LegacyScopeAdapter.kt`**
Builds an `EnforcementScope` from the three legacy fields so there is exactly one matcher and zero
behavior change:

| Legacy state | Adapter output |
|---|---|
| packages ∅ + brands ∅ | `LEGACY_ALL` — one clause, all axes `ANY` (preserves `matches()==true`) |
| packages non-∅ | `PACKAGE_PINNED(packages)` |
| brands ∋ youtube, kind `youtube_content` | `CONTENT_ANY_HOST(YOUTUBE)` |
| kind ∈ {`youtube_app_only`, `youtube_only`, `youtube_shorts_only`} | `APP_ONLY(YOUTUBE)` + pinned packages |

> **Deliberately preserve the `youtube_only` conflation here.** Un-conflating it is a *compiler*
> change (Phase 2B / Phase 3). If the adapter fixes it, you cannot distinguish an intended fix from
> a scope regression in the trace diff.

**New: `domain/scope/ScopeGate.kt`** — one function returning `ScopeMatch` + the monk-override
boolean, so the coordinator and the live AccessibilityService share a single typed input instead of
five scattered calls.

**Edit: `StudyWorldSettings.kt`** — add `enforcementScope: EnforcementScope? = null`. Keep the three
legacy fields untouched and authoritative.

**Edit: `PromiseEnforcementCoordinator.kt` and `PhoneCodexAccessibilityService.kt`** — replace the
current early `EntertainmentBanGate.mustEvaluate` scope preflight with:

```
scopeMatch = ScopeGate.evaluate(pkg, text, detection,
                 settings.enforcementScope ?: LegacyScopeAdapter.from(settings))
monkOverride = PromiseIntentRules.isLifestyleEntertainmentBan(goal) && !namesThisAppOnly(goal)

Unresolved -> ALLOW  SCOPE_UNRESOLVED_NO_ENFORCE
Exempt     -> ALLOW  SCOPE_EXEMPT_ALLOW
OutOfScope && !monkOverride -> (appRule BLOCK ? BLOCK : ALLOW ENFORCEMENT_SCOPE_ALLOW)
otherwise  -> continue
```

**Edit (remove scope checks only):** `MediaLengthEnforcement`, `ShortFormQuotaGate`,
`BlankVideoTreeGate`, `EntertainmentBanGate.mustEvaluate`. Signatures keep the legacy params as
`@Deprecated` no-ops for one release so the 80+ traces compile unchanged. This is safe only because
both live callers now run `ScopeGate` first.

**Do not touch `SessionLockLaw` in 2A.** It must work with no session scope; converting it is a
separate, later decision.

**Edit: `EnforcementDecisionLog`** — add `scopeKind` / `scopeClause`; append
`SCOPE_UNRESOLVED_NO_ENFORCE`, `SCOPE_EXEMPT_ALLOW` (append-only, per the file's contract).

**Exit gate for 2A:** the full existing trace catalog passes **byte-identical decision + reason
code**, with zero edits to any existing trace expectation. *If a single expectation needs editing,
2A is wrong.*

### Phase 2B — flip the authority

Only after 2A is green: migrate traces to explicit `enforcementScope`, wire `CompiledPromiseMapper`
+ `ConfirmedPromiseBinder` + `StudyWorldSettingsStore`, apply the `PromiseClarificationLaw` scope
precondition (design §5.1), then retire `LegacyScopeAdapter` and the `EnforcementScopeLaw` shim.

---

## 4. Risk cases

| Case | Risk | Required handling |
|---|---|---|
| **WhatsApp/Gmail explicit blocks** | F2 false-block; runs *upstream* of scope; `LOCKED` path now honors it too | Fix the cue binding **before** 2A, or 2A inherits a live false-block on connector apps. Emergency packages must stay non-overridable (already true). |
| **YouTube app vs browser vs NewPipe** | F1 — `BROWSER_ONLY` misses long/short players; F5 — NewPipe resolves as YOUTUBE identity | Fix `PLAYER_ANY` family first. Add all three detection kinds × all three hosts as a 3×3 matrix. |
| **Chrome normal pages** | F8 — `VIDEO_ANY` matches the Chrome package, so Docs/SERP enter scope | Constrain `VIDEO_CLASS_ANY_HOST` to player surfaces; assert SERP + Docs `OutOfScope`. |
| **Shorts quota** | Quota gate loses its own scope check; cross-app dedupe must still count YT→IG→TikTok as one counter | Quota must receive the coordinator's `ScopeMatch`, not re-derive it. Assert `count=k/N` unchanged across the catalog. |
| **Locked sessions** | Lock ordering unchanged in 2A, but `effectiveAppRule == BLOCK` now pre-empts `mayUseAppWhileLocked` | Assert: locked + out-of-scope + unrelated app ⇒ still `SESSION_LOCK_ALLOW_UNRELATED`, not scope-allow. Lock law must not be replaced by scope. |
| **Emergency apps** | Safe-app gate is upstream of scope in both paths — correct today | Assert dialer/SOS ALLOW under `UNRESOLVED`, under `PACKAGE_PINNED([dialer])`, and under explicit BLOCK. `phone_explicit_block_still_emergency_allow` already covers the last. |

---

## 5. Required new trace tests — **before** any Phase 2 code

Add to `PhoneSurfaceTraceCatalog` / `ScopeMatcherTest` while `ScopeMatcher` is still dead code, so
each fails for a readable reason.

**Blockers (must fail today — they prove F1/F2):**

1. `scope_browser_only_chrome_yt_long_lecture_in` — `BROWSER_ONLY(YOUTUBE)`, Chrome, `LONG_FORM_PLAYER` ⇒ `InScope`. **Fails today.**
2. `scope_browser_only_chrome_yt_short_in` — same, `SHORT_FORM_PLAYER` ⇒ `InScope`. **Fails today.**
3. `scope_category_exempt_long_movie_player_in` — `HOST_WITH_CATEGORY_EXEMPTIONS`, `LONG_FORM_PLAYER` on an unlisted site ⇒ `InScope`. **Fails today.**
4. `whatsapp_allowed_alongside_no_shorts_not_blocked` — goal `"allow whatsapp and gmail, no shorts"` ⇒ WhatsApp **ALLOW**. **Fails today.**
5. `gmail_allowed_alongside_stop_wasting_time_not_blocked` — same shape for Gmail. **Fails today.**
6. `live_service_and_coordinator_share_scope_gate_contract` — focused test/replay proving the phone path and coordinator path use the same `ScopeGate` preflight result before gate internals run.

**Equivalence tests (the Phase 2A no-op proof):**

7. `legacy_adapter_empty_scope_matches_everything` — legacy ∅/∅ ⇒ `InScope` for YT, Chrome, NewPipe, unknown clone.
8. `legacy_adapter_equals_old_matches_for_every_trace` — property test: for every catalog trace, `ScopeMatcher(LegacyScopeAdapter.from(s))` ≡ `EnforcementScopeLaw.matches(...)`.
9. `legacy_adapter_preserves_youtube_only_conflation` — `youtube_only` ⇒ app-only, Chrome out. Locks the bug in place deliberately.

**Ordering / interaction:**

10. `monk_unknown_app_still_blocks_when_out_of_scope` — monk goal, out-of-scope unknown video app ⇒ BLOCK (guards F3).
11. `locked_unrelated_app_allows_even_when_out_of_scope` ⇒ `SESSION_LOCK_ALLOW_UNRELATED`.
12. `blank_tree_adult_app_still_waits_or_blocks` ⇒ blank-tree law fires despite `ADULT_ANY` text miss (guards F7).
13. `quota_counts_across_apps_under_short_form_scope` — YT Short → IG Reel → TikTok, `count=3/N`, single counter.
14. `chrome_serp_out_of_video_class_scope` and `chrome_docs_out_of_video_class_scope` (guards F8).
15. `dialer_allows_under_unresolved_scope` and `dialer_allows_under_package_pinned_scope`.

**Resolver negatives:**

16. `host_resolver_does_not_classify_brave_lookalike_as_browser`
17. `site_resolver_ignores_non_host_dotted_tokens`

---

## 6. Forbidden in Phase 2A

- Do **not** change `SurfaceDetector` detection heuristics, regexes, or thresholds. `scopeClass` mapping only.
- Do **not** delete `EnforcementScopeLaw` in 2A — it is the equivalence oracle for test #7.
- Do **not** edit any existing trace expectation in 2A. An expectation change means the refactor changed behavior.
- Do **not** convert `SessionLockLaw` to `ScopeMatcher` in Phase 2.
- Do **not** add packages to `VideoPlatformRegistry` — it feeds `enforcesMediaSurfaces` / quota membership and would change enforcement. Keep identity tables inside `IdentityResolver`.
- Do **not** let scope produce a verdict. `InScope` only permits existing gates to run.
- Do **not** move the safe-app / emergency gate below scope.
- Do **not** "fix" the `youtube_only` conflation inside `LegacyScopeAdapter`.
- Do **not** invent a default scope. Unresolved stays unresolved.
- No vision/screenshot/audio capture, no Azure services, no package rename, no UI changes in 2A.

---

## 7. Two judgment calls made in this review

1. **Phase 2 was split into 2A/2B.** The original design has it as one phase, but F4 (empty-scope
   inversion) makes a single flip unsafe against 80+ traces, and the equivalence oracle only exists
   while `EnforcementScopeLaw` is still alive.
2. **The F2 WhatsApp/Gmail fix is folded into the Phase 2A prompt** rather than filed separately,
   because that code path runs upstream of scope and 2A would otherwise bake the false-block into
   the new architecture.

---

## 8. Verification commands

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
cd C:\Users\HP\strlix
.\gradlew.bat :app:testDebugUnitTest --console=plain

# Trace replay alone
.\gradlew.bat :app:testDebugUnitTest --tests `
  com.phonecodex.app.domain.enforcement.trace.PhoneSurfaceTraceReplayTest

# Scope suite alone
.\gradlew.bat :app:testDebugUnitTest --tests "com.phonecodex.app.domain.scope.*"

# Backend contract smoke
node backend\smoke_scope_semantics_v1.js
```
