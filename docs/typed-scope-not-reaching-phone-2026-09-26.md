# Root-Cause Trace: Typed Scope Never Reaches the Phone (2026-09-26)

Research only. No code edited, nothing deployed, no Azure settings changed, no phone state touched. All evidence below came from files already on disk in this repo (local source) and already-downloaded Azure App Service logs (`.tmp-phone-verify-20260926c/weblogs/LogFiles/`) — no live network call to Azure was made for this investigation.

## The exact break point

**File:** `backend/promiseCompilerService.js:22-39` (`resolvePromptVersion()`), specifically the default at line 26:
```
process.env.PROMISE_COMPILER_PROMPT_VERSION || process.env.PROMISE_COMPILER_PROMPT || "promise_compiler_v07"
```
**Gate that depends on it:** `promiseCompilerService.js:473-477`
```
const SCOPE_CONTRACT_V1_PROMPTS = new Set(["promise_compiler_v09"]);
function scopeContractFor(promptVersion) {
  return SCOPE_CONTRACT_V1_PROMPTS.has(promptVersion) ? "v1" : "legacy";
}
```
**Consequence:** `applyScopeContract()` (`promiseCompilerService.js:731-742`) checks `scopeContractFor(ctx.promptVersion) !== "v1"` and, when true, unconditionally runs `delete dto.scope;` and strips `scope` off every `clarificationOptions[i]` before the response ever leaves the backend (line 734-741). This is not a bug in the scope logic itself — it is a deliberate, correctly-implemented gate that is closed because the input to it (`promptVersion`) is wrong on the live server.

## Sanitized evidence proving it

Live Azure App Service startup log, from a container that started **today**, no secrets, no user promise text:

```
.tmp-phone-verify-20260926c/weblogs/LogFiles/2026_09_26_10-30-0-64_docker.log:105
2026-09-26T09:06:54.8336944+00:00 ContainerStream:
{"event":"promise_compiler_startup","provider":"azure","deployment":"pc-lab-astra","promptVersion":"promise_compiler_v07"}
```

This line is emitted by `backend/server.js:59-66`, which logs the exact `PROMPT_VERSION` constant exported from `promiseCompilerService.js` (imported at `server.js:14` as `PROMISE_COMPILER_PROMPT_VERSION`). The only way this line can print `"promise_compiler_v07"` is if neither `PROMISE_COMPILER_PROMPT_VERSION` nor `PROMISE_COMPILER_PROMPT` is set as an App Service application setting on the live site — confirmed independently by the hosted-backend runbook (`docs/azure-hosted-backend.md`), whose documented App Service env-var list does not include either variable.

Since `scopeContractFor` maps **only** `"promise_compiler_v09"` to `"v1"`, and the live process is running `"promise_compiler_v07"`, every `/compile-promise` response from this server has `scopeContract: "legacy"` and no `scope` field at all — **deterministically, on every request, regardless of the promise text, the clarification pick, or anything Android does.** This fully and sufficiently explains the phone evidence (`typedScope=legacy`, `enforcementScopePackages=[com.android.chrome]`) without needing any of hypotheses 2-4.

**Additional finding — the live deploy may also be running stale code, independent of the prompt-version setting.** A deployment log confirms a zip-deploy completed today immediately before that startup log (`kudu/deployment/a8f1414118e6-9d027d51-....txt`, deploy window `09:06:26`–`09:06:35`, container restart at `09:06:54` — the same restart that printed the v07 line above). However, local staging snapshots of prior deploy attempts (`.tmp-azure-deploy-20260925000033/backend/promiseCompilerService.js`, `.tmp-azure-deploy-20260925000153/promiseCompilerService.js`, both dated 2026-09-25 00:00, 118,483 bytes) contain **no** `applyScopeContract` or `scopeContractFor` at all — that machinery does not exist in those snapshots. The current local `backend/promiseCompilerService.js` (2026-09-26 17:15, 149,232 bytes — ~26% larger) does contain it. I cannot confirm from the downloaded logs alone whether today's 09:06 deploy shipped the current (scope-contract-v1-capable) code or an older snapshot without the feature — the deployment log shows build/npm-install steps but not a file-level diff or hash I can compare. **This is unverified, not proven**, and matters independently of the prompt-version fix: if the live code predates `applyScopeContract`, setting the prompt version alone would not produce `scopeContract: "v1"` either.

## Distinguishing the five possibilities

| # | Possibility | Verdict |
|---|---|---|
| 1 | Azure is running an older backend/prompt/configuration | **PROVEN** — live `promptVersion` is `promise_compiler_v07` (sanitized startup log, today's restart). This alone is sufficient to explain 100% of observed `typedScope=legacy` behavior. Additionally **unverified but plausible**: the live *code* itself may predate the scope-contract-v1 implementation (see above) — a second, compounding instance of the same category of cause. |
| 2 | Azure returns v1, but the Android parser/mapper drops it | **Not observed, and cannot be tested right now.** Azure never returns v1 today (see #1), so this path is never exercised on the phone. Unverified — neither confirmed nor ruled out for after #1 is fixed. |
| 3 | The selected clarification option loses its typed scope on the follow-up request | **Not observed, and cannot be tested right now**, for the same reason as #2 — there is no typed scope on turn 1 to lose on turn 2 while the server is on v07. Separately, the current local code (`applyScopeContract`, `promiseCompilerService.js:750-772`) already implements exactly the fingerprint-verified carry-through this possibility describes (`carry_snapshot_typed_scope_mismatch` fail-closed check) — but this local code is not proven live (see the deploy-staleness finding above), so its correctness cannot be phone-verified yet either. |
| 4 | The typed scope reaches Android but the binder/store fails to persist it | **Not observed, and cannot be tested right now** — same reasoning; nothing typed ever reaches Android today to test persistence against. |
| 5 | Another cause | The compound cause under #1 (stale prompt-version setting **and possibly stale deployed code**) is the identified cause from the actual request/response path. No other cause was found in the traced path. |

**Why 2/3/4 are "unverified" rather than "ruled out":** they describe real code paths (Android's `CompiledPromiseResponseParser`, `CompiledPromiseMapper`, `ConfirmedPromiseBinder`, `StudyWorldSettingsStore`) that exist and could independently be buggy. But with the live server permanently emitting `scopeContract: "legacy"`, none of that Android code ever receives a `scope` field to parse, map, bind, or persist — so its correctness is simply untested by the phone evidence you have, not confirmed correct. This is exactly why Cursor's phone test "verified legacy and corrupt-scope behavior, but could not verify a valid typed scope" — it structurally cannot, against this server.

## Smallest safe implementation plan (not executed — recommendation only)

1. **Confirm live code freshness first**, before touching any setting: redeploy the current `backend/` directory as-is (no code changes needed — it already exists locally) so the live container is guaranteed to be running the code that contains `applyScopeContract`/`scopeContractFor`. This resolves the "unverified" deploy-staleness finding above as a side effect.
2. **Set the App Service application setting** `PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09` (Portal or `az webapp config appsettings set`, per the existing runbook's pattern for other env vars). This is a configuration change, not a code change — do it only when ready to test, since it changes live model behavior and cost profile (v09 is a different, presumably more expensive/slower prompt than v07).
3. **Restart and re-check the startup log** (`ContainerStream` / `docker.log`) for `{"event":"promise_compiler_startup",...,"promptVersion":"promise_compiler_v09"}` — this is the same safe, secret-free diagnostic used above to prove the current bug; use it again to prove the fix before any further phone testing.
4. Only after step 3 confirms `promptVersion=promise_compiler_v09` live, proceed to phone verification (below) to actually test possibilities 2-4 for the first time.

Compatibility risk: **low for the setting itself** (it only changes which prompt file is loaded; `ALLOWED_PROMPT_VERSIONS` already includes v09 and the code path is designed to handle both). **Medium for cost/latency** — v09 is the newer, more capable prompt and may cost more / respond slower than v07 per the existing model-ladder notes in `docs/azure-hosted-backend.md`; watch the credit-window guidance already documented there.

## Tests and phone checks needed to prove a real v1 scope is received, confirmed, persisted, and enforced

**Safe diagnostic call (after the fix, before broader phone testing):** one `/compile-promise` request with a short synthetic test phrase (not a real user promise), reading back only: `promptVersion`, `scopeContract`, whether `scope` is present, `scope.kind` if present, and `canStartCommitment` — never log the request/response body wholesale, never print the `X-Strlix-App-Secret` header.

**Phone/unit checks, once `scopeContract=v1` is confirmed live:**
1. Compile a browser-video promise ("all video apps and browsers" style) → confirm the response's `scopeContract == "v1"` and `scope.kind` is one of the typed enum values (not null, not `UNRESOLVED` for an unambiguous phrasing).
2. Pick the same clarification option 3 times across repeated compiles of the identical promise text → confirm `typedScopeFingerprint` (or equivalent, e.g. comparing `scope.kind`+`scope.include`) is identical every time — this directly tests possibility 3 (carry-through) for the first time.
3. On Android: verify `CompiledPromiseMapper` actually populates `FocusPromise.scope` (confirm this field exists post the browser-scope review's Phase 2B-2 recommendation — at the time of the last review it did not exist on `FocusPromise.kt`; re-check current state before assuming it's wired, since the codebase has continued to change).
4. Verify `ConfirmedPromiseBinder` writes `enforcementScope` into `StudyWorldSettings` (not just the legacy three fields) — this tests possibility 4.
5. Verify `StudyWorldSettingsStore` persists and reloads `enforcementScope` correctly across an app restart (kill app, reopen, check the stored scope survived) — also possibility 4.
6. Phone enforcement check: start the browser-video promise, open a real video in Chrome and, if installed, Firefox/Brave/Samsung Internet under an active min-length rule; confirm BLOCK in each, and confirm a Chrome search-results page or Docs page does not false-block — this is the actual enforcement proof the whole chain exists to deliver.
7. Re-run the existing `PhoneSurfaceTraceCatalog`/`ScopeMatcherTest` suites to confirm no regression from the App Service setting/deploy change (these are local unit tests, unaffected by what Azure serves, but should be run as a sanity check alongside any redeploy).

## What remains unverified, and why

- **Whether today's live deploy already contains the scope-contract-v1 code** (`applyScopeContract`, `scopeContractFor`, the carry-through fingerprint check) — the downloaded deployment/container logs prove *that* a deploy happened today and *what prompt version it resolved to*, but not the exact file contents that were shipped. Confirming this would require either a live diagnostic endpoint that echoes a code/build version (none found in this pass) or redeploying, which this task explicitly excludes.
- **Whether Android's `CompiledPromiseMapper`/`FocusPromise`/`ConfirmedPromiseBinder`/`StudyWorldSettingsStore` correctly handle a genuine `scopeContract=v1` response** (possibilities 2-4) — structurally untestable against the current live server, since it never emits one. This is the main reason the plan above sequences "fix the server" before "test the phone."
- **Whether `PROMISE_COMPILER_PROMPT_VERSION` is truly unset on Azure**, versus set to something else that also resolves to v07 by coincidence — inferred from the runbook's documented env-var list (which omits it) plus the observed default-fallback value; not confirmed by directly reading the App Service's application settings blade (out of scope for read-only log inspection, and changing/viewing settings was not attempted here per the "don't change Azure settings" constraint — note that viewing, unlike changing, would likely be safe, but was not required to reach a proven root cause here).
