/**
 * Offline smoke: typed scope contract v1 (prompt v09) on the compile path.
 * Run: node backend/smoke_scope_contract_v1.js [--write-fixtures]
 *
 * Contract under test:
 *   - Only v09 is the v1 contract; v06/v07/v08 stay "legacy" with no typed scope.
 *   - v1 primary + option scopes are validated against the original promise and
 *     rebuilt from canonical presets (model clauses are discarded).
 *   - Missing / unknown / malformed / unresolved v1 scope closes Start, and concrete
 *     clocks cannot reopen it.
 *   - A pick takes its typed scope ONLY from the verified snapshot entry; reordered or
 *     reused ids in fresh model output never change it; a carried option naming a
 *     different typed scope fails closed.
 */
const assert = require("assert");
const {
  normalizeCompiledPromise,
  scopeContractFor,
  canonicalizeScope,
  validateAndCanonicalizeScope,
  scopeOptionLabel,
  isEnforceableScope,
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

const V09 = "promise_compiler_v09";
const PROMISE = "No Google video shorter than 30 min for 1 hour";

function basePolicy(overrides = {}) {
  return {
    commitmentType: "focus_session",
    duration: { kind: "fixed", value: 1, unit: "hours", until: null },
    startCondition: "immediate",
    strictnessLevel: "STRICT",
    allowedApps: [],
    blockedApps: [],
    allowedContent: [],
    blockedContent: [{ type: "video", operator: "lt", value: 30, unit: "minutes" }],
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

/** A model-authored scope with deliberately sloppy clauses (Chrome pin, surfaces=ANY). */
function modelScope(kind, identity = "VIDEO_ANY", extra = {}) {
  return {
    version: 1,
    kind,
    resolution: "EXCLUSIVE",
    identity,
    include: [
      {
        id: "i1",
        host: kind === "VIDEO_CLASS_ANY_HOST" ? "ANY_HOST" : kind === "APP_ONLY" ? "NATIVE_APP" : "BROWSER",
        identity,
        surfaces: ["ANY"],
        packages: ["com.android.chrome"],
        packageMatch: "PINNED",
      },
    ],
    exempt: [],
    confidence: 0.8,
    appliesToText: "Chrome in .",
    evidenceSpans: ["Google video"],
    ...extra,
  };
}

const UNRESOLVED_SCOPE = {
  version: 1,
  kind: "UNRESOLVED",
  unresolvedReason: "HOST_AMBIGUOUS",
  evidenceSpans: ["Google video"],
};

function compile(parsed, opts = {}, promise = PROMISE) {
  return normalizeCompiledPromise(parsed, promise, { promptVersion: V09, ...opts });
}

function assertStartClosed(dto, label) {
  eq(dto.canStartCommitment, false, `${label}: Start disabled`);
  eq(dto.clarificationRequired, true, `${label}: clarification required`);
  check(!isEnforceableScope(dto.scope), `${label}: scope not enforceable`);
}

// ---------------------------------------------------------------------------
// 1. Contract identification
// ---------------------------------------------------------------------------
for (const v of ["promise_compiler_v06", "promise_compiler_v07", "promise_compiler_v08"]) {
  eq(scopeContractFor(v), "legacy", `${v} is the legacy contract`);
  const legacy = normalizeCompiledPromise(
    basePolicy({ scope: modelScope("BROWSER_VIDEO") }),
    PROMISE,
    { promptVersion: v }
  );
  eq(legacy.scopeContract, "legacy", `${v}: dto labelled legacy`);
  check(!("scope" in legacy), `${v}: no typed scope on the legacy path`);
}
eq(scopeContractFor(V09), "v1", "v09 is the v1 contract");

// ---------------------------------------------------------------------------
// 2. Primary scope: canonical, player-only, not Chrome-pinned
// ---------------------------------------------------------------------------
// "Google video" is genuinely ambiguous: a confident typed scope must not skip the question.
const googleConfident = compile(basePolicy({ scope: modelScope("BROWSER_VIDEO") }));
eq(googleConfident.canStartCommitment, false, "ambiguous 'Google video' still asks despite a typed scope");
eq(googleConfident.clarificationRequired, true, "ambiguous 'Google video' requires clarification");

const BROWSER_PROMISE = "No browser video shorter than 30 min for 1 hour";
const ALL_VIDEO_PROMISE = "No video shorter than 30 min in any app or browser for 1 hour";
const browserVideo = compile(
  basePolicy({ scope: modelScope("BROWSER_VIDEO", "VIDEO_ANY", { evidenceSpans: ["browser video"] }) }),
  {},
  BROWSER_PROMISE
);
eq(browserVideo.scopeContract, "v1", "v09 dto labelled v1");
eq(browserVideo.scope.kind, "BROWSER_VIDEO", "browser video kind kept");
const bvClause = browserVideo.scope.include[0];
eq(browserVideo.scope.include.length, 1, "one clause");
eq(bvClause.host, "BROWSER", "browser host");
eq(bvClause.identity, "VIDEO_ANY", "video identity");
eq(JSON.stringify(bvClause.surfaces), JSON.stringify(["PLAYER_ANY"]), "player-only surfaces");
eq(bvClause.packages.length, 0, "model Chrome pin discarded");
eq(bvClause.packageMatch, "CLASS_RESOLVED", "class resolved");
eq(browserVideo.scope.appliesToText, null, "model prose dropped");
eq(browserVideo.canStartCommitment, true, "valid v1 scope may start");

const allVideo = compile(
  basePolicy({ scope: modelScope("VIDEO_CLASS_ANY_HOST", "VIDEO_ANY", { evidenceSpans: ["any app or browser"] }) }),
  {},
  ALL_VIDEO_PROMISE
);
eq(allVideo.canStartCommitment, true, "valid all-video scope may start");
eq(allVideo.scope.include[0].host, "ANY_HOST", "all-video any host");
eq(JSON.stringify(allVideo.scope.include[0].surfaces), JSON.stringify(["PLAYER_ANY"]), "all-video is player-only");

// ---------------------------------------------------------------------------
// 3. Invalid v1 scope closes Start
// ---------------------------------------------------------------------------
const invalid = {
  missing: basePolicy(),
  unknownKind: basePolicy({ scope: modelScope("CHROME_ONLY") }),
  badVersion: basePolicy({ scope: modelScope("BROWSER_VIDEO", "VIDEO_ANY", { version: 2 }) }),
  notVerbatim: basePolicy({ scope: modelScope("BROWSER_VIDEO", "VIDEO_ANY", { evidenceSpans: ["every browser"] }) }),
  badSurface: basePolicy({
    scope: modelScope("BROWSER_VIDEO", "VIDEO_ANY", {
      include: [{ id: "i1", host: "BROWSER", identity: "VIDEO_ANY", surfaces: ["EVERYWHERE"] }],
    }),
  }),
  genericBrowserOnlyVideo: basePolicy({ scope: modelScope("BROWSER_ONLY") }),
  contentAnyHostVideo: basePolicy({ scope: modelScope("CONTENT_ANY_HOST") }),
  unresolved: basePolicy({ scope: UNRESOLVED_SCOPE }),
};
for (const [name, parsed] of Object.entries(invalid)) {
  assertStartClosed(compile(parsed), `invalid:${name}`);
}

// Concrete clocks (60 min + block < 30) must not reopen an unresolved scope.
const concreteButUnresolved = compile(
  basePolicy({ scope: UNRESOLVED_SCOPE, followUpQuestionRequired: false, confidence: 0.95 })
);
assertStartClosed(concreteButUnresolved, "concrete clocks + unresolved scope");

// ---------------------------------------------------------------------------
// 4. Turn 1 options: canonical scope + label
// ---------------------------------------------------------------------------
const turn1Options = [
  { id: "A", label: "Chrome only", description: "Browser video", scope: modelScope("BROWSER_VIDEO"), recommended: true },
  { id: "B", label: "Everything", description: "All video", scope: modelScope("VIDEO_CLASS_ANY_HOST") },
  { id: "C", label: "YT", description: "YouTube app", scope: modelScope("APP_ONLY", "YOUTUBE") },
];
const t1 = compile(
  basePolicy({
    scope: { ...UNRESOLVED_SCOPE, candidateScopes: turn1Options.map((o) => o.scope) },
    followUpQuestionRequired: true,
    followUpQuestion: "Where should this apply?",
    clarificationRequired: true,
    clarificationOptions: turn1Options,
  })
);
assertStartClosed(t1, "turn 1 asks");
eq(
  JSON.stringify(t1.clarificationOptions.map((o) => o.label)),
  JSON.stringify(["Video in any browser", "All video apps and browsers", "YouTube app only"]),
  "option labels come from structured scope"
);
eq(t1.clarificationOptions[0].scope.kind, "BROWSER_VIDEO", "option A scope canonical");
eq(t1.clarificationOptions[0].scope.include[0].packages.length, 0, "option A not Chrome-pinned");
for (const o of t1.clarificationOptions) {
  check(!/\b(?:com|org)\.[a-z]/i.test(o.label), `label ${o.id} has no package id`);
}
const snapshot = JSON.parse(JSON.stringify(t1.clarificationOptions));
const carry = (id) => snapshot.find((o) => o.id === id);

// ---------------------------------------------------------------------------
// 5. Turn 2 pick: snapshot authority survives reordering + reused ids
// ---------------------------------------------------------------------------
const reorderedFresh = [
  { id: "A", label: "YouTube app only", description: "x", scope: modelScope("APP_ONLY", "YOUTUBE") },
  { id: "B", label: "Browser video", description: "x", scope: modelScope("BROWSER_VIDEO") },
];
const pickA = compile(basePolicy({ clarificationOptions: reorderedFresh, scope: modelScope("APP_ONLY", "YOUTUBE") }), {
  selectedClarificationOptionId: "A",
  priorClarificationOptionsSnapshot: snapshot,
  selectedClarificationOptionFullText: carry("A"),
  priorClarificationPromiseText: PROMISE,
});
eq(pickA.scope.kind, "BROWSER_VIDEO", "pick A keeps the snapshot's browser-video scope");
eq(pickA.canStartCommitment, true, "verified pick may start");
check(/verified/.test(pickA.optionVerification), `pick A verified (${pickA.optionVerification})`);
check((pickA.cautionMessages || []).includes("Using your choice: Video in any browser"), "confirmation names the canonical label");

const pickB = compile(basePolicy({ clarificationOptions: reorderedFresh }), {
  selectedClarificationOptionId: "B",
  priorClarificationOptionsSnapshot: snapshot,
  selectedClarificationOptionFullText: carry("B"),
  priorClarificationPromiseText: PROMISE,
});
eq(pickB.scope.kind, "VIDEO_CLASS_ANY_HOST", "pick B is all video, not the fresh B");

const pickCNoCarry = compile(basePolicy({ clarificationOptions: reorderedFresh }), {
  selectedClarificationOptionId: "C",
  priorClarificationOptionsSnapshot: snapshot,
  priorClarificationPromiseText: PROMISE,
});
eq(pickCNoCarry.scope.kind, "APP_ONLY", "snapshot-only pick C is YouTube app only");

// ---------------------------------------------------------------------------
// 6. Fail closed: typed carry mismatch, invalid snapshot scope, edited promise, id only
// ---------------------------------------------------------------------------
const lyingCarry = { ...carry("A"), scope: modelScope("VIDEO_CLASS_ANY_HOST") };
const mismatch = compile(basePolicy({ clarificationOptions: reorderedFresh }), {
  selectedClarificationOptionId: "A",
  priorClarificationOptionsSnapshot: snapshot,
  selectedClarificationOptionFullText: lyingCarry,
  priorClarificationPromiseText: PROMISE,
});
assertStartClosed(mismatch, "typed carry mismatch");
eq(mismatch.optionVerification, "unrecognized_fail_closed", "typed carry mismatch verdict");
eq(mismatch.optionVerificationReason, "carry_snapshot_typed_scope_mismatch", "typed carry mismatch reason");
check(!(mismatch.cautionMessages || []).some((s) => /^Using your choice/i.test(s)), "no resolved-choice claim");

const brokenSnapshot = snapshot.map((o) => (o.id === "A" ? { ...o, scope: modelScope("CHROME_ONLY") } : o));
const broken = compile(basePolicy({ clarificationOptions: reorderedFresh }), {
  selectedClarificationOptionId: "A",
  priorClarificationOptionsSnapshot: brokenSnapshot,
  priorClarificationPromiseText: PROMISE,
});
assertStartClosed(broken, "invalid snapshot typed scope");
eq(broken.optionVerificationReason, "snapshot_invalid_typed_scope", "invalid snapshot scope reason");

const edited = compile(
  basePolicy({ clarificationOptions: reorderedFresh }),
  {
    selectedClarificationOptionId: "A",
    priorClarificationOptionsSnapshot: snapshot,
    selectedClarificationOptionFullText: carry("A"),
    priorClarificationPromiseText: PROMISE,
  },
  "No Google video shorter than 45 min for 2 hours"
);
assertStartClosed(edited, "edited promise invalidates the pick");
eq(edited.optionVerificationReason, "promise_text_changed", "edited promise reason");

const idOnly = compile(basePolicy({ clarificationOptions: reorderedFresh }), { selectedClarificationOptionId: "A" });
assertStartClosed(idOnly, "id-only pick");

// ---------------------------------------------------------------------------
// 7. Canonicalization is idempotent and label-stable
// ---------------------------------------------------------------------------
for (const dto of [browserVideo, allVideo, pickA, pickB, pickCNoCarry]) {
  eq(JSON.stringify(canonicalizeScope(dto.scope)), JSON.stringify(dto.scope), `idempotent ${dto.scope.kind}`);
}
eq(
  scopeOptionLabel(validateAndCanonicalizeScope(modelScope("BROWSER_VIDEO", "VIDEO_ANY", { appliesToText: "whatever", evidenceSpans: ["browser video"] }), BROWSER_PROMISE)),
  scopeOptionLabel(browserVideo.scope),
  "label ignores model prose"
);

const socialAnyScope = canonicalizeScope(
  modelScope("SOCIAL_FEED_AND_REELS_ONLY", "SOCIAL_ANY", { evidenceSpans: ["social apps"] })
);
const socialAnyLabel = scopeOptionLabel(socialAnyScope);
for (const service of ["Instagram", "Facebook", "Messenger", "TikTok", "Snapchat", "WhatsApp"]) {
  check(socialAnyLabel.includes(service), `SOCIAL_ANY option names supported service ${service}`);
}
check(socialAnyLabel.includes("Recognized social apps/sites only"), "SOCIAL_ANY option discloses finite coverage");

const packagePinnedScope = validateAndCanonicalizeScope(
  modelScope("PACKAGE_PINNED", "ANY", {
    evidenceSpans: [],
    include: [{
      id: "i1",
      host: "ANY_HOST",
      identity: "ANY",
      surfaces: ["ANY"],
      sites: [],
      packages: ["com.discord"],
      packageMatch: "PINNED",
    }],
  }),
  "Block Discord for one hour"
);
eq(scopeOptionLabel(packagePinnedScope), "Discord only", "PACKAGE_PINNED option discloses verified app names");

// ---------------------------------------------------------------------------
// 8. Local installed-app selection is exposed only for the sole missing target
// ---------------------------------------------------------------------------
const unknownAppPromise = "Block BeReal for 1 hour";
const unknownAppScope = {
  version: 1,
  kind: "PACKAGE_PINNED",
  resolution: "EXCLUSIVE",
  identity: "ANY",
  include: [{
    id: "i1",
    host: "ANY_HOST",
    identity: "ANY",
    surfaces: ["ANY"],
    sites: [],
    packages: ["com.bereal.app"],
    packageMatch: "PINNED",
  }],
  exempt: [],
  confidence: 0.9,
  appliesToText: null,
  evidenceSpans: [],
};
const unknownAppCompile = (overrides = {}, promise = unknownAppPromise) =>
  compile(basePolicy({
    blockedContent: [],
    blockedApps: [{ packageName: "com.bereal.app", appLabel: "BeReal", scope: "all" }],
    scope: unknownAppScope,
    ...overrides,
  }), {}, promise);

const localAppCandidate = unknownAppCompile();
assertStartClosed(localAppCandidate, "unknown local app target remains gated before user picks");
eq(localAppCandidate.localAppTargetSelection?.kind, "WHOLE_APP_BLOCK", "sole target gap emits local whole-app picker hint");
eq(localAppCandidate.localAppTargetSelection?.label, "BeReal", "picker hint contains only the user-named label");
check(!JSON.stringify(localAppCandidate.localAppTargetSelection).includes("com.bereal.app"), "picker hint does not expose model package ID");
eq(unknownAppCompile({ scope: { ...unknownAppScope, include: [
  { ...unknownAppScope.include[0], surfaces: ["PLAYER_ANY"] },
] } }).localAppTargetSelection, undefined, "surface-constrained model scope gets no whole-app picker hint");
eq(unknownAppCompile({ scope: { ...unknownAppScope, include: [
  unknownAppScope.include[0], { ...unknownAppScope.include[0], id: "i2", packages: ["com.example.other"] },
] } }).localAppTargetSelection, undefined, "multi-clause model scope gets no single-app picker hint");
eq(unknownAppCompile({ scope: { ...unknownAppScope, exempt: [{ id: "e1", packages: ["com.example.other"] }] } }).localAppTargetSelection, undefined, "excluded-package model scope gets no single-app picker hint");

eq(unknownAppCompile({}, "Block Discord for 1 hour").localAppTargetSelection, undefined, "different user target gets no picker hint");
eq(unknownAppCompile({ blockedContent: [{ type: "video", operator: "gt", value: 40, unit: "minutes" }] }).localAppTargetSelection, undefined, "mixed content policy gets no picker hint");
eq(unknownAppCompile({ clarificationRequired: true, followUpQuestionRequired: true }).localAppTargetSelection, undefined, "pre-existing ambiguity gets no picker hint");
eq(
  unknownAppCompile(
    { duration: { kind: "fixed", value: 1, unit: "years", until: null } },
    "Block BeReal for 1 year"
  ).localAppTargetSelection,
  undefined,
  "permanent strong-confirmation promise gets no picker hint"
);
eq(unknownAppCompile({ blockedApps: [
  { packageName: "com.bereal.app", appLabel: "BeReal", scope: "all" },
  { packageName: "com.example.second", appLabel: "Second", scope: "all" },
] }).localAppTargetSelection, undefined, "multiple app actions get no picker hint");
eq(unknownAppCompile({ blockedApps: [{ packageName: "com.bereal.app", appLabel: "BeReal", scope: "shorts" }] }).localAppTargetSelection, undefined, "surface-only target gets no whole-app picker hint");
eq(unknownAppCompile({ blockedApps: [{ packageName: "com.bereal.app", appLabel: "Discord", scope: "all" }] }).localAppTargetSelection, undefined, "model label must exactly occur in the promise");

// ---------------------------------------------------------------------------
// Cross-language fixtures (Android: TypedScopeContractFixtureTest)
// ---------------------------------------------------------------------------
if (process.argv.includes("--write-fixtures")) {
  const fs = require("fs");
  const path = require("path");
  const dir = path.join(__dirname, "..", "app", "src", "test", "resources", "scope-contract");
  fs.mkdirSync(dir, { recursive: true });
  const fixtures = {
    browser_video_primary: {
      promise: BROWSER_PROMISE,
      modelScope: modelScope("BROWSER_VIDEO", "VIDEO_ANY", { evidenceSpans: ["browser video"] }),
      dto: browserVideo,
    },
    all_video_primary: {
      promise: ALL_VIDEO_PROMISE,
      modelScope: modelScope("VIDEO_CLASS_ANY_HOST", "VIDEO_ANY", { evidenceSpans: ["any app or browser"] }),
      dto: allVideo,
    },
    pick_a_browser_video: { modelScope: turn1Options[0].scope, dto: pickA },
    pick_b_all_video: { modelScope: turn1Options[1].scope, dto: pickB },
    pick_c_youtube_app: { modelScope: turn1Options[2].scope, dto: pickCNoCarry },
    turn1_options: { modelScope: UNRESOLVED_SCOPE, dto: t1 },
    typed_carry_mismatch: { modelScope: UNRESOLVED_SCOPE, dto: mismatch },
    unresolved_concrete_clocks: { modelScope: UNRESOLVED_SCOPE, dto: concreteButUnresolved },
  };
  for (const [name, f] of Object.entries(fixtures)) {
    fs.writeFileSync(
      path.join(dir, `${name}.json`),
      JSON.stringify({ promise: f.promise || PROMISE, modelScope: f.modelScope, response: f.dto }, null, 2) + "\n"
    );
  }
  console.log(`wrote ${Object.keys(fixtures).length} fixtures to ${dir}`);
}

console.log(`ASSERTIONS_PASSED=${ASSERTIONS}`);
