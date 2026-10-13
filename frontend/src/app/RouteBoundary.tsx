import { Component, Suspense, type ErrorInfo, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { useT } from '@/shared/i18n/LocaleProvider'

/**
 * VYB-0958: what stands in for a screen while its chunk is fetched, and what replaces it if the chunk cannot be (typically the
 * application was updated after this page opened, so the old chunk's file is gone). Both are announced to assistive technology
 * and say what to do; the boundary resets when the person moves to another screen.
 */
function Loading() {
  const t = useT()
  return <p role="status" className="eyebrow" style={{ padding: 24 }}>{t('route.loading')}</p>
}

function Failed() {
  const t = useT()
  return (
    <div role="alert" style={{ padding: 24 }}>
      <p className="err-text">{t('route.failed')}</p>
      <button className="btn pri" onClick={() => window.location.reload()}>{t('route.reload')}</button>
    </div>
  )
}

class Catch extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() { return { failed: true } }
  componentDidCatch(error: Error, info: ErrorInfo) { console.error('[route] a screen failed to load or render', error, info.componentStack) }
  render() { return this.state.failed ? <Failed /> : this.props.children }
}

export function RouteBoundary({ children }: { children: ReactNode }) {
  const { pathname } = useLocation()
  return (
    <Catch key={pathname}>
      <Suspense fallback={<Loading />}>{children}</Suspense>
    </Catch>
  )
}
