# Android debug Firebase Bearer → local backend E2E — PASS

When: 2026-10-31 ~15:58 IST (Asia/Calcutta)

## Result
**PASS** — `FirebaseDebugBearerBackendE2ETest` on AVD `codex-strlix-ui` / emulator-5554  
`connectedDebugAndroidTest` → **BUILD SUCCESSFUL** (1/1, 0 failed)

## What ran
1. **AVD network**: restored/confirmed GREEN (icmp 8.8.8.8 + google.com). **Did not start `android-kvm-1`.**
2. **Debug opt-in**: `local.properties` `strlix.useFirebaseIdAuth=true` → BuildConfig debug `STRLIX_USE_FIREBASE_ID_AUTH=true`. **release/phoneTest remain hard false.** After PASS, opt-in stripped back to `false` (re-enable for local debug only).
3. **Local backend** on laptop `127.0.0.1:8787` (`smoke_android_debug_e2e_server.js`): real Firebase Admin (`phonecodex-strlix` SA) + session auth; **in-memory** quota store (laptop Azure CLI is `ay186mnc`, not Strlix `ay1181mnc` — no Strlix table RBAC; real Azure Table already `FIREBASE_TABLE_INTEGRATION_OK` on box). Stub `/classify` (no Sol/OpenAI). `adb reverse tcp:8787 tcp:8787`.
4. **Debug cleartext**: `app/src/debug` network security allows loopback only (release unchanged).
5. **Asserts**: Anonymous ID token → Bearer → 200 within capacity 2 → 429; missing Bearer 401; app-secret alone 401 (no shared-secret fallback); failed token provider sends no secret headers.

## HOLDs
- No kvm start; no ACA/tip/prod; no hosted session auth; no release/phoneTest flag flip; no paid Sol; no new Azure resources

## Evidence
- Box: `/workspace/live/PHONECODEX-P0-ANDROID-DEBUG-E2E.md`
- Laptop: `C:\Users\HP\strlix\docs\android-debug-firebase-e2e-PASS-2026-10-31.md`
- XML: `app/build/outputs/androidTest-results/connected/debug/TEST-codex-strlix-ui(AVD) - 15-_app-.xml`
- Test: `FirebaseDebugBearerBackendE2ETest.kt`
