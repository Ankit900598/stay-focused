# Scope Semantics v1 — Phase 1 Exit Report

**Track:** AI Lab + Android domain
**Date:** 2026-09-22
**Design ref:** `docs/scope-semantics-v1.md` §7 Phase 1
**Scope of this phase:** data model + compiler contract + tests only. No enforcement wiring, no UI change, no `EnforcementScopeLaw` deletion. `ScopeMatcher` is dead code in the app until Phase 2.

---

## 1. Files delivered

### Kotlin domain (pure, no Android imports outside SurfaceDetectionResult import)

| File | Role |
|---|---|
| `app/src/main/java/com/phonecodex/app/domain/scope/EnforcementScope.kt` | Data classes + all enums + `ScopeMatch` sealed interface |
| `app/src/main/java/com/phonecodex/app/domain/scope/ScopeKindPresets.kt` | Kind -> `(include, exempt)` clause expansion (11 kinds) |
| `app/src/main/java/com/phonecodex/app/domain/scope/HostResolver.kt` | `host(pkg, text) -> NATIVE_APP | BROWSER | ANY_HOST` |
| `app/src/main/java/com/phonecodex/app/domain/scope/IdentityResolver.kt` | Identity + `*_ANY` class resolution; delegates adult to `AdultContentLaw`, video/short-form to `VideoPlatformRegistry` |
| `app/src/main/java/com/phonecodex/app/domain/scope/SiteResolver.kt` | Generalised host-pattern matcher over a11y text |
| `app/src/main/java/com/phonecodex/app/domain/scope/ScopeMatcher.kt` | Pure `match(pkg, text, detection, scope)` — the one scope authority |
| `app/src/main/java/com/phonecodex/app/domain/scope/ScopeSerialization.kt` | JSON <-> `EnforcementScope`; validate-or-`UNRESOLVED` |
| `app/src/main/java/com/phonecodex/app/domain/scope/ScopeAppliesToCopy.kt` | The only producer of user-facing scope copy |
| `app/src/main/java/com/phonecodex/app/domain/scope/SurfaceScopeMapping.kt` | Pure `SurfaceDetectionResult.scopeClass` extension (no SurfaceDetector change) |

### Tests

| File | Tests |
|---|---|
| `ScopeMatcherTest.kt` | 33 (algebra + design §6.3 rows 1–29 encoded verbatim) |
| `ScopeKindPresetsTest.kt` | 11 (one per kind + UNRESOLVED-has-no-preset) |
| `ScopeSerializationTest.kt` | 12 (round-trip + all rejection paths) |
| `HostResolverTest.kt` | 3 (browser set, native set, unknown default) |
| `IdentityResolverTest.kt` | 9 (each identity + `*_ANY` class resolution) |
| `SiteResolverTest.kt` | 6 (patterns, wildcards, noisy text) |
| `ScopeAppliesToCopyTest.kt` | 4 (every kind package-free + UNRESOLVED + null) |

### Backend + evals

| File | Role |
|---|---|
| `evals/prompts/promise_compiler_v09.txt` | v08 verbatim + required `scope` object (§3.1) + all 11 kinds + `evidenceSpans` rule + no default kind |
| `backend/promiseCompilerService.js` (edit) | Added `promise_compiler_v09` to `ALLOWED_PROMPT_VERSIONS`; exported `validateScope`, `validateOptionScope`, `enforceScopeStartGates`, `isEnforceableScope` + all SCOPE_* enum sets. **Default prompt unchanged at `promise_compiler_v07`.** No change to existing normalize path. |
| `backend/smoke_scope_semantics_v1.js` | Backend contract smoke — 12 groups of assertions + dataset self-check |
| `evals/datasets/build/v9_scope_semantics_build.js` | Deterministic dataset generator |
| `evals/datasets/v9_scope_semantics.jsonl` | 155 rows across 11 kinds + Hinglish + typos + voice + all §5.2 ambiguity triggers |

---

## 2. Success metrics

### 2.1 Gradle unit tests

```
> .\gradlew.bat :app:testDebugUnitTest
BUILD SUCCESSFUL
tests: 635    failures: 0    skipped: 0
```

Of those:

| Suite | Tests | Failures |
|---|---|---|
| `com.phonecodex.app.domain.scope.*` (new) | 78 | 0 |
| `PhoneSurfaceTraceReplayTest` (unchanged expectations) | 6 | 0 |
| Everything else | 551 | 0 |

**Exit gate: PASS.** Existing `PhoneSurfaceTraceReplayTest` expectations untouched.

### 2.2 Backend smoke

```
> node backend\smoke_scope_semantics_v1.js
v9 dataset per-kind counts:
  APP_ONLY                         15
  BROWSER_ONLY                     12
  CONTENT_ANY_HOST                 12
  HOST_WITH_CATEGORY_EXEMPTIONS    14
  PACKAGE_PINNED                   11
  SHORT_FORM_ANY_HOST              16
  SITE_SCOPED                      13
  SOCIAL_FEED_AND_REELS_ONLY       12
  SOCIAL_MESSAGING_ONLY            11
  UNRESOLVED                       30
  VIDEO_CLASS_ANY_HOST              9
  TOTAL                            155
ASSERTIONS_PASSED=676
```

Contract invariants asserted:
- `validateScope` accepts well-formed v09 APP_ONLY
- Missing / null / non-object / array / string / number scope -> UNRESOLVED(MISSING_EVIDENCE)
- Bad version (0, 2, 99, "1", null) -> UNRESOLVED(BAD_VERSION)
- Unknown kind -> UNRESOLVED(UNKNOWN_KIND)
- Non-verbatim evidenceSpans -> UNRESOLVED(MISSING_EVIDENCE)
- Empty evidenceSpans on non-`PACKAGE_PINNED` kind -> UNRESOLVED(MISSING_EVIDENCE)
- `PACKAGE_PINNED` tolerates empty evidenceSpans (design §3.1)
- Package id in `appliesToText` -> UNRESOLVED(PACKAGE_LEAK_IN_APPLIES_TO)
- Bad surfaces / host / packageMatch enum -> UNRESOLVED(MALFORMED_CLAUSE)
- Missing clause id -> UNRESOLVED(MALFORMED_CLAUSE)
- Empty include on non-UNRESOLVED kind -> UNRESOLVED(MALFORMED_CLAUSE)
- `validateOptionScope` runs through the SAME validator (English-blob regex fallback deleted for v09)
- `enforceScopeStartGates` forces `canStartCommitment=false` + `clarificationRequired=true` on any UNRESOLVED
- UNRESOLVED with 3 candidateScopes preserves alternatives after round-trip

**Exit gate: PASS.**

Existing smoke tests (`smoke_promise_compiler_normalize.js` = 176 assertions, `smoke_promise_compiler_v08.js` = 76 assertions) continue to pass unchanged.

### 2.3 v9 dataset structural invariants

Every dataset row was checked against:
- `expectedScope.kind` is one of the 11 SCOPE_KINDS: **155 / 155 pass**
- `expectedScope.identity` is a valid SCOPE_IDENTITIES value or `null`: **0 unknown identity rows**
- `expectedScope.appliesToText` contains no package id: **0 leaks**
- Every non-UNRESOLVED / non-PACKAGE_PINNED row supplies non-empty `evidenceSpans`: **0 missing**
- Every `evidenceSpan` is a verbatim substring of `userPromise` (generator asserts at build time): **enforced**
- Dataset covers ≥ 8 of the 11 kinds: **11 / 11 covered**
- Dataset has ≥ 15 UNRESOLVED rows: **30 UNRESOLVED**

### 2.4 §6.3 rows 1–29 encoded as unit tests

All 29 rows run inside `ScopeMatcherTest` under names `row01_scope_app_only_yt_player` through
`row29_scope_short_form_ig_dm_out`. All 29 pass.

The row-6 killer case (`BROWSER_ONLY(YOUTUBE)` + Firefox with a youtube.com URL -> IN_SCOPE) is
green — the exact case today's `chrome_browser` rematerialize fails.

### 2.5 Wrong-host errors on v9 dataset

Phase 1 does not yet run the model itself; wrong-host is measured against the ground-truth scope
kinds encoded in the dataset. Since the dataset is authored, this metric on the dataset itself is
0/155. **Model-vs-dataset wrong-host will be measured in Phase 2** after the compiler is switched to
`promise_compiler_v09` behind the env flag.

Structural invariant: `validateScope` mathematically cannot emit a scope whose host doesn't match
one of `NATIVE_APP | BROWSER | ANY_HOST`; any other value becomes UNRESOLVED(MALFORMED_CLAUSE).
This is asserted by `ScopeSerializationTest.malformedSurfaceEnumFallsToUnresolved` and by direct
enum-set membership checks in `validateClauseList`.

### 2.6 Package-leak invariant

`ScopeAppliesToCopyTest.everyEnforceableKindProducesNonEmptyPackageFreeCopy` iterates all 10
enforceable kinds and asserts `ScopeAppliesToCopy.containsPackageId(copy) == false` for each.
**Package leaks in scope copy: 0.**

The backend enforces the same rule on the compiler boundary via `validateScope`:
`appliesToText` matching `/\b(?:com|org|net|io)\.[a-zA-Z0-9_.]+\b/i` -> UNRESOLVED.

---

## 3. Known limitations of Phase 1

By design, Phase 1 does not:

1. Wire `ScopeMatcher` into `PromiseEnforcementCoordinator`. Six gates still call
   `EnforcementScopeLaw.matches()`. This is Phase 2.
2. Persist `enforcementScope` in `StudyWorldSettings`. Phase 2.
3. Change any user-visible behaviour on the phone. Phase 3.
4. Flip the backend default prompt to v09. Phase 4 (env flag change only).
5. Run the v9 dataset through the actual compiler — that requires the AI Lab runner, which
   lives outside this phase.
6. Delete `EnforcementScopeLaw` or the deprecated `enforcementScope{Packages,Kind,Brands}`
   settings fields. Phase 4.

None of these are exit-gate requirements for Phase 1.

---

## 4. Failures / regressions caught during development

- **`ScopeSerializationTest.candidateScopesAreParsedRecursively` initially failed:** the test
  built a candidate scope with `evidenceSpans=["YouTube app only"]` under an outer promise
  `"only YouTube"`. The recursive parse then correctly rewrote the candidate to UNRESOLVED
  because "YouTube app only" is not a verbatim substring of "only YouTube".
  **Fix:** rewrite the test to use the actual shared user span `"only YouTube"` for every
  candidate — which is the real compiler contract anyway. This bug was in the *test*, not the
  code, but it validated that the boundary correctly refuses hallucinated evidence.

Nothing regressed in the existing 551 pre-Phase-1 tests or the two existing backend smokes
(176 + 76 assertions).

---

## 5. What Phase 2 inherits

- One typed selector algebra, versioned, testable, and honest about ambiguity.
- One `ScopeMatcher.match(...)` call ready to replace 6 gate-level `matches()` calls.
- One `ScopeAppliesToCopy.forScope(...)` ready to replace `sanitizeConfirmationLinesForScope` +
  friends in `CompiledPromiseMapper` (~130 lines of prose surgery).
- One `validateScope()` ready to accept `option.scope` on rematerialize (deletes the
  English-blob regex fallback in `resolveOptionRematerialize`).
- 155 dataset rows for measuring compiler exact-kind-match + wrong-host once v09 is enabled.
- Every ambiguity trigger in §5.2 has at least one dataset row.
