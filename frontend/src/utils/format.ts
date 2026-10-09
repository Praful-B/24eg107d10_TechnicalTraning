/** Formats seconds as mm:ss (or h:mm:ss past an hour) for transcript timestamps. */
export function formatTime(seconds: number | null): string {
  if (seconds == null || Number.isNaN(seconds)) return '--:--'
  const total = Math.max(0, Math.floor(seconds))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const secs = total % 60
  const stamp = `${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
  return hours > 0 ? `${hours}:${stamp}` : stamp
}
