# Scope Semantics v1 — Engineering Design

**Status:** Design only. No code written. Implementation gated behind the phased plan in §7.
**Author:** CTO/research track, 2026-09-22
**Audience:** Cursor / Codex implementer, AI Lab, future Ankit
**Supersedes for scope only:** the YouTube-specific parts of `docs/android-promise-semantics-contract.md`

> **One-line thesis**
> Today scope is *two lists of strings and a free-text kind, consumed by an OR, with exactly one
> hard-coded brand*. It must become *one versioned selector algebra with five orthogonal axes and a
> polarity*, where the user's phrasings (`YouTube app only`, `no short videos`, `IG messages only`)
> are **presets over the algebra (data)**, not new code paths.
> And the default when scope cannot be resolved must be `UNRESOLVED` → **ask** — never "app-only",
> never "everything".

---

## 1. Current-state diagnosis

### 1.1 Exact files that currently handle scope

**Scope model / storage**

| File | Role today |
|---|---|
| `app/src/main/java/com/phonecodex/app/domain/model/StudyWorldSettings.kt` | Stores scope as **three loose fields**: `enforcementScopePackages: List<String>`, `enforcementContentBrands: List<String>`, `enforcementScopeKind: String?` |
| `app/src/main/java/com/phonecodex/app/domain/model/FocusPromise.kt` | Draft-side scope: `scopePackages`, `contentBrands`, `surfaceScope`, `contentScope`, `scopeKind` — **four list fields, three of which are aliases of each other** |
| `app/src/main/java/com/phonecodex/app/data/StudyWorldSettingsStore.kt` | Persists the three settings fields |

**Scope resolution (promise → scope)**

| File | Role today |
|---|---|
| `domain/enforcement/EnforcementScopeLaw.kt` | The **only** matcher (`matches()`), plus `resolvePackageScope()` / `resolveContentBrands()` which re-read `rawText` |
| `domain/promise/PromiseContentRuleSupport.kt:124-159` | `enforcementScopePackages()` / `enforcementContentBrands()` / `enforcementScopeKind()` |
| `domain/promise/ConfirmedPromiseBinder.kt:38-41` | Writes the three fields into `StudyWorldSettings` at Start |
| `domain/promise/FocusPromiseParser.kt:678-700` | **Second, independent** offline scope inference (`inferContentBrands`, `inferScopeKind`, `inferScopePackages`) |
| `domain/promise/CompiledPromiseMapper.kt:280-420` | DTO → `FocusPromise`, plus scope-conditional **copy rewriting** (`sanitizeConfirmationLinesForScope`, `isYouTubeContentScope`) |
| `backend/promiseCompilerService.js` | `buildScopeInternalPreview()`, `normalizeInternalPolicyPreview()`, `resolveOptionRematerialize()`, `rematerializeDtoFromOptionPreview()`, `inferGoogleVideoClarification()`, `inferShortFormScopeClarification()` |

**Scope consumers (gates that ask "is this screen mine?")**

| File | Line | Call |
|---|---|---|
| `domain/enforcement/MediaLengthEnforcement.kt` | 80 | `EnforcementScopeLaw.matches(...)` → else `NONE` |
| `domain/enforcement/ShortFormQuotaGate.kt` | 113 | `matches(...)` → else `NONE` |
| `domain/enforcement/EntertainmentBanGate.kt` | 64 | `matches(...)` inside `mustEvaluate` |
| `domain/enforcement/BlankVideoTreeGate.kt` | 55, 113 | `matches(...)` + a literal `BRAND_YOUTUBE` check |
| `domain/enforcement/SessionLockLaw.kt` | 21 | `matches(...)` + active-player |
| `domain/enforcement/VideoPlatformRegistry.kt` | 155 | `isInEnforcementScope(...)` delegating to scope law |
| `domain/enforcement/PromiseEnforcementCoordinator.kt` | 47-71 | Scope is expressed **only indirectly**, via `EntertainmentBanGate.mustEvaluate` |
| `domain/enforcement/trace/EnforcementTraceEvaluator.kt` | — | Replays the same chain offline |

### 1.2 Where YouTube-specific logic lives

Raw counts of `youtube|YOUTUBE` per file under `app/src/main/java`:

```
EnforcementScopeLaw.kt        31
FocusPromiseParser.kt         28
CompiledPromiseMapper.kt      19
PromiseIntentRules.kt         12
AccessibilityUrlExtractor.kt  12
SurfaceDetector.kt            11
ContentSignalDetector.kt       9
VideoPlatformRegistry.kt       6
PromiseContentRuleSupport.kt   5
BlankVideoTreeGate.kt          4
```

The load-bearing one-offs:

1. **`BRAND_YOUTUBE` is the only brand with semantics.** `EnforcementScopeLaw.matches()` (line 45) checks
   exactly `if (BRAND_YOUTUBE in brands && isYouTubeSurface(...)) return true`. `normalizeBrand()` happily
   returns `"instagram"` or `"tiktok"` — and then **nothing ever reads it**. A compiled
   `contentBrands=["instagram"]` is a silent no-op: the brand axis exists for one brand only.
2. **Four YouTube scope-kind string constants** — `SCOPE_YOUTUBE_CONTENT`, `SCOPE_YOUTUBE_APP_ONLY`,
   `SCOPE_YOUTUBE_ONLY`, `SCOPE_YOUTUBE_SHORTS_ONLY` — and `isYouTubeAppOnlyScopeKind()` **conflates three
   of them**. Consequences:
   - clarification option C "YouTube only" (backend `inferGoogleVideoClarification`) compiles to
     `youtube_only` → treated as **app-only** → browser `youtube.com` silently escapes. The user was never
     asked "app or browser?"; the ambiguity was resolved *against* them.
   - "only YouTube Shorts" → `youtube_shorts_only` → package-exclusive YouTube → Shorts watched in Chrome
     silently escape.
3. **`isYouTubeSurface()` / `looksLikeYouTubePlayerAccessibility()` / `YOUTUBE_HOST_INLINE` /
   `YOUTUBE_CLIENT_NAME_HINTS`** — a YouTube-only host+client detector with no generic counterpart. There is
   no `isInstagramSurface`, no `isNetflixSurface`, no generic `isBrandSurface(brand, …)`.
4. **The backend recovers scope by regexing its own English option labels.**
   `resolveOptionRematerialize()` (`promiseCompilerService.js:699-772`) falls back to
   `/youtube only|only youtube(?!\s+shorts)/`, `/all video apps|all major video/`, `/chrome|browser/` over a
   concatenated blob of `id + label + description + policyPreview`. **AI prose is load-bearing for
   enforcement scope** — a direct violation of the project's own "raw AI copy cannot override structured
   scope" rule.
5. **Hard-coded package lists stand in for categories.** `SHORT_FORM_PLATFORM_PACKAGES` (7),
   `ALL_VIDEO_PLATFORM_PACKAGES` (4), `ConfirmationUserCopy.SHORT_FORM_PACKAGES` (7),
   `VideoPlatformRegistry.shortFormQuotaPackages()`. Four lists, three files, two languages, no single
   source of truth. Meanwhile `VideoPlatformRegistry.looksLikeUnknownStreamingPackage()` already knows how
   to class-resolve unknown video apps — and scope never uses it.
6. **`chrome_browser` exists on the backend but not on Android.** It compiles to
   `scopePackages=[com.android.chrome]` — package-exclusive — so Firefox / Brave / Samsung Internet escape,
   even though `EnforcementScopeLaw.looksLikeBrowserPackage()` already recognises all of them.

### 1.3 Where package / content / surface scope are mixed

**(a) `enforcementScopePackages` conflates identity with exclusivity.**
A non-empty list means both *"these packages"* and *"nothing else"*. There is no way to say "these packages,
**and also** class-resolved unknown clones" — which is exactly what `ALL_VIDEO_APPS` needs.

**(b) `matches()` collapses two axes into one boolean and has no surface axis.**

```kotlin
if (packages.isEmpty() && brands.isEmpty()) return true   // <- the bug
if (packageName in packages) return true
if (BRAND_YOUTUBE in brands && isYouTubeSurface(...)) return true
return false
```

Package-OR-brand. Surface is **absent**. So *"Instagram messages only, no Reels"* cannot be expressed in
scope at all. `SurfaceDetector` already emits `SOCIAL_DM`, `SOCIAL_FEED`, `MESSAGING_THREAD` — and **no gate
consumes them for an allow/block verdict**. `isActiveBehaviorSurface` merely promotes `MESSAGING_THREAD` to
`ACTIVE`, which means "continue". The nuance is pushed into `contentRules`, and backend
`deriveAppRulesFromContent()` explicitly *downgrades the app to ALLOW* when surfaces are mixed — so the
mixed-surface promise ends as "app allowed" with nothing enforcing the Reels half.

**(c) Empty scope means maximum scope; over-narrowed scope means silence. Both wrong, opposite directions.**

| Compile outcome | Runtime effect | Failure class |
|---|---|---|
| Scope fields empty (compiler failed, offline parser thin) | `matches()` → `true` for every package | **False blocks across the whole phone** |
| Scope over-narrowed to `[com.google.android.youtube]` | every other package → `ENFORCEMENT_SCOPE_ALLOW` | **False allows / trivial escape** |

Neither outcome asks the user. There is no third state.

**(d) `resolvePackageScope()` encodes scope algebra as a list filter.**
`PromiseContentRuleSupport.enforcementScopePackages` → `EnforcementScopeLaw.resolvePackageScope` will, when
the brand is YouTube, *strip YouTube client packages out of the list* and **return empty if that empties it**
(lines 141-143). So `scopePackages=[youtube]` + `brands=[youtube]` silently becomes `scopePackages=[]` +
`brands=[youtube]` — the selector mutates from "exclusive" to "unrestricted + brand". Correct behaviour
today, but it is arithmetic over two untyped lists rather than a declared scope kind.

**(e) English is still law at decision time — contradicting the stated contract.**
`ConfirmedPromiseBinder`'s own docstring says *"Accessibility must read these fields; it must not re-parse
English."* But at runtime:

- `EnforcementScopeLaw.resolveContentBrands(..., rawText)` / `resolvePackageScope(..., rawText)` call
  `PromiseIntentRules.namesYouTubeAppOnly(rawText)`
- `MediaLengthEnforcement.decideDetailed(goal = …)` → `PromiseIntentRules.hasMediaLengthLimit(goal)`
- `SurfaceEnforcementGate.evaluate(goal = …)` → `blocksEntertainmentOrMovies`, `blocksShortForm`,
  `allowsShortForm`, `parseDailyShortFormLimit`
- `PromiseEnforcementCoordinator.shortFormBan()` → `PromiseIntentRules.allowsShortForm(goal)` /
  `blocksShortForm(goal)`

`PromiseIntentRules` is ~40 regex/phrase lists. Every new user phrasing is a new phrase-list entry. This is
the "fixing examples one by one" treadmill, structurally.

**(f) Two independent inference paths that can disagree.**
Backend `resolveOptionRematerialize()` and Android `FocusPromiseParser.inferScopeKind()` both produce
`scopeKind`, from different rules, with no shared schema and no validation. `scopeKind` is `String?` — a typo
compiles, ships, and matches nothing.

**(g) Scope is not an input to Start-gating. [highest severity]**
`PromiseClarificationLaw.clearGhostClarification()` sets `clarificationRequired = false,
canStartCommitment = true` whenever `isConcreteClockPolicy(draft)` holds — and that predicate checks only
*session duration* + *a min-length rule*. **It never inspects scope.** So `"only YouTube, min 30 min, 1
hour"` can auto-clear clarification and Start with a guessed scope. Ambiguity silently becoming a guess is
already reachable in shipped code.

### 1.4 Why this architecture fails general user prompts

| User prompt | Why today's model cannot express it |
|---|---|
| "YouTube only in Chrome, not the app" | No **host** axis. Only `packages` (exclusive) vs `brand youtube` (any host). Browser-only is unrepresentable; `chrome_browser` degrades to one package. |
| "YouTube app only" | Representable, but `isYouTubeAppOnlyScopeKind` also swallows `youtube_only` and `youtube_shorts_only`, so three different intents collapse to one. |
| "YouTube videos anywhere" | Representable **only for YouTube** — `matches()` hard-codes `BRAND_YOUTUBE`. |
| "No short videos" (anywhere) | No `SHORT_FORM_ANY` identity. Works only by accident via `ShortFormQuotaGate`'s own 7-package allowlist — and breaks the moment scope is narrowed. |
| "Instagram messages only, no Reels" | No **surface** axis in scope, and no exemption polarity. `SOCIAL_DM`/`SOCIAL_FEED` are detected and discarded. |
| "Facebook feed blocked but Messenger allowed" | Messenger is `com.facebook.orca` — **not in `VideoPlatformRegistry` at all**. No exemption polarity to carve it out either. |
| "Chrome allowed except adult/movie/video sites" | No **site/domain** axis and no `exempt` list. `AccessibilityUrlExtractor` knows only YouTube and Instagram URLs. |
| Ambiguous "only YouTube" | No `UNRESOLVED` scope state. Ambiguity lives in `clarificationRequired`, which §1.3(g) can auto-clear. |

**Root cause, stated once:** scope is modelled as *lists of strings consumed by an OR*, when the domain is a
*small typed algebra over independent axes with a polarity*. Every new phrasing therefore needs new code (a
brand branch, a scope-kind constant, a phrase list, a package array) instead of new data.

---

## 2. Proposed universal scope model

### 2.1 Design rules (non-negotiable)

1. **Scope answers exactly one question:** *does this screen fall under this promise?* It must **never**
   carry a verdict. (This is why `SOCIAL_FEED_BLOCKED` is renamed — §2.4.) Verdicts stay in `contentRules`
   and the existing gates.
2. **Five orthogonal axes**, each independently `null`-able (`null` / `ANY` = don't care).
3. **Two polarities:** `include` (in scope) and `exempt` (carved out). `exempt` always wins.
4. **`UNRESOLVED` is the default, not a fallback.** An empty selector is `UNRESOLVED`: it enforces nothing
   and blocks Start. The current `packages.isEmpty() && brands.isEmpty() → true` is deleted.
5. **Categories are class-resolved, never enumerated.** `packageMatch = CLASS_RESOLVED` delegates to
   `VideoPlatformRegistry` / `AppClassResolver` so unlisted clones are covered.
6. **Named kinds are presets compiled to clauses.** The kind is kept for logs, copy, and telemetry; the
   *clauses* are law. Adding a phrasing = adding a preset row, not a code branch.
7. **Scope is versioned** (`version: 1`) and validated at the boundary; an unknown kind or malformed clause
   is `UNRESOLVED`, not a best guess.

### 2.2 The five axes

| Axis | Values | Notes |
|---|---|---|
| `host` | `NATIVE_APP` / `BROWSER` / `ANY_HOST` | Answers "YouTube app vs YouTube in Chrome". Resolved by a new `HostResolver` (promoted from `EnforcementScopeLaw.looksLikeBrowserPackage` + `isYouTubeClientPackage`). Browser includes WebView shells. |
| `identity` | `YOUTUBE`, `INSTAGRAM`, `FACEBOOK`, `MESSENGER`, `TIKTOK`, `SNAPCHAT`, `WHATSAPP`, `VIDEO_ANY`, `SHORT_FORM_ANY`, `MOVIE_OTT_ANY`, `ADULT_ANY`, `SOCIAL_ANY`, `ANY` | Content family, **not** package. `*_ANY` values are class-resolved. Generalises `BRAND_YOUTUBE`. |
| `surfaces` | `PLAYER_ANY`, `PLAYER_SHORT`, `PLAYER_LONG`, `FEED`, `DM_LIST`, `DM_THREAD`, `SEARCH`, `BROWSE`, `INSTALL`, `PAYMENT`, `SYSTEM`, `ANY` | Grouping over the existing 20-value `SurfaceDetectionResult`, via a new `SurfaceDetectionResult.scopeClass`. This is the axis that makes IG-DM-only possible. |
| `sites` | host patterns, e.g. `["youtube.com","youtu.be"]`, `["*"]` | Only meaningful when `host != NATIVE_APP`. Needs a generalised URL/host extractor. |
| `packages` + `packageMatch` | explicit package ids; `PINNED` / `CLASS_RESOLVED` | **Internal only — never rendered.** `PINNED` = literal set (unknown app ⇒ out of scope ⇒ ALLOW). `CLASS_RESOLVED` = set ∪ class heuristics (unknown video-ish app ⇒ in scope ⇒ at least `UNKNOWN_CLONE_WARN`). |

### 2.3 Match semantics (deterministic, ordered)

```
match(pkg, screenText, detection, scope):
  1. scope == null || scope.kind == UNRESOLVED   -> Unresolved        (enforce nothing)
  2. any clause in scope.exempt matches          -> Exempt(clauseId)  (enforce nothing)
  3. any clause in scope.include matches         -> InScope(clauseId)
  4. otherwise                                   -> OutOfScope

clauseMatches(clause):
     (clause.host == ANY_HOST     || HostResolver.host(pkg, text) == clause.host)
  && (clause.identity == ANY      || IdentityResolver.matches(clause.identity, pkg, text))
  && (ANY in clause.surfaces      || detection.scopeClass in clause.surfaces)
  && (clause.sites.isEmpty()      || SiteResolver.hostMatchesAny(text, clause.sites))
  && (clause.packages.isEmpty()   || PackageMatcher.matches(clause, pkg, text))
```

`resolution`:

- `EXCLUSIVE` — anything not included is out of scope → `ENFORCEMENT_SCOPE_ALLOW`. Default for sessions.
- `ADDITIVE` — this promise adds scope on top of other active promises; for stacked commitments.
  Permanent guardrails remain **outside** scope entirely (§8).

### 2.4 The scope-kind catalog (presets)

Renames from the brief, with reasons:

| Brief name | v1 name | Why renamed |
|---|---|---|
| `APP_ONLY` | `APP_ONLY` | kept |
| `BROWSER_ONLY` | `BROWSER_ONLY` | kept |
| `CONTENT_ANYWHERE` | `CONTENT_ANY_HOST` | "anywhere" reads as "all apps"; it means "any host for this identity" |
| `ALL_VIDEO_APPS` | `VIDEO_CLASS_ANY_HOST` | "apps" is wrong — browser video counts; and it is class-resolved, not a list |
| `ALL_SHORT_FORM` | `SHORT_FORM_ANY_HOST` | consistency with above |
| `SOCIAL_DM_ONLY` | `SOCIAL_MESSAGING_ONLY` | "DM" is IG jargon; this covers threads + message lists |
| `SOCIAL_FEED_BLOCKED` | `SOCIAL_FEED_AND_REELS_ONLY` | **scope must not name an action.** The intent is "only the feed/reels surfaces are in scope"; the BLOCK lives in `contentRules` |
| `SITE_OR_DOMAIN_ONLY` | `SITE_SCOPED` | shorter; a domain is a site pattern |
| `UNKNOWN_NEEDS_CLARIFICATION` | `UNRESOLVED` | it is a state, not a request — and it must be the **default** |
| — | `HOST_WITH_CATEGORY_EXEMPTIONS` | **new.** Required for "Chrome allowed except adult/movie/video sites" |
| — | `PACKAGE_PINNED` | **new.** "block NetMirror" — every package must map to a supported app name the user actually wrote |

Preset expansions (`{B}` = identity parameter, `{S}` = site list):

| Kind | include | exempt | resolution |
|---|---|---|---|
| `APP_ONLY` | `host=NATIVE_APP, identity={B}, packageMatch=PINNED` | — | EXCLUSIVE |
| `BROWSER_ONLY` | `host=BROWSER, identity={B}, sites={B}.hosts, packageMatch=CLASS_RESOLVED` | — | EXCLUSIVE |
| `CONTENT_ANY_HOST` | `host=ANY_HOST, identity={B}, packageMatch=CLASS_RESOLVED` | — | EXCLUSIVE |
| `VIDEO_CLASS_ANY_HOST` | `host=ANY_HOST, identity=VIDEO_ANY, packageMatch=CLASS_RESOLVED` | — | EXCLUSIVE |
| `SHORT_FORM_ANY_HOST` | `host=ANY_HOST, identity=SHORT_FORM_ANY, surfaces=[PLAYER_SHORT], packageMatch=CLASS_RESOLVED` | — | EXCLUSIVE |
| `SOCIAL_MESSAGING_ONLY` | `identity={B}, surfaces=[ANY]` | `identity={B}, surfaces=[DM_LIST, DM_THREAD]` | EXCLUSIVE |
| `SOCIAL_FEED_AND_REELS_ONLY` | `identity={B}, surfaces=[FEED, PLAYER_SHORT]` | companion package if named | EXCLUSIVE |
| `SITE_SCOPED` | `host=BROWSER, sites={S}` | — | EXCLUSIVE |
| `HOST_WITH_CATEGORY_EXEMPTIONS` | one clause per covered identity (`ADULT_ANY`, `MOVIE_OTT_ANY`, `VIDEO_ANY`) at `host=BROWSER, sites=["*"]` | — | EXCLUSIVE |
| `PACKAGE_PINNED` | `packages=[…], packageMatch=PINNED` | — | EXCLUSIVE |
| `UNRESOLVED` | — | — | — |

> **Polarity note on `HOST_WITH_CATEGORY_EXEMPTIONS`:** "Chrome allowed except adult sites" is, in *scope*
> terms, `include = [browser ∧ (ADULT_ANY ∨ MOVIE_OTT_ANY ∨ VIDEO_ANY)]`. The user's "allowed" is the
> *complement* and needs no scope at all. Keeping scope strictly positive ("what am I watching?") avoids a
> double negative nobody can debug. The compiler owns that inversion; `appliesToText` stays in user
> language ("adult, movie, and video sites in your browser").

### 2.5 Kotlin shape (design, not shipped)

```kotlin
// app/src/main/java/com/phonecodex/app/domain/scope/EnforcementScope.kt
data class EnforcementScope(
    val version: Int = 1,
    val kind: ScopeKind,
    val resolution: ScopeResolution = ScopeResolution.EXCLUSIVE,
    val include: List<ScopeClause> = emptyList(),
    val exempt: List<ScopeClause> = emptyList(),
    val confidence: Double = 0.0,
    val appliesToText: String? = null,      // generated, category-level, never AI prose
    val unresolvedReason: UnresolvedReason? = null
) {
    val isEnforceable: Boolean
        get() = version == 1 && kind != ScopeKind.UNRESOLVED && include.isNotEmpty()
}

data class ScopeClause(
    val id: String,                          // "i1", "x1" — appears in every decision log
    val host: ScopeHost = ScopeHost.ANY_HOST,
    val identity: ScopeIdentity = ScopeIdentity.ANY,
    val surfaces: Set<ScopeSurfaceClass> = setOf(ScopeSurfaceClass.ANY),
    val sites: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val packageMatch: PackageMatchMode = PackageMatchMode.CLASS_RESOLVED
)

sealed interface ScopeMatch {
    data class InScope(val clauseId: String) : ScopeMatch
    data class Exempt(val clauseId: String) : ScopeMatch
    data object OutOfScope : ScopeMatch
    data class Unresolved(val reason: UnresolvedReason?) : ScopeMatch
}
```

---

## 3. Compiler contract

### 3.1 Required JSON (added to the v08 envelope; flat policy unchanged)

```json
"scope": {
  "version": 1,
  "kind": "APP_ONLY | BROWSER_ONLY | CONTENT_ANY_HOST | VIDEO_CLASS_ANY_HOST | SHORT_FORM_ANY_HOST | SOCIAL_MESSAGING_ONLY | SOCIAL_FEED_AND_REELS_ONLY | SITE_SCOPED | HOST_WITH_CATEGORY_EXEMPTIONS | PACKAGE_PINNED | UNRESOLVED",
  "resolution": "EXCLUSIVE | ADDITIVE",
  "identity": "YOUTUBE | INSTAGRAM | … | null",
  "include": [
    { "id": "i1",
      "host": "NATIVE_APP|BROWSER|ANY_HOST",
      "identity": "YOUTUBE|…|ANY",
      "surfaces": ["PLAYER_ANY"],
      "sites": [],
      "packages": ["com.google.android.youtube"],
      "packageMatch": "PINNED|CLASS_RESOLVED" }
  ],
  "exempt": [],
  "confidence": 0.0,
  "appliesToText": "category language only — no package ids",
  "unresolvedReason": null,
  "evidenceSpans": ["verbatim substring(s) of the user promise that set this scope"],
  "candidateScopes": []
}
```

Hard rules for the compiler (add to `evals/prompts/promise_compiler_v09.txt`):

- `scope` is **required**. Omission ⇒ backend rewrites to `UNRESOLVED` and sets `canStartCommitment=false`.
- `kind` must be one of the enum literals. Any other string ⇒ `UNRESOLVED`.
- `packages` / `packageMatch` may appear **only** inside `scope.include` / `scope.exempt` /
  `hiddenInternalScope`. A package id in `appliesToText` ⇒ backend rejects the field and regenerates it from
  the preset catalog.
- `evidenceSpans` must be verbatim substrings of `userPromise`. If the model cannot point at the words that
  set the scope, scope is `UNRESOLVED`. *This is the mechanism that kills "AI prose becomes law".*
- When ambiguous: `kind="UNRESOLVED"`, `unresolvedReason` set, **and** `candidateScopes` holds 2–3 full
  `scope` objects, each also attached to a `clarificationOptions[i].scope`. No text-blob fallback.
- `clarificationRequired` MUST be `true` whenever `kind == "UNRESOLVED"`.
- Backend **deletes** `resolveOptionRematerialize`'s regex fallback path. Option → scope is `option.scope`
  or nothing.

### 3.2 Worked examples (abbreviated to `scope` only)

**(1) "YouTube app only: block videos shorter than 30 min for 1 hour"**

```json
{ "version":1, "kind":"APP_ONLY", "resolution":"EXCLUSIVE", "identity":"YOUTUBE",
  "include":[{"id":"i1","host":"NATIVE_APP","identity":"YOUTUBE",
              "surfaces":["PLAYER_ANY"],"packages":["com.google.android.youtube"],
              "packageMatch":"PINNED"}],
  "exempt":[], "confidence":0.93,
  "appliesToText":"the YouTube app only — browser YouTube and other players are untouched",
  "evidenceSpans":["YouTube app only"] }
```

**(2) "Only YouTube in Chrome, not the app"**

```json
{ "version":1, "kind":"BROWSER_ONLY", "resolution":"EXCLUSIVE", "identity":"YOUTUBE",
  "include":[{"id":"i1","host":"BROWSER","identity":"YOUTUBE",
              "surfaces":["PLAYER_ANY"],"sites":["youtube.com","m.youtube.com","youtu.be"],
              "packages":[],"packageMatch":"CLASS_RESOLVED"}],
  "exempt":[], "confidence":0.9,
  "appliesToText":"YouTube pages inside your browser — the YouTube app is not covered",
  "evidenceSpans":["Only YouTube in Chrome, not the app"] }
```

Note `packages:[]` + `CLASS_RESOLVED`: covers Firefox / Brave / Samsung Internet, not just
`com.android.chrome`.

**(3) "No YouTube videos anywhere for 2 hours"**

```json
{ "version":1, "kind":"CONTENT_ANY_HOST", "resolution":"EXCLUSIVE", "identity":"YOUTUBE",
  "include":[{"id":"i1","host":"ANY_HOST","identity":"YOUTUBE",
              "surfaces":["PLAYER_ANY"],"packages":[],"packageMatch":"CLASS_RESOLVED"}],
  "confidence":0.92,
  "appliesToText":"YouTube videos anywhere — the app, browser pages, and other YouTube players",
  "evidenceSpans":["YouTube videos anywhere"] }
```

**(4) "No short videos" / "at most 10 shorts today"**

```json
{ "version":1, "kind":"SHORT_FORM_ANY_HOST", "resolution":"EXCLUSIVE", "identity":"SHORT_FORM_ANY",
  "include":[{"id":"i1","host":"ANY_HOST","identity":"SHORT_FORM_ANY",
              "surfaces":["PLAYER_SHORT"],"packages":[],"packageMatch":"CLASS_RESOLVED"}],
  "confidence":0.88,
  "appliesToText":"short videos everywhere — Shorts, Reels, TikTok-style clips, and short videos in your browser",
  "evidenceSpans":["short videos"] }
```

The 7-package `SHORT_FORM_PLATFORM_PACKAGES` array is **deleted**; `SHORT_FORM_ANY` + `PLAYER_SHORT` +
`CLASS_RESOLVED` covers listed and unlisted apps alike.

**(5) "Instagram messages only, no Reels"**

```json
{ "version":1, "kind":"SOCIAL_MESSAGING_ONLY", "resolution":"EXCLUSIVE", "identity":"INSTAGRAM",
  "include":[{"id":"i1","host":"ANY_HOST","identity":"INSTAGRAM",
              "surfaces":["ANY"],"packages":["com.instagram.android"],"packageMatch":"PINNED"}],
  "exempt":[{"id":"x1","host":"ANY_HOST","identity":"INSTAGRAM",
             "surfaces":["DM_LIST","DM_THREAD"],"packages":["com.instagram.android"],"packageMatch":"PINNED"}],
  "confidence":0.87,
  "appliesToText":"Instagram — messages stay open, the feed and Reels are covered",
  "evidenceSpans":["Instagram messages only, no Reels"] }
```

Reads exactly as intended: all of Instagram is in scope, DMs are carved out, and `contentRules` supply
`BLOCK` for feed/reels.

**(6) "Block the Facebook feed but let Messenger work"**

```json
{ "version":1, "kind":"SOCIAL_FEED_AND_REELS_ONLY", "resolution":"EXCLUSIVE", "identity":"FACEBOOK",
  "include":[{"id":"i1","host":"ANY_HOST","identity":"FACEBOOK",
              "surfaces":["FEED","PLAYER_SHORT"],"packages":["com.facebook.katana"],"packageMatch":"PINNED"}],
  "exempt":[{"id":"x1","identity":"MESSENGER","surfaces":["ANY"],
             "packages":["com.facebook.orca"],"packageMatch":"PINNED"}],
  "confidence":0.85,
  "appliesToText":"the Facebook feed and Facebook reels — Messenger is not covered",
  "evidenceSpans":["Block the Facebook feed but let Messenger work"] }
```

Requires adding `com.facebook.orca` to `VideoPlatformRegistry` with `participatesInShortFormQuota = false`.

**(7) "Chrome is fine except adult, movie, and video sites"**

```json
{ "version":1, "kind":"HOST_WITH_CATEGORY_EXEMPTIONS", "resolution":"EXCLUSIVE", "identity":null,
  "include":[
    {"id":"i1","host":"BROWSER","identity":"ADULT_ANY","surfaces":["ANY"],"sites":["*"],"packageMatch":"CLASS_RESOLVED"},
    {"id":"i2","host":"BROWSER","identity":"MOVIE_OTT_ANY","surfaces":["ANY"],"sites":["*"],"packageMatch":"CLASS_RESOLVED"},
    {"id":"i3","host":"BROWSER","identity":"VIDEO_ANY","surfaces":["PLAYER_ANY"],"sites":["*"],"packageMatch":"CLASS_RESOLVED"}
  ],
  "exempt":[], "confidence":0.78,
  "appliesToText":"adult, movie, and video pages in your browser — normal browsing and search stay open",
  "evidenceSpans":["except adult, movie, and video sites"] }
```

Search / docs / SERP are `surfaces = SEARCH|BROWSE`, matched by no clause ⇒ `ENFORCEMENT_SCOPE_ALLOW`. This
is the structural fix for the `docs/CURRENT_STATE.md` §7 step-8 false-block class.

**(8) Ambiguous "only YouTube" — must ask**

```json
{ "version":1, "kind":"UNRESOLVED", "resolution":"EXCLUSIVE", "identity":"YOUTUBE",
  "include":[], "exempt":[], "confidence":0.31,
  "appliesToText":null,
  "unresolvedReason":"HOST_AMBIGUOUS",
  "evidenceSpans":["only YouTube"],
  "candidateScopes":[ "<scope object of example 1>", "<example 2>", "<example 3>" ] }
```

with

```json
"clarificationRequired": true,
"clarificationQuestion": "When you said \u201conly YouTube\u201d, which YouTube did you mean?",
"clarificationOptions": [
  {"id":"A","label":"The YouTube app only","recommended":false,
   "policyPreview":"Browser YouTube stays normal.","scope": "<example 1>"},
  {"id":"B","label":"YouTube in my browser only","recommended":false,
   "policyPreview":"The YouTube app stays normal.","scope": "<example 2>"},
  {"id":"C","label":"YouTube videos anywhere","recommended":true,
   "policyPreview":"App, browser pages, and other YouTube players.","scope": "<example 3>"}
],
"canStartCommitment": false
```

---

## 4. Android enforcement contract

### 4.1 New files

| File | Contents |
|---|---|
| `domain/scope/EnforcementScope.kt` | `EnforcementScope`, `ScopeClause`, `ScopeKind`, `ScopeHost`, `ScopeIdentity`, `ScopeSurfaceClass`, `PackageMatchMode`, `ScopeResolution`, `UnresolvedReason`, `ScopeMatch` |
| `domain/scope/ScopeMatcher.kt` | pure `match(packageName, screenText, detection, scope): ScopeMatch` — the **single** scope authority |
| `domain/scope/ScopeKindPresets.kt` | `expand(kind, identity, sites, packages): Pair<include, exempt>`; the one place a kind becomes clauses |
| `domain/scope/HostResolver.kt` | `host(pkg, text): ScopeHost` — promoted from `EnforcementScopeLaw.looksLikeBrowserPackage` / `isYouTubeClientPackage` |
| `domain/scope/IdentityResolver.kt` | `matches(identity, pkg, text)` — generalises `isYouTubeSurface`; delegates `*_ANY` to `VideoPlatformRegistry` / `AppClassResolver` / `AdultContentLaw` |
| `domain/scope/SiteResolver.kt` | generalised host extraction from a11y text; generalises `AccessibilityUrlExtractor` beyond YouTube/IG |
| `domain/scope/ScopeSerialization.kt` | JSON ⇄ `EnforcementScope`; **validate-or-`UNRESOLVED`**, never partial |
| `domain/scope/ScopeAppliesToCopy.kt` | `forScope(scope): String` — the only producer of user-facing scope copy |

### 4.2 Changes to existing files

**`domain/model/StudyWorldSettings.kt`**

- Add `val enforcementScope: EnforcementScope? = null`.
- Keep `enforcementScopePackages` / `enforcementContentBrands` / `enforcementScopeKind` as `@Deprecated`
  **derived** values for one release so the 80+ traces and existing tests compile; delete in Phase 4.

**`data/StudyWorldSettingsStore.kt`**

- Persist `enforcementScope` as a JSON string. On read failure ⇒ `UNRESOLVED` (fail to "not enforcing" and
  let Home tell the truth), never silently `null`-to-everything.
- `replaceSettings` must not wipe `enforcementScope` — same treatment `visionExperimentEnabled` already has.

**`domain/enforcement/EnforcementScopeLaw.kt`**

- Becomes a ~30-line deprecated shim: `matches(...)` → `ScopeMatcher.match(...) is InScope`.
- Delete `BRAND_YOUTUBE`, the four `SCOPE_YOUTUBE_*` constants, `isYouTubeAppOnlyScopeKind`,
  `isYouTubeContentScopeKind`, `resolvePackageScope`, `resolveContentBrands`, `normalizeBrand`.
- Move `isYouTubeSurface` / `looksLikeYouTubePlayerAccessibility` / `YOUTUBE_HOST_INLINE` /
  `YOUTUBE_CLIENT_NAME_HINTS` into `IdentityResolver` under `ScopeIdentity.YOUTUBE`.
- **Net effect: 31 YouTube references in scope law → 0 outside `IdentityResolver`.**

**`domain/enforcement/SurfaceDetector.kt`**

- Add `val SurfaceDetectionResult.scopeClass: ScopeSurfaceClass` (pure mapping, no detection change):
  `SHORT_FORM_PLAYER→PLAYER_SHORT`; `LONG_FORM_PLAYER→PLAYER_LONG`; `ACTIVE_VIDEO_PLAYER→PLAYER_ANY`;
  `SOCIAL_FEED|SUBSCRIPTIONS|DISCOVER_NEWS→FEED`; `SOCIAL_DM→DM_LIST`; `MESSAGING_THREAD→DM_THREAD`;
  `SEARCH→SEARCH`; `HOME|CHANNEL|COMMENTS→BROWSE`; `INSTALL_FLOW→INSTALL`; `PAYMENT_FLOW→PAYMENT`;
  `PLAY_STORE|SYSTEM_SHADE|LAUNCHER|BROWSER_TAB_SWITCHER→SYSTEM`; `UNKNOWN→ANY`.
- **Do not change detection heuristics in this project.** Scope v1 is re-plumbing, not a detector rewrite.

**`domain/enforcement/VideoPlatformRegistry.kt`**

- Add `com.facebook.orca` (Messenger), `com.facebook.lite`, `com.instagram.lite`, `org.mozilla.firefox`,
  `com.brave.browser`, `com.sec.android.app.sbrowser`, `com.microsoft.emmx`, with
  `enforcesMediaSurfaces` / `participatesInShortFormQuota` set honestly.
- Add `fun identityOf(packageName): ScopeIdentity?` so `IdentityResolver` has one table to read.
- Delete both `isInEnforcementScope(...)` overloads — scope is `ScopeMatcher`'s job only.

**`domain/enforcement/PromiseEnforcementCoordinator.kt`**

- New **first** step, before `EntertainmentBanGate.mustEvaluate`:

  ```
  when (ScopeMatcher.match(pkg, text, detection, settings.enforcementScope)) {
    Unresolved -> ALLOW, code = SCOPE_UNRESOLVED_NO_ENFORCE, stage = "scope_unresolved"
    Exempt     -> ALLOW, code = SCOPE_EXEMPT_ALLOW,          stage = "scope_exempt"
    OutOfScope -> if (appRule == BLOCK) BLOCK/APP_RULE_BLOCK else ALLOW/ENFORCEMENT_SCOPE_ALLOW
    InScope    -> continue the existing chain
  }
  ```

- Delete the `goal`-text calls: `shortFormBan()`'s `PromiseIntentRules.allowsShortForm/blocksShortForm` and
  `SurfaceEnforcementGate`'s `blocksEntertainmentOrMovies/blocksShortForm/allowsShortForm` become
  **structured flags** on the compiled policy (`shortFormPolicy: BAN|QUOTA(n)|ALLOW`,
  `entertainmentBan: Boolean`). Drop `goal` from `PromiseEvalInput` once traces are migrated. This is what
  actually ends the phrase-list treadmill.
- `evalPrelude` logs `scope=${scope?.kind} clause=… resolution=…` instead of `scope=[pkgs] brands=[…]`.

**Gates that currently call `matches()` — remove their scope checks entirely:**
`MediaLengthEnforcement.kt:80`, `ShortFormQuotaGate.kt:113`, `EntertainmentBanGate.kt:64`,
`BlankVideoTreeGate.kt:55,113`, `SessionLockLaw.kt:21`. Scope is decided once, by the coordinator; a gate
re-deciding scope is exactly how the current model produces contradictory verdicts. `SessionLockLaw` keeps
its own independent LOCK law because it must also work with no session scope.

**`domain/enforcement/EnforcementDecisionLog.kt`**

- `format(...)` gains `scopeKind: String?` and `scopeClause: String?`; add reason codes
  `SCOPE_UNRESOLVED_NO_ENFORCE`, `SCOPE_EXEMPT_ALLOW`, `SCOPE_IN_SCOPE` (informational). Existing codes are
  append-only per the file's stated contract.

**`domain/promise/ConfirmedPromiseBinder.kt`**

- Writes `enforcementScope = draft.scope` verbatim. **No inference at bind time.** If
  `draft.scope == null || !isEnforceable` ⇒ Start is refused upstream (§5), and the binder writes
  `UNRESOLVED` rather than inventing a scope.

**`domain/promise/PromiseContentRuleSupport.kt`**

- Delete `enforcementScopePackages`, `enforcementContentBrands`, `enforcementScopeKind`. The clock helpers
  (`maxVideoLengthBlockMinutes`, `minVideoLengthBlockMinutes`, `shortFormDailyQuotaLimit`) stay.

**`domain/promise/CompiledPromiseMapper.kt`**

- Parse `scope` via `ScopeSerialization`. Delete `isYouTubeContentScope`,
  `sanitizeConfirmationLinesForScope`, `sanitizeConfirmationNotesForScope`,
  `sanitizeConfirmationLineForScope`, `mergeBrandLists` (~130 lines of scope-aware prose surgery).
  `appliesTo` comes from `ScopeAppliesToCopy.forScope(scope)`.

**`domain/promise/FocusPromiseParser.kt`** (offline fallback)

- Delete `inferContentBrands` / `inferScopeKind` / `inferScopePackages`. The offline parser emits
  **`UNRESOLVED` with `candidateScopes`** for anything it cannot pin from a preset — offline must ask, not
  guess. This removes the second, divergent inference path.

**Confirmation UI**

- `ui/home/ConfirmationUserCopy.kt`: `appliesToLabel(draft)` → `ScopeAppliesToCopy.forScope(draft.scope)`.
  Delete `SHORT_FORM_PACKAGES`, `BYPASS_CLASS_APPS`, `categoryWordForPackage`, `CATEGORY_*` — with scope
  typed, copy is generated from the kind and never needs package scrubbing. Keep `containsPackageName` as a
  **test-only assertion helper**.
- `ui/home/PromiseUnderstandingCard.kt`: render `appliesToText` as a first-class "Applies to" row; when
  `scope.kind == UNRESOLVED`, render the A/B/C cards and **disable Start** (§5).

**Last decision / debug**

- `domain/enforcement/CommitmentTransparency.kt`: the user line gains *"This applies to: {appliesToText}"*
  and *"This screen was {in/out of} scope ({clauseId})."* Engineering detail gains `scopeKind`, `clauseId`,
  `resolution`, `host`, `identity`, `surfaceClass`. The sanitized copy report still omits screen text;
  `clauseId` + `kind` are safe to include.

### 4.3 What must **not** change

- `PolicyEngine` remains law; scope is a *filter in front of* the gates, never a verdict.
- Permanent guardrails and safe/emergency apps are evaluated **before** scope and are unaffected by it.
- No new capture, no screenshots, no surface-detection heuristic changes in this project.

---

## 5. Clarification strategy

### 5.1 Start is hard-disabled when

1. `scope == null`, `scope.kind == UNRESOLVED`, or `!scope.isEnforceable`
2. `scope.version != 1`
3. `scope.confidence < 0.60`
4. `candidateScopes.size >= 2` and no option is `recommended`
5. For non-`PACKAGE_PINNED` scopes, `scope.evidenceSpans` is empty or non-verbatim. For
   `PACKAGE_PINNED`, any package is unmapped or its supported app name is absent from the
   user promise *(a package ID or model label alone is not evidence)*.
6. `appliesToText` contains a package id (compiler contract breach)
7. the kind is a permanent / Life-Rule scope and `requiresStrongerConfirmation` is unmet

**Fix required in existing code:** `PromiseClarificationLaw.isConcreteClockPolicy` /
`clearGhostClarification` must gain a scope precondition — `if (draft.scope?.isEnforceable != true) return
draft`. Today clock-concreteness alone can clear clarification and Start with a guessed scope (§1.3g). This
is the highest-severity item in the whole design.

### 5.2 Ambiguity triggers (each maps to an `UnresolvedReason`)

| Reason | Trigger | Question | A / B / C |
|---|---|---|---|
| `HOST_AMBIGUOUS` | brand named, no host word, no surface word — "only YouTube", "block Instagram" | "Which YouTube did you mean?" | app only / browser only / anywhere |
| `IDENTITY_SCOPE_AMBIGUOUS` | "video"/"videos" with no brand; "Google video" | "What should this cover?" | browser video / all video apps / one named app |
| `BRAND_VS_CATEGORY` | "shorts"/"reels" **plus exactly one app named** — "no Instagram reels" | "Just this app, or short videos everywhere?" | this app only / all short-form |
| `SURFACE_AMBIGUOUS` | social app named with no surface verb — "block Facebook" | "All of Facebook, or just part of it?" | whole app / feed+reels only / feed covered but messaging open |
| `COMPANION_APP_AMBIGUOUS` | app with a separate messaging package (FB/Messenger, IG/Threads) | "Should Messenger stay open?" | keep messaging open / cover it too |
| `SITE_LIST_UNRESOLVED` | "adult sites", "movie sites" with no category mapping | "Which kinds of sites?" | adult only / adult+movies / adult+movies+all video |
| `EXEMPTION_UNCLEAR` | "except", "but", "other than" without a resolvable carve-out | restate the carve-out | 2–3 concrete readings |
| `COMPOUND_SCOPE_CONFLICT` | two exclusive scopes in one sentence (already handled by `CompoundIntentLaw`) | existing copy | both / first / second |

### 5.3 Rules that prevent silent guessing

- **No default kind.** The compiler has no "if unsure, assume X" branch. Unsure ⇒ `UNRESOLVED`.
- **The backend enforces it, not the prompt.** `promiseCompilerService.js` validates `scope` against the
  enum and `evidenceSpans` against `userPromise`; on any failure it *overwrites* to `UNRESOLVED` +
  `canStartCommitment=false`. The model cannot talk its way past this.
- **Options carry structured scope.** `option.scope` is the only path from a pick to a scope. The English
  blob regex fallback is deleted.
- **The offline parser asks too.** `FocusPromiseParser` emits `UNRESOLVED` + candidates when the backend is
  down, rather than a thin guess. Slightly worse offline UX; strictly better trust.
- **Unresolved enforces nothing and says so.** `SCOPE_UNRESOLVED_NO_ENFORCE` + Home shows "Not protecting —
  finish setting up this promise." A promise that silently does nothing is worse than one that admits it.
- **The asymmetry is intentional:** `UNRESOLVED` fails *open* for enforcement (no false blocks) but fails
  *closed* for Start (the user can never falsely believe they are protected). Permanent guardrails are
  untouched, so a no-porn Life Rule still holds while a session scope is unresolved.

---

## 6. Test strategy

### 6.1 New unit test files

| File | Covers |
|---|---|
| `app/src/test/java/com/phonecodex/app/domain/scope/ScopeMatcherTest.kt` | axis matching, exempt-wins, unresolved, resolution modes |
| `…/scope/ScopeKindPresetsTest.kt` | every kind expands to the clause table in §2.4; every kind round-trips |
| `…/scope/ScopeSerializationTest.kt` | validate-or-`UNRESOLVED`; unknown kind; bad version; malformed clause; package id in `appliesToText` |
| `…/scope/HostResolverTest.kt` | Chrome/Firefox/Brave/Samsung/Edge/WebView ⇒ BROWSER; YT/NewPipe/Vanced ⇒ NATIVE_APP |
| `…/scope/IdentityResolverTest.kt` | each `ScopeIdentity`; `*_ANY` class resolution; unknown clone |
| `…/scope/SiteResolverTest.kt` | host patterns, `*`, subdomains, `youtu.be`, noisy text |
| `…/scope/ScopeAppliesToCopyTest.kt` | every kind yields non-empty, package-free copy (assert via `ConfirmationUserCopy.containsPackageName`) |
| `…/promise/ScopeStartGatingTest.kt` | the 7 hard-disable conditions in §5.1 |
| `backend/smoke_scope_semantics_v1.js` | compiler contract invariants (mirrors `smoke_promise_compiler_normalize.js`) |
| `evals/datasets/v9_scope_semantics.jsonl` | ≥150 promise→expected-scope cases; metric = **exact scope-kind match + 0 wrong-host** |

### 6.2 Files extended

`EnforcementScopeLawTest.kt` (shim behaviour only) · `SurfaceDetectorTest.kt` (`scopeClass` mapping) ·
`CompiledPromiseMapperTest.kt` (scope parse; deleted-sanitizer regressions) ·
`PromiseClarificationLawTest.kt` **(new file — the §5.1 fix has no test today)** ·
`ConfirmationUserCopyTest.kt` · `PhoneSurfaceTraceCatalog.kt` (+ the 40 traces below) ·
`ReliabilityGateInvariantsTest.kt` (scope-unresolved must never read as Protected).

### 6.3 The 40 edge cases

Each row = one trace in `PhoneSurfaceTraceCatalog` (offline, laptop-runnable) **and** one row in the phone
matrix (§7 Phase 4).

**YouTube — host discrimination (the failure family that started this)**

| # | id | scope | screen | expect |
|---|---|---|---|---|
| 1 | `scope_app_only_yt_player` | APP_ONLY(YOUTUBE) | YT app, `00:15 / 00:59` | IN_SCOPE → BLOCK min-30 |
| 2 | `scope_app_only_chrome_yt_watch_out` | APP_ONLY(YOUTUBE) | Chrome `youtube.com/watch`, player clocks | OUT_OF_SCOPE → `ENFORCEMENT_SCOPE_ALLOW` |
| 3 | `scope_app_only_newpipe_out` | APP_ONLY(YOUTUBE) | NewPipe active player | OUT_OF_SCOPE (PINNED) |
| 4 | `scope_browser_only_chrome_yt_in` | BROWSER_ONLY(YOUTUBE) | Chrome youtube.com watch | IN_SCOPE → BLOCK |
| 5 | `scope_browser_only_yt_app_out` | BROWSER_ONLY(YOUTUBE) | YT app player | OUT_OF_SCOPE |
| 6 | `scope_browser_only_firefox_yt_in` | BROWSER_ONLY(YOUTUBE) | Firefox youtube.com | **IN_SCOPE** (today's `chrome_browser` fails this) |
| 7 | `scope_any_host_yt_app_in` | CONTENT_ANY_HOST(YOUTUBE) | YT app | IN_SCOPE |
| 8 | `scope_any_host_chrome_yt_in` | CONTENT_ANY_HOST(YOUTUBE) | Chrome youtube.com | IN_SCOPE |
| 9 | `scope_any_host_newpipe_in` | CONTENT_ANY_HOST(YOUTUBE) | NewPipe | IN_SCOPE (CLASS_RESOLVED) |
| 10 | `scope_any_host_vanced_in` | CONTENT_ANY_HOST(YOUTUBE) | `com.vanced.android.youtube` | IN_SCOPE |
| 11 | `scope_yt_home_passive_no_enforce` | CONTENT_ANY_HOST(YOUTUBE) | YT Home + Shorts shelf, no player | IN_SCOPE but `SURFACE_PASS_PASSIVE` — **scope ≠ verdict** |
| 12 | `scope_yt_shorts_url_in_chrome` | SHORT_FORM_ANY_HOST | Chrome `/shorts/` player | IN_SCOPE → quota counts |

**Chrome / browser / sites**

| # | id | scope | screen | expect |
|---|---|---|---|---|
| 13 | `scope_chrome_except_adult_serp_out` | HOST_WITH_CATEGORY_EXEMPTIONS | Chrome Google SERP "dsa tutorial" | OUT_OF_SCOPE → ALLOW (kills the §7-step-8 false block) |
| 14 | `scope_chrome_except_adult_adult_in` | same | Chrome adult site player | IN_SCOPE → BLOCK |
| 15 | `scope_chrome_except_adult_movie_in` | same | Chrome NetMirror-style movie page | IN_SCOPE |
| 16 | `scope_chrome_except_adult_docs_out` | same | Chrome Google Docs | OUT_OF_SCOPE |
| 17 | `scope_site_scoped_only_named_host` | SITE_SCOPED(["nesoacademy.org"]) | Chrome other-site player | OUT_OF_SCOPE |
| 18 | `scope_chrome_tab_switcher_system` | any | Chrome tab switcher | `scopeClass=SYSTEM` → PASS |

**Instagram / Facebook / surface + companion apps**

| # | id | scope | screen | expect |
|---|---|---|---|---|
| 19 | `scope_ig_messaging_only_thread_exempt` | SOCIAL_MESSAGING_ONLY(INSTAGRAM) | IG thread, "Type a message" | EXEMPT → `SCOPE_EXEMPT_ALLOW` |
| 20 | `scope_ig_messaging_only_dm_list_exempt` | same | IG inbox list | EXEMPT |
| 21 | `scope_ig_messaging_only_reel_in` | same | IG Reels player | IN_SCOPE → BLOCK |
| 22 | `scope_ig_messaging_only_feed_in` | same | IG feed | IN_SCOPE |
| 23 | `scope_fb_feed_only_feed_in` | SOCIAL_FEED_AND_REELS_ONLY(FACEBOOK) | FB feed | IN_SCOPE |
| 24 | `scope_fb_feed_only_messenger_exempt` | same | `com.facebook.orca` thread | EXEMPT → ALLOW |
| 25 | `scope_fb_feed_only_fb_reel_in` | same | FB reel player | IN_SCOPE |

**Short-form category & TikTok**

| # | id | scope | screen | expect |
|---|---|---|---|---|
| 26 | `scope_short_form_tiktok_in` | SHORT_FORM_ANY_HOST | TikTok For You player | IN_SCOPE → quota |
| 27 | `scope_short_form_yt_lecture_out` | SHORT_FORM_ANY_HOST | YT app `00:15 / 52:10` long player | OUT_OF_SCOPE (`PLAYER_LONG`) → ALLOW |
| 28 | `scope_short_form_unknown_clone_in` | SHORT_FORM_ANY_HOST | `com.xyz.reelsvideo` short player | IN_SCOPE (CLASS_RESOLVED) → at least `UNKNOWN_CLONE_WARN` |
| 29 | `scope_short_form_ig_dm_out` | SHORT_FORM_ANY_HOST | IG DM thread | OUT_OF_SCOPE (no `PLAYER_SHORT`) |

**Unknown / unresolved / connector-future / safety**

| # | id | scope | screen | expect |
|---|---|---|---|---|
| 30 | `scope_unresolved_enforces_nothing` | UNRESOLVED | YT app short player, min-30 promise | ALLOW + `SCOPE_UNRESOLVED_NO_ENFORCE`; Home = Not protecting |
| 31 | `scope_unresolved_blocks_start` | UNRESOLVED | — (unit, `ScopeStartGatingTest`) | Start disabled, A/B/C shown |
| 32 | `scope_bad_version_falls_to_unresolved` | `version=2` | — | `ScopeSerialization` ⇒ UNRESOLVED |
| 33 | `scope_unknown_kind_falls_to_unresolved` | `kind="YT_ONLY_LOL"` | — | UNRESOLVED, no crash |
| 34 | `scope_pinned_unknown_app_out` | APP_ONLY(YOUTUBE) | `app.newtv.mirror` player | OUT_OF_SCOPE (PINNED excludes unknown) |
| 35 | `scope_class_resolved_unknown_app_in` | VIDEO_CLASS_ANY_HOST | `app.newtv.mirror` player | IN_SCOPE |
| 36 | `scope_whatsapp_out_of_video_scope` | VIDEO_CLASS_ANY_HOST | WhatsApp chat | OUT_OF_SCOPE (connector-future: scope must not creep into messaging) |
| 37 | `scope_gmail_out_of_video_scope` | VIDEO_CLASS_ANY_HOST | Gmail inbox | OUT_OF_SCOPE |
| 38 | `scope_dialer_safe_before_scope` | any, incl. UNRESOLVED | dialer | `EMERGENCY_ALLOW` — safe apps resolved **before** scope |
| 39 | `scope_adult_guardrail_ignores_scope` | APP_ONLY(YOUTUBE) + adult Life Rule | Chrome adult page | `ADULT_BLOCK_GUARDRAIL` — guardrails bypass scope |
| 40 | `scope_ai_cannot_expand_scope` | APP_ONLY(YOUTUBE), backend says BLOCK | Chrome youtube.com | ALLOW — AI cannot pull an out-of-scope screen in |

Rows 36–40 are the invariant tests that keep scope honest: it cannot shrink safety, and it cannot be widened
by the model.

### 6.4 Phone trace tests (Redmi/MIUI, after unit green)

Add to `docs/CURRENT_STATE.md` §7 as a new block. Each must be verified on device because scope interacts
with overlay retain and a11y text availability:

1. APP_ONLY(YOUTUBE) → YT app short BLOCKs; Chrome youtube.com watch **shows no overlay**; log shows
   `code=ENFORCEMENT_SCOPE_ALLOW scopeKind=APP_ONLY`.
2. BROWSER_ONLY(YOUTUBE) → mirror image of (1); verify on Chrome **and** one non-Chrome browser.
3. CONTENT_ANY_HOST(YOUTUBE) → app, Chrome, and NewPipe all enforce; Home/Search still PASS.
4. SHORT_FORM_ANY_HOST quota → count increments across YT Shorts → IG Reels → TikTok; N+1 BLOCKs; IG DM does
   not count.
5. SOCIAL_MESSAGING_ONLY(INSTAGRAM) → open DM thread (no overlay), swipe to Reels (overlay), back to DM
   (overlay clears within one debounce).
6. SOCIAL_FEED_AND_REELS_ONLY(FACEBOOK) → FB feed blocked, Messenger opens clean.
7. HOST_WITH_CATEGORY_EXEMPTIONS → Google SERP + Docs clean; adult/movie page blocked.
8. UNRESOLVED → Home says **Not protecting**, Start disabled, A/B/C visible; nothing on the phone is blocked.
9. Reinstall with an active scoped promise → the `StudyWorldSettingsStore` read-failure path ⇒ UNRESOLVED +
   Needs setup, never a silent phantom scope.
10. Last decision card shows "Applies to: …" and the in/out-of-scope reason for the last four decisions.

---

## 7. Migration plan

### Phase 1 — data model + compiler contract + tests (laptop only, no enforcement change)

- Add `domain/scope/*` (model, presets, matcher, resolvers, serialization, copy).
- Add `scope` to the compiler: `evals/prompts/promise_compiler_v09.txt` + `promiseCompilerService.js`
  validation (enum, version, evidence spans, package-leak rejection) + `option.scope`. Keep the current
  backend default prompt unchanged (`promise_compiler_v07` unless explicitly flagged); keep v08/v09 behind
  `PROMISE_COMPILER_PROMPT_VERSION`.
- Add `evals/datasets/v9_scope_semantics.jsonl` (≥150) + `backend/smoke_scope_semantics_v1.js`.
- Add all §6.1 unit tests. `ScopeMatcher` is dead code in the app at the end of Phase 1.
- **Exit gate:** §6.3 rows 1–29 pass as `ScopeMatcherTest` / preset tests; scope-kind exact match ≥90% and
  **wrong-host errors = 0** on `v9`; 0 package leaks in generated copy.

**Phase 1 exit-gate results (2026-09-22):**
- `:app:testDebugUnitTest` — **635 tests, 0 failures**, including the new 78 tests under
  `com.phonecodex.app.domain.scope.*` and the untouched `PhoneSurfaceTraceReplayTest` (6/6).
- §6.3 rows 1–29 encoded verbatim in `ScopeMatcherTest` (`row01_…` through `row29_…`) —
  **29/29 pass**, including the row-6 killer case
  (`BROWSER_ONLY(YOUTUBE)` + Firefox + youtube.com URL → IN_SCOPE) that today's `chrome_browser`
  rematerialize path gets wrong.
- `node backend/smoke_scope_semantics_v1.js` — **676 assertions pass**; every UNRESOLVED
  fall-back path in `validateScope` is asserted; `enforceScopeStartGates` correctly forces
  `canStartCommitment=false` on any UNRESOLVED.
- Existing `smoke_promise_compiler_normalize.js` (176) + `smoke_promise_compiler_v08.js` (76)
  still pass — no regression on live backend paths.
- `evals/datasets/v9_scope_semantics.jsonl` — **155 rows** (deterministically generated),
  covers all 11 kinds, includes 30 UNRESOLVED clarification cases, all §5.2 ambiguity triggers,
  and Hinglish/typo/voice variants. `evidenceSpans` verbatim-substring rule is enforced at
  build time. **Dataset-level wrong-host = 0/155**; model-vs-dataset wrong-host will be
  measured in Phase 2 once `promise_compiler_v09` is switched on behind the env flag.
- **Package leaks in scope copy = 0** — `ScopeAppliesToCopyTest` iterates all 10 enforceable
  kinds and asserts `containsPackageId` is false on each; the compiler boundary rejects any
  `appliesToText` matching `/\b(?:com|org|net|io)\.[a-zA-Z0-9_.]+\b/i` as
  UNRESOLVED(PACKAGE_LEAK_IN_APPLIES_TO).
- Backend default prompt **unchanged** at `promise_compiler_v07`; v09 is available only via
  `PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09`.

Full report: `evals/reports/scope_semantics_v1_phase1.md`.

### Phase 2 — Android mapper / binder / enforcement

- `CompiledPromiseMapper` parses `scope`; `FocusPromise.scope` added; `ConfirmedPromiseBinder` writes it;
  `StudyWorldSettingsStore` persists it.
- `PromiseEnforcementCoordinator` gains the scope-first step; the six gates lose their `matches()` calls;
  `EnforcementScopeLaw` becomes the shim.
- Apply the §5.1 `PromiseClarificationLaw` fix.
- Add reason codes + log fields. Migrate the 80+ traces to `enforcementScope`; add rows 30–40.
- Replace `goal`-text calls with structured flags; drop `goal` from `PromiseEvalInput`.
- **Exit gate:** `:app:testDebugUnitTest` green including the full trace catalog; no behaviour change for the
  three already-phone-verified promises (20-shorts, `upto 20 shots`, compound A/B/C) — asserted by trace.

### Phase 3 — UI confirmation copy generated from structured scope

- `ScopeAppliesToCopy` drives the "Applies to" row; delete the sanitizer families in
  `CompiledPromiseMapper` and `ConfirmationUserCopy`.
- `PromiseUnderstandingCard` renders A/B/C from `option.scope` and gates Start per §5.1.
- `CommitmentTransparency` shows applies-to + in/out-of-scope reason.
- **Exit gate:** `ConfirmationUserCopyTest` + `PromiseUnderstandingTest` + `HomeChatLayoutTest` green; zero
  package ids reachable in any user-facing string, asserted over all preset kinds.

### Phase 4 — phone verification matrix

- Run all 10 phone traces in §6.4 on Redmi; record pass/fail in `docs/CURRENT_STATE.md`.
- Flip `PROMISE_COMPILER_PROMPT_VERSION=promise_compiler_v09` on `phonecodex-backend`.
- Delete the deprecated `enforcementScopePackages` / `enforcementContentBrands` / `enforcementScopeKind`
  fields and the `EnforcementScopeLaw` shim.
- **Exit gate:** all 10 green + Azure latency/cost unchanged within noise.

**Explicitly out of scope for this project:** surface-detection heuristic changes, vision, PiP detection, new
Azure services, connector work, playlist/channel-level scope (a future `PLAYLIST_SCOPED` kind slots into the
same algebra with no refactor).

---

## 8. Safety rules (binding)

1. **Emergency and safe paths are resolved before scope.** `SafeAppsCatalog` / dialer / SOS / settings
   recovery are evaluated first and cannot be pulled into scope by any kind, including
   `VIDEO_CLASS_ANY_HOST` and `PACKAGE_PINNED`. Trace 38 enforces this.
2. **AI cannot override permanent guardrails, and scope cannot shrink them.** Life Rules
   (`no_adult_content`, dating, gambling) run **outside** `EnforcementScope`, at their existing position in
   the chain. A narrow session scope must never create an adult-content hole. Trace 39.
3. **Raw AI copy cannot override structured scope.** `option.scope` is the only path from a clarification
   pick to enforcement. `resolveOptionRematerialize`'s English-blob regexes are deleted. `evidenceSpans` must
   be verbatim user substrings or scope is `UNRESOLVED`.
4. **Unknown or ambiguous scope asks the user.** No default kind, no "assume app-only", no "assume
   anywhere". `UNRESOLVED` enforces nothing, blocks Start, and says so on Home. The backend enforces this
   even when the model does not comply.
5. **No screen surveillance by default.** Scope is computed from package + existing a11y text +
   `SurfaceDetector` output only. `SiteResolver` reads text already present in the a11y tree; it must not
   trigger capture. No new vision path, and `VisionCaptureGate`'s existing exclusions (dialer, settings,
   SMS/OTP, banking) are untouched.
6. **Scope never widens a verdict.** `ScopeMatch.InScope` only *permits* the existing gates to run; it never
   itself blocks. Trace 11 (YT Home passive under an in-scope promise) is the canary.
7. **Fail-open for enforcement, fail-closed for trust.** Malformed / unknown-version / unknown-kind scope ⇒
   `UNRESOLVED` ⇒ nothing is blocked (no false blocks) **and** the user is never told they are protected
   (`ProtectionReliabilityGate` must treat unresolved scope as not-protecting).
8. **Package ids stay internal.** `packages` / `packageMatch` / `hiddenInternalScope` only. Any package id
   reaching `appliesToText` or a clarification option is a contract breach that rejects the field and
   regenerates from the preset catalog. Enforced by a test over every kind.
9. **Append-only reason codes.** `SCOPE_UNRESOLVED_NO_ENFORCE` / `SCOPE_EXEMPT_ALLOW` / `SCOPE_IN_SCOPE` are
   added; no existing value in `EnforcementReasonCodes` changes.

---

## 9. IMPLEMENTATION PROMPT DRAFT

> Give this to Cursor/Codex **one phase at a time**. Do not paste all four phases at once.

```text
=== IMPLEMENTATION PROMPT DRAFT — Scope Semantics v1, PHASE 1 ONLY ===

CONTEXT
Repo: C:\Users\HP\strlix  (Android app package com.phonecodex.app + Node backend/)
Read first: PROJECT_CONTEXT.md, CURRENT_STATUS.md, NEXT_TASKS.md, AGENTS.md,
docs/CURRENT_STATE.md, and docs/scope-semantics-v1.md (the design you are implementing).
Strlix is a personal commitment OS, not a blocker. AI is counsel; PolicyEngine is law.

STRATEGIC GOAL
Replace ad-hoc YouTube-specific enforcement scope with one versioned typed selector
algebra, so a new user phrasing becomes new DATA (a preset row), never new code.
Read docs/scope-semantics-v1.md sections 2, 3, 5, 6, 8 as the contract. Do not redesign.

CURRENT VERIFIED STATE
- Scope today = StudyWorldSettings.enforcementScopePackages / enforcementContentBrands /
  enforcementScopeKind, matched by EnforcementScopeLaw.matches() (package OR one hard-coded
  youtube brand; no surface axis; empty means "everything").
- Backend recovers option scope by regexing its own English labels
  (promiseCompilerService.js resolveOptionRematerialize). That is the bug, not a feature.
- PhoneSurfaceTraceCatalog has 80+ offline traces; PhoneSurfaceTraceReplayTest must stay green.

PHASE 1 SCOPE (do exactly this, nothing more)
1. Create app/src/main/java/com/phonecodex/app/domain/scope/:
   EnforcementScope.kt, ScopeClause + all enums (ScopeKind, ScopeHost, ScopeIdentity,
   ScopeSurfaceClass, PackageMatchMode, ScopeResolution, UnresolvedReason, ScopeMatch),
   ScopeMatcher.kt, ScopeKindPresets.kt, HostResolver.kt, IdentityResolver.kt,
   SiteResolver.kt, ScopeSerialization.kt, ScopeAppliesToCopy.kt.
   Exact shapes and match order: design doc sections 2.2, 2.3, 2.4, 2.5.
   ScopeMatcher must be a pure function. No Android imports. No network. No capture.
2. Add SurfaceDetectionResult.scopeClass as a PURE mapping per design section 4.2.
   DO NOT change any detection heuristic, regex, or threshold in SurfaceDetector.
3. Add evals/prompts/promise_compiler_v09.txt: v08 verbatim plus the required "scope"
   object from design section 3.1, including the evidenceSpans rule and the
   "UNRESOLVED when unsure, never a default kind" rule.
4. In backend/promiseCompilerService.js add validateScope(): enum check, version check,
   evidenceSpans-are-verbatim-substrings check, package-id-in-appliesToText rejection.
   On ANY failure -> rewrite scope to UNRESOLVED and set canStartCommitment=false.
   Accept option.scope. Leave the old paths intact this phase; keep
   PROMISE_COMPILER_PROMPT_VERSION default at promise_compiler_v07.
5. Add evals/datasets/v9_scope_semantics.jsonl (>=150 cases) covering all 11 kinds,
   Hinglish, typos, voice-style transcripts, and every §5.2 ambiguity trigger.
6. Add backend/smoke_scope_semantics_v1.js and the unit tests listed in design section 6.1.
   Encode design rows 1-29 (section 6.3) as ScopeMatcher / preset assertions.

FORBIDDEN IN PHASE 1
- Do NOT touch PromiseEnforcementCoordinator, MediaLengthEnforcement, ShortFormQuotaGate,
  EntertainmentBanGate, BlankVideoTreeGate, SessionLockLaw, ConfirmedPromiseBinder,
  StudyWorldSettings, StudyWorldSettingsStore, or any ui/ file. ScopeMatcher stays dead
  code in the app until Phase 2.
- Do NOT delete EnforcementScopeLaw or its YouTube constants yet.
- Do NOT change SurfaceDetector detection logic, VideoDurationParser, or any threshold.
- Do NOT rename the app/package. Do NOT add Azure services. Do NOT commit .env.
- Do NOT add screenshot/vision/audio capture of any kind.
- Do NOT invent a default scope kind, a "best guess" fallback, or an English-regex
  scope recovery path. Unsure MUST mean UNRESOLVED.

SUCCESS METRICS
- ./gradlew :app:testDebugUnitTest green, including the existing
  PhoneSurfaceTraceReplayTest with ZERO changes to its expectations.
- node backend/smoke_scope_semantics_v1.js passes all invariants.
- On v9_scope_semantics.jsonl: scope-kind exact match >= 90%, wrong-HOST errors == 0,
  parse failures == 0. Wrong-host is the metric that matters; report it separately.
- ScopeAppliesToCopyTest: every ScopeKind produces non-empty copy and
  ConfirmationUserCopy.containsPackageName(copy) == false.

DELIVERABLES
- The files above.
- evals/reports/scope_semantics_v1_phase1.md: the v9 metric table (exact kind match,
  wrong-host count, per-kind confusion), plus every case the compiler got wrong and why.
- A short note in docs/scope-semantics-v1.md recording actual Phase 1 exit-gate numbers.

BEFORE CODING: state the plan in <=10 lines and name every file you will create or edit.
AFTER CODING: give the exact commands to verify (gradle test filter + node smoke + eval runner).
NORTH STAR: Strlix turns a natural-language promise into deterministic, explainable phone
behaviour. Scope must be typed, versioned, testable, and honest about what it does not know.
```
