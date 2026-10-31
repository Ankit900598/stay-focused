# SPRINT-3H Onboarding E2E (AVD adb-only)

**Result: PARTIAL → core path PASS** (a11y *enable* blocked by Android restricted-settings; disclosure+privacy+perms PASS)

Updated: 2026-10-31 ~16:25 IST · AVD `codex-strlix-ui` emulator-5554 headless · **no PC desktop screenshots**

## Device
| Item | Value |
|---|---|
| Package | `com.phonecodex.app` |
| APK | debug `assembleDebug` after POST_NOTIFICATIONS wire |
| AVD | codex-strlix-ui (Android 15 / sdk_gphone64_x86_64) |

## Checks

| Step | Result | Evidence |
|---|---|---|
| Install debug APK | PASS | `adb install -r` Success |
| RECORD_AUDIO grant | PASS | `dumpsys package` granted=true |
| POST_NOTIFICATIONS grant | PASS | Added to manifest (was missing); granted=true after rebuild |
| Launch MainActivity | PASS | Home shows **Needs setup** / **Turn on Accessibility** / Speak promise |
| Accessibility disclosure | PASS | Tap CTA → dialog **Before enabling Accessibility** + Agree / Not now / Open privacy policy |
| Privacy from disclosure | PASS | Tap **Open privacy policy** → Chrome VIEW `https://strlix.tech/privacy` |
| Privacy HTTP | PASS | Host HEAD/GET **200** (Starlink #1 confirmed) |
| ACTION_ACCESSIBILITY_SETTINGS | PASS | Intent opens Settings$AccessibilitySettingsActivity |
| Enable a11y via adb | **BLOCKED** | `settings put secure enabled_accessibility_services` cleared; `ACCESS_RESTRICTED_SETTINGS` reject (API 33+). User must toggle in Settings after Agree. Service **registered** in package. |
| Firebase anon in-app path | N/A (flag OFF) | `STRLIX_USE_FIREBASE_ID_AUTH` hard-false all variants. Path proven earlier by `FirebaseAnonymousIdTokenSmokeTest` + Admin verify + Android debug Bearer E2E. **Do not flip.** |
| Notification channel | DEFERRED | `phonecodex_protection_heartbeat` created when ProtectionHeartbeatService starts (needs active session + a11y). Perm grant ready. |

## Smallest wire this sprint
- `AndroidManifest.xml`: added `POST_NOTIFICATIONS` (box + laptop)

## Continuity (existing tip — no second phone stack)
Hand continuing AI work to Pilot tip **g148phone** / `https://phone.strlix.tech`.

## Related docs
- `/workspace/live/SPRINT-3H-ONBOARDING-CHECKLIST.md`
- `/workspace/live/SPRINT-3H-AUTH-FLIP-RECIPE.md` (prep only)
- `/workspace/live/SPRINT-3H-MERGE-NOTES.md`
- `/workspace/live/SPRINT-3H-MULTICHAT.md`

## HOLDs honored
No auth flag flip · no kvm · no Play · no Sol · no desktop screenshots
