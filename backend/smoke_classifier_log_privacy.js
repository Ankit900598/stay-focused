/** Offline classifier fallback log privacy smoke; no provider calls. */

process.env.AZURE_OPENAI_ENDPOINT = "https://example.openai.azure.com/";
process.env.AZURE_OPENAI_API_KEY = "local-test-key";
process.env.AZURE_OPENAI_DEPLOYMENT = "pc-lab-terra";
process.env.AZURE_OPENAI_API_VERSION = "2024-08-01-preview";
process.env.AZURE_VISION_EXPERIMENT = "1";

const routing = require("./azureModelRouting");
const originalCompletion = routing.completeJsonChatWithFallback;
let mode = "vision";
let calls = 0;
const sensitiveMarker = "private-screen-message-987654";

routing.completeJsonChatWithFallback = async () => {
  calls += 1;
  if (
    (mode === "vision" && calls === 1) ||
    (["escalation", "ambiguous_block", "ambiguous_allow", "invalid_category_allow", "invalid_category_block", "uncertain_allow"].includes(mode) && calls === 2)
  ) {
    const error = new Error(sensitiveMarker);
    error.status = 429;
    throw error;
  }
  const response = mode === "ambiguous_block" || (mode === "resolved_block" && calls === 1)
    ? { decision: "BLOCK", confidence: 0.95, reasonCategory: "ambiguous", reason: "Unclear" }
    : mode === "ambiguous_allow" || (mode === "resolved_allow" && calls === 1)
      ? { decision: "ALLOW", confidence: 0.95, reasonCategory: "ambiguous", reason: "Unclear" }
    : mode === "invalid_category_allow"
      ? { decision: "ALLOW", confidence: 0.95, reasonCategory: "study_alignd", reason: "Looks aligned" }
    : mode === "invalid_category_block"
      ? { decision: "BLOCK", confidence: 0.95, reasonCategory: "shortformdisallowed", reason: "Looks prohibited" }
    : mode === "resolved_allow"
      ? { decision: "ALLOW", confidence: 0.95, reasonCategory: "study_aligned", reason: "Matches confirmed promise" }
    : mode === "resolved_block"
      ? { decision: "BLOCK", confidence: 0.95, reasonCategory: "short_form_disallowed", reason: "Matches confirmed rule" }
      : mode === "uncertain_allow"
      ? { decision: "ALLOW", confidence: 0.4, reasonCategory: "study_aligned", reason: "Maybe study" }
      : mode === "vision"
        ? { decision: "WARN", confidence: 0.95, reasonCategory: "study_aligned", reason: "Screen is unclear" }
      : { decision: "WARN", confidence: mode === "escalation" ? 0.3 : 0.95, reasonCategory: "ambiguous", reason: "Unclear" };
  return {
    deployment: "pc-lab-terra",
    completion: {
      choices: [{ message: { content: JSON.stringify(response) } }],
    },
  };
};

const { classifyActivity } = require("./classifierService");
routing.completeJsonChatWithFallback = originalCompletion;

async function main() {
  const originalLog = console.log;
  const capturedLogs = [];
  try {
    console.log = (message) => capturedLogs.push(String(message));
    await classifyActivity({ packageName: "com.example.app", screenText: "visible text", goal: "focus", imageJpegBase64: "YQ==" });
    assert(calls === 2, "vision failure falls back to text");
    assert(capturedLogs.some((line) => line.includes("vision_image_failed_closed")), "vision fallback logged");

    mode = "escalation";
    calls = 0;
    await classifyActivity({ packageName: "com.example.app", screenText: "visible text", goal: "focus" });
    assert(calls === 2, "low confidence triggers escalation");
    assert(capturedLogs.some((line) => line.includes("classify_escalation_failed")), "escalation failure logged");

    mode = "ambiguous_block";
    calls = 0;
    const ambiguousBlock = await classifyActivity({ packageName: "com.example.app", screenText: "visible text", goal: "focus" });
    assert(ambiguousBlock.decision === "WARN", "failed second opinion cannot leave ambiguous BLOCK enforceable");
    assert(ambiguousBlock.verificationIncomplete === true, "ambiguous BLOCK reports incomplete verification");

    mode = "ambiguous_allow";
    calls = 0;
    const ambiguousAllow = await classifyActivity({ packageName: "com.example.app", screenText: "visible text", goal: "focus" });
    assert(calls === 2, "ambiguous ALLOW receives a second opinion");
    assert(ambiguousAllow.decision === "WARN", "failed second opinion cannot leave ambiguous ALLOW permissive");
    assert(ambiguousAllow.verificationIncomplete === true, "ambiguous ALLOW reports incomplete verification");

    mode = "invalid_category_allow";
    calls = 0;
    const invalidCategoryAllow = await classifyActivity({ packageName: "com.example.app", screenText: "visible text", goal: "focus" });
    assert(calls === 2, "unknown reason category is treated as uncertain and escalated");
    assert(invalidCategoryAllow.decision === "WARN", "unknown reason category cannot leave ALLOW permissive");
    assert(invalidCategoryAllow.reasonCategory === "ambiguous", "unknown text category normalizes to ambiguous");

    mode = "invalid_category_block";
    calls = 0;
    const invalidCategoryBlock = await classifyActivity({ packageName: "com.example.app", screenText: "visible text", goal: "focus" });
    assert(calls === 2, "invalid BLOCK category is treated as uncertain and escalated");
    assert(invalidCategoryBlock.decision === "WARN", "unknown reason category cannot leave BLOCK enforceable");
    assert(invalidCategoryBlock.verificationIncomplete === true, "invalid BLOCK category reports incomplete verification");

    mode = "resolved_allow";
    calls = 0;
    const resolvedAllow = await classifyActivity({ packageName: "com.example.app", screenText: "visible text", goal: "focus" });
    assert(calls === 2, "ambiguous ALLOW receives a second opinion");
    assert(resolvedAllow.decision === "ALLOW", "clear second opinion may resolve to ALLOW");
    assert(resolvedAllow.verificationIncomplete === false, "clear second opinion closes verification");
    assert(capturedLogs.some((line) => line.includes('"reason":"uncertain_result"')), "escalation log labels an uncertain result accurately");

    mode = "resolved_block";
    calls = 0;
    const resolvedBlock = await classifyActivity({ packageName: "com.example.app", screenText: "visible text", goal: "focus" });
    assert(calls === 2, "ambiguous BLOCK receives a second opinion");
    assert(resolvedBlock.decision === "BLOCK", "resolved second opinion retains a clear BLOCK");
    assert(resolvedBlock.verificationIncomplete === false, "clear second opinion closes verification");

    mode = "uncertain_allow";
    calls = 0;
    const uncertainAllow = await classifyActivity({ packageName: "com.example.app", screenText: "visible text", goal: "focus" });
    assert(uncertainAllow.decision === "WARN", "failed second opinion cannot leave uncertain ALLOW permissive");
    assert(uncertainAllow.verificationIncomplete === true, "uncertain ALLOW reports incomplete verification");
    assert(!capturedLogs.some((line) => line.includes(sensitiveMarker)), "upstream error text excluded from logs");
  } finally {
    console.log = originalLog;
  }
  console.log(JSON.stringify({ ok: true, event: "smoke_classifier_log_privacy" }));
}

function assert(condition, label) {
  if (!condition) throw new Error(`smoke_classifier_log_privacy failed: ${label}`);
}

main().catch((error) => {
  console.error(JSON.stringify({ ok: false, error: error.message }));
  process.exit(1);
});
