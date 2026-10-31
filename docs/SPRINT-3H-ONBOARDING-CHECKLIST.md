# PhoneCodex first-run setup checklist (merge-readiness)

Updated: 2026-10-31 ~16:10 IST · Debug / local only · Flags OFF

## Goal
Fresh install → permissions → Accessibility disclosure → privacy link → (optional) Firebase Anonymous when flag on → continue AI work via **existing** Pilot tip phone+browser path. **Do not invent a second phone stack.**

## Continuing-work path (existing)
| Piece | Where |
|---|---|
| Phone + browser tip | **g148phone** @100% phone-on (Pilot / CA tip) |
| Public health | `https://phone.strlix.tech` (Access + Bearer) |
| PhoneCodex local auth | Firebase project `phonecodex-strlix` Anonymous → local backend → Table `strlixauthstate` (hosted session **HOLD**) |

## Checklist (debug APK / AVD)

1. **Install** debug or phoneTest APK (`assembleDebug` / `assemblePhoneTest`). phoneTest has empty secret + `http://localhost:8787`.
2. **Mic / RECORD_AUDIO** — grant when promise voice input is used (`HomeScreen` requests on record).
3. **Notifications** — ProtectionHeartbeat channel; grant POST_NOTIFICATIONS on API 33+ if prompted.
4. **Accessibility** — in-app disclosure (`AccessibilityDisclosureDialog`) → Agree → Android Settings → enable Strlix Focus Protection. Consent versioned (`AccessibilityDisclosureConsentStore`).
5. **Privacy policy** — Advanced Controls → Open privacy policy **or** disclosure → Open privacy policy → `https://strlix.tech/privacy` (LIVE 200).
6. **Firebase Anonymous** — only when `STRLIX_USE_FIREBASE_ID_AUTH=true` (still **hard-false** all variants). Path proven by `FirebaseAnonymousIdTokenSmokeTest` + Admin verify; do **not** flip for this sprint.
7. **Backend pair** — local `node backend/start-phone-test.js` (paid routes disabled by default) + `adb reverse tcp:8787 tcp:8787` for emulator/device.
8. **Continue work** — handoff to Pilot tip **g148phone** / `phone.strlix.tech` for phone+browser agent control (not a new PhoneCodex cloud phone).

## Prep only (do not flip until CEO says)
```
# Staging recipe draft — PAID ROUTES DISABLED — Sol OFF
STRLIX_USE_FIREBASE_ID_AUTH=1          # Android BuildConfig / backend expect Firebase Bearer
STRLIX_USER_SESSION_AUTH=1             # hosted backend
STRLIX_FIREBASE_PROJECT_ID=phonecodex-strlix
# Azure Table AAD → ststrlixagentin / strlixauthstate (ay1181mnc)
STRLIX_PAID_ROUTES_DISABLED=1          # REQUIRED until Sol budget OK
# Do NOT set STRLIX_PHONE_TEST_PAID_CALLS=1 on hosted
```

## HOLDs
No Play upload · no auth flag flips · no kvm · no paid Sol · no desktop screenshots of Ankit’s PC
