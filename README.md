# Wave Parser

Asynchronous, queue-driven audio transcription. The upload returns immediately with a
job id; a Python worker transcribes chunks with a local Whisper model; results are
stitched back together in order and pushed to the browser over SSE.

```
browser ──► nginx ──► Spring Boot ──► RabbitMQ ──► Python worker (faster-whisper)
                          │                              │
                          │◄──── result messages ────────┘
                          ▼
                    Postgres (jobs, chunks)
```

## Services

| Service | Path | What it does |
| --- | --- | --- |
| Backend | `filehandler/` | Upload, chunking, aggregation, auth, SSE, search, exports |
| Worker | `whisper/` | Consumes chunk messages and runs faster-whisper |
| Frontend | `client/` | React SPA: upload, job progress, transcript viewer |
| Proxy | `client/nginx.conf` | Single entry point: serves the SPA and proxies `/api` |

## How a job flows

1. `POST /api/jobs` validates the upload (extension **and** file signature), stores it,
   and returns a `jobId`. Normalizing and splitting happens off the request thread.
2. FFmpeg normalizes the audio to 16 kHz mono WAV and cuts it on **silences**, with a
   small overlap so words are never sliced. Each chunk becomes its own queue message
   carrying `jobId`, `chunkIndex`, `totalChunks` and its start offset.
3. Workers transcribe chunks independently (`prefetch_count=1`). A failing chunk is
   retried through a delay queue and dead-lettered after `CHUNK_MAX_ATTEMPTS`.
4. The backend stores each result idempotently (a redelivered chunk is ignored) and,
   under a row-level lock, assembles the transcript in `chunkIndex` order once every
   chunk has arrived. The overlap between chunks is trimmed so text is not duplicated.
5. Progress and the finished transcript are pushed over `GET /api/jobs/{id}/events`.

## Deployment

### Option 1: All-in-one on Render (free tier)

Deploy the entire stack (backend + worker + postgres + rabbitmq) as a Docker service:

1. Push this repo to GitHub
2. In Render, create a new **_web_service**:
   - Connect your repo
   - Build command: `docker compose build`
   - Start command: `docker compose up`
   - Add all environment variables from `.env.example`
   - **Important:** Set `JWT_SECRET` to a strong random value (generate with `openssl rand -base64 48`)
   - Set `ALLOWED_ORIGINS` to your Netlify URL (see Option 2)
3. Render will give you a URL like `https://wave-parser.onrender.com`

**Note:** The free tier spins down after 15 minutes of inactivity. The first request
after idle will take 30-60 seconds to wake up.

### Option 2: Frontend on Netlify, Backend on Render (recommended)

This separates concerns and gives you better performance:

**Frontend (Netlify):**
1. In Netlify, create a new site from Git
2. Build command: `cd client && npm install && npm run build`
3. Publish directory: `client/dist`
4. Add environment variable: `VITE_API_BASE_URL=https://your-backend.onrender.com`

**Backend (Render):**
1. Same as Option 1, but set `ALLOWED_ORIGINS` to your Netlify URL:
   - `ALLOWED_ORIGINS=https://your-app.netlify.app`

### Local development

```bash
# Postgres and RabbitMQ
docker compose up -d postgres rabbitmq

# Backend (needs FFmpeg installed)
cd filehandler && ./mvnw spring-boot:run

# Worker
cd whisper && uv sync && uv run whisper

# Frontend (proxies /api to :8080)
cd client && npm install && npm run dev
```

### URLs

When running locally with Docker:

* App: http://localhost:8080
* RabbitMQ management UI: http://localhost:15673
* Swagger UI: http://localhost:8080/api/swagger-ui.html

For local development (backend on 8080, frontend on 5173):

* Frontend: http://localhost:5173 (proxies /api to backend)
* Swagger UI: http://localhost:8080/api/swagger-ui.html

## API

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/auth/register`, `/api/auth/login` | Get a JWT |
| `POST` | `/api/jobs` | Upload audio, returns a job id |
| `GET` | `/api/jobs` | Job history for the signed-in user |
| `GET` | `/api/jobs/{id}` | Status and chunk progress |
| `GET` | `/api/jobs/{id}/events` | Live status over SSE |
| `GET` | `/api/jobs/{id}/transcript` | Full transcript with segments |
| `PUT` | `/api/jobs/{id}/transcript` | Save manual corrections |
| `GET` | `/api/jobs/{id}/search?q=` | Phrase search with timestamps |
| `GET` | `/api/jobs/{id}/audio` | Original audio (supports Range, for seeking) |
| `GET` | `/api/jobs/{id}/export?format=` | `txt`, `srt`, `vtt` or `json` |
| `POST` | `/api/jobs/{id}/retry` | Re-run a failed job |
| `DELETE` | `/api/jobs/{id}` | Delete a job, its chunks and its audio |

`GET /actuator/health` backs the Docker healthchecks.

Because `<audio>` and `EventSource` cannot send headers, those two endpoints also
accept the token as `?token=`.

## Configuration

Everything is environment-driven; see `.env.example` for the full list. Notable knobs:

| Variable | Default | Meaning |
| --- | --- | --- |
| `WHISPER_MODEL_SIZE` | `base` | `tiny` … `medium`; quality vs speed |
| `APP_CHUNK_TARGET_SECONDS` | `600` | Preferred chunk length before silence snapping |
| `APP_CHUNK_SILENCE_NOISE_DB` | `-35` | Silence threshold used when splitting |
| `APP_STORAGE_RETENTION_DAYS` | `7` | Old audio is deleted after this |
| `APP_RATE_LIMIT_UPLOADS_PER_MINUTE` | `10` | Per-user upload rate limit |
| `APP_RATE_LIMIT_MAX_ACTIVE_JOBS` | `3` | Cap on jobs running at once per user |
| `CHUNK_MAX_ATTEMPTS` | `3` | Retries before a chunk is dead-lettered |

## Production Checklist

Before going live:

- [ ] Generate a strong JWT secret: `openssl rand -base64 48`
- [ ] Change all default passwords (POSTGRES_PASSWORD, RABBITMQ_PASSWORD)
- [ ] Set `ALLOWED_ORIGINS` to your Netlify URL
- [ ] Disable Swagger UI in production (set `springdoc.api-docs.enabled=false`)
- [ ] Use environment variables, never hardcode secrets
- [ ] Test the full flow: register → upload → wait → view transcript → export

## Notes and trade-offs

* Schema is managed by Hibernate (`ddl-auto=update`) rather than Flyway, so an existing
  local database keeps working. Switching to Flyway with a baseline migration is the
  natural next step for production.
* Rate limiting lives in memory, which is correct for a single instance; it would move
  to Redis if the backend were scaled out.
* Speaker diarization and the metrics dashboard are intentionally not implemented —
  both are listed as optional in the design.
* For a college demo, the free tiers of Render + Netlify work well. Just remember the
  backend will sleep after 15 minutes of inactivity on Render's free tier.
