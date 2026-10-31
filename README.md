# Strlix

Personal commitment OS for phones:

```text
natural-language promise -> structured policy -> PolicyEngine -> enforce -> log
```

AI is counsel. PolicyEngine is law.

## Read in this order

1. `PROJECT_CONTEXT.md` — what the product is
2. `CURRENT_STATUS.md` — short truth now (history in `docs/archive/`)
3. `NEXT_TASKS.md` — current P0/P1 only
4. `AGENTS.md` — rules for AI/coding work
5. `docs/README.md` — living docs map

Do not “clean” PolicyEngine / Accessibility / paid auth just to shorten comments.
Behavior and tests beat cosmetic refactors.

**Content rule:** do not delete product/design docs or working code for tidy’s sake.
History goes to `docs/archive/` (still in the repo). Temporary junk only (`.tmp*`, screenshots, logs) may be removed.

## Layout

| Path | What it is |
|---|---|
| `app/` | Android app (Kotlin) |
| `backend/` | Node API: classify, compile, speech, mail, auth |
| `evals/` | Promise-compiler prompts and eval sets |
| `tools/phone/` | Phone harness / fixtures |
| `infra/` | Infra helpers |
| `docs/` | Living contracts and plans |
| `docs/archive/` | Old investigation notes (not current plan) |

Local-only (never commit): `.env`, `local.properties`, `.android-cloud-phone-creds.txt`.

## Debug fast

| Problem | Start here |
|---|---|
| Product / promise behavior | `docs/CURRENT_STATE.md`, then `app/` policy + overlay code |
| Backend route / paid call / auth | `backend/server.js`, then `backend/sessionRouteAuth.js` / `backend/appAuth.js` |
| Backend regression | `cd backend` → `node smoke_<name>.js` |
| Azure / hosted config | `docs/azure-hosted-backend.md` |
| Auth release gate | `docs/release-api-auth-adr-2026-10-12.md` |
| Phone install / Play path | `docs/phone-test-play-distribution.md` |
| AI strategy / evals | `docs/cto-strategy-phonecodex-ai.md`, `evals/` |

## Run locally (dev)

```bash
# Backend (default off paid routes when using phone-test launcher)
cd backend
npm install
node start-phone-test.js
```

Android: open the repo in Android Studio / Gradle (`app` module). Do not sideload past Play Protect unless you explicitly authorize that session.
