/**
 * Offline smoke for the Express API boundary in backend/server.js:
 * auth, body parsing/limits, validation, provider-error redaction, success shapes.
 * No network, no model calls, no .env: dotenv, openai and all services are stubbed.
 * Run: node backend/smoke_api_boundary.js
 */
const assert = require("assert");
const Module = require("module");
const path = require("path");

const SECRET = "boundary-test-secret";
const FAKE_KEY = "sk-test-FAKEKEY0123456789abcdef";
const USER_CANARY = "USER_PRIVATE_PROMISE_CANARY";
const UPSTREAM_CANARY = "UPSTREAM_RAW_BODY_CANARY";
const FORBIDDEN = [FAKE_KEY, "FAKEKEY0123456789", USER_CANARY, UPSTREAM_CANARY, SECRET];

function stubModule(request, exportsObj) {
  const resolved = require.resolve(request, { paths: [__dirname] });
  const mod = new Module(resolved);
  mod.filename = resolved;
  mod.loaded = true;
  mod.exports = exportsObj;
  require.cache[resolved] = mod;
}

stubModule("dotenv", { config: () => ({ parsed: {} }) });

let openaiBehavior = null;
const openaiCtorOpts = [];
class FakeOpenAI {
  constructor(opts) {
    openaiCtorOpts.push(opts || {});
    this.chat = {
      completions: {
        create: async (...args) => openaiBehavior(...args),
      },
    };
  }
}
stubModule("openai", FakeOpenAI);

const behaviors = {};
const classifier = require("./classifierService");
classifier.getClassifierProvider = () => ({ kind: "test", deployment: "test" });
classifier.classifyActivity = async (body) => behaviors.classify(body);
const compiler = require("./promiseCompilerService");
compiler.getPromiseCompilerProvider = () => ({ kind: "test", deployment: "test" });
compiler.compilePromise = async (promise, opts) => behaviors.compile(promise, opts);
const speech = require("./speechTranscriptionService");
speech.getSpeechHealth = () => ({ configured: false, region: null, language: null, model: null });
speech.transcribePromise = async (body) => behaviors.transcribe(body);
const dossier = require("./appDossierService");
dossier.researchApp = async (body) => behaviors.research(body);

for (const key of [
  "WEBSITE_HOSTNAME",
  "STRLIX_REQUIRE_APP_SECRET",
  "OPENAI_API_KEY",
  "AZURE_OPENAI_API_KEY",
  "AZURE_OPENAI_ENDPOINT",
]) {
  delete process.env[key];
}
process.env.STRLIX_BACKEND_APP_SECRET = SECRET;
process.env.STRLIX_SERVER_NO_LISTEN = "1";
delete process.env.STRLIX_USER_SESSION_AUTH;

const logs = [];
for (const level of ["log", "warn", "error"]) {
  const original = console[level];
  console[level] = (...args) => {
    logs.push(args.map((a) => (typeof a === "string" ? a : JSON.stringify(a))).join(" "));
    if (process.env.SMOKE_VERBOSE === "1") original(...args);
  };
}

const { app } = require(path.join(__dirname, "server.js"));
const { resetRateLimitsForTests, clientIp } = require("./rateLimit");
const { completeJsonChatWithFallback, MODEL_MAX_RETRIES } = require("./azureModelRouting");

function assertClean(label, text) {
  for (const needle of FORBIDDEN) {
    assert.ok(!text.includes(needle), `${label} leaked ${needle.slice(0, 12)}…`);
  }
  assert.ok(!/Bearer\s+sk-/i.test(text), `${label} leaked bearer token`);
}

async function run() {
  const server = await new Promise((resolve) => {
    const s = app.listen(0, "127.0.0.1", () => resolve(s));
  });
  const base = `http://127.0.0.1:${server.address().port}`;

  let resetEachCall = true;
  async function call(method, route, { body, raw, secret = SECRET, contentType, headers: extra = {} } = {}) {
    if (resetEachCall) resetRateLimitsForTests();
    const headers = { ...extra };
    if (secret !== null) headers["x-strlix-app-secret"] = secret;
    let payload;
    if (raw !== undefined) {
      payload = raw;
      headers["content-type"] = contentType || "application/json";
    } else if (body !== undefined) {
      payload = JSON.stringify(body);
      headers["content-type"] = "application/json";
    }
    const res = await fetch(base + route, { method, headers, body: payload });
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch (_) {
      json = null;
    }
    return { status: res.status, text, json, type: res.headers.get("content-type") || "" };
  }

  const protectedRoutes = [
    "/research-app",
    "/classify",
    "/compile-promise",
    "/transcribe-promise",
    "/draft-focus-promise",
  ];

  // 1. /health is public.
  const health = await call("GET", "/health", { secret: null });
  assert.strictEqual(health.status, 200);
  assert.strictEqual(health.json.ok, true);
  assert.strictEqual(health.json.appSecretRequired, true);
  assertClean("health", health.text);

  // 2. Protected routes reject missing, wrong, and same-length-wrong secrets.
  const sameLengthWrong = SECRET.replace(/.$/, SECRET.endsWith("x") ? "y" : "x");
  for (const route of protectedRoutes) {
    for (const secret of [null, "", "wrong", sameLengthWrong, `${SECRET}x`]) {
      const r = await call("POST", route, { body: { promise: "hi" }, secret });
      assert.strictEqual(r.status, 401, `${route} secret=${JSON.stringify(secret)}`);
      assert.deepStrictEqual(r.json, { error: "unauthorized" });
    }
  }

  // 3. Auth precedes body parsing: unauthenticated oversized / malformed bodies get 401, not 413/400.
  const sevenMb = JSON.stringify({ audioBase64: "A".repeat(7 * 1024 * 1024) });
  const unauthBig = await call("POST", "/transcribe-promise", { raw: sevenMb, secret: null });
  assert.strictEqual(unauthBig.status, 401);
  const unauthMalformed = await call("POST", "/compile-promise", { raw: "{bad", secret: "nope" });
  assert.strictEqual(unauthMalformed.status, 401);

  // 4. Malformed JSON: 400 JSON body, no stack, no echoed content.
  logs.length = 0;
  const malformed = await call("POST", "/compile-promise", {
    raw: `{"promise": "${USER_CANARY}", oops}`,
  });
  assert.strictEqual(malformed.status, 400);
  assert.ok(malformed.type.includes("application/json"), malformed.type);
  assert.deepStrictEqual(malformed.json, { error: "invalid_json" });
  assert.ok(!/at \S+ \(/.test(malformed.text), "stack trace in response");
  assertClean("malformed response", malformed.text);
  assertClean("malformed logs", logs.join("\n"));
  assert.ok(logs.some((l) => l.includes("entity.parse.failed")), "parse failure logged by type");

  logs.length = 0;
  const privatePath = await call("POST", `/${USER_CANARY}`, { raw: "{bad" });
  assert.strictEqual(privatePath.status, 400);
  assertClean("malformed private path logs", logs.join("\n"));

  // Non-object top-level JSON is rejected by strict parsing.
  const scalar = await call("POST", "/classify", { raw: `"${USER_CANARY}"` });
  assert.strictEqual(scalar.status, 400);
  assert.deepStrictEqual(scalar.json, { error: "invalid_json" });

  // Unsupported charset: 415 invalid_request.
  const charset = await call("POST", "/compile-promise", {
    raw: "{}",
    contentType: "application/json; charset=latin1",
  });
  assert.strictEqual(charset.status, 415);
  assert.deepStrictEqual(charset.json, { error: "invalid_request" });

  // 5. Oversized per-route limits: 413 JSON, no echo.
  logs.length = 0;
  const bigCompile = await call("POST", "/compile-promise", {
    raw: JSON.stringify({ promise: USER_CANARY.repeat(5000) }),
  });
  assert.strictEqual(bigCompile.status, 413);
  assert.deepStrictEqual(bigCompile.json, { error: "payload_too_large" });
  const bigClassify = await call("POST", "/classify", {
    raw: JSON.stringify({ packageName: "p", goal: "g", screenText: "x".repeat(500 * 1024) }),
  });
  assert.strictEqual(bigClassify.status, 413);
  assert.deepStrictEqual(bigClassify.json, { error: "payload_too_large" });
  const bigTranscribe = await call("POST", "/transcribe-promise", { raw: sevenMb });
  assert.strictEqual(bigTranscribe.status, 413);
  assertClean("oversize logs", logs.join("\n"));

  // /classify's 400kb parser admits a 300kb body (screenshot room); the field cap then applies.
  behaviors.classify = async () => ({
    decision: "WARN",
    confidence: 0.4,
    verificationIncomplete: true,
  });
  const midClassify = await call("POST", "/classify", {
    body: { packageName: "p", goal: "g", screenText: "s", screenshotBase64: "x".repeat(300 * 1024) },
  });
  assert.strictEqual(midClassify.status, 200);
  assert.strictEqual(midClassify.json.decision, "WARN");
  assert.strictEqual(midClassify.json.verificationIncomplete, true);

  // 6. Route validation stays predictable.
  const cases = [
    ["/compile-promise", {}, "promise must be a non-empty string"],
    ["/compile-promise", { promise: "   " }, "promise must be a non-empty string"],
    ["/compile-promise", { promise: 42 }, "promise must be a non-empty string"],
    ["/draft-focus-promise", { promise: ["x"] }, "promise must be a non-empty string"],
    ["/classify", { packageName: "p", goal: "g" }, "packageName, screenText, and goal must be strings"],
  ];
  for (const [route, body, message] of cases) {
    const r = await call("POST", route, { body });
    assert.strictEqual(r.status, 400, `${route} ${JSON.stringify(body)}`);
    assert.deepStrictEqual(r.json, { error: message });
  }
  const arrayBody = await call("POST", "/compile-promise", { raw: "[]" });
  assert.strictEqual(arrayBody.status, 400);

  // Service-level 4xx keep their existing {error, detail} shape.
  behaviors.compile = async () => {
    throw Object.assign(new Error("promise must be a non-empty string"), { statusCode: 400 });
  };
  const svc400 = await call("POST", "/compile-promise", { body: { promise: "x" } });
  assert.strictEqual(svc400.status, 400);
  assert.deepStrictEqual(svc400.json, {
    error: "promise must be a non-empty string",
    detail: "promise must be a non-empty string",
  });
  behaviors.transcribe = async () => {
    throw Object.assign(new Error("audioBase64 or transcript is required"), { statusCode: 400 });
  };
  const tr400 = await call("POST", "/transcribe-promise", { body: {} });
  assert.deepStrictEqual(tr400.json, {
    ok: false,
    error: "audioBase64 or transcript is required",
    detail: "audioBase64 or transcript is required",
  });

  // 7. Provider failures: no keys, auth headers, raw upstream bodies, prompts, or user content.
  const sdkAuthError = () =>
    Object.assign(
      new Error(`401 Incorrect API key provided: ${FAKE_KEY}. Authorization: Bearer ${FAKE_KEY}`),
      {
        name: "AuthenticationError",
        status: 401,
        headers: { authorization: `Bearer ${FAKE_KEY}` },
        error: { message: UPSTREAM_CANARY },
      }
    );
  const failures = [
    {
      route: "/compile-promise",
      body: { promise: USER_CANARY },
      set: () => {
        behaviors.compile = async () => {
          throw Object.assign(new Error(UPSTREAM_CANARY), {
            statusCode: 502,
            name: USER_CANARY,
            code: UPSTREAM_CANARY,
            raw: `{"reply":"${USER_CANARY} ${UPSTREAM_CANARY}"}`,
          });
        };
      },
      status: 502,
      json: { error: "Promise compilation failed" },
    },
    {
      route: "/compile-promise",
      body: { promise: USER_CANARY },
      set: () => {
        behaviors.compile = async () => {
          throw sdkAuthError();
        };
      },
      status: 500,
      json: { error: "Promise compilation failed" },
    },
    {
      route: "/classify",
      body: { packageName: "com.x", goal: USER_CANARY, screenText: USER_CANARY },
      set: () => {
        behaviors.classify = async () => {
          throw Object.assign(new Error("Model did not return valid JSON"), {
            statusCode: 502,
            raw: `system prompt... ${USER_CANARY} ${UPSTREAM_CANARY}`,
          });
        };
      },
      status: 502,
      json: { error: "Classification failed" },
    },
    {
      route: "/classify",
      body: { packageName: "com.x", goal: "g", screenText: USER_CANARY },
      set: () => {
        behaviors.classify = async () => {
          throw sdkAuthError();
        };
      },
      status: 500,
      json: { error: "Classification failed" },
    },
    {
      route: "/transcribe-promise",
      body: { transcript: USER_CANARY },
      set: () => {
        behaviors.transcribe = async () => {
          throw Object.assign(
            new Error(`Azure Speech failed: ${UPSTREAM_CANARY} Ocp-Apim-Subscription-Key=${FAKE_KEY}`),
            { statusCode: 502, raw: `${UPSTREAM_CANARY} ${USER_CANARY}` }
          );
        };
      },
      status: 502,
      json: { ok: false, error: "Promise transcription failed" },
    },
    {
      route: "/research-app",
      body: { packageName: USER_CANARY },
      set: () => {
        behaviors.research = async () => {
          throw new Error(`upstream said ${UPSTREAM_CANARY} for ${USER_CANARY}`);
        };
      },
      status: 500,
      json: { error: "App research failed" },
    },
  ];
  for (const f of failures) {
    f.set();
    logs.length = 0;
    const r = await call("POST", f.route, { body: f.body });
    const label = `${f.route} ${f.status}`;
    assert.strictEqual(r.status, f.status, label);
    assert.deepStrictEqual(r.json, f.json, label);
    assertClean(`${label} response`, r.text);
    assertClean(`${label} logs`, logs.join("\n"));
    assert.ok(logs.length > 0, `${label} should log an error event`);
  }

  // draft-focus-promise: SDK failure falls back to the keyword draft; logs stay clean.
  process.env.OPENAI_API_KEY = FAKE_KEY;
  openaiBehavior = async () => {
    throw sdkAuthError();
  };
  logs.length = 0;
  const draftFail = await call("POST", "/draft-focus-promise", {
    body: { promise: `study 30 minutes ${USER_CANARY}` },
  });
  assert.strictEqual(draftFail.status, 200);
  assert.strictEqual(draftFail.json.durationMinutes, 30);
  assertClean("draft logs", logs.join("\n"));
  delete process.env.OPENAI_API_KEY;

  // 8. Success shapes unchanged (handler passes service results through untouched).
  const compileOk = {
    policy: { durationMinutes: 60 },
    needsClarification: false,
    clarificationQuestion: null,
    clarificationOptions: [],
    promptVersion: "vX",
  };
  behaviors.compile = async (promise, opts) => {
    assert.strictEqual(promise, "block reels for 1 hour");
    assert.strictEqual(opts.selectedClarificationOptionId, "a");
    return compileOk;
  };
  const okCompile = await call("POST", "/compile-promise", {
    body: { promise: "block reels for 1 hour", selectedClarificationOptionId: "a" },
  });
  assert.strictEqual(okCompile.status, 200);
  assert.deepStrictEqual(okCompile.json, compileOk);

  const classifyOk = { decision: "BLOCK", confidence: 0.8, reason: "r", category: "c" };
  behaviors.classify = async () => classifyOk;
  const okClassify = await call("POST", "/classify", {
    body: { packageName: "p", screenText: "s", goal: "g" },
  });
  assert.deepStrictEqual(okClassify.json, classifyOk);

  const transcribeOk = { ok: true, transcript: "hi", provider: "mock", language: USER_CANARY, latencyMs: 1, confidence: 1 };
  behaviors.transcribe = async () => transcribeOk;
  logs.length = 0;
  const okTranscribe = await call("POST", "/transcribe-promise", { body: { mock: true } });
  assert.deepStrictEqual(okTranscribe.json, transcribeOk);
  assertClean("transcribe success logs", logs.join("\n"));

  const researchOk = { packageName: "p", surfaces: [] };
  behaviors.research = async () => researchOk;
  const okResearch = await call("POST", "/research-app", { body: { packageName: "p" } });
  assert.deepStrictEqual(okResearch.json, researchOk);

  const okDraft = await call("POST", "/draft-focus-promise", { body: { promise: "study 2 hours strict" } });
  assert.strictEqual(okDraft.status, 200);
  assert.deepStrictEqual(Object.keys(okDraft.json).sort(), [
    "allowedKeywords",
    "blockedKeywords",
    "clarifyingQuestion",
    "durationMinutes",
    "strictness",
    "suggestedAppRules",
  ]);
  assert.strictEqual(okDraft.json.durationMinutes, 120);

  // 9. Hosted runtime without a secret fails closed on POST, /health stays public.
  delete process.env.STRLIX_BACKEND_APP_SECRET;
  process.env.WEBSITE_HOSTNAME = "example.azurewebsites.net";
  const hosted = await call("POST", "/compile-promise", { body: { promise: "x" }, secret: null });
  assert.strictEqual(hosted.status, 503);
  assert.deepStrictEqual(hosted.json, { error: "app_secret_not_configured" });
  assert.strictEqual((await call("GET", "/health", { secret: null })).status, 200);
  delete process.env.WEBSITE_HOSTNAME;
  process.env.STRLIX_BACKEND_APP_SECRET = SECRET;

  // 10. Per-field caps reject before any provider call.
  let providerCalls = 0;
  behaviors.compile = async () => {
    providerCalls += 1;
    return compileOk;
  };
  behaviors.classify = async () => {
    providerCalls += 1;
    return classifyOk;
  };
  const longPromise = await call("POST", "/compile-promise", { body: { promise: "a".repeat(4001) } });
  assert.strictEqual(longPromise.status, 400);
  assert.deepStrictEqual(longPromise.json, { error: "promise_too_long" });
  const longScreen = await call("POST", "/classify", {
    body: { packageName: "p", goal: "g", screenText: "x".repeat(20001) },
  });
  assert.strictEqual(longScreen.status, 400);
  assert.deepStrictEqual(longScreen.json, { error: "input_too_long" });
  const longDraft = await call("POST", "/draft-focus-promise", { body: { promise: "a".repeat(4001) } });
  assert.strictEqual(longDraft.status, 400);
  assert.strictEqual(providerCalls, 0, "caps must reject before provider calls");

  // 11. Labelled keys: per-tester auth, rotation overlap, revocation, legacy still accepted.
  process.env.STRLIX_BACKEND_APP_SECRETS = "t1:tester-one-key,t2:tester-two-key";
  for (const key of ["tester-one-key", "tester-two-key", SECRET]) {
    const r = await call("POST", "/compile-promise", { body: { promise: "x" }, secret: key });
    assert.strictEqual(r.status, 200, `key ${key.slice(0, 6)} accepted`);
  }
  process.env.STRLIX_BACKEND_APP_SECRETS = "t2:tester-two-key";
  const revoked = await call("POST", "/compile-promise", { body: { promise: "x" }, secret: "tester-one-key" });
  assert.strictEqual(revoked.status, 401, "revoked tester key rejected");
  const malformedList = "nolabel,:nokey,t3:";
  process.env.STRLIX_BACKEND_APP_SECRETS = malformedList;
  const onlyLegacy = await call("POST", "/compile-promise", { body: { promise: "x" }, secret: "nokey" });
  assert.strictEqual(onlyLegacy.status, 401, "malformed entries are ignored");
  delete process.env.STRLIX_BACKEND_APP_SECRETS;

  // 12. Rate limits: burst then 429 with Retry-After; isolated per caller; unauth never counts.
  resetEachCall = false;
  resetRateLimitsForTests();
  process.env.STRLIX_BACKEND_APP_SECRETS = "t1:tester-one-key,t2:tester-two-key";
  providerCalls = 0;
  for (let i = 0; i < 10; i++) {
    const r = await call("POST", "/compile-promise", { body: { promise: "x" }, secret: "tester-one-key" });
    assert.strictEqual(r.status, 200, `burst call ${i + 1}`);
  }
  const limited = await call("POST", "/compile-promise", { body: { promise: "x" }, secret: "tester-one-key" });
  assert.strictEqual(limited.status, 429);
  assert.deepStrictEqual(limited.json, { error: "rate_limited" });
  assert.strictEqual(providerCalls, 10, "rate-limited request must not reach the provider");
  const otherTester = await call("POST", "/compile-promise", { body: { promise: "x" }, secret: "tester-two-key" });
  assert.strictEqual(otherTester.status, 200, "tester B is not throttled by tester A");
  const otherRoute = await call("POST", "/classify", {
    body: { packageName: "p", screenText: "s", goal: "g" },
    secret: "tester-one-key",
  });
  assert.strictEqual(otherRoute.status, 200, "limits are per route");
  for (let i = 0; i < 20; i++) {
    await call("POST", "/compile-promise", { body: { promise: "x" }, secret: "wrong" });
  }
  const afterUnauth = await call("POST", "/compile-promise", { body: { promise: "x" }, secret: "tester-two-key" });
  assert.strictEqual(afterUnauth.status, 200, "unauthenticated floods do not consume buckets");

  const retryRes = await fetch(`${base}/compile-promise`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-strlix-app-secret": "tester-one-key" },
    body: JSON.stringify({ promise: "x" }),
  });
  assert.strictEqual(retryRes.status, 429);
  assert.ok(Number(retryRes.headers.get("retry-after")) > 0, "Retry-After set");

  // Kill switch: paid routes 503, /health unaffected, no provider call.
  resetRateLimitsForTests();
  process.env.STRLIX_PAID_ROUTES_DISABLED = "1";
  providerCalls = 0;
  const killed = await call("POST", "/compile-promise", { body: { promise: "x" }, secret: "tester-one-key" });
  assert.strictEqual(killed.status, 503);
  assert.deepStrictEqual(killed.json, { error: "paid_routes_disabled" });
  assert.strictEqual((await call("GET", "/health", { secret: null })).status, 200);
  assert.strictEqual(providerCalls, 0);
  delete process.env.STRLIX_PAID_ROUTES_DISABLED;

  // Invalid JSON and schema-rejected requests must not consume the daily paid-call cap.
  resetRateLimitsForTests();
  process.env.STRLIX_DAILY_PAID_CALL_CAP = "1";
  resetEachCall = false;
  const malformedBeforeCap = await call("POST", "/classify", { raw: "{" });
  assert.strictEqual(malformedBeforeCap.status, 400);
  const invalidBeforeCap = await call("POST", "/classify", { body: { packageName: 4, screenText: "s", goal: "g" } });
  assert.strictEqual(invalidBeforeCap.status, 400);
  const firstPaid = await call("POST", "/classify", { body: { packageName: "p", screenText: "s", goal: "g" } });
  assert.strictEqual(firstPaid.status, 200);
  const afterDailyCap = await call("POST", "/classify", { body: { packageName: "p", screenText: "s", goal: "g" } });
  assert.strictEqual(afterDailyCap.status, 503);
  delete process.env.STRLIX_DAILY_PAID_CALL_CAP;

  // Global daily cap counts valid paid operations, not invalid requests or offline fallbacks.
  resetRateLimitsForTests();
  process.env.STRLIX_DAILY_PAID_CALL_CAP = "3";
  resetEachCall = false;
  for (let i = 0; i < 3; i++) {
    const r = await call("POST", "/classify", {
      body: { packageName: "p", screenText: "s", goal: "g" },
      secret: i % 2 ? "tester-one-key" : "tester-two-key",
    });
    assert.strictEqual(r.status, 200);
  }
  const capped = await call("POST", "/classify", {
    body: { packageName: "p", screenText: "s", goal: "g" },
    secret: "tester-one-key",
  });
  assert.strictEqual(capped.status, 503);
  assert.deepStrictEqual(capped.json, { error: "daily_capacity_reached" });
  delete process.env.STRLIX_DAILY_PAID_CALL_CAP;
  delete process.env.STRLIX_BACKEND_APP_SECRETS;
  resetRateLimitsForTests();
  resetEachCall = true;

  // Hosted client identity comes from App Service's overwritten client-ip header;
  // caller-controlled X-Forwarded-For is ignored.
  process.env.WEBSITE_HOSTNAME = "example.azurewebsites.net";
  const fakeReq = (clientIpHeader, xff = "") => ({
    get: (h) => (h === "client-ip" ? clientIpHeader : h === "x-forwarded-for" ? xff : ""),
    socket: { remoteAddress: "10.0.0.1" },
  });
  assert.strictEqual(clientIp(fakeReq("203.0.113.9", "6.6.6.6, 198.51.100.4")), "203.0.113.9");
  assert.strictEqual(clientIp(fakeReq("[2001:db8::1]:443", "6.6.6.6")), "2001:db8::1");
  assert.strictEqual(clientIp(fakeReq("not-an-ip", "198.51.100.4")), "10.0.0.1");
  assert.strictEqual(clientIp(fakeReq("", "198.51.100.4")), "10.0.0.1");
  delete process.env.WEBSITE_HOSTNAME;

  // 13. Model routing: quota errors stop the chain; other errors fall back; SDK retries capped.
  process.env.AZURE_OPENAI_ENDPOINT = "https://example.invalid";
  process.env.AZURE_OPENAI_API_VERSION = "test";
  let modelCalls = 0;
  const provider = { kind: "azure", deployment: "d1" };
  openaiBehavior = async () => {
    modelCalls += 1;
    throw Object.assign(new Error(`429 Too Many Requests ${USER_CANARY}`), { status: 429, name: "RateLimitError" });
  };
  logs.length = 0;
  openaiCtorOpts.length = 0;
  await assert.rejects(
    completeJsonChatWithFallback({ provider, deployments: ["d1", "d2", "d3"], messages: [], maxTokens: 10 })
  );
  assert.strictEqual(modelCalls, 1, "429 must not spill to fallback deployments");
  assert.ok(openaiCtorOpts.every((o) => o.maxRetries === MODEL_MAX_RETRIES && MODEL_MAX_RETRIES <= 1));
  assertClean("quota-stop logs", logs.join("\n"));
  modelCalls = 0;
  openaiBehavior = async () => {
    modelCalls += 1;
    throw Object.assign(new Error("deployment not found"), { status: 404, name: "NotFoundError" });
  };
  await assert.rejects(
    completeJsonChatWithFallback({ provider, deployments: ["d1", "d2", "d3"], messages: [], maxTokens: 10 })
  );
  assert.strictEqual(modelCalls, 3, "non-quota errors still fall back");
  modelCalls = 0;
  openaiBehavior = async () => {
    modelCalls += 1;
    if (modelCalls === 1) throw Object.assign(new Error("boom"), { status: 500 });
    return { choices: [{ message: { content: "{\"ok\":true}" } }] };
  };
  const routed = await completeJsonChatWithFallback({ provider, deployments: ["d1", "d2"], messages: [], maxTokens: 10 });
  assert.strictEqual(routed.deployment, "d2");
  delete process.env.AZURE_OPENAI_ENDPOINT;
  delete process.env.AZURE_OPENAI_API_VERSION;


  // Server survived everything above.
  assert.strictEqual((await call("GET", "/health", { secret: null })).status, 200);

  await new Promise((resolve) => server.close(resolve));
}

run()
  .then(() => {
    process.stdout.write("ASSERTIONS_PASSED=api_boundary\n");
  })
  .catch((err) => {
    process.stderr.write(`${err && err.stack ? err.stack : err}\n`);
    process.exit(1);
  });
