import { useQuery } from '@tanstack/react-query'
import { Fragment, useState } from 'react'
import { api, type ConnectorStatus, type ConnectorSyncEntry } from '@/shared/api/client'
import { Empty } from '@/shared/ui/Page'
import {
  STATE_CLASS, STATE_GLYPH, STATE_LABEL, attentionOrder, detailFor, formatDuration, lastSuccessText, lastSyncText,
  sends, summarise, syncStatusLabel,
} from './connectorHealth'

const REFRESH_MS = 15_000

/**
 * VYB-0917 (F40): where an administrator sees whether each connection is working: its state, why it cannot
 * send, the last failure, when it last succeeded, and the log of what was sent through it. Read-only; a
 * connection is set up under "Connected systems" (the tab beside it).
 *
 * <p>Principle 8: absence reads "not connected" / "never succeeded" / "nothing sent yet", never blank or zero,
 * and the reason a receiver gave for a failure is hatched with the connection named as its source, because
 * Vyoog displays it and does not own it. State is a label and a glyph as well as a colour, and never amber
 * (amber means AI).
 */
export function ConnectorHealthTab() {
  const { data, isLoading, isError, isFetching, refetch, dataUpdatedAt } = useQuery({
    queryKey: ['connector-health'], queryFn: api.connectorHealth, refetchInterval: REFRESH_MS,
  })
  const [open, setOpen] = useState<string | null>(null)

  if (isLoading) return <p className="eyebrow">Loading…</p>
  if (isError || !data) {
    return (
      <div className="empty">
        <h4>Could not load connector health</h4>
        <p>The request failed. Connector health needs a platform administrator grant; if you have one, try again.</p>
        <button className="btn" onClick={() => void refetch()}>Try again</button>
      </div>
    )
  }

  const rows = attentionOrder(data)
  const summary = summarise(data)

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10, gap: 10, flexWrap: 'wrap' }}>
        <p className="hint muted" role="status" aria-live="polite">{summary.text}</p>
        <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="hint muted">Updated {new Date(dataUpdatedAt).toLocaleTimeString()}</span>
          <button className="btn" onClick={() => void refetch()} disabled={isFetching}>{isFetching ? 'Refreshing…' : 'Refresh'}</button>
        </span>
      </div>

      {summary.total === 0 ? (
        <Empty title="No connections registered" desc="Add one under Connected systems." />
      ) : (
        <div className="tbl-wrap">
          <table className="tbl cn-tbl">
            <thead>
              <tr><th>Connection</th><th>State</th><th>Detail</th><th>Last success</th><th>Last sent</th><th aria-label="History" /></tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <ConnectorRow key={s.key} status={s} expanded={open === s.key}
                  onToggle={() => setOpen(open === s.key ? null : s.key)} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="hint muted" style={{ marginTop: 10 }}>
        Set a connection up, or mark it connected, under <b>Connected systems</b>. A connection is degraded after three
        failed operations in a row and healthy again as soon as one succeeds.
      </p>
    </>
  )
}

function ConnectorRow({ status, expanded, onToggle }: { status: ConnectorStatus; expanded: boolean; onToggle: () => void }) {
  const detail = detailFor(status)
  const panelId = `cn-history-${status.key}`
  return (
    <Fragment>
      <tr style={{ cursor: 'default' }}>
        <td>
          <span className="mono">{status.key}</span>
          <div className="hint muted">{status.owns ?? 'no description'} · <span className="mono">{status.direction}</span></div>
        </td>
        <td>
          <span className={`badge ${STATE_CLASS[status.state]}`}>
            <span aria-hidden="true">{STATE_GLYPH[status.state]}</span> {STATE_LABEL[status.state]}
          </span>
        </td>
        <td>
          {detail.kind === 'text' ? (
            <span className="muted">{detail.text}</span>
          ) : (
            <>
              <div>{detail.lead}</div>
              <BorrowedText source={detail.source} text={detail.text} />
            </>
          )}
        </td>
        <td className="muted">{lastSuccessText(status)}</td>
        <td className="muted">{lastSyncText(status)}</td>
        <td style={{ textAlign: 'right' }}>
          {/* an inbound connection sends nothing, so it has no send history */}
          {sends(status) && (
            <button className="btn" aria-expanded={expanded} aria-controls={panelId} onClick={onToggle}>
              {expanded ? 'Hide history' : 'History'}
            </button>
          )}
        </td>
      </tr>
      {expanded && sends(status) && (
        <tr style={{ cursor: 'default' }}>
          <td colSpan={6} id={panelId}>
            <SyncHistory connectionKey={status.key} />
          </td>
        </tr>
      )}
    </Fragment>
  )
}

/** Words from the other system: hatched, with whose they are. */
function BorrowedText({ source, text }: { source: string; text: string }) {
  return (
    <div className="cn-borrowed">
      <div className="cn-src">reported by {source}</div>
      <div className="mono" style={{ fontSize: 11.5, wordBreak: 'break-word' }}>{text}</div>
    </div>
  )
}

function SyncHistory({ connectionKey }: { connectionKey: string }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['connector-sync-log', connectionKey], queryFn: () => api.connectorSyncLog(connectionKey, 20),
    refetchInterval: REFRESH_MS,
  })
  if (isLoading) return <p className="eyebrow">Loading…</p>
  if (isError || !data) return <p className="hint muted">Could not load the history of {connectionKey}.</p>
  if (data.length === 0) return <p className="hint muted">Nothing has been sent through {connectionKey} yet.</p>
  return (
    <table className="tbl cn-log" aria-label={`Recent operations on ${connectionKey}`}>
      <thead><tr><th>Started</th><th>Operation</th><th>Result</th><th>HTTP</th><th>Took</th><th>Receiver's reason</th></tr></thead>
      <tbody>
        {data.map((e: ConnectorSyncEntry) => (
          <tr key={e.id} style={{ cursor: 'default' }}>
            <td className="mono muted">{new Date(e.startedAt).toLocaleString()}</td>
            <td className="mono">{e.operation}</td>
            <td>{syncStatusLabel(e)}</td>
            <td className="mono">{e.httpStatus ?? '—'}</td>
            <td className="mono muted">{formatDuration(e)}</td>
            <td>{e.error ? <BorrowedText source={connectionKey} text={e.error} /> : <span className="muted">—</span>}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
