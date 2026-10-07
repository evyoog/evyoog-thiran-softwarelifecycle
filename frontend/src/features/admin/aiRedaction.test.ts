import { describe, expect, it } from 'vitest'
import { REDACTION_KINDS, isOn, stateText, summary, toggled } from './aiRedaction'

const kind = (key: string) => REDACTION_KINDS.find((k) => k.key === key)!

describe('VYB-0937 — AI redaction card', () => {
  it('VYB0937_AC20_secretsAreAlwaysOnAndHaveNoSwitch', () => {
    expect(kind('SECRET').locked).toBe(true)
    expect(isOn(kind('SECRET'), [])).toBe(true)
    expect(isOn(kind('SECRET'), ['SECRET'])).toBe(true)
  })

  it('VYB0937_AC20_aLockedKindIsNeverAddedToTheListToSave', () => {
    expect(toggled([], 'SECRET')).toEqual([])
    expect(toggled(['PHONE'], 'SECRET')).toEqual(['PHONE'])
    expect(toggled([], 'NOT_A_KIND')).toEqual([])
  })

  it('VYB0937_AC20_flippingAKindAddsItToTheListAndFlippingItBackRemovesIt', () => {
    expect(toggled([], 'EMAIL')).toEqual(['EMAIL'])
    expect(toggled(['EMAIL'], 'PERSON')).toEqual(['EMAIL', 'PERSON'])
    expect(toggled(['EMAIL', 'PERSON'], 'EMAIL')).toEqual(['PERSON'])
  })

  it('VYB0937_AC20_aKindIsOnUnlessItIsInTheDisabledList', () => {
    expect(isOn(kind('EMAIL'), [])).toBe(true)
    expect(isOn(kind('EMAIL'), ['EMAIL'])).toBe(false)
    expect(isOn(kind('PHONE'), ['EMAIL'])).toBe(true)
  })

  it('VYB0937_AC20_stateIsAWordNotOnlyAColour', () => {
    expect(stateText(true)).toBe('On')
    expect(stateText(false)).toBe('Off')
  })

  it('VYB0937_AC20_theSummaryNamesWhatIsOffAndSaysSecretsAreStillRemoved', () => {
    expect(summary([])).toMatch(/removed or replaced/)
    const text = summary(['EMAIL', 'PERSON'])
    expect(text).toMatch(/email addresses/)
    expect(text).toMatch(/names of people/)
    expect(text).toMatch(/Secrets are still removed/)
    expect(text).not.toMatch(/phone/)
  })

  it('VYB0937_AC20_everyKindHasAnExplanationAndExactlyOneIsLocked', () => {
    for (const k of REDACTION_KINDS) expect(k.detail.length).toBeGreaterThan(10)
    expect(REDACTION_KINDS.filter((k) => k.locked)).toHaveLength(1)
  })
})
