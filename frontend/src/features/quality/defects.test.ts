import { describe, expect, it } from 'vitest'
import type { Defect, DefectTransition, Me } from '@/shared/api/client'
import {
  FILTERS, MAX_COMMENT, STATE_CLASS, STATE_GLYPH, STATE_LABEL, assigneeText, commentProblem, defectActions, emptyText, linkText,
  reopenProblem, transitionText,
} from './defects'

const defect = (over: Partial<Defect> = {}): Defect => ({
  id: 'd', key: 'DEF-1', title: 'T', severity: 'MEDIUM', untraced: false, foundIn: 'QA', state: 'OPEN', raisedAt: '2026-10-07T00:00:00Z', ...over,
})
const me = (over: Partial<Me> = {}): Me => ({ id: 'me', email: 'e', displayName: 'n', platformAdministrator: false, activeGrants: [], ...over })
const tester = me({ activeGrants: [{ role: 'TESTER', scopeType: 'PLATFORM' }] })

describe('VYB-0931 defects: how a state is shown', () => {
  it('VYB0931_AC10_everyStateHasALabelAndAGlyphSoItIsNeverColourAlone', () => {
    for (const s of ['OPEN', 'FIXED', 'CLOSED'] as const) {
      expect(STATE_LABEL[s]).toBeTruthy()
      expect(STATE_GLYPH[s]).toBeTruthy()
      expect(STATE_CLASS[s]).not.toMatch(/(^|-)ai(-|$)|amber/)
    }
    expect(new Set(Object.values(STATE_GLYPH)).size).toBe(3)
    expect(FILTERS).toEqual(['OPEN', 'FIXED', 'CLOSED', 'ALL'])
  })
})

describe('VYB-0931 defects: who is offered what', () => {
  it('VYB0931_AC11_theAssignedDeveloperMayMarkItFixedButNothingElse', () => {
    const d = defect({ developerId: 'me', rootCause: 'CODING_ERROR' })
    expect(defectActions(d, me())).toEqual({
      fix: true, close: false, closeBlockedReason: undefined, reopen: false, edit: false, assign: false, link: false, comment: true,
    })
  })

  it('VYB0931_AC11_aDeveloperWhoIsNotAssignedMayNotFixItAndAnyoneSignedInMayComment', () => {
    const a = defectActions(defect({ developerId: 'someone-else' }), me())
    expect(a.fix).toBe(false)
    expect(a.comment).toBe(true)
    expect(defectActions(defect(), undefined).comment).toBe(false)
  })

  it('VYB0931_AC11_aTesterOrAdministratorDecides', () => {
    for (const who of [tester, me({ platformAdministrator: true })]) {
      const open = defectActions(defect({ rootCause: 'DATA' }), who)
      expect(open).toMatchObject({ fix: true, close: true, reopen: false, edit: true, assign: true, link: true, comment: true })
      const fixed = defectActions(defect({ state: 'FIXED', rootCause: 'DATA' }), who)
      expect(fixed).toMatchObject({ fix: false, close: true, reopen: true, edit: true })
      const closed = defectActions(defect({ state: 'CLOSED', rootCause: 'DATA' }), who)
      expect(closed).toMatchObject({ fix: false, close: false, reopen: true, edit: false, assign: true, link: true })
    }
  })

  it('VYB0931_AC11_closingNeedsARootCauseAndSaysSoInWords', () => {
    const a = defectActions(defect(), tester)
    expect(a.close).toBe(false)
    expect(a.closeBlockedReason).toBe('Classify the root cause first.')
    expect(defectActions(defect({ rootCause: 'DATA' }), tester).closeBlockedReason).toBeUndefined()
    // not shown to someone who could not close it anyway
    expect(defectActions(defect(), me()).closeBlockedReason).toBeUndefined()
  })
})

describe('VYB-0931 defects: words for the panel', () => {
  it('VYB0931_AC12_aDefectSaysWhoItIsRoutedToOrThatNobodyIs', () => {
    expect(assigneeText(defect())).toBe('nobody assigned')
    expect(assigneeText(defect({ developerName: 'Dan', testerName: 'Tara' }))).toBe('developer Dan, tester Tara')
    expect(assigneeText(defect({ developerName: 'Dan' }))).toBe('developer Dan')
    expect(assigneeText(defect({ testerId: 't' }))).toBe('tester assigned') // an id with no name yet is still said, not dropped
  })

  it('VYB0931_AC12_aMoveReadsAsWhoDidWhatAndWhy', () => {
    const t = (over: Partial<DefectTransition>): DefectTransition => ({ id: 'x', from: 'OPEN', to: 'FIXED', changedAt: 'now', ...over })
    expect(transitionText(t({ changedByName: 'Dan' }))).toBe('Marked fixed by Dan')
    expect(transitionText(t({ from: 'FIXED', to: 'CLOSED', changedByName: 'Tara' }))).toBe('Closed by Tara')
    expect(transitionText(t({ from: 'CLOSED', to: 'OPEN', changedByName: 'Tara', reason: 'came back' }))).toBe('Reopened by Tara: came back')
    expect(transitionText(t({}))).toBe('Marked fixed by someone')
  })

  it('VYB0931_AC12_aCommentOrAReopenReasonIsCheckedBeforeItIsSent', () => {
    expect(commentProblem('   ')).toBe('Write something first.')
    expect(commentProblem('ok')).toBeUndefined()
    expect(commentProblem('x'.repeat(MAX_COMMENT))).toBeUndefined()
    expect(commentProblem('x'.repeat(MAX_COMMENT + 1))).toMatch(/at most 4000/)
    expect(reopenProblem('  ')).toBe('Say why it is being reopened.')
    expect(reopenProblem('came back')).toBeUndefined()
  })

  it('VYB0931_AC12_aMissingLinkIsSaidNotLeftBlankAndAnEmptyListSaysWhichStateIsEmpty', () => {
    expect(linkText(undefined, 'release')).toBe('no release linked')
    expect(linkText('Release 4', 'release')).toBe('Release 4')
    expect(emptyText('OPEN', false).title).toBe('No open defects')
    expect(emptyText('FIXED', false).title).toBe('No fixed defects')
    expect(emptyText('ALL', false).title).toBe('No defects')
    expect(emptyText('CLOSED', true).title).toBe('No defect matches')
  })
})
