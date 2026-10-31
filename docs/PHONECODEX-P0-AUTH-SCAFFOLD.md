# PhoneCodex P0 #1: Android Firebase ID token scaffold

Status 2026-10-30: scaffold only, gate OFF, **staging NO-GO**. Full detail: `docs/release-api-auth-scaffold-2026-10-30.md`. Design: `docs/release-api-auth-adr-2026-10-12.md`.

## What changed

| Area | File |
| --- | --- |
| Interface + failure kinds (Unconfigured, SignedOut, Offline, RefreshFailed, Revoked) | `app/.../domain/auth/BackendIdentityTokenProvider.kt` |
| Default provider | `.../auth/UnconfiguredBackendIdentityTokenProvider.kt` |
| Firebase anonymous provider (reflection, no SDK needed to compile) | `.../auth/FirebaseBackendIdentityTokenProvider.kt` |
| Shared instance for Activity + AccessibilityService | `.../auth/BackendIdentityTokenProviders.kt` |
| Header decision (pure, tested) | `.../auth/BackendAuthHeaders.kt` |
| Single auth entry for all 5 hosted clients | `.../diagnostics/BackendConfig.kt` (`applyAuth`) |
| Install hooks | `MainActivity.kt`, `PhoneCodexAccessibilityService.kt` |
| Build gate | `app/build.gradle.kts` |
| Tests | `app/src/test/.../domain/auth/*Test.kt` |
| Backend | `backend/smoke_bearer_contract.js`, `backend/.env.example` (backend source unchanged; it already reads `Authorization: Bearer`) |

## Gate flags (defaults)

- `STRLIX_USE_FIREBASE_ID_AUTH` BuildConfig: **false**. Debug may opt in via `local.properties` `strlix.useFirebaseIdAuth=true`; release and phoneTest are hard false.
- `STRLIX_USER_SESSION_AUTH`: **off** (not touched).
- Release still rejects a non-empty shared secret (Gradle check) and never sends one at runtime.
- Gate on + token failure = no credential sent (401), never the shared secret.

## Ankit-only Firebase checklist

Nothing below was done by code or by the agent.

1. Create the Firebase project under an owner-controlled Google account.
2. Enable **Anonymous** sign-in in Authentication.
3. Register Android app `com.phonecodex.app`; add SHA-1 and SHA-256 for debug, and for the Play app-signing and upload keys.
4. Download the real `google-services.json` into `app/` (git-ignored; never commit).
5. Add the `com.google.gms.google-services` plugin, `firebase-bom` and `firebase-auth` (commented placeholders in `app/build.gradle.kts`; versions are your choice).
6. Backend env (hosted, **only after** step 8): `STRLIX_FIREBASE_PROJECT_ID=<your project id>` (token audience) plus Admin credentials per `backend/sessionRouteAuth.js`. `.env.example` has empty placeholders.
7. Privacy: disclose that an anonymous Firebase UID (pseudonymous personal data) is created and sent with hosted requests; decide account linking/recovery before promising durable cloud records.
8. Run the real two-instance Azure Table test. Only then consider `STRLIX_USER_SESSION_AUTH=1` on **staging**.
9. Flip the release `STRLIX_USE_FIREBASE_ID_AUTH` only after steps 1-8 and a non-production end-to-end run.

## Verify

```bash
cd backend && node smoke_bearer_contract.js && node smoke_user_session_auth.js && node smoke_user_session_routes.js
./gradlew :app:testDebugUnitTest --tests 'com.phonecodex.app.domain.auth.*'
```

## Still NO-GO

Azure Table multi-instance proof, real Firebase project, Android tokens against a non-prod backend, privacy approval, phone verification.
