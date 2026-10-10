import { describe, expect, it } from 'vitest'
import { budgetState, budgetSummary, dayLabel, parseBudget, percentOf, shareText, tallest, usedOf } from './aiUsage'

describe('VYB-0939 — AI usage and budget wording', () => {
  it('VYB0939_AC20_noLimitIsSaidInWordsAndIsNeverShownAsZeroOrFull', () => {
    expect(budgetState(500, null)).toBe('NONE')
    expect(budgetState(500, undefined)).toBe('NONE')
    expect(percentOf(500, null)).toBeNull()
    expect(usedOf(1234, null)).toBe('1,234 tokens, no limit set')
  })

  it('VYB0939_AC20_theStateIsOkNearOrOverAtTheEdges', () => {
    expect(budgetState(0, 1000)).toBe('OK')
    expect(budgetState(799, 1000)).toBe('OK')
    expect(budgetState(800, 1000)).toBe('NEAR')
    expect(budgetState(999, 1000)).toBe('NEAR')
    expect(budgetState(1000, 1000)).toBe('OVER')
    expect(budgetState(5000, 1000)).toBe('OVER')
  })

  it('VYB0939_AC20_theBarNeverPassesAHundredPercent', () => {
    expect(percentOf(250, 1000)).toBe(25)
    expect(percentOf(5000, 1000)).toBe(100)
    expect(percentOf(0, 1000)).toBe(0)
  })

  it('VYB0939_AC21_emptyMeansNoLimitAndAWholePositiveNumberIsAccepted', () => {
    expect(parseBudget('')).toEqual({ ok: true, value: null })
    expect(parseBudget('   ')).toEqual({ ok: true, value: null })
    expect(parseBudget('50000')).toEqual({ ok: true, value: 50000 })
    expect(parseBudget('1,500,000')).toEqual({ ok: true, value: 1500000 })
  })

  it('VYB0939_AC21_zeroNegativeFractionAndTextAreRefusedWithAReasonInWords', () => {
    for (const bad of ['0', '-5', '1.5', 'ten', '$20', '1e6']) {
      const r = parseBudget(bad)
      expect(r.ok, bad).toBe(false)
      if (!r.ok) expect(r.message.length).toBeGreaterThan(10)
    }
  })

  it('VYB0939_AC22_theSummaryNamesTokensAndNeverMoney', () => {
    const all = [budgetSummary(null, null), budgetSummary(1000, null), budgetSummary(null, 9000), budgetSummary(1000, 9000)]
    for (const s of all) expect(s).not.toMatch(/[$€£]|cost|price|spend|dollar|rupee/i)
    expect(all[0]).toContain('No limit')
    expect(all[1]).toContain('1,000 tokens a day')
    expect(all[2]).toContain('9,000 tokens a month')
    expect(all[3]).toContain('whichever is reached first')
  })

  it('VYB0939_AC23_aRowSharesTheMonthsTokensAndATinyOneIsSaidToBeUnderOnePercent', () => {
    const rows = [{ totalTokens: 9990 }, { totalTokens: 5 }, { totalTokens: 0 }]
    expect(shareText(rows[0]!, rows)).toBe('100%')
    expect(shareText(rows[1]!, rows)).toBe('<1%')
    expect(shareText(rows[2]!, rows)).toBe('—')
    expect(shareText({ totalTokens: 0 }, [{ totalTokens: 0 }])).toBe('—')
  })

  it('VYB0939_AC23_theChartScalesToTheTallestDayAndSurvivesAnEmptyOne', () => {
    expect(tallest([{ tokens: 10 }, { tokens: 400 }, { tokens: 0 }])).toBe(400)
    expect(tallest([{ tokens: 0 }, { tokens: 0 }])).toBe(1)
    expect(dayLabel('2026-10-03')).toBe('03/10')
  })
})
