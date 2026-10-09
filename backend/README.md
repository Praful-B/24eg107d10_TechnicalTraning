# Wave Parser Backend

Spring Boot backend for asynchronous audio transcription using Whisper.

## Features

- JWT authentication (register/login)
- File upload with validation (extension + signature)
- Audio chunking on silences using FFmpeg
- RabbitMQ-based job processing queue
- PostgreSQL for job/chunk storage
- Server-Sent Events for real-time progress
- Transcript search, export (TXT, SRT, VTT, JSON)
- Rate limiting per user

## Tech Stack

- Java 21 + Spring Boot 4.1
- PostgreSQL + RabbitMQ
- FFmpeg for audio processing
- faster-whisper (Python worker, separate repo)

## Quick Start

### Local Development

```bash
# 1. Start dependencies (PostgreSQL + RabbitMQ)
docker compose up -d postgres rabbitmq

# 2. Set up environment
cp .env.example .env
# Edit .env - especially JWT_SECRET!

# 3. Run backend
./mvnw spring-boot:run
```

Backend will start on http://localhost:8080

### Docker Deployment

```bash
# Build and run
docker compose up --build

# Or build the image directly
docker build -t wave-parser-backend .
docker run -p 8080:8080 wave-parser-backend
```

## API Endpoints

### Authentication
- `POST /api/auth/register` - Create account
- `POST /api/auth/login` - Get JWT token

### Jobs
- `POST /api/jobs` - Upload audio file (multipart/form-data)
- `GET /api/jobs` - List user's jobs
- `GET /api/jobs/{id}` - Get job status
- `GET /api/jobs/{id}/transcript` - Get transcript
- `PUT /api/jobs/{id}/transcript` - Update transcript
- `GET /api/jobs/{id}/search?q=` - Search transcript
- `GET /api/jobs/{id}/events` - SSE stream (live updates)
- `GET /api/jobs/{id}/export?format=` - Export (txt/srt/vtt/json)
- `GET /api/jobs/{id}/audio` - Stream audio (Range requests)
- `POST /api/jobs/{id}/retry` - Retry failed job
- `DELETE /api/jobs/{id}` - Delete job

## Environment Variables

See `.env.example` for all configuration options.

### Required
- `JWT_SECRET` - Generate with: `openssl rand -base64 48`

### Optional (with defaults)
- `POSTGRES_USER` - Database user (default: praful)
- `POSTGRES_PASSWORD` - Database password
- `RABBITMQ_USER` - RabbitMQ user (default: guest)
- `RABBITMQ_PASSWORD` - RabbitMQ password
- `APP_STORAGE_DIR` - Where to store audio files
- `APP_STORAGE_RETENTION_DAYS` - Auto-cleanup after N days
- `ALLOWED_ORIGINS` - CORS allowed origins (comma-separated)

## Deployment on Render

1. Create a new Web Service on Render
2. Connect this GitHub repo
3. Build command: `./mvnw package -DskipTests`
4. Start command: `java -jar target/*.jar`
5. Add all environment variables from `.env.example`
6. Set `JWT_SECRET` to a strong random value
7. Set `ALLOWED_ORIGINS` to your frontend URL

**Note:** The free tier spins down after 15 minutes of idle. First request after idle takes 30-60s to wake up.

## Frontend

The frontend is in a separate repository: `wave-parser-frontend`

For local development, run frontend on port 5173 and it will proxy `/api` to the backend on 8080.

## Testing

```bash
./mvnw test
```

## Architecture

```
Browser → Backend API → RabbitMQ → Python Worker → Backend → Database
                              ↓
                         Audio Files (/data)
```

- Backend handles: auth, file storage, job management, RabbitMQ messaging, SSE
- Worker (separate repo): Whisper inference only
- Shared volume `/data` for audio files between backend and worker
