import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { X, ExternalLink } from 'lucide-react'
import { api, type Requirement } from '@/shared/api/client'
import { StatusBadge, PriorityBadge, CoveragePips } from '@/shared/ui/Badges'

const TYPE_LABEL: Record<string, string> = {
  FUNCTIONAL: 'Functional', NON_FUNCTIONAL: 'Non-functional', BUSINESS_RULE: 'Business rule',
  INTERFACE: 'Interface', DATA: 'Data', REPORT: 'Report', SECURITY: 'Security', COMPLIANCE: 'Compliance',
}

/** "14 Jun" — the prototype's own history column format. */
function shortDate(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime())
    ? '—'
    : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

/**
 * The prototype's 372px detail pane. Deliberately a read view: it shows attributes,
 * the statement, acceptance criteria, traceability and recent history, and hands off
 * to the full page for everything that edits. RequirementDetail carries lifecycle
 * transitions, comments, clarifications, attachments and defect-raising — none of
 * which fit honestly in this width, and duplicating a subset of them here would mean
 * two places to keep a transition's validation in step.
 */
export function DetailPane({ req, onClose }: { req: Requirement; onClose: () => void }) {
  const criteria = useQuery({
    queryKey: ['acceptance-criteria', req.id],
    queryFn: () => api.acceptanceCriteria(req.id),
  })
  const graph = useQuery({
    queryKey: ['trace-graph', req.id],
    queryFn: () => api.traceGraph('REQUIREMENT', req.id, 1),
  })
  const history = useQuery({
    queryKey: ['audit', 'REQUIREMENT', req.id],
    queryFn: () => api.auditSearch({ objectType: 'REQUIREMENT', objectId: req.id, size: 6 }),
  })

  const label = (type: string, id: string) =>
    graph.data?.nodes.find((n) => n.type === type && n.id === id)?.label ?? id.slice(0, 8)

  const upstream = (graph.data?.edges ?? []).filter((e) => e.toType === 'REQUIREMENT' && e.toId === req.id)
  const downstream = (graph.data?.edges ?? []).filter((e) => e.fromType === 'REQUIREMENT' && e.fromId === req.id)

  return (
    <aside className="detail" aria-label={`Detail for ${req.key}`}>
      <div className="detail-h">
        <span className="detail-id">{req.key}</span>
        <StatusBadge status={req.status} />
        <div className="sp" />
        <Link className="btn" style={{ padding: 5 }} to={`/requirements/${req.id}`} title="Open the full requirement page">
          <ExternalLink />
        </Link>
        <button className="btn" style={{ padding: 5 }} onClick={onClose} title="Close detail pane" aria-label="Close detail pane">
          <X />
        </button>
      </div>

      <div className="detail-b">
        <div className="dsec">Attributes</div>
        <div className="frow"><span className="fl">Type</span><span>{TYPE_LABEL[req.type] ?? req.type}</span></div>
        <div className="frow"><span className="fl">Priority</span><PriorityBadge priority={req.priority} /></div>
        <div className="frow"><span className="fl">Revision</span><span className="mono">{req.revision}</span></div>
        <div className="frow"><span className="fl">Coverage</span><CoveragePips req={req} /></div>
        {req.qualityScore != null && (
          <div className="frow"><span className="fl">Quality</span><span className="mono">{req.qualityScore}</span></div>
        )}

        <div className="dsec">Statement</div>
        <p className="dtext">{req.statement}</p>
        {req.rationale && (
          <>
            <div className="dsec">Rationale</div>
            <p className="dtext">{req.rationale}</p>
          </>
        )}

        <div className="dsec">Acceptance criteria</div>
        {criteria.isLoading && <p className="hint muted" style={{ fontSize: 11 }}>Loading…</p>}
        {criteria.data?.map((c, i) => (
          <div className="ac-item" key={c.id}>
            <span className="n">{String(i + 1).padStart(2, '0')}</span>
            <span>{c.text}</span>
          </div>
        ))}
        {criteria.data?.length === 0 && (
          <p className="hint muted" style={{ fontSize: 11 }}>None yet — a requirement with no criteria has nothing a test can verify.</p>
        )}

        <div className="dsec">Traceability</div>
        {graph.isLoading && <p className="hint muted" style={{ fontSize: 11 }}>Loading…</p>}
        {upstream.map((e) => (
          <div className="trow" key={`u-${e.fromType}-${e.fromId}-${e.linkType}`}>
            <span className="ar">↑</span>
            <span className="ref">{label(e.fromType, e.fromId)}</span>
            <span className="rl">{e.linkType.toLowerCase()}</span>
          </div>
        ))}
        {downstream.map((e) => (
          <div className="trow" key={`d-${e.toType}-${e.toId}-${e.linkType}`}>
            <span className="ar">↓</span>
            <span className="ref">{label(e.toType, e.toId)}</span>
            <span className="rl">{e.linkType.toLowerCase()}</span>
          </div>
        ))}
        {/* Principle 3: Verified is a predicate over the current revision, so a missing
            passing test is stated as a gap rather than left as a blank row. */}
        {!req.hasTest && (
          <div className="trow">
            <span className="ar">↓</span>
            <span className="ref none">no test</span>
            <span style={{ color: 'var(--crit)' }}>Verification missing</span>
            <span className="rl">gap</span>
          </div>
        )}
        {graph.data && upstream.length === 0 && downstream.length === 0 && req.hasTest && (
          <p className="hint muted" style={{ fontSize: 11 }}>No links recorded.</p>
        )}

        <div className="dsec">History</div>
        {history.isLoading && <p className="hint muted" style={{ fontSize: 11 }}>Loading…</p>}
        {history.data?.content.map((e) => (
          <div className="hist" key={e.id}>
            <span className="hd">{shortDate(e.occurredAt)}</span>
            <span className="ht">{e.action.toLowerCase().replace(/_/g, ' ')}</span>
          </div>
        ))}
        {history.data?.content.length === 0 && (
          <p className="hint muted" style={{ fontSize: 11 }}>No audit events recorded.</p>
        )}

        <div style={{ marginTop: 18 }}>
          <Link className="btn" to={`/requirements/${req.id}`}>
            <ExternalLink /> Open full requirement
          </Link>
        </div>
      </div>
    </aside>
  )
}
