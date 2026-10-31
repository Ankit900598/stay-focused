/**
 * Verified-user gate for paid Strlix routes.
 *
 * Mounted only when STRLIX_USER_SESSION_AUTH=1, by sessionRouteAuth.js.
 * The flag defaults off. Phone-test keeps today's shared-key and mail-only
 * Google checks.
 *
 * Claim checks follow the Firebase Admin ID-token table
 * (https://firebase.google.com/docs/auth/admin/verify-id-tokens) and the
 * Admin SDK's verifyContent rules (RS256, kid, aud = project id,
 * iss = https://securetoken.google.com/<projectId>, sub non-empty and
 * at most 128 characters). iat and auth_time must be in the past. exp must
 * be in the future. Clock skew is 60 seconds, the same window already used
 * for Google ID tokens in this repo.
 *
 * verifyWithFirebaseAdmin is the production verifier. verifySessionJwt is
 * kept for isolated offline tests that inject keys and must not call Google.
 * A hosted runtime does not use it. The Admin SDK checks the signature and
 * the claim table except a missing or future iat and a future auth_time;
 * verifyWithFirebaseAdmin rejects those after the SDK accepts the signature.
 *
 * Keys, issuer, and audience are injected. No project id is hardcoded.
 * X-Strlix-App-Secret and a request-body uid are not an identity.
 */
const crypto = require("crypto");

const CLOCK_SKEW_SEC = 60;
const MAX_TOKEN_CHARS = 8192;
const MAX_UID_CHARS = 128;
const HOUR_MS = 60 * 60 * 1000;
const SESSION_AUTH_ENV = "STRLIX_USER_SESSION_AUTH";

class SessionAuthError extends Error {
  constructor(code, statusCode) {
    super(code);
    this.name = "SessionAuthError";
    this.code = code;
    this.statusCode = statusCode;
  }
}

/**
 * How each current route moves once the staging flag is on.
 * `user-session-before-body` means verify and revoke before express.json.
 */
const ROUTE_MIGRATION = [
  {
    method: "GET",
    path: "/health",
    paid: false,
    androidCaller: "BackendHealthProbe",
    today: "public; reports whether a shared app secret is configured",
    nextAuth: "public",
  },
  {
    method: "POST",
    path: "/compile-promise",
    paid: true,
    androidCaller: "PromiseCompilerClient via BackendConfig.applyAuth",
    today: "shared app secret, then caller+IP throttle, then global daily cap in the handler",
    nextAuth: "user-session-before-body",
  },
  {
    method: "POST",
    path: "/classify",
    paid: true,
    androidCaller: "NetworkAiContentClassifier and BackendHealthProbe via BackendConfig.applyAuth",
    today: "shared app secret, then caller+IP throttle, then global daily cap when a classifier provider exists",
    nextAuth: "user-session-before-body",
  },
  {
    method: "POST",
    path: "/transcribe-promise",
    paid: true,
    androidCaller: "PromiseSpeechClient via BackendConfig.applyAuth",
    today: "shared app secret, then caller+IP throttle, then global daily cap only when speech will call a provider",
    nextAuth: "user-session-before-body",
  },
  {
    method: "POST",
    path: "/research-app",
    paid: true,
    androidCaller: "AppDossierClient via BackendConfig.applyAuth",
    today: "shared app secret, then caller+IP throttle, then global daily cap when a classifier provider exists",
    nextAuth: "user-session-before-body",
  },
  {
    method: "POST",
    path: "/draft-focus-promise",
    paid: true,
    androidCaller: "none in the Android app",
    today: "shared app secret, then caller+IP throttle, then global daily cap only when OPENAI_API_KEY is set",
    nextAuth: "user-session-before-body",
  },
  {
    method: "POST",
    path: "/mail/summarize",
    paid: true,
    androidCaller: "BackendMailAssistant Bearer Google ID token; shared secret only if the APK embedded one",
    today: "mail kill switch, caller+IP throttle, verified Google ID token, then per-userKey limit",
    nextAuth: "user-session-and-google-mail-identity",
  },
  {
    method: "POST",
    path: "/mail/draft-reply",
    paid: true,
    androidCaller: "BackendMailAssistant Bearer Google ID token; shared secret only if the APK embedded one",
    today: "mail kill switch, caller+IP throttle, verified Google ID token, then per-userKey limit",
    nextAuth: "user-session-and-google-mail-identity",
  },
];

function createRevocationList() {
  const notBeforeSec = new Map();
  return {
    revoke(uid, authTimeMustBeAfterSec) {
      if (typeof uid !== "string" || uid.length === 0) {
        throw new SessionAuthError("user_auth_invalid", 401);
      }
      if (!Number.isFinite(authTimeMustBeAfterSec)) {
        throw new SessionAuthError("user_auth_invalid", 401);
      }
      notBeforeSec.set(uid, authTimeMustBeAfterSec);
    },
    isRevoked(uid, authTimeSec) {
      const floor = notBeforeSec.get(uid);
      return floor != null && authTimeSec < floor;
    },
  };
}

function decodePart(part) {
  try {
    return JSON.parse(Buffer.from(part, "base64url").toString("utf8"));
  } catch (_) {
    return null;
  }
}

function bearerToken(req) {
  const header = String((req && req.get && req.get("authorization")) || "");
  const match = /^Bearer\s+(\S+)$/i.exec(header.trim());
  return match ? match[1] : null;
}

function findRsaKey(keys, kid) {
  if (!Array.isArray(keys)) return null;
  return keys.find((key) => key && key.kid === kid && key.kty === "RSA") || null;
}

async function loadKeys(options) {
  if (typeof options.keys === "function") return options.keys();
  return options.keys;
}

function pastClaim(value, nowSec, clockSkewSec) {
  return typeof value === "number" && Number.isFinite(value) && value - clockSkewSec <= nowSec;
}

async function verifySessionJwt(token, options = {}) {
  const {
    audience,
    issuer,
    nowMs = Date.now(),
    revocation = null,
    clockSkewSec = CLOCK_SKEW_SEC,
  } = options;
  const invalid = () => new SessionAuthError("user_auth_invalid", 401);
  if (typeof audience !== "string" || audience.length === 0 || typeof issuer !== "string" || issuer.length === 0) {
    throw new SessionAuthError("user_auth_not_configured", 503);
  }
  if (typeof token !== "string" || token.length === 0 || token.length > MAX_TOKEN_CHARS) throw invalid();
  const parts = token.split(".");
  if (parts.length !== 3 || parts.some((part) => part.length === 0)) throw invalid();
  const header = decodePart(parts[0]);
  const payload = decodePart(parts[1]);
  if (!header || !payload || header.alg !== "RS256" || typeof header.kid !== "string" || header.kid.length === 0) {
    throw invalid();
  }

  let keys = await loadKeys(options);
  if (!Array.isArray(keys) && typeof options.resolveKeys !== "function") {
    throw new SessionAuthError("user_auth_not_configured", 503);
  }
  let jwk = findRsaKey(keys, header.kid);
  if (!jwk && typeof options.resolveKeys === "function") {
    keys = await options.resolveKeys(header.kid);
    jwk = findRsaKey(keys, header.kid);
  }
  if (!jwk) throw invalid();

  let signatureOk = false;
  try {
    const key = crypto.createPublicKey({ key: jwk, format: "jwk" });
    signatureOk = crypto.verify(
      "RSA-SHA256",
      Buffer.from(`${parts[0]}.${parts[1]}`),
      key,
      Buffer.from(parts[2], "base64url")
    );
  } catch (_) {
    signatureOk = false;
  }
  if (!signatureOk) throw invalid();

  const nowSec = Math.floor(nowMs / 1000);
  if (payload.iss !== issuer) throw invalid();
  if (typeof payload.aud !== "string" || payload.aud !== audience) {
    throw new SessionAuthError("user_auth_wrong_audience", 401);
  }
  if (typeof payload.exp !== "number" || !Number.isFinite(payload.exp)) throw invalid();
  if (payload.exp + clockSkewSec < nowSec) throw new SessionAuthError("user_auth_expired", 401);
  if (!pastClaim(payload.iat, nowSec, clockSkewSec)) throw invalid();
  if (typeof payload.sub !== "string" || payload.sub.length === 0 || payload.sub.length > MAX_UID_CHARS) throw invalid();
  if (!pastClaim(payload.auth_time, nowSec, clockSkewSec)) throw invalid();
  if (revocation && typeof revocation.isRevoked === "function" && await revocation.isRevoked(payload.sub, payload.auth_time)) {
    throw new SessionAuthError("user_auth_revoked", 401);
  }
  return { uid: payload.sub, audience: payload.aud, authTimeSec: payload.auth_time };
}

function createTokenVerifier(options) {
  return (token, nowMs = Date.now()) => verifySessionJwt(token, { ...options, nowMs });
}

function credentialFactory(admin) {
  const namespaced = admin && admin.credential;
  if (namespaced && (typeof namespaced.cert === "function" || typeof namespaced.applicationDefault === "function")) {
    return namespaced;
  }
  if (admin && (typeof admin.cert === "function" || typeof admin.applicationDefault === "function")) return admin;
  return null;
}

function credentialFromEnv(admin, projectId) {
  const factory = credentialFactory(admin);
  if (!factory) return null;
  const email = String(process.env.STRLIX_FIREBASE_CLIENT_EMAIL || "").trim();
  const privateKey = String(process.env.STRLIX_FIREBASE_PRIVATE_KEY || "");
  if (email && privateKey.trim()) {
    return factory.cert({
      projectId,
      clientEmail: email,
      privateKey: privateKey.replace(/\\n/g, "\n"),
    });
  }
  if (typeof factory.applicationDefault === "function") return factory.applicationDefault();
  return null;
}

function openAdminApps(admin) {
  if (Array.isArray(admin.apps)) return admin.apps;
  if (typeof admin.getApps === "function") return admin.getApps();
  return [];
}

function adminAuth(admin) {
  if (typeof admin.auth === "function") return admin.auth();
  const { getAuth } = require("firebase-admin/auth");
  return getAuth();
}

/**
 * Production verifier. Always calls verifyIdToken(token, true), so revocation
 * is the Firebase user record's tokensValidAfterTime.
 *
 * The Admin SDK checks signature, kid, aud, iss, exp, and sub length. It does
 * not reject a missing or future iat, or a future auth_time. The public
 * ID-token table requires those, so this wrapper rejects them after the SDK
 * has verified the signature. The original SDK error text is never returned
 * to the client; it can echo the token.
 */
async function verifyWithFirebaseAdmin(token, admin, projectId) {
  if (!admin || typeof projectId !== "string" || projectId.length === 0) {
    throw new SessionAuthError("user_auth_not_configured", 503);
  }
  if (openAdminApps(admin).length === 0) {
    let credential = null;
    try {
      credential = credentialFromEnv(admin, projectId);
    } catch (_) {
      throw new SessionAuthError("user_auth_not_configured", 503);
    }
    if (!credential) throw new SessionAuthError("user_auth_not_configured", 503);
    admin.initializeApp({ credential, projectId });
  }
  try {
    const decoded = await adminAuth(admin).verifyIdToken(token, true);
    if (!decoded || typeof decoded.sub !== "string" || decoded.sub.length === 0 || decoded.sub.length > MAX_UID_CHARS) {
      throw new SessionAuthError("user_auth_invalid", 401);
    }
    if (decoded.aud !== projectId) throw new SessionAuthError("user_auth_wrong_audience", 401);
    const nowSec = Math.floor(Date.now() / 1000);
    if (typeof decoded.exp !== "number" || !Number.isFinite(decoded.exp)) {
      throw new SessionAuthError("user_auth_invalid", 401);
    }
    if (decoded.exp + CLOCK_SKEW_SEC < nowSec) throw new SessionAuthError("user_auth_expired", 401);
    if (!pastClaim(decoded.iat, nowSec, CLOCK_SKEW_SEC)) throw new SessionAuthError("user_auth_invalid", 401);
    if (!pastClaim(decoded.auth_time, nowSec, CLOCK_SKEW_SEC)) throw new SessionAuthError("user_auth_invalid", 401);
    return { uid: decoded.sub, audience: decoded.aud, authTimeSec: decoded.auth_time };
  } catch (err) {
    if (err instanceof SessionAuthError) throw err;
    const code = err && err.code;
    if (code === "auth/id-token-revoked") throw new SessionAuthError("user_auth_revoked", 401);
    if (code === "auth/id-token-expired") throw new SessionAuthError("user_auth_expired", 401);
    const message = String((err && err.message) || "");
    if (code === "auth/argument-error" && /incorrect "?aud"?|audience/i.test(message)) {
      throw new SessionAuthError("user_auth_wrong_audience", 401);
    }
    throw new SessionAuthError("user_auth_invalid", 401);
  }
}

function currentLimits(limits) {
  const value = typeof limits === "function" ? limits() : limits;
  const capacity = Number(value && value.capacity);
  const perHour = Number(value && value.perHour);
  return {
    capacity: Number.isFinite(capacity) && capacity > 0 ? capacity : 8,
    perHour: Number.isFinite(perHour) && perHour > 0 ? perHour : 40,
  };
}

function requestIdFrom(req) {
  const raw = req && req.get && req.get("x-strlix-request-id");
  if (raw == null || String(raw).trim() === "") return null;
  const id = String(raw).trim();
  if (!/^[A-Za-z0-9._:-]{8,80}$/.test(id)) return { invalid: true };
  return id;
}

function createSessionGate({
  verify,
  limits = { capacity: 8, perHour: 40 },
  nowMs = Date.now,
  store = null,
} = {}) {
  if (typeof verify !== "function") throw new SessionAuthError("user_auth_not_configured", 503);
  const buckets = new Map();

  function takeMemory(uid, routePath, atMs) {
    const bounds = currentLimits(limits);
    const key = `${uid}|${routePath}`;
    let bucket = buckets.get(key);
    if (!bucket) {
      bucket = { tokens: bounds.capacity, at: atMs };
      buckets.set(key, bucket);
    }
    const refill = ((atMs - bucket.at) / HOUR_MS) * bounds.perHour;
    bucket.tokens = Math.min(bounds.capacity, bucket.tokens + refill);
    bucket.at = atMs;
    if (bucket.tokens >= 1) {
      bucket.tokens -= 1;
      return { ok: true };
    }
    const retryAfterSec = Math.max(1, Math.ceil(((1 - bucket.tokens) / bounds.perHour) * 3600));
    return { ok: false, retryAfterSec };
  }

  async function requireUser(req, res, next) {
    const token = bearerToken(req);
    if (!token) return res.status(401).json({ error: "user_auth_required" });
    try {
      const user = await verify(token, nowMs());
      req.strlixUser = { uid: user.uid, authTimeSec: user.authTimeSec };
      return next();
    } catch (err) {
      const code = err instanceof SessionAuthError ? err.code : "user_auth_invalid";
      const status = err instanceof SessionAuthError ? err.statusCode : 401;
      console.warn(JSON.stringify({ event: "user_auth_failed", code, path: req.path }));
      return res.status(status).json({ error: code });
    }
  }

  async function limitPaidUser(req, res, next) {
    const uid = req && req.strlixUser && req.strlixUser.uid;
    if (!uid) return res.status(401).json({ error: "user_auth_required" });
    const requestId = requestIdFrom(req);
    if (requestId && requestId.invalid) return res.status(400).json({ error: "invalid_request_id" });
    let result;
    try {
      result = store
        ? await store.takeUser(uid, req.path, currentLimits(limits), nowMs(), requestId)
        : takeMemory(uid, req.path, nowMs());
    } catch (_) {
      return res.status(503).json({ error: "user_auth_unavailable" });
    }
    if (result.duplicate) return res.status(409).json({ error: "duplicate_request" });
    if (result.failedAttempt) return res.status(503).json({ error: "paid_attempt_already_failed" });
    if (result.inflight) {
      res.set("Retry-After", String(result.retryAfterSec || 1));
      return res.status(429).json({ error: "request_in_flight" });
    }
    if (!result.ok) {
      res.set("Retry-After", String(result.retryAfterSec));
      return res.status(429).json({ error: "rate_limited" });
    }
    if (store && requestId) {
      res.on("finish", () => {
        Promise.resolve(store.finishRequest(uid, req.path, requestId, res.statusCode)).catch(() => {});
      });
    }
    return next();
  }

  return {
    requireUser,
    limitPaidUser,
    reset() {
      buckets.clear();
    },
  };
}

module.exports = {
  SessionAuthError,
  ROUTE_MIGRATION,
  SESSION_AUTH_ENV,
  CLOCK_SKEW_SEC,
  MAX_UID_CHARS,
  createRevocationList,
  verifySessionJwt,
  verifyWithFirebaseAdmin,
  createTokenVerifier,
  createSessionGate,
  bearerToken,
};
