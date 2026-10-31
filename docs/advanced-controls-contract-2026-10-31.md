# Advanced Controls contract — 2026-10-31

PolicyEngine is law. Advanced Controls the user set override inferred strictness / parser defaults.
Do not claim settings work without unit-test round-trip (`ConfirmedPromiseBinderAdvancedControlsTest`).

## Field → storage → binder → enforce

| UI control | Storage | Binder read | Enforce path | Status |
|---|---|---|---|---|
| Lock after attempts (Auto / 3 / 5 / 10) | `StudyWorldSettingsStore` `lock_attempt_threshold` + `lock_attempt_threshold_customized` | `ConfirmedPromiseBinder.bind`: draft.lockAttemptThreshold → customized saved → strictness map (3/10) | `PhoneCodexAccessibilityService` reads `getSettings().lockAttemptThreshold` | **WIRED** + unit tests |
| Block cooldown (1 / 2 / 5 min) | `block_attempt_cooldown_minutes` | `savedDefaults?.blockAttemptCooldownMinutes ?: 2` | Accessibility uses `blockAttemptCooldownMinutes` for re-block delay | **WIRED** + unit tests |
| Duration (15 / 30 / 60 + current) | `duration_minutes` | When `!sessionDurationWasExplicit` and not calendar-day: Advanced duration; else promise duration | Session clock via `sessionDurationMillis(bound)` | **WIRED** (2026-10-31) + unit tests |
| Strict zone recovery (mistakes / cooldown hours / admin / fee / override) | `RecoveryPolicyDefaults` constants only | N/A — not bound into StudyWorldSettings | Recovery flows use `RecoveryPolicyDefaults.default()` | **READ-ONLY display** — copy says fixed defaults; no fake editors |

## Precedence (CEO)

1. Promise-stated value (explicit duration, explicit lock count)
2. User Advanced Controls override (`lockAttemptThresholdCustomized`, saved cooldown/duration)
3. Inferred defaults (strictness→lock map; parser fallback duration; cooldown 2)

## Start path

`PromiseUnderstanding` → `ConfirmedPromiseBinder.bind(draft, studyWorldSettingsStore.getSettings())` → `beginCommitment(bound)` → Accessibility reads store.

## Gaps left (not this lander)

- Recovery policy not user-editable (intentional until a store exists).
- Hosted auth flags still HOLD.
- No Play upload.
