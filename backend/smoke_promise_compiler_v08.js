/**
 * Extra v08-focused smoke assertions (offline, no Azure).
 * Run: node backend/smoke_promise_compiler_v08.js
 *
 * Confidence Keys CK-01..CK-12 proved here + normalize smoke + Android unit tests.
 */
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const service = require("./promiseCompilerService");
// v08-contract smoke: pinned so a v09 process env cannot change what it asserts.
const normalizeCompiledPromise = (parsed, text, options = {}, trusted) =>
  service.normalizeCompiledPromise(parsed, text, { promptVersion: "promise_compiler_v08", ...options }, trusted);
const {
  sanitizeUserFacingCopy,
  userFacingHasPackageLeak,
  ALLOWED_PROMPT_VERSIONS,
  DEFAULT_SESSION_MINUTES,
  CALENDAR_DAY_MINUTES,
} = require("./promiseCompilerService");

let ASSERTIONS = 0;
function check(c, m) {
  assert.ok(c, m);
  ASSERTIONS += 1;
}
function eq(a, e, m) {
  assert.strictEqual(a, e, m);
  ASSERTIONS += 1;
}

function base(overrides = {}) {
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
    confidence: 0.9,
    ...overrides,
  };
}

function userFacingBlob(dto) {
  return [
    dto.cleanedPromiseText,
    dto.userIntentSummary,
    dto.recommendedInterpretation,
    dto.clarificationQuestion,
    ...(dto.alternativeInterpretations || []),
    ...(dto.allowedSummaries || []),
    ...(dto.blockedSummaries || []),
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
}

// Prompt + schema files exist
const promptPath = path.join(
  __dirname,
  "..",
  "evals",
  "prompts",
  "promise_compiler_v08.txt"
);
const schemaPath = path.join(
  __dirname,
  "..",
  "evals",
  "schemas",
  "promise_understanding_v08.schema.json"
);
check(fs.existsSync(promptPath), "v08 prompt exists");
check(fs.existsSync(schemaPath), "v08 schema exists");
check(
  ALLOWED_PROMPT_VERSIONS.has("promise_compiler_v08"),
  "v08 in ALLOWED_PROMPT_VERSIONS"
);

const schema = JSON.parse(fs.readFileSync(schemaPath, "utf8"));
check(schema.required.includes("cleanedPromiseText"));
check(schema.required.includes("userFacingConfirmation"));
check(schema.required.includes("internalPolicy"));
check(schema.required.includes("riskReview"));
check(schema.required.includes("hiddenInternalScope"));
check(/package/i.test(schema.description), "schema documents package-ID rule");

const prompt = fs.readFileSync(promptPath, "utf8");
check(/Google video/i.test(prompt));
check(/never invent/i.test(prompt));
check(/package/i.test(prompt));
check(/transcriptConfidence/i.test(prompt));
check(/policyPreview/i.test(prompt));

// --- CK-CLARIFY-START / CK-HIGH-AMBIG ---
const hi = normalizeCompiledPromise(
  base({
    ambiguityLevel: "high",
    clarificationRequired: false,
    canStartCommitment: true,
    duration: { kind: "none", value: null, unit: null, until: null },
    confidence: 0.2,
  }),
  "do something with google stuff maybe"
);
eq(hi.clarificationRequired, true, "CK-HIGH-AMBIG");
eq(hi.canStartCommitment, false, "CK-CLARIFY-START high ambig");

const staleStart = normalizeCompiledPromise(
  base({
    followUpQuestionRequired: true,
    followUpQuestion: "What did you mean?",
    clarificationRequired: true,
    ambiguityLevel: "medium",
  }),
  "focus somehow"
);
eq(staleStart.clarificationRequired, true);
eq(staleStart.canStartCommitment, false, "CK-CLARIFY-START");

// --- CK-GOOGLE-VIDEO ---
const gv = normalizeCompiledPromise(
  base({}),
  "block Google video less than 30 min for 1 hour"
);
check(gv.clarificationOptions.length >= 2, "CK-GOOGLE-VIDEO options");
check(gv.clarificationRequired, "CK-GOOGLE-VIDEO clarify");
check(gv.clarificationOptions.every((o) => o.policyPreview), "options have preview");
eq(gv.canStartCommitment, false, "CK-GOOGLE-VIDEO no start");
eq(gv.sessionDurationMinutes, 60);

// --- CK-NO-PKG-LEAK ---
check(userFacingHasPackageLeak("x com.instagram.android y"));
check(!userFacingHasPackageLeak(sanitizeUserFacingCopy("x com.instagram.android y")));
const leaky = normalizeCompiledPromise(
  base({
    userFacingConfirmation: {
      understood: "Allowed (com.google.android.youtube)",
      allowed: ["long (org.schabi.newpipe)"],
      blocked: ["reels com.instagram.android"],
      time: "1 hour",
      appliesTo: "com.android.chrome",
      checkThis: ["includes com.zhiliaoapp.musically"],
      safetyNotes: ["keep com.whatsapp for emergency"],
    },
    clarificationOptions: [
      {
        id: "A",
        label: "Chrome com.android.chrome",
        description: "Use com.android.chrome only",
        recommended: true,
        policyPreview: "Scope com.android.chrome",
      },
    ],
  }),
  "allow long youtube for 1 hour"
);
check(!userFacingHasPackageLeak(userFacingBlob(leaky)), "CK-NO-PKG-LEAK");

// --- CK-PERMANENT-PORN ---
const year = normalizeCompiledPromise(
  base({
    commitmentType: "permanent_guardrail",
    duration: { kind: "fixed", value: 1, unit: "years", until: null },
    activeGuardrails: ["no_adult_content"],
    blockedContent: [{ type: "adult_sexual", description: "porn", apps: [] }],
  }),
  "no porn for 1 year, normal phone otherwise"
);
check(year.requiresStrongerConfirmation, "CK-PERMANENT-PORN stronger");
check(year.riskReview.requiresHumanConfirmation, "CK-PERMANENT-PORN human");
eq(year.canStartCommitment, false, "CK-PERMANENT-PORN no soft-start");
eq(year.commitmentType, "permanent_guardrail");
check(year.isPermanentCommitment);

// --- CK-SHORTS-QUOTA ---
const shorts = normalizeCompiledPromise(
  base({
    commitmentType: "quota_entertainment",
    duration: { kind: "none", value: null, unit: null, until: null },
    quotas: [{ metric: "shorts", limit: 10, period: "day" }],
  }),
  "watch 10 shorts today"
);
eq(shorts.clarificationRequired, false);
eq(shorts.timeWindowKind, "calendar_day", "CK-SHORTS-QUOTA calendar day");
eq(shorts.sessionDurationMinutes, CALENDAR_DAY_MINUTES);
check(shorts.sessionDurationMinutes !== DEFAULT_SESSION_MINUTES);
check(
  !shorts.cautionMessages.some((m) => /defaulted to 60 minutes/i.test(m)),
  "CK-SHORTS-QUOTA no invented 1h"
);
check(
  shorts.contentRules.some(
    (r) => r.unit === "count" && r.value === 10 && r.action === "ALLOW"
  ),
  "CK-SHORTS-QUOTA daily count"
);

// --- CK-MEDIA-VS-SESSION ---
const mediaSession = normalizeCompiledPromise(
  base({
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    quotas: [{ metric: "min_item_minutes", limit: 30, period: "item" }],
  }),
  "only allow video less than 30 min for 1 hour"
);
eq(mediaSession.sessionDurationMinutes, 60, "CK-MEDIA-VS-SESSION session=60");
check(
  mediaSession.contentRules.some(
    (r) =>
      r.action === "BLOCK" &&
      r.operator === "gt" &&
      r.value === 30 &&
      r.unit === "minutes"
  ),
  "CK-MEDIA-VS-SESSION media threshold BLOCK gt 30 (not inverted lt)"
);

// --- CK-NORMAL-EXCEPT ---
const normalPhone = normalizeCompiledPromise(
  base({
    commitmentType: "monk_mode",
    duration: { kind: "fixed", value: 1, unit: "years", until: null },
    activeGuardrails: [
      "no_adult_content",
      "no_social_feed",
      "no_short_form_video",
      "no_entertainment_installs",
    ],
    blockedContent: [{ type: "adult_sexual", description: "porn", apps: [] }],
  }),
  "normal phone except porn for 1 year"
);
eq(normalPhone.commitmentType, "permanent_guardrail", "CK-NORMAL-EXCEPT");
eq(normalPhone.isStudyModeLike, false, "CK-NORMAL-EXCEPT not study");
check(!(normalPhone.internalPolicy.guardrails || []).includes("no_social_feed"));

// --- CK-EMERGENCY ---
const withEmergency = normalizeCompiledPromise(
  base({
    emergencyExceptions: [
      { type: "calls", detail: "Family and emergency calls always allowed" },
      { type: "sos", description: "SOS / emergency dialer" },
    ],
    activeGuardrails: ["no_adult_content"],
    blockedContent: [{ type: "adult_sexual", description: "porn", apps: [] }],
  }),
  "no porn for 1 hour but keep emergency calls"
);
check(
  Array.isArray(withEmergency.emergencyExceptions) &&
    withEmergency.emergencyExceptions.length === 2,
  "CK-EMERGENCY preserved"
);
check(
  withEmergency.emergencyExceptions.some((e) => e.type === "calls"),
  "CK-EMERGENCY calls kept"
);
check(
  withEmergency.emergencyExceptions.some((e) => /emergency|sos/i.test(e.detail)),
  "CK-EMERGENCY detail kept"
);

// --- riskReview alone cannot soft-start ---
const riskOnly = normalizeCompiledPromise(
  base({
    riskReview: {
      falseAllowRisk: "high",
      falseBlockRisk: "low",
      safetyRisk: "medium",
      requiresHumanConfirmation: true,
    },
  }),
  "focus for 1 hour no shorts"
);
eq(riskOnly.canStartCommitment, false, "riskReview.requiresHumanConfirmation ⇒ !canStart");
check(riskOnly.requiresStrongerConfirmation, "forces stronger confirm path");

// --- CK-OPTION-MAP (rematerialize, not preview-only) ---
const optionBase = {
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
      id: "B",
      label: "All video apps on my phone",
      description: "All apps",
      recommended: false,
      policyPreview: "All video apps follow length rules",
    },
    {
      id: "C",
      label: "YouTube only",
      description: "YouTube app only",
      recommended: false,
      policyPreview: "Only YouTube follows these rules for this commitment.",
    },
  ],
};
const beforePick = normalizeCompiledPromise(
  base(optionBase),
  "don't let me watch google video less than 30 min for 1 hour"
);
eq(beforePick.clarificationRequired, true, "CK-OPTION-MAP before pick clarify");
eq(beforePick.canStartCommitment, false, "CK-OPTION-MAP before pick no start");

/** What Android sends on a pick: the shown options snapshot + the tapped option. */
function pickFrom(turn1, id) {
  const snapshot = JSON.parse(JSON.stringify(turn1.clarificationOptions || []));
  return {
    selectedClarificationOptionId: id,
    priorClarificationOptionsSnapshot: snapshot,
    selectedClarificationOptionFullText: snapshot.find((o) => o.id === id) || null,
  };
}

const idOnlyPick = normalizeCompiledPromise(
  base(optionBase),
  "don't let me watch google video less than 30 min for 1 hour",
  { selectedClarificationOptionId: "A" }
);
eq(idOnlyPick.canStartCommitment, false, "CK-OPTION-MAP id-only pick cannot be verified");

const afterPickChrome = normalizeCompiledPromise(
  base(optionBase),
  "don't let me watch google video less than 30 min for 1 hour",
  pickFrom(beforePick, "A")
);
eq(afterPickChrome.clarificationRequired, false, "CK-OPTION-MAP clarify cleared");
eq(afterPickChrome.canStartCommitment, true, "CK-OPTION-MAP start after valid pick");
check(afterPickChrome.optionRematerialized, "CK-OPTION-MAP rematerialized");
check(
  afterPickChrome.scopePackages.includes("com.android.chrome"),
  "CK-OPTION-MAP Chrome scope"
);
check(
  afterPickChrome.contentRules.some(
    (r) => r.packageName === "com.android.chrome" && r.unit === "minutes"
  ),
  "CK-OPTION-MAP Chrome stamped on contentRules"
);
check(
  !afterPickChrome.scopePackages.includes("com.google.android.youtube"),
  "CK-OPTION-MAP Chrome pick excludes YouTube scope"
);
check(
  /Chrome/i.test(afterPickChrome.recommendedInterpretation || ""),
  "CK-OPTION-MAP preview maps to interpretation"
);
check(!userFacingHasPackageLeak(userFacingBlob(afterPickChrome)), "CK-OPTION-MAP no pkg leak after rematerialize");

const afterPickAll = normalizeCompiledPromise(
  base(optionBase),
  "don't let me watch google video less than 30 min for 1 hour",
  pickFrom(beforePick, "B")
);
const afterPickYt = normalizeCompiledPromise(
  base(optionBase),
  "don't let me watch google video less than 30 min for 1 hour",
  pickFrom(beforePick, "C")
);
check(
  afterPickYt.scopePackages.includes("com.google.android.youtube"),
  "CK-OPTION-MAP YouTube only scope"
);
check(
  afterPickAll.scopePackages.includes("com.google.android.youtube") &&
    afterPickAll.scopePackages.includes("com.android.chrome"),
  "CK-OPTION-MAP all-video broader than YouTube-only"
);
check(
  afterPickAll.scopePackages.length > afterPickYt.scopePackages.length,
  "CK-OPTION-MAP all-video vs YouTube-only differ"
);
check(
  JSON.stringify(afterPickYt.scopePackages) !==
    JSON.stringify(afterPickChrome.scopePackages),
  "CK-OPTION-MAP YouTube vs Chrome differ"
);

const badPick = normalizeCompiledPromise(
  base({
    followUpQuestionRequired: true,
    clarificationOptions: [
      { id: "A", label: "Option A", description: "A", recommended: true, policyPreview: "Chrome video" },
      { id: "B", label: "Option B", description: "B", recommended: false, policyPreview: "All video apps" },
    ],
  }),
  "block Google video for 1 hour",
  { selectedClarificationOptionId: "Z" }
);
eq(badPick.clarificationRequired, true, "invalid option keeps clarify");
eq(badPick.canStartCommitment, false, "invalid option never unlocks Start");

const missingPreview = normalizeCompiledPromise(
  base({
    followUpQuestionRequired: true,
    clarificationOptions: [
      { id: "A", label: "Vague choice", description: "No preview", recommended: true },
    ],
  }),
  "be strict for exam mode tonight",
  { selectedClarificationOptionId: "A" }
);
eq(missingPreview.clarificationRequired, true, "missing preview keeps clarify");
eq(missingPreview.canStartCommitment, false, "missing preview never unlocks Start");
check(!missingPreview.optionRematerialized, "missing preview not rematerialized");

// Shorts all-surfaces option rematerialize
const shortsOptBase = {
  confidence: 0.4,
  followUpQuestionRequired: true,
  clarificationOptions: [
    {
      id: "A",
      label: "All short-form surfaces",
      description: "Shorts + Reels + TikTok-style",
      recommended: true,
      policyPreview:
        "All short-form video surfaces follow this commitment (not YouTube only).",
    },
    {
      id: "B",
      label: "YouTube Shorts only",
      description: "YT only",
      recommended: false,
      policyPreview: "Only YouTube Shorts follow this commitment.",
    },
  ],
};
const shortsTurn1 = normalizeCompiledPromise(base(shortsOptBase), "shorts later maybe");
const shortsAll = normalizeCompiledPromise(
  base(shortsOptBase),
  "shorts later maybe",
  pickFrom(shortsTurn1, "A")
);
check(shortsAll.optionRematerialized, "shorts all-surfaces rematerialized");
check(
  shortsAll.scopePackages.length >= 5,
  "shorts all-surfaces expands scope"
);
check(
  shortsAll.scopePackages.includes("com.instagram.android"),
  "shorts all-surfaces includes IG"
);
eq(shortsAll.canStartCommitment, true, "shorts all-surfaces can start");
const shortsYtOnly = normalizeCompiledPromise(
  base(shortsOptBase),
  "shorts later maybe",
  pickFrom(shortsTurn1, "B")
);
check(
  shortsYtOnly.scopePackages.length === 1 &&
    shortsYtOnly.scopePackages[0] === "com.google.android.youtube",
  "YouTube Shorts only narrows scope"
);
check(
  shortsAll.scopePackages.length > shortsYtOnly.scopePackages.length,
  "shorts all vs YT-only differ"
);

const youtubeContent = normalizeCompiledPromise(
  base({
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    allowedContent: [
      {
        type: "long_form_video",
        description: "videos longer than 40 min",
        apps: ["com.google.android.youtube"],
      },
    ],
  }),
  "allow YouTube video longer than 40 min only"
);
eq(youtubeContent.scopeKind, "youtube_content", "CK-YT-CONTENT scope kind");
check(
  youtubeContent.contentBrands.includes("youtube") &&
    youtubeContent.surfaceScope.includes("youtube"),
  "CK-YT-CONTENT brand/surface separate from package"
);
check(
  !youtubeContent.scopePackages.includes("com.android.chrome") &&
    youtubeContent.scopePackages.every((p) => p !== "com.google.android.youtube" || youtubeContent.contentBrands.includes("youtube")),
  "CK-YT-CONTENT does not lock Chrome out by official-package scope"
);
check(
  youtubeContent.scopePackages.length === 0,
  "CK-YT-CONTENT exclusive package list empty"
);

console.log(`ASSERTIONS_PASSED=${ASSERTIONS}`);
console.log(
  JSON.stringify({
    ok: true,
    event: "promise_compiler_v08_smoke",
    assertionsPassed: ASSERTIONS,
    confidenceKeys: [
      "CK-CLARIFY-START",
      "CK-HIGH-AMBIG",
      "CK-NO-PKG-LEAK",
      "CK-GOOGLE-VIDEO",
      "CK-SHORTS-QUOTA",
      "CK-MEDIA-VS-SESSION",
      "CK-NORMAL-EXCEPT",
      "CK-PERMANENT-PORN",
      "CK-EMERGENCY",
      "CK-OPTION-MAP",
    ],
  })
);

if (ASSERTIONS < 40) {
  console.error(`Expected at least 40 assertions, got ${ASSERTIONS}`);
  process.exit(1);
}
