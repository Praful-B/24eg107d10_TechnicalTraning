export type MessageStatus =
  | 'SAVED'
  | 'PREPARING'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED'

export interface JobStatus {
  jobId: string
  status: MessageStatus
  originalFileName: string | null
  totalChunks: number | null
  chunksReceived: number | null
  language: string | null
  failureReason: string | null
  hasTranscript: boolean
  createdAt: string
  updatedAt: string
}

export interface TranscriptSegment {
  start: number | null
  end: number | null
  text: string | null
}

export interface Transcript {
  jobId: string
  status: MessageStatus
  language: string | null
  text: string | null
  segments: TranscriptSegment[]
}

export interface TranscriptMatch {
  start: number | null
  end: number | null
  text: string | null
}

export interface JobEvent {
  jobId: string
  status: MessageStatus
  chunksReceived: number | null
  totalChunks: number | null
  failureReason: string | null
}

export interface AuthResponse {
  token: string
}
