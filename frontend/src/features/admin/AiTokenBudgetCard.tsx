import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, api } from '@/shared/api/client'
import { budgetSummary, parseBudget } from '../analytics/aiUsage'

/**
 * VYB-0939 (F29): the most tokens the AI may use per UTC day and per UTC month. Empty means no limit; the tighter of the two
 * applies; once it is reached further AI calls are refused with the reason. It is a count of tokens, never an amount of money.
 * Both are saved together and audited. The server refuses a limit of zero or less; this checks first so the reason reads well.
 */
export function AiTokenBudgetCard({ daily, monthly }: { daily: number | null | undefined; monthly: number | null | undefined }) {
  const qc = useQueryClient()
  const [dailyText, setDailyText] = useState(daily == null ? '' : String(daily))
  const [monthlyText, setMonthlyText] = useState(monthly == null ? '' : String(monthly))
  const [message, setMessage] = useState<string>()
  const save = useMutation({
    mutationFn: (v: { daily: number | null; monthly: number | null }) => api.setAiTokenBudget(v.daily, v.monthly),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['app-config'] })
      void qc.invalidateQueries({ queryKey: ['ai-usage-summary'] })
    },
  })

  const d = parseBudget(dailyText)
  const m = parseBudget(monthlyText)
  const invalid = !d.ok ? d.message : !m.ok ? m.message : undefined
  const changed = d.ok && m.ok && (d.value !== (daily ?? null) || m.value !== (monthly ?? null))
  const problem = message ?? (save.error instanceof ApiError ? (save.error.detail ?? save.error.title) : save.isError ? 'Could not save this change.' : undefined)

  function submit() {
    if (!d.ok || !m.ok) { setMessage(invalid); return }
    setMessage(undefined)
    save.mutate({ daily: d.value, monthly: m.value })
  }

  return (
    <div className="card" style={{ marginTop: 16 }} role="group" aria-label="AI token budget">
      <div className="eyebrow" style={{ marginBottom: 4 }}>AI token budget</div>
      <p className="hint muted" style={{ fontSize: 11.5, marginTop: 0 }}>
        The most tokens the AI may use in a day and in a month (both UTC). Leave a box empty for no limit. When one is reached,
        AI calls are refused and say why, until it resets or you raise it. This counts tokens, not money.
      </p>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
        <label style={{ display: 'grid', gap: 4 }}>
          <span className="hint">Per day</span>
          <input className="input mono" inputMode="numeric" value={dailyText} placeholder="No limit" aria-invalid={!d.ok}
            onChange={(e) => { setDailyText(e.target.value); setMessage(undefined) }} />
        </label>
        <label style={{ display: 'grid', gap: 4 }}>
          <span className="hint">Per month</span>
          <input className="input mono" inputMode="numeric" value={monthlyText} placeholder="No limit" aria-invalid={!m.ok}
            onChange={(e) => { setMonthlyText(e.target.value); setMessage(undefined) }} />
        </label>
        <button className="btn pri" disabled={!changed || save.isPending} onClick={submit}>
          {save.isPending ? 'Saving…' : 'Save'}
        </button>
      </div>
      <p className="hint" style={{ fontSize: 11.5, marginBottom: 0 }}>{budgetSummary(daily, monthly)}</p>
      {(problem || invalid) && <p className="err-text" role="alert">{problem ?? invalid}</p>}
    </div>
  )
}
