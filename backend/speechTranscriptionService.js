/**
 * Promise speech transcription — provider-abstracted.
 * Voice is input only; never starts enforcement.
 *
 * Providers: azure_speech (live), mock (tests/local), client_transcript (passthrough).
 * No raw audio is written to disk.
 */

const DEFAULT_LANGUAGE = "en-IN";
const DEFAULT_REGION = "eastus2";
const RECOGNITION_PATH =
  "/speech/recognition/conversation/cognitiveservices/v1";
const FAST_TRANSCRIBE_API_VERSION = "2024-11-15";
const MAX_PROMISE_AUDIO_BYTES = 2 * 1024 * 1024;
const MAX_PROMISE_AUDIO_BASE64_CHARS = 4 * Math.ceil(MAX_PROMISE_AUDIO_BYTES / 3);
const BASE64_PATTERN = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
const PROMISE_PHRASE_HINTS = [
  "monk mode",
  "shorts",
  "reels",
  "calculus",
  "lecture",
  "Neso Academy",
  "Instagram",
  "YouTube",
  "no porn",
  "study only",
];

function getSpeechConfig() {
  const key = (process.env.AZURE_SPEECH_KEY || "").trim();
  const region = (process.env.AZURE_SPEECH_REGION || DEFAULT_REGION).trim();
  const endpoint = (process.env.AZURE_SPEECH_ENDPOINT || "").trim().replace(/\/$/, "");
  const language = (process.env.AZURE_SPEECH_LANGUAGE || DEFAULT_LANGUAGE).trim();
  const mockEnv =
    process.env.SPEECH_TRANSCRIBE_MOCK === "1" ||
    process.env.SPEECH_TRANSCRIBE_MOCK === "true";

  return {
    key,
    region,
    endpoint,
    language,
    mockEnv,
    configured: Boolean(key && region),
  };
}

function getSpeechHealth() {
  const cfg = getSpeechConfig();
  return {
    configured: cfg.configured,
    provider: cfg.configured ? "azure_speech_fast" : null,
    region: cfg.region || null,
    language: cfg.language,
    model: cfg.configured ? "fast_transcription" : null,
    mockAvailable: true,
    endpointConfigured: Boolean(cfg.endpoint),
  };
}

function willCallPaidSpeechProvider(input = {}) {
  const cfg = getSpeechConfig();
  const transcript = typeof input.transcript === "string" ? input.transcript.trim() : "";
  const wantsMock = input.mock === true || input.provider === "mock" || cfg.mockEnv;
  const provider = input.provider === "openai_whisper" || input.provider === "google_speech"
    ? input.provider
    : "azure_speech";
  const audioBase64 = typeof input.audioBase64 === "string" ? input.audioBase64.trim() : "";
  let hasValidAudio = false;
  try {
    hasValidAudio = Boolean(audioBase64) && decodePromiseAudio(audioBase64).length > 0;
  } catch {
    hasValidAudio = false;
  }
  return !transcript && !wantsMock && cfg.configured && provider === "azure_speech" && hasValidAudio;
}

/**
 * @param {object} input
 * @param {string} [input.audioBase64]
 * @param {string} [input.audioFormat]
 * @param {string} [input.transcript]
 * @param {string} [input.language]
 * @param {string} [input.provider] azure_speech | mock | auto
 * @param {boolean} [input.mock]
 */
async function transcribePromise(input = {}) {
  const started = Date.now();
  const cfg = getSpeechConfig();
  const language =
    typeof input.language === "string" && input.language.trim()
      ? input.language.trim()
      : cfg.language;

  const passthrough =
    typeof input.transcript === "string" ? input.transcript.trim() : "";
  if (passthrough) {
    return {
      ok: true,
      transcript: passthrough,
      confidence: 1,
      language,
      provider: "client_transcript",
      latencyMs: Date.now() - started,
    };
  }

  const wantMock =
    input.mock === true ||
    input.provider === "mock" ||
    cfg.mockEnv;

  const audioBase64 =
    typeof input.audioBase64 === "string" ? input.audioBase64.trim() : "";

  if (!audioBase64 && !wantMock) {
    const err = new Error("audioBase64 or transcript is required");
    err.statusCode = 400;
    throw err;
  }

  if (wantMock) {
    return buildMockResult({
      audioBase64,
      language,
      started,
    });
  }

  const audioBuffer = audioBase64 ? decodePromiseAudio(audioBase64) : null;

  if (!cfg.configured) {
    const err = new Error(
      "Speech unavailable: set AZURE_SPEECH_KEY and AZURE_SPEECH_REGION (or SPEECH_TRANSCRIBE_MOCK=1)"
    );
    err.statusCode = 503;
    throw err;
  }

  const provider = input.provider === "openai_whisper" || input.provider === "google_speech"
    ? input.provider
    : "azure_speech";

  if (provider !== "azure_speech") {
    const err = new Error(`Speech provider not implemented yet: ${provider}`);
    err.statusCode = 501;
    throw err;
  }

  const format = normalizeAudioFormat(input.audioFormat);
  const result = await transcribeWithAzureSpeech({
    audioBuffer,
    format,
    language,
    key: cfg.key,
    region: cfg.region,
    endpoint: cfg.endpoint,
  });

  return {
    ok: true,
    transcript: result.transcript,
    confidence: result.confidence,
    language,
    provider: result.provider || "azure_speech_fast",
    latencyMs: Date.now() - started,
  };
}

function decodePromiseAudio(value) {
  if (typeof value !== "string" || value.length === 0 || !BASE64_PATTERN.test(value)) {
    const err = new Error("audioBase64 must be valid standard Base64");
    err.statusCode = 400;
    throw err;
  }
  if (value.length > MAX_PROMISE_AUDIO_BASE64_CHARS) {
    const err = new Error("audio clip exceeds the 2 MB limit");
    err.statusCode = 413;
    throw err;
  }
  const audioBuffer = Buffer.from(value, "base64");
  if (audioBuffer.length === 0 || audioBuffer.toString("base64") !== value) {
    const err = new Error("audioBase64 is empty or malformed");
    err.statusCode = 400;
    throw err;
  }
  if (audioBuffer.length > MAX_PROMISE_AUDIO_BYTES) {
    const err = new Error("audio clip exceeds the 2 MB limit");
    err.statusCode = 413;
    throw err;
  }
  return audioBuffer;
}

function normalizeAudioFormat(value) {
  const raw = typeof value === "string" ? value.trim().toLowerCase() : "wav";
  if (raw === "wav" || raw === "pcm" || raw === "audio/wav") return "wav";
  if (raw === "ogg" || raw === "opus" || raw === "audio/ogg") return "ogg";
  if (raw === "mp3" || raw === "audio/mpeg") return "mp3";
  if (raw === "m4a" || raw === "mp4" || raw === "aac") return "m4a";
  if (raw === "webm") return "webm";
  return "wav";
}

function contentTypeForFormat(format) {
  switch (format) {
    case "ogg":
      return "audio/ogg; codecs=opus";
    case "mp3":
      return "audio/mpeg";
    case "m4a":
      return "audio/mp4";
    case "webm":
      return "audio/webm";
    case "wav":
    default:
      // Azure short-audio REST expects WAV/PCM for the conversation endpoint.
      return "audio/wav; codecs=audio/pcm; samplerate=16000";
  }
}

function parseFastTranscriptionResult(json) {
  const combined =
    (typeof json?.combinedPhrases?.[0]?.text === "string" &&
      json.combinedPhrases[0].text.trim()) ||
    "";
  if (combined) {
    const confidenceRaw = json.combinedPhrases[0].confidence;
    return {
      transcript: combined,
      confidence:
        typeof confidenceRaw === "number" && Number.isFinite(confidenceRaw)
          ? confidenceRaw
          : 0.9,
    };
  }
  const phrase =
    (typeof json?.phrases?.[0]?.text === "string" && json.phrases[0].text.trim()) ||
    "";
  if (phrase) {
    return { transcript: phrase, confidence: 0.85 };
  }
  return null;
}

async function transcribeWithFastTranscription({
  audioBuffer,
  format,
  language,
  key,
  region,
  endpoint,
}) {
  const base =
    (endpoint && endpoint.replace(/\/$/, "")) ||
    `https://${region}.api.cognitive.microsoft.com`;
  const url = `${base}/speechtotext/transcriptions:transcribe?api-version=${FAST_TRANSCRIBE_API_VERSION}`;
  const form = new FormData();
  form.append(
    "audio",
    new Blob([audioBuffer], { type: contentTypeForFormat(format) }),
    `promise.${format}`
  );
  form.append(
    "definition",
    JSON.stringify({
      locales: [language],
      profanityFilterMode: "Raw",
      phraseList: { phrases: PROMISE_PHRASE_HINTS },
    })
  );

  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Ocp-Apim-Subscription-Key": key,
    },
    body: form,
  });
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (!response.ok) {
    const detail =
      (json && (json.error?.message || json.message)) || `HTTP ${response.status}`;
    throw new Error(`Azure Speech fast transcription failed: ${detail}`);
  }
  const parsed = parseFastTranscriptionResult(json);
  if (!parsed) {
    throw new Error("Azure Speech fast transcription returned empty transcript");
  }
  return { ...parsed, provider: "azure_speech_fast" };
}

async function transcribeWithAzureSpeech({
  audioBuffer,
  format,
  language,
  key,
  region,
  endpoint,
}) {
  try {
    return await transcribeWithFastTranscription({
      audioBuffer,
      format,
      language,
      key,
      region,
      endpoint,
    });
  } catch (_fastErr) {
    console.log(
      JSON.stringify({
        event: "speech_fast_fallback",
      })
    );
  }

  const url =
    `https://${region}.stt.speech.microsoft.com${RECOGNITION_PATH}` +
    `?language=${encodeURIComponent(language)}&format=detailed`;

  let response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: {
        "Ocp-Apim-Subscription-Key": key,
        Accept: "application/json",
        "Content-Type": contentTypeForFormat(format),
      },
      body: audioBuffer,
    });
  } catch (networkErr) {
    const err = new Error(
      `Azure Speech network error: ${networkErr.message || "request failed"}`
    );
    err.statusCode = 502;
    throw err;
  }

  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }

  if (!response.ok) {
    const detail =
      (json && (json.error?.message || json.message)) ||
      `HTTP ${response.status}`;
    const err = new Error(`Azure Speech failed: ${detail}`);
    err.statusCode = 502;
    // Never attach raw audio; safe truncated body only.
    err.raw = typeof text === "string" ? text.slice(0, 400) : null;
    throw err;
  }

  const transcript =
    (typeof json?.DisplayText === "string" && json.DisplayText.trim()) ||
    (typeof json?.NBest?.[0]?.Display === "string" &&
      json.NBest[0].Display.trim()) ||
    "";

  if (!transcript) {
    const err = new Error("Azure Speech returned empty transcript");
    err.statusCode = 502;
    err.raw = json;
    throw err;
  }

  const confidenceRaw = json?.NBest?.[0]?.Confidence;
  const confidence =
    typeof confidenceRaw === "number" && Number.isFinite(confidenceRaw)
      ? confidenceRaw
      : json?.RecognitionStatus === "Success"
        ? 0.85
        : 0.5;

  return { transcript, confidence, provider: "azure_speech" };
}

function buildMockResult({ audioBase64, language, started }) {
  const hint =
    typeof audioBase64 === "string" && audioBase64.length > 0
      ? " (audio received, not sent)"
      : "";
  return {
    ok: true,
    transcript:
      "I want to watch at most 10 shorts today, but never adult shorts. Long lectures stay allowed." +
      hint,
    confidence: 0.91,
    language,
    provider: "mock",
    latencyMs: Date.now() - started,
  };
}

module.exports = {
  getSpeechConfig,
  getSpeechHealth,
  willCallPaidSpeechProvider,
  transcribePromise,
  normalizeAudioFormat,
  buildMockResult,
  parseFastTranscriptionResult,
  decodePromiseAudio,
  MAX_PROMISE_AUDIO_BYTES,
};
