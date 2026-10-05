import type { ConnectorState, ConnectorStatus, ConnectorSyncEntry } from '@/shared/api/client'

/**
 * VYB-0917: the logic of the Administration "Connector health" screen, kept out of the component so it can be
 * tested without a browser.
 *
 * Principle 8 is the rule behind most of it: a connection that is not connected, or has never sent anything,
 * says so ("not connected", "never succeeded", "nothing sent yet"). It is never blank and never a zero that
 * reads as a measurement. And text that came from the other system (the receiver's reason for a failure) is
 * marked as borrowed, so it is not mistaken for something Vyoog worked out.
 */

export const STATE_LABEL: Record<ConnectorState, string> = {
  HEALTHY: 'Healthy',
  DEGRADED: 'Degraded',
  NOT_CONNECTED: 'Not connected',
}

/** A glyph beside the label so the state is never carried by colour alone. */
export const STATE_GLYPH: Record<ConnectorState, string> = {
  HEALTHY: '●',
  DEGRADED: '▲',
  NOT_CONNECTED: '○',
}

/** Badge classes (tokens.css). Never the amber token: amber means AI (CLAUDE.md rule 5). */
export const STATE_CLASS: Record<ConnectorState, string> = {
  HEALTHY: 'cn-healthy',
  DEGRADED: 'cn-degraded',
  NOT_CONNECTED: 'cn-off',
}

const ORDER: Record<ConnectorState, number> = { DEGRADED: 0, HEALTHY: 1, NOT_CONNECTED: 2 }

/** Degraded first, because that is what an administrator opens this for; then healthy; then those not connected. Stable by key within each. */
export function attentionOrder(statuses: ConnectorStatus[]): ConnectorStatus[] {
  return [...statuses].sort((a, b) => ORDER[a.state] - ORDER[b.state] || a.key.localeCompare(b.key))
}

export interface ConnectorSummary {
  total: number
  healthy: number
  degraded: number
  notConnected: number
  text: string
}

export function summarise(statuses: ConnectorStatus[]): ConnectorSummary {
  const count = (s: ConnectorState) => statuses.filter((c) => c.state === s).length
  const healthy = count('HEALTHY')
  const degraded = count('DEGRADED')
  const notConnected = count('NOT_CONNECTED')
  const total = statuses.length
  if (total === 0) return { total, healthy, degraded, notConnected, text: 'No connections are registered.' }
  const parts = [`${healthy} healthy`, `${degraded} degraded`, `${notConnected} not connected`]
  return { total, healthy, degraded, notConnected, text: `${total} connection${total === 1 ? '' : 's'}: ${parts.join(', ')}.` }
}

/** "just now", "3 min ago", "2 h ago", "4 d ago". Empty string for a time that cannot be read. */
export function ago(iso: string | undefined, now: number = Date.now()): string {
  if (!iso) return ''
  const then = Date.parse(iso)
  if (Number.isNaN(then)) return ''
  const seconds = Math.max(0, Math.round((now - then) / 1000))
  if (seconds < 45) return 'just now'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.round(minutes / 60)
  if (hours < 48) return `${hours} h ago`
  return `${Math.round(hours / 24)} d ago`
}

/** "250 ms", "1.2 s", "in progress" for an operation that has not finished, "—" if unknown. */
export function formatDuration(entry: Pick<ConnectorSyncEntry, 'status' | 'durationMs'>): string {
  if (entry.status === 'IN_PROGRESS') return 'in progress'
  const ms = entry.durationMs
  if (ms === undefined || ms === null || ms < 0) return '—'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)} s`
}

export function syncStatusLabel(entry: Pick<ConnectorSyncEntry, 'status' | 'attempts'>): string {
  switch (entry.status) {
    case 'SUCCEEDED': return entry.attempts > 1 ? `Succeeded (attempt ${entry.attempts})` : 'Succeeded'
    case 'IN_PROGRESS': return 'In progress'
    case 'FAILED': return entry.attempts > 0 ? `Failed after ${entry.attempts} attempt${entry.attempts === 1 ? '' : 's'}` : 'Failed'
  }
}

/** Whether anything is sent from here through this connection. An INBOUND one only receives. */
export function sends(status: Pick<ConnectorStatus, 'direction'>): boolean {
  return status.direction !== 'INBOUND'
}

/**
 * When the connection last worked: for one that sends, its last successful operation; for one that receives, its
 * last verified delivery. A connection that has never worked says why, in words, not a blank.
 */
export function lastSuccessText(
  status: Pick<ConnectorStatus, 'state' | 'lastSuccessAt'> & Partial<Pick<ConnectorStatus, 'direction'>>,
  now: number = Date.now(),
): string {
  const when = ago(status.lastSuccessAt, now)
  if (when) return when
  if (status.state === 'NOT_CONNECTED') return 'not connected'
  return status.direction === 'INBOUND' ? 'nothing received yet' : 'never succeeded'
}

export function lastSyncText(
  status: Pick<ConnectorStatus, 'lastSync'> & Partial<Pick<ConnectorStatus, 'direction'>>,
  now: number = Date.now(),
): string {
  if (status.direction === 'INBOUND') return 'receives only'
  const sync = status.lastSync
  if (!sync) return 'nothing sent yet'
  const when = ago(sync.finishedAt ?? sync.startedAt, now)
  return `${sync.operation}: ${syncStatusLabel(sync).toLowerCase()}${when ? `, ${when}` : ''}`
}

export type Detail =
  /** Something Vyoog worked out or knows about its own setup. */
  | { kind: 'text'; text: string }
  /** Words from the other system. Shown hatched, with the connection named as the source. */
  | { kind: 'borrowed'; text: string; source: string; lead: string }

/**
 * What to say beside the state. A degraded connection leads with the receiver's last reason, marked as
 * theirs; one that cannot send yet says what is missing; a healthy one says what the code on it does.
 */
export function detailFor(status: ConnectorStatus): Detail {
  if (status.state === 'DEGRADED') {
    const lead = `${status.consecutiveFailures} failed operation${status.consecutiveFailures === 1 ? '' : 's'} in a row`
    return status.lastError
      ? { kind: 'borrowed', text: status.lastError, source: status.key, lead }
      : { kind: 'text', text: lead }
  }
  if (status.state === 'NOT_CONNECTED') {
    if (!sends(status)) return { kind: 'text', text: 'Not connected.' }
    if (status.notConfiguredReason) return { kind: 'text', text: `Cannot send yet: ${status.notConfiguredReason}.` }
    return { kind: 'text', text: 'Configured, and nothing has been sent through it yet.' }
  }
  if (status.connectorDescription) return { kind: 'text', text: status.connectorDescription }
  if (!sends(status)) return { kind: 'text', text: 'Receives deliveries; nothing is sent from here.' }
  return { kind: 'text', text: 'No connector code is bound to this connection.' }
}
