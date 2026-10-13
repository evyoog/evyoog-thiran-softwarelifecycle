import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '@/auth/AuthProvider'
import { Sun, Moon, LogOut, Command } from 'lucide-react'
import { useEffect, useState } from 'react'
import { NAV } from './nav'
import { CommandPalette } from './CommandPalette'
import { NotificationBell } from './NotificationBell'
import { RouteBoundary } from './RouteBoundary'
import { LiveAnnouncer } from '@/shared/ui/Announcer'
import { useMe } from '@/shared/useMe'
import { useLocale } from '@/shared/i18n/LocaleProvider'
import { watchLabels } from '@/shared/a11y/associateLabels'
import { LocaleSwitcher } from '@/shared/i18n/LocaleSwitcher'

export function Shell() {
  const auth = useAuth()
  const { data: me } = useMe()
  const { t } = useLocale()
  const [theme, setTheme] = useState<'dark' | 'light'>(
    () => (localStorage.getItem('vyoog-theme') as 'dark' | 'light') ?? 'dark',
  )

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('vyoog-theme', theme)
  }, [theme])

  // VYB-0958: join each form field's label to its control as screens render (see shared/a11y/associateLabels).
  useEffect(() => watchLabels(document), [])

  const name = auth.user?.username ?? auth.user?.email ?? t('shell.you')

  // VYB-0765 AC2: an item requiring administration is dropped entirely, not shown disabled — while `me` is still loading, it's held back rather than flashed.
  const visibleNav = NAV.filter((item) => !item.requiresAdmin || me?.platformAdministrator)

  return (
    <div className="shell">
      <a href="#main-content" className="skip-link">{t('shell.skip')}</a>
      <LiveAnnouncer />
      <CommandPalette items={visibleNav} />
      <aside className="sb" aria-label={t('shell.sidebar')}>
        <div className="sb-h">
          <span className="sb-logo">Vyoog</span>
        </div>

        <nav className="sb-nav" aria-label={t('shell.mainMenu')}>
          {visibleNav.map((item) => (
            <div key={item.key}>
              {item.divideBefore && <div className="sb-div" />}
              <NavLink
                to={item.path}
                end={item.path === '/'}
                className={({ isActive }) => `sb-i${isActive ? ' on' : ''}`}
              >
                <item.icon />
                <span>{t(item.labelKey)}</span>
              </NavLink>
            </div>
          ))}
        </nav>

        <div className="sb-f">
          <span className="mono" style={{ fontSize: 10, color: 'var(--tx-3)' }} title={t('shell.signedInAs', { name })}>
            {name}
          </span>
          <div className="sp" />
          <button
            className="btn"
            style={{ padding: 5 }}
            title={t('shell.signOut')}
            aria-label={t('shell.signOut')}
            onClick={() => auth.logout()}
          >
            <LogOut aria-hidden="true" />
          </button>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <span className="eyebrow">{t('shell.platform')}</span>
          <div className="sp" />
          <span className="hint muted" style={{ fontSize: 10.5, display: 'flex', alignItems: 'center', gap: 4 }}>
            <Command style={{ width: 12, height: 12 }} aria-hidden="true" />{t('shell.paletteHint')}
          </span>
          <LocaleSwitcher compact />
          <NotificationBell />
          <button
            className="btn"
            style={{ padding: 5 }}
            title={t('shell.toggleTheme')}
            aria-label={t('shell.toggleTheme')}
            onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
          >
            {theme === 'dark' ? <Sun aria-hidden="true" /> : <Moon aria-hidden="true" />}
          </button>
        </header>
        <main className="content" id="main-content" tabIndex={-1}>
          <RouteBoundary><Outlet /></RouteBoundary>
        </main>
      </div>
    </div>
  )
}
