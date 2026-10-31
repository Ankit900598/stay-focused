/**
 * Smoke test for POST /transcribe-promise — no Azure key required.
 * Uses mock + client transcript passthrough.
 *
 * Run: node backend/smoke_transcribe_promise.js
 */

process.env.SPEECH_TRANSCRIBE_MOCK = "1";

const {
  transcribePromise,
  getSpeechHealth,
  willCallPaidSpeechProvider,
  parseFastTranscriptionResult,
  MAX_PROMISE_AUDIO_BYTES,
} = require("./speechTranscriptionService");

async function main() {
  const health = getSpeechHealth();
  if (!health.mockAvailable) {
    throw new Error("mock path unavailable");
  }

  assert(willCallPaidSpeechProvider({ transcript: "already typed" }) === false, "passthrough is free");
  assert(willCallPaidSpeechProvider({ mock: true, audioBase64: "YQ==" }) === false, "mock is free");
  assert(willCallPaidSpeechProvider({ provider: "google_speech", audioBase64: "YQ==" }) === false, "unsupported provider is free");
  assert(willCallPaidSpeechProvider({ audioBase64: "" }) === false, "empty audio is free");
  const oldMock = process.env.SPEECH_TRANSCRIBE_MOCK;
  const oldSpeechKey = process.env.AZURE_SPEECH_KEY;
  process.env.SPEECH_TRANSCRIBE_MOCK = "0";
  process.env.AZURE_SPEECH_KEY = "local-test-key";
  assert(willCallPaidSpeechProvider({ audioBase64: "YQ==" }) === true, "configured Azure audio is paid");
  assert(willCallPaidSpeechProvider({ audioBase64: "%%%" }) === false, "invalid base64 is not billed");
  const oversizedAudio = Buffer.alloc(MAX_PROMISE_AUDIO_BYTES + 1).toString("base64");
  assert(willCallPaidSpeechProvider({ audioBase64: oversizedAudio }) === false, "oversized audio is not billed");
  if (oldMock === undefined) delete process.env.SPEECH_TRANSCRIBE_MOCK;
  else process.env.SPEECH_TRANSCRIBE_MOCK = oldMock;
  if (oldSpeechKey === undefined) delete process.env.AZURE_SPEECH_KEY;
  else process.env.AZURE_SPEECH_KEY = oldSpeechKey;

  const mock = await transcribePromise({
    mock: true,
    audioBase64: Buffer.from("fake-wav-bytes").toString("base64"),
    audioFormat: "wav",
    language: "en-IN",
  });

  assert(mock.ok === true, "mock.ok");
  assert(mock.provider === "mock", "mock.provider");
  assert(typeof mock.transcript === "string" && mock.transcript.length > 10, "mock.transcript");
  assert(typeof mock.latencyMs === "number", "mock.latencyMs");
  assert(mock.language === "en-IN", "mock.language");

  const passthrough = await transcribePromise({
    transcript: "  study mode for 1 hour no shorts  ",
  });
  assert(passthrough.provider === "client_transcript", "passthrough.provider");
  assert(passthrough.transcript === "study mode for 1 hour no shorts", "passthrough.trim");
  assert(passthrough.confidence === 1, "passthrough.confidence");

  const validationMock = process.env.SPEECH_TRANSCRIBE_MOCK;
  const validationKey = process.env.AZURE_SPEECH_KEY;
  process.env.SPEECH_TRANSCRIBE_MOCK = "0";
  delete process.env.AZURE_SPEECH_KEY;
  const invalidAudioStatus = await captureStatus(() => transcribePromise({ audioBase64: "%%%" }));
  const oversizedAudioStatus = await captureStatus(() => transcribePromise({ audioBase64: oversizedAudio }));
  assert(invalidAudioStatus === 400, "invalid base64 → 400 before provider lookup");
  assert(oversizedAudioStatus === 413, "oversized audio → 413 before provider lookup");
  if (validationMock === undefined) delete process.env.SPEECH_TRANSCRIBE_MOCK;
  else process.env.SPEECH_TRANSCRIBE_MOCK = validationMock;
  if (validationKey === undefined) delete process.env.AZURE_SPEECH_KEY;
  else process.env.AZURE_SPEECH_KEY = validationKey;

  let missingStatus = null;
  try {
    // Force non-mock empty request against a clean env snapshot.
    delete process.env.SPEECH_TRANSCRIBE_MOCK;
    delete process.env.AZURE_SPEECH_KEY;
    await transcribePromise({});
  } catch (err) {
    missingStatus = err.statusCode;
  }
  assert(missingStatus === 400, "missing audio/transcript → 400");

  const fast = parseFastTranscriptionResult({
    phrases: [{ text: "allow only calculus lecture" }],
  });
  assert(fast.transcript === "allow only calculus lecture", "fast phrase fallback");

  await verifyFastFallbackLogPrivacy();

  console.log(
    JSON.stringify({
      ok: true,
      event: "smoke_transcribe_promise",
      checks: ["mock", "client_transcript", "paid_operation_gate", "audio_validation_400_413", "missing_input_400", "fast_parse", "fast_fallback_log_privacy"],
    })
  );
}

async function verifyFastFallbackLogPrivacy() {
  const sensitiveMarker = "private-voice-transcript-987654";
  const oldFetch = global.fetch;
  const oldLog = console.log;
  const oldMock = process.env.SPEECH_TRANSCRIBE_MOCK;
  const oldKey = process.env.AZURE_SPEECH_KEY;
  const oldEndpoint = process.env.AZURE_SPEECH_ENDPOINT;
  const capturedLogs = [];
  let calls = 0;
  try {
    process.env.SPEECH_TRANSCRIBE_MOCK = "0";
    process.env.AZURE_SPEECH_KEY = "local-test-key";
    process.env.AZURE_SPEECH_ENDPOINT = "https://speech.example.test";
    console.log = (message) => capturedLogs.push(String(message));
    global.fetch = async () => {
      calls += 1;
      if (calls === 1) {
        return {
          ok: false,
          status: 400,
          text: async () => JSON.stringify({ error: { message: sensitiveMarker } }),
        };
      }
      return {
        ok: true,
        status: 200,
        text: async () => JSON.stringify({ DisplayText: "study for an hour", RecognitionStatus: "Success" }),
      };
    };
    const result = await transcribePromise({ audioBase64: "YQ==", audioFormat: "wav" });
    assert(result.transcript === "study for an hour", "legacy fallback returns transcript");
    assert(calls === 2, "fast path falls back exactly once");
    assert(capturedLogs.some((line) => line.includes("speech_fast_fallback")), "fallback event logged");
    assert(!capturedLogs.some((line) => line.includes(sensitiveMarker)), "upstream text excluded from logs");
  } finally {
    global.fetch = oldFetch;
    console.log = oldLog;
    if (oldMock === undefined) delete process.env.SPEECH_TRANSCRIBE_MOCK;
    else process.env.SPEECH_TRANSCRIBE_MOCK = oldMock;
    if (oldKey === undefined) delete process.env.AZURE_SPEECH_KEY;
    else process.env.AZURE_SPEECH_KEY = oldKey;
    if (oldEndpoint === undefined) delete process.env.AZURE_SPEECH_ENDPOINT;
    else process.env.AZURE_SPEECH_ENDPOINT = oldEndpoint;
  }
}

async function captureStatus(callback) {
  try {
    await callback();
    return null;
  } catch (err) {
    return err.statusCode;
  }
}

function assert(cond, label) {
  if (!cond) {
    throw new Error(`smoke_transcribe_promise failed: ${label}`);
  }
}

main().catch((err) => {
  console.error(JSON.stringify({ ok: false, error: err.message }));
  process.exit(1);
});
