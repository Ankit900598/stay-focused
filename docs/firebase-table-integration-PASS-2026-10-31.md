# Firebase Anonymous JWT → local backend → real Azure Table — PASS

When: 2026-10-31 ~15:35 IST (Asia/Calcutta)

## Result
**PASS** — `node backend/smoke_firebase_table_integration.js` → `FIREBASE_TABLE_INTEGRATION_OK`

```
project=phonecodex-strlix
table=strlixauthstate
account_endpoint_host=ststrlixagentin.table.core.windows.net
auth=AzureAD+FirebaseAdmin
tokens_minted=4
capacity_exact=1then2of4
paid_kill_switch=503
fail_closed=1
uids_deleted=4
```

## What ran (local only)
1. **Minted** real Firebase Anonymous ID tokens for `phonecodex-strlix` via Identity Toolkit `accounts:signUp` (Web API key from gitignored `app/google-services.json`). Claims: aud/iss project match, `sign_in_provider=anonymous`. Token files shredded; Auth users deleted after.
2. **Local Express** with `mountPaidSessionAuth` (same path as `server.js` when `STRLIX_USER_SESSION_AUTH=1`):
   - `STRLIX_FIREBASE_PROJECT_ID=phonecodex-strlix` + Admin SA (`GOOGLE_APPLICATION_CREDENTIALS`, gitignored)
   - Real table AAD: `STRLIX_AUTH_TABLE_CONNECTION=https://ststrlixagentin.table.core.windows.net` + `STRLIX_AUTH_TABLE_NAME=strlixauthstate`
   - Small caps: capacity/perHour 1 then concurrent capacity 2
   - `STRLIX_PAID_ROUTES_DISABLED=1` kill-switch asserted separately → `503 paid_routes_disabled`
3. **Asserts**
   - App-secret alone / missing Bearer → `401 user_auth_required` (no shared-secret fallback)
   - Tampered / wrong-shape JWT → `401 user_auth_invalid`
   - Valid Bearer → `200` until capacity; next → `429 rate_limited` (exact)
   - Concurrent capacity=2 with 4 parallel → exactly `2`×200 + `2`×429
   - Bad table connection → fail-closed (ready refuses)
   - Logs: no AccountKey / private key / raw JWT
4. **Optional Android debug hit**: skipped — `android-kvm-1` TERMINATED; prior AVD network unreachable. release/phoneTest flags remain hard false.

## HOLDs (unchanged)
- No ACA/tip/prod env changes; no hosted `STRLIX_USER_SESSION_AUTH=1`
- No release/phoneTest `STRLIX_USE_FIREBASE_ID_AUTH` flip
- No paid Sol routes; no new Azure resources; `android-kvm-1` TERMINATED

## Evidence
- Box: `/workspace/live/PHONECODEX-P0-FIREBASE-TABLE-INTEGRATION.md`
- Laptop: `C:\Users\HP\strlix\docs\firebase-table-integration-PASS-2026-10-31.md`
- Script: `backend/smoke_firebase_table_integration.js`
