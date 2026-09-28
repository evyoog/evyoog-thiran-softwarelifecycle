import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '@/auth/AuthProvider'
import { Sun, Moon, LogOut, Command } from 'lucide-react'
import { useEffect, useState } from 'react'
import { NAV } from './nav'
import { CommandPalette } from './CommandPalette'
import { NotificationBell } from './NotificationBell'
import { LiveAnnouncer } from '@/shared/ui/Announcer'
import { useMe } from '@/shared/useMe'

export function Shell() {
  const auth = useAuth()
  const { data: me } = useMe()
  const [theme, setTheme] = useState<'dark' | 'light'>(
    () => (localStorage.getItem('vyoog-theme') as 'dark' | 'light') ?? 'dark',
  )

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('vyoog-theme', theme)
  }, [theme])

  const name = auth.user?.username ?? auth.user?.email ?? 'you'

  // VYB-0765 AC2: an item requiring administration is dropped entirely, not shown disabled — while `me` is still loading, it's held back rather than flashed.
  const visibleNav = NAV.filter((item) => !item.requiresAdmin || me?.platformAdministrator)

  return (
    <div className="shell">
      <LiveAnnouncer />
      <CommandPalette items={visibleNav} />
      <aside className="sb">
        <div className="sb-h">
          <span className="sb-logo">Vyoog</span>
        </div>

        <nav className="sb-nav">
          {visibleNav.map((item) => (
            <div key={item.key}>
              {item.divideBefore && <div className="sb-div" />}
              <NavLink
                to={item.path}
                end={item.path === '/'}
                className={({ isActive }) => `sb-i${isActive ? ' on' : ''}`}
              >
                <item.icon />
                <span>{item.label}</span>
              </NavLink>
            </div>
          ))}
        </nav>

        <div className="sb-f">
          <span className="mono" style={{ fontSize: 10, color: 'var(--tx-3)' }}>
            {name}
          </span>
          <div className="sp" />
          <button
            className="btn"
            style={{ padding: 5 }}
            title="Sign out"
            onClick={() => auth.logout()}
          >
            <LogOut />
          </button>
        </div>
      </aside>

      <div className="main">
        <div className="topbar">
          <span className="eyebrow">Requirements platform</span>
          <div className="sp" />
          <span className="hint muted" style={{ fontSize: 10.5, display: 'flex', alignItems: 'center', gap: 4 }}>
            <Command style={{ width: 12, height: 12 }} />K for the command palette
          </span>
          <NotificationBell />
          <button
            className="btn"
            style={{ padding: 5 }}
            title="Toggle theme"
            onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
          >
            {theme === 'dark' ? <Sun /> : <Moon />}
          </button>
        </div>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
