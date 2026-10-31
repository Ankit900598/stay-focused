# The Decisive Answer: Delay-Locked Commitment + Relay-Based Safe Access

One committed architecture, not a menu. This replaces the "which OS tier should we use" framing from the earlier option-matrix report with a different question entirely, reasoned from evidence rather than from comparing platform APIs.

## Independent review correction — 2026-10-11

Treat this document as a hypothesis and research direction, not a validated product decision. The earlier draft overstated both Android enforcement capability and behavioral-science evidence:

- An Android `AccessibilityService` stops when the user turns it off in Settings; Strlix cannot guarantee a countdown or keep enforcement active after that OS-level action. A revocation delay can govern only exit/weakening actions initiated inside Strlix while its enforcement service is alive. It must never be marketed as tamper-proof. ([Android `AccessibilityService` lifecycle](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService))
- Evidence does not establish that cravings generally pass within 15–30 minutes or that a delay of that length will prevent commitment exits. Urge surfing is a coping technique, not a reliable craving timer; a randomized smoking study found lower cigarette use at follow-up but no significant difference in measured urges, and systematic reviews describe the evidence as uncertain/low quality. ([Bowen & Marlatt study](https://pubmed.ncbi.nlm.nih.gov/20025372/), [systematic review and meta-analysis](https://pmc.ncbi.nlm.nih.gov/articles/PMC10583418/))
- The FTC cooling-off rule is an analogy for reconsideration, not evidence that delaying a user's own commitment exit is effective or equivalent.
- Emergency access, turning off Accessibility, uninstalling Strlix, and other OS-controlled actions must not be described as delayed or reliably interceptable. The app should clearly report when protection is no longer active.

Consequently, no fixed delay duration or default is endorsed here. Any in-app revocation friction must be voluntary, visible, cancellable by choosing to remain committed, immediately bypassable for safety, and evaluated against user harm and disengagement as well as early-exit rates.

## Start from the actual failure mode, not the platform feature list

Every enforcement tier researched earlier (Accessibility, Screen Time, Device Owner) shares one property: **the OS is designed to let the device's own user turn it off.** Android's Accessibility toggle, Apple's Screen Time PIN/biometric disable, even Device Owner's factory-reset escape hatch — these aren't bugs or gaps to engineer around. They are deliberate platform guarantees, stated in official documentation, that exist so a device can never become unrecoverable to its owner. Chasing a "stronger lock" inside that constraint is optimizing the wrong variable: **no matter how strong the technical block is, the user can always reach the off-switch. The only question that matters is what happens between the moment they decide to reach for it and the moment it actually takes effect.**

This reframes the engineering problem from *"how do we make the block harder to defeat"* to *"how do we make the moment of defeat cost more than the craving that's driving it."* That second question has an actual scientific answer, and it doesn't require a factory reset, Device Owner enrollment, or a fight with Play policy.

## The evidence

**Urge surfing is a coping technique, not a clock.** It teaches noticing an urge without automatically acting on it. The evidence cited here does not establish a universal 15–30-minute craving duration, nor show that an app-enforced waiting period will reliably reduce social-media or pornography use. In one randomized study of college smokers, the intervention group reported fewer cigarettes at seven-day follow-up, but measured urges did not differ significantly between groups. A later review/meta-analysis found substantial inconsistency and low-quality evidence for craving reduction. Those findings justify testing a user-chosen pause as a product hypothesis; they do not justify claiming that an urge will pass on schedule. ([Bowen & Marlatt study](https://pubmed.ncbi.nlm.nih.gov/20025372/), [systematic review and meta-analysis](https://pmc.ncbi.nlm.nih.gov/articles/PMC10583418/).)

**A cooling-off period is an analogy, not proof.** The FTC rule gives consumers cancellation rights for certain covered transactions; it does not establish that restricting a user's exit from a self-imposed digital commitment improves outcomes. The analogy may inspire an experiment, but the product mechanism, consent model, safety exceptions, and evidence must be evaluated independently. ([FTC, *Cooling-Off Rule*](https://www.ftc.gov/legal-library/browse/rules/cooling-period-sales-made-home-or-other-locations).)

**Conclusion, stated as a testable hypothesis:** *An optional, transparent in-app pause before weakening an active commitment may help some users reconsider an impulsive exit, at lower engineering cost than device-management modes.* It cannot stop OS-level permission removal or uninstall, and no evidence yet shows that it outperforms other approaches. The hypothesis must be measured, not assumed.

## The architecture: Revocation Delay Law, not a stronger lock

**Core mechanism — one new law, layered on top of everything Strlix already has, replacing nothing:**

1. Blocking itself stays exactly as it is today (`CommitmentPolicyEvaluator`, `ShortFormQuotaGate`, `ScopeGate` — all unchanged, per this codebase's existing design). This proposal does not touch enforcement *logic*.
2. **Limit the mechanism to Strlix-controlled actions while enforcement is alive** — for example, ending a session through Strlix or lowering a rule in Strlix. The Android Accessibility setting is OS-controlled: if the user switches it off, the service stops, and Strlix cannot guarantee that its countdown or overlay remains active. On re-entry, Home must truthfully say protection is off or needs setup. Do not disguise an OS-level disable as a revocation countdown.
3. **Keep the safety path explicit and immediate.** A visible emergency exit must not require AI classification, an explanation, an accountability contact, or waiting. Separately, the user can always revoke Accessibility in Android Settings; Strlix must not claim it can intercept or delay that action.
4. **Do not infer delay duration from commitment length.** A one-year commitment does not scientifically imply a longer cooldown. Delay length and default behavior remain open product questions; test user-selected, short pauses with an immediate no-delay safety exit before considering stronger friction.
5. **If an in-app delay is tested, make it truthful and reversible.** Show its countdown and effect clearly; provide an immediate action to cancel the exit and stay committed; never apply penalties, share data, or notify another person unless the user pre-consented to that exact consequence.

**Why this beats chasing a stronger OS tier, concretely:**
- An in-app delay requires **no factory reset or Device Owner enrollment** and need not add an Android permission. It does **not** remove the existing Accessibility distribution/policy risk or keep enforcement alive after the user disables the service.
- The product concept can be shared across Android and iOS, but detection, enforcement continuity, and bypass behavior are platform-specific and must be designed and tested separately.
- It scales cleanly from a two-hour session to a one-year Life Rule using one mechanism, not a tier ladder — the "how do we make a one-year commitment stronger without demanding a device reset" question from the earlier report is answered directly: **the delay window is the strength**, not the device's management mode.
- It directly implements this repo's own stated principle — *"commitment mechanisms, not traps"* — better than a technical lock does, because a technical lock's only options are "unbreakable" (risk of becoming an actual trap if something goes wrong) or "breakable" (no real strength); a delay is neither — it is always escapable, on a known clock, truthfully displayed, which is precisely the humane middle this product has been reaching for across every doc read this session.

## The second half: invert the safe-access problem instead of chasing closed APIs

The earlier connector research found a hard structural fact: **WhatsApp, Instagram, Facebook Messenger, and LinkedIn have no official API for a personal account's own messages** — this is a permanent policy position of those platforms (verified against their own current developer documentation), not a gap Strlix can engineer around without violating terms of service, which the product must never do. Building "one connector per app" against that reality means building toward permanently closed doors for four of five platforms researched. That is the wrong shape of solution.

**The different question: instead of "how does Strlix reach into the blocked app," ask "how does the user redirect what matters to a channel Strlix never needs special access to."** Every one of these platforms already lets a user forward, notify-elsewhere, or export a specific conversation *by their own action, once* — this is not a new integration, it's the user doing what they already can do, and Strlix's job shrinks to: receive it, triage it, draft a response, and hand it back through the *same* native share/send flow, with the human always in the loop.

**Concrete design, one unified path, not five per-app integrations:**
1. **One relay identity per user** — an email address (or a single Gmail-connector inbox, since Gmail is the one platform in this matrix with a real, if CASA-gated, official API) that the user can forward or CC anything important into, once, at the moment it arrives, from *any* platform's own native "forward" action.
2. **Strlix's AI triage runs only on what lands in that one relay** — never on a standing, always-on read scope into any of the closed platforms. This collapses the entire "which platform's API do we integrate with" question into one already-solved case (Gmail), and makes every other platform's unavailability a non-issue, because the user — not Strlix — is the one crossing each platform's boundary, using each platform's own sanctioned UI, exactly once per important item.
3. **Draft-and-send always ends back through the same human-confirmed loop already designed in the earlier report** — nothing here changes that; it only changes *how the content arrives* at the triage step, not what happens after.

**Why this is the right inversion, not just a workaround:** it turns four "permanently unavailable" rows in the earlier capability matrix into "not needed," rather than leaving them as open technical debt Strlix keeps hoping some future partnership will resolve. It needs no OAuth scope beyond what Gmail already required, no partner-program approval, and no dependency on any platform's future policy choices.

## What this replaces, and what it doesn't

**Replaces:** the earlier "which enforcement tier" decision as the primary lever for making long commitments harder to break, and the earlier "one connector per platform" plan as the primary lever for safe access.

**Does not replace, and must not be read as removing:** the underlying blocking logic itself (`CommitmentPolicyEvaluator` and everything downstream), the typed-scope work, the existing mistake-window/cooldown numbers in `PRODUCT_MEMORY.md` (this proposal generalizes them, not discards them), the emergency-allow path, or the Gmail connector's own OAuth/CASA plan from the earlier report (it becomes *more* central here, not less).

**Genuinely optional, not ruled out, just no longer load-bearing:** the Device-Owner "dedicated commitment device" tier from the earlier report can still exist as a niche opt-in for users who specifically want it — but it is no longer the answer to "how do we make a one-year commitment strong," because the Revocation Delay Law already answers that question without it.

## Falsifiable exit criteria (a scientist commits to a test, not just a theory)

- **Prediction 1:** among testers given a short (10-20 minute) revocation delay on a Strict-mode session, the majority of early-exit *attempts* will not be followed through to completion once the delay expires — i.e., most people who reach for the off-switch mid-craving will let the countdown run out without confirming the exit, because the craving passed. If this is **false** (most people simply wait out the delay and exit anyway), the core hypothesis is wrong and the mechanism needs redesign, not a longer delay.
- **Prediction 2:** testers using the Gmail-relay safe-access path will report needing to unblock a social app "to just check something" measurably less often than a matched group without it. If this is **false**, the relay pattern isn't reducing the actual felt need, and the connector work should be reconsidered before expanding it to more platforms.
- **Prediction 3:** the delay mechanism will require materially less engineering effort than any Device-Owner-tier work, measurable in files touched and new permissions requested. This is a build-cost check, not a behavioral one, but it should hold given §"why this beats chasing a stronger OS tier."

These predictions are not yet evidence. A small closed pilot can test usability, but cannot establish general effectiveness. Track successful exits, exits abandoned, permission revocations, user-reported distress, false lockouts, and safety-exit use; publish limitations and stop the experiment if users report feeling trapped. If the delay does not help, do not simply make it longer.
