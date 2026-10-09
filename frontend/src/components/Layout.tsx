import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../services/auth'
import { useTheme } from '../services/theme'

export default function Layout() {
  const { email, logout } = useAuth()
  const { theme, toggle } = useTheme()
  const navigate = useNavigate()

  return (
    <div className="app-shell">
      <header className="topbar">
        <NavLink to="/" className="brand">
          Wave Parser
        </NavLink>
        <nav className="nav-links">
          <NavLink to="/" end>
            Upload
          </NavLink>
          <NavLink to="/jobs">Jobs</NavLink>
        </nav>
        <span className="topbar-user">{email}</span>
        <button className="ghost" type="button" onClick={toggle} title="Toggle dark mode">
          {theme === 'dark' ? '☀ Light' : '🌙 Dark'}
        </button>
        <button
          className="ghost"
          type="button"
          onClick={() => {
            logout()
            navigate('/login')
          }}
        >
          Sign out
        </button>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}
