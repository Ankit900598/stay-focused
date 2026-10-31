# Strlix Project Context

Read this first in every new AI/Codex/Cursor task.

## North Star

Strlix is building a personal commitment OS for phones.

The product is not a normal app blocker. The long-term goal is: a user speaks or types a messy commitment in natural language, the system understands it, converts it into structured policy, and the phone behavior changes around that promise.

Examples:

- "Only Neso Academy OS playlist for 3 hours. No Shorts. Lock me if I drift."
- "No porn for 1 year, but keep the rest of my phone normal."
- "Allow Instagram messages, but block Reels during work."
- "Let me watch 40 Shorts, but block adult content and stop me after the quota."
- "Tomorrow I have an exam. Allow only study YouTube and required browser search."

The unique idea is promise-relative control inside apps, not just blocking whole apps.

The second unique idea is safe substitution: when a distracting app is blocked,
Strlix should eventually offer a delegated AI path for necessary life tasks.
Example: if Gmail is blocked for 3 weeks, the user should not freely open Gmail,
but a pre-authorized Strlix AI connector can summarize important mail, draft a
reply, and ask for confirmation before sending. The same long-term pattern can
extend to LinkedIn, WhatsApp, Instagram, Facebook, and other apps where safe APIs,
notifications, share flows, companion control, or OS-level control planes exist.
Blocked UI does not mean blocked life; it means the addictive surface is closed
while necessary tasks move through a controlled AI operator.

## Core Law

```text
user promise -> Promise Compiler -> structured policy -> PolicyEngine -> overlay/action -> log
```

AI suggests. Policy decides. Overlay enforces. Logs prove what happened.

Never let a model directly become the final authority for dangerous user-facing actions. The PolicyEngine is the law.

## Two Product Layers

### Commitment Worlds

Temporary worlds for a goal:

- Study World
- Deep Work World
- Monk Mode
- Sleep World
- Recovery World

These are session-based and may last minutes, hours, or days.

### Life Rules

Long-term commitments independent of a session:

- no porn for 1 year
- no betting apps
- no toxic chat apps
- stronger, transparent friction before weakening a commitment inside Strlix

Life Rules can add reversible, user-chosen friction to actions Strlix controls, while preserving emergency and safety paths. They must not claim to prevent a device owner from turning off Android Accessibility in Settings, force-stopping/uninstalling Strlix, or using other OS controls. A managed-device tier would require separate explicit administrator/parent authorization and platform-policy review.

## Current Priority

Latest audit update (2026-10-12): the current Android fail-closed boundary rejects structured `study` content rules because no reliable on-device study classifier exists; explicit unsupported collection surfaces also remain blocked. The mapper-level test proves a backend DTO claiming `canStart=true` cannot bypass this rule. Full Android tests: 1,139 passing; phoneTest APK SHA-256 `7F0E4B63C20DEB03FF0D1889FCF2E4C5301ED8742CACCAB5197F054C843CB6C1`. This is not Xiaomi behavior proof: Strlix is absent. A read-only Play Console check currently shows new-account signup and requires the human owner to choose individual vs organization; the page warns account ownership cannot be changed, so no choice was made. A separate Play-policy gate is now recorded: the Accessibility service can automatically surface a classifier BLOCK after a matching user-confirmed `AI_DECIDE` rule. Disclosure and rule gating are present, but Google's prohibition on autonomous Accessibility decisions needs expert policy review; this is an unresolved risk, not a final violation finding. Repository search also found no in-app privacy-policy/Data Safety artifact; confirm external policy and provider-retention wording before distribution. The merged manifest has explicit microphone, Usage Access, and special-use FGS capabilities; voice/usage are disclosed in the UI source, but Xiaomi behavior remains unverified and FGS/biometric declarations need review. **A P0 backend-release auth mismatch is now confirmed:** release builds reject embedded shared secrets while core Android API clients still rely only on that key and hosted routes require it. Keep non-mail paid APIs closed until a deliberate user-bound/session or attestation design is implemented route-by-route. The two mail-assist endpoints alone now use verified Google identity; source requires the flag to equal `1`, but the live Azure flag was not checked. Gmail OAuth/CASA/privacy approval is not complete. Official Internal App Sharing remains gated by account, permanent app identity, and policy review. Supporting study-only and exact named sources requires an evaluated typed-evidence contract plus multilingual fixtures.

The current product must become reliable before expanding.

The first reliable loop is:

```text
start promise -> detect phone context -> decide with policy -> warn/block/lock -> show reason -> log event
```

Do not chase UI polish, mascot, payments, full phone operator, or extra Azure experiments if this loop is broken.

Current audit authority (2026-10-12): use `CURRENT_STATUS.md`, `NEXT_TASKS.md`, `docs/phone-test-play-distribution.md`, and the single issue ledger `docs/phone-observation-errors-2026-10-11.md` for exact hashes and gates; dated audit snapshots below this paragraph may be superseded. Confirmed-session classifier authority, app-target binding, declared-surface matching, and unsupported collection-target/start refusal are implemented locally; structured `study` classifier rules are now refused because Android cannot reliably enforce them. Latest full Android suite: **1,139 tests, 0 failures/errors/skips**, `:app:assemblePhoneTest` succeeds, and `:app:lintPhoneTest` passed for the production-source changes (0 errors, 97 warnings, 2 hints). Current local credential-free phoneTest APK SHA-256: `7F0E4B63C20DEB03FF0D1889FCF2E4C5301ED8742CACCAB5197F054C843CB6C1`. This artifact is not phone-verified: Xiaomi has no Strlix package or PhoneCodex Accessibility binding; a prior normal Files install was blocked by Play Protect. Do not bypass Play Protect. The loopback URL is intentional for `adb reverse`; raw phone HTTP transport is not an installed-app compiler request. No inference/deployment/model call occurred. Official Play owner/account, app-identity, policy, and Internal App Sharing gates precede the cross-app Xiaomi matrix. Android 16 large-screen behavior and study-content classification remain unverified/unsupported.

Install-gate update (2026-10-12): the newer local APK `827DA6162D1A01AB1C4D2971AE3ABEFCF9324066175419147760E8DC015745AB` was verified on Xiaomi, but Google Play Protect explicitly blocked installation from Xiaomi Files with the sensitive-data warning. Google's guidance describes this as an automatic internet-sideloading restriction for apps with Accessibility, distinct from a malware classification. Play Protect stayed enabled, the APK was not installed, and no current-app phone behavior was tested. Stop repeating Files installs; remove the embedded reusable backend key before an official Play test distribution, then verify the installed artifact and phone matrix. `CURRENT_STATUS.md` and `NEXT_TASKS.md` carry the latest gate.

Earlier checkpoint (2026-10-12): fixed the recommendation-clock false block, safe-app and ordinary-ALLOW explanation errors, decision-to-promise attribution risk, and Home's confusing permanent-guardrail label. Decision diagnostics store only a session ID, never promise text; older unproven snapshots are cleared once. The previous APK hash was `094B9E3E2FE7DB5BE6798CB5EE6F9D0B7239C0701F4F3F572790EBEAB546C89A`; 1,051 Android unit tests, lint, assembly, and 7/7 exact-current-source isolated-emulator UI tests passed then. Emulator tests are not phone enforcement proof. The prior physical verification covered idle Home/UI and Accessibility health only; a transient post-install Not protecting frame recovered, then remained Protected in a passive 35-second watch. No Azure inference, deployment, or configuration change was made; GPT-6.1 Sol was not confirmed as the live route. Use `CURRENT_STATUS.md`, `NEXT_TASKS.md`, and the single issue ledger `docs/phone-observation-errors-2026-10-11.md`; never infer that every app/surface passed from a narrow YouTube test.

## Working System

Use separate chats/tasks for separate kinds of work so context stays clean and API cost stays controlled.

### Main / Android / Backend Chat

Use this for implementation, tests, phone verification, backend wiring, overlay behavior, and release-gate checks.

This chat owns:

- Android app code
- backend endpoints
- phone/ADB verification
- bug reproduction
- build/test/install loops
- safety-critical enforcement behavior

### AI Lab Chat

Use this for promise understanding, prompts, datasets, evals, model comparisons, and Azure inference experiments.

This chat owns:

- Promise Compiler prompt versions
- messy promise datasets
- red-team cases
- eval metrics
- model routing recommendations
- clarification quality

### Research Lab Chat

Use this for deep unknowns before coding.

This chat owns:

- Android control-plane research
- Device Owner / MDM / Shizuku / OS-level options
- competitor teardown
- product strategy
- scientific hypotheses
- billion-user trust and safety analysis

When a hard problem appears, do not immediately patch one symptom. First ask the Research Lab for the strongest legal, ethical, owner-consented solution, the constraints, the proof experiments, and the recommendation for now vs later.

### Control-Room Rule

This current/main Codex conversation is the control room. It decides which track gets the next task, verifies outputs, keeps the north star intact, and prevents random one-off fixes.

For difficult or uncertain work, use this handoff in order:

1. Codex diagnoses the current code/state and defines the question to prove before proposing a fix.
2. Claude Research investigates the hard unknown and returns evidence, alternatives, constraints, and verification criteria.
3. Codex checks that research against the repository and product goals, then writes one cohesive implementation task.
4. Cursor Backend (or the appropriate Cursor implementation chat) implements the validated approach and runs focused tests.
5. After implementation, Cursor gets a separate phone-verification task with exact steps, expected outcomes, and evidence to collect.
6. Codex independently reviews the actual diff, tests, logs, screenshots, and phone result. A “done” message alone is not proof.

Skip Claude research for small, already-understood fixes, but keep independent verification. Codex owns coordination and should not send Ankit to copy technical handoffs or make routine decisions; ask him only when genuine on-device confirmation, product intent, or security approval is required.

Every task must name the product horizon it advances and the user outcome it unlocks. Turn a reported bug into a reusable rule plus representative regression cases, not a one-app patch; define a measurable exit gate, and once it passes, move to the next horizon instead of reopening the same class without new evidence. Keep the full arc visible: reliable commitment runtime → conversational text/voice experience → safe, least-privilege delegated connectors and user-approved operations.

## Long-Term Direction

After the core blocker is reliable, Strlix should grow toward:

- natural-language promise compiler
- confirm-before-start flow
- promise-specific YouTube/channel/video rules
- inside-app behavior control such as Shorts vs lectures, Reels vs messages
- permanent guardrails
- recovery and accountability paths
- optional vision understanding when screen text is insufficient
- safe phone operator assistant for user-approved tasks
- safe delegated connectors for blocked apps: read important messages, summarize,
  draft replies, and act only through pre-authorized channels without unlocking
  addictive feeds or inboxes

## Safety Principles

- Always allow emergency paths.
- Never silently capture private screens.
- Never collect OTP, banking, passwords, or private messages for cloud classification.
- Strong mode must be pre-consented before it starts.
- Any paid escape or accountability penalty must be chosen before the commitment, not forced during distress.
- AI can warn, explain, and suggest; final enforcement must go through policy.

## Canonical Deep Docs

- Product memory: `docs/PRODUCT_MEMORY.md`
- Current state: `docs/CURRENT_STATE.md`
- AI strategy: `docs/cto-strategy-phonecodex-ai.md`
- Azure plan: `docs/azure-15-day-execution-plan.md`
- AI Lab 12-day acceleration: `docs/azure-12-day-ai-lab-plan.md`
