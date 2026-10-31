# Azure Table multi-instance session-auth smoke — PREP ONLY (2026-10-31)

Status: **PREP. Nothing in this doc has been executed against Azure.** Staging session auth remains **NO-GO** until the real two-instance test below passes. `STRLIX_USER_SESSION_AUTH` default stays OFF on `phonecodex-backend` (westus3).

## Purpose
Prove that the ETag-based quota state in `backend/tableAuthState.js` stays correct when **two real App Service instances** of `phonecodex-backend` share one Azure Table — not just Azurite.

> **Azurite local multi-process ≠ Azure multi-instance proof.** `backend/smoke_table_auth_state.js` only proves the logic against the local emulator. It has not been run against Azure cloud storage.

## HARD HOLD
Do **not** create a Storage account, table, or App Service scale-out (or second instance/slot), and do not change any app setting or flag, until **Ankit explicitly approves the spend**. Agents must not run any `az ... create`, `az webapp config appsettings set`, or `az appservice plan update --number-of-workers` command.

## Env vars (names only — never commit or log values)
| Name | Notes |
|---|---|
| `STRLIX_USER_SESSION_AUTH` | Session-auth flag. Default OFF. Do not flip on hosted until this test passes and owner approves. |
| `STRLIX_AUTH_TABLE_CONNECTION` | Azure Table connection string. Secret — set only via App Service settings / Key Vault reference by owner. |
| `STRLIX_AUTH_TABLE_NAME` | Optional; defaults to `strlixauth`. |
| `STRLIX_FIREBASE_PROJECT_ID` | Placeholder `<OWNER TBD: Firebase project id>` — no project exists/confirmed yet. |
| `STRLIX_USER_ROUTE_CAPACITY` / `STRLIX_USER_ROUTE_PER_HOUR` | Optional; lower them for the test so limits trip quickly. |

`WEBSITE_INSTANCE_ID` names the current process only; it is **not** an instance count and must not be used as evidence of multiple instances (see `sessionRouteAuth.js`).

## Pre-flight (local, free)
1. `node backend/smoke_table_auth_state.js` (Azurite multi-process) — must pass.
2. `node backend/smoke_user_session_auth.js`, `node backend/smoke_user_session_routes.js`, `node backend/smoke_bearer_contract.js` — must pass.
3. `bash backend/scripts/prep_azure_table_multi_instance_checklist.sh` — review printed checklist.

## Pass / fail criteria
PASS only if all hold:
- Concurrent paid-route attempts are sent to **both** instances (verify distinct instance identity per response/log, e.g. ARRAffinity cookie or sanitized instance id — never treat a single id as a count).
- One test UID hits the per-route capacity and per-hour limits **at the same total count** regardless of which instance served the calls (no over-admission).
- Lost-update contention (HTTP 409/412 on ETag) is retried and resolved; no double-counting or lost decrements.
- When table state is unreachable/misconfigured, paid calls are **denied** with `auth_state_unavailable` (fail closed).
- Logs contain no connection string, account key, ID token, bearer token, or raw UID beyond what existing log-privacy rules allow.
- Paid provider calls kept bounded (use `STRLIX_PAID_ROUTES_DISABLED`/validation-only payloads where possible).

FAIL on any over-admission, unhandled 412, fail-open path, or secret in logs.

## Steps AFTER Ankit spend OK (owner-approved, numbered)
1. Ankit confirms spend + which storage to use (existing westus3 resource group/storage, or a new one he names). Record approval.
2. Owner (or agent with explicit per-step approval) creates/uses the table `strlixauth` in the existing westus3 stack.
3. Set `STRLIX_AUTH_TABLE_CONNECTION` (and optionally `STRLIX_AUTH_TABLE_NAME`) on `phonecodex-backend` via App Service settings / Key Vault reference — never in repo or chat.
4. Get two instances: scale out the plan to 2 workers (or a second identical instance) — owner-approved, temporary.
5. Enable session auth only for the test window, with a test Firebase UID (Firebase project id OWNER TBD).
6. Run the concurrent smoke from a laptop, hitting both instances; capture sanitized results (counts, status codes, instance labels — no tokens).
7. Scale back to 1 worker; revert flag to OFF if not proceeding.
8. Write a results note in `docs/`; only then consider staging session auth (separate owner decision).

## Companion
The script `backend/scripts/prep_azure_table_multi_instance_checklist.sh` prints this checklist and env names; it refuses to create resources.
