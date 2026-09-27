/**
 * Offline smoke: v09 clarification picks -> typed scope (no Azure, no network).
 * Run: node backend/smoke_v09_clarification_scope.js [--write-fixtures]
 *
 * Contract under test (docs/v09-typed-scope-clarification-loop-2026-09-26-v2.md +
 * docs/v09-typed-scope-security-addendum-2026-09-26.md):
 *   - A compound A/B/C pick is verified against the snapshot + prior promise BEFORE
 *     clause narrowing or any model call; failures return a fail-closed re-ask and the
 *     model is never invoked with narrowed text.
 *   - A verified compound pick takes its typed scope from the parse of the exact text
 *     compiled (full text for A, narrowed clause for B/C); `compound_combine` is never
 *     bridged to a scope.
 *   - Only allowlisted deterministic families (Google video, bare shorts) bridge
 *     scopeKind -> typed scope, and only when the builder re-derives the identical option.
 *   - Model-authored / regex-fallback options never gain scope from a scopeKind string.
 *   - Unknown / unsupported scopes stay UNRESOLVED and keep Start closed.
 */
process.env.PROMISE_COMPILER_PROMPT_VERSION = "promise_compiler_v09";

const assert = require("assert");
const svc = require("./promiseCompilerService");
const {
  compilePromise,
  normalizeCompiledPromise,
  setCompilerTransportForTests,
  verifyCompoundClauseSelection,
  matchDeterministicScopeOption,
  inferShortFormScopeClarification,
  inferGoogleVideoClarification,
  canonicalizeScope,
  DETERMINISTIC_SCOPE_BRIDGE,
  PROMPT_VERSION,
} = svc;

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

eq(PROMPT_VERSION, "promise_compiler_v09", "smoke must run the v09 contract");

const GOOGLE = "No Google video shorter than 30 min for 1 hour";
const SHORTS = "I waste my evenings on shorts";
const COMPOUND = "allow only YouTube, allow only Instagram";
const COMPOUND_B_TEXT = "allow only YouTube";
const COMPOUND_C_TEXT = "allow only Instagram";

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

function modelScope(kind, identity, evidence) {
  return {
    version: 1,
    kind,
    resolution: "EXCLUSIVE",
    identity,
    include: [{ id: "i1", host: "NATIVE_APP", identity, surfaces: ["ANY"], packages: ["com.android.chrome"], packageMatch: "PINNED" }],
    exempt: [],
    confidence: 0.8,
    evidenceSpans: [evidence],
  };
}

const UNRESOLVED = {
  version: 1,
  kind: "UNRESOLVED",
  unresolvedReason: "HOST_AMBIGUOUS",
  resolution: "EXCLUSIVE",
  identity: null,
  include: [],
  exempt: [],
  confidence: 0.3,
  evidenceSpans: [],
};

/** Runs compilePromise with a fake model. Returns { dto, calls }. */
async function compile(text, options, modelOutput) {
  const calls = [];
  setCompilerTransportForTests(async ({ userPayload, compileText }) => {
    calls.push({ userPayload, compileText });
    return JSON.stringify(typeof modelOutput === "function" ? modelOutput(compileText) : modelOutput);
  });
  const origLog = console.log;
  console.log = () => {};
  try {
    const dto = await compilePromise(text, options || {});
    return { dto, calls };
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

const clone = (x) => JSON.parse(JSON.stringify(x));
const ids = (opts) => (opts || []).map((o) => String(o.id).toUpperCase());
const scopeOf = (dto) => dto.scope || {};
const clause = (dto) => (scopeOf(dto).include || [])[0] || {};

function pickOptions(text, snapshot, id, extra = {}) {
  const carried = snapshot.find((o) => String(o.id).toUpperCase() === id) || null;
  return {
    selectedClarificationOptionId: id,
    priorClarificationOptionsSnapshot: clone(snapshot),
    selectedClarificationOptionFullText: carried ? clone(carried) : null,
    priorClarificationPromiseText: text,
    ...extra,
  };
}

function assertFailClosed(dto, calls, reason, label) {
  eq(calls.length, 0, `${label}: model must not be called`);
  eq(dto.meta && dto.meta.modelCalled, false, `${label}: meta.modelCalled=false`);
  eq(dto.canStartCommitment, false, `${label}: Start closed`);
  eq(dto.clarificationRequired, true, `${label}: re-ask`);
  eq(dto.optionVerification, "unrecognized_fail_closed", `${label}: fail-closed verdict`);
  eq(dto.optionVerificationReason, reason, `${label}: specific reason`);
  deepEq(ids(dto.clarificationOptions), ["A", "B", "C"], `${label}: fresh server-built options`);
  eq(dto.clarificationOptions[0].label, "Both together", `${label}: options are the server compound builder's`);
  eq((dto.scope || {}).kind, "UNRESOLVED", `${label}: no typed scope`);
}

/** Real compilePromise round-trips exported for Android TypedScopeContractFixtureTest. */
const FIXTURES = {};

(async () => {
  // ========== 1. Google video: turn-1 snapshot round-trips, A/B/C bridge ==========
  const g1 = await compile(GOOGLE, {}, basePolicy({
    scope: UNRESOLVED,
    followUpQuestionRequired: true,
    blockedContent: [{ type: "video", operator: "lt", value: 30, unit: "minutes" }],
  }));
  eq(g1.calls.length, 1, "turn 1 calls the model once");
  eq(g1.dto.canStartCommitment, false, "turn 1 ambiguous -> Start closed");
  const gSnap = g1.dto.clarificationOptions;
  deepEq(ids(gSnap), ["A", "B", "C"], "turn 1 shows Google A/B/C");
  eq(gSnap[0].scope.kind, "BROWSER_VIDEO", "displayed A carries bridged BROWSER_VIDEO");
  eq(gSnap[1].scope.kind, "VIDEO_CLASS_ANY_HOST", "displayed B carries VIDEO_CLASS_ANY_HOST");
  eq(gSnap[2].scope.kind, "APP_ONLY", "displayed C carries APP_ONLY");
  // Server-displayed options (with v1 labels) must be recognized by the recompute.
  for (const opt of gSnap) {
    check(matchDeterministicScopeOption(opt, GOOGLE), `displayed ${opt.id} re-derives deterministically`);
  }

  const expectGoogle = {
    A: { kind: "BROWSER_VIDEO", identity: "VIDEO_ANY", host: "BROWSER" },
    B: { kind: "VIDEO_CLASS_ANY_HOST", identity: "VIDEO_ANY", host: "ANY_HOST" },
    C: { kind: "APP_ONLY", identity: "YOUTUBE", host: "NATIVE_APP" },
  };
  for (const id of ["A", "B", "C"]) {
    // Model turn-2 output names a DIFFERENT scope; the verified pick must win.
    const r = await compile(GOOGLE, pickOptions(GOOGLE, gSnap, id), basePolicy({
      scope: modelScope("APP_ONLY", "YOUTUBE", "Google video"),
      blockedContent: [{ type: "video", operator: "lt", value: 30, unit: "minutes" }],
    }));
    const want = expectGoogle[id];
    eq(r.calls.length, 1, `google ${id}: model called once`);
    eq(r.calls[0].userPayload.userPromise, GOOGLE, `google ${id}: full promise compiled`);
    eq(r.dto.optionVerification, "snapshot_and_carry_verified", `google ${id}: verified`);
    eq(scopeOf(r.dto).kind, want.kind, `google ${id}: typed scope kind`);
    eq(scopeOf(r.dto).identity, want.identity, `google ${id}: identity`);
    eq(clause(r.dto).host, want.host, `google ${id}: host`);
    eq(r.dto.canStartCommitment, true, `google ${id}: Start opens after verified pick`);
    if (id === "B") FIXTURES.v09_google_pick_b_bridged = { promise: GOOGLE, modelScope: gSnap[1].scope, dto: r.dto };
  }
  // chrome_browser -> BROWSER_VIDEO is an explicit, intended broadening from Chrome-pinned
  // to any verified browser (v1 contract only).
  deepEq(DETERMINISTIC_SCOPE_BRIDGE.chrome_browser, { kind: "BROWSER_VIDEO", identity: "VIDEO_ANY" }, "chrome_browser bridge target is explicit");
  {
    const r = await compile(GOOGLE, pickOptions(GOOGLE, gSnap, "A"), basePolicy({ scope: UNRESOLVED }));
    deepEq(clause(r.dto).packages || [], [], "BROWSER_VIDEO A: no Chrome package pin");
    deepEq(clause(r.dto).surfaces, ["PLAYER_ANY"], "BROWSER_VIDEO A: player surfaces only");
  }
  {
    // Legacy contract keeps the Chrome-pinned package list (unchanged behaviour).
    const legacySnap = quietNormalize(basePolicy({ followUpQuestionRequired: true }), GOOGLE, { promptVersion: "promise_compiler_v07" }).clarificationOptions;
    const legacy = quietNormalize(basePolicy(), GOOGLE, { promptVersion: "promise_compiler_v07", ...pickOptions(GOOGLE, legacySnap, "A") });
    eq(legacy.scopeContract, "legacy", "v07 stays legacy");
    check((legacy.scopePackages || []).includes("com.android.chrome"), "v07 Chrome pick still pins Chrome");
    eq(legacy.scope, undefined, "v07 carries no typed scope");
  }

  // Pick without prior promise text: no deterministic bridge; option's own scope is
  // validated (existing model path). Strip it and the pick fails closed.
  {
    const noPrior = pickOptions(GOOGLE, gSnap, "B", { priorClarificationPromiseText: undefined });
    const r = await compile(GOOGLE, noPrior, basePolicy({ scope: UNRESOLVED }));
    eq(scopeOf(r.dto).kind, "VIDEO_CLASS_ANY_HOST", "no prior text: option's own validated scope");
    const stripped = clone(gSnap).map(({ scope: _s, ...rest }) => rest);
    const r2 = await compile(GOOGLE, {
      selectedClarificationOptionId: "B",
      priorClarificationOptionsSnapshot: stripped,
      selectedClarificationOptionFullText: clone(stripped[1]),
    }, basePolicy({ scope: UNRESOLVED }));
    eq(scopeOf(r2.dto).kind, "UNRESOLVED", "no prior text + no own scope: not bridged");
    eq(r2.dto.canStartCommitment, false, "no prior text + no own scope: Start closed");
    eq(r2.dto.optionVerificationReason, "snapshot_invalid_typed_scope", "no prior text: specific reason");
  }
  // Own scope that disagrees with the re-derived deterministic scope -> fail closed.
  {
    const forged = clone(gSnap);
    forged[1].scope = modelScope("APP_ONLY", "YOUTUBE", "Google video");
    const r = await compile(GOOGLE, pickOptions(GOOGLE, forged, "B"), basePolicy({ scope: UNRESOLVED }));
    eq(r.dto.optionVerificationReason, "snapshot_typed_scope_not_reproducible", "forged own scope vs bridge: fail closed");
    eq(r.dto.canStartCommitment, false, "forged own scope: Start closed");
    eq(scopeOf(r.dto).kind, "UNRESOLVED", "forged own scope: UNRESOLVED");
  }
  // Tampered visible field on a Google option -> not deterministic -> no bridge.
  for (const [field, value] of [
    ["description", "Apply rules to everything on the phone."],
    ["policyPreview", "Everything is blocked."],
    ["recommended", true],
  ]) {
    const tampered = clone(gSnap).map(({ scope: _s, ...rest }) => rest);
    tampered[1][field] = value;
    if (field === "policyPreview") tampered[1].resultingPolicyPreview = value;
    const r = await compile(GOOGLE, pickOptions(GOOGLE, tampered, "B"), basePolicy({ scope: UNRESOLVED }));
    eq(scopeOf(r.dto).kind, "UNRESOLVED", `google tampered ${field}: no bridge`);
    eq(r.dto.canStartCommitment, false, `google tampered ${field}: Start closed`);
  }
  // Tampered internalPolicyPreview (scopeKind swap) -> no bridge.
  {
    const tampered = clone(gSnap).map(({ scope: _s, ...rest }) => rest);
    tampered[2].internalPolicyPreview = clone(tampered[1].internalPolicyPreview);
    const r = await compile(GOOGLE, pickOptions(GOOGLE, tampered, "C"), basePolicy({ scope: UNRESOLVED }));
    eq(scopeOf(r.dto).kind, "UNRESOLVED", "google tampered internalPolicyPreview: no bridge");
  }

  // ========== 2. Short-form: text-only eligibility + A/B bridge ==========
  check(inferShortFormScopeClarification(SHORTS), "bare shorts -> clarify");
  check(inferShortFormScopeClarification("shorts"), "single word shorts -> clarify");
  for (const t of [
    "10 shorts today",
    "max ten shorts",
    "no shorts",
    "block shorts for 1 hour",
    "only 3 shorts a day",
    "youtube shorts only",
    "only youtube shorts",
    "I watch too much YouTube",
    "",
  ]) {
    eq(inferShortFormScopeClarification(t), null, `not bare shorts: "${t}"`);
  }
  eq(inferShortFormScopeClarification.length, 1, "eligibility takes promise text only");
  deepEq(inferShortFormScopeClarification(SHORTS), inferShortFormScopeClarification(SHORTS), "eligibility deterministic");

  const s1 = await compile(SHORTS, {}, basePolicy({ scope: UNRESOLVED, followUpQuestionRequired: true }));
  const sSnap = s1.dto.clarificationOptions;
  deepEq(ids(sSnap), ["A", "B"], "shorts turn 1 shows A/B");
  {
    const r = await compile(SHORTS, pickOptions(SHORTS, sSnap, "A"), basePolicy({ scope: UNRESOLVED }));
    eq(scopeOf(r.dto).kind, "SHORT_FORM_ANY_HOST", "shorts A -> SHORT_FORM_ANY_HOST");
    eq(r.dto.canStartCommitment, true, "shorts A: Start opens");
    FIXTURES.v09_shorts_pick_a_all_surfaces = { promise: SHORTS, modelScope: sSnap[0].scope, dto: r.dto };
  }
  {
    const r = await compile(SHORTS, pickOptions(SHORTS, sSnap, "B"), basePolicy({ scope: UNRESOLVED }));
    eq(scopeOf(r.dto).kind, "APP_SHORT_FORM_ONLY", "shorts B -> APP_SHORT_FORM_ONLY (not APP_ONLY)");
    eq(scopeOf(r.dto).identity, "YOUTUBE", "shorts B identity YouTube");
    deepEq(clause(r.dto).surfaces, ["PLAYER_SHORT"], "shorts B: short player only (long-form out of scope)");
    eq(clause(r.dto).host, "NATIVE_APP", "shorts B: native app host");
    deepEq(clause(r.dto).packages, ["com.google.android.youtube"], "shorts B: YouTube package pinned");
    eq(sSnap[1].label, "YouTube short videos only", "shorts B displayed with its canonical label");
    eq(r.dto.canStartCommitment, true, "shorts B: Start opens");
    FIXTURES.v09_shorts_pick_b_youtube_shorts = { promise: SHORTS, modelScope: sSnap[1].scope, dto: r.dto };
  }
  // New kind: canonical, and only for short-form app identities.
  {
    const ok = canonicalizeScope({ version: 1, kind: "APP_SHORT_FORM_ONLY", identity: "INSTAGRAM", include: [{ id: "i1", host: "ANY_HOST", identity: "INSTAGRAM", surfaces: ["ANY"] }], exempt: [], evidenceSpans: ["reels"] });
    eq(ok.kind, "APP_SHORT_FORM_ONLY", "APP_SHORT_FORM_ONLY(INSTAGRAM) canonical");
    deepEq(ok.include[0].surfaces, ["PLAYER_SHORT"], "model clauses replaced by preset");
    for (const bad of ["VIDEO_ANY", "WHATSAPP", "SHORT_FORM_ANY"]) {
      const r = canonicalizeScope({ version: 1, kind: "APP_SHORT_FORM_ONLY", identity: bad, include: [{ id: "i1", host: "ANY_HOST", identity: bad, surfaces: ["ANY"] }], exempt: [], evidenceSpans: ["x"] });
      eq(r.kind, "UNRESOLVED", `APP_SHORT_FORM_ONLY(${bad}) stays UNRESOLVED`);
    }
  }

  // ========== 3. Model-authored / regex-fallback / unknown options are never bridged ==========
  {
    // Model options that mention Chrome get Google previews merged in (same scopeKind),
    // but their visible text is model-authored -> must not bridge.
    const modelOpts = [
      { id: "A", label: "Chrome videos", description: "Model wrote this.", recommended: true },
      { id: "B", label: "All video apps", description: "Model wrote this too.", recommended: false },
      { id: "C", label: "YouTube", description: "Model again.", recommended: false },
    ];
    const t1 = await compile(GOOGLE, {}, basePolicy({ scope: UNRESOLVED, followUpQuestionRequired: true, clarificationOptions: modelOpts }));
    const snap = t1.dto.clarificationOptions;
    eq(snap[1].internalPolicyPreview && snap[1].internalPolicyPreview.scopeKind, "all_video_apps", "model option carries matching scopeKind");
    eq(snap[1].scope.kind, "UNRESOLVED", "model option with matching scopeKind: not bridged on display");
    const r = await compile(GOOGLE, pickOptions(GOOGLE, snap, "B"), basePolicy({ scope: UNRESOLVED }));
    eq(scopeOf(r.dto).kind, "UNRESOLVED", "model option pick: not bridged");
    eq(r.dto.optionVerificationReason, "snapshot_invalid_typed_scope", "model option pick: specific reason");
    eq(r.dto.canStartCommitment, false, "model option pick: Start closed");
  }
  {
    // Regex fallback: preview derived from label prose on a non-deterministic promise.
    const text = "Limit my video watching for 1 hour";
    const t1 = await compile(text, {}, basePolicy({
      scope: UNRESOLVED,
      followUpQuestionRequired: true,
      clarificationOptions: [
        { id: "A", label: "All video apps and browsers", description: "Every video app.", recommended: true },
        { id: "B", label: "YouTube only", description: "Just YouTube.", recommended: false },
      ],
    }));
    const snap = t1.dto.clarificationOptions;
    check(snap[0].internalPolicyPreview, "regex fallback attached a preview");
    eq(snap[0].scope.kind, "UNRESOLVED", "regex fallback option: no typed scope");
    const r = await compile(text, pickOptions(text, snap, "A"), basePolicy({ scope: UNRESOLVED }));
    eq(scopeOf(r.dto).kind, "UNRESOLVED", "regex fallback pick: not bridged");
    eq(r.dto.canStartCommitment, false, "regex fallback pick: Start closed");
  }
  {
    // Unknown scopeKind + unknown typed kind stay UNRESOLVED.
    const text = "Limit my video watching for 1 hour";
    const snap = [
      { id: "A", label: "Weird", description: "d", recommended: true, policyPreview: "p",
        internalPolicyPreview: { scopeKind: "tiktok_only_weird", appliesTo: "x", packages: [{ packageName: "com.zhiliaoapp.musically", appLabel: "TikTok" }] } },
      { id: "B", label: "Weirder", description: "d", recommended: false, policyPreview: "p",
        internalPolicyPreview: { scopeKind: "all_video_apps", appliesTo: "x", packages: [] },
        scope: { ...modelScope("TELEPORT_ONLY", "VIDEO_ANY", "video"), kind: "TELEPORT_ONLY" } },
    ];
    for (const id of ["A", "B"]) {
      const r = await compile(text, pickOptions(text, snap, id), basePolicy({ scope: UNRESOLVED }));
      eq(scopeOf(r.dto).kind, "UNRESOLVED", `unknown scope ${id}: UNRESOLVED`);
      eq(r.dto.canStartCommitment, false, `unknown scope ${id}: Start closed`);
    }
    eq(matchDeterministicScopeOption(snap[1], text), null, "matching scopeKind on non-deterministic promise: no bridge");
  }

  // ========== 4. Compound: valid A/B/C ==========
  const c1 = await compile(COMPOUND, {}, basePolicy({ scope: UNRESOLVED, followUpQuestionRequired: true }));
  const cSnap = c1.dto.clarificationOptions;
  deepEq(ids(cSnap), ["A", "B", "C"], "compound turn 1 A/B/C");
  eq(cSnap[0].internalPolicyPreview.scopeKind, "compound_combine", "A is intent, not scope");
  eq(cSnap[0].scope.kind, "UNRESOLVED", "compound_combine is never bridged to a scope");
  eq(matchDeterministicScopeOption(cSnap[0], COMPOUND), null, "compound_combine: no bridge");

  const compoundModel = (compiled) => {
    if (compiled === COMPOUND_B_TEXT) return basePolicy({ scope: modelScope("APP_ONLY", "YOUTUBE", "YouTube") });
    if (compiled === COMPOUND_C_TEXT) return basePolicy({ scope: modelScope("APP_ONLY", "INSTAGRAM", "Instagram") });
    return basePolicy({ scope: modelScope("APP_ONLY", "YOUTUBE", "YouTube") });
  };
  {
    const r = await compile(COMPOUND, pickOptions(COMPOUND, cSnap, "B"), compoundModel);
    eq(r.calls.length, 1, "compound B: model called once");
    eq(r.calls[0].userPayload.userPromise, COMPOUND_B_TEXT, "compound B: narrowed first clause compiled");
    eq(r.dto.optionVerification, "compound_clause_verified", "compound B verified");
    eq(r.dto.selectedClarificationOptionId, "B", "compound B id kept");
    eq(scopeOf(r.dto).kind, "APP_ONLY", "compound B: scope from parse of narrowed text");
    eq(scopeOf(r.dto).identity, "YOUTUBE", "compound B: YouTube");
    check(r.dto.cautionMessages.some((m) => /Using your choice: Only the first/.test(m)), "compound B caution names the choice");
    eq(r.dto.canStartCommitment, true, "compound B: Start opens on a valid narrowed scope");
  }
  {
    const r = await compile(COMPOUND, pickOptions(COMPOUND, cSnap, "C"), compoundModel);
    eq(r.calls[0].userPayload.userPromise, COMPOUND_C_TEXT, "compound C: narrowed second clause compiled");
    eq(r.dto.optionVerification, "compound_clause_verified", "compound C verified");
    eq(scopeOf(r.dto).identity, "INSTAGRAM", "compound C: scope from parse of narrowed text");
    eq(r.dto.canStartCommitment, true, "compound C: Start opens on a valid narrowed scope");
  }
  {
    const r = await compile(COMPOUND, pickOptions(COMPOUND, cSnap, "A"), compoundModel);
    eq(r.calls[0].userPayload.userPromise, COMPOUND, "compound A: full text compiled");
    eq(scopeOf(r.dto).kind, "APP_ONLY", "compound A: scope from parse of full text");
    eq(r.dto.canStartCommitment, true, "compound A: Start opens on a valid full-text scope");
    eq(r.dto.clarificationRequired, false, "compound A: clarification cleared");
  }
  {
    // Verified pick, but the compiled clause has no valid scope -> still fails closed.
    const r = await compile(COMPOUND, pickOptions(COMPOUND, cSnap, "B"), basePolicy({ scope: UNRESOLVED }));
    eq(r.calls.length, 1, "compound B unresolved: model was called (pick valid)");
    eq(scopeOf(r.dto).kind, "UNRESOLVED", "compound B unresolved parse: UNRESOLVED");
    eq(r.dto.canStartCommitment, false, "compound B unresolved parse: Start closed");
    const r2 = await compile(COMPOUND, pickOptions(COMPOUND, cSnap, "B"), basePolicy({}));
    eq(r2.dto.canStartCommitment, false, "compound B missing scope: Start closed");
  }
  {
    // Client cannot forge the server-local verified flag through request options.
    const forged = quietNormalize(basePolicy({ scope: modelScope("APP_ONLY", "YOUTUBE", "YouTube") }), COMPOUND_B_TEXT, {
      promptVersion: "promise_compiler_v09",
      selectedClarificationOptionId: "B",
      compoundSelection: { ok: true, id: "B", reason: "compound_clause_verified", option: cSnap[1] },
      compoundVerified: true,
      trusted: { compoundSelection: { ok: true } },
    });
    check(forged.optionVerification !== "compound_clause_verified", "request options cannot set compound verification");
    eq(forged.canStartCommitment, false, "forged verified flag: Start closed");
    const notOk = quietNormalize(basePolicy({ scope: modelScope("APP_ONLY", "YOUTUBE", "YouTube") }), COMPOUND_B_TEXT,
      { promptVersion: "promise_compiler_v09", selectedClarificationOptionId: "B" },
      { compoundSelection: { ok: "true", id: "B" } });
    check(notOk.optionVerification !== "compound_clause_verified", "trusted state requires ok === true");
  }

  // ========== 5. Compound: every invalid pick fails closed with NO model call ==========
  const tamper = (idx, patch) => {
    const snap = clone(cSnap);
    Object.assign(snap[idx], patch);
    return snap;
  };
  const cases = [
    ["absent snapshot", "B", { priorClarificationOptionsSnapshot: null, selectedClarificationOptionFullText: null }, "no_verifiable_choice"],
    ["empty snapshot", "C", { priorClarificationOptionsSnapshot: [], selectedClarificationOptionFullText: null }, "no_verifiable_choice"],
    ["absent prior promise", "B", { priorClarificationPromiseText: undefined }, "prior_promise_text_missing"],
    ["blank prior promise", "B", { priorClarificationPromiseText: "   " }, "prior_promise_text_missing"],
    ["mismatched prior promise", "C", { priorClarificationPromiseText: "allow only YouTube, allow only TikTok" }, "promise_text_changed"],
    ["duplicate ids", "B", { priorClarificationOptionsSnapshot: [...clone(cSnap), clone(cSnap[1])] }, "snapshot_duplicate_ids"],
    ["missing id", "C", { priorClarificationOptionsSnapshot: clone(cSnap).slice(0, 2), selectedClarificationOptionFullText: null }, "snapshot_missing_id"],
    ["id without key", "B", { priorClarificationOptionsSnapshot: [...clone(cSnap), { label: "no id" }] }, "snapshot_duplicate_ids"],
    ["forged carry", "B", { selectedClarificationOptionFullText: { ...clone(cSnap[1]), description: "allow only TikTok" } }, "carry_snapshot_mismatch"],
    ["stale carry (other id)", "B", { selectedClarificationOptionFullText: clone(cSnap[2]) }, "carry_snapshot_mismatch"],
    ["stale carry (other promise)", "B", { selectedClarificationOptionFullText: clone(gSnap[1]) }, "carry_snapshot_mismatch"],
    ["array carry", "B", { selectedClarificationOptionFullText: ["B"] }, "carry_snapshot_mismatch"],
    ["tampered label", "B", { priorClarificationOptionsSnapshot: tamper(1, { label: "Only the second" }), selectedClarificationOptionFullText: null }, "snapshot_not_reproducible"],
    ["tampered description", "B", { priorClarificationOptionsSnapshot: tamper(1, { description: "allow only TikTok" }), selectedClarificationOptionFullText: null }, "snapshot_not_reproducible"],
    ["tampered policyPreview", "C", { priorClarificationOptionsSnapshot: tamper(2, { policyPreview: "Enforce only: nothing", resultingPolicyPreview: "Enforce only: nothing" }), selectedClarificationOptionFullText: null }, "snapshot_not_reproducible"],
    ["tampered recommended", "C", { priorClarificationOptionsSnapshot: tamper(2, { recommended: true }), selectedClarificationOptionFullText: null }, "snapshot_not_reproducible"],
    ["tampered A internal preview", "A", { priorClarificationOptionsSnapshot: tamper(0, { internalPolicyPreview: { scopeKind: "all_video_apps", appliesTo: "x", packages: [] } }), selectedClarificationOptionFullText: null }, "snapshot_not_reproducible"],
  ];
  // Forged snapshot + matching forged carry: agreement between two client copies proves nothing.
  {
    const snap = tamper(1, { description: "allow only TikTok" });
    cases.push(["forged snapshot + forged carry", "B", { priorClarificationOptionsSnapshot: snap, selectedClarificationOptionFullText: clone(snap[1]) }, "snapshot_not_reproducible"]);
  }
  for (const [label, id, overrides, reason] of cases) {
    const r = await compile(COMPOUND, pickOptions(COMPOUND, cSnap, id, overrides), compoundModel);
    assertFailClosed(r.dto, r.calls, reason, `compound ${id} ${label}`);
    if (label === "tampered description") {
      FIXTURES.v09_compound_fail_closed = { promise: COMPOUND, modelScope: UNRESOLVED, dto: r.dto };
    }
    check(!r.calls.some((c) => c.compileText === COMPOUND_B_TEXT || c.compileText === COMPOUND_C_TEXT), `compound ${id} ${label}: narrowed text never compiled`);
  }

  // Direct gate: success shape + the general resolver stays strict.
  {
    const ok = verifyCompoundClauseSelection({ selectedId: "b", snapshot: cSnap, carried: cSnap[1], priorPromiseText: `  ${COMPOUND}  `, promiseText: COMPOUND });
    eq(ok.ok, true, "gate: lowercase id + whitespace-normalized prior text verifies");
    eq(ok.id, "B", "gate: canonical id");
    eq(verifyCompoundClauseSelection({ selectedId: "B", snapshot: gSnap, priorPromiseText: GOOGLE, promiseText: GOOGLE }).reason, "not_compound", "gate: non-compound text");
    // The general resolver is untouched and stays strict: no evidence -> no pick.
    eq(svc.resolveClarificationSelection({ selectedId: "B", snapshot: [], carried: null, promiseText: COMPOUND }).reason, "no_verifiable_choice", "general resolver: no evidence rejected");
    eq(svc.resolveClarificationSelection({ selectedId: "B", snapshot: [{ id: "B", label: "x" }], carried: null, promiseText: COMPOUND }).reason, "snapshot_invalid_scope", "general resolver: missing preview rejected");
  }

  eq(Object.keys(FIXTURES).length, 4, "all fixtures captured");
  if (process.argv.includes("--write-fixtures")) {
    const fs = require("fs");
    const path = require("path");
    const dir = path.join(__dirname, "..", "app", "src", "test", "resources", "scope-contract");
    fs.mkdirSync(dir, { recursive: true });
    for (const [name, f] of Object.entries(FIXTURES)) {
      fs.writeFileSync(
        path.join(dir, `${name}.json`),
        JSON.stringify({ promise: f.promise, modelScope: f.modelScope, response: f.dto }, null, 2) + "\n"
      );
    }
    console.log(`wrote ${Object.keys(FIXTURES).length} fixtures to ${dir}`);
  }

  console.log(`ASSERTIONS_PASSED=${ASSERTIONS}`);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
