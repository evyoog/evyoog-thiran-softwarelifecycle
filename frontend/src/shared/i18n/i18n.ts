import { en, type MessageKey } from './catalog.en'
import { pseudoLocalize } from './pseudo'

/**
 * VYB-0958 (D45): the locale layer, written for this app rather than taken from a library: a typed catalogue of English messages
 * (the source of truth, so a key that does not exist is a compile error), a translator that fills {placeholders} and picks the
 * right plural form for the locale, formatting of dates and numbers by locale, and a fallback to English for any message a
 * translation lacks. A real language is one more catalogue file that may be partial; nothing breaks while it is.
 */
export type Locale = 'en' | 'en-XA'

/** A message is text, or the forms of a plural chosen by {count} for the locale ({one}, {other}, and others where a language has them). */
export type PluralMessage = Partial<Record<Intl.LDMLPluralRule, string>> & { other: string }
export type Message = string | PluralMessage

export type Params = Record<string, string | number>
export type Translator = (key: MessageKey, params?: Params) => string

export interface LocaleInfo {
  code: Locale
  /** The language's own name, shown in the switcher in its own language. */
  label: string
  /** Offered only while developing, or once chosen: it is a tool for finding untranslated text, not a language. */
  testOnly?: boolean
}

export const LOCALES: LocaleInfo[] = [
  { code: 'en', label: 'English' },
  { code: 'en-XA', label: 'Test language (pseudo)', testOnly: true },
]

export const DEFAULT_LOCALE: Locale = 'en'

/** The languages written right to left; none is supported yet, so this is here to be right on the day one is. */
const RTL = new Set(['ar', 'he', 'fa', 'ur'])

export function directionOf(locale: string): 'ltr' | 'rtl' {
  return RTL.has(locale.split('-')[0]!.toLowerCase()) ? 'rtl' : 'ltr'
}

/** The tag Intl is given: the test language formats as English. */
export function intlTag(locale: Locale): string {
  return locale === 'en-XA' ? 'en' : locale
}

export function isLocale(value: string | null | undefined): value is Locale {
  return !!value && LOCALES.some((l) => l.code === value)
}

/**
 * Which locale to start in: the one the person chose before, else the first of the browser's languages we have (an exact match, then
 * the same language), else English. The test language is never chosen from the browser's languages.
 */
export function resolveLocale(stored: string | null | undefined, browserLanguages: readonly string[]): Locale {
  if (isLocale(stored)) return stored
  for (const tag of browserLanguages) {
    const real = LOCALES.filter((l) => !l.testOnly)
    const exact = real.find((l) => l.code.toLowerCase() === tag.toLowerCase())
    if (exact) return exact.code
    const sameLanguage = real.find((l) => l.code.split('-')[0]!.toLowerCase() === tag.split('-')[0]!.toLowerCase())
    if (sameLanguage) return sameLanguage.code
  }
  return DEFAULT_LOCALE
}

type Catalog = Partial<Record<MessageKey, Message>>

let pseudoCatalog: Catalog | undefined

function pseudoOf(message: Message): Message {
  if (typeof message === 'string') return pseudoLocalize(message)
  return Object.fromEntries(Object.entries(message).map(([form, text]) => [form, pseudoLocalize(text)])) as PluralMessage
}

/** The messages of a locale. English is complete; any other may be partial and falls back to English message by message. */
export function catalogOf(locale: Locale): Catalog {
  if (locale === 'en') return en
  if (!pseudoCatalog) {
    pseudoCatalog = Object.fromEntries(Object.entries(en).map(([key, message]) => [key, pseudoOf(message)])) as Catalog
  }
  return pseudoCatalog
}

function fill(template: string, params: Params | undefined, locale: Locale): string {
  if (!params) return template
  return template.replace(/\{([A-Za-z0-9_]+)\}/g, (whole, name: string) => {
    const value = params[name]
    if (value === undefined) return whole          // a missing parameter stays visible rather than vanishing
    return typeof value === 'number' ? new Intl.NumberFormat(intlTag(locale)).format(value) : value
  })
}

/** The text of a message for these parameters: a plural picks its form by {count}, falling back to {other}. */
export function render(message: Message, params: Params | undefined, locale: Locale): string {
  if (typeof message === 'string') return fill(message, params, locale)
  const count = params?.count
  const form = typeof count === 'number' ? new Intl.PluralRules(intlTag(locale)).select(count) : 'other'
  return fill(message[form] ?? message.other, params, locale)
}

/** A translator over a given catalogue: a message the catalogue lacks is shown in English, never as its key. */
export function createTranslatorFor(locale: Locale, catalog: Catalog): Translator {
  return (key, params) => {
    const message = catalog[key] ?? (en as Record<string, Message>)[key]
    // Typed keys make this unreachable from code, but a key built from data (a status or a state the server sends that the app does not
    // know) can miss. A missing message must never take a screen down: it shows its key, so the gap is visible and findable.
    return message === undefined ? key : render(message, params, locale)
  }
}

export function createTranslator(locale: Locale): Translator {
  return createTranslatorFor(locale, catalogOf(locale))
}

/** English, for tests and for code that has no locale context. */
export const tEn: Translator = createTranslator('en')

// ------------------------------------------------------------------ formatting by locale

export interface Formatters {
  date: (value: Date | string | number | undefined | null, style?: 'short' | 'long') => string
  dateTime: (value: Date | string | number) => string
  monthYear: (value: Date) => string
  weekdayShort: (mondayIndex: number) => string
  number: (value: number) => string
}

export function createFormatters(locale: Locale): Formatters {
  const tag = intlTag(locale)
  const asDate = (v: Date | string | number) => (v instanceof Date ? v : new Date(v))
  return {
    date: (value, style = 'short') => {
      if (value === undefined || value === null || value === '') return ''
      const d = asDate(value)
      if (Number.isNaN(d.getTime())) return ''
      return new Intl.DateTimeFormat(tag, style === 'long' ? { day: 'numeric', month: 'long', year: 'numeric' } : { day: 'numeric', month: 'short', year: 'numeric' }).format(d)
    },
    dateTime: (value) => new Intl.DateTimeFormat(tag, { dateStyle: 'medium', timeStyle: 'short' }).format(asDate(value)),
    monthYear: (value) => new Intl.DateTimeFormat(tag, { month: 'long', year: 'numeric' }).format(value),
    // 2024-01-01 was a Monday, so index 0 is Monday: the calendar here starts its week there.
    weekdayShort: (i) => new Intl.DateTimeFormat(tag, { weekday: 'short' }).format(new Date(Date.UTC(2024, 0, 1 + i, 12))),
    number: (value) => new Intl.NumberFormat(tag).format(value),
  }
}
