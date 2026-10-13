import { useQuery, useQueries } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api, type RequirementStatus } from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'
import { useLocale } from '@/shared/i18n/LocaleProvider'
import {
  STATE_CLASS, STATE_GLYPH, stateLabel, blockedReason, failingGates, forwardMoves, moveLabel, readinessText, targetDateText,
} from './homeRelease'

const FLOW_STAGES: RequirementStatus[] = ['DRAFT', 'IN_REVIEW', 'REVIEWED', 'APPROVED']

/**
 * VYB-0230: every figure here is derived from a live query, nothing stored.
 * VYB-0232: counts per stage, with the largest drop-off marked stuck.
 * VYB-0231: reuses {@code ReleaseService#blocked} (Phase 3) — unverified/conflicting/
 * unowned reasons, not literally "only critical findings" (its own AC1's exact
 * wording); a real, disclosed gap rather than a second query pretending to filter to
 * critical severity specifically. AC2 ("opens Analytics filtered to it") is also not
 * quite what this does — Analytics has no per-requirement filter to deep-link into,
 * so this opens the requirement detail instead, where the same findings are visible.
 * VYB-0929: the "blocking the release" panel reads the release being prepared (OPEN or FROZEN, earliest target
 * date first) from the server, now that a release really has a state (VYB-0928): its state and date, why its next
 * move is not ready (each failing check, in words), and the blocked requirements as before.
 * VYB-0233: the same connection registry Administration's Connected Systems screen
 * reads (VYB-0756) — reused here rather than a second copy.
 */
export function Home() {
  const navigate = useNavigate()
  const { t, fmt } = useLocale()
  const { data: me } = useQuery({ queryKey: ['me'], queryFn: api.me })
  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  const { data: requirements } = useQuery({
    queryKey: ['requirements', 'home-count'],
    queryFn: () => api.requirements({ size: 1 }),
  })
  const { data: openFindings } = useQuery({
    queryKey: ['findings', 'OPEN', 'home-count'],
    queryFn: () => api.findings({ state: 'OPEN', size: 1 }),
  })
  const { data: currentRelease, isLoading: releaseLoading } = useQuery({
    queryKey: ['release-current'],
    // a 204 (nothing being prepared) comes back as undefined, which react-query does not accept as data
    queryFn: async () => (await api.currentRelease()) ?? null,
  })
  const { data: integrations } = useQuery({ queryKey: ['integrations'], queryFn: api.integrations })

  const stageQueries = useQueries({
    queries: FLOW_STAGES.map((status) => ({
      queryKey: ['requirements', status, 'home-flow'],
      queryFn: () => api.requirements({ status, size: 1 }),
    })),
  })
  const stageCounts = stageQueries.map((q) => q.data?.totalElements ?? 0)
  const flowLoaded = stageQueries.every((q) => q.data)

  const total = requirements?.totalElements ?? 0
  // VYB-0810: this used to count VERIFIED — APPROVED is now the terminal stage.
  const approvedCount = stageCounts[3] ?? 0
  const coveragePct = total > 0 ? Math.round((approvedCount / total) * 100) : 0

  // VYB-0232 AC2: the largest drop between consecutive stages, not just the smallest count.
  let stuckIndex = -1
  if (flowLoaded && total > 0) {
    let maxDrop = -1
    for (let i = 0; i < stageCounts.length - 1; i++) {
      const drop = stageCounts[i] - stageCounts[i + 1]
      if (drop > maxDrop) { maxDrop = drop; stuckIndex = i }
    }
  }

  const stats = [
    { l: t('home.stat.products'), v: fmt.number(products?.filter((p) => !p.archived).length ?? 0) },
    { l: t('home.stat.requirements'), v: fmt.number(total) },
    { l: t('home.stat.gaps'), v: fmt.number(openFindings?.totalElements ?? 0) },
    { l: t('home.stat.coverage'), v: `${fmt.number(coveragePct)}%` },
  ]

  return (
    <Page
      eyebrow={me ? me.displayName : t('home.eyebrowLoading')}
      title={t('home.title')}
      desc={t('home.desc')}
    >
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit,minmax(178px,1fr))',
          gap: 12,
          marginBottom: 18,
        }}
      >
        {stats.map((s) => (
          <div key={s.l} className="card">
            <div className="eyebrow">{s.l}</div>
            <div className="mono" style={{ fontSize: 26, marginTop: 6 }}>
              {s.v}
            </div>
          </div>
        ))}
      </div>

      {total === 0 ? (
        <Empty title={t('home.empty.title')} desc={t('home.empty.desc')} />
      ) : (
        <>
          <h2 className="section-h">{t('home.flow')}</h2>
          <div style={{ display: 'flex', gap: 10, marginBottom: 20 }}>
            {FLOW_STAGES.map((stage, i) => (
              <div key={stage} className="card" style={{ flex: 1, textAlign: 'center' }}>
                <div className="eyebrow">{t(`status.${stage}`)}</div>
                <div className="mono" style={{ fontSize: 22, marginTop: 6 }}>{fmt.number(stageCounts[i] ?? 0)}</div>
                {i === stuckIndex && (
                  <div className="badge sev-high" style={{ marginTop: 6 }}>{t('home.stuck')}</div>
                )}
              </div>
            ))}
          </div>
        </>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
        <div>
          <h2 className="section-h">{currentRelease ? t('home.blocking', { name: currentRelease.name }) : t('home.blockingCurrent')}</h2>
          {releaseLoading && <p className="eyebrow">{t('common.loading')}</p>}
          {!releaseLoading && !currentRelease && (
            <Empty title={t('home.noRelease.title')} desc={t('home.noRelease.desc')} />
          )}
          {currentRelease && (
            <>
              <p style={{ margin: '0 0 8px', display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                <span className={`badge ${STATE_CLASS[currentRelease.state]}`}>
                  <span aria-hidden="true">{STATE_GLYPH[currentRelease.state]}</span> {stateLabel(currentRelease.state, t)}
                </span>
                <span className="muted">{targetDateText(currentRelease.targetDate, t, fmt)}</span>
              </p>
              {forwardMoves(currentRelease.moves).map((m) => (
                <div key={m.to} style={{ marginBottom: 8 }}>
                  <div role="status" style={{ fontWeight: 600, color: m.ready ? 'var(--ok)' : 'var(--crit)' }}>
                    <span aria-hidden="true">{m.ready ? '✓' : '✕'}</span> {readinessText(m, t)}
                  </div>
                  {failingGates(m).map((g) => (
                    <div key={g.gate} className="list-item"><span style={{ flex: 1, color: 'var(--crit)' }}>{g.detail}</span></div>
                  ))}
                  {m.signatureRequired && (
                    <div className="hint muted">{t('release.signoff', { move: moveLabel(m, t) })}</div>
                  )}
                </div>
              ))}
              {currentRelease.blocked.length === 0 ? (
                <Empty title={t('home.nothingBlocking.title')} desc={t('home.nothingBlocking.desc')} />
              ) : (
                <h2 className="section-h" style={{ marginTop: 10 }}>{t('home.blockedRequirements')}</h2>
              )}
              {currentRelease.blocked.map((b) => (
                <div
                  key={b.requirementId} className="list-item" style={{ cursor: 'pointer' }} role="link" tabIndex={0}
                  onClick={() => navigate(`/requirements/${b.requirementId}`)}
                  onKeyDown={(e) => { if (e.key === 'Enter') navigate(`/requirements/${b.requirementId}`) }}
                >
                  <span className="mono muted" style={{ fontSize: 10 }}>{b.key}</span>
                  <span style={{ flex: 1, color: 'var(--crit)' }}>{blockedReason(b.reason, t)}</span>
                </div>
              ))}
            </>
          )}
        </div>

        <div>
          <h2 className="section-h">{t('home.notOwned')}</h2>
          {integrations?.map((i) => (
            <div key={i.key} className="list-item">
              <span className="mono" style={{ width: 70 }}>{i.key}</span>
              <span className="muted" style={{ flex: 1, fontSize: 12 }}>{i.owns}</span>
              <span style={{ fontSize: 11.5, color: i.connected ? 'var(--ok)' : 'var(--tx-3)' }}>
                {i.connected ? t('home.connected') : t('home.notConnected')}
              </span>
            </div>
          ))}
        </div>
      </div>
    </Page>
  )
}
