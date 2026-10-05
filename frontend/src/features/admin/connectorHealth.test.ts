import { describe, expect, it } from 'vitest'
import type { ConnectorStatus, ConnectorSyncEntry } from '@/shared/api/client'
import {
  STATE_CLASS, STATE_GLYPH, STATE_LABEL, ago, attentionOrder, detailFor, formatDuration, lastSuccessText,
  lastSyncText, sends, summarise, syncStatusLabel,
} from './connectorHealth'

function status(over: Partial<ConnectorStatus> = {}): ConnectorStatus {
  return { key: 'planning', state: 'HEALTHY', direction: 'OUTBOUND', operations: [], consecutiveFailures: 0, ...over }
}

function sync(over: Partial<ConnectorSyncEntry> = {}): ConnectorSyncEntry {
  return {
    id: 'i', operation: 'brief.push', idempotencyKey: 'k', status: 'SUCCEEDED', attempts: 1, payloadBytes: 10,
    startedAt: '2026-10-05T10:00:00Z', ...over,
  }
}

const NOW = Date.parse('2026-10-05T12:00:00Z')

describe('VYB-0917 — connector health: how each state is shown', () => {
  it('VYB0917_AC6_everyStateHasALabelAGlyphAndAClassSoColourIsNeverTheOnlySignal', () => {
    for (const s of ['HEALTHY', 'DEGRADED', 'NOT_CONNECTED'] as const) {
      expect(STATE_LABEL[s]).toBeTruthy()
      expect(STATE_GLYPH[s]).toBeTruthy()
      expect(STATE_CLASS[s]).toMatch(/^cn-/)
    }
    expect(new Set(Object.values(STATE_GLYPH)).size).toBe(3)
    expect(new Set(Object.values(STATE_LABEL)).size).toBe(3)
  })

  it('VYB0917_AC6_noStateUsesTheAmberTokenWhichMeansAi', () => {
    expect(Object.values(STATE_CLASS).join(' ')).not.toMatch(/ai|amber/)
  })

  it('VYB0917_AC6_degradedConnectionsComeFirstThenHealthyThenNotConnectedByKey', () => {
    const ordered = attentionOrder([
      status({ key: 'b', state: 'NOT_CONNECTED' }), status({ key: 'z', state: 'HEALTHY' }),
      status({ key: 'a', state: 'NOT_CONNECTED' }), status({ key: 'y', state: 'DEGRADED' }), status({ key: 'c', state: 'HEALTHY' }),
    ])
    expect(ordered.map((s) => s.key)).toEqual(['y', 'c', 'z', 'a', 'b'])
  })

  it('VYB0917_AC6_sortingDoesNotChangeTheListItWasGiven', () => {
    const input = [status({ key: 'b', state: 'NOT_CONNECTED' }), status({ key: 'a', state: 'DEGRADED' })]
    attentionOrder(input)
    expect(input.map((s) => s.key)).toEqual(['b', 'a'])
  })
})

describe('VYB-0917 — connector health: the summary line', () => {
  it('VYB0917_AC6_countsEachStateAndSaysHowManyConnectionsThereAre', () => {
    const s = summarise([status({ state: 'HEALTHY' }), status({ state: 'DEGRADED' }), status({ state: 'NOT_CONNECTED' }), status({ state: 'NOT_CONNECTED' })])
    expect(s).toMatchObject({ total: 4, healthy: 1, degraded: 1, notConnected: 2 })
    expect(s.text).toBe('4 connections: 1 healthy, 1 degraded, 2 not connected.')
  })

  it('VYB0917_AC6_oneConnectionIsSingularAndNoneSaysSo', () => {
    expect(summarise([status()]).text).toBe('1 connection: 1 healthy, 0 degraded, 0 not connected.')
    expect(summarise([]).text).toBe('No connections are registered.')
  })
})

describe('VYB-0917 — connector health: absence is said in words, never blank or zero (Principle 8)', () => {
  it('VYB0917_AC6_aConnectionThatHasNeverWorkedSaysNotConnectedOrNeverSucceeded', () => {
    expect(lastSuccessText({ state: 'NOT_CONNECTED', lastSuccessAt: undefined }, NOW)).toBe('not connected')
    expect(lastSuccessText({ state: 'DEGRADED', lastSuccessAt: undefined }, NOW)).toBe('never succeeded')
    expect(lastSuccessText({ state: 'HEALTHY', lastSuccessAt: '2026-10-05T11:57:00Z' }, NOW)).toBe('3 min ago')
  })

  it('VYB0917_AC6_aConnectionThatSentNothingSaysSoAndOtherwiseNamesTheLatestOperation', () => {
    expect(lastSyncText({ lastSync: undefined }, NOW)).toBe('nothing sent yet')
    expect(lastSyncText({ lastSync: sync({ finishedAt: '2026-10-05T11:00:00Z' }) }, NOW)).toBe('brief.push: succeeded, 1 h ago')
    expect(lastSyncText({ lastSync: sync({ status: 'FAILED', attempts: 3, finishedAt: '2026-10-05T11:59:50Z' }) }, NOW))
      .toBe('brief.push: failed after 3 attempts, just now')
  })

  it('VYB0917_AC6_aNotConnectedConnectionSaysWhatIsMissingInWords', () => {
    expect(detailFor(status({ state: 'NOT_CONNECTED', notConfiguredReason: 'no baseUrl is set' })))
      .toEqual({ kind: 'text', text: 'Cannot send yet: no baseUrl is set.' })
    expect(detailFor(status({ state: 'NOT_CONNECTED' })))
      .toEqual({ kind: 'text', text: 'Configured, and nothing has been sent through it yet.' })
  })

  it('VYB0917_AC6_aConnectionWithNoCodeBoundSaysThatRatherThanShowingNothing', () => {
    expect(detailFor(status({ state: 'HEALTHY' }))).toEqual({ kind: 'text', text: 'No connector code is bound to this connection.' })
    expect(detailFor(status({ state: 'HEALTHY', connectorDescription: 'Briefs and signals.' })))
      .toEqual({ kind: 'text', text: 'Briefs and signals.' })
  })
})

describe('VYB-0917 — connector health: an inbound connection receives, it does not send', () => {
  it('VYB0917_AC6_anInboundConnectionIsNeverToldItCannotSend', () => {
    const inbound = status({ key: 'ci', state: 'NOT_CONNECTED', direction: 'INBOUND' })
    expect(detailFor(inbound)).toEqual({ kind: 'text', text: 'Not connected.' })
    expect(detailFor({ ...inbound, state: 'HEALTHY' })).toEqual({ kind: 'text', text: 'Receives deliveries; nothing is sent from here.' })
    expect(sends(inbound)).toBe(false)
    expect(sends(status({ direction: 'OUTBOUND' }))).toBe(true)
    expect(sends(status({ direction: 'BOTH' }))).toBe(true)
  })

  it('VYB0917_AC6_anInboundConnectionsLastSuccessIsADeliveryAndItsLastSentSaysItOnlyReceives', () => {
    expect(lastSyncText({ lastSync: undefined, direction: 'INBOUND' }, NOW)).toBe('receives only')
    expect(lastSuccessText({ state: 'HEALTHY', lastSuccessAt: undefined, direction: 'INBOUND' }, NOW)).toBe('nothing received yet')
    expect(lastSuccessText({ state: 'HEALTHY', lastSuccessAt: '2026-10-05T11:50:00Z', direction: 'INBOUND' }, NOW)).toBe('10 min ago')
    expect(lastSuccessText({ state: 'NOT_CONNECTED', lastSuccessAt: undefined, direction: 'INBOUND' }, NOW)).toBe('not connected')
  })
})

describe('VYB-0917 — connector health: what came from the other system is marked as theirs (Principle 8)', () => {
  it('VYB0917_AC6_aDegradedConnectionLeadsWithTheReceiversReasonMarkedAsBorrowedFromThatConnection', () => {
    const detail = detailFor(status({ key: 'planning', state: 'DEGRADED', consecutiveFailures: 3, lastError: 'HTTP 503: down' }))
    expect(detail).toEqual({ kind: 'borrowed', text: 'HTTP 503: down', source: 'planning', lead: '3 failed operations in a row' })
  })

  it('VYB0917_AC6_aSingleFailureIsNotPluralAndAMissingReasonIsStillSaid', () => {
    expect(detailFor(status({ state: 'DEGRADED', consecutiveFailures: 1, lastError: 'x' }))).toMatchObject({ lead: '1 failed operation in a row' })
    expect(detailFor(status({ state: 'DEGRADED', consecutiveFailures: 3 }))).toEqual({ kind: 'text', text: '3 failed operations in a row' })
  })
})

describe('VYB-0917 — connector health: times and durations', () => {
  it('VYB0917_AC6_agoRoundsToTheUnitAReaderWouldUse', () => {
    expect(ago('2026-10-05T11:59:30Z', NOW)).toBe('just now')
    expect(ago('2026-10-05T11:55:00Z', NOW)).toBe('5 min ago')
    expect(ago('2026-10-05T09:00:00Z', NOW)).toBe('3 h ago')
    expect(ago('2026-10-01T12:00:00Z', NOW)).toBe('4 d ago')
    expect(ago('2026-10-05T12:00:30Z', NOW)).toBe('just now') // a clock a little ahead is not "in the future"
  })

  it('VYB0917_AC6_agoOfNothingOrNonsenseIsEmptyForTheCallerToReplaceWithWords', () => {
    expect(ago(undefined, NOW)).toBe('')
    expect(ago('', NOW)).toBe('')
    expect(ago('not a date', NOW)).toBe('')
  })

  it('VYB0917_AC6_durationsReadAsMillisecondsOrSeconds', () => {
    expect(formatDuration({ status: 'SUCCEEDED', durationMs: 250 })).toBe('250 ms')
    expect(formatDuration({ status: 'SUCCEEDED', durationMs: 1234 })).toBe('1.2 s')
    expect(formatDuration({ status: 'FAILED', durationMs: 42_000 })).toBe('42 s')
    expect(formatDuration({ status: 'IN_PROGRESS', durationMs: undefined })).toBe('in progress')
    expect(formatDuration({ status: 'SUCCEEDED', durationMs: undefined })).toBe('—')
  })

  it('VYB0917_AC6_syncStatusesSaySuccessRetriedSuccessAndFailureAfterHowManyAttempts', () => {
    expect(syncStatusLabel({ status: 'SUCCEEDED', attempts: 1 })).toBe('Succeeded')
    expect(syncStatusLabel({ status: 'SUCCEEDED', attempts: 3 })).toBe('Succeeded (attempt 3)')
    expect(syncStatusLabel({ status: 'FAILED', attempts: 1 })).toBe('Failed after 1 attempt')
    expect(syncStatusLabel({ status: 'FAILED', attempts: 4 })).toBe('Failed after 4 attempts')
    expect(syncStatusLabel({ status: 'IN_PROGRESS', attempts: 0 })).toBe('In progress')
  })
})
