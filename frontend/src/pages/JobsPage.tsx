import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../services/api'
import type { JobStatus } from '../constants/types'

function progress(job: JobStatus): string {
  const total = job.totalChunks ?? 0
  const received = job.chunksReceived ?? 0
  if (total <= 0) return job.status === 'COMPLETED' ? 'done' : 'preparing…'
  return `${received} of ${total} chunks`
}

export default function JobsPage() {
  const [jobs, setJobs] = useState<JobStatus[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    api
      .listJobs()
      .then((data) => {
        if (active) setJobs(data)
      })
      .catch((err: unknown) => {
        if (active) setError(err instanceof Error ? err.message : 'Could not load jobs')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  async function remove(id: string) {
    if (!window.confirm('Delete this job and its audio?')) return
    try {
      await api.remove(id)
      setJobs((current) => current.filter((job) => job.jobId !== id))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not delete the job')
    }
  }

  return (
    <div className="stack">
      <div className="row spread">
        <h1>Your jobs</h1>
        <Link className="button" to="/">
          New upload
        </Link>
      </div>

      {error && <p className="error-text">{error}</p>}
      {loading && <p className="hint">Loading…</p>}
      {!loading && jobs.length === 0 && <p className="hint">No jobs yet. Upload a recording to start.</p>}

      <div className="card-list">
        {jobs.map((job) => (
          <div key={job.jobId} className="linked-card">
            <Link to={`/jobs/${job.jobId}`} style={{ textDecoration: 'none', color: 'inherit', flex: 1 }}>
              <div className="row" style={{ gap: 10 }}>
                <strong>{job.originalFileName ?? job.jobId}</strong>
                <span className={`badge ${job.status}`}>{job.status}</span>
              </div>
              <div className="hint" style={{ marginTop: 4 }}>
                {progress(job)} · {new Date(job.createdAt).toLocaleString()}
                {job.failureReason ? ` · ${job.failureReason}` : ''}
              </div>
            </Link>
            <button type="button" className="danger" onClick={() => void remove(job.jobId)}>
              Delete
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}
