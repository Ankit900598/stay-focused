# PhoneCodex P0: Firebase Gradle wire (2026-10-31)

**Status: Gradle wired. Release gate OFF. Anonymous Auth project ready.**
Next: debug-only opt-in + device token smoke. Backend verifier + `STRLIX_USER_SESSION_AUTH` still **HOLD** (staging NO-GO).

## Facts
- Firebase project `phonecodex-strlix` (Spark). Android package `com.phonecodex.app`.
- `app/google-services.json` is local only, git-ignored (`app/google-services.json`, `**/google-services.json`). Never commit/print it.

## What changed
| File | Change |
| --- | --- |
| `gradle/libs.versions.toml` | `googleServices = 4.5.0`, `firebaseBom = 34.19.0`; libs `firebase-bom`, `firebase-auth`; plugin `google-services` |
| `build.gradle.kts` | `alias(libs.plugins.google.services) apply false` |
| `app/build.gradle.kts` | applies plugin; `implementation(platform(libs.firebase.bom))` + `implementation(libs.firebase.auth)`; OWNER-ONLY placeholders replaced by short comment (release/phoneTest gate hard false) |
| `app/src/androidTest/.../domain/auth/FirebaseAnonymousIdTokenSmokeTest.kt` | new; anonymous sign-in -> `getIdToken`; independent of `STRLIX_USE_FIREBASE_ID_AUTH` |
| `.gitignore` | already contained the entries; unchanged |

### Version note (deliberate deviation)
Latest BoM is 35.1.0 (`firebase-auth` 25.0.0), but it ships Kotlin **2.4.0** metadata; this repo is on Kotlin 2.2.10 and `compileDebugKotlin` failed ("binary version of its metadata is 2.4.0, expected 2.2.0"). Pinned **BoM 34.19.0** (`firebase-auth` 24.x, latest 34.x). Upgrade BoM together with a Kotlin bump (separate task).

## Verification (all on this box, JAVA_HOME=/home/box/jdk17, ANDROID_HOME=/home/box/Android)
- `:app:assembleDebug` OK
- `:app:assemblePhoneTest` OK
- `:app:testDebugUnitTest --tests 'com.phonecodex.app.domain.auth.*'` green (2 classes: 12 + 10 tests, 0 fail)
- `:app:compileDebugAndroidTestKotlin` OK (smoke test compiles)
- Generated BuildConfig `STRLIX_USE_FIREBASE_ID_AUTH`: **debug=false** (no local opt-in set), **release=false**, **phoneTest=false**. Release/phoneTest are hard-coded false in `app/build.gradle.kts`.
- google-services plugin: `processDebugGoogleServices`, `processPhoneTestGoogleServices`, `processReleaseGoogleServices` all ran and generated `values.xml`. JSON package matches `com.phonecodex.app`, project id matches `phonecodex-strlix`. No values dumped.

## Caveats
- Plugin applies to every variant, so **builds now require `app/google-services.json`** (including phoneTest and CI). A fresh checkout without it fails at `process*GoogleServices`.
- Release tasks need an https backend URL (e.g. `STRLIX_BACKEND_URL=https://...`) because of the existing `validateReleaseBackendTransport` check; I used a dummy `.invalid` URL only to generate release BuildConfig, no release APK built.
- Pre-existing, unrelated: running release tasks with configuration cache reports a problem in `validateReleaseBackendTransport` (script object reference). Not touched.
- `phoneTest` now has the Firebase SDK on the classpath, so `FirebaseBackendIdentityTokenProvider.create` would build the real provider, but nothing uses it while the gate is false.

## RUN-ON-DEVICE: Anonymous ID token proof
No emulator/device here, so the instrumented test was **not run**. On a device/emulator with network:
```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phonecodex.app.domain.auth.FirebaseAnonymousIdTokenSmokeTest
```
It asserts: anonymous user, 3-part JWT, `aud`=`phonecodex-strlix`, `iss`=securetoken, `sign_in_provider`=anonymous. Never logs the token; deletes the throwaway anonymous user afterwards.

## Not done (by instruction)
No gate flipped, no `STRLIX_USER_SESSION_AUTH` change, no cloud resources, no secrets printed.
