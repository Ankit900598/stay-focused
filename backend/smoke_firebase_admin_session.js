/**
 * Official firebase-admin verifyIdToken path, with Google's hosts stubbed.
 * Run: node backend/smoke_firebase_admin_session.js
 * Does not load .env, listen, or call a real Google host.
 */
const assert = require("assert");
const crypto = require("crypto");
const fs = require("fs");
const https = require("https");
const os = require("os");
const path = require("path");
const { PassThrough } = require("stream");
const { spawnSync } = require("child_process");
const { verifyWithFirebaseAdmin } = require("./userSessionAuth");

const PROJECT = "strlix-sdk-test";
const CERT_HOST = "www.googleapis.com";
const CERT_PATH = "/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";
const LOOKUP_HOST = "identitytoolkit.googleapis.com";

function sign(payload, privateKey, kid) {
  const header = Buffer.from(JSON.stringify({ alg: "RS256", kid, typ: "JWT" })).toString("base64url");
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const data = `${header}.${body}`;
  const sig = crypto.sign("RSA-SHA256", Buffer.from(data), privateKey).toString("base64url");
  return `${data}.${sig}`;
}

function certificateFor(privateKeyPem, dir, name) {
  const keyPath = path.join(dir, `${name}.key`);
  const certPath = path.join(dir, `${name}.crt`);
  fs.writeFileSync(keyPath, privateKeyPem, { mode: 0o600 });
  const result = spawnSync("openssl", [
    "req", "-new", "-x509", "-key", keyPath, "-out", certPath,
    "-days", "1", "-subj", "/CN=strlix-session-test",
  ], { encoding: "utf8" });
  if (result.status !== 0) throw new Error("openssl_cert_failed");
  return fs.readFileSync(certPath, "utf8");
}

function rsaPem() {
  return crypto.generateKeyPairSync("rsa", { modulusLength: 2048 })
    .privateKey.export({ type: "pkcs8", format: "pem" });
}

function claims(overrides) {
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    iss: `https://securetoken.google.com/${PROJECT}`,
    aud: PROJECT,
    sub: "uid-sdk-1",
    iat: now,
    exp: now + 600,
    auth_time: now,
    ...overrides,
  };
  for (const key of Object.keys(payload)) {
    if (payload[key] === undefined) delete payload[key];
  }
  return payload;
}

async function expectCode(token, code) {
  await assert.rejects(
    () => verifyWithFirebaseAdmin(token, admin, PROJECT),
    (err) => err.code === code && err.message === code
  );
}

const realRequest = https.request;
const realFetch = global.fetch;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "strlix-fb-sdk-"));
const keyA = rsaPem();
const keyB = rsaPem();
const forged = rsaPem();
const certA = certificateFor(keyA, dir, "a");
const certB = certificateFor(keyB, dir, "b");
const certQueue = [];
let certFetches = 0;
let lookupCalls = 0;
let validSince = "1";
let admin;

function answer(statusCode, headers, body) {
  return { statusCode, headers, body };
}

https.request = function stubRequest(urlOrOptions, optionsOrCallback, maybeCallback) {
  let options = urlOrOptions;
  let callback = optionsOrCallback;
  if (typeof urlOrOptions === "string" || (urlOrOptions && urlOrOptions.href)) {
    const parsed = new URL(typeof urlOrOptions === "string" ? urlOrOptions : urlOrOptions.href);
    const extra = optionsOrCallback && typeof optionsOrCallback === "object" ? optionsOrCallback : {};
    options = { ...extra, hostname: parsed.hostname, path: `${parsed.pathname}${parsed.search}` };
    callback = typeof optionsOrCallback === "function" ? optionsOrCallback : maybeCallback;
  }
  const host = options.hostname || options.host;
  const reqPath = options.path || "";
  const req = new PassThrough();
  req.setTimeout = () => req;
  req.destroy = () => {};
  req.abort = () => {};
  req.end = () => {
    let response;
    if (host === CERT_HOST && reqPath.startsWith(CERT_PATH)) {
      certFetches += 1;
      const next = certQueue.shift();
      if (!next) throw new Error("cert_queue_empty");
      response = answer(200, {
        "content-type": "application/json",
        "cache-control": `max-age=${next.maxAge}`,
      }, JSON.stringify(next.map));
    } else if (host === LOOKUP_HOST && reqPath.includes("/accounts:lookup")) {
      lookupCalls += 1;
      response = answer(200, { "content-type": "application/json" }, JSON.stringify({
        users: [{ localId: "uid-sdk-1", validSince }],
      }));
    } else if (host === "oauth2.googleapis.com") {
      response = answer(200, { "content-type": "application/json" }, JSON.stringify({
        access_token: "test-access-token",
        expires_in: 3600,
        token_type: "Bearer",
      }));
    } else {
      throw new Error(`unexpected_host ${host} ${String(reqPath).split("?")[0]}`);
    }
    const res = new PassThrough();
    res.statusCode = response.statusCode;
    res.headers = response.headers;
    process.nextTick(() => {
      if (typeof callback === "function") callback(res);
      else req.emit("response", res);
      res.end(response.body);
    });
    return req;
  };
  return req;
};

global.fetch = async (url) => {
  const href = String(url && url.url ? url.url : url);
  if (!href.includes("oauth2.googleapis.com/token")) {
    throw new Error("unexpected_fetch_host");
  }
  return new Response(JSON.stringify({
    access_token: "test-access-token",
    expires_in: 3600,
    token_type: "Bearer",
  }), { status: 200, headers: { "content-type": "application/json" } });
};

(async () => {
  assert.ok(require.resolve("firebase-admin"));
  admin = require("firebase-admin");
  const signingA = crypto.createPrivateKey(keyA);
  const signingB = crypto.createPrivateKey(keyB);
  const signingForged = crypto.createPrivateKey(forged);
  process.env.STRLIX_FIREBASE_CLIENT_EMAIL = "session-test@strlix-sdk-test.iam.gserviceaccount.com";
  process.env.STRLIX_FIREBASE_PRIVATE_KEY = keyA;
  delete process.env.GOOGLE_APPLICATION_CREDENTIALS;

  certQueue.push({ map: { "kid-a": certA }, maxAge: 3600 });
  const now = Math.floor(Date.now() / 1000);
  const ok = await verifyWithFirebaseAdmin(sign(claims(), signingA, "kid-a"), admin, PROJECT);
  assert.strictEqual(ok.uid, "uid-sdk-1");
  assert.strictEqual(certFetches, 1);
  assert.ok(lookupCalls >= 1);

  await expectCode(sign(claims(), signingForged, "kid-a"), "user_auth_invalid");
  assert.strictEqual(certFetches, 1);
  await expectCode(sign(claims({ aud: "other-project" }), signingA, "kid-a"), "user_auth_wrong_audience");
  await expectCode(sign(claims({ iss: "https://securetoken.google.com/other-project" }), signingA, "kid-a"), "user_auth_invalid");
  await expectCode(sign(claims({ exp: now - 120 }), signingA, "kid-a"), "user_auth_expired");
  await expectCode(sign(claims({ iat: undefined }), signingA, "kid-a"), "user_auth_invalid");
  await expectCode(sign(claims({ iat: now + 120 }), signingA, "kid-a"), "user_auth_invalid");
  await expectCode(sign(claims({ auth_time: now + 120 }), signingA, "kid-a"), "user_auth_invalid");
  await expectCode(sign(claims({ exp: undefined }), signingA, "kid-a"), "user_auth_invalid");
  await expectCode(sign(claims({ sub: "" }), signingA, "kid-a"), "user_auth_invalid");
  await expectCode(sign(claims({ sub: "u".repeat(129) }), signingA, "kid-a"), "user_auth_invalid");

  validSince = String(now + 30);
  await expectCode(sign(claims({ auth_time: now }), signingA, "kid-a"), "user_auth_revoked");
  validSince = "1";

  await expectCode(sign(claims(), signingB, "kid-b"), "user_auth_invalid");
  assert.strictEqual(certFetches, 1);

  const { getAuth } = require("firebase-admin/auth");
  const fetcher = getAuth().idTokenVerifier.signatureVerifier.keyFetcher;
  fetcher.publicKeysExpireAt = Date.now() - 1;
  certQueue.push({ map: { "kid-a": certA, "kid-b": certB }, maxAge: 0 });
  const rotated = await verifyWithFirebaseAdmin(sign(claims(), signingB, "kid-b"), admin, PROJECT);
  assert.strictEqual(rotated.uid, "uid-sdk-1");
  assert.strictEqual(certFetches, 2);

  process.stdout.write("FIREBASE_ADMIN_SESSION_OK\n");
})().catch((err) => {
  process.stderr.write(`${err && err.stack ? err.stack : err}\n`);
  process.exitCode = 1;
}).finally(async () => {
  https.request = realRequest;
  global.fetch = realFetch;
  delete process.env.STRLIX_FIREBASE_CLIENT_EMAIL;
  delete process.env.STRLIX_FIREBASE_PRIVATE_KEY;
  try {
    const { deleteApp, getApps } = require("firebase-admin/app");
    const apps = getApps();
    if (apps.length) await deleteApp(apps[0]);
  } catch (_) {}
  fs.rmSync(dir, { recursive: true, force: true });
});
