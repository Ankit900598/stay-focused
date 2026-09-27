/**
 * Offline smoke: a clarification pick means exactly the option the user saw.
 * Run: node backend/smoke_clarification_choice_identity.js
 *
 * Phone evidence 2026-09-26 (.tmp-phone-verify-20260926b): the same tap on
 * "A = All video apps and browsers" resolved to Chrome-only on some runs
 * because the turn-2 model output reused id "A" for a different scope.
 *
 * Contract under test:
 *   - The prior options snapshot (what the user saw) is the authority.
 *   - The selected id must appear exactly once and resolve to a valid scope.
 *   - A carried option must agree with its snapshot entry, else fail closed.
 *   - Fresh same-id model options never authorize or replace a pick.
 *   - Unverifiable picks keep Start disabled and re-ask.
 */
const assert = require("assert");
const service = require("./promiseCompilerService");
// Legacy-contract smoke: pinned to v07 so a v09 process env cannot change what it asserts.
// v09 behavior is covered by smoke_v09_clarification_scope.js.
const normalizeCompiledPromise = (parsed, text, options = {}, trusted) =>
  service.normalizeCompiledPromise(parsed, text, { promptVersion: "promise_compiler_v07", ...options }, trusted);
const {
  resolveClarificationSelection,
  resolveOptionRematerialize,
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

const PKG = {
  youtube: "com.google.android.youtube",
  newpipe: "org.schabi.newpipe",
  chrome: "com.android.chrome",
  vanced: "com.vanced.android.youtube",
  instagram: "com.instagram.android",
};

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
    confidence: 0.9,
    ...overrides,
  };
}

/** What Android caches: the verbatim JSON of turn 1's clarificationOptions. */
function androidSnapshot(turn1) {
  return JSON.parse(JSON.stringify(turn1.clarificationOptions || []));
}

function carryFor(snapshot, id) {
  return snapshot.find((o) => String(o.id).toUpperCase() === id.toUpperCase()) || null;
}

function turn1(promiseText, modelOptions, extra = {}) {
  return normalizeCompiledPromise(
    basePolicy({
      followUpQuestionRequired: true,
      followUpQuestion: "Where should this apply?",
      clarificationRequired: true,
      clarificationOptions: modelOptions,
      ...extra,
    }),
    promiseText
  );
}

/** Turn 2 model output reproduces the phone copy: a reused id + "choice missing" prose. */
function turn2Parsed(freshOptions, extra = {}) {
  return basePolicy({
    clarificationOptions: freshOptions,
    interpretationNotes: [
      "Your selection A has no earlier option list attached, so I need to confirm which video sources you mean.",
    ],
    userFacingConfirmation: {
      understood:
        "I understood: block videos shorter than 30 minutes for the next hour; the video sources still need confirmation.",
      checkThis: [
        "Your selection A arrived without its earlier option text, so I cannot reliably map it to a scope.",
        "Using your choice: Browser video only",
      ],
    },
    ...extra,
  });
}

function pick(promiseText, parsed2, selectedId, { snapshot, carry, priorPromiseText } = {}) {
  const opts = { selectedClarificationOptionId: selectedId };
  if (snapshot !== undefined) opts.priorClarificationOptionsSnapshot = snapshot;
  if (carry !== undefined) opts.selectedClarificationOptionFullText = carry;
  if (priorPromiseText !== undefined) opts.priorClarificationPromiseText = priorPromiseText;
  return normalizeCompiledPromise(parsed2, promiseText, opts);
}

function allUserCopy(dto) {
  const ufc = dto.userFacingConfirmation || {};
  return [
    ...(dto.cautionMessages || []),
    ...(dto.interpretationNotes || []),
    ...(ufc.checkThis || []),
    ufc.understood || "",
    ufc.understoodSummary || "",
    ufc.appliesTo || "",
  ].join(" | ");
}

function assertVerified(dto, label, expectedLabel) {
  eq(dto.clarificationRequired, false, `${label}: clarification cleared`);
  eq(dto.canStartCommitment, true, `${label}: Start enabled`);
  check(
    /verified|carried_full_text/.test(String(dto.optionVerification)),
    `${label}: verification recorded (${dto.optionVerification})`
  );
  const copy = allUserCopy(dto);
  check(
    copy.includes(`Using your choice: ${expectedLabel}`),
    `${label}: confirmation names the chosen option (${copy})`
  );
  check(
    !/no earlier option|arrived without|previously defined option|cannot reliably map|still need confirmation/i.test(copy),
    `${label}: stale "choice missing" copy removed (${copy})`
  );
  check(
    !/clarification choice was not recognized/i.test(copy),
    `${label}: no unrecognized warning`
  );
  const understood = (dto.userFacingConfirmation || {}).understood || "";
  check(!/[;,]\s*$/.test(understood), `${label}: understood copy ends cleanly (${understood})`);
}

function assertFailClosed(dto, label, reason) {
  eq(dto.canStartCommitment, false, `${label}: Start disabled`);
  eq(dto.clarificationRequired, true, `${label}: must re-ask`);
  eq(dto.optionVerification, "unrecognized_fail_closed", `${label}: verdict`);
  if (reason) eq(dto.optionVerificationReason, reason, `${label}: reason`);
  check(
    (dto.clarificationOptions || []).length > 0,
    `${label}: options stay available so the user can pick again`
  );
  check(
    !/Using your choice/i.test(allUserCopy(dto)),
    `${label}: never claims a resolved choice`
  );
}

const families = [];
const fixtureDtos = {};
function record(name, dto, fixtureName) {
  if (fixtureName) fixtureDtos[fixtureName] = dto;
  families.push({
    family: name,
    verification: dto.optionVerification,
    label: (allUserCopy(dto).match(/Using your choice: ([^|]+)/) || [])[1]?.trim() || null,
    scopeKind: dto.scopeKind,
    packages: [...(dto.scopePackages || [])].sort().join(","),
    brands: (dto.contentBrands || []).join(","),
  });
}

// ---------------------------------------------------------------------------
// 1. The phone bug: turn-1 A = all video apps; turn-2 fresh A = Chrome only.
// ---------------------------------------------------------------------------
const MIN30 = "do not allow videos shorter than 30 minutes for next 1 hour";
const videoTurn1Options = [
  {
    id: "A",
    label: "All video apps and browsers",
    description: "Apply the limit wherever you watch videos.",
    recommended: true,
    policyPreview: "For one hour, block videos shorter than 30 minutes across video apps and browsers.",
  },
  {
    id: "B",
    label: "Browser video only",
    description: "Only videos opened in Chrome or other browsers.",
    policyPreview: "Only browser video follows the length rule.",
  },
  {
    id: "C",
    label: "YouTube app only",
    description: "Only the YouTube app.",
    policyPreview: "Only the YouTube app follows the length rule.",
  },
];
const videoT1 = turn1(MIN30, videoTurn1Options);
const videoSnapshot = androidSnapshot(videoT1);
eq(videoSnapshot.length, 3, "turn 1 shows three options");
eq(
  videoSnapshot[0].internalPolicyPreview && videoSnapshot[0].internalPolicyPreview.scopeKind,
  "all_video_apps",
  "turn 1 pins the shown option A to a structured all-video scope"
);

const freshConflicting = [
  { id: "A", label: "Browser video only", description: "Chrome", policyPreview: "Chrome only" },
  { id: "B", label: "All apps and browsers", description: "Everything", policyPreview: "All video" },
];

const allVideo = pick(MIN30, turn2Parsed(freshConflicting), "A", {
  snapshot: videoSnapshot,
  carry: carryFor(videoSnapshot, "A"),
  priorPromiseText: MIN30,
});
assertVerified(allVideo, "all-video pick", "All video apps and browsers");
eq(allVideo.scopeKind, "all_video_apps", "all-video scope kind preserved");
for (const p of [PKG.youtube, PKG.newpipe, PKG.chrome, PKG.vanced]) {
  check(allVideo.scopePackages.includes(p), `all-video scope includes ${p}`);
  check(
    allVideo.suggestedAppRules.some((r) => r.packageName === p),
    `all-video app rules include ${p}`
  );
  check(
    allVideo.contentRules.some(
      (r) => r.packageName === p && r.operator === "lt" && r.value === 30 && r.action === "BLOCK"
    ),
    `all-video length rule stamped on ${p}`
  );
}
check(
  !/Browser video only/.test(allUserCopy(allVideo)),
  "fresh same-id label never reaches the confirmation"
);
eq(
  (allVideo.userFacingConfirmation || {}).appliesTo,
  "Major video apps and browser video",
  "shown scope matches the resolved scope"
);
check(
  (allVideo.clarificationOptions || []).map((o) => o.id).join() === "A,B,C" &&
    allVideo.clarificationOptions[0].label === "All video apps and browsers",
  "response echoes the authoritative option list, not the fresh one"
);
record("all video apps + browser video", allVideo, "all_video");

// Same bug with snapshot only (no carry) and with text-only snapshot entries.
const allVideoSnapshotOnly = pick(MIN30, turn2Parsed(freshConflicting), "A", {
  snapshot: videoSnapshot,
});
assertVerified(allVideoSnapshotOnly, "all-video snapshot only", "All video apps and browsers");
eq(allVideoSnapshotOnly.scopeKind, "all_video_apps", "snapshot-only keeps all-video");

const textOnlySnapshot = videoTurn1Options.map((o) => ({ ...o, internalPolicyPreview: null }));
const allVideoTextOnly = pick(MIN30, turn2Parsed(freshConflicting), "A", {
  snapshot: textOnlySnapshot,
});
assertVerified(allVideoTextOnly, "all-video text-only snapshot", "All video apps and browsers");
eq(allVideoTextOnly.scopeKind, "all_video_apps", "text-only snapshot keeps all-video");

// "All apps and browsers" must never resolve to Chrome-only.
eq(
  (resolveOptionRematerialize({ id: "A", label: "All apps and browsers" }) || {}).scopeKind,
  "all_video_apps",
  "'All apps and browsers' is the all-video scope, not Chrome"
);

// ---------------------------------------------------------------------------
// 2. Chrome/browser only and YouTube app only stay narrow.
// ---------------------------------------------------------------------------
const chromeOnly = pick(MIN30, turn2Parsed(freshConflicting), "B", {
  snapshot: videoSnapshot,
  carry: carryFor(videoSnapshot, "B"),
  priorPromiseText: MIN30,
});
assertVerified(chromeOnly, "chrome pick", "Browser video only");
eq(chromeOnly.scopeKind, "chrome_browser", "chrome scope kind");
eq(chromeOnly.scopePackages.join(), PKG.chrome, "chrome pick stays Chrome-only");
record("Chrome / browser only", chromeOnly, "chrome_only");

const youtubeAppOnly = pick(MIN30, turn2Parsed(freshConflicting), "C", {
  snapshot: videoSnapshot,
  carry: carryFor(videoSnapshot, "C"),
  priorPromiseText: MIN30,
});
assertVerified(youtubeAppOnly, "youtube-app pick", "YouTube app only");
eq(youtubeAppOnly.scopeKind, "youtube_app_only", "youtube app scope kind");
eq(youtubeAppOnly.scopePackages.join(), PKG.youtube, "youtube-app pick stays YouTube-only");
check(!youtubeAppOnly.scopePackages.includes(PKG.newpipe), "youtube-app excludes NewPipe");
record("YouTube app only", youtubeAppOnly, "youtube_app_only");

// ---------------------------------------------------------------------------
// 3. YouTube content anywhere must not be narrowed to the app by promise text.
// ---------------------------------------------------------------------------
const YT_TEXT = "no youtube videos or youtube app videos longer than 20 minutes for 1 hour";
const ytT1 = turn1(YT_TEXT, [
  {
    id: "A",
    label: "YouTube videos anywhere",
    description: "App, browser pages, and other YouTube clients.",
    recommended: true,
    policyPreview: "YouTube videos anywhere follow the rule.",
    internalPolicyPreview: {
      scopeKind: "youtube_content",
      appliesTo: "YouTube videos anywhere — app, browser pages, and other YouTube clients",
      contentBrands: ["youtube"],
      packages: [],
    },
  },
  {
    id: "B",
    label: "YouTube app only",
    description: "Only the official app.",
    policyPreview: "Only the YouTube app.",
  },
]);
const ytSnapshot = androidSnapshot(ytT1);
const ytContent = pick(
  YT_TEXT,
  turn2Parsed([{ id: "A", label: "YouTube app only", description: "app", policyPreview: "YouTube only" }]),
  "A",
  { snapshot: ytSnapshot, carry: carryFor(ytSnapshot, "A"), priorPromiseText: YT_TEXT }
);
assertVerified(ytContent, "youtube-content pick", "YouTube videos anywhere");
eq(ytContent.scopeKind, "youtube_content", "youtube content scope survives app-only wording");
check((ytContent.contentBrands || []).includes("youtube"), "youtube brand kept");
record("YouTube content anywhere", ytContent, "youtube_content");

// ---------------------------------------------------------------------------
// 4. Short-form across apps.
// ---------------------------------------------------------------------------
const SHORTS = "no shorts for 2 hours";
const shortsT1 = turn1(SHORTS, [
  {
    id: "A",
    label: "All short-form surfaces",
    description: "Shorts, Reels, TikTok-style clips, and browser short video.",
    recommended: true,
    policyPreview: "All short-form video surfaces follow this commitment (not YouTube only).",
  },
  {
    id: "B",
    label: "YouTube Shorts only",
    description: "Only YouTube Shorts.",
    policyPreview: "Only YouTube Shorts follow this commitment.",
  },
], { duration: { kind: "fixed", value: 2, unit: "hours", until: null } });
const shortsSnapshot = androidSnapshot(shortsT1);
const shortsAll = pick(
  SHORTS,
  turn2Parsed(
    [
      { id: "A", label: "YouTube Shorts only", description: "YouTube", policyPreview: "YouTube Shorts only" },
    ],
    { duration: { kind: "fixed", value: 2, unit: "hours", until: null } }
  ),
  "A",
  { snapshot: shortsSnapshot, carry: carryFor(shortsSnapshot, "A"), priorPromiseText: SHORTS }
);
assertVerified(shortsAll, "short-form pick", "All short-form surfaces");
eq(shortsAll.scopeKind, "short_form_all_surfaces", "short-form scope kind");
for (const p of [PKG.youtube, PKG.instagram, PKG.chrome]) {
  check(shortsAll.scopePackages.includes(p), `short-form includes ${p}`);
}
check(
  shortsAll.contentRules.some((r) => r.contentType === "short_form_video" && r.action === "BLOCK"),
  "short-form block rule present"
);
record("short-form across apps", shortsAll, "short_form_all");

// ---------------------------------------------------------------------------
// 5. Non-video clarification: compound "Both together".
// ---------------------------------------------------------------------------
const COMPOUND = "allow only calculus, allow only monk 4 hrs";
const compoundT1 = normalizeCompiledPromise(
  basePolicy({ duration: { kind: "fixed", value: 4, unit: "hours", until: null } }),
  COMPOUND
);
const compoundSnapshot = androidSnapshot(compoundT1);
eq(compoundSnapshot.length, 3, "compound shows three options");
const compoundBoth = pick(
  COMPOUND,
  basePolicy({ duration: { kind: "fixed", value: 4, unit: "hours", until: null } }),
  "A",
  { snapshot: compoundSnapshot, carry: carryFor(compoundSnapshot, "A"), priorPromiseText: COMPOUND }
);
eq(compoundBoth.canStartCommitment, true, "compound Both together can start");
eq(compoundBoth.optionScopeKind, "compound_combine", "compound scope kind");
check(/verified/.test(compoundBoth.optionVerification), "compound verified from snapshot");
record("non-video: compound Both together", compoundBoth);

const compoundNoProof = pick(
  COMPOUND,
  basePolicy({ duration: { kind: "fixed", value: 4, unit: "hours", until: null } }),
  "A"
);
eq(compoundNoProof.canStartCommitment, false, "compound pick without snapshot fails closed");
fixtureDtos.compound_no_proof = compoundNoProof;

// ---------------------------------------------------------------------------
// 6. Adversarial cases.
// ---------------------------------------------------------------------------
// Option order changes between turns: resolution is by id against the snapshot.
const reordered = pick(
  MIN30,
  turn2Parsed([
    { id: "C", label: "All video apps and browsers", description: "", policyPreview: "all" },
    { id: "A", label: "YouTube app only", description: "", policyPreview: "YouTube only" },
  ]),
  "A",
  { snapshot: [...videoSnapshot].reverse(), carry: carryFor(videoSnapshot, "A") }
);
assertVerified(reordered, "reordered options", "All video apps and browsers");
eq(reordered.scopeKind, "all_video_apps", "reordered snapshot still resolves by id");

// Duplicate ids in the authoritative snapshot.
const dupSnapshot = [
  ...videoSnapshot,
  { ...videoSnapshot[1], id: "A" },
];
assertFailClosed(
  pick(MIN30, turn2Parsed(freshConflicting), "A", {
    snapshot: dupSnapshot,
    carry: carryFor(videoSnapshot, "A"),
  }),
  "duplicate snapshot ids",
  "snapshot_duplicate_ids"
);

// Missing snapshot and carry: the fresh same-id option must NOT authorize.
const noProof = pick(MIN30, turn2Parsed(freshConflicting), "A");
assertFailClosed(noProof, "no snapshot, no carry", "no_verifiable_choice");
fixtureDtos.min30_no_proof = noProof;

// Carry disagrees with its snapshot entry.
assertFailClosed(
  pick(MIN30, turn2Parsed(freshConflicting), "A", {
    snapshot: videoSnapshot,
    carry: { ...carryFor(videoSnapshot, "B"), id: "A" },
  }),
  "carry vs snapshot mismatch",
  "carry_snapshot_mismatch"
);

// Selected id missing from snapshot.
assertFailClosed(
  pick(MIN30, turn2Parsed(freshConflicting), "D", { snapshot: videoSnapshot }),
  "id not in snapshot",
  "snapshot_missing_id"
);

// Snapshot entry without a resolvable scope.
assertFailClosed(
  pick(MIN30, turn2Parsed(freshConflicting), "A", {
    snapshot: [{ id: "A", label: "Something else", description: "", policyPreview: "" }],
  }),
  "unresolvable snapshot entry",
  "snapshot_invalid_scope"
);

// Promise edited after the options were shown.
assertFailClosed(
  pick("do not allow videos shorter than 45 minutes for next 2 hours", turn2Parsed(freshConflicting), "A", {
    snapshot: videoSnapshot,
    carry: carryFor(videoSnapshot, "A"),
    priorPromiseText: MIN30,
  }),
  "promise edited after options",
  "promise_text_changed"
);

// Whitespace/case-only differences are the same promise.
assertVerified(
  pick(MIN30, turn2Parsed(freshConflicting), "A", {
    snapshot: videoSnapshot,
    priorPromiseText: `  ${MIN30.toUpperCase()}  `,
  }),
  "same promise modulo whitespace/case",
  "All video apps and browsers"
);

// Pure resolver: fresh options are not even an input.
const pure = resolveClarificationSelection({
  selectedId: "a",
  snapshot: videoSnapshot,
  carried: carryFor(videoSnapshot, "A"),
  priorPromiseText: MIN30,
  promiseText: MIN30,
});
eq(pure.ok, true, "pure resolver accepts lowercase id");
eq(pure.preview.scopeKind, "all_video_apps", "pure resolver scope");

// `--write-fixtures` refreshes the Android cross-stack fixtures from this exact output.
if (process.argv.includes("--write-fixtures")) {
  const fs = require("fs");
  const path = require("path");
  const dir = path.join(
    __dirname,
    "..",
    "app",
    "src",
    "test",
    "resources",
    "clarification-choice"
  );
  fs.mkdirSync(dir, { recursive: true });
  for (const [name, dto] of Object.entries(fixtureDtos)) {
    fs.writeFileSync(path.join(dir, `${name}.json`), `${JSON.stringify(dto, null, 2)}\n`);
  }
  console.log(`wrote ${Object.keys(fixtureDtos).length} fixtures to ${dir}`);
}

console.log(JSON.stringify({ event: "clarification_scope_families", families }, null, 2));
console.log(`ASSERTIONS_PASSED=${ASSERTIONS}`);
