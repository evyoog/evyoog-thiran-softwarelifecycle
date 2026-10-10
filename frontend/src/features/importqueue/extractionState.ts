/**
 * VYB-0940 (F31): how the Import screen words where an AI extraction stands. A failed extraction keeps the steps it finished,
 * and extracting again continues from them, so the button says "Continue extraction" and the screen says why it stopped.
 * The server decides the state; this only labels it.
 */

export type ExtractionPhase = 'READY' | 'RUNNING' | 'FAILED' | 'DONE'

export function phaseOf(state: string): ExtractionPhase {
  if (state === 'UPLOADED') return 'READY'
  if (state === 'EXTRACTING') return 'RUNNING'
  if (state === 'EXTRACTION_FAILED') return 'FAILED'
  return 'DONE'
}

/** True while the extract button, not the commit button, belongs on the screen. */
export function awaitingExtraction(state: string): boolean {
  return phaseOf(state) !== 'DONE'
}

export function extractButtonLabel(state: string, pending: boolean): string {
  if (pending || phaseOf(state) === 'RUNNING') return 'Extracting…'
  return phaseOf(state) === 'FAILED' ? 'Continue extraction' : 'Extract candidates'
}

/** The state as a person reads it, for the batch list and header. A glyph goes with it where it is shown; colour is never alone. */
export function stateLabel(state: string): string {
  switch (phaseOf(state)) {
    case 'RUNNING': return 'extracting'
    case 'FAILED': return 'extraction stopped'
    default: return state
  }
}

/** The note shown above the candidates after a failed extraction; empty when there is nothing to say. */
export function failureNote(state: string, reason: string | null | undefined): string | null {
  if (phaseOf(state) !== 'FAILED') return null
  const why = reason && reason.trim() !== '' ? reason.trim() : 'No reason was recorded.'
  return `The last extraction stopped: ${why} What it had already read is kept. Continue extraction picks up from there.`
}

export function runningNote(state: string): string | null {
  return phaseOf(state) === 'RUNNING'
    ? 'This document is being extracted, here or by someone else. Reload in a minute to see the result.'
    : null
}
