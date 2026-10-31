# Next Tasks

History dump: `docs/archive/NEXT_TASKS-full-2026-10-12.md`. This file is **current work only**.

## P0 — do these first

1. **Release API auth — local GREEN / hosted HOLD**  
   Local: Firebase Anonymous → Admin verify → Azure Table AAD smoke → Android debug Bearer E2E PASS (flags still hard-false). Hosted / staging: **HOLD** — do not flip `STRLIX_USE_FIREBASE_ID_AUTH` / `STRLIX_USER_SESSION_AUTH` until spend OK + Play path. ADR: `docs/release-api-auth-adr-2026-10-12.md`.

2. **Play Accessibility / install gate**  
   Xiaomi has no Strlix package. Do not sideload or bypass Play Protect. Need Play Console owner decision, Internal App Sharing / internal test, then real-device matrix. Policy: live classifier BLOCK under confirmed `AI_DECIDE` vs Google Accessibility automation rules is unresolved. Ledger: `docs/phone-observation-errors-2026-10-11.md`. Runbook: `docs/phone-test-play-distribution.md`.

3. **Privacy / Data Safety before any Play upload**  
   Draft + Play A11y/IAS runbook landed; **in-app Privacy policy link** opens `https://strlix.tech/privacy` placeholder (Settings Advanced + Accessibility disclosure). Ankit-only Console/legal TODOs stacked (interview day — do not ping until after Oct 31 evening). No Play upload until owner completes those.

4. **Advanced Controls contract — DONE 2026-10-31**  
   Precedence: promise explicit > Advanced user override > inferred defaults. Binder wires lock / cooldown / non-explicit duration; recovery policy display-only. Unit tests: `ConfirmedPromiseBinderAdvancedControlsTest`. Contract: `docs/advanced-controls-contract-2026-10-31.md`.

## P1 — after P0

- Phone-verify duration / surface / classifier-authority fixes (unit-tested only so far).
- Mail assistant: OAuth verification + restricted-scope assessment before enabling hosted `STRLIX_MAIL_ASSIST_ENABLED`.
- Social messaging-only scopes: design evidence-aware enforcement; keep Start closed until then.
- Study-only structured rules: Start stays closed until Android can enforce them honestly.

## Standing rules

- AI is counsel. PolicyEngine is law. Confirm before enforce.
- No Azure deploy / paid inference / `.env` edit without explicit yes.
- Keep the browser project out of this repo.
- One passing local suite ≠ phone proof.

## Product horizon (do not forget)

Reliable promise → confirm → enforce → log on a real phone first. Then insight UI, voice, safe connectors. Play eligibility is a hard gate, not a paperwork afterthought.
