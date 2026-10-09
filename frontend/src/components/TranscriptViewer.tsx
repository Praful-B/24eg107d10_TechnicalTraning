import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from '../services/api'
import { formatTime } from '../utils/format'
import type { Transcript, TranscriptMatch, TranscriptSegment } from '../constants/types'

interface Props {
  jobId: string
  transcript: Transcript
  onTranscriptChange: (transcript: Transcript) => void
}

const FORMATS = ['txt', 'srt', 'vtt', 'json']

/** Wraps every case-insensitive occurrence of `query` in a <mark>. */
function highlight(text: string, query: string): ReactNode {
  if (!query.trim()) return text
  const needle = query.trim().toLowerCase()
  const haystack = text.toLowerCase()
  const parts: ReactNode[] = []
  let cursor = 0
  let found = haystack.indexOf(needle)
  let key = 0
  while (found !== -1) {
    if (found > cursor) parts.push(text.slice(cursor, found))
    parts.push(<mark key={key++}>{text.slice(found, found + needle.length)}</mark>)
    cursor = found + needle.length
    found = haystack.indexOf(needle, cursor)
  }
  parts.push(text.slice(cursor))
  return parts
}

export default function TranscriptViewer({ jobId, transcript, onTranscriptChange }: Props) {
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const listRef = useRef<HTMLDivElement | null>(null)
  const [currentTime, setCurrentTime] = useState(0)
  const [query, setQuery] = useState('')
  const [matches, setMatches] = useState<TranscriptMatch[]>([])
  const [searching, setSearching] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<string[]>([])
  const [saving, setSaving] = useState(false)

  const segments = transcript.segments

  const currentIndex = useMemo(() => {
    let index = -1
    for (let i = 0; i < segments.length; i += 1) {
      const start = segments[i].start ?? 0
      if (start <= currentTime + 0.15) index = i
      else break
    }
    return index
  }, [segments, currentTime])

  useEffect(() => {
    if (currentIndex < 0 || editing) return
    const node = listRef.current?.querySelector(`[data-index="${currentIndex}"]`)
    node?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [currentIndex, editing])

  function seek(seconds: number | null) {
    const audio = audioRef.current
    if (audio == null || seconds == null) return
    audio.currentTime = seconds
    void audio.play()
  }

  async function runSearch(event: React.FormEvent) {
    event.preventDefault()
    if (!query.trim()) {
      setMatches([])
      return
    }
    setSearching(true)
    setError(null)
    try {
      setMatches(await api.search(jobId, query.trim()))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Search failed')
    } finally {
      setSearching(false)
    }
  }

  function startEditing() {
    setDraft(segments.map((segment) => segment.text ?? ''))
    setEditing(true)
  }

  async function save() {
    setSaving(true)
    setError(null)
    try {
      const updated: TranscriptSegment[] = segments.map((segment, index) => ({
        start: segment.start,
        end: segment.end,
        text: draft[index],
      }))
      const saved = await api.updateTranscript(jobId, updated)
      onTranscriptChange(saved)
      setEditing(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save changes')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="card stack">
      <div className="row spread">
        <div>
          <h2>Transcript</h2>
          <p className="hint">
            {segments.length} segment(s)
            {transcript.language ? ` · detected language: ${transcript.language}` : ''}
          </p>
        </div>
        <div className="row">
          {FORMATS.map((format) => (
            <a key={format} className="button secondary" href={api.exportUrl(jobId, format)}>
              {format.toUpperCase()}
            </a>
          ))}
        </div>
      </div>

      <audio
        ref={audioRef}
        controls
        preload="metadata"
        src={api.audioUrl(jobId)}
        onTimeUpdate={(event) => setCurrentTime(event.currentTarget.currentTime)}
      />

      <form className="row" onSubmit={runSearch}>
        <input
          placeholder="Search this transcript…"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <button type="submit" disabled={searching}>
          {searching ? 'Searching…' : 'Search'}
        </button>
      </form>

      {error && <p className="error-text">{error}</p>}

      {matches.length > 0 && (
        <div className="match-list">
          <p className="hint">{matches.length} match(es)</p>
          {matches.map((match, index) => (
            <button
              key={`${match.start}-${index}`}
              type="button"
              className="match"
              onClick={() => seek(match.start)}
            >
              <span className="time">{formatTime(match.start)}</span>
              <span>{highlight(match.text ?? '', query)}</span>
            </button>
          ))}
        </div>
      )}

      <div className="row spread">
        <h2 style={{ margin: 0 }}>Segments</h2>
        {editing ? (
          <div className="row">
            <button type="button" className="secondary" onClick={() => setEditing(false)} disabled={saving}>
              Cancel
            </button>
            <button type="button" onClick={save} disabled={saving}>
              {saving ? 'Saving…' : 'Save edits'}
            </button>
          </div>
        ) : (
          <button type="button" className="ghost" onClick={startEditing}>
            Edit text
          </button>
        )}
      </div>

      <div className="segment-list" ref={listRef}>
        {segments.length === 0 && <p className="segment muted">No segments were produced.</p>}
        {segments.map((segment, index) => (
          <div
            key={`${segment.start}-${index}`}
            data-index={index}
            className={`segment${index === currentIndex ? ' current' : ''}`}
          >
            <button type="button" className="time" onClick={() => seek(segment.start)}>
              {formatTime(segment.start)}
            </button>
            {editing ? (
              <textarea
                className="text"
                value={draft[index] ?? ''}
                onChange={(event) => {
                  const next = [...draft]
                  next[index] = event.target.value
                  setDraft(next)
                }}
              />
            ) : (
              <span className="text">{highlight(segment.text ?? '', query)}</span>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
