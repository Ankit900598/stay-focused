/**
 * Offline smoke for backend/appAuth.js
 * Run: node backend/smoke_app_auth.js
 */
const assert = require("assert");
const { isPublicHealth, requireAppSecret, appAuthMiddleware } = require("./appAuth");

process.env.STRLIX_BACKEND_APP_SECRET = "test-secret";

function fakeRes() {
  return {
    statusCode: 200,
    body: null,
    status(code) {
      this.statusCode = code;
      return this;
    },
    json(obj) {
      this.body = obj;
      return this;
    },
  };
}

let nextCalled = false;
requireAppSecret(
  { get: () => "" },
  fakeRes(),
  () => {
    nextCalled = true;
  }
);
assert.strictEqual(nextCalled, false, "missing secret must not next");

nextCalled = false;
const denied = fakeRes();
requireAppSecret({ get: () => "wrong" }, denied, () => {
  nextCalled = true;
});
assert.strictEqual(denied.statusCode, 401);
assert.strictEqual(nextCalled, false);

nextCalled = false;
requireAppSecret({ get: (h) => (h === "x-strlix-app-secret" ? "test-secret" : "") }, fakeRes(), () => {
  nextCalled = true;
});
assert.strictEqual(nextCalled, true);

assert.strictEqual(isPublicHealth({ method: "GET", path: "/health" }), true);
assert.strictEqual(isPublicHealth({ method: "POST", path: "/compile-promise" }), false);

nextCalled = false;
appAuthMiddleware({ method: "GET", path: "/health", get: () => "" }, fakeRes(), () => {
  nextCalled = true;
});
assert.strictEqual(nextCalled, true, "health stays public");

nextCalled = false;
appAuthMiddleware({ method: "POST", path: "/mail/summarize", get: () => "" }, fakeRes(), () => {
  nextCalled = true;
});
assert.strictEqual(nextCalled, true, "mail user-auth routes reach Google identity verification without a shared app key");

for (const req of [
  { method: "GET", path: "/mail/summarize" },
  { method: "POST", path: "/mail/other" },
  { method: "POST", path: "/compile-promise" },
]) {
  const res = fakeRes();
  nextCalled = false;
  appAuthMiddleware({ ...req, get: () => "" }, res, () => {
    nextCalled = true;
  });
  assert.strictEqual(res.statusCode, 401, `${req.method} ${req.path} does not bypass shared app-key auth`);
  assert.strictEqual(nextCalled, false);
}

delete process.env.STRLIX_BACKEND_APP_SECRET;
nextCalled = false;
requireAppSecret({ get: () => "" }, fakeRes(), () => {
  nextCalled = true;
});
assert.strictEqual(nextCalled, true, "empty env is local-open");

process.env.WEBSITE_HOSTNAME = "phonecodex-backend.azurewebsites.net";
const hostedDenied = fakeRes();
nextCalled = false;
requireAppSecret({ get: () => "" }, hostedDenied, () => {
  nextCalled = true;
});
assert.strictEqual(hostedDenied.statusCode, 503);
assert.strictEqual(nextCalled, false, "hosted empty secret must not next");
delete process.env.WEBSITE_HOSTNAME;

console.log("ASSERTIONS_PASSED=app_auth");
