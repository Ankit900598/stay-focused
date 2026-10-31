const fs = require("fs");
const path = require("path");
const {
  DEFAULT_VISION_DEPLOYMENT,
  FALLBACK_OPENAI_MODEL,
  hasAzureConfig,
  getClassifierProvider,
  getPromiseCompilerProvider,
  getVisionDeployment,
  getVisionDeploymentChain,
  getBestDeployment,
  getClassifierDeployment,
  getClassifierDeploymentChain,
  createClassifierClient,
  completeJsonChatWithFallback,
} = require("./azureModelRouting");
const { resolvePromptsDir } = require("./promptPaths");

const PROMPT_VERSION = "v04";
const PROMPT_PATH = path.join(resolvePromptsDir(), "classifier_v04.txt");
const ESCALATION_CONFIDENCE_THRESHOLD = 0.75;
const TEXT_CLASSIFIER_CATEGORIES = new Set([
  "study_aligned",
  "user_allowed_entertainment",
  "limit_not_reached",
  "limit_reached",
  "adult_content",
  "short_form_disallowed",
  "strict_monk_mode",
  "install_disallowed",
  "tamper_attempt",
  "ambiguous",
  "safe_app",
  "emergency_or_system",
  "neutral_navigation",
  "social_feed",
  "gaming",
  "shopping",
  "install_page_productivity",
]);
const VISION_ADVISORY_CATEGORIES = new Set([
  "likely_short_form",
  "likely_long_form",
  "likely_movie",
  "unknown",
  "adult_signal",
]);
function getTextClassifierCategories() {
  return [...TEXT_CLASSIFIER_CATEGORIES].sort();
}
function getVisionAdvisoryCategories() {
  return [...VISION_ADVISORY_CATEGORIES].sort();
}

const VISION_SYSTEM_ADDENDUM = [
  "If an image is provided, treat it as advisory counsel only.",
  `reasonCategory MUST be one of: ${[...VISION_ADVISORY_CATEGORIES].join(", ")}.`,
  "Do not invent a numeric duration. Clock BLOCK/ALLOW is local phone law.",
  "Do not override emergency, settings, SMS, OTP, or banking screens.",
  "Blank/unknown OTT players with no readable timer → unknown or likely_movie/likely_short_form from visible UI only.",
  "Also set whatOnScreen to one short English sentence of what is visibly on screen (title/UI only). Not a duration. Not audio.",
].join(" ");

let cachedPrompt = null;

function loadClassifierPrompt() {
  if (cachedPrompt) {
    return cachedPrompt;
  }
  cachedPrompt = fs.readFileSync(PROMPT_PATH, "utf8");
  return cachedPrompt;
}

function isVisionExperimentEnabled() {
  return process.env.AZURE_VISION_EXPERIMENT === "1";
}

function extractImageBase64(body) {
  const raw = body.imageJpegBase64 || body.imageBase64 || body.image;
  if (typeof raw !== "string" || !raw.trim()) {
    return null;
  }
  return raw.replace(/^data:image\/\w+;base64,/, "").trim();
}

function buildClassifierUserPayload(body) {
  const packageName = body.packageName;
  const screenText = body.screenText;
  const goal = body.goal;

  return {
    packageName,
    appLabel:
      typeof body.appLabel === "string" && body.appLabel.trim()
        ? body.appLabel.trim()
        : packageName,
    screenText,
    userGoal: goal,
    strictnessLevel:
      typeof body.strictnessLevel === "string"
        ? body.strictnessLevel.toUpperCase()
        : "SMART",
    activeGuardrails: Array.isArray(body.activeGuardrails)
      ? body.activeGuardrails
      : [],
    commitmentType:
      typeof body.commitmentType === "string"
        ? body.commitmentType
        : "focus_session",
    sessionCounters:
      body.sessionCounters && typeof body.sessionCounters === "object"
        ? body.sessionCounters
        : null,
    limitState:
      typeof body.limitState === "string" ? body.limitState : null,
  };
}

function parseClassifierJson(raw) {
  const trimmed = raw.trim();
  const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)\s*```/i);
  const jsonText = fenced ? fenced[1].trim() : trimmed;
  return JSON.parse(jsonText);
}

function normalizeClassifierResult(
  parsed,
  allowedReasonCategories = TEXT_CLASSIFIER_CATEGORIES,
  invalidReasonCategory = "ambiguous"
) {
  const decision = String(parsed.decision || "WARN").toUpperCase();
  const validDecisions = new Set(["ALLOW", "WARN", "BLOCK", "LOCK"]);

  if (!validDecisions.has(decision)) {
    throw new Error("Invalid decision from model");
  }

  const confidence = parsed.confidence;
  if (typeof confidence !== "number" || !Number.isFinite(confidence) || confidence < 0 || confidence > 1) {
    throw new Error("Invalid confidence from model");
  }

  const reason =
    typeof parsed.reason === "string" ? parsed.reason : "No reason provided.";

  const categoryCandidate =
    typeof parsed.reasonCategory === "string"
      ? parsed.reasonCategory.trim().toLowerCase()
      : "ambiguous";
  const reasonCategory = allowedReasonCategories.has(categoryCandidate)
    ? categoryCandidate
    : invalidReasonCategory;

  const whatOnScreen =
    typeof parsed.whatOnScreen === "string"
      ? parsed.whatOnScreen.trim().slice(0, 160)
      : "";

  return { decision, confidence, reason, reasonCategory, whatOnScreen };
}

function shouldEscalate(confidence, reasonCategory) {
  return (
    confidence < ESCALATION_CONFIDENCE_THRESHOLD ||
    reasonCategory === "ambiguous" ||
    reasonCategory === "unknown"
  );
}

function redactForLog(text, maxLen) {
  if (typeof text !== "string") {
    return { length: 0, preview: "" };
  }
  const collapsed = text.replace(/\s+/g, " ").trim();
  return {
    length: collapsed.length,
    preview:
      collapsed.length <= maxLen
        ? collapsed
        : `${collapsed.slice(0, maxLen)}…`,
  };
}

function logClassifyEvent({
  provider,
  deployment,
  latencyMs,
  decision,
  reasonCategory,
  confidence,
  wouldEscalate,
  packageName,
  goal,
  screenText,
}) {
  const goalSafe = redactForLog(goal, 60);
  const screenSafe = redactForLog(screenText, 40);

  console.log(
    JSON.stringify({
      event: "classify",
      provider,
      deployment,
      promptVersion: PROMPT_VERSION,
      latencyMs: Math.round(latencyMs),
      decision,
      reasonCategory,
      confidence: Number(confidence.toFixed(3)),
      would_escalate: wouldEscalate,
      packageName,
      goalLength: goalSafe.length,
      screenTextLength: screenSafe.length,
    })
  );
}

async function classifyActivity(body) {
  const provider = getClassifierProvider();
  if (!provider) {
    const err = new Error(
      "No classifier configured: set Azure OpenAI env vars or OPENAI_API_KEY"
    );
    err.statusCode = 500;
    throw err;
  }

  const image = extractImageBase64(body);
  let usedImage = false;
  let imageRejected = false;
  let userContent = JSON.stringify(buildClassifierUserPayload(body));
  let systemContent = loadClassifierPrompt();
  let deployments = getClassifierDeploymentChain();

  if (image) {
    if (!isVisionExperimentEnabled()) {
      imageRejected = true;
      console.log(
        JSON.stringify({
          event: "vision_image_rejected",
          reason: "AZURE_VISION_EXPERIMENT_off",
          // never log the image
          imageChars: image.length,
        })
      );
    } else if (provider.kind === "azure") {
      systemContent = `${loadClassifierPrompt()}\n${VISION_SYSTEM_ADDENDUM}`;
      userContent = [
        { type: "text", text: JSON.stringify(buildClassifierUserPayload(body)) },
        {
          type: "image_url",
          image_url: { url: `data:image/jpeg;base64,${image}` },
        },
      ];
      usedImage = true;
      deployments = getVisionDeploymentChain();
    } else {
      imageRejected = true;
      console.log(
        JSON.stringify({
          event: "vision_image_rejected",
          reason: "provider_not_azure",
          imageChars: image.length,
        })
      );
    }
  }

  const userPayload = buildClassifierUserPayload(body);
  const started = Date.now();
  let completion;
  let deploymentUsed = provider.deployment;
  try {
    const first = await completeJsonChatWithFallback({
      provider,
      deployments,
      maxTokens: 800,
      messages: [
        { role: "system", content: systemContent },
        { role: "user", content: userContent },
      ],
    });
    completion = first.completion;
    deploymentUsed = first.deployment;
  } catch (visionCallErr) {
    if (usedImage) {
      console.log(
        JSON.stringify({
          event: "vision_image_failed_closed",
          upstreamStatus: typeof visionCallErr.status === "number" ? visionCallErr.status : null,
        })
      );
      usedImage = false;
      imageRejected = true;
      const textPass = await completeJsonChatWithFallback({
        provider,
        deployments: getClassifierDeploymentChain(),
        maxTokens: 300,
        messages: [
          { role: "system", content: loadClassifierPrompt() },
          { role: "user", content: JSON.stringify(userPayload) },
        ],
      });
      completion = textPass.completion;
      deploymentUsed = textPass.deployment;
    } else {
      throw visionCallErr;
    }
  }
  const latencyMs = Date.now() - started;

  const raw = completion.choices[0]?.message?.content;
  if (!raw) {
    const err = new Error("Empty model response");
    err.statusCode = 502;
    throw err;
  }

  let parsed;
  try {
    parsed = parseClassifierJson(raw);
  } catch (_parseErr) {
    const err = new Error("Model did not return valid JSON");
    err.statusCode = 502;
    err.raw = raw;
    throw err;
  }

  const normalized = normalizeClassifierResult(
    parsed,
    usedImage ? VISION_ADVISORY_CATEGORIES : TEXT_CLASSIFIER_CATEGORIES,
    usedImage ? "unknown" : "ambiguous"
  );
  if (usedImage && !VISION_ADVISORY_CATEGORIES.has(normalized.reasonCategory)) {
    normalized.reasonCategory = "unknown";
  }
  const wouldEscalate = shouldEscalate(
    normalized.confidence,
    normalized.reasonCategory
  );
  let verificationIncomplete = wouldEscalate;

  logClassifyEvent({
    provider: provider.kind,
    deployment: deploymentUsed,
    latencyMs,
    decision: normalized.decision,
    reasonCategory: normalized.reasonCategory,
    confidence: normalized.confidence,
    wouldEscalate,
    packageName: body.packageName,
    goal: body.goal,
    screenText: body.screenText,
  });
  if (image) {
    console.log(
      JSON.stringify({
        event: "vision_classify_meta",
        usedImage,
        imageRejected,
        deployment: deploymentUsed,
        // never log the image
        imageChars: image.length,
      })
    );
  }

  if (wouldEscalate && !usedImage && deploymentUsed !== getBestDeployment()) {
    try {
      const escalated = await completeJsonChatWithFallback({
        provider,
        deployments: [getBestDeployment()],
        maxTokens: 800,
        messages: [
          { role: "system", content: loadClassifierPrompt() },
          { role: "user", content: JSON.stringify(userPayload) },
        ],
      });
      const escalatedRaw = escalated.completion.choices[0]?.message?.content;
      const escalatedParsed = parseClassifierJson(escalatedRaw);
      Object.assign(normalized, normalizeClassifierResult(escalatedParsed));
      deploymentUsed = escalated.deployment;
      verificationIncomplete = shouldEscalate(
        normalized.confidence,
        normalized.reasonCategory
      );
      console.log(
        JSON.stringify({
          event: "classify_escalation",
          from: provider.deployment,
          to: deploymentUsed,
          promptVersion: PROMPT_VERSION,
          reason:
            normalized.confidence < ESCALATION_CONFIDENCE_THRESHOLD
              ? "low_confidence"
              : "uncertain_result",
        })
      );
    } catch (escalateErr) {
      console.log(
        JSON.stringify({
          event: "classify_escalation_failed",
          targetDeployment: getBestDeployment(),
          upstreamStatus: typeof escalateErr.status === "number" ? escalateErr.status : null,
        })
      );
    }
  }

  if (verificationIncomplete) {
    normalized.decision = "WARN";
    normalized.reason = "Could not verify this screen safely";
  }

  return {
    decision: normalized.decision,
    confidence: normalized.confidence,
    reason: normalized.reason,
    reasonCategory: normalized.reasonCategory,
    whatOnScreen: normalized.whatOnScreen || "",
    would_escalate: wouldEscalate,
    verificationIncomplete,
    usedImage,
    imageRejected,
    meta: {
      provider: provider.kind,
      deployment: deploymentUsed,
      promptVersion: usedImage ? `${PROMPT_VERSION}+vision` : PROMPT_VERSION,
      latencyMs: Math.round(latencyMs),
    },
  };
}

module.exports = {
  PROMPT_VERSION,
  PROMPT_PATH,
  ESCALATION_CONFIDENCE_THRESHOLD,
  DEFAULT_VISION_DEPLOYMENT,
  getClassifierProvider,
  getPromiseCompilerProvider,
  createClassifierClient,
  classifyActivity,
  parseClassifierJson,
  getTextClassifierCategories,
  getVisionAdvisoryCategories,
  redactForLog,
  extractImageBase64,
  isVisionExperimentEnabled,
  getVisionDeployment,
};
