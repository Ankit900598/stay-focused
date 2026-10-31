# PHONECODEX P0 — Advanced Controls contract

When: 2026-10-31 ~15:40 IST
Owner: Droid (CEO GO from Starlink)
Result: **PASS** (computer-only wire + unit tests)

## Done

1. Scan: Session Tuning (lock / cooldown / duration) already stored; binder already preferred customized lock + saved cooldown; **gap** was non-explicit duration ignoring Advanced duration, and no round-trip unit tests. Recovery card is display-only hardcodes — labeled as fixed defaults.
2. Smallest wire in `ConfirmedPromiseBinder.bind`:
   - non-explicit duration → `savedDefaults.durationMinutes`
   - cooldown fallback const named `DEFAULT_BLOCK_ATTEMPT_COOLDOWN_MINUTES`
3. Unit tests: `ConfirmedPromiseBinderAdvancedControlsTest` (7 cases)
4. Contract note: `docs/advanced-controls-contract-2026-10-31.md`
5. NEXT_TASKS P0#1 / P0#4 refreshed

## Unit test result

`./gradlew :app:testDebugUnitTest --tests com.phonecodex.app.domain.promise.ConfirmedPromiseBinderAdvancedControlsTest` on laptop → **BUILD SUCCESSFUL** (7 tests).

## HOLD unchanged

No Play upload, no auth flag flips, no paid Sol, no kvm, no Ankit ping for legal/Play.

## Evidence

- Box: `/workspace/live/PHONECODEX-P0-ADVANCED-CONTROLS.md`
- Laptop: `C:\Users\HP\strlix\docs\advanced-controls-contract-2026-10-31.md`
- Tests: `app/src/test/.../ConfirmedPromiseBinderAdvancedControlsTest.kt`
