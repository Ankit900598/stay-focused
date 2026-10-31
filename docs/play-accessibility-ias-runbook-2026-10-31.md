# PhoneCodex P0 — Play Accessibility / Internal App Sharing runbook (refresh)

_Refreshed 2026-10-31 ~16:05 IST after P0#1 local auth GREEN. Checklist only. **No sideload. No Play Protect bypass. No Play upload in this task.** Secrets-free._

## Current state (live)
- **Auth:** Local chain GREEN (Firebase `phonecodex-strlix` Anonymous, Admin verify, Azure Table `strlixauthstate`, debug Bearer E2E). **Release/phoneTest `STRLIX_USE_FIREBASE_ID_AUTH` = hard false. Hosted `STRLIX_USER_SESSION_AUTH` = OFF.**
- **Xiaomi 2312DRAABI:** still treat as **no trusted install** until IAS; prior Files/sideload path blocked by Play Protect (Accessibility restriction — not a malware verdict). **Do not ADB-install onto Xiaomi.**
- **Play Console:** owner signup / org vs individual still Ankit-only; no app listing or IAS binary published by assistants today.
- **phoneTest build:** empty backend secret + Google web client ID; loopback `http://localhost:8787` only; use after Play install via `adb reverse` — never as sideload.
- **Disclosure UI:** `AccessibilityDisclosureDialog` + versioned `AccessibilityDisclosureConsentStore`; body string discloses visible-text / backend AI classification / `AI_DECIDE` block risk. Self-dismiss “Not now”; Accessibility stays off without recorded consent.

## Hard rules (unchanged)
1. **No sideload. No Play Protect disable/bypass. No ADB install onto Xiaomi for distribution testing.**
2. Never set `isAccessibilityTool=true` to dodge policy. Don’t strip Accessibility permission just to pass Protect.
3. Upload candidates must be sanitized (no embedded `STRLIX_BACKEND_APP_SECRET`; phoneTest already empty).
4. Play re-signs IAS artifacts — record Play’s identity, not only local SHA-256.
5. Cross-app Xiaomi matrix **only after** install from Play IAS/internal link; restore device state after.
6. No multi-device `connected*` Gradle while Xiaomi is the target device.

## IAS path checklist (when Ankit authorizes upload later)
- [ ] Ankit: Play account owner + individual vs organization (permanent)
- [ ] Ankit: confirm package `com.phonecodex.app` permanent
- [ ] Ankit: choose **Internal App Sharing** (recommended first) vs internal testing track (needs Data Safety filled)
- [ ] Rebuild phoneTest/release-candidate from reviewed tree; preflight empty secrets + loopback/backend URL as intended
- [ ] Record local APK SHA-256; authorized human uploads via Play Console IAS
- [ ] Install **from Play link** on Xiaomi; verify package + open app → prominent disclosure → enable Accessibility **only in Settings**
- [ ] Confirm consent persisted; Accessibility heartbeat / service running
- [ ] Laptop/host: `node backend/start-phone-test.js` with paid routes off; `adb reverse tcp:8787 tcp:8787`; phone hits `/health`
- [ ] Any paid compiler call needs **separate** Ankit OK (`STRLIX_PHONE_TEST_PAID_CALLS` / Sol budget)
- [ ] Matrix + teardown reverse; note false allows vs false blocks separately

## OPEN policy gate — Accessibility + `AI_DECIDE` (NOT PASSED)
Google AccessibilityService policy may treat autonomous AI-driven blocks differently from static user rules. Disclosure/consent exist; **Play-policy / counsel review still required** before relying on IAS or production. Options for Ankit/product: IAS build limited to static rules; human confirm before block; or written counsel sign-off. Also review: `RECORD_AUDIO`, Usage Access, `specialUse` FGS demo, biometric if any.

## Related docs
- Privacy / Data Safety draft: `PHONECODEX-P0-PRIVACY-DATA-SAFETY-DRAFT.md`
- Auth local GREEN: `PHONECODEX-P0-AUTH-LOCAL-GREEN.md`
- Longer history: `docs/phone-test-play-distribution.md`
