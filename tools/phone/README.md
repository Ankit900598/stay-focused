# Strlix phone diagnostics capture

`Capture-StrlixDiag.ps1` takes a read-only, privacy-safe snapshot of Strlix's process and Accessibility state. Run it before and after every phone check so an Accessibility kill (e.g. MIUI `OneKeyClean` / `SwipeUpClean`, see `docs/accessibility-kill-recovery-2026-09-28.md`) is already on disk when it happens.

It is a capture tool only. It does not fix or prevent the Xiaomi kill.

## Use

```powershell
# One connected device:
.\tools\phone\Capture-StrlixDiag.ps1 -Label before
# Several devices:
.\tools\phone\Capture-StrlixDiag.ps1 -Serial <adb-serial> -Label after
# Offline, no phone (synthetic fixture):
.\tools\phone\Capture-StrlixDiag.ps1 -FixtureDir tools\phone\fixtures\device-crashed -Label fixture
# After a user-confirmed phone test, capture its normalized decision trace:
.\tools\phone\Capture-StrlixDecisionTrace.ps1 -Label after-test
# Tests:
powershell -NoProfile -ExecutionPolicy Bypass -File tools\phone\Test-StrlixPhoneDiag.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools\phone\Test-StrlixDecisionTrace.ps1
```

Output: `.tmp-phone-verify-diag/<yyyyMMdd-HHmmss>_<label>/` (git-ignored by `.tmp-phone-verify-*/`). The script refuses any `-OutRoot` that is not a git-ignored `.tmp-phone-*` folder at the repo root (exit 3). Zero devices, only unauthorized/offline devices, several devices without `-Serial`, or an unknown `-Serial` exit 2 before any device command runs.

| File | Contents |
|---|---|
| `summary.json` | host start/end time (local + UTC), device clock and skew, uptime, model, Android/MIUI/HyperOS version, Strlix versionName/versionCode, PID (null when dead), Accessibility verdict and flags, parsed exit records |
| `accessibility_strlix.txt` | Strlix-only lines: enabled / binding / bound / crashed, plus Strlix's own bound entry |
| `exit_info.txt` | `dumpsys activity exit-info com.phonecodex.app`, other package names replaced with `<other-package>` |
| `package_version.txt` | versionCode, versionName, firstInstallTime, lastUpdateTime |

`a11y_verdict` is one of `bound`, `crashed`, `binding`, `enabled_not_bound`, `bound_not_enabled`, `not_enabled`, or `unparsed`. Only `bound` together with `strlix_process_alive=true` means the service is actually connected.

## What it runs

Only the allowlist in `StrlixPhoneDiag.psm1` (`date`, `cat /proc/uptime`, a few `getprop` keys, `pidof`, `dumpsys package|activity exit-info|accessibility`, `settings get secure ...`). The tests fail if any entry is not a read-only query or if the script contains a state-changing or screen-capturing command.

`Capture-StrlixDiag.ps1` still never captures screenshots, UI hierarchy dumps, logcat, app preferences, promise text, secrets, or names of other apps' Accessibility services. Raw command output stays in memory; only sanitized files are written. The serial is masked to its last 4 characters.

## Emulator-only Android instrumentation

When a Xiaomi or other physical device is connected, do **not** run Gradle's `connectedDebugAndroidTest`: it may install the test APK on every connected device, even when an attempted selector is supplied. Use the guarded wrapper instead:

```powershell
.\tools\phone\Invoke-StrlixEmulatorTests.ps1 -Serial emulator-5556
```

The wrapper accepts only `emulator-<port>`, verifies the selected target is a booted emulator before building or installing, and pins each ADB install/run to that serial. It installs the credential-free `phoneTest` app variant plus the instrumentation APK—not the normal Debug app, which can embed a local backend secret. It refuses the Xiaomi serial before any device mutation. This verifies Android tests on the emulator only; it does not count as physical-device enforcement verification.

## Optional decision trace

`Capture-StrlixDecisionTrace.ps1` is a separate, opt-in tool for synchronizing enforcement, classifier, and overlay events with a phone test. It reads at most 1,200 recent records by default, restricted on-device to the live Strlix PID and the three Strlix log tags. It does not clear the Android log buffer or change phone state. Its offline fixture mode makes no ADB calls.

The tool parses raw logcat in memory and writes only `trace.jsonl` plus a small `summary.json`. It retains timestamps, validated package IDs, policy/surface enums, decision codes, quota counters, classifier decision/confidence, and overlay transition/timing fields. It drops free-form reasons, evidence, scope clauses, titles, page identities, URLs, raw log lines, and the process ID. It omits unrecognized/malformed lines and other process IDs. Package IDs can still reveal which apps were active, so keep the ignored output private and redact before sharing.

The original snapshot tool's no-logcat guarantee remains tested independently. The decision trace is not automatic telemetry: run it only for a specific, user-approved phone test, immediately after the test, alongside before/after `Capture-StrlixDiag.ps1` snapshots.

## Limitations

- The `dumpsys accessibility` format is not a public API. The parser recognizes the current `Strlix Focus Protection` label plus older app labels; a new label or OEM format becomes `unparsed` or `enabled_not_bound`, never a false `bound`. Re-check against a real device after Android/MIUI updates.
- The accessibility fixtures are synthetic, modeled on one real 2026-09-27 capture; the crashed-list format and the exit-info layout come from AOSP dump code and the incident report, not from a captured crash on this phone.
- Exit records come from `dumpsys activity exit-info`, the shell view of `ApplicationExitInfo`; the app does not call `getHistoricalProcessExitReasons`.
- No heartbeat age: Strlix's lease lives in app preferences, which this tool deliberately does not read. Use Home "Run protection check" for that.
- Running `dumpsys` is read-only, but it is still ADB traffic; do not run it in a tight loop during a timing-sensitive test.
