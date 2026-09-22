# PhoneCodex — Current State

**Updated:** 2026-09-22 (Commitment Transparency: Last decision on Home/Advanced)

**Hosted backend:** https://phonecodex-backend.azurewebsites.net in `phonecodex-dev`. POST routes require `X-Strlix-App-Secret`. Ladder: compiler `pc-lab-astra` (gpt-6-astra), classifier `pc-lab-terra` (gpt-5.6-terra) → luna/sol/strong. Vision env OFF. Localhost `:8787` still works when secret is empty. Runbook: `docs/azure-hosted-backend.md`. AI does not enforce.  
**Audience:** Future Cursor / Codex sessions  
**Rule:** Read this before shipping features. Prefer this file over chat memory.

**Pipeline map:** `docs/enforcement-reliability-gate.md`

**Invariant (P0):** Before Chrome/YouTube tests, Home must tell the truth about whether enforcement is alive. `ProtectionReliabilityGate` is a preflight (Accessibility heartbeat + session + guardrail + backend). It is not the Surface/media-length gate. **Protected** = Accessibility loop alive AND (active commitment OR enabled life rule). Backend missing does not flip Protected; it only shows Bridge missing + exact `adb reverse tcp:8787 tcp:8787` on loopback. No session and no guardrail → **Not protecting** + `Nothing is enforced.` Run protection check must not Start a promise.

**Invariant (P0):** Passive surfaces (Home/Search/shelf/recs/comments/channel/feed/DM list/tab switcher/shade/launcher/Play Store) must never WARN/BLOCK/COUNT for media-length or short-form law. Only active players (and install/payment/DM thread when those rules apply) may enforce. Log reason: `Surface Gate PASS passive video surface`. Backend AI cannot WARN/BLOCK after PASS.

**Invariant (P0):** Media-length BLOCK is legal only when `EnforcementContext.mayApplyMediaLengthBlock()` is true: active player **and** `durationSource=CURRENT_PLAYER`. Recommendation / ambiguous clocks → WAIT, never BLOCK.

**Invariant (P0):** Every decision log must include `code=` from `EnforcementReasonCodes` plus `surface` / `activity` / `durationSource`.

**Invariant (P0):** User confirmation is category-level. Internal `scopePackages` may be specific; UI must not dump `com.*` / NewPipe unless the user named it.

**Invariant (P0):** After ALLOW / WARN / BLOCK / LOCK (and WAIT), Home → **Last decision** explains the last action in user language: promise, decision, why, evidence, next step. Engineering detail may include package / reason code / surface / duration source. Copy debug report is sanitized and omits screen text by default. This screen does not change enforcement. AI is counsel; PolicyEngine is law.

### Experiment (2026-09-19 → 2026-09-29 IST): vision counsel

Time-boxed opt-in. Home → Advanced Controls → Engineering → Developer diagnostics → **10-day vision experiment**. Debug builds default ON until flipped; kill date **29 Sep 2026 00:00 IST**. Captures at most one downscaled JPEG on WAIT / `VIDEO_APP_DURATION_UNAVAILABLE` (NetMirror / unknown OTT blank player). Never on Surface Gate PASS, never when a `CURRENT_PLAYER` clock already BLOCK/ALLOWs, never dialer/settings/SMS/OTP/banking. Backend needs `AZURE_VISION_EXPERIMENT=1` in local `.env` (not committed) and uses **`pc-lab-astra`** (gpt-6-astra) first, then `pc-lab-vision` / `pc-lab-best`. Not `pc-lab-cheap`. Image is advisory (`likely_short_form` / `likely_long_form` / `likely_movie` / `unknown` / `adult_signal`). Merge cannot invent `MEDIA_BLOCK_OVER_MAX` without a clock and cannot unlock Home PASS. Unused Azure credits are better spent on promise-compiler evals than spraying frames.

Related depth (do not replace this file):

- Product vision: `docs/PRODUCT_MEMORY.md`
- AI / eval strategy: `docs/cto-strategy-phonecodex-ai.md`
- Classifier shipping bar: `evals/reports/classifier_v04_recommendation.md`
- Promise Compiler design: `evals/reports/promise_compiler_v04_design.md`
- Promise Compiler eval: `evals/reports/promise_compiler_v04_eval.md`
- Promise Compiler v03 failure analysis: `evals/reports/promise_compiler_v03_failure_analysis.md`
- Temporal clocks lab: `evals/reports/promise_compiler_temporal_clocks.md`
- Voice promise input design: `docs/azure-voice-promise-input.md`
- Android promise semantics: `docs/android-promise-semantics-contract.md`
- Phone Surface Trace Replay: `docs/enforcement-trace-replay.md`

---

## Tomorrow morning phone checklist (2026-09-08)

Install already on Redmi (`installDebug` succeeded). Backend: `adb reverse tcp:8787 tcp:8787` + Node `/health`.

1. Start promise: `do not allow videos shorter than 30 minutes for next 1 hour` → confirm → Start.
2. Chrome → YouTube Home (recs + Shorts shelf, no player). **Expected:** no overlay; Logcat `activity=passive` / `Surface Gate PASS passive video surface`.
3. Chrome → watch page with related 2m/4m/8m and **no** current total clock. **Expected:** WAIT/no BLOCK; `duration_source=recommendation_ignored`.
4. Active short `00:15 / 00:59` (NewPipe or YT). **Expected:** BLOCK for min-30 promise.
5. Long lecture ≥40–52 min with player total. **Expected:** ALLOW for min-30.
6. Start: `I want to watch at most 10 shorts today, but never adult shorts. Long educational YouTube should still be allowed.` Confirm sheet uses **categories**, not packages.
7. First NewPipe/YouTube short play. **Expected:** ALLOW `count=1/10`.
8. After 10 distinct shorts, 11th. **Expected:** BLOCK; Home/Search clears overlay.
9. Adult short. **Expected:** BLOCK, count unchanged.
10. Mic fills composer text only — does **not** Start commitment.

**Not production-ready until this phone list is green.**

---

## UX reliability notes (v0)

### Portrait-only MainActivity (v0 choice)

`MainActivity` is locked to **portrait** via `android:screenOrientation="portrait"` in `AndroidManifest.xml`.

**Why:** Commitment composer + overlay debugging must not break on accidental rotation. Landscape responsive UI does not exist yet. This does **not** affect other apps.

**Revisit when:** A real landscape layout for composer / confirmation exists.

### Full-screen, not a floating panel (2026-09-20)

`android:resizeableActivity="false"` on application + MainActivity. PIP off. Theme `windowIsFloating=false` with opaque background. This stops MIUI/Android freeform from opening Strlix as a broken mini window. Overlay enforcement windows are unchanged.

### Reinstall setup truth (2026-09-20)

Accessibility is off after reinstall/update. Protection status is **Needs setup**, primary CTA **Turn on Accessibility**, greeting is not “Ready to keep a promise?”. Leftover session/guardrail/heartbeat cannot flip Protected. Backup restore of a fake “working” home is disabled (`allowBackup=false`).

### Backend status truth

Probe kinds (`BackendProbeKind`): `REACHABLE` | `UNREACHABLE` | `BRIDGE_MISSING` | `CHECKING` | `STALE_OK` | `IDLE`.

- Loopback `127.0.0.1:8787` connect failures → **ADB/local bridge likely missing** + hint: `adb reverse tcp:8787 tcp:8787`
- Recent successful probe stays sticky (`STALE_OK`) so a blip does not flash scary “offline”
- Auto-probe once after home bootstrap; Test Backend debounced + one quiet retry
- Never invent “Backend offline” when never tested

**Phone verification**

1. Open PhoneCodex → rotate the phone: UI stays portrait.
2. Start backend on PC (`/health` OK). Without reverse, diagnostics should say bridge missing / unreachable with the `adb reverse` hint — not a vague flash of offline.
3. Run: `adb reverse tcp:8787 tcp:8787`
4. Open app / tap **Test Backend** → status becomes **Backend connected**.
5. Unplug USB (or `adb reverse --remove tcp:8787`) → status explains bridge/local reachability clearly after sticky window expires.

### Active player duration only (media-length)

`VideoDurationParser` separates `currentPlayerDurationSeconds` from `recommendationDurationsSeconds`.

- **BLOCK/ALLOW** only from `duration_source=current_player` (`current/total` clocks, `time duration …`, seekbar near player controls).
- Recommendation cards (`8 minutes 55 seconds`, related rows) → `recommendation_ignored` → **WAIT**, never BLOCK.
- Live regression: Chrome watch title + “Show player controls” + recs 2m/4m/8m, no player total → WAIT.

---
## 1. North star

**PhoneCodex is a personal commitment OS, not a normal blocker.**

Users speak intentions (“no Shorts during study”, “no porn for a year”, “40 Shorts then stop”). The product turns those into structured policy, then the phone enforces them deterministically.

```text
promise → policy → PolicyEngine (law) → overlay (hand) → log (witness)
AI advises. Policy decides. Never the reverse.
```

Normal blockers = package deny-lists. PhoneCodex = promise-relative behavior inside the same apps (lecture vs Shorts, DMs vs Reels).

---

## 2. Current architecture

```text
Android AccessibilityService
  → screen / package signals
  → PolicyEngine (local law: safe apps, guardrails, rules, counters, tamper)
  → overlay (WARN / BLOCK / LOCK)

Android → local backend → Azure OpenAI
  → classifier_v04 (advisory ALLOW / WARN / BLOCK)
  → AiConfidenceGate → merge into PolicyEngine

Promise Compiler (NL → CommitmentPolicy JSON)
  → lab + backend counsel: **v07** assistant options (`promise_compiler_v07.txt`)
  → pin v06 via `PROMISE_COMPILER_PROMPT_VERSION` if needed
  → NOT app-ready for blind PolicyEngine auto-wire — confirm + option cards first
```

| Layer | Status |
|-------|--------|
| Accessibility detect + evaluate | In app |
| PolicyEngine + Decision Inspector | In app |
| Last decision (Commitment Transparency) | In app — user explanation + engineering + sanitized copy. Display only. |
| Overlay enforcement | In app — own-package retain + away debounce + decision hold; still verify on device |
| Surface detection | `SurfaceDetector` + `VideoPlatformRegistry` (YT, Chrome, NewPipe, IG, FB, TikTok, Snap) |
| Media-length (clock B) | Local law on **active player only**; passive Home/Search/shelf/recs → `Surface Gate PASS passive video surface` (no AI WARN/BLOCK) |
| Short-form daily quota | `ShortFormQuotaGate` — plays 1..N ALLOW across apps; N+1 BLOCK; adult excluded; day key `YYYY-MM-DD:short_form_video` (in-memory; TODO persist) |
| Surface activity | `SurfaceDetector` + `SurfaceEnforcementGate`: PASSIVE vs ACTIVE; AI cannot WARN/BLOCK after PASS |
| Backend `/classify` + Azure | Working in lab; classify = `pc-lab-strong` (gpt-4.1), escalate = `pc-lab-best` (gpt-5.6-sol) |
| Promise Compiler | Lab only — v04 general set + **v05 temporal clocks** (not product-ready) |

---

## 3. Verified working parts (as of 2026-09-06)

**Android / product loop**

- Study World session start / stop
- AccessibilityService foreground package detection
- PolicyEngine decisions (ALLOW / WARN / BLOCK / LOCK)
- Overlay show path for BLOCK / WARN
- Safe-app allowlist (including PhoneCodex itself in study allow set)
- Permanent guardrails store + evaluator
- Tamper guard hooks
- Decision Inspector / debug UI + event log
- Last decision card on session Home + Advanced (sanitized copy report)
- Network classifier path: app → backend → Azure → confidence gate → policy merge

**AI Lab (not product-critical this week)**

- Screen classifier **v04** on `v1_edge_cases`: ~95% accuracy on `pc-lab-cheap`, **0 false allows** — recommended production advisor prompt
- Promise Compiler **v04** (`promise_compiler_v04.txt`) on quality set `evals/datasets/v3_commitment_messy_global.jsonl` (200 distinction cases, no `synthetic_fill`):
  - `pc-lab-cheap`: exact **41.0%**, safety **62.5%**, violations **7**, parseFailures **0**
  - Clarification recall **82.5%** (vs v03 **62.5%**); `duration_ambiguous` and `session_and_content` improved vs v03
  - Aggregate safetyMatch slightly below v03 (**64.5% → 62.5%**) but load-bearing distinctions improved; remaining violations = missed follow-ups on vague phrases
- Promise Compiler **temporal clocks** (`v4_temporal_clocks.jsonl`, 87 cases; schema `max_item_minutes` / `min_item_minutes`):
  - Primary metric: **temporal clock OK** (session vs media length vs usage quota vs lock vs permanent)
  - `pc-lab-cheap`: **v05 100%** clock OK vs **v04 65.5%** (media_* and session_and_media all fail on v04 — prose-only thresholds, not structured quotas)
  - Report: `evals/reports/promise_compiler_temporal_clocks.md` — still lab-only; PolicyEngine must implement item-length rules before any ship
  - Verdict: still research-stage / **NOT app-ready** — do not auto-wire into PolicyEngine
  - Design / eval: `evals/reports/promise_compiler_v04_design.md`, `evals/reports/promise_compiler_v04_eval.md`
- Promise Compiler **Semantics v1** (`promise_compiler_v06.txt` + normalize):
  - Live failure family: shorts category ≠ YouTube-only; calendar day ≠ 60m session; quota `allow_first_n`; NewPipe in scope; confirmationPreview
  - Dataset: `evals/datasets/v6_promise_semantics.jsonl` (122)
  - Smoke: 20 invariants green (`node backend/smoke_promise_compiler_normalize.js`)
  - Reports: `evals/reports/promise_semantics_v1.md`, `docs/android-promise-semantics-contract.md`
  - Still **NOT** auto-wire into PolicyEngine

---

## 4. Current known blocker (P0)

### Overlay self-hide / foreground package loop around `com.phonecodex.app`

**Symptom:** Overlay appears on a blocked app (e.g. YouTube), then vanishes or flickers because Accessibility reports the foreground package as PhoneCodex’s own package when the overlay window is shown.

**Mechanism:**

1. User opens blocked package (e.g. YouTube).
2. PolicyEngine → BLOCK → TYPE_ACCESSIBILITY_OVERLAY shown.
3. Next accessibility event may report `packageName = com.phonecodex.app`.
4. If that event is treated as “safe app / left target,” the overlay hides → loop.

**Code already has a guard** (`activeOverlayTargetPackage`, `handleActiveOverlayForeground`) — treat as **not fully verified on device**. Stabilize and prove with the checklist below before any new features.

**Until this is green:** do not trust “blocking works” demos.

---

## 5. Current next priority

**Stabilize the blocking loop before new features.**

Order:

1. Prove overlay stays up on YouTube (or Instagram) under Study World.
2. Prove PhoneCodex own-package events do **not** wrongly hide the overlay.
3. Prove logs show a stable final decision (not thrashing ALLOW/BLOCK).
4. Only then: next product step for Promise Compiler — confirm-sheet for compiled policy + follow-ups before Start (still behind PolicyEngine; no auto-wire).

---

## 6. What NOT to do now

| Do not | Why |
|--------|-----|
| New AI Lab phases / datasets / prompt v05+ | Distraction until blocking loop is solid |
| Azure expansion (new deployments, vision path) | Classifier is already past shipping bar for text; vision waits for OCR failure evidence |
| UI polish / mascot / marketing screens | No product without reliable block |
| Feature creep (payments, friends, pet, operator taps) | Premature |
| Auto-wire Promise Compiler into PolicyEngine | Research-stage / not app-ready; confirm-sheet + follow-ups first after overlay P0 |
| Treat AI as sole authority | PolicyEngine must remain law |

---

## 7. Exact verification checklist (next build)

Run on a real device (Xiaomi / target phone). All must pass.

### Core overlay loop

1. **Backend `/health`** — local Node backend up; health endpoint OK.
2. **`adb reverse`** — phone can reach host backend: `adb reverse tcp:8787 tcp:8787`.
3. **Accessibility alive** — PhoneCodex service On; Logcat shows `PhoneCodexA11y` / `PhoneCodexDecision`.
4. **Launch YouTube** (or known blocked package) with Study World active.
5. **Overlay stays** — block overlay remains; no flicker-off within ~5–10s idle on the blocked app.
6. **Logs show final decision** — Logcat filter `PhoneCodexDecision` shows stable lines:
   `pkg=… surface=… rule=… count=… decision=… reason=…` (not rapid flip-flop).
7. **Own package does not hide wrongly** — events for `com.phonecodex.app` while overlay is up must **retain** overlay for the external target.

### Surface / false-block checks (P0 reliability)

8. **Chrome Google Search / Videos tab** — with a media-length promise, must **ALLOW/PASS** (not block as “video too long”). Surface log should be `SEARCH` or non-player; reason `Surface Gate PASS passive video surface`.
9. **YouTube Home / Search / Chrome YouTube Home** — must not be treated as `ACTIVE_VIDEO_PLAYER`; thumbnail clocks (e.g. “52 minutes”) must **not** WARN/BLOCK/COUNT. Min-length promises (“shorter than 30 min”) same rule.
10. **YouTube player (fullscreen and inline)** — with length promise, long/short violation → BLOCK; within limit → ALLOW; missing duration → WAIT (no AI storm).
11. **Leave blocked content** — after BLOCK/WARN, navigate to Home or Search: overlay clears (Surface Gate PASS / ALLOW); no stuck WARN.
12. **NewPipe** (if installed) — open a playing video with clocks like `00:15 / 45:00`; length promise must parse duration and BLOCK/ALLOW correctly; app should appear under AI_DECIDE defaults (not silent bypass).
13. **Emergency / phone / Settings** — dialer, phone, system settings remain allowed (safe apps).
14. **Quota (if promise allows N Shorts)** — first N distinct Shorts ALLOW; after limit → BLOCK; duplicates do not double-count. Log shows `count=k/N`.
15. **Performance** — scrolling YouTube home should not fire AI every tick; Logcat `PhoneCodexNetAI` shows throttle/cache, not request spam.

### Cross-app daily short-form quota (Promise Semantics v1)

16. **Start promise** — “at most 10 shorts today, never adult/sexual shorts; long educational YouTube still allowed.” Confirm sheet / settings: duration ~1440 (calendar day), short-form quota=10.
17. **Open NewPipe short** — active player with clocks like `00:15 / 00:59`. Logcat:
    `ShortQuota pkg=org.schabi.newpipe surface=SHORT_FORM_PLAYER count=1/10 counted=true action=ALLOW …`
18. **Repeat distinct short plays** across NewPipe / YouTube Shorts / IG Reels until count=10. Each new play increments once; rapid a11y thrash must not double-count.
19. **11th short play** — must **BLOCK** until local day ends. Log shows `count=10/10 action=BLOCK`.
20. **Adult/sexual short** — BLOCK immediately; count must **not** increase.
21. **Long educational lecture (1h+)** — ALLOW; must **not** consume short-form quota.
22. **Chrome Search / YouTube Home Shorts shelf** — must **not** count and must **not** quota-block.
23. **AI cannot override** — with quota exceeded, backend ALLOW must not clear the BLOCK overlay.

Fail any core step (1–7) → stay on P0. Quota steps (16–23) are the short-form ship gate.

---

## 8. Important future ideas (remember; do not build now)

From product memory + CTO strategy — park here until the loop is stable:

| Idea | One-line |
|------|----------|
| **Guided Study Rail** | Allowed path inside distracting apps (lecture track, not free roam) |
| **Exact YouTube playlist / channel enforcement** | Promise-scoped allow: only named channel/playlist |
| **Natural-language Promise Compiler** | Text → CommitmentPolicy (v04 general + v05 temporal clocks lab); confirm-sheet must show five clocks; ship only behind PolicyEngine + item-length enforcement |
| **Strict mode / tamper protection** | Harder exit, preventDisable, LOCKED — with emergency always open |
| **Vision classifier** | Screenshot / frame when OCR lies; opt-in; never bank/OTP |
| **Safe phone operator** | Agentic “do X on phone” — long-term only; Play + liability constrained |

Also remember (not ship now): permanent Life Rules vs session Worlds, Recovery World, install gate, accountability friend, recovery fees only with pre-consent.

---

## 9. Session bootstrap (for agents)

When starting work:

1. Read **this file** first.
2. If task is Android blocking: fix overlay loop; use §7 checklist.
3. If task is AI Lab: confirm Ankit explicitly wants lab work; otherwise refuse and point here.
4. Never print secrets / `.env` keys.
5. Do not “helpfully” expand scope into UI polish or Azure.

**One sentence truth:** The product is a commitment OS; the current job is making the overlay stay up when YouTube is blocked — everything else waits.

---

## Future Reliability Issue — PiP / Floating Video Escape

**Discovered:** 2026-09-05 during phone testing on Xiaomi / MIUI.

**Symptom:** Full-screen YouTube/Chrome blocking works, but a distracting video can continue playing in a small Picture-in-Picture / floating mini-player while the foreground package is `com.miui.home`.

**Why it matters:** Foreground package detection alone can say “home screen is safe” while the user is still watching distracting media. This is exactly the kind of small escape path that makes a blocker feel untrustworthy at scale.

**Not a P0 today:** Full-screen enforcement is the current priority. Do not derail the main loop for PiP yet.

**Later solution direction:** Add a media/PiP detection layer using a combination of Accessibility window changes, media session signals, notification/media controls, and possibly screenshot/vision classification. Policy should treat active distracting media overlay as context, not just foreground app.

**Future test case:** Start commitment → open YouTube distracting video → enter PiP/floating window → return home → PhoneCodex should detect active media distraction and warn/block/close/guide back according to policy.

---

## Operating Principles — Do Not Forget

**Added:** 2026-09-05

Before any major prompt, feature, bugfix, or architecture decision, think from three roles at once:

1. **Scientist:** What is the measurable hypothesis? What evidence proves it? What edge case breaks it?
2. **Best engineer:** What is the simplest reliable system that survives Android reality, scale, latency, crashes, permissions, and debugging?
3. **Founder / product thinker:** If this had millions or a billion users, would people trust it, understand it, love it, and keep using it?

### Required decision filter

Before giving Cursor/Fable/Claude/any AI a task, check:

- **North star:** PhoneCodex is an AI commitment OS, not a normal blocker.
- **Scale:** Would this architecture still make sense for millions/billions of users?
- **Trust:** Does this make the user feel protected by their own promise, not controlled by malware-like software?
- **Accuracy:** Are false allows, false blocks, latency, and user harm measured?
- **UX quality:** Does this move toward YouTube/Netflix/Claude/Gemini-level smoothness, not a rough settings form?
- **Android reality:** Does it account for accessibility quirks, overlays, PiP/floating windows, battery killing, permission loss, settings tamper, and OS limits?
- **Competitive learning:** Study and borrow patterns from Opal, Freedom, One Sec, ScreenZen, Stay Focused, Digital Wellbeing, YouTube, Netflix, Duolingo, Headspace, Claude, ChatGPT, Gemini.
- **Uniqueness:** The unique wedge is natural-language promise → structured policy → phone behavior → logs/feedback → smarter future behavior.

### Prompt quality bar

Do not give shallow Cursor tasks for hard problems. For serious work, prompts must include:

```text
Context → strategic goal → current verified state → phases → deliverables → success metrics → forbidden actions → product north star
```

Use strong models like Fable/Opus only for monster tasks: ambiguous product architecture, eval science, user psychology, trust/safety, failure analysis, and new system design. Do not waste them on tiny edits.

### Stability rule

Do not chase new features while the core loop is unstable:

```text
promise starts → app detects context → policy decides → overlay/warn/block works → user understands why → logs prove it
```

If a bug appears, do not immediately prompt Cursor. First:

```text
VERIFY → DIAGNOSE → EXPLAIN → THEN PROMPT
```

---

## Ways this can still fail on a real phone (overnight sprint honesty)

Unit tests are green; phone verification is still required. Remaining risks:

1. PiP / floating mini-player with weak a11y text may look passive while video plays.
2. NewPipe history rows with `00:15 / 45:00` **and** a title containing “play” may false-active.
3. YouTube mobile web may hide total duration until controls expand → prolonged WAIT (good) or user thinks app is “broken.”
4. Recommendation rows that use `time duration` phrasing (rare) could pollute current-player parse.
5. Instagram Reels / TikTok without readable clocks may under-count quota or misclassify short vs long.
6. Short-form quota is **in-memory** — process death resets count mid-day.
7. Cross-app quota dedupe keys may collide on similar clocks + empty titles.
8. MIUI battery killers can pause Accessibility → silent non-enforcement.
9. Overlay TYPE_ACCESSIBILITY may still flicker on some Xiaomi skins despite own-package retain.
10. System UI / notification shade packages vary by OEM beyond the small allowlist.
11. Adult keyword list still false-positives news titles; euphemisms still false-negative.
12. Messaging-thread vs DM-list classification is coarse — message-restrict promises not fully product-wired.
13. Install/payment flow detection is phrase-based — Play Store browsing vs Install button races.
14. Azure `/compile-promise` offline → local parser confirmation; category copy may be thinner offline.
15. Voice path still uses on-device `RecognizerIntent` for fill — Azure `/transcribe-promise` not fully phone-wired.
16. Browser embeds / WebView players outside Chrome package may miss registry.
17. Multi-window / split-screen package identity can confuse target tracking.
18. Rapid swipe Shorts may generate duplicate or skipped counts under debounce.
19. LOCKED session overlay clear rules need phone proof (unit only covers gate helper).
20. Confirmation UX sanitizer may over-strip “Chrome” when user meant browser videos — rare, verify with “only Chrome”.
