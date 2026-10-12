import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, api, type TraceLinkType } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { KINDS, emptyDraft, missing, releaseChoice, type Draft, type LinkKind } from './linkWork'

const LINK_TYPES: TraceLinkType[] = ['SATISFIES', 'DERIVES', 'VERIFIES', 'IMPLEMENTS', 'REFINES', 'CONFLICTS']

/**
 * VYB-0943 (F19, D36): link a requirement to another requirement, a test case, a design node, a release or a defect, by picking
 * from a list, not by pasting an id. Each kind calls the mechanism it already had: a test is a VERIFIES trace link, design is the
 * design-node mapping, a release commits the requirement to its scope with a reason, a defect with no requirement yet is given
 * this one. The server decides who may do each and refuses what breaks a rule; its reason is shown as it is.
 */
export function LinkDialog({ requirementId, applicationId, onClose }: { requirementId: string; applicationId?: string | null; onClose: () => void }) {
  const qc = useQueryClient()
  const [draft, setDraft] = useState<Draft>(emptyDraft())
  const [search, setSearch] = useState('')
  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }))
  const choose = (kind: LinkKind) => { setDraft(emptyDraft(kind)); setSearch('') }
  const q = search.trim()

  const requirements = useQuery({ queryKey: ['link-pick-requirements', q], queryFn: () => api.requirements({ title: q, size: 8 }), enabled: draft.kind === 'requirement' && q.length >= 2 })
  const tests = useQuery({ queryKey: ['link-pick-tests', q], queryFn: () => api.testCases({ q, size: 8 }), enabled: draft.kind === 'test' && q.length >= 2 })
  const defects = useQuery({ queryKey: ['link-pick-defects', q], queryFn: () => api.defects({ untraced: true, state: 'ALL', q: q || undefined, size: 8 }), enabled: draft.kind === 'defect' })
  const releases = useQuery({ queryKey: ['releases'], queryFn: api.releases, enabled: draft.kind === 'release' })
  const flow = useQuery({ queryKey: ['design-flow', applicationId], queryFn: () => api.designFlowFor(applicationId!), enabled: draft.kind === 'design' && !!applicationId, retry: false })
  const nodes = useQuery({ queryKey: ['design-nodes', flow.data?.id], queryFn: () => api.designNodes(flow.data!.id), enabled: draft.kind === 'design' && !!flow.data })

  const save = useMutation({
    mutationFn: async () => {
      switch (draft.kind) {
        case 'requirement':
          return api.createLink(draft.direction === 'upstream'
            ? { fromType: 'REQUIREMENT', fromId: draft.targetId, toType: 'REQUIREMENT', toId: requirementId, linkType: draft.linkType }
            : { fromType: 'REQUIREMENT', fromId: requirementId, toType: 'REQUIREMENT', toId: draft.targetId, linkType: draft.linkType })
        case 'test':
          return api.createLink({ fromType: 'TEST', fromId: draft.targetId, toType: 'REQUIREMENT', toId: requirementId, linkType: 'VERIFIES' })
        case 'design':
          return api.linkDesignRequirement(draft.targetId, requirementId)
        case 'release':
          return api.commitToRelease(draft.targetId, requirementId, draft.reason.trim())
        case 'defect':
          return api.linkDefectToRequirement(draft.targetId, requirementId)
      }
    },
    onSuccess: () => {
      for (const key of ['requirement-linked', 'trace-links', 'requirement', 'requirements', 'release-matrix', 'defect-matrix']) {
        void qc.invalidateQueries({ queryKey: [key] })
      }
      onClose()
    },
  })
  const need = missing(draft)
  const problem = save.error instanceof ApiError ? (save.error.detail ?? save.error.title) : save.isError ? 'Could not save this link.' : undefined
  const kind = KINDS.find((k) => k.kind === draft.kind)!

  const pickList = (items: { id: string; label: string }[] | undefined, empty: string) => (
    <div role="listbox" aria-label={`Choose ${kind.label.toLowerCase()}`} className="mt-2 border border-line rounded max-h-48 overflow-auto">
      {(items ?? []).length === 0 && <p className="text-sm text-tx-3 p-2">{empty}</p>}
      {(items ?? []).map((i) => (
        <button key={i.id} role="option" aria-selected={draft.targetId === i.id} onClick={() => set({ targetId: i.id })}
          className={`block w-full text-left text-sm px-2 py-1.5 border-b border-line last:border-0 ${draft.targetId === i.id ? 'bg-brand-dim' : 'hover:bg-panel-2'}`}>
          {draft.targetId === i.id && <span aria-hidden="true">✓ </span>}{i.label}
        </button>
      ))}
    </div>
  )

  return (
    <Modal onClose={onClose} title="Link this requirement" maxWidth={560}>
      <h3 className="text-base font-semibold mb-2">Link this requirement</h3>
      <div role="tablist" aria-label="What to link" className="flex flex-wrap gap-1.5 mb-2">
        {KINDS.map((k) => (
          <button key={k.kind} role="tab" aria-selected={draft.kind === k.kind} className={`btn${draft.kind === k.kind ? ' pri' : ''}`} onClick={() => choose(k.kind)}>{k.label}</button>
        ))}
      </div>
      <p className="text-sm text-tx-2">{kind.how}</p>

      {draft.kind === 'requirement' && (
        <div className="grid grid-cols-2 gap-2 mt-3">
          <label className="text-sm"><span className="text-tx-3">Direction</span>
            <select className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm" value={draft.direction} onChange={(e) => set({ direction: e.target.value as Draft['direction'] })}>
              <option value="upstream">The other is upstream of this</option>
              <option value="downstream">The other is downstream of this</option>
            </select></label>
          <label className="text-sm"><span className="text-tx-3">Link type</span>
            <select className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm" value={draft.linkType} onChange={(e) => set({ linkType: e.target.value as TraceLinkType })}>
              {LINK_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select></label>
        </div>
      )}

      {(draft.kind === 'requirement' || draft.kind === 'test' || draft.kind === 'defect') && (
        <label className="block mt-3 text-sm"><span className="text-tx-3">{draft.kind === 'defect' ? 'Search defects with no requirement (or leave empty to list them)' : 'Search by name or key'}</span>
          <input className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm" value={search} onChange={(e) => { setSearch(e.target.value); set({ targetId: '' }) }} />
        </label>
      )}
      {draft.kind === 'requirement' && q.length >= 2 && pickList(requirements.data?.content.filter((r) => r.id !== requirementId).map((r) => ({ id: r.id, label: `${r.key} — ${r.title}` })), 'No requirement matches.')}
      {draft.kind === 'requirement' && q.length < 2 && <p className="text-xs text-tx-3 mt-1">Type at least two characters.</p>}
      {draft.kind === 'test' && q.length >= 2 && pickList(tests.data?.content.map((t) => ({ id: t.id, label: `${t.key} — ${t.title}` })), 'No test case matches.')}
      {draft.kind === 'test' && q.length < 2 && <p className="text-xs text-tx-3 mt-1">Type at least two characters.</p>}
      {draft.kind === 'defect' && pickList(defects.data?.content.map((d) => ({ id: d.id, label: `${d.key} — ${d.title} (${d.severity.toLowerCase()}, ${d.state.toLowerCase()})` })), 'Every defect already has a requirement.')}

      {draft.kind === 'design' && (
        !applicationId
          ? <p className="text-sm text-tx-3 mt-3" role="status">This requirement is not in an application yet. Place it in a capability first, then link design.</p>
          : flow.isError
            ? <p className="text-sm text-tx-3 mt-3" role="status">This application has no design yet. Create its design flow on the Design screen first.</p>
            : pickList(nodes.data?.map((n) => ({ id: n.id, label: `${n.label} (${n.kind})` })), 'This design has no nodes yet.')
      )}

      {draft.kind === 'release' && (
        <>
          <label className="block mt-3 text-sm"><span className="text-tx-3">Release</span>
            <select className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm" value={draft.targetId} onChange={(e) => set({ targetId: e.target.value })}>
              <option value="">Choose a release…</option>
              {(releases.data ?? []).map((r) => { const c = releaseChoice(r); return <option key={r.id} value={r.id} disabled={c.disabled}>{c.label}</option> })}
            </select></label>
          <label className="block mt-3 text-sm"><span className="text-tx-3">Reason</span>
            <input className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm" value={draft.reason} onChange={(e) => set({ reason: e.target.value })} placeholder="Why this release" /></label>
        </>
      )}

      {problem && <p className="text-sm text-crit mt-3" role="alert">{problem}</p>}
      <div className="flex items-center gap-2 justify-end mt-4">
        {need && <span className="text-xs text-tx-3 mr-auto" role="status">{need}</span>}
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn pri" disabled={need !== null || save.isPending} onClick={() => save.mutate()}>{save.isPending ? 'Linking…' : 'Link'}</button>
      </div>
    </Modal>
  )
}
