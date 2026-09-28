import { useState, type FormEvent } from 'react'
import {
  AlertCircle, Eye, EyeOff, GitBranch, ListChecks, Loader2, LogIn, Lock, ScrollText, ShieldCheck, User,
} from 'lucide-react'
import { useAuth } from '@/auth/AuthProvider'

/**
 * VYB-0048b: a username/password sign-in screen, styled after vyg-pms's own login
 * page — split brand panel + glass card, animated background — but NOT after its
 * mechanism. vyg-pms's frontend calls Keycloak's token endpoint directly from the
 * browser with a *confidential* client's secret baked into its own Vite bundle
 * (`vyg-pms-ui/src/api/auth.api.js`, `VITE_CLIENT_SECRET`) — readable by anyone via
 * devtools, and (per docs/DECISIONS.md D7) that same client is also used for the
 * Keycloak Admin API, so the exposure is worse than "one user's login."
 *
 * Here, this form calls Vyoog's own `/api/v1/auth/login`. The password reaches
 * Keycloak through `KeycloakPasswordGrantService` on the backend — the only place
 * a client secret exists, in a backend env var, never in anything shipped to a
 * browser. See AuthProvider.tsx for why neither resulting token is persisted.
 */
export function AuthScreen() {
  const auth = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    if (!username.trim() || !password || auth.isLoading) return
    auth.login(username.trim(), password).catch(() => { /* auth.error already carries the reason */ })
  }

  return (
    <div className="auth-shell">
      <div className="auth-brand" aria-hidden="true">
        <div className="auth-grid" />
        <div style={{ position: 'absolute', left: 0, right: 0, height: 1, background: 'var(--brand)', animation: 'auth-scan 7s ease-in-out infinite' }} />
        {SHAPES.map((s, i) => (
          <div key={i} className="auth-shape" style={{ ...s, animation: `auth-float ${8 + i}s ease-in-out ${i * 0.6}s infinite` }} />
        ))}

        <div className="auth-wordmark">Vyoog<span className="dot">.</span></div>

        <div>
          <p className="auth-tagline">Every requirement, traced to the evidence that verified it.</p>
          <p className="auth-sub">
            Need → requirement → design → code → test — one real chain, not a status field anyone can set by hand.
          </p>
          <div className="auth-chips">
            {FEATURES.map(({ icon: Icon, label }) => (
              <span key={label} className="auth-chip" style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                <Icon size={11} /> {label}
              </span>
            ))}
          </div>
        </div>

        <div className="auth-footer">© {new Date().getFullYear()} Vyoog Information Pvt Ltd</div>
      </div>

      <div className="auth-panel">
        <div style={{ width: '100%', maxWidth: 400 }}>
          <div className="auth-mobile-mark">
            <span className="sb-logo" style={{ fontSize: 20 }}>Vyoog</span>
          </div>

          <div className="auth-card">
            <h1>Welcome back</h1>
            <p className="auth-lede">Sign in with your Vyoog account.</p>

            {auth.error && (
              <div className="auth-error">
                <AlertCircle size={15} style={{ flexShrink: 0, marginTop: 1 }} />
                <span>{auth.error}</span>
              </div>
            )}

            <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div className="field" style={{ marginBottom: 0 }}>
                <label className="label">Username</label>
                <div className="auth-field-row">
                  <User size={15} />
                  <input
                    type="text" autoComplete="username" autoFocus placeholder="your username"
                    value={username} onChange={(e) => setUsername(e.target.value)}
                  />
                </div>
              </div>

              <div className="field" style={{ marginBottom: 0 }}>
                <label className="label">Password</label>
                <div className="auth-field-row">
                  <Lock size={15} />
                  <input
                    type={showPassword ? 'text' : 'password'} autoComplete="current-password" placeholder="••••••••"
                    value={password} onChange={(e) => setPassword(e.target.value)}
                  />
                  <button type="button" className="auth-eye" tabIndex={-1} onClick={() => setShowPassword((v) => !v)}>
                    {showPassword ? <EyeOff size={15} /> : <Eye size={15} />}
                  </button>
                </div>
              </div>

              <button
                type="submit" className="btn pri" style={{ width: '100%', justifyContent: 'center', padding: '10px 0' }}
                disabled={!username.trim() || !password || auth.isLoading}
              >
                {auth.isLoading
                  ? <><Loader2 className="auth-spin" size={15} /> Signing in…</>
                  : <><LogIn size={15} /> Sign in</>}
              </button>
            </form>
          </div>

          <p className="auth-note">Authenticated against the shared eVyoog Keycloak realm.</p>
        </div>
      </div>
    </div>
  )
}

const FEATURES = [
  { icon: GitBranch, label: 'Trace links' },
  { icon: ListChecks, label: 'Verification predicate' },
  { icon: ScrollText, label: 'Audit trail' },
  { icon: ShieldCheck, label: 'Detection sweeps' },
]

const SHAPES = [
  { top: '14%', left: '10%', width: 70, height: 70, borderRadius: 16 },
  { top: '60%', left: '76%', width: 40, height: 40, borderRadius: 999 },
  { top: '38%', left: '20%', width: 46, height: 46, borderRadius: 10, transform: 'rotate(20deg)' },
  { top: '76%', left: '14%', width: 20, height: 20, borderRadius: 6 },
  { top: '24%', left: '62%', width: 96, height: 96, borderRadius: 999 },
]
