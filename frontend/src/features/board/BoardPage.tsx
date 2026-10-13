import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Pencil, Plus, Trash2 } from 'lucide-react'
import { ApiError, api, type Requirement, type RequirementStatus } from '@/shared/api/client'
import { Page } from '@/shared/ui/Page'
import { Modal } from '@/shared/ui/Modal'
import { PriorityBadge } from '@/shared/ui/Badges'
import { useMe } from '@/shared/useMe'
import { verifyDestination } from '../RequirementDetail'
import { isVerifyMove, moveLabel } from '../requirements/lifecycle'
import { COLUMN_LABEL, canEditTemplate, columnParams, legalTargets, outcome, refusal, templateLabel, templateSummary } from './boardModel'
import { MoveDialog } from './MoveDialog'
import { TemplateForm } from './TemplateForm'

const PAGE_SIZE = 50

/**
 * VYB-0953 (F42, D42): the requirement lifecycle as a Kanban board. A card's column is its status, nothing else; there is no board
 * state of its own. A template says which columns, in what order, and over which requirements. Dropping a card on a column asks
 * the server to make that transition, so the roles, separation of duties and blocking clarifications are the server's, as on the
 * requirement page. Drops the lifecycle never allows are refused here with the reason; the move to Reviewed is a person's
 * verification, so it opens the Design page rather than moving the card.
 */
export function BoardPage() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { data: me } = useMe()
  const [params, setParams] = useSearchParams()
  const [dragging, setDragging] = useState<Requirement | null>(null)
  const [over, setOver] = useState<RequirementStatus | null>(null)
  const [move, setMove] = useState<{ req: Requirement; target: RequirementStatus } | null>(null)
  const [notice, setNotice] = useState('')
  const [editing, setEditing] = useState<'new' | 'edit' | null>(null)
  const [deleting, setDeleting] = useState(false)

  const { data: templates, isLoading, isError } = useQuery({ queryKey: ['board-templates'], queryFn: api.boardTemplates })
  const { data: teams } = useQuery({ queryKey: ['teams'], queryFn: api.teams })
  const chosen = params.get('template')
  const template = useMemo(() => templates?.find((t) => t.id === chosen) ?? templates?.[0], [templates, chosen])
  const { data: members } = useQuery({
    queryKey: ['team-members', template?.teamId], queryFn: () => api.teamMembers(template!.teamId!), enabled: !!template?.teamId,
  })
  const canEdit = canEditTemplate(me, template, members)
  const canCreate = !!me?.platformAdministrator || !!teams?.length

  const columns = template?.columns ?? []
  const results = useQueries({
    queries: columns.map((status) => ({
      queryKey: ['board-column', template?.id, template?.updatedAt, status],
      queryFn: () => api.requirements(columnParams(template!, status, PAGE_SIZE)),
      enabled: !!template,
    })),
  })

  const choose = (id: string) => setParams(id ? { template: id } : {}, { replace: true })
  useEffect(() => { if (template && chosen && chosen !== template.id) choose(template.id) }) // a deleted template falls back

  const moveReq = useMutation({
    mutationFn: ({ req, target, reason }: { req: Requirement; target: RequirementStatus; reason?: string }) => api.transitionRequirement(req.id, req.revision, target, reason),
    onSuccess: (_d, v) => {
      setMove(null)
      setNotice(`${v.req.key} moved to ${COLUMN_LABEL[v.target].toLowerCase()}.`)
      void qc.invalidateQueries({ queryKey: ['board-column'] })
      void qc.invalidateQueries({ queryKey: ['requirements'] })
    },
  })
  const moveError = moveReq.error instanceof ApiError ? (moveReq.error.detail ?? moveReq.error.title) : moveReq.isError ? 'The move could not be made.' : undefined

  const remove = useMutation({
    mutationFn: () => api.deleteBoardTemplate(template!.id),
    onSuccess: () => { setDeleting(false); choose(''); void qc.invalidateQueries({ queryKey: ['board-templates'] }) },
  })

  const attempt = (req: Requirement, target: RequirementStatus) => {
    setNotice('')
    const o = outcome(req.status, target)
    if (o === 'SAME') return
    if (o === 'ILLEGAL') { setNotice(refusal(req.status, target) ?? ''); return }
    if (o === 'VERIFY') { navigate(verifyDestination(req.id)); return }
    moveReq.reset()
    setMove({ req, target })
  }

  const drop = (target: RequirementStatus) => {
    const req = dragging
    setDragging(null); setOver(null)
    if (req) attempt(req, target)
  }

  return (
    <Page
      title="Board"
      desc="Requirements by status. Drag a card to another column to move it; the same rules apply as on the requirement page."
      actions={(
        <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
          <div className="field" style={{ margin: 0 }}>
            <label className="label" htmlFor="board-template">Template</label>
            <select id="board-template" className="select" value={template?.id ?? ''} onChange={(e) => choose(e.target.value)}>
              {templates?.map((t) => <option key={t.id} value={t.id}>{templateLabel(t)}</option>)}
            </select>
          </div>
          {canEdit && <button className="btn" onClick={() => setEditing('edit')}><Pencil /> Edit</button>}
          {canEdit && template && !template.isDefault && <button className="btn danger" onClick={() => setDeleting(true)}><Trash2 /> Delete</button>}
          {canCreate && <button className="btn pri" onClick={() => setEditing('new')}><Plus /> New template</button>}
        </div>
      )}
    >
      {isLoading && <p className="eyebrow">Loading…</p>}
      {isError && <p className="err-text" role="alert">The board could not be loaded.</p>}
      {template && <p className="hint" style={{ marginBottom: 10 }}>{templateSummary(template)}</p>}
      <p role="status" className="hint" style={{ minHeight: 18, marginBottom: 8 }}>{notice}</p>

      <div style={{ display: 'grid', gridAutoFlow: 'column', gridAutoColumns: 'minmax(240px, 1fr)', gap: 12, overflowX: 'auto', paddingBottom: 12 }}>
        {columns.map((status, i) => {
          const res = results[i]
          const cards = res?.data?.content ?? []
          const total = res?.data?.totalElements ?? 0
          const legal = dragging ? outcome(dragging.status, status) : null
          return (
            <section
              key={status}
              aria-label={COLUMN_LABEL[status]}
              onDragOver={(e) => { if (dragging) { e.preventDefault(); setOver(status) } }}
              onDragLeave={() => setOver((o) => (o === status ? null : o))}
              onDrop={(e) => { e.preventDefault(); drop(status) }}
              style={{
                background: 'var(--panel-2, var(--panel))', border: `1px solid ${over === status && legal !== 'ILLEGAL' && legal !== 'SAME' ? 'var(--accent, var(--line-2))' : 'var(--line)'}`,
                borderRadius: 10, padding: 10, minHeight: 200, opacity: dragging && legal === 'ILLEGAL' ? 0.55 : 1,
              }}
            >
              <h2 className="eyebrow" style={{ margin: '0 0 8px' }}>{COLUMN_LABEL[status]} <span className="muted">· {total}</span></h2>
              {res?.isLoading && <p className="hint">Loading…</p>}
              {res?.isError && <p className="err-text" role="alert">Could not load this column.</p>}
              {!res?.isLoading && !res?.isError && cards.length === 0 && <p className="hint">Nothing here.</p>}
              <div style={{ display: 'grid', gap: 8 }}>
                {cards.map((r) => {
                  const targets = legalTargets(r.status, columns)
                  return (
                    <article
                      key={r.id}
                      className="card"
                      draggable
                      aria-label={`${r.key} ${r.title}`}
                      onDragStart={(e) => { setDragging(r); e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', r.id) }}
                      onDragEnd={() => { setDragging(null); setOver(null) }}
                      style={{ padding: 10, cursor: 'grab', opacity: dragging?.id === r.id ? 0.5 : 1 }}
                    >
                      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 4 }}>
                        <button className="link mono" style={{ fontSize: 11 }} onClick={() => navigate(`/requirements/${r.id}`)}>{r.key}</button>
                        <span className="sp" />
                        <PriorityBadge priority={r.priority} />
                      </div>
                      <div className="text-sm" style={{ marginBottom: 6 }}>{r.title}</div>
                      {targets.length > 0 && (
                        <select
                          className="select" aria-label={`Move ${r.key}`} value="" style={{ fontSize: 12 }}
                          onChange={(e) => { const t = e.target.value as RequirementStatus; if (t) attempt(r, t) }}
                        >
                          <option value="">Move…</option>
                          {targets.map((t) => <option key={t} value={t}>{isVerifyMove(r.status, t) ? 'Verify…' : moveLabel(r.status, t).button}</option>)}
                        </select>
                      )}
                    </article>
                  )
                })}
              </div>
              {total > cards.length && (
                <p className="hint" style={{ marginTop: 8 }}>Showing the first {cards.length} of {total}. Narrow the template to see the rest.</p>
              )}
            </section>
          )
        })}
      </div>

      {move && (
        <MoveDialog
          requirement={move.req} target={move.target} pending={moveReq.isPending} error={moveError}
          onCancel={() => setMove(null)} onConfirm={(reason) => moveReq.mutate({ req: move.req, target: move.target, reason })}
        />
      )}
      {editing && teams && (
        <TemplateForm
          template={editing === 'edit' ? template : undefined} teams={teams}
          onClose={() => setEditing(null)} onSaved={(id) => choose(id)}
        />
      )}
      {deleting && template && (
        <Modal onClose={() => setDeleting(false)} title="Delete this template" maxWidth={420}>
          <h3 className="text-base font-semibold mb-1">Delete {template.name}?</h3>
          <p className="text-sm text-tx-2">Requirements are not affected; only this view goes.</p>
          {remove.isError && <p className="err-text" role="alert">It could not be deleted.</p>}
          <div className="actions">
            <button className="btn" onClick={() => setDeleting(false)}>Cancel</button>
            <button className="btn danger" disabled={remove.isPending} onClick={() => remove.mutate()}>Delete</button>
          </div>
        </Modal>
      )}
    </Page>
  )
}
