# Firebase Admin verify — PASS (real Anonymous JWT)

When: 2026-10-31 ~15:11 IST (Asia/Calcutta)

## Result
**PASS** — `backend/smoke_firebase_admin_real_verify.js` → `FIREBASE_ADMIN_REAL_VERIFY_OK`

## What ran
1. **Admin SA**: reused Firebase default `firebase-adminsdk-fbsvc@phonecodex-strlix.iam.gserviceaccount.com`; created one JSON key at `/workspace/secrets/phonecodex-strlix-firebase-adminsdk.json` (mode 600, gitignored). Never printed.
2. **Anonymous ID token**: laptop AVD `codex-strlix-ui` had **network unreachable** after idle-stop cycle, so host minted via Identity Toolkit `accounts:signUp` using the same project's Web API key from gitignored `app/google-services.json` (same Auth backend as the proven AVD smoke earlier today). Claims only: `aud=phonecodex-strlix`, `iss=https://securetoken.google.com/phonecodex-strlix`, `sign_in_provider=anonymous`, `uid_len=28`. Token file shredded after verify.
3. **Admin verify**: `verifyWithFirebaseAdmin` + modular `getAuth().verifyIdToken(..., true)` — asserted aud/project, anonymous provider, UID; rejected tampered/empty/wrong-shape; rejected wrong-project audience (`user_auth_wrong_audience`). Deleted anon user after success.

## HOLDs (unchanged)
- `STRLIX_USE_FIREBASE_ID_AUTH` OFF
- `STRLIX_USER_SESSION_AUTH` OFF
- No Azure Table create, no paid/Blaze, no sideload
- `android-kvm-1` TERMINATED (idle-stop)

## Evidence paths
- Box: `/workspace/live/PHONECODEX-P0-FIREBASE-ADMIN-VERIFY.md` (this file)
- Laptop: `C:\Users\HP\strlix\docs\firebase-admin-verify-PASS-2026-10-31.md`
- Script: `backend/smoke_firebase_admin_real_verify.js`
- Capture test (AVD; blocked by emu net): `FirebaseAnonymousIdTokenCaptureTest.kt`
- SA path (secret): `/workspace/secrets/phonecodex-strlix-firebase-adminsdk.json`
