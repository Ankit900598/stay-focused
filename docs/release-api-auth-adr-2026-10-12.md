# Release API Authentication Decision Record — 2026-10-12

## Status

**Recommended, not implemented.** The release API boundary is a P0 blocker. This review created no Firebase project/configuration and changed no production setting.

## Recommendation

Use Firebase Authentication ID tokens as the user-bound identity for hosted Android API calls, initially with anonymous Firebase accounts to avoid forcing a sign-in screen. Send a short-lived token as `Authorization: Bearer ...`, verify it on the backend, and derive all quotas from the verified UID. Offer account linking to Google later for cross-device recovery and account features.

Do not ship a shared backend secret in an APK. Keep it only for controlled local/debug tooling if still needed. Keep `/health` narrowly public; every paid or user-data route must authenticate before parsing request bodies, charging model spend, or accessing connectors.

## Repository evidence

- `app/build.gradle.kts` intentionally rejects a non-empty backend secret in release variants.
- `BackendConfig.applyAuth()` only sends `X-Strlix-App-Secret`; compiler, classifier, speech, dossier, and diagnostic clients have no general user-token provider.
- `backend/appAuth.js` requires that secret for every route except health and two exact mail POST routes.
- Mail uses verified Google identity, but `GoogleMailAuthorizer` keeps its ID token in memory and obtains it through a mail-specific foreground flow. It is not a durable background credential for Accessibility-service requests.
- The backend has a verified-user limiter, but currently applies it to mail only. Most paid-route limits are process-memory/IP/app-key based and reset on restart.
- No Firebase Gradle configuration or `google-services.json` is present. A real Firebase implementation cannot be built or verified until the owner configures a project.

## Why this is the best first design

1. The credential belongs to a user/install, not the binary; extracting the APK does not reveal one universal Strlix key.
2. The Android Auth SDK manages the user session and ID-token refresh, unlike the existing in-memory Google mail token.
3. Anonymous-first reduces onboarding friction. Firebase supports temporary anonymous users and linking a provider later. Local commitments can remain usable without cloud sign-in.
4. The backend receives a stable principal. Verify token signature, issuer, audience/project and expiry; use only the verified UID for quotas and audit attribution. Never accept a request-body UID as identity.
5. Play Integrity is complementary, not a replacement: it provides app/device/request integrity signals, not a durable Strlix user identity or entitlement. Consider it later as an anti-abuse signal after official Play distribution.

## Alternatives rejected

| Option | Decision | Reason |
| --- | --- | --- |
| Embed the shared app secret in release APKs | Reject | Extractable from every binary; one leak authorizes all users and spend. |
| Reuse the current Google ID token for every route | Reject as the sole session design | The current implementation is mail-scoped, in-memory, and foreground-oriented; it does not establish a reliable refreshable identity for background calls. |
| Play Integrity alone | Reject as authentication | It attests app/device/request properties, not the principal used for per-user quotas. It may supplement identity later. |
| Open hosted model routes without identity | Reject | Enables anonymous spend and prevents per-user limits and abuse response. |
| Firebase Authentication ID tokens | Recommend | Standard Android refreshable user identity with backend verification support; requires owner-controlled project setup and privacy disclosure. |

## Implementation contract

### Android

- Introduce an injected `BackendIdentityTokenProvider`; networking clients must not depend on UI or Gmail classes.
- Obtain a Firebase ID token immediately before each hosted request and rely on the SDK for refresh. Never log or manually persist the raw token.
- Share the provider across Activity and Accessibility-service paths. Handle signed-out, refresh, offline, revoked, and unconfigured states explicitly; never silently retry paid operations.
- Keep local loopback/debug behavior separate from hosted release behavior. Release builds contain no shared backend secret.
- Treat an anonymous UID as pseudonymous personal data, not as legal anonymity. Disclose it and provide account linking/recovery before promising durable cloud records.

### Backend

- Verify Firebase ID tokens and use the verified UID, never a body UID or email, as principal.
- Require a valid principal for hosted `POST /compile-promise`, `/classify`, `/transcribe-promise`, `/research-app`, `/draft-focus-promise`, mail-assist, and every future paid/user-data route. Keep the allowlist explicit; unknown/new routes default closed.
- Authenticate before JSON parsing, paid-call charging, provider invocation, or Gmail content handling.
- Apply both per-UID route limits and the global spend cap. Existing in-memory limits are temporary single-instance beta controls, not multi-instance/billion-user quota enforcement.
- Preserve mail consent/scope gates. Firebase authentication grants no Gmail access and must not alter Google OAuth consent boundaries.
- Keep `/health` minimal; do not expose secrets, user data, detailed deployment identifiers, or prompts.

## Tests and rollout gates

1. Backend tests: valid token; missing, malformed, expired, wrong-issuer, wrong-project/audience, revoked (where supported), and forged-UID requests.
2. Route matrix: every paid route rejects missing/invalid identity before body parsing and fake-provider calls; valid calls charge the verified UID; health stays minimal; no unrelated route is accidentally exempted.
3. Android tests: token refresh, concurrent callers, sign-out/revocation, offline, anonymous bootstrap, account linking, and absence of token logging.
4. Release gate: build the no-secret artifact, scan APK/AAB, test against a non-production backend, and prove per-user limit isolation.
5. Owner setup: create/connect the Firebase project, enable Anonymous Auth, register the exact package/signing fingerprints, configure backend verification, and approve privacy disclosures. Do not invent project IDs or credentials in code.
6. Beta gate: retain a global kill switch and spend cap; test App Service restart behavior and quota persistence before expanding traffic.

## Immediate next work

1. Keep hosted paid routes closed to release clients until identity is implemented; do not make them anonymous to unblock UI.
2. Owner completes Firebase project/configuration and approves the identity/privacy choice. No console resource was created by this audit.
3. Implement the injected token provider and backend verifier behind a non-production gate; add the route matrix before enabling hosted inference.
4. Separately clear the official Play Console owner/account-type and permanent package-identity gate. Then use Internal App Sharing. Do not retry sideloading or bypass Play Protect.

## Official references

- Firebase Android Auth setup and token use: <https://firebase.google.com/docs/auth/android/start>
- Firebase anonymous auth and linking: <https://firebase.google.com/docs/auth/android/anonymous-auth>
- Firebase ID-token verification on a custom backend: <https://firebase.google.com/docs/auth/admin/verify-id-tokens>
- Play Integrity role and server verification: <https://developer.android.com/google/play/integrity/overview>
