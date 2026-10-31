# Azure Table real multi-process smoke — PASS

When: 2026-10-31 ~15:20 IST (Asia/Calcutta)

## Result
**PASS** — `node backend/smoke_table_real_azure.js` → `TABLE_REAL_AZURE_OK`

```
account=ststrlixagentin
table=strlixauthstate
auth=AzureAD_DefaultAzureCredential
race=1of2
capacity=2of4
run=realmvw7ulj4
EXIT:0
```

## What ran
1. **Table create** (existing account only): `az storage table create --account-name ststrlixagentin -n strlixauthstate --auth-mode login` → created in `rg-strlix` / centralindia / Standard_LRS. **No new storage account. No ACA scale-out. No new compute.**
2. **Auth**: account has `allowSharedKeyAccess=false`. Granted signed-in user **Storage Table Data Contributor**. Smoke uses `DefaultAzureCredential` against `https://ststrlixagentin.table.core.windows.net` (`tableAuthState.js` accepts HTTPS table endpoint).
3. **2-process local smoke** (`smoke_table_real_azure.js`):
   - Fail-closed on bad endpoint (`auth_state_unavailable`)
   - ETag contention: capacity=1 → exactly one process exit 0, other exit 3; third take denied
   - Exact capacity: capacity=2 with 4 concurrent workers → 2 ok / 2 no
   - Logs contain no AccountKey / connection string / Bearer JWT
4. Azurite regression: `smoke_table_auth_state.js` still `TABLE_AUTH_STATE_OK`.

## HOLDs (unchanged)
- Hosted `STRLIX_USER_SESSION_AUTH` OFF
- Client `STRLIX_USE_FIREBASE_ID_AUTH` OFF
- Tip/prod/#118 untouched; no paid Sol routes; `android-kvm-1` TERMINATED

## Cost note
Table-only on existing LRS account — incremental near zero (within Nov Azure $500 cap).

## Evidence
- Box: `/workspace/live/PHONECODEX-P0-AZURE-TABLE-REAL-SMOKE.md`
- Laptop: `C:\Users\HP\strlix\docs\azure-table-real-smoke-PASS-2026-10-31.md`
- Scripts: `backend/smoke_table_real_azure.js`, `backend/tableAuthState.js` (AAD endpoint support)
