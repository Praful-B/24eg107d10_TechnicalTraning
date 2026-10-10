# DEMO_CHANGES.md

This file records every way the running system deviates from the **original
architecture**, added so the app can be demoed for **free on Render**
(backend + Postgres + CloudAMQP) with **no separately deployed worker**, while
still producing **real** transcriptions of uploaded audio.

If you want the original behaviour back, set `TRANSCRIPTION_MODE=queue`. Nothing
in the queue path was deleted.

---

## Original architecture (for reference)

```
Frontend (Netlify)
      │ upload
      ▼
Backend (Render, Spring Boot)  ──publish chunk──▶  RabbitMQ (CloudAMQP)
      │                                                   │
      │◀─────────────publish result──────────────── Python Whisper worker
      ▼
Postgres (job status + assembled transcript)
```

The worker and backend must share a filesystem because the queue message carries
a local file path. On Render there is no shared disk and no free worker, so the
job sat at `PREPARING` ("preprocessing") forever.

## New architecture (demo mode)

```
Frontend (Netlify)
      │ upload
      ▼
Backend (Render, Spring Boot)
      │
      ├─ mode=whispercpp : embedded whisper.cpp binary transcribes in-process
      └─ mode=api        : calls a hosted OpenAI-compatible API (e.g. Groq)
      ▼
Postgres (job status + transcript)
```

The **RabbitMQ worker is no longer required**. The backend itself produces the
transcript and writes the final status.

---

## What changed

### 1. Configurable transcription mode (queue path preserved)
- `UploadService.createJob` / `retryJob` now dispatch through `startTranscription(...)`.
  - `queue` → original `ChunkingService` + RabbitMQ flow.
  - anything else → new `LocalTranscriptionService` (in-process, synchronous).
- `RabbitMQConfiguration`: the result listener bean is now
  `@ConditionalOnProperty(app.transcription.mode=queue)`, so non-queue modes do
  not open a broker connection or spam logs.

### 2. New in-process transcription code (backend)
Added package `com.praful.filehandler.transcription`:
- `TranscriptionEngine` / `EngineResult` — engine abstraction.
- `FfmpegAudioNormalizer` — converts input to 16 kHz mono WAV (shared helper).
- `WhisperCppEngine` — runs the embedded `whisper-cli` and parses its JSON.
- `OpenAiCompatibleApiEngine` — multipart POST to a hosted API, parses
  `verbose_json` segments (works with Groq's free tier).
- `LocalTranscriptionService` — `@Async` single-thread runner; sets
  `PREPARING → PROCESSING → COMPLETED` (or `FAILED`), with optional API fallback.

`AsyncConfig` gained a `transcriptionExecutor` (1 thread) so only one Whisper
process runs at a time (memory safety on a 512 MB instance).

### 3. Embedded whisper.cpp build (backend image)
`backend/Dockerfile` gained a `whisperbuild` stage that:
- clones whisper.cpp `v1.8.1`,
- builds a **static** `whisper-cli` (no shared libs to copy),
- bakes in the **multilingual tiny, 5-bit quantized** model
  `ggml-tiny-q5_1.bin` (~32 MB) from `ggerganov/whisper.cpp`.

The runtime image installs `ffmpeg libstdc++ libgomp` and sets
`APP_WHISPER_BINARY` / `APP_WHISPER_MODEL`. The image is larger and the first
build is slower than before.

### 4. Frontend
**No changes.** It already reacts to the `PREPARING → PROCESSING → COMPLETED`
transitions pushed over SSE, so the "preprocessing → done → transcript" flow
works as-is.

### 5. New configuration
Added to `backend/src/main/resources/application.properties`:

| Property | Env var | Default | Purpose |
| --- | --- | --- | --- |
| `app.transcription.mode` | `TRANSCRIPTION_MODE` | `queue` | `queue` \| `whispercpp` \| `api` |
| `app.transcription.fallback-to-api` | `TRANSCRIPTION_FALLBACK_TO_API` | `false` | On whisper.cpp failure, retry via API |
| `app.whisper.binary` | `APP_WHISPER_BINARY` | `/opt/whisper/whisper-cli` | Path to binary |
| `app.whisper.model` | `APP_WHISPER_MODEL` | `/opt/models/ggml-tiny-q5_1.bin` | Path to model |
| `app.whisper.threads` | `APP_WHISPER_THREADS` | `1` | whisper.cpp threads |
| `app.transcription.api.url` | `TRANSCRIPTION_API_URL` | Groq audio endpoint | Hosted API URL |
| `app.transcription.api.key` | `TRANSCRIPTION_API_KEY` / `GROQ_API_KEY` | _empty_ | API key |
| `app.transcription.api.model` | `TRANSCRIPTION_API_MODEL` | `whisper-large-v3-turbo` | API model |
| `management.health.rabbit.enabled` | `MANAGEMENT_HEALTH_RABBIT_ENABLED` | `false` | Off by default so `/actuator/health` stays `UP` without a broker; set `true` for queue mode |

---

## Render setup (free tier)

In the **backend** service → Environment, add:

```
TRANSCRIPTION_MODE=whispercpp
TRANSCRIPTION_FALLBACK_TO_API=true
GROQ_API_KEY=<your free Groq key>          # only needed if you want the fallback
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=45 -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -Xss512k -XX:ActiveProcessorCount=1
```

`JAVA_TOOL_OPTIONS` keeps the JVM small so the whisper child process fits inside
Render's 512 MB.

Then redeploy (the Docker image rebuild compiles whisper.cpp — allow a few
extra minutes on the first build).

To force the hosted API instead of the embedded engine:

```
TRANSCRIPTION_MODE=api
GROQ_API_KEY=<your free Groq key>
```

To go back to the original worker + queue flow:

```
TRANSCRIPTION_MODE=queue
```

---

## Caveats (read before demo day)

- **512 MB is tight even for whisper.cpp tiny.** The embedded engine may still be
  OOM-killed by Render. That is exactly why `TRANSCRIPTION_FALLBACK_TO_API=true`
  exists: if it dies, the job retries through Groq automatically. Keep a Groq key
  configured as insurance.
- **Render free spins down after 15 minutes of inactivity** (cold start ~1 min)
  and the filesystem is **ephemeral** — an upload only survives while the
  instance stays up. Do the upload → transcribe flow in one warm session.
- **0.1 CPU is slow.** A short clip may take tens of seconds to a couple of
  minutes; longer recordings scale up. Use short audio for the live demo.
- **Tiny model accuracy** is lower than `base`/`small`. It handles any speaker
  ("custom voice") but may mis-hear names and rare words.
- **Privacy**: `mode=api` sends the audio to a third party. `mode=whispercpp`
  stays entirely on your server.

### Fallback if the whole thing misbehaves on Render
Run the full stack locally with the root `docker-compose.yml`
(`docker compose up --build`) and demo from `http://localhost:8080`. That setup
already has the shared `audio` volume and the original worker, and it is not
subject to Render's 512 MB / 0.1 CPU limits.

---

## Verification performed

- `./mvnw -DskipTests package` — backend compiles.
- whisper.cpp `v1.8.1` static binary builds and runs on the `eclipse-temurin:21-jre-alpine`
  runtime with only `libstdc++`/`libgomp`.
- `WhisperCppEngine` parser validated against real `whisper-cli -oj` output
  (language, segment offsets, and text all parsed correctly).
- **Full end-to-end** in `whispercpp` mode (backend image + Postgres, no broker,
  no worker): registered a user, uploaded a WAV, watched the job go
  `PREPARING → PROCESSING → COMPLETED`, and fetched the transcript
  (`"And so, my fellow Americans, …"`, language `en`, one segment `0.0–10.6s`).
