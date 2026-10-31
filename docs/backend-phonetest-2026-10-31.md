# PHONECODEX P0 — Backend smoke suite + assemblePhoneTest

When: 2026-10-31 ~16:00 IST
Owner: Droid (CEO GO)
Result: **PASS** (phoneTest + offline smokes) with **2 known laptop-AAD gaps** on real Table scripts

## 1) Backend `smoke_*.js`

- **Excluded (not an exit smoke):** `smoke_android_debug_e2e_server.js` — long-running local E2E helper server; hangs if treated as a smoke.
- **Paid / Sol:** parent env cleared `AZURE_OPENAI_*` / `OPENAI_*` / Sol keys; `STRLIX_PHONE_TEST_PAID_CALLS` unset. `smoke_api_boundary` re-run **without** global `STRLIX_PAID_ROUTES_DISABLED=1` (that kill-switch made it expect 400 but get 503); still **no Sol keys** so no paid provider calls.
- **Counts (exit-based scripts):** **23 PASS / 2 FAIL / 25 run** (+1 excluded server).

### PASS (23)
api_boundary (re-run), app_auth, app_dossier, app_surface_choices, bearer_contract, clarification_choice_identity, classifier_confidence, classifier_log_privacy, **firebase_admin_real_verify** (re-mint + shred token), firebase_admin_session, mail_assist, model_routing, phone_test_launcher, promise_compiler_normalize, promise_compiler_v08, scope_contract_v1, scope_semantics_v1, scoped_app_block, table_auth_state (Azurite), transcribe_promise, user_session_auth, user_session_routes, v09_clarification_scope.

### FAIL (2) — laptop Strlix Table AAD / shared-state (known; morning box PASS remains)
| Script | Why |
|---|---|
| `smoke_table_real_azure.js` | `race codes=[1,1]` — laptop Azure CLI default sub cannot complete Strlix `ststrlixagentin` AAD race (ay1181mnc-owned). Earlier today PASS from box with correct identity. |
| `smoke_firebase_table_integration.js` | `Shared auth state is unavailable` — same Table access path on this laptop session. |

Log: `C:\Users\HP\strlix\docs\backend-smoke-run-2026-10-31.log`

## 2) `:app:assemblePhoneTest`

- **BUILD SUCCESSFUL**
- APK: `C:\Users\HP\strlix\app\build\outputs\apk\phoneTest\app-phoneTest.apk`
- SHA-256: `F206067C448C30AB91CB491FC57EE5F0DA501DF473EF3B5502914EEEEA4A1839`
- Size: 22580396 bytes
- Credential-free BuildConfig: `BACKEND_APP_SECRET=""`, `BACKEND_BASE_URL="http://localhost:8787"`, `PRIVACY_POLICY_URL="https://strlix.tech/privacy"`, Firebase auth gate hard-false

## HOLD unchanged

No Play upload · no auth flag flips · no hosted session auth · no paid Sol · no kvm · no Ankit legal ping · no PC desktop screenshots

## Evidence

- Box: `/workspace/live/PHONECODEX-P0-BACKEND-PHONETEST.md`
- Laptop: `C:\Users\HP\strlix\docs\backend-phonetest-2026-10-31.md`
