import type {
  AuthResponse,
  JobStatus,
  Transcript,
  TranscriptMatch,
  TranscriptSegment,
} from '../constants/types'

// Token storage key
const TOKEN_KEY = 'waveparser.token'

// Shared constants - keep in sync with backend UploadService.ALLOWED_EXTENSIONS
/** Audio formats accepted by the backend */
export const ALLOWED_FORMATS = ['wav', 'mp3', 'm4a', 'flac'] as const

/** Maximum file size the backend accepts (100 MB) */
export const MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024

/** API base path - configured via VITE_API_BASE_URL env var, defaults to /api */
export const API_BASE = (import.meta.env.VITE_API_BASE_URL as string) || '/api'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null): void {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

/** Decodes the JWT payload so the UI can show who is signed in without an extra endpoint. */
export function emailFromToken(token: string | null): string | null {
  if (!token) return null
  try {
    const payload = token.split('.')[1]
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    const claims = JSON.parse(json) as { sub?: string }
    return claims.sub ?? null
  } catch {
    return null
  }
}

export class ApiError extends Error {
  status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function readError(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string; error?: string }
    return body.message ?? body.error ?? `Request failed (${response.status})`
  } catch {
    return `Request failed (${response.status})`
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  if (init.body && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(path, { ...init, headers })
  if (!response.ok) {
    throw new ApiError(await readError(response), response.status)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export const api = {
  register: (username: string, email: string, password: string) =>
    request<AuthResponse>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, email, password }),
    }),

  login: (email: string, password: string) =>
    request<AuthResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),

  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<{ jobId: string; status: string }>('/api/jobs', {
      method: 'POST',
      body: form,
    })
  },

  listJobs: () => request<JobStatus[]>('/api/jobs'),

  getJob: (id: string) => request<JobStatus>(`/api/jobs/${id}`),

  getTranscript: (id: string) => request<Transcript>(`/api/jobs/${id}/transcript`),

  updateTranscript: (id: string, segments: TranscriptSegment[]) =>
    request<Transcript>(`/api/jobs/${id}/transcript`, {
      method: 'PUT',
      body: JSON.stringify({ segments }),
    }),

  search: (id: string, query: string) =>
    request<TranscriptMatch[]>(`/api/jobs/${id}/search?q=${encodeURIComponent(query)}`),

  retry: (id: string) => request<JobStatus>(`/api/jobs/${id}/retry`, { method: 'POST' }),

  remove: (id: string) => request<void>(`/api/jobs/${id}`, { method: 'DELETE' }),

  /** <audio> and EventSource cannot send headers, so the token goes in the query string. */
  audioUrl: (id: string) => withToken(`/api/jobs/${id}/audio`),

  eventsUrl: (id: string) => withToken(`/api/jobs/${id}/events`),

  exportUrl: (id: string, format: string) =>
    withToken(`/api/jobs/${id}/export?format=${format}`),
}

/** Uploads with a real progress bar; fetch cannot report request progress. */
export function uploadWithProgress(
  file: File,
  onProgress: (fraction: number) => void,
): Promise<{ jobId: string; status: string }> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/jobs')
    const token = getToken()
    if (token) {
      xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        onProgress(event.loaded / event.total)
      }
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(JSON.parse(xhr.responseText) as { jobId: string; status: string })
        return
      }
      let message = `Upload failed (${xhr.status})`
      try {
        const body = JSON.parse(xhr.responseText) as { message?: string; error?: string }
        message = body.message ?? body.error ?? message
      } catch {
        // Non-JSON error body, keep the generic message.
      }
      reject(new ApiError(message, xhr.status))
    }
    xhr.onerror = () => reject(new ApiError('Network error during upload', 0))

    const form = new FormData()
    form.append('file', file)
    xhr.send(form)
  })
}

function withToken(url: string): string {
  const token = getToken()
  if (!token) return url
  return `${url}${url.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`
}
