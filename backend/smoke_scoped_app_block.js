/**
 * Offline smoke: a scoped app instruction ("block YouTube Shorts") must compile to a
 * structured short-form BLOCK, never to a whole-package BLOCK. No Azure, no network.
 * Run: node backend/smoke_scoped_app_block.js
 *
 * Invariant: typed scope = WHERE, structured content rule = WHAT. A package-wide BLOCK
 * must not broaden a narrower scope; explicit whole-app blocks still block the app;
 * scope/action contradictions keep Start closed.
 */
process.env.PROMISE_COMPILER_PROMPT_VERSION = "promise_compiler_v09";

const assert = require("assert");
const {
  compilePromise,
  normalizeCompiledPromise,
  setCompilerTransportForTests,
  PROMPT_VERSION,
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

eq(PROMPT_VERSION, "promise_compiler_v09", "smoke must run the v09 contract");

const YT = "com.google.android.youtube";
const WA = "com.whatsapp";

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
    strikePolicy: { warnBeforeLock: 0, strikesBeforeLock: 0, resetPeriod: "session" },
    lockPolicy: { enabled: false, durationMinutes: null, scope: "commitment_pause" },
    emergencyExceptions: [],
    tamperPolicy: { preventDisable: false, onTamper: "NONE", allowSettingsBrowse: true },
    followUpQuestionRequired: false,
    followUpQuestion: null,
    rejectedUnsafeParts: [],
    ambiguityLevel: "none",
    confidence: 0.9,
    ...overrides,
  };
}

function scope(kind, identity, pkg, surfaces, evidence) {
  return {
    version: 1,
    kind,
    resolution: "EXCLUSIVE",
    identity,
    include: [{ id: "i1", host: "NATIVE_APP", identity, surfaces, packages: [pkg], packageMatch: "PINNED" }],
    exempt: [],
    confidence: 0.9,
    evidenceSpans: [evidence],
  };
}

const ytShortsScope = (evidence) => scope("APP_SHORT_FORM_ONLY", "YOUTUBE", YT, ["PLAYER_SHORT"], evidence);
const ytAppScope = (evidence) => scope("APP_ONLY", "YOUTUBE", YT, ["ANY"], evidence);

const SHORT_FORM_APP_PACKAGES = {
  YOUTUBE: YT,
  INSTAGRAM: "com.instagram.android",
  FACEBOOK: "com.facebook.katana",
  TIKTOK: "com.zhiliaoapp.musically",
  SNAPCHAT: "com.snapchat.android",
};

function appShortFormScope(identity, evidence) {
  return scope(
    "APP_SHORT_FORM_ONLY",
    identity,
    SHORT_FORM_APP_PACKAGES[identity],
    ["PLAYER_SHORT"],
    evidence
  );
}

async function compile(text, modelOutput) {
  setCompilerTransportForTests(async () => JSON.stringify(modelOutput));
  const origLog = console.log;
  console.log = () => {};
  try {
    return await compilePromise(text, {});
  } finally {
    console.log = origLog;
    setCompilerTransportForTests(null);
  }
}

const quietNormalize = (...args) => {
  const origLog = console.log;
  console.log = () => {};
  try {
    return normalizeCompiledPromise(...args);
  } finally {
    console.log = origLog;
  }
};

const appRule = (dto, pkg) => (dto.suggestedAppRules || []).find((r) => r.packageName === pkg);
const internalAppRule = (dto, pkg) =>
  ((dto.internalPolicy && dto.internalPolicy.appRules) || []).find((r) => r.packageName === pkg);
const shortFormBlock = (dto, pkg) =>
  (dto.contentRules || []).find(
    (r) =>
      r.packageName === pkg &&
      r.contentType === "short_form_video" &&
      r.action === "BLOCK" &&
      !r.operator &&
      r.value == null
  );
const bullets = (dto, key) => ((dto.userFacingConfirmation || {})[key] || []).map(String);

async function main() {
  const SHORTS_TEXT = "block youtube shorts for 1 hour";

  // 1) First-turn v09: model returns YouTube blockedApps with scope "shorts".
  for (const withContent of [false, true]) {
    const dto = await compile(
      SHORTS_TEXT,
      basePolicy({
        blockedApps: [{ packageName: YT, appLabel: "YouTube", scope: "shorts" }],
        blockedContent: withContent
          ? [{ type: "short_form_video", description: "YouTube Shorts", apps: [YT] }]
          : [],
        scope: ytShortsScope("youtube shorts"),
        userFacingConfirmation: {
          understood: "Block YouTube for 1 hour.",
          allowed: ["Long YouTube videos"],
          blocked: ["YouTube"],
        },
      })
    );
    const tag = `shorts-scoped(content=${withContent})`;
    const yt = appRule(dto, YT);
    check(!yt || yt.behavior !== "BLOCK", `${tag}: no whole-app YouTube BLOCK in suggestedAppRules`);
    const iyt = internalAppRule(dto, YT);
    check(!iyt || iyt.behavior !== "BLOCK", `${tag}: no whole-app YouTube BLOCK in internalPolicy.appRules`);
    check(shortFormBlock(dto, YT), `${tag}: structured YouTube short-form BLOCK`);
    eq((dto.scope || {}).kind, "APP_SHORT_FORM_ONLY", `${tag}: narrow typed scope kept`);
    eq(dto.clarificationRequired, false, `${tag}: no clarification`);
    eq(dto.canStartCommitment, true, `${tag}: can start`);
    check(!bullets(dto, "blocked").some((b) => /^youtube$/i.test(b.trim())), `${tag}: preview must not claim all of YouTube is blocked`);
    check(bullets(dto, "blocked").some((b) => /youtube shorts/i.test(b)), `${tag}: preview names YouTube Shorts`);
  }

  // 2) Explicit whole-app YouTube block stays a whole-app BLOCK.
  {
    const dto = await compile(
      "block youtube for 1 hour",
      basePolicy({
        blockedApps: [{ packageName: YT, appLabel: "YouTube", scope: "" }],
        scope: ytAppScope("youtube"),
      })
    );
    eq((appRule(dto, YT) || {}).behavior, "BLOCK", "whole-app: YouTube BLOCK kept");
    check(!shortFormBlock(dto, YT), "whole-app: no invented short-form rule");
    eq((dto.scope || {}).kind, "APP_ONLY", "whole-app: APP_ONLY scope");
    eq(dto.canStartCommitment, true, "whole-app: can start");
  }

  // 3) Broad BLOCK with no structured narrow action vs narrow scope: cannot start.
  {
    const dto = await compile(
      SHORTS_TEXT,
      basePolicy({
        blockedApps: [{ packageName: YT, appLabel: "YouTube" }],
        scope: ytShortsScope("youtube shorts"),
      })
    );
    eq(dto.canStartCommitment, false, "conflict: Start closed");
    eq(dto.clarificationRequired, true, "conflict: clarification required");
    check(typeof dto.clarificationQuestion === "string" && dto.clarificationQuestion.length > 0, "conflict: asks a question");
    check(dto.policyConflict, "conflict: reason recorded");
  }

  // 4) Unrepresentable app scope: never a whole-app BLOCK, Start closed.
  {
    const dto = await compile(
      "block youtube comments for 1 hour",
      basePolicy({
        blockedApps: [{ packageName: YT, appLabel: "YouTube", scope: "comments" }],
        scope: ytAppScope("youtube"),
      })
    );
    const yt = appRule(dto, YT);
    check(!yt || yt.behavior !== "BLOCK", "unrepresentable: no whole-app BLOCK");
    eq(dto.canStartCommitment, false, "unrepresentable: Start closed");
    check(dto.policyConflict, "unrepresentable: reason recorded");
  }

  // 5) Whole-app BLOCK contradicted by an ALLOW for the same app: cannot start.
  {
    const dto = await compile(
      "block youtube but allow long lectures on youtube for 1 hour",
      basePolicy({
        blockedApps: [{ packageName: YT, appLabel: "YouTube", scope: "" }],
        allowedContent: [{ type: "long_form_video", description: "long lectures", apps: [YT] }],
        scope: ytAppScope("youtube"),
      })
    );
    eq(dto.canStartCommitment, false, "block+allow same app: Start closed");
    check(dto.policyConflict, "block+allow same app: reason recorded");
  }

  // 6) WhatsApp whole-app block unchanged.
  {
    const dto = await compile(
      "do not allow whatsapp for 10 minutes",
      basePolicy({
        duration: { kind: "fixed", value: 10, unit: "minutes", until: null },
        blockedApps: [{ packageName: WA, appLabel: "WhatsApp", scope: "" }],
        scope: scope("APP_ONLY", "WHATSAPP", WA, ["ANY"], "whatsapp"),
      })
    );
    eq((appRule(dto, WA) || {}).behavior, "BLOCK", "whatsapp: whole-app BLOCK kept");
    check(!dto.policyConflict, "whatsapp: no conflict");
  }

  // 7) Content-only short-form rule for YouTube must not derive a whole-app BLOCK.
  {
    const dto = await compile(
      SHORTS_TEXT,
      basePolicy({
        blockedContent: [{ type: "short_form_video", description: "YouTube Shorts", apps: [YT] }],
        scope: ytShortsScope("youtube shorts"),
      })
    );
    const yt = appRule(dto, YT);
    check(!yt || yt.behavior !== "BLOCK", "derived: no whole-app YouTube BLOCK");
    check(shortFormBlock(dto, YT), "derived: short-form BLOCK kept");
    eq(dto.canStartCommitment, true, "derived: can start");
  }

  // 8) Legacy (v07, no typed scope) normalize path converts the same way.
  {
    const dto = quietNormalize(
      basePolicy({ blockedApps: [{ packageName: YT, appLabel: "YouTube", scope: "shorts" }] }),
      SHORTS_TEXT,
      { promptVersion: "promise_compiler_v07" }
    );
    const yt = appRule(dto, YT);
    check(!yt || yt.behavior !== "BLOCK", "v07: no whole-app YouTube BLOCK");
    check(shortFormBlock(dto, YT), "v07: structured short-form BLOCK");
  }

  // 9) Free-text Shorts scope (observed from v09 on 2026-09-27) narrows, no clarification.
  for (const freeText of [
    "Shorts player only; not the whole app",
    "only the Shorts player, not the entire app",
    "short videos only",
  ]) {
    const dto = await compile(
      "Block YouTube Shorts only for the next 10 minutes. Keep YouTube Home, long videos, Chrome, and other apps available.",
      basePolicy({
        blockedApps: [{ packageName: YT, appLabel: "YouTube", scope: freeText }],
        scope: ytShortsScope("YouTube Shorts only"),
      })
    );
    const tag = `free-text "${freeText}"`;
    const yt = appRule(dto, YT);
    check(!yt || yt.behavior !== "BLOCK", `${tag}: no whole-app YouTube BLOCK`);
    check(shortFormBlock(dto, YT), `${tag}: structured YouTube short-form BLOCK`);
    eq(dto.clarificationRequired, false, `${tag}: no clarification`);
    eq(dto.canStartCommitment, true, `${tag}: can start`);
  }

  // 10) Free text that claims more than Shorts stays unrepresentable: Start closed.
  for (const freeText of [
    "whole app except Shorts",
    "everything but Shorts",
    "Shorts and comments",
    "Shorts plus the home feed",
    "all of the app, including Shorts",
  ]) {
    const dto = await compile(
      SHORTS_TEXT,
      basePolicy({
        blockedApps: [{ packageName: YT, appLabel: "YouTube", scope: freeText }],
        scope: ytShortsScope("youtube shorts"),
      })
    );
    const tag = `broad free-text "${freeText}"`;
    const yt = appRule(dto, YT);
    check(!yt || yt.behavior !== "BLOCK", `${tag}: no whole-app BLOCK`);
    check(!shortFormBlock(dto, YT), `${tag}: not narrowed to Shorts`);
    eq(dto.canStartCommitment, false, `${tag}: Start closed`);
    eq(dto.policyConflict, "unrepresentable_app_scope", `${tag}: reason recorded`);
    check(
      (dto.cautionMessages || []).some((m) => /couldn't tell which part of YouTube/i.test(m)),
      `${tag}: caution names the real reason`
    );
  }

  // 11) A validated app-specific short-player scope is never expanded into a
  // cross-app package list or contradicted by whole-brand confirmation copy.
  for (const testCase of [
    {
      text: "For 10 minutes, block only Shorts in the YouTube app. Keep YouTube Home, long videos, Chrome, and other apps allowed.",
      identity: "YOUTUBE",
      appLabel: "YouTube",
      surfaceLabel: "Shorts",
      evidence: "Shorts in the YouTube app",
    },
    {
      text: "Block YouTube Shorts only for 10 minutes.",
      identity: "YOUTUBE",
      appLabel: "YouTube",
      surfaceLabel: "Shorts",
      evidence: "YouTube Shorts",
    },
    {
      text: "Block Reels in Instagram for 10 minutes.",
      identity: "INSTAGRAM",
      appLabel: "Instagram",
      surfaceLabel: "Reels",
      evidence: "Reels in Instagram",
    },
    {
      text: "Block Facebook Reels only for 10 minutes.",
      identity: "FACEBOOK",
      appLabel: "Facebook",
      surfaceLabel: "Reels",
      evidence: "Facebook Reels",
    },
    {
      text: "Block TikTok short-form videos for 10 minutes.",
      identity: "TIKTOK",
      appLabel: "TikTok",
      surfaceLabel: "short-form videos",
      evidence: "TikTok short-form videos",
    },
  ]) {
    const pkg = SHORT_FORM_APP_PACKAGES[testCase.identity];
    const dto = quietNormalize(
      basePolicy({
        duration: { kind: "fixed", value: 10, unit: "minutes", until: null },
        blockedContent: [{
          type: "short_form_video",
          description: `${testCase.appLabel} ${testCase.surfaceLabel}`,
          apps: [pkg],
        }],
        scope: appShortFormScope(testCase.identity, testCase.evidence),
        userFacingConfirmation: {
          understood: `Block ${testCase.surfaceLabel} in ${testCase.appLabel} for 10 minutes.`,
          allowed: ["Other apps and long videos"],
          blocked: [`${testCase.appLabel} ${testCase.surfaceLabel}`],
        },
      }),
      testCase.text,
      { promptVersion: "promise_compiler_v09" }
    );
    const tag = `typed ${testCase.identity} short-form scope`;
    eq(dto.scope.kind, "APP_SHORT_FORM_ONLY", `${tag}: typed surface scope retained`);
    eq(dto.scope.identity, testCase.identity, `${tag}: app identity retained`);
    eq(JSON.stringify(dto.scopePackages), JSON.stringify([pkg]), `${tag}: package projection stays app-specific`);
    check(
      !(dto.suggestedAppRules || []).some((rule) => rule.packageName !== pkg),
      `${tag}: no unrelated short-form apps added`
    );
    check(
      !(dto.interpretationNotes || []).some((note) => /all short-form|reels-style apps/i.test(note)),
      `${tag}: no conflicting cross-app interpretation note`
    );
    check(
      !(dto.userFacingConfirmation.checkThis || []).some((note) => /all short-form|reels-style apps/i.test(note)),
      `${tag}: no duplicate cross-app warning in confirmation card`
    );
    check(
      new RegExp(`${testCase.appLabel}.*short.*only`, "i").test(dto.userFacingConfirmation.appliesTo),
      `${tag}: confirmation applies-to copy reflects the typed scope`
    );
    eq(dto.canStartCommitment, true, `${tag}: valid narrow scope can start`);
  }

  console.log(`smoke_scoped_app_block OK (${ASSERTIONS} assertions)`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
