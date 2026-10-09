import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api, emailFromToken, getToken, setToken } from './api'

// Re-export utility functions for convenience
/**
 * Decodes the JWT payload to get the user email without an API call.
 * Used by the UI to display the signed-in user.
 */
export { emailFromToken, getToken, setToken }

interface AuthValue {
  token: string | null
  email: string | null
  login: (email: string, password: string) => Promise<void>
  register: (username: string, email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(() => getToken())

  const apply = useCallback((next: string | null) => {
    setToken(next)
    setTokenState(next)
  }, [])

  const login = useCallback(
    async (email: string, password: string) => {
      const response = await api.login(email, password)
      apply(response.token)
    },
    [apply],
  )

  const register = useCallback(
    async (username: string, email: string, password: string) => {
      const response = await api.register(username, email, password)
      apply(response.token)
    },
    [apply],
  )

  const logout = useCallback(() => apply(null), [apply])

  const value = useMemo<AuthValue>(
    () => ({ token, email: emailFromToken(token), login, register, logout }),
    [token, login, register, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthValue {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider')
  }
  return context
}
