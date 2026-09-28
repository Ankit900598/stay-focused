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
# Tests:
powershell -NoProfile -ExecutionPolicy Bypass -File tools\phone\Test-StrlixPhoneDiag.ps1
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

Never captured: screenshots, UI hierarchy dumps, logcat, app preferences, promise text, secrets, or names of other apps' Accessibility services. Raw command output stays in memory; only sanitized files are written. The serial is masked to its last 4 characters.

## Limitations

- The `dumpsys accessibility` format is not a public API. The parser matches the Bound list by service label (`PhoneCodex Focus Detection`); if the label or OEM format changes, the verdict becomes `unparsed` or `enabled_not_bound`, never a false `bound`. Re-check against a real capture after Android/MIUI updates.
- The accessibility fixtures are synthetic, modeled on one real 2026-09-27 capture; the crashed-list format and the exit-info layout come from AOSP dump code and the incident report, not from a captured crash on this phone.
- Exit records come from `dumpsys activity exit-info`, the shell view of `ApplicationExitInfo`; the app does not call `getHistoricalProcessExitReasons`.
- No heartbeat age: Strlix's lease lives in app preferences, which this tool deliberately does not read. Use Home "Run protection check" for that.
- Running `dumpsys` is read-only, but it is still ADB traffic; do not run it in a tight loop during a timing-sensitive test.
