import { describe, expect, it } from 'vitest'
import { awaitingExtraction, extractButtonLabel, failureNote, phaseOf, runningNote, stateLabel } from './extractionState'

describe('VYB-0940 — extraction state wording', () => {
  it('VYB0940_AC41_eachStateOfABatchHasAPhase', () => {
    expect(phaseOf('UPLOADED')).toBe('READY')
    expect(phaseOf('EXTRACTING')).toBe('RUNNING')
    expect(phaseOf('EXTRACTION_FAILED')).toBe('FAILED')
    expect(phaseOf('EXTRACTED')).toBe('DONE')
    expect(phaseOf('COMMITTED')).toBe('DONE')
  })

  it('VYB0940_AC41_theExtractButtonStaysUntilTheBatchIsExtracted', () => {
    expect(awaitingExtraction('UPLOADED')).toBe(true)
    expect(awaitingExtraction('EXTRACTING')).toBe(true)
    expect(awaitingExtraction('EXTRACTION_FAILED')).toBe(true)
    expect(awaitingExtraction('EXTRACTED')).toBe(false)
  })

  it('VYB0940_AC42_aFailedExtractionOffersToContinueNotToStartAgain', () => {
    expect(extractButtonLabel('UPLOADED', false)).toBe('Extract candidates')
    expect(extractButtonLabel('EXTRACTION_FAILED', false)).toBe('Continue extraction')
    expect(extractButtonLabel('EXTRACTION_FAILED', true)).toBe('Extracting…')
    expect(extractButtonLabel('EXTRACTING', false)).toBe('Extracting…')
  })

  it('VYB0940_AC43_theStopReasonIsShownInWordsAndAMissingOneSaysSo', () => {
    expect(failureNote('EXTRACTION_FAILED', 'The AI token budget for today is used up'))
      .toContain('The last extraction stopped: The AI token budget for today is used up')
    expect(failureNote('EXTRACTION_FAILED', '  ')).toContain('No reason was recorded.')
    expect(failureNote('EXTRACTION_FAILED', null)).toContain('Continue extraction picks up')
    expect(failureNote('UPLOADED', 'old reason')).toBeNull()
    expect(failureNote('EXTRACTED', 'old reason')).toBeNull()
  })

  it('VYB0940_AC44_aRunningExtractionSaysSoAndOtherStatesSayNothing', () => {
    expect(runningNote('EXTRACTING')).toContain('being extracted')
    expect(runningNote('UPLOADED')).toBeNull()
    expect(stateLabel('EXTRACTING')).toBe('extracting')
    expect(stateLabel('EXTRACTION_FAILED')).toBe('extraction stopped')
    expect(stateLabel('EXTRACTED')).toBe('EXTRACTED')
  })
})
