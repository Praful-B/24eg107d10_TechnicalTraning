import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { uploadWithProgress, ALLOWED_FORMATS, MAX_FILE_SIZE_BYTES } from '../services/api'

// Note: ALLOWED_FORMATS and MAX_FILE_SIZE_BYTES are defined in api.ts to keep them in sync with backend

export default function UploadPage() {
  const navigate = useNavigate()
  const inputRef = useRef<HTMLInputElement | null>(null)
  const [dragging, setDragging] = useState(false)
  const [progress, setProgress] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function send(file: File) {
    setError(null)

    const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
    // Type assertion needed because extension comes from file name, not the const array
    if (!ALLOWED_FORMATS.includes(extension as typeof ALLOWED_FORMATS[number])) {
      setError(`Unsupported format ".${extension}". Allowed: ${ALLOWED_FORMATS.join(', ')}`)
      return
    }
    if (file.size > MAX_FILE_SIZE_BYTES) {
      setError('File is larger than 100 MB')
      return
    }

    setProgress(0)
    try {
      const job = await uploadWithProgress(file, setProgress)
      navigate(`/jobs/${job.jobId}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed')
      setProgress(null)
    }
  }

  return (
    <div className="stack">
      <div>
        <h1>Upload audio</h1>
        <p className="hint">
          WAV, MP3, M4A or FLAC, up to 100 MB. The recording is split on silences and
          transcribed in parallel.
        </p>
      </div>

      <div
        className={`dropzone${dragging ? ' dragging' : ''}`}
        onDragOver={(event) => {
          event.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault()
          setDragging(false)
          const file = event.dataTransfer.files[0]
          if (file) void send(file)
        }}
        onClick={() => inputRef.current?.click()}
        role="button"
        tabIndex={0}
        onKeyDown={(event) => {
          if (event.key === 'Enter') inputRef.current?.click()
        }}
      >
        <p style={{ margin: 0, fontSize: 16 }}>
          {progress === null ? 'Drop an audio file here, or click to browse' : 'Uploading…'}
        </p>
        <input
          ref={inputRef}
          type="file"
          accept=".wav,.mp3,.m4a,.flac,audio/*"
          hidden
          onChange={(event) => {
            const file = event.target.files?.[0]
            if (file) void send(file)
          }}
        />
      </div>

      {progress !== null && (
        <div className="stack" style={{ gap: 6 }}>
          <div className="progress-track">
            <div className="progress-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
          </div>
          <span className="hint">{Math.round(progress * 100)}% uploaded</span>
        </div>
      )}

      {error && <p className="error-text">{error}</p>}
    </div>
  )
}
