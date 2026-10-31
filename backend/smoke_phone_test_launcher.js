const assert = require("assert");
const { spawnSync } = require("child_process");

const inspect = `
process.env.STRLIX_SERVER_NO_LISTEN = "1";
require("./start-phone-test");
const auth = require("./appAuth");
const compiler = require("./promiseCompilerService");
console.log("PHONE_TEST_STATE=" + JSON.stringify({ hosted: auth.isHostedRuntime(), keys: auth.acceptedKeys().length, promptVersion: compiler.PROMPT_VERSION, scopeContract: compiler.scopeContractFor(compiler.PROMPT_VERSION), paidRoutesDisabled: process.env.STRLIX_PAID_ROUTES_DISABLED }));
const app = require("./server").app;
const server = app.listen(0, "127.0.0.1", async () => {
  try {
    const base = "http://127.0.0.1:" + server.address().port;
    const health = await fetch(base + "/health");
    const healthBody = await health.json();
    const options = { method: "POST", headers: { "content-type": "application/json" }, body: "{}" };
    const classify = await fetch(base + "/classify", options);
    const compile = await fetch(base + "/compile-promise", options);
    console.log("PHONE_TEST_ROUTES=" + JSON.stringify({ health: health.status, classify: classify.status, compile: compile.status, hosted: healthBody.hosted, appSecretRequired: healthBody.appSecretRequired }));
  } catch (error) {
    process.exitCode = 1;
    console.error(error);
  } finally {
    server.close();
  }
});
`;

const local = spawnSync(process.execPath, ["-e", inspect], {
  cwd: __dirname,
  env: {
    ...process.env,
    STRLIX_BACKEND_APP_SECRET: "test-secret",
    STRLIX_BACKEND_APP_SECRETS: "tester:other-secret",
    STRLIX_REQUIRE_APP_SECRET: "0",
    WEBSITE_HOSTNAME: "",
    PROMISE_COMPILER_PROMPT_VERSION: "promise_compiler_v07",
    STRLIX_PAID_ROUTES_DISABLED: "0",
    STRLIX_PHONE_TEST_PAID_CALLS: "",
  },
  encoding: "utf8",
});

assert.strictEqual(local.status, 0, local.stderr);
assert.match(local.stdout, /PHONE_TEST_STATE=\{"hosted":false,"keys":0,"promptVersion":"promise_compiler_v09","scopeContract":"v1","paidRoutesDisabled":"1"\}/);
assert.match(local.stdout, /PHONE_TEST_ROUTES=\{"health":200,"classify":503,"compile":503,"hosted":false,"appSecretRequired":false\}/);

const optedIn = spawnSync(process.execPath, ["-e", inspect], {
  cwd: __dirname,
  env: {
    ...process.env,
    WEBSITE_HOSTNAME: "",
    STRLIX_REQUIRE_APP_SECRET: "0",
    STRLIX_PAID_ROUTES_DISABLED: "1",
    STRLIX_PHONE_TEST_PAID_CALLS: "1",
  },
  encoding: "utf8",
});

assert.strictEqual(optedIn.status, 0, optedIn.stderr);
assert.match(optedIn.stdout, /PHONE_TEST_STATE=\{"hosted":false,"keys":0,"promptVersion":"promise_compiler_v09","scopeContract":"v1","paidRoutesDisabled":"0"\}/);
assert.match(optedIn.stdout, /PHONE_TEST_ROUTES=\{"health":200,"classify":400,"compile":400,"hosted":false,"appSecretRequired":false\}/);

const hosted = spawnSync(process.execPath, ["-e", inspect], {
  cwd: __dirname,
  env: { ...process.env, WEBSITE_HOSTNAME: "unit-test.example" },
  encoding: "utf8",
});

assert.notStrictEqual(hosted.status, 0);
assert.match(hosted.stderr, /Phone-test backend must run only on an unhosted development machine/);

console.log("ASSERTIONS_PASSED=phone_test_launcher");
