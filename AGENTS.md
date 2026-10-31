# Agent Instructions

These instructions apply to the whole `strlix` repo.

## Read First

Before making changes, read:

1. `README.md` (repo map + debug index)
2. `PROJECT_CONTEXT.md`
3. `CURRENT_STATUS.md`
4. `NEXT_TASKS.md`
5. `docs/CURRENT_STATE.md` when touching Android/product behavior
6. `docs/cto-strategy-phonecodex-ai.md` when touching AI/evals
7. `docs/README.md` for the living docs list; ignore `docs/archive/` unless hunting old evidence

## Product Identity

Strlix is a personal commitment OS for phones.

Do not reduce it to a generic blocker. The important behavior is:

```text
natural-language promise -> structured policy -> deterministic enforcement -> log/feedback
```

AI is counsel. PolicyEngine is law.

## Engineering Rules

- Keep changes scoped to the requested track.
- Prefer existing Kotlin/JS/test patterns.
- Do not print or commit secrets.
- Do not edit `.env`.
- Do not rename package names or project structure without explicit request.
- Do not silently weaken safety rules.
- Do not add new cloud services without a clear reason.
- Do not treat one manual demo as proof; add tests or a verification checklist.

## Android Rules

- Phone verification matters.
- Keep emergency/safety paths allowed.
- Accessibility and overlay behavior must be robust against flicker and own-package events.
- Policy logic should stay testable outside Android where possible.
- Expensive or slow work must not run on every tiny accessibility event without throttling/debouncing.

## AI Rules

- Promise Compiler output must be confirmed by the user before strict enforcement.
- Ambiguous commitments should ask follow-up questions.
- Evals should include messy real language, not only clean synthetic examples.
- Measure false allows and false blocks separately.
- Never let cloud AI override permanent guardrails or emergency rules.

## Communication Style

When explaining to Ankit:

- Be direct and concrete.
- Explain what changed and why.
- Teach the concept briefly so he can debug later.
- Give exact verification steps.
- If a task is unsafe, impossible, or strategically wrong, say so clearly and propose the closest safe path.
