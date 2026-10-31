# CTO Strategy — PhoneCodex AI Architecture

**Author:** AI Lab  
**Date:** 2026-09-05 (post v1 model matrix + classifier_v04)  
**Audience:** Ankit Yadav (founder)  
**Status:** Active — update after v2_noisy_ocr dataset

---

## One-line strategy

**Build the most evaluated promise-relative phone advisor on earth — policy is law, AI is counsel, evals are the moat.**

---

## 1. What is the real technical wedge?

**Not** an AI blocker. **Not** “GPT reads your screen.”

The wedge is:

> **Natural-language commitment → structured policy → deterministic phone behavior → logged outcomes → better advisor over time.**

What competitors cannot copy in a week:

| Layer | Wedge |
|-------|-------|
| **Promise-relative labels** | Same YouTube screen → ALLOW / WARN / BLOCK depending on `userGoal` only |
| **Policy merge** | AI advises; PolicyEngine decides. Counters and guardrails are law |
| **Eval-gated trust** | We prove 0 false allows on 164+ labeled cases before model swaps |
| **Recovery, not shame** | Fail → Recovery World, not permanent guilt UX |

**Proof today (2026-09-05):**

| Dataset | Prompt | Deployment | Accuracy | False allows |
|---------|--------|------------|----------|--------------|
| v0_challenge (50) | v03 | cheap | 88% | 0 |
| v1_edge_cases (114) | v03 | cheap | 84% | **2** |
| v1_edge_cases (114) | **v04** | **cheap** | **95%** | **0** |

v0_challenge alone lied to us. v1 + v04 is the first honest production signal.

**Moat trajectory:** Proprietary eval corpus (promise × screen × decision) + policy architecture + user commitment history. Models are interchangeable; **labeled promise behavior** is not.

---

## 2. Why normal blockers are weak

| Normal blocker | PhoneCodex |
|----------------|------------|
| Block Instagram always | “College work on social” → IG DM = WARN, not BLOCK |
| Block all Shorts | “40 Shorts, no adult” → ALLOW until quota; guardrail overrides |
| One-size morality | User’s words define behavior |
| Keyword “adult” | “Adult learners” news ≠ porn — v1 `ch_false_adult` cluster |
| Block YouTube | Lecture with shelf visible = ALLOW under study promise, BLOCK under monk |

**Example generic blockers fail:**

*“Warn me if I drift toward Shorts, but let me watch this lecture.”*

- Foreground: 58-min lecture ✅  
- Visible: Shorts shelf not opened ⚠️  
- Promise: explicit nudge language  

**Correct: WARN.** v03 got this on challenge; v1 exposed absolute-ban shelf cases where BLOCK is required even with lecture foreground.

Blocklists cannot express quota, monk mode, drift nudges, or guardrail-only promises. Only a **commitment OS** can.

---

## 3. Why natural-language commitments are the product

Users do not think in package names. They think:

- “No porn for a year”
- “Monk mode until JEE”
- “40 Shorts then stop”
- “Warn when I drift”
- “Lock if I keep cheating”

The product **is** the promise compiler: speech/text → `commitmentType` + guardrails + counters + friction level → phone behavior.

If we ship “block TikTok,” we compete with Screen Time, Digital Wellbeing, and 50 indie blockers — **commodity, no wedge**.

If we ship “your words become reliable behavior, tested and logged,” we own a category: **personal commitment OS**.

AI is necessary because promises are ambiguous. Policy is necessary because promises must not be hallucinated.

---

## 4. Why policy must dominate AI

**AI without policy is dangerous.** BLOCK from an LLM is an opinion with UX consequences.

```text
Safe App → Accessibility-control telemetry → Permanent Guardrail → App Rule
    → Content Signals → AI Classifier (advisory)
    → AiConfidenceGate → PolicyEngine (law) → Overlay
```

**PolicyEngine owns (never AI):**

- Counter math (`shortsWatched` / `shortsLimit`)
- Guardrail catalog (`no_adult_content`)
- Attempt lock thresholds
- Detect and log entry to Strlix's Accessibility controls; never prevent the user from disabling or uninstalling the service
- Emergency override (calls, SOS)
- Final merge when AI confidence is low

**AI owns:**

- Ambiguous screen interpretation
- WARN vs ALLOW friction calls
- Reason string for Decision Inspector

**Evidence:** v03 cheap had 0 false allows on 50-case challenge but **2 false allows on 114-case v1** until prompt fix. PolicyEngine must **never** auto-ALLOW when guardrail + absolute ban flags fire, regardless of model.

---

## 5. Why on-phone enforcement is hard

| Hard problem | Why |
|--------------|-----|
| **Accessibility OCR** | Noisy trees, WebViews, missing labels — not ChatGPT’s fault |
| **Overlay without malware shape** | Play Store scrutiny; user must disable us |
| **Same app, opposite rules** | YouTube lecture vs Shorts player — foreground detection |
| **Tamper arms race** | Settings → force stop → uninstall |
| **Battery / latency** | Classify on every screen change — cost + UX |
| **Emergency ethics** | False block on call = uninstall + liability |
| **Offline** | Cloud classify fails in airplane mode |

Cloud AI is the **easy** part. The wedge is on-device: promise state, overlay, OCR pipeline, PolicyEngine merge, Recovery World.

We eval cloud classifier now; Android must implement the same decision table locally for deterministic paths (emergency and quota exhausted). Accessibility revocation is an OS-controlled state transition: Strlix can report stale protection and offer recovery after the user returns, but cannot promise enforcement after the service is disabled.

**Distribution gate (unresolved):** Google Play requires declaration and prominent disclosure/consent for non-disability uses of AccessibilityService and prohibits preventing users from disabling or uninstalling the service. Its [automation guidance](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en) distinguishes deterministic, rule-based automation that follows a static, human-defined script from prohibited autonomous initiation/planning/execution. That distinction informs risk but is not a Play pre-approval: the explicit-confirmation → deterministic PolicyEngine enforcement loop is closer to the static-rule example, while a general AI phone/browser operator planning and executing actions via Accessibility is materially higher risk. Android's [API guidance](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) says Accessibility services should only assist users with disabilities. Before Play launch, obtain policy/legal review of the actual user flow and choose a viable platform strategy; a closed sideload pilot does not establish eligibility. Never claim `isAccessibilityTool` eligibility or tamper-proof control. Prefer narrower APIs where feasible.

---

## 6. Why full phone operator AI is long-term, not v0

“AI operates your phone” requires:

- Reliable tap/swipe/type across OEM skins  
- Multi-step plans with rollback  
- Banking/2FA safety boundaries  
- User trust earned over months  
- Regulatory surface (accessibility abuse, spyware labels)

**v0 scope:** Advise ALLOW/WARN/BLOCK/LOCK on foreground screen relative to active promise. Human still taps. Overlay adds friction; PolicyEngine enforces counters and locks.

**v1+ scope:** Escalation to stronger model on ambiguity. Vision when OCR fails.

**Long-term:** Agentic flows (“open Anki and start 25-min timer”) — only after classify accuracy ≥95% on v1+v2 with 0 FA and real user logs.

Shipping operator AI before eval discipline = **trash** — one wrong tap in banking app ends the company.

---

## 7. What can make this globally unique?

| Asset | If we execute |
|-------|---------------|
| **Promise-relative eval corpus** | No public dataset labels ALLOW/WARN/BLOCK per userGoal on same screen — publish methodology |
| **Policy-first architecture** | Classifier advises, engine decides — rare in consumer |
| **Three-axis framework** | Alignment × violation clarity × friction intent — portable across models |
| **0 false allow proof** | Trust brand: “we test your promises, not ours” |
| **Recovery World** | Retention vs punitive blockers |
| **Hybrid inference ladder** | Cheap default → strong escalation → vision when OCR fails |

**Not unique:** Calling GPT on screen text. Any app can do that in a week.

**Unique:** Eval-gated promise compiler + policy merge + commitment history. **Ship Android v1 with Study world + v04 cheap default** or it stays a lab toy.

**ICP:** Students / exam cram / founders first — not “everyone with a phone.”

---

## 8. What unsafe paths we refuse to build

| Path | Why |
|------|-----|
| Silent full-screen logging to cloud | Surveillance — consent death |
| Unkillable overlay | Malware pattern |
| Credential / banking harvest | Legal + moral liability |
| Hidden VPN / traffic intercept | Spyware |
| Block emergency / SOS | Liability |
| Shaming UX | Recovery World exists |
| Sell screen data | Business poison |
| Bypass Android security without informed consent | Play removal |
| **AI as sole authority** | One hallucination = false allow |

**Safe shape:** User writes promise (consent) → on-device OCR first → cloud optional with disclosure → AI advises → policy decides → Decision Inspector shows reason → emergency always ALLOW.

---

## 9. When will we need our own model?

**Not now. Not at 164 labeled cases.**

| Stage | Trigger | Action |
|-------|---------|--------|
| **Now** | < 500 cases | Azure + prompt engineering (v04) |
| **Month 2–3** | 500+ cases, stuck ≥95% with 0 FA | Fine-tune gpt-4.1-mini on Azure |
| **Month 6+** | Inference cost > margin | Distill or on-device tiny model |
| **Year+** | 10k+ proprietary labels, generic ceiling proven | Custom model — **cost play, not wedge** |

Fine-tuning on 114 cases = overfit to v1 wording. **Trash until v2_noisy_ocr + live logs exist.**

**Exception:** On-device fallback classifier (ALLOW/WARN only, never solo BLOCK on guardrail surfaces) — post-PMF offline story.

**Model matrix result:** gpt-4.1-mini + v04 matches or beats gpt-4.1 on v1 for safety. **No custom model needed for MVP.**

---

## 10. Next 30-day technical plan

### Week 1 (done / in progress)

- [x] v1_edge_cases dataset (114 cases)
- [x] Runner `--deployment` + latency metadata
- [x] v03 model matrix on v1 (cheap/strong/vision)
- [x] classifier_v04 + re-test
- [x] v1_model_comparison_summary.md + v04 recommendation

### Week 2 — Dataset + regression

- [ ] `v2_noisy_ocr.jsonl` (80–120 cases) — truncated OCR, Hinglish promises
- [ ] `v0_vision_v1` screenshot fixtures (30 cases) — test pc-lab-vision lift
- [ ] Automated regression script: v04 on seed + challenge + v1 + v2; fail CI if FA > 0
- [ ] Schema v2: optional `severityLevel`, `pairedGroupId`, `failureCluster`

### Week 3 — Policy integration (eval-only, no Android yet)

- [ ] `v1_policy_merge.jsonl` — PolicyEngine expected output cases
- [ ] Document AiConfidenceGate thresholds (escalate cheap → strong < 0.85)
- [ ] Azure Functions `/classify` prototype spec (consumption, v04, cheap default)
- [ ] Application Insights: token/cost/latency per deployment

### Week 4 — Android handoff prep

- [ ] Freeze `classifier_v04.txt` as `PROMPT_VERSION=v04`
- [ ] Export decision rubric one-pager for PolicyEngine implementer
- [ ] Quota request: gpt-5.4 + Claude Opus (premium ambiguous set only)
- [ ] Budget alerts: $1.5k / $1.8k Azure credit

### Explicitly not in 30 days

- Full phone operator agent  
- Custom model training  
- PTU / GPU VMs  
- Deleting pc-lab-* deployments  

---

## Architecture decision record (updated)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Default prompt | **classifier_v04** | 94.7% v1, 0 FA on cheap |
| Default deployment | **pc-lab-cheap** (gpt-4.1-mini) | Best cost/safety after v04 |
| Escalation deployment | pc-lab-strong (gpt-4.1) | Ambiguous cases, low confidence |
| Vision deployment | pc-lab-vision (gpt-4o) | Hold until screenshot evals |
| Premium tier | gpt-5.4 / Claude Opus | Quota blocked |
| Hosting | Azure Functions consumption | min=0 |
| Policy location | On-device | Cloud advises only |
| Fine-tune | Deferred | < 500 cases |
| v03 prompt | Retired | v1 false allows |

---

## Azure role (concise)

Azure accelerates the **advisor brain** — not overlay, not OCR, not Play compliance.

| Service | Use |
|---------|-----|
| Azure OpenAI (3 tiers) | Eval + `/classify` prototype |
| Blob (private) | Screenshot fixtures |
| Functions | Hosted classify |
| App Insights | Cost/latency truth |

Spend credits on **inference + eval burn**, not VMs.

---

## What we learned today (scientific)

1. **v0_challenge is insufficient** — 88% masked v1 failures.  
2. **Cheap model is not safe with v03 on v1** — 2 false allows on shelf/monk.  
3. **Strong model fixes guardrail false blocks; prompt fixes shelf false allows.**  
4. **Vision is useless without screenshots** — identical text-only accuracy to strong.  
5. **v04 prompt > bigger model** for safety on cheap tier (+10.5pp, 0 FA).

---

*Related: `evals/reports/v1_model_comparison_summary.md`, `evals/reports/classifier_v04_recommendation.md`, `evals/reports/v1_edge_cases_design.md`, `docs/azure-research-plan.md`*
