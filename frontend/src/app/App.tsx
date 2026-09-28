import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { useAuth } from '@/auth/AuthProvider'
import { AuthScreen } from './AuthScreen'
import { Shell } from './Shell'
import { Home } from '@/features/Home'
import { MyWork } from '@/features/MyWork'
import { Portfolio } from '@/features/Portfolio'
import { Requirements } from '@/features/Requirements'
import { RequirementNew } from '@/features/RequirementNew'
import { RequirementDetail } from '@/features/RequirementDetail'
import { ImportQueue } from '@/features/ImportQueue'
import { Documents } from '@/features/Documents'
import { CoverageMatrixPage } from '@/features/CoverageMatrixPage'
import { TraceGraphPage } from '@/features/TraceGraphPage'
import { Design } from '@/features/Design'
import { Analytics } from '@/features/Analytics'
import { Quality } from '@/features/Quality'
import { Delivery } from '@/features/Delivery'
import { Releases } from '@/features/Releases'
import { Admin } from '@/features/Admin'

// Local-dev-only escape hatch (VITE_SKIP_AUTH=true in frontend/.env, gitignored,
// never in .env.production). Vyoog otherwise never renders as signed-in without a
// real Keycloak session — see VYB-0048 "honest auth states" — so this bends that
// rule on purpose, only locally, and stays visibly flagged (DevModeBanner below)
// specifically so it can never be mistaken for a real authenticated session.
const SKIP_AUTH = import.meta.env.VITE_SKIP_AUTH === 'true'

export function App() {
  const auth = useAuth()

  // VYB-0800: one silent attempt to restore a session from the HttpOnly refresh
  // cookie runs before anything else — without this gate, a signed-in user
  // hitting reload would see the sign-in screen flash for a moment even though
  // they're about to land back in the app a beat later.
  if (!SKIP_AUTH && auth.isBootstrapping) {
    return <p className="eyebrow" style={{ padding: 24 }}>Loading…</p>
  }

  // In skip-auth mode nobody ever calls login(), so AuthProvider's registered
  // token getter simply keeps returning undefined and every API call goes out
  // unauthenticated — the real backend (once it enforces auth) 401s them; this
  // bypass only gets you past the login screen, it does not fake a working
  // backend session.
  if (!SKIP_AUTH && !auth.isAuthenticated) {
    return <AuthScreen />
  }

  return (
    <BrowserRouter>
      {SKIP_AUTH && <DevModeBanner />}
      <Routes>
        <Route element={<Shell />}>
          <Route path="/" element={<Home />} />
          <Route path="/my-work" element={<MyWork />} />
          <Route path="/portfolio" element={<Portfolio />} />
          <Route path="/requirements" element={<Requirements />} />
          <Route path="/requirements/new" element={<RequirementNew />} />
          <Route path="/requirements/import" element={<ImportQueue />} />
          <Route path="/requirements/documents" element={<Documents />} />
          <Route path="/requirements/coverage" element={<CoverageMatrixPage />} />
          <Route path="/requirements/trace-graph" element={<TraceGraphPage />} />
          <Route path="/requirements/:id" element={<RequirementDetail />} />
          <Route path="/design" element={<Design />} />
          <Route path="/analytics" element={<Analytics />} />
          <Route path="/quality" element={<Quality />} />
          <Route path="/delivery" element={<Delivery />} />
          <Route path="/releases" element={<Releases />} />
          <Route path="/admin" element={<Admin />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

/** Always visible when auth is bypassed, so this state is never mistaken for a real session. */
function DevModeBanner() {
  return (
    <div
      style={{
        position: 'fixed', top: 0, left: 0, right: 0, zIndex: 100,
        background: 'var(--ai)', color: '#1a1200', fontSize: 11, fontWeight: 700,
        textAlign: 'center', padding: '3px 0', letterSpacing: '.04em',
      }}
    >
      DEV MODE — authentication bypassed (VITE_SKIP_AUTH). Never set this in production.
    </div>
  )
}
