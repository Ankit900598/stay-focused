# Next Tasks

Use this file to choose the next small task for a fresh chat.

## Highest Priority

### Immediate Release Gate — 2026-09-27

The latest Shorts-only typed-scope case passed on the phone against the local v09 backend; it has **not** been verified against the hosted Azure deployment. Do not claim general or production-ready support from that one YouTube test.

Do these in order before widening the tester release:

1. Review and partition the existing dirty tree into coherent, tested changes. Do not stage the whole tree.
2. The hosted `/health` endpoint already identifies compiler deployment/version: it currently reports `pc-lab-astra` / `promise_compiler_v07` and classifier `pc-lab-terra` / `v04`. Do not mistake a local v09 phone test for hosted v09 behavior.
3. Deploy the reviewed backend change to an existing staging target, if available; do not create another Azure service just for this.
4. Run the same typed-scope phone flow through hosted Azure: confirmation copy, YouTube Home ALLOW, Shorts BLOCK, regular long-player ALLOW, Chrome, and safe/emergency paths.
5. Promote only after logs prove the hosted revision/version and the phone behavior matches the preview. Preserve local v09 evidence and rollback state.

Current proof (2026-09-27 19:25 IST): 884 Android unit tests pass; all 12 backend smoke scripts pass; the phone proved YouTube Home and Shorts behavior against the local v09 backend; hosted `/health` still reports `promise_compiler_v07`. Still unproven: hosted-v09 parity, a true 40-minute lecture, Chrome under this scope, and other social apps.

Blockers found by the 2026-09-27 audit:

- **Quota false block (phone-verified):** pause/resume on one Short counts twice. Fix the title-hint/fingerprint (drop player-control labels such as "Pause video", "Play video", "Go to channel") with a trace from the phone log before any tester build that uses a shorts quota.
- **No provable hosted revision:** `/health` has prompt version but no code revision. Add a build id (for example the deployed commit SHA via an App Setting) before the hosted run, or the gate cannot prove what was tested.
- **No staging target:** only the production web app exists. Step 3 needs an explicit decision (see the audit's staging plan) before any deploy.
- **Unreviewed checkpoint:** the Android tree is one interlocked compile unit; checkpoint it as a single reviewed batch, not piecemeal.

### Launch bar (closed APK, not Play Store)

**Hard no:** Play Store on Mon 21 Sep. Review takes days. Do not market 100% blocks.

**Yes:** sideload APK to 5 people by **Mon 28 Sep**. USB-only backend is a launch killer — testers must Understand on Wi‑Fi.

| Day | Ship |
|---|---|
| Now | Hosted backend live: https://phonecodex-backend.azurewebsites.net (secret required on POST). Runbook: `docs/azure-hosted-backend.md` |
| Next | Sideload APK with `local.properties` URL+secret. 5 demo promises on a stranger's phone: shorts quota, compound A/B/C, no-porn-rest-normal, min-30, Accessibility setup |
| Then | Watch Azure spend during the 4-day credit window; do not add a new OpenAI account |
| Forbidden | chat-thread UI, GPT-as-cop, new OpenAI resource, NSG 0.0.0.0/0, per-app firefighter lists |

Phone-verified already: 20-shorts; `upto 20 shots`; compound `allow only X, allow only Y`.

## Always on

1. Stabilize phone enforcement for Chrome and YouTube.
2. Reduce false blocks and false allows.
3. Make the app UI faster and simpler.
4. Improve commitment understanding only through confirm-before-start, not blind auto-enforcement.
5. Keep AI evals connected to real phone failures.

## Track Routing

Use the right chat for the right work:

- **Main / Android / Backend:** implementation, tests, phone verification, backend wiring, overlay reliability, install/open behavior, logs.
- **AI Lab:** prompt versions, datasets, evals, Azure model comparisons, messy-language promise understanding, red-team cases.
- **Research Lab:** deep unknowns, Android control planes, Device Owner/MDM/Shizuku, competitor research, product strategy, trust/safety, invention-level exploration.

If a problem is small and already understood, send it to Main / Android / Backend.

If a problem is about whether the AI understands promises, send it to AI Lab.

If a problem is unknown, OS-level, strategic, or needs world-class research before coding, send it to Research Lab first.

Do not let one chat randomly do every track.

Before giving a serious Cursor/Fable/Grok task, include:

```text
Context -> strategic goal -> current verified state -> phases -> deliverables -> success metrics -> forbidden actions -> product north star
```

## Android Task Track

Use this when working on the phone app.

- **Commitment Transparency (2026-09-22):** Home session list + Advanced **Last decision** explains the latest allow/warn/block/lock. Copy debug report is sanitized (no screen text by default). Tests: `CommitmentTransparencyTest`, `HomeChatLayoutTest`. Does not change enforcement.
- **Phone Surface Trace Replay (2026-09-21):** 80+ frozen a11y traces in `PhoneSurfaceTraceCatalog`. When a phone bug appears, add the screen text first, then fix law. Run `:app:testDebugUnitTest --tests com.phonecodex.app.domain.enforcement.trace.PhoneSurfaceTraceReplayTest`. Doc: `docs/enforcement-trace-replay.md`.
- **Protection Reliability Gate (2026-09-20):** Home card + Accessibility heartbeat + `Run protection check` shipped in unit tests. Phone-verify after reinstall/restart: card must say Needs setup / Not protecting / Protected *before* opening Chrome/YouTube. Do not treat a green pill as a11y-enabled-only.
- Verify Accessibility service is alive.
- Verify overlay stays stable on blocked apps.
- Verify own app package events do not hide the overlay incorrectly.
- Verify Chrome and YouTube decisions match the active commitment.
- Fix slow UI paths.
- Keep a simple Decision Inspector for debugging.
- Add tests only around policy/promise logic where possible.

## AI Lab Task Track

Use this when working on model prompts, datasets, or evals.

- Convert real user language into structured commitment policy.
- Include messy English, Hinglish, and later Indian languages.
- Mark ambiguity and ask follow-up questions when needed.
- Measure false allows, false blocks, clarification recall, and parse failures.
- Do not optimize for pretty model answers. Optimize for safe policy output.
- Temporal clocks (session vs media length vs usage quota vs lock): lab done — see `evals/reports/promise_compiler_temporal_clocks.md`.
- **Promise Understanding v07 (2026-09-07):** assistant clarification options + human confirm copy; prompt `promise_compiler_v07.txt`; datasets v7 (1516+102); UX contracts in `docs/ai-promise-understanding-system.md` + `docs/promise-confirmation-ux-contract.md`. Backend default prompt v07 (flag to pin v06). Backend accepts `selectedClarificationOptionId` and unlocks `canStartCommitment` after pick. **Android Home now renders A/B/C option cards + stronger-confirm checkbox + Start gating** (unit tests green). Leftover: `internalPolicy` blob persistence, PolicyEngine auto-wire (still NO), phone verify. See `evals/reports/promise_understanding_v07.md`.
- Promise Semantics v1 (v06 clocks/shorts category) remains the enforcement contract baseline.

## Azure/Backend Task Track

Use this when working on hosted AI or APIs.

- Keep secrets out of git.
- Keep `/health` and smoke tests working.
- Track latency, cost, tokens, prompt version, model deployment, and decision output.
- Use Azure credits for useful eval/model experiments, not random compute burn.
- Design so model providers can be swapped later.

## Product Task Track

Use this when working on UX or strategy.

- User should write a natural promise in their own words.
- App should understand, then show a confirmation sheet before strict enforcement.
- User should understand what will be allowed, warned, blocked, or locked.
- Complex controls should stay hidden until needed.
- UI should feel closer to Claude/Codex/Gemini level clarity than a settings form.

## Do Not Do Randomly

- Do not rename the app/package unless explicitly requested.
- Do not auto-wire a weak promise compiler into enforcement.
- Do not add more Azure services just because credits exist.
- Do not build full phone operator before blocker reliability is solid.
- Do not commit `.env` or secrets.
- Do not remove safety/emergency paths.

## Standard New Chat Instruction

Paste this into any fresh task:

```text
Read PROJECT_CONTEXT.md, CURRENT_STATUS.md, NEXT_TASKS.md, and AGENTS.md first. Then work only on the requested track. Keep PhoneCodex/Strlix as a personal commitment OS, not a normal app blocker. Before changing code, explain the plan briefly. After changes, tell me exactly how to verify on phone or with tests.
```
