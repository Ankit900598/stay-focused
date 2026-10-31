#!/usr/bin/env bash
# PREP ONLY. Prints the two-instance Azure Table session-auth checklist.
# This script NEVER creates Azure resources and never reads/prints secret values.
# Later human-only commands (DO NOT run without Ankit spend OK) — examples, commented out:
#   az storage table create --name strlixauth --connection-string "<from portal; never paste in repo>"
#   az appservice plan update --name <plan> --resource-group <rg> --number-of-workers 2
set -u
if [ "${1:-}" = "--create" ] || [ "${1:-}" = "--apply" ]; then
  echo "REFUSED: this script never creates Azure resources. Owner must act after spend OK." >&2
  exit 0
fi
cat <<'TXT'
== PhoneCodex Azure Table multi-instance PREP (HOLD: no resource creation until Ankit spend OK) ==
Target: phonecodex-backend (westus3). Session auth default OFF. Staging NO-GO.
NOTE: Azurite local multi-process != Azure multi-instance proof.

Env var NAMES (values never printed):
  STRLIX_USER_SESSION_AUTH
  STRLIX_AUTH_TABLE_CONNECTION
  STRLIX_AUTH_TABLE_NAME (default strlixauth)
  STRLIX_FIREBASE_PROJECT_ID (OWNER TBD)
  WEBSITE_INSTANCE_ID is NOT an instance count.

Pre-flight (local):
  [ ] node backend/smoke_table_auth_state.js
  [ ] node backend/smoke_user_session_auth.js
  [ ] node backend/smoke_user_session_routes.js
  [ ] node backend/smoke_bearer_contract.js

After spend OK (owner-approved):
  [ ] 1 Ankit approves spend + storage choice
  [ ] 2 Create/use table in existing westus3 stack
  [ ] 3 Wire STRLIX_AUTH_TABLE_CONNECTION to app settings (Key Vault ref)
  [ ] 4 Scale to TWO instances (temporary)
  [ ] 5 Run concurrent paid-route smoke across both instances
  [ ] 6 Check: consistent per-route/per-hour limits, 412 retry, auth_state_unavailable denies, no secrets in logs
  [ ] 7 Capture sanitized results; scale back; flag OFF
  [ ] 8 Only then consider staging session auth
See docs/azure-table-multi-instance-prep-2026-10-31.md
TXT
for v in STRLIX_USER_SESSION_AUTH STRLIX_AUTH_TABLE_CONNECTION STRLIX_AUTH_TABLE_NAME; do
  if [ -n "${!v:-}" ]; then echo "  local env $v: set (value hidden)"; else echo "  local env $v: unset"; fi
done
exit 0
