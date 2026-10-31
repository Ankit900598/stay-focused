/**
 * Local quota, retry, and revocation-floor file.
 *
 * This is one machine's disk. It survives a process restart and can be locked
 * by two processes on that machine. It is not shared across Azure App Service
 * instances. Hosted session auth does not use this file. A full map refuses
 * a new user rather than deleting an active bucket. A bucket may be dropped
 * only after its own refill window has elapsed.
 *
 * Writes go to a temp file, are fsynced, then renamed over the live file.
 * The live file is never deleted first. A crash before the rename keeps the
 * previous counters. Corrupt JSON fails closed and is not replaced with an
 * empty budget. The file stores counters only, never a bearer token.
 *
 * Lock waits are asynchronous. The request path must not spin the event loop.
 */
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const HOUR_MS = 60 * 60 * 1000;
const IDEMPOTENCY_TTL_MS = 10 * 60 * 1000;
const INFLIGHT_MS = 30 * 1000;
const DEFAULT_MAX_BUCKETS = 10000;
let maxBuckets = DEFAULT_MAX_BUCKETS;
const LOCK_WAIT_MS = 3000;
const STALE_LOCK_MS = 5000;
const LOCK_POLL_MS = 15;

let writeBarrier = null;

function setAuthStateWriteBarrierForTests(fn) {
  writeBarrier = typeof fn === "function" ? fn : null;
}

function setAuthStateBucketCapForTests(n) {
  maxBuckets = Number.isFinite(n) && n > 0 ? Math.floor(n) : DEFAULT_MAX_BUCKETS;
}

function refillWindowMs(bounds) {
  return (bounds.capacity / bounds.perHour) * HOUR_MS;
}

function emptyState() {
  return {
    day: null,
    dailyCount: 0,
    charged: {},
    buckets: {},
    revoked: {},
    idempotency: {},
  };
}

function positiveLimit(limit) {
  const capacity = Number(limit && limit.capacity);
  const perHour = Number(limit && limit.perHour);
  return {
    capacity: Number.isFinite(capacity) && capacity > 0 ? capacity : 8,
    perHour: Number.isFinite(perHour) && perHour > 0 ? perHour : 40,
  };
}

function prune(state, nowMs) {
  for (const [key, rec] of Object.entries(state.idempotency || {})) {
    if (!rec || nowMs - rec.at > IDEMPOTENCY_TTL_MS) delete state.idempotency[key];
  }
}

function applyTake(state, uid, routePath, limit, nowMs, requestId) {
  prune(state, nowMs);
  const bounds = positiveLimit(limit);
  const idemKey = requestId ? `${uid}|${routePath}|${requestId}` : null;
  const existing = idemKey ? state.idempotency[idemKey] : null;
  if (existing && existing.state === "inflight") {
    if (nowMs - existing.at < INFLIGHT_MS) return { ok: false, inflight: true, retryAfterSec: 1 };
    return { ok: false, failedAttempt: true };
  }
  if (existing && existing.state === "done" && nowMs - existing.at < IDEMPOTENCY_TTL_MS) {
    if (existing.status >= 500) return { ok: false, failedAttempt: true };
    return { ok: false, duplicate: true };
  }
  const key = `${uid}|${routePath}`;
  let bucket = state.buckets[key];
  if (!bucket) {
    const keys = Object.keys(state.buckets);
    if (keys.length >= maxBuckets) {
      const victim = keys.find((name) => {
        const candidate = state.buckets[name];
        return candidate && typeof candidate.fullAt === "number" && candidate.fullAt <= nowMs;
      });
      if (!victim) throw new Error("auth_state_bucket_capacity");
      delete state.buckets[victim];
    }
    bucket = { tokens: bounds.capacity, at: nowMs, fullAt: nowMs + refillWindowMs(bounds) };
    state.buckets[key] = bucket;
  }
  const refill = ((nowMs - bucket.at) / HOUR_MS) * bounds.perHour;
  bucket.tokens = Math.min(bounds.capacity, bucket.tokens + refill);
  bucket.at = nowMs;
  bucket.fullAt = nowMs + refillWindowMs(bounds);
  if (bucket.tokens < 1) {
    const retryAfterSec = Math.max(1, Math.ceil(((1 - bucket.tokens) / bounds.perHour) * 3600));
    return { ok: false, retryAfterSec };
  }
  bucket.tokens -= 1;
  if (idemKey) state.idempotency[idemKey] = { state: "inflight", at: nowMs };
  return { ok: true };
}

function applyFinish(state, uid, routePath, requestId, statusCode) {
  const idemKey = `${uid}|${routePath}|${requestId}`;
  if (!state.idempotency[idemKey]) return;
  if (statusCode >= 500 || (statusCode >= 200 && statusCode < 300)) {
    state.idempotency[idemKey] = { state: "done", at: Date.now(), status: statusCode };
    return;
  }
  delete state.idempotency[idemKey];
}

function applyCharge(state, nowMs, cap, requestId) {
  const today = new Date(nowMs).toISOString().slice(0, 10);
  if (state.day !== today) {
    state.day = today;
    state.dailyCount = 0;
    state.charged = {};
  }
  if (requestId && state.charged[requestId]) return { ok: true, replay: true };
  if (state.dailyCount >= cap) return { ok: false };
  state.dailyCount += 1;
  if (requestId) state.charged[requestId] = 1;
  return { ok: true };
}

function applyRevoke(state, uid, notBeforeSec) {
  state.revoked[uid] = notBeforeSec;
}

function applyIsRevoked(state, uid, authTimeSec) {
  const floor = state.revoked[uid];
  return floor != null && authTimeSec < floor;
}

function resetState(state) {
  const blank = emptyState();
  for (const key of Object.keys(state)) delete state[key];
  Object.assign(state, blank);
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function withFileLock(lockPath, fn) {
  const start = Date.now();
  for (;;) {
    let handle = null;
    try {
      handle = await fs.promises.open(lockPath, "wx");
    } catch (err) {
      if (err.code !== "EEXIST") throw err;
      try {
        const stat = await fs.promises.stat(lockPath);
        if (Date.now() - stat.mtimeMs > STALE_LOCK_MS) await fs.promises.unlink(lockPath);
      } catch (_) {
        // Another waiter recovered the lock, or it was already removed.
      }
      if (Date.now() - start > LOCK_WAIT_MS) throw new Error("auth_state_lock_timeout");
      await delay(LOCK_POLL_MS);
      continue;
    }
    try {
      return await fn();
    } finally {
      await handle.close().catch(() => {});
      await fs.promises.unlink(lockPath).catch(() => {});
    }
  }
}

function createMemoryAuthState() {
  const state = emptyState();
  function update(mutator) {
    return mutator(state);
  }
  return {
    takeUser: (uid, routePath, limit, nowMs, requestId) => update((s) => applyTake(s, uid, routePath, limit, nowMs, requestId)),
    finishRequest: (uid, routePath, requestId, statusCode) => update((s) => {
      applyFinish(s, uid, routePath, requestId, statusCode);
    }),
    chargeGlobal: (nowMs, cap, requestId) => update((s) => applyCharge(s, nowMs, cap, requestId)),
    revoke: (uid, notBeforeSec) => update((s) => {
      applyRevoke(s, uid, notBeforeSec);
    }),
    isRevoked: (uid, authTimeSec) => update((s) => applyIsRevoked(s, uid, authTimeSec)),
    reset: () => update((s) => {
      resetState(s);
    }),
  };
}

function ensureStateFile(file) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  let handle;
  try {
    handle = fs.openSync(file, "wx", 0o600);
  } catch (err) {
    if (err.code !== "EEXIST") throw err;
    return;
  }
  try {
    fs.writeFileSync(handle, JSON.stringify(emptyState()));
  } finally {
    fs.closeSync(handle);
  }
}

function createFileAuthState(filePath) {
  const file = path.resolve(String(filePath));
  const lockPath = `${file}.lock`;
  ensureStateFile(file);

  async function read() {
    const raw = await fs.promises.readFile(file, "utf8");
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (_) {
      throw new Error("auth_state_corrupt");
    }
    if (!parsed || typeof parsed !== "object" || !parsed.buckets || typeof parsed.buckets !== "object" || !parsed.revoked) {
      throw new Error("auth_state_corrupt");
    }
    parsed.charged = parsed.charged || {};
    parsed.idempotency = parsed.idempotency || {};
    return parsed;
  }

  async function write(state) {
    const tmp = `${file}.${process.pid}.${crypto.randomBytes(4).toString("hex")}.tmp`;
    const handle = await fs.promises.open(tmp, "w", 0o600);
    try {
      await handle.writeFile(JSON.stringify(state));
      await handle.sync();
    } finally {
      await handle.close();
    }
    if (writeBarrier) await writeBarrier({ tmp, dest: file });
    let lastError = null;
    for (let attempt = 0; attempt < 8; attempt += 1) {
      try {
        await fs.promises.rename(tmp, file);
        return;
      } catch (err) {
        lastError = err;
        if (!["EPERM", "EBUSY", "EACCES"].includes(err && err.code)) throw err;
        await delay(15);
      }
    }
    throw lastError;
  }

  function update(mutator, { writeBack = true } = {}) {
    return withFileLock(lockPath, async () => {
      const state = await read();
      const result = mutator(state);
      if (writeBack) await write(state);
      return result;
    });
  }

  return {
    takeUser: (uid, routePath, limit, nowMs, requestId) => update((s) => applyTake(s, uid, routePath, limit, nowMs, requestId)),
    finishRequest: (uid, routePath, requestId, statusCode) => update((s) => {
      applyFinish(s, uid, routePath, requestId, statusCode);
    }),
    chargeGlobal: (nowMs, cap, requestId) => update((s) => applyCharge(s, nowMs, cap, requestId)),
    revoke: (uid, notBeforeSec) => update((s) => {
      applyRevoke(s, uid, notBeforeSec);
    }),
    isRevoked: (uid, authTimeSec) => update((s) => applyIsRevoked(s, uid, authTimeSec), { writeBack: false }),
    reset: () => update((s) => {
      resetState(s);
    }),
  };
}

module.exports = {
  createMemoryAuthState,
  createFileAuthState,
  setAuthStateWriteBarrierForTests,
  setAuthStateBucketCapForTests,
};
