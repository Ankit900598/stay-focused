/**
 * Offline smoke: the Android contract is `Authorization: Bearer <Firebase ID token>`.
 * Proves session auth reads ONLY that header for identity: not X-Strlix-App-Secret, not
 * X-Strlix-Google-Id-Token, not a body uid. No network, no .env, no Firebase project id.
 * Run: node backend/smoke_bearer_contract.js
 */
const assert = require("assert");
const { bearerToken, createSessionGate } = require("./userSessionAuth");

function fakeReq(headers, body) {
  const lower = {};
  for (const [k, v] of Object.entries(headers)) lower[k.toLowerCase()] = v;
  return { path: "/compile-promise", body: body || {}, get: (name) => lower[String(name).toLowerCase()] };
}

function fakeRes() {
  return {
    statusCode: 200,
    body: null,
    status(code) { this.statusCode = code; return this; },
    json(payload) { this.body = payload; return this; },
    set() { return this; },
    on() {},
  };
}

(async () => {
  assert.strictEqual(bearerToken(fakeReq({ Authorization: "Bearer abc.def.ghi" })), "abc.def.ghi");
  assert.strictEqual(bearerToken(fakeReq({ authorization: "bearer abc.def.ghi" })), "abc.def.ghi");
  assert.strictEqual(bearerToken(fakeReq({ Authorization: "Basic abc" })), null);
  assert.strictEqual(bearerToken(fakeReq({ "X-Strlix-App-Secret": "s3cret" })), null);
  assert.strictEqual(bearerToken(fakeReq({ "X-Strlix-Google-Id-Token": "g.t.k" })), null);

  const seen = [];
  const gate = createSessionGate({
    verify: async (token) => {
      seen.push(token);
      return { uid: "verified-uid", authTimeSec: 1 };
    },
  });

  // Valid Bearer: verifier gets exactly the Bearer token; uid comes from the verifier.
  let req = fakeReq(
    { Authorization: "Bearer id.token.value", "X-Strlix-App-Secret": "s3cret" },
    { uid: "forged-body-uid" }
  );
  let res = fakeRes();
  let nextCalled = false;
  await gate.requireUser(req, res, () => { nextCalled = true; });
  assert.strictEqual(nextCalled, true);
  assert.deepStrictEqual(seen, ["id.token.value"]);
  assert.strictEqual(req.strlixUser.uid, "verified-uid");

  // Shared secret / Google mail token alone is not an identity: 401, verifier untouched.
  for (const headers of [
    { "X-Strlix-App-Secret": "s3cret" },
    { "X-Strlix-Google-Id-Token": "g.t.k" },
    { Authorization: "Basic dXNlcjpwYXNz" },
    {},
  ]) {
    seen.length = 0;
    req = fakeReq(headers, { uid: "forged-body-uid" });
    res = fakeRes();
    nextCalled = false;
    await gate.requireUser(req, res, () => { nextCalled = true; });
    assert.strictEqual(nextCalled, false);
    assert.strictEqual(res.statusCode, 401);
    assert.deepStrictEqual(res.body, { error: "user_auth_required" });
    assert.strictEqual(seen.length, 0);
    assert.strictEqual(req.strlixUser, undefined);
  }

  console.log("smoke_bearer_contract: ok");
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
