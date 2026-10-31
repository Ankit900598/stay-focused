/**
 * Shared quota state against Azurite, the local Azure Table emulator.
 * Run: node backend/smoke_table_auth_state.js
 * Does not call Azure, load .env, listen on 8787, or call a paid provider.
 */
const assert = require("assert");
const fs = require("fs");
const net = require("net");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const { createTableAuthState, tableClientOptions } = require("./tableAuthState");

const PROJECT = "strlix-table-test";
const ACCOUNT_KEY = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
const CANARY_TOKEN = "token-user-a";
const DUMMY_KEY = "not-a-real-key";
const PAID = ["/classify", "/compile-promise", "/transcribe-promise", "/research-app", "/draft-focus-promise", "/mail/summarize", "/mail/draft-reply"];

if (process.argv[2] === "worker") {
  worker(process.argv[3]).catch((err) => {
    process.stderr.write(`${err && err.code ? err.code : "worker_failed"}\n`);
    process.exit(1);
  });
} else {
  main().catch((err) => {
    process.stderr.write(`${err && err.stack ? err.stack : err}\n`);
    process.exit(1);
  });
}

function connectionString(port) {
  return `DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=${ACCOUNT_KEY};TableEndpoint=http://127.0.0.1:${port}/devstoreaccount1;`;
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

function waitForTcp(port, timeoutMs) {
  const started = Date.now();
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const socket = net.connect(port, "127.0.0.1");
      socket.once("connect", () => {
        socket.end();
        resolve();
      });
      socket.once("error", () => {
        socket.destroy();
        if (Date.now() - started > timeoutMs) reject(new Error("azurite_timeout"));
        else setTimeout(attempt, 50);
      });
    };
    attempt();
  });
}

function spawnNode(args, env) {
  const child = spawn(process.execPath, args, { env, windowsHide: true });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (chunk) => { stdout += chunk.toString(); });
  child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
  child.collected = () => ({ stdout, stderr });
  return child;
}

function exitCode(child, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error("worker_timeout"));
    }, timeoutMs);
    child.once("exit", (code) => {
      clearTimeout(timer);
      resolve(code);
    });
  });
}

function baseEnv(conn, table, extra = {}) {
  return {
    PATH: process.env.PATH,
    SystemRoot: process.env.SystemRoot,
    TEMP: process.env.TEMP,
    TMP: process.env.TMP,
    STRLIX_SERVER_NO_LISTEN: "1",
    STRLIX_USER_SESSION_AUTH: "1",
    STRLIX_BACKEND_APP_SECRET: "hosted-table-secret",
    WEBSITE_HOSTNAME: "unit-test.example",
    STRLIX_FIREBASE_PROJECT_ID: PROJECT,
    STRLIX_FIREBASE_CLIENT_EMAIL: "table-test@strlix-table-test.iam.gserviceaccount.com",
    STRLIX_FIREBASE_PRIVATE_KEY: DUMMY_KEY,
    STRLIX_AUTH_TABLE_CONNECTION: conn,
    STRLIX_AUTH_TABLE_NAME: table,
    STRLIX_USER_ROUTE_CAPACITY: "8",
    STRLIX_USER_ROUTE_PER_HOUR: "40",
    STRLIX_DAILY_PAID_CALL_CAP: "20",
    ...extra,
  };
}

function storeOf(conn, table) {
  return createTableAuthState(conn, table);
}

async function worker(mode) {
  const store = storeOf(process.env.STRLIX_AUTH_TABLE_CONNECTION, process.env.STRLIX_AUTH_TABLE_NAME);
  if (mode === "take") {
    const result = await store.takeUser(process.argv[4], "/classify", { capacity: 1, perHour: 1 }, Number(process.argv[5]), null);
    process.exit(result.ok ? 0 : 3);
  }
  if (mode === "charge") {
    const result = await store.chargeGlobal(Number(process.argv[4]), Number(process.argv[5]), process.argv[6]);
    process.stdout.write(`${result.ok ? "ok" : "no"}\n`, () => process.exit(0));
    return;
  }
  if (mode === "revoke") {
    await store.revoke("revoke-user", 500);
    process.exit(0);
  }
  if (mode === "revoked?") {
    const revoked = await store.isRevoked("revoke-user", 100);
    process.stdout.write(revoked ? "revoked\n" : "open\n", () => process.exit(0));
    return;
  }
  if (mode === "boot") {
    const Module = require("module");
    const resolved = require.resolve("dotenv");
    const mod = new Module(resolved);
    mod.filename = resolved;
    mod.loaded = true;
    mod.exports = { config: () => ({ parsed: {} }) };
    require.cache[resolved] = mod;
    const { sessionAuthReady } = require("./server");
    await sessionAuthReady;
    process.stdout.write("HOSTED_BOOT_OK\n", () => process.exit(0));
    return;
  }
  if (mode === "http") {
    await startHttp();
    return;
  }
  throw new Error("unknown_worker");
}

async function startHttp() {
  const Module = require("module");
  function stub(request, exportsObj) {
    const resolved = require.resolve(request);
    const mod = new Module(resolved);
    mod.filename = resolved;
    mod.loaded = true;
    mod.exports = exportsObj;
    require.cache[resolved] = mod;
  }
  stub("dotenv", { config: () => ({ parsed: {} }) });
  stub("openai", class FakeOpenAI {});
  const now = Math.floor(Date.now() / 1000);
  const admin = {
    apps: [],
    getApps() { return this.apps; },
    cert() { return { kind: "cert" }; },
    applicationDefault() { return { kind: "adc" }; },
    initializeApp(opts) {
      this.apps.push({ projectId: opts.projectId });
      return { projectId: opts.projectId };
    },
    auth() {
      return {
        async verifyIdToken(token, checkRevoked) {
          if (checkRevoked !== true) throw new Error("missing revocation check");
          if (token === CANARY_TOKEN || token === "token-user-b") {
            return {
              sub: token === CANARY_TOKEN ? "user-a" : "user-b",
              aud: PROJECT,
              iat: now,
              exp: now + 600,
              auth_time: now,
            };
          }
          if (token === "token-expired") {
            const err = new Error("expired");
            err.code = "auth/id-token-expired";
            throw err;
          }
          if (token === "token-revoked") {
            const err = new Error("revoked");
            err.code = "auth/id-token-revoked";
            throw err;
          }
          if (token === "token-aud") {
            const err = new Error('incorrect "aud"');
            err.code = "auth/argument-error";
            throw err;
          }
          const err = new Error("bad token");
          err.code = "auth/argument-error";
          throw err;
        },
      };
    },
  };
  stub("firebase-admin", admin);
  const callsFile = process.env.CALLS_FILE;
  function bump(name) {
    const current = fs.existsSync(callsFile) ? JSON.parse(fs.readFileSync(callsFile, "utf8")) : {};
    current[name] = (current[name] || 0) + 1;
    fs.writeFileSync(callsFile, JSON.stringify(current));
  }
  const classifier = require("./classifierService");
  classifier.getClassifierProvider = () => ({ kind: "test", deployment: "test" });
  classifier.classifyActivity = async () => {
    bump("classify");
    if (process.env.FAIL_FLAG && fs.existsSync(process.env.FAIL_FLAG)) {
      const err = new Error("upstream");
      err.statusCode = 500;
      throw err;
    }
    return { decision: "ALLOW" };
  };
  const compiler = require("./promiseCompilerService");
  compiler.getPromiseCompilerProvider = () => ({ kind: "test", deployment: "test" });
  compiler.compilePromise = async () => {
    bump("compile");
    return { ok: true };
  };
  const speech = require("./speechTranscriptionService");
  speech.willCallPaidSpeechProvider = () => false;
  speech.transcribePromise = async (body) => {
    if (!body || typeof body.audioBase64 !== "string") {
      const err = new Error("audio required");
      err.statusCode = 400;
      throw err;
    }
    bump("transcribe");
    return { provider: "test", transcript: "study", language: "en", confidence: 1, latencyMs: 1 };
  };
  const dossier = require("./appDossierService");
  dossier.researchApp = async () => {
    bump("research");
    return { ok: true };
  };
  const mail = require("./mailAssistService");
  mail.summarizeMail = async () => {
    bump("summarize");
    return { summary: { containsInstructionsToAssistant: false, text: "Noted." }, deployment: "test" };
  };
  mail.draftMailReply = async () => {
    bump("draftMail");
    return { draft: { text: "Thanks." }, deployment: "test" };
  };
  const { app, sessionAuthReady } = require("./server");
  await sessionAuthReady;
  const server = app.listen(0, "127.0.0.1", () => {
    process.stdout.write(`PORT=${server.address().port}\n`);
  });
  process.on("SIGTERM", () => server.close());
}

function readCalls(file) {
  if (!fs.existsSync(file)) return {};
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

async function post(base, route, { token, raw, headers } = {}) {
  const response = await fetch(`${base}${route}`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(headers || {}),
    },
    body: raw == null ? "{}" : raw,
  });
  const text = await response.text();
  let json = null;
  try { json = JSON.parse(text); } catch (_) { json = null; }
  return { status: response.status, json, text };
}

async function listenChild(env) {
  const child = spawnNode([__filename, "worker", "http"], env);
  const port = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`http_timeout ${child.collected().stderr}`)), 20000);
    child.stdout.on("data", () => {
      const match = child.collected().stdout.match(/PORT=(\d+)/);
      if (!match) return;
      clearTimeout(timer);
      const found = Number(match[1]);
      if (found === 8787) reject(new Error("bound_8787"));
      else resolve(found);
    });
    child.once("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`http_exit_${code} ${child.collected().stderr}`));
    });
  });
  return { child, base: `http://127.0.0.1:${port}` };
}

async function main() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "strlix-table-"));
  const table = `strlix${process.pid}`.slice(0, 40);
  const callsA = path.join(dir, "calls-a.json");
  const callsB = path.join(dir, "calls-b.json");
  const failFlag = path.join(dir, "fail.flag");
  const logs = [];
  const children = [];
  let azurite = null;
  try {
    const dead = spawnNode([__filename, "worker", "boot"], baseEnv(connectionString(9), table));
    children.push(dead);
    const deadCode = await exitCode(dead, 15000);
    logs.push(dead.collected().stderr);
    assert.notStrictEqual(deadCode, 0);
    assert.match(dead.collected().stderr, /Shared auth state is unavailable/);
    assert.strictEqual(dead.collected().stderr.includes(ACCOUNT_KEY), false);
    assert.strictEqual(dead.collected().stderr.includes(DUMMY_KEY), false);

    const tablePort = await freePort();
    const azuriteBin = path.join(__dirname, "node_modules", "azurite", "dist", "src", "table", "main.js");
    azurite = spawn(process.execPath, [
      azuriteBin,
      "--silent",
      "--disableTelemetry",
      "--location",
      dir,
      "--tablePort",
      String(tablePort),
      "--skipApiVersionCheck",
    ], { windowsHide: true, stdio: "ignore" });
    await waitForTcp(tablePort, 20000);
    const conn = connectionString(tablePort);

    const boot = spawnNode([__filename, "worker", "boot"], baseEnv(conn, table));
    children.push(boot);
    const bootCode = await exitCode(boot, 20000);
    logs.push(boot.collected().stdout, boot.collected().stderr);
    assert.strictEqual(bootCode, 0, boot.collected().stderr);
    assert.match(boot.collected().stdout, /HOSTED_BOOT_OK/);

    const now = Date.parse("2026-10-22T12:00:00Z");
    const race = await Promise.all([0, 1].map(() => {
      const child = spawnNode([__filename, "worker", "take", "race-user", String(now)], baseEnv(conn, table));
      children.push(child);
      return exitCode(child, 15000);
    }));
    assert.deepStrictEqual(race.slice().sort(), [0, 3]);

    const again = spawnNode([__filename, "worker", "take", "race-user", String(now)], baseEnv(conn, table));
    children.push(again);
    assert.strictEqual(await exitCode(again, 15000), 3);

    const revoker = spawnNode([__filename, "worker", "revoke"], baseEnv(conn, table));
    children.push(revoker);
    assert.strictEqual(await exitCode(revoker, 15000), 0);
    const checker = spawnNode([__filename, "worker", "revoked?"], baseEnv(conn, table));
    children.push(checker);
    assert.strictEqual(await exitCode(checker, 15000), 0);
    assert.match(checker.collected().stdout, /revoked/);

    const store = storeOf(conn, table);
    await store.reset();
    const { TableClient } = require("@azure/data-tables");
    const direct = TableClient.fromConnectionString(conn, table, tableClientOptions(conn));
    await direct.upsertEntity({ partitionKey: "g.day", rowKey: "count", day: "2026-10-21", count: 5 }, "Replace");
    const midnight = Date.parse("2026-10-22T00:30:00Z");
    const chargers = await Promise.all(["midnight-01", "midnight-02"].map(async (id) => {
      const child = spawnNode([__filename, "worker", "charge", String(midnight), "5", id], baseEnv(conn, table));
      children.push(child);
      await exitCode(child, 15000);
      return child.collected().stdout.trim();
    }));
    assert.deepStrictEqual(chargers.slice().sort(), ["ok", "ok"]);
    const rolled = await store.readGlobalCount();
    assert.strictEqual(rolled.day, "2026-10-22");
    assert.strictEqual(rolled.count, 2);

    await direct.upsertEntity({
      partitionKey: `b.${Buffer.from("corrupt-user", "utf8").toString("base64url")}`,
      rowKey: Buffer.from("/classify", "utf8").toString("base64url"),
      tokens: "nope",
      at: now,
    }, "Replace");
    await assert.rejects(
      () => store.takeUser("corrupt-user", "/classify", { capacity: 1, perHour: 1 }, now, null),
      /auth_state_corrupt/
    );
    const corrupt = await direct.getEntity(
      `b.${Buffer.from("corrupt-user", "utf8").toString("base64url")}`,
      Buffer.from("/classify", "utf8").toString("base64url")
    );
    assert.strictEqual(corrupt.tokens, "nope");

    await store.reset();
    const httpEnv = {
      STRLIX_MAIL_ASSIST_ENABLED: "1",
      STRLIX_GOOGLE_WEB_CLIENT_IDS: "table-test.apps.googleusercontent.com",
      STRLIX_DAILY_PAID_CALL_CAP: "2",
      STRLIX_USER_ROUTE_CAPACITY: "1",
      STRLIX_USER_ROUTE_PER_HOUR: "1",
    };
    const serverA = await listenChild(baseEnv(conn, table, { ...httpEnv, CALLS_FILE: callsA, FAIL_FLAG: failFlag }));
    const serverB = await listenChild(baseEnv(conn, table, { ...httpEnv, CALLS_FILE: callsB, FAIL_FLAG: failFlag }));
    children.push(serverA.child, serverB.child);

    const health = await fetch(`${serverA.base}/health`, { headers: { authorization: "Bearer not-a-token" } });
    const healthBody = await health.json();
    assert.strictEqual(health.status, 200);
    assert.strictEqual(healthBody.userSessionAuth, true);
    for (const route of PAID) {
      const missing = await post(serverA.base, route, { raw: "{" });
      assert.strictEqual(missing.status, 401, `${route} ${missing.text}`);
      assert.strictEqual(missing.json.error, "user_auth_required", route);
    }
    const mailOff = await listenChild(baseEnv(conn, table, { CALLS_FILE: path.join(dir, "calls-mail-off.json") }));
    children.push(mailOff.child);
    const mailClosed = await post(mailOff.base, "/mail/summarize", { token: CANARY_TOKEN, raw: "{" });
    assert.strictEqual(mailClosed.status, 404, mailClosed.text);
    for (const route of ["/mail/summarize", "/mail/draft-reply"]) {
      const denied = await post(serverA.base, route, { token: CANARY_TOKEN, raw: "{" });
      assert.strictEqual(denied.status, 401, denied.text);
      assert.strictEqual(denied.json.error, "user_auth_required");
    }
    const secretOnly = await post(serverA.base, "/classify", {
      raw: "{",
      headers: { "x-strlix-app-secret": "hosted-table-secret" },
    });
    assert.strictEqual(secretOnly.status, 401);
    assert.strictEqual(secretOnly.json.error, "user_auth_required");
    for (const token of ["token-expired", "token-revoked", "token-aud", "not-a-jwt"]) {
      const bad = await post(serverA.base, "/compile-promise", { token, raw: "{" });
      assert.strictEqual(bad.status, 401, token);
      assert.notStrictEqual(bad.json.error, undefined);
    }
    const authedEmpty = await post(serverA.base, "/research-app", { token: CANARY_TOKEN, raw: "{}" });
    assert.strictEqual(authedEmpty.status, 400, authedEmpty.text);
    assert.deepStrictEqual(readCalls(callsA), {});
    assert.deepStrictEqual(readCalls(callsB), {});
    const transcribe = await post(serverA.base, "/transcribe-promise", { token: CANARY_TOKEN, raw: "{}" });
    assert.strictEqual(transcribe.status, 400, transcribe.text);
    const draft = await post(serverA.base, "/draft-focus-promise", {
      token: CANARY_TOKEN,
      raw: JSON.stringify({ promise: "study algorithms for 25 minutes" }),
    });
    assert.strictEqual(draft.status, 200, draft.text);
    assert.deepStrictEqual(readCalls(callsA), {});
    assert.deepStrictEqual(readCalls(callsB), {});

    const unknown = await post(serverA.base, "/not-a-route", { token: CANARY_TOKEN, raw: "{" });
    assert.strictEqual(unknown.status, 401);
    assert.strictEqual(unknown.json.error, "unauthorized");

    await store.reset();
    const first = await post(serverA.base, "/classify", {
      token: CANARY_TOKEN,
      raw: JSON.stringify({ packageName: "com.example.app", screenText: "notes", goal: "study" }),
      headers: { "x-strlix-request-id": "retry-key-01" },
    });
    assert.strictEqual(first.status, 200, first.text);
    let duplicate = null;
    for (let i = 0; i < 40; i += 1) {
      duplicate = await post(serverB.base, "/classify", {
        token: CANARY_TOKEN,
        raw: JSON.stringify({ packageName: "com.example.app", screenText: "notes", goal: "study" }),
        headers: { "x-strlix-request-id": "retry-key-01" },
      });
      if (duplicate.status !== 429) break;
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    assert.strictEqual(duplicate.status, 409, duplicate.text);
    assert.strictEqual(duplicate.json.error, "duplicate_request");
    assert.strictEqual(readCalls(callsA).classify, 1);
    assert.strictEqual(readCalls(callsB).classify, undefined);

    const limited = await post(serverB.base, "/classify", {
      token: CANARY_TOKEN,
      raw: JSON.stringify({ packageName: "com.example.app", screenText: "notes", goal: "study" }),
      headers: { "x-strlix-request-id": "other-key-02" },
    });
    assert.strictEqual(limited.status, 429, limited.text);
    const otherRoute = await post(serverB.base, "/compile-promise", {
      token: CANARY_TOKEN,
      raw: JSON.stringify({ promise: "study algorithms for 25 minutes" }),
    });
    assert.strictEqual(otherRoute.status, 200, otherRoute.text);
    const globalStop = await post(serverA.base, "/compile-promise", {
      token: "token-user-b",
      raw: JSON.stringify({ promise: "read notes for 20 minutes" }),
    });
    assert.strictEqual(globalStop.status, 503, globalStop.text);
    assert.strictEqual(globalStop.json.error, "daily_capacity_reached");
    assert.strictEqual(readCalls(callsB).compile, 1);
    assert.strictEqual(readCalls(callsA).compile, undefined);

    fs.writeFileSync(failFlag, "1");
    await store.reset();
    const failed = await post(serverA.base, "/classify", {
      token: CANARY_TOKEN,
      raw: JSON.stringify({ packageName: "com.example.app", screenText: "notes", goal: "study" }),
      headers: { "x-strlix-request-id": "fail-key-01" },
    });
    assert.strictEqual(failed.status, 500, failed.text);
    let failedRetry = null;
    for (let i = 0; i < 40; i += 1) {
      failedRetry = await post(serverB.base, "/classify", {
        token: CANARY_TOKEN,
        raw: JSON.stringify({ packageName: "com.example.app", screenText: "notes", goal: "study" }),
        headers: { "x-strlix-request-id": "fail-key-01" },
      });
      if (failedRetry.status !== 429) break;
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    assert.strictEqual(failedRetry.status, 503, failedRetry.text);
    assert.strictEqual(failedRetry.json.error, "paid_attempt_already_failed");
    assert.strictEqual(readCalls(callsA).classify, 2);
    assert.strictEqual(readCalls(callsB).classify, undefined);

    azurite.kill();
    azurite = null;
    const down = await post(serverA.base, "/classify", {
      token: "token-user-b",
      raw: JSON.stringify({ packageName: "com.example.app", screenText: "notes", goal: "study" }),
      headers: { "x-strlix-request-id": "after-storage-down" },
    });
    assert.strictEqual(down.status, 503, down.text);
    assert.strictEqual(down.json.error, "user_auth_unavailable");

    const off = spawnNode([__filename, "worker", "http"], {
      PATH: process.env.PATH,
      SystemRoot: process.env.SystemRoot,
      TEMP: process.env.TEMP,
      TMP: process.env.TMP,
      STRLIX_SERVER_NO_LISTEN: "1",
      STRLIX_BACKEND_APP_SECRET: "feature-off-secret",
      CALLS_FILE: path.join(dir, "calls-off.json"),
    });
    children.push(off);
    const offPort = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(off.collected().stderr)), 15000);
      off.stdout.on("data", () => {
        const match = off.collected().stdout.match(/PORT=(\d+)/);
        if (!match) return;
        clearTimeout(timer);
        resolve(Number(match[1]));
      });
    });
    const offHealth = await fetch(`http://127.0.0.1:${offPort}/health`).then((res) => res.json());
    assert.strictEqual(offHealth.userSessionAuth, false);
    const offMissing = await post(`http://127.0.0.1:${offPort}`, "/classify", { raw: "{" });
    assert.strictEqual(offMissing.status, 401);
    const offParsed = await post(`http://127.0.0.1:${offPort}`, "/classify", {
      raw: "{}",
      headers: { "x-strlix-app-secret": "feature-off-secret" },
    });
    assert.strictEqual(offParsed.status, 400);

    const haystack = [
      ...logs,
      ...children.map((child) => `${child.collected().stdout}\n${child.collected().stderr}`),
      JSON.stringify(healthBody),
      first.text,
      duplicate.text,
      down.text,
    ].join("\n");
    assert.strictEqual(haystack.includes(ACCOUNT_KEY), false);
    assert.strictEqual(haystack.includes(CANARY_TOKEN), false);
    assert.strictEqual(haystack.includes(DUMMY_KEY), false);
    process.stdout.write("TABLE_AUTH_STATE_OK\n");
  } finally {
    for (const child of children) {
      if (!child.killed) child.kill();
    }
    if (azurite && !azurite.killed) azurite.kill();
    fs.rmSync(dir, { recursive: true, force: true });
  }
}
