/**
 * Abuse/cost guard for paid (model/speech) routes.
 * Default counters live in memory and reset on restart. That remains the
 * phone-test and flag-off path. Session auth installs a delegate so the
 * daily cap can live in the durable store instead.
 * Law stays on the phone; this only caps what one caller can spend.
 */
const { isHostedRuntime } = require("./appAuth");
const { isIP } = require("node:net");

const HOUR_MS = 60 * 60 * 1000;

// capacity = burst; perHour = sustained refill.
const ROUTE_LIMITS = {
  "/compile-promise": { capacity: 10, perHour: 60 },
  "/classify": { capacity: 60, perHour: 1200 },
  "/transcribe-promise": { capacity: 5, perHour: 30 },
  "/research-app": { capacity: 10, perHour: 60 },
  "/draft-focus-promise": { capacity: 10, perHour: 60 },
  "/mail/summarize": { capacity: 10, perHour: 60 },
  "/mail/draft-reply": { capacity: 10, perHour: 60 },
};

// Per verified Google user, on top of the per-app-key bucket above.
const USER_ROUTE_LIMIT = { capacity: 8, perHour: 40 };

const DEFAULT_DAILY_PAID_CALL_CAP = 3000;
const MAX_BUCKETS = 10000;

const buckets = new Map();
let day = null;
let dailyCount = 0;
let paidChargeDelegate = null;

function dailyCap() {
  const n = Number(process.env.STRLIX_DAILY_PAID_CALL_CAP);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : DEFAULT_DAILY_PAID_CALL_CAP;
}

function normalizeIp(value) {
  const address = String(value || "").trim();
  if (isIP(address)) return address.toLowerCase();
  const bracketed = /^\[([^\]]+)\](?::\d{1,5})?$/.exec(address);
  if (bracketed && isIP(bracketed[1]) === 6) return bracketed[1].toLowerCase();
  const ipv4WithPort = /^((?:\d{1,3}\.){3}\d{1,3}):(\d{1,5})$/.exec(address);
  if (ipv4WithPort && isIP(ipv4WithPort[1]) === 4 && Number(ipv4WithPort[2]) <= 65535) {
    return ipv4WithPort[1];
  }
  return null;
}

// App Service overwrites client-ip at its front end. X-Forwarded-For can
// contain caller-supplied hops, so never use it as an identity source here.
function clientIp(req) {
  if (isHostedRuntime()) {
    const platformIp = normalizeIp(req.get && req.get("client-ip"));
    if (platformIp) return platformIp;
  }
  return normalizeIp(req.socket && req.socket.remoteAddress) || "unknown";
}

function take(key, limit, nowMs) {
  let b = buckets.get(key);
  if (!b) {
    if (buckets.size >= MAX_BUCKETS) buckets.delete(buckets.keys().next().value);
    b = { tokens: limit.capacity, at: nowMs };
    buckets.set(key, b);
  }
  const refill = ((nowMs - b.at) / HOUR_MS) * limit.perHour;
  b.tokens = Math.min(limit.capacity, b.tokens + refill);
  b.at = nowMs;
  if (b.tokens >= 1) {
    b.tokens -= 1;
    return { ok: true };
  }
  const retryAfterSec = Math.ceil(((1 - b.tokens) / limit.perHour) * 3600);
  return { ok: false, retryAfterSec };
}

function paidRouteGuard(req, res, next, nowMs = Date.now()) {
  const limit = req.method === "POST" ? ROUTE_LIMITS[req.path] : null;
  if (!limit) return next();

  if (process.env.STRLIX_PAID_ROUTES_DISABLED === "1") {
    return res.status(503).json({ error: "paid_routes_disabled" });
  }

  const caller = req.caller || "anonymous";
  const key = `${caller}|${clientIp(req)}|${req.path}`;
  const result = take(key, limit, nowMs);
  if (!result.ok) {
    console.warn(
      JSON.stringify({ event: "rate_limited", caller, path: req.path, retryAfterSec: result.retryAfterSec })
    );
    res.set("Retry-After", String(result.retryAfterSec));
    return res.status(429).json({ error: "rate_limited" });
  }
  return next();
}

function setPaidChargeDelegate(fn) {
  paidChargeDelegate = typeof fn === "function" ? fn : null;
}

function paidDailyCap() {
  return dailyCap();
}

function chargePaidCall(res, nowMs = Date.now()) {
  if (process.env.STRLIX_PAID_ROUTES_DISABLED === "1") {
    res.status(503).json({ error: "paid_routes_disabled" });
    return false;
  }
  if (paidChargeDelegate) return paidChargeDelegate(res, nowMs);
  const today = new Date(nowMs).toISOString().slice(0, 10);
  if (today !== day) {
    day = today;
    dailyCount = 0;
  }
  if (dailyCount >= dailyCap()) {
    console.error(JSON.stringify({ event: "daily_paid_cap_reached", cap: dailyCap() }));
    res.status(503).json({ error: "daily_capacity_reached" });
    return false;
  }
  dailyCount += 1;
  return true;
}

function userRouteGuard(req, res, next, nowMs = Date.now()) {
  const user = req.googleUser && req.googleUser.userKey;
  if (!user) return res.status(401).json({ error: "user_auth_required" });
  const result = take(`user:${user}|${req.path}`, USER_ROUTE_LIMIT, nowMs);
  if (!result.ok) {
    console.warn(JSON.stringify({ event: "user_rate_limited", path: req.path, retryAfterSec: result.retryAfterSec }));
    res.set("Retry-After", String(result.retryAfterSec));
    return res.status(429).json({ error: "rate_limited" });
  }
  return next();
}

function resetRateLimitsForTests() {
  buckets.clear();
  day = null;
  dailyCount = 0;
}

module.exports = {
  ROUTE_LIMITS,
  USER_ROUTE_LIMIT,
  paidRouteGuard,
  chargePaidCall,
  setPaidChargeDelegate,
  paidDailyCap,
  userRouteGuard,
  clientIp,
  resetRateLimitsForTests,
};
