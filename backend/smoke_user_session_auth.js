/**
 * Offline smoke for backend/userSessionAuth.js.
 * Run: node backend/smoke_user_session_auth.js
 * Does not load server.js, listen, or call a provider.
 */
const assert = require("assert");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync } = require("child_process");
const { ROUTE_LIMITS } = require("./rateLimit");
const { createFileAuthState, createMemoryAuthState, setAuthStateBucketCapForTests } = require("./authStateStore");
const {
  ROUTE_MIGRATION,
  SESSION_AUTH_ENV,
  CLOCK_SKEW_SEC,
  createRevocationList,
  createTokenVerifier,
  createSessionGate,
  verifySessionJwt,
  verifyWithFirebaseAdmin,
} = require("./userSessionAuth");

const AUDIENCE = "strlix-test-audience";
const ISSUER = "https://securetoken.google.com/strlix-test-audience";
const NOW_MS = Date.parse("2026-10-22T10:00:00Z");

function keyPair(kid) {
  const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const jwk = publicKey.export({ format: "jwk" });
  jwk.kid = kid;
  jwk.alg = "RS256";
  jwk.use = "sig";
  return { jwk, privateKey };
}

function sign(payload, privateKey, kid, alg = "RS256") {
  const header = Buffer.from(JSON.stringify({ alg, kid, typ: "JWT" })).toString("base64url");
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const data = `${header}.${body}`;
  const sig = crypto.sign("RSA-SHA256", Buffer.from(data), privateKey).toString("base64url");
  return `${data}.${sig}`;
}

function claims(overrides = {}) {
  const nowSec = Math.floor(NOW_MS / 1000);
  return {
    iss: ISSUER,
    aud: AUDIENCE,
    sub: "user-a",
    iat: nowSec,
    exp: nowSec + 3600,
    auth_time: nowSec,
    ...overrides,
  };
}

function fakeRes() {
  const headers = {};
  return {
    statusCode: 200,
    body: null,
    headers,
    status(code) {
      this.statusCode = code;
      return this;
    },
    json(obj) {
      this.body = obj;
      return this;
    },
    set(name, value) {
      headers[name] = value;
    },
  };
}

function request(token, routePath = "/classify") {
  return {
    method: "POST",
    path: routePath,
    get(name) {
      if (String(name).toLowerCase() === "authorization" && token) return `Bearer ${token}`;
      if (String(name).toLowerCase() === "x-strlix-app-secret") return "reusable-apk-secret";
      return "";
    },
  };
}

const trusted = keyPair("trusted");
const forger = keyPair("trusted");
const revocation = createRevocationList();
const verify = createTokenVerifier({
  keys: [trusted.jwk],
  audience: AUDIENCE,
  issuer: ISSUER,
  revocation,
});
const gate = createSessionGate({
  verify,
  limits: { capacity: 2, perHour: 2 },
  nowMs: () => NOW_MS,
});

(async () => {
  const valid = sign(claims(), trusted.privateKey, "trusted");
  const validUser = await verify(valid, NOW_MS);
  assert.strictEqual(validUser.uid, "user-a");
  assert.strictEqual(validUser.audience, AUDIENCE);

  const missing = fakeRes();
  let nextCalled = false;
  await gate.requireUser(request(null), missing, () => {
    nextCalled = true;
  });
  assert.strictEqual(missing.statusCode, 401);
  assert.strictEqual(missing.body.error, "user_auth_required");
  assert.strictEqual(nextCalled, false);

  const secretOnly = fakeRes();
  nextCalled = false;
  await gate.requireUser(request(null), secretOnly, () => {
    nextCalled = true;
  });
  assert.strictEqual(secretOnly.body.error, "user_auth_required");
  assert.strictEqual(nextCalled, false, "shared APK secret is not a user");

  const expiredRes = fakeRes();
  const expired = sign(claims({ exp: Math.floor(NOW_MS / 1000) - 120 }), trusted.privateKey, "trusted");
  nextCalled = false;
  await gate.requireUser(request(expired), expiredRes, () => {
    nextCalled = true;
  });
  assert.strictEqual(expiredRes.statusCode, 401);
  assert.strictEqual(expiredRes.body.error, "user_auth_expired");
  assert.strictEqual(nextCalled, false);

  const forgedRes = fakeRes();
  const forged = sign(claims(), forger.privateKey, "trusted");
  nextCalled = false;
  await gate.requireUser(request(forged), forgedRes, () => {
    nextCalled = true;
  });
  assert.strictEqual(forgedRes.statusCode, 401);
  assert.strictEqual(forgedRes.body.error, "user_auth_invalid");
  assert.strictEqual(nextCalled, false);

  const audienceRes = fakeRes();
  const wrongAudience = sign(claims({ aud: "other-project" }), trusted.privateKey, "trusted");
  nextCalled = false;
  await gate.requireUser(request(wrongAudience), audienceRes, () => {
    nextCalled = true;
  });
  assert.strictEqual(audienceRes.statusCode, 401);
  assert.strictEqual(audienceRes.body.error, "user_auth_wrong_audience");
  assert.strictEqual(nextCalled, false);

  const nowSec = Math.floor(NOW_MS / 1000);
  async function expectCode(token, code) {
    const res = fakeRes();
    let called = false;
    await gate.requireUser(request(token), res, () => {
      called = true;
    });
    assert.strictEqual(called, false, code);
    assert.strictEqual(res.statusCode, 401, code);
    assert.strictEqual(res.body.error, code);
    assert.strictEqual(JSON.stringify(res.body).includes(token), false);
  }

  const missingIatClaims = claims();
  delete missingIatClaims.iat;
  await expectCode(sign(missingIatClaims, trusted.privateKey, "trusted"), "user_auth_invalid");

  const missingAuthTime = claims();
  delete missingAuthTime.auth_time;
  await expectCode(sign(missingAuthTime, trusted.privateKey, "trusted"), "user_auth_invalid");
  await expectCode(sign(claims({ auth_time: nowSec + CLOCK_SKEW_SEC + 1 }), trusted.privateKey, "trusted"), "user_auth_invalid");
  await expectCode(sign(claims({ iat: nowSec + CLOCK_SKEW_SEC + 1 }), trusted.privateKey, "trusted"), "user_auth_invalid");
  await expectCode(sign(claims({ sub: "" }), trusted.privateKey, "trusted"), "user_auth_invalid");
  await expectCode(sign(claims({ sub: "u".repeat(129) }), trusted.privateKey, "trusted"), "user_auth_invalid");
  await expectCode(sign(claims({ iss: "https://securetoken.google.com/other-project" }), trusted.privateKey, "trusted"), "user_auth_invalid");
  await expectCode(sign(claims(), trusted.privateKey, "trusted", "none"), "user_auth_invalid");
  await expectCode(sign(claims(), trusted.privateKey, "trusted", "HS256"), "user_auth_invalid");
  await expectCode(sign(claims(), trusted.privateKey, "unknown-kid"), "user_auth_invalid");

  const edge = await verify(sign(claims({
    iat: nowSec + CLOCK_SKEW_SEC,
    auth_time: nowSec + CLOCK_SKEW_SEC,
    exp: nowSec - CLOCK_SKEW_SEC,
  }), trusted.privateKey, "trusted"), NOW_MS);
  assert.strictEqual(edge.uid, "user-a");
  await expectCode(sign(claims({ exp: nowSec - CLOCK_SKEW_SEC - 1 }), trusted.privateKey, "trusted"), "user_auth_expired");

  const rotated = keyPair("rotated");
  let published = [trusted.jwk];
  let refreshes = 0;
  const rotatingVerify = createTokenVerifier({
    keys: () => published.slice(),
    resolveKeys: async (kid) => {
      refreshes += 1;
      if (kid === "rotated") published = [trusted.jwk, rotated.jwk];
      return published.slice();
    },
    audience: AUDIENCE,
    issuer: ISSUER,
  });
  refreshes = 0;
  const rotatedUser = await rotatingVerify(sign(claims(), rotated.privateKey, "rotated"), NOW_MS);
  assert.strictEqual(rotatedUser.uid, "user-a");
  assert.strictEqual(refreshes, 1, "unknown kid refreshes keys once");
  refreshes = 0;
  await rotatingVerify(sign(claims(), trusted.privateKey, "trusted"), NOW_MS);
  assert.strictEqual(refreshes, 0, "known kid does not refresh");
  await assert.rejects(
    () => rotatingVerify(sign(claims(), rotated.privateKey, "still-unknown"), NOW_MS),
    (err) => err.code === "user_auth_invalid"
  );

  let adminChecks = 0;
  const admin = {
    apps: [],
    credential: { applicationDefault: () => ({ kind: "adc" }) },
    initializeApp(opts) {
      this.apps.push(opts);
    },
    auth() {
      return {
        verifyIdToken: async (token, checkRevoked) => {
          adminChecks += 1;
          assert.strictEqual(checkRevoked, true);
          if (token === "revoked") {
            const err = new Error("revoked");
            err.code = "auth/id-token-revoked";
            throw err;
          }
          if (token === "expired") {
            const err = new Error("expired");
            err.code = "auth/id-token-expired";
            throw err;
          }
          if (token === "aud") {
            const err = new Error("incorrect aud");
            err.code = "auth/argument-error";
            throw err;
          }
          if (token === "leak-token-value") throw new Error(`bad ${token}`);
          const now = Math.floor(Date.now() / 1000);
          return { sub: "uid-admin", aud: "demo-project", auth_time: now, iat: now, exp: now + 600 };
        },
      };
    },
  };
  const adminUser = await verifyWithFirebaseAdmin("good", admin, "demo-project");
  assert.strictEqual(adminUser.uid, "uid-admin");
  assert.strictEqual(admin.apps[0].projectId, "demo-project");
  assert.strictEqual(adminChecks, 1);
  await assert.rejects(() => verifyWithFirebaseAdmin("revoked", admin, "demo-project"), (err) => err.code === "user_auth_revoked");
  await assert.rejects(() => verifyWithFirebaseAdmin("expired", admin, "demo-project"), (err) => err.code === "user_auth_expired");
  await assert.rejects(() => verifyWithFirebaseAdmin("aud", admin, "demo-project"), (err) => err.code === "user_auth_wrong_audience");
  await assert.rejects(
    () => verifyWithFirebaseAdmin("leak-token-value", admin, "demo-project"),
    (err) => err.code === "user_auth_invalid" && err.message === "user_auth_invalid"
  );

  revocation.revoke("user-a", Math.floor(NOW_MS / 1000) + 1);
  const revokedRes = fakeRes();
  nextCalled = false;
  await gate.requireUser(request(valid), revokedRes, () => {
    nextCalled = true;
  });
  assert.strictEqual(revokedRes.statusCode, 401);
  assert.strictEqual(revokedRes.body.error, "user_auth_revoked");
  assert.strictEqual(nextCalled, false);
  revocation.revoke("user-a", Math.floor(NOW_MS / 1000));

  const paidReq = request(valid, "/compile-promise");
  await gate.requireUser(paidReq, fakeRes(), () => {});
  let allowed = 0;
  for (let i = 0; i < 2; i += 1) {
    const res = fakeRes();
    gate.limitPaidUser(paidReq, res, () => {
      allowed += 1;
    });
    assert.strictEqual(res.statusCode, 200);
  }
  const limited = fakeRes();
  nextCalled = false;
  gate.limitPaidUser(paidReq, limited, () => {
    nextCalled = true;
  });
  assert.strictEqual(limited.statusCode, 429);
  assert.strictEqual(limited.body.error, "rate_limited");
  assert.ok(Number(limited.headers["Retry-After"]) > 0);
  assert.strictEqual(nextCalled, false);
  assert.strictEqual(allowed, 2);

  const other = request(sign(claims({ sub: "user-b" }), trusted.privateKey, "trusted"), "/compile-promise");
  await gate.requireUser(other, fakeRes(), () => {});
  const otherRes = fakeRes();
  nextCalled = false;
  gate.limitPaidUser(other, otherRes, () => {
    nextCalled = true;
  });
  assert.strictEqual(nextCalled, true, "a second user does not share the first user's bucket");

  const paidPaths = ROUTE_MIGRATION.filter((route) => route.paid).map((route) => route.path);
  for (const configured of Object.keys(ROUTE_LIMITS)) {
    assert.ok(paidPaths.includes(configured), `${configured} needs a user-session migration`);
  }
  assert.strictEqual(ROUTE_MIGRATION.find((route) => route.path === "/health").nextAuth, "public");
  for (const route of ROUTE_MIGRATION.filter((item) => item.paid)) {
    assert.notStrictEqual(route.nextAuth, "public");
    assert.notStrictEqual(route.nextAuth, "shared-app-secret");
  }
  assert.strictEqual(SESSION_AUTH_ENV, "STRLIX_USER_SESSION_AUTH");
  const serverSrc = fs.readFileSync(path.join(__dirname, "server.js"), "utf8");
  const flagAt = serverSrc.indexOf('process.env.STRLIX_USER_SESSION_AUTH === "1"');
  const offAt = serverSrc.indexOf("if (!sessionAuthEnabled)");
  const legacyAuth = serverSrc.indexOf("app.use(appAuthMiddleware)", offAt);
  const legacyPaid = serverSrc.indexOf("app.use(paidRouteGuard)", offAt);
  const mountedAt = serverSrc.indexOf("mountPaidSessionAuth");
  assert.ok(flagAt > 0 && offAt > flagAt);
  assert.ok(legacyAuth > offAt && legacyAuth < legacyPaid);
  assert.ok(mountedAt > legacyPaid);
  const phoneSrc = fs.readFileSync(path.join(__dirname, "start-phone-test.js"), "utf8");
  assert.ok(phoneSrc.includes("delete process.env.STRLIX_USER_SESSION_AUTH"));
  const routeAuthSrc = fs.readFileSync(path.join(__dirname, "sessionRouteAuth.js"), "utf8");
  assert.ok(routeAuthSrc.includes("if (await revocationCheck(store, user.uid, user.authTimeSec))"));

  const stateDir = fs.mkdtempSync(path.join(os.tmpdir(), "strlix-auth-state-"));
  const stateFile = path.join(stateDir, "state.json");
  const { setAuthStateWriteBarrierForTests } = require("./authStateStore");
  const first = createFileAuthState(stateFile);
  const limit = { capacity: 1, perHour: 1 };
  assert.strictEqual((await first.takeUser("user-a", "/classify", limit, NOW_MS, null)).ok, true);
  assert.strictEqual((await first.takeUser("user-a", "/classify", limit, NOW_MS, null)).ok, false);
  assert.strictEqual((await first.takeUser("user-a", "/compile-promise", limit, NOW_MS, null)).ok, true);
  assert.strictEqual((await first.takeUser("user-b", "/classify", limit, NOW_MS, null)).ok, true);
  assert.strictEqual((await first.chargeGlobal(NOW_MS, 1, null)).ok, true);
  assert.strictEqual((await first.chargeGlobal(NOW_MS, 1, null)).ok, false);
  assert.strictEqual((await first.chargeGlobal(NOW_MS, 5, "replay-id-1")).ok, true);
  assert.strictEqual((await first.chargeGlobal(NOW_MS, 5, "replay-id-1")).replay, true);
  await first.revoke("user-a", nowSec + 1);
  const second = createFileAuthState(stateFile);
  assert.strictEqual((await second.takeUser("user-a", "/classify", limit, NOW_MS, null)).ok, false);
  assert.strictEqual(await second.isRevoked("user-a", nowSec), true);
  assert.strictEqual(fs.readFileSync(stateFile, "utf8").includes("eyJ"), false);

  const beforeInterrupt = fs.readFileSync(stateFile, "utf8");
  setAuthStateWriteBarrierForTests(() => {
    throw new Error("interrupted before rename");
  });
  await assert.rejects(() => first.takeUser("user-z", "/classify", limit, NOW_MS, null), /interrupted before rename/);
  assert.strictEqual(fs.readFileSync(stateFile, "utf8"), beforeInterrupt);
  setAuthStateWriteBarrierForTests(null);
  assert.strictEqual((await first.takeUser("user-z", "/classify", limit, NOW_MS, null)).ok, true);

  fs.writeFileSync(stateFile, "{not json");
  await assert.rejects(() => first.takeUser("user-a", "/classify", limit, NOW_MS, null), /auth_state_corrupt/);
  assert.strictEqual(fs.readFileSync(stateFile, "utf8"), "{not json");
  fs.writeFileSync(stateFile, beforeInterrupt);

  const staleLock = `${stateFile}.lock`;
  fs.writeFileSync(staleLock, "");
  const staleTime = new Date(Date.now() - 10000);
  fs.utimesSync(staleLock, staleTime, staleTime);
  assert.strictEqual((await first.takeUser("lock-user", "/research-app", limit, NOW_MS, null)).ok, true);

  const held = await fs.promises.open(`${stateFile}.lock`, "wx");
  let timerFired = false;
  const pendingTake = first.takeUser("loop-user", "/draft-focus-promise", limit, NOW_MS, null);
  const winner = await Promise.race([
    pendingTake.then(() => "take"),
    new Promise((resolve) => setTimeout(() => {
      timerFired = true;
      resolve("tick");
    }, 40)),
  ]);
  assert.strictEqual(winner, "tick");
  assert.strictEqual(timerFired, true);
  await held.close();
  await fs.promises.unlink(`${stateFile}.lock`);
  assert.strictEqual((await pendingTake).ok, true);

  const restartScript = path.join(stateDir, "restart.js");
  fs.writeFileSync(restartScript, `
    const { createFileAuthState } = require(${JSON.stringify(path.join(__dirname, "authStateStore.js"))});
    (async () => {
      const store = createFileAuthState(process.argv[2]);
      const limit = { capacity: 1, perHour: 1 };
      const now = ${NOW_MS};
      if ((await store.takeUser("user-a", "/classify", limit, now, null)).ok) process.exit(2);
      if ((await store.chargeGlobal(now, 1, null)).ok) process.exit(3);
      if (!(await store.isRevoked("user-a", ${nowSec}))) process.exit(4);
      if ((await store.takeUser("user-b", "/classify", limit, now, null)).ok) process.exit(5);
    })().catch((err) => {
      console.error(err);
      process.exit(1);
    });
  `);
  const restarted = spawnSync(process.execPath, [restartScript, stateFile], { encoding: "utf8" });
  assert.strictEqual(restarted.status, 0, restarted.stderr);

  const raceFile = path.join(stateDir, "race.json");
  const raceScript = path.join(stateDir, "race.js");
  fs.writeFileSync(raceScript, `
    const { createFileAuthState } = require(${JSON.stringify(path.join(__dirname, "authStateStore.js"))});
    (async () => {
      const store = createFileAuthState(process.argv[2]);
      const result = await store.takeUser("race-user", "/classify", { capacity: 1, perHour: 1 }, ${NOW_MS}, null);
      process.exit(result.ok ? 0 : 3);
    })().catch((err) => {
      console.error(err);
      process.exit(1);
    });
  `);
  const { spawn } = require("child_process");
  const raceCodes = await Promise.all([0, 1].map(() => new Promise((resolve) => {
    const child = spawn(process.execPath, [raceScript, raceFile], { stdio: "ignore" });
    child.on("exit", (code) => resolve(code));
  })));
  assert.deepStrictEqual(raceCodes.slice().sort(), [0, 3]);

  const retryStore = createFileAuthState(path.join(stateDir, "retry.json"));
  assert.strictEqual((await retryStore.takeUser("user-a", "/classify", limit, NOW_MS, "retry-key-1")).ok, true);
  assert.strictEqual((await retryStore.takeUser("user-a", "/classify", limit, NOW_MS, "retry-key-1")).inflight, true);
  await retryStore.finishRequest("user-a", "/classify", "retry-key-1", 200);
  assert.strictEqual((await retryStore.takeUser("user-a", "/classify", limit, NOW_MS, "retry-key-1")).duplicate, true);
  await retryStore.finishRequest("user-a", "/classify", "retry-key-1", 500);
  assert.strictEqual((await retryStore.takeUser("user-a", "/classify", limit, NOW_MS, "retry-key-1")).failedAttempt, true);
  assert.strictEqual((await retryStore.takeUser("user-c", "/classify", limit, NOW_MS, null)).ok, true);

  setAuthStateBucketCapForTests(2);
  const capped = createMemoryAuthState();
  const tight = { capacity: 1, perHour: 1 };
  const t0 = Date.parse("2026-10-22T10:00:00Z");
  assert.strictEqual(capped.takeUser("active-a", "/classify", tight, t0, null).ok, true);
  assert.strictEqual(capped.takeUser("idle-b", "/classify", tight, t0, null).ok, true);
  assert.throws(() => capped.takeUser("new-c", "/classify", tight, t0 + 1000, null), /auth_state_bucket_capacity/);
  assert.strictEqual(capped.takeUser("active-a", "/classify", tight, t0 + 1000, null).ok, false);
  const hourLater = t0 + (60 * 60 * 1000);
  assert.strictEqual(capped.takeUser("active-a", "/classify", tight, hourLater, null).ok, true);
  assert.strictEqual(capped.takeUser("new-c", "/classify", tight, hourLater, null).ok, true);
  setAuthStateBucketCapForTests(10000);

  console.log("USER_SESSION_AUTH_OK");
  process.exit(0);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
