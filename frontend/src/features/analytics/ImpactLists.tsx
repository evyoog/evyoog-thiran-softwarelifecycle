import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { ChangeImpact } from '@/shared/api/client'
import { countLabel, impactSections, listMatchesCount, type ImpactKind } from './impactItems'

/**
 * VYB-0943 (F19, D36): a change request's impact, each count a toggle that opens the items behind it. The lists come from the
 * same walk of the trace graph as the counts. A requirement says whether the change request names it or it was reached
 * downstream; each links to its page.
 */
export function ImpactLists({ impact }: { impact: ChangeImpact }) {
  const navigate = useNavigate()
  const [open, setOpen] = useState<ImpactKind | null>(null)
  const sections = impactSections(impact)
  const shown = sections.find((s) => s.kind === open)

  return (
    <div className="hint" style={{ marginBottom: 8 }} aria-label="Impact">
      <span>Impact: </span>
      {sections.map((s, i) => (
        <span key={s.kind}>
          {i > 0 && ' · '}
          <button className="underline" aria-expanded={open === s.kind} aria-controls={`impact-${s.kind}`} disabled={s.count === 0}
            onClick={() => setOpen(open === s.kind ? null : s.kind)}>{countLabel(s)}</button>
        </span>
      ))}
      {shown && (
        <div id={`impact-${shown.kind}`} className="card" style={{ marginTop: 6, padding: 8 }}>
          {!listMatchesCount(shown) && <p className="err-text" role="status">The list has {shown.items.length} of {shown.count}; reload to compute again.</p>}
          <ul style={{ margin: 0, paddingLeft: 16 }}>
            {shown.items.map((it) => (
              <li key={it.id} style={{ fontSize: 12.5 }}>
                {it.href ? <button className="underline" onClick={() => navigate(it.href!)}>{it.label}</button> : it.label}
                {it.detail && <span className="muted"> · {it.detail}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
