/**
 * Offline smoke for promiseCompilerService.normalizeCompiledPromise (no Azure).
 * Run: node backend/smoke_promise_compiler_normalize.js
 *
 * Guards FOUR CLOCKS + Promise Understanding v08 invariants.
 * Prints ASSERTIONS_PASSED=N on success.
 */
const assert = require("assert");
const service = require("./promiseCompilerService");
// Legacy-contract smoke: pinned to v07 so a v09 process env cannot change what it asserts.
// v09 behavior is covered by smoke_scope_contract_v1.js + smoke_v09_clarification_scope.js.
const normalizeCompiledPromise = (parsed, text, options = {}, trusted) =>
  service.normalizeCompiledPromise(parsed, text, { promptVersion: "promise_compiler_v07", ...options }, trusted);
const {
  parseShortFormLimitFromText,
  needsCompoundIntentClarification,
  resolveCompoundCompileText,
  durationToSessionMinutes,
  parseMediaLengthThreshold,
  DEFAULT_SESSION_MINUTES,
  CALENDAR_DAY_MINUTES,
  QUOTA_BOUNDARY,
  SHORT_FORM_PLATFORM_PACKAGES,
  PROMPT_VERSION,
  sanitizeUserFacingCopy,
  userFacingHasPackageLeak,
  isPermanentCommitment,
  isNormalPhoneExceptSafety,
  normalizeUserFacingConfirmation,
  normalizeRiskReview,
} = require("./promiseCompilerService");

let ASSERTIONS = 0;
function check(condition, message) {
  assert.ok(condition, message);
  ASSERTIONS += 1;
}
function eq(actual, expected, message) {
  assert.strictEqual(actual, expected, message);
  ASSERTIONS += 1;
}
function deepEq(actual, expected, message) {
  assert.deepStrictEqual(actual, expected, message);
  ASSERTIONS += 1;
}

/** What Android sends on a pick: the shown options snapshot + the tapped option. */
function pickFrom(turn1, id, extra = {}) {
  const snapshot = JSON.parse(JSON.stringify(turn1.clarificationOptions || []));
  return {
    selectedClarificationOptionId: id,
    priorClarificationOptionsSnapshot: snapshot,
    selectedClarificationOptionFullText: snapshot.find((o) => o.id === id) || null,
    ...extra,
  };
}

/** Turn 1 shows `parsed`'s options; turn 2 picks `id` from that snapshot. */
function pickAgainstOwnTurn1(parsed, promiseText, id) {
  const turn1 = normalizeCompiledPromise(structuredClone(parsed), promiseText);
  return normalizeCompiledPromise(structuredClone(parsed), promiseText, pickFrom(turn1, id));
}

function basePolicy(overrides = {}) {
  return {
    commitmentType: "focus_session",
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    startCondition: "immediate",
    strictnessLevel: "STRICT",
    allowedApps: [],
    blockedApps: [],
    allowedContent: [],
    blockedContent: [],
    activeGuardrails: [],
    quotas: [],
    strikePolicy: {
      warnBeforeLock: 0,
      strikesBeforeLock: 0,
      resetPeriod: "session",
    },
    lockPolicy: {
      enabled: false,
      durationMinutes: null,
      scope: "commitment_pause",
    },
    emergencyExceptions: [],
    tamperPolicy: {
      preventDisable: false,
      onTamper: "NONE",
      allowSettingsBrowse: true,
    },
    followUpQuestionRequired: false,
    followUpQuestion: null,
    rejectedUnsafeParts: [],
    confidence: 0.92,
    ...overrides,
  };
}

function assertNoPackageLeak(dto, label) {
  const blob = [
    dto.cleanedPromiseText,
    dto.userIntentSummary,
    dto.recommendedInterpretation,
    dto.clarificationQuestion,
    ...(dto.alternativeInterpretations || []),
    ...(dto.confirmationPreview || []),
    ...(dto.interpretationNotes || []),
    ...(dto.cautionMessages || []),
    ...(dto.conditionalSummaries || []),
    ...(dto.clarificationOptions || []).flatMap((o) => [
      o.label,
      o.description,
      o.policyPreview,
      o.resultingPolicyPreview,
    ]),
    JSON.stringify(dto.userFacingConfirmation || {}),
  ]
    .filter(Boolean)
    .join("\n");
  check(
    !userFacingHasPackageLeak(blob),
    `${label}: user-facing copy must not leak package IDs`
  );
}

// ========== Duration / clocks helpers ==========
eq(
  durationToSessionMinutes({ kind: "fixed", value: 3, unit: "hours" }).minutes,
  180
);
eq(
  durationToSessionMinutes({ kind: "fixed", value: 7, unit: "days" }).minutes,
  7 * 24 * 60
);
eq(
  durationToSessionMinutes({ kind: "fixed", value: 1, unit: "years" }).minutes,
  365 * 24 * 60
);
eq(
  durationToSessionMinutes({ kind: "none", value: null, unit: null }).minutes,
  DEFAULT_SESSION_MINUTES
);

deepEq(parseMediaLengthThreshold("videos over 40 min"), {
  operator: "gt",
  value: 40,
  unit: "minutes",
});
eq(parseMediaLengthThreshold("shorts and reels"), null);

// --- Clock B: session 1h + block videos longer than 40 ---
const KEY_PROMISE =
  "for 1 hour do not allow to watch any video whose length is greater than 40 min";

const keyExample = normalizeCompiledPromise(
  basePolicy({
    blockedContent: [
      {
        type: "long_form_video",
        description: "videos longer than 40 min",
        apps: [],
      },
    ],
    quotas: [{ metric: "entertainment_minutes", limit: 40, period: "session" }],
  }),
  KEY_PROMISE
);
eq(keyExample.sessionDurationMinutes, 60);
check(
  keyExample.contentRules.some(
    (r) =>
      r.action === "BLOCK" &&
      r.operator === "gt" &&
      r.value === 40 &&
      r.unit === "minutes"
  ),
  "expected BLOCK gt 40 media-length ContentRule"
);
deepEq(
  keyExample.conditionalSummaries.filter((s) =>
    /Entertainment budget/i.test(s)
  ),
  []
);
check(
  !keyExample.contentRules.some((r) => r.contentType === "usage_quota"),
  "must NOT emit usage_quota for media-length promises"
);

const azureQuotaOnly = normalizeCompiledPromise(
  basePolicy({
    blockedContent: [
      { type: "long_form_video", description: "long videos", apps: [] },
    ],
    quotas: [{ metric: "entertainment_minutes", limit: 40, period: "session" }],
  }),
  KEY_PROMISE
);
check(
  !azureQuotaOnly.conditionalSummaries.some((s) =>
    /Entertainment budget/i.test(s)
  )
);
check(
  azureQuotaOnly.contentRules.some(
    (r) => r.operator === "gt" && r.value === 40 && r.action === "BLOCK"
  )
);

for (const phrase of [
  "block videos longer than 40 min for 1 hour",
  "do not allow video length greater than 40 minutes for next 1 hour",
  "whose length is greater than 40 min — focus 1 hour",
]) {
  const dto = normalizeCompiledPromise(
    basePolicy({
      quotas: [{ metric: "entertainment_minutes", limit: 40, period: "session" }],
    }),
    phrase
  );
  check(
    !dto.conditionalSummaries.some((s) => /Entertainment budget/i.test(s)),
    `budget leaked for: ${phrase}`
  );
}

const fromDesc = normalizeCompiledPromise(
  basePolicy({
    allowedApps: [
      {
        packageName: "com.google.android.youtube",
        appLabel: "YouTube",
        scope: "long_videos",
      },
    ],
    allowedContent: [
      {
        type: "long_form_video",
        description: "videos over 40 min",
        apps: ["com.google.android.youtube"],
      },
    ],
    blockedContent: [
      {
        type: "short_form_video",
        description: "shorts and reels",
        apps: [],
      },
    ],
    activeGuardrails: ["no_short_form_video"],
  }),
  "only allow YouTube videos longer than 40 min for next 1 hour"
);
eq(fromDesc.sessionDurationMinutes, 60);
check(
  fromDesc.contentRules.some(
    (r) => r.action === "ALLOW" && r.operator === "gt" && r.value === 40
  )
);
check(
  fromDesc.contentRules.some(
    (r) => r.action === "BLOCK" && r.contentType === "short_form_video"
  )
);
check(
  fromDesc.contentBrands.includes("youtube") &&
    fromDesc.surfaceScope.includes("youtube") &&
    fromDesc.contentScope.includes("youtube"),
  "YouTube video promise uses content/surface brand"
);
eq(fromDesc.scopeKind, "youtube_content");
check(
  !fromDesc.scopePackages.includes("com.android.chrome") ||
    fromDesc.contentBrands.includes("youtube"),
  "YouTube content is not a Chrome-only package lock"
);
check(
  fromDesc.scopePackages.length === 0 ||
    !fromDesc.scopePackages.every((p) => p === "com.google.android.youtube"),
  "YouTube video must not exclusive-package the official app"
);

const youtubeAppOnlyDto = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    allowedContent: [
      {
        type: "long_form_video",
        description: "videos longer than 40 min",
        apps: ["com.google.android.youtube"],
      },
    ],
  }),
  "YouTube app only: only allow videos longer than 40 min for 1 hour"
);
eq(youtubeAppOnlyDto.scopeKind, "youtube_app_only");
check(
  youtubeAppOnlyDto.scopePackages.includes("com.google.android.youtube"),
  "YouTube app only keeps official package"
);
check(
  youtubeAppOnlyDto.contentBrands.length === 0,
  "YouTube app only has no content brand"
);

// --- Clock C: true usage budget ---
const budget = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 1, unit: "days", until: null },
    quotas: [{ metric: "entertainment_minutes", limit: 40, period: "day" }],
  }),
  "only 40 min entertainment today"
);
eq(budget.sessionDurationMinutes, 24 * 60);
check(budget.conditionalSummaries.some((s) => /Entertainment budget/i.test(s)));
check(budget.contentRules.some((r) => r.contentType === "usage_quota"));

// --- Shorts count quota ---
const shortsQuota = normalizeCompiledPromise(
  basePolicy({
    commitmentType: "quota_entertainment",
    duration: { kind: "none", value: null, unit: null, until: null },
    quotas: [{ metric: "shorts", limit: 40, period: "day" }],
  }),
  "allow 40 Shorts then stop"
);
check(shortsQuota.conditionalSummaries.some((s) => /first 40 short-form/i.test(s)));
check(
  shortsQuota.contentRules.some(
    (r) =>
      r.contentType === "short_form_video" &&
      r.action === "ALLOW" &&
      r.operator === "lte" &&
      r.value === 40
  )
);
check(
  shortsQuota.contentRules.some(
    (r) =>
      r.contentType === "short_form_video" &&
      r.action === "BLOCK" &&
      r.operator === "gt" &&
      r.value === 40
  )
);
eq(shortsQuota.timeWindowKind, "calendar_day");
check(shortsQuota.sessionDurationMinutes !== DEFAULT_SESSION_MINUTES);

// Model omitted quotas — lift messy spoken count ("shots" / "upto").
const messyShots = normalizeCompiledPromise(
  basePolicy({
    commitmentType: "quota_entertainment",
    duration: { kind: "none", value: null, unit: null, until: null },
    quotas: [],
  }),
  "allow me upto 20 shots then block"
);
check(messyShots.conditionalSummaries.some((s) => /first 20 short-form/i.test(s)));
check(
  messyShots.contentRules.some(
    (r) =>
      r.contentType === "short_form_video" &&
      r.action === "ALLOW" &&
      r.operator === "lte" &&
      r.value === 20
  )
);
check(
  messyShots.contentRules.some(
    (r) =>
      r.contentType === "short_form_video" &&
      r.action === "BLOCK" &&
      r.operator === "gt" &&
      r.value === 20
  )
);
const missingQuotasField = normalizeCompiledPromise(
  basePolicy({
    commitmentType: "quota_entertainment",
    duration: { kind: "none", value: null, unit: null, until: null },
    quotas: null,
  }),
  "only upto max 10 sorts"
);
check(
  missingQuotasField.contentRules.some(
    (r) => r.contentType === "short_form_video" && r.value === 10 && r.action === "ALLOW"
  )
);

eq(parseShortFormLimitFromText("allow me upto 20 shots"), 20);
eq(parseShortFormLimitFromText("10 sorts then stop"), 10);
eq(parseShortFormLimitFromText("watch calculus"), null);

check(
  needsCompoundIntentClarification("allow only calculus, allow only 10 shorts")
);
check(
  needsCompoundIntentClarification("allow only calculus lecture, monk 4 hours")
);
check(
  !needsCompoundIntentClarification(
    "I want to watch at most 10 shorts today, but never adult shorts"
  )
);
check(!needsCompoundIntentClarification("allow me upto 20 shots then block"));
eq(
  resolveCompoundCompileText(
    "allow only calculus lecture, monk 4 hours",
    "B"
  ),
  "allow only calculus lecture"
);

const twoWorlds = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 4, unit: "hours", until: null },
  }),
  "allow only calculus, allow only monk 4 hrs"
);
eq(twoWorlds.canStartCommitment, false);
eq(twoWorlds.clarificationOptions.length, 3);
check(/two different phone rules/i.test(twoWorlds.clarificationQuestion || ""));

const twoWorldsPicked = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 4, unit: "hours", until: null },
  }),
  "allow only calculus, allow only monk 4 hrs",
  pickFrom(twoWorlds, "A")
);
eq(twoWorldsPicked.canStartCommitment, true);
eq(twoWorldsPicked.optionRematerialized, true);

const unclear = normalizeCompiledPromise(
  basePolicy({
    followUpQuestionRequired: true,
    followUpQuestion: "Session length, daily quota, or max video length?",
    confidence: 0.3,
    duration: { kind: "none", value: null, unit: null, until: null },
  }),
  "40 min youtube"
);
check(Boolean(unclear.clarificationQuestion));

const liftRaw = normalizeCompiledPromise(
  basePolicy({ blockedContent: [], quotas: [] }),
  "for 1 hour block any video whose length is greater than 40 min"
);
check(
  liftRaw.contentRules.some(
    (r) => r.operator === "gt" && r.value === 40 && r.action === "BLOCK"
  )
);

// ========== Live shorts semantics ==========
const LIVE =
  "I want to watch at most 10 shorts today, but never adult/sexual shorts. After 10 shorts, block shorts for the rest of the day. Long educational YouTube should still be allowed.";

const liveDto = normalizeCompiledPromise(
  basePolicy({
    commitmentType: "quota_entertainment",
    duration: { kind: "none", value: null, unit: null, until: null },
    quotas: [{ metric: "shorts", limit: 10, period: "day" }],
    activeGuardrails: ["no_adult_content"],
    allowedContent: [
      {
        type: "long_form_video",
        description: "educational youtube",
        apps: ["com.google.android.youtube"],
      },
    ],
    blockedContent: [
      { type: "adult_sexual", description: "adult sexual shorts", apps: [] },
    ],
    userFacingConfirmation:
      "First 10 short-form today; adult always blocked; long educational YouTube allowed.",
    interpretationNotes: [
      "Interpreted shorts as all short-form video surfaces.",
    ],
  }),
  LIVE
);

eq(liveDto.timeWindowKind, "calendar_day");
eq(liveDto.sessionDurationMinutes, CALENDAR_DAY_MINUTES);
check(!liveDto.cautionMessages.some((m) => /defaulted to 60 minutes/i.test(m)));
eq(liveDto.quotaBoundary, QUOTA_BOUNDARY);
eq(QUOTA_BOUNDARY, "allow_first_n");
check(
  liveDto.contentRules.some(
    (r) => r.action === "ALLOW" && r.unit === "count" && r.value === 10
  )
);
check(
  liveDto.contentRules.some(
    (r) => r.action === "BLOCK" && r.operator === "gt" && r.value === 10
  )
);
check(
  liveDto.contentRules.some(
    (r) => r.contentType === "adult_sexual" && r.action === "BLOCK"
  )
);
check(liveDto.confirmationPreview.some((l) => /adult\/sexual/i.test(l)));
check(
  liveDto.contentRules.some(
    (r) =>
      r.action === "ALLOW" &&
      (r.contentType === "long_form_video" || r.contentType === "study")
  )
);

const pkgs = new Set(liveDto.suggestedAppRules.map((r) => r.packageName));
check(pkgs.has("org.schabi.newpipe"));
check(pkgs.has("com.instagram.android"));
check(pkgs.has("com.facebook.katana"));
check(pkgs.has("com.zhiliaoapp.musically"));
check(pkgs.has("com.android.chrome"));
check(pkgs.has("com.google.android.youtube"));
check(liveDto.scopePackages.includes("org.schabi.newpipe"));
check(SHORT_FORM_PLATFORM_PACKAGES.length >= 6);
check(liveDto.confirmationPreview.length >= 3);
assertNoPackageLeak(liveDto, "liveDto");

// Vague Google video
const vagueGoogleVideoClarify = normalizeCompiledPromise(
  basePolicy({
    followUpQuestionRequired: false,
    followUpQuestion: null,
    blockedContent: [{ type: "video", description: "google video", apps: [] }],
  }),
  "block Google video for 1 hour"
);
check(
  vagueGoogleVideoClarify.clarificationRequired,
  "Google video must require clarification"
);
check(
  vagueGoogleVideoClarify.clarificationOptions.length >= 2,
  "Google video options required"
);
check(
  Boolean(vagueGoogleVideoClarify.clarificationQuestion),
  "Google video question required"
);

const neso = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 3, unit: "hours", until: null },
    allowedContent: [
      { type: "study", description: "neso academy OS playlist", apps: [] },
    ],
    blockedContent: [
      { type: "short_form_video", description: "shorts", apps: [] },
    ],
    lockPolicy: {
      enabled: true,
      durationMinutes: null,
      scope: "commitment_pause",
    },
    followUpQuestionRequired: true,
    followUpQuestion:
      "Confirm the exact Neso Academy OS playlist/channel if known?",
    confidence: 0.55,
  }),
  "Only Neso Academy OS playlist for 3 hours, no shorts, lock me if I drift."
);
eq(neso.sessionDurationMinutes, 180);
eq(neso.timeWindowKind, "session_fixed");
check(Boolean(neso.clarificationQuestion));

const yearGuard = normalizeCompiledPromise(
  basePolicy({
    commitmentType: "permanent_guardrail",
    duration: { kind: "fixed", value: 1, unit: "years", until: null },
    activeGuardrails: [
      "no_adult_content",
      "no_dating_apps",
      "no_entertainment_installs",
    ],
    blockedContent: [
      { type: "adult_sexual", description: "porn", apps: [] },
      { type: "install", description: "adult chat flirt installs", apps: [] },
    ],
    tamperPolicy: {
      preventDisable: true,
      onTamper: "LOCK",
      allowSettingsBrowse: true,
    },
    strictnessLevel: "LOCKED",
  }),
  "For one year no porn, dating app, random girl chatting, or installing adult/chat/flirt apps. Rest phone normal."
);
eq(yearGuard.timeWindowKind, "permanent");
check(yearGuard.contentRules.some((r) => r.contentType === "adult_sexual"));
check(yearGuard.isPermanentCommitment, "1 year → permanent");
check(
  yearGuard.requiresStrongerConfirmation,
  "1 year no porn → stronger confirm"
);
check(
  yearGuard.riskReview.requiresHumanConfirmation,
  "permanent riskReview requires human confirm"
);
check(
  yearGuard.internalPolicy.recoveryRules.requiresExplicitConfirm,
  "recoveryRules stronger for permanent"
);
eq(yearGuard.canStartCommitment, false, "permanent never soft-starts");

const emergencyKept = normalizeCompiledPromise(
  basePolicy({
    emergencyExceptions: [
      { type: "calls", detail: "Emergency and family calls" },
    ],
    activeGuardrails: ["no_adult_content"],
  }),
  "no porn but keep emergency calls"
);
check(
  emergencyKept.emergencyExceptions.some((e) => e.type === "calls"),
  "CK-EMERGENCY: exceptions preserved through normalize"
);

const ig = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 7, unit: "days", until: null },
    allowedContent: [
      {
        type: "social_dm",
        description: "college replies",
        apps: ["com.instagram.android"],
      },
    ],
    blockedContent: [
      {
        type: "short_form_video",
        description: "reels",
        apps: ["com.instagram.android"],
      },
      {
        type: "social_feed",
        description: "explore stories model pages",
        apps: ["com.instagram.android"],
      },
    ],
    allowedApps: [
      {
        packageName: "com.instagram.android",
        appLabel: "Instagram",
        scope: "college_dm",
      },
    ],
  }),
  "Instagram only for college replies for 7 days, no reels/explore/model pages/stories."
);
check(ig.contentRules.some((r) => r.contentType === "social_dm"));

const vague = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "none", value: null, unit: null, until: null },
    confidence: 0.2,
  }),
  "study only"
);
check(Boolean(vague.clarificationQuestion));

const mediaOnly = normalizeCompiledPromise(
  basePolicy({
    quotas: [{ metric: "entertainment_minutes", limit: 40, period: "session" }],
  }),
  "block videos longer than 40 min"
);
check(
  !mediaOnly.conditionalSummaries.some((s) => /Entertainment budget/i.test(s))
);

const hinglish = normalizeCompiledPromise(
  basePolicy({
    commitmentType: "quota_entertainment",
    duration: { kind: "none", value: null, unit: null, until: null },
    quotas: [{ metric: "shorts", limit: 10, period: "day" }],
    activeGuardrails: ["no_adult_content"],
  }),
  "aaj max 10 shorts, adult wale bilkul nahi, baaki lecture chalne do"
);
eq(hinglish.timeWindowKind, "calendar_day");
eq(hinglish.detectedLanguage, "hinglish");

let threw = false;
try {
  normalizeCompiledPromise({ commitmentType: "focus_session" }, "x");
} catch (err) {
  threw = true;
  eq(err.statusCode, 502);
}
check(threw, "expected invalid model to throw 502");

check(
  PROMPT_VERSION.includes("v06") ||
    PROMPT_VERSION.includes("v07") ||
    PROMPT_VERSION.includes("v08") ||
    PROMPT_VERSION.includes("v09"),
  `expected v06/v07/v08/v09 prompt, got ${PROMPT_VERSION}`
);

// ========== v08: package leak strip ==========
eq(userFacingHasPackageLeak("videos (com.google.android.youtube)"), true);
eq(
  userFacingHasPackageLeak(
    sanitizeUserFacingCopy("videos (com.google.android.youtube)")
  ),
  false
);

const googleVideoLength = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    quotas: [{ metric: "min_item_minutes", limit: 30, period: "item" }],
    confidence: 0.35,
    followUpQuestionRequired: false,
  }),
  "don't let me watch google video less than 30 min for 1 hour"
);
check(googleVideoLength.clarificationRequired);
check(googleVideoLength.clarificationOptions.length >= 2);
eq(googleVideoLength.canStartCommitment, false);
eq(googleVideoLength.sessionDurationMinutes, 60);
check(
  googleVideoLength.contentRules.some(
    (r) =>
      (r.operator === "lt" || r.operator === "gt") &&
      r.value === 30 &&
      r.unit === "minutes"
  ) ||
    googleVideoLength.contentRules.some(
      (r) => r.value === 30 && r.unit === "minutes"
    ),
  "media threshold 30m present"
);
for (const opt of googleVideoLength.clarificationOptions) {
  check(!userFacingHasPackageLeak(opt.label));
  check(!userFacingHasPackageLeak(opt.description || ""));
  check(Boolean(opt.policyPreview || opt.resultingPolicyPreview));
}

const leaky = normalizeCompiledPromise(
  basePolicy({
    userFacingConfirmation: {
      understood: "Allowed: long videos (com.google.android.youtube)",
      allowed: ["long videos (org.schabi.newpipe)"],
      blocked: [],
      time: "1 hour",
      appliesTo: "YouTube (com.google.android.youtube)",
      checkThis: ["scope includes com.android.chrome"],
      safetyNotes: [],
    },
    allowedContent: [
      {
        type: "long_form_video",
        description: "long videos",
        apps: ["com.google.android.youtube"],
      },
    ],
  }),
  "allow long youtube videos for 1 hour"
);
check(
  !userFacingHasPackageLeak(JSON.stringify(leaky.userFacingConfirmation || {})),
  "userFacingConfirmation must not leak packages"
);
assertNoPackageLeak(leaky, "leaky");

// ========== v08: high ambiguity → clarificationRequired ==========
const highAmb = normalizeCompiledPromise(
  basePolicy({
    ambiguityLevel: "high",
    clarificationRequired: false,
    followUpQuestionRequired: false,
    followUpQuestion: null,
    confidence: 0.2,
    duration: { kind: "none", value: null, unit: null, until: null },
  }),
  "maybe do something about videos"
);
eq(highAmb.ambiguityLevel, "high");
check(
  highAmb.clarificationRequired,
  "high ambiguity → clarificationRequired"
);
eq(highAmb.canStartCommitment, false);

// ========== v08: 10 shorts today → no forced clarify, interpretation note ==========
const tenShorts = normalizeCompiledPromise(
  basePolicy({
    commitmentType: "quota_entertainment",
    duration: { kind: "none", value: null, unit: null, until: null },
    ambiguityLevel: "none",
    clarificationRequired: false,
    quotas: [{ metric: "shorts", limit: 10, period: "day" }],
    interpretationNotes: [
      "I treated shorts as all short-form video surfaces. Change?",
    ],
  }),
  "watch 10 shorts today"
);
eq(tenShorts.clarificationRequired, false);
eq(tenShorts.canStartCommitment, true);
eq(tenShorts.timeWindowKind, "calendar_day");
check(tenShorts.sessionDurationMinutes !== DEFAULT_SESSION_MINUTES);
check(
  tenShorts.interpretationNotes.some((n) => /short-form/i.test(n)) ||
    (tenShorts.userFacingConfirmation.checkThis || []).some((n) =>
      /short-form|shorts/i.test(n)
    ),
  "10 shorts → interpretation note, not forced clarify"
);
check(
  !tenShorts.cautionMessages.some((m) => /defaulted to 60 minutes/i.test(m)),
  "watch 10 shorts today → no invented 1h session"
);

// ========== v08: normal phone except porn → not study mode ==========
check(
  isNormalPhoneExceptSafety(
    "keep my normal phone except no porn for one year"
  ),
  "detector: normal phone except porn"
);
const normalPhone = normalizeCompiledPromise(
  basePolicy({
    commitmentType: "monk_mode",
    duration: { kind: "fixed", value: 1, unit: "years", until: null },
    activeGuardrails: [
      "no_adult_content",
      "no_social_feed",
      "no_short_form_video",
      "no_entertainment_installs",
    ],
    blockedContent: [
      { type: "adult_sexual", description: "porn", apps: [] },
    ],
  }),
  "normal phone except porn for 1 year"
);
eq(normalPhone.commitmentType, "permanent_guardrail");
check(
  !["monk_mode", "focus_session"].includes(normalPhone.commitmentType),
  "normal phone except porn → not study mode"
);
check(normalPhone.isPermanentCommitment);
check(
  (normalPhone.internalPolicy.guardrails || []).includes("no_adult_content"),
  "adult guardrail kept after repair"
);
check(
  !(normalPhone.internalPolicy.guardrails || []).includes("no_social_feed"),
  "social feed ban stripped for normal-phone-except-porn"
);

// ========== v08: video less than 30 min for 1 hour ==========
const mediaSession = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    quotas: [{ metric: "min_item_minutes", limit: 30, period: "item" }],
  }),
  "only allow video less than 30 min for 1 hour"
);
eq(mediaSession.sessionDurationMinutes, 60);
check(
  mediaSession.contentRules.some(
    (r) =>
      r.action === "BLOCK" &&
      r.operator === "gt" &&
      r.value === 30 &&
      r.unit === "minutes"
  ),
  "media threshold 30m as BLOCK gt (max length), not inverted lt"
);
check(
  mediaSession.timeWindowKind === "session_fixed" ||
    mediaSession.sessionDurationMinutes === 60,
  "time window is 1h session not 30m"
);

// ========== v08 schema-ish shape ==========
const shape = normalizeCompiledPromise(
  basePolicy({
    cleanedPromiseText: "Focus for one hour",
    detectedLanguage: "en",
    transcriptConfidence: 0.55,
    userIntentSummary: "Block distractions for an hour",
    ambiguityLevel: "low",
    alternativeInterpretations: ["Maybe a daily quota instead"],
    riskReview: {
      falseAllowRisk: "low",
      falseBlockRisk: "medium",
      safetyRisk: "none",
      requiresHumanConfirmation: false,
    },
    internalPolicy: {
      timeWindow: { kind: "session_fixed", minutes: 60 },
      surfaceRules: [],
      contentRules: [],
      appRules: [],
      quotaRules: [],
      recoveryRules: { hardness: "normal", requiresExplicitConfirm: false },
      guardrails: [],
    },
    userFacingConfirmation: {
      understood: "I understood: one hour focus",
      allowed: ["study apps"],
      blocked: ["short videos"],
      time: "1 hour",
      appliesTo: "named apps",
      checkThis: ["Voice transcript uncertain"],
      safetyNotes: [],
    },
    hiddenInternalScope: ["com.google.android.youtube"],
  }),
  "focus for 1 hour no shorts"
);
check(typeof shape.cleanedPromiseText === "string");
check(typeof shape.detectedLanguage === "string");
check(shape.transcriptConfidence === 0.55);
check(typeof shape.userIntentSummary === "string");
check(Array.isArray(shape.alternativeInterpretations));
check(shape.userFacingConfirmation.understood || shape.userFacingConfirmation.understoodSummary);
check(Array.isArray(shape.userFacingConfirmation.allowed || shape.userFacingConfirmation.allowedBullets));
check(Array.isArray(shape.userFacingConfirmation.checkThis));
check(shape.internalPolicy && typeof shape.internalPolicy === "object");
check(Array.isArray(shape.internalPolicy.surfaceRules));
check(Array.isArray(shape.internalPolicy.appRules));
check(shape.riskReview && typeof shape.riskReview.falseAllowRisk === "string");
check(Array.isArray(shape.hiddenInternalScope));
check(
  (shape.userFacingConfirmation.checkThis || []).some((n) =>
    /uncertain|transcript/i.test(n)
  ) ||
    shape.transcriptConfidence < 0.7,
  "voice uncertainty surfaced"
);
assertNoPackageLeak(shape, "shape");

// Dual v07/v08 confirmation normalize
const dual = normalizeUserFacingConfirmation({
  understood: "Hello com.google.android.youtube",
  allowed: ["ok"],
  blocked: [],
  time: "today",
  appliesTo: "shorts",
  checkThis: [],
  safetyNotes: [],
});
eq(dual.understoodSummary, dual.understood);
check(!userFacingHasPackageLeak(dual.understood));
check(Array.isArray(dual.allowedBullets));

const risk = normalizeRiskReview(null, { requiresHumanConfirmation: true });
eq(risk.requiresHumanConfirmation, true);

check(
  isPermanentCommitment(
    { commitmentType: "permanent_guardrail", duration: {} },
    "x"
  )
);

// Temporal: until_clock still maps without inventing day
const untilClock = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "until_clock", value: null, unit: null, until: "22:00" },
  }),
  "block reels until 10pm"
);
eq(untilClock.timeWindowKind, "until_clock");

// Permanent from text even if model forgot type
check(
  isPermanentCommitment(
    { commitmentType: "focus_session", duration: { kind: "fixed", value: 1, unit: "hours" } },
    "no porn for 1 year"
  )
);

// Selected clarification option rematerializes enforceable scope (not preview-only)
const afterPick = pickAgainstOwnTurn1(
  basePolicy({
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    quotas: [{ metric: "min_item_minutes", limit: 30, period: "item" }],
    confidence: 0.3,
    followUpQuestionRequired: true,
    clarificationOptions: [
      {
        id: "A",
        label: "Videos inside Chrome / browser",
        description: "Chrome",
        recommended: true,
        resultingPolicyPreview: "Apply length rules in Chrome for 1 hour",
      },
      {
        id: "B",
        label: "All video apps on my phone",
        description: "All apps",
        recommended: false,
        policyPreview: "All video apps follow length rules",
      },
      {
        id: "C",
        label: "YouTube only",
        description: "YT",
        recommended: false,
        policyPreview: "Only YouTube follows these rules for this commitment.",
      },
    ],
  }),
  "don't let me watch google video less than 30 min for 1 hour",
  "A"
);
eq(afterPick.clarificationRequired, false);
eq(afterPick.canStartCommitment, true);
eq(afterPick.selectedClarificationOptionId, "A");
check(afterPick.optionRematerialized, "option rematerialize flag");
check(
  afterPick.scopePackages.includes("com.android.chrome"),
  "Chrome rematerialize scope"
);
check(
  afterPick.contentRules.some((r) => r.packageName === "com.android.chrome"),
  "Chrome stamped onto contentRules"
);

const afterPickYt = pickAgainstOwnTurn1(
  basePolicy({
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    quotas: [{ metric: "min_item_minutes", limit: 30, period: "item" }],
    confidence: 0.3,
    followUpQuestionRequired: true,
    clarificationOptions: [
      {
        id: "A",
        label: "Videos inside Chrome / browser",
        description: "Chrome",
        recommended: true,
        policyPreview: "Apply length rules in Chrome for 1 hour",
      },
      {
        id: "C",
        label: "YouTube only",
        description: "YT",
        recommended: false,
        policyPreview: "Only YouTube follows these rules for this commitment.",
      },
    ],
  }),
  "don't let me watch google video less than 30 min for 1 hour",
  "C"
);
check(
  afterPickYt.scopePackages.includes("com.google.android.youtube") &&
    !afterPickYt.scopePackages.includes("com.android.chrome"),
  "YouTube-only rematerialize differs from Chrome"
);
check(
  afterPickYt.contentBrands.length === 0 &&
    (afterPickYt.scopeKind === "youtube_app_only" ||
      afterPickYt.optionScopeKind === "youtube_only" ||
      afterPickYt.optionScopeKind === "youtube_app_only"),
  "YouTube-only rematerialize stays package-exclusive"
);
assertNoPackageLeak(afterPickYt, "afterPickYt");
assertNoPackageLeak(afterPick, "afterPickChrome");

const noPreviewPick = pickAgainstOwnTurn1(
  basePolicy({
    followUpQuestionRequired: true,
    clarificationOptions: [
      { id: "A", label: "Mystery", description: "no preview", recommended: true },
    ],
  }),
  "be strict for exam mode tonight",
  "A"
);
eq(noPreviewPick.canStartCommitment, false, "missing preview cannot start");
eq(noPreviewPick.clarificationRequired, true);

const shortsAllPick = pickAgainstOwnTurn1(
  basePolicy({
    followUpQuestionRequired: true,
    clarificationOptions: [
      {
        id: "A",
        label: "All short-form surfaces",
        description: "everywhere",
        recommended: true,
        policyPreview:
          "All short-form video surfaces follow this commitment (not YouTube only).",
      },
    ],
  }),
  "shorts later maybe",
  "A"
);
check(shortsAllPick.scopePackages.length >= 5, "shorts all-surfaces expands");
check(shortsAllPick.optionRematerialized);

// Shorts quota must not keep no_short_form_video hard guardrail internally
const quotaNoHardBan = normalizeCompiledPromise(
  basePolicy({
    commitmentType: "quota_entertainment",
    duration: { kind: "none", value: null, unit: null, until: null },
    quotas: [{ metric: "shorts", limit: 10, period: "day" }],
    activeGuardrails: ["no_adult_content", "no_short_form_video"],
    allowedContent: [
      { type: "long_form_video", description: "lectures", apps: [] },
    ],
  }),
  "10 shorts today no adult long lectures ok"
);
check(
  !(quotaNoHardBan.internalPolicy.guardrails || []).includes(
    "no_short_form_video"
  ),
  "shorts quota must strip no_short_form_video hard guardrail"
);

// Concrete clocks + no options must not stay locked, and must not invent
// "choice was not recognized" unless the user picked a real invalid optionId.
const messyLonger50 =
  "allow video longer than 50 min for one hours shorter video block";
const ghostConcrete = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    ambiguityLevel: "high",
    clarificationRequired: true,
    followUpQuestionRequired: true,
    followUpQuestion: null,
    clarificationOptions: [],
    allowedContent: [
      {
        type: "long_form_video",
        description: "videos longer than 50 minutes",
      },
    ],
    blockedContent: [
      {
        type: "long_form_video",
        description: "videos shorter than 50 minutes",
      },
    ],
    userFacingConfirmation: {
      understood:
        "For one hour, only videos longer than 50 minutes are allowed; shorter videos are blocked.",
      allowed: ["videos longer than 50 minutes"],
      blocked: ["shorter videos"],
      time: "1 hour",
      appliesTo: "video apps",
      checkThis: [
        "That clarification choice was not recognized. Pick an option again.",
      ],
      safetyNotes: [],
    },
    interpretationNotes: [
      "That clarification choice was not recognized. Pick an option again.",
    ],
  }),
  messyLonger50
);
eq(ghostConcrete.sessionDurationMinutes, 60, "messy one hours → 60");
eq(ghostConcrete.clarificationRequired, false, "concrete clocks clear ghost clarify");
eq(ghostConcrete.canStartCommitment, true, "concrete clocks can start");
check(
  !(ghostConcrete.clarificationOptions || []).length,
  "no leftover options"
);
check(
  !(ghostConcrete.cautionMessages || []).some((s) =>
    /clarification choice was not recognized/i.test(s)
  ),
  "unrecognized warning absent without invalid pick"
);
check(
  !((ghostConcrete.userFacingConfirmation || {}).checkThis || []).some((s) =>
    /clarification choice was not recognized/i.test(s)
  ),
  "checkThis must not keep unrecognized warning without invalid pick"
);

// Reliability design 2026-09-25 §Fix 1: a leftover `selectedClarificationOptionId`
// with no fresh options AND no carry-through payload MUST fail closed. This
// used to fall into `clearGhostClarificationIfConcrete` and silently unlock
// Start on whatever narrower scope the model happened to produce on turn 2 —
// that is the "silently swapped to Browser videos only" bug. Never invent
// a scope the user did not confirm.
const leftoverIdNoOptionsNoCarryFailsClosed = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    clarificationRequired: true,
    clarificationOptions: [],
    allowedContent: [
      { type: "long_form_video", description: "videos longer than 50 minutes" },
    ],
    blockedContent: [
      { type: "long_form_video", description: "videos shorter than 50 minutes" },
    ],
  }),
  messyLonger50,
  { selectedClarificationOptionId: "A" }
);
eq(
  leftoverIdNoOptionsNoCarryFailsClosed.canStartCommitment,
  false,
  "leftover id + no options + no carry-through must NOT unlock Start"
);
eq(
  leftoverIdNoOptionsNoCarryFailsClosed.clarificationRequired,
  true,
  "leftover id + no options + no carry-through must re-clarify"
);
eq(
  leftoverIdNoOptionsNoCarryFailsClosed.optionVerification,
  "unrecognized_fail_closed",
  "unmatched id logs unrecognized_fail_closed"
);
check(
  (leftoverIdNoOptionsNoCarryFailsClosed.cautionMessages || []).some((s) =>
    /clarification choice was not recognized/i.test(s)
  ),
  "leftover id without carry-through emits unrecognized warning"
);

// Carry-through happy path: same shape, but the client echoed the exact option
// (full text + internalPolicyPreview). Server must apply it deterministically
// and Start must unlock without re-deriving scope from the model.
const leftoverIdWithCarryVerifies = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    clarificationRequired: true,
    clarificationOptions: [],
    allowedContent: [
      { type: "long_form_video", description: "videos longer than 50 minutes" },
    ],
    blockedContent: [
      { type: "long_form_video", description: "videos shorter than 50 minutes" },
    ],
  }),
  messyLonger50,
  {
    selectedClarificationOptionId: "A",
    selectedClarificationOptionFullText: {
      id: "A",
      label: "Videos inside Chrome / browser",
      description: "Chrome",
      recommended: true,
      policyPreview: "Apply length rules in Chrome for 1 hour",
      internalPolicyPreview: {
        scopeKind: "chrome_browser",
        appliesTo: "Chrome / browser video",
        packages: [
          { packageName: "com.android.chrome", appLabel: "Chrome" },
        ],
      },
    },
  }
);
eq(
  leftoverIdWithCarryVerifies.canStartCommitment,
  true,
  "carry-through with internalPolicyPreview unlocks Start"
);
eq(
  leftoverIdWithCarryVerifies.clarificationRequired,
  false,
  "carry-through clears clarification"
);
eq(
  leftoverIdWithCarryVerifies.optionVerification,
  "carried_full_text",
  "carry-through logs carried_full_text"
);
check(
  leftoverIdWithCarryVerifies.scopePackages.includes("com.android.chrome"),
  "carry-through rematerializes the exact scope the user picked"
);
check(
  !(leftoverIdWithCarryVerifies.cautionMessages || []).some((s) =>
    /clarification choice was not recognized/i.test(s)
  ),
  "carry-through must not surface unrecognized warning"
);

// Snapshot fallback: no carry-through object but a matching snapshot entry.
const leftoverIdWithSnapshotVerifies = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    clarificationRequired: true,
    clarificationOptions: [],
    allowedContent: [
      { type: "long_form_video", description: "videos longer than 50 minutes" },
    ],
    blockedContent: [
      { type: "long_form_video", description: "videos shorter than 50 minutes" },
    ],
  }),
  messyLonger50,
  {
    selectedClarificationOptionId: "A",
    priorClarificationOptionsSnapshot: [
      {
        id: "A",
        label: "All video apps and browsers",
        description: "Apply length rules everywhere",
        recommended: true,
        policyPreview: "Applies to major video apps and browser video",
        internalPolicyPreview: {
          scopeKind: "all_video_apps",
          appliesTo: "Major video apps and browser video",
          packages: [
            { packageName: "com.google.android.youtube", appLabel: "YouTube" },
            { packageName: "com.android.chrome", appLabel: "Chrome" },
          ],
        },
      },
    ],
  }
);
eq(
  leftoverIdWithSnapshotVerifies.canStartCommitment,
  true,
  "snapshot verify unlocks Start"
);
eq(
  leftoverIdWithSnapshotVerifies.optionVerification,
  "snapshot_verified",
  "snapshot fallback logs snapshot_verified"
);
check(
  leftoverIdWithSnapshotVerifies.scopePackages.includes(
    "com.google.android.youtube"
  ),
  "snapshot rematerialize preserves original scope"
);

// Carry-through with a MALFORMED internalPolicyPreview must fail closed —
// never invent a fallback. Old client would still land here.
const leftoverIdWithMalformedCarryFailsClosed = normalizeCompiledPromise(
  basePolicy({
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    clarificationRequired: true,
    clarificationOptions: [],
    allowedContent: [
      { type: "long_form_video", description: "videos longer than 50 minutes" },
    ],
    blockedContent: [
      { type: "long_form_video", description: "videos shorter than 50 minutes" },
    ],
  }),
  messyLonger50,
  {
    selectedClarificationOptionId: "A",
    selectedClarificationOptionFullText: {
      id: "A",
      label: "Mystery option with no preview",
      description: "",
      recommended: false,
    },
  }
);
eq(
  leftoverIdWithMalformedCarryFailsClosed.canStartCommitment,
  false,
  "malformed carry-through preview cannot start"
);
eq(
  leftoverIdWithMalformedCarryFailsClosed.optionVerification,
  "unrecognized_fail_closed",
  "malformed carry-through logs unrecognized_fail_closed"
);

const hallucinatedAdultOnWhatsapp = normalizeCompiledPromise(
  basePolicy({
    blockedApps: [
      {
        packageName: "com.whatsapp",
        appLabel: "WhatsApp",
        scope: "messaging",
      },
    ],
    activeGuardrails: ["no_adult_content"],
    blockedContent: [
      {
        type: "adult_sexual",
        description: "adult content",
        apps: [],
      },
    ],
  }),
  "do not allow whatsapp for 10 minutes"
);
const hallucinatedAdultCopy = JSON.stringify({
  confirmation: hallucinatedAdultOnWhatsapp.userFacingConfirmation,
  blocked: hallucinatedAdultOnWhatsapp.blockedSummaries,
  preview: hallucinatedAdultOnWhatsapp.confirmationPreview,
  guardrails: hallucinatedAdultOnWhatsapp.internalPolicy.guardrails,
});
check(
  !/adult|sexual|porn|nsfw|18\+/i.test(hallucinatedAdultCopy),
  "unrelated app-block promise must not show hallucinated adult guardrail"
);
check(
  !(hallucinatedAdultOnWhatsapp.internalPolicy.guardrails || []).includes(
    "no_adult_content"
  ),
  "unrelated app-block promise must not store hallucinated adult guardrail"
);

const explicitAdultGuardrail = normalizeCompiledPromise(
  basePolicy({
    commitmentType: "permanent_guardrail",
    duration: { kind: "permanent", value: null, unit: null, until: null },
    activeGuardrails: ["no_adult_content"],
    blockedContent: [
      {
        type: "adult_sexual",
        description: "porn and adult content",
        apps: [],
      },
    ],
  }),
  "no porn for 1 year"
);
check(
  /adult|porn|sexual/i.test(
    JSON.stringify({
      confirmation: explicitAdultGuardrail.userFacingConfirmation,
      blocked: explicitAdultGuardrail.blockedSummaries,
      guardrails: explicitAdultGuardrail.internalPolicy.guardrails,
    })
  ),
  "explicit adult safety promise still keeps adult guardrail copy"
);

// ========== Phone P0 2026-09-25: media-length direction never collapses to min=max ==========
function lengthSides(dto) {
  return dto.contentRules
    .filter((r) => r.unit === "minutes" && r.contentType !== "usage_quota")
    .map((r) => `${r.action}:${r.operator}:${r.value}`);
}
const noShorterThan30 = normalizeCompiledPromise(
  basePolicy({
    quotas: [{ metric: "min_item_minutes", limit: 30, period: "item" }],
    blockedContent: [
      { type: "short_form_video", description: "videos shorter than 30 min", apps: [] },
    ],
  }),
  "do not allow videos shorter than 30 minutes for next 1 hour"
);
check(lengthSides(noShorterThan30).includes("BLOCK:lt:30"), "block-shorter keeps lt 30");
check(
  !lengthSides(noShorterThan30).some((s) => /BLOCK:gte?:/.test(s)),
  `block-shorter must not add a max rule, got ${lengthSides(noShorterThan30)}`
);

const allowOnlyLonger30 = normalizeCompiledPromise(
  basePolicy({
    quotas: [{ metric: "max_item_minutes", limit: 30, period: "item" }],
  }),
  "allow only videos longer than 30 minutes"
);
check(lengthSides(allowOnlyLonger30).includes("BLOCK:lt:30"), "allow-only-longer → lt 30");
check(
  !lengthSides(allowOnlyLonger30).some((s) => /BLOCK:gte?:/.test(s)),
  `allow-only-longer must not keep a max rule, got ${lengthSides(allowOnlyLonger30)}`
);

const blockLonger30 = normalizeCompiledPromise(
  basePolicy({
    quotas: [{ metric: "min_item_minutes", limit: 30, period: "item" }],
  }),
  "block videos longer than 30 minutes"
);
check(lengthSides(blockLonger30).includes("BLOCK:gt:30"), "block-longer → gt 30");
check(
  !lengthSides(blockLonger30).some((s) => /BLOCK:lte?:/.test(s)),
  `block-longer must not keep a min rule, got ${lengthSides(blockLonger30)}`
);

const misencodedEntertainment = normalizeCompiledPromise(
  basePolicy({
    quotas: [{ metric: "entertainment_minutes", limit: 30, period: "session" }],
  }),
  "do not allow videos shorter than 30 minutes for next 1 hour"
);
deepEq(
  lengthSides(misencodedEntertainment),
  ["BLOCK:lt:30"],
  "mis-encoded entertainment quota repaired in the promise's direction"
);

const {
  inferMediaLengthIntent,
  reconcileMediaLengthRules,
} = require("./promiseCompilerService");
deepEq(inferMediaLengthIntent("do not allow videos shorter than 30 minutes"), {
  minBlock: 30,
  maxBlock: null,
  exact: false,
});
deepEq(inferMediaLengthIntent("allow only videos shorter than 20 min"), {
  minBlock: null,
  maxBlock: 20,
  exact: false,
});
deepEq(inferMediaLengthIntent("only exactly 30 minutes videos"), {
  minBlock: 30,
  maxBlock: 30,
  exact: true,
});
const silentCollision = reconcileMediaLengthRules(
  [
    { action: "BLOCK", operator: "lt", value: 30, unit: "minutes", contentType: "long_form_video", surface: "video" },
    { action: "BLOCK", operator: "gt", value: 30, unit: "minutes", contentType: "long_form_video", surface: "video" },
  ],
  "focus for 1 hour"
);
eq(silentCollision.rules.length, 1, "min=max collision without text keeps one side");
eq(silentCollision.rules[0].operator, "lt", "collision keeps block-shorter");

console.log(`ASSERTIONS_PASSED=${ASSERTIONS}`);
console.log(
  JSON.stringify({
    ok: true,
    event: "promise_compiler_normalize_smoke",
    promptVersion: PROMPT_VERSION,
    quotaBoundary: QUOTA_BOUNDARY,
    assertionsPassed: ASSERTIONS,
    keyExampleSessionMinutes: keyExample.sessionDurationMinutes,
    liveExample: {
      timeWindowKind: liveDto.timeWindowKind,
      sessionMinutes: liveDto.sessionDurationMinutes,
      scopePackages: liveDto.scopePackages.length,
      previewLines: liveDto.confirmationPreview.length,
    },
  })
);

if (ASSERTIONS < 50) {
  console.error(`Expected at least 50 assertions, got ${ASSERTIONS}`);
  process.exit(1);
}
