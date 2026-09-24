/**
 * Offline smoke for Scope Semantics v1 (Phase 1) — backend contract only.
 * Run: node backend/smoke_scope_semantics_v1.js
 *
 * Guards the invariants in docs/scope-semantics-v1.md §3 + §5:
 *   - `validateScope()` accepts a well-formed v09 scope object.
 *   - Missing / null / non-object / bad version / unknown kind -> UNRESOLVED.
 *   - `evidenceSpans` must be verbatim substrings of `userPromise`.
 *   - `appliesToText` with a package id -> UNRESOLVED(PACKAGE_LEAK_IN_APPLIES_TO).
 *   - Malformed clause enum values -> UNRESOLVED(MALFORMED_CLAUSE).
 *   - `option.scope` runs through the SAME validator (no English-blob fallback).
 *   - `enforceScopeStartGates` sets canStartCommitment=false on any UNRESOLVED result.
 *   - Also runs the full v9_scope_semantics dataset and prints per-kind metrics.
 *
 * Prints ASSERTIONS_PASSED=N on success and exits 0; exits 1 on failure.
 */
const assert = require("assert");
const fs = require("fs");
const path = require("path");

const {
  validateScope,
  validateOptionScope,
  enforceScopeStartGates,
  isEnforceableScope,
  SCOPE_KINDS,
  SCOPE_IDENTITIES,
  SCOPE_SURFACE_CLASSES,
  ALLOWED_PROMPT_VERSIONS,
  PROMPT_VERSION,
} = require("./promiseCompilerService");

let ASSERTIONS = 0;
function check(cond, msg) {
  assert.ok(cond, msg);
  ASSERTIONS += 1;
}
function eq(a, e, msg) {
  assert.strictEqual(a, e, msg);
  ASSERTIONS += 1;
}

// -- 0. Prompt registry ------------------------------------------------------

check(
  ALLOWED_PROMPT_VERSIONS.has("promise_compiler_v09"),
  "promise_compiler_v09 must be in ALLOWED_PROMPT_VERSIONS"
);
// Phase 1: default prompt MUST stay v07 unless explicitly flagged.
eq(
  PROMPT_VERSION,
  process.env.PROMISE_COMPILER_PROMPT_VERSION || "promise_compiler_v07",
  "default PROMPT_VERSION must not silently change"
);

// -- 1. Well-formed APP_ONLY scope round-trips through the validator --------

const appOnlyScope = {
  version: 1,
  kind: "APP_ONLY",
  resolution: "EXCLUSIVE",
  identity: "YOUTUBE",
  include: [
    {
      id: "i1",
      host: "NATIVE_APP",
      identity: "YOUTUBE",
      surfaces: ["PLAYER_ANY"],
      sites: [],
      packages: ["com.google.android.youtube"],
      packageMatch: "PINNED",
    },
  ],
  exempt: [],
  confidence: 0.93,
  appliesToText: "the YouTube app only — browser YouTube and other players are untouched",
  evidenceSpans: ["YouTube app only"],
  candidateScopes: [],
};
const validApp = validateScope(appOnlyScope, "YouTube app only: block videos shorter than 30 min for 1 hour");
eq(validApp.kind, "APP_ONLY", "APP_ONLY scope must validate");
check(isEnforceableScope(validApp), "APP_ONLY scope must be enforceable");

// -- 2. Missing / null / non-object scope -> UNRESOLVED ---------------------

for (const bad of [undefined, null, "", 0, 42, "APP_ONLY", []]) {
  const v = validateScope(bad, "anything");
  eq(v.kind, "UNRESOLVED", `bad scope (${JSON.stringify(bad)}) must be UNRESOLVED`);
  eq(v.unresolvedReason, "MISSING_EVIDENCE", "reason for missing scope");
}

// -- 3. Bad version --------------------------------------------------------

for (const badV of [0, 2, 99, "1", null]) {
  const v = validateScope({ ...appOnlyScope, version: badV }, "YouTube app only");
  eq(v.kind, "UNRESOLVED", `version=${badV} must be UNRESOLVED`);
  eq(v.unresolvedReason, "BAD_VERSION", "reason for bad version");
}

// -- 4. Unknown kind -------------------------------------------------------

const unknownKind = validateScope(
  { ...appOnlyScope, kind: "YT_ONLY_LOL" },
  "YouTube app only"
);
eq(unknownKind.kind, "UNRESOLVED", "unknown kind must be UNRESOLVED");
eq(unknownKind.unresolvedReason, "UNKNOWN_KIND", "reason for unknown kind");

// -- 5. evidenceSpans must be verbatim ------------------------------------

const notVerbatim = validateScope(
  { ...appOnlyScope, evidenceSpans: ["a hallucinated span"] },
  "YouTube app only"
);
eq(notVerbatim.kind, "UNRESOLVED", "non-verbatim evidence -> UNRESOLVED");
eq(notVerbatim.unresolvedReason, "MISSING_EVIDENCE", "reason for non-verbatim");

const empty = validateScope({ ...appOnlyScope, evidenceSpans: [] }, "YouTube app only");
eq(empty.kind, "UNRESOLVED", "empty evidence on non-PACKAGE_PINNED -> UNRESOLVED");

// PACKAGE_PINNED tolerates empty evidence
const packagePinned = validateScope(
  {
    version: 1,
    kind: "PACKAGE_PINNED",
    resolution: "EXCLUSIVE",
    identity: null,
    include: [
      {
        id: "i1",
        host: "ANY_HOST",
        identity: "ANY",
        surfaces: ["ANY"],
        sites: [],
        packages: ["app.netmirror.newtv"],
        packageMatch: "PINNED",
      },
    ],
    exempt: [],
    confidence: 0.85,
    appliesToText: "specific apps you named",
    evidenceSpans: [],
    candidateScopes: [],
  },
  "block NetMirror app"
);
eq(packagePinned.kind, "PACKAGE_PINNED", "PACKAGE_PINNED valid without evidenceSpans");

// -- 6. Package id in appliesToText -> UNRESOLVED --------------------------

const leaked = validateScope(
  { ...appOnlyScope, appliesToText: "YouTube (com.google.android.youtube) only" },
  "YouTube app only"
);
eq(leaked.kind, "UNRESOLVED", "package leak in appliesToText -> UNRESOLVED");
eq(leaked.unresolvedReason, "PACKAGE_LEAK_IN_APPLIES_TO", "reason for package leak");

// -- 7. Malformed clause enum values -> UNRESOLVED ------------------------

const badSurface = validateScope(
  {
    ...appOnlyScope,
    include: [{ ...appOnlyScope.include[0], surfaces: ["PLAYER_BOGUS"] }],
  },
  "YouTube app only"
);
eq(badSurface.kind, "UNRESOLVED", "bad surfaces enum -> UNRESOLVED");
eq(badSurface.unresolvedReason, "MALFORMED_CLAUSE", "reason for bad enum");

const badHost = validateScope(
  {
    ...appOnlyScope,
    include: [{ ...appOnlyScope.include[0], host: "WEIRD_HOST" }],
  },
  "YouTube app only"
);
eq(badHost.unresolvedReason, "MALFORMED_CLAUSE", "reason for bad host enum");

const missingId = validateScope(
  {
    ...appOnlyScope,
    include: [{ ...appOnlyScope.include[0], id: "" }],
  },
  "YouTube app only"
);
eq(missingId.unresolvedReason, "MALFORMED_CLAUSE", "reason for missing clause id");

// -- 8. Empty include on non-UNRESOLVED kind -> UNRESOLVED -----------------

const emptyInclude = validateScope(
  { ...appOnlyScope, include: [] },
  "YouTube app only"
);
eq(emptyInclude.kind, "UNRESOLVED", "empty include on APP_ONLY -> UNRESOLVED");
eq(emptyInclude.unresolvedReason, "MALFORMED_CLAUSE", "reason for empty include");

// -- 9. option.scope runs through the SAME validator ----------------------

const optionScope = validateOptionScope(appOnlyScope, "YouTube app only");
eq(optionScope.kind, "APP_ONLY", "option.scope validates identically to scope");
const optionBad = validateOptionScope(null, "anything");
eq(optionBad.kind, "UNRESOLVED", "null option.scope -> UNRESOLVED");

// -- 10. Start gating: UNRESOLVED forces canStartCommitment=false ---------

const gatedBad = enforceScopeStartGates({ canStartCommitment: true }, leaked);
eq(gatedBad.canStartCommitment, false, "UNRESOLVED must disable Start");
eq(gatedBad.clarificationRequired, true, "UNRESOLVED must require clarification");

const gatedGood = enforceScopeStartGates({ canStartCommitment: true }, validApp);
eq(gatedGood.canStartCommitment, true, "enforceable scope must NOT disable Start");

// -- 11. UNRESOLVED with candidateScopes preserves alternatives ----------

const unresolvedWithCandidates = validateScope(
  {
    version: 1,
    kind: "UNRESOLVED",
    resolution: "EXCLUSIVE",
    identity: "YOUTUBE",
    include: [],
    exempt: [],
    confidence: 0.31,
    appliesToText: null,
    unresolvedReason: "HOST_AMBIGUOUS",
    evidenceSpans: ["only YouTube"],
    candidateScopes: [appOnlyScope, appOnlyScope, appOnlyScope],
  },
  "only YouTube"
);
eq(unresolvedWithCandidates.kind, "UNRESOLVED", "UNRESOLVED preserved");
eq(unresolvedWithCandidates.unresolvedReason, "HOST_AMBIGUOUS", "reason preserved");
eq(unresolvedWithCandidates.candidateScopes.length, 3, "3 candidateScopes preserved");

// -- 12. Dataset self-check: v9_scope_semantics.jsonl ---------------------

const DATASET_PATH = path.join(
  __dirname,
  "..",
  "evals",
  "datasets",
  "v9_scope_semantics.jsonl"
);
if (!fs.existsSync(DATASET_PATH)) {
  console.error("dataset missing:", DATASET_PATH);
  process.exit(1);
}
const raw = fs.readFileSync(DATASET_PATH, "utf8");
const lines = raw.split(/\r?\n/).filter((l) => l.trim().length > 0);
check(lines.length >= 150, `v9 dataset must have >= 150 rows, has ${lines.length}`);

const perKind = new Map();
let totalRows = 0;
let unknownKindRows = 0;
let evidenceMissingRows = 0;
let appliesToLeakRows = 0;
let unresolvedRows = 0;
for (const line of lines) {
  totalRows += 1;
  const row = JSON.parse(line);
  check(typeof row.id === "string" && row.id.length > 0, "row.id required");
  check(typeof row.userPromise === "string", "row.userPromise required");
  const expected = row.expectedScope;
  check(expected && typeof expected === "object", `row ${row.id}: expectedScope required`);
  const kind = expected.kind;
  check(SCOPE_KINDS.has(kind), `row ${row.id}: unknown expected kind ${kind}`);
  perKind.set(kind, (perKind.get(kind) || 0) + 1);
  if (kind === "UNRESOLVED") unresolvedRows += 1;
  if (expected.identity && !SCOPE_IDENTITIES.has(expected.identity)) {
    unknownKindRows += 1;
  }
  if (expected.appliesToText && /\b(?:com|org|net|io)\.[a-zA-Z0-9_.]+\b/i.test(expected.appliesToText)) {
    appliesToLeakRows += 1;
  }
  if (
    kind !== "UNRESOLVED" &&
    kind !== "PACKAGE_PINNED" &&
    (!Array.isArray(expected.evidenceSpans) || expected.evidenceSpans.length === 0)
  ) {
    evidenceMissingRows += 1;
  }
}
eq(unknownKindRows, 0, "no row expected an unknown identity value");
eq(appliesToLeakRows, 0, "no row's expected appliesToText leaks a package id");
eq(evidenceMissingRows, 0, "every non-UNRESOLVED / non-PACKAGE_PINNED row must supply evidenceSpans");

check(perKind.size >= 8, "dataset must cover at least 8 of the 11 kinds");
check(unresolvedRows >= 15, `dataset needs >= 15 UNRESOLVED rows (has ${unresolvedRows})`);

console.log("v9 dataset per-kind counts:");
for (const [kind, count] of [...perKind.entries()].sort()) {
  console.log(`  ${kind.padEnd(32)} ${count}`);
}
console.log(`  TOTAL                             ${totalRows}`);

console.log(`ASSERTIONS_PASSED=${ASSERTIONS}`);
