# Production Deployment Guide - Render + Netlify

This guide covers deploying the Wave Parser application to:
- **Backend**: Render Free Tier (Web Service + PostgreSQL + RabbitMQ)
- **Frontend**: Netlify Free Tier (Static site)

## Architecture

```
┌─────────────────┐         ┌─────────────────┐
│   Netlify       │         │     Render      │
│   Frontend      │────────▶│   Backend       │
│   (Static)      │  API    │   (Spring Boot) │
│                 │         │                 │
└─────────────────┘         │  ┌───────────┐  │
                            │  │ PostgreSQL│  │
                            │  └───────────┘  │
                            │  ┌───────────┐  │
                            │  │ RabbitMQ  │  │
                            │  └───────────┘  │
                            └─────────────────┘
```

**Note**: For full transcription functionality, you also need a **Whisper Worker** running somewhere (Render Worker, a VPS, or a separate service). The free Render web service doesn't run background workers.

---

## Step 1: Set Up External Services on Render

### 1.1 Create PostgreSQL Database

1. Go to [Render Dashboard](https://dashboard.render.com)
2. Click **New** → **PostgreSQL**
3. Configure:
   - **Name**: `wave-parser-db`
   - **Database Name**: `honeycomb`
   - **User**: `praful` (or leave default)
   - **Region**: Closest to you
   - **Instance Type**: Free
4. Click **Create Database**
5. Copy the **Internal Database URL** (looks like: `postgresql://user:pass@host:port/dbname`)

### 1.2 Create RabbitMQ Service

*Note*: Render doesn't offer a free RabbitMQ managed service. Options:
- **Option A**: Use [CloudAMQP](https://cloudamqp.com) free tier (recommended)
- **Option B**: Run RabbitMQ in a Render Worker (more complex)
- **Option C**: Skip RabbitMQ for demo - process audio synchronously (hacky)

For this guide, we'll use **CloudAMQP**:

1. Go to [CloudAMQP](https://cloudamqp.com)
2. Sign up for free account
3. Create a new instance (free tier: `little-lemur`)
4. Copy the **AMQP URL** (looks like: `amqp://user:pass@host/vhost`)

---

## Step 2: Deploy Backend on Render

### 2.1 Create Web Service

1. Go to [Render Dashboard](https://dashboard.render.com)
2. Click **New** → **Web Service**
3. Connect your GitHub repository containing the `backend/` folder
4. Configure:
   - **Name**: `wave-parser-backend`
   - **Region**: Same as your database
   - **Branch**: `main`
   - **Root Directory**: `backend` (important if repo has both frontend and backend)
   - **Runtime**: `Docker`
   - **Build Command**: (auto-detected from Dockerfile)
   - **Start Command**: (auto-detected from Dockerfile)
   - **Instance Type**: Free

### 2.2 Add Environment Variables

In the Render dashboard for your backend service, add these environment variables:

```
# Database (from Step 1.1)
# Use the host/port from Render's "Internal Database URL".
# Spring Boot needs the "jdbc:" prefix. (A bare postgresql:// URL also works -
# the app normalizes it automatically.)
SPRING_DATASOURCE_URL=jdbc:postgresql://host:port/honeycomb
SPRING_DATASOURCE_USERNAME=praful
SPRING_DATASOURCE_PASSWORD=your-db-password

# RabbitMQ (from Step 1.2 - CloudAMQP)
SPRING_RABBITMQ_HOST=your-cloudamqp-host
SPRING_RABBITMQ_PORT=5672
SPRING_RABBITMQ_USERNAME=your-cloudamqp-user
SPRING_RABBITMQ_PASSWORD=your-cloudamqp-pass

# JWT Secret (generate with: openssl rand -base64 48)
JWT_SECRET=generate-a-secure-random-value-here

# Frontend URL (your Netlify URL from Step 3)
ALLOWED_ORIGINS=https://your-frontend-app.netlify.app

# Storage (Render filesystem is ephemeral, use external storage for production)
APP_STORAGE_DIR=/tmp
APP_STORAGE_RETENTION_DAYS=7

# Optional: Disable worker features if no worker is running
# WHISPER_DEVICE=disable
```

### 2.3 Deploy

1. Click **Create Web Service**
2. Wait for deployment (2-3 minutes)
3. Check logs for any errors
4. Your backend URL will be: `https://wave-parser-backend.onrender.com`

---

## Step 3: Deploy Frontend on Netlify

### 3.1 Build and Deploy

**Option A: GitHub Integration (Recommended)**

1. Push your `frontend/` folder to a GitHub repository (or use the monorepo)
2. Go to [Netlify](https://netlify.com)
3. Click **Add new site** → **Import from Git**
4. Select your frontend repository
5. Configure:
   - **Build command**: `npm install && npm run build`
   - **Publish directory**: `dist`
   - **Environment variables**:
     ```
     VITE_API_BASE_URL=https://wave-parser-backend.onrender.com
     ```
6. Click **Deploy site**

**Option B: Drag and Drop (Quickest)**

1. Build locally: `cd frontend && npm run build`
2. Go to [Netlify Drop](https://app.netlify.com/drop)
3. Drag the `frontend/dist/` folder
4. Done! Get your URL

### 3.3 Get Your Frontend URL

Your frontend URL will be something like:
- `https://random-name.netlify.app` (default)
- Or a custom domain you configure

---

## Step 4: Connect Frontend and Backend

### 4.1 Update Backend CORS

1. In Render dashboard, update the backend's `ALLOWED_ORIGINS` env var:
   ```
   ALLOWED_ORIGINS=https://your-frontend-app.netlify.app
   ```
2. **Restart** the backend service for changes to take effect

### 4.2 Verify Connection

1. Open your Netlify frontend URL
2. Try to register/login
3. API calls should go to your Render backend

---

## Step 5: (Optional) Deploy Whisper Worker

For full transcription functionality, you need a worker that processes audio chunks.

### Option A: Render Worker Service

1. Create a new **Worker** service on Render
2. Connect the same repo, set root directory to `whisper/` (or `whisper`)
3. Use the `whisper/Dockerfile`
4. Add environment variables:
   ```
   RABBITMQ_HOST=your-cloudamqp-host
   RABBITMQ_USER=your-cloudamqp-user
   RABBITMQ_PASS=your-cloudamqp-pass
   WHISPER_MODEL_SIZE=base
   WHISPER_DEVICE=cpu
   ```
5. Note: Free tier workers may sleep - transcription will be slow

### Option B: Skip Worker for Demo

If you just need to demo the UI:
1. Modify backend to process audio synchronously (no RabbitMQ)
2. Or pre-transcribe some audio files and store results

---

## Troubleshooting

### Backend Won't Start
- Check Render logs for errors
- Verify database connection settings
- Ensure `JWT_SECRET` is set

### CORS Errors
- Verify `ALLOWED_ORIGINS` includes your Netlify URL
- Restart backend after updating env vars
- Check browser console for exact error

### 502/503 Errors
- Render free tier sleeps after 15 min of inactivity
- First request after sleep takes 30-60 seconds to wake up
- This is normal for free tier

### Audio Upload Fails
- Check file size limits (100MB max)
- Verify backend has write permissions to storage dir
- For Render: use `/tmp` as storage (ephemeral)

---

## Environment Variables Summary

### Backend (Render)
```
SPRING_DATASOURCE_URL=postgresql://...
SPRING_DATASOURCE_USERNAME=praful
SPRING_DATASOURCE_PASSWORD=...
SPRING_RABBITMQ_HOST=...
SPRING_RABBITMQ_PORT=5672
SPRING_RABBITMQ_USERNAME=...
SPRING_RABBITMQ_PASSWORD=...
JWT_SECRET=... (required)
ALLOWED_ORIGINS=https://your-netlify-url.netlify.app
APP_STORAGE_DIR=/tmp
APP_STORAGE_RETENTION_DAYS=7
```

### Frontend (Netlify)
```
VITE_API_BASE_URL=https://your-backend.onrender.com
```

---

## Quick Reference Links

- Render Dashboard: https://dashboard.render.com
- Netlify Dashboard: https://app.netlify.com
- CloudAMQP (free RabbitMQ): https://cloudamqp.com
- Generate JWT secret: `openssl rand -base64 48`
