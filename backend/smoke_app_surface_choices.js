/**
 * Offline smoke: a stuck app-scope clarification ("which part of Instagram?") must end
 * in either server-built, policy-complete choices or truthful non-startable guidance.
 * No Azure, no network. Run: node backend/smoke_app_surface_choices.js
 *
 * Law: a choice is offered only if its exact meaning can be previewed, persisted and
 * enforced. Choices are re-derived from the unchanged promise text; the model's raw
 * scope, fresh model options and client-echoed scopes are never provenance.
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
const IG = "com.instagram.android";
const V07 = "promise_compiler_v07";

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

function scope(kind, identity, pkg, surfaces, evidence, exempt = []) {
  return {
    version: 1,
    kind,
    resolution: "EXCLUSIVE",
    identity,
    include: [{ id: "i1", host: "NATIVE_APP", identity, surfaces, packages: [pkg], packageMatch: "PINNED" }],
    exempt,
    confidence: 0.9,
    evidenceSpans: [evidence],
  };
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

/** What Android echoes back: the options exactly as received (JSON round-trip). */
const echo = (options) => JSON.parse(JSON.stringify(options || []));

function pick(text, modelOutput, displayed, id, opts = {}) {
  const snapshot = opts.snapshot || echo(displayed);
  const carried =
    opts.carried !== undefined ? opts.carried : snapshot.find((o) => String(o.id).toUpperCase() === id) || null;
  return quietNormalize(modelOutput, text, {
    selectedClarificationOptionId: id,
    priorClarificationOptionsSnapshot: snapshot,
    selectedClarificationOptionFullText: carried,
    priorClarificationPromiseText: opts.prior !== undefined ? opts.prior : text,
    promptVersion: opts.promptVersion,
  });
}

/** Real compilePromise round-trip of a pick (what the HTTP route does). */
async function compilePick(text, modelOutput, displayed, id) {
  const snapshot = echo(displayed);
  setCompilerTransportForTests(async () => JSON.stringify(modelOutput));
  const origLog = console.log;
  console.log = () => {};
  try {
    return await compilePromise(text, {
      selectedClarificationOptionId: id,
      priorClarificationOptionsSnapshot: snapshot,
      selectedClarificationOptionFullText: snapshot.find((o) => o.id === id),
      priorClarificationPromiseText: text,
    });
  } finally {
    console.log = origLog;
    setCompilerTransportForTests(null);
  }
}

/** Exported for Android AppSurfaceChoiceFixtureTest (`--write-fixtures`). */
const FIXTURES = {};

const appRule = (dto, pkg) => (dto.suggestedAppRules || []).find((r) => r.packageName === pkg);
const wholeBlock = (dto, pkg) => (appRule(dto, pkg) || {}).behavior === "BLOCK";
const shortFormBlock = (dto, pkg) =>
  (dto.contentRules || []).find(
    (r) => r.packageName === pkg && r.contentType === "short_form_video" && r.action === "BLOCK" && !r.operator
  );
const bullets = (dto, key) => ((dto.userFacingConfirmation || {})[key] || []).map(String);
const cautions = (dto) => (dto.cautionMessages || []).join(" | ");
const kinds = (dto) => (dto.clarificationOptions || []).map((o) => (o.scope || {}).kind);

function assertChoices(dto, tag, identity, pkgLabel) {
  eq(dto.canStartCommitment, false, `${tag}: Start closed until a pick`);
  eq(dto.clarificationRequired, true, `${tag}: clarification required`);
  const opts = dto.clarificationOptions || [];
  eq(opts.length, 2, `${tag}: exactly two choices`);
  eq(opts.map((o) => o.id).join(","), "A,B", `${tag}: stable ids`);
  check(opts.every((o) => o.recommended === false), `${tag}: no choice preselected/recommended`);
  eq(kinds(dto).join(","), "APP_SHORT_FORM_ONLY,APP_ONLY", `${tag}: typed scopes are short-form-only vs whole app`);
  check(opts.every((o) => (o.scope || {}).identity === identity), `${tag}: both pinned to ${identity}`);
  check(/stay open/i.test(opts[0].description), `${tag}: narrow choice says what stays open`);
  check(new RegExp(`whole ${pkgLabel}`, "i").test(opts[1].description), `${tag}: whole choice says the whole app is blocked`);
  check(typeof dto.clarificationQuestion === "string" && /which part/i.test(dto.clarificationQuestion), `${tag}: asks which part`);
}

function assertClosedNoChoices(dto, tag, cautionRe) {
  eq(dto.canStartCommitment, false, `${tag}: Start closed`);
  eq((dto.clarificationOptions || []).length, 0, `${tag}: no fake choices`);
  check(cautionRe.test(cautions(dto)), `${tag}: truthful guidance (${cautions(dto)})`);
}

async function main() {
  // ---------------------------------------------------------------------------
  // 1) Whole-app BLOCK vs a valid narrow scope: accurate choices, no default.
  const YT_TEXT = "block youtube shorts for 1 hour";
  const ytConflict = basePolicy({
    blockedApps: [{ packageName: YT, appLabel: "YouTube" }],
    scope: scope("APP_SHORT_FORM_ONLY", "YOUTUBE", YT, ["PLAYER_SHORT"], "youtube shorts"),
  });
  const yt1 = await compile(YT_TEXT, ytConflict);
  assertChoices(yt1, "yt conflict", "YOUTUBE", "YouTube");

  // Pick A: only Shorts. Preview, typed scope and structured rule agree.
  {
    const dto = pick(YT_TEXT, ytConflict, yt1.clarificationOptions, "A");
    eq(dto.canStartCommitment, true, "yt pick A: can start");
    eq((dto.scope || {}).kind, "APP_SHORT_FORM_ONLY", "yt pick A: typed scope");
    check(shortFormBlock(dto, YT), "yt pick A: structured Shorts BLOCK");
    check(!wholeBlock(dto, YT), "yt pick A: no whole-app BLOCK");
    check(!dto.policyConflict, "yt pick A: no conflict left");
    check(bullets(dto, "blocked").some((b) => /youtube shorts/i.test(b)), "yt pick A: preview names YouTube Shorts");
    check(!bullets(dto, "blocked").some((b) => /^all of youtube$/i.test(b.trim())), "yt pick A: preview not whole app");
  }
  // Pick B: all of YouTube.
  {
    const dto = pick(YT_TEXT, ytConflict, yt1.clarificationOptions, "B");
    eq(dto.canStartCommitment, true, "yt pick B: can start");
    eq((dto.scope || {}).kind, "APP_ONLY", "yt pick B: typed scope");
    check(wholeBlock(dto, YT), "yt pick B: whole-app BLOCK");
    check(!shortFormBlock(dto, YT), "yt pick B: no leftover short-form-only rule");
    check(bullets(dto, "blocked").some((b) => /all of youtube/i.test(b)), "yt pick B: preview says all of YouTube");
    check(!bullets(dto, "allowed").some((b) => /youtube/i.test(b)), "yt pick B: nothing claims YouTube stays allowed");
  }

  // ---------------------------------------------------------------------------
  // 2) Instagram Reels only; keep feed and messages -> only the short-form player.
  const IG_REELS_TEXT = "Block Instagram Reels only for 1 hour; keep the feed and messages available.";
  const igMessy = basePolicy({
    blockedApps: [{ packageName: IG, appLabel: "Instagram", scope: "reels only, keep feed and messages" }],
    scope: scope("APP_SHORT_FORM_ONLY", "INSTAGRAM", IG, ["PLAYER_SHORT"], "Instagram Reels only"),
  });
  const ig1 = await compile(IG_REELS_TEXT, igMessy);
  assertChoices(ig1, "ig reels messy", "INSTAGRAM", "Instagram");
  FIXTURES.app_surface_ig_turn1 = { promise: IG_REELS_TEXT, dto: ig1 };
  for (const id of ["A", "B"]) {
    const dto = await compilePick(IG_REELS_TEXT, igMessy, ig1.clarificationOptions, id);
    eq(dto.canStartCommitment, true, `ig compilePick ${id}: can start`);
    eq(dto.optionVerification, "snapshot_and_carry_verified", `ig compilePick ${id}: verified`);
    FIXTURES[`app_surface_ig_pick_${id.toLowerCase()}`] = { promise: IG_REELS_TEXT, dto };
  }
  {
    const dto = pick(IG_REELS_TEXT, igMessy, ig1.clarificationOptions, "A");
    eq(dto.canStartCommitment, true, "ig pick A: can start");
    eq((dto.scope || {}).kind, "APP_SHORT_FORM_ONLY", "ig pick A: typed scope short-form only");
    eq((dto.scope || {}).identity, "INSTAGRAM", "ig pick A: pinned to Instagram");
    check(shortFormBlock(dto, IG), "ig pick A: structured Reels BLOCK");
    check(!wholeBlock(dto, IG), "ig pick A: no whole-app BLOCK (feed + messages stay open)");
    check(bullets(dto, "blocked").some((b) => /instagram reels/i.test(b)), "ig pick A: preview names Instagram Reels");
    check(bullets(dto, "allowed").some((b) => /feed/i.test(b) && /messages/i.test(b)), "ig pick A: preview keeps feed + messages");
  }
  {
    const dto = pick(IG_REELS_TEXT, igMessy, ig1.clarificationOptions, "B");
    eq(dto.canStartCommitment, true, "ig pick B: explicit whole-app choice can start");
    check(wholeBlock(dto, IG), "ig pick B: whole-app BLOCK");
    eq((dto.scope || {}).kind, "APP_ONLY", "ig pick B: APP_ONLY");
  }
  // Clean model output for the same promise: startable directly, no question.
  {
    const dto = await compile(
      IG_REELS_TEXT,
      basePolicy({
        blockedApps: [{ packageName: IG, appLabel: "Instagram", scope: "reels" }],
        scope: scope("APP_SHORT_FORM_ONLY", "INSTAGRAM", IG, ["PLAYER_SHORT"], "Instagram Reels only"),
      })
    );
    eq(dto.canStartCommitment, true, "ig clean: can start");
    eq((dto.clarificationOptions || []).length, 0, "ig clean: no question");
    check(shortFormBlock(dto, IG), "ig clean: structured Reels BLOCK");
  }
  // Model broadens to whole Instagram while the promise keeps messages: never startable as-is.
  {
    const dto = await compile(
      IG_REELS_TEXT,
      basePolicy({
        blockedApps: [{ packageName: IG, appLabel: "Instagram", scope: "" }],
        scope: scope("APP_ONLY", "INSTAGRAM", IG, ["ANY"], "Instagram"),
      })
    );
    assertChoices(dto, "ig broadened", "INSTAGRAM", "Instagram");
  }
  // Model-authored options are replaced by server-built ones.
  {
    const dto = await compile(
      IG_REELS_TEXT,
      basePolicy({
        ...igMessy,
        clarificationOptions: [
          { id: "A", label: "Everything", description: "block it all", recommended: true, scope: scope("APP_ONLY", "INSTAGRAM", IG, ["ANY"], "Instagram") },
          { id: "B", label: "Messages", description: "only DMs", recommended: false },
          { id: "C", label: "Feed", description: "only feed", recommended: false },
        ],
      })
    );
    assertChoices(dto, "ig model options replaced", "INSTAGRAM", "Instagram");
  }

  // ---------------------------------------------------------------------------
  // 2b) A pick changes only the chosen app's whole-app-vs-short-form conflict.
  //     Independent rules survive: target-app adult / media-length rules, global
  //     short-form and quota rules, and other apps' rules.
  const IG_RICH_TEXT = "Block Instagram Reels and adult content for 1 hour; keep the feed and messages available.";
  const igRich = basePolicy({
    ...igMessy,
    blockedContent: [
      { type: "adult_sexual", description: "Instagram adult content", apps: [IG] },
      { type: "short_form_video", description: "Instagram Reels", apps: [IG] },
      { type: "short_form_video", description: "short-form videos everywhere", apps: [] },
      { type: "short_form_video", description: "YouTube Shorts", apps: [YT] },
      { type: "long_form_video", description: "Instagram videos longer than 20 minutes", apps: [IG] },
    ],
    allowedContent: [{ type: "social_dm", description: "Instagram messages", apps: [IG] }],
    quotas: [{ metric: "social_minutes", limit: 30, period: "day" }],
    userFacingConfirmation: {
      understood: "Block Instagram Reels.",
      blocked: ["Instagram adult content", "short-form videos everywhere", "YouTube Shorts", "Instagram videos longer than 20 minutes"],
      allowed: ["Instagram messages"],
    },
  });
  const rule = (dto, pred) => (dto.contentRules || []).filter(pred);
  const igAdult = (dto) => rule(dto, (r) => r.packageName === IG && r.contentType === "adult_sexual" && r.action === "BLOCK");
  const globalShort = (dto) =>
    rule(dto, (r) => !r.packageName && r.contentType === "short_form_video" && r.action === "BLOCK" && !r.operator);
  const ytShort = (dto) => rule(dto, (r) => r.packageName === YT && r.contentType === "short_form_video" && r.action === "BLOCK");
  const igLength = (dto) => rule(dto, (r) => r.packageName === IG && r.operator === "gt" && r.value === 20 && r.unit === "minutes");
  const socialQuota = (dto) => rule(dto, (r) => r.contentType === "usage_quota" && r.value === 30);
  const igAllow = (dto) => rule(dto, (r) => r.packageName === IG && r.action === "ALLOW");
  const igShort = (dto) => rule(dto, (r) => r.packageName === IG && r.contentType === "short_form_video" && r.action === "BLOCK" && !r.operator);
  const assertIndependentKept = (dto, tag) => {
    eq(igAdult(dto).length, 1, `${tag}: Instagram adult BLOCK kept`);
    eq(globalShort(dto).length, 1, `${tag}: global short-form BLOCK kept`);
    eq(ytShort(dto).length, 1, `${tag}: YouTube short-form BLOCK kept`);
    eq(igLength(dto).length, 1, `${tag}: Instagram media-length limit kept`);
    eq(socialQuota(dto).length, 1, `${tag}: global social quota kept`);
    check(!wholeBlock(dto, YT), `${tag}: YouTube app rule unchanged (no new block)`);
    check(bullets(dto, "blocked").some((b) => /adult/i.test(b)), `${tag}: preview still lists the adult block`);
    check(bullets(dto, "blocked").some((b) => /short-form videos everywhere/i.test(b)), `${tag}: preview still lists the global short-form block`);
    check(bullets(dto, "blocked").some((b) => /youtube shorts/i.test(b)), `${tag}: preview still lists YouTube Shorts`);
    check(bullets(dto, "blocked").some((b) => /longer than 20 minutes/i.test(b)), `${tag}: preview still lists the length limit`);
  };
  const igRich1 = await compile(IG_RICH_TEXT, igRich);
  assertChoices(igRich1, "ig rich turn1", "INSTAGRAM", "Instagram");
  assertIndependentKept(igRich1, "ig rich turn1");
  const otherAppRules = (dto) =>
    JSON.stringify(
      (dto.suggestedAppRules || [])
        .filter((r) => r.packageName !== IG)
        .map((r) => `${r.packageName}:${r.behavior}`)
        .sort()
    );
  const assertOtherAppsUnchanged = (dto, tag) =>
    eq(otherAppRules(dto), otherAppRules(igRich1), `${tag}: other apps' app rules identical to turn 1`);
  // A pick never deletes an independent rule. When the chosen typed scope cannot reach a
  // kept restriction (other apps, global rules, or Instagram surfaces outside Reels), Start
  // stays closed and says which rules would go unenforced -- nothing is silently dropped.
  const assertMissesRules = (dto, tag, namedRe, notNamedRe) => {
    eq(dto.canStartCommitment, false, `${tag}: Start closed`);
    eq(dto.optionVerification, "snapshot_and_carry_verified", `${tag}: pick itself verified`);
    check((dto.policyConflictReasons || []).includes("app_surface_choice_misses_rules"), `${tag}: reason names the scope gap`);
    eq((dto.clarificationOptions || []).length, 0, `${tag}: no fake choices`);
    for (const re of namedRe) check(re.test(cautions(dto)), `${tag}: guidance names ${re} (${cautions(dto)})`);
    for (const re of notNamedRe) check(!re.test(cautions(dto)), `${tag}: guidance does not name covered ${re}`);
  };
  {
    const dto = await compilePick(IG_RICH_TEXT, igRich, igRich1.clarificationOptions, "A");
    const tag = "ig rich pick A";
    eq((dto.scope || {}).kind, "APP_SHORT_FORM_ONLY", `${tag}: typed scope`);
    assertIndependentKept(dto, tag);
    assertOtherAppsUnchanged(dto, tag);
    assertMissesRules(dto, tag, [/youtube shorts/i, /short-form videos everywhere/i, /instagram adult content/i, /longer than 20 minutes/i, /30 min/i], []);
    eq(igShort(dto).length, 1, `${tag}: exactly one Instagram short-form BLOCK (deduped)`);
    check(!wholeBlock(dto, IG), `${tag}: no Instagram whole-app BLOCK`);
    eq(igAllow(dto).length, 1, `${tag}: compatible Instagram messages ALLOW kept`);
    check(bullets(dto, "allowed").some((b) => /instagram messages/i.test(b)), `${tag}: preview keeps the messages allowance`);
    check(bullets(dto, "blocked").some((b) => /instagram reels/i.test(b)), `${tag}: preview names Instagram Reels`);
    check(!bullets(dto, "blocked").some((b) => /^all of instagram$/i.test(b.trim())), `${tag}: preview not whole app`);
    check(/other Instagram rules still apply/i.test(dto.userFacingConfirmation.understood), `${tag}: summary does not imply the rest is unrestricted`);
    check(!bullets(dto, "allowed").some((b) => /feed/i.test(b)), `${tag}: preview does not claim the feed is unrestricted`);
    FIXTURES.app_surface_ig_rich_pick_a = { promise: IG_RICH_TEXT, dto };
  }
  {
    const dto = await compilePick(IG_RICH_TEXT, igRich, igRich1.clarificationOptions, "B");
    const tag = "ig rich pick B";
    eq((dto.scope || {}).kind, "APP_ONLY", `${tag}: typed scope`);
    assertIndependentKept(dto, tag);
    assertOtherAppsUnchanged(dto, tag);
    // All of Instagram covers the Instagram adult / length rules; other apps stay uncovered.
    assertMissesRules(dto, tag, [/youtube shorts/i, /short-form videos everywhere/i, /30 min/i], [/instagram adult/i, /longer than 20 minutes/i]);
    check(wholeBlock(dto, IG), `${tag}: Instagram whole-app BLOCK`);
    eq(igAllow(dto).length, 0, `${tag}: conflicting Instagram ALLOW removed`);
    eq(igShort(dto).length, 0, `${tag}: redundant Instagram short-form BLOCK folded into the whole-app BLOCK`);
    check(!bullets(dto, "allowed").some((b) => /instagram/i.test(b)), `${tag}: preview never claims Instagram stays allowed`);
    check(bullets(dto, "blocked").some((b) => /all of instagram/i.test(b)), `${tag}: preview says all of Instagram`);
    FIXTURES.app_surface_ig_rich_pick_b = { promise: IG_RICH_TEXT, dto };
  }
  // Edited / stale picks leave every rule exactly as compiled and keep Start closed.
  for (const [tag, id, opts] of [
    ["ig rich edited promise", "A", { prior: IG_RICH_TEXT.replace("Reels", "Stories") }],
    ["ig rich edited promise B", "B", { prior: IG_RICH_TEXT + " please" }],
  ]) {
    const dto = pick(IG_RICH_TEXT, igRich, igRich1.clarificationOptions, id, opts);
    eq(dto.canStartCommitment, false, `${tag}: Start closed`);
    eq(dto.optionVerification, "unrecognized_fail_closed", `${tag}: fail closed`);
    assertIndependentKept(dto, tag);
    assertOtherAppsUnchanged(dto, tag);
    eq(igAllow(dto).length, 1, `${tag}: Instagram ALLOW untouched`);
    if (id === "A") FIXTURES.app_surface_ig_rich_stale = { promise: IG_RICH_TEXT, dto };
  }

  // Instagram-only independent rules: All of Instagram covers them, so Start opens with
  // the adult / length rules kept and the contradicting messages ALLOW removed.
  const igSolo = basePolicy({
    ...igMessy,
    blockedContent: [
      { type: "adult_sexual", description: "Instagram adult content", apps: [IG] },
      { type: "short_form_video", description: "Instagram Reels", apps: [IG] },
      { type: "long_form_video", description: "Instagram videos longer than 20 minutes", apps: [IG] },
    ],
    allowedContent: [{ type: "social_dm", description: "Instagram messages", apps: [IG] }],
    userFacingConfirmation: {
      understood: "Block Instagram Reels and adult content.",
      blocked: ["Instagram adult content", "Instagram Reels", "Instagram videos longer than 20 minutes"],
      allowed: ["Instagram messages"],
    },
  });
  const igSolo1 = await compile(IG_RICH_TEXT, igSolo);
  assertChoices(igSolo1, "ig solo turn1", "INSTAGRAM", "Instagram");
  {
    const dto = await compilePick(IG_RICH_TEXT, igSolo, igSolo1.clarificationOptions, "B");
    const tag = "ig solo pick B";
    eq(dto.canStartCommitment, true, `${tag}: can start`);
    eq((dto.scope || {}).kind, "APP_ONLY", `${tag}: typed scope`);
    check(!dto.policyConflict, `${tag}: no conflict`);
    check(wholeBlock(dto, IG), `${tag}: Instagram whole-app BLOCK`);
    eq(igAdult(dto).length, 1, `${tag}: Instagram adult BLOCK kept`);
    eq(igLength(dto).length, 1, `${tag}: Instagram media-length limit kept`);
    eq(igAllow(dto).length, 0, `${tag}: conflicting Instagram ALLOW removed`);
    check(bullets(dto, "blocked").some((b) => /adult/i.test(b)), `${tag}: preview lists the adult block`);
    check(bullets(dto, "blocked").some((b) => /longer than 20 minutes/i.test(b)), `${tag}: preview lists the length limit`);
    check(bullets(dto, "blocked").some((b) => /all of instagram/i.test(b)), `${tag}: preview says all of Instagram`);
    check(!bullets(dto, "allowed").some((b) => /instagram/i.test(b)), `${tag}: preview never claims Instagram stays allowed`);
    FIXTURES.app_surface_ig_solo_pick_b = { promise: IG_RICH_TEXT, dto };
  }
  {
    const dto = await compilePick(IG_RICH_TEXT, igSolo, igSolo1.clarificationOptions, "A");
    const tag = "ig solo pick A";
    eq(igAdult(dto).length, 1, `${tag}: Instagram adult BLOCK kept`);
    eq(igLength(dto).length, 1, `${tag}: Instagram media-length limit kept`);
    eq(igShort(dto).length, 1, `${tag}: one Instagram short-form BLOCK`);
    assertMissesRules(dto, tag, [/instagram adult content/i, /longer than 20 minutes/i], [/instagram reels,/i]);
  }

  // Reels only with duplicate Reels rules, a compatible messages ALLOW and another app's
  // rule: short-form rules collapse to one, everything else is untouched, Start opens.
  const WA = "com.whatsapp";
  const igDup = basePolicy({
    ...igMessy,
    allowedApps: [{ packageName: WA, appLabel: "WhatsApp" }],
    blockedContent: [
      { type: "short_form_video", description: "Instagram Reels", apps: [IG] },
      { type: "short_form_video", description: "Reels on Instagram", apps: [IG] },
    ],
    allowedContent: [{ type: "social_dm", description: "Instagram messages", apps: [IG] }],
  });
  const igDup1 = await compile(IG_REELS_TEXT, igDup);
  assertChoices(igDup1, "ig dedupe turn1", "INSTAGRAM", "Instagram");
  const waRules = (dto) => JSON.stringify((dto.suggestedAppRules || []).filter((r) => r.packageName === WA));
  {
    const dto = await compilePick(IG_REELS_TEXT, igDup, igDup1.clarificationOptions, "A");
    const tag = "ig dedupe pick A";
    eq(dto.canStartCommitment, true, `${tag}: can start`);
    eq((dto.scope || {}).kind, "APP_SHORT_FORM_ONLY", `${tag}: typed scope`);
    check(!dto.policyConflict, `${tag}: no conflict`);
    eq(igShort(dto).length, 1, `${tag}: duplicate Reels rules collapse to one`);
    eq(igAllow(dto).length, 1, `${tag}: messages ALLOW kept`);
    check(waRules(igDup1) !== "[]", `${tag}: fixture really has another app's rule`);
    eq(waRules(dto), waRules(igDup1), `${tag}: other app's rule unchanged`);
    check(bullets(dto, "allowed").some((b) => /instagram messages/i.test(b)), `${tag}: preview keeps the messages allowance`);
    eq(bullets(dto, "blocked").filter((b) => /reels/i.test(b)).length, 1, `${tag}: preview lists Reels once`);
    FIXTURES.app_surface_ig_dedupe_pick_a = { promise: IG_REELS_TEXT, dto };
  }
  {
    const dto = await compilePick(IG_REELS_TEXT, igDup, igDup1.clarificationOptions, "B");
    eq(dto.canStartCommitment, true, "ig dedupe pick B: can start");
    eq(igShort(dto).length, 0, "ig dedupe pick B: Reels rules folded into the whole-app BLOCK");
    eq(waRules(dto), waRules(igDup1), "ig dedupe pick B: other app's rule unchanged");
  }

  // ---------------------------------------------------------------------------
  // 3) Stale / forged / duplicate / malformed picks fail closed.
  const failClosed = (dto, tag) => {
    eq(dto.canStartCommitment, false, `${tag}: Start closed`);
    eq(dto.optionVerification, "unrecognized_fail_closed", `${tag}: fail-closed verdict`);
    check(!wholeBlock(dto, IG), `${tag}: no whole-app BLOCK`);
    check(!shortFormBlock(dto, IG) || (dto.scope || {}).kind !== "APP_SHORT_FORM_ONLY" || dto.canStartCommitment === false, `${tag}: nothing enforceable leaks`);
  };
  failClosed(
    pick(IG_REELS_TEXT + " Actually block all of it.", igMessy, ig1.clarificationOptions, "A", { prior: IG_REELS_TEXT }),
    "promise edited after options"
  );
  {
    const forged = echo(ig1.clarificationOptions);
    forged[0].internalPolicyPreview.scopeKind = forged[1].internalPolicyPreview.scopeKind;
    failClosed(pick(IG_REELS_TEXT, igMessy, forged, "A", { snapshot: forged }), "forged preview kind");
  }
  {
    const forged = echo(ig1.clarificationOptions);
    forged[0].scope = forged[1].scope;
    failClosed(pick(IG_REELS_TEXT, igMessy, forged, "A", { snapshot: forged }), "forged typed scope");
  }
  {
    const forged = echo(ig1.clarificationOptions);
    forged[0].description = "Blocks nothing.";
    failClosed(pick(IG_REELS_TEXT, igMessy, forged, "A", { snapshot: forged }), "forged copy");
  }
  {
    const dup = echo(ig1.clarificationOptions);
    dup[1].id = "A";
    failClosed(pick(IG_REELS_TEXT, igMessy, dup, "A", { snapshot: dup }), "duplicate ids");
  }
  failClosed(pick(IG_REELS_TEXT, igMessy, ig1.clarificationOptions, "C"), "missing id");
  {
    const snap = echo(ig1.clarificationOptions);
    failClosed(pick(IG_REELS_TEXT, igMessy, snap, "A", { snapshot: snap, carried: snap[1] }), "carry mismatch");
  }
  {
    const snap = echo(ig1.clarificationOptions);
    snap[0].scope = { version: 1, kind: "NOT_A_KIND", include: "x" };
    failClosed(pick(IG_REELS_TEXT, igMessy, snap, "A", { snapshot: snap }), "malformed scope");
  }
  // v07 legacy contract: no typed scope, so no app-surface choices and no pick path.
  {
    const dto = quietNormalize(igMessy, IG_REELS_TEXT, { promptVersion: V07 });
    assertClosedNoChoices(dto, "v07 ig messy", /couldn't tell which part of Instagram/i);
    const replay = pick(IG_REELS_TEXT, igMessy, ig1.clarificationOptions, "A", { promptVersion: V07 });
    failClosed(replay, "v07 replay of a v09 choice");
  }

  // ---------------------------------------------------------------------------
  // 4) Messages only: no DM-only policy exists (DM screens are not reliably
  //    distinguishable), so never startable, and never SOCIAL_MESSAGING_ONLY.
  const MSG_TEXT = "Block Instagram messages only for 1 hour";
  for (const [tag, out] of [
    ["free-text messages", basePolicy({ blockedApps: [{ packageName: IG, appLabel: "Instagram", scope: "messages" }] })],
    [
      "inverted SOCIAL_MESSAGING_ONLY",
      basePolicy({
        blockedApps: [{ packageName: IG, appLabel: "Instagram", scope: "" }],
        scope: scope("SOCIAL_MESSAGING_ONLY", "INSTAGRAM", IG, ["ANY"], "Instagram messages"),
      }),
    ],
    [
      "whole app for messages",
      basePolicy({
        blockedApps: [{ packageName: IG, appLabel: "Instagram", scope: "" }],
        scope: scope("APP_ONLY", "INSTAGRAM", IG, ["ANY"], "Instagram"),
      }),
    ],
    [
      "unenforced social_dm rule",
      basePolicy({
        blockedContent: [{ type: "social_dm", description: "Instagram messages", apps: [IG] }],
        scope: scope("APP_ONLY", "INSTAGRAM", IG, ["ANY"], "Instagram"),
      }),
    ],
  ]) {
    const dto = await compile(MSG_TEXT, out);
    assertClosedNoChoices(dto, `messages-only (${tag})`, /messages/i);
    if (tag === "inverted SOCIAL_MESSAGING_ONLY") FIXTURES.app_surface_ig_messages_only = { promise: MSG_TEXT, dto };
  }

  // ---------------------------------------------------------------------------
  // 5) Feed and Reels, keep messages: the feed has no structured block, so the
  //    preview would promise more than enforcement. Non-startable, no fake choice.
  const FEED_TEXT = "Block the Instagram feed and Reels for 1 hour; keep messages open.";
  for (const [tag, out] of [
    [
      "SOCIAL_FEED_AND_REELS_ONLY",
      basePolicy({
        blockedApps: [{ packageName: IG, appLabel: "Instagram", scope: "reels" }],
        scope: scope("SOCIAL_FEED_AND_REELS_ONLY", "INSTAGRAM", IG, ["FEED", "PLAYER_SHORT"], "Instagram feed and Reels"),
      }),
    ],
    [
      "whole app",
      basePolicy({
        blockedApps: [{ packageName: IG, appLabel: "Instagram", scope: "" }],
        scope: scope("APP_ONLY", "INSTAGRAM", IG, ["ANY"], "Instagram"),
      }),
    ],
    ["free text", basePolicy({ blockedApps: [{ packageName: IG, appLabel: "Instagram", scope: "feed and reels" }] })],
  ]) {
    const dto = await compile(FEED_TEXT, out);
    assertClosedNoChoices(dto, `feed+reels (${tag})`, /feed/i);
  }

  // ---------------------------------------------------------------------------
  // 6) Instagram except messages: existing SOCIAL_MESSAGING_ONLY meaning unchanged.
  const EXCEPT_MSG_TEXT = "Block Instagram except messages for 1 hour";
  const exceptMsgOut = basePolicy({
    blockedApps: [{ packageName: IG, appLabel: "Instagram", scope: "" }],
    scope: scope("SOCIAL_MESSAGING_ONLY", "INSTAGRAM", IG, ["ANY"], "Instagram except messages", [
      { id: "x1", host: "ANY_HOST", identity: "INSTAGRAM", surfaces: ["DM_LIST", "DM_THREAD"], packages: [IG], packageMatch: "PINNED" },
    ]),
  });
  {
    const dto = await compile(EXCEPT_MSG_TEXT, exceptMsgOut);
    eq(dto.canStartCommitment, true, "except messages: can start");
    eq((dto.scope || {}).kind, "SOCIAL_MESSAGING_ONLY", "except messages: kind unchanged");
    check(wholeBlock(dto, IG), "except messages: app rule BLOCK (DMs exempt via scope)");
  }
  // Legacy contract cannot carry the DM exemption: whole-app BLOCK would block messages.
  {
    const dto = quietNormalize(exceptMsgOut, EXCEPT_MSG_TEXT, { promptVersion: V07 });
    assertClosedNoChoices(dto, "v07 except messages", /messages/i);
  }
  // v09 APP_ONLY for the same promise would also block messages.
  {
    const dto = await compile(
      EXCEPT_MSG_TEXT,
      basePolicy({
        blockedApps: [{ packageName: IG, appLabel: "Instagram", scope: "" }],
        scope: scope("APP_ONLY", "INSTAGRAM", IG, ["ANY"], "Instagram"),
      })
    );
    assertClosedNoChoices(dto, "v09 except messages as APP_ONLY", /messages/i);
  }

  // ---------------------------------------------------------------------------
  // 7) Everything except Shorts / long videos only: fail closed, specific guidance.
  for (const [text, out] of [
    [
      "Block everything on YouTube except Shorts for 1 hour",
      basePolicy({ blockedApps: [{ packageName: YT, appLabel: "YouTube", scope: "everything except shorts" }] }),
    ],
    [
      "Block everything on YouTube except Shorts for 1 hour",
      basePolicy({
        blockedApps: [{ packageName: YT, appLabel: "YouTube" }],
        scope: scope("APP_SHORT_FORM_ONLY", "YOUTUBE", YT, ["PLAYER_SHORT"], "YouTube"),
      }),
    ],
    [
      "Block only long YouTube videos for 1 hour",
      basePolicy({ blockedApps: [{ packageName: YT, appLabel: "YouTube", scope: "long videos" }] }),
    ],
  ]) {
    const dto = await compile(text, out);
    assertClosedNoChoices(dto, `unsupported "${text}"`, /except its short videos|only its long videos/i);
  }

  // ---------------------------------------------------------------------------
  // 8) Simultaneous producers: server families keep precedence, nothing merges.
  {
    const text = "allow only instagram reels, allow only youtube";
    const dto = await compile(text, igMessy);
    check((dto.clarificationOptions || []).some((o) => /both together/i.test(o.label)), "compound: compound options kept");
    check(!kinds(dto).includes("APP_SHORT_FORM_ONLY") || (dto.clarificationOptions || []).length === 3, "compound: no app-surface options merged in");
    eq(dto.canStartCommitment, false, "compound: Start closed");
  }
  {
    const text = "block google videos and instagram reels for 1 hour";
    const dto = await compile(text, igMessy);
    check(
      (dto.clarificationOptions || []).some((o) => /chrome|browser/i.test(`${o.label} ${o.description}`)),
      "google: google options kept"
    );
    eq(dto.canStartCommitment, false, "google: Start closed");
  }
  // Two apps named: which app a choice applies to is ambiguous -> guidance only.
  {
    const text = "Block Instagram Reels and YouTube Shorts for 1 hour; keep feed and messages.";
    const dto = await compile(text, igMessy);
    assertClosedNoChoices(dto, "two apps", /Instagram/i);
  }

  eq(Object.keys(FIXTURES).length, 9, "all fixtures captured");
  if (process.argv.includes("--write-fixtures")) {
    const fs = require("fs");
    const path = require("path");
    const dir = path.join(__dirname, "..", "app", "src", "test", "resources", "scope-contract");
    fs.mkdirSync(dir, { recursive: true });
    for (const [name, f] of Object.entries(FIXTURES)) {
      fs.writeFileSync(
        path.join(dir, `${name}.json`),
        JSON.stringify({ promise: f.promise, response: f.dto }, null, 2) + "\n"
      );
    }
    console.log(`wrote ${Object.keys(FIXTURES).length} fixtures to ${dir}`);
  }

  console.log(`smoke_app_surface_choices OK (${ASSERTIONS} assertions)`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
