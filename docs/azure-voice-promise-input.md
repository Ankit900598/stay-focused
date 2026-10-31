# Azure Voice Promise Input

**Audience:** Android + backend owners  
**Status:** Android + backend path implemented (2026-10-11); real-phone Azure check pending
**Rule:** Voice is **input only**. It never starts strict or permanent enforcement without a visible confirmation sheet.

Related:

- Product north star: `PROJECT_CONTEXT.md`
- Phone enforcement state: `docs/CURRENT_STATE.md`
- AI counsel vs PolicyEngine law: `docs/cto-strategy-phonecodex-ai.md`
- Backend route: `POST /transcribe-promise` (`backend/speechTranscriptionService.js`)

---

## 1. Why voice matters

Strlix is a personal commitment OS. Real commitments are messy spoken language:

- “yaar aaj sirf 10 shorts, adult wale bilkul nahi, lecture chalne do”
- “for one hour do not allow any video whose length is greater than forty minutes”

Typing is friction. Voice lowers that friction so users actually create promises. The transcript still becomes normal promise text, then:

```text
spoken promise → transcript → edit → Understand Promise → confirmation → Start Commitment
```

Voice must never skip confirmation. AI/speech is counsel. PolicyEngine remains law.

---

## 2. Azure Speech architecture

| Mode | Use |
|------|-----|
| **Android speech recognizer** | User-selected phone speech service; Strlix receives only the returned transcript |
| **Azure fast transcription** | Explicitly selected, one-shot promise clip; complete audio is uploaded synchronously and then discarded by Strlix |
| **Custom Speech (later)** | Hinglish, Indian accents, promise vocabulary (“Shorts”, “Neso”, “Reels”) |

The current home-mic path uses Azure fast transcription for short recordings rather than the legacy short-audio recognition endpoint. Microsoft documents fast transcription for quick recording transcription; it is a complete-clip request, not continuous/streaming conversation. See [Use the fast transcription API](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/fast-transcription-create).

### Current Azure resource (no secrets)

| Field | Value |
|-------|--------|
| Resource name | `phonecodex-speech-dev` |
| Resource group | `phonecodex-dev` |
| Region | `eastus2` |
| Kind | `SpeechServices` (SKU F0 for lab) |
| Endpoint pattern | `https://phonecodex-speech-dev.cognitiveservices.azure.com/` |
| STT region host pattern | `https://{AZURE_SPEECH_REGION}.stt.speech.microsoft.com/` |

Env variable names (values live only in local `.env`, never committed):

```text
AZURE_SPEECH_KEY=
AZURE_SPEECH_REGION=
AZURE_SPEECH_ENDPOINT=
AZURE_SPEECH_LANGUAGE=en-IN
SPEECH_TRANSCRIBE_MOCK=   # optional: 1 for local/tests without Azure
```

Provider abstraction in code: `azure_speech` now; later `openai_whisper` / `google_speech` behind the same DTO.

---

## 3. Privacy

- **No always-listening.** Capture starts only on explicit mic tap.
- **No always-listening or silent cloud upload.** Each Azure clip requires an explicit choice in the voice-source dialog and the Android microphone permission.
- **No local raw-audio files or raw-audio logs.** Capture is held in memory on-device, sent through the backend to Azure Speech, and released after the request. Azure service terms govern provider-side processing; Strlix does not claim control over Azure retention outside its service configuration.
- **Transcript becomes normal promise text** in the composer (same as typed text). User can edit before Understand Promise.
- Logs may record: provider, language, latencyMs, confidence, transcript length — **not** raw PCM/WAV bytes.
- Do not send accessibility screen dumps to Speech.

---

## 4. Latency & UX states

| State | Meaning |
|-------|---------|
| Idle | Mic available; typing still works |
| Listening | Recording on device (Android) |
| Transcribing | Upload / Azure STT in flight |
| Ready | Transcript filled into promise box |
| Error | Timeout / 503 / network — show message, keep typing fallback |

Guidelines:

- Target interactive P95 under ~3s for short promises; measure actual Azure-region latency before making this a user-facing guarantee.
- Azure client has a 3s connect timeout and 20s read timeout. Capture is capped at 15 seconds; timeout/failure preserves typing and phone-recognizer alternatives.
- Never block the Understand Promise path if Speech is down — user types.

---

## 5. Multilingual future

1. **Now:** `en-IN` (Indian English) as default language tag.
2. **Next:** Hinglish / code-mixed speech via Custom Speech + phrase lists (Shorts, Reels, Neso, Instagram).
3. **Later:** Hindi, Tamil, Telugu, Bengali locales; auto language ID when confidence is low.
4. Transcript still feeds the same Promise Compiler confirm-before-start loop (language-agnostic after text).

---

## 6. Backend API — `POST /transcribe-promise`

Accepts **one** of:

- `audioBase64` + optional `audioFormat` (`wav` | `ogg` | `m4a` | `webm`)
- `transcript` passthrough (tests / on-device STT fallback)

Audio requests are capped at 2 MB decoded on the backend. The Android Azure path sends mono 16 kHz PCM WAV, capped at 15 seconds (about 469 KiB plus the WAV header).

Optional: `language`, `provider`, `mock`.

Success response:

```json
{
  "ok": true,
  "transcript": "...",
  "confidence": 0.92,
  "language": "en-IN",
  "provider": "azure_speech",
  "latencyMs": 1234
}
```

Errors:

- `400` — missing audio and transcript
- `400` — malformed Base64 audio
- `413` — audio exceeds the 2 MB backend cap
- `503` — Speech not configured (no key) and mock not enabled
- `502` — Azure STT failed / bad recognition payload

Health: `/health` includes a `speech` block (`configured` boolean, region, language) without keys.

---

## 7. Android UI (product shape)

- Mic control near the promise box (`PromiseComposerSection`).
- Tap → choose phone speech or Azure; choosing Azure discloses the upload, then requests microphone permission → record up to 15 seconds → `POST /transcribe-promise` → fill `promiseText`.
- During Azure capture, the mic becomes a stop control; leaving Home cancels the capture. Audio is never saved to a local file.
- User **edits** transcript if needed.
- **Understand Promise** still required.
- Confirmation sheet still required before **Start Commitment**.
- Visual states: listening / transcribing / error; typing always available.

Voice alone must not call `startCommitment()`.

---

## 8. Cost

- Call Azure Speech only after the user explicitly chooses Azure for that clip; never from accessibility/content-changed events.
- Prefer short clips (promise length), not continuous streaming in v1.
- The backend charges the existing paid-operation counter once per Azure transcription; this is not a dollar-exact Azure budget limit.
- Log latency + provider for cost/debug; never log raw audio.
- F0 lab SKU for early development; move to S0 when phone traffic is real.

---

## 9. Security / commitment integrity

| Rule | Why |
|------|-----|
| Voice ≠ Start | Strict/permanent locks need visible confirmation |
| Transcript editable | STT errors must not become silent policy |
| Same confirm gate as typing | Compiler counsel → user confirms → PolicyEngine |
| Emergency/safe apps unchanged | Speech path must not weaken guardrails |

---

## 10. Future

- Transcript eval dataset (spoken messy promises → gold policies).
- Azure Custom Speech for Hinglish + promise lexicon.
- Provider bake-off: Azure vs OpenAI Whisper vs Google STT on the same clips.
- Optional on-device STT with cloud refine for offline-first.

---

## 11. Verification checklist

1. `SPEECH_TRANSCRIBE_MOCK=1 node backend/smoke_transcribe_promise.js` passes without an Azure key and proves malformed/oversized input fails before provider lookup.
2. Android unit tests validate WAV headers, request shape, transcript parsing, and HTTP failures without Azure inference.
3. On the phone, explicitly choose Azure, grant microphone permission, record a short promise, verify transcript/edit/Understand/confirmation; verify phone-recognizer choice remains separate.
4. Confirm microphone denial, backend offline, Azure unconfigured, empty audio, cancellation, timeout, and editing during transcription all preserve typed text and never start enforcement.
5. Voice alone must not call `startCommitment()`.
