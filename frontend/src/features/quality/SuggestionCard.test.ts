import { describe, it, expect } from 'vitest'
import type { TestCaseSuggestion } from '@/shared/api/client'
import { editsOf, groupByCategory, parseBullets } from './SuggestionCard'

function suggestion(over: Partial<TestCaseSuggestion> = {}): TestCaseSuggestion {
  return {
    category: 'INDIVIDUAL', title: 'T', description: 'D', rationale: 'R', proposalId: 'p1',
    ...over,
  }
}

describe('VYB-0824 — AI test-case suggestions group by category', () => {
  it('VYB0824_AC1_individualAndDependencyAreSeparated', () => {
    const groups = groupByCategory([
      suggestion({ category: 'INDIVIDUAL', title: 'a' }),
      suggestion({ category: 'DEPENDENCY', title: 'b' }),
      suggestion({ category: 'INDIVIDUAL', title: 'c' }),
    ])
    expect(groups.individual.map((s) => s.title)).toEqual(['a', 'c'])
    expect(groups.dependency.map((s) => s.title)).toEqual(['b'])
  })

  it('VYB0824_AC1_noRelatedRequirementsMeansNoDependencyGroup', () => {
    const groups = groupByCategory([suggestion({ category: 'INDIVIDUAL' })])
    expect(groups.dependency).toEqual([])
  })

  it('VYB0824_AC1_emptyInputIsBothGroupsEmpty', () => {
    const groups = groupByCategory([])
    expect(groups.individual).toEqual([])
    expect(groups.dependency).toEqual([])
  })
})

describe('VYB-0828 — bulleted test-case descriptions', () => {
  it('VYB0828_AC1_parsesDashBulletedLines', () => {
    expect(parseBullets('- Set up X\n- Do Y\n- Expect Z')).toEqual(['Set up X', 'Do Y', 'Expect Z'])
  })

  it('VYB0828_AC1_parsesBulletCharacterLines', () => {
    expect(parseBullets('• Set up X\n• Expect Z')).toEqual(['Set up X', 'Expect Z'])
  })

  it('VYB0828_AC1_plainProseHasNoBulletsReturnsNull', () => {
    expect(parseBullets('This is a plain sentence describing the test.')).toBeNull()
  })

  it('VYB0828_AC1_blankLinesAreIgnored', () => {
    expect(parseBullets('- First\n\n- Second\n')).toEqual(['First', 'Second'])
  })

  it('VYB0828_AC1_emptyStringReturnsNull', () => {
    expect(parseBullets('')).toBeNull()
  })

  it('VYB0828_AC1_mixedBulletAndProseLinesOnlyKeepsTheBulletedOnes', () => {
    expect(parseBullets('Some context first.\n- Then a real step\n- And another')).toEqual(['Then a real step', 'And another'])
  })
})

describe('VYB-0938 — accepting a suggestion is a decision on its proposal', () => {
  it('VYB0938_AC24_anUntouchedSuggestionIsAcceptedAsProposedWithNoEdits', () => {
    expect(editsOf(suggestion(), 'T', 'D')).toBeUndefined()
    expect(editsOf(suggestion(), '  T ', ' D  ')).toBeUndefined()
  })

  it('VYB0938_AC24_onlyTheFieldsThePersonChangedAreSentAndABlankedDescriptionIsNotSentAsAnEdit', () => {
    expect(editsOf(suggestion(), 'New title', 'D')).toEqual({ title: 'New title' })
    expect(editsOf(suggestion(), 'T', '- step one\n- step two')).toEqual({ description: '- step one\n- step two' })
    expect(editsOf(suggestion(), 'New', 'Changed')).toEqual({ title: 'New', description: 'Changed' })
    expect(editsOf(suggestion(), 'T', '   ')).toBeUndefined()
  })
})
