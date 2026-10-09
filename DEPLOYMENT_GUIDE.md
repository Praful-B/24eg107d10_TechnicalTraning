# College Demo Deployment Guide

## Quick Setup (Do this BEFORE demo day)

### 1. Backend on Render

1. Go to https://render.com
2. Create new **Web Service**
3. Connect your `backend/` GitHub repo
4. Configure:
   - **Name**: wave-parser-backend
   - **Region**: Closest to you
   - **Branch**: main
   - **Root Directory**: Leave blank (or set to `backend/` if repo contains both)
   - **Runtime**: Docker (or Java if you prefer)
   - **Build Command**: `./mvnw package -DskipTests`
   - **Start Command**: `java -jar target/*.jar`
   - **Instance Type**: Free

5. Add Environment Variables:
   ```
   JWT_SECRET=<generate with: openssl rand -base64 48>
   POSTGRES_USER=praful
   POSTGRES_PASSWORD=<choose a strong password>
   RABBITMQ_USER=guest
   RABBITMQ_PASSWORD=<choose a strong password>
   ALLOWED_ORIGINS=https://your-frontend-app.netlify.app
   APP_STORAGE_RETENTION_DAYS=7
   ```

6. Click **Create Web Service**
7. Wait for deployment (2-3 minutes)

### 2. Frontend on Netlify

**Option A: Drag and Drop (easiest)**
1. Build frontend: `cd frontend && npm run build`
2. Go to https://netlify.com
3. Drag the `frontend/dist/` folder to the deploy area
4. Done! Get your URL (e.g., https://random-name.netlify.app)

**Option B: GitHub Integration**
1. Push `frontend/` to a GitHub repo
2. On Netlify, click "Add new site" → "Import from Git"
3. Select your frontend repo
4. Configure:
   - **Build command**: `npm install && npm run build`
   - **Publish directory**: `dist`
   - **Environment variables**: `VITE_API_BASE_URL=https://your-backend.onrender.com`
5. Click "Deploy site"

### 3. Connect Them

1. Get your backend URL from Render (e.g., https://wave-parser-backend.onrender.com)
2. Get your frontend URL from Netlify (e.g., https://wave-parser-frontend.netlify.app)
3. Update `ALLOWED_ORIGINS` on backend to: `https://wave-parser-frontend.netlify.app`
4. Restart backend on Render

### 4. Test Before Demo

Open your frontend URL and:
- ✅ Register a new account
- ✅ Upload an audio file
- ✅ Wait for transcription (may take 1-2 min on free tier)
- ✅ View transcript
- ✅ Play audio
- ✅ Export transcript (try SRT or TXT)
- ✅ Search within transcript

## During Demo Day

### What to Show

1. **Upload page** - Drag-and-drop an audio file
2. **Jobs list** - Show the job appearing
3. **Job detail** - Show progress bar (chunks processing)
4. **Transcript** - When complete, show the full transcript
5. **Audio player** - Play the audio and show seeking works
6. **Search** - Search for a word in the transcript
7. **Export** - Download as SRT or TXT
8. **Edit transcript** - Make a quick edit and save

### If Backend is Slow (Free Tier)

The free tier on Render sleeps after 15 minutes of idle. If the backend is slow:
- Say: "The backend is waking up from sleep mode - this is expected on the free tier"
- Wait 30-60 seconds
- Or pre-upload a file before the demo and show the completed result

### Backup Plan

Have a pre-transcribed job ready to show in case of issues:
- Upload a file before the demo
- Take screenshots of the full flow
- If live demo fails, show screenshots

## Troubleshooting

### Backend won't start
- Check Render logs for errors
- Make sure JWT_SECRET is set
- Verify PostgreSQL and RabbitMQ are accessible

### Frontend can't reach backend
- Check `VITE_API_BASE_URL` is set correctly
- Verify backend URL is accessible
- Check CORS settings on backend (`ALLOWED_ORIGINS`)

### Job stuck in PROCESSING
- Check if worker is running (separate deployment needed)
- Or the job might have failed - check logs

### Audio playback issues
- Make sure audio file was uploaded successfully
- Check browser console for errors

## Environment Variables Summary

### Backend (.env)
```
JWT_SECRET=<required - generate with openssl rand -base64 48>
POSTGRES_USER=praful
POSTGRES_PASSWORD=<change this>
RABBITMQ_USER=guest
RABBITMQ_PASSWORD=<change this>
ALLOWED_ORIGINS=https://your-frontend-url.netlify.app
```

### Frontend (.env or Netlify settings)
```
VITE_API_BASE_URL=https://your-backend.onrender.com
```

## Files to Submit

For your college submission, provide:
1. Link to `backend/` GitHub repo
2. Link to `frontend/` GitHub repo
3. Live demo URL (frontend)
4. Architecture diagram (optional but impressive)
5. Brief documentation of features

---

**Good luck with your demo!** 🎉
