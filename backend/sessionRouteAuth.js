/**
 * Mounts user-session auth in front of paid routes.
 *
 * Off unless STRLIX_USER_SESSION_AUTH=1. The phone-test launcher deletes that
 * flag before it loads server.js, so the shared-key path stays in place.
 *
 * Custom RS256 verification is only for isolated offline tests that set
 * STRLIX_AUTH_JWKS_PATH. A hosted process rejects that file. Identity on the
 * real path is firebase-admin verifyIdToken(token, true).
 *
 * Hosted quotas live in Azure Table Storage (STRLIX_AUTH_TABLE_CONNECTION).
 * A local file, a single-instance flag, and WEBSITE_INSTANCE_ID are not a
 * shared budget. If that table is missing or unreachable, startup fails.
 * STRLIX_AUTH_QUOTA_SCOPE is not consulted.
 *
 * Identity is the verified token subject.
 * X-Strlix-App-Secret and any body uid are ignored on this path.
 *
 * Mail keeps its own Google ID token, now in X-Strlix-Google-Id-Token, plus
 * the existing consent check. A Firebase uid does not grant Gmail.
 */
const fs = require("fs");

const { isPublicHealth, requireAppSecret, isHostedRuntime } = require("./appAuth");
const { createFileAuthState, createMemoryAuthState } = require("./authStateStore");
const { createTableAuthState } = require("./tableAuthState");
const {
  ROUTE_LIMITS,
  USER_ROUTE_LIMIT,
  paidDailyCap,
  setPaidChargeDelegate,
} = require("./rateLimit");
const {
  SessionAuthError,
  createTokenVerifier,
  createSessionGate,
  verifyWithFirebaseAdmin,
} = require("./userSessionAuth");

const GOOGLE_ID_TOKEN_HEADER = "x-strlix-google-id-token";
const REQUEST_ID_HEADER = "x-strlix-request-id";

function positiveInt(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : fallback;
}

function isPaidRoute(req) {
  return Boolean(req && req.method === "POST" && Object.prototype.hasOwnProperty.call(ROUTE_LIMITS, req.path));
}

const HOSTED_QUOTA_REFUSAL = [
  "STRLIX_USER_SESSION_AUTH=1 cannot serve paid calls on a hosted runtime without shared table state.",
  "A local file is not shared across App Service instances.",
  "WEBSITE_INSTANCE_ID names this process and is not an instance count.",
  "STRLIX_AUTH_QUOTA_SCOPE is not a scale setting.",
  "Set STRLIX_AUTH_TABLE_CONNECTION to the Azure Table connection for this app.",
  "Refusing to start.",
].join(" ");

function tableConnection() {
  return String(process.env.STRLIX_AUTH_TABLE_CONNECTION || "").trim();
}

function tableName() {
  return String(process.env.STRLIX_AUTH_TABLE_NAME || "strlixauth").trim() || "strlixauth";
}

function firebaseAdminLoads() {
  try {
    require.resolve("firebase-admin");
    require("firebase-admin");
    return true;
  } catch (_) {
    return false;
  }
}

function firebaseCredentialsConfigured() {
  const file = String(process.env.GOOGLE_APPLICATION_CREDENTIALS || "").trim();
  if (file && fs.existsSync(file)) return true;
  const email = String(process.env.STRLIX_FIREBASE_CLIENT_EMAIL || "").trim();
  const key = String(process.env.STRLIX_FIREBASE_PRIVATE_KEY || "");
  return Boolean(email && key.trim());
}

function assertSessionAuthReady() {
  const hosted = isHostedRuntime();
  const jwksPath = String(process.env.STRLIX_AUTH_JWKS_PATH || "").trim();
  const projectId = String(process.env.STRLIX_AUTH_PROJECT_ID || "").trim();
  const firebaseProject = String(process.env.STRLIX_FIREBASE_PROJECT_ID || "").trim();
  if (hosted && jwksPath) {
    throw new Error("STRLIX_AUTH_JWKS_PATH cannot be used when session auth is on for a hosted runtime");
  }
  if (hosted && !firebaseProject) {
    throw new Error("STRLIX_FIREBASE_PROJECT_ID is required when STRLIX_USER_SESSION_AUTH=1 on a hosted runtime");
  }
  if (hosted && !firebaseAdminLoads()) {
    throw new Error("firebase-admin is not installed. Session auth on a hosted runtime needs the official Admin SDK.");
  }
  if (hosted && !firebaseCredentialsConfigured()) {
    throw new Error("Firebase Admin credentials are not available. Refusing to start session auth on a hosted runtime.");
  }
  if (hosted && String(process.env.STRLIX_AUTH_STATE_PATH || "").trim()) {
    throw new Error("STRLIX_AUTH_STATE_PATH cannot be used on a hosted runtime. A local file is not a shared quota.");
  }
  if (hosted && !tableConnection()) throw new Error(HOSTED_QUOTA_REFUSAL);
  if (!jwksPath && !firebaseProject) {
    throw new Error(
      "STRLIX_USER_SESSION_AUTH=1 needs STRLIX_AUTH_JWKS_PATH and STRLIX_AUTH_PROJECT_ID, or STRLIX_FIREBASE_PROJECT_ID"
    );
  }
  if (jwksPath && !projectId) {
    throw new Error("STRLIX_AUTH_PROJECT_ID is required with STRLIX_AUTH_JWKS_PATH");
  }
  if (jwksPath && !fs.existsSync(jwksPath)) {
    throw new Error("STRLIX_AUTH_JWKS_PATH does not exist");
  }
  if (firebaseProject && !jwksPath && !firebaseAdminLoads()) {
    throw new Error("firebase-admin is not installed. Refusing session auth without a verifier.");
  }
}

function readJwks(jwksPath) {
  const parsed = JSON.parse(fs.readFileSync(jwksPath, "utf8"));
  return Array.isArray(parsed.keys) ? parsed.keys : [];
}

function openAuthState() {
  const conn = tableConnection();
  if (isHostedRuntime() || conn) return createTableAuthState(conn, tableName());
  const file = String(process.env.STRLIX_AUTH_STATE_PATH || "").trim();
  if (file) return createFileAuthState(file);
  return createMemoryAuthState();
}

async function revocationCheck(store, uid, authTimeSec) {
  try {
    return await store.isRevoked(uid, authTimeSec);
  } catch (err) {
    if (err instanceof SessionAuthError) throw err;
    throw new SessionAuthError("user_auth_unavailable", 503);
  }
}

function buildVerifier(store) {
  const jwksPath = String(process.env.STRLIX_AUTH_JWKS_PATH || "").trim();
  if (jwksPath) {
    const projectId = String(process.env.STRLIX_AUTH_PROJECT_ID || "").trim();
    return createTokenVerifier({
      audience: projectId,
      issuer: `https://securetoken.google.com/${projectId}`,
      keys: () => readJwks(jwksPath),
      resolveKeys: () => readJwks(jwksPath),
      revocation: {
        isRevoked(uid, authTimeSec) {
          return revocationCheck(store, uid, authTimeSec);
        },
      },
    });
  }
  const projectId = String(process.env.STRLIX_FIREBASE_PROJECT_ID || "").trim();
  return async (token) => {
    const admin = require("firebase-admin");
    const user = await verifyWithFirebaseAdmin(token, admin, projectId);
    if (await revocationCheck(store, user.uid, user.authTimeSec)) {
      throw new SessionAuthError("user_auth_revoked", 401);
    }
    return user;
  };
}

function requestIdForCharge(req) {
  const uid = req && req.strlixUser && req.strlixUser.uid;
  const raw = String((req && req.get && req.get(REQUEST_ID_HEADER)) || "").trim();
  if (!uid || !/^[A-Za-z0-9._:-]{8,80}$/.test(raw)) return null;
  return `${uid}|${raw}`;
}

async function chargeFromStore(store, res, nowMs) {
  const req = res && res.req;
  let result;
  try {
    result = await store.chargeGlobal(nowMs, paidDailyCap(), requestIdForCharge(req));
  } catch (_) {
    if (!res.headersSent) res.status(503).json({ error: "user_auth_unavailable" });
    return false;
  }
  if (result.replay) {
    res.status(409).json({ error: "duplicate_request" });
    return false;
  }
  if (!result.ok) {
    console.error(JSON.stringify({ event: "daily_paid_cap_reached", cap: paidDailyCap() }));
    res.status(503).json({ error: "daily_capacity_reached" });
    return false;
  }
  return true;
}

function mountPaidSessionAuth(app, { mailAssist, requireGoogleUser, userRouteGuard }) {
  assertSessionAuthReady();
  const store = openAuthState();
  const gate = createSessionGate({
    verify: buildVerifier(store),
    store,
    limits: () => ({
      capacity: positiveInt(process.env.STRLIX_USER_ROUTE_CAPACITY, USER_ROUTE_LIMIT.capacity),
      perHour: positiveInt(process.env.STRLIX_USER_ROUTE_PER_HOUR, USER_ROUTE_LIMIT.perHour),
    }),
  });
  setPaidChargeDelegate((res, nowMs) => chargeFromStore(store, res, nowMs));

  app.use((req, res, next) => {
    if (isPublicHealth(req) || isPaidRoute(req)) return next();
    return requireAppSecret(req, res, next);
  });
  app.use("/mail", (req, res, next) => {
    if (!mailAssist.isMailAssistEnabled()) return res.status(404).json({ error: "not_found" });
    return next();
  });
  app.use((req, res, next) => {
    if (!isPaidRoute(req)) return next();
    if (process.env.STRLIX_PAID_ROUTES_DISABLED === "1") {
      return res.status(503).json({ error: "paid_routes_disabled" });
    }
    return next();
  });
  app.use((req, res, next) => {
    if (!isPaidRoute(req)) return next();
    return gate.requireUser(req, res, () => gate.limitPaidUser(req, res, next));
  });
  app.use("/mail", (req, res, next) => {
    if (req.method !== "POST" || !["/summarize", "/draft-reply"].includes(req.path)) return next();
    const googleToken = String(req.get(GOOGLE_ID_TOKEN_HEADER) || "").trim();
    const original = req.get.bind(req);
    req.get = (name) => {
      if (String(name).toLowerCase() === "authorization") {
        return googleToken ? `Bearer ${googleToken}` : "";
      }
      return original(name);
    };
    return requireGoogleUser(req, res, () => userRouteGuard(req, res, next));
  });
  return typeof store.ready === "function" ? store.ready() : Promise.resolve();
}

module.exports = {
  GOOGLE_ID_TOKEN_HEADER,
  REQUEST_ID_HEADER,
  HOSTED_QUOTA_REFUSAL,
  assertSessionAuthReady,
  mountPaidSessionAuth,
};
