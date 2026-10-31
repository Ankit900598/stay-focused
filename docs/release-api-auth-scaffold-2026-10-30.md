# Release API auth: Android token scaffold (2026-10-30)

Implements the Android half of `docs/release-api-auth-adr-2026-10-12.md` behind a **non-production gate**. Nothing here enables hosted auth. Staging stays **NO-GO**.

## Contract

```text
Android: BackendConfig.applyAuth -> Authorization: Bearer <Firebase ID token>
Backend: userSessionAuth.bearerToken(req) -> verify -> req.strlixUser.uid   (only when STRLIX_USER_SESSION_AUTH=1)
```

The backend already reads `Authorization: Bearer`. `X-Strlix-App-Secret`, `X-Strlix-Google-Id-Token`, and a body `uid` are never an identity. Mail routes keep their Google ID token in `X-Strlix-Google-Id-Token`, so there is no clash with `Authorization`. No backend source change was needed.

## Gate flags (all default OFF)

| Flag | Where | Default | Effect |
| --- | --- | --- | --- |
| `STRLIX_USE_FIREBASE_ID_AUTH` (BuildConfig boolean) | `app/build.gradle.kts` | `false` | On: send Bearer ID token. Off: shared-secret behavior. |
| `strlix.useFirebaseIdAuth` / env `STRLIX_USE_FIREBASE_ID_AUTH` | `local.properties` / env | `false` | Read for the **debug** build type only. `release` and `phoneTest` are hard-wired `false`. |
| `STRLIX_USER_SESSION_AUTH` | backend env | `0` (unchanged) | Backend verifies session tokens. Not flipped anywhere. |

Shared secret rules: `validateReleaseBackendTransport` still rejects a non-empty secret for release. `applyAuth` additionally never sends the secret when `BuildConfig.DEBUG` is false.

## Behavior of `BackendConfig.applyAuth`

| Gate | Provider result | Header sent |
| --- | --- | --- |
| on | token | `Authorization: Bearer <token>` |
| on | any failure (Unconfigured, SignedOut, Offline, RefreshFailed, Revoked) | **none** (backend answers 401). Never falls back to the shared secret. |
| off | n/a (provider not asked) | `X-Strlix-App-Secret` if debug and non-empty, else none |

`applyAuth` is blocking, never throws, and returns a `BackendAuthResolution` (clients ignore it today). It logs only the failure kind. Callers never retry a paid call because of an auth failure.

## DI / one provider for Activity and AccessibilityService

`BackendIdentityTokenProviders` is a Kotlin `object` (process singleton; Activity and service share the app process). `MainActivity.onCreate` and `PhoneCodexAccessibilityService.onServiceConnected` both call `installIfNeeded(applicationContext)`, which is idempotent and does nothing while the gate is off. Clients (`PromiseCompilerClient`, `NetworkAiContentClassifier`, `PromiseSpeechClient`, `AppDossierClient`, `BackendHealthProbe`) already call `BackendConfig.applyAuth`, which reads `BackendIdentityTokenProviders.shared`, so no constructor changes were needed.

Threading: token minting uses `Tasks.await`, which throws on the main thread. That surfaces as `RefreshFailed` (fail closed). All current callers run on background threads.

## Firebase provider without the SDK

`FirebaseBackendIdentityTokenProvider` reaches Firebase Auth **by reflection**, so credential-free `phoneTest` and unit builds need no google-services.json and no Firebase artifacts. It reports `Unconfigured` unless a `FirebaseApp` exists in the process. It signs in anonymously once (serialized) when there is no user. After `Revoked` it will **not** silently mint a new anonymous UID (that would dodge per-UID limits); `clearRevoked()` is for an explicit user action.

## Files

New (Android, `app/src/main/java/com/phonecodex/app/domain/auth/`):
`BackendIdentityTokenProvider.kt`, `UnconfiguredBackendIdentityTokenProvider.kt`, `FirebaseBackendIdentityTokenProvider.kt`, `BackendIdentityTokenProviders.kt`, `BackendAuthHeaders.kt`.

Changed: `domain/diagnostics/BackendConfig.kt`, `MainActivity.kt`, `accessibility/PhoneCodexAccessibilityService.kt`, `app/build.gradle.kts` (BuildConfig flag + commented owner-only Firebase lines).

Tests (`app/src/test/.../domain/auth/`): `BackendIdentityTokenProviderTest.kt`, `FirebaseBackendIdentityTokenProviderTest.kt`. Cover concurrent callers, unconfigured, sign-out, refresh failure, offline, revoked latch, bootstrap-once, no token or secret in log sinks or `toString`.

Backend: `backend/smoke_bearer_contract.js` (new), `backend/.env.example` (new, empty placeholders, flag `0`).

## Verification

```bash
cd backend && node smoke_bearer_contract.js && node smoke_user_session_auth.js && node smoke_user_session_routes.js
./gradlew :app:testDebugUnitTest --tests 'com.phonecodex.app.domain.auth.*'
```

## Not done / still NO-GO

- No Firebase project, `google-services.json`, project IDs, or Gradle plugin/deps added (commented placeholders only).
- No account linking/recovery UI, no `X-Strlix-Request-Id` sending from Android (backend supports it).
- No forced-refresh-on-401 retry (would be a silent paid retry; decide product policy first).
- Staging NO-GO until the real Azure Table multi-instance test passes and Android tokens are proven end to end against a non-production backend.
- Not phone-verified.
