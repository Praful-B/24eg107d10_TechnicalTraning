# Wave Parser Frontend

React + TypeScript frontend for the Wave Parser audio transcription app.

## Features

- User authentication (login/register)
- Drag-and-drop file upload with progress bar
- Real-time job progress via Server-Sent Events
- Transcript viewer with timestamped segments
- Inline editing of transcripts
- Search within transcripts
- Export to TXT, SRT, VTT, JSON formats
- Audio player with seeking
- Dark/light theme toggle

## Tech Stack

- React 19 + TypeScript
- React Router v7
- Vite for build tooling
- CSS custom properties (no Tailwind)
- Context API for auth state

## Quick Start

### Local Development

```bash
# 1. Install dependencies
npm install

# 2. Start dev server (proxies /api to localhost:8080)
npm run dev
```

Frontend will start on http://localhost:5173

The dev server automatically proxies API calls to the backend running on port 8080.

### Production Build

```bash
npm run build
```

Output goes to `dist/` folder. You can:
- Serve with any static host (Netlify, Vercel, nginx, etc.)
- Or use the included Dockerfile for Docker deployment

## Environment Variables

### Build-time (via Vite)

Create a `.env` file or set environment variables:

```bash
# API base URL - where the backend is hosted
# For local dev: defaults to /api (proxied by Vite)
# For production: set to your backend URL
VITE_API_BASE_URL=https://your-backend.onrender.com
```

**Important:** Variables must start with `VITE_` to be exposed to the client.

### Runtime (via docker-compose or hosting platform)

For Docker deployment, the backend URL can be set via:
- Build arg: `docker build --build-arg VITE_API_BASE_URL=https://...`
- Or update `nginx.conf` proxy_pass if backend is on same network

## Deployment Options

### Option 1: Static Hosting (Recommended for demo)

Deploy the `dist/` folder to:
- **Netlify**: Drag-and-drop the `dist/` folder or connect GitHub
- **Vercel**: Connect GitHub repo
- **Render Static Sites**: Upload `dist/`
- **GitHub Pages**: Push `dist/` to `gh-pages` branch

Set environment variable `VITE_API_BASE_URL` to your backend URL.

**Example for Netlify:**
1. Build: `npm install && npm run build`
2. Publish directory: `dist`
3. Environment: `VITE_API_BASE_URL=https://your-backend.onrender.com`

### Option 2: Docker on Render

```bash
# Build
docker build -t wave-parser-frontend .

# Run
docker run -p 80:80 \
  -e VITE_API_BASE_URL=https://your-backend.onrender.com \
  wave-parser-frontend
```

Or use `docker-compose.yml` if deploying with backend on same host.

## Connecting to Backend

The frontend expects the backend at the URL set in `VITE_API_BASE_URL`.

For local development:
- Frontend: http://localhost:5173
- Backend: http://localhost:8080
- Vite proxies `/api` → `http://localhost:8080/api`

For production:
- Frontend: https://your-app.netlify.app
- Backend: https://your-backend.onrender.com
- Set `VITE_API_BASE_URL=https://your-backend.onrender.com`

## Project Structure

```
src/
├── components/       # Reusable UI components
│   ├── Layout.tsx           # App shell with navigation
│   ├── ProtectedRoute.tsx   # Auth guard for routes
│   └── TranscriptViewer.tsx # Transcript display + editing
├── pages/            # Page components (routes)
│   ├── AuthPage.tsx         # Login/Register
│   ├── UploadPage.tsx       # File upload
│   ├── JobsPage.tsx         # Job list
│   └── JobDetailPage.tsx    # Job detail + transcript
├── services/         # Business logic
│   ├── api.ts               # API client + shared constants
│   ├── auth.tsx             # Auth context + utilities
│   └── theme.ts             # Theme management
├── constants/        # Type definitions
│   └── types.ts
├── utils/            # Utility functions
│   └── format.ts
├── main.tsx          # Entry point
├── App.tsx           # Router setup
└── styles.css        # Global styles
```

## Building for Production

```bash
# Install dependencies
npm ci

# Build for production
npm run build

# Preview production build locally
npm run preview
```

## Environment Setup for Demo Day

1. Deploy backend on Render
2. Deploy frontend on Netlify (or Render Docker)
3. Set `VITE_API_BASE_URL` on frontend to backend URL
4. Set `ALLOWED_ORIGINS` on backend to frontend URL
5. Test the full flow: register → upload → wait → view transcript

## Testing

```bash
npm run typecheck  # TypeScript checks
npm run lint       # ESLint
```

## Notes

- The app is a single-page application (SPA)
- All API calls go through `services/api.ts`
- Auth state is managed via React Context
- Theme preference is stored in localStorage
- Token is stored in localStorage (for demo purposes; httpOnly cookies are more secure for production)
