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
- no uninstall/tamper during strict commitments

These should be harder to remove than normal session rules, while still preserving emergency and safety paths.

## Current Priority

The current product must become reliable before expanding.

The first reliable loop is:

```text
start promise -> detect phone context -> decide with policy -> warn/block/lock -> show reason -> log event
```

Do not chase UI polish, mascot, payments, full phone operator, or extra Azure experiments if this loop is broken.

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
