/**
 * Real-project Firebase Admin verify of one Anonymous ID token (phonecodex-strlix).
 * Run (from repo root or backend/):
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json \
 *   STRLIX_FIREBASE_PROJECT_ID=phonecodex-strlix \
 *   STRLIX_FIREBASE_ID_TOKEN_FILE=/path/to/token.txt \
 *   node backend/smoke_firebase_admin_real_verify.js
 *
 * Never prints the token or private key. Claims-only stdout.
 * Does not flip STRLIX_USER_SESSION_AUTH. Does not start the HTTP server.
 */
"use strict";

const assert = require("assert");
const fs = require("fs");
const path = require("path");
const { verifyWithFirebaseAdmin, SessionAuthError } = require("./userSessionAuth");

const PROJECT = String(process.env.STRLIX_FIREBASE_PROJECT_ID || "phonecodex-strlix").trim();
const tokenFile = String(process.env.STRLIX_FIREBASE_ID_TOKEN_FILE || "").trim();
const creds = String(process.env.GOOGLE_APPLICATION_CREDENTIALS || "").trim();

function readToken() {
  assert.ok(tokenFile, "STRLIX_FIREBASE_ID_TOKEN_FILE required");
  assert.ok(fs.existsSync(tokenFile), "token file missing");
  const token = fs.readFileSync(tokenFile, "utf8").trim();
  assert.ok(token.split(".").length === 3, "token must be a JWT");
  return token;
}

function claimsPreview(token) {
  const mid = token.split(".")[1];
  const json = Buffer.from(mid, "base64url").toString("utf8");
  const c = JSON.parse(json);
  return {
    aud: c.aud,
    iss: c.iss,
    uid: c.sub,
    sign_in_provider: c.firebase && c.firebase.sign_in_provider,
    exp: c.exp,
  };
}

async function main() {
  assert.strictEqual(PROJECT, "phonecodex-strlix", "project must be phonecodex-strlix");
  assert.ok(creds && fs.existsSync(creds), "GOOGLE_APPLICATION_CREDENTIALS must point to SA JSON");
  // Confirm SA project without printing secrets
  const sa = JSON.parse(fs.readFileSync(creds, "utf8"));
  assert.strictEqual(sa.project_id, PROJECT, "SA project_id mismatch");
  assert.ok(String(sa.client_email || "").includes("@phonecodex-strlix.iam.gserviceaccount.com"));

  const admin = require("firebase-admin");
  const token = readToken();
  const preview = claimsPreview(token);
  assert.strictEqual(preview.aud, PROJECT);
  assert.strictEqual(preview.iss, `https://securetoken.google.com/${PROJECT}`);
  assert.strictEqual(preview.sign_in_provider, "anonymous");
  assert.ok(preview.uid && preview.uid.length > 0);

  process.env.STRLIX_FIREBASE_PROJECT_ID = PROJECT;
  delete process.env.STRLIX_FIREBASE_CLIENT_EMAIL;
  delete process.env.STRLIX_FIREBASE_PRIVATE_KEY;

  const ok = await verifyWithFirebaseAdmin(token, admin, PROJECT);
  assert.strictEqual(ok.uid, preview.uid);
  assert.strictEqual(ok.audience, PROJECT);

  // Direct SDK decode for anonymous provider (wrapper returns uid/aud only)
  const { getAuth } = require("firebase-admin/auth");
  const decoded = await getAuth().verifyIdToken(token, true);
  assert.strictEqual(decoded.firebase.sign_in_provider, "anonymous");
  assert.strictEqual(decoded.aud, PROJECT);

  // Reject bad tokens
  async function expectReject(label, bad, code) {
    try {
      await verifyWithFirebaseAdmin(bad, admin, PROJECT);
      throw new Error(`${label}_should_reject`);
    } catch (err) {
      assert.ok(err instanceof SessionAuthError, `${label}_not_SessionAuthError`);
      assert.strictEqual(err.code, code, `${label}_code got=${err.code}`);
    }
  }

  await expectReject("tampered", token.slice(0, -4) + "dead", "user_auth_invalid");
  await expectReject("empty", "", "user_auth_invalid");
  await expectReject("wrong_shape", "a.b.c", "user_auth_invalid");

  // Wrong project audience check: re-init would be heavy; assert wrapper rejects aud mismatch
  // by verifying with a different expected project id on already-initialized app —
  // verifyWithFirebaseAdmin compares decoded.aud to projectId arg.
  try {
    await verifyWithFirebaseAdmin(token, admin, "some-other-project");
    throw new Error("wrong_project_should_reject");
  } catch (err) {
    assert.ok(err instanceof SessionAuthError);
    assert.strictEqual(err.code, "user_auth_wrong_audience");
  }

  // Cleanup anonymous user so we do not leave orphan Auth users
  await getAuth().deleteUser(ok.uid);

  process.stdout.write(
    [
      "FIREBASE_ADMIN_REAL_VERIFY_OK",
      `project=${PROJECT}`,
      `uid_len=${ok.uid.length}`,
      `aud=${ok.audience}`,
      `sign_in_provider=anonymous`,
      `sa_email=${sa.client_email}`,
      `token_file=${path.basename(tokenFile)}`,
    ].join("\n") + "\n"
  );
}

main().catch((err) => {
  const code = err && err.code ? err.code : "error";
  const msg = err && err.message ? String(err.message).slice(0, 200) : "unknown";
  // Never echo token-like strings
  process.stderr.write(`FIREBASE_ADMIN_REAL_VERIFY_FAIL code=${code} msg=${msg}\n`);
  process.exit(1);
});
