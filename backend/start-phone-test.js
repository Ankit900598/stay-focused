if (process.env.WEBSITE_HOSTNAME || process.env.STRLIX_REQUIRE_APP_SECRET === "1") {
  throw new Error("Phone-test backend must run only on an unhosted development machine");
}

process.env.STRLIX_BACKEND_APP_SECRET = "";
process.env.STRLIX_BACKEND_APP_SECRETS = "";
process.env.STRLIX_REQUIRE_APP_SECRET = "0";
process.env.WEBSITE_HOSTNAME = "";
// Phone tests keep the shared-key / local-open path. Session auth stays off
// even if the parent shell exported the staging flag.
delete process.env.STRLIX_USER_SESSION_AUTH;
delete process.env.STRLIX_FIREBASE_PROJECT_ID;
delete process.env.STRLIX_AUTH_JWKS_PATH;
delete process.env.STRLIX_AUTH_PROJECT_ID;
delete process.env.STRLIX_AUTH_STATE_PATH;
delete process.env.STRLIX_AUTH_QUOTA_SCOPE;
process.env.PROMISE_COMPILER_PROMPT_VERSION = "promise_compiler_v09";
process.env.STRLIX_PAID_ROUTES_DISABLED =
  process.env.STRLIX_PHONE_TEST_PAID_CALLS === "1" ? "0" : "1";

require("./server");
