# Current Status

Read after `PROJECT_CONTEXT.md`. Full dated ledger: `docs/archive/CURRENT_STATUS-full-2026-10-12.md`.
Updated: **2026-10-31** (Asia/Calcutta).

## Now

| Area | State |
|---|---|
| Product | Personal commitment OS: promise → confirmed policy → PolicyEngine → overlay → log |
| Package | `com.phonecodex.app` (do not rename casually) |
| Phone / kvm | GCP `android-kvm-1` **TERMINATED** (idle-stop). No paid host unless Starlink starts for real use |
| P0#1 Auth | **Local GREEN**: Firebase Anonymous (`phonecodex-strlix`) → Admin JWT verify → Azure Table `strlixauthstate` on `ststrlixagentin` (AAD smoke) → Android debug Bearer E2E. Flags `STRLIX_USE_FIREBASE_ID_AUTH` / `STRLIX_USER_SESSION_AUTH` still **hard-false**. **Hosted/release HOLD** |
| P0#2 Play | A11y/IAS runbook refreshed; **no Play upload**. Ankit-only owner/IAS TODOs stacked (interview day — do not ping until after Oct 31 evening) |
| P0#3 Privacy | Draft + Data Safety refreshed; in-app Privacy policy link opens configured HTTPS placeholder (`https://strlix.tech/privacy`). Ankit-only legal TODOs stacked |
| P0#4 Advanced Controls | **DONE** 2026-10-31 — binder prefers Advanced lock/cooldown/non-explicit duration; unit tests 7/7 |
| Azure Table | `strlixauthstate` **exists**; local multi-process + Firebase→table smokes PASS. Hosted session auth **still off** |
| Hosted backend | Shared-key default; session auth flag off |
| Spend | Nov caps: Azure $500 / Sol $300 / AWS $100 / GCP $50. No new cloud spend without OK |

## What is true in code (local, not phone-proven)

- Confirm-before-start; PolicyEngine is law; AI is counsel.
- Advanced Controls → binder → Accessibility for lock / cooldown / duration (when duration not explicit in promise).
- Accessibility in-app consent versioned; Settings-only bind without consent self-disables.
- Privacy policy row in Advanced Controls + link on Accessibility disclosure (opens URL; no invented legal body in-app).
- `phoneTest` / release auth gates remain hard-false.

## Do not claim

- Production-ready user auth or hosted session quotas live.
- Play approval or Accessibility-tool eligibility.
- Phone enforcement for the latest APK on a real device matrix.
- Final published privacy policy page (URL is a placeholder until Ankit publishes).

## Repo map

See root `README.md`. Living docs: `docs/README.md`. Archive: `docs/archive/`.
