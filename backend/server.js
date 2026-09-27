const path = require("path");
require("dotenv").config({ path: path.join(__dirname, "..", ".env") });

const express = require("express");
const OpenAI = require("openai");
const {
  PROMPT_VERSION,
  getClassifierProvider,
  classifyActivity,
  getVisionDeployment,
  isVisionExperimentEnabled,
} = require("./classifierService");
const {
  PROMPT_VERSION: PROMISE_COMPILER_PROMPT_VERSION,
  getPromiseCompilerProvider,
  compilePromise,
} = require("./promiseCompilerService");
const {
  getSpeechHealth,
  transcribePromise,
} = require("./speechTranscriptionService");
const { researchApp } = require("./appDossierService");

const {
  appAuthMiddleware,
  assertHostedAppSecretConfigured,
  isHostedRuntime,
} = require("./appAuth");

assertHostedAppSecretConfigured();

const PORT = Number(process.env.PORT) || 8787;
const app = express();

// Voice uploads need a larger JSON body; other routes stay lean via separate parsers.
app.use("/transcribe-promise", express.json({ limit: "6mb" }));
app.use("/classify", express.json({ limit: "400kb" }));
app.use(express.json({ limit: "100kb" }));
app.use(appAuthMiddleware);

const classifierProvider = getClassifierProvider();
if (classifierProvider) {
  console.log(
    JSON.stringify({
      event: "classifier_startup",
      provider: classifierProvider.kind,
      deployment: classifierProvider.deployment,
      promptVersion: PROMPT_VERSION,
    })
  );
} else {
  console.warn(
    "Classifier not configured: set AZURE_OPENAI_* env vars or OPENAI_API_KEY"
  );
}

const promiseCompilerProvider = getPromiseCompilerProvider();
if (promiseCompilerProvider) {
  console.log(
    JSON.stringify({
      event: "promise_compiler_startup",
      provider: promiseCompilerProvider.kind,
      deployment: promiseCompilerProvider.deployment,
      promptVersion: PROMISE_COMPILER_PROMPT_VERSION,
    })
  );
} else {
  console.warn(
    "Promise compiler not configured: set AZURE_OPENAI_* env vars or OPENAI_API_KEY"
  );
}

const speechHealthAtBoot = getSpeechHealth();
console.log(
  JSON.stringify({
    event: "speech_startup",
    configured: speechHealthAtBoot.configured,
    region: speechHealthAtBoot.region,
    language: speechHealthAtBoot.language,
  })
);

app.get("/health", (_req, res) => {
  const provider = getClassifierProvider();
  const promiseCompiler = getPromiseCompilerProvider();
  res.json({
    ok: true,
    build: process.env.STRLIX_BUILD_ID || null,
    classifier: provider
      ? {
          provider: provider.kind,
          deployment: provider.deployment,
          promptVersion: PROMPT_VERSION,
        }
      : null,
    promiseCompiler: promiseCompiler
      ? {
          provider: promiseCompiler.kind,
          deployment: promiseCompiler.deployment,
          promptVersion: PROMISE_COMPILER_PROMPT_VERSION,
        }
      : null,
    speech: getSpeechHealth(),
    visionExperiment: {
      enabled: isVisionExperimentEnabled(),
      deployment: getVisionDeployment(),
    },
    modelLadder: {
      compiler: promiseCompiler ? promiseCompiler.deployment : null,
      classifier: provider ? provider.deployment : null,
      vision: getVisionDeployment(),
      speech: getSpeechHealth().model,
    },
    hosted: isHostedRuntime(),
    appSecretRequired: isHostedRuntime() || Boolean(process.env.STRLIX_BACKEND_APP_SECRET),
    visionDefault: "off",
    appDossier: true,
  });
});

app.post("/research-app", async (req, res) => {
  try {
    const result = await researchApp(req.body || {});
    return res.json(result);
  } catch (err) {
    const status = err.statusCode || 500;
    console.error(
      JSON.stringify({
        event: "research_app_error",
        message: err.message,
        statusCode: status,
      })
    );
    return res.status(status).json({
      error: status === 400 ? err.message : "App research failed",
      detail: err.message,
    });
  }
});

app.post("/classify", async (req, res) => {
  try {
    const body = req.body || {};
    const { packageName, screenText, goal } = body;

    if (
      typeof packageName !== "string" ||
      typeof screenText !== "string" ||
      typeof goal !== "string"
    ) {
      return res.status(400).json({
        error: "packageName, screenText, and goal must be strings",
      });
    }

    const result = await classifyActivity(body);
    return res.json(result);
  } catch (err) {
    if (err.statusCode === 502 && err.raw) {
      return res.status(502).json({
        error: err.message,
        raw: err.raw,
      });
    }

    console.error(
      JSON.stringify({
        event: "classify_error",
        message: err.message,
      })
    );

    const status = err.statusCode || 500;
    return res.status(status).json({
      error: status === 500 ? "Classification failed" : err.message,
      detail: err.message,
    });
  }
});

app.post("/compile-promise", async (req, res) => {
  try {
    const body = req.body || {};
    const promise = body.promise;
    const selectedClarificationOptionId =
      body.selectedClarificationOptionId || body.selectedOptionId || null;
    // Reliability design 2026-09-25 §Fix 1: turn 2 must not re-derive scope
    // from the model. Android echoes the exact option the user tapped
    // (label + internalPolicyPreview) and the snapshot of options shown on
    // turn 1 so the server can verify + rematerialize deterministically.
    // Both are optional so old clients keep working (they fail closed).
    const selectedClarificationOptionFullText =
      body.selectedClarificationOptionFullText &&
      typeof body.selectedClarificationOptionFullText === "object"
        ? body.selectedClarificationOptionFullText
        : null;
    const priorClarificationOptionsSnapshot = Array.isArray(
      body.priorClarificationOptionsSnapshot
    )
      ? body.priorClarificationOptionsSnapshot
      : null;
    // Promise text the snapshot was shown for; a mismatch means the promise was edited.
    const priorClarificationPromiseText =
      typeof body.priorClarificationPromiseText === "string"
        ? body.priorClarificationPromiseText
        : undefined;

    if (typeof promise !== "string" || promise.trim().length === 0) {
      return res.status(400).json({
        error: "promise must be a non-empty string",
      });
    }

    if (!getPromiseCompilerProvider()) {
      return res.status(503).json({
        error:
          "Promise compiler unavailable: set AZURE_OPENAI_* env vars or OPENAI_API_KEY",
      });
    }

    const result = await compilePromise(promise, {
      selectedClarificationOptionId,
      selectedClarificationOptionFullText,
      priorClarificationOptionsSnapshot,
      priorClarificationPromiseText,
    });
    return res.json(result);
  } catch (err) {
    if (err.statusCode === 502 && err.raw) {
      return res.status(502).json({
        error: err.message,
        raw: err.raw,
      });
    }

    console.error(
      JSON.stringify({
        event: "compile_promise_error",
        message: err.message,
        statusCode: err.statusCode || 500,
      })
    );

    const status = err.statusCode || 500;
    return res.status(status).json({
      error:
        status === 500 ? "Promise compilation failed" : err.message,
      detail: err.message,
    });
  }
});

/**
 * Voice promise input — transcript only. Never starts enforcement.
 * Body: { audioBase64, audioFormat? } OR { transcript } OR { mock: true }.
 */
app.post("/transcribe-promise", async (req, res) => {
  try {
    const body = req.body || {};
    const started = Date.now();
    const result = await transcribePromise(body);

    console.log(
      JSON.stringify({
        event: "transcribe_promise_ok",
        provider: result.provider,
        language: result.language,
        latencyMs: result.latencyMs,
        transcriptChars: result.transcript ? result.transcript.length : 0,
        confidence: result.confidence,
        wallMs: Date.now() - started,
      })
    );

    return res.json(result);
  } catch (err) {
    console.error(
      JSON.stringify({
        event: "transcribe_promise_error",
        message: err.message,
        statusCode: err.statusCode || 500,
      })
    );

    const status = err.statusCode || 500;
    const payload = {
      ok: false,
      error:
        status === 500 ? "Promise transcription failed" : err.message,
      detail: err.message,
    };
    if (status === 502 && err.raw) {
      payload.raw = err.raw;
    }
    return res.status(status).json(payload);
  }
});

app.post("/draft-focus-promise", async (req, res) => {
  try {
    const { promise } = req.body || {};

    if (typeof promise !== "string" || promise.trim().length === 0) {
      return res.status(400).json({
        error: "promise must be a non-empty string",
      });
    }

    const trimmedPromise = promise.trim();
    const apiKey = process.env.OPENAI_API_KEY;
    if (!apiKey) {
      return res.json(
        normalizeDraftFocusPromise(createKeywordFallbackDraft(trimmedPromise), trimmedPromise, {
          usedFallback: true,
        })
      );
    }

    const openai = new OpenAI({ apiKey });

    const systemPrompt = [
      "You are PhoneCodex focus promise drafter.",
      "Convert a user's plain-English focus promise into structured study-world settings.",
      "Return only JSON with this exact shape:",
      "{",
      '  "durationMinutes": number,',
      '  "strictness": "SOFT" | "SMART" | "STRICT" | "LOCKED",',
      '  "allowedKeywords": string[],',
      '  "blockedKeywords": string[],',
      '  "suggestedAppRules": [',
      '    { "packageName": string, "label": string, "behavior": "ALLOW" | "WARN" | "BLOCK" | "AI_DECIDE" }',
      "  ],",
      '  "clarifyingQuestion": string | null',
      "}",
      "",
      "Rules:",
      "- durationMinutes should be inferred from phrases like 30 minutes or 2 hours. Default 30.",
      "- strictness: monk mode / hard mode / fully restricted -> LOCKED; strict / no entertainment -> STRICT.",
      "- allowedKeywords and blockedKeywords should be short lowercase tokens from the promise.",
      "- suggestedAppRules should use these package names when relevant:",
      "  Telegram -> org.telegram.messenger",
      "  WhatsApp -> com.whatsapp",
      "  YouTube -> com.google.android.youtube",
      "  Chrome -> com.android.chrome",
      "  Instagram -> com.instagram.android",
      "- Telegram/WhatsApp are usually WARN, YouTube/Chrome AI_DECIDE, Instagram BLOCK unless user clearly allows them.",
      "- Ask clarifyingQuestion only when the promise is too vague to draft safely.",
      "- Do not invent apps that are not implied by the promise.",
    ].join("\n");

    let parsed = null;
    try {
      const completion = await openai.chat.completions.create({
        model: "gpt-4o-mini",
        temperature: 0,
        response_format: { type: "json_object" },
        messages: [
          { role: "system", content: systemPrompt },
          { role: "user", content: `promise: ${trimmedPromise}` },
        ],
      });

      const raw = completion.choices[0]?.message?.content;
      if (raw) {
        parsed = JSON.parse(raw);
      }
    } catch (err) {
      console.error("draft-focus-promise model failed:", err.message);
    }

    if (!parsed || typeof parsed !== "object") {
      return res.json(
        normalizeDraftFocusPromise(createKeywordFallbackDraft(trimmedPromise), trimmedPromise, {
          usedFallback: true,
        })
      );
    }

    return res.json(normalizeDraftFocusPromise(parsed, trimmedPromise));
  } catch (err) {
    console.error("draft-focus-promise failed:", err.message);
    const promise = typeof req.body?.promise === "string" ? req.body.promise.trim() : "";
    return res.json(
      normalizeDraftFocusPromise(createKeywordFallbackDraft(promise), promise, {
        usedFallback: true,
      })
    );
  }
});

app.listen(PORT, () => {
  console.log(`PhoneCodex backend listening on http://localhost:${PORT}`);
});

const VALID_STRICTNESS = new Set(["SOFT", "SMART", "STRICT", "LOCKED"]);
const VALID_BEHAVIORS = new Set(["ALLOW", "WARN", "BLOCK", "AI_DECIDE"]);
const KNOWN_APP_RULES = {
  telegram: {
    packageName: "org.telegram.messenger",
    label: "Telegram",
    behavior: "WARN",
  },
  whatsapp: {
    packageName: "com.whatsapp",
    label: "WhatsApp",
    behavior: "WARN",
  },
  youtube: {
    packageName: "com.google.android.youtube",
    label: "YouTube",
    behavior: "AI_DECIDE",
  },
  lecture: {
    packageName: "com.google.android.youtube",
    label: "YouTube",
    behavior: "AI_DECIDE",
  },
  chrome: {
    packageName: "com.android.chrome",
    label: "Chrome",
    behavior: "AI_DECIDE",
  },
  docs: {
    packageName: "com.android.chrome",
    label: "Chrome",
    behavior: "AI_DECIDE",
  },
  browser: {
    packageName: "com.android.chrome",
    label: "Chrome",
    behavior: "AI_DECIDE",
  },
  instagram: {
    packageName: "com.instagram.android",
    label: "Instagram",
    behavior: "BLOCK",
  },
  reels: {
    packageName: "com.instagram.android",
    label: "Instagram",
    behavior: "BLOCK",
  },
};

const ALLOW_KEYWORDS = [
  "study",
  "lecture",
  "docs",
  "telegram",
  "whatsapp",
  "coding",
  "code",
  "github",
  "stackoverflow",
  "compiler",
  "architecture",
  "course",
  "class",
  "assignment",
  "project",
  "exam",
];

const BLOCK_KEYWORDS = [
  "reels",
  "reel",
  "shorts",
  "short",
  "porn",
  "adult",
  "movie",
  "netflix",
  "prime",
  "hotstar",
  "games",
  "game",
  "shopping",
  "amazon",
  "flipkart",
  "memes",
  "meme",
  "news",
  "entertainment",
];

function createKeywordFallbackDraft(promise) {
  const text = promise.toLowerCase();
  const suggestedAppRules = [];

  Object.entries(KNOWN_APP_RULES).forEach(([keyword, rule]) => {
    if (text.includes(keyword)) {
      suggestedAppRules.push({ ...rule });
    }
  });

  return {
    durationMinutes: parseDurationMinutes(text),
    strictness: parseStrictness(text),
    allowedKeywords: ALLOW_KEYWORDS.filter((keyword) => text.includes(keyword)),
    blockedKeywords: BLOCK_KEYWORDS.filter((keyword) => text.includes(keyword)),
    suggestedAppRules: dedupeAppRules(suggestedAppRules),
    clarifyingQuestion:
      promise.trim().length < 12
        ? "What apps should be allowed or blocked during this focus session?"
        : null,
  };
}

function parseDurationMinutes(text) {
  const hourMatch = text.match(/\b(\d+)\s*(?:hours?|hrs?)\b/);
  if (hourMatch) {
    const hours = Number(hourMatch[1]);
    if (Number.isFinite(hours) && hours > 0) {
      return Math.min(hours * 60, 480);
    }
  }

  const minuteMatch = text.match(/\b(\d+)\s*(?:minutes?|mins?)\b/);
  if (minuteMatch) {
    const minutes = Number(minuteMatch[1]);
    if (Number.isFinite(minutes) && minutes > 0) {
      return Math.min(minutes, 480);
    }
  }

  return 30;
}

function parseStrictness(text) {
  if (
    text.includes("monk mode") ||
    text.includes("hard mode") ||
    text.includes("fully restricted") ||
    text.includes("locked") ||
    text.includes("no escape")
  ) {
    return "LOCKED";
  }

  if (
    text.includes("strict mode") ||
    text.includes("no entertainment") ||
    text.includes("strict") ||
    text.includes("no distraction")
  ) {
    return "STRICT";
  }

  if (text.includes("soft") || text.includes("gentle")) {
    return "SOFT";
  }

  if (text.includes("smart")) {
    return "SMART";
  }

  return "STRICT";
}

function normalizeDraftFocusPromise(parsed, promise, options = {}) {
  const fallback = createKeywordFallbackDraft(promise);
  const durationMinutes = normalizeDurationMinutes(parsed?.durationMinutes, fallback.durationMinutes);
  const strictness = normalizeStrictness(parsed?.strictness, fallback.strictness);
  const allowedKeywords = normalizeKeywordList(parsed?.allowedKeywords, fallback.allowedKeywords);
  const blockedKeywords = normalizeKeywordList(parsed?.blockedKeywords, fallback.blockedKeywords);
  const suggestedAppRules = normalizeSuggestedAppRules(
    parsed?.suggestedAppRules,
    fallback.suggestedAppRules,
    promise
  );
  const clarifyingQuestion = normalizeClarifyingQuestion(
    parsed?.clarifyingQuestion,
    fallback.clarifyingQuestion,
    options.usedFallback
  );

  return {
    durationMinutes,
    strictness,
    allowedKeywords,
    blockedKeywords,
    suggestedAppRules,
    clarifyingQuestion,
  };
}

function normalizeDurationMinutes(value, fallback) {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return fallback;
  }

  const rounded = Math.round(value);
  return Math.min(Math.max(rounded, 5), 480);
}

function normalizeStrictness(value, fallback) {
  if (typeof value !== "string") {
    return fallback;
  }

  const normalized = value.trim().toUpperCase();
  return VALID_STRICTNESS.has(normalized) ? normalized : fallback;
}

function normalizeKeywordList(value, fallback) {
  if (!Array.isArray(value)) {
    return fallback;
  }

  const cleaned = value
    .filter((item) => typeof item === "string")
    .map((item) => item.trim().toLowerCase())
    .filter((item) => item.length > 0);

  return cleaned.length > 0 ? [...new Set(cleaned)] : fallback;
}

function normalizeSuggestedAppRules(value, fallback, promise = "") {
  if (!Array.isArray(value)) {
    return fallback;
  }

  const promiseText = promise.toLowerCase();
  const cleaned = value
    .map((rule) => normalizeAppRule(rule))
    .filter((rule) => rule !== null)
    .filter((rule) => isAppRuleMentioned(rule, promiseText));

  return cleaned.length > 0 ? dedupeAppRules(cleaned) : fallback;
}

function isAppRuleMentioned(rule, promiseText) {
  const label = rule.label.toLowerCase();
  const packageParts = rule.packageName
    .toLowerCase()
    .split(".")
    .filter((part) => part.length > 2);

  return (
    promiseText.includes(label) ||
    packageParts.some((part) => promiseText.includes(part))
  );
}

function normalizeAppRule(rule) {
  if (!rule || typeof rule !== "object") {
    return null;
  }

  const packageName =
    typeof rule.packageName === "string" ? rule.packageName.trim() : "";
  const label = typeof rule.label === "string" ? rule.label.trim() : "";
  const behavior =
    typeof rule.behavior === "string"
      ? rule.behavior.trim().toUpperCase()
      : "";

  if (!packageName || !label || !VALID_BEHAVIORS.has(behavior)) {
    return null;
  }

  return { packageName, label, behavior };
}

function dedupeAppRules(rules) {
  const seen = new Set();
  const result = [];

  rules.forEach((rule) => {
    if (!rule || seen.has(rule.packageName)) {
      return;
    }
    seen.add(rule.packageName);
    result.push(rule);
  });

  return result;
}

function normalizeClarifyingQuestion(value, fallback, usedFallback) {
  if (usedFallback) {
    return typeof value === "string" && value.trim().length > 0
      ? value.trim()
      : fallback;
  }

  if (value === null) {
    return null;
  }

  if (typeof value !== "string") {
    return fallback;
  }

  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}
