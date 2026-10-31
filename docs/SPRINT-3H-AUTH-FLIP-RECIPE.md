# PREP ONLY — Firebase ID auth + hosted session flip recipe

**DO NOT APPLY until CEO (Starlink) says flip.** Paid routes stay DISABLED. Sol OFF.

Updated: 2026-10-31 ~16:15 IST

## Ingredients (already proven today)
| Piece | Value / evidence |
|---|---|
| Firebase project | `phonecodex-strlix` |
| Android app | `com.phonecodex.app` (+ debug SHA when Starlink added) |
| Admin SA | `/workspace/secrets/phonecodex-strlix-firebase-adminsdk.json` (gitignored) |
| Azure Table | `strlixauthstate` on `ststrlixagentin` (AAD, ay1181mnc) |
| Privacy URL | `https://strlix.tech/privacy` LIVE 200 |
| Continuity tip | **g148phone** / `phone.strlix.tech` (existing Pilot path) |

## Android (debug only first)
```
# gradle.properties or -P (debug buildType only reads prop; release/phoneTest hard false)
strlix.useFirebaseIdAuth=true
# rebuild assembleDebug — BuildConfig.STRLIX_USE_FIREBASE_ID_AUTH becomes true
```

## Hosted / local backend staging
```
export STRLIX_USER_SESSION_AUTH=1
export STRLIX_FIREBASE_PROJECT_ID=phonecodex-strlix
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/phonecodex-strlix-firebase-adminsdk.json
# Azure Table AAD env already used for morning PASS
export STRLIX_PAID_ROUTES_DISABLED=1   # REQUIRED — Sol over $300
# Do NOT set STRLIX_PHONE_TEST_PAID_CALLS=1
```

## Verify after flip (when CEO GO)
1. FirebaseAnonymousIdTokenSmokeTest still PASS
2. Android debug Bearer E2E PASS against staging
3. smoke_firebase_table_integration PASS (box AAD, not laptop ay186)
4. Paid route returns kill-switch / 403 — never live Sol

## HOLDs
No flip this sprint · no Play · no kvm · no new VMs · no Sol spend
