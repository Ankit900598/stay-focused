/**
 * Offline route smoke for STRLIX_USER_SESSION_AUTH=1.
 * Fake providers only. Does not load .env, listen on 8787, or call a model.
 * Run: node backend/smoke_user_session_routes.js
 */
const assert = require("assert");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const Module = require("module");
const { spawnSync } = require("child_process");

const SECRET = "route-smoke-app-secret";
const FAKE_KEY = "sk-test-FAKEKEY0123456789abcdef";
const PROJECT_ID = "strlix-test-audience";
const ISSUER = `https://securetoken.google.com/${PROJECT_ID}`;
const GOOGLE_AUD = "route-smoke-web.apps.googleusercontent.com";
const MAIL_BODY = "PRIVATE_EMAIL_BODY_CANARY_7731";
const MAIL_EMAIL = "route.smoke.user@example.com";

function stubModule(request, exportsObj) {
  const resolved = require.resolve(request, { paths: [__dirname] });
  const mod = new Module(resolved);
  mod.filename = resolved;
  mod.loaded = true;
  mod.exports = exportsObj;
  require.cache[resolved] = mod;
}

stubModule("dotenv", { config: () => ({ parsed: {} }) });

const calls = { classify: 0, compile: 0, research: 0, transcribe: 0, summarize: 0, draftMail: 0, openai: 0 };
const behaviors = {
  classify: async () => {
    calls.classify += 1;
    return { decision: "ALLOW" };
  },
  compile: async () => {
    calls.compile += 1;
    return { ok: true };
  },
  research: async () => {
    calls.research += 1;
    return { ok: true };
  },
  transcribe: async () => {
    calls.transcribe += 1;
    return { provider: "test", transcript: "study", language: "en", confidence: 1, latencyMs: 1 };
  },
  speechPaid: false,
  summarize: async () => {
    calls.summarize += 1;
    return { summary: { containsInstructionsToAssistant: false, text: "Noted." }, deployment: "test" };
  },
  draftMail: async () => {
    calls.draftMail += 1;
    return { draft: { text: "Thanks." }, deployment: "test" };
  },
};

class FakeOpenAI {
  constructor() {
    this.chat = {
      completions: {
        create: async () => {
          calls.openai += 1;
          return {
            choices: [{
              message: {
                content: JSON.stringify({
                  durationMinutes: 25,
                  strictness: "STRICT",
                  allowedKeywords: ["study"],
                  blockedKeywords: [],
                  suggestedAppRules: [],
                  clarifyingQuestion: null,
                }),
              },
            }],
          };
        },
      },
    };
  }
}
stubModule("openai", FakeOpenAI);

const classifier = require("./classifierService");
classifier.getClassifierProvider = () => ({ kind: "test", deployment: "test" });
classifier.classifyActivity = (body) => behaviors.classify(body);
const compiler = require("./promiseCompilerService");
compiler.getPromiseCompilerProvider = () => ({ kind: "test", deployment: "test" });
compiler.compilePromise = (promise, opts) => behaviors.compile(promise, opts);
const speech = require("./speechTranscriptionService");
speech.willCallPaidSpeechProvider = () => behaviors.speechPaid;
speech.transcribePromise = (body) => behaviors.transcribe(body);
const dossier = require("./appDossierService");
dossier.researchApp = (body) => behaviors.research(body);
const mailAssist = require("./mailAssistService");
mailAssist.summarizeMail = (input) => behaviors.summarize(input);
mailAssist.draftMailReply = (input) => behaviors.draftMail(input);

function keyPair(kid) {
  const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const jwk = publicKey.export({ format: "jwk" });
  jwk.kid = kid;
  jwk.alg = "RS256";
  jwk.use = "sig";
  return { jwk, privateKey };
}

function sign(payload, privateKey, kid, issuerAlg = "RS256") {
  const header = Buffer.from(JSON.stringify({ alg: issuerAlg, kid, typ: "JWT" })).toString("base64url");
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const data = `${header}.${body}`;
  const sig = crypto.sign("RSA-SHA256", Buffer.from(data), privateKey).toString("base64url");
  return `${data}.${sig}`;
}

function sessionClaims(sub, overrides = {}) {
  const nowSec = Math.floor(Date.now() / 1000);
  return {
    iss: ISSUER,
    aud: PROJECT_ID,
    sub,
    iat: nowSec,
    exp: nowSec + 3600,
    auth_time: nowSec,
    ...overrides,
  };
}

function googleClaims(overrides = {}) {
  const nowSec = Math.floor(Date.now() / 1000);
  return {
    iss: "https://accounts.google.com",
    aud: GOOGLE_AUD,
    sub: "google-sub-route-1",
    email: MAIL_EMAIL,
    email_verified: true,
    iat: nowSec,
    exp: nowSec + 3600,
    ...overrides,
  };
}

const trusted = keyPair("route-trusted");
const forger = keyPair("route-trusted");
const rotated = keyPair("route-rotated");
const googleKey = keyPair("google-route");
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "strlix-session-routes-"));
const jwksPath = path.join(dir, "jwks.json");
const statePath = path.join(dir, "state.json");
fs.writeFileSync(jwksPath, JSON.stringify({ keys: [trusted.jwk] }), { mode: 0o600 });

for (const key of [
  "WEBSITE_HOSTNAME",
  "STRLIX_REQUIRE_APP_SECRET",
  "OPENAI_API_KEY",
  "AZURE_OPENAI_API_KEY",
  "AZURE_OPENAI_ENDPOINT",
  "STRLIX_PAID_ROUTES_DISABLED",
  "STRLIX_MAIL_ASSIST_ENABLED",
  "STRLIX_GOOGLE_WEB_CLIENT_IDS",
  "STRLIX_FIREBASE_PROJECT_ID",
  "STRLIX_DAILY_PAID_CALL_CAP",
  "STRLIX_AUTH_QUOTA_SCOPE",
]) {
  delete process.env[key];
}
process.env.STRLIX_SERVER_NO_LISTEN = "1";
process.env.STRLIX_USER_SESSION_AUTH = "1";
process.env.STRLIX_AUTH_JWKS_PATH = jwksPath;
process.env.STRLIX_AUTH_PROJECT_ID = PROJECT_ID;
process.env.STRLIX_AUTH_STATE_PATH = statePath;
process.env.STRLIX_BACKEND_APP_SECRET = SECRET;
process.env.STRLIX_USER_ROUTE_CAPACITY = "20";
process.env.STRLIX_USER_ROUTE_PER_HOUR = "20";

const logs = [];
const responses = [];
for (const level of ["log", "warn", "error"]) {
  const original = console[level];
  console[level] = (...args) => {
    logs.push(args.map((a) => (typeof a === "string" ? a : JSON.stringify(a))).join(" "));
    if (process.env.SMOKE_VERBOSE === "1") original(...args);
  };
}

const { app } = require("./server");
const { createFileAuthState } = require("./authStateStore");
const { setJwksFetcherForTests } = require("./googleIdentity");
const store = createFileAuthState(statePath);
setJwksFetcherForTests(async () => ({ keys: [googleKey.jwk], maxAgeSec: 3600 }));

function paidCalls() {
  return calls.classify + calls.compile + calls.research + calls.transcribe + calls.summarize + calls.draftMail + calls.openai;
}

function hostedChild(extraEnv) {
  const script = `
    const Module = require("module");
    const path = require("path");
    function stub(request, exportsObj) {
      const resolved = require.resolve(request, { paths: [process.cwd()] });
      const mod = new Module(resolved);
      mod.filename = resolved;
      mod.loaded = true;
      mod.exports = exportsObj;
      require.cache[resolved] = mod;
    }
    stub("dotenv", { config: () => ({ parsed: {} }) });
    require(path.join(process.cwd(), "server.js"));
  `;
  return spawnSync(process.execPath, ["-e", script], {
    cwd: __dirname,
    env: {
      PATH: process.env.PATH,
      SystemRoot: process.env.SystemRoot,
      TEMP: process.env.TEMP,
      TMP: process.env.TMP,
      STRLIX_SERVER_NO_LISTEN: "1",
      STRLIX_USER_SESSION_AUTH: "1",
      STRLIX_BACKEND_APP_SECRET: "hosted-test-secret",
      WEBSITE_HOSTNAME: "unit-test.example",
      ...extraEnv,
    },
    encoding: "utf8",
  });
}

function featureOffChild() {
  const script = `
    const Module = require("module");
    const path = require("path");
    function stub(request, exportsObj) {
      const resolved = require.resolve(request, { paths: [process.cwd()] });
      const mod = new Module(resolved);
      mod.filename = resolved;
      mod.loaded = true;
      mod.exports = exportsObj;
      require.cache[resolved] = mod;
    }
    stub("dotenv", { config: () => ({ parsed: {} }) });
    stub("openai", class FakeOpenAI {});
    const { app } = require(path.join(process.cwd(), "server.js"));
    const server = app.listen(0, "127.0.0.1", async () => {
      try {
        const base = "http://127.0.0.1:" + server.address().port;
        const health = await fetch(base + "/health");
        const healthBody = await health.json();
        const missing = await fetch(base + "/classify", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: "{",
        });
        const parsed = await fetch(base + "/classify", {
          method: "POST",
          headers: {
            "content-type": "application/json",
            "x-strlix-app-secret": process.env.STRLIX_BACKEND_APP_SECRET,
          },
          body: "{}",
        });
        process.stdout.write("FEATURE_OFF=" + JSON.stringify({
          health: health.status,
          session: healthBody.userSessionAuth,
          missing: missing.status,
          parsed: parsed.status,
          port: server.address().port,
        }) + "\\n");
      } finally {
        server.close();
      }
    });
  `;
  return spawnSync(process.execPath, ["-e", script], {
    cwd: __dirname,
    env: {
      PATH: process.env.PATH,
      SystemRoot: process.env.SystemRoot,
      TEMP: process.env.TEMP,
      TMP: process.env.TMP,
      STRLIX_SERVER_NO_LISTEN: "1",
      STRLIX_BACKEND_APP_SECRET: "feature-off-secret",
    },
    encoding: "utf8",
    timeout: 20000,
  });
}

async function run() {
  const missingProject = hostedChild({});
  assert.notStrictEqual(missingProject.status, 0);
  assert.match(missingProject.stderr, /STRLIX_FIREBASE_PROJECT_ID is required/);

  const missingCredentials = hostedChild({ STRLIX_FIREBASE_PROJECT_ID: "demo-project" });
  assert.notStrictEqual(missingCredentials.status, 0);
  assert.match(missingCredentials.stderr, /Firebase Admin credentials are not available/);

  const credentialFile = path.join(dir, "empty-cred.json");
  fs.writeFileSync(credentialFile, "{}");
  const stringFlagDoesNotScale = hostedChild({
    STRLIX_FIREBASE_PROJECT_ID: "demo-project",
    STRLIX_AUTH_QUOTA_SCOPE: "single-instance",
    GOOGLE_APPLICATION_CREDENTIALS: credentialFile,
  });
  assert.notStrictEqual(stringFlagDoesNotScale.status, 0);
  assert.match(stringFlagDoesNotScale.stderr, /cannot serve paid calls on a hosted runtime/);
  assert.match(stringFlagDoesNotScale.stderr, /STRLIX_AUTH_QUOTA_SCOPE is not a scale setting/);

  const fileOnHosted = hostedChild({
    STRLIX_FIREBASE_PROJECT_ID: "demo-project",
    STRLIX_AUTH_STATE_PATH: statePath,
    STRLIX_AUTH_TABLE_CONNECTION: "DefaultEndpointsProtocol=https;AccountName=example;AccountKey=not-used;EndpointSuffix=core.windows.net",
    GOOGLE_APPLICATION_CREDENTIALS: credentialFile,
  });
  assert.notStrictEqual(fileOnHosted.status, 0);
  assert.match(fileOnHosted.stderr, /STRLIX_AUTH_STATE_PATH cannot be used/);

  const jwksOnHosted = hostedChild({
    STRLIX_FIREBASE_PROJECT_ID: "demo-project",
    STRLIX_AUTH_JWKS_PATH: jwksPath,
    STRLIX_AUTH_STATE_PATH: statePath,
    STRLIX_AUTH_QUOTA_SCOPE: "single-instance",
    GOOGLE_APPLICATION_CREDENTIALS: credentialFile,
  });
  assert.notStrictEqual(jwksOnHosted.status, 0);
  assert.match(jwksOnHosted.stderr, /STRLIX_AUTH_JWKS_PATH cannot be used/);

  const featureOff = featureOffChild();
  assert.strictEqual(featureOff.status, 0, featureOff.stderr);
  const featureOffLine = featureOff.stdout.split("\n").find((line) => line.startsWith("FEATURE_OFF="));
  const featureOffState = JSON.parse(featureOffLine.slice("FEATURE_OFF=".length));
  assert.strictEqual(featureOffState.health, 200);
  assert.strictEqual(featureOffState.session, false);
  assert.strictEqual(featureOffState.missing, 401);
  assert.strictEqual(featureOffState.parsed, 400);
  assert.notStrictEqual(featureOffState.port, 8787);

  const server = await new Promise((resolve) => {
    const listening = app.listen(0, "127.0.0.1", () => resolve(listening));
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  assert.notStrictEqual(server.address().port, 8787);

  async function call(route, { method = "POST", token, body, raw, headers = {} } = {}) {
    const requestHeaders = { ...headers };
    if (token) requestHeaders.authorization = `Bearer ${token}`;
    let payload;
    if (raw !== undefined) {
      payload = raw;
      requestHeaders["content-type"] = requestHeaders["content-type"] || "application/json";
    } else if (body !== undefined) {
      payload = JSON.stringify(body);
      requestHeaders["content-type"] = "application/json";
    }
    const res = await fetch(base + route, { method, headers: requestHeaders, body: payload });
    const text = await res.text();
    responses.push(text);
    let json = null;
    try {
      json = JSON.parse(text);
    } catch (_) {
      json = null;
    }
    return { status: res.status, text, json };
  }

  const classifyBody = { packageName: "com.example.app", screenText: "notes", goal: "study", uid: "body-must-not-win" };
  const userA = sign(sessionClaims("user-a"), trusted.privateKey, "route-trusted");
  const userB = sign(sessionClaims("user-b"), trusted.privateKey, "route-trusted");

  try {
    const health = await call("/health", { method: "GET" });
    assert.strictEqual(health.status, 200);
    assert.strictEqual(health.json.ok, true);
    assert.strictEqual(health.json.userSessionAuth, true);
    const healthWithJunk = await call("/health", { method: "GET", token: "not-a-token" });
    assert.strictEqual(healthWithJunk.status, 200);
    const unknown = await call("/not-a-route", { token: userA, raw: "{" });
    assert.strictEqual(unknown.status, 401);
    assert.strictEqual(unknown.json.error, "unauthorized");
    const unknownWithSecret = await call("/not-a-route", {
      token: userA,
      raw: "{}",
      headers: { "x-strlix-app-secret": SECRET },
    });
    assert.strictEqual(unknownWithSecret.status, 404);
    assert.strictEqual(paidCalls(), 0);

    const paidRoutes = ["/classify", "/compile-promise", "/transcribe-promise", "/research-app", "/draft-focus-promise"];
    for (const route of paidRoutes) {
      const before = paidCalls();
      const missing = await call(route, { raw: "{", headers: { "x-strlix-app-secret": SECRET } });
      assert.strictEqual(missing.status, 401, route);
      assert.strictEqual(missing.json.error, "user_auth_required", route);
      assert.strictEqual(paidCalls(), before, route);
    }
    const mailOff = await call("/mail/summarize", { token: userA, body: {} });
    assert.strictEqual(mailOff.status, 404);
    assert.strictEqual(calls.summarize, 0);

    async function expectAuth(token, code) {
      const before = paidCalls();
      const res = await call("/classify", { token, body: classifyBody, headers: { "x-strlix-app-secret": SECRET } });
      assert.strictEqual(res.status, 401);
      assert.strictEqual(res.json.error, code);
      assert.strictEqual(paidCalls(), before);
      return res;
    }

    await expectAuth(undefined, "user_auth_required");
    await expectAuth("not-a-jwt", "user_auth_invalid");
    const nowSec = Math.floor(Date.now() / 1000);
    await expectAuth(sign(sessionClaims("user-a", { exp: nowSec - 120 }), trusted.privateKey, "route-trusted"), "user_auth_expired");
    await expectAuth(sign(sessionClaims("user-a"), forger.privateKey, "route-trusted"), "user_auth_invalid");
    await expectAuth(sign(sessionClaims("user-a", { aud: "other-project" }), trusted.privateKey, "route-trusted"), "user_auth_wrong_audience");
    const missingIat = sessionClaims("user-a");
    delete missingIat.iat;
    await expectAuth(sign(missingIat, trusted.privateKey, "route-trusted"), "user_auth_invalid");
    await expectAuth(sign(sessionClaims("user-a", { auth_time: nowSec + 180 }), trusted.privateKey, "route-trusted"), "user_auth_invalid");

    await store.revoke("revoked-user", nowSec + 30);
    await expectAuth(sign(sessionClaims("revoked-user", { auth_time: nowSec }), trusted.privateKey, "route-trusted"), "user_auth_revoked");

    const beforeRotate = paidCalls();
    const rotatedToken = sign(sessionClaims("user-rotate"), rotated.privateKey, "route-rotated");
    const unknownKid = await call("/classify", { token: rotatedToken, body: classifyBody });
    assert.strictEqual(unknownKid.status, 401);
    assert.strictEqual(paidCalls(), beforeRotate);
    fs.writeFileSync(jwksPath, JSON.stringify({ keys: [trusted.jwk, rotated.jwk] }), { mode: 0o600 });
    const knownKid = await call("/classify", { token: rotatedToken, body: classifyBody });
    assert.strictEqual(knownKid.status, 200, knownKid.text);
    assert.strictEqual(knownKid.json.decision, "ALLOW");

    await store.reset();
    process.env.STRLIX_USER_ROUTE_CAPACITY = "1";
    process.env.STRLIX_USER_ROUTE_PER_HOUR = "1";
    const firstA = await call("/classify", { token: userA, body: { ...classifyBody, uid: "user-b" } });
    assert.strictEqual(firstA.status, 200, firstA.text);
    const secondA = await call("/classify", { token: userA, body: { ...classifyBody, uid: "user-c" } });
    assert.strictEqual(secondA.status, 429);
    assert.strictEqual(secondA.json.error, "rate_limited");
    const firstB = await call("/classify", { token: userB, body: classifyBody });
    assert.strictEqual(firstB.status, 200, firstB.text);

    const restartScript = path.join(dir, "restart.js");
    fs.writeFileSync(restartScript, `
      const { createFileAuthState } = require(${JSON.stringify(path.join(__dirname, "authStateStore.js"))});
      (async () => {
        const store = createFileAuthState(process.argv[2]);
        const blocked = await store.takeUser("user-a", "/classify", { capacity: 1, perHour: 1 }, Date.now(), null);
        if (blocked.ok) process.exit(2);
        if (!(await store.isRevoked("revoked-user", ${nowSec}))) process.exit(3);
      })().catch((err) => {
        console.error(err && err.message ? err.message : err);
        process.exit(1);
      });
    `);
    await store.revoke("revoked-user", nowSec + 30);
    const restarted = spawnSync(process.execPath, [restartScript, statePath], { encoding: "utf8" });
    assert.strictEqual(restarted.status, 0, restarted.stderr);

    await store.reset();
    process.env.STRLIX_USER_ROUTE_CAPACITY = "1";
    const concurrent = await Promise.all([
      call("/classify", { token: userA, body: classifyBody, headers: { "x-strlix-request-id": "parallel-user-a-1" } }),
      call("/classify", { token: userA, body: classifyBody, headers: { "x-strlix-request-id": "parallel-user-a-2" } }),
    ]);
    const concurrentStatuses = concurrent.map((item) => item.status).sort();
    assert.deepStrictEqual(concurrentStatuses, [200, 429]);

    await store.reset();
    process.env.STRLIX_USER_ROUTE_CAPACITY = "8";
    let releaseClassify;
    const classifyBeforeInflight = calls.classify;
    behaviors.classify = () => {
      calls.classify += 1;
      return new Promise((resolve) => {
        releaseClassify = resolve;
      });
    };
    const inflightPromise = Promise.all([
      call("/classify", { token: userA, body: classifyBody, headers: { "x-strlix-request-id": "inflight-1" } }),
      call("/classify", { token: userA, body: classifyBody, headers: { "x-strlix-request-id": "inflight-1" } }),
    ]);
    const inflightDeadline = Date.now() + 3000;
    while (calls.classify < classifyBeforeInflight + 1) {
      if (Date.now() > inflightDeadline) break;
      await new Promise((resolve) => setTimeout(resolve, 15));
    }
    assert.strictEqual(calls.classify, classifyBeforeInflight + 1);
    await new Promise((resolve) => setTimeout(resolve, 50));
    releaseClassify({ decision: "ALLOW" });
    const inflight = await inflightPromise;
    assert.strictEqual(inflight.filter((item) => item.status === 200).length, 1);
    assert.strictEqual(inflight.filter((item) => item.json && item.json.error === "request_in_flight").length, 1);
    behaviors.classify = async () => {
      calls.classify += 1;
      return { decision: "ALLOW" };
    };

    await store.reset();
    const retry1 = await call("/classify", { token: userA, body: classifyBody, headers: { "x-strlix-request-id": "retry-key-1" } });
    assert.strictEqual(retry1.status, 200, retry1.text);
    const classifyAfterRetry = calls.classify;
    const retry2 = await call("/classify", { token: userA, body: classifyBody, headers: { "x-strlix-request-id": "retry-key-1" } });
    assert.strictEqual(retry2.status, 409);
    assert.strictEqual(retry2.json.error, "duplicate_request");
    assert.strictEqual(calls.classify, classifyAfterRetry);
    const tokenAsId = await call("/classify", {
      token: userA,
      body: classifyBody,
      headers: { "x-strlix-request-id": userA },
    });
    assert.strictEqual(tokenAsId.status, 400);
    assert.strictEqual(tokenAsId.json.error, "invalid_request_id");

    await store.reset();
    process.env.STRLIX_DAILY_PAID_CALL_CAP = "1";
    process.env.STRLIX_USER_ROUTE_CAPACITY = "8";
    const capped1 = await call("/classify", { token: userA, body: classifyBody });
    assert.strictEqual(capped1.status, 200, capped1.text);
    const classifyAfterCap = calls.classify;
    const globalRace = await Promise.all([
      call("/classify", { token: userA, body: classifyBody }),
      call("/classify", { token: userB, body: classifyBody }),
    ]);
    assert.ok(globalRace.every((item) => item.status === 503), globalRace.map((item) => item.text).join(" | "));
    assert.ok(
      globalRace.every((item) => item.json && item.json.error === "daily_capacity_reached"),
      globalRace.map((item) => `${item.status} ${item.text}`).join(" | ")
    );
    assert.strictEqual(calls.classify, classifyAfterCap);
    delete process.env.STRLIX_DAILY_PAID_CALL_CAP;

    await store.reset();
    process.env.STRLIX_PAID_ROUTES_DISABLED = "1";
    const beforeKill = paidCalls();
    const killed = await call("/compile-promise", { token: userA, body: { promise: "study" } });
    assert.strictEqual(killed.status, 503);
    assert.strictEqual(killed.json.error, "paid_routes_disabled");
    assert.strictEqual(paidCalls(), beforeKill);
    const healthDuringKill = await call("/health", { method: "GET" });
    assert.strictEqual(healthDuringKill.status, 200);
    delete process.env.STRLIX_PAID_ROUTES_DISABLED;

    await store.reset();
    process.env.STRLIX_USER_ROUTE_CAPACITY = "20";
    const compile = await call("/compile-promise", { token: userA, body: { promise: "study algorithms for 25 minutes" } });
    assert.strictEqual(compile.status, 200, compile.text);
    assert.strictEqual(calls.compile > 0, true);
    behaviors.speechPaid = true;
    const transcribed = await call("/transcribe-promise", { token: userA, body: { audioBase64: "YQ==" } });
    assert.strictEqual(transcribed.status, 200, transcribed.text);
    assert.strictEqual(calls.transcribe > 0, true);
    behaviors.speechPaid = false;
    const research = await call("/research-app", { token: userA, body: { packageName: "com.example.app" } });
    assert.strictEqual(research.status, 200, research.text);
    process.env.OPENAI_API_KEY = FAKE_KEY;
    const drafted = await call("/draft-focus-promise", { token: userA, body: { promise: "study algorithms for 25 minutes" } });
    assert.strictEqual(drafted.status, 200, drafted.text);
    assert.strictEqual(calls.openai, 1);
    delete process.env.OPENAI_API_KEY;

    process.env.STRLIX_MAIL_ASSIST_ENABLED = "1";
    process.env.STRLIX_GOOGLE_WEB_CLIENT_IDS = GOOGLE_AUD;
    const mailRaw = await call("/mail/summarize", { raw: "{" });
    assert.strictEqual(mailRaw.status, 401);
    assert.strictEqual(mailRaw.json.error, "user_auth_required");
    assert.strictEqual(calls.summarize, 0);
    await store.revoke("revoked-mail-user", Math.floor(Date.now() / 1000) + 30);
    const revokedMail = await call("/mail/summarize", {
      token: sign(sessionClaims("revoked-mail-user"), trusted.privateKey, "route-trusted"),
      raw: "{",
    });
    assert.strictEqual(revokedMail.status, 401);
    assert.strictEqual(revokedMail.json.error, "user_auth_revoked");
    assert.strictEqual(calls.summarize, 0);
    const googleToken = sign(googleClaims(), googleKey.privateKey, "google-route");
    const mailBody = {
      consent: "mail_ai_v1",
      message: { bodyText: MAIL_BODY, subject: "Lab", fromAddress: "ta@example.com" },
    };
    const mailAnon = await call("/mail/summarize", { body: mailBody, headers: { "x-strlix-app-secret": SECRET } });
    assert.strictEqual(mailAnon.status, 401);
    assert.strictEqual(mailAnon.json.error, "user_auth_required");
    assert.strictEqual(calls.summarize, 0);
    const mailGoogleOnly = await call("/mail/summarize", { token: googleToken, body: mailBody });
    assert.strictEqual(mailGoogleOnly.status, 401);
    assert.strictEqual(calls.summarize, 0);
    const mailSessionOnly = await call("/mail/summarize", { token: userA, body: mailBody });
    assert.strictEqual(mailSessionOnly.status, 401);
    assert.strictEqual(mailSessionOnly.json.error, "user_auth_required");
    assert.strictEqual(calls.summarize, 0);
    const mailNoConsent = await call("/mail/summarize", {
      token: userA,
      body: { message: mailBody.message },
      headers: { "x-strlix-google-id-token": googleToken },
    });
    assert.strictEqual(mailNoConsent.status, 400);
    assert.strictEqual(mailNoConsent.json.error, "consent_required");
    assert.strictEqual(calls.summarize, 0);
    const mailOk = await call("/mail/summarize", {
      token: userA,
      body: mailBody,
      headers: { "x-strlix-google-id-token": googleToken },
    });
    assert.strictEqual(mailOk.status, 200, mailOk.text);
    assert.strictEqual(calls.summarize, 1);
    const mailDraft = await call("/mail/draft-reply", {
      token: userA,
      body: { ...mailBody, userNote: "keep it short" },
      headers: { "x-strlix-google-id-token": googleToken },
    });
    assert.strictEqual(mailDraft.status, 200, mailDraft.text);
    assert.strictEqual(calls.draftMail, 1);
    const wrongGoogle = sign(googleClaims({ aud: "other-client.apps.googleusercontent.com" }), googleKey.privateKey, "google-route");
    const mailWrongGoogle = await call("/mail/summarize", {
      token: userB,
      body: mailBody,
      headers: { "x-strlix-google-id-token": wrongGoogle },
    });
    assert.strictEqual(mailWrongGoogle.status, 401);
    assert.strictEqual(calls.summarize, 1);

    await store.reset();
    process.env.STRLIX_USER_ROUTE_CAPACITY = "1";
    process.env.STRLIX_USER_ROUTE_PER_HOUR = "1";
    delete process.env.STRLIX_DAILY_PAID_CALL_CAP;
    const routeA = await call("/classify", { token: userA, body: classifyBody });
    assert.strictEqual(routeA.status, 200, routeA.text);
    const routeAAgain = await call("/classify", { token: userA, body: classifyBody });
    assert.strictEqual(routeAAgain.status, 429);
    const compileBefore = calls.compile;
    const otherRoute = await call("/compile-promise", { token: userA, body: { promise: "study algorithms for 25 minutes" } });
    assert.strictEqual(otherRoute.status, 200, otherRoute.text);
    assert.strictEqual(calls.compile, compileBefore + 1);

    await store.reset();
    process.env.STRLIX_USER_ROUTE_CAPACITY = "8";
    process.env.STRLIX_DAILY_PAID_CALL_CAP = "1";
    const globalFirst = await call("/classify", { token: userA, body: classifyBody });
    assert.strictEqual(globalFirst.status, 200, globalFirst.text);
    const compileAfterGlobal = calls.compile;
    const globalSecond = await call("/compile-promise", { token: userB, body: { promise: "read notes for 20 minutes" } });
    assert.strictEqual(globalSecond.status, 503);
    assert.strictEqual(globalSecond.json.error, "daily_capacity_reached");
    assert.strictEqual(calls.compile, compileAfterGlobal);

    await store.reset();
    process.env.STRLIX_DAILY_PAID_CALL_CAP = "1";
    const classifyBeforeFail = calls.classify;
    behaviors.classify = async () => {
      calls.classify += 1;
      const err = new Error("upstream");
      err.statusCode = 500;
      throw err;
    };
    const failed = await call("/classify", {
      token: userA,
      body: classifyBody,
      headers: { "x-strlix-request-id": "fail-key-01" },
    });
    assert.strictEqual(failed.status, 500, failed.text);
    assert.strictEqual(calls.classify, classifyBeforeFail + 1);
    const failDeadline = Date.now() + 5000;
    let failedDisk = "";
    while (Date.now() < failDeadline) {
      failedDisk = fs.readFileSync(statePath, "utf8");
      if (failedDisk.includes("fail-key-01") && failedDisk.includes('"status":500')) break;
      await new Promise((resolve) => setTimeout(resolve, 15));
    }
    assert.ok(failedDisk.includes('"status":500'), failedDisk);
    const failedRetry = await call("/classify", {
      token: userA,
      body: classifyBody,
      headers: { "x-strlix-request-id": "fail-key-01" },
    });
    assert.strictEqual(failedRetry.status, 503, `${failedRetry.text}\n${fs.readFileSync(statePath, "utf8")}`);
    assert.strictEqual(failedRetry.json.error, "paid_attempt_already_failed");
    assert.strictEqual(calls.classify, classifyBeforeFail + 1);
    const failedNewId = await call("/classify", {
      token: userA,
      body: classifyBody,
      headers: { "x-strlix-request-id": "fail-key-02" },
    });
    assert.strictEqual(failedNewId.status, 503);
    assert.strictEqual(failedNewId.json.error, "daily_capacity_reached");
    assert.strictEqual(calls.classify, classifyBeforeFail + 1);
    behaviors.classify = async () => {
      calls.classify += 1;
      return { decision: "ALLOW" };
    };
    delete process.env.STRLIX_DAILY_PAID_CALL_CAP;

    const forbidden = [userA, userB, rotatedToken, googleToken, wrongGoogle, SECRET, FAKE_KEY, MAIL_BODY, MAIL_EMAIL, "google-sub-route-1"];
    const haystack = `${logs.join("\n")}\n${responses.join("\n")}\n${fs.readFileSync(statePath, "utf8")}`;
    for (const needle of forbidden) {
      assert.strictEqual(haystack.includes(needle), false, `leaked ${needle.slice(0, 12)}`);
    }

    process.stdout.write("USER_SESSION_ROUTES_OK\n");
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

run().catch((err) => {
  process.stderr.write(`${err && err.stack ? err.stack : err}\n`);
  process.exit(1);
});
