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
  "CONTENT_ANY_HOST",
  "VIDEO_CLASS_ANY_HOST",
  "SHORT_FORM_ANY_HOST",
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

/**
 * Bare “shorts” without quota/ban verbs — ask all surfaces vs YouTube Shorts only.
 * Clear “10 shorts today” stays auto-expanded (no options) via shortFormCategory.
 */
function inferShortFormScopeClarification(rawText, parsed) {
  const text = String(rawText || "").toLowerCase().trim();
  if (!text) return null;
  if (
    !/\bshorts?\b/.test(text) ||
    /\b(at most|only|max|allow|block|no|never|ban|\d+)\b/.test(text) ||
    (Array.isArray(parsed.quotas) && parsed.quotas.length > 0)
  ) {
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
  if (/youtube only|only youtube(?!\s+shorts)/.test(blob)) {
    return buildScopeInternalPreview({
      scopeKind: "youtube_only",
      appliesTo: "YouTube only",
      packages: [{ packageName: PKG_YOUTUBE, appLabel: "YouTube" }],
    });
  }
  if (/all video apps|all major video|every video app/.test(blob)) {
    return buildScopeInternalPreview({
      scopeKind: "all_video_apps",
      appliesTo: "Major video apps and browser video",
      packages: ALL_VIDEO_PLATFORM_PACKAGES,
    });
  }
  if (
    (/chrome|browser/.test(blob) && !/all video/.test(blob)) ||
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

/**
 * Repair inverted min/max_item_minutes from the model using raw promise text.
 * "only allow / under / less than X min" → max length → BLOCK gt X.
 * "only / longer than / greater than X" (min length) → BLOCK lt X.
 */
function repairMediaLengthItemQuotas(quotas, rawText) {
  if (!Array.isArray(quotas)) return quotas;
  const text = String(rawText || "").toLowerCase();
  const wantsMaxLength =
    /\b(?:less than|under|below|shorter than|at most|no more than|up to)\s*\d+\s*(?:min|mins|minutes?|hrs?|hours?)\b/.test(
      text
    ) ||
    /\bonly\s+allow\b.*\b(?:less than|under|below|shorter than)\b/.test(text);
  const wantsMinLength =
    /\b(?:greater than|more than|longer than|over|above)\s*\d+\s*(?:min|mins|minutes?|hrs?|hours?)\b/.test(
      text
    ) &&
    /\b(?:only|at least|minimum|no\s+less)\b/.test(text);
  return quotas.map((q) => {
    if (!q || typeof q !== "object") return q;
    const metric = String(q.metric || "");
    if (metric !== "max_item_minutes" && metric !== "min_item_minutes") return q;
    if (wantsMaxLength && metric === "min_item_minutes") {
      return { ...q, metric: "max_item_minutes" };
    }
    if (wantsMinLength && metric === "max_item_minutes") {
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

  clearGhostClarificationIfConcrete(dto);

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
 */
function normalizeCompiledPromise(parsed, rawText, options = {}) {
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
  const blockedApps = mapAppRules(parsed.blockedApps, "BLOCK");
  const packageLabels = buildPackageLabelMap(parsed.allowedApps, parsed.blockedApps);

  const allowedMapped = mapContentItems(parsed.allowedContent, "ALLOW", packageLabels);
  const blockedMapped = mapContentItems(parsed.blockedContent, "BLOCK", packageLabels);

  let contentRules = dedupeContentRules([
    ...allowedMapped.rules,
    ...blockedMapped.rules,
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
    const fromRaw = parseMediaLengthThreshold(rawText);
    if (fromRaw) {
      contentRules = dedupeContentRules([
        ...contentRules,
        {
          appLabel: null,
          packageName: null,
          surface: "video",
          contentType: "long_form_video",
          operator: fromRaw.operator,
          value: fromRaw.value,
          unit: fromRaw.unit,
          action: "BLOCK",
          description: `videos ${fromRaw.operator} ${fromRaw.value} min`,
        },
      ]);
      blockedMapped.summaries.push(
        `videos longer than ${fromRaw.value} min → BLOCK`
      );
    }
  }

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

  const longFormAllowed = contentRules.some(
    (r) =>
      r.action === "ALLOW" &&
      (r.contentType === "long_form_video" || r.contentType === "study")
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

  const shortsClarify = inferShortFormScopeClarification(promiseText, parsed);
  if (shortsClarify && !googleClarify) {
    const optsMentionShorts = clarificationOptions.some((o) =>
      /short-form|shorts|reels|youtube shorts/i.test(
        `${o.label} ${o.description || ""} ${o.policyPreview || ""}`
      )
    );
    if (clarificationOptions.length === 0 || !optsMentionShorts) {
      clarificationOptions = normalizeClarificationOptions(shortsClarify.options);
    }
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
  if (selectedId) {
    const matched = (dto.clarificationOptions || []).find(
      (o) => String(o.id).toUpperCase() === selectedId.toUpperCase()
    );
    dto.selectedClarificationOptionId = selectedId;
    if (!matched) {
      if (
        shouldShowUnrecognizedClarificationChoice(
          selectedId,
          dto.clarificationOptions
        )
      ) {
        // Invalid option id must NOT unlock Start (CK-OPTION-MAP fail-closed).
        dto.clarificationRequired = true;
        dto.canStartCommitment = false;
        dto.optionRematerialized = false;
        dto.cautionMessages = uniqueStrings([
          ...dto.cautionMessages,
          UNRECOGNIZED_CLARIFICATION_CHOICE,
        ]);
      } else {
        dto.optionRematerialized = false;
        clearGhostClarificationIfConcrete(dto);
      }
    } else {
      const rematerialize = resolveOptionRematerialize(matched);
      const hasUsablePreview =
        Boolean(matched.policyPreview || matched.resultingPolicyPreview) ||
        Boolean(matched.internalPolicyPreview);
      if (!hasUsablePreview || !rematerialize) {
        // No inventing policy — Start stays blocked without rematerialize payload.
        dto.clarificationRequired = true;
        dto.canStartCommitment = false;
        dto.optionRematerialized = false;
        dto.cautionMessages = uniqueStrings([
          ...dto.cautionMessages,
          "That option has no enforceable policy preview. Pick another option or edit your promise.",
        ]);
      } else {
        const applied = rematerializeDtoFromOptionPreview(
          dto,
          rematerialize,
          promiseText
        );
        if (!applied) {
          dto.clarificationRequired = true;
          dto.canStartCommitment = false;
          dto.optionRematerialized = false;
          dto.cautionMessages = uniqueStrings([
            ...dto.cautionMessages,
            "Could not apply that option’s policy. Pick another option or edit your promise.",
          ]);
        } else {
          dto.clarificationRequired = false;
          dto.ambiguityLevel = "low";
          // User answered A/B/C — drop clarify-driven human confirm; keep permanent/transcript.
          if (dto.riskReview && typeof dto.riskReview === "object") {
            dto.riskReview = {
              ...dto.riskReview,
              requiresHumanConfirmation: permanent || lowTranscript,
              falseAllowRisk: "low",
            };
          }
          dto.requiresStrongerConfirmation = permanent || lowTranscript;
          dto.recommendedInterpretation =
            matched.policyPreview ||
            matched.resultingPolicyPreview ||
            matched.description ||
            matched.label;
          dto.cautionMessages = uniqueStrings([
            ...dto.cautionMessages,
            `Using your choice: ${matched.label}`,
          ]);
          // Soft-start still banned while stronger confirm required (permanent / low transcript).
          dto.canStartCommitment = !dto.requiresStrongerConfirmation;
        }
      }
    }
  }

  stripFalseUnrecognizedWarnings(dto, selectedId);
  clearGhostClarificationIfConcrete(dto);
  stripPackageLeaksFromDto(dto, promiseText);
  applyYouTubeContentOrAppScope(dto, promiseText);
  applyFailClosedStartGates(dto);
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
  if (namesYouTubeAppOnly(promiseText)) {
    dto.scopeKind = "youtube_app_only";
    dto.scopePackages = [PKG_YOUTUBE];
    dto.contentBrands = [];
    dto.surfaceScope = [];
    dto.contentScope = [];
    return;
  }
  if (!namesYouTubeContentBrand(promiseText)) return;

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
  const appSpecificOnly =
    /\b(youtube\s+shorts?\s+only|only\s+youtube\s+shorts?|instagram\s+reels?\s+only|only\s+ig\s+reels?)\b/.test(
      text
    );
  if (appSpecificOnly) return false;
  if (shortFormQuota) return true;
  if (
    contentRules.some((r) => r.contentType === "short_form_video") &&
    mentionsShorts &&
    !appSpecificOnly
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
    const thisBehavior = rule.action === "BLOCK" ? "BLOCK" : "ALLOW";
    // Mixed surfaces (allow DMs + block reels) → app stays ALLOW; contentRules do the nuance.
    const behavior =
      prev && prev.behavior !== thisBehavior ? "ALLOW" : thisBehavior;
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

async function compilePromise(rawPromise, options = {}) {
  const provider = getPromiseCompilerProvider();
  if (!provider) {
    const err = new Error(
      "No promise compiler configured: set Azure OpenAI env vars or OPENAI_API_KEY"
    );
    err.statusCode = 503;
    throw err;
  }

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

  const compileText = resolveCompoundCompileText(
    trimmed,
    selectedClarificationOptionId
  );
  const compilingSingleClause = compileText !== trimmed;

  const safe = redactForLog(compileText, 40);
  const started = Date.now();

  const userPayload = { userPromise: compileText };
  if (selectedClarificationOptionId && !compilingSingleClause) {
    userPayload.selectedClarificationOptionId = selectedClarificationOptionId;
  }

  let completion;
  let deploymentUsed = provider.deployment;
  try {
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
    });
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
};
