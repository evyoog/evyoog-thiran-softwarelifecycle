import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, api, type RiskDetail } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { UserPicker } from '@/shared/ui/UserPicker'
import { BAND_GLYPH, IMPACT_LABEL, LEVELS, LIKELIHOOD_LABEL, bandOf, ratingText } from './riskModel'

/** VYB-0949: add a risk, or edit one. The rating shown is likelihood times impact, read the same way the server reads it. */
export function RiskForm({ risk, onClose, onSaved }: { risk?: RiskDetail; onClose: () => void; onSaved: (id: string) => void }) {
  const qc = useQueryClient()
  const [title, setTitle] = useState(risk?.risk.title ?? '')
  const [description, setDescription] = useState(risk?.description ?? '')
  const [likelihood, setLikelihood] = useState(risk?.risk.likelihood ?? 3)
  const [impact, setImpact] = useState(risk?.risk.impact ?? 3)
  const [ownerId, setOwnerId] = useState(risk?.risk.ownerId ?? '')
  const [mitigation, setMitigation] = useState(risk?.mitigation ?? '')
  const rating = likelihood * impact
  const band = bandOf(rating)

  const save = useMutation({
    mutationFn: async () => {
      const body = { title: title.trim(), description: description.trim() || undefined, likelihood, impact, ownerId: ownerId || undefined, mitigation: mitigation.trim() || undefined }
      if (risk) { await api.updateRisk(risk.risk.id, body); return risk.risk.id }
      return (await api.createRisk(body)).id
    },
    onSuccess: (id) => {
      for (const key of ['risks', 'risk', 'requirement-linked']) void qc.invalidateQueries({ queryKey: [key] })
      onSaved(id)
      onClose()
    },
  })
  const problem = save.error instanceof ApiError ? (save.error.detail ?? save.error.title) : save.isError ? 'Could not save this risk.' : undefined

  return (
    <Modal onClose={onClose} title={risk ? 'Edit risk' : 'Add a risk'} maxWidth={600}>
      <h3 className="text-base font-semibold mb-2">{risk ? `Edit ${risk.risk.key}` : 'Add a risk'}</h3>
      <div className="field">
        <label className="label" htmlFor="rk-title">What could go wrong</label>
        <input id="rk-title" className="input" value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} />
      </div>
      <div className="field">
        <label className="label" htmlFor="rk-desc">Detail (optional)</label>
        <textarea id="rk-desc" className="input" rows={3} value={description} maxLength={4000} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <div className="row">
        <div className="field">
          <label className="label" htmlFor="rk-l">Likelihood</label>
          <select id="rk-l" className="select" value={likelihood} onChange={(e) => setLikelihood(Number(e.target.value))}>
            {LEVELS.map((n) => <option key={n} value={n}>{n} — {LIKELIHOOD_LABEL[n]}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="label" htmlFor="rk-i">Impact</label>
          <select id="rk-i" className="select" value={impact} onChange={(e) => setImpact(Number(e.target.value))}>
            {LEVELS.map((n) => <option key={n} value={n}>{n} — {IMPACT_LABEL[n]}</option>)}
          </select>
        </div>
      </div>
      <p className="text-sm" role="status"><span aria-hidden="true">{BAND_GLYPH[band]} </span>Rating {ratingText({ rating, band })}: likelihood {likelihood} × impact {impact}</p>
      <div className="field">
        <label className="label">Owner (optional)</label>
        <UserPicker value={ownerId} onSelect={setOwnerId} placeholder={risk?.risk.ownerName ?? 'Search for a person'} />
      </div>
      <div className="field">
        <label className="label" htmlFor="rk-mit">Mitigation (optional)</label>
        <textarea id="rk-mit" className="input" rows={3} value={mitigation} maxLength={4000} onChange={(e) => setMitigation(e.target.value)} />
      </div>
      {problem && <p className="err-text" role="alert">{problem}</p>}
      <div className="actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn pri" disabled={!title.trim() || save.isPending} onClick={() => save.mutate()}>{risk ? 'Save' : 'Add risk'}</button>
      </div>
    </Modal>
  )
}
