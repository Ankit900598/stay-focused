# PhoneCodex (Strlix) — Privacy Policy + Play Data Safety DRAFT (refreshed)

_Refreshed 2026-10-31 ~16:05 IST after P0#1 local auth chain GREEN. **NOT legal advice; NOT counsel approval; NOT a Play upload.** Every `TODO` stays open until Ankit/counsel resolves it. Secrets-free._

## Status vs earlier draft
| Item | Was (morning) | Now (live) |
|---|---|---|
| Firebase project | not created | **`phonecodex-strlix`** live; Anonymous Auth used for ID tokens |
| Android + debug SHA | pending | wired; debug E2E PASS (opt-in stripped after) |
| Admin verify + Table | pending | Admin verify PASS; table **`strlixauthstate`** on existing **`ststrlixagentin`** (AAD) PASS |
| Release / hosted auth flags | OFF | **Still OFF / HOLD** — do not declare "account required" as shipped until flags flip |

## In-app / public policy link
- Policy URL: `TODO: https://<owner-domain>/strlix/privacy` (public HTTPS page, not PDF, no login)
- In-app: Settings → Privacy policy + link from Accessibility disclosure. `TODO: no dedicated Privacy policy deep-link in repo yet` (Accessibility disclosure dialog exists; see strings below).

## Providers / processors (live facts + TODOs)
| Provider | Purpose | Data (as designed / proven) | Retention |
|---|---|---|---|
| **Firebase Authentication (Anonymous)** — Google, project **`phonecodex-strlix`** | Mint ID token; backend `verifyIdToken(..., true)` binds paid/session routes to UID when flags on | Firebase Anonymous **UID**, short-lived **ID token** (JWT aud/iss = project), Google Auth metadata | **Proposed (TODO Ankit accept):** delete Auth user on in-app “reset identity” / account delete; idle anon users purged after **90 days** without backend activity; tokens not stored server-side beyond verify |
| **Azure Table Storage** — Microsoft, account **`ststrlixagentin`**, table **`strlixauthstate`**, AAD (shared key disabled) | Per-UID route capacity / hourly counters, revocation markers, daily paid cap entity | Partition/row keys derived from UID+route (base64url), numeric counters, timestamps, ETag; **not** promise text or raw JWT | **Proposed (TODO Ankit accept):** hour buckets naturally age; delete UID partition on account delete within **7 days**; global-day entity retained **≤ 35 days**; no PII beyond UID key material |
| Azure App Service / Container Apps — Strlix backend | Promise compile, classifier, optional voice | Promise text, classifier inputs (app/surface / visible text when commitment active), request metadata | TODO app log retention; **must not** log promise text or Bearer tokens (smokes assert clean logs) |
| Azure OpenAI / model routing | Promise understanding + classifier | Prompt content from promise & on-screen context | TODO model/region + abuse-monitoring retention |
| Azure Speech (optional) | Voice promise transcription | Audio clip, transcript | TODO |
| Google Sign-In / Gmail (mail-assist) | Not in current default path | — | Out of scope until OAuth/CASA — TODO |
| On-device | Disclosure consent prefs, local diagnostics | Local prefs / session IDs | Until uninstall/clear — TODO confirm no promise text on disk |

## Data Safety form mapping (draft — unknowns marked)
- **Collected:** App activity (foreground app/surface for enforcement): YES · User-generated content (promise text): YES · Audio (optional voice): YES · Device or other IDs (**Firebase Anonymous UID**): YES when auth gate on · Personal info name/email: NO (Anonymous; mail-assist TBD) · Location/Contacts/Photos/Financial/Health: NO — TODO confirm Accessibility never uploads message bodies beyond disclosed classifier path
- **Shared:** Processors (Google Firebase, Microsoft Azure) acting on our behalf — counsel TODO whether Play “sharing” applies
- **Encrypted in transit:** YES HTTPS to hosted backend; debug/phoneTest may use loopback cleartext — note for test builds only
- **Deletion:** TODO in-app “Delete my anonymous account” (Firebase `deleteUser` + table UID purge) + web form; smoke path already deletes throwaway anon users after tests
- **Required vs optional:** Accessibility required for enforcement; voice optional; Anonymous account automatic when auth gate on
- **Accessibility:** Prominent disclosure + versioned consent in app (`accessibility_disclosure_*` strings; `AccessibilityDisclosureConsentStore`). Policy must mirror: identifies foreground app, may read visible text (incl. private messages), may send app name / visible text / promise / safety-rule context to backend for AI classification; `AI_DECIDE` may warn/block; emergency/out-of-scope on-device first; optional screenshot experiment if enabled

## Policy outline (unchanged structure)
1. Who we are / contact (**TODO**) 2. What we collect 3. Accessibility use & limits 4. AI processing 5. Processors 6. Retention (**proposed above — TODO accept**) 7. Deletion & rights 8. Children (**TODO age rating**) 9. Security 10. Changes

## Unknowns still open
Owner legal entity + contact email · exact live model deployment/region · Azure diagnostic log TTL · whether any crash/analytics SDK present (none required for auth) · Play “data shared” counsel call · final deletion UX copy
