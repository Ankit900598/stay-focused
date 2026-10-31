# Cursor Task — Trustworthy Commitment Runtime v1

## Product outcome

Strlix is a personal commitment OS, not a generic blocker. A person should be able to express a messy promise; Strlix should clarify uncertainty, show an accurate preview, require confirmation, enforce the resulting policy deterministically on the phone, explain each outcome, and provide a real safe recovery path. Later horizons are a conversational text/voice assistant and separately authorized connectors that can help with necessary tasks while the distracting app UI remains blocked. This task strengthens the shared runtime those features depend on; it does not narrow the product to YouTube.

## Verified starting facts (2026-09-27)

- The repo has a large dirty working tree. Preserve every existing edit. Do not reset, stash, overwrite, blanket-stage, or commit it as part of this task.
- `PhoneCodexAccessibilityService.inspectAndEvaluate()` runs the live phone decision chain directly. The service calls `PromiseEnforcementCoordinator.evalPrelude()` for diagnostics, not `PromiseEnforcementCoordinator.evaluate()` for the decision. `EnforcementTraceEvaluator` calls the coordinator. Therefore trace replay and live phone enforcement do not currently share one complete decision path.
- The Recovery card wires “Emergency exit” and “Request cooldown unlock” to `HomeScreenState.logEmergencyExit()` and `logCooldownUnlock()`. Those methods only append event text; they do not end or suspend the session. This is a real UI/function mismatch.
- A normal “End Commitment” path exists separately and calls `performEndCommitment()`, which clears the session and restores commitment app rules. Preserve this cleanup behavior and never remove permanent guardrails.
- A direct, read-only GET of hosted `/health` returned compiler `pc-lab-astra` / `promise_compiler_v07`, classifier `pc-lab-terra` / `v04`, and vision disabled/default-off. Do not deploy, flip Azure settings, or describe hosted behavior as v09 in this task.
- Android parses `scopeContract`, but its promise DTO does not carry `meta.promptVersion`. Local v09 phone evidence in `CURRENT_STATUS.md` is one YouTube flow; it is not proof of hosted parity or other app classes. The 833 Android tests and 12 backend smoke results in the status document were not rerun during this review.
- The code stores promise strictness separately as `promiseStrictness`; `FocusSession.strictness` still comes from the world and is what `PolicyEngine` reads. Do not assume those meanings are equivalent. Make the chosen user-visible semantics explicit and test them.
- Vision is currently off by default in settings and hosted health. Some comments/docs incorrectly say debug-default-on; do not enable vision or spend inference as part of this task.

## Task

Make the commitment runtime have one testable decision law, an honest preview/version contract, and functional recovery controls. Work in phases; preserve behavior unless a verified defect requires a targeted correction. Do not build new app-specific exceptions.

### Phase 0 — protect and map the current work

1. Record the initial `git status` and summarize existing modified/untracked files by logical feature. Do not attribute old edits to this task or overwrite them.
2. Read the current Kotlin and backend implementation before changing it. Separate already-present functionality from genuine gaps.
3. Add no code until the current baseline and the exact files to touch are identified.

### Phase 1 — one decision law for phone and replay

1. Add characterization tests for the current ordering and high-risk outcomes before refactoring.
2. Create or complete a pure, deterministic commitment evaluator that consumes an immutable snapshot of the active promise/session, stored typed scope, app rule, package, detected surface/evidence, temporal counters, and guardrail state, then returns one structured decision (decision, reason code, rule/stage, and policy/scope version).
3. Make both the live AccessibilityService and `EnforcementTraceEvaluator` use that same evaluator for the policy decision. Keep Android-only event collection, foreground lifecycle, tamper detection, network calls, and overlay effects outside the pure evaluator.
4. Explicitly document and test precedence for emergency/safe paths, permanent guardrails, scope, structured surface rules, quotas/clocks, app rules, and advisory AI. Do not let AI override safety or confirmed deterministic rules.
5. If a full extraction cannot be done without a broad behavioral rewrite, stop at a smaller delegation seam that demonstrably shares the same law; report the blocker rather than leaving two evaluators that merely look similar.

### Phase 2 — make the confirmed contract truthful

1. Carry backend compiler/policy version metadata into the parsed promise and start audit. Record the hosted v07 vs v09 distinction; never infer v09 from a local prompt or deployment name.
2. For a promise whose requested behavior requires typed scope, do not silently start under a legacy response that cannot represent it. Keep Start disabled with clear reconnect/unsupported-version copy. Preserve valid simple legacy promises where their behavior is actually representable.
3. Audit the full path from compiler DTO → `FocusPromise` → confirmation preview → `ConfirmedPromiseBinder` → stored settings/session → evaluator. Temporal meanings (session duration, media length, daily quota/calendar reset) and strictness must not be re-guessed differently at each stage. Either bind the confirmed structured value or explicitly clarify/refuse unsupported semantics.
4. Build confirmation copy from the resolved policy that will actually be stored and enforced, not an optimistic paraphrase. Add paired tests asserting preview and stored/evaluated behavior agree.
5. Keep unknown/ambiguous app or surface behavior unresolved; never broaden an unsupported narrow scope to a whole app. Preserve existing typed-scope corruption fail-closed behavior.

### Phase 3 — make recovery controls real and safe

1. Fix the “Emergency exit” action so tapping it invokes a real, documented safety exit (or replace the control with accurate wording if a separate emergency-exit design is required). A log entry alone is not an exit.
2. Safety calls, emergency functions, and critical system paths remain usable even during a locked commitment. No model, promise, or app rule may remove them.
3. “Request cooldown unlock” must either perform a real, bounded, user-understood recovery flow or be disabled/removed with honest copy until implemented; never present a decorative action that only logs.
4. Keep ordinary early-end friction distinct from emergency exit. Reuse the existing session/app-rule cleanup exactly once; end only session-scoped rules, never permanent guardrails. Log the final result, not merely that a request was tapped.
5. Test process recreation, duplicate taps, already-expired sessions, LOCKED sessions, and state restoration so the UI cannot say active after the safety exit completed.

## Generalization tests

Use a table-driven set of real or frozen cases across the supported semantic classes—not 40 variants of YouTube. Include at least:

- Video/player class: short-form player versus long video; Home/recommendations are not active playback; missing player evidence must not invent a duration.
- Social surfaces: messages/DMs versus feed/Reels; test only app/surface pairs the detector actually supports. Unsupported feed/per-chat scope must ask or refuse honestly.
- Browser: ordinary search/docs versus a detected active video; test Chrome plus another browser only if the resolver supports it.
- Scope and identity: named app, app class, any supported video host, unknown package, malformed/corrupt typed scope, and legacy scope.
- Time semantics: session timer versus per-item media length versus daily count; include exact boundary and midnight behavior where supported.
- Safety/lifecycle: emergency/dialer, app switching, own overlay events, Accessibility loss, stale classifier response, session expiry, manual end, and recovery exit.
- Language: messy English/Hinglish/typos and negation; ambiguity returns a useful clarification rather than guessed enforcement.

For each case record expected and actual decision separately. Report false allows and false blocks separately; do not summarize them as one accuracy percentage. Unknown evidence should have an explicit WAIT/clarification policy rather than a fabricated classification.

## Required verification and deliverables

- Run the relevant focused tests first, then the full Android unit suite and every backend smoke script. Report the exact command, test count, and failures; do not quote old counts as new results.
- Add a structural regression test proving the live service and trace replay both delegate policy decisions to the same pure evaluator.
- Add tests for the recovery actions and for compiler-version/scope-contract handling, including hosted-v07/legacy behavior and v09/typed behavior.
- Build the debug APK, but do not install or change phone settings in this implementation task. Stop after tests/build and provide the separate phone-verification checklist for the next task.
- Do not deploy to Azure, change prompt versions, change secrets, edit `.env`, add a cloud service, or enable vision.
- Do not stage or commit the existing dirty tree. Keep changes reviewable and list the exact files modified by this task separately from pre-existing changes.
- Finish with: (1) verified root causes fixed, (2) architecture before/after, (3) test/build results, (4) unsupported cases still clarified/refused, (5) precise phone test cases and expected log reason codes, (6) risks and next product horizon.

## Exit gate

This milestone is complete only when the live phone decision path and offline replay share the same policy evaluator; preview, stored policy, and decision agree for the covered cases; no unsupported typed promise silently downgrades; recovery controls do what their labels claim while safety paths remain available; and all listed automated checks pass. After this gate, the next horizon is the conversational text/voice experience—not more one-off app patches. Delegated connectors remain a later, separately consented, least-privilege project.
