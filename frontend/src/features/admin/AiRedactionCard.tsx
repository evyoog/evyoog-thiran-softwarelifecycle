import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError, api } from '@/shared/api/client'
import { REDACTION_KINDS, isOn, stateText, summary, toggled } from './aiRedaction'

/**
 * VYB-0937 (F27): what is taken out of text before it goes to an AI provider, and the switch for each kind of personal
 * data. Secrets are always removed and have no switch. A change is saved at once, audited, and takes effect on the next
 * AI call. The state of each kind is a word (On or Off) as well as the tick.
 */
export function AiRedactionCard({ disabled }: { disabled: string[] }) {
  const qc = useQueryClient()
  const save = useMutation({
    mutationFn: (next: string[]) => api.setAiRedaction(next),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['app-config'] }),
  })
  const problem = save.error instanceof ApiError ? (save.error.detail ?? save.error.title) : save.isError ? 'Could not save this change.' : undefined

  return (
    <div className="card" style={{ marginTop: 16 }} role="group" aria-label="AI redaction">
      <div className="eyebrow" style={{ marginBottom: 4 }}>Before text goes to the AI provider</div>
      <p className="hint muted" style={{ fontSize: 11.5, marginTop: 0 }}>
        Secrets are removed. Each kind of personal data below is replaced by a placeholder and put back in the reply, so the
        provider never sees it. Names are found only for people in this system.
      </p>
      {REDACTION_KINDS.map((k) => {
        const on = isOn(k, disabled)
        return (
          <label key={k.key} className="row" style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: '6px 0' }}>
            <input type="checkbox" checked={on} disabled={k.locked || save.isPending}
              onChange={() => save.mutate(toggled(disabled, k.key))} aria-describedby={`redact-${k.key}`} />
            <span>
              <strong>{k.label}</strong> <span className="mono muted" style={{ fontSize: 11 }}>{k.locked ? 'Always on' : stateText(on)}</span>
              <span id={`redact-${k.key}`} className="hint muted" style={{ display: 'block', fontSize: 11 }}>{k.detail}</span>
            </span>
          </label>
        )
      })}
      <p className="hint" style={{ fontSize: 11.5, marginBottom: 0 }}>{summary(disabled)}</p>
      {problem && <p className="err-text" role="alert">{problem}</p>}
    </div>
  )
}
