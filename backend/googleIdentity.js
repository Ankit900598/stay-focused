/**
 * Per-user identity for delegated-connector routes: a Google ID token (Sign in with Google,
 * audience = Strlix's web client ID), verified locally against Google's published keys.
 * The static app secret only says "this is the Strlix app"; it is never a user identity.
 * Gmail access tokens never reach this server.
 */
const crypto = require("crypto");

const GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const ISSUERS = new Set(["accounts.google.com", "https://accounts.google.com"]);
const CLOCK_SKEW_SEC = 60;
const MAX_TOKEN_CHARS = 4096;
const UNKNOWN_KID_REFRESH_MS = 60 * 1000;

class IdentityError extends Error {
  constructor(code, statusCode) {
    super(code);
    this.name = "IdentityError";
    this.code = code;
    this.statusCode = statusCode;
  }
}

async function defaultFetchJwks() {
  const res = await fetch(GOOGLE_JWKS_URL, { signal: AbortSignal.timeout(5000) });
  if (!res.ok) throw new IdentityError("user_auth_unavailable", 503);
  const match = /max-age=(\d+)/.exec(res.headers.get("cache-control") || "");
  const body = await res.json();
  return { keys: Array.isArray(body.keys) ? body.keys : [], maxAgeSec: match ? Number(match[1]) : 3600 };
}

let fetchJwks = defaultFetchJwks;
let cache = { keys: null, expiresAt: 0, lastFetchAt: 0 };
let refreshInFlight = null;

function configuredAudiences() {
  return String(process.env.STRLIX_GOOGLE_WEB_CLIENT_IDS || "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

async function refresh(nowMs) {
  if (refreshInFlight) return refreshInFlight;
  const pending = (async () => {
    let fetched;
    try {
      fetched = await fetchJwks();
    } catch (err) {
      if (err instanceof IdentityError) throw err;
      throw new IdentityError("user_auth_unavailable", 503);
    }
    cache = {
      keys: fetched.keys,
      expiresAt: nowMs + Math.max(60, Math.min(fetched.maxAgeSec, 24 * 3600)) * 1000,
      lastFetchAt: nowMs,
    };
  })();
  refreshInFlight = pending;
  try {
    await pending;
  } finally {
    if (refreshInFlight === pending) refreshInFlight = null;
  }
}

async function keyFor(kid, nowMs) {
  if (!cache.keys || nowMs >= cache.expiresAt) await refresh(nowMs);
  let jwk = cache.keys.find((k) => k.kid === kid);
  if (!jwk && nowMs - cache.lastFetchAt >= UNKNOWN_KID_REFRESH_MS) {
    await refresh(nowMs);
    jwk = cache.keys.find((k) => k.kid === kid);
  }
  return jwk || null;
}

function decodePart(part) {
  try {
    return JSON.parse(Buffer.from(part, "base64url").toString("utf8"));
  } catch (_) {
    return null;
  }
}

async function verifyGoogleIdToken(token, { nowMs = Date.now(), audiences = configuredAudiences() } = {}) {
  if (audiences.length === 0) throw new IdentityError("user_auth_not_configured", 503);
  const invalid = () => new IdentityError("user_auth_invalid", 401);
  if (typeof token !== "string" || token.length === 0 || token.length > MAX_TOKEN_CHARS) throw invalid();
  const parts = token.split(".");
  if (parts.length !== 3) throw invalid();
  const header = decodePart(parts[0]);
  const payload = decodePart(parts[1]);
  if (!header || !payload || header.alg !== "RS256" || typeof header.kid !== "string") throw invalid();

  const jwk = await keyFor(header.kid, nowMs);
  if (!jwk || jwk.kty !== "RSA") throw invalid();
  let ok = false;
  try {
    const key = crypto.createPublicKey({ key: jwk, format: "jwk" });
    ok = crypto.verify(
      "RSA-SHA256",
      Buffer.from(`${parts[0]}.${parts[1]}`),
      key,
      Buffer.from(parts[2], "base64url")
    );
  } catch (_) {
    ok = false;
  }
  if (!ok) throw invalid();

  const nowSec = Math.floor(nowMs / 1000);
  if (!ISSUERS.has(payload.iss)) throw invalid();
  if (typeof payload.aud !== "string" || !audiences.includes(payload.aud)) throw invalid();
  if (typeof payload.exp !== "number" || payload.exp + CLOCK_SKEW_SEC < nowSec) {
    throw new IdentityError("user_auth_expired", 401);
  }
  if (typeof payload.iat === "number" && payload.iat - CLOCK_SKEW_SEC > nowSec) throw invalid();
  if (typeof payload.sub !== "string" || payload.sub.length === 0) throw invalid();
  if (payload.email_verified !== true && payload.email_verified !== "true") {
    throw new IdentityError("email_not_verified", 401);
  }
  return {
    sub: payload.sub,
    email: String(payload.email || "").toLowerCase(),
    // Logs and rate-limit keys use this, never the email or raw sub.
    userKey: crypto.createHash("sha256").update(payload.sub).digest("hex").slice(0, 16),
  };
}

function bearerToken(req) {
  const header = String((req.get && req.get("authorization")) || "");
  const match = /^Bearer\s+(\S+)$/i.exec(header.trim());
  return match ? match[1] : null;
}

async function requireGoogleUser(req, res, next) {
  const token = bearerToken(req);
  if (!token) return res.status(401).json({ error: "user_auth_required" });
  try {
    req.googleUser = await verifyGoogleIdToken(token);
    return next();
  } catch (err) {
    const code = err instanceof IdentityError ? err.code : "user_auth_invalid";
    const status = err instanceof IdentityError ? err.statusCode : 401;
    console.warn(JSON.stringify({ event: "user_auth_failed", code, path: req.path }));
    return res.status(status).json({ error: code });
  }
}

function setJwksFetcherForTests(fn) {
  fetchJwks = fn || defaultFetchJwks;
  cache = { keys: null, expiresAt: 0, lastFetchAt: 0 };
  refreshInFlight = null;
}

module.exports = {
  IdentityError,
  verifyGoogleIdToken,
  requireGoogleUser,
  configuredAudiences,
  setJwksFetcherForTests,
};
