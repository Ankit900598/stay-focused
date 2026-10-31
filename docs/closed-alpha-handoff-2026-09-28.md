# Closed-Alpha Handoff — 2026-09-28 (prepared, NOT distributed)

Status: **do not send yet.** The phone gate below has not passed. Nobody has received this APK.

## Build

| Field | Value |
|---|---|
| File (local, git-ignored) | `.tmp-phone-verify-20260928-hosted/strlix-tester-7db302a.apk` |
| SHA-256 | `24AEBE221B85BE99D731BE6C8352D0655CC10462C6238C5402C5FD953C8EBFC9` |
| Size | 19,878,151 bytes |
| Source commit | `7db302a` (branch `checkpoint/2026-09-27`) |
| Backend | production `phonecodex-backend`, build `8ed37e4`, `promise_compiler_v09` |
| Package / version | `com.phonecodex.app`, versionName 1.0, minSdk 26 |

Testers verify the hash before installing: `certutil -hashfile strlix-tester-7db302a.apk SHA256` (Windows) or `sha256sum` (Linux/macOS).

## Who

Five opted-in people Ankit knows personally, Android 8+, who agree to send feedback. No public link, no Play Store.

## Install steps (tester)

1. Allow "Install unknown apps" for the app you use to open the APK (Files/Chrome). Install the APK.
2. Open Strlix. Settings > Accessibility > Downloaded/Installed apps > **PhoneCodex Focus Detection** > On. This lets Strlix see which app and screen is in front; it does not read passwords, and the debug report never includes screen text.
3. Allow notifications (the "protection" notification keeps enforcement alive).
4. Xiaomi/Redmi/POCO: Settings > Apps > Strlix > Autostart On, Battery saver "No restrictions". Do **not** clear Strlix from recent apps. MIUI cleaners kill Accessibility; if Home says "Not protecting", toggle the Accessibility switch off and on again.
5. Type a promise, e.g. "Strict: only 2 YouTube Shorts today", answer the question if asked, read the card, tick the confirmation, tap Start.

## Tested behaviour (phone, Xiaomi 2312DRAABI / Android 15)

- Promise → card → pick → confirmation → Start against production v09 (2026-09-28).
- Quota: Shorts 1–2 counted, Short 3 blocked with overlay; "today" ends at local midnight; the card never says "not locking".
- YouTube Home/long videos allowed under a Shorts-only scope; dialer, emergency dialer, SMS, Accessibility settings never blocked (09-28 staging run).
- End Commitment restores app rules exactly; permanent guardrails stay.

## Known limitations (tell testers up front)

- **Video (including ads) may continue playing in picture-in-picture** after a block, in any video app. Strlix cannot tell which app is playing without extra access, so it does not interrupt audio at all (that would also stop your allowed music). Note: the APK `24AEBE22…` listed above still has the older audio-focus behaviour; rebuild from the fail-safe change before sending.
- On Xiaomi/HyperOS, cleaning recent apps or "OneKeyClean" kills Accessibility; protection stays off until the tester re-toggles it in Settings.
- Needs internet to understand a promise; offline, Start stays off.
- Some promises ask a follow-up question even when the wording looks clear ("Block YouTube Shorts for 1 hour").
- Complaint-style promises ("I waste my evenings on shorts") can end without options.
- Card copy has small glitches (duplicated "After limit" lines, "1 shorts left").

## Feedback path

1. When something is wrong: Home > Last decision > **Copy debug report** (sanitized: no private screen text, no keys).
2. Paste it with one line ("what I expected / what happened") into the private chat Ankit opens with each tester.
3. Ankit logs each report in `NEXT_TASKS.md` with separate false-allow / false-block counts.

## Phone gate (must pass before sending)

Run on `7db302a` with `tools/phone/Capture-StrlixDiag.ps1` before and after; screencap + logcat only (never `uiautomator dump`); no force-stop.

1. "Strict: only 2 YouTube Shorts today" → Short 3 blocked (`QUOTA_BLOCK_EXCEEDED`).
2. Home → YouTube PiP, including an ad: logcat `PIP_GUARD_ENTERED` → `MEDIA_CONTROL_REQUESTED` → `MEDIA_STOP_CONFIRMED`; two PiP screenshots 2 s apart identical; YouTube's player not `started` in `dumpsys audio`; no volume panel. `CONTROL_UNAVAILABLE` = fail (keep limitation).
3. Allowed long lecture in PiP keeps playing (no guard events).
4. Chrome, dialer, a call/meeting app: untouched.
5. End Commitment: rules restored, Accessibility still bound, no new unexpected exit.

Blocked on 2026-09-28: YouTube is disabled-by-user on the test phone (set by MIUI Security Center at 16:31:14). Ankit must re-enable it.
