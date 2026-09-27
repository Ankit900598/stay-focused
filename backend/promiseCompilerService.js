const fs = require("fs");
const path = require("path");
const {
  getPromiseCompilerProvider,
  parseClassifierJson,
  redactForLog,
} = require("./classifierService");
const {
  getCompilerDeploymentChain,
  completeJsonChatWithFallback,
} = require("./azureModelRouting");
const { resolvePromptsDir } = require("./promptPaths");

const PROMPTS_DIR = resolvePromptsDir();
const ALLOWED_PROMPT_VERSIONS = new Set([
  "promise_compiler_v06",
  "promise_compiler_v07",
  "promise_compiler_v08",
  "promise_compiler_v09",
]);

function resolvePromptVersion() {
  const raw = String(
    process.env.PROMISE_COMPILER_PROMPT_VERSION ||
      process.env.PROMISE_COMPILER_PROMPT ||
      "promise_compiler_v07"
  )
    .trim()
    .replace(/\.txt$/i, "");
  if (ALLOWED_PROMPT_VERSIONS.has(raw)) return raw;
  // Accept short forms: v06 / v07 / v08
  if (raw === "v06" || raw === "06") return "promise_compiler_v06";
  if (raw === "v07" || raw === "07") return "promise_compiler_v07";
  if (raw === "v08" || raw === "08") return "promise_compiler_v08";
  if (raw === "v09" || raw === "09") return "promise_compiler_v09";
  return "promise_compiler_v07";
}

const PROMPT_VERSION = resolvePromptVersion();
const PROMPT_PATH = path.join(PROMPTS_DIR, `${PROMPT_VERSION}.txt`);
const DEFAULT_SESSION_MINUTES = 60;
/** Calendar-day stand-in when user said "today" / daily quota without a session timer. */
const CALENDAR_DAY_MINUTES = 24 * 60;
/**
 * Quota boundary contract (Promise Semantics v1):
 * allow_first_n — events 1..N ALLOW, event N+1 and later BLOCK until period ends.
 */
const QUOTA_BOUNDARY = "allow_first_n";

/** Category expansion for bare "shorts" / short-form quotas (not YouTube-only). */
const SHORT_FORM_PLATFORM_PACKAGES = [
  { packageName: "com.google.android.youtube", appLabel: "YouTube" },
  { packageName: "org.schabi.newpipe", appLabel: "NewPipe" },
  { packageName: "com.instagram.android", appLabel: "Instagram" },
  { packageName: "com.facebook.katana", appLabel: "Facebook" },
  { packageName: "com.zhiliaoapp.musically", appLabel: "TikTok" },
  { packageName: "com.snapchat.android", appLabel: "Snapchat" },
  { packageName: "com.android.chrome", appLabel: "Chrome" },
];

/** Major video apps for “all Google video / all video apps” scope rematerialize. */
const ALL_VIDEO_PLATFORM_PACKAGES = [
  { packageName: "com.google.android.youtube", appLabel: "YouTube" },
  { packageName: "org.schabi.newpipe", appLabel: "NewPipe" },
  { packageName: "com.android.chrome", appLabel: "Chrome" },
  { packageName: "com.vanced.android.youtube", appLabel: "YouTube Vanced" },
];

const PKG_YOUTUBE = "com.google.android.youtube";
const PKG_CHROME = "com.android.chrome";

const PACKAGE_ID_RE = /\b(?:com|org|net|io)\.[a-zA-Z0-9_.]+\b/g;
const VALID_STRICTNESS = new Set(["SOFT", "SMART", "STRICT", "LOCKED"]);
const REQUIRED_FIELDS = [
  "commitmentType",
  "duration",
  "strictnessLevel",
  "allowedApps",
  "blockedApps",
  "allowedContent",
  "blockedContent",
  "followUpQuestionRequired",
  "confidence",
];

let cachedPrompt = null;
let cachedPromptVersion = null;

function loadPromiseCompilerPrompt() {
  if (cachedPrompt && cachedPromptVersion === PROMPT_VERSION) {
    return cachedPrompt;
  }
  cachedPrompt = fs.readFileSync(PROMPT_PATH, "utf8");
  cachedPromptVersion = PROMPT_VERSION;
  return cachedPrompt;
}

/** Strip Android package IDs and verbose app dumps from user-facing strings. */
function sanitizeUserFacingCopy(text, promiseText = "") {
  if (typeof text !== "string") return "";
  const promise = typeof promiseText === "string" ? promiseText.toLowerCase() : "";
  let out = text
    .replace(/\b(?:com|org|net|io)\.[a-zA-Z0-9_.]+\b/gi, "")
    .replace(/\(\s*\)/g, "")
    .replace(/\s{2,}/g, " ")
    .replace(/\s+([,.;:])/g, "$1")
    .trim();
  out = out.replace(
    /\b(?:YouTube|Instagram|Facebook|TikTok|Snapchat|NewPipe|Chrome)(?:\s*,\s*(?:YouTube|Instagram|Facebook|TikTok|Snapchat|NewPipe|Chrome)){2,}\b/gi,
    "short-video and reels-style apps"
  );
  if (!/\bnewpipe\b/.test(promise)) {
    out = out.replace(/\bNewPipe\b/gi, "").replace(/\s{2,}/g, " ").trim();
  }
  return out.replace(/\s*,\s*,+/g, ",").replace(/^[\s,·]+|[\s,·]+$/g, "").trim();
}

function userFacingHasPackageLeak(text) {
  if (typeof text !== "string") return false;
  return /\b(?:com|org|net|io)\.[a-zA-Z0-9_.]+\b/i.test(text);
}

// =============================================================================
// Scope Semantics v1 — validateScope()
//
// The one boundary that turns the raw `scope` object emitted by promise_compiler_v09
// into either an enforceable typed selector or `UNRESOLVED`.
//
// Contract (docs/scope-semantics-v1.md §3.1, §5):
//   - `scope` is REQUIRED. Missing / null / non-object -> UNRESOLVED (MISSING_EVIDENCE).
//   - `version` MUST equal SCOPE_VERSION_V1. Otherwise UNRESOLVED (BAD_VERSION).
//   - `kind` MUST be one of SCOPE_KINDS. Otherwise UNRESOLVED (UNKNOWN_KIND).
//   - Enum values on `resolution`, `identity`, clause `host` / `identity` / `surfaces`
//     / `packageMatch` MUST be in their enum sets. Otherwise UNRESOLVED (MALFORMED_CLAUSE).
//   - `appliesToText` MUST NOT contain a package id. Leak -> UNRESOLVED (PACKAGE_LEAK_IN_APPLIES_TO).
//   - `evidenceSpans` MUST be verbatim substrings of `userPromise`, except for
//     `PACKAGE_PINNED` (user literally named an app). Otherwise UNRESOLVED (MISSING_EVIDENCE).
//   - A non-UNRESOLVED kind with an empty `include` -> UNRESOLVED (MALFORMED_CLAUSE).
//
// Any failure ALSO forces `canStartCommitment=false` and `clarificationRequired=true`
// upstream — see enforceScopeStartGates(). Callers must pass the ORIGINAL userPromise so
// evidenceSpans can be verified verbatim.
// =============================================================================

const SCOPE_VERSION_V1 = 1;

const SCOPE_KINDS = new Set([
  "APP_ONLY",
  "BROWSER_ONLY",
  "BROWSER_VIDEO",
  "CONTENT_ANY_HOST",
  "VIDEO_CLASS_ANY_HOST",
  "SHORT_FORM_ANY_HOST",
  "APP_SHORT_FORM_ONLY",
  "SOCIAL_MESSAGING_ONLY",
  "SOCIAL_FEED_AND_REELS_ONLY",
  "SITE_SCOPED",
  "HOST_WITH_CATEGORY_EXEMPTIONS",
  "PACKAGE_PINNED",
  "UNRESOLVED",
]);

const SCOPE_RESOLUTIONS = new Set(["EXCLUSIVE", "ADDITIVE"]);

const SCOPE_HOSTS = new Set(["NATIVE_APP", "BROWSER", "ANY_HOST"]);

const SCOPE_IDENTITIES = new Set([
  "YOUTUBE",
  "INSTAGRAM",
  "FACEBOOK",
  "MESSENGER",
  "TIKTOK",
  "SNAPCHAT",
  "WHATSAPP",
  "VIDEO_ANY",
  "SHORT_FORM_ANY",
  "MOVIE_OTT_ANY",
  "ADULT_ANY",
  "SOCIAL_ANY",
  "ANY",
]);

const SCOPE_SURFACE_CLASSES = new Set([
  "PLAYER_ANY",
  "PLAYER_SHORT",
  "PLAYER_LONG",
  "FEED",
  "DM_LIST",
  "DM_THREAD",
  "SEARCH",
  "BROWSE",
  "INSTALL",
  "PAYMENT",
  "SYSTEM",
  "ANY",
]);

const SCOPE_PACKAGE_MATCH_MODES = new Set(["PINNED", "CLASS_RESOLVED"]);

const SCOPE_UNRESOLVED_REASONS = new Set([
  "HOST_AMBIGUOUS",
  "IDENTITY_SCOPE_AMBIGUOUS",
  "BRAND_VS_CATEGORY",
  "SURFACE_AMBIGUOUS",
  "COMPANION_APP_AMBIGUOUS",
  "SITE_LIST_UNRESOLVED",
  "EXEMPTION_UNCLEAR",
  "COMPOUND_SCOPE_CONFLICT",
  "MISSING_EVIDENCE",
  "UNKNOWN_KIND",
  "BAD_VERSION",
  "MALFORMED_CLAUSE",
  "PACKAGE_LEAK_IN_APPLIES_TO",
]);

const SCOPE_PACKAGE_ID_RE = /\b(?:com|org|net|io)\.[a-zA-Z0-9_.]+\b/i;

function unresolvedScope(reason, evidenceSpans) {
  return {
    version: SCOPE_VERSION_V1,
    kind: "UNRESOLVED",
    resolution: "EXCLUSIVE",
    identity: null,
    include: [],
    exempt: [],
    confidence: 0,
    appliesToText: null,
    unresolvedReason: SCOPE_UNRESOLVED_REASONS.has(reason)
      ? reason
      : "MISSING_EVIDENCE",
    evidenceSpans: Array.isArray(evidenceSpans)
      ? evidenceSpans.filter((s) => typeof s === "string")
      : [],
    candidateScopes: [],
  };
}

function normalizeStringArray(raw) {
  if (!Array.isArray(raw)) return [];
  const out = [];
  for (const item of raw) {
    if (typeof item !== "string") continue;
    const trimmed = item.trim();
    if (trimmed) out.push(trimmed);
  }
  return out;
}

function spansAreVerbatim(spans, userPromise) {
  if (!Array.isArray(spans) || spans.length === 0) return true;
  if (typeof userPromise !== "string" || !userPromise) return false;
  const hay = userPromise;
  const hayLower = hay.toLowerCase();
  return spans.every((span) => {
    if (typeof span !== "string") return false;
    const t = span.trim();
    if (!t) return false;
    return hay.includes(t) || hayLower.includes(t.toLowerCase());
  });
}

function validateClauseList(rawList, listName) {
  if (rawList == null) return { ok: true, list: [] };
  if (!Array.isArray(rawList)) {
    return { ok: false, reason: "MALFORMED_CLAUSE", where: listName };
  }
  const out = [];
  for (let i = 0; i < rawList.length; i += 1) {
    const raw = rawList[i];
    if (!raw || typeof raw !== "object") {
      return { ok: false, reason: "MALFORMED_CLAUSE", where: `${listName}[${i}]` };
    }
    const id = typeof raw.id === "string" && raw.id.trim() ? raw.id.trim() : "";
    if (!id) {
      return { ok: false, reason: "MALFORMED_CLAUSE", where: `${listName}[${i}].id` };
    }
    const host = typeof raw.host === "string" ? raw.host.trim().toUpperCase() : "ANY_HOST";
    if (!SCOPE_HOSTS.has(host)) {
      return { ok: false, reason: "MALFORMED_CLAUSE", where: `${listName}[${i}].host` };
    }
    const identity =
      typeof raw.identity === "string" ? raw.identity.trim().toUpperCase() : "ANY";
    if (!SCOPE_IDENTITIES.has(identity)) {
      return { ok: false, reason: "MALFORMED_CLAUSE", where: `${listName}[${i}].identity` };
    }
    const surfacesRaw = Array.isArray(raw.surfaces) ? raw.surfaces : ["ANY"];
    const surfaces = [];
    for (const s of surfacesRaw) {
      if (typeof s !== "string") {
        return { ok: false, reason: "MALFORMED_CLAUSE", where: `${listName}[${i}].surfaces` };
      }
      const up = s.trim().toUpperCase();
      if (!SCOPE_SURFACE_CLASSES.has(up)) {
        return { ok: false, reason: "MALFORMED_CLAUSE", where: `${listName}[${i}].surfaces` };
      }
      if (!surfaces.includes(up)) surfaces.push(up);
    }
    if (surfaces.length === 0) surfaces.push("ANY");
    const sites = normalizeStringArray(raw.sites);
    const packages = normalizeStringArray(raw.packages);
    const packageMatch =
      typeof raw.packageMatch === "string"
        ? raw.packageMatch.trim().toUpperCase()
        : "CLASS_RESOLVED";
    if (!SCOPE_PACKAGE_MATCH_MODES.has(packageMatch)) {
      return { ok: false, reason: "MALFORMED_CLAUSE", where: `${listName}[${i}].packageMatch` };
    }
    out.push({ id, host, identity, surfaces, sites, packages, packageMatch });
  }
  return { ok: true, list: out };
}

/**
 * Validate the compiler-emitted `scope` object against Scope Semantics v1.
 * Returns the normalized scope on success, or a rewritten UNRESOLVED scope on any failure.
 *
 * @param {object|null|undefined} rawScope  the model-emitted scope
 * @param {string} userPromise              the original user promise (for evidenceSpans verify)
 * @returns {object} normalized scope (either enforceable v1 or UNRESOLVED)
 */
function validateScope(rawScope, userPromise) {
  if (rawScope == null || typeof rawScope !== "object" || Array.isArray(rawScope)) {
    return unresolvedScope("MISSING_EVIDENCE");
  }
  const version =
    typeof rawScope.version === "number" && Number.isFinite(rawScope.version)
      ? rawScope.version
      : null;
  if (version !== SCOPE_VERSION_V1) {
    return unresolvedScope("BAD_VERSION");
  }
  const kindRaw =
    typeof rawScope.kind === "string" ? rawScope.kind.trim().toUpperCase() : "";
  if (!kindRaw || !SCOPE_KINDS.has(kindRaw)) {
    return unresolvedScope("UNKNOWN_KIND", normalizeStringArray(rawScope.evidenceSpans));
  }

  const evidenceSpans = normalizeStringArray(rawScope.evidenceSpans);
  const resolution =
    typeof rawScope.resolution === "string" &&
    SCOPE_RESOLUTIONS.has(rawScope.resolution.trim().toUpperCase())
      ? rawScope.resolution.trim().toUpperCase()
      : "EXCLUSIVE";
  const identity =
    typeof rawScope.identity === "string" && rawScope.identity.trim()
      ? SCOPE_IDENTITIES.has(rawScope.identity.trim().toUpperCase())
        ? rawScope.identity.trim().toUpperCase()
        : null
      : null;
  const confidence =
    typeof rawScope.confidence === "number" && Number.isFinite(rawScope.confidence)
      ? Math.max(0, Math.min(1, rawScope.confidence))
      : 0;
  const appliesToText =
    typeof rawScope.appliesToText === "string" && rawScope.appliesToText.trim()
      ? rawScope.appliesToText.trim()
      : null;

  if (appliesToText && SCOPE_PACKAGE_ID_RE.test(appliesToText)) {
    return unresolvedScope("PACKAGE_LEAK_IN_APPLIES_TO", evidenceSpans);
  }

  const includeResult = validateClauseList(rawScope.include, "include");
  if (!includeResult.ok) return unresolvedScope(includeResult.reason, evidenceSpans);
  const exemptResult = validateClauseList(rawScope.exempt, "exempt");
  if (!exemptResult.ok) return unresolvedScope(exemptResult.reason, evidenceSpans);

  const unresolvedReasonRaw =
    typeof rawScope.unresolvedReason === "string"
      ? rawScope.unresolvedReason.trim().toUpperCase()
      : "";
  const unresolvedReason = SCOPE_UNRESOLVED_REASONS.has(unresolvedReasonRaw)
    ? unresolvedReasonRaw
    : null;

  const candidateScopes = Array.isArray(rawScope.candidateScopes)
    ? rawScope.candidateScopes
        .map((c) => validateScope(c, userPromise))
        .filter((c) => c && c.kind)
    : [];

  if (kindRaw === "UNRESOLVED") {
    return {
      version: SCOPE_VERSION_V1,
      kind: "UNRESOLVED",
      resolution,
      identity,
      include: [],
      exempt: [],
      confidence,
      appliesToText: null,
      unresolvedReason: unresolvedReason || "MISSING_EVIDENCE",
      evidenceSpans,
      candidateScopes,
    };
  }

  // Non-UNRESOLVED kinds require evidence (except PACKAGE_PINNED — user named a literal app).
  if (kindRaw !== "PACKAGE_PINNED") {
    if (evidenceSpans.length === 0) {
      return unresolvedScope("MISSING_EVIDENCE");
    }
    if (typeof userPromise === "string" && userPromise) {
      if (!spansAreVerbatim(evidenceSpans, userPromise)) {
        return unresolvedScope("MISSING_EVIDENCE", evidenceSpans);
      }
    }
  }

  if (includeResult.list.length === 0) {
    return unresolvedScope("MALFORMED_CLAUSE", evidenceSpans);
  }

  return {
    version: SCOPE_VERSION_V1,
    kind: kindRaw,
    resolution,
    identity,
    include: includeResult.list,
    exempt: exemptResult.list,
    confidence,
    appliesToText,
    unresolvedReason: null,
    evidenceSpans,
    candidateScopes,
  };
}

/**
 * Validate the `scope` attached to one clarificationOption. Same rules; used when a
 * user picks A/B/C and the backend rematerializes the DTO — option.scope is the ONLY
 * path from a pick to enforcement scope (v09 deletes the English-blob regex fallback).
 */
function validateOptionScope(optionScope, userPromise) {
  return validateScope(optionScope, userPromise);
}

function isEnforceableScope(scope) {
  return (
    scope &&
    scope.version === SCOPE_VERSION_V1 &&
    scope.kind !== "UNRESOLVED" &&
    Array.isArray(scope.include) &&
    scope.include.length > 0
  );
}

/**
 * When scope is UNRESOLVED / invalid, force Start off and clarification on. Non-destructive:
 * leaves the rest of the compiled DTO intact. Callers that opt into v09 should invoke this
 * after normalizeCompiledPromise() to preserve fail-closed trust while keeping enforcement
 * fail-open (nothing is blocked when scope is unresolved).
 */
function enforceScopeStartGates(dto, validatedScope) {
  if (!dto || typeof dto !== "object") return dto;
  const out = { ...dto, scope: validatedScope };
  if (!isEnforceableScope(validatedScope)) {
    out.canStartCommitment = false;
    out.clarificationRequired = true;
    out.followUpQuestionRequired = true;
  }
  return out;
}

// -----------------------------------------------------------------------------
// Scope contract + canonical presets
//
// Only prompts listed here emit a typed `scope`; every other prompt is the legacy
// contract (scopePackages / contentBrands / scopeKind) and is labelled as such.
// The model picks kind + parameters; clauses are rebuilt from the presets below,
// mirroring app/.../domain/scope/ScopeKindPresets.kt + ScopeCanonicalizer.kt.
// -----------------------------------------------------------------------------

const SCOPE_CONTRACT_V1_PROMPTS = new Set(["promise_compiler_v09"]);

function scopeContractFor(promptVersion) {
  return SCOPE_CONTRACT_V1_PROMPTS.has(promptVersion) ? "v1" : "legacy";
}

const NAMED_SCOPE_IDENTITIES = new Set([
  "YOUTUBE",
  "INSTAGRAM",
  "FACEBOOK",
  "MESSENGER",
  "TIKTOK",
  "SNAPCHAT",
  "WHATSAPP",
]);
const SOCIAL_SCOPE_IDENTITIES = new Set([
  "INSTAGRAM",
  "FACEBOOK",
  "MESSENGER",
  "TIKTOK",
  "SNAPCHAT",
  "WHATSAPP",
  "SOCIAL_ANY",
]);
/** Named apps with a short-form player surface (Shorts / Reels / Spotlight). */
const SHORT_FORM_APP_IDENTITIES = new Set(["YOUTUBE", "INSTAGRAM", "FACEBOOK", "TIKTOK", "SNAPCHAT"]);
const SCOPE_SITE_RE = /^[a-z0-9-]+(\.[a-z0-9-]+)+$/;
const SCOPE_PACKAGE_RE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$/;

const IDENTITY_DEFAULT_PACKAGES = {
  YOUTUBE: ["com.google.android.youtube"],
  INSTAGRAM: ["com.instagram.android"],
  FACEBOOK: ["com.facebook.katana"],
  MESSENGER: ["com.facebook.orca"],
  TIKTOK: ["com.zhiliaoapp.musically"],
  SNAPCHAT: ["com.snapchat.android"],
  WHATSAPP: ["com.whatsapp"],
};
const IDENTITY_DEFAULT_SITES = {
  YOUTUBE: ["youtube.com", "m.youtube.com", "youtu.be", "youtube-nocookie.com"],
  INSTAGRAM: ["instagram.com", "instagr.am"],
  FACEBOOK: ["facebook.com", "fb.com", "fb.watch"],
  MESSENGER: ["messenger.com", "m.me"],
  TIKTOK: ["tiktok.com", "vm.tiktok.com"],
  SNAPCHAT: ["snapchat.com"],
  WHATSAPP: ["wa.me", "whatsapp.com"],
};

function presetClause(id, host, identity, surfaces, extra = {}) {
  return {
    id,
    host,
    identity,
    surfaces,
    sites: extra.sites || [],
    packages: extra.packages || [],
    packageMatch: extra.packageMatch || "CLASS_RESOLVED",
  };
}

function expandScopePreset(kind, identity, sites, packages) {
  const pkgsFor = (id) => IDENTITY_DEFAULT_PACKAGES[id] || [];
  switch (kind) {
    case "APP_ONLY":
      return {
        include: [presetClause("i1", "NATIVE_APP", identity, ["ANY"], { packages: pkgsFor(identity), packageMatch: "PINNED" })],
        exempt: [],
      };
    case "BROWSER_ONLY":
      return {
        include: [
          presetClause("i1", "BROWSER", identity, ["PLAYER_ANY", "BROWSE", "SEARCH"], {
            sites: sites.length > 0 ? sites : IDENTITY_DEFAULT_SITES[identity] || [],
          }),
        ],
        exempt: [],
      };
    case "BROWSER_VIDEO":
      return { include: [presetClause("i1", "BROWSER", "VIDEO_ANY", ["PLAYER_ANY"])], exempt: [] };
    case "CONTENT_ANY_HOST":
      return { include: [presetClause("i1", "ANY_HOST", identity, ["ANY"])], exempt: [] };
    case "VIDEO_CLASS_ANY_HOST":
      return { include: [presetClause("i1", "ANY_HOST", "VIDEO_ANY", ["PLAYER_ANY"])], exempt: [] };
    case "SHORT_FORM_ANY_HOST":
      return { include: [presetClause("i1", "ANY_HOST", "SHORT_FORM_ANY", ["PLAYER_SHORT"])], exempt: [] };
    case "APP_SHORT_FORM_ONLY":
      return {
        include: [
          presetClause("i1", "NATIVE_APP", identity, ["PLAYER_SHORT"], {
            packages: pkgsFor(identity),
            packageMatch: "PINNED",
          }),
        ],
        exempt: [],
      };
    case "SOCIAL_MESSAGING_ONLY":
      return {
        include: [presetClause("i1", "ANY_HOST", identity, ["ANY"], { packages: pkgsFor(identity), packageMatch: "PINNED" })],
        exempt: [
          presetClause("x1", "ANY_HOST", identity, ["DM_LIST", "DM_THREAD"], {
            packages: pkgsFor(identity),
            packageMatch: "PINNED",
          }),
        ],
      };
    case "SOCIAL_FEED_AND_REELS_ONLY":
      return {
        include: [
          presetClause("i1", "ANY_HOST", identity, ["FEED", "PLAYER_SHORT"], {
            packages: pkgsFor(identity),
            packageMatch: "PINNED",
          }),
        ],
        exempt: [],
      };
    case "SITE_SCOPED":
      return { include: [presetClause("i1", "BROWSER", "ANY", ["ANY"], { sites })], exempt: [] };
    case "HOST_WITH_CATEGORY_EXEMPTIONS":
      return {
        include: [
          presetClause("i1", "BROWSER", "ADULT_ANY", ["ANY"], { sites: ["*"] }),
          presetClause("i2", "BROWSER", "MOVIE_OTT_ANY", ["ANY"], { sites: ["*"] }),
          presetClause("i3", "BROWSER", "VIDEO_ANY", ["PLAYER_ANY"], { sites: ["*"] }),
        ],
        exempt: [],
      };
    case "PACKAGE_PINNED":
      return {
        include: [presetClause("i1", "ANY_HOST", "ANY", ["ANY"], { packages, packageMatch: "PINNED" })],
        exempt: [],
      };
    default:
      return null;
  }
}

/**
 * validateScope() output -> the one clause set the preset table defines for its kind.
 * Model-written clauses are discarded; unusable parameters -> UNRESOLVED, never broader.
 */
function canonicalizeScope(scope) {
  if (!scope || typeof scope !== "object") return unresolvedScope("MISSING_EVIDENCE");
  const spans = Array.isArray(scope.evidenceSpans) ? scope.evidenceSpans : [];
  if (scope.kind === "UNRESOLVED") {
    return {
      ...unresolvedScope(scope.unresolvedReason || "MISSING_EVIDENCE", spans),
      resolution: scope.resolution || "EXCLUSIVE",
      identity: scope.identity || null,
      confidence: typeof scope.confidence === "number" ? scope.confidence : 0,
      candidateScopes: (scope.candidateScopes || []).map(canonicalizeScope),
    };
  }
  if (!isEnforceableScope(scope)) return unresolvedScope("MALFORMED_CLAUSE", spans);
  const identity = scope.identity || scope.include[0].identity;
  const uniq = (arr) => [...new Set(arr)];
  const sites = uniq(
    scope.include.flatMap((c) => c.sites || []).map((s) => String(s).trim().toLowerCase()).filter(Boolean)
  );
  const packages = uniq(scope.include.flatMap((c) => c.packages || []).map((p) => String(p).trim()).filter(Boolean));
  const fail = (reason) => unresolvedScope(reason, spans);

  let canonicalIdentity;
  switch (scope.kind) {
    case "APP_ONLY":
    case "CONTENT_ANY_HOST":
      if (!NAMED_SCOPE_IDENTITIES.has(identity)) return fail("IDENTITY_SCOPE_AMBIGUOUS");
      canonicalIdentity = identity;
      break;
    case "BROWSER_ONLY": {
      if (!sites.every((s) => SCOPE_SITE_RE.test(s))) return fail("SITE_LIST_UNRESOLVED");
      const named = NAMED_SCOPE_IDENTITIES.has(identity);
      if (!named && (identity !== "ANY" || sites.length === 0)) return fail("SITE_LIST_UNRESOLVED");
      canonicalIdentity = identity;
      break;
    }
    case "BROWSER_VIDEO":
    case "VIDEO_CLASS_ANY_HOST":
      canonicalIdentity = "VIDEO_ANY";
      break;
    case "SHORT_FORM_ANY_HOST":
      canonicalIdentity = "SHORT_FORM_ANY";
      break;
    case "APP_SHORT_FORM_ONLY":
      if (!SHORT_FORM_APP_IDENTITIES.has(identity)) return fail("IDENTITY_SCOPE_AMBIGUOUS");
      canonicalIdentity = identity;
      break;
    case "SOCIAL_MESSAGING_ONLY":
    case "SOCIAL_FEED_AND_REELS_ONLY":
      if (!SOCIAL_SCOPE_IDENTITIES.has(identity)) return fail("IDENTITY_SCOPE_AMBIGUOUS");
      canonicalIdentity = identity;
      break;
    case "SITE_SCOPED":
      if (sites.length === 0 || !sites.every((s) => SCOPE_SITE_RE.test(s))) return fail("SITE_LIST_UNRESOLVED");
      canonicalIdentity = "ANY";
      break;
    case "HOST_WITH_CATEGORY_EXEMPTIONS":
      canonicalIdentity = null;
      break;
    case "PACKAGE_PINNED":
      if (packages.length === 0 || !packages.every((p) => SCOPE_PACKAGE_RE.test(p))) return fail("MALFORMED_CLAUSE");
      canonicalIdentity = null;
      break;
    default:
      return fail("UNKNOWN_KIND");
  }
  const expansion = expandScopePreset(scope.kind, canonicalIdentity, sites, packages);
  if (!expansion) return fail("UNKNOWN_KIND");
  return {
    version: SCOPE_VERSION_V1,
    kind: scope.kind,
    resolution: scope.resolution || "EXCLUSIVE",
    identity: canonicalIdentity,
    include: expansion.include,
    exempt: expansion.exempt,
    confidence: typeof scope.confidence === "number" ? scope.confidence : 0,
    appliesToText: null,
    unresolvedReason: null,
    evidenceSpans: spans,
    candidateScopes: [],
  };
}

function validateAndCanonicalizeScope(rawScope, userPromise) {
  return canonicalizeScope(validateScope(rawScope, userPromise));
}

const SCOPE_IDENTITY_LABELS = {
  YOUTUBE: "YouTube",
  INSTAGRAM: "Instagram",
  FACEBOOK: "Facebook",
  MESSENGER: "Messenger",
  TIKTOK: "TikTok",
  SNAPCHAT: "Snapchat",
  WHATSAPP: "WhatsApp",
  VIDEO_ANY: "video",
  SHORT_FORM_ANY: "short video",
  MOVIE_OTT_ANY: "movie / streaming",
  ADULT_ANY: "adult",
  SOCIAL_ANY: "social",
  ANY: "the selected",
};

/** Mirrors ScopeAppliesToCopy.optionLabel on Android. */
function scopeOptionLabel(scope) {
  if (!isEnforceableScope(scope)) return null;
  const identity = scope.identity || (scope.include[0] && scope.include[0].identity) || "ANY";
  const name = SCOPE_IDENTITY_LABELS[identity] || SCOPE_IDENTITY_LABELS.ANY;
  switch (scope.kind) {
    case "APP_ONLY": return `${name} app only`;
    case "BROWSER_ONLY": return `${name} in your browser only`;
    case "BROWSER_VIDEO": return "Video in any browser";
    case "CONTENT_ANY_HOST": return `${name} anywhere`;
    case "VIDEO_CLASS_ANY_HOST": return "All video apps and browsers";
    case "SHORT_FORM_ANY_HOST": return "Short videos everywhere";
    case "APP_SHORT_FORM_ONLY": return `${name} short videos only`;
    case "SOCIAL_MESSAGING_ONLY": return `${name} except messages`;
    case "SOCIAL_FEED_AND_REELS_ONLY": return `${name} feed and short videos`;
    case "SITE_SCOPED": return "Specific sites in your browser";
    case "HOST_WITH_CATEGORY_EXEMPTIONS": return "Adult, movie, and video pages in your browser";
    case "PACKAGE_PINNED": return "Specific apps you named";
    default: return null;
  }
}

/** Enforcement identity of a canonical scope (copy/confidence/evidence excluded). */
function typedScopeFingerprint(scope) {
  if (!isEnforceableScope(scope)) return null;
  return JSON.stringify({ kind: scope.kind, identity: scope.identity, include: scope.include, exempt: scope.exempt });
}

/**
 * Typed scope an option stands for: the re-derived deterministic scope when the
 * builder reproduces it, else the option's own `scope` object (validated against the
 * promise). Returns `{scope, deterministic, conflict}`; `conflict` means the option
 * carries a typed scope that disagrees with its re-derived deterministic scope.
 */
function optionTypedScope(option, promiseText, allowDeterministic) {
  const own = option && option.scope != null ? validateAndCanonicalizeScope(option.scope, promiseText) : null;
  const match = allowDeterministic ? matchDeterministicScopeOption(option, promiseText) : null;
  if (match) {
    const conflict = Boolean(own) && typedScopeFingerprint(own) !== typedScopeFingerprint(match.scope);
    return { scope: match.scope, deterministic: true, conflict };
  }
  return { scope: own || unresolvedScope("MISSING_EVIDENCE"), deterministic: false, conflict: false };
}

/**
 * v1 contract: attach the canonical typed scope and close Start when it is missing or
 * unresolved. Two separate gates:
 *   - verified compound pick (server-local trust only): scope comes from the model's
 *     parse of the exact text compiled (full text for A, the narrowed clause for B/C);
 *   - any other pick: ONLY the snapshot entry the resolver verified (never fresh model
 *     options). Its typed scope is re-derived for allowlisted deterministic options,
 *     else taken from its own `scope`; a carried option must name the same typed scope.
 */
function applyScopeContract(dto, parsed, ctx) {
  const contract = scopeContractFor(ctx.promptVersion);
  dto.scopeContract = contract;
  if (contract !== "v1") {
    delete dto.scope;
    dto.clarificationOptions = (dto.clarificationOptions || []).map((opt) => {
      if (!opt || !("scope" in opt)) return opt;
      const { scope: _unused, ...rest } = opt;
      return rest;
    });
    return dto;
  }
  const promiseText = ctx.promiseText;
  const seenScopes = new Set();
  dto.clarificationOptions = (dto.clarificationOptions || [])
    .map((opt) => {
      const { scope } = optionTypedScope(opt, promiseText, true);
      const label = scopeOptionLabel(scope);
      return { ...opt, scope, label: label || opt.label };
    })
    .filter((opt) => {
      const key = typedScopeFingerprint(opt.scope);
      if (key == null) return true;
      if (seenScopes.has(key)) return false;
      seenScopes.add(key);
      return true;
    });

  const promiseUnchanged =
    typeof ctx.priorPromiseText === "string" &&
    ctx.priorPromiseText.trim() !== "" &&
    promiseBindingKey(ctx.priorPromiseText) === promiseBindingKey(promiseText);

  let scope;
  if (ctx.compoundVerified) {
    scope = validateAndCanonicalizeScope(parsed && parsed.scope, promiseText);
  } else if (ctx.selectedId) {
    const chosen = ctx.verifiedChoice;
    const chosenTyped = chosen ? optionTypedScope(chosen, promiseText, promiseUnchanged) : null;
    scope = chosenTyped ? chosenTyped.scope : unresolvedScope("MISSING_EVIDENCE");
    let failReason = null;
    if (chosenTyped && chosenTyped.conflict) failReason = "snapshot_typed_scope_not_reproducible";
    if (chosen && !failReason && !isEnforceableScope(scope)) failReason = "snapshot_invalid_typed_scope";
    if (chosen && !failReason && ctx.carriedOption) {
      const carried = optionTypedScope(ctx.carriedOption, promiseText, promiseUnchanged);
      if (carried.conflict || typedScopeFingerprint(carried.scope) !== typedScopeFingerprint(scope)) {
        failReason = "carry_snapshot_typed_scope_mismatch";
      }
    }
    if (failReason) {
      scope = unresolvedScope("MISSING_EVIDENCE", scope.evidenceSpans);
      dto.optionRematerialized = false;
      dto.optionVerification = "unrecognized_fail_closed";
      dto.optionVerificationReason = failReason;
      stripUsingChoiceClaims(dto);
      dto.cautionMessages = uniqueStrings([...(dto.cautionMessages || []), UNRECOGNIZED_CLARIFICATION_CHOICE]);
    }
  } else {
    scope = validateAndCanonicalizeScope(parsed && parsed.scope, promiseText);
  }
  Object.assign(dto, enforceScopeStartGates(dto, scope));
  if (isEnforceableScope(scope) && scope.kind === "APP_SHORT_FORM_ONLY") {
    applyAppShortFormScopeProjection(dto, scope);
  }
  return dto;
}

function applyAppShortFormScopeProjection(dto, scope) {
  const identity = scope.identity || (scope.include[0] && scope.include[0].identity) || "";
  const packages = uniqueStrings(
    scope.include.flatMap((clause) => clause.packages || []).concat(
      IDENTITY_DEFAULT_PACKAGES[identity] || []
    )
  );
  const appliesTo = scopeOptionLabel(scope);
  if (!appliesTo) return;

  dto.scopePackages = packages;
  dto.hiddenInternalScope = packages;
  dto.contentBrands = [];
  dto.surfaceScope = [];
  dto.contentScope = [];
  dto.scopeKind = identity === "YOUTUBE" ? "youtube_shorts_only" : "app_short_form_only";
  dto.conditionalSummaries = uniqueStrings([
    ...(dto.conditionalSummaries || []).filter(
      (line) => !/^applies to:/i.test(String(line || ""))
    ),
    `Applies to: ${appliesTo}`,
  ]);
  dto.confirmationPreview = uniqueStrings([
    ...(dto.confirmationPreview || []).filter(
      (line) => !/^applies to:/i.test(String(line || ""))
    ),
    `Applies to: ${appliesTo}`,
  ]);
  if (dto.userFacingConfirmation && typeof dto.userFacingConfirmation === "object") {
    dto.userFacingConfirmation = {
      ...dto.userFacingConfirmation,
      appliesTo,
      appliesToText: appliesTo,
      hiddenInternalScope: [],
    };
  }
}


function normalizeInternalPolicyPreview(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const packages = [];
  const seen = new Set();
  const pushPkg = (packageName, appLabel) => {
    const pkg =
      typeof packageName === "string" ? packageName.trim() : "";
    if (!pkg || seen.has(pkg)) return;
    seen.add(pkg);
    packages.push({
      packageName: pkg,
      appLabel:
        (typeof appLabel === "string" && appLabel.trim()) ||
        knownAppLabel(pkg),
    });
  };
  if (Array.isArray(raw.packages)) {
    for (const p of raw.packages) {
      if (!p || typeof p !== "object") continue;
      pushPkg(p.packageName, p.appLabel || p.label);
    }
  }
  if (Array.isArray(raw.scopePackages)) {
    for (const pkg of raw.scopePackages) {
      if (typeof pkg === "string") pushPkg(pkg, knownAppLabel(pkg));
    }
  }
  const scopeKind =
    typeof raw.scopeKind === "string" && raw.scopeKind.trim()
      ? raw.scopeKind.trim()
      : null;
  const appliesTo =
    typeof raw.appliesTo === "string" && raw.appliesTo.trim()
      ? sanitizeUserFacingCopy(raw.appliesTo.trim())
      : null;
  const expandShortForm = Boolean(raw.expandShortForm);
  const contentBrands = uniqueStrings(
    ["contentBrands", "contentScope", "surfaceScope"].flatMap((key) =>
      Array.isArray(raw[key])
        ? raw[key].filter((s) => typeof s === "string")
        : []
    )
  ).map((b) => normalizeContentBrand(b));
  if (
    packages.length === 0 &&
    !expandShortForm &&
    !scopeKind &&
    contentBrands.length === 0
  ) {
    return null;
  }
  return {
    scopeKind,
    appliesTo,
    expandShortForm,
    packages,
    scopePackages: packages.map((p) => p.packageName),
    contentBrands,
    surfaceScope: contentBrands,
    contentScope: contentBrands,
  };
}

function normalizeClarificationOptions(raw) {
  if (!Array.isArray(raw)) return [];
  const out = [];
  for (const item of raw) {
    if (!item || typeof item !== "object") continue;
    const id = typeof item.id === "string" ? item.id.trim() : "";
    const label = sanitizeUserFacingCopy(
      typeof item.label === "string" ? item.label : ""
    );
    const description = sanitizeUserFacingCopy(
      typeof item.description === "string" ? item.description : ""
    );
    if (!id || !label) continue;
    // v1 typed scope rides along untouched; applyScopeContract validates it.
    const scope =
      item.scope && typeof item.scope === "object" && !Array.isArray(item.scope)
        ? item.scope
        : undefined;
    const previewRaw =
      typeof item.policyPreview === "string"
        ? item.policyPreview
        : typeof item.resultingPolicyPreview === "string"
          ? item.resultingPolicyPreview
          : "";
    const policyPreview = sanitizeUserFacingCopy(previewRaw) || null;
    const internalPolicyPreview =
      normalizeInternalPolicyPreview(
        item.internalPolicyPreview || item.policyPreviewStructured
      ) || null;
    out.push({
      id,
      label,
      description,
      recommended: Boolean(item.recommended),
      policyPreview,
      // v07 alias kept for Android clients still reading resultingPolicyPreview
      resultingPolicyPreview: policyPreview,
      // Structured rematerialize payload (never shown raw to users).
      internalPolicyPreview,
      ...(scope ? { scope } : {}),
    });
  }
  return out.slice(0, 4);
}

function buildScopeInternalPreview({
  scopeKind,
  appliesTo,
  packages,
  expandShortForm = false,
  contentBrands = [],
}) {
  const pkgs = (packages || []).map((p) => ({
    packageName: p.packageName,
    appLabel: p.appLabel || knownAppLabel(p.packageName),
  }));
  const brands = uniqueStrings(contentBrands || []).map((b) =>
    normalizeContentBrand(b)
  );
  return {
    scopeKind,
    appliesTo: sanitizeUserFacingCopy(appliesTo || ""),
    expandShortForm: Boolean(expandShortForm),
    packages: pkgs,
    scopePackages: pkgs.map((p) => p.packageName),
    contentBrands: brands,
    surfaceScope: brands,
    contentScope: brands,
  };
}

function normalizeContentBrand(raw) {
  const lower = String(raw || "")
    .trim()
    .toLowerCase();
  if (!lower) return "";
  if (lower === "youtube" || lower.includes("youtube") || lower === "yt") {
    return "youtube";
  }
  return lower;
}

function namesYouTubeAppOnly(rawText) {
  const text = String(rawText || "").toLowerCase();
  return /youtube\s+app\s+only|only\s+(?:the\s+)?youtube\s+app|youtube\s+application\s+only|limit(?:\s+\w+){0,8}\s+to\s+(?:the\s+)?youtube\s+app/.test(
    text
  );
}

function namesYouTubeContentBrand(rawText) {
  const text = String(rawText || "").toLowerCase();
  if (namesYouTubeAppOnly(text)) return false;
  if (!/\byoutube\b/.test(text) && !/\byt\b/.test(text)) return false;
  return /\b(?:video|videos|content|lecture|lectures|watch|watching)\b/.test(
    text
  ) || looksLikeMediaLengthThreshold(text);
}

function stringListField(raw, ...keys) {
  for (const key of keys) {
    if (Array.isArray(raw[key])) {
      return raw[key]
        .filter((s) => typeof s === "string")
        .map(sanitizeUserFacingCopy)
        .filter(Boolean);
    }
  }
  return null;
}

function stringField(raw, ...keys) {
  for (const key of keys) {
    if (typeof raw[key] === "string" && raw[key].trim()) {
      return sanitizeUserFacingCopy(raw[key]);
    }
  }
  return "";
}

/**
 * Normalize v07 + v08 confirmation shapes into a dual-compatible object.
 * v08: understood / allowed / blocked / time / appliesTo / checkThis / safetyNotes
 * v07: understoodSummary / allowedBullets / blockedBullets / timeWindowText / appliesToText
 */
function normalizeUserFacingConfirmation(raw, fallbacks = {}) {
  if (raw && typeof raw === "object" && !Array.isArray(raw)) {
    const understood =
      stringField(raw, "understood", "understoodSummary") ||
      sanitizeUserFacingCopy(fallbacks.understoodSummary || fallbacks.understood || "");
    const allowed =
      stringListField(raw, "allowed", "allowedBullets") ||
      fallbacks.allowedBullets ||
      fallbacks.allowed ||
      [];
    const blocked =
      stringListField(raw, "blocked", "blockedBullets") ||
      fallbacks.blockedBullets ||
      fallbacks.blocked ||
      [];
    const time =
      stringField(raw, "time", "timeWindowText") ||
      sanitizeUserFacingCopy(fallbacks.timeWindowText || fallbacks.time || "");
    const appliesTo =
      stringField(raw, "appliesTo", "appliesToText") ||
      sanitizeUserFacingCopy(fallbacks.appliesToText || fallbacks.appliesTo || "");
    const checkThis =
      stringListField(raw, "checkThis") ||
      fallbacks.checkThis ||
      [];
    const safetyNotes =
      stringListField(raw, "safetyNotes") ||
      fallbacks.safetyNotes ||
      [];
    const hiddenInternalScope = Array.isArray(raw.hiddenInternalScope)
      ? raw.hiddenInternalScope.filter((s) => typeof s === "string")
      : fallbacks.hiddenInternalScope || [];
    return {
      // v08 preferred
      understood,
      allowed,
      blocked,
      time,
      appliesTo,
      checkThis,
      safetyNotes,
      // v07 aliases
      understoodSummary: understood,
      allowedBullets: allowed,
      blockedBullets: blocked,
      timeWindowText: time,
      appliesToText: appliesTo,
      hiddenInternalScope,
    };
  }
  if (typeof raw === "string" && raw.trim()) {
    const understood = sanitizeUserFacingCopy(raw);
    const allowed = fallbacks.allowedBullets || fallbacks.allowed || [];
    const blocked = fallbacks.blockedBullets || fallbacks.blocked || [];
    const time = sanitizeUserFacingCopy(
      fallbacks.timeWindowText || fallbacks.time || ""
    );
    const appliesTo = sanitizeUserFacingCopy(
      fallbacks.appliesToText || fallbacks.appliesTo || ""
    );
    return {
      understood,
      allowed,
      blocked,
      time,
      appliesTo,
      checkThis: fallbacks.checkThis || [],
      safetyNotes: fallbacks.safetyNotes || [],
      understoodSummary: understood,
      allowedBullets: allowed,
      blockedBullets: blocked,
      timeWindowText: time,
      appliesToText: appliesTo,
      hiddenInternalScope: fallbacks.hiddenInternalScope || [],
    };
  }
  const understood = sanitizeUserFacingCopy(
    fallbacks.understoodSummary || fallbacks.understood || ""
  );
  const allowed = fallbacks.allowedBullets || fallbacks.allowed || [];
  const blocked = fallbacks.blockedBullets || fallbacks.blocked || [];
  const time = sanitizeUserFacingCopy(
    fallbacks.timeWindowText || fallbacks.time || ""
  );
  const appliesTo = sanitizeUserFacingCopy(
    fallbacks.appliesToText || fallbacks.appliesTo || ""
  );
  return {
    understood,
    allowed,
    blocked,
    time,
    appliesTo,
    checkThis: fallbacks.checkThis || [],
    safetyNotes: fallbacks.safetyNotes || [],
    understoodSummary: understood,
    allowedBullets: allowed,
    blockedBullets: blocked,
    timeWindowText: time,
    appliesToText: appliesTo,
    hiddenInternalScope: fallbacks.hiddenInternalScope || [],
  };
}

function normalizeRiskReview(raw, opts = {}) {
  const levels = new Set(["none", "low", "medium", "high"]);
  const pick = (v, fallback) =>
    levels.has(String(v || "")) ? String(v) : fallback;
  const base =
    raw && typeof raw === "object" && !Array.isArray(raw) ? raw : {};
  return {
    falseAllowRisk: pick(base.falseAllowRisk, opts.falseAllowRisk || "low"),
    falseBlockRisk: pick(base.falseBlockRisk, opts.falseBlockRisk || "low"),
    safetyRisk: pick(base.safetyRisk, opts.safetyRisk || "none"),
    requiresHumanConfirmation: Boolean(
      base.requiresHumanConfirmation ?? opts.requiresHumanConfirmation
    ),
  };
}

function normalizeInternalPolicy(raw, built = {}) {
  const base =
    raw && typeof raw === "object" && !Array.isArray(raw) ? raw : {};
  return {
    timeWindow: base.timeWindow || built.timeWindow || null,
    surfaceRules: Array.isArray(base.surfaceRules)
      ? base.surfaceRules
      : built.surfaceRules || [],
    contentRules: Array.isArray(base.contentRules)
      ? base.contentRules
      : built.contentRules || [],
    appRules: Array.isArray(base.appRules) ? base.appRules : built.appRules || [],
    quotaRules: Array.isArray(base.quotaRules)
      ? base.quotaRules
      : built.quotaRules || [],
    recoveryRules:
      base.recoveryRules && typeof base.recoveryRules === "object"
        ? base.recoveryRules
        : built.recoveryRules || {
            hardness: "normal",
            requiresExplicitConfirm: false,
            notes: "",
          },
    guardrails: Array.isArray(base.guardrails)
      ? base.guardrails
      : built.guardrails || [],
  };
}

function isPermanentCommitment(parsed, promiseText) {
  const text = String(promiseText || "").toLowerCase();
  if (String(parsed.commitmentType || "") === "permanent_guardrail") return true;
  const dur = parsed.duration || {};
  if (String(dur.unit || "").toLowerCase() === "years") return true;
  if (String(dur.kind || "").toLowerCase() === "indefinite") return true;
  if (/\b(1\s*year|one\s*year|forever|permanent|for\s+life)\b/.test(text)) {
    return true;
  }
  return false;
}

function looksLikeStudyMode(parsed) {
  return (
    String(parsed.commitmentType || "") === "monk_mode" ||
    (Array.isArray(parsed.activeGuardrails) &&
      parsed.activeGuardrails.includes("no_social_feed") &&
      parsed.activeGuardrails.includes("no_short_form_video") &&
      parsed.activeGuardrails.includes("no_entertainment_installs"))
  );
}

/** “Normal phone except porn” must stay a guardrail — not study/monk lockdown. */
function isNormalPhoneExceptSafety(rawText) {
  const text = String(rawText || "").toLowerCase();
  const normalPhone =
    /\b(normal\s+phone|rest\s+(of\s+)?(my\s+)?phone\s+normal|phone\s+normal|baaki\s+normal)\b/.test(
      text
    );
  const safetyOnly =
    /\b(porn|adult|sexual|dating|gambling|betting)\b/.test(text) &&
    !/\b(study|exam|monk|focus\s+mode|only\s+study)\b/.test(text);
  return normalPhone && safetyOnly;
}

function explicitlyMentionsAdultSafety(rawText) {
  return /\b(porn|porno|nsfw|adult|sexual|sex|18\+|xxx|onlyfans|nude|nudity|hookup|dating)\b/i.test(
    String(rawText || "")
  );
}

function repairNormalPhoneExceptSafety(parsed, promiseText) {
  if (!isNormalPhoneExceptSafety(promiseText)) return parsed;
  const next = { ...parsed };
  if (
    next.commitmentType === "monk_mode" ||
    next.commitmentType === "focus_session"
  ) {
    next.commitmentType = "permanent_guardrail";
  }
  if (Array.isArray(next.activeGuardrails)) {
    next.activeGuardrails = next.activeGuardrails.filter(
      (g) =>
        g === "no_adult_content" ||
        g === "no_dating_apps" ||
        g === "no_gambling" ||
        g === "no_entertainment_installs"
    );
    if (!next.activeGuardrails.includes("no_adult_content")) {
      next.activeGuardrails = ["no_adult_content", ...next.activeGuardrails];
    }
  }
  return next;
}

function inferGoogleVideoClarification(rawText) {
  const text = String(rawText || "").toLowerCase();
  if (!/\bgoogle\s+video\b/.test(text) && !/\bgoogle\s+videos?\b/.test(text)) {
    return null;
  }
  return {
    question:
      "When you said “Google video,” what did you mean?",
    options: [
      {
        id: "A",
        label: "Videos inside Chrome / browser",
        description:
          "Apply the time and length rules to videos you open in Chrome or browser pages.",
        recommended: true,
        policyPreview:
          "Browser/Chrome video follows your length and time rules; other apps unchanged unless named.",
        internalPolicyPreview: buildScopeInternalPreview({
          scopeKind: "chrome_browser",
          appliesTo: "Chrome / browser video",
          packages: [{ packageName: PKG_CHROME, appLabel: "Chrome" }],
        }),
      },
      {
        id: "B",
        label: "All video apps on my phone",
        description:
          "Apply the same rules across YouTube-style apps, browser video, and similar video apps.",
        recommended: false,
        policyPreview:
          "All major video apps and browser video follow your length and time rules.",
        internalPolicyPreview: buildScopeInternalPreview({
          scopeKind: "all_video_apps",
          appliesTo: "Major video apps and browser video",
          packages: ALL_VIDEO_PLATFORM_PACKAGES,
        }),
      },
      {
        id: "C",
        label: "YouTube only",
        description: "Limit the rules to the YouTube app only.",
        recommended: false,
        policyPreview: "Only YouTube follows these rules for this commitment.",
        internalPolicyPreview: buildScopeInternalPreview({
          scopeKind: "youtube_only",
          appliesTo: "YouTube only",
          packages: [{ packageName: PKG_YOUTUBE, appLabel: "YouTube" }],
        }),
      },
    ],
  };
}

/** Quota / ban / count wording: a "shorts" promise with any of these is not bare. */
const SHORTS_NOT_BARE_RE = new RegExp(
  "\\b(?:" +
    [
      "at most", "up to", "only", "max", "maximum", "min", "minimum", "allow", "allowed",
      "block", "no", "not", "never", "ban", "stop", "avoid", "quit", "skip", "zero", "none",
      "limit", "limited", "cap", "quota", "budget", "less", "fewer", "under", "reduce", "cut",
      "few", "couple", "once", "twice", "times", "daily", "per day", "a day", "each day",
      "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
      "eleven", "twelve", "fifteen", "twenty", "thirty", "\\d+",
    ].join("|") +
    ")\\b"
);

/**
 * Bare “shorts” without quota/ban wording — ask all surfaces vs YouTube Shorts only.
 * Clear “10 shorts today” stays auto-expanded (no options) via shortFormCategory.
 * Eligibility is a pure function of the promise text so a pick can be re-derived.
 */
function inferShortFormScopeClarification(rawText) {
  const text = String(rawText || "").toLowerCase().trim();
  if (!text) return null;
  if (!/\bshorts?\b/.test(text) || SHORTS_NOT_BARE_RE.test(text)) {
    return null;
  }
  if (
    /\b(youtube\s+shorts?\s+only|only\s+youtube\s+shorts?|instagram\s+reels?\s+only)\b/.test(
      text
    )
  ) {
    return null;
  }
  return {
    question:
      "Did you mean all short-form video across short-video and reels-style apps, or only YouTube Shorts?",
    options: [
      {
        id: "A",
        label: "All short-form surfaces",
        description:
          "Count and gate Shorts, Reels, TikTok-style clips, and browser short video.",
        recommended: true,
        policyPreview:
          "All short-form video surfaces follow this commitment (not YouTube only).",
        internalPolicyPreview: buildScopeInternalPreview({
          scopeKind: "short_form_all_surfaces",
          appliesTo: "Short-form video apps and browser short videos",
          packages: SHORT_FORM_PLATFORM_PACKAGES,
          expandShortForm: true,
        }),
      },
      {
        id: "B",
        label: "YouTube Shorts only",
        description: "Limit short-form rules to the YouTube app only.",
        recommended: false,
        policyPreview: "Only YouTube Shorts follow this commitment.",
        internalPolicyPreview: buildScopeInternalPreview({
          scopeKind: "youtube_shorts_only",
          appliesTo: "YouTube Shorts only",
          packages: [{ packageName: PKG_YOUTUBE, appLabel: "YouTube" }],
          expandShortForm: false,
        }),
      },
    ],
  };
}

/**
 * Two exclusive worlds in one sentence ("allow only X, allow only Y") must ask.
 * "10 shorts today, never adult" composes — no extra question.
 */
function splitCompoundClauses(rawText) {
  return String(rawText || "")
    .split(/\s*(?:,|;|\bbut\b|\band then\b|\bsecond(?:ly)?(?:\s+is)?\b)\s*/i)
    .map((s) => s.trim())
    .filter((s) => s.length >= 6);
}

function isExclusiveCompoundClause(text) {
  return /\b(?:allow\s+only|only\s+allow)\b/i.test(text) || /\bmonk(?:\s+mode)?\b/i.test(text);
}

function needsCompoundIntentClarification(rawText) {
  const text = String(rawText || "").trim();
  if (!text) return false;
  const allowOnlyHits = text.match(/\b(?:allow\s+only|only\s+allow)\b/gi) || [];
  if (allowOnlyHits.length >= 2) return true;
  return splitCompoundClauses(text).filter(isExclusiveCompoundClause).length >= 2;
}

function resolveCompoundCompileText(rawText, selectedOptionId) {
  const text = String(rawText || "").trim();
  const id = String(selectedOptionId || "").trim().toUpperCase();
  if (!id || id === "A" || !needsCompoundIntentClarification(text)) return text;
  const exclusive = splitCompoundClauses(text).filter(isExclusiveCompoundClause);
  if (id === "B") return exclusive[0] || text;
  if (id === "C") return exclusive[1] || exclusive[0] || text;
  return text;
}

function inferCompoundIntentClarification(rawText) {
  if (!needsCompoundIntentClarification(rawText)) return null;
  const exclusive = splitCompoundClauses(rawText).filter(isExclusiveCompoundClause);
  const first = (exclusive[0] || "the first part").slice(0, 80);
  const second = (exclusive[1] || "the second part").slice(0, 80);
  const twoAllowOnly =
    (String(rawText || "").match(/\b(?:allow\s+only|only\s+allow)\b/gi) || []).length >= 2;
  return {
    question: "You wrote two different phone rules. Which should Strlix enforce?",
    options: [
      {
        id: "A",
        label: "Both together",
        description:
          "One commitment with both parts. The stricter rule wins if they clash.",
        recommended: !twoAllowOnly,
        policyPreview: "Run both rules in one session.",
        internalPolicyPreview: {
          scopeKind: "compound_combine",
          appliesTo: "Both parts of your promise",
          packages: [],
          expandShortForm: false,
        },
      },
      {
        id: "B",
        label: "Only the first",
        description: first,
        recommended: twoAllowOnly,
        policyPreview: `Enforce only: ${first}`,
      },
      {
        id: "C",
        label: "Only the second",
        description: second,
        recommended: false,
        policyPreview: `Enforce only: ${second}`,
      },
    ],
  };
}

/**
 * Apps whose short-form player is a structured, detectable surface. A stuck scope for
 * one of these can be answered with exactly two policy-complete choices: its
 * short-form player only (APP_SHORT_FORM_ONLY + structured short-form BLOCK) or the
 * whole app (APP_ONLY + app BLOCK). Feed and message surfaces are not offered: the
 * runtime cannot yet tell them apart reliably, so no choice may promise them.
 */
const APP_SURFACE_FAMILY = [
  {
    key: "youtube",
    identity: "YOUTUBE",
    packageName: PKG_YOUTUBE,
    label: "YouTube",
    nameRe: /\byou\s?tube\b|\byt\b/i,
    shortRe: /\b(?:shorts?|short[\s-]*(?:form|videos?|clips?))\b/i,
    shortName: "YouTube Shorts",
    player: "Shorts",
    restOpen: "YouTube Home, search, and long videos",
  },
  {
    key: "instagram",
    identity: "INSTAGRAM",
    packageName: "com.instagram.android",
    label: "Instagram",
    nameRe: /\binstagram\b|\binsta\b|\big\b/i,
    shortRe: /\b(?:reels?|short[\s-]*(?:form|videos?|clips?))\b/i,
    shortName: "Instagram Reels",
    player: "Reels",
    restOpen: "The Instagram feed, Stories, and messages",
  },
];
const APP_SURFACE_SHORT_PREFIX = "app_surface_short_form_";
const APP_SURFACE_WHOLE_PREFIX = "app_surface_whole_app_";

const SURFACE_SHORT_WORD = "(?:shorts?|reels?|short[\\s-]*(?:form|videos?|clips?))";
const EXCEPT_SHORT_FORM_RE = new RegExp(
  `\\b(?:except|but|besides|other than|apart from|excluding|not)\\s+(?:the\\s+|for\\s+|its\\s+)?${SURFACE_SHORT_WORD}`,
  "i"
);
const LONG_ONLY_RE =
  /\blong(?:[\s-]*form)?\s+videos?\s+only\b|\b(?:only|just)\s+(?:the\s+)?long(?:[\s-]*form)?\b/i;
const NEGATED_SHORT_BLOCK_RE = new RegExp(
  `\\b(?:don'?t|do not|never|no need to)\\s+block\\s+(?:the\\s+|my\\s+)?(?:\\w+\\s+)?${SURFACE_SHORT_WORD}`,
  "i"
);
const OTHER_APP_SURFACE_RE =
  /\b(feeds?|home|comments?|messages?|dms?|direct|inbox|chats?|stor(?:y|ies)|search|explore|long|subscriptions?|posts?|profiles?|live|music|timeline)\b/i;
const MESSAGE_SURFACE_RE = /\b(messages?|dms?|direct messages?|inbox|chats?)\b/i;
const FEED_SURFACE_RE = /\b(feeds?|timeline|explore)\b/i;
const KEEP_SURFACE_RE =
  /\b(keep|keeps|allow|allowed|leave|open|available|fine|ok|okay|still|stay|stays|usable|unblocked|except|besides|excluding|other than|apart from|without)\b/i;
const BLOCK_VERB_RE = /\b(block|blocked|ban|stop|no|lock|limit|restrict)\b/i;
/** Social apps whose feed and message screens live inside the same package as everything else. */
const MIXED_SOCIAL_APPS = [
  { packageName: "com.instagram.android", label: "Instagram", nameRe: /\binstagram\b|\binsta\b|\big\b/i },
  { packageName: "com.facebook.katana", label: "Facebook", nameRe: /\bfacebook\b|\bfb\b/i },
  { packageName: "com.snapchat.android", label: "Snapchat", nameRe: /\bsnapchat\b|\bsnap\b/i },
];

/**
 * Promise text split into clauses, each marked kept (names what stays open) or not.
 * A list item with neither a keep word nor a block verb ("keep Home, long videos, …")
 * inherits the previous clause's intent.
 */
function surfaceClauses(text) {
  const parts = String(text || "")
    .toLowerCase()
    .split(/[.;!?\n,]|\b(?:and then|but|while|however)\b|\band\s+(?=keep|allow|leave)/)
    .map((s) => s.trim())
    .filter(Boolean);
  let kept = false;
  return parts.map((c) => {
    if (KEEP_SURFACE_RE.test(c)) kept = true;
    else if (BLOCK_VERB_RE.test(c)) kept = false;
    return { text: c, kept };
  });
}

/** True when the promise names `surfaceRe` as something to block (not kept open). */
function surfaceIsBlockTarget(text, surfaceRe) {
  const lower = String(text || "").toLowerCase();
  const excluded = new RegExp(
    `\\b(?:except|besides|other than|apart from|excluding|without|but not)\\s+(?:the\\s+|my\\s+|its\\s+)?(?:\\w+\\s+)?${surfaceRe.source}`,
    "i"
  );
  if (excluded.test(lower)) return false;
  return surfaceClauses(lower).some((c) => surfaceRe.test(c.text) && !c.kept);
}

function surfaceIsKeptOpen(text, surfaceRe) {
  const lower = String(text || "").toLowerCase();
  if (!surfaceRe.test(lower)) return false;
  return !surfaceIsBlockTarget(lower, surfaceRe);
}

function mentionedMixedSocialApps(text) {
  return MIXED_SOCIAL_APPS.filter((a) => a.nameRe.test(String(text || "")));
}

function appSurfaceScopeKinds(entry) {
  return {
    short: `${APP_SURFACE_SHORT_PREFIX}${entry.key}`,
    whole: `${APP_SURFACE_WHOLE_PREFIX}${entry.key}`,
  };
}

function appSurfaceEntryForScopeKind(scopeKind) {
  const kind = String(scopeKind || "");
  for (const entry of APP_SURFACE_FAMILY) {
    const k = appSurfaceScopeKinds(entry);
    if (kind === k.short) return { entry, choice: "short" };
    if (kind === k.whole) return { entry, choice: "whole" };
  }
  return null;
}

/**
 * The app a promise asks to narrow to its short-form player, or null. A pure function
 * of the promise text so a pick can be re-derived: exactly one family app named, its
 * short-form surface named, no exclusion/negation of short-form, and every other
 * surface mentioned only as something that stays open.
 */
function appSurfaceFamilyEntry(rawText) {
  const text = String(rawText || "").trim();
  if (!text) return null;
  if (
    inferGoogleVideoClarification(text) ||
    inferShortFormScopeClarification(text) ||
    needsCompoundIntentClarification(text)
  ) {
    return null;
  }
  const named = APP_SURFACE_FAMILY.filter((e) => e.nameRe.test(text));
  if (named.length !== 1 || mentionedMixedSocialApps(text).some((a) => a.packageName !== named[0].packageName)) {
    return null;
  }
  const entry = named[0];
  if (!entry.shortRe.test(text)) return null;
  if (EXCEPT_SHORT_FORM_RE.test(text) || LONG_ONLY_RE.test(text) || NEGATED_SHORT_BLOCK_RE.test(text)) {
    return null;
  }
  const otherSurfaceBlocked = surfaceClauses(text).some(
    (c) => OTHER_APP_SURFACE_RE.test(c.text) && !c.kept
  );
  if (otherSurfaceBlocked) return null;
  return entry;
}

function inferAppSurfaceClarification(rawText) {
  const entry = appSurfaceFamilyEntry(rawText);
  if (!entry) return null;
  const kinds = appSurfaceScopeKinds(entry);
  const pkgs = [{ packageName: entry.packageName, appLabel: entry.label }];
  return {
    entry,
    question: `Which part of ${entry.label} should Strlix block?`,
    options: [
      {
        id: "A",
        label: `Only ${entry.shortName}`,
        description: `Blocks the ${entry.player} player only. ${entry.restOpen} stay open.`,
        recommended: false,
        policyPreview: `${entry.shortName} are blocked; the rest of ${entry.label} stays open.`,
        internalPolicyPreview: buildScopeInternalPreview({
          scopeKind: kinds.short,
          appliesTo: entry.shortName,
          packages: pkgs,
        }),
      },
      {
        id: "B",
        label: `All of ${entry.label}`,
        description: `Blocks the whole ${entry.label} app while this promise runs.`,
        recommended: false,
        policyPreview: `All of ${entry.label} is blocked.`,
        internalPolicyPreview: buildScopeInternalPreview({
          scopeKind: kinds.whole,
          appliesTo: `All of ${entry.label}`,
          packages: pkgs,
        }),
      },
    ],
  };
}

/** Specific, truthful copy for a scope Strlix cannot enforce as written. */
function appScopeGuidance(promiseText, label, reason, packageName) {
  const text = String(promiseText || "");
  const canShort = SHORT_FORM_PLATFORM_PACKAGES.some((p) => p.packageName === packageName);
  const edit = `Edit the promise to block all of ${label}${canShort ? ", or only its short videos," : ""} before Start.`;
  if (EXCEPT_SHORT_FORM_RE.test(text) || LONG_ONLY_RE.test(text)) {
    return `Strlix can't yet block ${label} except its short videos, or only its long videos. ${edit}`;
  }
  const messagesBlocked = surfaceIsBlockTarget(text, MESSAGE_SURFACE_RE);
  if (reason === "messages_surface_unsupported" || (reason === "unrepresentable_app_scope" && messagesBlocked)) {
    return `Strlix can't block only ${label} messages yet: it can't reliably tell messages apart from other ${label} screens. ${edit}`;
  }
  if (reason === "messages_kept_but_whole_app_blocked") {
    return `Strlix can't yet keep only ${label} messages open while blocking the rest: it can't reliably tell messages apart from other ${label} screens. ${edit}`;
  }
  if (reason === "feed_surface_unsupported" ||
      (reason === "unrepresentable_app_scope" && surfaceIsBlockTarget(text, FEED_SURFACE_RE))) {
    return `Strlix can't block only the ${label} feed yet. ${edit}`;
  }
  return reason === "unrepresentable_app_scope"
    ? `I couldn't tell which part of ${label} to block. Edit the promise to name it before Start.`
    : `Your promise both limits and fully blocks ${label}. Pick one before Start.`;
}

const ALL_VIDEO_SCOPE_RE =
  /all video apps|all major video|every video app|all (?:the )?apps and browsers|all video (?:sources|platforms)|video apps and browsers|across (?:all )?video apps|wherever you watch/;
/** Broad wording that must never collapse to a single browser package. */
const BROAD_SCOPE_RE = /all video|\ball\b[^.]*\b(?:apps|browsers)\b|\bevery\b|\bacross\b|wherever/;

/**
 * Resolve a rematerialize payload from structured preview or known option fingerprints.
 * Text-only previews without a recognizable scope fingerprint are NOT usable.
 */
function resolveOptionRematerialize(option) {
  if (!option || typeof option !== "object") return null;
  const structured = normalizeInternalPolicyPreview(option.internalPolicyPreview);
  if (structured && String(structured.scopeKind || "") === "compound_combine") {
    return structured;
  }
  if (
    structured &&
    (structured.packages.length > 0 ||
      structured.expandShortForm ||
      (structured.contentBrands && structured.contentBrands.length > 0) ||
      structured.scopeKind === "youtube_content")
  ) {
    return structured;
  }
  const blob = [
    option.id,
    option.label,
    option.description,
    option.policyPreview,
    option.resultingPolicyPreview,
  ]
    .filter((s) => typeof s === "string")
    .join(" ")
    .toLowerCase();
  if (!blob.trim()) return null;

  if (
    /youtube\s+shorts?\s+only|only\s+youtube\s+shorts?/.test(blob)
  ) {
    return buildScopeInternalPreview({
      scopeKind: "youtube_shorts_only",
      appliesTo: "YouTube Shorts only",
      packages: [{ packageName: PKG_YOUTUBE, appLabel: "YouTube" }],
    });
  }
  if (
    /all short-form|all shorts|reels-style|short-form surfaces|short-video and reels/.test(
      blob
    )
  ) {
    return buildScopeInternalPreview({
      scopeKind: "short_form_all_surfaces",
      appliesTo: "Short-form video apps and browser short videos",
      packages: SHORT_FORM_PLATFORM_PACKAGES,
      expandShortForm: true,
    });
  }
  if (
    /youtube (?:app )?only|only (?:the )?youtube(?! shorts)(?: app)?/.test(blob) &&
    !ALL_VIDEO_SCOPE_RE.test(blob)
  ) {
    return buildScopeInternalPreview({
      scopeKind: "youtube_only",
      appliesTo: "YouTube only",
      packages: [{ packageName: PKG_YOUTUBE, appLabel: "YouTube" }],
    });
  }
  if (ALL_VIDEO_SCOPE_RE.test(blob)) {
    return buildScopeInternalPreview({
      scopeKind: "all_video_apps",
      appliesTo: "Major video apps and browser video",
      packages: ALL_VIDEO_PLATFORM_PACKAGES,
    });
  }
  if (
    (/chrome|browser/.test(blob) && !BROAD_SCOPE_RE.test(blob)) ||
    /videos inside chrome/.test(blob)
  ) {
    return buildScopeInternalPreview({
      scopeKind: "chrome_browser",
      appliesTo: "Chrome / browser video",
      packages: [{ packageName: PKG_CHROME, appLabel: "Chrome" }],
    });
  }
  return null;
}

function clarificationOptionKey(option) {
  if (!option || typeof option !== "object" || typeof option.id !== "string") return "";
  return option.id.trim().toUpperCase();
}

/** Policy-relevant identity of a scope preview (labels/copy excluded). */
function scopeFingerprint(preview) {
  if (!preview) return null;
  const pkgs = (preview.packages || [])
    .map((p) => (p && p.packageName) || "")
    .concat(preview.scopePackages || [])
    .filter(Boolean);
  return JSON.stringify({
    scopeKind: String(preview.scopeKind || ""),
    packages: [...new Set(pkgs)].sort(),
    expandShortForm: Boolean(preview.expandShortForm),
    contentBrands: [...new Set(preview.contentBrands || [])].sort(),
  });
}

function promiseBindingKey(text) {
  return String(text || "").trim().replace(/\s+/g, " ").toLowerCase();
}

/**
 * Deterministic authority for a clarification pick. Fresh model options are
 * deliberately NOT an input: ids are per-response labels, so a same-id option
 * from a later completion can mean a different scope.
 *
 * Authority: the prior options snapshot (what the user saw). The selected id
 * must appear exactly once and resolve to a valid scope. A carried option must
 * agree with that entry. Without a snapshot, a valid carried option is accepted.
 * Anything else fails closed.
 *
 * @returns {{ok: boolean, reason: string, option?: object, preview?: object}}
 */
function resolveClarificationSelection({
  selectedId,
  snapshot,
  carried,
  priorPromiseText,
  promiseText,
}) {
  const id = String(selectedId || "").trim().toUpperCase();
  const fail = (reason) => ({ ok: false, reason });
  if (!id) return fail("no_selection");
  if (
    typeof priorPromiseText === "string" &&
    priorPromiseText.trim() &&
    promiseBindingKey(priorPromiseText) !== promiseBindingKey(promiseText)
  ) {
    return fail("promise_text_changed");
  }
  const carriedPresent = Boolean(carried && typeof carried === "object");
  const carriedPreview =
    carriedPresent && clarificationOptionKey(carried) === id
      ? resolveOptionRematerialize(carried)
      : null;

  if (Array.isArray(snapshot) && snapshot.length > 0) {
    const keys = snapshot.map(clarificationOptionKey);
    if (keys.some((k) => !k) || new Set(keys).size !== keys.length) {
      return fail("snapshot_duplicate_ids");
    }
    const entries = snapshot.filter((o) => clarificationOptionKey(o) === id);
    if (entries.length !== 1) return fail("snapshot_missing_id");
    const entry = entries[0];
    const preview = resolveOptionRematerialize(entry);
    if (!preview) return fail("snapshot_invalid_scope");
    if (carriedPresent) {
      if (!carriedPreview) return fail("carry_invalid");
      if (scopeFingerprint(carriedPreview) !== scopeFingerprint(preview)) {
        return fail("carry_snapshot_mismatch");
      }
      return { ok: true, reason: "snapshot_and_carry_verified", option: entry, preview };
    }
    return { ok: true, reason: "snapshot_verified", option: entry, preview };
  }
  if (carriedPreview) {
    return { ok: true, reason: "carried_full_text", option: carried, preview: carriedPreview };
  }
  return fail(carriedPresent ? "carry_invalid" : "no_verifiable_choice");
}

/**
 * An app-surface pick rewrites that app's rules, so on top of snapshot/carry checks it
 * must be reproduced by the server builder for the unchanged promise text, under the
 * typed (v1) contract. Anything else fails closed.
 */
function verifyAppSurfaceResolution(resolution, { promiseText, priorPromiseText, contract }) {
  if (!resolution || !resolution.ok || !appSurfaceEntryForScopeKind(resolution.preview && resolution.preview.scopeKind)) {
    return resolution;
  }
  const fail = { ok: false, reason: "app_surface_choice_not_reproducible" };
  if (contract !== "v1") return fail;
  if (typeof priorPromiseText !== "string" || promiseBindingKey(priorPromiseText) !== promiseBindingKey(promiseText)) {
    return fail;
  }
  const match = matchDeterministicScopeOption(resolution.option, promiseText);
  if (!match || match.scopeKind !== resolution.preview.scopeKind) return fail;
  return resolution;
}

// -----------------------------------------------------------------------------
// Deterministic clarification options: canonical projection + re-derivation.
//
// Stateless recomputation proves an option is the canonical server-built option for
// the unchanged promise text. It does NOT prove the option was previously displayed;
// the user still confirms the resolved policy before Start.
// -----------------------------------------------------------------------------

function projectionText(value, promiseText) {
  if (typeof value !== "string") return "";
  return sanitizeUserFacingCopy(value, promiseText).replace(/\s+/g, " ").trim();
}

/** Every user-visible field of an option, normalized the way responses are. Key order is irrelevant. */
function clarificationOptionProjection(option, promiseText) {
  if (!option || typeof option !== "object") return null;
  const id = clarificationOptionKey(option);
  if (!id) return null;
  const policyPreview = projectionText(
    typeof option.policyPreview === "string" ? option.policyPreview : option.resultingPolicyPreview,
    promiseText
  );
  const resultingPolicyPreview =
    typeof option.resultingPolicyPreview === "string"
      ? projectionText(option.resultingPolicyPreview, promiseText)
      : policyPreview;
  return {
    id,
    label: projectionText(option.label, promiseText),
    description: projectionText(option.description, promiseText),
    policyPreview,
    resultingPolicyPreview,
    recommended: option.recommended === true,
  };
}

function internalPreviewFingerprint(option) {
  return scopeFingerprint(normalizeInternalPolicyPreview(option && option.internalPolicyPreview));
}

/**
 * Visible fields + policy-relevant internalPolicyPreview must match. `acceptedLabels`
 * lists the server-generated labels this option may carry (builder label, and under
 * the v1 contract its canonical typed-scope label).
 */
function sameDeterministicOption(candidate, canonical, promiseText, acceptedLabels) {
  const a = clarificationOptionProjection(candidate, promiseText);
  const b = clarificationOptionProjection(canonical, promiseText);
  if (!a || !b) return false;
  const labels = new Set(
    (acceptedLabels || [canonical.label]).map((l) => projectionText(l, promiseText))
  );
  return (
    a.id === b.id &&
    labels.has(a.label) &&
    a.description === b.description &&
    a.policyPreview === b.policyPreview &&
    a.resultingPolicyPreview === b.resultingPolicyPreview &&
    a.recommended === b.recommended &&
    internalPreviewFingerprint(candidate) === internalPreviewFingerprint(canonical)
  );
}

/** The options a response for this exact text would have carried, after response normalization. */
function displayedBuilderOptions(builderOptions, promiseText) {
  return normalizeClarificationOptions(builderOptions).map((o) => ({
    ...o,
    internalPolicyPreview: o.internalPolicyPreview
      ? { ...o.internalPolicyPreview, appliesTo: sanitizeUserFacingCopy(o.internalPolicyPreview.appliesTo || "", promiseText) || null }
      : null,
  }));
}

/**
 * The only legacy scopeKind -> typed scope mappings. Allowlisted server-built
 * families only; `compound_combine` is intent, not scope, and is never bridged.
 * `chrome_browser` intentionally becomes BROWSER_VIDEO (any verified browser),
 * replacing the legacy Chrome-pinned package list.
 */
const DETERMINISTIC_SCOPE_BRIDGE = {
  chrome_browser: { kind: "BROWSER_VIDEO", identity: "VIDEO_ANY" },
  all_video_apps: { kind: "VIDEO_CLASS_ANY_HOST", identity: "VIDEO_ANY" },
  youtube_only: { kind: "APP_ONLY", identity: "YOUTUBE" },
  short_form_all_surfaces: { kind: "SHORT_FORM_ANY_HOST", identity: "SHORT_FORM_ANY" },
  youtube_shorts_only: { kind: "APP_SHORT_FORM_ONLY", identity: "YOUTUBE" },
};
for (const entry of APP_SURFACE_FAMILY) {
  const k = appSurfaceScopeKinds(entry);
  DETERMINISTIC_SCOPE_BRIDGE[k.short] = { kind: "APP_SHORT_FORM_ONLY", identity: entry.identity };
  DETERMINISTIC_SCOPE_BRIDGE[k.whole] = { kind: "APP_ONLY", identity: entry.identity };
}

function bridgedTypedScope(scopeKind, evidence) {
  const target = DETERMINISTIC_SCOPE_BRIDGE[scopeKind];
  if (!target || !evidence) return null;
  const expansion = expandScopePreset(target.kind, target.identity, [], []);
  if (!expansion) return null;
  const scope = canonicalizeScope({
    version: SCOPE_VERSION_V1,
    kind: target.kind,
    resolution: "EXCLUSIVE",
    identity: target.identity,
    include: expansion.include,
    exempt: expansion.exempt,
    confidence: 1,
    evidenceSpans: [evidence],
  });
  return isEnforceableScope(scope) ? scope : null;
}

/** Families that pin scope, in the same precedence normalizeCompiledPromise uses. */
function deterministicScopeFamily(promiseText) {
  const text = String(promiseText || "");
  const google = inferGoogleVideoClarification(text);
  if (google) {
    const m = text.match(/google\s+videos?/i);
    return { options: displayedBuilderOptions(google.options, text), evidence: m ? m[0] : null };
  }
  const shorts = inferShortFormScopeClarification(text);
  if (shorts) {
    const m = text.match(/\bshorts?\b/i);
    return { options: displayedBuilderOptions(shorts.options, text), evidence: m ? m[0] : null };
  }
  const surface = inferAppSurfaceClarification(text);
  if (surface) {
    const m = text.match(surface.entry.nameRe);
    return { options: displayedBuilderOptions(surface.options, text), evidence: m ? m[0] : null };
  }
  return null;
}

/**
 * Typed scope for an option ONLY if re-running the deterministic builder on this
 * promise text reproduces it (all visible fields + internalPolicyPreview) and its
 * scopeKind is allowlisted. A matching scopeKind string alone never qualifies.
 */
function matchDeterministicScopeOption(option, promiseText) {
  const family = deterministicScopeFamily(promiseText);
  if (!family) return null;
  const id = clarificationOptionKey(option);
  const canonical = family.options.find((o) => clarificationOptionKey(o) === id);
  if (!canonical || !canonical.internalPolicyPreview) return null;
  const scopeKind = String(canonical.internalPolicyPreview.scopeKind || "");
  const scope = bridgedTypedScope(scopeKind, family.evidence);
  if (!scope) return null;
  const labels = [canonical.label, scopeOptionLabel(scope)].filter(Boolean);
  if (!sameDeterministicOption(option, canonical, promiseText, labels)) return null;
  return { scopeKind, scope };
}

/**
 * Identity gate for a compound A/B/C pick. Runs BEFORE clause narrowing or any
 * model call. Deliberately separate from resolveClarificationSelection: B/C carry
 * no rematerializable preview, so this proves identity, not scope.
 *
 * @returns {{ok: true, reason: string, id: string, option: object} | {ok: false, reason: string}}
 */
function verifyCompoundClauseSelection({ selectedId, snapshot, carried, priorPromiseText, promiseText }) {
  const id = String(selectedId || "").trim().toUpperCase();
  const fail = (reason) => ({ ok: false, reason });
  const text = String(promiseText || "").trim();
  if (!id) return fail("no_selection");
  if (!needsCompoundIntentClarification(text)) return fail("not_compound");
  if (typeof priorPromiseText !== "string" || !priorPromiseText.trim()) {
    return fail("prior_promise_text_missing");
  }
  if (promiseBindingKey(priorPromiseText) !== promiseBindingKey(text)) {
    return fail("promise_text_changed");
  }
  if (!Array.isArray(snapshot) || snapshot.length === 0) return fail("no_verifiable_choice");
  const keys = snapshot.map(clarificationOptionKey);
  if (keys.some((k) => !k) || new Set(keys).size !== keys.length) {
    return fail("snapshot_duplicate_ids");
  }
  const entries = snapshot.filter((o) => clarificationOptionKey(o) === id);
  if (entries.length !== 1) return fail("snapshot_missing_id");
  const entry = entries[0];
  if (carried != null) {
    if (typeof carried !== "object" || Array.isArray(carried)) return fail("carry_snapshot_mismatch");
    const a = clarificationOptionProjection(carried, text);
    const b = clarificationOptionProjection(entry, text);
    if (!a || !b || JSON.stringify(a) !== JSON.stringify(b) ||
        internalPreviewFingerprint(carried) !== internalPreviewFingerprint(entry)) {
      return fail("carry_snapshot_mismatch");
    }
  }
  const fresh = inferCompoundIntentClarification(text);
  const canonical = fresh
    ? displayedBuilderOptions(fresh.options, text).find((o) => clarificationOptionKey(o) === id)
    : null;
  if (!canonical || !sameDeterministicOption(entry, canonical, text, [canonical.label])) {
    return fail("snapshot_not_reproducible");
  }
  return { ok: true, reason: "compound_clause_verified", id, option: canonical };
}

/** Model prose that claims the pick is missing/unverified — false once the resolver verified it. */
const UNVERIFIED_CHOICE_COPY_RE =
  /\b(?:selection|choice|option|pick)\b[^.]*\b(?:no (?:earlier|previous|prior|previously defined)|without (?:its |the |an )?(?:earlier|previous|prior)|not (?:been )?(?:verified|recognized|attached)|unverified|cannot (?:reliably )?(?:map|verify|confirm)|could not (?:verify|map)|missing|need to confirm)/i;
const PENDING_SCOPE_COPY_RE =
  /\b(?:video )?(?:sources?|scope)\b[^.]*\bstill need(?:s)? (?:confirmation|to be confirmed)|\bplease (?:choose|confirm) where\b|\bneed to confirm (?:which|where)\b/i;

function stripStaleChoiceSentences(text) {
  if (typeof text !== "string" || !text) return text;
  const parts = text.split(/(?<=[.!?])\s+/);
  const kept = parts
    .map((part) => {
      if (UNVERIFIED_CHOICE_COPY_RE.test(part)) return "";
      const clauses = part.split(/;\s*/);
      const keptClauses = clauses.filter((c) => !PENDING_SCOPE_COPY_RE.test(c));
      if (keptClauses.length === clauses.length) return part;
      const joined = keptClauses.join("; ").trim();
      return joined && !/[.!?]$/.test(joined) ? `${joined}.` : joined;
    })
    .filter(Boolean);
  return kept.join(" ").trim();
}

/** Model-written "Using your choice: …" is never authoritative; only the resolver adds one. */
function stripUsingChoiceClaims(dto) {
  const keep = (arr) =>
    Array.isArray(arr)
      ? arr.filter((s) => !/^using your choice:/i.test(String(s || "").trim()))
      : arr;
  dto.cautionMessages = keep(dto.cautionMessages);
  dto.interpretationNotes = keep(dto.interpretationNotes);
  if (dto.userFacingConfirmation && typeof dto.userFacingConfirmation === "object") {
    dto.userFacingConfirmation = {
      ...dto.userFacingConfirmation,
      checkThis: keep(dto.userFacingConfirmation.checkThis),
    };
  }
}

/** After a verified pick: drop "choice missing" prose and stale "Using your choice" lines. */
function scrubStaleChoiceCopy(dto) {
  const isStale = (s) =>
    UNVERIFIED_CHOICE_COPY_RE.test(String(s || "")) ||
    PENDING_SCOPE_COPY_RE.test(String(s || "")) ||
    /^using your choice:/i.test(String(s || "").trim());
  const clean = (arr) => (Array.isArray(arr) ? arr.filter((s) => !isStale(s)) : arr);
  dto.cautionMessages = clean(dto.cautionMessages);
  dto.interpretationNotes = clean(dto.interpretationNotes);
  const ufc = dto.userFacingConfirmation;
  if (ufc && typeof ufc === "object") {
    dto.userFacingConfirmation = {
      ...ufc,
      checkThis: clean(ufc.checkThis),
      understood: stripStaleChoiceSentences(ufc.understood),
      understoodSummary: stripStaleChoiceSentences(ufc.understoodSummary),
    };
  }
}

function isVideoishContentRule(rule) {
  if (!rule || typeof rule !== "object") return false;
  const type = String(rule.contentType || "").toLowerCase();
  const surface = String(rule.surface || "").toLowerCase();
  if (
    type === "adult_sexual" ||
    type === "usage_quota" ||
    type === "messaging" ||
    type === "social_dm"
  ) {
    return false;
  }
  return (
    type.includes("video") ||
    type === "study" ||
    surface === "video" ||
    surface === "shorts" ||
    surface === "reels" ||
    surface === "player" ||
    (rule.unit === "minutes" && rule.operator) ||
    (rule.unit === "count" && type.includes("short"))
  );
}

/**
 * Merge option scope into enforceable DTO fields (contentRules, suggestedAppRules,
 * scopePackages, internalPolicy, user-facing appliesTo). Fail-closed caller checks null.
 */
function rematerializeDtoFromOptionPreview(dto, preview, promiseText) {
  if (!dto || !preview) return false;
  if (String(preview.scopeKind || "") === "compound_combine") {
    dto.optionRematerialized = true;
    dto.optionScopeKind = "compound_combine";
    return true;
  }
  const surfaceChoice = appSurfaceEntryForScopeKind(preview.scopeKind);
  if (surfaceChoice) {
    return materializeAppSurfaceChoice(dto, surfaceChoice, String(preview.scopeKind));
  }
  const packages =
    Array.isArray(preview.packages) && preview.packages.length
      ? preview.packages
      : (preview.scopePackages || []).map((pkg) => ({
          packageName: pkg,
          appLabel: knownAppLabel(pkg),
        }));
  const previewBrands = uniqueStrings([
    ...(preview.contentBrands || []),
    ...(preview.contentScope || []),
    ...(preview.surfaceScope || []),
  ]).map((b) => normalizeContentBrand(b));
  if (
    packages.length === 0 &&
    !preview.expandShortForm &&
    previewBrands.length === 0 &&
    preview.scopeKind !== "youtube_content"
  ) {
    return false;
  }

  const scopePkgs = packages.map((p) => p.packageName);
  const pkgSet = new Set(scopePkgs);

  // Stamp / expand videoish content rules onto selected packages.
  const nextRules = [];
  for (const rule of dto.contentRules || []) {
    if (!isVideoishContentRule(rule)) {
      nextRules.push(rule);
      continue;
    }
    // Already package-scoped: keep only if in selected scope (or unscoped adult kept above).
    if (rule.packageName) {
      if (pkgSet.has(rule.packageName)) nextRules.push(rule);
      continue;
    }
    if (packages.length === 1) {
      nextRules.push({
        ...rule,
        packageName: packages[0].packageName,
        appLabel: packages[0].appLabel,
      });
    } else {
      for (const p of packages) {
        nextRules.push({
          ...rule,
          packageName: p.packageName,
          appLabel: p.appLabel,
        });
      }
    }
  }

  if (preview.expandShortForm) {
    const hasShortForm = nextRules.some(
      (r) => r.contentType === "short_form_video"
    );
    if (!hasShortForm) {
      nextRules.push({
        appLabel: null,
        packageName: null,
        surface: "shorts",
        contentType: "short_form_video",
        operator: null,
        value: null,
        unit: null,
        action: "BLOCK",
        description: "all short-form video",
      });
    }
  }

  dto.contentRules = dedupeContentRules(nextRules);
  dto.scopePackages = uniqueStrings(scopePkgs);
  const youtubeAppOnly = isYouTubeAppOnlyScopeKind(preview.scopeKind);
  const youtubeContent =
    preview.scopeKind === "youtube_content" || previewBrands.includes("youtube");
  dto.contentBrands = youtubeAppOnly ? [] : uniqueStrings(previewBrands);
  dto.surfaceScope = dto.contentBrands;
  dto.contentScope = dto.contentBrands;
  dto.scopeKind = youtubeAppOnly
    ? "youtube_app_only"
    : preview.scopeKind || (youtubeContent ? "youtube_content" : null);
  dto.hiddenInternalScope = uniqueStrings([
    ...(dto.hiddenInternalScope || []),
    ...scopePkgs,
  ]);

  const scopedAppRules = packages.map((p) => ({
    packageName: p.packageName,
    label: p.appLabel || knownAppLabel(p.packageName),
    behavior: "ALLOW",
  }));
  dto.suggestedAppRules = dedupeSuggestedApps([
    ...(dto.suggestedAppRules || []).filter(
      (r) => r && pkgSet.has(r.packageName)
    ),
    ...scopedAppRules,
  ]);

  if (dto.internalPolicy && typeof dto.internalPolicy === "object") {
    dto.internalPolicy = {
      ...dto.internalPolicy,
      contentRules: dto.contentRules,
      appRules: dto.suggestedAppRules,
      surfaceRules: (dto.contentRules || []).map((r) => ({
        surface: r.surface,
        action: r.action,
        note: r.description || "",
        packageName: r.packageName || null,
      })),
    };
  }

  const appliesTo =
    preview.appliesTo ||
    (preview.scopeKind === "youtube_only" ||
    preview.scopeKind === "youtube_app_only"
      ? "YouTube only"
      : preview.scopeKind === "youtube_content"
        ? "YouTube videos anywhere — app, browser pages, and other YouTube clients"
      : preview.scopeKind === "chrome_browser"
        ? "Chrome / browser video"
        : preview.scopeKind === "all_video_apps"
          ? "Major video apps and browser video"
          : preview.expandShortForm
            ? "Short-form video apps and browser short videos"
            : null);

  if (appliesTo && dto.userFacingConfirmation) {
    const cleanApplies = sanitizeUserFacingCopy(appliesTo, promiseText);
    dto.userFacingConfirmation = {
      ...dto.userFacingConfirmation,
      appliesTo: cleanApplies,
      appliesToText: cleanApplies,
      hiddenInternalScope: [],
    };
  }

  // Keep conditional / preview applies-to lines honest after rematerialize.
  if (appliesTo) {
    const cleanApplies = sanitizeUserFacingCopy(appliesTo, promiseText);
    dto.conditionalSummaries = uniqueStrings([
      ...(dto.conditionalSummaries || []).filter(
        (s) => !/^applies to:/i.test(String(s || ""))
      ),
      `Applies to: ${cleanApplies}`,
    ]);
  }

  dto.optionRematerialized = true;
  dto.optionScopeKind = preview.scopeKind || null;
  return true;
}

const isShortFormSurfaceRule = (r) =>
  Boolean(r) &&
  r.contentType === "short_form_video" &&
  !r.operator &&
  (r.value == null || r.value === "");

/** A rule that restricts something (a ban, a length / count limit, or a time budget). */
function isRestrictingRule(r) {
  return r.action === "BLOCK" || Boolean(r.operator) || r.contentType === "usage_quota";
}

/**
 * Typed scope is one WHERE per session: anything outside it is OutOfScope and never
 * reaches the content gates. A kept restriction the chosen scope cannot reach would be
 * listed on the card but not enforced.
 */
function appSurfaceChoiceCovers(rule, pkg, choice) {
  if (!isRestrictingRule(rule)) return true;
  if (rule.packageName !== pkg) return false;
  if (choice === "whole") return true;
  return rule.action === "BLOCK" && isShortFormSurfaceRule(rule);
}

/**
 * A verified app-surface pick changes only the chosen app's short-form-vs-whole-app
 * policy. Target-app short-form BLOCKs collapse into the chosen shape; target-app ALLOWs
 * are dropped only when they contradict it. Everything else (target-app adult / length
 * rules, quotas, global rules, other apps) is kept as compiled, and the preview is
 * rebuilt from the rules that remain.
 */
function materializeAppSurfaceChoice(dto, { entry, choice }, scopeKind) {
  const pkg = entry.packageName;
  const removed = [];
  const nextRules = [];
  for (const r of dto.contentRules || []) {
    if (!r) continue;
    if (r.packageName === pkg && isShortFormSurfaceRule(r)) {
      // BLOCK: replaced by the canonical rule / subsumed by the app BLOCK.
      // ALLOW: contradicts either choice.
      if (r.action !== "BLOCK") removed.push(r);
      continue;
    }
    if (choice === "whole" && r.packageName === pkg && r.action === "ALLOW") {
      removed.push(r);
      continue;
    }
    nextRules.push(r);
  }
  const targetAppRules = (dto.suggestedAppRules || []).filter((r) => r && r.packageName === pkg);
  const otherApps = (dto.suggestedAppRules || []).filter((r) => r && r.packageName !== pkg);
  let blockedLine;
  let allowedLine = null;
  if (choice === "short") {
    nextRules.push({
      appLabel: entry.label,
      packageName: pkg,
      surface: "shorts",
      contentType: "short_form_video",
      operator: null,
      value: null,
      unit: null,
      action: "BLOCK",
      description: entry.shortName,
    });
    dto.suggestedAppRules = [...otherApps, ...targetAppRules.filter((r) => r.behavior !== "BLOCK")];
    blockedLine = entry.shortName;
    allowedLine = entry.restOpen;
  } else {
    dto.suggestedAppRules = [...otherApps, { packageName: pkg, label: entry.label, behavior: "BLOCK" }];
    blockedLine = `All of ${entry.label}`;
  }
  dto.contentRules = dedupeContentRules(nextRules);
  const keptTarget = dto.contentRules.filter(
    (r) => r.packageName === pkg && !(choice === "short" && isShortFormSurfaceRule(r) && r.action === "BLOCK")
  );
  const keptTargetBlocks = keptTarget.filter(isRestrictingRule);
  const keptTargetAllows = keptTarget.filter((r) => !isRestrictingRule(r));
  const uncovered = dto.contentRules.filter((r) => !appSurfaceChoiceCovers(r, pkg, choice));
  if (keptTargetBlocks.length > 0) allowedLine = null;
  dto.scopePackages = [pkg];
  dto.contentBrands = [];
  dto.surfaceScope = [];
  dto.contentScope = [];
  dto.scopeKind = scopeKind;
  dto.hiddenInternalScope = uniqueStrings([...(dto.hiddenInternalScope || []), pkg]);
  if (dto.internalPolicy && typeof dto.internalPolicy === "object") {
    dto.internalPolicy = {
      ...dto.internalPolicy,
      contentRules: dto.contentRules,
      appRules: dto.suggestedAppRules,
      surfaceRules: dto.contentRules.map((r) => ({
        surface: r.surface,
        action: r.action,
        note: r.description || "",
        packageName: r.packageName || null,
      })),
    };
  }
  const norm = (s) => String(s || "").trim().toLowerCase();
  const ruleText = (r) => String(r.description || "").trim();
  const removedTexts = new Set(removed.map(ruleText).filter(Boolean).map(norm));
  const keptBlockTexts = keptTargetBlocks.map(ruleText).filter(Boolean);
  const keptAllowTexts = keptTargetAllows.map(ruleText).filter(Boolean);
  // Lines about the chosen app survive only when they describe a rule that is still kept;
  // lines describing a removed rule never survive, whichever app they name.
  const rebuild = (lines, keptTexts, extra) => {
    const keep = new Set(keptTexts.map(norm));
    const out = (Array.isArray(lines) ? lines : []).filter((l) => {
      const n = norm(l);
      if (removedTexts.has(n)) return false;
      return !mentionsLabel(l, entry.label) || keep.has(n);
    });
    for (const line of [...keptTexts, ...extra]) {
      if (line && !out.some((l) => norm(l) === norm(line))) out.push(line);
    }
    return out;
  };
  const ufcIn = dto.userFacingConfirmation || {};
  const blocked = rebuild(ufcIn.blocked || ufcIn.blockedBullets, keptBlockTexts, [blockedLine]);
  const allowed = rebuild(
    ufcIn.allowed || ufcIn.allowedBullets,
    keptAllowTexts,
    allowedLine ? [allowedLine] : []
  );
  const understood =
    choice === "short"
      ? keptTargetBlocks.length > 0
        ? `Block ${entry.shortName}. Your other ${entry.label} rules still apply.`
        : `Block ${entry.shortName}. ${entry.restOpen} stay open.`
      : `Block all of ${entry.label}.`;
  if (dto.userFacingConfirmation && typeof dto.userFacingConfirmation === "object") {
    dto.userFacingConfirmation = {
      ...dto.userFacingConfirmation,
      blocked,
      blockedBullets: blocked,
      allowed,
      allowedBullets: allowed,
      understood,
      understoodSummary: understood,
      appliesTo: choice === "short" ? entry.shortName : `All of ${entry.label}`,
      appliesToText: choice === "short" ? entry.shortName : `All of ${entry.label}`,
      hiddenInternalScope: [],
    };
  }
  dto.blockedSummaries = rebuild(dto.blockedSummaries, keptBlockTexts, [blockedLine]);
  dto.allowedSummaries = rebuild(dto.allowedSummaries, keptAllowTexts, allowedLine ? [allowedLine] : []);
  const keptPreviewTexts = new Set([...keptBlockTexts, ...keptAllowTexts].map(norm));
  dto.confirmationPreview = (dto.confirmationPreview || []).filter((l) => {
    const body = norm(String(l).replace(/^(still allowed|blocked|allowed):\s*/i, ""));
    if (removedTexts.has(body)) return false;
    return !mentionsLabel(l, entry.label) || keptPreviewTexts.has(body);
  });
  dto.appSurfaceChoice = {
    packageName: pkg,
    choice,
    uncoveredRules: uniqueStrings(uncovered.map((r) => ruleText(r) || r.contentType)),
  };
  dto.optionRematerialized = true;
  dto.optionScopeKind = scopeKind;
  return true;
}

function durationToSessionMinutes(duration, opts = {}) {
  const calendarDayFallback = Boolean(opts.calendarDayFallback);
  if (!duration || typeof duration !== "object") {
    if (calendarDayFallback) {
      return {
        minutes: CALENDAR_DAY_MINUTES,
        usedDefault: false,
        timeWindowKind: "calendar_day",
      };
    }
    return {
      minutes: DEFAULT_SESSION_MINUTES,
      usedDefault: true,
      timeWindowKind: "session_fixed_default",
    };
  }

  const kind = String(duration.kind || "none").toLowerCase();
  if (kind === "indefinite") {
    return {
      minutes: CALENDAR_DAY_MINUTES,
      usedDefault: false,
      timeWindowKind: "permanent",
    };
  }

  if (kind === "none") {
    if (calendarDayFallback) {
      return {
        minutes: CALENDAR_DAY_MINUTES,
        usedDefault: false,
        timeWindowKind: "calendar_day",
      };
    }
    return {
      minutes: DEFAULT_SESSION_MINUTES,
      usedDefault: true,
      timeWindowKind: "session_fixed_default",
    };
  }

  if (kind === "until_clock") {
    return {
      minutes: DEFAULT_SESSION_MINUTES,
      usedDefault: true,
      timeWindowKind: "until_clock",
    };
  }

  if (kind !== "fixed") {
    if (calendarDayFallback) {
      return {
        minutes: CALENDAR_DAY_MINUTES,
        usedDefault: false,
        timeWindowKind: "calendar_day",
      };
    }
    return {
      minutes: DEFAULT_SESSION_MINUTES,
      usedDefault: true,
      timeWindowKind: "session_fixed_default",
    };
  }

  const value = Number(duration.value);
  const unit = typeof duration.unit === "string" ? duration.unit.toLowerCase() : "";
  if (!Number.isFinite(value) || value <= 0) {
    if (calendarDayFallback) {
      return {
        minutes: CALENDAR_DAY_MINUTES,
        usedDefault: false,
        timeWindowKind: "calendar_day",
      };
    }
    return {
      minutes: DEFAULT_SESSION_MINUTES,
      usedDefault: true,
      timeWindowKind: "session_fixed_default",
    };
  }

  let minutes;
  let timeWindowKind = "session_fixed";
  switch (unit) {
    case "minutes":
      minutes = value;
      break;
    case "hours":
      minutes = value * 60;
      break;
    case "days":
      minutes = value * 24 * 60;
      timeWindowKind = value === 1 ? "calendar_day" : "multi_day";
      break;
    case "years":
      minutes = value * 365 * 24 * 60;
      timeWindowKind = "permanent";
      break;
    default:
      if (calendarDayFallback) {
        return {
          minutes: CALENDAR_DAY_MINUTES,
          usedDefault: false,
          timeWindowKind: "calendar_day",
        };
      }
      return {
        minutes: DEFAULT_SESSION_MINUTES,
        usedDefault: true,
        timeWindowKind: "session_fixed_default",
      };
  }

  return {
    minutes: Math.max(1, Math.round(minutes)),
    usedDefault: false,
    timeWindowKind,
  };
}

function mapAppRules(apps, behavior) {
  if (!Array.isArray(apps)) {
    return [];
  }
  const seen = new Set();
  const rules = [];
  for (const app of apps) {
    if (!app || typeof app !== "object") continue;
    const packageName =
      typeof app.packageName === "string" ? app.packageName.trim() : "";
    const label =
      typeof app.appLabel === "string" && app.appLabel.trim()
        ? app.appLabel.trim()
        : packageName;
    if (!packageName || seen.has(packageName)) continue;
    seen.add(packageName);
    rules.push({
      packageName,
      label,
      behavior,
      scope: typeof app.scope === "string" ? app.scope : "",
    });
  }
  return rules;
}

// blockedApps[].scope vocabulary. Anything outside these sets cannot be enforced as a
// narrower rule and must never silently become a whole-package BLOCK.
const WHOLE_APP_SCOPES = new Set([
  "", "all", "app", "whole", "whole_app", "whole app", "entire", "entire_app",
  "entire app", "full", "full_app", "any", "everything",
]);
const SHORT_FORM_APP_SCOPES = new Set([
  "shorts", "short", "reels", "reel", "short_form", "short-form", "short form",
  "short_form_video", "short-form video", "short videos", "short video",
  "short_videos", "short_video", "spotlight",
]);
// Apps that also carry non-short-form surfaces (home, long video, DMs, browsing).
const MIXED_SURFACE_PACKAGES = new Set([
  "com.google.android.youtube",
  "com.instagram.android",
  "com.facebook.katana",
  "com.snapchat.android",
  "com.android.chrome",
  "org.schabi.newpipe",
]);
const NARROW_SURFACE_SCOPE_KINDS = new Set([
  "APP_SHORT_FORM_ONLY",
  "SHORT_FORM_ANY_HOST",
  "SOCIAL_FEED_AND_REELS_ONLY",
]);

// Free-text scopes ("Shorts player only; not the whole app") are short-form only when they
// name a short-form surface and nothing else: any whole-app claim, exclusion ("except",
// "but") or other surface keeps them unknown so Start stays closed.
const SHORT_FORM_SCOPE_WORD = /\b(shorts?|reels?|short[\s_-]*(?:form|videos?|clips?)|spotlight)\b/;
const NEGATED_WHOLE_APP_SCOPE = /\b(?:not|no|never|without|rather than|instead of)\s+(?:the\s+|a\s+)?(?:whole|entire|full|all of)(?:\s+the)?\s+app\b/g;
const WHOLE_APP_SCOPE_CLAIM = /\b(whole|entire|full|all|everything|every)\b/;
const EXCLUSION_SCOPE_WORD = /\b(except|but|besides|other than|apart from|excluding|outside)\b/;
const OTHER_SURFACE_SCOPE_WORD = /\b(feed|home|comments?|messages?|dms?|chats?|stor(?:y|ies)|search|live|long|explore|brows(?:e|ing)|music|subscriptions?|posts?|calls?)\b/;

function classifyAppScope(raw) {
  const s = String(raw == null ? "" : raw).trim().toLowerCase().replace(/\s+/g, " ");
  if (WHOLE_APP_SCOPES.has(s)) return "whole_app";
  if (SHORT_FORM_APP_SCOPES.has(s)) return "short_form";
  if (!SHORT_FORM_SCOPE_WORD.test(s)) return "unknown";
  const rest = s.replace(NEGATED_WHOLE_APP_SCOPE, " ");
  if (
    WHOLE_APP_SCOPE_CLAIM.test(rest) ||
    EXCLUSION_SCOPE_WORD.test(rest) ||
    OTHER_SURFACE_SCOPE_WORD.test(rest)
  ) {
    return "unknown";
  }
  return "short_form";
}

function shortFormSurfaceLabel(packageName, label) {
  if (packageName === "com.google.android.youtube") return "YouTube Shorts";
  if (packageName === "com.instagram.android") return "Instagram Reels";
  if (packageName === "com.facebook.katana") return "Facebook Reels";
  return `${label || knownAppLabel(packageName)} short videos`;
}

/**
 * blockedApps with a surface scope ("YouTube, scope: shorts") become a structured
 * short-form content BLOCK for that package; only unscoped / whole-app entries stay
 * package-wide BLOCKs. Unknown scopes are reported so Start can stay closed.
 */
function splitScopedAppBlocks(blockedApps) {
  const wholeApp = [];
  const surfaceRules = [];
  const surfaceBlocks = [];
  const unrepresentable = [];
  for (const rule of blockedApps) {
    const kind = classifyAppScope(rule.scope);
    if (kind === "whole_app") {
      wholeApp.push(rule);
    } else if (kind === "short_form") {
      const surfaceLabel = shortFormSurfaceLabel(rule.packageName, rule.label);
      surfaceBlocks.push({ packageName: rule.packageName, label: rule.label, surfaceLabel });
      surfaceRules.push({
        appLabel: rule.label,
        packageName: rule.packageName,
        surface: "shorts",
        contentType: "short_form_video",
        operator: null,
        value: null,
        unit: null,
        action: "BLOCK",
        description: surfaceLabel,
      });
    } else {
      unrepresentable.push(rule);
    }
  }
  return { wholeApp, surfaceRules, surfaceBlocks, unrepresentable };
}

function isShortFormSurfaceBlock(rule) {
  return (
    rule &&
    rule.action === "BLOCK" &&
    rule.contentType === "short_form_video" &&
    !rule.operator &&
    (rule.value == null || rule.value === "") &&
    typeof rule.packageName === "string" &&
    rule.packageName !== ""
  );
}

function escapeRegExp(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** "YouTube" / "all of YouTube" / "YouTube app" — a bullet that claims the whole app. */
function isBareAppBullet(line, label) {
  const re = new RegExp(`^(?:all\\s+of\\s+|the\\s+)?${escapeRegExp(label)}(?:\\s+app)?\\.?$`, "i");
  return re.test(String(line || "").trim());
}

function mentionsShortFormWord(line) {
  return /\b(shorts?|reels?|short[\s-]*(?:form|videos?))\b/i.test(String(line || ""));
}

function mentionsLabel(line, label) {
  return new RegExp(`\\b${escapeRegExp(label)}\\b`, "i").test(String(line || ""));
}

/**
 * Keep the confirmation card honest about what Start will save: a surface-scoped block
 * is shown as "YouTube Shorts", never as all of YouTube; a whole-app block never sits
 * next to an "allowed" line for the same app.
 */
function reconcilePreviewWithEffectivePolicy(dto, scopedSplit) {
  const surfaceBlocks = scopedSplit.surfaceBlocks.filter(
    (s) =>
      !(dto.suggestedAppRules || []).some(
        (r) => r.packageName === s.packageName && r.behavior === "BLOCK"
      )
  );
  const wholeBlockLabels = (dto.suggestedAppRules || [])
    .filter((r) => r.behavior === "BLOCK" && r.label)
    .map((r) => r.label);

  const fixBlocked = (lines) => {
    let out = (lines || []).filter(
      (line) => !surfaceBlocks.some((s) => isBareAppBullet(line, s.label))
    );
    for (const s of surfaceBlocks) {
      if (!out.some((line) => mentionsLabel(line, s.label) && mentionsShortFormWord(line))) {
        out = [...out, s.surfaceLabel];
      }
    }
    return out;
  };
  const fixAllowed = (lines) =>
    (lines || []).filter((line) => !wholeBlockLabels.some((label) => mentionsLabel(line, label)));

  const ufc = dto.userFacingConfirmation;
  if (ufc && typeof ufc === "object") {
    const blocked = fixBlocked(ufc.blocked || ufc.blockedBullets);
    const allowed = fixAllowed(ufc.allowed || ufc.allowedBullets);
    ufc.blocked = blocked;
    ufc.blockedBullets = blocked;
    ufc.allowed = allowed;
    ufc.allowedBullets = allowed;
    const understood = ufc.understood || ufc.understoodSummary || "";
    const misleading = surfaceBlocks.some(
      (s) => mentionsLabel(understood, s.label) && !mentionsShortFormWord(understood)
    );
    if (misleading) {
      const labels = surfaceBlocks.map((s) => s.surfaceLabel).join(", ");
      const apps = surfaceBlocks.map((s) => s.label).join(", ");
      ufc.understood = `Block ${labels}. The rest of ${apps} stays available.`;
      ufc.understoodSummary = ufc.understood;
    }
  }
  dto.blockedSummaries = fixBlocked(dto.blockedSummaries);
  dto.allowedSummaries = fixAllowed(dto.allowedSummaries);
  dto.confirmationPreview = (dto.confirmationPreview || []).filter((line) => {
    const body = String(line).replace(/^(blocked|allowed):\s*/i, "");
    if (/^blocked:/i.test(line)) {
      return !surfaceBlocks.some((s) => isBareAppBullet(body, s.label));
    }
    if (/^allowed:|^still allowed:/i.test(line)) {
      return !wholeBlockLabels.some((label) => mentionsLabel(body, label));
    }
    return true;
  });
}

/**
 * Final gate: scope (WHERE) and app/content actions (WHAT) must agree. Only ever closes
 * Start; never reopens it and never rewrites an action into a weaker one.
 */
const APP_SURFACE_OFFERABLE_REASONS = new Set([
  "unrepresentable_app_scope",
  "whole_app_block_vs_narrow_scope",
  "whole_app_block_vs_content_allow",
  "messages_kept_but_whole_app_blocked",
]);

function serverClarificationFamilyActive(promiseText) {
  return Boolean(
    inferGoogleVideoClarification(promiseText) ||
      inferShortFormScopeClarification(promiseText) ||
      needsCompoundIntentClarification(promiseText)
  );
}

/**
 * Final gate: scope (WHERE) and app/content actions (WHAT) must agree. Only ever closes
 * Start; never reopens it and never rewrites an action into a weaker one. When the
 * contradiction is "short-form player vs whole app" for a family app, the only choices
 * offered are the server-built ones re-derived from the promise text (v1 contract).
 */
function applyScopedAppRuleGates(dto, scopedSplit, ctx = {}) {
  const promiseText = String(ctx.promiseText || "");
  const chosenPkg =
    dto.appSurfaceChoice && dto.optionVerification !== "unrecognized_fail_closed"
      ? dto.appSurfaceChoice.packageName
      : null;
  const reasons = [];
  const subjects = [];
  const add = (reason, subject) => {
    reasons.push(reason);
    subjects.push(subject);
  };
  const wholeBlocks = (dto.suggestedAppRules || []).filter((r) => r.behavior === "BLOCK");
  const scopeKind = dto.scope && typeof dto.scope === "object" ? dto.scope.kind : null;
  const unrepresentable = scopedSplit.unrepresentable.filter((r) => r.packageName !== chosenPkg);
  if (unrepresentable.length > 0) add("unrepresentable_app_scope", unrepresentable[0]);
  const broadVsNarrow = wholeBlocks.find(
    (r) => MIXED_SURFACE_PACKAGES.has(r.packageName) && NARROW_SURFACE_SCOPE_KINDS.has(scopeKind)
  );
  if (broadVsNarrow) add("whole_app_block_vs_narrow_scope", broadVsNarrow);
  const blockVsAllow = wholeBlocks.find((r) =>
    (dto.contentRules || []).some((c) => c.action === "ALLOW" && c.packageName === r.packageName)
  );
  if (blockVsAllow) add("whole_app_block_vs_content_allow", blockVsAllow);
  // Feed and message screens are not reliably distinguishable from the rest of these
  // apps, so a promise about them alone can be neither narrowed nor widened.
  for (const app of mentionedMixedSocialApps(promiseText)) {
    if (app.packageName === chosenPkg) continue;
    const whole = wholeBlocks.find((r) => r.packageName === app.packageName);
    const subject = whole || { packageName: app.packageName, label: app.label };
    if (surfaceIsBlockTarget(promiseText, MESSAGE_SURFACE_RE)) {
      add("messages_surface_unsupported", subject);
    } else if (surfaceIsBlockTarget(promiseText, FEED_SURFACE_RE)) {
      add("feed_surface_unsupported", subject);
    } else if (whole && scopeKind !== "SOCIAL_MESSAGING_ONLY" && surfaceIsKeptOpen(promiseText, MESSAGE_SURFACE_RE)) {
      add("messages_kept_but_whole_app_blocked", subject);
    }
  }
  if (scopeKind === "SOCIAL_FEED_AND_REELS_ONLY" && !reasons.includes("feed_surface_unsupported")) {
    const pkg = ((dto.scope.include || [])[0] || {}).packages?.[0] || null;
    add("feed_surface_unsupported", { packageName: pkg, label: pkg ? knownAppLabel(pkg) : null });
  }
  const uncovered = chosenPkg ? dto.appSurfaceChoice.uncoveredRules || [] : [];
  if (uncovered.length > 0) {
    add("app_surface_choice_misses_rules", { packageName: chosenPkg, label: knownAppLabel(chosenPkg) });
  }
  if (reasons.length === 0) return dto;
  const subject = subjects[0];
  const label = (subject && (subject.label || knownAppLabel(subject.packageName))) || "this app";
  dto.policyConflict = reasons[0];
  dto.policyConflictReasons = reasons;
  dto.clarificationRequired = true;
  dto.canStartCommitment = false;
  dto.ambiguityLevel = "high";

  const family =
    ctx.contract === "v1" && !ctx.selectedId ? inferAppSurfaceClarification(promiseText) : null;
  const offer =
    family &&
    reasons.every((r) => APP_SURFACE_OFFERABLE_REASONS.has(r)) &&
    subjects.every((s) => s && s.packageName === family.entry.packageName);
  if (offer) {
    dto.clarificationOptions = displayedBuilderOptions(family.options, promiseText).map((opt) => {
      const { scope } = optionTypedScope(opt, promiseText, true);
      return { ...opt, scope, label: scopeOptionLabel(scope) || opt.label };
    });
    dto.clarificationQuestion = sanitizeUserFacingCopy(family.question, promiseText);
    dto.cautionMessages = uniqueStrings([
      ...(dto.cautionMessages || []),
      reasons[0] === "unrepresentable_app_scope"
        ? `I couldn't tell which part of ${label} to block. Pick a choice below before Start.`
        : `Your promise both limits and fully blocks ${label}. Pick a choice below before Start.`,
    ]);
    return dto;
  }
  if (reasons.includes("app_surface_choice_misses_rules")) {
    const picked =
      dto.appSurfaceChoice.choice === "short"
        ? `Only ${(APP_SURFACE_FAMILY.find((e) => e.packageName === chosenPkg) || {}).shortName || label}`
        : `All of ${label}`;
    dto.clarificationOptions = [];
    dto.clarificationQuestion = "One promise can't enforce all of this yet. Edit the promise to split it.";
    dto.cautionMessages = uniqueStrings([
      ...(dto.cautionMessages || []),
      `"${picked}" can't also enforce: ${uncovered.join(", ")}. Make a separate promise for those, or edit this one.`,
    ]);
    return dto;
  }
  // Model-authored options cannot resolve this contradiction; never leave a fake choice.
  if (!ctx.selectedId && !serverClarificationFamilyActive(promiseText)) {
    dto.clarificationOptions = [];
    dto.clarificationQuestion = `What should Strlix block in ${label}? Edit the promise to say.`;
  } else if (!dto.clarificationQuestion) {
    dto.clarificationQuestion = `What should Strlix block in ${label}? Edit the promise to say.`;
  }
  dto.cautionMessages = uniqueStrings([
    ...(dto.cautionMessages || []),
    appScopeGuidance(promiseText, label, reasons[0], subject && subject.packageName),
  ]);
  return dto;
}

function contentTypeToSurface(type) {
  switch (type) {
    case "short_form_video":
      return "shorts";
    case "long_form_video":
      return "video";
    case "social_dm":
      return "messages";
    case "social_feed":
      return "feed";
    case "adult_sexual":
      return "adult";
    case "study":
      return "study";
    case "gaming":
      return "gaming";
    case "entertainment":
      return "entertainment";
    case "install":
      return "install";
    case "calls":
      return "calls";
    default:
      return "any";
  }
}

function summarizeContent(item, action) {
  const description =
    typeof item.description === "string" && item.description.trim()
      ? item.description.trim()
      : item.type || "content";
  // Never append raw package IDs to user-facing summaries.
  return `${description} → ${action}`;
}

/**
 * Parse per-video / media length thresholds from free-text descriptions.
 * Clock B — never session duration, never usage quota.
 */
function parseMediaLengthThreshold(text) {
  if (typeof text !== "string" || !text.trim()) return null;
  const lower = text.toLowerCase();

  const patterns = [
    {
      re: /\b(?:greater than|more than|longer than|over|above|gt)\s*(\d+)\s*(min|mins|minutes?|hrs?|hours?)\b/,
      operator: "gt",
    },
    {
      re: /\b(?:at least|no less than|gte|≥)\s*(\d+)\s*(min|mins|minutes?|hrs?|hours?)\b/,
      operator: "gte",
    },
    {
      re: /\b(?:less than|under|below|shorter than|lt)\s*(\d+)\s*(min|mins|minutes?|hrs?|hours?)\b/,
      operator: "lt",
    },
    {
      re: /\b(?:up to|at most|no more than|max(?:imum)?|lte|≤)\s*(\d+)\s*(min|mins|minutes?|hrs?|hours?)\b/,
      operator: "lte",
    },
    {
      re: /\b(?:length\s+is\s+(?:greater|more|longer)\s+than)\s*(\d+)\s*(min|mins|minutes?|hrs?|hours?)\b/,
      operator: "gt",
    },
  ];

  for (const { re, operator } of patterns) {
    const match = lower.match(re);
    if (!match) continue;
    const amount = Number(match[1]);
    if (!Number.isFinite(amount) || amount <= 0) continue;
    const unitRaw = match[2];
    const minutes = unitRaw.startsWith("h") ? amount * 60 : amount;
    return {
      operator,
      value: Math.round(minutes),
      unit: "minutes",
    };
  }
  return null;
}

function looksLikeMediaLengthThreshold(text) {
  if (typeof text !== "string") return false;
  const lower = text.toLowerCase();
  const hasVideo =
    /\b(?:video|videos|youtube|yt|lecture|lectures|episode|episodes)\b/.test(
      lower
    );
  const hasCompare =
    /\b(?:greater than|more than|longer than|over|above|less than|under|shorter than|up to|whose\s+length|length\s+is)\b/.test(
      lower
    ) && /\d+\s*(?:min|mins|minutes?|hrs?|hours?)\b/.test(lower);
  return hasVideo && hasCompare;
}

/**
 * Clock C only. Require explicit usage/budget language — never match
 * "videos longer than N today" via a bare "today"/"quota" token.
 */
function looksLikeUsageBudget(text) {
  if (typeof text !== "string") return false;
  const lower = text.toLowerCase();
  return (
    (/\bentertainment\b/.test(lower) &&
      /\b(?:budget|quota|total|today|daily|per\s+day|this\s+session|max|maximum|only|limit)\b/.test(
        lower
      )) ||
    (/\b(?:budget|quota)\b/.test(lower) &&
      /\b(?:entertainment|watch(?:ing)?|screen\s*time|youtube)\b/.test(lower)) ||
    /\bonly\s+\d+\s*(?:min|mins|minutes?|hrs?|hours?)\s+(?:of\s+)?(?:entertainment|youtube|phone|screen)\b/.test(
      lower
    ) ||
    /\b(?:max|maximum|limit)\s+\d+\s*(?:min|mins|minutes?)\s+(?:of\s+)?(?:entertainment|youtube|watch(?:ing)?|screen\s*time)\b/.test(
      lower
    ) ||
    /\bwatch(?:ing)?\s+(?:entertainment\s+)?(?:for\s+)?(?:max|maximum|up to|only)?\s*\d+\s*(?:min|mins|minutes?)\b/.test(
      lower
    ) ||
    /\b\d+\s*(?:min|mins|minutes?)\s+(?:of\s+)?entertainment\b/.test(lower) ||
    /\btotal\s+(?:of\s+)?\d+\s*(?:min|mins|minutes?)\b/.test(lower)
  );
}

/** All per-video length threshold minutes mentioned in text (clock B). */
function extractMediaLengthThresholdMinutes(text) {
  if (typeof text !== "string" || !text.trim()) return [];
  const lower = text.toLowerCase();
  const values = new Set();
  const patterns = [
    /\b(?:greater than|more than|longer than|over|above)\s*(\d+)\s*(min|mins|minutes?|hrs?|hours?)\b/g,
    /\b(?:less than|under|below|shorter than)\s*(\d+)\s*(min|mins|minutes?|hrs?|hours?)\b/g,
    /\b(?:up to|at most|no more than|max(?:imum)?)\s*(\d+)\s*(min|mins|minutes?|hrs?|hours?)\b/g,
    /\b(?:whose\s+)?length\s+is\s+(?:greater|more|longer)\s+than\s*(\d+)\s*(min|mins|minutes?|hrs?|hours?)\b/g,
    /\bvideo(?:s)?\s+(?:length\s+)?(?:greater than|more than|longer than|over)\s*(\d+)\s*(min|mins|minutes?|hrs?|hours?)\b/g,
  ];
  for (const re of patterns) {
    let match;
    while ((match = re.exec(lower)) !== null) {
      const amount = Number(match[1]);
      if (!Number.isFinite(amount) || amount <= 0) continue;
      const unitRaw = match[2];
      values.add(Math.round(unitRaw.startsWith("h") ? amount * 60 : amount));
    }
  }
  return [...values];
}

/**
 * Drop entertainment_minutes when it is really a mis-encoded video-length gate.
 */
function isMisencodedMediaLengthQuota(rawText, limit, contentDescriptions = []) {
  if (!Number.isFinite(limit)) return false;
  if (looksLikeUsageBudget(rawText)) return false;

  const rounded = Math.round(limit);
  const fromRaw = extractMediaLengthThresholdMinutes(rawText);
  if (fromRaw.includes(rounded)) return true;

  if (looksLikeMediaLengthThreshold(rawText)) {
    const parsed = parseMediaLengthThreshold(rawText);
    if (parsed && parsed.value === rounded) return true;
    // Same number appears as a bare minutes token next to video/length language.
    if (
      fromRaw.length === 0 &&
      new RegExp(
        `\\b${rounded}\\s*(?:min|mins|minutes?)\\b`
      ).test(String(rawText).toLowerCase())
    ) {
      return true;
    }
  }

  for (const description of contentDescriptions) {
    const parsed = parseMediaLengthThreshold(description);
    if (parsed && parsed.value === rounded) return true;
    if (extractMediaLengthThresholdMinutes(description).includes(rounded)) {
      return true;
    }
  }

  return false;
}

function mapContentItems(items, action, packageLabels = {}) {
  if (!Array.isArray(items)) {
    return { rules: [], summaries: [] };
  }
  const rules = [];
  const summaries = [];
  for (const item of items) {
    if (!item || typeof item !== "object") continue;
    const type =
      typeof item.type === "string" && item.type.trim()
        ? item.type.trim()
        : "other";
    const description =
      typeof item.description === "string" ? item.description.trim() : "";
    const apps = Array.isArray(item.apps)
      ? item.apps.filter((a) => typeof a === "string")
      : [];
    const packageName = apps[0] || null;
    const appLabel = packageName
      ? packageLabels[packageName] || knownAppLabel(packageName)
      : null;
    const threshold = parseMediaLengthThreshold(description);

    rules.push({
      appLabel,
      packageName,
      surface: contentTypeToSurface(type),
      contentType: type,
      operator: threshold ? threshold.operator : null,
      value: threshold ? threshold.value : null,
      unit: threshold ? threshold.unit : null,
      action,
      description,
    });
    summaries.push(summarizeContent({ ...item, type, description, apps }, action));
  }
  return { rules, summaries };
}

function knownAppLabel(packageName) {
  const known = {
    "com.google.android.youtube": "YouTube",
    "org.schabi.newpipe": "NewPipe",
    "com.instagram.android": "Instagram",
    "com.facebook.katana": "Facebook",
    "com.whatsapp": "WhatsApp",
    "com.android.chrome": "Chrome",
    "org.telegram.messenger": "Telegram",
    "com.zhiliaoapp.musically": "TikTok",
    "com.twitter.android": "X",
    "com.netflix.mediaclient": "Netflix",
    "com.reddit.frontpage": "Reddit",
    "com.discord": "Discord",
    "com.spotify.music": "Spotify",
    "com.snapchat.android": "Snapchat",
    "com.android.vending": "Play Store",
  };
  return known[packageName] || packageName;
}

function buildPackageLabelMap(...appLists) {
  const map = {};
  for (const list of appLists) {
    if (!Array.isArray(list)) continue;
    for (const app of list) {
      if (!app || typeof app !== "object") continue;
      const packageName =
        typeof app.packageName === "string" ? app.packageName.trim() : "";
      const label =
        typeof app.appLabel === "string" && app.appLabel.trim()
          ? app.appLabel.trim()
          : knownAppLabel(packageName);
      if (packageName) map[packageName] = label;
    }
  }
  return map;
}

const LENGTH_COMPARATOR_RE =
  /\b(at\s+least|minimum\s+of|no\s+shorter\s+than|shorter than|less than|under|below|length\s+is\s+(?:shorter|less)\s+than|greater than|more than|over|longer than|above|length\s+is\s+(?:greater|more|longer)\s+than)\s*(\d+)\s*(min|mins|minutes?|hrs?|hours?)\b/g;
// Group 1 = block cue, group 2 = allow cue. Block phrases first so "do not allow" beats "allow".
const LENGTH_POLARITY_CUE_RE =
  /\b(do\s+not\s+allow|don'?t\s+allow|not\s+allow|never\s+allow|do\s+not\s+let\s+me|don'?t\s+let\s+me|do\s+not\s+watch|don'?t\s+watch|block|blocks|ban|stop|forbid|avoid|except|no)\b|\b(allow\s+only|only\s+allow|allow|only|just|let\s+me)\b/g;

function lengthToMinutes(amountRaw, unitRaw) {
  const amount = Number(amountRaw);
  if (!Number.isFinite(amount) || amount <= 0) return null;
  return Math.round(String(unitRaw).startsWith("h") ? amount * 60 : amount);
}

function lengthPolarity(text, start, end) {
  const windowStart = Math.max(
    0,
    start - 80,
    text.lastIndexOf(".", start - 1) + 1,
    text.lastIndexOf(";", start - 1) + 1
  );
  const before = text.slice(windowStart, start);
  let last = null;
  let m;
  const re = new RegExp(LENGTH_POLARITY_CUE_RE.source, "g");
  while ((m = re.exec(before)) !== null) last = m;
  if (last) return last[1] ? "block" : "allow";
  const after = text.slice(end, end + 40);
  if (/^\s*(?:videos?\s+)?(?:are|is|will\s+be)?\s*(?:not\s+allowed|blocked|banned|forbidden)\b/.test(after)) {
    return "block";
  }
  if (/^\s*(?:videos?\s+)?(?:are|is|will\s+be)?\s*(?:allowed|ok|fine)\b/.test(after)) {
    return "allow";
  }
  return null;
}

/**
 * Polarity-aware per-video length intent (clock B). Mirrors Android
 * PromiseIntentRules.mediaLengthIntent:
 *   "do not allow videos shorter than 30" → minBlock 30
 *   "allow only videos shorter than 30"   → maxBlock 30
 *   "allow only videos longer than 30"    → minBlock 30
 *   "block videos longer than 30"         → maxBlock 30
 * Both equal only for "exactly N minutes".
 */
function inferMediaLengthIntent(rawText) {
  const text = String(rawText || "").toLowerCase();
  const exact = text.match(/\bexactly\s+(\d+)\s*(min|mins|minutes?|hrs?|hours?)\b/);
  if (exact) {
    const n = lengthToMinutes(exact[1], exact[2]);
    if (n != null) return { minBlock: n, maxBlock: n, exact: true };
  }
  let minBlock = null;
  let maxBlock = null;
  const re = new RegExp(LENGTH_COMPARATOR_RE.source, "g");
  let m;
  while ((m = re.exec(text)) !== null) {
    const minutes = lengthToMinutes(m[2], m[3]);
    if (minutes == null) continue;
    const comparator = m[1];
    const polarity = lengthPolarity(text, m.index, m.index + m[0].length);
    let blocksShorter;
    if (/^(?:at\s+least|minimum\s+of|no\s+shorter\s+than)/.test(comparator)) {
      blocksShorter = true;
    } else if (/(?:shorter|less|under|below)/.test(comparator)) {
      blocksShorter = polarity !== "allow";
    } else {
      blocksShorter = polarity === "allow";
    }
    if (blocksShorter) {
      if (minBlock == null) minBlock = minutes;
    } else if (maxBlock == null) {
      maxBlock = minutes;
    }
  }
  return { minBlock, maxBlock, exact: false };
}

function isVideoLengthRule(r) {
  if (!r || r.unit !== "minutes") return false;
  const val = Number(r.value);
  if (!Number.isFinite(val) || val <= 0) return false;
  const type = String(r.contentType || "").toLowerCase();
  const surface = String(r.surface || "").toLowerCase();
  if (type === "usage_quota") return false;
  return type.includes("video") || type === "entertainment" || surface === "video" || surface === "any";
}

/** Which side a length rule blocks: "min" (block shorter) / "max" (block longer) / null. */
function lengthRuleSide(r) {
  if (!isVideoLengthRule(r)) return null;
  const op = String(r.operator || "");
  const action = String(r.action || "").toUpperCase();
  if (action === "BLOCK" && (op === "lt" || op === "lte")) return "min";
  if (action === "BLOCK" && (op === "gt" || op === "gte")) return "max";
  if (action === "ALLOW" && (op === "gt" || op === "gte")) return "min";
  if (action === "ALLOW" && (op === "lt" || op === "lte")) return "max";
  return null;
}

function lengthBlockRule(side, minutes) {
  return {
    appLabel: null,
    packageName: null,
    surface: "video",
    contentType: "long_form_video",
    operator: side === "min" ? "lt" : "gt",
    value: minutes,
    unit: "minutes",
    action: "BLOCK",
    description:
      side === "min"
        ? `videos shorter than ${minutes} min`
        : `videos longer than ${minutes} min`,
  };
}

/**
 * Never ship min=N and max=N (or min >= max) unless the user said "exactly N".
 * When the text names one side only, drop length rules on the other side and
 * make sure the named side exists. Returns { rules, repaired }.
 */
function reconcileMediaLengthRules(contentRules, rawText) {
  const rules = Array.isArray(contentRules) ? contentRules : [];
  const intent = inferMediaLengthIntent(rawText);
  if (intent.exact) return { rules, repaired: false };
  let out = rules;
  let repaired = false;
  const single =
    intent.minBlock != null && intent.maxBlock == null
      ? { side: "min", minutes: intent.minBlock }
      : intent.maxBlock != null && intent.minBlock == null
        ? { side: "max", minutes: intent.maxBlock }
        : null;
  if (single) {
    const kept = out.filter((r) => {
      const side = lengthRuleSide(r);
      return side == null || side === single.side;
    });
    if (kept.length !== out.length) repaired = true;
    out = kept;
    if (!out.some((r) => lengthRuleSide(r) === single.side)) {
      out = [...out, lengthBlockRule(single.side, single.minutes)];
      repaired = true;
    }
    return { rules: out, repaired };
  }
  const mins = out
    .filter((r) => lengthRuleSide(r) === "min")
    .map((r) => Number(r.value));
  const maxes = out
    .filter((r) => lengthRuleSide(r) === "max")
    .map((r) => Number(r.value));
  if (mins.length && maxes.length && Math.max(...mins) >= Math.min(...maxes)) {
    out = out.filter((r) => lengthRuleSide(r) !== "max");
    repaired = true;
  }
  return { rules: out, repaired };
}

/**
 * Repair inverted min/max_item_minutes from the model using raw promise text,
 * with allow/block polarity (see inferMediaLengthIntent).
 */
function repairMediaLengthItemQuotas(quotas, rawText) {
  if (!Array.isArray(quotas)) return quotas;
  const intent = inferMediaLengthIntent(rawText);
  if (intent.exact) return quotas;
  const onlyMin = intent.minBlock != null && intent.maxBlock == null;
  const onlyMax = intent.maxBlock != null && intent.minBlock == null;
  return quotas.map((q) => {
    if (!q || typeof q !== "object") return q;
    const metric = String(q.metric || "");
    if (metric !== "max_item_minutes" && metric !== "min_item_minutes") return q;
    if (onlyMax && metric === "min_item_minutes") {
      return { ...q, metric: "max_item_minutes" };
    }
    if (onlyMin && metric === "max_item_minutes") {
      return { ...q, metric: "min_item_minutes" };
    }
    return q;
  });
}

function normalizeEmergencyExceptions(raw) {
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((item) => item && typeof item === "object")
    .map((item) => ({
      type:
        typeof item.type === "string" && item.type.trim()
          ? item.type.trim()
          : "other",
      detail:
        typeof item.detail === "string"
          ? item.detail.trim()
          : typeof item.description === "string"
            ? item.description.trim()
            : "",
    }));
}

const UNRECOGNIZED_CLARIFICATION_CHOICE =
  "That clarification choice was not recognized. Pick an option again.";

function isUnrecognizedClarificationChoice(text) {
  return /clarification choice was not recognized/i.test(String(text || ""));
}

function shouldShowUnrecognizedClarificationChoice(selectedId, options) {
  const id = typeof selectedId === "string" ? selectedId.trim() : "";
  if (!id) return false;
  const list = Array.isArray(options) ? options : [];
  if (list.length === 0) return false;
  return !list.some(
    (o) => String((o && o.id) || "").toUpperCase() === id.toUpperCase()
  );
}

function hasMinVideoLengthRule(dto) {
  const rules = Array.isArray(dto.contentRules) ? dto.contentRules : [];
  if (
    rules.some((r) => {
      const op = String((r && r.operator) || "");
      const action = String((r && r.action) || "").toUpperCase();
      const val = Number(r && r.value);
      if (!Number.isFinite(val) || val <= 0) return false;
      const type = String((r && r.contentType) || "").toLowerCase();
      const surface = String((r && r.surface) || "").toLowerCase();
      const videoish =
        type.includes("video") || surface === "video" || surface === "any";
      if (!videoish) return false;
      if (action === "BLOCK" && (op === "lt" || op === "lte")) return true;
      if (action === "ALLOW" && (op === "gt" || op === "gte")) return true;
      return false;
    })
  ) {
    return true;
  }
  const text = String((dto && dto.rawText) || "").toLowerCase();
  return (
    /(?:allow|only).{0,80}(?:videos?|youtube).{0,60}(?:longer|greater|more)\s+than\s+\d+/.test(
      text
    ) || /(?:shorter|less)\s+than\s+\d+/.test(text)
  );
}

function hasBlockedShorterVideos(dto) {
  const rules = Array.isArray(dto.contentRules) ? dto.contentRules : [];
  if (
    rules.some((r) => {
      const op = String((r && r.operator) || "");
      const action = String((r && r.action) || "").toUpperCase();
      return action === "BLOCK" && (op === "lt" || op === "lte");
    })
  ) {
    return true;
  }
  const understood =
    dto.userFacingConfirmation && dto.userFacingConfirmation.understood
      ? String(dto.userFacingConfirmation.understood)
      : "";
  const blob = `${dto.rawText || ""} ${understood}`.toLowerCase();
  if (/\bshorter\b/.test(blob) && /\bblock/.test(blob)) return true;
  return /\b(?:only\s+allow|allow\s+only|only\s+(?:videos?|youtube))\b/.test(
    String((dto && dto.rawText) || "")
  );
}

function isConcreteClockPolicy(dto) {
  const minutes = Number(dto && dto.sessionDurationMinutes);
  if (!Number.isFinite(minutes) || minutes <= 0) return false;
  return hasMinVideoLengthRule(dto) && hasBlockedShorterVideos(dto);
}

function stripFalseUnrecognizedWarnings(dto, selectedId) {
  if (!dto || typeof dto !== "object") return;
  // Reliability design 2026-09-25 §Fix 1: keep the warning when the option
  // codepath explicitly failed closed. Otherwise the user sees "Start" back on
  // with no clue why we changed our mind about their pick.
  if (dto.optionVerification === "unrecognized_fail_closed") return;
  if (
    shouldShowUnrecognizedClarificationChoice(selectedId, dto.clarificationOptions)
  ) {
    return;
  }
  const strip = (arr) =>
    (Array.isArray(arr) ? arr : []).filter(
      (s) => !isUnrecognizedClarificationChoice(s)
    );
  dto.cautionMessages = strip(dto.cautionMessages);
  dto.interpretationNotes = strip(dto.interpretationNotes);
  if (dto.userFacingConfirmation && typeof dto.userFacingConfirmation === "object") {
    dto.userFacingConfirmation.checkThis = strip(
      dto.userFacingConfirmation.checkThis
    );
  }
}

function clearGhostClarificationIfConcrete(dto) {
  if (!dto || typeof dto !== "object") return dto;
  // Concrete clocks say nothing about WHERE a v1 promise applies.
  if (dto.scopeContract === "v1" && !isEnforceableScope(dto.scope)) return dto;
  const options = Array.isArray(dto.clarificationOptions)
    ? dto.clarificationOptions
    : [];
  if (options.length > 0) return dto;
  if (!isConcreteClockPolicy(dto)) return dto;
  dto.clarificationRequired = false;
  dto.clarificationQuestion = null;
  if (dto.ambiguityLevel === "high") dto.ambiguityLevel = "low";
  if (!dto.requiresStrongerConfirmation) {
    dto.canStartCommitment = true;
  }
  if (dto.riskReview && typeof dto.riskReview === "object") {
    dto.riskReview.requiresHumanConfirmation = Boolean(
      dto.requiresStrongerConfirmation
    );
  }
  return dto;
}

/**
 * Single source of truth for Start gating after all repairs/aliases.
 * Fail-closed: clarify or stronger-confirm ⇒ never soft-start.
 */
function applyFailClosedStartGates(dto) {
  if (!dto || typeof dto !== "object") return dto;

  // Reliability design 2026-09-25 §Fix 1: honour unrecognized-fail-closed here
  // too. Otherwise the ghost-concrete clearer would resurrect canStart on the
  // exact bug scope-narrow path this fix set out to close.
  if (dto.optionVerification !== "unrecognized_fail_closed") {
    clearGhostClarificationIfConcrete(dto);
  }

  if (dto.ambiguityLevel === "high" && !isConcreteClockPolicy(dto)) {
    dto.clarificationRequired = true;
    if (!dto.clarificationQuestion) {
      dto.clarificationQuestion = "Which of these matches what you meant?";
    }
  }

  if (dto.clarificationRequired) {
    dto.canStartCommitment = false;
  }

  if (dto.requiresStrongerConfirmation) {
    dto.canStartCommitment = false;
    if (dto.riskReview && typeof dto.riskReview === "object") {
      dto.riskReview.requiresHumanConfirmation = true;
    }
  }

  // Model may set requiresHumanConfirmation without permanent/clarify — still soft-start ban.
  if (dto.riskReview && dto.riskReview.requiresHumanConfirmation) {
    dto.canStartCommitment = false;
    if (!dto.clarificationRequired && !dto.requiresStrongerConfirmation) {
      dto.requiresStrongerConfirmation = true;
    }
  }

  // Absolute invariant (CK-CLARIFY-START).
  if (dto.clarificationRequired) {
    dto.canStartCommitment = false;
  }
  return dto;
}

/** Exhaustive package-ID strip on every user-facing string (including nested options). */
function stripPackageLeaksFromDto(dto, promiseText) {
  if (!dto || typeof dto !== "object") return dto;
  const scrub = (s) =>
    typeof s === "string" ? sanitizeUserFacingCopy(s, promiseText) : s;

  dto.cleanedPromiseText = scrub(dto.cleanedPromiseText);
  dto.userIntentSummary = scrub(dto.userIntentSummary);
  dto.recommendedInterpretation = scrub(dto.recommendedInterpretation) || null;
  dto.clarificationQuestion = scrub(dto.clarificationQuestion) || null;
  dto.alternativeInterpretations = (dto.alternativeInterpretations || []).map(scrub);
  dto.allowedSummaries = (dto.allowedSummaries || []).map(scrub);
  dto.blockedSummaries = (dto.blockedSummaries || []).map(scrub);
  dto.conditionalSummaries = (dto.conditionalSummaries || []).map(scrub);
  dto.confirmationPreview = (dto.confirmationPreview || []).map(scrub);
  dto.interpretationNotes = (dto.interpretationNotes || []).map(scrub);
  dto.cautionMessages = (dto.cautionMessages || []).map(scrub);

  if (Array.isArray(dto.clarificationOptions)) {
    dto.clarificationOptions = dto.clarificationOptions.map((o) => ({
      ...o,
      label: scrub(o.label),
      description: scrub(o.description),
      policyPreview: scrub(o.policyPreview) || null,
      resultingPolicyPreview: scrub(o.resultingPolicyPreview) || null,
      // Keep structured rematerialize payload; scrub nested appliesTo only.
      internalPolicyPreview: o.internalPolicyPreview
        ? {
            ...o.internalPolicyPreview,
            appliesTo: scrub(o.internalPolicyPreview.appliesTo) || null,
          }
        : null,
    }));
  }

  if (dto.userFacingConfirmation && typeof dto.userFacingConfirmation === "object") {
    const u = dto.userFacingConfirmation;
    const allowed = (u.allowed || u.allowedBullets || []).map(scrub);
    const blocked = (u.blocked || u.blockedBullets || []).map(scrub);
    const checkThis = (u.checkThis || []).map(scrub);
    const safetyNotes = (u.safetyNotes || []).map(scrub);
    const understood = scrub(u.understood || u.understoodSummary || "");
    const time = scrub(u.time || u.timeWindowText || "");
    const appliesTo = scrub(u.appliesTo || u.appliesToText || "");
    dto.userFacingConfirmation = {
      ...u,
      understood,
      allowed,
      blocked,
      time,
      appliesTo,
      checkThis,
      safetyNotes,
      understoodSummary: understood,
      allowedBullets: allowed,
      blockedBullets: blocked,
      timeWindowText: time,
      appliesToText: appliesTo,
      hiddenInternalScope: [],
    };
  }
  return dto;
}

/**
 * Map quotas. Clock C (usage budget / counts) only.
 * Mis-encoded media-length (clock B) stuffed into entertainment_minutes is
 * repaired — never emitted as "Entertainment budget".
 *
 * Shorts/reels quotas use allow_first_n: first N matching plays ALLOW, then BLOCK.
 */
function mapQuotas(quotas, rawText = "", contentDescriptions = []) {
  const list = Array.isArray(quotas) ? quotas : [];
  const rules = [];
  const conditionals = [];
  const repairedLengthRules = [];
  let shortFormQuota = null;
  const repairedQuotas = repairMediaLengthItemQuotas(list, rawText);

  for (const quota of repairedQuotas) {
    if (!quota || typeof quota !== "object") continue;
    const metric = typeof quota.metric === "string" ? quota.metric : "";
    const limit = Number(quota.limit);
    const period = typeof quota.period === "string" ? quota.period : "session";
    if (!Number.isFinite(limit) || limit < 0 || !metric) continue;

    if (metric === "max_item_minutes" || metric === "min_item_minutes") {
      const operator = metric === "max_item_minutes" ? "gt" : "lt";
      repairedLengthRules.push({
        appLabel: null,
        packageName: null,
        surface: "video",
        contentType: "long_form_video",
        operator,
        value: Math.round(limit),
        unit: "minutes",
        action: "BLOCK",
        description:
          metric === "max_item_minutes"
            ? `videos longer than ${Math.round(limit)} min`
            : `videos shorter than ${Math.round(limit)} min`,
      });
      continue;
    }

    if (metric === "entertainment_minutes") {
      if (isMisencodedMediaLengthQuota(rawText, limit, contentDescriptions)) {
        repairedLengthRules.push({
          appLabel: null,
          packageName: null,
          surface: "video",
          contentType: "long_form_video",
          operator: "gt",
          value: Math.round(limit),
          unit: "minutes",
          action: "BLOCK",
          description: `videos longer than ${Math.round(limit)} min`,
        });
        continue;
      }
      conditionals.push(
        `Entertainment budget: ${Math.round(limit)} minutes per ${period}`
      );
      rules.push({
        appLabel: null,
        packageName: null,
        surface: "video",
        contentType: "usage_quota",
        operator: "lte",
        value: Math.round(limit),
        unit: "minutes",
        action: "ALLOW",
        description: `entertainment budget ≤ ${Math.round(limit)} min / ${period}`,
      });
    } else if (metric === "social_minutes") {
      conditionals.push(
        `Social budget: ${Math.round(limit)} minutes per ${period}`
      );
      rules.push({
        appLabel: null,
        packageName: null,
        surface: "feed",
        contentType: "usage_quota",
        operator: "lte",
        value: Math.round(limit),
        unit: "minutes",
        action: "ALLOW",
        description: `social budget ≤ ${Math.round(limit)} min / ${period}`,
      });
    } else if (metric === "shorts" || metric === "reels" || metric === "shots") {
      const n = Math.round(limit);
      shortFormQuota = { limit: n, period, metric };
      conditionals.push(
        `Allowed: first ${n} short-form videos ${period === "day" ? "today" : "per " + period} (then block)`
      );
      conditionals.push(
        `After limit: block short-form until ${period === "day" ? "tomorrow" : "period reset"}`
      );
      // Under-quota: ALLOW matching plays (not WARN, not immediate BLOCK).
      rules.push({
        appLabel: null,
        packageName: null,
        surface: "shorts",
        contentType: "short_form_video",
        operator: "lte",
        value: n,
        unit: "count",
        action: "ALLOW",
        description: `short-form ≤ ${n} / ${period} (${QUOTA_BOUNDARY})`,
      });
      // Over-quota companion signal for PolicyEngine: BLOCK when count exceeds N.
      rules.push({
        appLabel: null,
        packageName: null,
        surface: "shorts",
        contentType: "short_form_video",
        operator: "gt",
        value: n,
        unit: "count",
        action: "BLOCK",
        description: `short-form after ${n} / ${period}`,
      });
    }
  }
  if (!shortFormQuota) {
    const lifted = parseShortFormLimitFromText(rawText);
    if (lifted != null) {
      const period = /\b(today|daily|per day|aaj|kal)\b/i.test(String(rawText || ""))
        ? "day"
        : "session";
      const n = lifted;
      shortFormQuota = { limit: n, period, metric: "shorts" };
      conditionals.push(
        `Allowed: first ${n} short-form videos ${period === "day" ? "today" : "per " + period} (then block)`
      );
      conditionals.push(
        `After limit: block short-form until ${period === "day" ? "tomorrow" : "period reset"}`
      );
      rules.push({
        appLabel: null,
        packageName: null,
        surface: "shorts",
        contentType: "short_form_video",
        operator: "lte",
        value: n,
        unit: "count",
        action: "ALLOW",
        description: `short-form ≤ ${n} / ${period} (${QUOTA_BOUNDARY})`,
      });
      rules.push({
        appLabel: null,
        packageName: null,
        surface: "shorts",
        contentType: "short_form_video",
        operator: "gt",
        value: n,
        unit: "count",
        action: "BLOCK",
        description: `short-form after ${n} / ${period}`,
      });
    }
  }
  return { rules, conditionals, repairedLengthRules, shortFormQuota };
}

/**
 * Messy spoken quota: "upto 20 shots", "allow me 10 sorts".
 * Same law as Android ShortFormLanguage — model miss must not drop the count.
 */
function parseShortFormLimitFromText(rawText) {
  const text = String(rawText || "").toLowerCase();
  const noun = "shorts?|shots?|sorts?|reels?|short\\s+videos?";
  const prefixed = new RegExp(
    `\\b(?:at\\s+most|up\\s*to(?:\\s+max(?:imum)?)?|only(?:\\s+up\\s*to)?(?:\\s+max(?:imum)?)?|max(?:imum)?|allow|watch)\\s+(\\d+)\\s*(?:${noun})\\b`
  );
  const bare = new RegExp(`\\b(\\d+)\\s*(?:${noun})\\b`);
  const hit = prefixed.exec(text) || bare.exec(text);
  if (!hit) return null;
  const n = Number(hit[1]);
  return Number.isFinite(n) && n >= 1 && n <= 500 ? n : null;
}

function validateCompilerModelOutput(parsed) {
  if (!parsed || typeof parsed !== "object") {
    throw invalidModelError("Model output is not an object");
  }
  for (const field of REQUIRED_FIELDS) {
    if (!(field in parsed)) {
      throw invalidModelError(`Missing required field: ${field}`);
    }
  }
  if (!parsed.duration || typeof parsed.duration !== "object") {
    throw invalidModelError("duration must be an object");
  }
  if (typeof parsed.followUpQuestionRequired !== "boolean") {
    throw invalidModelError("followUpQuestionRequired must be boolean");
  }
  const confidence = Number(parsed.confidence);
  if (!Number.isFinite(confidence) || confidence < 0 || confidence > 1) {
    throw invalidModelError("confidence must be a number between 0 and 1");
  }
  const strictness = String(parsed.strictnessLevel || "").toUpperCase();
  if (!VALID_STRICTNESS.has(strictness)) {
    throw invalidModelError("invalid strictnessLevel");
  }
  return true;
}

function invalidModelError(message) {
  const err = new Error(message);
  err.statusCode = 502;
  return err;
}

/**
 * Normalize Azure/OpenAI promise-compiler JSON into an Android-friendly DTO.
 * Pure / unit-testable — no network.
 * Promise Semantics v1: time / scope / action / quota / guardrail / preview stay separated.
 *
 * `trusted` is server-local state produced by compilePromise (never request data):
 * `trusted.compoundSelection` is the verifyCompoundClauseSelection() result for a
 * compound A/B/C pick that passed BEFORE narrowing / the model call.
 */
function normalizeCompiledPromise(parsed, rawText, options = {}, trusted = {}) {
  validateCompilerModelOutput(parsed);

  const cautionMessages = [];
  const interpretationNotes = [];
  const promiseText = typeof rawText === "string" ? rawText : "";

  parsed = repairNormalPhoneExceptSafety(parsed, promiseText);

  const contentDescriptions = collectContentDescriptions(
    parsed.allowedContent,
    parsed.blockedContent
  );
  const quotaMapped = mapQuotas(parsed.quotas, promiseText, contentDescriptions);

  const calendarDayFallback = shouldUseCalendarDayWindow(
    parsed,
    promiseText,
    quotaMapped.shortFormQuota
  );

  const durationMapped = durationToSessionMinutes(parsed.duration, {
    calendarDayFallback,
  });
  if (durationMapped.usedDefault && !calendarDayFallback) {
    cautionMessages.push(
      "Session duration was unclear, so I defaulted to 60 minutes. Edit if wrong."
    );
  }
  if (calendarDayFallback && durationMapped.timeWindowKind === "calendar_day") {
    interpretationNotes.push(
      "Time window: calendar day / rest of today — not an invented 1-hour session."
    );
  }

  const strictness = String(parsed.strictnessLevel).toUpperCase();
  const allowedApps = mapAppRules(parsed.allowedApps, "ALLOW");
  const scopedSplit = splitScopedAppBlocks(mapAppRules(parsed.blockedApps, "BLOCK"));
  const blockedApps = scopedSplit.wholeApp;
  const packageLabels = buildPackageLabelMap(parsed.allowedApps, parsed.blockedApps);

  const allowedMapped = mapContentItems(parsed.allowedContent, "ALLOW", packageLabels);
  const blockedMapped = mapContentItems(parsed.blockedContent, "BLOCK", packageLabels);
  for (const s of scopedSplit.surfaceBlocks) {
    blockedMapped.summaries.push(s.surfaceLabel);
  }

  let contentRules = dedupeContentRules([
    ...allowedMapped.rules,
    ...blockedMapped.rules,
    ...scopedSplit.surfaceRules,
    ...quotaMapped.repairedLengthRules,
    ...quotaMapped.rules,
  ]);

  // If raw text clearly states a media-length gate but model omitted thresholds,
  // lift one BLOCK gt rule from the promise text.
  if (
    !contentRules.some((r) => r.operator && r.unit === "minutes") &&
    looksLikeMediaLengthThreshold(rawText) &&
    !looksLikeUsageBudget(rawText)
  ) {
    const intent = inferMediaLengthIntent(rawText);
    const side =
      intent.minBlock != null ? "min" : intent.maxBlock != null ? "max" : null;
    if (side) {
      const lifted = lengthBlockRule(
        side,
        side === "min" ? intent.minBlock : intent.maxBlock
      );
      contentRules = dedupeContentRules([...contentRules, lifted]);
      blockedMapped.summaries.push(`${lifted.description} → BLOCK`);
    }
  }

  contentRules = reconcileMediaLengthRules(contentRules, rawText).rules;

  const shortFormCategory = needsShortFormCategoryExpansion(
    promiseText,
    parsed,
    quotaMapped.shortFormQuota,
    contentRules
  );
  // Quota ≠ permanent shorts ban. Model often emits both; keep count rules only.
  if (quotaMapped.shortFormQuota) {
    contentRules = contentRules.filter(
      (r) =>
        !(
          r.contentType === "short_form_video" &&
          r.action === "BLOCK" &&
          (r.operator == null || r.operator === "") &&
          (r.value == null || r.value === "") &&
          (r.unit == null || r.unit === "")
        )
    );
    if (Array.isArray(parsed.activeGuardrails)) {
      parsed.activeGuardrails = parsed.activeGuardrails.filter(
        (g) => g !== "no_short_form_video"
      );
    }
  }
  let scopePackages = [];
  if (shortFormCategory) {
    scopePackages = SHORT_FORM_PLATFORM_PACKAGES.map((p) => p.packageName);
    interpretationNotes.push(
      "I interpreted 'shorts' as all short-form video, including reels-style apps. Change?"
    );
    // Ensure category content rule exists (quota already adds ALLOW/BLOCK count rules).
    if (
      !contentRules.some((r) => r.contentType === "short_form_video") &&
      !quotaMapped.shortFormQuota
    ) {
      contentRules = dedupeContentRules([
        ...contentRules,
        {
          appLabel: null,
          packageName: null,
          surface: "shorts",
          contentType: "short_form_video",
          operator: null,
          value: null,
          unit: null,
          action: "BLOCK",
          description: "all short-form video",
        },
      ]);
    }
  }

  // Adult always immediate BLOCK — never consume friendly short-form quota.
  const adultSafetyExplicit = explicitlyMentionsAdultSafety(promiseText);
  if (!adultSafetyExplicit) {
    contentRules = contentRules.filter((r) => r.contentType !== "adult_sexual");
    blockedMapped.summaries = blockedMapped.summaries.filter(
      (s) => !/adult|sexual|porn|nsfw|18\+/i.test(String(s))
    );
  }
  const hasAdult =
    adultSafetyExplicit &&
    ((Array.isArray(parsed.activeGuardrails) &&
      parsed.activeGuardrails.includes("no_adult_content")) ||
      contentRules.some((r) => r.contentType === "adult_sexual"));
  if (hasAdult) {
    if (!contentRules.some((r) => r.contentType === "adult_sexual" && r.action === "BLOCK")) {
      contentRules = dedupeContentRules([
        ...contentRules,
        {
          appLabel: null,
          packageName: null,
          surface: "adult",
          contentType: "adult_sexual",
          operator: null,
          value: null,
          unit: null,
          action: "BLOCK",
          description: "adult/sexual content",
        },
      ]);
    }
    blockedMapped.summaries.push("adult/sexual content → BLOCK always (no quota)");
  }

  let suggestedAppRules = dedupeSuggestedApps([...allowedApps, ...blockedApps]);
  if (shortFormCategory) {
    suggestedAppRules = dedupeSuggestedApps([
      ...suggestedAppRules,
      ...SHORT_FORM_PLATFORM_PACKAGES.map((p) => ({
        packageName: p.packageName,
        label: p.appLabel,
        behavior: "ALLOW",
      })),
    ]);
  }
  if (suggestedAppRules.length === 0) {
    suggestedAppRules = dedupeSuggestedApps(
      deriveAppRulesFromContent(contentRules, packageLabels)
    );
  }

  // Unknown video clones named in text → WARN, never silent allow-as-harmless.
  if (/\bnewpipe\b/i.test(promiseText) || shortFormCategory) {
    const hasNewPipe = suggestedAppRules.some(
      (r) => r.packageName === "org.schabi.newpipe"
    );
    if (!hasNewPipe && (/\bnewpipe\b/i.test(promiseText) || shortFormCategory)) {
      suggestedAppRules = dedupeSuggestedApps([
        ...suggestedAppRules,
        {
          packageName: "org.schabi.newpipe",
          label: "NewPipe",
          behavior: "ALLOW",
        },
      ]);
    }
  }

  let clarificationQuestion =
    parsed.followUpQuestionRequired === true &&
    typeof parsed.followUpQuestion === "string" &&
    parsed.followUpQuestion.trim().length > 0
      ? parsed.followUpQuestion.trim()
      : null;

  const overrideClarification = inferServerSideClarification(
    promiseText,
    parsed,
    contentRules
  );
  if (overrideClarification && !clarificationQuestion) {
    clarificationQuestion = overrideClarification;
    cautionMessages.push(
      "AI interpretation was treated as uncertain, so confirmation is required."
    );
  }

  if (Array.isArray(parsed.rejectedUnsafeParts) && parsed.rejectedUnsafeParts.length > 0) {
    cautionMessages.push(
      `Rejected unsafe parts: ${parsed.rejectedUnsafeParts
        .filter((p) => typeof p === "string")
        .slice(0, 3)
        .join("; ")}`
    );
  }

  if (Array.isArray(parsed.interpretationNotes)) {
    for (const note of parsed.interpretationNotes) {
      if (typeof note === "string" && note.trim()) {
        interpretationNotes.push(note.trim());
      }
    }
  }

  // Final belt: never ship Entertainment budget for video-length-only promises.
  let conditionalSummaries = [...quotaMapped.conditionals];
  if (
    looksLikeMediaLengthThreshold(promiseText) &&
    !looksLikeUsageBudget(promiseText)
  ) {
    conditionalSummaries = conditionalSummaries.filter(
      (s) => !/entertainment\s+budget/i.test(String(s))
    );
    contentRules = contentRules.filter((r) => r.contentType !== "usage_quota");
  }

  if (hasAdult && quotaMapped.shortFormQuota) {
    conditionalSummaries.unshift(
      "Blocked always: adult/sexual short videos (bypass quota)"
    );
  }

  const wholeBlockedPackages = new Set(
    suggestedAppRules.filter((r) => r.behavior === "BLOCK").map((r) => r.packageName)
  );
  const longFormAllowed = contentRules.some(
    (r) =>
      r.action === "ALLOW" &&
      (r.contentType === "long_form_video" || r.contentType === "study") &&
      !(r.packageName && wholeBlockedPackages.has(r.packageName))
  );
  if (longFormAllowed) {
    conditionalSummaries.push("Still allowed: long educational / study videos");
  }

  if (shortFormCategory && scopePackages.length) {
    conditionalSummaries.push(
      "Applies to: short-video apps, social reels apps, and browser video pages"
    );
  }

  const confirmationPreview = buildConfirmationPreview({
    modelCopy:
      typeof parsed.userFacingConfirmation === "string"
        ? parsed.userFacingConfirmation.trim()
        : "",
    allowedSummaries: allowedMapped.summaries,
    blockedSummaries: blockedMapped.summaries,
    conditionalSummaries,
    interpretationNotes,
    shortFormQuota: quotaMapped.shortFormQuota,
    hasAdult,
    longFormAllowed,
    timeWindowKind: durationMapped.timeWindowKind,
    promiseText,
  });

  for (const note of interpretationNotes) {
    if (!cautionMessages.includes(note)) {
      cautionMessages.push(note);
    }
  }

  const userFacingNotes = uniqueStrings(interpretationNotes).map((n) =>
    sanitizeUserFacingCopy(n, promiseText)
  );
  const userFacingCautions = uniqueStrings(cautionMessages).map((n) =>
    sanitizeUserFacingCopy(n, promiseText)
  );

  let clarificationOptions = normalizeClarificationOptions(
    parsed.clarificationOptions
  );
  const googleClarify = inferGoogleVideoClarification(promiseText);
  if (googleClarify) {
    const optsMentionScope = clarificationOptions.some((o) =>
      /chrome|browser|youtube|all video/i.test(
        `${o.label} ${o.description || ""}`
      )
    );
    if (clarificationOptions.length === 0 || !optsMentionScope) {
      clarificationOptions = normalizeClarificationOptions(googleClarify.options);
    } else {
      // Model options may lack structured rematerialize — merge Google previews by id/fingerprint.
      clarificationOptions = clarificationOptions.map((opt) => {
        if (opt.internalPolicyPreview) return opt;
        const matchedGoogle = googleClarify.options.find(
          (g) =>
            String(g.id).toUpperCase() === String(opt.id).toUpperCase() ||
            resolveOptionRematerialize(opt)?.scopeKind ===
              g.internalPolicyPreview?.scopeKind
        );
        if (!matchedGoogle) return opt;
        return {
          ...opt,
          policyPreview: opt.policyPreview || matchedGoogle.policyPreview,
          resultingPolicyPreview:
            opt.policyPreview || matchedGoogle.policyPreview,
          internalPolicyPreview: matchedGoogle.internalPolicyPreview,
        };
      });
    }
    if (!clarificationQuestion) {
      clarificationQuestion = googleClarify.question;
    } else if (!/chrome|youtube|video apps|google video/i.test(clarificationQuestion)) {
      clarificationQuestion = googleClarify.question;
    }
    userFacingCautions.push(
      sanitizeUserFacingCopy(
        "“Google video” is ambiguous — pick Chrome, all video apps, or YouTube before Start.",
        promiseText
      )
    );
  }

  const shortsClarify = inferShortFormScopeClarification(promiseText);
  if (shortsClarify && !googleClarify) {
    // Server-built A/B only: model-authored shorts options carried their own typed
    // scopes and often collapsed both choices to SHORT_FORM_ANY_HOST.
    clarificationOptions = normalizeClarificationOptions(shortsClarify.options);
    if (!clarificationQuestion) {
      clarificationQuestion = shortsClarify.question;
    }
  }

  const selectedClarifyId =
    typeof options.selectedClarificationOptionId === "string"
      ? options.selectedClarificationOptionId.trim()
      : "";
  const compoundClarify = inferCompoundIntentClarification(promiseText);
  if (compoundClarify && !/^B$|^C$/i.test(selectedClarifyId)) {
    clarificationOptions = normalizeClarificationOptions(compoundClarify.options);
    clarificationQuestion = compoundClarify.question;
  }
  // Pin each shown option to a structured scope now, so the snapshot Android
  // echoes back on the pick is exactly what the user saw (not re-parsed later).
  if (!compoundClarify) {
    clarificationOptions = clarificationOptions.map((opt) =>
      opt.internalPolicyPreview
        ? opt
        : { ...opt, internalPolicyPreview: resolveOptionRematerialize(opt) || null }
    );
  }

  let ambiguityLevel = ["none", "low", "medium", "high"].includes(
    String(parsed.ambiguityLevel || "")
  )
    ? String(parsed.ambiguityLevel)
    : null;

  // High ambiguity always requires clarification (v08 contract).
  let clarificationRequired =
    Boolean(clarificationQuestion) ||
    clarificationOptions.length > 0 ||
    parsed.clarificationRequired === true ||
    parsed.followUpQuestionRequired === true ||
    ambiguityLevel === "high";

  if (ambiguityLevel === "high" && clarificationOptions.length === 0 && !clarificationQuestion) {
    clarificationQuestion =
      "Which of these matches what you meant?";
  }

  if (!ambiguityLevel) {
    ambiguityLevel = clarificationRequired ? "high" : "none";
  }

  // If model marked high ambiguity but forgot the flag, force it.
  if (ambiguityLevel === "high") {
    clarificationRequired = true;
  }

  if (clarificationRequired && !clarificationQuestion && clarificationOptions.length) {
    clarificationQuestion = "Which of these matches what you meant?";
  }

  const permanent = isPermanentCommitment(parsed, promiseText);
  const transcriptConfidence = Number.isFinite(Number(parsed.transcriptConfidence))
    ? Number(parsed.transcriptConfidence)
    : null;
  const lowTranscript =
    transcriptConfidence != null && transcriptConfidence < 0.7;

  const timeWindowText =
    durationMapped.timeWindowKind === "calendar_day"
      ? "Today / rest of the day"
      : durationMapped.timeWindowKind === "permanent"
        ? "Long-term / permanent window"
        : durationMapped.usedDefault
          ? "About 1 hour (default — edit if wrong)"
          : `About ${durationMapped.minutes} minutes`;

  const appliesDefault = shortFormCategory
    ? "Short-form video apps and browser short videos"
    : "Apps and content named in your promise";

  const checkThisDefaults = uniqueStrings([
    ...userFacingNotes,
    ...(shortFormCategory
      ? [
          "I treated “shorts” as all short-form video surfaces (editable — change if you meant YouTube only).",
        ]
      : []),
    ...(lowTranscript
      ? [
          "Voice transcript looked uncertain — please re-read before locking a long commitment.",
        ]
      : []),
    ...(permanent
      ? [
          "This looks like a long-term / permanent commitment — confirm carefully before Start.",
        ]
      : []),
  ]).map((n) => sanitizeUserFacingCopy(n, promiseText));

  const userFacingConfirmation = normalizeUserFacingConfirmation(
    parsed.userFacingConfirmation,
    {
      understoodSummary:
        (confirmationPreview[0] &&
          sanitizeUserFacingCopy(confirmationPreview[0], promiseText)) ||
        sanitizeUserFacingCopy(
          typeof parsed.recommendedInterpretation === "string"
            ? parsed.recommendedInterpretation
            : typeof parsed.userIntentSummary === "string"
              ? parsed.userIntentSummary
              : "",
          promiseText
        ) ||
        "Review the allow and block lists below.",
      allowedBullets: allowedMapped.summaries
        .map((s) => sanitizeUserFacingCopy(s, promiseText))
        .filter(Boolean),
      blockedBullets: blockedMapped.summaries
        .map((s) => sanitizeUserFacingCopy(s, promiseText))
        .filter(Boolean),
      timeWindowText,
      appliesToText: appliesDefault,
      checkThis: checkThisDefaults,
      safetyNotes: uniqueStrings(
        confirmationPreview
          .concat(userFacingNotes)
          .filter((s) => /adult|safety|emergency|interpreted|permanent|year/i.test(String(s)))
          .map((s) => sanitizeUserFacingCopy(s, promiseText))
          .concat(
            permanent
              ? ["Long-term rule: removing this later should require stronger confirmation."]
              : []
          )
          .concat(
            lowTranscript
              ? ["Transcript confidence was low — verify wording before permanent Start."]
              : []
          )
      ),
      hiddenInternalScope: scopePackages,
    }
  );

  const cleanedPromiseText = sanitizeUserFacingCopy(
    typeof parsed.cleanedPromiseText === "string" && parsed.cleanedPromiseText.trim()
      ? parsed.cleanedPromiseText
      : promiseText,
    promiseText
  );

  const userIntentSummary = sanitizeUserFacingCopy(
    typeof parsed.userIntentSummary === "string" && parsed.userIntentSummary.trim()
      ? parsed.userIntentSummary
      : typeof parsed.recommendedInterpretation === "string"
        ? parsed.recommendedInterpretation
        : cleanedPromiseText,
    promiseText
  );

  const alternativeInterpretations = (
    Array.isArray(parsed.alternativeInterpretations)
      ? parsed.alternativeInterpretations
      : []
  )
    .filter((s) => typeof s === "string")
    .map((s) => sanitizeUserFacingCopy(s, promiseText))
    .filter(Boolean);

  const detectedLanguage =
    typeof parsed.detectedLanguage === "string" && parsed.detectedLanguage.trim()
      ? parsed.detectedLanguage.trim()
      : /[^\x00-\x7F]/.test(promiseText) ||
          /\b(aaj|nahi|bilkul|sirf|mat|karo)\b/i.test(promiseText)
        ? "hinglish"
        : "en";

  const riskReview = normalizeRiskReview(parsed.riskReview, {
    falseAllowRisk: clarificationRequired ? "medium" : "low",
    falseBlockRisk: "low",
    safetyRisk: hasAdult || permanent ? "medium" : "none",
    requiresHumanConfirmation: permanent || clarificationRequired || lowTranscript,
  });
  if (permanent) {
    riskReview.requiresHumanConfirmation = true;
    if (riskReview.safetyRisk === "none") riskReview.safetyRisk = "medium";
  }

  const recoveryRules = {
    hardness: permanent ? "permanent" : "normal",
    requiresExplicitConfirm: permanent,
    notes: permanent
      ? "Permanent / multi-year commitments need explicit confirm checkbox before Start."
      : "",
  };
  if (
    parsed.internalPolicy &&
    typeof parsed.internalPolicy === "object" &&
    parsed.internalPolicy.recoveryRules &&
    typeof parsed.internalPolicy.recoveryRules === "object"
  ) {
    Object.assign(recoveryRules, parsed.internalPolicy.recoveryRules);
    if (permanent) {
      recoveryRules.hardness = recoveryRules.hardness || "permanent";
      recoveryRules.requiresExplicitConfirm = true;
    }
  }

  const internalPolicy = normalizeInternalPolicy(parsed.internalPolicy, {
    timeWindow: {
      kind: durationMapped.timeWindowKind,
      minutes: durationMapped.minutes,
      label: timeWindowText,
    },
    surfaceRules: contentRules.map((r) => ({
      surface: r.surface,
      action: r.action,
      note: r.description || "",
    })),
    contentRules,
    appRules: suggestedAppRules,
    quotaRules: Array.isArray(parsed.quotas) ? parsed.quotas : [],
    recoveryRules,
    guardrails: (() => {
      const guards = Array.isArray(parsed.activeGuardrails)
        ? [...parsed.activeGuardrails]
        : [];
      const adultFiltered = adultSafetyExplicit
        ? guards
        : guards.filter((g) => g !== "no_adult_content");
      if (quotaMapped.shortFormQuota) {
        return adultFiltered.filter((g) => g !== "no_short_form_video");
      }
      return adultFiltered;
    })(),
  });

  const hiddenInternalScope = uniqueStrings([
    ...(Array.isArray(parsed.hiddenInternalScope)
      ? parsed.hiddenInternalScope.filter((s) => typeof s === "string")
      : []),
    ...scopePackages,
    ...(userFacingConfirmation.hiddenInternalScope || []),
  ]);
  // Keep packages out of the nested confirmation object shown to primary UI helpers.
  userFacingConfirmation.hiddenInternalScope = [];

  const dto = {
    rawText: promiseText,
    cleanedPromiseText,
    detectedLanguage,
    transcriptConfidence,
    userIntentSummary,
    recommendedInterpretation:
      sanitizeUserFacingCopy(
        typeof parsed.recommendedInterpretation === "string"
          ? parsed.recommendedInterpretation
          : "",
        promiseText
      ) || null,
    alternativeInterpretations,
    interpretationConfidence: Number.isFinite(Number(parsed.interpretationConfidence))
      ? Number(parsed.interpretationConfidence)
      : Number(parsed.confidence),
    ambiguityLevel,
    clarificationRequired,
    clarificationOptions,
    userFacingConfirmation,
    internalPolicy,
    riskReview,
    hiddenInternalScope,
    // Never strip emergency exceptions — fail-closed safety path (CK-EMERGENCY).
    emergencyExceptions: normalizeEmergencyExceptions(parsed.emergencyExceptions),
    sessionDurationMinutes: durationMapped.minutes,
    timeWindowKind: durationMapped.timeWindowKind,
    quotaBoundary: QUOTA_BOUNDARY,
    strictness,
    allowedSummaries: allowedMapped.summaries.map((s) =>
      sanitizeUserFacingCopy(s, promiseText)
    ),
    blockedSummaries: blockedMapped.summaries.map((s) =>
      sanitizeUserFacingCopy(s, promiseText)
    ),
    conditionalSummaries: conditionalSummaries.map((s) =>
      sanitizeUserFacingCopy(s, promiseText)
    ),
    confirmationPreview: confirmationPreview.map((s) =>
      sanitizeUserFacingCopy(s, promiseText)
    ),
    interpretationNotes: userFacingNotes,
    scopePackages,
    scopeKind: null,
    contentBrands: [],
    surfaceScope: [],
    contentScope: [],
    contentRules,
    clarificationQuestion: clarificationQuestion
      ? sanitizeUserFacingCopy(clarificationQuestion, promiseText)
      : null,
    cautionMessages: uniqueStrings(userFacingCautions),
    source: "azure_promise_compiler",
    confidence: Number(parsed.confidence),
    suggestedAppRules,
    commitmentType:
      typeof parsed.commitmentType === "string" ? parsed.commitmentType : "focus_session",
    promptVersion: PROMPT_VERSION,
    // Start only when clarify is done; permanent/transcript still need stronger confirm UI.
    canStartCommitment: !clarificationRequired && !(permanent || lowTranscript),
    isPermanentCommitment: permanent,
    // Stronger confirm = checkbox path (permanent / bad transcript), NOT "pick A/B/C".
    requiresStrongerConfirmation: permanent || lowTranscript,
    isStudyModeLike: looksLikeStudyMode(parsed),
  };

  const selectedId =
    typeof options.selectedClarificationOptionId === "string"
      ? options.selectedClarificationOptionId.trim()
      : "";
  // A clarification pick is resolved ONLY from what the user saw: the prior
  // options snapshot (+ carried option cross-check). The fresh options in this
  // turn's model output are never consulted — ids are per-response labels and
  // a reused "A" once flipped "All video apps" to Chrome-only (2026-09-26).
  const carriedOption =
    options && typeof options.selectedClarificationOptionFullText === "object"
      ? options.selectedClarificationOptionFullText
      : null;
  const snapshotOptions = Array.isArray(
    options && options.priorClarificationOptionsSnapshot
  )
    ? options.priorClarificationOptionsSnapshot
    : [];
  let verifiedChoice = null;
  if (selectedId) {
    dto.selectedClarificationOptionId = selectedId;
    const priorPromiseText =
      options && typeof options.priorClarificationPromiseText === "string"
        ? options.priorClarificationPromiseText
        : undefined;
    const resolution = verifyAppSurfaceResolution(
      resolveClarificationSelection({
        selectedId,
        snapshot: snapshotOptions,
        carried: carriedOption,
        priorPromiseText,
        promiseText,
      }),
      {
        promiseText,
        priorPromiseText,
        contract: scopeContractFor(options.promptVersion || PROMPT_VERSION),
      }
    );
    const applied =
      resolution.ok &&
      rematerializeDtoFromOptionPreview(dto, resolution.preview, promiseText);
    const authoritativeOptions =
      snapshotOptions.length > 0 &&
      resolution.reason !== "snapshot_duplicate_ids" &&
      resolution.reason !== "promise_text_changed"
        ? normalizeClarificationOptions(snapshotOptions)
        : [];
    if (!applied) {
      dto.clarificationRequired = true;
      dto.canStartCommitment = false;
      dto.optionRematerialized = false;
      dto.optionVerification = "unrecognized_fail_closed";
      dto.optionVerificationReason = resolution.ok
        ? "rematerialize_failed"
        : resolution.reason;
      if (!dto.clarificationQuestion) {
        dto.clarificationQuestion = "Which of these matches what you meant?";
      }
      // Re-ask with a list the user can actually tap: the one they saw, else fresh.
      if (authoritativeOptions.length > 0 && resolution.reason !== "snapshot_missing_id") {
        dto.clarificationOptions = authoritativeOptions;
      }
      stripUsingChoiceClaims(dto);
      dto.cautionMessages = uniqueStrings([
        ...dto.cautionMessages,
        UNRECOGNIZED_CLARIFICATION_CHOICE,
      ]);
    } else {
      const chosen = resolution.option;
      verifiedChoice = chosen;
      dto.optionRematerialized = true;
      dto.optionVerification = resolution.reason;
      dto.optionVerificationReason = resolution.reason;
      dto.clarificationRequired = false;
      dto.ambiguityLevel = "low";
      if (dto.riskReview && typeof dto.riskReview === "object") {
        dto.riskReview = {
          ...dto.riskReview,
          requiresHumanConfirmation: permanent || lowTranscript,
          falseAllowRisk: "low",
        };
      }
      dto.requiresStrongerConfirmation = permanent || lowTranscript;
      dto.recommendedInterpretation =
        sanitizeUserFacingCopy(
          chosen.policyPreview ||
            chosen.resultingPolicyPreview ||
            chosen.description ||
            chosen.label ||
            "",
          promiseText
        ) || null;
      if (authoritativeOptions.length > 0) {
        dto.clarificationOptions = authoritativeOptions;
      }
      scrubStaleChoiceCopy(dto);
      const chosenLabel =
        sanitizeUserFacingCopy(chosen.label || "", promiseText) || `option ${selectedId}`;
      dto.cautionMessages = uniqueStrings([
        ...dto.cautionMessages.filter((s) => !isUnrecognizedClarificationChoice(s)),
        `Using your choice: ${chosenLabel}`,
      ]);
      dto.canStartCommitment = !dto.requiresStrongerConfirmation;
    }
  }

  const compoundSelection =
    trusted && trusted.compoundSelection && trusted.compoundSelection.ok === true
      ? trusted.compoundSelection
      : null;
  if (compoundSelection && compoundSelection.id !== "A") {
    // B/C compiled a narrowed clause; the pick was verified before narrowing.
    // Start stays governed by this clause's own compile gates.
    dto.selectedClarificationOptionId = compoundSelection.id;
    dto.optionVerification = compoundSelection.reason;
    dto.optionVerificationReason = compoundSelection.reason;
    const chosenLabel =
      sanitizeUserFacingCopy(compoundSelection.option.label || "", promiseText) ||
      `option ${compoundSelection.id}`;
    dto.cautionMessages = uniqueStrings([
      ...dto.cautionMessages.filter((s) => !isUnrecognizedClarificationChoice(s)),
      `Using your choice: ${chosenLabel}`,
    ]);
  }

  stripFalseUnrecognizedWarnings(dto, selectedId);
  // Reliability design 2026-09-25 §Fix 1: do NOT let the concrete-policy ghost
  // clearer undo an unrecognized-fail-closed verdict from the option block
  // above. That was the exact bypass — the model produced a concrete-looking
  // policy on turn 2 and clearGhostClarificationIfConcrete silently unlocked
  // Start on a scope the user never confirmed.
  if (dto.optionVerification !== "unrecognized_fail_closed") {
    clearGhostClarificationIfConcrete(dto);
  }
  reconcilePreviewWithEffectivePolicy(dto, scopedSplit);
  stripPackageLeaksFromDto(dto, promiseText);
  applyYouTubeContentOrAppScope(dto, promiseText);
  applyFailClosedStartGates(dto);
  // Last: nothing after this may reopen Start on a missing/unresolved v1 scope.
  applyScopeContract(dto, parsed, {
    promptVersion: options.promptVersion || PROMPT_VERSION,
    promiseText,
    selectedId,
    verifiedChoice,
    carriedOption,
    priorPromiseText:
      options && typeof options.priorClarificationPromiseText === "string"
        ? options.priorClarificationPromiseText
        : undefined,
    compoundVerified: Boolean(compoundSelection),
  });
  // Close-only: a scope/action contradiction keeps Start shut on every contract.
  applyScopedAppRuleGates(dto, scopedSplit, {
    promiseText,
    selectedId,
    contract: scopeContractFor(options.promptVersion || PROMPT_VERSION),
  });
  return dto;
}

function isYouTubeAppOnlyScopeKind(scopeKind) {
  const kind = String(scopeKind || "").trim();
  return (
    kind === "youtube_app_only" ||
    kind === "youtube_only" ||
    kind === "youtube_shorts_only"
  );
}

/**
 * "YouTube video/content" is a brand, not the official package.
 * Package-exclusive scope only when the user said YouTube app only
 * or picked the app-only rematerialize option.
 */
function applyYouTubeContentOrAppScope(dto, promiseText) {
  if (!dto || typeof dto !== "object") return;
  const kind = dto.optionScopeKind || dto.scopeKind;
  if (appSurfaceEntryForScopeKind(kind)) return;
  if (isYouTubeAppOnlyScopeKind(kind)) {
    dto.scopeKind = kind === "youtube_shorts_only" ? kind : "youtube_app_only";
    dto.contentBrands = [];
    dto.surfaceScope = [];
    dto.contentScope = [];
    if (!Array.isArray(dto.scopePackages) || dto.scopePackages.length === 0) {
      dto.scopePackages = [PKG_YOUTUBE];
    }
    return;
  }
  if (
    kind === "chrome_browser" ||
    kind === "all_video_apps" ||
    kind === "short_form_all_surfaces"
  ) {
    return;
  }
  if (/\bgoogle\s+videos?\b/i.test(promiseText) && !dto.optionRematerialized) {
    return;
  }
  // A verified "YouTube content anywhere" pick is the user's answer; promise
  // wording must not re-narrow it to the app.
  const verifiedContentPick =
    kind === "youtube_content" &&
    dto.optionRematerialized &&
    /verified|carried_full_text/.test(String(dto.optionVerification || ""));
  if (!verifiedContentPick && namesYouTubeAppOnly(promiseText)) {
    dto.scopeKind = "youtube_app_only";
    dto.scopePackages = [PKG_YOUTUBE];
    dto.contentBrands = [];
    dto.surfaceScope = [];
    dto.contentScope = [];
    return;
  }
  if (!verifiedContentPick && !namesYouTubeContentBrand(promiseText)) return;

  dto.scopeKind = "youtube_content";
  dto.contentBrands = ["youtube"];
  dto.surfaceScope = ["youtube"];
  dto.contentScope = ["youtube"];
  const pkgs = Array.isArray(dto.scopePackages) ? dto.scopePackages : [];
  const withoutYoutubeClients = pkgs.filter(
    (p) =>
      p !== PKG_YOUTUBE &&
      p !== "com.vanced.android.youtube" &&
      !/youtube|vanced|revanced/i.test(String(p || ""))
  );
  dto.scopePackages =
    pkgs.length > 0 && withoutYoutubeClients.length === 0
      ? []
      : withoutYoutubeClients;

  const applies =
    "YouTube videos anywhere — app, browser pages, and other YouTube clients";
  if (dto.userFacingConfirmation) {
    dto.userFacingConfirmation = {
      ...dto.userFacingConfirmation,
      appliesTo: applies,
      appliesToText: applies,
    };
  }
  dto.conditionalSummaries = uniqueStrings([
    ...(dto.conditionalSummaries || []).filter(
      (s) => !/^applies to:/i.test(String(s || ""))
    ),
    `Applies to: ${applies}`,
  ]);
}

function uniqueStrings(items) {
  const seen = new Set();
  const out = [];
  for (const item of items) {
    if (typeof item !== "string") continue;
    const t = item.trim();
    if (!t || seen.has(t)) continue;
    seen.add(t);
    out.push(t);
  }
  return out;
}

function hasDayPeriodQuota(quotas) {
  if (!Array.isArray(quotas)) return false;
  return quotas.some(
    (q) =>
      q &&
      typeof q === "object" &&
      String(q.period || "").toLowerCase() === "day"
  );
}

function shouldUseCalendarDayWindow(parsed, rawText, shortFormQuota) {
  const text = String(rawText || "").toLowerCase();
  if (/\b(today|rest of (the )?day|for the day|aaj|daily)\b/.test(text)) {
    return true;
  }
  if (shortFormQuota && shortFormQuota.period === "day") return true;
  if (hasDayPeriodQuota(parsed.quotas)) return true;
  const dur = parsed.duration || {};
  if (
    String(dur.kind || "").toLowerCase() === "fixed" &&
    String(dur.unit || "").toLowerCase() === "days"
  ) {
    return true;
  }
  // Pure daily quota commitment with duration none — never invent 60m session.
  if (
    String(parsed.commitmentType || "") === "quota_entertainment" &&
    String(dur.kind || "none").toLowerCase() === "none"
  ) {
    return true;
  }
  return false;
}

function needsShortFormCategoryExpansion(rawText, parsed, shortFormQuota, contentRules) {
  const text = String(rawText || "").toLowerCase();
  const mentionsShorts =
    /\b(shorts?|shots?|sorts?|short[- ]form|reels?|tiktoks?|spotlight)\b/.test(text);
  const appScopedShortForm =
    /\b(?:youtube|instagram|facebook|tiktok|snapchat)(?:\s+app)?\s+(?:shorts?|reels?|spotlight|short(?:[- ]form)?\s+videos?)\b/.test(
      text
    ) ||
    /\b(?:shorts?|reels?|spotlight|short(?:[- ]form)?\s+videos?)(?:\s+only)?\s+(?:in|on|inside|within|from)\s+(?:the\s+)?(?:youtube|instagram|facebook|tiktok|snapchat)(?:\s+app)?\b/.test(
      text
    );
  const explicitlyGlobal =
    /\b(?:all|every|any)\s+(?:apps?|platforms?|surfaces?)\b|\beverywhere\b|\bacross\s+all\b/.test(
      text
    );
  if (appScopedShortForm && !explicitlyGlobal) return false;
  if (shortFormQuota) return true;
  if (
    contentRules.some((r) => r.contentType === "short_form_video") &&
    mentionsShorts &&
    (!appScopedShortForm || explicitlyGlobal)
  ) {
    return true;
  }
  if (mentionsShorts && /\b(at most|only|max|quota|today|allow\s+\d+|up\s*to)\b/.test(text)) {
    return true;
  }
  return false;
}

function buildConfirmationPreview({
  modelCopy,
  allowedSummaries,
  blockedSummaries,
  conditionalSummaries,
  interpretationNotes,
  shortFormQuota,
  hasAdult,
  longFormAllowed,
  timeWindowKind,
  promiseText = "",
}) {
  const lines = [];
  if (modelCopy) lines.push(modelCopy);
  if (shortFormQuota) {
    lines.push(
      `Allowed: first ${shortFormQuota.limit} short-form videos ${
        shortFormQuota.period === "day" ? "today" : "per " + shortFormQuota.period
      }`
    );
    lines.push(
      `After limit: block short-form videos until ${
        shortFormQuota.period === "day" ? "tomorrow" : "reset"
      }`
    );
  }
  if (hasAdult) {
    lines.push("Blocked always: adult/sexual short videos");
  }
  if (longFormAllowed) {
    lines.push("Still allowed: long educational / study videos");
  }
  if (timeWindowKind === "calendar_day") {
    lines.push("Time window: today / rest of day (not a 1-hour session)");
  }
  for (const s of conditionalSummaries || []) {
    if (typeof s === "string" && s.trim() && !lines.includes(s.trim())) {
      lines.push(s.trim());
    }
  }
  for (const s of allowedSummaries || []) {
    if (typeof s === "string" && s.trim() && lines.length < 8) {
      lines.push(`Allowed: ${s.trim()}`);
    }
  }
  for (const s of blockedSummaries || []) {
    if (typeof s === "string" && s.trim() && lines.length < 10) {
      lines.push(`Blocked: ${s.trim()}`);
    }
  }
  for (const n of interpretationNotes || []) {
    if (typeof n === "string" && /interpreted/i.test(n) && !lines.includes(n)) {
      lines.push(sanitizeUserFacingCopy(n, promiseText));
    }
  }
  return uniqueStrings(
    lines.map((line) => sanitizeUserFacingCopy(line, promiseText))
  ).slice(0, 12);
}

/**
 * User-facing confirmation must stay category-level.
 * Internal scopePackages / suggestedAppRules may still list concrete packages.
 */

function collectContentDescriptions(...lists) {
  const out = [];
  for (const list of lists) {
    if (!Array.isArray(list)) continue;
    for (const item of list) {
      if (!item || typeof item !== "object") continue;
      if (typeof item.description === "string" && item.description.trim()) {
        out.push(item.description.trim());
      }
    }
  }
  return out;
}

const VAGUE_STRICT_PATTERNS = [
  /\bmake me strict\b/,
  /\bbe strict\b/,
  /\bfocus mode\b/,
  /\bstudy mode\b/,
  /\bexam mode\b/,
  /\bclean phone\b/,
  /\bdetox\b/,
  /\bless phone\b/,
  /\bbad apps?\b/,
  /\bnormal phone\b/,
  /\ballow youtube\b/,
  /\bstudy only\b/,
  /\bentertainment\b(?!.*\d)/,
];

function inferServerSideClarification(rawText, parsed, contentRules) {
  const text = rawText.trim().toLowerCase();
  if (!text) {
    return "What promise should I protect?";
  }

  if (isVagueStrictPromise(text, parsed, contentRules)) {
    return "What exactly should I restrict, and for how long? Options: apps, short-form video, adult content, or a daily quota.";
  }

  if (isAmbiguousVideoLengthAllowance(text)) {
    return "Do you mean allow only videos longer than this limit, or simply allow those long videos while keeping shorter videos normal?";
  }

  // Bare "shorts" with no quota/ban/allow verb → ask scope (category language).
  if (
    /\bshorts?\b/.test(text) &&
    !/\b(at most|only|max|allow|block|no|never|ban|\d+)\b/.test(text) &&
    !(Array.isArray(parsed.quotas) && parsed.quotas.length > 0)
  ) {
    return "Did you mean all short-form video across short-video and reels-style apps, or only YouTube Shorts?";
  }

  if (isVagueGoogleVideoScope(text)) {
    return (
      "What should I cover for ‘Google video’? " +
      "Videos inside Chrome/Google search, all video apps, or only YouTube?"
    );
  }

  return null;
}

function isVagueGoogleVideoScope(text) {
  if (!/\bgoogle\b/.test(text) || !/\bvideos?\b/.test(text)) {
    return false;
  }
  // Already specific enough.
  if (
    /\b(youtube|yt|chrome|browser|only|sirf|all\s+video\s+apps?)\b/.test(text)
  ) {
    return false;
  }
  return true;
}

function isVagueStrictPromise(text, parsed, contentRules) {
  const hasConcreteRules =
    contentRules.length > 0 ||
    (Array.isArray(parsed.allowedApps) && parsed.allowedApps.length > 0) ||
    (Array.isArray(parsed.blockedApps) && parsed.blockedApps.length > 0) ||
    (Array.isArray(parsed.activeGuardrails) && parsed.activeGuardrails.length > 0) ||
    (Array.isArray(parsed.quotas) && parsed.quotas.length > 0);
  if (hasConcreteRules) {
    return false;
  }

  return VAGUE_STRICT_PATTERNS.some((pattern) => pattern.test(text));
}

function isAmbiguousVideoLengthAllowance(text) {
  const mentionsVideoLength =
    /\b(?:video|videos|youtube|yt)\b/.test(text) &&
    /\b(?:greater than|more than|over|longer than|above)\s*\d+\s*(?:min|mins|minutes?|hrs?|hours?)\b/.test(
      text
    );
  if (!mentionsVideoLength) {
    return false;
  }

  const explicitlyOnly = /\b(?:only|sirf|mattum|मात्र|केवल)\b/.test(text);
  const explicitlyBlock =
    /\b(?:block|stop|ban|not allow|don't allow|dont allow|no)\b/.test(text);
  return text.includes("allow") && !explicitlyOnly && !explicitlyBlock;
}

function deriveAppRulesFromContent(contentRules, packageLabels) {
  const byPackage = new Map();
  for (const rule of contentRules) {
    if (!rule.packageName) continue;
    const prev = byPackage.get(rule.packageName);
    // A short-form surface BLOCK is WHAT inside the app, not a package-wide BLOCK.
    const thisBehavior = isShortFormSurfaceBlock(rule)
      ? "AI_DECIDE"
      : rule.action === "BLOCK"
        ? "BLOCK"
        : "ALLOW";
    // Mixed surfaces (allow DMs + block reels) → app stays ALLOW; contentRules do the nuance.
    // A short-form-only AI_DECIDE defers to any other surface's verdict for the same app.
    let behavior = thisBehavior;
    if (prev && prev.behavior !== thisBehavior) {
      if (prev.behavior === "AI_DECIDE") behavior = thisBehavior;
      else if (thisBehavior === "AI_DECIDE") behavior = prev.behavior;
      else behavior = "ALLOW";
    }
    byPackage.set(rule.packageName, {
      packageName: rule.packageName,
      label:
        rule.appLabel ||
        packageLabels[rule.packageName] ||
        knownAppLabel(rule.packageName),
      behavior,
    });
  }
  return [...byPackage.values()];
}

function dedupeContentRules(rules) {
  const seen = new Set();
  const out = [];
  for (const rule of rules) {
    if (!rule) continue;
    const key = [
      rule.packageName || "",
      rule.contentType || "",
      rule.surface || "",
      rule.operator || "",
      rule.value == null ? "" : String(rule.value),
      rule.unit || "",
      rule.action || "",
    ].join("|");
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(rule);
  }
  return out;
}

function dedupeSuggestedApps(rules) {
  const seen = new Set();
  const out = [];
  for (const rule of rules) {
    if (!rule || seen.has(rule.packageName)) continue;
    seen.add(rule.packageName);
    out.push({
      packageName: rule.packageName,
      label: rule.label,
      behavior: rule.behavior,
    });
  }
  return out;
}

/** Test-only: replaces the model call. `null` restores the real provider path. */
let compilerTransportForTests = null;
function setCompilerTransportForTests(fn) {
  compilerTransportForTests = typeof fn === "function" ? fn : null;
}

/**
 * Fail-closed response for a compound pick that failed verification. No model call:
 * the policy is empty, Start is closed, and the server re-offers freshly built options.
 */
function buildCompoundFailClosedResponse(promiseText, selectedId, reason, promptVersion) {
  const stub = {
    commitmentType: "focus_session",
    duration: { kind: "fixed", value: DEFAULT_SESSION_MINUTES, unit: "minutes", until: null },
    startCondition: "manual_confirm",
    strictnessLevel: "STRICT",
    allowedApps: [],
    blockedApps: [],
    allowedContent: [],
    blockedContent: [],
    activeGuardrails: [],
    quotas: [],
    followUpQuestionRequired: true,
    followUpQuestion: null,
    clarificationRequired: true,
    ambiguityLevel: "high",
    rejectedUnsafeParts: [],
    confidence: 0,
  };
  const dto = normalizeCompiledPromise(stub, promiseText, { promptVersion });
  dto.selectedClarificationOptionId = selectedId;
  dto.clarificationRequired = true;
  dto.canStartCommitment = false;
  dto.optionRematerialized = false;
  dto.optionVerification = "unrecognized_fail_closed";
  dto.optionVerificationReason = reason;
  if (!dto.clarificationQuestion) {
    dto.clarificationQuestion = "Which of these matches what you meant?";
  }
  stripUsingChoiceClaims(dto);
  dto.cautionMessages = uniqueStrings([...(dto.cautionMessages || []), UNRECOGNIZED_CLARIFICATION_CHOICE]);
  return dto;
}

async function compilePromise(rawPromise, options = {}) {
  const trimmed =
    typeof rawPromise === "string" ? rawPromise.trim() : "";
  if (!trimmed) {
    const err = new Error("promise must be a non-empty string");
    err.statusCode = 400;
    throw err;
  }

  const selectedClarificationOptionId =
    typeof options.selectedClarificationOptionId === "string"
      ? options.selectedClarificationOptionId.trim()
      : typeof options.selectedOptionId === "string"
        ? options.selectedOptionId.trim()
        : "";
  // Reliability design 2026-09-25 §Fix 1: option carry-through payload.
  const selectedClarificationOptionFullText =
    options && typeof options.selectedClarificationOptionFullText === "object"
      ? options.selectedClarificationOptionFullText
      : null;
  const priorClarificationOptionsSnapshot = Array.isArray(
    options && options.priorClarificationOptionsSnapshot
  )
    ? options.priorClarificationOptionsSnapshot
    : null;
  const priorClarificationPromiseText =
    options && typeof options.priorClarificationPromiseText === "string"
      ? options.priorClarificationPromiseText
      : undefined;

  // A compound pick is verified against the snapshot BEFORE any narrowing or model
  // call; a failed pick never compiles partial text.
  let compoundSelection = null;
  if (selectedClarificationOptionId && needsCompoundIntentClarification(trimmed)) {
    compoundSelection = verifyCompoundClauseSelection({
      selectedId: selectedClarificationOptionId,
      snapshot: priorClarificationOptionsSnapshot,
      carried: selectedClarificationOptionFullText,
      priorPromiseText: priorClarificationPromiseText,
      promiseText: trimmed,
    });
    if (!compoundSelection.ok) {
      const failed = buildCompoundFailClosedResponse(
        trimmed,
        selectedClarificationOptionId,
        compoundSelection.reason,
        PROMPT_VERSION
      );
      const safeFull = redactForLog(trimmed, 40);
      console.log(
        JSON.stringify({
          event: "compile_promise_compound_fail_closed",
          promptVersion: PROMPT_VERSION,
          selectedOption: selectedClarificationOptionId,
          verificationReason: compoundSelection.reason,
          snapshotSize: priorClarificationOptionsSnapshot ? priorClarificationOptionsSnapshot.length : 0,
          carrySent: Boolean(selectedClarificationOptionFullText),
          promiseLength: safeFull.length,
        })
      );
      return {
        ...failed,
        selectedClarificationOptionId,
        meta: { provider: "none", deployment: null, promptVersion: PROMPT_VERSION, latencyMs: 0, modelCalled: false },
      };
    }
  }

  const compileText = compoundSelection
    ? resolveCompoundCompileText(trimmed, compoundSelection.id)
    : trimmed;
  const compilingSingleClause = compileText !== trimmed;

  const provider = compilerTransportForTests
    ? { kind: "test", deployment: "test" }
    : getPromiseCompilerProvider();
  if (!provider) {
    const err = new Error(
      "No promise compiler configured: set Azure OpenAI env vars or OPENAI_API_KEY"
    );
    err.statusCode = 503;
    throw err;
  }

  const safe = redactForLog(compileText, 40);
  const started = Date.now();

  const userPayload = { userPromise: compileText };
  if (selectedClarificationOptionId && !compilingSingleClause) {
    userPayload.selectedClarificationOptionId = selectedClarificationOptionId;
    // The model has no memory of turn 1's options. Feed the option label / preview
    // in the user message so it doesn't invent a narrower scope. This does NOT
    // remove the deterministic verify-in-normalize step below.
    if (selectedClarificationOptionFullText) {
      userPayload.selectedClarificationOption = {
        id: selectedClarificationOptionFullText.id,
        label: selectedClarificationOptionFullText.label,
        description: selectedClarificationOptionFullText.description,
        policyPreview:
          selectedClarificationOptionFullText.policyPreview ||
          selectedClarificationOptionFullText.resultingPolicyPreview,
      };
    }
  }

  let completion;
  let deploymentUsed = provider.deployment;
  try {
    if (compilerTransportForTests) {
      const content = await compilerTransportForTests({ userPayload, compileText });
      completion = { choices: [{ message: { content } }] };
    } else {
      const compiled = await completeJsonChatWithFallback({
        provider,
        deployments: getCompilerDeploymentChain(),
        maxTokens: 4000,
        messages: [
          { role: "system", content: loadPromiseCompilerPrompt() },
          {
            role: "user",
            content: JSON.stringify(userPayload),
          },
        ],
      });
      completion = compiled.completion;
      deploymentUsed = compiled.deployment;
    }
  } catch (apiErr) {
    console.error(
      JSON.stringify({
        event: "compile_promise_api_error",
        message: apiErr.message,
        promiseLength: safe.length,
        promisePreview: safe.preview,
      })
    );
    const err = new Error("Promise compiler model request failed");
    err.statusCode = 502;
    throw err;
  }

  const latencyMs = Date.now() - started;
  const raw = completion.choices[0]?.message?.content;
  if (!raw) {
    const err = new Error("Empty model response");
    err.statusCode = 502;
    throw err;
  }

  let parsed;
  try {
    parsed = parseClassifierJson(raw);
  } catch (_parseErr) {
    const err = new Error("Model did not return valid JSON");
    err.statusCode = 502;
    err.raw = raw.slice(0, 500);
    throw err;
  }

  let normalized;
  try {
    normalized = normalizeCompiledPromise(parsed, compileText, {
      selectedClarificationOptionId: compilingSingleClause
        ? ""
        : selectedClarificationOptionId,
      // Only forward carry-through fields on the same clause the user picked
      // on. In the compound-clause path they belong to a different sub-promise.
      selectedClarificationOptionFullText: compilingSingleClause
        ? null
        : selectedClarificationOptionFullText,
      priorClarificationOptionsSnapshot: compilingSingleClause
        ? null
        : priorClarificationOptionsSnapshot,
      priorClarificationPromiseText: compilingSingleClause
        ? undefined
        : priorClarificationPromiseText,
    }, { compoundSelection });
  } catch (normErr) {
    if (!normErr.statusCode) {
      normErr.statusCode = 502;
    }
    throw normErr;
  }

  console.log(
    JSON.stringify({
      event: "compile_promise",
      provider: provider.kind,
      deployment: deploymentUsed,
      promptVersion: PROMPT_VERSION,
      latencyMs: Math.round(latencyMs),
      confidence: normalized.confidence,
      clarification: Boolean(normalized.clarificationQuestion),
      selectedOption: selectedClarificationOptionId || null,
      // (snapshot_and_carry_verified | snapshot_verified | carried_full_text | unrecognized_fail_closed | null)
      verification: normalized.optionVerification || null,
      verificationReason: normalized.optionVerificationReason || null,
      selectedScopeKind: selectedClarificationOptionId
        ? normalized.optionScopeKind || normalized.scopeKind || null
        : null,
      selectedScopePackageCount: selectedClarificationOptionId
        ? (normalized.scopePackages || []).length
        : null,
      snapshotSize: priorClarificationOptionsSnapshot
        ? priorClarificationOptionsSnapshot.length
        : 0,
      carrySent: Boolean(selectedClarificationOptionFullText),
      scopeContract: normalized.scopeContract || null,
      typedScopeKind: normalized.scope ? normalized.scope.kind : null,
      typedScopeUnresolvedReason: normalized.scope ? normalized.scope.unresolvedReason || null : null,
      promiseLength: safe.length,
      promisePreview: safe.preview,
      sessionDurationMinutes: normalized.sessionDurationMinutes,
      strictness: normalized.strictness,
    })
  );

  return {
    ...normalized,
    selectedClarificationOptionId: selectedClarificationOptionId || null,
    meta: {
      provider: provider.kind,
      deployment: deploymentUsed,
      promptVersion: PROMPT_VERSION,
      latencyMs: Math.round(latencyMs),
    },
  };
}

module.exports = {
  PROMPT_VERSION,
  PROMPT_PATH,
  DEFAULT_SESSION_MINUTES,
  CALENDAR_DAY_MINUTES,
  QUOTA_BOUNDARY,
  SHORT_FORM_PLATFORM_PACKAGES,
  ALL_VIDEO_PLATFORM_PACKAGES,
  ALLOWED_PROMPT_VERSIONS,
  getPromiseCompilerProvider,
  loadPromiseCompilerPrompt,
  durationToSessionMinutes,
  normalizeCompiledPromise,
  parseShortFormLimitFromText,
  parseMediaLengthThreshold,
  inferMediaLengthIntent,
  reconcileMediaLengthRules,
  extractMediaLengthThresholdMinutes,
  looksLikeMediaLengthThreshold,
  looksLikeUsageBudget,
  isMisencodedMediaLengthQuota,
  sanitizeUserFacingCopy,
  userFacingHasPackageLeak,
  normalizeClarificationOptions,
  normalizeUserFacingConfirmation,
  normalizeRiskReview,
  normalizeInternalPolicy,
  normalizeEmergencyExceptions,
  repairMediaLengthItemQuotas,
  applyFailClosedStartGates,
  stripPackageLeaksFromDto,
  isPermanentCommitment,
  isNormalPhoneExceptSafety,
  resolveOptionRematerialize,
  resolveClarificationSelection,
  rematerializeDtoFromOptionPreview,
  inferGoogleVideoClarification,
  inferShortFormScopeClarification,
  inferCompoundIntentClarification,
  needsCompoundIntentClarification,
  resolveCompoundCompileText,
  compilePromise,
  // Scope Semantics v1 (Phase 1) — validate-or-UNRESOLVED
  validateScope,
  validateOptionScope,
  enforceScopeStartGates,
  isEnforceableScope,
  SCOPE_VERSION_V1,
  SCOPE_KINDS,
  SCOPE_HOSTS,
  SCOPE_IDENTITIES,
  SCOPE_SURFACE_CLASSES,
  SCOPE_PACKAGE_MATCH_MODES,
  SCOPE_UNRESOLVED_REASONS,
  // Scope contract v1 (prompt v09): canonical presets + start gate
  SCOPE_CONTRACT_V1_PROMPTS,
  scopeContractFor,
  canonicalizeScope,
  validateAndCanonicalizeScope,
  scopeOptionLabel,
  typedScopeFingerprint,
  applyScopeContract,
  // v09 clarification: compound identity gate + deterministic scope bridge
  verifyCompoundClauseSelection,
  clarificationOptionProjection,
  matchDeterministicScopeOption,
  DETERMINISTIC_SCOPE_BRIDGE,
  buildCompoundFailClosedResponse,
  setCompilerTransportForTests,
};
