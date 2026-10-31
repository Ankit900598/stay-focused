# Sprint 3H merge-readiness notes (Droid slice)

Updated: 2026-10-31 ~16:28 IST

## Already PASS (pre-sprint / earlier today)
- Backend smokes 23/25 + `assemblePhoneTest`
- Advanced Controls contract 7/7
- Status + privacy link unit
- Firebase anon smoke, Admin verify, Azure Table, Firebase→table, Android debug Bearer E2E
- Privacy page LIVE 200 (Starlink #1)

## This sprint DONE / PARTIAL
| Item | Status | Path |
|---|---|---|
| A Onboarding E2E | **PARTIAL→core PASS** | `/workspace/live/SPRINT-3H-ONBOARDING-E2E.md` + laptop `docs/` |
| B Multi-chat scaffold | **PASS** (unit; toggle OFF default) | `domain/multichat/*` + `SPRINT-3H-MULTICHAT.md` |
| C Merge notes + checklist | **PASS** | this file + ONBOARDING-CHECKLIST |
| Auth flip recipe | **PREP only** | `SPRINT-3H-AUTH-FLIP-RECIPE.md` |

### Onboarding gaps (not blockers for tip path)
- ADB cannot flip Accessibility on (ACCESS_RESTRICTED_SETTINGS) — user toggles after disclosure Agree
- Heartbeat notification channel only after live session
- Firebase in-app gated OFF (proven separately)

### Wire
- `POST_NOTIFICATIONS` added to AndroidManifest (box + laptop)

## Continuity
Pilot tip **g148phone** / `https://phone.strlix.tech` — no second phone stack.

## HOLDs
Auth flags OFF · kvm STOPPED · no Play · Sol OFF · no PC desktop screenshots
