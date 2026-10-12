import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { api, type RequirementStatus } from '@/shared/api/client'
import { StatusBadge } from '@/shared/ui/Badges'
import {
  INCOMPLETE_NOTE, actorText, changeSummaries, defaultPair, diffStats, diffWords, fieldChanges, revisionsOf, statusMoveText,
  visibleEntries, type Revision,
} from './revisionDiff'

/**
 * VYB-0941 (F18): a requirement's revisions and status changes, newest first, and a word-level comparison of any two revisions
 * (default: the one before the latest, and the latest). Added words are underlined and marked +, removed words are struck through
 * and marked −, and a legend and a count say the same, so colour is never the only signal. Nothing here edits anything.
 */
export function HistorySection({ requirementId }: { requirementId: string }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['requirement-history', requirementId],
    queryFn: () => api.requirementHistory(requirementId),
  })
  const [showAll, setShowAll] = useState(false)
  const [picked, setPicked] = useState<{ from: number; to: number } | null>(null)

  const revisions = useMemo(() => revisionsOf(data?.entries ?? []), [data])
  const summaries = useMemo(() => changeSummaries(revisions), [revisions])
  const pair = picked ?? defaultPair(revisions)

  return (
    <section className="border-b border-line pb-6" aria-label="History">
      <h2 className="text-[11.5px] font-semibold uppercase tracking-[.05em] text-tx-3 pb-2.5 mb-4 border-b border-line">History</h2>
      {isLoading && <p className="text-sm text-tx-3">Loading…</p>}
      {isError && <p className="text-sm text-crit" role="alert">Could not load the history.</p>}
      {data && data.entries.length === 0 && (
        <div className="text-center py-8 bg-panel-2 rounded-lg border border-dashed border-line-2">
          <p className="font-medium text-tx-2 text-sm">No history on record</p>
          <p className="text-sm text-tx-3">This requirement has no stored revisions.</p>
        </div>
      )}
      {data && data.entries.length > 0 && (
        <>
          <ol className="m-0 p-0 list-none">
            {visibleEntries(data.entries, showAll).map((e, i) => (
              <li key={`${e.kind}-${e.at}-${e.revision ?? i}`} className="py-2.5 border-b border-line last:border-0 text-sm">
                {e.kind === 'REVISION' ? (
                  <>
                    <div className="flex flex-wrap gap-x-3 items-baseline">
                      <span className="font-mono font-medium">Revision {e.revision}</span>
                      <span className="text-tx-2">{summaries.get(e.revision as number)}</span>
                      <span className="text-xs text-tx-3">{actorText(e)} · {new Date(e.at).toLocaleString()}</span>
                    </div>
                    {e.reason && <p className="text-xs text-tx-3 mt-0.5">Reason: {e.reason}</p>}
                  </>
                ) : (
                  <>
                    <div className="flex flex-wrap gap-x-3 items-baseline">
                      <span className="inline-flex items-center gap-1.5">
                        <span className="text-xs text-tx-3">Status</span>
                        {e.fromStatus && <StatusBadge status={e.fromStatus as RequirementStatus} />}
                        <span aria-label="to">→</span>
                        {e.toStatus && <StatusBadge status={e.toStatus as RequirementStatus} />}
                      </span>
                      <span className="sr-only">{statusMoveText(e)}</span>
                      <span className="text-xs text-tx-3">{actorText(e)} · {new Date(e.at).toLocaleString()}</span>
                    </div>
                    {e.reason && <p className="text-xs text-tx-3 mt-0.5">Reason: {e.reason}</p>}
                  </>
                )}
              </li>
            ))}
          </ol>
          {data.entries.length > 6 && (
            <button className="text-sm text-brand mt-2 underline" onClick={() => setShowAll((s) => !s)} aria-expanded={showAll}>
              {showAll ? 'Show fewer' : `Show all ${data.entries.length}`}
            </button>
          )}
          {data.statusHistoryMayBeIncomplete && <p className="text-xs text-tx-3 mt-2" role="note">{INCOMPLETE_NOTE}</p>}

          {revisions.length > 1 && pair && (
            <Compare revisions={revisions} pair={pair} onPick={setPicked} />
          )}
        </>
      )}
    </section>
  )
}

function Compare({ revisions, pair, onPick }: {
  revisions: Revision[]
  pair: { from: number; to: number }
  onPick: (p: { from: number; to: number }) => void
}) {
  const from = revisions.find((r) => r.revision === pair.from)
  const to = revisions.find((r) => r.revision === pair.to)
  const changes = from && to ? fieldChanges(from, to) : []
  const statement = from && to ? diffWords(from.statement ?? '', to.statement ?? '') : []
  const stats = diffStats(statement)
  const others = changes.filter((c) => c.field !== 'statement')
  const statementChanged = changes.some((c) => c.field === 'statement')

  return (
    <div className="mt-5" role="group" aria-label="Compare revisions">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="text-tx-3">Compare</span>
        <label className="sr-only" htmlFor="hist-from">From revision</label>
        <select id="hist-from" className="p-1 bg-panel border border-line-2 rounded text-sm" value={pair.from}
          onChange={(e) => onPick({ from: Number(e.target.value), to: pair.to })}>
          {revisions.map((r) => <option key={r.revision} value={r.revision}>Revision {r.revision}</option>)}
        </select>
        <span className="text-tx-3">with</span>
        <label className="sr-only" htmlFor="hist-to">To revision</label>
        <select id="hist-to" className="p-1 bg-panel border border-line-2 rounded text-sm" value={pair.to}
          onChange={(e) => onPick({ from: pair.from, to: Number(e.target.value) })}>
          {revisions.map((r) => <option key={r.revision} value={r.revision}>Revision {r.revision}</option>)}
        </select>
      </div>

      {pair.from === pair.to && <p className="text-sm text-tx-3 mt-2">Choose two different revisions to see what changed.</p>}
      {pair.from !== pair.to && changes.length === 0 && (
        <p className="text-sm text-tx-3 mt-2">Nothing differs between these revisions in title, statement, type, priority or capability.</p>
      )}

      {pair.from !== pair.to && changes.length > 0 && (
        <div className="mt-3">
          {others.length > 0 && (
            <dl className="m-0 mb-3 text-sm">
              {others.map((c) => (
                <div key={c.field} className="flex flex-wrap gap-x-2 py-0.5">
                  <dt className="text-tx-3 w-24">{c.label}</dt>
                  <dd className="m-0"><del className="line-through text-tx-3"><span className="sr-only">removed: </span>{c.from || 'empty'}</del>
                    {' → '}<ins className="underline"><span className="sr-only">added: </span>{c.to || 'empty'}</ins></dd>
                </div>
              ))}
            </dl>
          )}
          {statementChanged && (
            <>
              <p className="text-xs text-tx-3 mb-1">
                Statement: <span aria-hidden="true">+</span> {stats.added} word{stats.added === 1 ? '' : 's'} added (underlined),{' '}
                <span aria-hidden="true">−</span> {stats.removed} removed (struck through)
              </p>
              <p className="text-sm leading-relaxed p-3 bg-panel-2 rounded-lg border border-line whitespace-pre-wrap" data-testid="statement-diff">
                {statement.map((s, i) => s.op === 'same'
                  ? <span key={i}>{s.text}</span>
                  : s.op === 'add'
                    ? <ins key={i} className="underline bg-ok-dim text-ok"><span className="sr-only"> [added: </span>{s.text}<span className="sr-only">] </span></ins>
                    : <del key={i} className="line-through bg-crit-dim text-crit"><span className="sr-only"> [removed: </span>{s.text}<span className="sr-only">] </span></del>)}
              </p>
            </>
          )}
        </div>
      )}
    </div>
  )
}
