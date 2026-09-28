import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { ApiError, api, setTokenProvider } from '@/shared/api/client'

/**
 * VYB-0048b/VYB-0800: username/password sign-in against Vyoog's own
 * `/api/v1/auth/login`, which does the real Keycloak exchange server-side (see
 * KeycloakPasswordGrantService) — this file never talks to Keycloak directly and
 * never holds a client secret, unlike vyg-pms-ui's `api/auth.api.js`.
 *
 * The access token lives in memory only (component state) — an XSS reading a
 * stored bearer token is a full account takeover, so it never touches
 * `localStorage`/`sessionStorage`. The refresh token is longer-lived and more
 * damaging to leak, so it never reaches this file's JavaScript at all: the
 * backend sets it as an HttpOnly, Secure, SameSite=Lax cookie
 * (`AuthController#setRefreshCookie`) this code couldn't read even if it tried,
 * and the browser attaches it automatically on calls to `/api/v1/auth/session`.
 * That cookie is what survives a page reload — a hard refresh wipes this
 * component's memory (the access token goes with it), but not the cookie, so
 * `checkSession` below silently exchanges it for a fresh access token before
 * the sign-in screen ever gets a chance to render.
 *
 * Cross-app SSO bridge: `/api/v1/auth/session` also checks a second, shared
 * `vyoog_sso` cookie against eis-platform, vyg-pms, and vyg-ticket — see
 * AuthController's own doc. Signing into any one of the four apps silently
 * authenticates the other three too, with no Keycloak UI ever shown. This
 * replaces the old `evyoog-website-ui` hash-fragment hand-off this file used
 * to read on mount: a raw access token riding in the URL fragment is exactly
 * the pattern the other three apps' own SSO integrations found and removed
 * (see e.g. vyg-ticket-ui's index.js) — the cookie bridge achieves the same
 * "already logged in elsewhere" outcome without ever putting a token in a URL.
 */
interface AuthUser {
  username: string
  email?: string
}

interface AuthState {
  user: AuthUser | null
  isAuthenticated: boolean
  /** True only during the one silent session check on mount — see the class comment. */
  isBootstrapping: boolean
  isLoading: boolean
  error: string | null
  login: (username: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)

// Cross-tab / cross-app session sync, same pattern as the other three Vyoog
// apps: there's no shared Keycloak browser cookie to react to (own login form
// on every app, no Keycloak UI ever), so instead this polls this app's own
// backend, which itself checks both this app's own session and the shared
// vyoog_sso bridge against the other three. This is what makes logging out
// of any other Vyoog app eventually flip an already-open tab here to
// signed-out too, and logging into any of them auto-log this one in.
const SESSION_POLL_MS = 20_000

function decodeJwtPayload(token: string): Record<string, unknown> {
  try {
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
    return JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>
  } catch {
    return {}
  }
}

function userFromAccessToken(accessToken: string): AuthUser {
  const claims = decodeJwtPayload(accessToken)
  return {
    username: (claims.preferred_username as string | undefined) ?? (claims.sub as string) ?? 'you',
    email: claims.email as string | undefined,
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [isBootstrapping, setIsBootstrapping] = useState(true)
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const accessTokenRef = useRef<string | undefined>(undefined)
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const hasSessionRef = useRef(false)
  const checkInFlightRef = useRef(false)

  const applyTokens = useCallback((accessToken: string, expiresInSeconds: number) => {
    hasSessionRef.current = true
    accessTokenRef.current = accessToken
    setUser(userFromAccessToken(accessToken))

    if (refreshTimer.current) clearTimeout(refreshTimer.current)
    if (expiresInSeconds > 30) {
      // Silently renew a bit before expiry — same intent as oidc-client-ts's
      // automaticSilentRenew, reimplemented here since ROPC has no redirect
      // round-trip for that library to hook into. Goes through the same
      // three-way /session check as everything else (not a plain /refresh),
      // since a token obtained via the cross-app bridge needs re-exchange,
      // not a normal refresh grant — /session already knows which.
      refreshTimer.current = setTimeout(() => { void checkSession() }, (expiresInSeconds - 30) * 1000)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const attemptSessionCheck = useCallback(async () => {
    const result = await api.authSession()
    applyTokens(result.accessToken, result.expiresInSeconds)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const checkSession = useCallback(async () => {
    // Reentrancy guard against overlapping calls from the mount effect, the
    // interval, and the 'focus' listener all sharing this function.
    if (checkInFlightRef.current) return
    checkInFlightRef.current = true
    try {
      await attemptSessionCheck()
    } catch {
      // Found live on vyg-ticket-ui's own integration of this exact pattern: a
      // single failed check is not reliable enough to treat as "signed out" —
      // a concurrent /auth/session call from another source can land at
      // nearly the same moment and race over the same pre-rotation refresh
      // cookie, and Keycloak's refresh-token rotation invalidates it for
      // whichever call loses that race. A lone failure is expected noise, not
      // necessarily a real sign-out. Confirm with one retry after a short
      // delay (by then any racing call has resolved and the cookie has
      // settled) before actually declaring the session dead — this still
      // catches a genuine logout correctly, since that retry fails too.
      await new Promise((resolve) => setTimeout(resolve, 1500))
      try {
        await attemptSessionCheck()
      } catch {
        if (hasSessionRef.current) {
          hasSessionRef.current = false
          accessTokenRef.current = undefined
          setUser(null)
        }
      }
    } finally {
      checkInFlightRef.current = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attemptSessionCheck])

  // Runs once, before the sign-in screen ever gets a chance to render, then
  // keeps polling so an already-open tab notices a logout that happened in
  // another Vyoog app. App.tsx holds rendering on isBootstrapping until the
  // first check settles, either way.
  useEffect(() => {
    checkSession().finally(() => setIsBootstrapping(false))
    const interval = setInterval(checkSession, SESSION_POLL_MS)
    window.addEventListener('focus', checkSession)
    return () => {
      clearInterval(interval)
      window.removeEventListener('focus', checkSession)
    }
  }, [checkSession])

  const login = useCallback(async (username: string, password: string) => {
    setIsLoading(true)
    setError(null)
    try {
      const result = await api.authLogin(username, password)
      applyTokens(result.accessToken, result.expiresInSeconds)
    } catch (e) {
      setError(e instanceof ApiError ? (e.detail ?? e.title) : 'Sign-in failed. Please try again.')
      throw e
    } finally {
      setIsLoading(false)
    }
  }, [applyTokens])

  const logout = useCallback(() => {
    if (refreshTimer.current) clearTimeout(refreshTimer.current)
    hasSessionRef.current = false
    accessTokenRef.current = undefined
    setUser(null)
    setError(null)
    // Real logout: ends the actual Keycloak session server-side (not just
    // this tab's local copy) and broadcasts to the other three apps in the
    // SSO mesh — see AuthController#logout. Best-effort: a failure here
    // (e.g. already offline) shouldn't block the local sign-out the user
    // actually asked for.
    void api.authLogout().catch(() => {})
  }, [])

  // The API client reads the live token via this getter rather than a copied
  // value, so a silent renew (applyTokens above) is picked up immediately with
  // no re-registration needed.
  useEffect(() => {
    setTokenProvider(() => accessTokenRef.current)
  }, [])

  return (
    <AuthContext.Provider value={{ user, isAuthenticated: !!user, isBootstrapping, isLoading, error, login, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth() called outside <AuthProvider>')
  return ctx
}
