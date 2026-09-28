# Hosted backend runbook (closed APK)

**Updated:** 2026-09-20  
**Goal:** testers hit Understand over HTTPS. PolicyEngine stays law. AI does not enforce.

Do not commit secrets. Do not edit `.env` in git. Do not create a new OpenAI resource. Do not open NSG `0.0.0.0/0`.

## Live Azure

| Piece | Value |
|---|---|
| Subscription | Azure subscription 1 (`a3dc5296-f948-427e-8656-c6bc52afee21`) |
| Resource group | `phonecodex-dev` (eastus2) |
| OpenAI account | `ay186mnc-1561-resource` (westus3) |
| Speech | `phonecodex-speech-dev` (eastus2) |
| App Service | `phonecodex-backend` on plan `phonecodex-backend-plan` (P1v3, westus3) |
| Public URL | `https://phonecodex-backend.azurewebsites.net` |

`GET /health` is public. Every POST needs header `X-Strlix-App-Secret`.

## Model ladder (credit window — strongest useful)

| Job | Deployment | Model | Fallback |
|---|---|---|---|
| Promise Compiler | `pc-lab-astra` | gpt-6-astra | `pc-lab-best` (gpt-5.6-sol) → `pc-lab-strong` |
| Classifier | `pc-lab-terra` | gpt-5.6-terra | `pc-lab-luna` → `pc-lab-best` → `pc-lab-strong` |
| Vision | `pc-lab-astra` | gpt-6-astra | `pc-lab-vision` (gpt-4o). **Default OFF** (`AZURE_VISION_EXPERIMENT` unset) |

Existing `pc-lab-cheap` stays for evals. Do not delete deployments.

## Quota / deployment check

```powershell
az cognitiveservices account deployment list `
  --name ay186mnc-1561-resource `
  --resource-group phonecodex-dev `
  --query "[].{name:name, model:properties.model.name, state:properties.provisioningState, cap:sku.capacity}" `
  -o table

az cognitiveservices account list-models `
  --name ay186mnc-1561-resource `
  --resource-group phonecodex-dev `
  --query "[?contains(name, 'gpt-5.6') || contains(name, 'gpt-6')].{name:name, version:version, sku:skus[0].name}" `
  -o table
```

Create missing classifier deployments only on this account:

```powershell
az cognitiveservices account deployment create `
  --name ay186mnc-1561-resource --resource-group phonecodex-dev `
  --deployment-name pc-lab-terra --model-name gpt-5.6-terra `
  --model-version 2026-07-09 --model-format OpenAI `
  --sku-capacity 50 --sku-name GlobalStandard

az cognitiveservices account deployment create `
  --name ay186mnc-1561-resource --resource-group phonecodex-dev `
  --deployment-name pc-lab-luna --model-name gpt-5.6-luna `
  --model-version 2026-07-09 --model-format OpenAI `
  --sku-capacity 50 --sku-name GlobalStandard
```

If quota rejects 50, retry `--sku-capacity 10`. Do not create a new Cognitive Services account.

## App Service env (Portal or `az webapp config appsettings`)

Set on the web app only — never in git:

- `AZURE_OPENAI_ENDPOINT`
- `AZURE_OPENAI_API_KEY`
- `AZURE_OPENAI_DEPLOYMENT=pc-lab-cheap` (required for `hasAzureConfig`; live routes ignore cheap)
- `AZURE_OPENAI_API_VERSION=2024-08-01-preview`
- `AZURE_PROMISE_COMPILER_DEPLOYMENT=pc-lab-astra`
- `AZURE_PROMISE_COMPILER_FALLBACKS=pc-lab-best,pc-lab-strong`
- `AZURE_CLASSIFIER_DEPLOYMENT=pc-lab-terra`
- `AZURE_CLASSIFIER_FALLBACKS=pc-lab-luna,pc-lab-best,pc-lab-strong`
- `AZURE_VISION_DEPLOYMENT=pc-lab-astra`
- `AZURE_VISION_FALLBACKS=pc-lab-vision,pc-lab-best,pc-lab-strong`
- `STRLIX_BACKEND_APP_SECRET` (required; hosted process refuses POST without it)
- `STRLIX_REQUIRE_APP_SECRET=1`
- `AZURE_VISION_EXPERIMENT` — leave unset (OFF)
- Speech keys only if testers need mic Understand

## Localhost still works

```powershell
cd backend
npm start
```

Listens on `http://127.0.0.1:8787`. Empty `STRLIX_BACKEND_APP_SECRET` stays local-open. Phone USB: `adb reverse tcp:8787 tcp:8787` and no `strlix.backendUrl` override.

Closed-beta phone: `local.properties` (gitignored):

```properties
strlix.backendUrl=https://phonecodex-backend.azurewebsites.net
strlix.backendSecret=<same value as App Service STRLIX_BACKEND_APP_SECRET>
```

Then `installDebug`.

## Verify

1. `Invoke-RestMethod https://phonecodex-backend.azurewebsites.net/health` → `ok: true`, compiler `pc-lab-astra`, classifier `pc-lab-terra`, `visionExperiment.enabled=false`
2. POST `/compile-promise` without secret → 401
3. POST with secret → compile JSON (confirm card, not auto-enforce)
4. Local `npm start` + USB reverse still Understands
5. PolicyEngine / emergency allows unchanged

## Redeploy code

Stage `backend/*` plus `evals/prompts` next to `server.js`, zip, then:

```powershell
az webapp deploy --resource-group phonecodex-dev --name phonecodex-backend --src-path .tmp-azure-deploy/app.zip --type zip
```

Build the zip from a commit, not the working tree: `git archive <sha> backend evals/prompts`, move `backend/*` to the zip root, keep `evals/prompts` next to `server.js`, and zip with `tar -a -c -f app.zip *` (forward-slash paths). Set App Setting `STRLIX_BUILD_ID=<sha>`; `/health` then reports it as `build`. On F1 the synchronous deploy poll can hang at "Starting the site"; deploy with `--async true` and poll `/health` until `build` equals the SHA.

## Staging

The plan is F1 Free (no deployment slots). Staging is a second web app on the same plan: `phonecodex-backend-staging` (settings copied from production, plus `PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09`). It shares F1's daily CPU quota with production, so delete it when not testing:

```powershell
az webapp delete -g phonecodex-dev -n phonecodex-backend-staging
```

Gate: staging `/health` `build` equals the SHA, v09, vision off, secret required; POST without the secret returns 401; production `/health` is unchanged.
