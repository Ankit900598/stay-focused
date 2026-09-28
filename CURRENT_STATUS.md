# Current Status

Read this after `PROJECT_CONTEXT.md`.

## Current Repo

Path:

```text
C:\Users\HP\strlix
```

Current app package:

```text
com.phonecodex.app
```

The name may change later. Do not rename packages casually.

## Latest Verified Checkpoint — 2026-09-28 13:30 (production promoted to v09)

- `39f130c`: the confirmation card drops model notes that deny the session lock ("…not locking the phone"); the drift line "I block it and lock the session after 3 tries." stays. Every strictness locks (Strict/Locked after 3, Gentle/Balanced after 10). **908 Android unit tests pass** (fresh result XMLs).
- **Production `phonecodex-backend` promoted** (Ankit approved): App Settings `PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09`, `STRLIX_BUILD_ID=8ed37e46d2f52f8d863473499944b37b7e3c2887`; zip built by `git archive 8ed37e4`, deployed `--async` (Azure deployment status 4 = success). Gate: `/health` `build=8ed37e4…`, `promise_compiler_v09`, compiler `pc-lab-astra`, classifier `pc-lab-terra`/`v04`, vision off, secret required, POST without secret -> 401. Live parity: "Strict: only 2 YouTube Shorts today" -> options "YouTube short videos only | Short videos everywhere"; pick -> `snapshot_and_carry_verified`, no clarification, stronger confirm required, rules identical to the staging fixture (≤2 count ALLOW, >2 count BLOCK).
- Rollback: `.tmp-azure-prod-rollback/prod_wwwroot_before_v09.zip` (pre-promotion wwwroot, v07 default, no `build`). Redeploy it and delete both App Settings.
- `phonecodex-backend-staging` deleted; the F1 plan is kept for production.
- Tester APK built from `39f130c` with the production URL (dex has the production URL, not staging): SHA-256 `BF0A101A…2EF23187`, kept at `.tmp-phone-verify-20260928-hosted/strlix-tester-39f130c.apk` (ignored). Installed on Ankit's phone with `install -r` (data kept, no active commitment), Accessibility rebound, Home says Protected, decisions logged, `stay_on=0`.
- Not yet done on this APK: an end-to-end promise on the phone against production, and an agent-recorded PiP escape check.

## Earlier checkpoint — 2026-09-28 afternoon (PiP escape + today deadline)

- Branch `checkpoint/2026-09-27` gained `0bdde0c` (picture-in-picture escape guard) and `33dd29e` ("today" promises end at local midnight, as the card says).
  - `PictureInPictureEscapeLaw` (pure, unit-tested): pause a PiP window when the session is LOCKED and the app is not usable while locked, or when the same package was blocked in the last 10 minutes and has not been allowed since. Safe packages and no-session are never touched. The service listens to `TYPE_WINDOWS_CHANGED` (`flagRetrieveInteractiveWindows`), pauses via a media-pause key, rechecks every 2 s while the guard holds, and also pauses video apps whenever the block overlay is shown.
  - Deadline: a "today / rest of the day / for the day" promise with no explicit duration runs until the next local midnight; `SessionExpiryPolicy` computes end-of-day from the session start (before, quota sessions never expired). The checkbox now reads "I understand this commitment lasts until midnight tonight".
- Proof: **906 Android unit tests pass**; 12/12 backend smokes pass; `backend/` unchanged since `8ed37e4`.
- Phone (staging APK `5708DD16`, built from `33dd29e`), "Strict: only 2 YouTube Shorts today": checkbox shows the midnight label; session deadline 29-09-2026 00:00 IST; shorts 1–2 counted, short 3 `QUOTA_BLOCK_EXCEEDED` with "Media paused reason=block_overlay" logged before the overlay.
- PiP escape (block, then Home): the agent's run was interrupted — the commitment was ended by hand on the phone 13 s after the block, so the harness recorded nothing either way. **Ankit then checked the escape by hand and reports it is fixed.** No agent screenshot/logcat proves the `pip_guard` path; re-check it in the next tester APK run.
- Phone restored again: production APK `E0917876`; 9 prefs files MD5-match the backup; test-only recovery/quota prefs deleted; Accessibility list equals the saved value and decisions are logged; `stay_on_while_plugged_in=0`; Home says Protected, no timed commitment. `local.properties` points at production.

## Earlier checkpoint — 2026-09-28 morning (hosted-v09 phone flow on staging)

- Branch `checkpoint/2026-09-27` (local, not pushed; `master` unchanged) gained `59e0a18` (Start gate agrees with the card after a pick plus stronger confirm), `8ed37e4` (backend: legacy option preview follows the typed scope; fixed 1-in-3 `snapshot_invalid_scope` pick failures), `66007ee` (short-form count rules stay inside a Shorts-only scope, so quota promises can start).
- Proof: **894 Android unit tests pass** (forced rerun, fresh result XMLs); **12/12 backend smokes pass**. Staging `/health`: `build=8ed37e46…`, `promise_compiler_v09`; `backend/` is unchanged since that SHA. Production `/health`: still `promise_compiler_v07`, no `build`.
- Phone (staging backend, APK built from `59e0a18`, then `66007ee` for the quota test). Promise "Block YouTube Shorts for 1 hour", pick YouTube-only, tick confirm, Start:
  - Saved typed scope `APP_SHORT_FORM_ONLY`; YouTube app rule stays `AI_DECIDE`.
  - YouTube Home ALLOW (`ENFORCEMENT_SCOPE_ALLOW`); Shorts BLOCK with overlay, "Leave this screen" returns to YouTube Home.
  - 50-min MIT 6.006 lecture ALLOW (stage SCOPE; surface mislabeled COMMENTS). Chrome `m.youtube.com/shorts` ALLOW as out of scope (matches the promise); Chrome article ALLOW.
  - Messages, Accessibility settings, dialer, emergency dialer: `EMERGENCY_ALLOW`, no overlay.
  - End Commitment (Balanced = no friction, by design) ended in one tap; `app_rules` content identical to backup; Shorts then `NO_ACTIVE_SESSION_ALLOW`.
- Phone quota test, "Strict: only 2 YouTube Shorts today", pick YouTube-only: **found and fixed** — the card invented "Should I block all of YouTube, or only part of it?" and Start never opened (`app_surface_choice_misses_rules` treated the ≤2/>2 count rules as outside the Shorts scope). After `66007ee`: Start opens; short 1 counted 1/2, pause/resume = same play, short 2 counted 2/2 and kept playing through pause/resume, short 3 BLOCK `QUOTA_BLOCK_EXCEEDED`; "N shorts left" warning overlay shows. Three blocked attempts -> session LOCKED. Emergency exit (Recovery options) ended it: session cleared, `app_rules` MD5 = backup, guardrails kept, use recorded. "Only once per 24h" is unit-tested (`CommitmentTeardownTest`), not phone-tested.
- Harness lesson: `uiautomator dump` unbinds every Accessibility service while it runs, which causes "Not protecting" flicker. Check enforcement with `screencap` + logcat only.
- Phone restored: production APK `E0917876` reinstalled; all 9 prefs files match the pre-test backup byte-for-byte (a Strlix process restarted after reinstall and rewrote two diag files; it was killed via `run-as kill` — not force-stop — and the files restored again); test-only `phonecodex_recovery.xml` and `phonecodex_short_form_quota.xml` deleted; `stay_on_while_plugged_in=0`; Accessibility bound; protection check "Protected; a11y=Alive; commitment=None; guardrail=On; backend=Reachable". `local.properties` points at production. Evidence: `.tmp-phone-verify-20260928-hosted/` (local, ignored).

## Earlier checkpoint — 2026-09-27 night (hosted-v09 staging)

- Branch `checkpoint/2026-09-27` (local, not pushed; `master` unchanged): `b6f799f` gitignore, `bb7611a` backend v09, `993a274` WIP Android runtime v1, `19dfea6` docs, `68c7532` quota fingerprint fix plus `/health` `build`, `37ac85c` bare-shorts options fix.
- Quota fix (code + tests only, **not yet phone-verified**): a short is keyed by channel handle plus title with player chrome stripped, so play/pause/resume is one play; a short already counted today stays `ALLOW_DUPLICATE` at the limit, and only a new short past N blocks. Regression tests use the real 2026-09-27 YouTube dumps. **887 Android tests pass** (forced rerun), `assembleDebug` OK, 12/12 backend smokes pass.
- `/health` now returns `build` = App Setting `STRLIX_BUILD_ID` (null when unset).
- The plan is **F1 Free**, which allows no deployment slots. With approval, a second web app, **`phonecodex-backend-staging`**, was created on the same F1 plan (settings copied from production, Node 20, `PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09`). It shares F1's daily CPU quota with production; delete it when the hosted gate is done.
- Staging gate PASS: `/health` reports `build=37ac85cb3bd4…`, compiler `pc-lab-astra` / `promise_compiler_v09`, vision OFF, secret required; POST without secret -> 401. Production is unchanged: `promise_compiler_v07`, no `build`.
- Hosted API parity (staging, real model):
  - Found and fixed: for "I waste my evenings on shorts", 6/7 turn-1 runs showed two identical "Short videos everywhere" options (model-authored options kept, then relabeled from their own `SHORT_FORM_ANY_HOST` scope). After `37ac85c`: 3/3 runs show the server-built A (all short video) and B (YouTube short videos only).
  - Pick B: scope matches the fixture exactly (`APP_SHORT_FORM_ONLY`, YouTube app, `PLAYER_SHORT`, pinned), `snapshot_and_carry_verified`. Start stays closed: the model treats the sentence as a complaint, not a rule, and asks a free-text block-or-warn question with no options, so the user is stuck (safe, but a UX dead end).
  - "Block YouTube Shorts for 1 hour": always asks YouTube-only vs everywhere (extra tap); after the pick, the scope is correct and Start needs the "Confirm before Start" checkbox (the model sets `requiresHumanConfirmation`).
- Phone: **the hosted-v09 phone flow has NOT been run.** The staging APK (`88A6E580…`) was installed briefly, then the phone was restored to the 16:01 APK (`E0917876…`). 7/9 pref files match the backup byte-for-byte; `debug_state` and `runtime_diag` differ only in live last-event fields. No commitment is active. After `install -r`, MIUI leaves Strlix Accessibility enabled but unbound; re-toggling the setting (same value) rebinds it and decisions are logged, but the restored 16:01 build's Home still says "Not protecting" (the staging build showed "Protected" after the same rebind). Tap Reconnect Accessibility on the phone if it persists.

## Earlier checkpoint — 2026-09-27 evening (release audit)

- Full Android unit suite, forced fresh rerun at 16:03 IST on the current tree: **884 tests, 0 failures, 0 errors, 0 skipped**. No source file changed after that run. Debug APK built 16:01, SHA-256 `E0917876…7C1E6A17`; this same APK is installed on the phone.
- All 12 backend `smoke_*.js` scripts re-run at 19:25 IST, all exit 0 (local; mock/fake endpoints, no Azure call).
- Hosted `/health` re-checked read-only at 19:24 IST: compiler `pc-lab-astra` / **`promise_compiler_v07`**, classifier `pc-lab-terra` / `v04`, vision OFF, app secret required. **Hosted is not v09.** `/health` exposes no code revision/build id, so a deploy cannot yet be proven from it. There is no staging slot; `phonecodex-backend` is the only web app.
- Quota phone test (daily limit 2, long educational allowed) on the 16:01 APK: passive screens PASS, distinct shorts count once PASS, play N+1 blocks PASS. **FAIL: pausing/resuming one Short counted it twice and then false-blocked it** (title hint picks up the "Pause video" label). Long lecture was allowed but via `COMMENTS`/passive, not `ALLOW_LONG_EDUCATIONAL`. Unreadable-quota handling is unit-tested only.
- After that test the phone was restored: all 9 preference files match the pre-test backup byte-for-byte, the new quota file was deleted, **no commitment is active**, and **Accessibility is bound** (`dumpsys accessibility`; Home says Protected, no timed commitment). Evidence: `.tmp-phone-verify-20260927-quota/` (local, ignored).
- Working tree: **73 modified + 85 untracked entries**, nothing staged, no secrets found in tracked or untracked source (keyword hits are env reads/error strings only). Local evidence folders `.tmp-*` are ignored.

### Earlier checkpoint — 2026-09-27 morning

- Full Android unit suite was forced to rerun: **833 tests, 0 failures, 0 errors, 0 skipped**.
- All 12 backend `smoke_*.js` scripts passed; `smoke_scoped_app_block.js` passed 114 assertions.
- Phone-tested a v09 Shorts-only promise using the local backend and Azure lab deployment `pc-lab-astra`: YouTube Home stayed available; the Shorts player showed the structured-surface BLOCK overlay; a regular 2:52 YouTube player stayed unobstructed. This does not prove a 40-minute lecture, Chrome, or other social apps.
- The test session was ended through the app. Session storage is empty, all seven app rules are restored to `AI_DECIDE`, and Accessibility is bound with a fresh heartbeat.
- Rebuilt/reinstalled the Azure-configured debug APK; installed SHA-256 matched the build. Local backend stopped and `adb reverse tcp:8787` removed.
- Azure `/health` confirms compiler deployment `pc-lab-astra` is still on `promise_compiler_v07`; classifier is `pc-lab-terra` / `v04`. The v09 change was **not** deployed to the hosted backend.
- At that time the working tree was **62 modified + 42 untracked** (superseded above). Local Azure/phone captures and screenshots are retained but ignored by Git.
- A local Logcat artifact (`.tmp-phone-verify-20260925/18_D_logcat.txt`) contains password-like strings. Values were not printed; keep the file local and do not share or stage it.

## Built So Far

- Android Kotlin app exists and runs on the phone.
- Accessibility-based foreground detection exists.
- Overlay-based warn/block experience exists.
- Local policy models and `PolicyEngine` exist.
- Session and permanent guardrail concepts exist.
- Backend classifier exists.
- Azure OpenAI path exists for AI classification experiments.
- AI lab has prompt/eval infrastructure for classifier and promise compiler research.
- Home / Advanced **Last decision** explains the latest ALLOW / WARN / BLOCK / LOCK without Logcat. Engineering detail + sanitized copy report. Display only; PolicyEngine is still law.

## Active Risk

The most important risk is reliability on the phone:

- overlay flicker
- false blocks
- false allows
- slow UI
- noisy accessibility text
- Chrome/YouTube edge cases
- PiP/floating video escapes
- tamper by disabling Accessibility

Every task should improve measured reliability, clarity, or speed.

Offline **Phone Surface Trace Replay** (`docs/enforcement-trace-replay.md`) freezes real Accessibility dumps and asserts ALLOW/WARN/BLOCK/LOCK/WAIT without the phone. Add a failing trace before fixing the next phone bug. This does not replace overlay / bind / Xiaomi checks.

Home now has a **Protection Reliability Gate**: Protected only if Accessibility is actually bound (alive or fresh heartbeat) **and** there is an active commitment or an enabled life rule. “Installed” is not “protecting.”

Hosted Understand is live on App Service `phonecodex-backend` (HTTPS). POSTs require `STRLIX_BACKEND_APP_SECRET`. Compiler `pc-lab-astra`, classifier `pc-lab-terra`, vision OFF. PolicyEngine is still law.

## What Is Not Product-Ready

- Full natural-language Promise Compiler enforcement is not fully product-ready.
- Vision classification is future work.
- Full phone operator AI is long-term.
- Payments/accountability penalties are future work.
- Public launch is not ready.

## How To Verify Work

Prefer this loop:

```text
build -> install/run on phone -> start commitment -> test real app -> inspect logs -> record pass/fail
```

For Android work, phone verification still matters for overlay and Accessibility bind. Run `PhoneSurfaceTraceReplayTest` first so decision-law bugs fail on a laptop.

For AI work, evals matter more than one impressive example.

## Current Default Work Split

- Android/main app: reliability, UX clarity, speed, policy wiring.
- AI lab: promise understanding, classifier evals, ambiguity handling.
- Azure/backend: stable API, cost/latency tracking, model experiments.
- Product/strategy: keep the whole system aligned with the commitment OS vision.
