const crypto = require("crypto");

function secretsMatch(got, expected) {
  const a = Buffer.from(String(got), "utf8");
  const b = Buffer.from(String(expected), "utf8");
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function isPublicHealth(req) {
  const path = String((req && req.path) || "");
  return req && req.method === "GET" && (path === "/health" || path === "/");
}

function expectedAppSecret() {
  return String(process.env.STRLIX_BACKEND_APP_SECRET || "").trim();
}

/**
 * Accepted caller keys. STRLIX_BACKEND_APP_SECRETS="label:key,label2:key2" gives
 * per-tester keys (revoke one without breaking others; two keys overlap during
 * rotation). STRLIX_BACKEND_APP_SECRET stays accepted as label "legacy".
 */
function acceptedKeys() {
  const keys = [];
  for (const entry of String(process.env.STRLIX_BACKEND_APP_SECRETS || "").split(",")) {
    const idx = entry.indexOf(":");
    if (idx <= 0) continue;
    const label = entry.slice(0, idx).trim();
    const key = entry.slice(idx + 1).trim();
    if (label && key) keys.push({ label, key });
  }
  const legacy = expectedAppSecret();
  if (legacy) keys.push({ label: "legacy", key: legacy });
  return keys;
}

function isHostedRuntime() {
  return Boolean(
    String(process.env.WEBSITE_HOSTNAME || "").trim() ||
      process.env.STRLIX_REQUIRE_APP_SECRET === "1"
  );
}

function assertHostedAppSecretConfigured() {
  if (isHostedRuntime() && acceptedKeys().length === 0) {
    throw new Error(
      "STRLIX_BACKEND_APP_SECRET or STRLIX_BACKEND_APP_SECRETS is required on hosted POST routes"
    );
  }
}

// Compares against every key so timing does not reveal which label matched.
function matchCaller(got, keys) {
  let label = null;
  for (const k of keys) {
    if (secretsMatch(got, k.key) && label === null) label = k.label;
  }
  return label;
}

function requireAppSecret(req, res, next) {
  const keys = acceptedKeys();
  if (keys.length === 0) {
    if (isHostedRuntime()) {
      return res.status(503).json({ error: "app_secret_not_configured" });
    }
    if (req) req.caller = "local";
    return next();
  }
  const got = String((req && req.get && req.get("x-strlix-app-secret")) || "").trim();
  const label = got ? matchCaller(got, keys) : null;
  if (!label) {
    return res.status(401).json({ error: "unauthorized" });
  }
  if (req) req.caller = label;
  return next();
}

function isGoogleUserAuthenticatedRoute(req) {
  return req && req.method === "POST" &&
    ["/mail/summarize", "/mail/draft-reply"].includes(String(req.path || ""));
}

function appAuthMiddleware(req, res, next) {
  if (isPublicHealth(req) || isGoogleUserAuthenticatedRoute(req)) return next();
  return requireAppSecret(req, res, next);
}

module.exports = {
  isPublicHealth,
  expectedAppSecret,
  acceptedKeys,
  isHostedRuntime,
  assertHostedAppSecretConfigured,
  isGoogleUserAuthenticatedRoute,
  requireAppSecret,
  appAuthMiddleware,
  secretsMatch,
};
