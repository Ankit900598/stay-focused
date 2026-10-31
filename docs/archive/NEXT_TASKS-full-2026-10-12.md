# Next Tasks

Start with `Current Next Steps`; dated snapshots below are historical unless explicitly marked current. Continue the full phone audit rather than treating one passing slice as completion.

## Current Next Steps — 2026-10-12

- **Latest source-audit fix — not phone-verified:** saved duration fallback now reaches offline parsing and hosted previews; explicit session time wins, content/video length stays a separate clock, and a server-marked default no longer leaves stale one-hour copy. Full suite is **1,147/1,147**; `:app:assemblePhoneTest` passes. APK SHA-256: `86A1E881F3F39913EEBFB4A808E40B10432B320FF4AE314005307C5C97C462AD`; embedded shared key and Google ID are empty, URL is loopback, and configured local key had 0 APK matches. The Xiaomi remains uninstalled with Accessibility off; lint was stopped after several CPU-minutes without output. Do not sideload or bypass Play Protect; resume real-device testing only through the owner-authorized Play path.
- **Separate Advanced Controls contract still needs a decision:** `ConfirmedPromiseBinder` derives lock attempts from inferred strictness and hardcodes a 2-minute cooldown, while the settings store exposes editable lock-threshold and cooldown values. Do not claim those settings affect a new promise until the intended precedence is made explicit and tested.
- **Late classifier response after Accessibility teardown:** in-flight text/vision results now fail freshness validation after the service clears disclosure consent, preventing stale counsel from incrementing attempts or locking a session. Covered by `CommitmentPolicyLawTest`; phone validation remains blocked by the same install gate.
- **P0: settle general API authentication before any release:** release artifacts must not contain the shared backend key, but core Android clients currently send only that key and hosted POST routes still require it. The Google-ID-token path is mail-only. Keep paid routes closed to anonymous callers. Research recommends Firebase Authentication ID tokens (anonymous-first, optionally linked to Google) for user identity; Play Integrity can only supplement as app/device anti-abuse. See `docs/release-api-auth-adr-2026-10-12.md`. No Firebase project/configuration was created. This is a release architecture gate, not an Android phone-test retry.
- **P0: resolve Play Accessibility automation eligibility before submission:** current code lets a live classifier BLOCK after matching a confirmed user `AI_DECIDE` or content rule. The disclosure and static-policy gates are present in source, but Google's current policy distinguishes static human-defined automation from autonomous decision/action behavior; this flow's eligibility is unresolved. Do not set `isAccessibilityTool=true`, submit, or claim approval without a policy review. Exact source trace and official references are in `docs/phone-observation-errors-2026-10-11.md` under “Play Accessibility automation rule checked against current classifier path.”
- **Privacy gate added — locally verified, not phone-tested:** Accessibility consent is now versioned and synchronously persisted before opening Android Settings; direct system-settings activation without a current in-app acceptance self-disables and cannot inspect/classify/enforce. The **1,144** Android tests, both phoneTest APK and Android-test APK builds, and **17/17** fake-provider backend smokes pass; current lint passes with **0 errors, 98 warnings, 2 hints** (the durable consent commit's `UseKtx` advisory is intentional). The updated Xiaomi has no Strlix package, so this behavior is not device-verified. This closes a consent-bypass gap, but does **not** decide Google's separate policy question about live AI-triggered Accessibility blocks.
- **Gmail assistant release-auth fix — verified locally, not a release approval:** the two exact mail POST endpoints now rely on verified Google identity plus the existing user rate limit instead of requiring a mobile-embedded app secret. All **17/17 backend smoke scripts** pass. Before public use, complete OAuth verification for restricted `gmail.readonly` and sensitive `gmail.send`; because this implementation transmits Gmail data through the Strlix/Azure server path, plan for the restricted-scope security assessment and confirm any exception with Google. Publish accurate privacy/Data Safety disclosures and verify the configured Azure retention/abuse-monitoring setting. The code requires `STRLIX_MAIL_ASSIST_ENABLED=1`, but the live Azure flag was not checked; no live account or model call was made.
- **Latest verified safety boundary — 2026-10-12:** the v09 `surface=study` / `contentType=study` rule has no corresponding reliable Android classifier, so Start stays closed for structured study-only promises rather than offering a false guarantee. The mapped backend DTO path is covered. The current source also contains the versioned Accessibility-consent gate; the full Android suite passes **1,144/1,144**, and the current `:app:assemblePhoneTest` artifact is SHA-256 `609DBA26577643CD3C0001B80286976F9667E200C1AB9CFC05B77D4DAB9AEDFB`, v2 signed with empty shared-key config and loopback URL. Final `:app:lintPhoneTest` passes with **0 errors, 98 warnings, 2 hints**. **Not phone-verified:** the Xiaomi has no Strlix package, Accessibility binding, or ADB reverse. Before Play distribution, resolve whether live model classification that triggers BLOCK under confirmed `AI_DECIDE` rules fits Google's Accessibility automation policy; the disclosure-consent fix is not Play approval. The repo also lacks a privacy-policy/Data Safety artifact or in-app link; establish actual provider/data-retention wording and the public policy URL before submission. The merged manifest's special-use heartbeat declaration and inherited biometric permissions also need review; voice and Usage Access are not phone-tested. Do not claim `isAccessibilityTool` eligibility or change runtime behavior without a product/policy decision. The read-only Console page remains new-account signup with an irreversible owner/type choice; official Internal App Sharing still requires that account/app setup. Next: complete policy/privacy and account-owner gates, then run the full real-device matrix after authorized installation. Do not retry sideload/ADB or bypass Play Protect.

- **Previous collection-target checkpoint — superseded by the study-category refusal above:** explicit playlist/channel/creator/series structured surfaces fail closed, including Hinglish. Its unresolved concern remains: a named source omitted from typed compiler output cannot be safely enforced from generic study summaries.

- **Earlier preview/start checkpoint — superseded by later study-only and consent fixes:** unsupported study/playlist targets fail closed; generic “YouTube lectures” and Hinglish session-duration wording avoid the earlier false positive. At this checkpoint, **1,136 Android unit tests** and **17 backend smoke scripts** passed with fake providers/local loopback only. APK `0D746B08310E4BB9CC75F4ED61EDED609924954C48CE843EA2F94398F77997AF` and its 6m16s lint report are historical, not the current candidate. Xiaomi has no Strlix package or Accessibility binding; no behavior is phone-verified. The current artifact and exact install gate are at the top of this file. Never bypass Play Protect or sideload with ADB.

- **Confirmed classifier authority and surface matching fixed locally; still no phone proof:** text and vision BLOCKs now require a matching confirmed content rule or visible app `AI_DECIDE` rule; app-label rules are package-bound, narrow content rules suppress broader fallthrough, and classifier LOCK is WARN-only. A red regression proved the gate ignored `ContentRule.surface` and could let a rule declared for `feed` block a Shorts player. Declared surface mismatch/unknown values now downgrade to WARN; matching Shorts/feed/message surfaces remain covered by controls. **1,133 Android unit tests** pass; `:app:assemblePhoneTest` succeeds. Checkpoint APK SHA-256: `D61F3EE7B869B804F1BDE076150F7CEA5F1F4FACA881C568870E9EACCEC4B446`; signature verifies; phoneTest config has empty app secrets and loopback URL. `:app:lintPhoneTest` remains unverified. Xiaomi has no app package or PhoneCodex Accessibility binding; normal Files install was previously blocked by Play Protect. Do not retry or bypass. No Azure inference/deployment. The preview/start audit is now fixed locally as recorded in the latest update above; then continue the on-device matrix only after authorized Play setup, policy review, and install. Earlier raw HTTP probing is not an app compiler request.

- **Credential containment after build diagnostic exposure:** a generated Debug `BuildConfig` matched the live Azure `STRLIX_BACKEND_APP_SECRET`. Using the earlier explicit approval, the hosted app-auth setting was rotated and only ignored `local.properties` was updated; the values were compared without displaying them, and Azure `/health` returned HTTP 200. No inference or deployment occurred. The exact current `phoneTest` APK has an empty shared key and no match for the current local key. Do not use stale Debug outputs (they carry the invalidated credential) or ship any mobile client with a global shared secret; proper user-bound authentication remains a release prerequisite.

- **Paid calls are opt-in for the local phone-test backend:** plain `node backend/start-phone-test.js` now answers `/health` but returns 503 `paid_routes_disabled` on model routes. Only after explicit approval for a bounded inference test, launch that temporary local process with `STRLIX_PHONE_TEST_PAID_CALLS=1`, keep `adb reverse` only during the test, and stop both afterward. This is not hosted Azure configuration and does not solve production user-bound authentication. Offline smoke covers both default and opted-in HTTP status paths without making a model call.

- **Play ownership and permanent identity are still human decisions:** read-only Play Console access with the current signed-in account still lands on developer-account signup. The latest APK is a v2-signed debuggable internal-test candidate with package `com.phonecodex.app` and version code 1, not a Play-approved install. Ankit must confirm whether that legacy PhoneCodex package ID should permanently represent Strlix, choose the developer-account owner and personal/organization type, and complete the official setup before any upload. Do not create the account or upload on his behalf while he is studying.

- **Historical classifier-contract checkpoint (superseded):** `phoneTest` SHA-256 `2AE598EDABF6FE6DD6A0157A6F8538CA19F3B334D46455F14C91F67510004817` passed 1,102 Android tests at that checkpoint. The current artifact hash and verification state are in the first item above; neither is installed or phone-enforcement verified.

- **Phone transport gate now has real-device evidence:** with a temporary paid-disabled local backend, Xiaomi returned HTTP 200 for `/health` and HTTP 503 `paid_routes_disabled` for an empty `/classify` over `adb reverse`; the mapping/server were removed after capture. Do not repeat transport probing as a substitute for the blocked app-install gate. Next physical test still requires policy-reviewed official Play distribution, app disclosure/Accessibility setup, an exact user-confirmed promise preview, and same-session cross-app decisions. No live model call or installed-app request has been proven.

- **Local backend auth parity verified, phone still gated:** the no-key `phoneTest` APK is intended only for the loopback launcher, not hosted Azure. The HTTP smoke proves `/health` works without an embedded key, the default paid-route kill switch returns 503 before inference, explicit test opt-in reaches malformed-request validation 400, and hosted launcher use is rejected. Do not interpret this as a real-phone compile or leave an auth-open test server exposed beyond a controlled local session; the Xiaomi package is absent until the official Play route succeeds.

- **Historical screen-identity build:** `C5E14DD3846C10B7BF7064067D6418385BE36A5D488382F4D71517117CA1E760` passed 1,100 Android tests then, but is superseded by the latest APK above. It was not installed on Xiaomi.

- **Historical phone-test artifact after stale-classifier gate fix:** SHA-256 `16DE3C290FFDFAB3C0FCC3ABBB057FC7B47F9BBF013F1BD7F6C277BE5169E007` passed the 1,098-test suite then, but is superseded by the current artifact above. It was not installed or phone-verified.

- **Historical P0 classifier-authority finding — local mitigation added, Play review still open:** text and vision results now pass through `ConfirmedClassifierActionGate` after current-law revalidation; only confirmed visible app/content rules can authorize a model block, and the model cannot select LOCK. This is local code/test evidence, not phone or policy approval. Review residual category/scope semantics and Google's current Accessibility eligibility before distribution; do not assume approval or rejection. Review the non-accessibility-tool declaration, in-app disclosure, data safety, special-use FGS declaration, and demo video before any Play submission. Full evidence: `docs/phone-observation-errors-2026-10-11.md`.

- **Unsupported social-surface boundary:** `SOCIAL_MESSAGING_ONLY` with WhatsApp or `SOCIAL_ANY` now refuses Start with an honest message because default-safe WhatsApp would otherwise escape restriction while messages remain exempt. This is a safety gate, not the finished feature. Design and phone-test a deterministic, evidence-aware non-message action before enabling that scope; do not substitute a whole-app block. The Instagram-only path is not certified by this change.

- **Local privacy patch not deployed:** backend error/request logs no longer emit arbitrary error metadata, malformed URL paths, or caller-supplied speech-language text; canary regressions pass locally. Review this patch with the dirty backend batch before any hosted deployment, then verify hosted build ID and sanitized logs without using real private content. The phone install/consent gate is unchanged.

- **Phone-test backend parity:** `backend/start-phone-test.js` now pins compiler v09 after a real local `/health` exposed a v07 mismatch with hosted. Recheck both local and hosted `/health` before a Play-installed phone test; do not infer model quality from configuration parity. The local server and ADB reverse bridge were removed after this read-only check.

- **Credential-free test artifact is ready; Play account is not:** the current `phoneTest` artifact and hash are listed above. It embeds no app key and uses loopback, but that does not unblock installation. The signed-in Google account reaches Play Console signup. Ankit must choose the owning account/type and confirm permanent package ID `com.phonecodex.app`; then review policy and upload only a freshly verified no-key build via the official Play route. Do not install through Files/ADB as a workaround or upload the normal debug APK. Runbook: `docs/phone-test-play-distribution.md`.

- **Immediate phone gate — stop repeating Files installs:** the current APK `827DA6162D1A01AB1C4D2971AE3ABEFCF9324066175419147760E8DC015745AB` matched its on-device hash. Play Protect is on, completed a rescan with “No harmful apps found,” and then explicitly blocked the APK opened from Xiaomi Files with “App blocked to protect your device” / sensitive-data warning. The earlier VerifyApps timeout was incomplete evidence; this is the completed verdict. The app is not installed and PhoneCodex Accessibility is off. Google's [Play Protect guidance](https://developers.google.com/android/play-protect/warning-dev-guidance) identifies internet-sideloaded Accessibility apps as an automatically blocked class in select markets. Do not disable protection, bypass the warning, or keep retrying Files. Next: review Accessibility/disclosure and least privilege; produce a **credential-free** test artifact (the current debug APK embeds a reusable backend key); then use an authorized [Play internal test](https://support.google.com/googleplay/android-developer/answer/9845334) or [internal app sharing](https://support.google.com/googleplay/android-developer/answer/9844679) route, subject to account/policy requirements. An [appeal](https://support.google.com/googleplay/android-developer/answer/2992033) is available if the compliant app is blocked in error. Neither upload nor appeal has been done. After a permitted install, inspect the disclosure, enable Accessibility through Android Settings, verify binding and Home status, and review each exact promise preview before enforcement. The full phone matrix remains open.
- **Play Console setup is a human gate:** a read-only visit to `play.google.com/console/` with the currently signed-in Google account redirected to **“Creating a developer account.”** No developer account was created or app uploaded; another Google account may have access, but was not checked. Before any Play test upload, Ankit must choose/verify the correct account and personal-versus-organization ownership and confirm that `com.phonecodex.app` is the intended permanent package ID. Google's [internal test guidance](https://support.google.com/googleplay/android-developer/answer/9845334) says the package name is fixed after first artifact upload. Do not automate that irreversible registration choice.
- **Regression update:** all **15/15 backend smoke scripts** now pass offline, including fake-error log-privacy checks; model routing is confirmed as Astra/Terra/Astra and no provider calls were made. The next product proof is still on the Xiaomi, not another duplicate local-only check.
- **Hosted runtime snapshot:** public `/health` confirms `promise_compiler_v09`, build ID `3aec067-wt-36df4f03c3da`, compiler `pc-lab-astra`, classifier `pc-lab-terra`, and vision `pc-lab-astra`; this is not proof of a real compile or phone behavior. Keep the real-phone matrix open.
- **Model decision, not yet implementation:** GPT-6.1 Sol is deployed in `phonecodex-ai-dev` (East US 2), but App Service uses a different endpoint/resource and currently routes Promise Compiler to `pc-lab-astra` (`gpt-6-astra`), classifier to `pc-lab-terra`, and default chat/classifier fallback to `pc-lab-cheap`. Do not claim 6.1 Sol is active or change the route blindly. First run a controlled eval/latency/cost comparison with existing provider safeguards, then explicitly approve any live routing change.
- **Current artifact verification:** latest debug APK hash is `827DA6162D1A01AB1C4D2971AE3ABEFCF9324066175419147760E8DC015745AB`; its focused `DebugStateStoreTest` is **7/7**, and both APK assembly tasks succeeded. The **1,084 unit tests** and **18/18** emulator instrumentation result were for earlier APK `5F51CF639348EA7E7737FBA0D3816D96CFD5EFDB5F2A8E8550691D42C83E1868`, not the exact latest artifact. `lintDebug` remains unresolved. The privacy fix keeps decision screen text/reasons transient for at most 10 minutes; private feedback text requires opt-in and can be cleared. No screenshot or emulator result proves Xiaomi behavior. The latest APK embeds a reusable shared backend key and stays local-only.
- **Earlier voice-lifecycle audit (2026-10-12; superseded by the current artifact details above):** `ON_STOP` cancels recording/transcription and invalidates late transcript results; lifecycle observer tests were added. That build passed 1,073 unit tests, 17/17 emulator instrumentation tests, lint, APK assembly, and offline speech smoke. Its hash was `3BEA44B05F40F8497DD76028F3BC1264E6C8E44B1F1B9A964B631B99F9C7202D`; it is not the current artifact. Xiaomi was not installed, and voice locale accuracy, physical-phone mic lifecycle, and core enforcement remained unverified.

**Earlier verification (2026-10-12, after credential rotation; superseded by the immediate gate above):** the saved whole-app-block vs `APP_SHORT_FORM_ONLY` preview/start mismatch remained fail-closed; the large-text confirmation flow passed its dark-theme 1.5× AVD test for acknowledgement gating, scrolling to Edit/Start, and explicit Start. That snapshot passed 1,071 unit tests, 17/17 AVD instrumentation tests, lint, and both APK assemblies; it used APK `28C417BCD39F062A3904B5F2FF36D8F655309EF6098ABA81082D97F7EA156244`, not the current build. The previous app-wide Azure credential was rotated under approval; ignored `local.properties`, generated `BuildConfig`, and Azure app setting matched, and public `/health` recovered. No paid inference or `.env` edit occurred. Xiaomi had no Strlix package/service and real-device enforcement was unverified. Keep the current gate and evidence log at `docs/phone-observation-errors-2026-10-11.md`; install only after approval for the exact current APK, then restore disclosed Accessibility consent and test one reviewed promise at a time.

**Earlier verification (2026-10-12 01:19 IST):** four targeted defects/copy issues are fixed: recommendation clocks cannot drive active-player-duration decisions; safe-app and ordinary ALLOW explanations do not invent a promise; Last Decision cannot borrow a different session's current draft; and Home labels permanent guardrails as safety rules, not timed commitments. Decision diagnostics store only session identity, never promise text; legacy unproven snapshots are cleared once. Full Android suite: **1,051 tests, 0 failures/errors/skips**; `lintDebug`, `assembleDebug`, and exact-current-source isolated-emulator UI tests (**7/7**) pass. Local and pulled Xiaomi APK SHA-256 match: `094B9E3E2FE7DB5BE6798CB5EE6F9D0B7239C0701F4F3F572790EBEAB546C89A`. Phone is restored to Home with no timed commitment/session/app rules; Accessibility is enabled and bound, no service is crashed. One post-install “Not protecting” frame recovered within ~12 seconds; passive 35-second observation remained Protected. This was an idle-state health/UI check, not a live enforcement or all-app/edge-case pass. The 14 backend smokes passed offline at 00:18. No Azure inference/deployment/settings change occurred; GPT-6.1 Sol is not verified as the live route.

The current live gate is service availability and real-device reliability, not a new browser feature. Keep the separate browser project isolated. The newly verified backend paid-operation cap is an in-memory logical-operation counter: it resets on process restart/UTC rollover, is not shared across instances, and is not a dollar-exact Azure spend limit. Do not present it as a hard financial ceiling or use it as the only production budget control.

1. **Azure state:** before rotation, read-only `GET /health` at 00:20 IST reported hosted=true, build `3aec067-wt-36df4f03c3da`, Promise Compiler `promise_compiler_v09` / `pc-lab-astra`, classifier `v04` / `pc-lab-terra`, Speech configured in East US 2, vision disabled, and app-secret auth required. After authorized rotation of `STRLIX_BACKEND_APP_SECRET`, a read-back matched the new value to ignored `local.properties` and generated `BuildConfig`; public `/health` returned success. This was one setting change, not a deployment or inference call. The 2026-10-11 portal record lists the separate Foundry model deployments; it does not prove which model handled a response. GPT-6.1 Sol was listed in separate resource `phonecodex-ai-dev`, not the recorded backend endpoint. No further Azure changes without explicit approval; paid inference still requires fresh approval.
2. **P0 — shared-key auth still blocks external tester distribution:** rotation invalidated the prior exposed app credential, but the current debug APK still embeds a new nonempty `BACKEND_APP_SECRET` in DEX. `BackendConfig` sends it as `X-Strlix-App-Secret`; `backend/server.js` applies shared-key auth globally, so the APK remains extractable authorization, not user identity. Do not distribute this artifact to testers. `backend/googleIdentity.js` already verifies Google ID tokens, but current route wiring uses that identity for mail-assist only; core promise/classifier routes still rely on the shared app key. Migrate core routes to identity-bound or short-lived scoped authorization, bind quotas/revocation to the authenticated user, then add tests and scan the release artifact for reusable credentials. The existing in-memory logical paid-call cap is not a dollar-exact budget or a substitute for auth.
3. **P0 — complete the current-build detector phone gate:** the player-zone fallback and exact max-duration boundary fixes are in the local source/build and have unit/replay coverage; saved whole-app BLOCK conflicts with Shorts-only typed scope now keep Start off unless the saved rule will be overridden by the confirmed plan. The current Xiaomi does not have Strlix installed, so do not call these fixes phone-verified. Regression tests assert recommendation-only spoken durations WAIT under both 30-minute minimum and 40-minute maximum policies, with 40:00/40:01 traces for Chrome, YouTube, and NewPipe. After normal installation and Accessibility setup, review the exact preview and confirm before Start; make no paid Azure compiler call without fresh approval. Then capture same-session decisions for current-player vs recommendation-only duration, passive Home/Search/Comments, blocked/allowed players, Chrome page/video, overlay clear, expiry, and restored rules. Capture with `Capture-StrlixDiag.ps1` and `Capture-StrlixDecisionTrace.ps1`; the latter is PID-scoped and sanitized, but has only been fixture-tested so far.
4. **Finish the mail connector safely:** the live App Service has no `STRLIX_MAIL_ASSIST_ENABLED` setting (server default is off) and no `STRLIX_GOOGLE_WEB_CLIENT_IDS` audience setting, so production mail-assist routes are not enabled/configured. Keep them that way until the Google OAuth web client and required consent/security review are complete. Do not read/send real mail until the user explicitly connects an account and grants the exact scopes. The consent UI discloses Azure's default flagged-content abuse-monitoring behavior, but the actual Azure resource setting is unverified. A disconnect race was just fixed so local access is cut off before remote revocation waits; this is code/test-verified only. The connector remains a prototype, unconfigured, uncommitted, and not launch-ready.
5. **Review the dirty tree by track:** current branch `checkpoint/2026-09-27`, base `3aec067`, with substantial modified/untracked WIP. Preserve all WIP, finish reviewing each coherent batch, and never stage the whole tree or mix the browser project into this repo.
6. **Product gate:** Strlix remains a personal commitment OS (natural-language promise → confirmed structured policy → deterministic enforcement → honest feedback), not a generic blocker. Expand supported apps/scopes only after representative device tests measure false allows, false blocks, clarification quality, latency, and safe-path behavior.

**Separate browser track:** the accessible `strlix browser` task/worktree has design discussion only, not an implementation; the OneDrive “strlix _” path is an empty non-bare Git worktree with no commits. Keep browser work isolated, and verify the real code repository/path before discussing merge or integration.

## Product Horizon — Keep the Whole Goal Visible

- **Core runtime (first):** make promise → confirmation → deterministic enforcement → honest feedback reliable on real Android devices. The 2026-10-11 hosted-v09 run passed its stated Shorts/Home/Chrome/expiry outcomes; the 17:04 lecture was classified as `COMMENTS`, so it proves only passive-surface allow, not media-length enforcement. Local duration logic has replay/unit coverage, including the exact max boundary across Chrome, YouTube, and NewPipe, but the current Xiaomi lacks the app and has Accessibility off. Do not call those paths phone-verified until a current APK is installed, a fresh capture shows the player clock and `CURRENT_PLAYER` decision, and the full reviewed preview is confirmed. Preserve the permanent life rule. The PiP path remains constrained by missing package-attributed playback control.
- **Daily insight UI slice:** the new home card can optionally read Android's `UsageStatsManager` after the user explicitly opens Usage Access settings. It shows only an aggregate for today, processed locally and never sent to Azure; automated tests cover permission denial, local-day boundaries, revocation races, and API failures. Three Compose instrumentation UI tests pass on a local API 35 emulator, not on the Xiaomi phone. It does not invent a streak, block count, or multiple active promises. Verify the permission-denied and permission-granted states on the phone after the P0 gate and when the device is available. Review the exact data disclosure and distribution requirements before a public release; this is not a replacement for the core reliability gate.
- **Conversational operator (after the P0 runtime gate):** the home now has two explicit voice-input paths: Android's selected recognizer and opt-in Azure transcription through the existing backend. This is voice-to-promise text only, not a live assistant or general phone operator; real-phone permission, recording, hosted request, transcript edit, and no-auto-start behavior still need verification. Then broaden toward natural-language/voice conversations while keeping every proposed action previewed, confirmed, and executed through deterministic, least-privilege tools. The current Gmail helper is only a constrained prototype.
- **Blocked UI, not blocked life:** Gmail is the first possible official connector, subject to OAuth configuration, Google consent/security review, privacy disclosure, and phone verification. Personal WhatsApp/Instagram/Facebook/LinkedIn access must use permitted user-driven relay/share channels unless those platforms provide approved APIs; no hidden scraping or credential-taking.
- **Commitment strength:** the revocation-delay document is a hypothesis, not an implemented lock. It cannot stop a user disabling Android Accessibility or uninstalling Strlix. Test any in-app delay as opt-in, visible, safely bypassable, and reversible; don't claim tamper-proof protection.
- **Google Play eligibility gate:** unresolved and material. Current Play policy requires declaration and prominent disclosure/consent for non-disability AccessibilityService use and forbids preventing users from disabling/uninstalling the service. The official automation guidance distinguishes deterministic, rule-based behavior following a static human-defined script from prohibited autonomous initiation/planning/execution; this is a useful distinction, not a pre-approval of Strlix. The confirmed-promise enforcement path is closer to the deterministic example; a general AI phone/browser operator that plans and executes actions through Accessibility is a materially higher distribution risk. Android API guidance says services should assist users with disabilities. Get policy/legal review for the actual product flow before Play launch, do not mislabel Strlix as a disability tool, and prefer narrower APIs where feasible. A sideload pilot is not evidence of Play eligibility. Sources: [Play Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en), [Play sensitive API policy](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en), [Android AccessibilityService API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).
- **Separate browser:** Ankit is developing the browser in an independent project. It is intentionally not part of this repository or today's dirty tree; review and merge it only later through an explicit integration/security contract.

> **Historical task log:** Everything under the dated 2026-09-27/28 headings below records prior work and must not be treated as today's todo list. It is superseded by **Current Next Steps** above and the live observations in `CURRENT_STATUS.md`; revalidate before reusing any deployment, phone, or release command.

## Highest Priority

### Immediate Release Gate — 2026-09-27

The latest Shorts-only typed-scope case passed on the phone against the local v09 backend; it has **not** been verified against the hosted Azure deployment. Do not claim general or production-ready support from that one YouTube test.

Do these in order before widening the tester release:

1. Review and partition the existing dirty tree into coherent, tested changes. Do not stage the whole tree.
2. The hosted `/health` endpoint already identifies compiler deployment/version: it currently reports `pc-lab-astra` / `promise_compiler_v07` and classifier `pc-lab-terra` / `v04`. Do not mistake a local v09 phone test for hosted v09 behavior.
3. Deploy the reviewed backend change to staging (`phonecodex-backend-staging`; the F1 plan has no slots).
4. Run the same typed-scope phone flow through hosted Azure: confirmation copy, YouTube Home ALLOW, Shorts BLOCK, regular long-player ALLOW, Chrome, and safe/emergency paths.
5. Promote only after logs prove the hosted revision/version and the phone behavior matches the preview. Preserve local v09 evidence and rollback state.

Current proof (2026-09-27 19:25 IST): 884 Android unit tests pass; all 12 backend smoke scripts pass; the phone proved YouTube Home and Shorts behavior against the local v09 backend; hosted `/health` still reports `promise_compiler_v07`. Still unproven: hosted-v09 parity, a true 40-minute lecture, Chrome under this scope, and other social apps.

Update 2026-09-27 night: steps 1–3 done. Commits are on branch `checkpoint/2026-09-27`; `/health` reports `build`; `phonecodex-backend-staging` (same F1 plan) runs `37ac85c` with v09. Next, in order:

1. **Hosted phone flow** against staging: build the APK with `strlix.backendUrl=https://phonecodex-backend-staging.azurewebsites.net` (restore `local.properties` after), `adb install -r`, re-toggle Accessibility (MIUI unbinds on reinstall), and confirm Home says Protected. Promise "Block YouTube Shorts for 1 hour", pick YouTube-only, tick confirm, Start. Record YouTube Home ALLOW, Shorts BLOCK (overlay clears on Back/Home), 40+ min lecture ALLOW (record which stage allowed it), Chrome `m.youtube.com/shorts` and an article, dialer/emergency/SMS/Accessibility settings never blocked, emergency exit, and end commitment restores rules. Do not force-stop Strlix on Xiaomi.
2. **Quota phone re-test** on the new build: pause/resume one Short = one play; the N-th short keeps playing; N+1 blocks.
3. Ask Ankit before promoting to production (set `PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09` + `STRLIX_BUILD_ID`, deploy the same SHA). Then delete `phonecodex-backend-staging`.

Open findings (not fixed):

- A complaint-style promise ("I waste my evenings on shorts") plus a scope pick ends with a free-text question, no options, and Start closed: a dead end. Offer concrete block/limit choices instead.
- "Block YouTube Shorts for 1 hour" still asks YouTube-only vs everywhere (the model returns UNRESOLVED). Prompt/eval track.
- The restored 16:01 build shows "Not protecting" after an Accessibility rebind although the service is logging decisions; check the heartbeat on the current build.
- From the quota phone test: the long lecture was allowed via passive/`COMMENTS`, not `ALLOW_LONG_EDUCATIONAL`; the "shorts remaining" overlay never showed; the card says "rest of today" but the deadline is +24h; card copy has ".," typos.
- The Android tree is one WIP commit (`993a274`); review it before any tester build.

Update 2026-09-28: hosted phone flow and quota re-test done on staging (see `CURRENT_STATUS.md`). Fixed on the way: `59e0a18`, `8ed37e4`, `66007ee`. Next, in order:

1. **P0 — Shorts escape via picture-in-picture.** After a quota-exceeded Short was blocked and the session LOCKED, pressing Home turned YouTube into a PiP window; the Short stayed on screen over Chrome and Chrome logged no decision. Reproduce with screenshots/logcat, then block or pause PiP playback of an in-scope surface. Unit-test the decision; phone-verify.
2. **Deadline vs card.** "Only 2 Shorts today" card says "ending at local midnight"; the session deadline is start + 24h (`SessionExpiryPolicy` takes the later of the two), so the promise runs into tomorrow. The checkbox says "lasts 1 day". Make one of them true.
3. **Strict copy vs lock.** The card says "Strict means blocking further Shorts, not locking the phone"; three blocked attempts lock the session. Align the copy or the lock rule.
4. Ask Ankit before promoting to production (set `PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09` + `STRLIX_BUILD_ID=8ed37e46d2f52f8d863473499944b37b7e3c2887`, deploy the same SHA, check `/health`). Then delete `phonecodex-backend-staging`. The Android fixes ship separately in the next tester APK.

Update 2026-09-28 afternoon: items 1 and 2 fixed (`0bdde0c` PiP guard, `33dd29e` midnight deadline); 906 tests pass. Phone: midnight label/deadline and media pause on block verified by the agent; the PiP escape itself was checked by Ankit by hand, not by the harness. Next: item 3 (Strict copy vs lock), then item 4 promotion together with a new tester APK built from `33dd29e` or later with the production URL.

Update 2026-09-28 13:30: item 3 fixed (`39f130c`); item 4 done — production runs `8ed37e4` with v09, staging deleted, tester APK `BF0A101A` (from `39f130c`) installed on Ankit's phone. Next, in order:

1. On the tester APK against production: "Strict: only 2 YouTube Shorts today" end-to-end (card has no "not locking" line, midnight label, 2 counted, 3rd blocked), then Home on the blocked Short and record the PiP guard with screenshot + logcat (`Media paused reason=pip_guard`). Do not run `uiautomator dump` while testing.
2. Sideload the same APK to testers only after step 1 passes.
3. Merge `checkpoint/2026-09-27` into `master` after review (still local, not pushed).

Update 2026-09-28 15:35: production phone run done (see `CURRENT_STATUS.md`). Quota, card copy, midnight deadline: PASS. PiP: **FAIL for ads** — the media-pause key does not stop a YouTube ad playing in PiP. Next, in order:

1. **P0 — PiP must stop, not just pause.** When the guard holds (locked, or same package blocked < 10 min), escalate if playback is still active after one pause: e.g. bring the Strlix block screen to front over the PiP (`performGlobalAction` / launch the overlay activity) or collapse PiP via `GLOBAL_ACTION_HOME` + re-block on expand. Research the Play-safe option first; add a pure-law test for "paused but still playing -> escalate". Check whether the repeated media key causes the MIUI volume panel; stop re-sending when `isMusicActive` stays true.
2. Re-run the same phone test with `tools/phone/Capture-StrlixDiag.ps1` before/after.
3. Then kill experiments #1 (OneKeyClean) and #3 (SwipeUpClean) from `docs/accessibility-kill-recovery-2026-09-28.md`, with captures.
4. Only then sideload testers.

Update 2026-09-28 17:05: item 1 done in code (`7db302a`): media keys removed; the guard requests audio focus (max 3 per PiP episode), verifies with `isMusicActive`, and logs truthful events. 916 Android unit tests pass. **Phone proof blocked**: MIUI Security Center disabled YouTube at 16:31:14 (`enabled=3`, not ADB), so the Shorts/PiP run could not start. APK `24AEBE22…` is installed; the test commitment was ended normally; Accessibility is bound. Next, in order:

1. Ankit re-enables YouTube on the phone (his decision), then run the matrix in `docs/closed-alpha-handoff-2026-09-28.md` §Phone gate: the Short quota block, Home into PiP including the ad case (`MEDIA_STOP_CONFIRMED` + PiP frames stop changing + no volume panel), an allowed long lecture in PiP keeps playing, Chrome/dialer/meeting untouched, End restores rules.
2. If the ad keeps playing after 3 requests (`CONTROL_UNAVAILABLE reason=STILL_PLAYING_AFTER_BOUND`), the remaining options need new access (notification listener for a `MediaController`, or an Accessibility gesture). Ask Ankit; do not add them silently.
3. Kill experiments: OneKeyClean (13:33:57) and SwipeUpClean (16:31:06) were both observed today, leaving Accessibility crashed until the Settings toggle; captures `before_rebind`/`after_rebind` and `pip2_before_install`/`pip2_after_rebind`. A clean, deliberate rerun with captures on each side is still owed.
4. Sideload to 5 testers only after step 1, with the known-limitations list.

Update 2026-09-28 23:15 (uncommitted): the PiP path is fail-safe. Audio focus is removed because it was device-wide and could stop allowed music; nothing is interrupted without package attribution. This replaces step 2 above: stopping PiP playback needs Ankit's product decision on new access (notification listener → `MediaController` for the exact package, or Accessibility gestures). Phone test for this build: allowed Spotify/podcast plays → open a blocked video app → block overlay shows, music keeps playing, logcat `CONTROL_UNAVAILABLE source=block_overlay reason=PLAYBACK_SOURCE_UNKNOWN`; Home into PiP → `PIP_GUARD_ENTERED`, one `CONTROL_UNAVAILABLE source=pip_guard`, music still plays; close PiP → `GUARD_EXITED`; a call during PiP → `reason=CALL_OR_MEETING_AUDIO`.

Update 2026-09-29 07:55 (uncommitted): card copy fixed on Android — short-form "After limit" variants render once, no ".," joins, "1 short left" (`PromiseCardDisplayCopyTest`; 930 tests pass). "Home status refreshes only on Run protection check" is stale: the core poll re-reads the 30 s service lease every 1–3 s, so a killed service shows "Not protecting" without a check.

Update 2026-09-29 08:40 (uncommitted), remaining 09-27 items:

- Complaint promise ("I waste my evenings on shorts"): production probe (3 runs) shows no Android dead end any more — after the pick, `requiresStrongerConfirmation=true` opens Start behind the checkbox (`59e0a18`). Found and fixed in backend (not deployed): turn 1 kept the model's "block or warn?" question over the server's scope A/B options; a verified pick left that question in the DTO. Smoke `smoke_v09_clarification_scope.js` covers both; fixtures regenerated (they were also stale on the "YouTube short videos only" label).
- "Block YouTube Shorts for 1 hour" still asks app vs everywhere. Kept: auto-resolving to the app would allow browser Shorts (false-allow risk). AI Lab: make option B "YouTube Shorts in the app and browsers" instead of "Short videos everywhere".
- Legacy `enforcement_scope_kind`: by design — a mirror read only when the typed scope is ABSENT (`TypedScopePersistenceStateTest`). Closed.
- Native YouTube watch page read as COMMENTS (controls hidden, no clock): real, but no captured screen text exists. Next phone session: open a long video in the YouTube app, wait for controls to hide, capture the screen text once (uiautomator dump is acceptable for this one capture, then rebind Accessibility), add it to `PhoneSurfaceTraceCatalog`, then fix `SurfaceDetector`.

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
