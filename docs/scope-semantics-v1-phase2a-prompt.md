# IMPLEMENTATION PROMPT DRAFT — Scope Semantics v1, Phase 2A

Paste the block below into Cursor / Codex as-is.
Run **2A only**. Do not proceed to 2B in the same task.

Background for the reviewer: `docs/scope-semantics-v1-phase2-review.md`
Original design: `docs/scope-semantics-v1.md`

---

```text
=== IMPLEMENTATION PROMPT DRAFT — Scope Semantics v1, PHASE 2A ONLY ===

CONTEXT
Repo: C:\Users\HP\strlix. Read PROJECT_CONTEXT.md, CURRENT_STATUS.md, NEXT_TASKS.md,
AGENTS.md, docs/CURRENT_STATE.md, docs/scope-semantics-v1.md, and
docs/scope-semantics-v1-phase2-review.md.
Strlix is a personal commitment OS, not a blocker. AI is counsel; PolicyEngine is law.
Phase 1 is merged and green (636 unit tests, 0 failures). ScopeMatcher exists but is
dead code — EnforcementScopeLaw.matches() is still the live authority for all gates.

GOAL OF 2A
Route ALL scope decisions through ScopeMatcher while changing ZERO decisions.
This is a provable no-op refactor. Behavior change is Phase 2B, not this task.
Important: there are TWO live callers of the scope-sensitive gates:
  - PromiseEnforcementCoordinator.evaluate (unit-test law path)
  - PhoneCodexAccessibilityService.inspectAndEvaluate (real phone path)
Both must use the same ScopeGate preflight. Coordinator-only wiring is not enough.

PRE-WORK (tests first — each MUST fail before you fix anything)
Add these and confirm each FAILS:
  1. BROWSER_ONLY(YOUTUBE) + com.android.chrome + LONG_FORM_PLAYER  -> InScope
  2. BROWSER_ONLY(YOUTUBE) + com.android.chrome + SHORT_FORM_PLAYER -> InScope
  3. HOST_WITH_CATEGORY_EXEMPTIONS + LONG_FORM_PLAYER on an unlisted site -> InScope
  4. goal "allow whatsapp and gmail, no shorts" -> com.whatsapp ALLOW (not BLOCK)
  5. goal "no shorts, keep gmail working" -> com.google.android.gm ALLOW (not BLOCK)
Then fix exactly these two root causes:
  - ScopeMatcher.surfaceAxisMatches: a clause listing PLAYER_ANY must match
    PLAYER_ANY, PLAYER_SHORT, and PLAYER_LONG (player family). Exact membership
    for every other ScopeSurfaceClass value.
  - PromiseIntentRules.explicitlyBlocksKnownPackage: the negation cue must be
    syntactically bound to the alias (a negation governing that app), not merely
    co-occurring within an 80-character window. A bare "no"/"stop"/"except"
    elsewhere in the sentence MUST NOT block a named app the user allowed.
    Apply the same fix to FocusPromiseParser.explicitlyBlocksNamedApp.

THEN 2A
6.  New domain/scope/LegacyScopeAdapter.kt — legacy fields -> EnforcementScope:
      packages EMPTY + brands EMPTY -> LEGACY_ALL (one all-ANY clause, matches
                                       everything, preserving matches()==true)
      packages non-empty            -> PACKAGE_PINNED(packages)
      brands has youtube + kind youtube_content -> CONTENT_ANY_HOST(YOUTUBE)
      kind youtube_app_only|youtube_only|youtube_shorts_only -> APP_ONLY(YOUTUBE)
    PRESERVE the youtube_only conflation exactly as it behaves today. Do not fix it here.
7.  New domain/scope/ScopeGate.kt — returns ScopeMatch + monkOverridesScope.
    ScopeGate must be called by BOTH PromiseEnforcementCoordinator and
    PhoneCodexAccessibilityService before their blank/media/quota/content gate chains.
8.  StudyWorldSettings: add enforcementScope: EnforcementScope? = null. Leave the three
    legacy fields present and authoritative. Do not touch StudyWorldSettingsStore yet.
9.  PromiseEnforcementCoordinator AND PhoneCodexAccessibilityService: replace the
    current early EntertainmentBanGate.mustEvaluate scope preflight with ScopeGate:
      Unresolved -> ALLOW SCOPE_UNRESOLVED_NO_ENFORCE
      Exempt     -> ALLOW SCOPE_EXEMPT_ALLOW
      OutOfScope && !monkOverride -> appRule BLOCK ? BLOCK : ALLOW ENFORCEMENT_SCOPE_ALLOW
      otherwise  -> continue the existing chain
    Monk override MUST still reach the gates (EntertainmentBanGate.mustEvaluate is
    scope OR monk-bypass today — do not collapse it to one boolean).
10. Remove the internal EnforcementScopeLaw.matches() calls from
    MediaLengthEnforcement, ShortFormQuotaGate, BlankVideoTreeGate, and
    EntertainmentBanGate.mustEvaluate. Keep their parameters as deprecated no-ops so
    the 80+ existing traces compile unchanged. This is safe only if step 9 is done
    in BOTH live callers.
11. EnforcementDecisionLog: add scopeKind + scopeClause fields; append the two new
    reason codes (append-only — no existing code value may change).
12. Add equivalence property test: for EVERY trace in PhoneSurfaceTraceCatalog,
    ScopeMatcher(LegacyScopeAdapter.from(settings)) == EnforcementScopeLaw.matches(...).
13. Add ordering tests: monk unknown app still blocks when out of scope; locked
    unrelated app still allows; blank-tree adult law still fires; cross-app shorts
    quota count unchanged; dialer allows under UNRESOLVED and under PACKAGE_PINNED.
14. Add a focused test that proves coordinator and live-service helper share the same
    ScopeGate preflight contract before gate internals run.
15. Add resolver negative tests: a "brave"-lookalike package is not a browser;
    SiteResolver ignores non-host dotted tokens.

FORBIDDEN
- Do NOT edit any EXISTING trace expectation. If one needs editing, your refactor
  changed behavior — STOP and report instead of editing the expectation.
- Do NOT delete EnforcementScopeLaw (it is the equivalence oracle for step 12).
- Do NOT convert SessionLockLaw to ScopeMatcher.
- Do NOT change SurfaceDetector heuristics, regexes, or thresholds.
- Do NOT add packages to VideoPlatformRegistry (it feeds enforcesMediaSurfaces and
  short-form quota membership — that would change enforcement). Keep identity tables
  inside IdentityResolver.
- Do NOT let scope emit a verdict. InScope only permits the existing gates to run.
- Do NOT move the safe-app / emergency gate below scope.
- Do NOT fix the youtube_only conflation in the adapter.
- Do NOT invent a default scope when unclear. Unresolved stays unresolved.
- No UI changes, no vision/screenshot/audio capture, no Azure services, no package
  rename, and no compiler/binder/store wiring (that is Phase 2B).

SUCCESS
- $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
  .\gradlew.bat :app:testDebugUnitTest  -> BUILD SUCCESSFUL,
  >= 636 tests + the new ones, 0 failures, 0 errors.
- PhoneSurfaceTraceReplayTest passes with ZERO edits to existing expectations.
- The 5 pre-work tests now pass.
- Both coordinator and live AccessibilityService path use ScopeGate before direct
  gate internals; no remaining live path calls EnforcementScopeLaw through those gates.
- The equivalence property test passes for every catalog trace.

DELIVER
- Code + tests only.
- A short note listing every decision that changed (expected: none), plus the
  before/after behavior of the two bug fixes (PLAYER_ANY family, app-block cue).

BEFORE CODING: state the plan in <=10 lines and name every file you will create or edit.
AFTER CODING: give the exact commands you ran and their output.

NORTH STAR: natural-language promise -> structured policy -> deterministic enforcement
-> log/feedback. Scope must be typed, versioned, testable, and honest about what it
does not know. "Blocked UI, not blocked life" — never false-block a connector app the
user explicitly allowed.
```
