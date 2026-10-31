# PhoneCodex P0#1 — local auth chain GREEN (2026-10-31 ~16:00 IST)

## Proven today
| Step | Result |
|---|---|
| Firebase project `phonecodex-strlix` + Anonymous Auth + Android app + debug SHA | GREEN |
| Gradle wire BoM 34.19.0; gates hard false | GREEN |
| AVD Anonymous ID token smoke | PASS |
| Firebase Admin verifyIdToken (real JWT) | PASS |
| Azure Table `strlixauthstate` on `ststrlixagentin` + 2-process real smoke | PASS |
| Firebase Anon JWT → local backend → real Table | PASS |
| Android debug Bearer E2E (local.properties opt-in; stripped after) | PASS |

## Still HOLD (CEO)
- Release/phoneTest `STRLIX_USE_FIREBASE_ID_AUTH` flip
- Hosted `STRLIX_USER_SESSION_AUTH` on ACA/tip/prod
- Paid Sol routes (over $300 key-cut)
- `android-kvm-1` start (idle-stop)
