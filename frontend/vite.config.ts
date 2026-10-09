import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// The dev server proxies API calls to Spring Boot so the browser sees one origin.
// In production, set VITE_API_BASE_URL to your backend URL.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': 'http://localhost:8080',
        '/actuator': 'http://localhost:8080',
      },
    },
    define: {
      // Make API base URL available to the app
      'import.meta.env.VITE_API_BASE_URL': JSON.stringify(
        env.VITE_API_BASE_URL || '/api'
      ),
    },
  }
})
