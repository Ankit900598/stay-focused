/**
 * Local-only mini backend for Android debug E2E.
 * Real Firebase Admin verify. Auth state: memory (laptop lacks Strlix Azure AAD).
 * No OpenAI/Sol. No hosted env flips.
 */
"use strict";
const express = require("express");
const path = require("path");

const PROJECT = "phonecodex-strlix";
const PORT = Number(process.env.PORT || 8787);

process.env.STRLIX_USER_SESSION_AUTH = "1";
process.env.STRLIX_FIREBASE_PROJECT_ID = PROJECT;
process.env.STRLIX_USER_ROUTE_CAPACITY = process.env.STRLIX_USER_ROUTE_CAPACITY || "2";
process.env.STRLIX_USER_ROUTE_PER_HOUR = process.env.STRLIX_USER_ROUTE_PER_HOUR || "2";
process.env.STRLIX_DAILY_PAID_CALL_CAP = process.env.STRLIX_DAILY_PAID_CALL_CAP || "20";
// E2E needs auth+capacity path; kill-switch off for stub (no model keys). Set 1 to demo 503.
if (process.env.STRLIX_PAID_ROUTES_DISABLED === undefined) {
  process.env.STRLIX_PAID_ROUTES_DISABLED = "0";
}
delete process.env.STRLIX_AUTH_TABLE_CONNECTION;
delete process.env.STRLIX_AUTH_JWKS_PATH;
delete process.env.WEBSITE_INSTANCE_ID;
delete process.env.WEBSITE_SITE_NAME;

const { mountPaidSessionAuth } = require("./sessionRouteAuth");

async function main() {
  const creds = String(process.env.GOOGLE_APPLICATION_CREDENTIALS || "").trim();
  if (!creds) {
    console.error("GOOGLE_APPLICATION_CREDENTIALS required");
    process.exit(1);
  }
  const app = express();
  await mountPaidSessionAuth(app, {
    mailAssist: { isMailAssistEnabled: () => false },
    requireGoogleUser: (_r, res) => res.status(501).end(),
    userRouteGuard: (_r, _s, n) => n(),
  });
  app.post("/classify", (_req, res) => res.status(200).json({ ok: true, e2e: "android-debug" }));
  app.get("/healthz", (_req, res) => res.status(200).json({ ok: true }));
  app.listen(PORT, "127.0.0.1", () => {
    console.log(JSON.stringify({ event: "android_e2e_server_listen", port: PORT, project: PROJECT, store: "memory" }));
  });
}

main().catch((e) => {
  console.error(String(e && e.message ? e.message : e).slice(0, 200));
  process.exit(1);
});
