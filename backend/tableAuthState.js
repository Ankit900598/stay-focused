/**
 * Shared quota state for more than one server process.
 *
 * Azure Table entities are updated with the entity ETag (If-Match). Two
 * App Service instances cannot both commit a stale counter. A lost response
 * is retried by reading again, not by repeating a blind increment.
 *
 * This is not a local file and not the app-wide secret. If the table cannot
 * be read or written, callers get an error and must deny the paid call.
 * The client does not log the connection string, tokens, or entity bodies.
 *
 * One global-day entity serializes the daily cap. That is enough for this
 * app's cap. It is not a high-QPS global budget service.
 */
const HOUR_MS = 60 * 60 * 1000;
const IDEMPOTENCY_TTL_MS = 10 * 60 * 1000;
const INFLIGHT_MS = 30 * 1000;
const TABLE_TIMEOUT_MS = 2500;

function positiveLimit(limit) {
  const capacity = Number(limit && limit.capacity);
  const perHour = Number(limit && limit.perHour);
  return {
    capacity: Number.isFinite(capacity) && capacity > 0 ? capacity : 8,
    perHour: Number.isFinite(perHour) && perHour > 0 ? perHour : 40,
  };
}

function statusOf(err) {
  const status = Number(err && (err.statusCode || err.status));
  return Number.isFinite(status) ? status : 0;
}

function unavailable(err) {
  const status = statusOf(err);
  const wrapped = new Error(status ? `auth_state_unavailable ${status}` : "auth_state_unavailable");
  wrapped.code = "auth_state_unavailable";
  return wrapped;
}

function safeKey(value) {
  return Buffer.from(String(value), "utf8").toString("base64url");
}

function utcDay(nowMs) {
  return new Date(nowMs).toISOString().slice(0, 10);
}

function finiteNumber(value, label) {
  const n = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(n)) throw new Error(`auth_state_corrupt ${label}`);
  return n;
}

function loopbackHttp(connectionString) {
  const match = /(?:^|;)TableEndpoint=([^;]+)/i.exec(String(connectionString || ""));
  if (!match) return false;
  let url;
  try {
    url = new URL(match[1]);
  } catch (_) {
    return false;
  }
  const host = url.hostname.replace(/^\[|\]$/g, "");
  return url.protocol === "http:" && (host === "127.0.0.1" || host === "localhost" || host === "::1");
}

function tableClientOptions(connectionString) {
  return {
    retryOptions: { maxRetries: 0 },
    allowInsecureConnection: loopbackHttp(connectionString),
  };
}

function isTableEndpoint(value) {
  const raw = String(value || "").trim();
  if (!/^https:\/\//i.test(raw)) return false;
  try {
    const url = new URL(raw);
    return /\.table\.core\.windows\.net$/i.test(url.hostname);
  } catch (_) {
    return false;
  }
}

function createTableAuthState(connectionString, tableName = "strlixauth") {
  const { TableClient } = require("@azure/data-tables");
  const name = String(tableName || "strlixauth");
  if (!/^[A-Za-z][A-Za-z0-9]{2,62}$/.test(name)) {
    throw new Error("STRLIX_AUTH_TABLE_NAME is not a valid Azure table name");
  }
  let client;
  try {
    const source = String(connectionString || "").trim();
    if (isTableEndpoint(source)) {
      // Shared-key disabled accounts (allowSharedKeyAccess=false): Azure AD via ADC.
      const { DefaultAzureCredential } = require("@azure/identity");
      const endpoint = source.replace(/\/$/, "");
      client = new TableClient(endpoint, name, new DefaultAzureCredential(), {
        retryOptions: { maxRetries: 0 },
      });
    } else {
      client = TableClient.fromConnectionString(source, name, tableClientOptions(source));
    }
  } catch (_) {
    client = null;
  }
  let readyPromise = null;

  function signal() {
    return AbortSignal.timeout(TABLE_TIMEOUT_MS);
  }

  async function ready() {
    if (!client) return Promise.reject(new Error("Shared auth state is unavailable. Refusing to start."));
    if (!readyPromise) {
      readyPromise = client.createTable({ abortSignal: signal() }).catch((err) => {
        if (statusOf(err) === 409) return null;
        throw new Error("Shared auth state is unavailable. Refusing to start.");
      });
    }
    return readyPromise;
  }

  async function getEntity(partitionKey, rowKey) {
    try {
      return await client.getEntity(partitionKey, rowKey, { abortSignal: signal() });
    } catch (err) {
      if (statusOf(err) === 404) return null;
      throw unavailable(err);
    }
  }

  async function mutate(partitionKey, rowKey, change) {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const current = await getEntity(partitionKey, rowKey);
      const next = change(current);
      if (!next.write) return next.result;
      const entity = { partitionKey, rowKey, ...next.fields };
      try {
        if (!current) {
          await client.createEntity(entity, { abortSignal: signal() });
        } else {
          await client.updateEntity(entity, "Replace", { etag: current.etag, abortSignal: signal() });
        }
        return next.result;
      } catch (err) {
        const status = statusOf(err);
        if (status === 409 || status === 412) continue;
        throw unavailable(err);
      }
    }
    throw new Error("auth_state_conflict");
  }

  async function remove(partitionKey, rowKey, etag) {
    try {
      await client.deleteEntity(partitionKey, rowKey, { etag, abortSignal: signal() });
    } catch (err) {
      if (statusOf(err) === 404) return;
      throw unavailable(err);
    }
  }

  function idemKeys(uid, routePath, requestId) {
    return {
      partitionKey: `i.${safeKey(uid)}`,
      rowKey: safeKey(`${routePath}|${requestId}`),
    };
  }

  function bucketKeys(uid, routePath) {
    return { partitionKey: `b.${safeKey(uid)}`, rowKey: safeKey(routePath) };
  }

  async function takeUser(uid, routePath, limit, nowMs, requestId) {
    await ready();
    const bounds = positiveLimit(limit);
    const idem = requestId ? idemKeys(uid, routePath, requestId) : null;
    if (idem) {
      const decision = await mutate(idem.partitionKey, idem.rowKey, (current) => {
        if (current && current.state === "inflight") {
          if (nowMs - finiteNumber(current.at, "idem") < INFLIGHT_MS) {
            return { write: false, result: { claim: false, outcome: { ok: false, inflight: true, retryAfterSec: 1 } } };
          }
          return { write: false, result: { claim: false, outcome: { ok: false, failedAttempt: true } } };
        }
        if (current && current.state === "done" && nowMs - finiteNumber(current.at, "idem") < IDEMPOTENCY_TTL_MS) {
          const status = finiteNumber(current.status, "idem");
          if (status >= 500) return { write: false, result: { claim: false, outcome: { ok: false, failedAttempt: true } } };
          return { write: false, result: { claim: false, outcome: { ok: false, duplicate: true } } };
        }
        return {
          write: true,
          fields: { state: "inflight", at: nowMs, status: 0 },
          result: { claim: true },
        };
      });
      if (!decision.claim) return decision.outcome;
    }

    const bucket = bucketKeys(uid, routePath);
    let spent;
    try {
      spent = await mutate(bucket.partitionKey, bucket.rowKey, (current) => {
        let tokens = bounds.capacity;
        let at = nowMs;
        if (current) {
          tokens = finiteNumber(current.tokens, "bucket");
          at = finiteNumber(current.at, "bucket");
          const refill = ((nowMs - at) / HOUR_MS) * bounds.perHour;
          tokens = Math.min(bounds.capacity, tokens + refill);
          at = nowMs;
        }
        if (tokens < 1) {
          const retryAfterSec = Math.max(1, Math.ceil(((1 - tokens) / bounds.perHour) * 3600));
          return { write: false, result: { ok: false, retryAfterSec } };
        }
        return {
          write: true,
          fields: { tokens: tokens - 1, at },
          result: { ok: true },
        };
      });
    } catch (err) {
      if (idem) await remove(idem.partitionKey, idem.rowKey, "*").catch(() => {});
      throw err;
    }
    if (!spent.ok && idem) {
      await remove(idem.partitionKey, idem.rowKey, "*");
    }
    return spent;
  }

  async function finishRequest(uid, routePath, requestId, statusCode) {
    await ready();
    const idem = idemKeys(uid, routePath, requestId);
    const current = await getEntity(idem.partitionKey, idem.rowKey);
    if (!current) return;
    if (statusCode >= 500 || (statusCode >= 200 && statusCode < 300)) {
      await mutate(idem.partitionKey, idem.rowKey, () => ({
        write: true,
        fields: { state: "done", at: Date.now(), status: statusCode },
        result: null,
      }));
      return;
    }
    await remove(idem.partitionKey, idem.rowKey, current.etag || "*");
  }

  async function chargeGlobal(nowMs, cap, requestId) {
    await ready();
    const limit = Number(cap);
    if (!Number.isFinite(limit) || limit <= 0) throw new Error("auth_state_corrupt cap");
    let createdMarker = false;
    const marker = requestId
      ? { partitionKey: `c.${safeKey(requestId)}`, rowKey: "once" }
      : null;
    if (marker) {
      try {
        await client.createEntity({ ...marker, marker: 1 }, { abortSignal: signal() });
        createdMarker = true;
      } catch (err) {
        if (statusOf(err) === 409) return { ok: true, replay: true };
        throw unavailable(err);
      }
    }
    try {
      const charged = await mutate("g.day", "count", (current) => {
        const today = utcDay(nowMs);
        if (!current || current.day !== today) {
          return { write: true, fields: { day: today, count: 1 }, result: { ok: true } };
        }
        const count = finiteNumber(current.count, "day");
        if (count >= limit) return { write: false, result: { ok: false } };
        return { write: true, fields: { day: today, count: count + 1 }, result: { ok: true } };
      });
      if (!charged.ok && createdMarker) await remove(marker.partitionKey, marker.rowKey, "*");
      return charged;
    } catch (err) {
      if (createdMarker) await remove(marker.partitionKey, marker.rowKey, "*").catch(() => {});
      throw err;
    }
  }

  async function revoke(uid, notBeforeSec) {
    await ready();
    const floor = finiteNumber(notBeforeSec, "revoke");
    await mutate(`r.${safeKey(uid)}`, "floor", (current) => {
      const previous = current ? finiteNumber(current.notBefore, "revoke") : floor;
      return {
        write: true,
        fields: { notBefore: Math.max(previous, floor) },
        result: null,
      };
    });
  }

  async function isRevoked(uid, authTimeSec) {
    await ready();
    const current = await getEntity(`r.${safeKey(uid)}`, "floor");
    if (!current) return false;
    const floor = finiteNumber(current.notBefore, "revoke");
    const authTime = finiteNumber(authTimeSec, "revoke");
    return authTime < floor;
  }

  async function readGlobalCount() {
    await ready();
    const current = await getEntity("g.day", "count");
    if (!current) return null;
    return { day: String(current.day || ""), count: finiteNumber(current.count, "day") };
  }

  async function reset() {
    await ready();
    const rows = [];
    for await (const entity of client.listEntities({ abortSignal: signal() })) rows.push(entity);
    for (const entity of rows) {
      await remove(entity.partitionKey, entity.rowKey, "*");
    }
  }

  return {
    ready,
    takeUser,
    finishRequest,
    chargeGlobal,
    revoke,
    isRevoked,
    readGlobalCount,
    reset,
  };
}

module.exports = { createTableAuthState, tableClientOptions };
