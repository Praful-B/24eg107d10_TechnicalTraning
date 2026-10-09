import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '../services/auth'

export default function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const { token, login, register } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (token) {
    return <Navigate to="/" replace />
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (mode === 'register') {
        await register(username, email, password)
      } else {
        await login(email, password)
      }
      navigate('/')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  const isRegister = mode === 'register'

  return (
    <div className="auth-page card">
      <h1>{isRegister ? 'Create an account' : 'Sign in'}</h1>
      <p className="hint" style={{ marginBottom: 20 }}>
        Transcribe audio asynchronously with local Whisper models.
      </p>

      <form onSubmit={submit}>
        {isRegister && (
          <div className="field">
            <label htmlFor="username">Name</label>
            <input
              id="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              required
            />
          </div>
        )}
        <div className="field">
          <label htmlFor="email">Email</label>
          <input
            id="email"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            type="password"
            value={password}
            minLength={6}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </div>

        {error && <p className="error-text">{error}</p>}

        <button type="submit" disabled={busy} style={{ width: '100%', justifyContent: 'center' }}>
          {busy ? 'Please wait…' : isRegister ? 'Sign up' : 'Sign in'}
        </button>
      </form>

      <p className="hint" style={{ marginTop: 16 }}>
        {isRegister ? (
          <>
            Already registered? <Link to="/login">Sign in</Link>
          </>
        ) : (
          <>
            No account? <Link to="/register">Create one</Link>
          </>
        )}
      </p>
    </div>
  )
}
