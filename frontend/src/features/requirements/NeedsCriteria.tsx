import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Plus } from 'lucide-react'
import { api } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'

/**
 * VYB-0666: the requirements that have no acceptance criteria yet.
 *
 * <p>They are kept out of the main grid — a requirement nobody can write a test against is
 * not ready to be read as one — but they are emphatically not hidden. A row that exists,
 * counts in every total, and appears on no screen is the worst of both: people stop
 * trusting the grid because they know things are missing from it and cannot see which.
 *
 * <p>So this is a strip above the grid that states the number and opens a list where the
 * criteria can be added on the spot. Adding one moves that requirement into the grid on
 * the next refresh, which is the whole loop: the strip empties as the work gets done, and
 * disappears entirely when it is.
 */
export function NeedsCriteria({ capabilityId }: { capabilityId?: string }) {
  const [open, setOpen] = useState(false)

  const { data } = useQuery({
    queryKey: ['requirements', 'no-criteria', capabilityId],
    queryFn: () => api.requirements({ hasCriteria: false, capabilityId, size: 200 }),
  })

  const missing = data?.content ?? []
  if (missing.length === 0) return null

  return (
    <>
      <button className="nc-strip" onClick={() => setOpen(true)}>
        <AlertTriangle />
        <span>
          <strong>{missing.length} requirement{missing.length === 1 ? '' : 's'} need acceptance criteria</strong>
          {' — '}not shown in the grid until {missing.length === 1 ? 'it has' : 'they have'} at least one.
        </span>
        <span className="nc-strip-go">Add them →</span>
      </button>

      {open && (
        <Modal onClose={() => setOpen(false)} title="Requirements with no acceptance criteria">
          <p className="muted" style={{ fontSize: 12.5, marginTop: 0 }}>
            Nothing can be tested against these, so they stay out of the grid. Add one criterion
            and the requirement appears there.
          </p>
          <div className="nc-list">
            {missing.map((r) => <Row key={r.id} id={r.id} keyText={r.key} title={r.title} statement={r.statement} />)}
          </div>
        </Modal>
      )}
    </>
  )
}

function Row({ id, keyText, title, statement }: { id: string; keyText: string; title: string; statement: string }) {
  const qc = useQueryClient()
  const [text, setText] = useState('')

  const add = useMutation({
    mutationFn: () => api.addAcceptanceCriterion(id, text.trim()),
    onSuccess: () => {
      setText('')
      // Both lists move: this requirement leaves the gap list and joins the grid.
      void qc.invalidateQueries({ queryKey: ['requirements'] })
      void qc.invalidateQueries({ queryKey: ['acceptance-criteria', id] })
    },
  })

  return (
    <div className="nc-row">
      <div className="nc-row-h">
        <span className="c-id">{keyText}</span>
        <span className="nc-row-t">{title}</span>
      </div>
      <p className="nc-row-s">{statement}</p>
      <div className="nc-row-a">
        <input
          className="input" value={text} placeholder="A criterion — what must be true for this to be done"
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && text.trim()) add.mutate() }}
        />
        <button className="btn pri" disabled={!text.trim() || add.isPending} onClick={() => add.mutate()}>
          <Plus /> Add
        </button>
      </div>
      {add.isError && <p className="err-text">Could not add that criterion.</p>}
    </div>
  )
}
