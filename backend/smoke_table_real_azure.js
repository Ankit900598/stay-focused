/**
 * Real Azure Table multi-process smoke against existing ststrlixagentin / strlixauthstate.
 * Uses Azure AD (DefaultAzureCredential). Shared-key access is disabled on this account.
 *
 *   node backend/smoke_table_real_azure.js
 *
 * Does NOT flip hosted STRLIX_USER_SESSION_AUTH. Does NOT create storage/compute.
 * Never prints connection strings, account keys, or tokens.
 */
"use strict";

const assert = require("assert");
const { spawn } = require("child_process");
const { createTableAuthState } = require("./tableAuthState");

const ENDPOINT = String(process.env.STRLIX_AUTH_TABLE_ENDPOINT || "https://ststrlixagentin.table.core.windows.net").trim();
const TABLE = String(process.env.STRLIX_AUTH_TABLE_NAME || "strlixauthstate").trim();
const RUN = `real${Date.now().toString(36)}`;

if (process.argv[2] === "worker") {
  worker(process.argv[3]).catch((err) => {
    process.stderr.write(`${err && err.code ? err.code : "worker_failed"}\n`);
    process.exit(1);
  });
} else {
  main().catch((err) => {
    const msg = String((err && err.stack) || err).slice(0, 400);
    process.stderr.write(`${msg}\n`);
    process.exit(1);
  });
}

function spawnWorker(mode, args, env) {
  const child = spawn(process.execPath, [__filename, "worker", mode, ...args], {
    env: { ...process.env, ...env, STRLIX_AUTH_TABLE_ENDPOINT: ENDPOINT, STRLIX_AUTH_TABLE_NAME: TABLE },
    windowsHide: true,
  });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (c) => { stdout += c.toString(); });
  child.stderr.on("data", (c) => { stderr += c.toString(); });
  child.collected = () => ({ stdout, stderr });
  return child;
}

function exitCode(child, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error("worker_timeout"));
    }, timeoutMs);
    child.once("exit", (code) => {
      clearTimeout(timer);
      resolve(code);
    });
  });
}

async function worker(mode) {
  const endpoint = process.env.STRLIX_AUTH_TABLE_ENDPOINT;
  const table = process.env.STRLIX_AUTH_TABLE_NAME;
  const store = createTableAuthState(endpoint, table);
  if (mode === "take") {
    const uid = process.argv[4];
    const now = Number(process.argv[5]);
    const result = await store.takeUser(uid, "/classify", { capacity: 1, perHour: 1 }, now, null);
    process.exit(result.ok ? 0 : 3);
  }
  if (mode === "takeN") {
    const uid = process.argv[4];
    const now = Number(process.argv[5]);
    const cap = Number(process.argv[6]);
    const result = await store.takeUser(uid, "/classify", { capacity: cap, perHour: cap }, now, null);
    process.stdout.write(result.ok ? "ok\n" : "no\n");
    process.exit(0);
  }
  throw new Error("unknown_worker");
}

async function main() {
  assert.ok(/^https:\/\/ststrlixagentin\.table\.core\.windows\.net\/?$/i.test(ENDPOINT.replace(/\/$/, "") + "/") || ENDPOINT.includes("ststrlixagentin.table.core.windows.net"));
  assert.strictEqual(TABLE, "strlixauthstate");

  const logs = [];
  const children = [];
  const store = createTableAuthState(ENDPOINT, TABLE);

  // Fail-closed: bad endpoint refuses
  const dead = createTableAuthState("https://ststrlixagentin-nope.table.core.windows.net", TABLE);
  await assert.rejects(() => dead.takeUser(`${RUN}-dead`, "/classify", { capacity: 1, perHour: 1 }, Date.now(), null), /auth_state_unavailable|unavailable|Refusing/);
  logs.push("dead_ok");

  const now = Date.now();
  const raceUid = `${RUN}-race`;

  // Two local processes contend for capacity=1
  const race = await Promise.all([0, 1].map(() => {
    const child = spawnWorker("take", [raceUid, String(now)], {});
    children.push(child);
    return exitCode(child, 45000);
  }));
  assert.deepStrictEqual(race.slice().sort(), [0, 3], `race codes=${JSON.stringify(race)}`);

  const again = spawnWorker("take", [raceUid, String(now)], {});
  children.push(again);
  assert.strictEqual(await exitCode(again, 45000), 3);

  // Exact capacity under multi-process contention (capacity 2 → exactly 2 ok)
  const capUid = `${RUN}-cap`;
  const capNow = Date.now();
  const attempts = await Promise.all([0, 1, 2, 3].map(() => {
    const child = spawnWorker("takeN", [capUid, String(capNow), "2"], {});
    children.push(child);
    return exitCode(child, 45000).then((code) => {
      const out = child.collected().stdout.trim();
      return { code, out };
    });
  }));
  const oks = attempts.filter((a) => a.out === "ok").length;
  const nos = attempts.filter((a) => a.out === "no").length;
  assert.strictEqual(oks, 2, `expected 2 ok got ${oks} ${JSON.stringify(attempts)}`);
  assert.strictEqual(nos, 2, `expected 2 no got ${nos}`);

  // Cleanup race/cap entities via store.reset if available; else leave partitioned by RUN
  if (typeof store.reset === "function") {
    await store.reset();
  }

  const haystack = [
    ...logs,
    ...children.map((c) => `${c.collected().stdout}\n${c.collected().stderr}`),
  ].join("\n");
  assert.strictEqual(/AccountKey=/i.test(haystack), false);
  assert.strictEqual(/DefaultEndpointsProtocol=/i.test(haystack), false);
  assert.strictEqual(/Bearer\s+[A-Za-z0-9\-_]+\./i.test(haystack), false);

  process.stdout.write(
    [
      "TABLE_REAL_AZURE_OK",
      `account=ststrlixagentin`,
      `table=${TABLE}`,
      `auth=AzureAD_DefaultAzureCredential`,
      `race=1of2`,
      `capacity=2of4`,
      `run=${RUN}`,
    ].join("\n") + "\n"
  );
}
