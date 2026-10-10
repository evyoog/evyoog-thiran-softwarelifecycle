import { useQuery } from '@tanstack/react-query'
import { api } from '@/shared/api/client'
import {
  BUDGET_CLASS, BUDGET_GLYPH, BUDGET_LABEL, budgetState, dayLabel, percentOf, shareText, tallest, tokens, usedOf,
  type BudgetState,
} from './aiUsage'

/**
 * VYB-0939 (F30): what the AI used, for any signed-in person: the day's and the month's tokens beside their limits, a chart of
 * the last 30 days, and the month by purpose, prompt version and model. There is no per-person view because none is recorded,
 * and nothing here is money (CLAUDE.md rule 7, D32). A limit's state is a word and a glyph as well as a colour, never amber.
 * Refreshes every 30 seconds.
 */
export function AiUsageTab() {
  const { data, isLoading, isError, refetch, isFetching } = useQuery({
    queryKey: ['ai-usage-summary', 30], queryFn: () => api.aiUsageSummary(30), refetchInterval: 30_000,
  })

  if (isLoading) return <p className="eyebrow">Loading…</p>
  if (isError || !data) {
    return (
      <div className="empty">
        <h4>Could not load AI usage</h4>
        <p>The request failed. Try again.</p>
        <button className="btn" onClick={() => void refetch()}>Try again</button>
      </div>
    )
  }

  const top = tallest(data.byDay)
  const month = data.thisMonth
  const noCalls = month.length === 0
  return (
    <>
      <p className="hint muted" style={{ marginTop: 0 }}>
        Tokens the AI used, counted as the provider reported them. Days and months are UTC. This is usage by purpose, prompt
        version and model; it is not by person and it is not money.
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 12, marginBottom: 16 }}>
        <BudgetCard title="Today" used={data.usedToday} limit={data.limits.daily} resets="Resets at 00:00 UTC" />
        <BudgetCard title="This month" used={data.usedThisMonth} limit={data.limits.monthly} resets={`Resets ${data.monthResets} 00:00 UTC`} />
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        <div className="eyebrow" style={{ marginBottom: 8 }}>Tokens per day, last {data.byDay.length} days</div>
        <div role="img" aria-label={`Tokens per day over the last ${data.byDay.length} days; the busiest day used ${tokens(top === 1 ? 0 : top)}.`}
          style={{ display: 'flex', alignItems: 'flex-end', gap: 2, height: 80 }}>
          {data.byDay.map((d) => (
            <div key={d.day} title={`${dayLabel(d.day)}: ${tokens(d.tokens)}, ${d.calls} calls`}
              style={{ flex: 1, minWidth: 2, height: `${Math.max(d.tokens > 0 ? 4 : 1, (d.tokens / top) * 100)}%`, background: d.tokens > 0 ? 'var(--info)' : 'var(--line-2)' }} />
          ))}
        </div>
        <div className="hint muted" style={{ display: 'flex', justifyContent: 'space-between', marginTop: 4 }}>
          <span>{dayLabel(data.byDay[0]!.day)}</span><span>{dayLabel(data.byDay[data.byDay.length - 1]!.day)}</span>
        </div>
      </div>

      <div className="eyebrow" style={{ marginBottom: 6 }}>This month, by purpose, prompt version and model</div>
      {noCalls ? (
        <div className="empty"><h4>No AI calls this month</h4><p>Calls appear here once the AI is used. If AI is switched off, none are made.</p></div>
      ) : (
        <div className="tbl-wrap">
          <table className="tbl" aria-label="AI usage this month by purpose, prompt version and model">
            <thead>
              <tr>
                <th scope="col">Purpose</th><th scope="col">Prompt version</th><th scope="col">Model</th>
                <th scope="col" style={{ textAlign: 'right' }}>Calls</th><th scope="col" style={{ textAlign: 'right' }}>Failed</th>
                <th scope="col" style={{ textAlign: 'right' }}>Refused</th>
                <th scope="col" style={{ textAlign: 'right' }}>Prompt</th><th scope="col" style={{ textAlign: 'right' }}>Reply</th>
                <th scope="col" style={{ textAlign: 'right' }}>Tokens</th><th scope="col" style={{ textAlign: 'right' }}>Share</th>
              </tr>
            </thead>
            <tbody>
              {month.map((r) => (
                <tr key={`${r.purpose}/${r.promptVersion}/${r.model}`} style={{ cursor: 'default' }}>
                  <td>{r.purpose}</td>
                  <td className="mono">{r.promptVersion}</td>
                  <td className="mono">{r.model}</td>
                  <td className="mono" style={{ textAlign: 'right' }}>{r.calls.toLocaleString('en-US')}</td>
                  <td className="mono" style={{ textAlign: 'right' }}>{r.failed.toLocaleString('en-US')}</td>
                  <td className="mono" style={{ textAlign: 'right' }}>{r.refused.toLocaleString('en-US')}</td>
                  <td className="mono" style={{ textAlign: 'right' }}>{r.promptTokens.toLocaleString('en-US')}</td>
                  <td className="mono" style={{ textAlign: 'right' }}>{r.completionTokens.toLocaleString('en-US')}</td>
                  <td className="mono" style={{ textAlign: 'right' }}>{r.totalTokens.toLocaleString('en-US')}</td>
                  <td className="mono" style={{ textAlign: 'right' }}>{shareText(r, month)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="hint muted" style={{ marginTop: 10 }}>
        <b>Failed</b> calls reached the provider and did not succeed. <b>Refused</b> calls were stopped by the token limit before
        anything was sent; a long run of refusals is recorded about once a minute per purpose. A call whose tokens the provider did
        not report counts as zero. Only an administrator sets the limits (Administration, Settings).
        {isFetching ? ' Refreshing…' : ''}
      </p>
    </>
  )
}

function BudgetCard({ title, used, limit, resets }: { title: string; used: number; limit: number | null | undefined; resets: string }) {
  const state: BudgetState = budgetState(used, limit)
  const pct = percentOf(used, limit)
  return (
    <div className="card" role="group" aria-label={`${title} token budget`}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
        <span className="eyebrow">{title}</span>
        <span className={`badge ${BUDGET_CLASS[state]}`}><span aria-hidden="true">{BUDGET_GLYPH[state]}</span> {BUDGET_LABEL[state]}</span>
      </div>
      <div className="mono" style={{ fontSize: 15, margin: '8px 0 6px' }}>{usedOf(used, limit)}</div>
      {pct !== null && (
        <div className="ai-bar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-label={`${title}: ${pct}% of the limit used`}>
          <span style={{ width: `${pct}%` }} />
        </div>
      )}
      <div className="hint muted" style={{ marginTop: 6 }}>{limit == null ? 'No limit is set.' : resets}</div>
    </div>
  )
}
