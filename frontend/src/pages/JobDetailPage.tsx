import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../services/api'
import TranscriptViewer from '../components/TranscriptViewer'
import type { JobEvent, JobStatus, Transcript } from '../constants/types'

export default function JobDetailPage() {
  const { id = '' } = useParams()
  const [job, setJob] = useState<JobStatus | null>(null)
  const [transcript, setTranscript] = useState<Transcript | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [retrying, setRetrying] = useState(false)
  const loadedTranscriptFor = useRef<string | null>(null)
  const terminal = useRef(false)

  const loadTranscript = useCallback(async () => {
    if (loadedTranscriptFor.current === id) return
    loadedTranscriptFor.current = id
    try {
      setTranscript(await api.getTranscript(id))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load the transcript')
    }
  }, [id])

  const applyStatus = useCallback(
    (status: JobStatus) => {
      setJob(status)
      if (status.status === 'COMPLETED') {
        void loadTranscript()
      }
    },
    [loadTranscript],
  )

  // Initial fetch, then live updates keep the page in sync without polling.
  useEffect(() => {
    let active = true
    api
      .getJob(id)
      .then((status) => {
        if (active) applyStatus(status)
      })
      .catch((err: unknown) => {
        if (active) setError(err instanceof Error ? err.message : 'Could not load the job')
      })
    return () => {
      active = false
    }
  }, [id, applyStatus])

  useEffect(() => {
    const source = new EventSource(api.eventsUrl(id))
    source.addEventListener('status', (event) => {
      const data = JSON.parse((event as MessageEvent<string>).data) as JobEvent
      setJob((current) => {
        if (!current) return current
        return {
          ...current,
          status: data.status,
          chunksReceived: data.chunksReceived,
          totalChunks: data.totalChunks,
          failureReason: data.failureReason,
        }
      })
      if (data.status === 'COMPLETED') {
        void loadTranscript()
      }
      if (data.status === 'COMPLETED' || data.status === 'FAILED') {
        // The backend closes the stream once the job is done; do not reconnect.
        terminal.current = true
        source.close()
      }
    })
    source.onerror = () => {
      // A transient drop: EventSource reconnects on its own and the backend replays
      // the current status. Only stay closed once the job has finished.
      if (terminal.current) {
        source.close()
      }
    }
    return () => source.close()
  }, [id, loadTranscript])

  async function retry() {
    setRetrying(true)
    setError(null)
    try {
      const status = await api.retry(id)
      loadedTranscriptFor.current = null
      setTranscript(null)
      applyStatus(status)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not retry the job')
    } finally {
      setRetrying(false)
    }
  }

  if (!job) {
    return (
      <div className="stack">
        {error ? <p className="error-text">{error}</p> : <p className="hint">Loading job…</p>}
        <Link to="/jobs">Back to jobs</Link>
      </div>
    )
  }

  const total = job.totalChunks ?? 0
  const received = job.chunksReceived ?? 0
  const percent = total > 0 ? Math.min(100, Math.round((received / total) * 100)) : 0
  const running = job.status === 'PREPARING' || job.status === 'PROCESSING' || job.status === 'SAVED'

  return (
    <div className="stack">
      <div className="card stack">
        <div className="row spread">
          <div>
            <h1>{job.originalFileName ?? job.jobId}</h1>
            <p className="hint">
              Started {new Date(job.createdAt).toLocaleString()}
              {job.language ? ` · language: ${job.language}` : ''}
            </p>
          </div>
          <span className={`badge ${job.status}`}>{job.status}</span>
        </div>

        {running && (
          <div className="stack" style={{ gap: 6 }}>
            <div className="progress-track">
              <div className="progress-fill" style={{ width: `${percent}%` }} />
            </div>
            <span className="hint">
              {total > 0 ? `${received} of ${total} chunks done` : 'Preparing audio…'}
            </span>
          </div>
        )}

        {job.status === 'FAILED' && (
          <div className="stack" style={{ gap: 10 }}>
            <p className="error-text">{job.failureReason ?? 'The job failed.'}</p>
            <div className="row">
              <button type="button" onClick={retry} disabled={retrying}>
                {retrying ? 'Retrying…' : 'Retry'}
              </button>
              <Link className="button secondary" to="/">
                Upload again
              </Link>
            </div>
          </div>
        )}
      </div>

      {error && <p className="error-text">{error}</p>}

      {transcript && transcript.segments.length > 0 && (
        <TranscriptViewer jobId={id} transcript={transcript} onTranscriptChange={setTranscript} />
      )}

      {job.status === 'COMPLETED' && transcript === null && <p className="hint">Loading transcript…</p>}
    </div>
  )
}
