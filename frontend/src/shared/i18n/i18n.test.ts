import { describe, it, expect } from 'vitest'
import { en } from './catalog.en'
import {
  LOCALES, createFormatters, createTranslator, createTranslatorFor, directionOf, catalogOf, render, resolveLocale, tEn, type Locale, type Message,
} from './i18n'
import { pseudoLocalize } from './pseudo'

const placeholders = (text: string): string[] => [...text.matchAll(/\{([A-Za-z0-9_]+)\}/g)].map((m) => m[1]!).sort()
const forms = (m: Message): string[] => (typeof m === 'string' ? [m] : Object.values(m))

describe('VYB-0958 — the locale layer', () => {
  it('VYB0958_AC1_theLanguageStartsFromTheChoiceMadeBeforeThenTheBrowsersThenEnglish', () => {
    expect(resolveLocale('en-XA', ['en'])).toBe('en-XA')                    // a stored choice wins, even the test language
    expect(resolveLocale(null, ['fr-FR', 'en-GB'])).toBe('en')               // the first browser language we have, by language
    expect(resolveLocale(null, ['EN'])).toBe('en')
    expect(resolveLocale(null, ['fr', 'de'])).toBe('en')                     // none of them: English
    expect(resolveLocale('xx', [])).toBe('en')                               // a stale stored value is ignored
    expect(resolveLocale(null, ['en-XA'])).toBe('en')                        // the test language is never picked from the browser
    expect(directionOf('en')).toBe('ltr')
    expect(directionOf('ar-EG')).toBe('rtl')
  })

  it('VYB0958_AC2_aMessageFillsItsPlaceholdersAndAMissingOneStaysVisible', () => {
    expect(tEn('home.blocking', { name: 'Release 4' })).toBe('Blocking Release 4')
    expect(tEn('home.blocking')).toBe('Blocking {name}')
    expect(tEn('mywork.cal.deployment', { build: 'b12', env: 'QA' })).toBe('b12 → QA')
    expect(tEn('mywork.pipeline.showMore', { shown: 50, left: 1234 })).toBe('Show 50 more (1,234 left)')
  })

  it('VYB0958_AC3_aCountPicksThePluralFormTheLocaleUses', () => {
    expect(tEn('mywork.deploy.inBuild', { count: 1 })).toBe('1 requirement in this build')
    expect(tEn('mywork.deploy.inBuild', { count: 0 })).toBe('0 requirements in this build')
    expect(tEn('mywork.deploy.inBuild', { count: 2 })).toBe('2 requirements in this build')
    expect(tEn('release.notReady.FROZEN', { count: 1 })).toBe('Not ready to freeze: 1 check failing')
    expect(tEn('release.notReady.FROZEN', { count: 3 })).toBe('Not ready to freeze: 3 checks failing')
    // a language with more forms: the form for the count wins, and {other} is the fallback for one it lacks
    const polish: Message = { one: '{count} plik', few: '{count} pliki', many: '{count} plików', other: '{count} pliku' }
    expect(render(polish, { count: 3 }, 'en')).toBe('3 pliku')                 // English rules know only "one" and "other"
    expect(render(polish, { count: 3 }, 'pl' as unknown as Locale)).toBe('3 pliki')       // Polish rules: 3 is "few"
    expect(render(polish, { count: 5 }, 'pl' as unknown as Locale)).toBe('5 plików')      // 5 is "many"
    expect(render(polish, { count: 1 }, 'pl' as unknown as Locale)).toBe('1 plik')
    expect(render({ other: '{count} x' }, { count: 1 }, 'en')).toBe('1 x')                // a form the message lacks falls back to "other"
  })

  it('VYB0958_AC4_aMessageATranslationLacksIsShownInEnglishNotAsItsKey', () => {
    const partial = createTranslatorFor('en', { 'nav.home': 'Inicio' })
    expect(partial('nav.home')).toBe('Inicio')
    expect(partial('nav.mywork')).toBe('My Work')
    expect(partial('mywork.deploy.inBuild', { count: 2 })).toBe('2 requirements in this build')
  })

  it('VYB0958_AC4_aKeyBuiltFromDataThatHasNoMessageShowsItsKeyInsteadOfBreakingTheScreen', () => {
    const t = createTranslator('en')
    expect(t('release.state.SOMETHING_NEW' as never)).toBe('release.state.SOMETHING_NEW')
    expect(() => t('mywork.kind.UNKNOWN' as never, { count: 2 })).not.toThrow()
  })

  it('VYB0958_AC5_theTestLanguageAltersEveryLetterButKeepsPlaceholdersAndPlurals', () => {
    expect(pseudoLocalize('Show {count} more')).toMatch(/^\[Šĥöŵ \{count\} ɱöŕé ~+\]$/)
    expect(pseudoLocalize('Show {count} more')).toBe(pseudoLocalize('Show {count} more'))
    const t = createTranslator('en-XA')
    expect(t('home.blocking', { name: 'R4' })).toContain('R4')
    expect(t('mywork.deploy.inBuild', { count: 1 })).toMatch(/^\[1 /)
    expect(t('mywork.deploy.inBuild', { count: 2 })).not.toBe(t('mywork.deploy.inBuild', { count: 1 }))
    for (const [key, message] of Object.entries(catalogOf('en-XA'))) {
      for (const form of forms(message as Message)) {
        const outsidePlaceholders = form.replace(/\{[A-Za-z0-9_]+\}/g, '')
        expect(outsidePlaceholders, key).not.toMatch(/[A-Za-z]/)               // no plain English letter survives
      }
    }
  })

  it('VYB0958_AC6_everyMessageIsNonEmptyAndEveryPluralHasOtherAndUsesCount', () => {
    for (const [key, message] of Object.entries(en) as [string, Message][]) {
      for (const form of forms(message)) expect(form.trim(), key).not.toBe('')
      if (typeof message !== 'string') {
        expect(message.other, key).toBeTruthy()
        for (const form of forms(message)) expect(placeholders(form), key).toContain('count')
      }
    }
  })

  it('VYB0958_AC7_aTranslationCatalogueHasTheSamePlaceholdersAsTheEnglishMessage', () => {
    const pseudo = catalogOf('en-XA')
    for (const [key, message] of Object.entries(en) as [string, Message][]) {
      const translated = pseudo[key as keyof typeof en] as Message
      expect(typeof translated, key).toBe(typeof message)
      if (typeof message === 'string') expect(placeholders(translated as string), key).toEqual(placeholders(message))
      else for (const form of Object.keys(message)) expect(placeholders((translated as Record<string, string>)[form]!), `${key}.${form}`).toEqual(placeholders((message as Record<string, string>)[form]!))
    }
    expect(Object.keys(pseudo).sort()).toEqual(Object.keys(en).sort())
  })

  it('VYB0958_AC8_theSwitcherOffersEnglishAndTheTestLanguageIsMarkedAsOne', () => {
    expect(LOCALES.map((l) => l.code)).toEqual(['en', 'en-XA'])
    expect(LOCALES.find((l) => l.code === 'en-XA')?.testOnly).toBe(true)
    expect(LOCALES.find((l) => l.code === 'en')?.testOnly).toBeUndefined()
  })
})

describe('VYB-0958 — dates and numbers follow the locale', () => {
  const fmt = createFormatters('en')

  it('VYB0958_AC9_datesAreFormattedByLocaleAndAnEmptyOrBadOneIsEmptyNotToday', () => {
    expect(fmt.date('2027-01-05T00:00:00Z')).toMatch(/^\d{1,2} Jan 2027$|^Jan \d{1,2}, 2027$/)
    expect(fmt.date('2027-01-05T12:00:00Z', 'long')).toContain('January')
    expect(fmt.date(undefined)).toBe('')
    expect(fmt.date('')).toBe('')
    expect(fmt.date('not a date')).toBe('')
    expect(fmt.monthYear(new Date(2027, 0, 15))).toBe('January 2027')
  })

  it('VYB0958_AC9_theWeekStartsOnMondayAndNumbersGroupDigits', () => {
    expect([0, 1, 2, 3, 4, 5, 6].map((i) => fmt.weekdayShort(i))).toEqual(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'])
    expect(fmt.number(1234567)).toBe('1,234,567')
    expect(fmt.number(0)).toBe('0')
    expect(createFormatters('en-XA').number(1234)).toBe('1,234')               // the test language formats as English
  })
})
