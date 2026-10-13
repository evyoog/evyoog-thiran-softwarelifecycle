import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  LOCALES, createFormatters, createTranslator, directionOf, isLocale, resolveLocale,
  type Formatters, type Locale, type LocaleInfo, type Translator,
} from './i18n'

/**
 * VYB-0958 (D45): the person's language, kept in this browser. It starts from what they chose before, else the browser's own
 * language, else English; the page's {@code lang} and {@code dir} follow it so assistive technology reads it in the right voice.
 * Browser storage is a per-device convenience here, so every read and write is wrapped: with it blocked, the choice lasts the visit.
 */
const STORAGE_KEY = 'vyoog-locale'

interface LocaleContextValue {
  locale: Locale
  setLocale: (locale: Locale) => void
  t: Translator
  fmt: Formatters
  /** The languages the switcher offers: the test language only while developing, or while it is the one in use. */
  offered: LocaleInfo[]
}

const LocaleContext = createContext<LocaleContextValue | null>(null)

function readStored(): string | null {
  try { return localStorage.getItem(STORAGE_KEY) } catch { return null }
}

function browserLanguages(): readonly string[] {
  return typeof navigator === 'undefined' ? [] : (navigator.languages?.length ? navigator.languages : [navigator.language].filter(Boolean))
}

export function LocaleProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(() => resolveLocale(readStored(), browserLanguages()))

  useEffect(() => {
    document.documentElement.lang = locale === 'en-XA' ? 'en' : locale
    document.documentElement.dir = directionOf(locale)
  }, [locale])

  const setLocale = useCallback((next: Locale) => {
    if (!isLocale(next)) return
    setLocaleState(next)
    try { localStorage.setItem(STORAGE_KEY, next) } catch { /* the choice lasts this visit only */ }
  }, [])

  const value = useMemo<LocaleContextValue>(() => ({
    locale,
    setLocale,
    t: createTranslator(locale),
    fmt: createFormatters(locale),
    offered: LOCALES.filter((l) => !l.testOnly || import.meta.env.DEV || import.meta.env.VITE_SHOW_TEST_LOCALE === 'true' || l.code === locale),
  }), [locale, setLocale])

  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>
}

export function useLocale(): LocaleContextValue {
  const ctx = useContext(LocaleContext)
  if (!ctx) throw new Error('useLocale needs a LocaleProvider above it')
  return ctx
}

/** The translator, for a component that only needs words. */
export function useT(): Translator {
  return useLocale().t
}
