import { useQuery, useQueries } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api, type RequirementStatus } from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'

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
 * VYB-0233: the same connection registry Administration's Connected Systems screen
 * reads (VYB-0756) — reused here rather than a second copy.
 */
export function Home() {
  const navigate = useNavigate()
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
  const { data: releases } = useQuery({ queryKey: ['releases'], queryFn: api.releases })
  const currentRelease = releases?.find((r) => r.state === 'OPEN')
  const { data: blocked } = useQuery({
    queryKey: ['release-blocked', currentRelease?.id],
    queryFn: () => api.releaseBlocked(currentRelease!.id),
    enabled: !!currentRelease,
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
    { l: 'Products', v: products?.filter((p) => !p.archived).length ?? 0 },
    { l: 'Requirements', v: total },
    { l: 'Open gaps', v: openFindings?.totalElements ?? 0 },
    { l: 'Approval coverage', v: `${coveragePct}%` },
  ]

  return (
    <Page
      eyebrow={me ? me.displayName : 'Loading'}
      title="Home"
      desc="Where the register stands right now. Every figure here is derived live — nothing is stored."
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
        <Empty
          title="No requirements yet"
          desc="Create the first one from the Requirements module to see this page come alive."
        />
      ) : (
        <>
          <h4 className="section-h">Requirement flow</h4>
          <div style={{ display: 'flex', gap: 10, marginBottom: 20 }}>
            {FLOW_STAGES.map((stage, i) => (
              <div key={stage} className="card" style={{ flex: 1, textAlign: 'center' }}>
                <div className="eyebrow">{stage.replace('_', ' ')}</div>
                <div className="mono" style={{ fontSize: 22, marginTop: 6 }}>{stageCounts[i]}</div>
                {i === stuckIndex && (
                  <div className="badge sev-high" style={{ marginTop: 6 }}>Stuck here</div>
                )}
              </div>
            ))}
          </div>
        </>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
        <div>
          <h4 className="section-h">Blocking {currentRelease ? currentRelease.name : 'the current release'}</h4>
          {!currentRelease && <Empty title="No open release" desc="Nothing is currently in an OPEN release scope." />}
          {currentRelease && blocked && blocked.length === 0 && (
            <Empty title="Nothing blocking it" desc="Every committed requirement in this release is ready." />
          )}
          {blocked?.map((b) => (
            <div key={b.requirementId} className="list-item" style={{ cursor: 'pointer' }} onClick={() => navigate(`/requirements/${b.requirementId}`)}>
              <span className="mono muted" style={{ fontSize: 10 }}>{b.key}</span>
              <span style={{ flex: 1, color: 'var(--crit)' }}>{b.reason}</span>
            </div>
          ))}
        </div>

        <div>
          <h4 className="section-h">Not owned here</h4>
          {integrations?.map((i) => (
            <div key={i.key} className="list-item">
              <span className="mono" style={{ width: 70 }}>{i.key}</span>
              <span className="muted" style={{ flex: 1, fontSize: 12 }}>{i.owns}</span>
              <span style={{ fontSize: 11.5, color: i.connected ? 'var(--ok)' : 'var(--tx-3)' }}>
                {i.connected ? 'Connected' : 'Not connected'}
              </span>
            </div>
          ))}
        </div>
      </div>
    </Page>
  )
}
