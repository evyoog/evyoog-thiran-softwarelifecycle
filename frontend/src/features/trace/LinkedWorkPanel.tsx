import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Link2 } from 'lucide-react'
import { api } from '@/shared/api/client'
import { LinkDialog } from './LinkDialog'
import { countText, groupsOf } from './linkWork'

/**
 * VYB-0943 (F19, D36): what a requirement is linked to besides other requirements, by name, with a "Link…" button. Tests,
 * design, the release and defects each say what is linked or, in words, that nothing is.
 */
export function LinkedWorkPanel({ requirementId }: { requirementId: string }) {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const { data, isError } = useQuery({ queryKey: ['requirement-linked', requirementId], queryFn: () => api.requirementLinked(requirementId) })
  const groups = groupsOf(data)

  return (
    <div className="mb-4" aria-label="Linked work">
      <div className="flex justify-between items-center mb-2">
        <h3 className="text-[11px] font-semibold uppercase tracking-[.05em] text-tx-3">Linked work</h3>
        <button className="btn" onClick={() => setOpen(true)}><Link2 size={14} /> Link…</button>
      </div>
      {isError && <p className="text-sm text-crit" role="alert">Could not load what this requirement is linked to.</p>}
      <div className="grid sm:grid-cols-2 gap-3">
        {groups.map((g) => (
          <div key={g.kind} className="bg-panel-2 border border-line rounded-lg p-3">
            <div className="flex justify-between text-xs text-tx-3 mb-1"><span className="font-semibold uppercase tracking-wide">{g.title}</span><span>{countText(g)}</span></div>
            {g.items.length === 0 && <p className="text-sm text-tx-3">{g.empty}</p>}
            <div className={g.source && g.items.length > 0 ? 'cn-borrowed' : undefined}>
              {g.source && g.items.length > 0 && <div className="cn-src">from {g.source}</div>}
              {g.items.map((i) => (
                <div key={i.id} className="text-sm py-0.5 flex flex-wrap gap-x-2 items-baseline">
                  {i.href ? <button className="underline text-brand text-left" onClick={() => navigate(i.href!)}>{i.label}</button>
                    : i.url ? <a className="underline text-brand mono" href={i.url} target="_blank" rel="noopener noreferrer" aria-label={`${i.label}, opens in the repository`}>{i.label}</a>
                    : <span className={g.source ? 'mono' : undefined}>{i.label}</span>}
                  {i.detail && <span className="text-xs text-tx-3">{i.detail}</span>}
                </div>
              ))}
            </div>
            {g.note && <p className="text-xs text-tx-3 mt-1" role="note">{g.note}</p>}
          </div>
        ))}
      </div>
      {open && <LinkDialog requirementId={requirementId} applicationId={data?.applicationId} onClose={() => setOpen(false)} />}
    </div>
  )
}
